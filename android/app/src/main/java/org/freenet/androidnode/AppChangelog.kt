package org.freenet.androidnode

import android.content.Context

internal const val APP_REPO_URL = "https://github.com/HostFat/freenet-android-node"
internal const val CORE_REPO_URL = "https://github.com/freenet/freenet-core"

internal fun panelVersionLabels(appVersion: String?, coreVersion: String?): Pair<String, String> {
    val app = appVersion?.trim()?.takeIf { it.isNotEmpty() } ?: "—"
    val core = coreVersion?.trim()?.takeIf { it.isNotEmpty() } ?: "—"
    return "App $app" to "Node $core"
}

internal fun panelVersionLine(appVersion: String?, coreVersion: String?): String {
    val (app, core) = panelVersionLabels(appVersion, coreVersion)
    return "$app · $core"
}

internal fun coreVersionFromBuildInfo(info: String?): String? =
    SemVer.parseFromCoreBuildInfo(info)?.toString()

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
        "This update: while Waiting, the menu and logs say why, for example when the network is metered. The settings menu scrolls."

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
