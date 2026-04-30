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

"""Query arXiv metadata via the public Atom API.

GET ``http://export.arxiv.org/api/query`` — see ``docs/arxiv-search-api.md``.
Uses ``urllib.request`` (like the API examples). No API key. Avoid rapid repeated calls.
"""

from __future__ import annotations

import asyncio
import random
import socket
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from typing import Any
from xml.etree.ElementTree import Element

from loguru import logger

from topoclaw.agent.tools.base import Tool

__all__ = ["ArxivSearchTool"]

_ARXIV_API_QUERY = "http://export.arxiv.org/api/query"
_ATOM = "{http://www.w3.org/2005/Atom}"
_ARXIV = "{http://arxiv.org/schemas/atom}"
_OPENSEARCH = "{http://a9.com/-/spec/opensearch/1.1/}"

_MAX_RESULTS_MIN = 1
_MAX_RESULTS_CAP = 100
_DEFAULT_MAX = 10
_MAX_SUMMARY_CHARS = 800
_MAX_START = 30_000
_ARXIV_MIN_INTERVAL_SEC = 1.05
_ARXIV_MAX_RETRIES = 3
_ARXIV_BACKOFF_BASE_SEC = 1.25
_ARXIV_HTTP_TIMEOUT_SEC = 120.0

_ARXIV_USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36 Edg/119.0.0.0"
)
_ARXIV_REFERER = "https://arxiv.org/"

_ARXIV_RATE_LOCK = threading.Lock()
_ARXIV_LAST_REQUEST_TS = 0.0


def _local_text(el: Element | None) -> str:
    if el is None or el.text is None:
        return ""
    return (el.text or "").strip()


def _entry_authors(entry: Element) -> str:
    names: list[str] = []
    for auth in entry.findall(f"{_ATOM}author"):
        n = auth.find(f"{_ATOM}name")
        if n is not None and (t := _local_text(n)):
            names.append(t)
    return ", ".join(names)


def _entry_pdf_href(entry: Element) -> str:
    for link in entry.findall(f"{_ATOM}link"):
        if link.get("title") == "pdf" and link.get("rel") == "related":
            if href := (link.get("href") or "").strip():
                return href
    return ""


def _entry_primary_category(entry: Element) -> str:
    pc = entry.find(f"{_ARXIV}primary_category")
    if pc is not None and (term := (pc.get("term") or "").strip()):
        return term
    return ""


def _is_error_entry(entry: Element) -> bool:
    tid_el = entry.find(f"{_ATOM}id")
    id_text = (tid_el.text or "").strip() if tid_el is not None and tid_el.text else ""
    title = _local_text(entry.find(f"{_ATOM}title"))
    return title.lower() == "error" or "arxiv.org/api/errors" in id_text


def _format_entries(entries: list[Element], *, total_hint: str | None) -> str:
    lines: list[str] = []
    if total_hint:
        lines.append(total_hint)
    for i, entry in enumerate(entries, 1):
        title = _local_text(entry.find(f"{_ATOM}title"))
        abs_url = _local_text(entry.find(f"{_ATOM}id"))
        published = _local_text(entry.find(f"{_ATOM}published"))
        summ = _local_text(entry.find(f"{_ATOM}summary"))
        if len(summ) > _MAX_SUMMARY_CHARS:
            summ = summ[: _MAX_SUMMARY_CHARS - 1].rstrip() + "…"
        pdf = _entry_pdf_href(entry)
        authors = _entry_authors(entry)
        cat = _entry_primary_category(entry)
        lines.append(f"{i}. {title}")
        if authors:
            lines.append(f"   Authors: {authors}")
        if cat:
            lines.append(f"   Primary: {cat}")
        if published:
            lines.append(f"   Published: {published}")
        if abs_url:
            lines.append(f"   Abs: {abs_url}")
        if pdf:
            lines.append(f"   PDF: {pdf}")
        if summ:
            lines.append(f"   Abstract: {summ}")
        lines.append("")
    return "\n".join(lines).strip()


