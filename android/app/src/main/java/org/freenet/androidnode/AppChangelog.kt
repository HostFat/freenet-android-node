package org.freenet.androidnode

import android.content.Context

internal object AppChangelog {
    const val PREFS = "app_changelog"
    const val LAST_SEEN_VERSION = "last_seen_version"

    const val LINE =
        "This update: clearer node status, live UDP port, start on boot, identity backup, recent logs, and a dashboard browser link."

    fun pendingLine(context: Context): String? {
        val current = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: return null
        val seen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LAST_SEEN_VERSION, null)
        return if (seen != current) LINE else null
    }

    fun markSeen(context: Context) {
        val current = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(LAST_SEEN_VERSION, current)
            .apply()
    }
}
