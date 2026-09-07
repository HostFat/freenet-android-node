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
import org.json.JSONObject

internal data class UpdateUiState(
    val checking: Boolean = false,
    val kind: UpdateKind = UpdateKind.None,
    val message: String? = null,
    val releaseUrl: String? = null,
    val lastError: String? = null,
)

internal object UpdateCheckRepository {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(UpdateUiState())
    private val mutableInterval = MutableStateFlow(UpdateCheckInterval.Default)
    val state: StateFlow<UpdateUiState> = mutableState.asStateFlow()
    val interval: StateFlow<UpdateCheckInterval> = mutableInterval.asStateFlow()

    fun initialize(context: Context) {
        mutableInterval.value = loadInterval(context.applicationContext)
    }

    fun setInterval(context: Context, interval: UpdateCheckInterval) {
        val appContext = context.applicationContext
        prefs(appContext).edit().putInt(KEY_INTERVAL_HOURS, interval.hours).apply()
        mutableInterval.value = interval
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
            val intervalMs = loadInterval(context).intervalMs
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
                    )
                    if (decision.kind == UpdateKind.ApkAvailable && decision.apkVersion != null) {
                        maybeNotifyApk(context, decision)
                    }
                },
                onFailure = { error ->
                    mutableState.value = UpdateUiState(
                        checking = false,
                        lastError = if (force) {
                            error.message ?: "Could not check for updates"
                        } else {
                            null
                        },
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
        val coreRelease = fetchLatestRelease(UpdateUrls.CORE_RELEASES_API)
        val apkRelease = fetchLatestRelease(UpdateUrls.APK_RELEASES_API)
        return decideUpdate(
            installedCore = installedCore,
            installedApp = installedApp,
            githubCore = coreRelease.version,
            githubApk = apkRelease.version,
            githubApkReleaseUrl = apkRelease.htmlUrl ?: UpdateUrls.APK_RELEASES_PAGE,
            peerSeen = SemVer.parse(peerVersion),
        )
    }

    private fun fetchLatestRelease(apiUrl: String): GithubRelease {
        val connection = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("GitHub returned HTTP $code")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            return GithubRelease(
                version = SemVer.parse(json.optString("tag_name")),
                htmlUrl = json.optString("html_url").ifBlank { null },
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun maybeNotifyApk(context: Context, decision: UpdateDecision) {
        val version = decision.apkVersion?.toString() ?: return
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

    private fun loadInterval(context: Context): UpdateCheckInterval =
        UpdateCheckInterval.fromHours(
            prefs(context).getInt(KEY_INTERVAL_HOURS, UpdateCheckInterval.Default.hours),
        )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private data class GithubRelease(
        val version: SemVer?,
        val htmlUrl: String?,
    )

    private const val PREFS = "update_check"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    private const val KEY_NOTIFIED_APK = "notified_apk_version"
    private const val KEY_INTERVAL_HOURS = "auto_interval_hours"
    private const val USER_AGENT = "freenet-android-node"
    private const val CHANNEL_ID = "freenet_apk_updates"
    private const val NOTIFICATION_ID = 7510
    private const val REQUEST_OPEN_RELEASE = 21
}
