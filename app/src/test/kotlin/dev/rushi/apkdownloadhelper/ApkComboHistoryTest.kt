package dev.rushi.apkdownloadhelper

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkComboHistoryTest {

    private val packageName = "com.example.app"
    private val latestPageUrl = "https://apkcombo.com/search/$packageName/download/apk"
    private val oldVersionsUrl = "https://apkcombo.com/search/$packageName/old-versions/"

    private val oldVersionsHtml = """
        <html><body>
          <a class="ver-item" href="/example-app/$packageName/download/phone-2.0.0-arm64_v8a.apk">2.0.0</a>
          <a class="ver-item" href="/example-app/$packageName/download/phone-1.0.0-arm64_v8a.apk">1.0.0</a>
        </body></html>
    """.trimIndent()

    @Test
    fun resolveHistory_listsVersionsNewestFirst() = runBlocking {
        val parser = ApkComboParser(
            testParserContext(
                pages = mapOf(oldVersionsUrl to oldVersionsHtml)
            )
        )

        val candidates = parser.resolveHistory(testRequest(packageName = packageName))

        assertEquals(2, candidates.size)
        assertEquals(listOf("2.0.0", "1.0.0"), candidates.map { it.versionName })
        assertEquals(
            "https://apkcombo.com/example-app/$packageName/download/phone-2.0.0-arm64_v8a.apk",
            candidates[0].url
        )
        assertEquals("web", candidates[0].fileKind)
        assertTrue(!candidates[0].directDownload)
    }

    @Test
    fun resolveHistoryCandidate_resolvesVersionPageToDirectDownload() = runBlocking {
        val versionPageUrl = "https://apkcombo.com/example-app/$packageName/download/phone-2.0.0-arm64_v8a.apk"
        val checkInUrl = "https://apkcombo.com/checkin"
        val parser = ApkComboParser(
            testParserContext(
                pages = mapOf(
                    versionPageUrl to
                        """<html><body>
                           <a class="variant" href="$versionPageUrl" data-arch="arm64-v8a">arm64-v8a</a>
                         </body></html>""",
                    checkInUrl to "key=abc"
                )
            )
        )
        val candidate = DownloadCandidate(
            source = DownloadSource.APK_COMBO,
            name = "Example App",
            packageName = packageName,
            versionName = "2.0.0",
            versionCode = null,
            url = versionPageUrl,
            fileKind = "web",
            option = CandidateOption.LATEST,
            directDownload = false,
            versionStatus = VersionStatus.LATEST,
            formatMatches = true
        )

        val resolved = parser.resolveHistoryCandidate(testRequest(packageName = packageName), candidate)

        assertNotNull(resolved)
        assertTrue(resolved!!.directDownload)
        assertEquals("apk", resolved.fileKind)
        assertTrue(resolved.url.startsWith(versionPageUrl))
        assertTrue(resolved.url.contains("&key=abc"))
    }
}
