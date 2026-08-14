package dev.rushi.apkdownloadhelper

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkComboParserTest {

    private val packageName = "com.example.app"
    private val pageUrl = "https://apkcombo.com/search/$packageName/download/apk"
    private val checkInUrl = "https://apkcombo.com/checkin"
    private val variantHref =
        "https://apkcombo.com/example-app/$packageName/download/apk/phone-9.2.1-arm64_v8a.apk"

    private val downloadPageHtml = """
        <html><head>
          <script type="application/ld+json">{"softwareVersion": "9.2.1"}</script>
        </head><body>
          <a class="variant" href="$variantHref" data-arch="arm64-v8a" title="arm64-v8a">arm64-v8a</a>
        </body></html>
    """.trimIndent()

    @Test
    fun findCandidates_latest_extractsDirectVariant() = runBlocking {
        val parser = ApkComboParser(
            testParserContext(
                pages = mapOf(
                    pageUrl to downloadPageHtml,
                    checkInUrl to "key=abc"
                )
            )
        )

        val candidates = parser.findCandidates(
            request = testRequest(packageName = packageName),
            option = CandidateOption.LATEST
        )

        assertEquals(1, candidates.size)
        val candidate = candidates[0]
        assertTrue(candidate.directDownload)
        assertEquals("9.2.1", candidate.versionName)
        assertEquals("apk", candidate.fileKind)
        assertEquals(DownloadSource.APK_COMBO, candidate.source)
        assertTrue(candidate.url.startsWith(variantHref))
        assertTrue(candidate.url.contains("&key=abc"))
        assertEquals("arm64-v8a", candidate.variantLabel)
        assertEquals(1, candidate.files.size)
        assertEquals(pageUrl, candidate.files[0].referer)
    }

    @Test
    fun findCandidates_skipsVariantWhenFormatRejected() = runBlocking {
        val parser = ApkComboParser(
            testParserContext(
                pages = mapOf(
                    pageUrl to downloadPageHtml,
                    checkInUrl to "key=abc"
                )
            )
        )

        // XAPK-only request: the APK variant must be filtered out.
        val candidates = parser.findCandidates(
            request = testRequest(
                packageName = packageName,
                requestedFileType = "XAPK",
                allowSplitArchive = true
            ),
            option = CandidateOption.LATEST
        )

        assertTrue(candidates.isEmpty())
    }
}
