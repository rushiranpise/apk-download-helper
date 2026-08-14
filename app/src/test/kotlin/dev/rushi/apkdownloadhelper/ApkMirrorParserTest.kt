package dev.rushi.apkdownloadhelper

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkMirrorParserTest {

    private val packageName = "com.example.app"
    private val searchUrl =
        "https://www.apkmirror.com/?post_type=app_release&searchtype=app&s=$packageName"
    private val appPageUrl = "https://www.apkmirror.com/apk/example-org/example-app/"
    private val uploadsUrl = "https://www.apkmirror.com/uploads/?appcategory=example-app"
    private val uploadsUrl2 = "https://www.apkmirror.com/uploads/page/2/?appcategory=example-app"
    private val uploadsUrl3 = "https://www.apkmirror.com/uploads/page/3/?appcategory=example-app"
    private val uploadsUrl4 = "https://www.apkmirror.com/uploads/page/4/?appcategory=example-app"

    private fun parserContext(fetcher: SourceTextFetcher): SourceParserContext =
        SourceParserContext(
            fetcher = fetcher,
            apkPureApi = FakeApkPureApi(),
            aptoideApi = FakeAptoideApi()
        )

    private fun apkMirrorPages(
        extraPages: Map<String, String> = emptyMap()
    ): Map<String, String> = mapOf(
        searchUrl to
            """<html><body><a href="/apk/example-org/example-app/">Example App</a></body></html>""",
        appPageUrl to
            """<html><body><span id="$packageName">Example App</span></body></html>"""
    ) + extraPages

    @Test
    fun resolveHistory_extractsReleaseUrlsNewestFirst() = runBlocking {
        val parser = ApkMirrorParser(
            testParserContext(
                pages = mapOf(
                    searchUrl to
                        """<html><body><a href="/apk/example-org/example-app/">Example App</a></body></html>""",
                    appPageUrl to
                        """<html><body><span id="$packageName">Example App</span></body></html>""",
                    uploadsUrl to
                        """<html><body>
                           <a href="/apk/example-org/example-app/2-0-0-release/">2.0.0</a>
                           <a href="/apk/example-org/example-app/1-0-0-release/">1.0.0</a>
                           <a href="/apk/other-dev/other-app/9-9-9-release/">Sidebar Trending</a>
                         </body></html>""",
                    uploadsUrl2 to
                        """<html><body>
                           <a href="/apk/example-org/example-app/0-9-0-release/">0.9.0</a>
                         </body></html>"""
                )
            )
        )

        val candidates = parser.resolveHistory(testRequest(packageName = packageName))

        // The trending sidebar link for another app must not leak into history.
        assertEquals(3, candidates.size)
        assertEquals(listOf("2.0.0", "1.0.0", "0.9.0"), candidates.map { it.versionName })
        assertEquals("https://www.apkmirror.com/apk/example-org/example-app/2-0-0-release/", candidates[0].url)
        assertEquals("web", candidates[0].fileKind)
        assertTrue(!candidates[0].directDownload)
    }

    @Test
    fun resolveHistory_prefersCanonicalListingOverEditionListing() = runBlocking {
        val packageName = "com.zhiliaoapp.musically"
        val searchUrl =
            "https://www.apkmirror.com/?post_type=app_release&searchtype=app&s=$packageName"
        val canonicalPage =
            "https://www.apkmirror.com/apk/tiktok-pte-ltd/tik-tok-including-musical-ly/"
        val amazonPage =
            "https://www.apkmirror.com/apk/tiktok-pte-ltd/tiktok-amazon-appstore-fire-tablet-version/"
        val canonicalUploads =
            "https://www.apkmirror.com/uploads/?appcategory=tik-tok-including-musical-ly"

        val parser = ApkMirrorParser(
            testParserContext(
                pages = mapOf(
                    searchUrl to
                        """<html><body>
                           <a href="/apk/tiktok-pte-ltd/tik-tok-including-musical-ly/">TikTok</a>
                           <a href="/apk/tiktok-pte-ltd/tiktok-amazon-appstore-fire-tablet-version/">TikTok Amazon Appstore</a>
                         </body></html>""",
                    canonicalPage to
                        """<html><body><span id="$packageName">TikTok</span></body></html>""",
                    amazonPage to
                        """<html><body><span id="$packageName">TikTok Amazon</span></body></html>""",
                    canonicalUploads to
                        """<html><body>
                           <a href="/apk/tiktok-pte-ltd/tik-tok-including-musical-ly/46-2-3-release/">46.2.3</a>
                         </body></html>"""
                )
            )
        )

        // The canonical listing and its Amazon Appstore edition share the same
        // package and developer; the canonical one must win so its version
        // stream (which carries the requested releases) is used.
        val candidates = parser.resolveHistory(
            testRequest(packageName = packageName, appName = "TikTok")
        )

        assertEquals(listOf("46.2.3"), candidates.map { it.versionName })
        assertEquals(
            "https://www.apkmirror.com/apk/tiktok-pte-ltd/tik-tok-including-musical-ly/46-2-3-release/",
            candidates.single().url
        )
    }

    @Test
    fun resolveHistory_fallsBackToSearchUrlWhenNoAppPage() = runBlocking {
        val parser = ApkMirrorParser(
            testParserContext(
                pages = mapOf(
                    searchUrl to """<html><body>No results</body></html>"""
                )
            )
        )

        val candidates = parser.resolveHistory(testRequest(packageName = packageName))

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun findCandidates_requested_throwsWhenAppNotListed() = runBlocking {
        val otherAppUrl = "https://www.apkmirror.com/apk/other-dev/other-app/"
        val parser = ApkMirrorParser(
            testParserContext(
                pages = mapOf(
                    searchUrl to
                        """<html><body><a href="/apk/other-dev/other-app/">Other App</a></body></html>""",
                    otherAppUrl to
                        """<html><body><span id="com.other.app">Other App</span></body></html>"""
                )
            )
        )
        val request = testRequest(
            packageName = packageName,
            versionName = "1.0.4",
            versionCode = 303
        )

        val result = runCatching { parser.findCandidates(request, CandidateOption.REQUESTED) }

        assertTrue(result.exceptionOrNull() is SourceAppNotFoundException)
    }

    @Test
    fun findCandidates_latest_throwsWhenAppNotListed() = runBlocking {
        val otherAppUrl = "https://www.apkmirror.com/apk/other-dev/other-app/"
        val parser = ApkMirrorParser(
            testParserContext(
                pages = mapOf(
                    searchUrl to
                        """<html><body><a href="/apk/other-dev/other-app/">Other App</a></body></html>""",
                    otherAppUrl to
                        """<html><body><span id="com.other.app">Other App</span></body></html>"""
                )
            )
        )

        val result = runCatching { parser.findCandidates(testRequest(packageName = packageName), CandidateOption.LATEST) }

        assertTrue(result.exceptionOrNull() is SourceAppNotFoundException)
    }

    @Test
    fun resolveHistory_capsAtThreePages() = runBlocking {
        val fetcher = FakeSourceTextFetcher(
            apkMirrorPages(
                mapOf(
                    uploadsUrl to
                        """<html><body><a href="/apk/example-org/example-app/4-0-0-release/">4.0.0</a></body></html>""",
                    uploadsUrl2 to
                        """<html><body><a href="/apk/example-org/example-app/3-0-0-release/">3.0.0</a></body></html>""",
                    uploadsUrl3 to
                        """<html><body><a href="/apk/example-org/example-app/2-0-0-release/">2.0.0</a></body></html>""",
                    uploadsUrl4 to
                        """<html><body><a href="/apk/example-org/example-app/1-0-0-release/">1.0.0</a></body></html>"""
                )
            )
        )
        val parser = ApkMirrorParser(parserContext(fetcher))

        val candidates = parser.resolveHistory(testRequest(packageName = packageName))

        assertEquals(listOf("4.0.0", "3.0.0", "2.0.0"), candidates.map { it.versionName })
        assertTrue(fetcher.requestedUrls().none { it.contains("uploads/page/4") })
    }

    @Test
    fun resolveHistory_cachesPerPackage() = runBlocking {
        val fetcher = FakeSourceTextFetcher(
            apkMirrorPages(
                mapOf(
                    uploadsUrl to
                        """<html><body><a href="/apk/example-org/example-app/4-0-0-release/">4.0.0</a></body></html>"""
                )
            )
        )
        val parser = ApkMirrorParser(parserContext(fetcher))

        parser.resolveHistory(testRequest(packageName = packageName))
        val requestCountAfterFirst = fetcher.requestedUrls().size
        val cached = parser.resolveHistory(testRequest(packageName = packageName))

        assertEquals(listOf("4.0.0"), cached.map { it.versionName })
        assertEquals(requestCountAfterFirst, fetcher.requestedUrls().size)
    }

    @Test
    fun resolveHistory_abortsOnRateLimit() = runBlocking {
        val fetcher = FakeSourceTextFetcher(
            apkMirrorPages(
                mapOf(
                    uploadsUrl to
                        """<html><body><a href="/apk/example-org/example-app/4-0-0-release/">4.0.0</a></body></html>""",
                    uploadsUrl2 to
                        """<html><body><a href="/apk/example-org/example-app/3-0-0-release/">3.0.0</a></body></html>"""
                )
            ),
            rateLimitedUrls = setOf(uploadsUrl2)
        )
        val parser = ApkMirrorParser(parserContext(fetcher))

        val candidates = parser.resolveHistory(testRequest(packageName = packageName))

        assertEquals(listOf("4.0.0"), candidates.map { it.versionName })
        assertTrue(fetcher.requestedUrls().none { it.contains("uploads/page/3") })
    }

    @Test
    fun findCandidates_requested_noResultsMarkerSkipsCandidateFetches() = runBlocking {
        val fetcher = FakeSourceTextFetcher(
            pages = mapOf(
                searchUrl to
                    """<html><body><p>No results found matching your query</p></body></html>"""
            )
        )
        val parser = ApkMirrorParser(parserContext(fetcher))
        val request = testRequest(
            packageName = packageName,
            versionName = "1.0.4",
            versionCode = 303
        )

        val result = runCatching { parser.findCandidates(request, CandidateOption.REQUESTED) }

        assertTrue(result.exceptionOrNull() is SourceAppNotFoundException)
        assertEquals(listOf(searchUrl), fetcher.requestedUrls())
    }

    @Test
    fun resolveHistoryCandidate_resolvesDirectDownloadFromReleasePage() = runBlocking {
        val releaseUrl = "https://www.apkmirror.com/apk/example-org/example-app/2-0-0-release/"
        val variantUrl = "$releaseUrl"
        val downloadPageUrl = "https://www.apkmirror.com/apk/example-org/example-app/2-0-0-release/download/"
        val finalUrl = "https://www.apkmirror.com/download.php?id=123&key=abc"
        val fetcher = FakeSourceTextFetcher(
            pages = mapOf(
                releaseUrl to
                    """<html><body>
                       <a class="downloadButton" href="$downloadPageUrl">Download</a>
                     </body></html>""",
                downloadPageUrl to
                    """<html><body><a id="download-link" href="$finalUrl"></a></body></html>"""
            )
        )
        val parser = ApkMirrorParser(parserContext(fetcher))
        val historyCandidate = DownloadCandidate(
            source = DownloadSource.APK_MIRROR,
            name = "Example App",
            packageName = packageName,
            versionName = "2.0.0",
            versionCode = null,
            url = releaseUrl,
            fileKind = "web",
            option = CandidateOption.LATEST,
            directDownload = false,
            versionStatus = VersionStatus.LATEST,
            formatMatches = true
        )

        val resolved = parser.resolveHistoryCandidate(
            request = testRequest(packageName = packageName),
            candidate = historyCandidate
        )

        assertTrue(resolved != null)
        assertTrue(resolved!!.directDownload)
        assertEquals(finalUrl, resolved.url)
        assertEquals("2.0.0", resolved.versionName)
        assertEquals(1, resolved.files.size)
    }

    @Test
    fun resolveHistoryCandidate_returnsNullWhenNoDirectDownload() = runBlocking {
        val releaseUrl = "https://www.apkmirror.com/apk/example-org/example-app/2-0-0-release/"
        val fetcher = FakeSourceTextFetcher(
            pages = mapOf(
                releaseUrl to """<html><body>No download buttons here.</body></html>"""
            )
        )
        val parser = ApkMirrorParser(parserContext(fetcher))
        val historyCandidate = DownloadCandidate(
            source = DownloadSource.APK_MIRROR,
            name = "Example App",
            packageName = packageName,
            versionName = "2.0.0",
            versionCode = null,
            url = releaseUrl,
            fileKind = "web",
            option = CandidateOption.LATEST,
            directDownload = false,
            versionStatus = VersionStatus.LATEST,
            formatMatches = true
        )

        val resolved = parser.resolveHistoryCandidate(
            request = testRequest(packageName = packageName),
            candidate = historyCandidate
        )

        // No direct download: the UI flips this row to "Open link".
        assertTrue(resolved == null)
    }

    @Test
    fun compareVersionNames_ordersCorrectly() {
        assertTrue(compareVersionNames("10.0.0", "9.9.9") > 0)
        assertTrue(compareVersionNames("2.0.1", "2.0.0") > 0)
        assertEquals(0, compareVersionNames("2.0.0", "2.0.0"))
        // Note: the suffix number is included in the comparison, so "1.0.0-rc1" > "1.0.0".
        assertTrue(compareVersionNames("1.0.0-rc1", "1.0.0") > 0)
        assertTrue(compareVersionNames(null, "1.0.0") < 0)
        assertTrue(compareVersionNames("1.0.0", null) > 0)
    }
}
