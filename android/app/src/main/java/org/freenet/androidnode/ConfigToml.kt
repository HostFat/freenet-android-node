package org.freenet.androidnode

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

internal object ConfigToml {
    const val MIN_KEY = "min-number-of-connections"
    const val MAX_KEY = "max-number-of-connections"
    const val FILE_PROVIDER_SUFFIX = ".fileprovider"

    fun file(context: Context): File =
        File(context.applicationContext.filesDir, "freenet/config/config.toml")

    fun read(context: Context): String {
        val target = file(context)
        return if (target.exists()) target.readText() else ""
    }

    fun write(context: Context, text: String) {
        val target = file(context)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "config.${android.os.Process.myPid()}.toml.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    fun fingerprint(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun fingerprint(context: Context): String = fingerprint(read(context))

    fun ensureExists(context: Context) {
        val target = file(context)
        if (target.exists()) return
        val policies = runCatching {
            NodePolicyRepository.initialize(context)
            NodePolicyRepository.state.value
        }.getOrNull()
        val min = policies?.minConnections ?: ConnectionLimits.DefaultMin
        val max = policies?.maxConnections ?: ConnectionLimits.DefaultMax
        write(
            context,
            """
            |# Unofficial Freenet Android node configuration.
            |# Freenet rewrites this file when the node starts and may drop comments.
            |
            |$MIN_KEY = $min
            |$MAX_KEY = $max
            |
            """.trimMargin(),
        )
    }

    fun upsertInt(text: String, key: String, value: Int): String {
        val line = "$key = $value"
        val regex = Regex("(?m)^[ \\t]*${Regex.escape(key)}[ \\t]*=[ \\t]*.*$")
        return if (regex.containsMatchIn(text)) {
            regex.replaceFirst(text, line)
        } else if (text.isBlank()) {
            "$line\n"
        } else {
            text.trimEnd() + "\n$line\n"
        }
    }

    fun parseInt(text: String, key: String): Int? {
        val regex = Regex("(?m)^[ \\t]*${Regex.escape(key)}[ \\t]*=[ \\t]*(\\d+)")
        return regex.find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    fun syncLimitsToFile(context: Context, min: Int, max: Int) {
        val current = read(context)
        val updated = upsertInt(upsertInt(current, MIN_KEY, min), MAX_KEY, max)
        write(context, updated)
    }

    fun syncLimitsFromFile(context: Context) {
        val text = read(context)
        val min = parseInt(text, MIN_KEY) ?: return
        val max = parseInt(text, MAX_KEY) ?: return
        NodePolicyRepository.setConnectionLimits(context, min, max)
    }

    fun openInExternalEditor(context: Context): Boolean {
        ensureExists(context)
        val target = file(context)
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + FILE_PROVIDER_SUFFIX,
            target,
        )
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val edit = Intent(Intent.ACTION_EDIT)
            .setDataAndType(uri, "text/plain")
            .addFlags(flags)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "text/plain")
            .addFlags(flags)
        val chosen = when {
            canHandle(context, edit) -> edit
            canHandle(context, view) -> view
            else -> return false
        }
        grantToHandlers(context, chosen, uri, flags)
        context.startActivity(
            Intent.createChooser(chosen, context.getString(R.string.open_config_external))
                .addFlags(flags),
        )
        return true
    }

    private fun queryHandlers(context: Context, intent: Intent): List<android.content.pm.ResolveInfo> {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    }

    private fun canHandle(context: Context, intent: Intent): Boolean =
        queryHandlers(context, intent).isNotEmpty()

    private fun grantToHandlers(context: Context, intent: Intent, uri: Uri, grantFlags: Int) {
        for (info in queryHandlers(context, intent)) {
            context.grantUriPermission(info.activityInfo.packageName, uri, grantFlags)
        }
    }
}
