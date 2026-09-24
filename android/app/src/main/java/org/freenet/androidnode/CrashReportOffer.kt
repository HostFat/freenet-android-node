package org.freenet.androidnode

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object CrashReportOffer {
    private const val PREFS = "crash_report_offer"
    private const val PENDING_KEY = "pending"
    private const val DETAIL_KEY = "detail"

    private val mutablePending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = mutablePending.asStateFlow()

    fun initialize(context: Context) {
        mutablePending.value = prefs(context).getBoolean(PENDING_KEY, false)
    }

    fun note(context: Context, detail: String) {
        prefs(context).edit()
            .putBoolean(PENDING_KEY, true)
            .putString(DETAIL_KEY, detail.take(500))
            .apply()
        mutablePending.value = true
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        mutablePending.value = false
    }

    fun detail(context: Context): String =
        prefs(context).getString(DETAIL_KEY, "").orEmpty()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

internal fun shouldOfferCrashReport(
    udpPortInUse: Boolean,
    policyBlocked: Boolean,
    userRequestedShutdown: Boolean,
): Boolean = !udpPortInUse && !policyBlocked && !userRequestedShutdown
