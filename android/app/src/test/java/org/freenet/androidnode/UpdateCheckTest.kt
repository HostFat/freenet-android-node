package org.freenet.androidnode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateCheckTest {
    @Test
    fun parsesCoreBuildInfoAndTags() {
        assertEquals(
            SemVer(0, 2, 134),
            SemVer.parseFromCoreBuildInfo(
                "Freenet core 0.2.134; features: redb, trace; default gateway port: 31337",
            ),
        )
        assertEquals(SemVer(0, 2, 135), SemVer.parse("v0.2.135"))
        assertEquals(SemVer(0, 2, 134, 1), SemVer.parse("v0.2.134.1"))
        assertEquals(1, SemVer(0, 2, 134, 1).compareTo(SemVer(0, 2, 134)))
        assertNull(SemVer.parse("main"))
        assertEquals(
            SemVer(0, 2, 134, 3),
            versionFromReleaseLocation(
                "https://github.com/HostFat/freenet-android-node/releases/tag/v0.2.134.3",
            ),
        )
        assertEquals(
            SemVer(0, 2, 134),
            versionFromReleaseLocation("/freenet/freenet-core/releases/tag/v0.2.134"),
        )
    }

    @Test
    fun autoCheckIntervalDefaultsToFourHoursAndRejectsUnknownValues() {
        assertEquals(UpdateCheckInterval.Hours4, UpdateCheckInterval.Default)
        assertEquals(4L * 60 * 60 * 1000, UpdateCheckInterval.Hours4.intervalMs)
        assertEquals(UpdateCheckInterval.Hours2, UpdateCheckInterval.fromHours(2))
        assertEquals(UpdateCheckInterval.Hours4, UpdateCheckInterval.fromHours(99))
    }

    @Test
    fun debugVersionNameDoesNotPretendTheApkIsOld() {
        val decision = decideUpdate(
            installedCore = SemVer(0, 2, 134),
            installedApp = SemVer(0, 1, 1),
            githubCore = SemVer(0, 2, 134),
            githubApk = SemVer(0, 2, 134),
            githubApkReleaseUrl = UpdateUrls.APK_RELEASES_PAGE,
            peerSeen = null,
        )
        assertEquals(UpdateKind.None, decision.kind)
    }

    @Test
    fun apkReleaseWinsAndPointsAtTheGithubPage() {
        val decision = decideUpdate(
            installedCore = SemVer(0, 2, 134),
            installedApp = SemVer(0, 2, 134),
            githubCore = SemVer(0, 2, 135),
            githubApk = SemVer(0, 2, 135),
            githubApkReleaseUrl = UpdateUrls.APK_RELEASES_PAGE,
            peerSeen = SemVer(0, 2, 135),
        )
        assertEquals(UpdateKind.ApkAvailable, decision.kind)
        assertEquals(UpdateUrls.APK_RELEASES_PAGE, decision.releaseUrl)
        assertEquals(SemVer(0, 2, 135), decision.apkVersion)
    }

    @Test
    fun newerCoreWithoutApkIsInAppOnly() {
        val fromGithub = decideUpdate(
            installedCore = SemVer(0, 2, 134),
            installedApp = SemVer(0, 2, 134),
            githubCore = SemVer(0, 2, 135),
            githubApk = SemVer(0, 2, 134),
            githubApkReleaseUrl = UpdateUrls.APK_RELEASES_PAGE,
            peerSeen = null,
        )
        assertEquals(UpdateKind.CoreOnly, fromGithub.kind)
        assertNull(fromGithub.releaseUrl)

        val fromPeers = decideUpdate(
            installedCore = SemVer(0, 2, 134),
            installedApp = SemVer(0, 2, 134),
            githubCore = SemVer(0, 2, 134),
            githubApk = SemVer(0, 2, 134),
            githubApkReleaseUrl = UpdateUrls.APK_RELEASES_PAGE,
            peerSeen = SemVer(0, 2, 136),
        )
        assertEquals(UpdateKind.CoreOnly, fromPeers.kind)
        assertEquals(true, fromPeers.message?.contains("peers"))
    }
}
