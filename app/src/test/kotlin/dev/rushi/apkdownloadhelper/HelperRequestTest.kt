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
}
