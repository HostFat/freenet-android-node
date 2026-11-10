package org.freenet.androidnode

import java.io.File
import java.io.RandomAccessFile

/** Read only the last [maxBytes] of a log file, never the whole file. */
internal fun readLogTail(file: File, maxBytes: Int): String {
    val length = file.length()
    if (length <= 0L) return ""
    val start = (length - maxBytes).coerceAtLeast(0L)
    return runCatching {
        RandomAccessFile(file, "r").use { handle ->
            handle.seek(start)
            val bytes = ByteArray((length - start).toInt())
            handle.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }.getOrDefault("")
}
