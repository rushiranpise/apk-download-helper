package dev.rushi.apkdownloadhelper

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AptoideHistoryTest {

    private val packageName = "com.example.app"
    private val appPageUrl = "https://com.example.app.aptoide.com/app"
    private val versionsUrl = "https://com.example.app.aptoide.com/versions"

    private val appFixture = AptoideApp(
        id = 1L,
        name = "Example App",
        packageName = packageName,
        file = AptoideFile(
            vername = "2.0.0",
            vercode = "200",
            path = "https://cdn.aptoide.com/app/2.apk"
        ),
        urls = AptoideUrls(
            w = "https://com.example.app.aptoide.com/app",
            m = "https://com.example.app.aptoide.com/app"
        )
    )

    private val versionsHtml = """
        <html><body>
          <script id="__NEXT_DATA__" type="application/json">
            {"props":{"pageProps":{"packageName":"$packageName","versions":[
              {"id": 2, "name": "2.0.0", "vername": "2.0.0", "vercode": 200},
              {"id": 1, "name": "1.0.0", "vername": "1.0.0", "vercode": 100}
            ]}}}
          </script>
        </body></html>
    """.trimIndent()

    private val slugUrl = "https://example-app.en.aptoide.com/app"
    private val slugVersionsUrl = "https://example-app.en.aptoide.com/versions"

    private val slugAppPageHtml = """
        <html><body>
          <script id="__NEXT_DATA__" type="application/json">
            {"props":{"pageProps":{"app":{
              "id": 1, "name": "Example App", "package": "$packageName",
              "file": {"vername": "2.0.0", "vercode": "200", "path": "https://cdn.aptoide.com/app/2.apk"},
              "urls": {"w": "$slugUrl", "m": "$slugUrl"}
            }}}}
          </script>
        </body></html>
    """.trimIndent()
    @Test
    fun resolveHistory_listsVersionsNewestFirst() = runBlocking {
        val api = FakeAptoideApi(appByPackage = appFixture)
        val parser = AptoideParser(
            testParserContext(
                pages = mapOf(versionsUrl to versionsHtml),
                aptoideApi = api
            )
        )

        val candidates = parser.resolveHistory(testRequest(packageName = packageName))

        assertEquals(2, candidates.size)
        assertEquals(listOf("2.0.0", "1.0.0"), candidates.map { it.versionName })
        assertEquals(listOf(200L, 100L), candidates.map { it.versionCode })
        assertEquals("$versionsUrl/2", candidates[0].url)
        assertEquals("apk", candidates[0].fileKind)
        assertTrue(!candidates[0].directDownload)
    }

    @Test
    fun resolveHistory_fallsBackToSlugWebPageWhenApiHasNoApp() = runBlocking {
        val api = FakeAptoideApi()
        val parser = AptoideParser(
            testParserContext(
                pages = mapOf(
                    slugUrl to slugAppPageHtml,
                    slugVersionsUrl to versionsHtml
                ),
                aptoideApi = api
            )
        )

        val candidates = parser.resolveHistory(testRequest(packageName = packageName))

        assertEquals(2, candidates.size)
        assertEquals(listOf("2.0.0", "1.0.0"), candidates.map { it.versionName })
    }

    @Test
    fun resolveHistoryCandidate_resolvesVersionIdToDirectDownload() = runBlocking {
        val api = FakeAptoideApi(appById = appFixture)
        val parser = AptoideParser(testParserContext(pages = emptyMap(), aptoideApi = api))
        val candidate = DownloadCandidate(
            source = DownloadSource.APTOIDE,
            name = "Example App",
            packageName = packageName,
            versionName = "2.0.0",
            versionCode = 200L,
            url = "$versionsUrl/2",
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
        assertEquals("https://cdn.aptoide.com/app/2.apk", resolved.url)
        assertEquals(1, resolved.files.size)
    }
}
