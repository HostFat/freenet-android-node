package org.freenet.androidnode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class UpdateUiState(
    val checking: Boolean = false,
    val kind: UpdateKind = UpdateKind.None,
    val message: String? = null,
    val releaseUrl: String? = null,
    val apkVersion: SemVer? = null,
    val lastError: String? = null,
)

internal object UpdateCheckRepository {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(UpdateUiState())
    private val mutableIncludePrereleases = MutableStateFlow(false)
    val state: StateFlow<UpdateUiState> = mutableState.asStateFlow()
    val includePrereleases: StateFlow<Boolean> = mutableIncludePrereleases.asStateFlow()

    fun initialize(context: Context) {
        val prefs = prefs(context.applicationContext)
        mutableIncludePrereleases.value = prefs.getBoolean(KEY_INCLUDE_PRERELEASES, false)
    }

    fun setIncludePrereleases(context: Context, enabled: Boolean) {
        prefs(context.applicationContext)
            .edit()
            .putBoolean(KEY_INCLUDE_PRERELEASES, enabled)
            .apply()
        mutableIncludePrereleases.value = enabled
    }

    suspend fun checkAutomatic(context: Context, peerVersion: String? = null) {
        check(context.applicationContext, peerVersion, force = false)
    }

    suspend fun checkManual(context: Context, peerVersion: String? = null) {
        check(context.applicationContext, peerVersion, force = true)
    }

    fun dismiss() {
        mutableState.value = UpdateUiState()
    }

    private suspend fun check(context: Context, peerVersion: String?, force: Boolean) {
        mutex.withLock {
            val prefs = prefs(context)
            val intervalMs = AUTO_CHECK_MS + autoCheckJitterMs(context)
            val now = System.currentTimeMillis()
            if (!force) {
                val last = prefs.getLong(KEY_LAST_CHECK_MS, 0L)
                if (now - last < intervalMs) {
                    return
                }
            }
            mutableState.value = mutableState.value.copy(checking = true, lastError = null)
            val result = withContext(Dispatchers.IO) {
                runCatching { fetchDecision(context, peerVersion) }
            }
            result.fold(
                onSuccess = { decision ->
                    prefs.edit().putLong(KEY_LAST_CHECK_MS, now).apply()
                    mutableState.value = UpdateUiState(
                        checking = false,
                        kind = decision.kind,
                        message = decision.message,
                        releaseUrl = decision.releaseUrl,
                        apkVersion = decision.apkVersion,
                    )
                    if (decision.kind == UpdateKind.ApkAvailable && decision.apkVersion != null) {
                        maybeNotifyApk(context, decision)
                    }
                },
                onFailure = { error ->
                    // Keep the failure visible even for an automatic check:
                    // "no update" and "could not check" are different states.
                    val reason = error.message ?: "Could not check for updates"
                    mutableState.value = UpdateUiState(
                        checking = false,
                        lastError = if (force) reason else "Could not check for updates: $reason",
                    )
                },
            )
        }
    }

    private fun fetchDecision(context: Context, peerVersion: String?): UpdateDecision {
        val installedCore = SemVer.parseFromCoreBuildInfo(
            NativeBridge.freenetBuildInfo().getOrNull(),
        )
        val installedApp = SemVer.parse(
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull(),
        )
        val includePrereleases = mutableIncludePrereleases.value
        // Static feed first, then the cached worker, then GitHub.
        val static = runCatching { fetchStaticReleases(includePrereleases) }.getOrNull()
        val worker = if (static == null) {
            runCatching { fetchWorkerReleases(includePrereleases) }.getOrNull()
        } else {
            null
        }
        val releases = static ?: worker
        val coreRelease = releases?.core ?: if (includePrereleases) {
            fetchNewestRelease(CORE_RELEASES_API)
        } else {
            fetchLatestRelease(UpdateUrls.CORE_RELEASES_PAGE)
        }
        val apkRelease = releases?.app ?: if (includePrereleases) {
            fetchNewestRelease(APK_RELEASES_API)
        } else {
            fetchLatestRelease(UpdateUrls.APK_RELEASES_PAGE)
        }
        return decideUpdate(
            installedCore = installedCore,
            installedApp = installedApp,
            githubCore = coreRelease.version,
            githubApk = apkRelease.version,
            githubApkReleaseUrl = apkRelease.htmlUrl ?: UpdateUrls.APK_RELEASES_PAGE,
            peerSeen = SemVer.parse(peerVersion),
        )
    }

    private data class WorkerReleases(val app: GithubRelease, val core: GithubRelease)

