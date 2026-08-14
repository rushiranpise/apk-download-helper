package dev.rushi.apkdownloadhelper

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkPureParserTest {

    private val packageName = "com.example.app"

    @Test
    fun findCandidates_latest_mapsApiResponseToCandidate() = runBlocking {
        val api = FakeApkPureApi(
            response = ApkPureUpdateResponse(
                app_update_response = listOf(
                    ApkPureAppUpdate(
                        package_name = packageName,
                        version_code = 1234L,
                        version_name = "1.2.3",
                        label = "Example App",
                        asset = ApkPureAsset(
                            url = "http://download.apkpure.com/b/APK/$packageName?version=latest"
                        )
                    )
                )
            )
        )
        val parser = ApkPureParser(testParserContext(pages = emptyMap(), apkPureApi = api))

        val candidates = parser.findCandidates(
            request = testRequest(packageName = packageName),
            option = CandidateOption.LATEST
        )

        assertEquals(1, candidates.size)
        val candidate = candidates[0]
        assertTrue(candidate.directDownload)
        assertEquals("1.2.3", candidate.versionName)
        assertEquals(1234L, candidate.versionCode)
        assertEquals("apk", candidate.fileKind)
        assertEquals(DownloadSource.APK_PURE, candidate.source)
        assertTrue(candidate.url.startsWith("https://download.apkpure.com/"))
        assertEquals(1, candidate.files.size)
    }

    @Test
    fun findCandidates_requested_fallsBackToApiWhenWebVersionsPageBlocked() = runBlocking {
        val api = FakeApkPureApi(
            response = ApkPureUpdateResponse(
                app_update_response = listOf(
                    ApkPureAppUpdate(
                        package_name = packageName,
                        version_code = 593L,
                        version_name = "8.72.0",
                        label = "Example App",
                        asset = ApkPureAsset(
                            url = "http://download.apkpure.com/b/XAPK/$packageName?version=8.72.0"
                        )
                    )
                )
            )
        )
        // Web versions page is blocked (410), but the API serves the exact
        // requested version — the parser must fall back to the API.
        val parser = ApkPureParser(
            testParserContext(
                pages = mapOf(
                    "https://apkpure.com/apk-info/$packageName" to """<html><head><link rel="canonical" href="https://apkpure.com/$packageName"></head><body></body></html>"""
                ),
                apkPureApi = api
            )
        )

        val candidates = parser.findCandidates(
            request = testRequest(
                packageName = packageName,
                versionName = "8.72.0",
                versionCode = 593L
            ),
            option = CandidateOption.REQUESTED
        )

        assertEquals(1, candidates.size)
        val candidate = candidates[0]
        assertTrue(candidate.directDownload)
        assertEquals("8.72.0", candidate.versionName)
        assertEquals(593L, candidate.versionCode)
        assertEquals("xapk", candidate.fileKind)
        assertTrue(candidate.url.startsWith("https://download.apkpure.com/"))
    }

    @Test
    fun findCandidates_requested_skipsApiWhenVersionMismatches() = runBlocking {
        val api = FakeApkPureApi(
            response = ApkPureUpdateResponse(
                app_update_response = listOf(
                    ApkPureAppUpdate(
                        package_name = packageName,
                        version_code = 999L,
                        version_name = "9.9.9",
                        label = "Example App",
                        asset = ApkPureAsset(url = "http://download.apkpure.com/b/APK/$packageName?version=9.9.9")
                    )
                )
            )
        )
        val parser = ApkPureParser(
            testParserContext(
                pages = mapOf(
                    "https://apkpure.com/apk-info/$packageName" to """<html><head><link rel="canonical" href="https://apkpure.com/$packageName"></head><body></body></html>""",
                    "https://apkpure.com/$packageName/versions" to "<html></html>"
                ),
                apkPureApi = api
            )
        )

        // API returns 9.9.9 but we asked for 8.72.0 — must not return it.
        val candidates = parser.findCandidates(
            request = testRequest(
                packageName = packageName,
                versionName = "8.72.0",
                versionCode = 593L
            ),
            option = CandidateOption.REQUESTED
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun findCandidates_latest_fallsBackToWebWhenApiEmpty() = runBlocking {
        val api = FakeApkPureApi(response = ApkPureUpdateResponse())
        val infoUrl = "https://apkpure.com/apk-info/$packageName"
        val canonicalUrl = "https://apkpure.com/$packageName"
        val parser = ApkPureParser(
            testParserContext(
                pages = mapOf(
                    infoUrl to
                        """<html><head><link rel="canonical" href="$canonicalUrl"></head>
                           <body>Example App</body></html>""",
                    "$canonicalUrl/downloading/" to
                        """<html><body>
                           <a id="download_link" href="http://download.apkpure.com/b/APK/$packageName?version=1234"></a>
                         </body></html>"""
                ),
                apkPureApi = api
            )
        )

        val candidates = parser.findCandidates(
            request = testRequest(packageName = packageName),
            option = CandidateOption.LATEST
        )

        assertEquals(1, candidates.size)
        val candidate = candidates[0]
        assertTrue(candidate.directDownload)
        assertEquals("apk", candidate.fileKind)
        assertTrue(candidate.url.startsWith("https://download.apkpure.com/"))
    }
}
