package dev.rushi.apkdownloadhelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelperRequestTest {

    private fun multiFormatRequest() = testRequest(
        packageName = "com.zhiliaoapp.musically",
        appName = "TikTok",
        versionName = "46.2.3",
        requestedFileType = "APK/APKM/APKS/XAPK",
        allowSplitArchive = true
    )

    @Test
    fun acceptsFormat_acceptsAnySingleKindFromMultiKindRequest() {
        val request = multiFormatRequest()
        assertTrue(request.acceptsFormat("apk"))
        assertTrue(request.acceptsFormat("apkm"))
        assertTrue(request.acceptsFormat("apks"))
        assertTrue(request.acceptsFormat("xapk"))
    }

    @Test
    fun acceptsFormat_acceptsWholeMultiKindLabel() {
        val request = multiFormatRequest()
        assertTrue(request.acceptsFormat("APK/APKM/APKS/XAPK"))
        assertTrue(request.acceptsFormat("apk/apkm/apks/xapk"))
    }

    @Test
    fun acceptsFormat_rejectsKindsOutsideRequest() {
        val request = multiFormatRequest()
        assertFalse(request.acceptsFormat("web"))
        assertFalse(request.acceptsFormat("zip"))
    }

    @Test
    fun acceptsFormat_rejectsKindsOutsideSingleKindRequest() {
        val request = testRequest(requestedFileType = "APKS", allowSplitArchive = true)
        assertTrue(request.acceptsFormat("apks"))
        assertFalse(request.acceptsFormat("apk"))
        assertFalse(request.acceptsFormat("xapk"))
        // A candidate labeled with the generic multi-kind tag still covers APKS.
        assertTrue(request.acceptsFormat("APK/APKM/APKS/XAPK"))
    }

    // ---- Fast Mode strict version matching (exact name AND code) ----

    @Test
    fun strictMatch_acceptsSameNameAndCode() {
        val request = testRequest(versionName = "2.5.0.6", versionCode = 723)
        assertTrue(request.matchesRequestedVersionStrict("2.5.0.6", 723))
    }

    @Test
    fun strictMatch_rejectsNameMatchWithDifferentCode() {
        // Battery Guru case: request wants build 723, source only has 721.
        val request = testRequest(versionName = "2.5.0.6", versionCode = 723)
        assertFalse(request.matchesRequestedVersionStrict("2.5.0.6", 721))
        // ...even though the lenient matcher (used by the Recommended tab)
        // still treats it as a name match.
        assertTrue(request.matchesRequestedVersion("2.5.0.6", 721))
    }

    @Test
    fun strictMatch_acceptsNameMatchWhenSourceDoesNotReportCode() {
        val request = testRequest(versionName = "2.5.0.6", versionCode = 723)
        assertTrue(request.matchesRequestedVersionStrict("2.5.0.6", null))
    }

    @Test
    fun strictMatch_rejectsDifferentNameWithSameCode() {
        val request = testRequest(versionName = "2.5.0.6", versionCode = 723)
        assertFalse(request.matchesRequestedVersionStrict("2.5.1.0", 723))
    }

    @Test
    fun strictMatch_nameOnlyRequestIgnoresCandidateCode() {
        val request = testRequest(versionName = "2.5.0.6", versionCode = null)
        assertTrue(request.matchesRequestedVersionStrict("2.5.0.6", 721))
        assertTrue(request.matchesRequestedVersionStrict("2.5.0.6", 723))
    }

    @Test
    fun strictMatch_rejectsWhenRequestHasNoVersionAtAll() {
        val request = testRequest(versionName = null, versionCode = null)
        assertFalse(request.matchesRequestedVersionStrict("2.5.0.6", 721))
    }

    // ---- stale-result scoping (PendingDownloadResult.belongsTo) ----

    private fun pendingResult(
        requestPackage: String = "com.example.app",
        versionName: String? = "1.2.3"
    ) = PendingDownloadResult(
        uri = "content://x/file.apk",
        fileName = "file.apk",
        packageName = "com.example.app",
        versionName = versionName,
        sourceName = "APKMirror",
        requestPackage = requestPackage,
        callerPackage = "app.morphe.manager"
    )

    @Test
    fun belongsTo_matchesSamePackageAndVersion() {
        val request = testRequest(packageName = "com.example.app", versionName = "1.2.3")
        assertTrue(pendingResult(requestPackage = "com.example.app", versionName = "1.2.3").belongsTo(request))
    }

    @Test
    fun belongsTo_rejectsDifferentPackage() {
        val request = testRequest(packageName = "com.new.app", versionName = "1.2.3")
        assertFalse(pendingResult(requestPackage = "com.example.app").belongsTo(request))
    }

    @Test
    fun belongsTo_rejectsDifferentRequestedVersionOfSamePackage() {
        val request = testRequest(packageName = "com.example.app", versionName = "9.9.9")
        assertFalse(pendingResult(requestPackage = "com.example.app", versionName = "1.2.3").belongsTo(request))
    }

    @Test
    fun belongsTo_acceptsWhenRequestPinsNoVersion() {
        val request = testRequest(packageName = "com.example.app", versionName = null)
        assertTrue(pendingResult(requestPackage = "com.example.app", versionName = "1.2.3").belongsTo(request))
    }

    @Test
    fun belongsTo_rejectsNullRequest() {
        assertFalse(pendingResult().belongsTo(null))
    }

    // ---- live-event scoping (PendingDownloadResult.belongsToCurrentSession) ----

    @Test
    fun belongsToCurrentSession_matchesSamePackageAndEpoch() {
        val request = testRequest(packageName = "com.example.app", versionName = "1.2.3")
        assertTrue(
            pendingResult(requestPackage = "com.example.app", versionName = "1.2.3")
                .belongsToCurrentSession(request, epoch = DownloadJobManager.currentEpoch)
        )
    }

    @Test
    fun belongsToCurrentSession_acceptsDifferentVersionSameSession() {
        // The user may deliberately download a different version (Latest tab)
        // in the same session; it must still be returned, not dropped.
        val request = testRequest(packageName = "com.example.app", versionName = "1.2.3")
        assertTrue(
            pendingResult(requestPackage = "com.example.app", versionName = "9.9.9")
                .belongsToCurrentSession(request, epoch = DownloadJobManager.currentEpoch)
        )
    }

    @Test
    fun belongsToCurrentSession_rejectsDifferentPackage() {
        val request = testRequest(packageName = "com.new.app", versionName = "1.2.3")
        assertFalse(
            pendingResult(requestPackage = "com.example.app")
                .belongsToCurrentSession(request, epoch = DownloadJobManager.currentEpoch)
        )
    }

    @Test
    fun belongsToCurrentSession_rejectsStaleEpoch() {
        val request = testRequest(packageName = "com.example.app", versionName = "1.2.3")
        assertFalse(
            pendingResult(requestPackage = "com.example.app")
                .belongsToCurrentSession(request, epoch = DownloadJobManager.currentEpoch - 1)
        )
    }

    @Test
    fun belongsToCurrentSession_rejectsNullRequest() {
        assertFalse(
            pendingResult().belongsToCurrentSession(null, epoch = DownloadJobManager.currentEpoch)
        )
    }

    // ---- fileKindFromUrl ----

    @Test
    fun fileKindFromUrl_apkmirrorCdnApkIsNotMistakenForBundle() {
        // The CDN file name embeds the host domain; "apkm" must not match "apkmirror".
        val url = "https://eb5e7388c3df147b74dd2379b7cf8323.r2.cloudflarestorage.com/downloadprod/" +
            "wp-content/uploads/2026/06/10/6a1d61d704300/com.accuweather.android.tablet_1.2.7-32_" +
            "minAPI11%28nodpi%29_apkmirror.com.apk?X-Amz-Expires=3600"
        assertEquals("apk", fileKindFromUrl(url))
    }

    @Test
    fun fileKindFromUrl_downloadPhpIsNotMistakenForBundle() {
        // APKMirror's download.php path itself contains "apkmirror".
        val url = "https://www.apkmirror.com/wp-content/themes/APKMirror/download.php?id=14072439&key=2b218113dda68435162f39e99e820c5525641192"
        assertEquals("apk", fileKindFromUrl(url))
    }

    @Test
    fun fileKindFromUrl_realBundleUrl() {
        assertEquals("apkm", fileKindFromUrl("https://cdn.example.com/apps/com.example.app-2.0_apkmirror.com.apkm"))
        assertEquals("apkm", fileKindFromUrl("https://cdn.example.com/download/12345.bundle-apkm.bin"))
    }

    @Test
    fun fileKindFromUrl_splitAndXapk() {
        assertEquals("apks", fileKindFromUrl("https://cdn.example.com/apps/com.example.app_apkmirror.com.apks"))
        assertEquals("xapk", fileKindFromUrl("https://cdn.example.com/apps/com.example.app.xapk"))
    }

    @Test
    fun fileKindFromUrl_filenameQueryParam() {
        assertEquals(
            "xapk",
            fileKindFromUrl("https://example.com/download.php?id=14072439&filename=app.xapk&key=abc")
        )
    }

    @Test
    fun fileKindFromUrl_noExtensionDefaultsToApk() {
        assertEquals("apk", fileKindFromUrl("https://example.com/download?id=1"))
    }
}
