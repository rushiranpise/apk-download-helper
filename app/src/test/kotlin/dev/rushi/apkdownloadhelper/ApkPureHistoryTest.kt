package dev.rushi.apkdownloadhelper

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkPureHistoryTest {

    private val packageName = "com.example.app"
    private val infoUrl = "https://apkpure.com/apk-info/$packageName"
    private val appPageUrl = "https://apkpure.com/$packageName"
    private val versionsUrl = "$appPageUrl/versions"

    private val versionsHtml = """
        <html><body>
          <div class="ver_download_link" data-dt-version="2.0.0" data-dt-versioncode="200">
            <a class="dt-version-name-link" href="/$packageName/download/2.0.0">2.0.0</a>
            <span class="tag">APK</span>
          </div>
          <div class="ver_download_link" data-dt-version="1.0.0" data-dt-versioncode="100">
            <a class="dt-version-name-link" href="/$packageName/download/1.0.0">1.0.0</a>
            <span class="tag">APK</span>
          </div>
        </body></html>
    """.trimIndent()

    @Test
    fun resolveHistory_listsVersionsNewestFirst() = runBlocking {
        val parser = ApkPureParser(
            testParserContext(
                pages = mapOf(
                    infoUrl to
                        """<html><head><link rel="canonical" href="$appPageUrl"></head><body></body></html>""",
                    versionsUrl to versionsHtml
                )
            )
        )

        val candidates = parser.resolveHistory(testRequest(packageName = packageName))

        assertEquals(2, candidates.size)
        assertEquals(listOf("2.0.0", "1.0.0"), candidates.map { it.versionName })
        assertEquals(listOf(200L, 100L), candidates.map { it.versionCode })
        assertEquals("$appPageUrl/download/2.0.0", candidates[0].url)
        assertEquals("apk", candidates[0].fileKind)
        assertTrue(!candidates[0].directDownload)
    }

    @Test
    fun resolveHistoryCandidate_resolvesDownloadPageToDirectUrl() = runBlocking {
        val downloadPageUrl = "$appPageUrl/download/2.0.0"
        val parser = ApkPureParser(
            testParserContext(
                pages = mapOf(
                    downloadPageUrl to
                        """<html><body>
                           <a id="download_link" href="http://download.apkpure.com/b/APK/$packageName?version=200"></a>
                         </body></html>"""
                )
            )
        )
        val candidate = DownloadCandidate(
            source = DownloadSource.APK_PURE,
            name = "Example App",
            packageName = packageName,
            versionName = "2.0.0",
            versionCode = 200L,
            url = downloadPageUrl,
            fileKind = "apk",
            option = CandidateOption.LATEST,
            directDownload = false,
            versionStatus = VersionStatus.LATEST,
            formatMatches = true
        )

        val resolved = parser.resolveHistoryCandidate(testRequest(packageName = packageName), candidate)

        assertNotNull(resolved)
        assertTrue(resolved!!.directDownload)
        assertEquals("2.0.0", resolved.versionName)
        assertEquals("https://download.apkpure.com/b/APK/$packageName?version=200", resolved.url)
        assertEquals("apk", resolved.fileKind)
        assertEquals(1, resolved.files.size)
        assertEquals(downloadPageUrl, resolved.files[0].referer)
    }
}
