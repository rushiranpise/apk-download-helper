package dev.rushi.apkdownloadhelper

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
}
