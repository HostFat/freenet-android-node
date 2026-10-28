package org.freenet.androidnode

import android.content.Context
import java.util.UUID

/**
 * A random per-installation identifier used only to group and limit reports.
 * It is not an account and it is not a secret.
 */
internal object InstallId {
    private const val PREFS = "report_identity"
    private const val KEY = "install_id"

    fun get(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, created).apply()
        return created
    }
}
