# APK Download Helper

Standalone Android helper for downloading original APK files requested by Morphe Manager.

This app is intentionally separate from Morphe Manager. Manager sends a package/version request
through an Android intent, the helper searches direct-download sources, downloads a matching file
when available, and returns a readable `content://` URI to Manager.

## Current scope

- Handles `app.morphe.manager.action.DOWNLOAD_ORIGINAL_APK`.
- Reads package name, display name, requested version, compatible versions/build codes, ABI hints,
  file type hints, and fallback web URL from intent extras.
- Uses APKMirror, Uptodown, APKPure, APKCombo, Aptoide, Aurora, and Play.
- Shows requested version/format candidates first, then latest available candidates separately.
- Searches supported direct-download sources and keeps manual web links available as fallback.
- Returns the downloaded APK/XAPK/APKS file to the caller with temporary read permission.
- Opens the official Play Store listing separately from Aurora's Play-backed download flow.

## Intent contract

Request action:

```text
app.morphe.manager.action.DOWNLOAD_ORIGINAL_APK
```

Important request extras:

```text
app.morphe.manager.extra.PACKAGE_NAME
app.morphe.manager.extra.APP_NAME
app.morphe.manager.extra.VERSION_NAME
app.morphe.manager.extra.COMPATIBLE_VERSION_NAMES
app.morphe.manager.extra.COMPATIBLE_VERSION_CODES
app.morphe.manager.extra.SUPPORTED_ABIS
app.morphe.manager.extra.REQUESTED_FILE_TYPE
app.morphe.manager.extra.ALLOW_SPLIT_ARCHIVE
app.morphe.manager.extra.INSTALL_STOCK_AFTER_DOWNLOAD
app.morphe.manager.extra.FALLBACK_WEB_URL
```

Result:

- `Activity.RESULT_OK`
- `Intent.data` is the downloaded file URI
- `Intent.FLAG_GRANT_READ_URI_PERMISSION` is granted to the caller

## Credits

Provider research and API shape were informed by APKUpdater:

```text
https://github.com/rumboalla/apkupdater
```

Keep attribution and license compatibility in mind before publishing this helper.
