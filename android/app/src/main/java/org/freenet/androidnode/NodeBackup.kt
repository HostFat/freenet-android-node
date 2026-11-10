package org.freenet.androidnode

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Keeps one archive of the node state before a new APK starts it. The backup
 * includes the config, gateways, node secrets, database and delegates, so a
 * failed core migration can be rolled back without losing River identities.
 */
internal object NodeBackup {
    private const val TAG = "NodeBackup"
    internal const val PREFS = "node_runtime"
    internal const val CORE_VERSION_KEY = "last_core_version"
    private const val KEY_LAST_VERSION = "last_backup_version"

    fun needsBackup(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_VERSION, 0) != BuildConfig.VERSION_CODE

    fun backup(context: Context): File? {
        val root = File(context.filesDir, "freenet/prebuilt")
        val backupDir = File(root, "backups").apply { mkdirs() }
        // Retention one: keep only the archive for this update.
        backupDir.listFiles()?.forEach { it.deleteRecursively() }
        val sources = listOf(
            File(root, "config"),
            File(root, "data/db"),
            File(root, "data/delegates"),
            File(root, "data/secrets"),
            File(root, "data/secrets_local"),
        ).filter { it.exists() }
        if (sources.isEmpty()) return null
        val target = File(
            backupDir,
            "node-${BuildConfig.VERSION_CODE}-${System.currentTimeMillis()}.zip",
        )
        return runCatching {
            ZipOutputStream(BufferedOutputStream(target.outputStream())).use { zip ->
                for (source in sources) {
                    add(zip, source, source.name)
                }
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_LAST_VERSION, BuildConfig.VERSION_CODE)
                .apply()
            Log.i(TAG, "backup written ${target.absolutePath}")
            target
        }.getOrElse { error ->
            Log.e(TAG, "backup failed", error)
            target.delete()
            null
        }
    }

    fun latest(context: Context): File? =
        File(context.filesDir, "freenet/prebuilt/backups")
            .listFiles()
            ?.filter { it.isFile && it.name.endsWith(".zip") }
            ?.maxByOrNull { it.lastModified() }

    fun restore(context: Context): Result<String> = runCatching {
        val root = File(context.filesDir, "freenet/prebuilt")
        val archive = latest(context) ?: error("No backup found")
        val staging = File(root, ".restore-tmp").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            java.util.zip.ZipInputStream(
                java.io.BufferedInputStream(archive.inputStream()),
            ).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val target = File(staging, entry.name).canonicalFile
                    require(
                        target.path.startsWith(staging.canonicalPath + File.separator),
                    ) { "Unsafe backup entry: ${entry.name}" }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            fun replace(name: String, destination: File) {
                val source = File(staging, name)
                if (!source.exists()) return
                destination.deleteRecursively()
                destination.parentFile?.mkdirs()
                check(source.renameTo(destination)) { "Could not restore $name" }
            }
            replace("config", File(root, "config"))
            replace("db", File(root, "data/db"))
            replace("delegates", File(root, "data/delegates"))
            replace("secrets", File(root, "data/secrets"))
            replace("secrets_local", File(root, "data/secrets_local"))
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_LAST_VERSION, BuildConfig.VERSION_CODE)
                .putString(CORE_VERSION_KEY, BuildConfig.CORE_VERSION)
                .apply()
            "Backup restored. Start the node."
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun add(zip: ZipOutputStream, file: File, name: String) {
        if (file.isDirectory) {
            val children = file.listFiles().orEmpty()
            if (children.isEmpty()) {
                zip.putNextEntry(ZipEntry("$name/"))
                zip.closeEntry()
            }
            for (child in children) {
                add(zip, child, "$name/${child.name}")
            }
        } else {
            zip.putNextEntry(ZipEntry(name))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}
