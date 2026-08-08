# APK Download Helper

<p align="center">
  <img src="docs/logo.png" alt="APK Download Helper for Morphe" width="180" />
</p>

APK Download Helper is a standalone Android helper app for finding and returning original APK files requested by Morphe Manager.

The helper receives a package/version request through an Android intent, lets the user choose a supported APK source, downloads a matching file when direct download is available, and returns a readable `content://` URI back to the caller.

This project is independent from the APK source websites listed below. Source availability, package versions, download formats, and region/device access can change at any time.

## Features

- Handles `app.morphe.manager.action.DOWNLOAD_ORIGINAL_APK` requests.
- Shows the requested app name, package name, version, version code, format, and ABI hints.
- Supports manual source links plus on-demand recommended/latest resolution.
- Returns APK, APKS, APKM, or XAPK files when the selected source provides a compatible direct download.
- Validates downloaded files before returning them to Morphe Manager.
- Falls back to web/manual flows when direct download is not available.
- Keeps Play Store listing opens separate from Aurora's Play-backed download flow.
- Includes Helper settings for download location, network access, and temporary APK cleanup.

## Supported Sources

- APKMirror
- Uptodown
- APKPure
- APKCombo
- Aptoide
- Aurora / Google Play
- Play Store listing

Not every source can provide every requested version or file format. Some sources only expose latest versions, some require manual web interaction, and some split formats may not match the patch request.

## Helper Settings

The helper has its own settings screen:

- **Download location**: use temporary hand-off cache, or keep a validated copy in `Downloads/APK Download Helper`.
- **Network access**: allow Wi-Fi only, mobile data only, or both Wi-Fi and mobile data.
- **Temporary cleanup**: remove staged APKs after Morphe has had time to copy them, and clean old cache files on launch.

Temporary hand-off is the default because Morphe Manager copies the returned APK URI into its own private workspace before patching.

## Release Signing

Release APKs must be signed with a private release certificate. Debug signing is only for local test builds and must not be used for public releases.

GitHub Actions expects these repository secrets:

- `HELPER_RELEASE_KEYSTORE_BASE64`: base64-encoded release keystore
- `HELPER_RELEASE_STORE_PASSWORD`
- `HELPER_RELEASE_KEY_ALIAS`
- `HELPER_RELEASE_KEY_PASSWORD`

For local release builds, place the same values in `local.properties` or pass them as Gradle properties/environment variables. Do not commit keystores or signing passwords.

## Source Audit Script

Use `tools/audit_helper_sources.py` to test source availability for every app declared in a Morphe `Constants.kt` file without launching the Android app:

```powershell
python tools\audit_helper_sources.py --output reports\source-audit.csv
```

Useful targeted checks:

```powershell
python tools\audit_helper_sources.py --package club.boxbox.android --sources apkpure,aptoide,apkcombo
python tools\audit_helper_sources.py --limit 20 --sources apkmirror,uptodown,apkpure,apkcombo,aptoide
```

The CSV/JSON output reports whether each source matched the requested version, only found latest, found the wrong file format, or failed to resolve the package.

## Intent Contract

Request action:

```text
app.morphe.manager.action.DOWNLOAD_ORIGINAL_APK
```

Important request extras:

```text
app.morphe.manager.extra.CALLER_PACKAGE
app.morphe.manager.extra.PROTOCOL_VERSION
app.morphe.manager.extra.PACKAGE_NAME
app.morphe.manager.extra.APP_NAME
app.morphe.manager.extra.VERSION_NAME
app.morphe.manager.extra.VERSION_CODES
app.morphe.manager.extra.COMPATIBLE_VERSION_NAMES
app.morphe.manager.extra.SUPPORTED_ABIS
app.morphe.manager.extra.FILE_TYPE
app.morphe.manager.extra.ALLOW_SPLIT_ARCHIVE
app.morphe.manager.extra.STOCK_INSTALL_REQUIRED
app.morphe.manager.extra.FALLBACK_WEB_URL
```

The helper also accepts older local draft keys for compatibility, including `REQUESTED_FILE_TYPE`, `INSTALL_STOCK_AFTER_DOWNLOAD`, `VERSION_CODE`, `COMPATIBLE_VERSION_CODES`, and `SOURCE_HINT_URLS`.

Successful result:

- `Activity.RESULT_OK`
- `Intent.data` points to the downloaded file URI
- `Intent.FLAG_GRANT_READ_URI_PERMISSION` is granted to the caller

## Credits

- [APKUpdater](https://github.com/rumboalla/apkupdater)
- [Aurora Store](https://gitlab.com/AuroraOSS/AuroraStore)
- Public web pages and APIs exposed by APKMirror, Uptodown, APKPure, APKCombo, and Aptoide

This helper does not claim ownership of source website data, packages, trademarks, or services.

## Support APK Sources

APK hosting and indexing costs money. If this helper saves you time, consider supporting the services it relies on:

- [APKMirror](https://www.apkmirror.com/premium/)
- [Uptodown](https://en.uptodown.com/turbo)
- [APKPure](https://apkpure.com/premium)
- [APKCombo](https://apkcombo.com/premium/)
- [Aptoide](https://en.aptoide.com/premium)
- [Aurora Store](https://gitlab.com/AuroraOSS/AuroraStore/#donations)

## License

APK Download Helper is licensed under the GNU General Public License v3.0. See [LICENSE](LICENSE).

## Notes

- Only download apps you are allowed to access.
- This helper does not bypass paid apps, license checks, account restrictions, or DRM.
- Always verify downloaded files before installing them on a device you care about.
- Source matching is best-effort because APK providers can change their sites and APIs without notice.
