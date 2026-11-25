package org.freenet.androidnode

import java.io.File
import java.io.RandomAccessFile

/** Read only the last [maxBytes] of a log file, never the whole file. */
internal fun readLogTail(file: File, maxBytes: Int): String {
    if (maxBytes <= 0 || !file.isFile) return ""
    return runCatching {
        RandomAccessFile(file, "r").use { handle ->
            val length = handle.length()
            if (length <= 0L) return@use ""
            val start = (length - maxBytes).coerceAtLeast(0L)
            val toRead = (length - start).toInt()
            handle.seek(start)
            val bytes = ByteArray(toRead)
            var total = 0
            while (total < toRead) {
                val read = handle.read(bytes, total, toRead - total)
                if (read < 0) break
                total += read
            }
            if (total <= 0) return@use ""
            var offset = 0
            if (start > 0L) {
                // Avoid slicing in the middle of a multi-byte UTF-8 character.
                while (offset < total && (bytes[offset].toInt() and 0xC0) == 0x80) {
                    offset += 1
                }
            }
            String(bytes, offset, total - offset, Charsets.UTF_8)
        }
    }.getOrDefault("")
}
