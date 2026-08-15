package dev.rushi.apkdownloadhelper

import java.net.URLEncoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UptodownParserTest {

    private val packageName = "com.example.app"
    private val searchUrl = "https://en.uptodown.com/android/search?query=${URLEncoder.encode(packageName, "UTF-8")}"
    // Subdomain must match the parser's [a-z0-9-]+ site regex (no dots).
    private val detailUrl = "https://example-app.en.uptodown.com/android"

    private val downloadPageHtml = """
        <html><body>
          <div id="detail-app-name" data-code="12345">Example App</div>
          <table><tr><th>Package Name</th><td>$packageName</td></tr></table>
        </body></html>
    """.trimIndent()

    private val versionsJson = """
        {
          "data": [
            {
              "fileID": 111,
              "version": "1.0.0",
              "kindFile": "APK",
              "versionURL": {
                "url": "$detailUrl",
                "extraURL": "download",
                "versionID": 111
              }
            },
            {
              "fileID": 222,
              "version": "2.0.0",
              "kindFile": "APK",
              "versionURL": {
                "url": "$detailUrl",
                "extraURL": "download",
                "versionID": 222
              }
            }
          ]
        }
    """.trimIndent()

    @Test
    fun resolveHistory_listsVersionsNewestFirst() = runBlocking {
        val parser = UptodownParser(
            testParserContext(
                pages = mapOf(
                    searchUrl to
                        """<html><body><a href="$detailUrl">Example App</a></body></html>""",
                    "$detailUrl/download" to downloadPageHtml,
                    "$detailUrl/apps/12345/versions/1" to versionsJson
                )
            )
        )

        val candidates = parser.resolveHistory(testRequest(packageName = packageName))

        assertEquals(2, candidates.size)
        assertEquals("2.0.0", candidates[0].versionName)
        assertEquals("1.0.0", candidates[1].versionName)
        assertEquals("$detailUrl/download/222", candidates[0].url)
        assertEquals("apk", candidates[0].fileKind)
        assertEquals(DownloadSource.UPTODOWN, candidates[0].source)
        assertTrue(!candidates[0].directDownload)
    }

    @Test
    fun resolveLatest_ignoresPlayStoreDataUrlExt() = runBlocking {
        val playDownloadPage = """
            <html><body>
              <div id="detail-app-name" data-code="12345">Example App</div>
              <div class="detail"><div class="info"><div class="version">2.0.0</div></div></div>
              <table><tr><th>Package Name</th><td>$packageName</td></tr></table>
              <button id="detail-download-button" class="button download external"
                data-url-ext="https://play.google.com/store/apps/details?id=$packageName"></button>
            </body></html>
        """.trimIndent()
        val parser = UptodownParser(
            testParserContext(
                pages = mapOf(
                    searchUrl to """<html><body><a href="$detailUrl">Example App</a></body></html>""",
                    "$detailUrl/download" to playDownloadPage
                )
            )
        )

        val candidates = parser.findCandidates(
            testRequest(packageName = packageName),
            CandidateOption.LATEST
        )

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        // A Play Store listing is not a downloadable file — the row must fall
        // back to opening the Uptodown page instead of offering a download.
        assertTrue(!candidate.directDownload)
        assertEquals("$detailUrl/download", candidate.url)
        assertTrue(candidate.files.isEmpty())
        assertEquals("2.0.0", candidate.versionName)
    }

    @Test
    fun resolveLatest_keepsRealExternalApkDataUrlExt() = runBlocking {
        val externalApkUrl = "https://example.com/apps/example.apk"
        val externalDownloadPage = """
            <html><body>
              <div id="detail-app-name" data-code="12345">Example App</div>
              <div class="detail"><div class="info"><div class="version">2.0.0</div></div></div>
              <table><tr><th>Package Name</th><td>$packageName</td></tr></table>
              <button id="detail-download-button" class="button download external"
                data-url-ext="$externalApkUrl"></button>
            </body></html>
        """.trimIndent()
        val parser = UptodownParser(
            testParserContext(
                pages = mapOf(
                    searchUrl to """<html><body><a href="$detailUrl">Example App</a></body></html>""",
                    "$detailUrl/download" to externalDownloadPage
                )
            )
        )

        val candidates = parser.findCandidates(
            testRequest(packageName = packageName),
            CandidateOption.LATEST
        )

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        // A genuine externally-hosted APK is still a valid direct download.
        assertTrue(candidate.directDownload)
        assertEquals(externalApkUrl, candidate.url)
        assertEquals(externalApkUrl, candidate.files.single().url)
    }

    @Test
    fun resolveHistoryCandidate_extractsDirectDownloadUrl() = runBlocking {
        val versionPageUrl = "$detailUrl/download/222"
        val parser = UptodownParser(
            testParserContext(
                pages = mapOf(
                    versionPageUrl to
                        """<html><body><a id="detail-download-button" data-url="xyz-abc"></a></body></html>"""
                )
            )
        )
        val candidate = DownloadCandidate(
            source = DownloadSource.UPTODOWN,
            name = "Example App",
            packageName = packageName,
            versionName = "2.0.0",
            versionCode = null,
            url = versionPageUrl,
            fileKind = "apk",
            option = CandidateOption.LATEST,
            directDownload = false,
            versionStatus = VersionStatus.LATEST,
            formatMatches = true
        )

        val resolved = parser.resolveHistoryCandidate(testRequest(packageName = packageName), candidate)

        assertNotNull(resolved)
        assertEquals("https://dw.uptodown.com/dwn/xyz-abc", resolved!!.url)
        assertTrue(resolved.directDownload)
        assertEquals(1, resolved.files.size)
        assertEquals("https://dw.uptodown.com/dwn/xyz-abc", resolved.files[0].url)
        assertEquals(versionPageUrl, resolved.files[0].referer)
    }
}
