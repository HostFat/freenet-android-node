package org.freenet.androidnode

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal object IdentityBackup {
    private val JNI_FILE_NAMES = listOf("transport_keypair", "delegate_cipher")
    private val MAGIC = byteArrayOf('F'.code.toByte(), 'N'.code.toByte(), 'I'.code.toByte(), '1'.code.toByte())
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val PBKDF2_ITERATIONS = 200_000
    private const val KEY_BITS = 256
    private const val MAX_BACKUP_BYTES = 4 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 2 * 1024 * 1024
    private val SAFE_FILE_NAME = Regex("""^[A-Za-z0-9._-]{1,128}$""")

    fun directory(context: Context): File {
        if (BuildConfig.PREBUILT_NODE) {
            return File(context.applicationContext.filesDir, "freenet/prebuilt/data/secrets")
        }
        return File(context.applicationContext.noBackupFilesDir, "freenet/identity")
    }

    internal fun isAllowedIdentityFileName(name: String, prebuilt: Boolean = BuildConfig.PREBUILT_NODE): Boolean {
        if (name.isBlank() || name == "." || name == ".." || name.startsWith(".")) return false
        if (!SAFE_FILE_NAME.matches(name)) return false
        return if (prebuilt) true else name in JNI_FILE_NAMES
    }

    internal fun isEncryptedHeader(bytes: ByteArray): Boolean =
        bytes.size >= MAGIC.size &&
            bytes[0] == MAGIC[0] &&
            bytes[1] == MAGIC[1] &&
            bytes[2] == MAGIC[2] &&
            bytes[3] == MAGIC[3]

    fun isEncryptedBackup(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(MAGIC.size)
            val read = input.read(header)
            read == MAGIC.size && isEncryptedHeader(header)
        } ?: false
    }.getOrDefault(false)

    internal fun encryptBackupPayload(zipBytes: ByteArray, passphrase: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(zipBytes)
        return ByteArrayOutputStream(MAGIC.size + salt.size + iv.size + ciphertext.size).use { out ->
            out.write(MAGIC)
            out.write(salt)
            out.write(iv)
            out.write(ciphertext)
            out.toByteArray()
        }
    }

    internal fun decryptBackupPayload(payload: ByteArray, passphrase: CharArray): ByteArray {
        val headerSize = MAGIC.size + SALT_BYTES + IV_BYTES
        require(payload.size > headerSize && isEncryptedHeader(payload)) {
            "Not a valid encrypted identity backup"
        }
        val salt = payload.copyOfRange(MAGIC.size, MAGIC.size + SALT_BYTES)
        val iv = payload.copyOfRange(MAGIC.size + SALT_BYTES, headerSize)
        val ciphertext = payload.copyOfRange(headerSize, payload.size)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
            SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun export(context: Context, uri: Uri, passphrase: String = ""): String = runCatching {
        val dir = directory(context)
        if (!File(dir, "transport_keypair").isFile) {
            return@runCatching "Identity files are missing: transport_keypair"
        }
        val names = if (BuildConfig.PREBUILT_NODE) {
            dir.listFiles()
                ?.filter { it.isFile && isAllowedIdentityFileName(it.name, prebuilt = true) }
                ?.map { it.name }
                ?.sorted()
                .orEmpty()
        } else {
            val missing = JNI_FILE_NAMES.filter { !File(dir, it).isFile }
            if (missing.isNotEmpty()) {
                return@runCatching "Identity files are missing: ${missing.joinToString()}"
            }
            JNI_FILE_NAMES
        }
        if (names.isEmpty()) {
            return@runCatching "Identity files are missing"
        }
        val zipBytes = ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                for (name in names) {
                    val file = File(dir, name)
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            bytes.toByteArray()
        }
        val payload = if (passphrase.isNotEmpty()) {
            val chars = passphrase.toCharArray()
            try {
                encryptBackupPayload(zipBytes, chars)
            } finally {
                chars.fill('\u0000')
            }
        } else {
            zipBytes
        }
        context.contentResolver.openOutputStream(uri)?.use { output ->
            output.write(payload)
            output.flush()
        } ?: return@runCatching "Could not write the backup file"
        if (passphrase.isNotEmpty()) {
            "Encrypted identity backup saved. Keep both the file and passphrase safe."
        } else {
            "Identity backup saved (unencrypted). Keep this file private."
        }
    }.getOrElse { error ->
        "Could not write the backup file: ${error.message ?: "unknown error"}"
    }

    fun importFrom(context: Context, uri: Uri, passphrase: String = ""): String = runCatching {
        val raw = context.contentResolver.openInputStream(uri)?.use { input ->
            readBoundedBytes(BufferedInputStream(input), MAX_BACKUP_BYTES)
        } ?: return@runCatching "Could not read the backup file"

        val zipBytes = if (isEncryptedHeader(raw)) {
            if (passphrase.isEmpty()) {
                return@runCatching "This identity backup is encrypted. Enter its passphrase to restore."
            }
            val chars = passphrase.toCharArray()
            try {
                runCatching { decryptBackupPayload(raw, chars) }.getOrElse {
                    return@runCatching "Incorrect passphrase or corrupted identity backup."
                }
            } finally {
                chars.fill('\u0000')
            }
        } else {
            raw
        }

        val dir = directory(context)
        dir.mkdirs()
        setOwnerOnlyPermissions(dir, isDirectory = true)
        val restored = mutableSetOf<String>()
        val staged = mutableListOf<Pair<File, File>>()
        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val name = File(entry.name).name
                        if (isAllowedIdentityFileName(name)) {
                            val bytes = readBoundedBytes(zip, MAX_ENTRY_BYTES)
                            val temp = File(dir, ".$name.${android.os.Process.myPid()}.tmp")
                            temp.writeBytes(bytes)
                            setOwnerOnlyPermissions(temp, isDirectory = false)
                            staged.add(temp to File(dir, name))
                            restored.add(name)
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val expected = if (BuildConfig.PREBUILT_NODE) listOf("transport_keypair") else JNI_FILE_NAMES
            if (!restored.containsAll(expected)) {
                staged.forEach { (temp, _) -> temp.delete() }
                return@runCatching "Backup did not contain required identity files. Found: " +
                    restored.joinToString().ifBlank { "none" }
            }
            for ((temp, target) in staged) {
                check(temp.renameTo(target)) { "Could not install ${target.name}" }
                setOwnerOnlyPermissions(target, isDirectory = false)
            }
        } catch (error: Throwable) {
            staged.forEach { (temp, _) -> temp.delete() }
            throw error
        }
        "Identity restored. Restart the node to use it. Without this backup, a new node is a different peer."
    }.getOrElse { error ->
        "Could not restore the backup: ${error.message ?: "unknown error"}"
    }

    private fun readBoundedBytes(input: java.io.InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "Identity backup file is too large" }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun setOwnerOnlyPermissions(file: File, isDirectory: Boolean) {
        val posixSet = runCatching {
            val perms = if (isDirectory) "rwx------" else "rw-------"
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(perms))
            true
        }.getOrDefault(false)
        if (!posixSet) {
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setExecutable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
            if (isDirectory) file.setExecutable(true, true)
        }
    }
}
