package org.freenet.androidnode

internal data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val extra: Int = 0,
) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch, SemVer::extra)

    override fun toString(): String =
        if (extra == 0) "$major.$minor.$patch" else "$major.$minor.$patch.$extra"

    companion object {
        fun parse(raw: String?): SemVer? {
            val value = raw?.trim()?.removePrefix("v").orEmpty()
            val match = VERSION.matchEntire(value) ?: return null
            return SemVer(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
                match.groupValues[4].ifBlank { "0" }.toInt(),
            )
        }

        fun parseFromCoreBuildInfo(info: String?): SemVer? {
            val match = CORE_INFO.find(info.orEmpty()) ?: return null
            return parse(match.groupValues[1])
        }

        private val VERSION = Regex("""^(\d+)\.(\d+)\.(\d+)(?:\.(\d+))?$""")
        private val CORE_INFO = Regex("""Freenet core (\d+\.\d+\.\d+)""")
    }
}

internal enum class UpdateKind {
    None,
    CoreOnly,
    ApkAvailable,
}

internal data class UpdateDecision(
    val kind: UpdateKind,
    val message: String? = null,
    val releaseUrl: String? = null,
    val apkVersion: SemVer? = null,
)

internal enum class UpdateCheckInterval(val hours: Int, val displayName: String) {
    Hours2(2, "2 hours"),
    Hours4(4, "4 hours"),
    Hours6(6, "6 hours"),
    Hours12(12, "12 hours"),
    ;

    val intervalMs: Long
        get() = hours * 60L * 60L * 1000L

    companion object {
        val Default = Hours4

        fun fromHours(hours: Int): UpdateCheckInterval =
            entries.find { it.hours == hours } ?: Default
    }
}

internal object UpdateUrls {
    const val CORE_RELEASES_PAGE =
        "https://github.com/freenet/freenet-core/releases/latest"
    const val APK_RELEASES_PAGE =
        "https://github.com/HostFat/freenet-android-node/releases/latest"
}

internal fun versionFromReleaseLocation(location: String): SemVer? {
    val path = location.substringBefore('?').substringBefore('#').trimEnd('/')
    val tag = path.substringAfterLast("/releases/tag/", missingDelimiterValue = "")
        .ifBlank { path.substringAfterLast('/') }
    return SemVer.parse(tag)
}

internal fun effectiveInstalledAppVersion(installedApp: SemVer?, installedCore: SemVer?): SemVer? {
    val app = installedApp ?: return installedCore
    return if (app == SemVer(0, 1, 1)) installedCore else app
}

internal fun decideUpdate(
    installedCore: SemVer?,
    installedApp: SemVer?,
    githubCore: SemVer?,
    githubApk: SemVer?,
    githubApkReleaseUrl: String?,
    peerSeen: SemVer?,
): UpdateDecision {
    val installedAppEffective = effectiveInstalledAppVersion(installedApp, installedCore)
    val apkNewer = githubApk != null && installedAppEffective != null && githubApk > installedAppEffective
    if (apkNewer) {
        return UpdateDecision(
            kind = UpdateKind.ApkAvailable,
            message = "APK ${githubApk} is available (this device: $installedAppEffective).",
            releaseUrl = githubApkReleaseUrl ?: UpdateUrls.APK_RELEASES_PAGE,
            apkVersion = githubApk,
        )
    }

    val newestCore = listOfNotNull(githubCore, peerSeen).maxOrNull()
    val coreNewer = newestCore != null && installedCore != null && newestCore > installedCore
    if (coreNewer) {
        val source = when {
            peerSeen != null && githubCore != null && peerSeen >= githubCore && peerSeen > installedCore ->
                "peers"
            githubCore != null && githubCore > installedCore ->
                "GitHub"
            else ->
                "the network"
        }
        return UpdateDecision(
            kind = UpdateKind.CoreOnly,
            message = "Freenet $newestCore is out ($source); a matching APK is not published yet.",
            releaseUrl = UpdateUrls.CORE_RELEASES_PAGE,
        )
    }

    return UpdateDecision(
        kind = UpdateKind.None,
        message = "No update available.",
    )
}
