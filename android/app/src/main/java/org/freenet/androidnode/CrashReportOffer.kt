package org.freenet.androidnode

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal const val IDLE_NODE_DETAIL = "The foreground service is not running"

internal fun crashSnapshotIsUseful(detail: String, ring: String, hasFileLog: Boolean): Boolean {
    if (hasFileLog) return true
    if (ring.isNotBlank()) return true
    val reason = detail.trim()
    return reason.isNotEmpty() && reason != IDLE_NODE_DETAIL
}

internal object CrashReportOffer {
    private const val PREFS = "crash_report_offer"
    private const val PENDING_KEY = "pending"
    private const val DETAIL_KEY = "detail"
    private const val RING_KEY = "ring"
    private const val NOTED_AT_KEY = "noted_at"
    private const val RING_LIMIT = 80_000
    private const val DETAIL_LIMIT = 2_000
    private const val STACK_LIMIT = 16_000

    private val mutablePending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = mutablePending.asStateFlow()

    fun initialize(context: Context) {
        mutablePending.value = prefs(context).getBoolean(PENDING_KEY, false)
    }

    fun note(context: Context, detail: String, ring: String?) {
        val savedRing = ring.orEmpty().takeLast(RING_LIMIT)
        val hasFileLog = ServiceReport.hasLogFiles(context)
        if (!crashSnapshotIsUseful(detail, savedRing, hasFileLog)) return
        prefs(context).edit()
            .putBoolean(PENDING_KEY, true)
            .putString(DETAIL_KEY, detail.trim().take(DETAIL_LIMIT))
            .putString(RING_KEY, savedRing)
            .putLong(NOTED_AT_KEY, System.currentTimeMillis())
            .apply()
        mutablePending.value = true
    }

    /**
     * Called from the uncaught-exception handler, where the process is about to
     * die. Use a synchronous commit so the stack is on disk before that happens.
     */
    fun noteThrowable(context: Context, error: Throwable) {
        val detail = buildString {
            append(error.javaClass.name)
            append(": ")
            append(error.message.orEmpty())
            append('\n')
            append(error.stackTraceToString())
        }.take(STACK_LIMIT)
        prefs(context).edit()
            .putBoolean(PENDING_KEY, true)
            .putString(DETAIL_KEY, detail)
            .putString(RING_KEY, "")
            .putLong(NOTED_AT_KEY, System.currentTimeMillis())
            .commit()
        mutablePending.value = true
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        mutablePending.value = false
    }

    fun detail(context: Context): String =
        prefs(context).getString(DETAIL_KEY, "").orEmpty()

    fun ring(context: Context): String =
        prefs(context).getString(RING_KEY, "").orEmpty()

    fun notedAtEpochMs(context: Context): Long =
        prefs(context).getLong(NOTED_AT_KEY, 0L)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

internal fun shouldOfferCrashReport(
    udpPortInUse: Boolean,
    policyBlocked: Boolean,
    userRequestedShutdown: Boolean,
): Boolean = !udpPortInUse && !policyBlocked && !userRequestedShutdown

internal fun shouldNoteUnexpectedStop(
    state: NodePhase,
    appInitiatedStop: Boolean,
    userRequestedShutdown: Boolean,
): Boolean = state == NodePhase.Failed && !appInitiatedStop && !userRequestedShutdown
