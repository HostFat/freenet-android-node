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

    fun directory(context: Context) =
        java.io.File(context.applicationContext.noBackupFilesDir, "freenet/identity")

    fun export(context: Context, uri: Uri): String {
        val dir = directory(context)
        val missing = FILE_NAMES.filter { !java.io.File(dir, it).isFile }
        if (missing.isNotEmpty()) {
            return "Identity files are missing: ${missing.joinToString()}"
        }
        context.contentResolver.openOutputStream(uri)?.use { output ->
            ZipOutputStream(BufferedOutputStream(output)).use { zip ->
                for (name in FILE_NAMES) {
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
                    if (name in FILE_NAMES && !entry.isDirectory) {
                        val target = java.io.File(dir, name)
                        target.outputStream().use { zip.copyTo(it) }
                        restored.add(name)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: return "Could not read the backup file"
        if (restored.size != FILE_NAMES.size) {
            return "Backup did not contain both identity files. Restored: ${restored.joinToString().ifBlank { "none" }}"
        }
        return "Identity restored. Restart the node to use it. Without this backup, a new node is a different peer."
    }
}
