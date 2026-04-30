# Copyright 2025 OPPO

# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at

#     http://www.apache.org/licenses/LICENSE-2.0

# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Web tools: web_search and web_fetch."""

import html
import json
import os
import re
from typing import Any
from urllib.parse import urlparse

import httpx
from loguru import logger
import trafilatura
from topoclaw.agent.tools.base import Tool
from topoclaw.config.loader import load_config

# Shared constants

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36 Edg/119.0.0.0"

MAX_REDIRECTS = 5  # Limit redirects to prevent DoS attacks
_WEB_FETCH_COOKIE_CACHE: dict[str, str] = {}


def _strip_tags(text: str) -> str:
    """Remove HTML tags and decode entities."""
    text = re.sub(r'<script[\s\S]*?</script>', '', text, flags=re.I)
    text = re.sub(r'<style[\s\S]*?</style>', '', text, flags=re.I)
    text = re.sub(r'<[^>]+>', '', text)
    return html.unescape(text).strip()


def _normalize(text: str) -> str:
    """Normalize whitespace."""
    text = re.sub(r'[ \t]+', ' ', text)
    return re.sub(r'\n{3,}', '\n\n', text).strip()


def _validate_url(url: str) -> tuple[bool, str]:
    """Validate URL: must be http(s) with valid domain."""
    try:
        p = urlparse(url)
        if p.scheme not in ('http', 'https'):
            return False, f"Only http/https allowed, got '{p.scheme or 'none'}'"
        if not p.netloc:
            return False, "Missing domain"
        return True, ""
    except Exception as e:
        return False, str(e)


def _web_fetch_cookie_domain_candidates(url: str) -> list[str]:
    """Build cookie domain candidates from most specific to broad."""
    hostname = urlparse(url).hostname or ""
    if not hostname:
        return []

    parts = hostname.split(".")
    candidates: list[str] = []
    candidates.append("." + hostname)

    for i in range(1, len(parts) - 1):
        parent = "." + ".".join(parts[i:])
        if parent not in candidates:
            candidates.append(parent)

    if hostname not in candidates:
        candidates.append(hostname)
    return candidates


def _web_fetch_auto_cookie_for_url(url: str) -> str:
    """Try reading Chrome cookies for URL domain. Returns empty string on failure."""
    for domain in _web_fetch_cookie_domain_candidates(url):
        if domain in _WEB_FETCH_COOKIE_CACHE:
            return _WEB_FETCH_COOKIE_CACHE[domain]
        try:
            import browser_cookie3

            cookie_jar = browser_cookie3.chrome(domain_name=domain)
            cookies = {c.name: c.value for c in cookie_jar}
            if not cookies:
                continue
            cookie = "; ".join(f"{k}={v}" for k, v in cookies.items())
            _WEB_FETCH_COOKIE_CACHE[domain] = cookie
            logger.info("WebFetch auto-cookie loaded for {}", domain)
            return cookie
        except ImportError:
            logger.debug("WebFetch auto-cookie skipped: browser_cookie3 not installed")
            return ""
        except Exception as e:
            logger.debug("WebFetch auto-cookie failed for {}: {}", domain, e)
            continue
    return ""


def _web_fetch_build_headers(url: str, cookie: str) -> dict[str, str]:
    parsed = urlparse(url)
    origin = f"{parsed.scheme}://{parsed.netloc}" if parsed.scheme and parsed.netloc else ""
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
        "Cache-Control": "no-cache",
        "Pragma": "no-cache",
    }
    if origin:
        headers["Origin"] = origin
        headers["Referer"] = origin + "/"
    if cookie:
        headers["Cookie"] = cookie
    return headers


def _web_fetch_decode_json_escaped(s: str) -> str:
    try:
        return json.loads(f"\"{s}\"")
    except Exception:
        return s.replace("\\u0026", "&")


