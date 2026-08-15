package dev.rushi.apkdownloadhelper

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvoziParserTest {

    private val packageName = "com.paget96.batteryguru"

    // Mirrors the Next.js flight payload apkcube serves: escaped quotes live
    // inside the script content verbatim.
    private fun downloaderPage(): String = """
        <!DOCTYPE html><html><head><title>APK Downloader</title></head><body>
        <script>self.__next_f.push([1,"39:[\"R\",\"RL38\",null,{\"as\":\"p\",\"className\":\"truncate font-mono\",\"children\":[\"2.5.0.6\",\" · \",\"\",\"Paget96\"]}]"])</script>
        <script>self.__next_f.push([1,"3a:[\"R\",\"RL3\",null,{\"href\":\"/battery-guru-battery-health/com.paget96.batteryguru/download\",\"className\":\"mt-4 flex\"}]"])</script>
        </body></html>
    """.trimIndent()

    private fun oldVersionsPage(): String = """
        <!DOCTYPE html><html><head><title>Old versions</title></head><body>
        <script>self.__next_f.push([1,"39:[\"R\",\"RL38\",null,{\"versionName\":\"2.5.0.6\"}]"])</script>
        <script>self.__next_f.push([1,"3a:[\"R\",\"RL38\",null,{\"versionName\":\"2.5.0.5\"}]"])</script>
        <a href="/battery-guru-battery-health/com.paget96.batteryguru/download?version=2.5.0.6">2.5.0.6</a>
        <a href="/battery-guru-battery-health/com.paget96.batteryguru/download?version=2.5.0.5">2.5.0.5</a>
        </body></html>
    """.trimIndent()

    private fun parser(pages: Map<String, String>) =
        EvoziParser(testParserContext(pages))

    private val downloaderUrl = "https://apkcube.com/apk-downloader?url=$packageName"
    private val oldVersionsUrl = "https://apkcube.com/battery-guru-battery-health/$packageName/old-versions"

    private fun latestRequest() = testRequest(
        packageName = packageName,
        appName = "Battery Guru",
        versionName = null
    )

    private fun requestedRequest(version: String) = testRequest(
        packageName = packageName,
        appName = "Battery Guru",
        versionName = version
    )

    @Test
    fun latest_findsVersionAndDownloadPage() {
        val result = runBlocking {
            parser(mapOf(downloaderUrl to downloaderPage())).findCandidates(latestRequest(), CandidateOption.LATEST)
        }
        assertEquals(1, result.size)
        val candidate = result.first()
        assertEquals("2.5.0.6", candidate.versionName)
        assertEquals(
            "https://apkcube.com/battery-guru-battery-health/$packageName/download",
            candidate.url
        )
        assertFalse(candidate.directDownload)
        assertTrue(candidate.note.orEmpty().contains("captcha", ignoreCase = true))
        assertEquals(candidate.url, candidate.captchaUrl)
        assertEquals(CandidateOption.LATEST, candidate.option)
    }

    @Test
    fun latest_skipsNavLabelsBeforeVersion() {
        // The real page has `children` arrays for nav labels ("apk-downloader")
        // before the app-version row; the version must still win.
        val page = """
            <script>self.__next_f.push([1,"39:[\"R\",\"RL38\",null,{\"children\":[\"apk-downloader\"]}]"])</script>
            <script>self.__next_f.push([1,"3a:[\"R\",\"RL3\",null,{\"children\":[\"2.5.0.6\",\" · \",\"\",\"Paget96\"]}]"])</script>
            <script>self.__next_f.push([1,"3a:[\"R\",\"RL3\",null,{\"href\":\"/battery-guru-battery-health/com.paget96.batteryguru/download\"}]"])</script>
        """.trimIndent()
        val result = runBlocking {
            parser(mapOf(downloaderUrl to page)).findCandidates(latestRequest(), CandidateOption.LATEST)
        }
        assertEquals(1, result.size)
        assertEquals("2.5.0.6", result.first().versionName)
    }

    @Test
    fun requested_matchesListedVersion() {
        val result = runBlocking {
            parser(
                mapOf(
                    downloaderUrl to downloaderPage(),
                    oldVersionsUrl to oldVersionsPage()
                )
            ).findCandidates(requestedRequest("2.5.0.6"), CandidateOption.REQUESTED)
        }
        assertEquals(1, result.size)
        val candidate = result.first()
        assertEquals("2.5.0.6", candidate.versionName)
        assertEquals(
            "https://apkcube.com/battery-guru-battery-health/$packageName/download?version=2.5.0.6",
            candidate.url
        )
        assertEquals(VersionStatus.REQUESTED, candidate.versionStatus)
        assertFalse(candidate.directDownload)
        assertEquals(candidate.url, candidate.captchaUrl)
    }

    @Test
    fun requested_notListed_returnsEmpty() {
        val result = runBlocking {
            parser(
                mapOf(
                    downloaderUrl to downloaderPage(),
                    oldVersionsUrl to oldVersionsPage()
                )
            ).findCandidates(requestedRequest("9.9.9"), CandidateOption.REQUESTED)
        }
        assertTrue(result.isEmpty())
    }

    @Test
    fun latest_appMissing_throwsNotFound() {
        val error = runCatching {
            runBlocking {
                parser(emptyMap()).findCandidates(latestRequest(), CandidateOption.LATEST)
            }
        }.exceptionOrNull()
        assertTrue("expected SourceAppNotFoundException, got $error", error is SourceAppNotFoundException)
    }

    // ---- blocked downloader services ----

    @Test
    fun mi9_fallbacksAreOpenLinkWithBlockedNote() {
        val parser = Mi9Parser()
        val request = testRequest(packageName = packageName, versionName = "2.5.0.6")
        val requested = parser.requestedFallbackCandidate(request)
        assertEquals(CandidateOption.REQUESTED, requested.option)
        assertFalse(requested.directDownload)
        assertEquals("https://mi9.com/package/$packageName/versions/", requested.url)
        assertEquals(requested.url, requested.captchaUrl)
        assertTrue(requested.note.orEmpty().contains("Cloudflare", ignoreCase = true))
        val latest = parser.latestFallbackCandidate(request)
        assertEquals(CandidateOption.LATEST, latest.option)
        assertFalse(latest.directDownload)
        assertEquals(latest.url, latest.captchaUrl)
        assertEquals("https://mi9.com/package/$packageName/versions/", latest.url)
        assertTrue(latest.note.orEmpty().contains("Cloudflare", ignoreCase = true))
    }

    @Test
    fun mi9_history_offersVersionHistoryBrowseRow() {
        val parser = Mi9Parser()
        val request = testRequest(packageName = packageName, versionName = "2.5.0.6")
        val result = runBlocking { parser.resolveHistory(request) }
        assertEquals(1, result.size)
        val row = result.first()
        assertEquals("Version history", row.versionName)
        assertFalse(row.directDownload)
        assertEquals("https://mi9.com/package/$packageName/versions/", row.url)
        assertEquals(row.url, row.captchaUrl)
        assertTrue(row.note.orEmpty().contains("Cloudflare", ignoreCase = true))
    }

    @Test
    fun pagesDev_fallbacksAreOpenLinkWithBlockedNote() {
        val parser = ApkDownloaderPagesParser()
        val request = testRequest(packageName = packageName, versionName = "2.5.0.6")
        val requested = parser.requestedFallbackCandidate(request)
        assertFalse(requested.directDownload)
        assertEquals("https://apkdownloader.pages.dev/?package=$packageName", requested.url)
        assertEquals(requested.url, requested.captchaUrl)
        assertTrue(requested.note.orEmpty().contains("Cloudflare", ignoreCase = true))
    }
}
