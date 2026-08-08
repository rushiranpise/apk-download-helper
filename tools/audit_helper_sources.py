#!/usr/bin/env python3
"""
Audit APK source availability for Morphe compatibility constants.

This is a lightweight headless companion to APK Download Helper. It parses a
Kotlin Constants.kt file, checks each requested package/version against the APK
sources used by the helper, and writes CSV/JSON reports.

It does not download APKs by default. It checks source pages/APIs for package
resolution, requested version matches, latest version hints, file format hints,
and failure reasons.
"""

from __future__ import annotations

import argparse
import ast
import csv
import hashlib
import html
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Callable, Iterable


DEFAULT_CONSTANTS = (
    r"C:\Users\rushi\Downloads\morpheai\morphe-patches-wip\patches\src\main"
    r"\kotlin\app\template\patches\shared\Constants.kt"
)
DEFAULT_SOURCES = ("apkmirror", "uptodown", "apkpure", "apkcombo", "aptoide", "play")
USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 15; APK Download Helper Audit) "
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
)


@dataclass(frozen=True)
class AppTarget:
    version: str | None
    version_code: int | None


@dataclass(frozen=True)
class AppRequest:
    const_name: str
    name: str
    package_name: str
    apk_file_type: str
    target: AppTarget

    @property
    def requested_format(self) -> str:
        return self.apk_file_type.lower()

    @property
    def requested_version_label(self) -> str:
        parts = []
        if self.target.version:
            parts.append(self.target.version)
        if self.target.version_code:
            parts.append(f"code {self.target.version_code}")
        return ", ".join(parts) if parts else "any"


@dataclass
class SourceResult:
    app_name: str
    package_name: str
    requested_version: str | None
    requested_version_code: int | None
    requested_format: str
    source: str
    status: str
    package_found: bool
    requested_match: bool
    latest_version: str | None = None
    found_version: str | None = None
    found_version_code: int | None = None
    found_format: str | None = None
    format_match: bool | None = None
    direct_download_hint: bool = False
    url: str | None = None
    note: str = ""
    elapsed_ms: int = 0


@dataclass
class HttpResponse:
    url: str
    status: int
    text: str
    from_cache: bool = False