def _web_fetch_extract_odocs_content_url(html_text: str) -> str | None:
    patterns = [
        r'"__CONTENT_URL__"\s*:\s*\{[^{}]*"url"\s*:\s*"([^"]+)"',
        r'"contentUrl"\s*:\s*"([^"]+)"',
    ]
    for p in patterns:
        m = re.search(p, html_text)
        if m:
            return _web_fetch_decode_json_escaped(m.group(1))
    return None


def _web_fetch_extract_text_from_json(data: Any) -> str:
    out: list[str] = []
    keys = {"text", "insert", "title", "name", "content", "value"}

    def walk(node: Any) -> None:
        if isinstance(node, dict):
            for k, v in node.items():
                if k in keys and isinstance(v, str):
                    s = v.strip()
                    if s and s not in out:
                        out.append(s)
                walk(v)
        elif isinstance(node, list):
            for item in node:
                walk(item)

    walk(data)
    return "\n".join(out).strip()


def _web_fetch_extract_text_from_odocs_ops(raw_text: str) -> str:
    try:
        data = json.loads(raw_text)
    except Exception:
        return ""
    if not isinstance(data, list):
        return ""

    lines: list[str] = []
    for item in data:
        if isinstance(item, list) and len(item) >= 2 and isinstance(item[1], str):
            s = item[1].replace("\r", "").strip("\n")
            if s:
                lines.append(s)
    return "\n".join(dict.fromkeys(lines)).strip()


# 联网搜索 API（对齐 UnifiedAssistant 的 Serper 通道）
_WEB_SEARCH_ENDPOINT = "/search/serper_search/search"
_WEB_SEARCH_DEFAULT_BASES: tuple[str, ...] = ()
_MAX_WEB_SEARCH_QUERY_LEN = 100
_WEB_SEARCH_COUNT_MAX = 50


def _web_search_config() -> tuple[str, str]:
    try:
        cfg = load_config()
        search = cfg.tools.web.search
        return (search.api_key or "").strip(), (search.api_base or "").strip()
    except Exception:
        return "", ""


def _web_search_resolve_api_key(init: str | None, config_key: str) -> str:
    return (
        init
        or os.environ.get("SERPER_API_KEY")
        or os.environ.get("SEARCH_API_KEY")
        or os.environ.get("HUOSHAN_API_KEY")
        or config_key
        or ""
    ).strip()


def _web_search_resolve_api_bases(config_base: str) -> list[str]:
    manual = (config_base or os.environ.get("SEARCH_API_BASE") or "").strip()
    roots = [manual] if manual else list(_WEB_SEARCH_DEFAULT_BASES)
    out: list[str] = []
    seen: set[str] = set()
    for root in roots:
        r = root.rstrip("/")
        if not r:
            continue
        # Some deployments document /v1 base, but serper route is mounted at /search/...
        if r.endswith("/v1"):
            r = r[: -len("/v1")]
        if r.endswith("/search") or r.endswith("/news") or r.endswith("/scholar"):
            r = r.rsplit("/", 1)[0]
        full = f"{r}{_WEB_SEARCH_ENDPOINT}"
        if full not in seen:
            out.append(full)
            seen.add(full)
    return out


def _web_search_clip_query(q: str) -> str:
    q = (q or "").strip()
    if len(q) > _MAX_WEB_SEARCH_QUERY_LEN:
        return q[:_MAX_WEB_SEARCH_QUERY_LEN]
    return q


def _web_search_build_payload(
    query: str,
    count: int | None,
    *,
    time_range: str | None,
    filter_need_url: bool | None,
    filter_need_content: bool | None,
    sites: str | None,
    block_hosts: str | None,
    auth_info_level: int | None,
    query_rewrite: bool | None,
    default_count: int,
) -> dict[str, Any]:
    n = min(max(count or default_count, 1), _WEB_SEARCH_COUNT_MAX)
    body: dict[str, Any] = {"q": query, "num": n}
    # 兼容 UnifiedAssistant 的默认参数
    body["gl"] = "cn"
    body["hl"] = "zh-cn"
    if time_range:
        # serper 通道使用 tbs 表示时间范围
        body["tbs"] = time_range.strip()
    # 以下参数在 serper 通道无标准映射，按需最小兼容
    if query_rewrite is not None:
        body["autocorrect"] = bool(query_rewrite)
    return body


