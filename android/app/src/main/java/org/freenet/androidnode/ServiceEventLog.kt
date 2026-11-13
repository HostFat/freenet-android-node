package org.freenet.androidnode

import java.time.Instant

/** Small in-memory ring of app state changes included in service reports. */
internal object ServiceEventLog {
    private const val MAX_EVENTS = 40
    private val events = ArrayDeque<String>()
    private val lock = Any()

    fun record(event: String) {
        synchronized(lock) {
            events.addLast("${Instant.now()} $event")
            while (events.size > MAX_EVENTS) {
                events.removeFirst()
            }
        }
    }

    fun recent(): String? = synchronized(lock) {
        events.joinToString("\n").ifBlank { null }
    }
}
