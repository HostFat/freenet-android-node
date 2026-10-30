package org.freenet.androidnode

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

internal fun shouldPromptRestartForConfigFile(
    fingerprintChanged: Boolean,
    userInitiatedEdit: Boolean,
    networkLive: Boolean,
): Boolean = fingerprintChanged && userInitiatedEdit && networkLive

internal object ConfigToml {
    const val MIN_KEY = "min-number-of-connections"
    const val MAX_KEY = "max-number-of-connections"
    const val NETWORK_PORT_KEY = "network-port"
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

    fun isComplete(text: String): Boolean =
        Regex("(?m)^[ \\t]*mode[ \\t]*=").containsMatchIn(text)

    /** Null when the text is a complete, syntactically valid Freenet config. */
    fun validationError(text: String): String? {
        if (!isComplete(text)) return "missing mode"
        if (!NativeBridge.isLoaded) return fallbackValidationError(text)
        val raw = NativeBridge.validateConfig(text).getOrElse { error ->
            return error.message ?: "invalid TOML"
        }
        val envelope = runCatching { org.json.JSONObject(raw) }.getOrNull()
            ?: return "invalid TOML"
        if (envelope.optBoolean("ok")) return null
        return envelope.optJSONObject("error")?.optString("message")
            ?.ifBlank { null }
            ?: "invalid TOML"
    }

    /**
     * Local unit tests and the first frame before JNI is ready use this check.
     * It only catches unbalanced containers, so the compiler never ships a
     * second TOML parser.
     */
    private fun fallbackValidationError(text: String): String? {
        var square = 0
        var curly = 0
        var quoted = false
        for (character in text) {
            when {
                character == '"' -> quoted = !quoted
                quoted -> Unit
                character == '[' -> square += 1
                character == ']' -> square -= 1
                character == '{' -> curly += 1
                character == '}' -> curly -= 1
            }
            if (square < 0 || curly < 0) return "invalid TOML"
        }
        return if (square == 0 && curly == 0 && !quoted) null else "invalid TOML"
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

    fun removeKey(text: String, key: String): String {
        val regex = Regex("(?m)^[ \\t]*${Regex.escape(key)}[ \\t]*=[ \\t]*.*\\n?")
        return regex.replace(text, "")
    }

    fun syncUdpPortToFile(context: Context, mode: UdpPortMode, port: Int) {
        val current = read(context)
        if (!isComplete(current)) return
        val updated = when (mode) {
            UdpPortMode.Custom -> upsertInt(current, NETWORK_PORT_KEY, UdpPorts.coerce(port))
            UdpPortMode.Random -> removeKey(current, NETWORK_PORT_KEY)
            UdpPortMode.Saved -> current
        }
        if (updated != current) {
            write(context, updated)
        }
    }

    fun syncLimitsToFile(context: Context, min: Int, max: Int) {
        val current = read(context)
        if (!isComplete(current)) return
        val updated = upsertInt(upsertInt(current, MIN_KEY, min), MAX_KEY, max)
        write(context, updated)
    }

    fun editorIntent(context: Context): Intent? {
        val target = file(context)
        if (!isComplete(read(context))) return null
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
            else -> return null
        }
        grantToHandlers(context, chosen, uri, flags)
        return Intent.createChooser(chosen, context.getString(R.string.open_config_external))
            .addFlags(flags)
    }

    fun openInExternalEditor(context: Context): Boolean {
        val intent = editorIntent(context) ?: return false
        context.startActivity(intent)
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
