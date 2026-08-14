package dev.rushi.apkdownloadhelper

/** Serves canned HTML/JSON responses keyed by URL so parsers run without the network. */
internal class FakeSourceTextFetcher(
    private val pages: Map<String, String>,
    private val rateLimitedUrls: Set<String> = emptySet()
) : SourceTextFetcher {
    private val requested = mutableListOf<String>()

    override fun fetchText(url: String, referer: String?): String {
        requested += url
        if (url in rateLimitedUrls) throw HttpRateLimitedException("HTTP 429 Too Many Requests")
        return pages[url] ?: error("Unexpected fetch: $url")
    }

    fun requestedUrls(): List<String> = requested.toList()
}

internal class FakeApkPureApi(
    var response: ApkPureUpdateResponse = ApkPureUpdateResponse()
) : ApkPureApi {
    override suspend fun getAppUpdate(
        header: String,
        request: ApkPureUpdateRequest
    ): ApkPureUpdateResponse = response
}

internal class FakeAptoideApi(
    var appByPackage: AptoideApp = AptoideApp(),
    var searchResults: List<AptoideApp> = emptyList(),
    var appById: AptoideApp = AptoideApp()
) : AptoideApi {
    override suspend fun searchApps(request: AptoideSearchRequest): AptoideSearchResponse =
        AptoideSearchResponse(datalist = AptoideDataList(list = searchResults))

    override suspend fun getAppByPackage(packageName: String): AptoideGetAppResponse =
        AptoideGetAppResponse(nodes = AptoideNodes(meta = AptoideMetaNode(data = appByPackage)))

    override suspend fun getAppById(appId: Long): AptoideGetAppResponse =
        AptoideGetAppResponse(nodes = AptoideNodes(meta = AptoideMetaNode(data = appById)))

    override suspend fun listAppVersionsByPackage(
        packageName: String,
        limit: Long
    ): AptoideVersionListResponse = AptoideVersionListResponse()

    override suspend fun listAppVersionsById(appId: Long, limit: Long): AptoideVersionListResponse =
        AptoideVersionListResponse()
}

internal fun testParserContext(
    pages: Map<String, String>,
    rateLimitedUrls: Set<String> = emptySet(),
    apkPureApi: ApkPureApi = FakeApkPureApi(),
    aptoideApi: AptoideApi = FakeAptoideApi()
): SourceParserContext = SourceParserContext(
    fetcher = FakeSourceTextFetcher(pages, rateLimitedUrls),
    apkPureApi = apkPureApi,
    aptoideApi = aptoideApi
)

internal fun testRequest(
    packageName: String = "com.example.app",
    appName: String = "Example App",
    versionName: String? = null,
    versionCode: Long? = null,
    requestedFileType: String? = "APK",
    allowSplitArchive: Boolean = false
): HelperRequest = HelperRequest(
    callerPackage = "test.caller",
    packageName = packageName,
    appName = appName,
    versionName = versionName,
    versionCode = versionCode,
    versionCodes = emptySet(),
    compatibleVersionNames = emptySet(),
    compatibleVersionCodes = emptySet(),
    supportedAbis = listOf("arm64-v8a"),
    requestedFileType = requestedFileType,
    allowSplitArchive = allowSplitArchive,
    stockInstallRequired = false,
    fallbackWebUrl = "",
    sourceHintUrls = emptyList()
)
