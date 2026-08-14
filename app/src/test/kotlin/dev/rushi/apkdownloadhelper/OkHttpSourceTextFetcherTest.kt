package dev.rushi.apkdownloadhelper

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpSourceTextFetcherTest {

    @Test
    fun pacesRequestsToMinGap() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setBody("one"))
        server.enqueue(MockResponse().setBody("two"))
        server.enqueue(MockResponse().setBody("three"))
        val fetcher = OkHttpSourceTextFetcher(OkHttpClient(), minRequestGapMillis = 500)
        val url = server.url("/").toString()

        val start = System.nanoTime()
        fetcher.fetchText(url, null)
        fetcher.fetchText(url, null)
        fetcher.fetchText(url, null)
        val elapsedMillis = (System.nanoTime() - start) / 1_000_000
        server.shutdown()

        // Three fetches with a 500ms min gap => at least two 500ms waits.
        assertTrue("expected pacing, took ${elapsedMillis}ms", elapsedMillis >= 900)
    }

    @Test
    fun surfacesHttp429AsRateLimitedException() {
        val server = MockWebServer()
        server.start()
        server.enqueue(MockResponse().setResponseCode(429))
        val fetcher = OkHttpSourceTextFetcher(OkHttpClient(), minRequestGapMillis = 0)
        val url = server.url("/").toString()

        val result = runCatching { fetcher.fetchText(url, null) }
        server.shutdown()

        assertTrue(result.exceptionOrNull() is HttpRateLimitedException)
    }
}