    private fun fetchStaticReleases(includePrereleases: Boolean): WorkerReleases? {
        val connection = (URL(UpdateUrls.STATIC_RELEASES).openConnection()
            as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = org.json.JSONObject(body)
            val channel = if (includePrereleases) "beta" else "stable"
            val app = root.optJSONObject("app")?.optJSONObject(channel) ?: return null
            val core = root.optJSONObject("core")?.optJSONObject(channel) ?: return null
            fun release(value: org.json.JSONObject): GithubRelease? {
                val version = SemVer.parse(value.optString("version")) ?: return null
                return GithubRelease(version, value.optString("url"))
            }
            return WorkerReleases(
                app = release(app) ?: return null,
                core = release(core) ?: return null,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchWorkerReleases(includePrereleases: Boolean): WorkerReleases? {
        val channel = if (includePrereleases) "beta" else "stable"
        val connection = (URL("${UpdateUrls.WORKER_RELEASES}?channel=$channel").openConnection()
            as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = org.json.JSONObject(body)
            val app = root.optJSONObject("app") ?: return null
            val core = root.optJSONObject("core") ?: return null
            fun release(value: org.json.JSONObject): GithubRelease? {
                val version = SemVer.parse(value.optString("version")) ?: return null
                return GithubRelease(version, value.optString("url"))
            }
            return WorkerReleases(
                app = release(app) ?: return null,
                core = release(core) ?: return null,
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchLatestRelease(latestUrl: String): GithubRelease {
        val connection = (URL(latestUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html")
        }
        try {
            val code = connection.responseCode
            val location = connection.getHeaderField("Location")
            val resolved = when {
                code in 300..399 && !location.isNullOrBlank() -> {
                    if (location.startsWith("http")) location else URL(URL(latestUrl), location).toString()
                }
                code in 200..299 -> connection.url.toString()
                code == 403 || code == 429 ->
                    throw IOException("GitHub rate limit (HTTP $code); try again later")
                else -> throw IOException("GitHub returned HTTP $code")
            }
            val version = versionFromReleaseLocation(resolved)
                ?: throw IOException("Could not parse a release tag from GitHub")
            val htmlUrl = if (resolved.contains("/releases/tag/")) resolved else latestUrl
            return GithubRelease(version = version, htmlUrl = htmlUrl)
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchNewestRelease(apiUrl: String): GithubRelease {
        val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = connection.responseCode
            if (code == 403 || code == 429) {
                throw IOException("GitHub rate limit (HTTP $code); try again later")
            }
            if (code !in 200..299) {
                throw IOException("GitHub returned HTTP $code")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val releases = org.json.JSONArray(body)
            var best: GithubRelease? = null
            for (index in 0 until releases.length()) {
                val release = releases.getJSONObject(index)
                if (release.optBoolean("draft")) continue
                if (release.optBoolean("prerelease") && !mutableIncludePrereleases.value) continue
                val version = SemVer.parse(
                    release.optString("tag_name").removePrefix("v"),
                ) ?: continue
                val current = best?.version
                if (current == null || version > current) {
                    best = GithubRelease(version, release.optString("html_url"))
                }
            }
            return best ?: throw IOException("No release found")
        } finally {
            connection.disconnect()
        }
    }

    private fun maybeNotifyApk(context: Context, decision: UpdateDecision) {
        val version = decision.apkVersion?.toString() ?: return
        NodePolicyRepository.initialize(context)
        if (!NodePolicyRepository.state.value.notifyUpdate) {
            return
        }
        val prefs = prefs(context)
        if (prefs.getString(KEY_NOTIFIED_APK, null) == version) {
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(context, manager)
        val openRelease = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_RELEASE,
            Intent(Intent.ACTION_VIEW, Uri.parse(decision.releaseUrl ?: UpdateUrls.APK_RELEASES_PAGE)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_node_notification)
            .setContentTitle(context.getString(R.string.update_notification_title))
            .setContentText(decision.message)
            .setStyle(Notification.BigTextStyle().bigText(decision.message))
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openRelease)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        prefs.edit().putString(KEY_NOTIFIED_APK, version).apply()
    }

    private fun ensureChannel(context: Context, manager: NotificationManager) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.update_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.update_notification_channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** Spread checks over six hours so devices do not all poll together. */
    private fun autoCheckJitterMs(context: Context): Long {
        val seed = InstallId.get(context).ifBlank { System.currentTimeMillis().toString() }
        return (seed.hashCode().toLong() and 0x7fffffff) % JITTER_MS
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private data class GithubRelease(
        val version: SemVer?,
        val htmlUrl: String?,
    )

    private const val PREFS = "update_check"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    private const val KEY_NOTIFIED_APK = "notified_apk_version"
    private const val KEY_INCLUDE_PRERELEASES = "include_prereleases"
    private const val AUTO_CHECK_MS = 24L * 60L * 60L * 1000L
    private const val JITTER_MS = 6L * 60L * 60L * 1000L
    private const val CORE_RELEASES_API =
        "https://api.github.com/repos/freenet/freenet-core/releases?per_page=30"
    private const val APK_RELEASES_API =
        "https://api.github.com/repos/HostFat/freenet-android-node/releases?per_page=30"
    private const val USER_AGENT = "freenet-android-node"
    private const val CHANNEL_ID = "freenet_apk_updates"
    private const val NOTIFICATION_ID = 7510
    private const val REQUEST_OPEN_RELEASE = 21
}