def _web_search_extract_result(data: dict[str, Any]) -> list[dict[str, Any]]:
    organic = data.get("organic")
    if isinstance(organic, list):
        return [x for x in organic if isinstance(x, dict)]
    return []


def _web_search_api_error_message(data: dict[str, Any]) -> str | None:
    err = data.get("error")
    if isinstance(err, dict):
        msg = str(err.get("message") or err.get("Message") or "").strip()
        if msg:
            return f"Error: {msg}"
    msg = data.get("message")
    if isinstance(msg, str) and msg.strip():
        return f"Error: {msg.strip()}"
    return None


def _web_search_format_results(items: list[dict[str, Any]], query: str) -> list[str]:
    lines: list[str] = [f"Results for: {query}"]
    for i, item in enumerate(items, 1):
        title = item.get("title") or ""
        url = item.get("link") or ""
        site = item.get("source") or item.get("siteName") or ""
        head = f"{i}. {title}"
        if site:
            head += f" ({site})"
        lines.append(head)
        if url:
            lines.append(f"   {url}")
        if snip := item.get("snippet"):
            lines.append(f"   {snip}")
    return lines


class WebSearchTool(Tool):
    """Search the web via Serper-compatible API (UniAPI)."""

    name = "web_search"
    description = (
        "Search the web via API。 "
        "Returns titles, URLs, and snippets. Query must be 1–100 characters (longer input is truncated)."
    )
    parameters = {
        "type": "object",
        "properties": {
            "query": {
                "type": "string",
                "description": "Search query (1–100 characters per API; longer text is truncated)",
            },
            "count": {
                "type": "integer",
                "description": "Number of results (max 50; default follows tool max_results)",
                "minimum": 1,
                "maximum": 50,
            },
            "time_range": {
                "type": "string",
                "description": "Optional time filter: OneDay, OneWeek, OneMonth, OneYear, or YYYY-MM-DD..YYYY-MM-DD",
            },
            "filter_need_url": {
                "type": "boolean",
                "description": "Only return results that have a landing URL",
            },
            "filter_need_content": {
                "type": "boolean",
                "description": "Only return results that have body content",
            },
            "sites": {
                "type": "string",
                "description": "Restrict to sites: full domains separated by | (max 20)",
            },
            "block_hosts": {
                "type": "string",
                "description": "Block sites: full domains separated by | (max 5)",
            },
            "auth_info_level": {
                "type": "integer",
                "description": "0 = any authority; 1 = only highly authoritative results",
                "minimum": 0,
                "maximum": 1,
            },
            "query_rewrite": {
                "type": "boolean",
                "description": "Enable query rewrite (increases latency)",
            },
        },
        "required": ["query"],
    }

    def __init__(self, api_key: str | None = None, max_results: int = 5, proxy: str | None = None):
        self._init_api_key = api_key
        self.max_results = max_results
        self.proxy = proxy

    @property
    def api_key(self) -> str:
        cfg_key, _ = _web_search_config()
        return _web_search_resolve_api_key(self._init_api_key, cfg_key)

    @property
    def api_base(self) -> str:
        _, cfg_base = _web_search_config()
        return cfg_base

    async def execute(self, query: str, **kwargs: Any) -> str:
        if not self.api_key:
            return (
                "Error: Search API key not configured. "
                "Set tools.web.search.api_key in config.json "
                "(or SERPER_API_KEY / SEARCH_API_KEY), then restart."
            )

        q = _web_search_clip_query(query)
        if not q:
            return "Error: query is empty"

        count = kwargs.get("count")
        if isinstance(count, (int, float)):
            count = int(count)
        else:
            count = None

        payload = _web_search_build_payload(
            q,
            count,
            time_range=kwargs.get("time_range"),
            filter_need_url=kwargs.get("filter_need_url"),
            filter_need_content=kwargs.get("filter_need_content"),
            sites=kwargs.get("sites"),
            block_hosts=kwargs.get("block_hosts"),
            auth_info_level=kwargs.get("auth_info_level"),
            query_rewrite=kwargs.get("query_rewrite"),
            default_count=self.max_results,
        )

        urls = _web_search_resolve_api_bases(self.api_base)
        if not urls:
            return (
                "Error: Search API base URL not configured. "
                "Set tools.web.search.api_base in config.json (or SEARCH_API_BASE), then restart."
            )
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json",
        }

        try:
            last_error = "unknown error"
            for url in urls:
                logger.debug("WebSearch: {} url={}", "proxy" if self.proxy else "direct", url)
                async with httpx.AsyncClient(proxy=self.proxy) as client:
                    r = await client.post(url, json=payload, headers=headers, timeout=60.0)
                try:
                    data = r.json()
                except Exception:
                    data = {"message": r.text[:300]}

                if isinstance(data, dict) and (err := _web_search_api_error_message(data)):
                    last_error = err
                    if r.status_code in (401, 403):
                        continue
                if r.status_code >= 400:
                    last_error = f"Error: HTTP {r.status_code} {r.text[:300]}"
                    continue
                if not isinstance(data, dict):
                    last_error = "Error: unexpected response shape"
                    continue

                web = _web_search_extract_result(data)
                if web:
                    out = "\n".join(_web_search_format_results(web, q)).strip()
                    return out or f"No results for: {q}"
                last_error = f"No web results for: {q}"

            return last_error

        except httpx.ProxyError as e:
            logger.error("WebSearch proxy error: {}", e)
            return f"Proxy error: {e}"
        except Exception as e:
            logger.error("WebSearch error: {}", e)
            return f"Error: {e}"


