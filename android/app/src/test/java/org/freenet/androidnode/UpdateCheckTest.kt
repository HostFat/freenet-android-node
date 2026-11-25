package org.freenet.androidnode

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun automaticChecksPreferStaticFeedThenWorkerThenGithub() {
        assertEquals(
            listOf(UpdateSource.StaticFeed, UpdateSource.Worker, UpdateSource.GitHub),
            updateSourceOrder(force = false),
        )
        val called = mutableListOf<UpdateSource>()
        val result = resolveReleases(
            force = false,
            fetchStatic = {
                called += UpdateSource.StaticFeed
                "from-static"
            },
            fetchWorker = {
                called += UpdateSource.Worker
                "from-worker"
            },
            fetchGitHub = {
                called += UpdateSource.GitHub
                "from-github"
            },
        )
        assertEquals("from-static", result)
        assertEquals(listOf(UpdateSource.StaticFeed), called)
    }

    @Test
    fun manualChecksPreferGithubAndFallbackToCachedFeedsOnRateLimit() {
        assertEquals(
            listOf(UpdateSource.GitHub, UpdateSource.StaticFeed, UpdateSource.Worker),
            updateSourceOrder(force = true),
        )
        val directCalled = mutableListOf<UpdateSource>()
        val directResult = resolveReleases(
            force = true,
            fetchStatic = {
                directCalled += UpdateSource.StaticFeed
                "from-static"
            },
            fetchWorker = {
                directCalled += UpdateSource.Worker
                "from-worker"
            },
            fetchGitHub = {
                directCalled += UpdateSource.GitHub
                "from-github"
            },
        )
        assertEquals("from-github", directResult)
        assertEquals(listOf(UpdateSource.GitHub), directCalled)

        val fallbackCalled = mutableListOf<UpdateSource>()
        val fallbackResult = resolveReleases(
            force = true,
            fetchStatic = {
                fallbackCalled += UpdateSource.StaticFeed
                null
            },
            fetchWorker = {
                fallbackCalled += UpdateSource.Worker
                "from-worker"
            },
            fetchGitHub = {
                fallbackCalled += UpdateSource.GitHub
                throw IOException("GitHub rate limit (HTTP 403); try again later")
            },
        )
        assertEquals("from-worker", fallbackResult)
        assertEquals(
            listOf(UpdateSource.GitHub, UpdateSource.StaticFeed, UpdateSource.Worker),
            fallbackCalled,
        )
    }

    @Test
    fun resolveReleasesRethrowsLastErrorWhenAllSourcesFail() {
        var thrown: Throwable? = null
        try {
            resolveReleases<String>(
                force = true,
                fetchStatic = { null },
                fetchWorker = { null },
                fetchGitHub = { throw IOException("GitHub rate limit (HTTP 403); try again later") },
            )
        } catch (error: Throwable) {
            thrown = error
        }
        assertTrue(thrown is IOException)
        assertTrue(thrown?.message?.contains("403") == true)
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
        assertEquals("No update available.", decision.message)
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
        assertEquals(UpdateUrls.CORE_RELEASES_PAGE, fromGithub.releaseUrl)

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
