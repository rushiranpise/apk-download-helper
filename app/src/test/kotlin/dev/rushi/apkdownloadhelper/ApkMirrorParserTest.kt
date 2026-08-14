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
                         </body></html>""",
                    uploadsUrl2 to
                        """<html><body>
                           <a href="/apk/example-org/example-app/0-9-0-release/">0.9.0</a>
                         </body></html>"""
                )
            )
        )

        val candidates = parser.resolveHistory(testRequest(packageName = packageName))

        assertEquals(3, candidates.size)
        assertEquals(listOf("2.0.0", "1.0.0", "0.9.0"), candidates.map { it.versionName })
        assertEquals("https://www.apkmirror.com/apk/example-org/example-app/2-0-0-release/", candidates[0].url)
        assertEquals("web", candidates[0].fileKind)
        assertTrue(!candidates[0].directDownload)
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
