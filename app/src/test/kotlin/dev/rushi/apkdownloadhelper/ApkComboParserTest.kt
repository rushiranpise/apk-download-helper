package dev.rushi.apkdownloadhelper

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private val obfuscatedVariantPageHtml = """
        <html><head>
          <script type="application/ld+json">{"softwareVersion": "9.2.1"}</script>
        </head><body>
          <a href="V0ZabGNtZHZaMjQ9P2tleT1hYmNkZWY=" class="variant" rel="nofollow noreferrer">
            <div class="info">
              <div class="header"><span class="vername">App 9.2.1</span><span class="vtype"><span class="type-xapk">XAPK</span></span></div>
            </div>
          </a>
        </body></html>
    """.trimIndent()

    private val gatedPageHtml = """
        <html><head>
          <script>
            aptcha.execute("sitekey", {action: "app_download"}).then(function (token) {
              fetchData(window.location.href.split('?')[0] + "?token=" + token);
            });
          </script>
        </head><body>
          <div class="widget seo-widget">No download rows here.</div>
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
        // No Referer: APKCombo's /d links redirect to download.pureapk.com, which
        // bounces requests carrying an apkcombo.com Referer to an HTML page.
        assertNull(candidate.files[0].referer)
    }

    @Test
    fun findCandidates_latest_usesVtypeBadgeWhenHrefIsObfuscated() = runBlocking {
        val parser = ApkComboParser(
            testParserContext(
                pages = mapOf(
                    pageUrl to obfuscatedVariantPageHtml,
                    checkInUrl to "key=abc"
                )
            )
        )

        // XAPK-only request: without the .vtype badge, the obfuscated href would
        // resolve to "apk" and the variant would be rejected.
        val candidates = parser.findCandidates(
            request = testRequest(
                packageName = packageName,
                requestedFileType = "XAPK",
                allowSplitArchive = true
            ),
            option = CandidateOption.LATEST
        )

        assertEquals(1, candidates.size)
        val candidate = candidates[0]
        assertTrue(candidate.directDownload)
        assertEquals("xapk", candidate.fileKind)
        assertTrue(candidate.url.contains("V0ZabGNtZHZaMjQ9P2tleT1hYmNkZWY="))
    }

    @Test
    fun findCandidates_latest_captchaGateReturnsManualFallback() = runBlocking {
        val parser = ApkComboParser(
            testParserContext(
                pages = mapOf(
                    pageUrl to gatedPageHtml,
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
        assertTrue(!candidate.directDownload)
        assertEquals("web", candidate.fileKind)
        assertEquals(pageUrl, candidate.url)
        assertTrue(candidate.note.orEmpty().contains("captcha", ignoreCase = true))
    }

    @Test
    fun findCandidates_offersMismatchedFormatInsteadOfEmpty() = runBlocking {
        val parser = ApkComboParser(
            testParserContext(
                pages = mapOf(
                    pageUrl to downloadPageHtml,
                    checkInUrl to "key=abc"
                )
            )
        )

        // XAPK-only request against an APK variant: no longer dropped silently.
        // The variant is offered with formatMatches=false so the UI can flag the
        // mismatch, matching how APKMirror/APKPure handle it.
        val candidates = parser.findCandidates(
            request = testRequest(
                packageName = packageName,
                requestedFileType = "XAPK",
                allowSplitArchive = true
            ),
            option = CandidateOption.LATEST
        )

        assertEquals(1, candidates.size)
        val candidate = candidates[0]
        assertTrue(candidate.directDownload)
        assertTrue(!candidate.formatMatches)
        assertEquals("apk", candidate.fileKind)
    }

    @Test
    fun findCandidates_prefersMatchingFormatOverMismatch() = runBlocking {
        // Page offers both an APK variant (matches) and an XAPK variant (mismatch
        // for an APK-only request). Only the matching one should be returned.
        val mixedPageHtml = """
            <html><head>
              <script type="application/ld+json">{"softwareVersion": "9.2.1"}</script>
            </head><body>
              <a class="variant" href="$variantHref" data-arch="arm64-v8a" title="arm64-v8a">arm64-v8a</a>
              <a href="V0ZabGNtZHZaMjQ9P2tleT1hYmNkZWY=" class="variant" rel="nofollow noreferrer">
                <div class="info">
                  <div class="header"><span class="vername">App 9.2.1</span><span class="vtype"><span class="type-xapk">XAPK</span></span></div>
                </div>
              </a>
            </body></html>
        """.trimIndent()
        val parser = ApkComboParser(
            testParserContext(
                pages = mapOf(
                    pageUrl to mixedPageHtml,
                    checkInUrl to "key=abc"
                )
            )
        )

        val candidates = parser.findCandidates(
            request = testRequest(packageName = packageName),
            option = CandidateOption.LATEST
        )

        assertEquals(1, candidates.size)
        assertTrue(candidates[0].formatMatches)
        assertEquals("apk", candidates[0].fileKind)
    }
}
