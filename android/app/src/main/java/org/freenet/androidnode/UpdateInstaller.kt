package org.freenet.androidnode

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
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

    fun download(context: Context, version: SemVer, onProgress: (Progress) -> Unit): File {
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = File(directory, "freenet-android-node-$version.apk")
        val url = URL("$RELEASES/v$version/freenet-android-node-release.apk")
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
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(Progress(downloaded, total))
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val expected = expectedSha256(version)
        val actual = sha256(target)
        require(actual.equals(expected, ignoreCase = true)) {
            "Downloaded APK failed the SHA256 check"
        }
        return target
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
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            val intent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pending = PendingIntent.getActivity(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            session.commit(pending.intentSender)
        }
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
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            val line = text.lineSequence().firstOrNull {
                it.trim().endsWith("freenet-android-node-release.apk")
            } ?: error("SHA256SUMS does not list the APK")
            return line.trim().split(Regex("\\s+")).first()
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