def _fetch_arxiv_feed(
    params_map: dict[str, str | int],
    proxy: str | None,
    timeout: float,
) -> tuple[int, str]:
    """Sync GET with UA/Referer, global rate limit, and bounded retries."""
    global _ARXIV_LAST_REQUEST_TS

    def _sleep_before_next_request() -> None:
        with _ARXIV_RATE_LOCK:
            now = time.monotonic()
            elapsed = now - _ARXIV_LAST_REQUEST_TS
            need_wait = _ARXIV_MIN_INTERVAL_SEC - elapsed
            if need_wait > 0:
                time.sleep(need_wait)
                now = time.monotonic()
            _ARXIV_LAST_REQUEST_TS = now

    def _retry_after_seconds(err: urllib.error.HTTPError) -> float | None:
        hdr = ""
        try:
            hdr = (err.headers.get("Retry-After") or "").strip()
        except Exception:
            hdr = ""
        if not hdr:
            return None
        try:
            # arXiv/CDN generally returns numeric seconds.
            val = float(hdr)
            return max(0.0, min(val, 120.0))
        except (TypeError, ValueError):
            return None

    def _backoff_delay(attempt: int) -> float:
        # 1.25s, 2.5s, 5s, 10s (+ small jitter).
        base = _ARXIV_BACKOFF_BASE_SEC * (2 ** max(0, attempt - 1))
        return min(base + random.uniform(0.0, 0.35), 30.0)

    q = urllib.parse.urlencode({k: str(v) for k, v in params_map.items()})
    url = f"{_ARXIV_API_QUERY}?{q}"
    handlers: list[urllib.request.BaseHandler] = []
    if proxy and str(proxy).strip():
        p = str(proxy).strip()
        handlers.append(urllib.request.ProxyHandler({"http": p, "https": p}))
    opener = urllib.request.build_opener(*handlers) if handlers else urllib.request.build_opener()
    last_status = 0
    last_body = ""
    last_url_err: urllib.error.URLError | None = None

    for attempt in range(1, _ARXIV_MAX_RETRIES + 2):
        _sleep_before_next_request()
        req = urllib.request.Request(
            url,
            headers={
                "User-Agent": _ARXIV_USER_AGENT,
                "Referer": _ARXIV_REFERER,
                "Accept": "application/atom+xml,application/xml,text/xml;q=0.9,*/*;q=0.8",
                "Connection": "close",
            },
        )
        try:
            with opener.open(req, timeout=timeout) as resp:
                code = resp.getcode() or 200
                raw = resp.read()
                return code, raw.decode("utf-8", errors="replace")
        except urllib.error.HTTPError as e:
            body = ""
            if e.fp:
                try:
                    body = e.fp.read().decode("utf-8", errors="replace")
                except Exception:
                    body = ""
            last_status, last_body = e.code, body
            if e.code in (429, 503) and attempt <= _ARXIV_MAX_RETRIES:
                retry_after = _retry_after_seconds(e)
                time.sleep(retry_after if retry_after is not None else _backoff_delay(attempt))
                continue
            return e.code, body
        except urllib.error.URLError as e:
            last_url_err = e
            reason = getattr(e, "reason", None)
            is_transient = isinstance(reason, TimeoutError | ConnectionResetError | socket.timeout)
            if attempt <= _ARXIV_MAX_RETRIES and is_transient:
                time.sleep(_backoff_delay(attempt))
                continue
            raise

    if last_status >= 400:
        return last_status, last_body
    if last_url_err is not None:
        raise last_url_err
    return 503, "Error: arXiv request retries exhausted"