class HttpClient:
    def __init__(self, cache_dir: Path | None, timeout: float, delay: float) -> None:
        self.cache_dir = cache_dir
        self.timeout = timeout
        self.delay = delay
        self.last_request_at = 0.0
        if cache_dir:
            cache_dir.mkdir(parents=True, exist_ok=True)

    def get(self, url: str, *, referer: str | None = None) -> HttpResponse:
        return self._request("GET", url, None, referer)

    def post_json(self, url: str, payload: dict[str, Any], *, referer: str | None = None) -> HttpResponse:
        body = json.dumps(payload).encode("utf-8")
        return self._request("POST", url, body, referer)

    def _request(
        self,
        method: str,
        url: str,
        body: bytes | None,
        referer: str | None,
    ) -> HttpResponse:
        cache_key = self._cache_key(method, url, body)
        cache_file = self.cache_dir / f"{cache_key}.json" if self.cache_dir else None
        if cache_file and cache_file.exists():
            try:
                cached = json.loads(cache_file.read_text(encoding="utf-8"))
                return HttpResponse(url=cached["url"], status=cached["status"], text=cached["text"], from_cache=True)
            except Exception:
                cache_file.unlink(missing_ok=True)

        now = time.monotonic()
        wait = self.delay - (now - self.last_request_at)
        if wait > 0:
            time.sleep(wait)

        headers = {
            "User-Agent": USER_AGENT,
            "Accept": "text/html,application/xhtml+xml,application/xml,application/json;q=0.9,*/*;q=0.8",
        }
        if body is not None:
            headers["Content-Type"] = "application/json"
        if referer:
            headers["Referer"] = referer

        request = urllib.request.Request(url, data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read()
                final_url = response.geturl()
                status = response.status
        except urllib.error.HTTPError as error:
            raw = error.read()
            final_url = error.geturl()
            status = error.code

        self.last_request_at = time.monotonic()
        text = raw.decode("utf-8", errors="replace")
        result = HttpResponse(url=final_url, status=status, text=text)
        if cache_file and status < 500:
            cache_file.write_text(json.dumps(asdict(result), ensure_ascii=False), encoding="utf-8")
        return result

    @staticmethod
    def _cache_key(method: str, url: str, body: bytes | None) -> str:
        digest = hashlib.sha256()
        digest.update(method.encode("utf-8"))
        digest.update(b"\0")
        digest.update(url.encode("utf-8"))
        digest.update(b"\0")
        if body:
            digest.update(body)
        return digest.hexdigest()


def parse_constants(path: Path) -> list[AppRequest]:
    text = path.read_text(encoding="utf-8")
    requests: list[AppRequest] = []

    for match in re.finditer(r"\bval\s+([A-Za-z0-9_]+_COMPATIBILITY)\s*=\s*Compatibility\s*\(", text):
        const_name = match.group(1)
        body_start = match.end() - 1
        body_end = find_matching_paren(text, body_start)
        if body_end is None:
            continue

        body = text[body_start + 1 : body_end]
        name = extract_named_string(body, "name") or const_name.removesuffix("_COMPATIBILITY").replace("_", " ").title()
        package_name = extract_named_string(body, "packageName")
        if not package_name:
            continue

        apk_file_type = extract_apk_file_type(body)
        targets = extract_targets(body)
        for target in targets or [AppTarget(version=None, version_code=None)]:
            requests.append(
                AppRequest(
                    const_name=const_name,
                    name=name,
                    package_name=package_name,
                    apk_file_type=apk_file_type,
                    target=target,
                )
            )

    return dedupe_requests(requests)


def find_matching_paren(text: str, open_index: int) -> int | None:
    depth = 0
    quote: str | None = None
    escaped = False

    for index in range(open_index, len(text)):
        char = text[index]
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = None
            continue

        if char in ("'", '"'):
            quote = char
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return index

    return None


def extract_named_string(text: str, name: str) -> str | None:
    match = re.search(rf"\b{name}\s*=\s*(null|\"(?:\\.|[^\"\\])*\")", text)
    if not match or match.group(1) == "null":
        return None
    try:
        return ast.literal_eval(match.group(1))
    except Exception:
        return match.group(1).strip('"')


def extract_named_int(text: str, name: str) -> int | None:
    match = re.search(rf"\b{name}\s*=\s*(-?\d+)", text)
    return int(match.group(1)) if match else None


def extract_apk_file_type(text: str) -> str:
    match = re.search(r"\bapkFileType\s*=\s*ApkFileType\.([A-Za-z0-9_]+)", text)
    return match.group(1).upper() if match else "APK"


def extract_targets(text: str) -> list[AppTarget]:
    targets: list[AppTarget] = []
    for match in re.finditer(r"\bAppTarget\s*\(", text):
        start = match.end() - 1
        end = find_matching_paren(text, start)
        if end is None:
            continue
        body = text[start + 1 : end]
        targets.append(
            AppTarget(
                version=extract_named_string(body, "version"),
                version_code=extract_named_int(body, "versionCode"),
            )
        )
    return targets


def dedupe_requests(requests: Iterable[AppRequest]) -> list[AppRequest]:
    seen: set[tuple[str, str | None, int | None, str]] = set()
    result: list[AppRequest] = []
    for request in requests:
        key = (
            request.package_name,
            request.target.version,
            request.target.version_code,
            request.apk_file_type,
        )
        if key in seen:
            continue
        seen.add(key)
        result.append(request)
    return result


def audit_apkpure(client: HttpClient, request: AppRequest) -> SourceResult:
    url = f"https://apkpure.com/apk-info/{urllib.parse.quote(request.package_name)}"
    response = client.get(url)
    if response.status >= 400:
        status = "not_found" if response.status == 404 else "error"
        return make_result(request, "apkpure", status, url=response.url, note=f"HTTP {response.status}")

    page = response.text
    attrs = [
        parse_attrs(tag)
        for tag in re.findall(r"<[^>]*\bversion-item\b[^>]*>", page, flags=re.I)
    ]
    attrs = [
        item
        for item in attrs
        if item.get("data-dt-package_name", "").lower() == request.package_name.lower()
    ]
    canonical = extract_canonical(page)
    package_found = bool(attrs) or canonical.rstrip("/").endswith("/" + request.package_name)
    versions = [
        (
            item.get("data-dt-version") or None,
            to_int(item.get("data-dt-version_code")),
            " ".join(re.findall(r"<span[^>]*class=[\"'][^\"']*tag[^\"']*[\"'][^>]*>(.*?)</span>", page, re.I)),
        )
        for item in attrs
    ]
    latest = first_non_empty([version for version, _, _ in versions]) or source_version_from_text(page)
    matched = first_match(request, versions)
    found_format = infer_format(page)
    status = classify(package_found, bool(matched), latest, request)
    return make_result(
        request,
        "apkpure",
        status,
        package_found=package_found,
        requested_match=bool(matched),
        latest_version=latest,
        found_version=matched[0] if matched else None,
        found_version_code=matched[1] if matched else None,
        found_format=found_format,
        url=canonical or response.url,
        note="version list parsed" if attrs else "no version items found",
    )


def audit_aptoide(client: HttpClient, request: AppRequest) -> SourceResult:
    package = urllib.parse.quote(request.package_name)
    latest_url = f"https://ws75.aptoide.com/api/7/getApp?package_name={package}"
    versions_url = f"https://ws75.aptoide.com/api/7/listAppVersions?package_name={package}"

    latest_data = json_response(client.get(latest_url))
    versions_data = json_response(client.get(versions_url))
    latest_app = (((latest_data.get("nodes") or {}).get("meta") or {}).get("data") or {})
    latest_file = latest_app.get("file") or {}
    latest_package = latest_app.get("package") or latest_app.get("packageName") or ""
    version_rows = versions_data.get("list") or []

    package_found = latest_package == request.package_name or any(
        (row.get("package") or row.get("packageName")) == request.package_name for row in version_rows
    )
    versions = []
    for row in version_rows:
        if (row.get("package") or row.get("packageName")) != request.package_name:
            continue
        file_info = row.get("file") or {}
        versions.append((file_info.get("vername") or None, to_int(file_info.get("vercode")), "apk"))

    latest_version = latest_file.get("vername") or first_non_empty([version for version, _, _ in versions])
    latest_code = to_int(latest_file.get("vercode"))
    matched = first_match(request, versions)
    status = classify(package_found, bool(matched), latest_version, request)
    direct = bool(latest_file.get("path") or latest_file.get("path_alt") or latest_file.get("pathAlt"))

    return make_result(
        request,
        "aptoide",
        status,
        package_found=package_found,
        requested_match=bool(matched),
        latest_version=latest_version,
        found_version=matched[0] if matched else (latest_version if request_matches(request, latest_version, latest_code) else None),
        found_version_code=matched[1] if matched else (latest_code if request_matches(request, latest_version, latest_code) else None),
        found_format="apk" if package_found else None,
        direct_download_hint=direct if package_found else False,
        url=(latest_app.get("urls") or {}).get("w") or f"https://en.aptoide.com/search?query={package}",
        note=f"{len(versions)} version rows",
    )


def audit_apkcombo(client: HttpClient, request: AppRequest) -> SourceResult:
    suffixes = wanted_suffixes(request)
    matched_page: HttpResponse | None = None
    matched_version: str | None = None
    matched_code: int | None = None
    found_format: str | None = None
    note_parts: list[str] = []

    if request.target.version:
        for suffix in suffixes:
            version = urllib.parse.quote(request.target.version, safe="")
            url = f"https://apkcombo.com/search/{request.package_name}/download/phone-{version}-{suffix}"
            response = client.get(url)
            if response.status >= 400:
                note_parts.append(f"{suffix}: HTTP {response.status}")
                continue
            if not page_mentions_package(response.text, response.url, request.package_name):
                note_parts.append(f"{suffix}: package mismatch")
                continue
            version_name = apkcombo_version(response.text, response.url) or request.target.version
            version_code = apkcombo_version_code(response.text)
            if request_matches(request, version_name, version_code):
                matched_page = response
                matched_version = version_name
                matched_code = version_code
                found_format = suffix
                break

    latest_url = f"https://apkcombo.com/search/{request.package_name}/download/apk"
    latest_response = matched_page or client.get(latest_url)
    if latest_response.status >= 400:
        status = "not_found" if latest_response.status == 404 else "error"
        return make_result(request, "apkcombo", status, url=latest_response.url, note=f"HTTP {latest_response.status}")

    package_found = page_mentions_package(latest_response.text, latest_response.url, request.package_name)
    latest_version = apkcombo_version(latest_response.text, latest_response.url)
    direct = "class=\"variant\"" in latest_response.text or "class='variant'" in latest_response.text
    status = classify(package_found, matched_page is not None, latest_version, request)
    return make_result(
        request,
        "apkcombo",
        status,
        package_found=package_found,
        requested_match=matched_page is not None,
        latest_version=latest_version,
        found_version=matched_version,
        found_version_code=matched_code,
        found_format=found_format or infer_format(latest_response.text),
        direct_download_hint=direct,
        url=matched_page.url if matched_page else latest_response.url,
        note="; ".join(note_parts[:3]),
    )


def audit_uptodown(client: HttpClient, request: AppRequest, max_candidates: int, version_pages: int) -> SourceResult:
    search_url = f"https://en.uptodown.com/android/search?query={urllib.parse.quote(request.package_name)}"
    search_response = client.get(search_url)
    detail_urls = re.findall(r"https://[a-z0-9-]+\.en\.uptodown\.com/android/?", search_response.text, flags=re.I)
    detail_urls = list(dict.fromkeys(detail_urls))[:max_candidates]

    fallback_slug = slug_for_url(request.name)
    if fallback_slug:
        detail_urls.append(f"https://{fallback_slug}.en.uptodown.com/android")
    detail_urls = list(dict.fromkeys(detail_urls))[:max_candidates]

    errors: list[str] = []
    best: SourceResult | None = None
    for detail_url in detail_urls:
        try:
            candidate = audit_uptodown_detail(client, request, detail_url.rstrip("/"), version_pages)
            if candidate.package_found and candidate.requested_match:
                return candidate
            if candidate.package_found and best is None:
                best = candidate
        except Exception as error:
            errors.append(f"{detail_url}: {error}")

    if best:
        if errors:
            best.note = f"{best.note}; errors: {errors[0]}"
        return best

    return make_result(
        request,
        "uptodown",
        "not_found",
        url=search_response.url,
        note=errors[0] if errors else "no matching detail page",
    )


def audit_uptodown_detail(client: HttpClient, request: AppRequest, detail_url: str, version_pages: int) -> SourceResult:
    download_url = f"{detail_url}/download"
    download_response = client.get(download_url)
    if download_response.status >= 400:
        status = "not_found" if download_response.status == 404 else "error"
        return make_result(request, "uptodown", status, url=download_response.url, note=f"HTTP {download_response.status}")

    package_name = uptodown_package_name(download_response.text)
    package_found = package_name == request.package_name
    latest_version = (
        parse_info_table_value(download_response.text, "Version")
        or source_version_from_text(strip_tags(download_response.text))
    )
    found_format = (parse_info_table_value(download_response.text, "File type") or "apk").lower()
    direct = "id=\"detail-download-button\"" in download_response.text and "data-url=" in download_response.text

    matched_version = latest_version if request_matches(request, latest_version, None) else None
    data_code = uptodown_data_code(download_response.text)
    if package_found and not matched_version and request.target.version and data_code:
        for page in range(1, version_pages + 1):
            versions_url = f"{detail_url}/apps/{data_code}/versions/{page}"
            data = json_response(client.get(versions_url, referer=f"{detail_url}/versions"))
            rows = data.get("data") or []
            if not rows:
                break
            for row in rows:
                version = row.get("version")
                if request_matches(request, version, None):
                    matched_version = version
                    found_format = (row.get("kindFile") or row.get("titleKindFile") or found_format or "apk").lower()
                    break
            if matched_version:
                break

    status = classify(package_found, matched_version is not None, latest_version, request)
    return make_result(
        request,
        "uptodown",
        status,
        package_found=package_found,
        requested_match=matched_version is not None,
        latest_version=latest_version,
        found_version=matched_version,
        found_format=found_format,
        direct_download_hint=direct,
        url=detail_url,
        note=f"package={package_name or 'unknown'}",
    )


def audit_apkmirror(client: HttpClient, request: AppRequest, max_candidates: int, upload_pages: int) -> SourceResult:
    search_url = f"https://www.apkmirror.com/?post_type=app_release&searchtype=app&s={urllib.parse.quote(request.package_name)}"
    search_response = client.get(search_url)
    if search_response.status >= 400:
        return make_result(request, "apkmirror", "error", url=search_response.url, note=f"HTTP {search_response.status}")

    app_urls = apkmirror_app_urls(search_response.text)[:max_candidates]
    errors: list[str] = []
    best: SourceResult | None = None

    for app_url in app_urls:
        try:
            candidate = audit_apkmirror_app(client, request, app_url, upload_pages)
            if candidate.package_found and candidate.requested_match:
                return candidate
            if candidate.package_found and best is None:
                best = candidate
        except Exception as error:
            errors.append(f"{app_url}: {error}")

    if best:
        if errors:
            best.note = f"{best.note}; errors: {errors[0]}"
        return best

    return make_result(
        request,
        "apkmirror",
        "not_found",
        url=search_response.url,
        note=errors[0] if errors else "no matching app page",
    )


def audit_apkmirror_app(client: HttpClient, request: AppRequest, app_url: str, upload_pages: int) -> SourceResult:
    app_response = client.get(app_url)
    package_found = page_mentions_package(app_response.text, app_response.url, request.package_name)
    release_urls = apkmirror_release_urls(app_response.text, app_url)
    category = app_url.rstrip("/").split("/")[-1]

    for page in range(1, upload_pages + 1):
        uploads_url = (
            f"https://www.apkmirror.com/uploads/?appcategory={category}"
            if page == 1
            else f"https://www.apkmirror.com/uploads/page/{page}/?appcategory={category}"
        )
        uploads_response = client.get(uploads_url, referer=app_url)
        release_urls.extend(apkmirror_release_urls(uploads_response.text, app_url))

    release_urls = list(dict.fromkeys(release_urls))
    release_versions = [(apkmirror_version_from_url(url), url) for url in release_urls]
    release_versions = [(version, url) for version, url in release_versions if version]
    latest_version = max((version for version, _ in release_versions), key=version_sort_key, default=None)
    match = next(
        ((version, url) for version, url in release_versions if request_matches(request, version, None)),
        None,
    )
    status = classify(package_found, match is not None, latest_version, request)

    return make_result(
        request,
        "apkmirror",
        status,
        package_found=package_found,
        requested_match=match is not None,
        latest_version=latest_version,
        found_version=match[0] if match else None,
        found_format=None,
        direct_download_hint=False,
        url=match[1] if match else app_url,
        note=f"{len(release_versions)} release links",
    )


def audit_play(client: HttpClient, request: AppRequest) -> SourceResult:
    url = f"https://play.google.com/store/apps/details?id={urllib.parse.quote(request.package_name)}"
    response = client.get(url)
    package_found = response.status < 400 and request.package_name in response.text
    status = "package_only" if package_found else "not_found"
    note = "listing loaded" if package_found else f"HTTP {response.status}"
    return make_result(request, "play", status, package_found=package_found, url=response.url, note=note)


def make_result(
    request: AppRequest,
    source: str,
    status: str,
    *,
    package_found: bool = False,
    requested_match: bool = False,
    latest_version: str | None = None,
    found_version: str | None = None,
    found_version_code: int | None = None,
    found_format: str | None = None,
    direct_download_hint: bool = False,
    url: str | None = None,
    note: str = "",
    elapsed_ms: int = 0,
) -> SourceResult:
    format_match = format_matches(request, found_format)
    if status == "matched" and format_match is False:
        status = "format_mismatch"

    return SourceResult(
        app_name=request.name,
        package_name=request.package_name,
        requested_version=request.target.version,
        requested_version_code=request.target.version_code,
        requested_format=request.requested_format,
        source=source,
        status=status,
        package_found=package_found,
        requested_match=requested_match,
        latest_version=latest_version,
        found_version=found_version,
        found_version_code=found_version_code,
        found_format=found_format,
        format_match=format_match,
        direct_download_hint=direct_download_hint,
        url=url,
        note=note,
        elapsed_ms=elapsed_ms,
    )


def classify(
    package_found: bool,
    requested_match: bool,
    latest_version: str | None,
    request: AppRequest,
) -> str:
    if requested_match:
        return "matched"
    if package_found and not request.target.version and not request.target.version_code:
        return "package_only"
    if package_found and latest_version:
        return "latest_only"
    if package_found:
        return "package_only"
    return "not_found"


def first_match(
    request: AppRequest,
    versions: Iterable[tuple[str | None, int | None, str | None]],
) -> tuple[str | None, int | None, str | None] | None:
    return next(
        (
            item
            for item in versions
            if request_matches(request, item[0], item[1])
        ),
        None,
    )


def request_matches(request: AppRequest, version: str | None, version_code: int | None) -> bool:
    target = request.target
    if not target.version and not target.version_code:
        return False
    version_ok = bool(target.version and version_name_equals(version, target.version))
    code_ok = bool(target.version_code and version_code and target.version_code == version_code)
    return version_ok or code_ok


def format_matches(request: AppRequest, found_format: str | None) -> bool | None:
    if not found_format:
        return None
    requested = request.requested_format.lower()
    found = found_format.lower().strip(".")
    if requested in {"apk", "apks", "apkm", "xapk"}:
        return found == requested
    return found in {"apk", "apks", "apkm", "xapk"}


def version_name_equals(left: str | None, right: str | None) -> bool:
    if not left or not right:
        return False
    norm_left = normalize_version(left)
    norm_right = normalize_version(right)
    if norm_left == norm_right:
        return True
    left_parts = version_number_parts(norm_left)
    right_parts = version_number_parts(norm_right)
    return bool(left_parts and left_parts == right_parts)


def normalize_version(value: str) -> str:
    value = value.lower()
    value = re.sub(r"\b(version|ver|v|release|stable|apk|xapk|apkm|apks|bundle)\b", " ", value)
    value = re.sub(r"[^0-9a-z]+", ".", value)
    return value.strip(".")


def version_number_parts(value: str | None) -> list[int]:
    if not value:
        return []
    return [int(item) for item in re.findall(r"\d+", value) if item.isdigit()]


def version_sort_key(version: str) -> tuple[list[int], str]:
    return (version_number_parts(version), version.lower())


def json_response(response: HttpResponse) -> dict[str, Any]:
    if response.status >= 400:
        return {}
    try:
        data = json.loads(response.text)
        return data if isinstance(data, dict) else {}
    except json.JSONDecodeError:
        return {}


def parse_attrs(tag: str) -> dict[str, str]:
    return {
        html.unescape(name): html.unescape(value)
        for name, _, value in re.findall(r"([:\w.-]+)\s*=\s*([\"'])(.*?)\2", tag, flags=re.S)
    }


def extract_canonical(page: str) -> str:
    match = re.search(r"<link[^>]+rel=[\"']canonical[\"'][^>]+href=[\"']([^\"']+)[\"']", page, re.I)
    return html.unescape(match.group(1)) if match else ""


def infer_format(text: str) -> str | None:
    lowered = text.lower()
    for kind in ("apkm", "apks", "xapk", "apk"):
        if kind in lowered:
            return kind
    return None


def wanted_suffixes(request: AppRequest) -> list[str]:
    requested = request.requested_format.lower()
    if requested == "apk":
        return ["apk"]
    if requested == "xapk":
        return ["xapk", "apk"]
    if requested == "apkm":
        return ["apkm", "apk"]
    if requested == "apks":
        return ["apks", "xapk", "apk"]
    return ["apk", "xapk", "apks"]


def page_mentions_package(text: str, url: str, package_name: str) -> bool:
    return package_name in text or package_name in urllib.parse.unquote(url)


def apkcombo_version(page: str, url: str) -> str | None:
    url_match = re.search(r"phone-(.+?)-(?:apk|xapk|apks|apkm)(?:[/?#]|$)", urllib.parse.unquote(url), re.I)
    if url_match:
        return url_match.group(1)
    for pattern in (
        r'"softwareVersion"\s*:\s*"([^"]+)"',
        r"Version:\s*([^<\n-]+)",
        r"Version</[^>]+>\s*<[^>]+>([^<]+)",
    ):
        match = re.search(pattern, page, re.I)
        if match:
            return strip_tags(match.group(1)).strip()
    return source_version_from_text(strip_tags(page))


def apkcombo_version_code(page: str) -> int | None:
    match = re.search(r"/(\d{1,12})[._-][A-Fa-f0-9]{8,}", urllib.parse.unquote(page))
    return int(match.group(1)) if match else None


def uptodown_package_name(page: str) -> str | None:
    play_url_match = re.search(r'id=["\']gplay-url["\'][^>]+data-url=["\']([^"\']+)["\']', page, re.I)
    if play_url_match:
        query = urllib.parse.urlparse(html.unescape(play_url_match.group(1))).query
        package = urllib.parse.parse_qs(query).get("id", [None])[0]
        if package:
            return package
    return parse_info_table_value(page, "Package Name")


def uptodown_data_code(page: str) -> str | None:
    match = re.search(r'id=["\']detail-app-name["\'][^>]+data-code=["\']([^"\']+)["\']', page, re.I)
    return html.unescape(match.group(1)) if match else None


def parse_info_table_value(page: str, label: str) -> str | None:
    rows = re.findall(r"<tr\b[^>]*>(.*?)</tr>", page, flags=re.I | re.S)
    for row in rows:
        header = strip_tags(" ".join(re.findall(r"<th\b[^>]*>(.*?)</th>", row, re.I | re.S))).strip()
        if header.lower() != label.lower():
            continue
        cells = re.findall(r"<td\b[^>]*>(.*?)</td>", row, re.I | re.S)
        if cells:
            return strip_tags(cells[-1]).strip() or None
    return None


def apkmirror_app_urls(page: str) -> list[str]:
    urls = []
    for href in re.findall(r'href=["\']([^"\']+)["\']', page, re.I):
        absolute = urllib.parse.urljoin("https://www.apkmirror.com/", html.unescape(href)).split("#", 1)[0]
        path = urllib.parse.urlparse(absolute).path
        if re.fullmatch(r"/apk/[^/]+/[^/]+/?", path):
            urls.append(absolute)
    return list(dict.fromkeys(urls))


def apkmirror_release_urls(page: str, app_url: str) -> list[str]:
    app_path = urllib.parse.urlparse(app_url).path.rstrip("/")
    urls = []
    for href in re.findall(r'href=["\']([^"\']+)["\']', page, re.I):
        absolute = urllib.parse.urljoin("https://www.apkmirror.com/", html.unescape(href)).split("#", 1)[0]
        path = urllib.parse.urlparse(absolute).path.rstrip("/")
        if path.startswith("/apk/") and path.endswith("-release") and path.startswith(app_path):
            urls.append(absolute)
    return list(dict.fromkeys(urls))


def apkmirror_version_from_url(url: str) -> str | None:
    segment = urllib.parse.urlparse(url).path.strip("/").split("/")[-1]
    segment = re.sub(r"-release$", "", segment)
    segment = re.sub(r"-android-apk$", "", segment)
    match = re.search(r"(\d+(?:-\d+)+(?:-[a-z0-9]+)*)$", segment, re.I)
    if not match:
        return None
    return match.group(1).replace("-", ".")


def strip_tags(value: str) -> str:
    return html.unescape(re.sub(r"<[^>]+>", " ", value)).replace("\xa0", " ")


def source_version_from_text(text: str) -> str | None:
    match = re.search(r"\b(v?\d+(?:[._-]\d+)+(?:[-.][A-Za-z0-9]+)?)\b", text, re.I)
    return match.group(1).strip() if match else None


def first_non_empty(values: Iterable[str | None]) -> str | None:
    return next((value for value in values if value), None)


def to_int(value: Any) -> int | None:
    try:
        if value in (None, ""):
            return None
        return int(value)
    except (TypeError, ValueError):
        return None


def slug_for_url(value: str) -> str:
    value = value.lower().replace("&", " and ").replace("'", "")
    value = re.sub(r"[^a-z0-9]+", "-", value).strip("-")
    return value or "app"


def run_audit(args: argparse.Namespace) -> list[SourceResult]:
    requests = parse_constants(Path(args.constants))
    if args.package:
        wanted = {item.lower() for item in args.package}
        requests = [item for item in requests if item.package_name.lower() in wanted or item.name.lower() in wanted]
    if args.name_contains:
        needle = args.name_contains.lower()
        requests = [item for item in requests if needle in item.name.lower() or needle in item.package_name.lower()]
    if args.limit:
        requests = requests[: args.limit]

    cache_dir = None if args.no_cache else Path(args.output).parent / ".source-audit-cache"
    client = HttpClient(cache_dir=cache_dir, timeout=args.timeout, delay=args.delay)
    sources = [source.lower() for source in args.sources.split(",") if source.strip()]
    checkers: dict[str, Callable[[AppRequest], SourceResult]] = {
        "apkpure": lambda item: audit_apkpure(client, item),
        "aptoide": lambda item: audit_aptoide(client, item),
        "apkcombo": lambda item: audit_apkcombo(client, item),
        "uptodown": lambda item: audit_uptodown(client, item, args.max_candidates, args.uptodown_pages),
        "apkmirror": lambda item: audit_apkmirror(client, item, args.max_candidates, args.apkmirror_upload_pages),
        "play": lambda item: audit_play(client, item),
    }

    results: list[SourceResult] = []
    total = len(requests) * len(sources)
    done = 0
    for app_request in requests:
        for source in sources:
            done += 1
            checker = checkers.get(source)
            if not checker:
                results.append(make_result(app_request, source, "error", note="unknown source"))
                continue

            started = time.monotonic()
            try:
                result = checker(app_request)
            except Exception as error:
                result = make_result(app_request, source, "error", note=repr(error))
            result.elapsed_ms = int((time.monotonic() - started) * 1000)
            results.append(result)
            if not args.quiet:
                print(
                    f"[{done}/{total}] {source:9} {result.status:12} "
                    f"{app_request.name} ({app_request.package_name}) "
                    f"requested={app_request.requested_version_label} latest={result.latest_version or '-'}"
                )

    return results


def write_reports(results: list[SourceResult], output: Path) -> tuple[Path, Path]:
    output.parent.mkdir(parents=True, exist_ok=True)
    csv_path = output.with_suffix(".csv")
    json_path = output.with_suffix(".json")

    rows = [asdict(result) for result in results]
    with csv_path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()) if rows else list(SourceResult.__dataclass_fields__))
        writer.writeheader()
        writer.writerows(rows)
    json_path.write_text(json.dumps(rows, indent=2, ensure_ascii=False), encoding="utf-8")
    return csv_path, json_path


