package org.freenet.androidnode

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads the APK published on the official GitHub release, checks the
 * published SHA256 and the signing certificate, then hands it to the system
 * installer. The user still confirms the installation.
 */
internal object UpdateInstaller {
    data class Progress(val downloaded: Long, val total: Long)

    private const val RELEASES = "https://github.com/HostFat/freenet-android-node/releases/download"
    private const val APK_FILENAME = "freenet-android-node-release.apk"
    private const val MAX_CHECKSUM_CHARS = 32 * 1024
    private const val PROGRESS_INTERVAL_MS = 100L

    fun download(context: Context, version: SemVer, onProgress: (Progress) -> Unit): File {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        // Remove stale APKs and partial downloads before fetching the new release.
        directory.listFiles()?.forEach { it.delete() }
        val temp = File(directory, "freenet-android-node-$version.apk.tmp")
        val target = File(directory, "freenet-android-node-$version.apk")
        val url = URL("$RELEASES/v$version/$APK_FILENAME")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "freenet-android-node")
        }
        try {
            require(connection.responseCode in 200..299) {
                "Download failed: HTTP ${connection.responseCode}"
            }
            val total = connection.contentLengthLong
            var lastEmit = 0L
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastEmit >= PROGRESS_INTERVAL_MS || (total > 0 && downloaded >= total)) {
                            lastEmit = now
                            onProgress(Progress(downloaded, total))
                        }
                    }
                }
            }
            val expected = expectedSha256(version)
            val actual = sha256(temp)
            require(actual.equals(expected, ignoreCase = true)) {
                "Downloaded APK failed the SHA256 check"
            }
            check(temp.renameTo(target)) { "Could not finalize downloaded APK" }
            return target
        } catch (error: Throwable) {
            temp.delete()
            target.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun verifySignature(context: Context, apk: File): Boolean {
        val archive = context.packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.GET_SIGNING_CERTIFICATES,
        ) ?: return false
        val installed = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val archiveSigners = archive.signingInfo?.apkContentsSigners
            ?.map { sha256(it.toByteArray()) }
            ?.toSet()
            .orEmpty()
        val installedSigners = installed.signingInfo?.apkContentsSigners
            ?.map { sha256(it.toByteArray()) }
            ?.toSet()
            .orEmpty()
        return archiveSigners.isNotEmpty() && archiveSigners == installedSigners
    }

    fun install(context: Context, apk: File) {
        // The installer kills the app but not necessarily the child node
        // process. Stop it first so the new version starts with clean sockets.
        runCatching { NodeBackend.stop() }
        runCatching { context.stopService(Intent(context, NodeService::class.java)) }
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        var committed = false
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                val intent = Intent(context, UpdateStatusReceiver::class.java).apply {
                    setPackage(context.packageName)
                }
                // PackageInstaller writes the status into the PendingIntent, so it
                // must be mutable from Android 12 on.
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_MUTABLE
                    } else {
                        0
                    }
                val pending = PendingIntent.getBroadcast(context, 1001, intent, flags)
                session.commit(pending.intentSender)
                committed = true
            }
        } catch (error: Throwable) {
            if (!committed) {
                runCatching { installer.abandonSession(sessionId) }
            }
            throw error
        }
    }

    internal fun parseExpectedSha256(text: String): String {
        val shaPattern = Regex("^[0-9a-fA-F]{64}$")
        for (rawLine in text.lineSequence()) {
            val parts = rawLine.trim().split(Regex("\\s+"))
            if (parts.size < 2) continue
            val hash = parts[0]
            val name = parts[1].removePrefix("*")
            val matchesApk = name == APK_FILENAME || name.endsWith("/$APK_FILENAME")
            if (matchesApk && shaPattern.matches(hash)) {
                return hash
            }
        }
        error("SHA256SUMS does not list $APK_FILENAME")
    }

    private fun expectedSha256(version: SemVer): String {
        val url = URL("$RELEASES/v$version/SHA256SUMS.txt")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "freenet-android-node")
        }
        try {
            require(connection.responseCode in 200..299) {
                "Could not fetch SHA256SUMS: HTTP ${connection.responseCode}"
            }
            val text = connection.inputStream.bufferedReader().use {
                readBoundedText(it, MAX_CHECKSUM_CHARS)
            }
            return parseExpectedSha256(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