class WebFetchTool(Tool):
    """Fetch and extract content from a URL using Readability."""

    name = "web_fetch"
    description = "Fetch URL and extract readable content (HTML → markdown/text). Prefer static content and avoid dynamic content."
    parameters = {
        "type": "object",
        "properties": {
            "url": {"type": "string", "description": "URL to fetch"},
            "extractMode": {"type": "string", "enum": ["markdown", "text"], "default": "markdown"},
            "useBrowserCookies": {
                "type": "boolean",
                "description": "Try auto-loading local Chrome cookies for authenticated pages (default: true)",
                "default": True,
            },
        },
        "required": ["url"]
    }
    # "maxChars": {"type": "integer", "minimum": 100}

    def __init__(self, max_chars: int = 50000, proxy: str | None = None):
        self.max_chars = max_chars
        self.proxy = proxy

    async def execute(self, url: str, extractMode: str = "markdown", maxChars: int | None = None, **kwargs: Any) -> str:
        max_chars = maxChars or self.max_chars
        use_browser_cookies = kwargs.get("useBrowserCookies", True)
        use_browser_cookies = bool(use_browser_cookies)
        is_valid, error_msg = _validate_url(url)
        if not is_valid:
            return json.dumps({"error": f"URL validation failed: {error_msg}", "url": url}, ensure_ascii=False)

        try:
            logger.debug("WebFetch: {}", "proxy enabled" if self.proxy else "direct connection")
            cookie = _web_fetch_auto_cookie_for_url(url) if use_browser_cookies else ""
            headers = _web_fetch_build_headers(url, cookie)
            async with httpx.AsyncClient(
                follow_redirects=True,
                max_redirects=MAX_REDIRECTS,
                timeout=30.0,
                proxy=self.proxy,
            ) as client:
                r = await client.get(url, headers=headers)
                if r.status_code == 403 and cookie:
                    retry_headers = dict(headers)
                    retry_headers.pop("Cookie", None)
                    logger.debug("WebFetch 403 with cookie; retrying without cookie")
                    r = await client.get(url, headers=retry_headers)
                r.raise_for_status()

            ctype = r.headers.get("content-type", "")

            if "application/json" in ctype:
                text, extractor = json.dumps(r.json(), indent=2, ensure_ascii=False), "json"
            elif "text/html" in ctype or r.text[:256].lower().startswith(("<!doctype", "<html")):
                extracted_text = trafilatura.extract(
                    r.text, 
                    include_links=False,  
                    include_tables=True, 
                    favor_precision=True  # 开启精确模式，宁缺毋滥，减少垃圾信息
                )
                
                if extracted_text:
                    text = extracted_text
                    extractor = "trafilatura"
                else:
                    content_url = _web_fetch_extract_odocs_content_url(r.text)
                    if content_url:
                        async with httpx.AsyncClient(
                            follow_redirects=True,
                            max_redirects=MAX_REDIRECTS,
                            timeout=30.0,
                            proxy=self.proxy,
                        ) as content_client:
                            content_resp = await content_client.get(content_url, headers=headers)
                            content_resp.raise_for_status()
                        content_ctype = content_resp.headers.get("content-type", "")
                        if "application/json" in content_ctype:
                            data = content_resp.json()
                            extracted_json_text = _web_fetch_extract_text_from_json(data)
                            if extracted_json_text:
                                text = extracted_json_text
                                extractor = "odocs_content_json"
                            else:
                                text = json.dumps(data, ensure_ascii=False, indent=2)
                                extractor = "odocs_content_json_raw"
                        else:
                            ops_text = _web_fetch_extract_text_from_odocs_ops(content_resp.text)
                            if ops_text:
                                text = ops_text
                                extractor = "odocs_content_ops"
                            else:
                                text = content_resp.text
                                extractor = "odocs_content_raw"
                    else:
                        text = f"[提取失败] URL: {url}"
                        extractor = "trafilatura_failed"
            else:
                text, extractor = r.text, "raw"

            truncated = len(text) > max_chars
            if truncated: text = text[:max_chars]

            return json.dumps({"url": url, "finalUrl": str(r.url), "status": r.status_code,
                              "extractor": extractor, "truncated": truncated, "length": len(text), "text": text}, ensure_ascii=False)
        except httpx.ProxyError as e:
            logger.error("WebFetch proxy error for {}: {}", url, e)
            return json.dumps({"error": f"Proxy error: {e}", "url": url}, ensure_ascii=False)
        except Exception as e:
            logger.error("WebFetch error for {}: {}", url, e)
            return json.dumps({"error": str(e), "url": url}, ensure_ascii=False)

    def _to_markdown(self, html: str) -> str:
        """Convert HTML to markdown."""
        # Convert links, headings, lists before stripping tags
        text = re.sub(r'<a\s+[^>]*href=["\']([^"\']+)["\'][^>]*>([\s\S]*?)</a>',
                      lambda m: f'[{_strip_tags(m[2])}]({m[1]})', html, flags=re.I)
        text = re.sub(r'<h([1-6])[^>]*>([\s\S]*?)</h\1>',
                      lambda m: f'\n{"#" * int(m[1])} {_strip_tags(m[2])}\n', text, flags=re.I)
        text = re.sub(r'<li[^>]*>([\s\S]*?)</li>', lambda m: f'\n- {_strip_tags(m[1])}', text, flags=re.I)
        text = re.sub(r'</(p|div|section|article)>', '\n\n', text, flags=re.I)
        text = re.sub(r'<(br|hr)\s*/?>', '\n', text, flags=re.I)
        return _normalize(_strip_tags(text))
