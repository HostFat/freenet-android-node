package org.freenet.androidnode

import android.content.Context

internal object MenuEdgeHint {
    private const val PREFS = "menu_edge_hint"
    private const val OPENED = "opened"

    fun hasOpened(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(OPENED, false)

    fun markOpened(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(OPENED, true)
            .apply()
    }
}
