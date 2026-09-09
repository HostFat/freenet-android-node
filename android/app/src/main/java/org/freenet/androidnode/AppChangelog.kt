package org.freenet.androidnode

import android.content.Context

internal fun changelogLineIfUpdated(current: String?, seen: String?): String? {
    if (current.isNullOrBlank()) return null
    if (seen.isNullOrBlank()) return null
    if (seen == current) return null
    return AppChangelog.LINE
}

internal object AppChangelog {
    const val PREFS = "app_changelog"
    const val LAST_SEEN_VERSION = "last_seen_version"

    const val LINE =
        "This update: clearer node status, live UDP port, start on boot, identity backup, recent logs, and a dashboard browser link."

    fun pendingLine(context: Context): String? {
        val current = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getString(LAST_SEEN_VERSION, null)
        val line = changelogLineIfUpdated(current, seen)
        if (seen.isNullOrBlank()) {
            markSeen(context)
        }
        return line
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