def print_summary(results: list[SourceResult]) -> None:
    by_source: dict[str, dict[str, int]] = {}
    for result in results:
        by_source.setdefault(result.source, {})
        by_source[result.source][result.status] = by_source[result.source].get(result.status, 0) + 1

    print("\nSummary")
    for source, counts in sorted(by_source.items()):
        total = sum(counts.values())
        status_bits = ", ".join(f"{status}={count}" for status, count in sorted(counts.items()))
        print(f"  {source}: total={total}, {status_bits}")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Audit APK source matches for Morphe Constants.kt.")
    parser.add_argument("--constants", default=DEFAULT_CONSTANTS, help="Path to Constants.kt.")
    parser.add_argument(
        "--sources",
        default=",".join(DEFAULT_SOURCES),
        help=f"Comma-separated sources. Default: {','.join(DEFAULT_SOURCES)}",
    )
    parser.add_argument("--package", action="append", help="Only test this package/app name. Can be repeated.")
    parser.add_argument("--name-contains", help="Only test apps whose name/package contains this text.")
    parser.add_argument("--limit", type=int, help="Only test the first N parsed targets.")
    parser.add_argument("--output", default="reports/source-audit.csv", help="Output CSV path. JSON is written beside it.")
    parser.add_argument("--timeout", type=float, default=20.0, help="HTTP timeout per request.")
    parser.add_argument("--delay", type=float, default=0.35, help="Delay between live HTTP requests.")
    parser.add_argument("--max-candidates", type=int, default=3, help="Max candidate app pages to inspect per source.")
    parser.add_argument("--uptodown-pages", type=int, default=5, help="Max Uptodown version pages to inspect.")
    parser.add_argument("--apkmirror-upload-pages", type=int, default=3, help="Max APKMirror upload pages to inspect.")
    parser.add_argument("--no-cache", action="store_true", help="Disable local HTTP cache.")
    parser.add_argument("--quiet", action="store_true", help="Only print final summary.")
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    results = run_audit(args)
    csv_path, json_path = write_reports(results, Path(args.output))
    print_summary(results)
    print(f"\nWrote:\n  {csv_path}\n  {json_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
