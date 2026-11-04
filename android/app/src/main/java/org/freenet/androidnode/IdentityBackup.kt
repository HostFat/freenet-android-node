package org.freenet.androidnode

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal object IdentityBackup {
    private val FILE_NAMES = listOf("transport_keypair", "delegate_cipher")

    fun directory(context: Context): java.io.File {
        if (BuildConfig.PREBUILT_NODE) {
            val root = java.io.File(context.applicationContext.filesDir, "freenet/prebuilt/data")
            val network = java.io.File(root, "secrets")
            return if (network.isDirectory) network else java.io.File(root, "secrets_local")
        }
        return java.io.File(context.applicationContext.noBackupFilesDir, "freenet/identity")
    }

    fun export(context: Context, uri: Uri): String {
        val dir = directory(context)
        if (!java.io.File(dir, "transport_keypair").isFile) {
            return "Identity files are missing: transport_keypair"
        }
        val names = if (BuildConfig.PREBUILT_NODE) {
            dir.listFiles()
                ?.filter { it.isFile }
                ?.map { it.name }
                ?.sorted()
                .orEmpty()
        } else {
            val missing = FILE_NAMES.filter { !java.io.File(dir, it).isFile }
            if (missing.isNotEmpty()) {
                return "Identity files are missing: ${missing.joinToString()}"
            }
            FILE_NAMES
        }
        if (names.isEmpty()) {
            return "Identity files are missing"
        }
        context.contentResolver.openOutputStream(uri)?.use { output ->
            ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                for (name in names) {
                    val file = java.io.File(dir, name)
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } ?: return "Could not write the backup file"
        return "Identity backup saved. Keep this file private."
    }

    fun importFrom(context: Context, uri: Uri): String {
        val dir = directory(context)
        dir.mkdirs()
        val restored = mutableSetOf<String>()
        context.contentResolver.openInputStream(uri)?.use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = java.io.File(entry.name).name
                    if (name.isNotBlank() && !entry.isDirectory) {
                        val target = java.io.File(dir, name)
                        target.outputStream().use { zip.copyTo(it) }
                        restored.add(name)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: return "Could not read the backup file"
        val expected = if (BuildConfig.PREBUILT_NODE) listOf("transport_keypair") else FILE_NAMES
        if (!restored.containsAll(expected)) {
            return "Backup did not contain both identity files. Restored: ${restored.joinToString().ifBlank { "none" }}"
        }
        return "Identity restored. Restart the node to use it. Without this backup, a new node is a different peer."
    }
}
