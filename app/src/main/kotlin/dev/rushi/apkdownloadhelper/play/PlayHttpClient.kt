package dev.rushi.apkdownloadhelper.play

import com.aurora.gplayapi.data.models.PlayResponse
import com.aurora.gplayapi.network.IHttpClient
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Cache
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class PlayHttpClient(cache: Cache) : IHttpClient {
    private val _responseCode = MutableStateFlow(100)
    override val responseCode: StateFlow<Int> get() = _responseCode.asStateFlow()

    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .dns(dev.rushi.apkdownloadhelper.AdGuardDns)
        .cache(cache)
        .addInterceptor(dev.rushi.apkdownloadhelper.httpLoggingInterceptor("Play"))
        .build()

    override fun post(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>
    ): PlayResponse {
        val request = Request.Builder()
            .url(buildUrl(url, params))
            .headers(headers.toHeaders())
            .post("".toRequestBody(null))
            .build()
        return processRequest(request)
    }

    override fun postAuth(url: String, body: ByteArray): PlayResponse {
        val requestBody = body.toRequestBody("application/json".toMediaType(), 0, body.size)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "com.aurora.store-4.4.2-56")
            .post(requestBody)
            .build()
        return processRequest(request)
    }

    override fun post(url: String, headers: Map<String, String>, body: ByteArray): PlayResponse {
        val requestBody = body.toRequestBody("application/x-protobuf".toMediaType(), 0, body.size)
        val request = Request.Builder()
            .url(url)
            .headers(headers.toHeaders())
            .post(requestBody)
            .build()
        return processRequest(request)
    }

    override fun get(url: String, headers: Map<String, String>): PlayResponse =
        get(url, headers, emptyMap())

    override fun get(
        url: String,
        headers: Map<String, String>,
        params: Map<String, String>
    ): PlayResponse {
        val request = Request.Builder()
            .url(buildUrl(url, params))
            .headers(headers.toHeaders())
            .get()
            .build()
        return processRequest(request)
    }

    override fun getAuth(url: String): PlayResponse {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "com.aurora.store-4.4.2-56")
            .get()
            .build()
        return processRequest(request)
    }

    override fun get(
        url: String,
        headers: Map<String, String>,
        paramString: String
    ): PlayResponse {
        val request = Request.Builder()
            .url(url + paramString)
            .headers(headers.toHeaders())
            .get()
            .build()
        return processRequest(request)
    }

    @Throws(IOException::class)
    fun post(url: String, headers: Map<String, String>, requestBody: RequestBody): PlayResponse {
        val request = Request.Builder()
            .url(url)
            .headers(headers.toHeaders())
            .post(requestBody)
            .build()
        return processRequest(request)
    }

    private fun processRequest(request: Request): PlayResponse =
        buildPlayResponse(client.newCall(request).execute())

    private fun buildUrl(url: String, params: Map<String, String>): HttpUrl {
        val builder = url.toHttpUrl().newBuilder()
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    private fun buildPlayResponse(response: Response): PlayResponse =
        response.use {
            PlayResponse(
                isSuccessful = it.isSuccessful,
                code = it.code,
                responseBytes = it.body.bytes(),
                errorString = if (!it.isSuccessful) it.message else ""
            ).also {
                _responseCode.value = response.code
            }
        }
}
