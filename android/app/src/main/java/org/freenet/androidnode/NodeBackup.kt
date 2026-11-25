package org.freenet.androidnode

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
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
    private const val MAX_RESTORE_BYTES = 512L * 1024 * 1024
    private const val MAX_RESTORE_ENTRIES = 20_000

    fun needsBackup(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_VERSION, 0) != BuildConfig.VERSION_CODE

    fun backup(context: Context): File? {
        val root = File(context.filesDir, "freenet/prebuilt")
        val backupDir = File(root, "backups").apply { mkdirs() }
        val sources = listOf(
            File(root, "config"),
            File(root, "data/db"),
            File(root, "data/delegates"),
            File(root, "data/secrets"),
        ).filter { it.exists() }
        if (sources.isEmpty()) return null
        val stamp = "${BuildConfig.VERSION_CODE}-${System.currentTimeMillis()}"
        val temp = File(backupDir, "node-$stamp.zip.tmp")
        val target = File(backupDir, "node-$stamp.zip")
        return runCatching {
            ZipOutputStream(BufferedOutputStream(temp.outputStream())).use { zip ->
                for (source in sources) {
                    add(zip, source, source.name)
                }
            }
            check(temp.renameTo(target)) { "Could not finalize backup archive" }
            // Retention one: remove older archives only after the new archive is safe on disk.
            backupDir.listFiles()
                ?.filter { it.absolutePath != target.absolutePath }
                ?.forEach { it.deleteRecursively() }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_LAST_VERSION, BuildConfig.VERSION_CODE)
                .apply()
            Log.i(TAG, "backup written ${target.absolutePath}")
            target
        }.getOrElse { error ->
            Log.e(TAG, "backup failed", error)
            temp.delete()
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
        if (NodeRepository.state.value.serviceActive || NodeBackend.isActive()) {
            error("Stop the node before restoring a backup.")
        }
        val root = File(context.filesDir, "freenet/prebuilt")
        val archive = latest(context) ?: error("No backup found")
        val staging = File(root, ".restore-tmp").apply {
            deleteRecursively()
            mkdirs()
        }
        val previous = File(root, ".restore-prev").apply {
            deleteRecursively()
            mkdirs()
        }
        val swapped = mutableListOf<Pair<File, File>>()
        try {
            extractArchiveSafely(archive, staging)
            val mapping = listOf(
                "config" to File(root, "config"),
                "db" to File(root, "data/db"),
                "delegates" to File(root, "data/delegates"),
                "secrets" to File(root, "data/secrets"),
            )
            for ((name, destination) in mapping) {
                val source = File(staging, name)
                if (!source.exists()) continue
                destination.parentFile?.mkdirs()
                val backupSlot = File(previous, name)
                if (destination.exists()) {
                    check(destination.renameTo(backupSlot)) {
                        "Could not stage current $name for rollback"
                    }
                    swapped.add(backupSlot to destination)
                }
                if (!source.renameTo(destination)) {
                    error("Could not restore $name")
                }
            }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(KEY_LAST_VERSION, BuildConfig.VERSION_CODE)
                .putString(CORE_VERSION_KEY, BuildConfig.CORE_VERSION)
                .apply()
            "Backup restored. Start the node."
        } catch (error: Throwable) {
            // Roll back any directories swapped before the failure.
            for ((backupSlot, destination) in swapped.asReversed()) {
                destination.deleteRecursively()
                backupSlot.renameTo(destination)
            }
            throw error
        } finally {
            staging.deleteRecursively()
            previous.deleteRecursively()
        }
    }

    internal fun extractArchiveSafely(archive: File, staging: File) {
        val canonicalRoot = staging.canonicalPath + File.separator
        var totalBytes = 0L
        var entryCount = 0
        val buffer = ByteArray(16 * 1024)
        ZipInputStream(BufferedInputStream(archive.inputStream())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entryCount += 1
                require(entryCount <= MAX_RESTORE_ENTRIES) { "Backup archive has too many entries" }
                val target = File(staging, entry.name).canonicalFile
                require(target.path.startsWith(canonicalRoot)) {
                    "Unsafe backup entry: ${entry.name}"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { out ->
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            totalBytes += read
                            require(totalBytes <= MAX_RESTORE_BYTES) {
                                "Backup archive exceeds maximum safe size"
                            }
                            out.write(buffer, 0, read)
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
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
