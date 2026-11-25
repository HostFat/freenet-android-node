package org.freenet.androidnode

import android.content.Context

object AlphaDisclaimerAcceptance {
    private const val PREFERENCES_NAME = "alpha_disclaimer"
    private const val ACCEPTED_VERSION_CODE_KEY = "accepted_version_code"
    private const val ACCEPTED_REVISION_KEY = "accepted_disclaimer_revision"
    private const val NO_ACCEPTED_VERSION = -1L

    /**
     * Bump this constant only when the disclaimer text/terms materially change.
     * Ordinary APK updates preserve acceptance so post-update auto-restart works.
     */
    const val DISCLAIMER_REVISION = 1

    fun isAccepted(
        context: Context,
        revision: Int = DISCLAIMER_REVISION,
    ): Boolean {
        val prefs = preferences(context)
        val storedRevision = prefs.getInt(ACCEPTED_REVISION_KEY, 0)
        if (storedRevision >= revision) return true
        // Migrate existing installs that accepted an older versionCode-based disclaimer.
        val legacyVersion = prefs.getLong(ACCEPTED_VERSION_CODE_KEY, NO_ACCEPTED_VERSION)
        if (legacyVersion != NO_ACCEPTED_VERSION && revision == 1) {
            prefs.edit().putInt(ACCEPTED_REVISION_KEY, revision).apply()
            return true
        }
        return false
    }

    fun accept(
        context: Context,
        revision: Int = DISCLAIMER_REVISION,
    ) {
        preferences(context)
            .edit()
            .putInt(ACCEPTED_REVISION_KEY, revision)
            .putLong(ACCEPTED_VERSION_CODE_KEY, currentVersionCode(context))
            .apply()
    }

    fun clear(context: Context) {
        preferences(context)
            .edit()
            .remove(ACCEPTED_REVISION_KEY)
            .remove(ACCEPTED_VERSION_CODE_KEY)
            .apply()
    }

    private fun currentVersionCode(context: Context): Long =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
}