class ArxivSearchTool(Tool):
    """学术检索优先；通过官方 Atom API 检索或按 ID 获取 arXiv 论文。"""

    name = "arxiv_search"
    description = (
        "学术搜索优先使用本工具，禁止并行同时调用多次此工具。"
        "Query arXiv via the official Atom API (no key): set search_query and/or id_list; "
        "optional sort_by, sort_order, and paging."
    )
    parameters = {
        "type": "object",
        "properties": {
            "search_query": {
                "type": "string",
                "description": (
                    "arXiv search_query per API docs. Examples: all:diffusion, ti:\"title phrase\", "
                    "au:lastname, cat:cs.LG. Omit when using id_list only."
                ),
            },
            "id_list": {
                "type": "string",
                "description": "Comma-separated arXiv ids (e.g. 2301.07041, cs/9901002v1)",
            },
            "start": {
                "type": "integer",
                "description": "0-based offset into the result set (API paging)",
                "minimum": 0,
                "maximum": _MAX_START,
            },
            "max_results": {
                "type": "integer",
                "description": f"Page size ({_MAX_RESULTS_MIN}–{_MAX_RESULTS_CAP}; default {_DEFAULT_MAX})",
                "minimum": _MAX_RESULTS_MIN,
                "maximum": _MAX_RESULTS_CAP,
            },
            "sort_by": {
                "type": "string",
                "enum": ["relevance", "lastUpdatedDate", "submittedDate"],
                "description": "Result ordering field (API sortBy)",
            },
            "sort_order": {
                "type": "string",
                "enum": ["ascending", "descending"],
                "description": "sortOrder for sort_by",
            },
        },
        "required": [],
    }

    def __init__(self, proxy: str | None = None):
        self.proxy = proxy

    async def execute(self, **kwargs: Any) -> str:
        sq_arg = kwargs.get("search_query")
        if isinstance(sq_arg, str):
            final_sq = sq_arg.strip()
        else:
            final_sq = ""

        id_list = kwargs.get("id_list")
        if isinstance(id_list, str):
            id_list = id_list.strip()
        else:
            id_list = None

        if not final_sq and not id_list:
            return "Error: provide search_query and/or id_list"

        start = kwargs.get("start")
        if isinstance(start, (int, float)):
            start_i = max(0, int(start))
        else:
            start_i = 0
        start_i = min(start_i, _MAX_START)

        mr = kwargs.get("max_results")
        if isinstance(mr, (int, float)):
            n = int(mr)
        else:
            n = _DEFAULT_MAX
        n = min(max(n, _MAX_RESULTS_MIN), _MAX_RESULTS_CAP)

        sort_by = kwargs.get("sort_by")
        sort_order = kwargs.get("sort_order")
        if sort_by is not None and sort_by not in (
            "relevance",
            "lastUpdatedDate",
            "submittedDate",
        ):
            return "Error: sort_by must be relevance, lastUpdatedDate, or submittedDate"
        if sort_order is not None and sort_order not in ("ascending", "descending"):
            return "Error: sort_order must be ascending or descending"

        params_map: dict[str, str | int] = {
            "start": start_i,
            "max_results": n,
        }
        if final_sq:
            params_map["search_query"] = final_sq
        if id_list:
            params_map["id_list"] = id_list
        if sort_by:
            params_map["sortBy"] = str(sort_by)
        if sort_order:
            params_map["sortOrder"] = str(sort_order)

        try:
            logger.debug("arxiv_search: {}", "proxy" if self.proxy else "direct")
            status, text = await asyncio.to_thread(
                _fetch_arxiv_feed,
                params_map,
                self.proxy,
                _ARXIV_HTTP_TIMEOUT_SEC,
            )

            if status >= 400:
                return f"Error: HTTP {status} — {(text or '')[:400].strip()}"

            try:
                root = ET.fromstring(text)
            except ET.ParseError as e:
                return f"Error: invalid Atom/XML response ({e})"

            total_el = root.find(f"{_OPENSEARCH}totalResults")
            total_txt = _local_text(total_el) if total_el is not None else ""

            entries = root.findall(f".//{_ATOM}entry")
            if not entries:
                return "No entries in arXiv response"

            if len(entries) == 1 and _is_error_entry(entries[0]):
                msg = _local_text(entries[0].find(f"{_ATOM}summary")) or "Unknown arXiv API error"
                return f"Error: {msg}"

            normal = [e for e in entries if not _is_error_entry(e)]
            if not normal:
                msg = _local_text(entries[0].find(f"{_ATOM}summary")) or "Unknown arXiv API error"
                return f"Error: {msg}"

            meta = f"arXiv: totalResults≈{total_txt} (showing up to {len(normal)}; start={start_i})" if total_txt else ""
            body = _format_entries(normal, total_hint=meta if meta else None)
            return body or "No papers matched"

        except urllib.error.URLError as e:
            logger.error("arxiv_search URL error: {}", e)
            return f"HTTP error: {e}"
        except Exception as e:
            logger.error("arxiv_search error: {}", e)
            return f"Error: {e}"
