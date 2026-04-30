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

"""
Web / news / scholar search via UniAPI Serper-compatible endpoints.

- Web: ``POST .../serper_search/search`` — organic, knowledgeGraph, peopleAlsoAsk, relatedSearches
- News: ``POST .../serper_search/news`` — news[]
- Scholar: ``POST .../serper_search/scholar`` — organic (publicationInfo, year, citedBy, pdfUrl, …)

See ``docs/serper-uni-api.md``. Bearer token: ``SERPER_API_KEY``.

``SERPER_API_BASE``: optional root like ``https://your-host/search/serper_search``
(no trailing ``/search``); the last path segment is chosen from ``search_type``.
A value ending in ``/search``, ``/news``, or ``/scholar`` is normalized to that root.
"""

import os
from typing import Any

import httpx
from loguru import logger

from topoclaw.agent.tools.base import Tool
from topoclaw.config.loader import load_config

# Default API root (append: search | news | scholar)
_DEFAULT_SERPER_ROOT = ""
_NUM_MIN = 1
_NUM_MAX = 100

# Maps tool search_type -> URL path segment (web uses "search" in UniAPI)
_MODE_SEGMENT: dict[str, str] = {"web": "search", "news": "news", "scholar": "scholar"}


def _read_search_config() -> tuple[str, str]:
    try:
        cfg = load_config()
        search = cfg.tools.web.search
        return (search.api_key or "").strip(), (search.serper_api_base or "").strip()
    except Exception:
        return "", ""


def _resolve_api_key(init: str | None, config_key: str) -> str:
    return (init or os.environ.get("SERPER_API_KEY") or config_key or "").strip()


def _canonical_serper_root(config_base: str = "") -> str:
    raw = (config_base or os.environ.get("SERPER_API_BASE") or "").strip().rstrip("/")
    if not raw:
        return _DEFAULT_SERPER_ROOT
    for suf in ("/search", "/news", "/scholar"):
        if raw.endswith(suf):
            return raw[: -len(suf)]
    return raw


def _resolve_search_url(search_type: str, config_base: str = "") -> str:
    seg = _MODE_SEGMENT.get(search_type, "search")
    root = _canonical_serper_root(config_base)
    if not root:
        return ""
    # google.serper.dev: base is host only → /search, /news, /scholar
    if "google.serper.dev" in root and "/serper_search" not in root:
        return f"{root.rstrip('/')}/{seg}"
    return f"{root}/{seg}"


def _format_organic(items: list[dict[str, Any]], query: str) -> list[str]:
    lines: list[str] = [f"Results for: {query}"]
    for i, item in enumerate(items, 1):
        title = item.get("title") or ""
        link = item.get("link") or ""
        head = f"{i}. {title}"
        lines.append(head)
        if link:
            lines.append(f"   {link}")
        if snip := item.get("snippet"):
            lines.append(f"   {snip}")
        if date := item.get("date"):
            lines.append(f"   [{date}]")
        attrs = item.get("attributes")
        if isinstance(attrs, dict) and attrs:
            for ak, av in list(attrs.items())[:6]:
                lines.append(f"   {ak}: {av}")
        sitelinks = item.get("sitelinks")
        if isinstance(sitelinks, list) and sitelinks:
            for sl in sitelinks[:4]:
                if not isinstance(sl, dict):
                    continue
                slt = sl.get("title") or ""
                sll = sl.get("link") or ""
                if slt or sll:
                    lines.append(f"   · {slt} {sll}".strip())
    return lines


def _format_news(items: list[dict[str, Any]], query: str) -> list[str]:
    lines: list[str] = [f"News results for: {query}"]
    for i, item in enumerate(items, 1):
        if not isinstance(item, dict):
            continue
        title = item.get("title") or ""
        link = item.get("link") or ""
        lines.append(f"{i}. {title}")
        if link:
            lines.append(f"   {link}")
        if src := item.get("source"):
            lines.append(f"   Source: {src}")
        if dt := item.get("date"):
            lines.append(f"   Date: {dt}")
        if snip := item.get("snippet"):
            first = (snip or "").replace("\n", " ").strip()[:400]
            if first:
                lines.append(f"   {first}")
    return lines


def _format_scholar(items: list[dict[str, Any]], query: str) -> list[str]:
    lines: list[str] = [f"Scholar results for: {query}"]
    for i, item in enumerate(items, 1):
        if not isinstance(item, dict):
            continue
        title = item.get("title") or ""
        link = item.get("link") or ""
        lines.append(f"{i}. {title}")
        if link:
            lines.append(f"   {link}")
        if pub := item.get("publicationInfo"):
            lines.append(f"   {pub}")
        if (yr := item.get("year")) is not None:
            lines.append(f"   Year: {yr}")
        if (cb := item.get("citedBy")) is not None:
            lines.append(f"   Cited by: {cb}")
        if pdf := item.get("pdfUrl"):
            lines.append(f"   PDF: {pdf}")
        if snip := item.get("snippet"):
            first = (snip or "").replace("\n", " ").strip()[:350]
            if first:
                lines.append(f"   {first}")
    return lines


def _format_people_also_ask(items: list[dict[str, Any]]) -> list[str]:
    lines: list[str] = ["", "[People also ask]"]
    for it in items[:8]:
        if not isinstance(it, dict):
            continue
        q = it.get("question") or ""
        if q:
            lines.append(f"   Q: {q}")
        if sn := it.get("snippet"):
            first = (sn or "").replace("\n", " ").strip()[:240]
            if first:
                lines.append(f"      {first}")
        if tl := it.get("title"):
            lines.append(f"      ({tl})")
        if lk := it.get("link"):
            lines.append(f"      {lk}")
    return lines


def _format_related_searches(items: list[dict[str, Any]]) -> list[str]:
    lines: list[str] = ["", "[Related searches]"]
    for it in items[:12]:
        if isinstance(it, dict) and (rq := it.get("query")):
            lines.append(f"   · {rq}")
        elif isinstance(it, str) and it.strip():
            lines.append(f"   · {it.strip()}")
    return lines


def _serper_http_error_message(status: int, text: str) -> str:
    snippet = (text or "")[:400].strip()
    if snippet:
        return f"Error: HTTP {status} — {snippet}"
    return f"Error: HTTP {status}"


def _request_headers(url: str, api_key: str) -> dict[str, str]:
    """Serper.dev uses ``X-API-KEY``; UniAPI and similar proxies use ``Authorization: Bearer``."""
    h = {"Content-Type": "application/json"}
    if "google.serper.dev" in url:
        h["X-API-KEY"] = api_key
    else:
        h["Authorization"] = f"Bearer {api_key}"
    return h


class SerperSearchTool(Tool):
    """General-purpose web search tool (news and academic modes via search_type)."""

    name = "serper_search"
    description = (
        "Versatile search for open-web information: everyday web lookup, current news, "
        "and academic literature (papers and scholarly sources). "
        "Switch the kind of search with search_type when the task calls for news or research, "
        "otherwise use the default for general queries."
    )
    parameters = {
        "type": "object",
        "properties": {
            "query": {
                "type": "string",
                "description": "Search query",
            },
            "search_type": {
                "type": "string",
                "enum": ["web", "news", "scholar"],
                "description": (
                    "web: general search (default); news: news-focused; scholar: academic and paper-oriented"
                ),
            },
            "count": {
                "type": "integer",
                "description": "Number of organic results (1–100; default follows tool max_results)",
                "minimum": _NUM_MIN,
                "maximum": _NUM_MAX,
            },
            "gl": {
                "type": "string",
                "description": "Optional country code for results (e.g. us, cn)",
            },
            "hl": {
                "type": "string",
                "description": "Optional interface language (e.g. en, zh-cn)",
            },
            "location": {
                "type": "string",
                "description": "Optional location string for localized results (e.g. China)",
            },
            "autocorrect": {
                "type": "boolean",
                "description": "Enable autocorrect (API default applies if omitted)",
            },
            "tbs": {
                "type": "string",
                "description": (
                    "Optional time filter (Google tbs), e.g. qdr:h (past hour), qdr:d (day), "
                    "qdr:w, qdr:m, qdr:y"
                ),
            },
            "page": {
                "type": "integer",
                "description": "Result page number (1-based; default 1)",
                "minimum": 1,
            },
        },
        "required": ["query"],
    }

    def __init__(self, api_key: str | None = None, max_results: int = 10, proxy: str | None = None):
        self._init_api_key = api_key
        self.max_results = max_results
        self.proxy = proxy

    @property
    def api_key(self) -> str:
        cfg_key, _ = _read_search_config()
        return _resolve_api_key(self._init_api_key, cfg_key)

    @property
    def api_base(self) -> str:
        _, cfg_base = _read_search_config()
        return cfg_base

    async def execute(self, query: str, **kwargs: Any) -> str:
        if not self.api_key:
            return (
                "Error: SERPER_API_KEY not configured. "
                "Set tools.web.search.api_key in config.json "
                "(or SERPER_API_KEY; optional tools.web.search.serper_api_base / SERPER_API_BASE), then restart."
            )

        q = (query or "").strip()
        if not q:
            return "Error: query is empty"

        count = kwargs.get("count")
        if isinstance(count, (int, float)):
            n = int(count)
        else:
            n = self.max_results
        n = min(max(n, _NUM_MIN), _NUM_MAX)

        payload: dict[str, Any] = {"q": q, "num": n}
        if (gl := kwargs.get("gl")) and isinstance(gl, str) and gl.strip():
            payload["gl"] = gl.strip()
        if (hl := kwargs.get("hl")) and isinstance(hl, str) and hl.strip():
            payload["hl"] = hl.strip()
        if (loc := kwargs.get("location")) and isinstance(loc, str) and loc.strip():
            payload["location"] = loc.strip()
        if "autocorrect" in kwargs and kwargs["autocorrect"] is not None:
            payload["autocorrect"] = bool(kwargs["autocorrect"])
        if (tbs := kwargs.get("tbs")) and isinstance(tbs, str) and tbs.strip():
            payload["tbs"] = tbs.strip()
        page_kw = kwargs.get("page")
        if isinstance(page_kw, (int, float)):
            p = max(int(page_kw), 1)
            payload["page"] = p

        raw_mode = kwargs.get("search_type")
        if isinstance(raw_mode, str) and raw_mode.strip().lower() in _MODE_SEGMENT:
            search_type = raw_mode.strip().lower()
        else:
            search_type = "web"

        url = _resolve_search_url(search_type, self.api_base)
        if not url:
            return (
                "Error: Serper API base URL not configured. "
                "Set tools.web.search.serper_api_base in config.json "
                "(or SERPER_API_BASE), then restart."
            )
        headers = _request_headers(url, self.api_key)

        try:
            logger.debug("serper_search: {} url={}", "proxy" if self.proxy else "direct", url)
            async with httpx.AsyncClient(proxy=self.proxy) as client:
                r = await client.post(url, json=payload, headers=headers, timeout=60.0)

            try:
                data = r.json()
            except Exception:
                r.raise_for_status()
                return _serper_http_error_message(r.status_code, r.text)

            if not isinstance(data, dict):
                return "Error: unexpected response shape"

            if r.status_code >= 400:
                msg = data.get("message")
                if isinstance(msg, str) and msg.strip():
                    return f"Error: {msg.strip()}"
                return _serper_http_error_message(r.status_code, r.text)

            if sp := data.get("searchParameters"):
                if isinstance(sp, dict) and (eq := sp.get("q")):
                    q_display = str(eq)
                else:
                    q_display = q
            else:
                q_display = q

            lines: list[str] = []

            if search_type == "news":
                news_items = data.get("news")
                if news_items is None:
                    if isinstance(data.get("message"), str) and (data.get("message") or "").strip():
                        return f"Error: {str(data.get('message')).strip()}"
                    news_items = []
                if not isinstance(news_items, list):
                    news_items = []
                if news_items:
                    lines.extend(_format_news(news_items, q_display))
                else:
                    lines.append(f"No news results for: {q_display}")

            elif search_type == "scholar":
                organic = data.get("organic")
                if organic is None:
                    if isinstance(data.get("message"), str) and (data.get("message") or "").strip():
                        return f"Error: {str(data.get('message')).strip()}"
                    organic = []
                if not isinstance(organic, list):
                    organic = []
                if organic:
                    lines.extend(_format_scholar(organic, q_display))
                else:
                    lines.append(f"No scholar results for: {q_display}")

            else:
                organic = data.get("organic")
                if organic is None:
                    if isinstance(data.get("message"), str) and (data.get("message") or "").strip():
                        return f"Error: {str(data.get('message')).strip()}"
                    organic = []
                if not isinstance(organic, list):
                    organic = []

                if organic:
                    lines.extend(_format_organic(organic, q_display))
                else:
                    lines.append(f"No organic results for: {q_display}")

                kg = data.get("knowledgeGraph")
                if isinstance(kg, dict) and (kgt := kg.get("title")):
                    lines.append("")
                    lines.append(f"[Knowledge graph] {kgt}")
                    if kt := kg.get("type"):
                        lines.append(f"   Type: {kt}")
                    if d := kg.get("description"):
                        lines.append(f"   {d}")
                    if w := kg.get("website"):
                        lines.append(f"   {w}")
                    kg_attrs = kg.get("attributes")
                    if isinstance(kg_attrs, dict) and kg_attrs:
                        lines.append("   Attributes:")
                        for ak, av in list(kg_attrs.items())[:12]:
                            lines.append(f"      {ak}: {av}")

                paa = data.get("peopleAlsoAsk")
                if isinstance(paa, list) and paa:
                    lines.extend(_format_people_also_ask(paa))

                rel = data.get("relatedSearches")
                if isinstance(rel, list) and rel:
                    lines.extend(_format_related_searches(rel))

            out = "\n".join(lines).strip()
            return out or f"No results for: {q_display}"

        except httpx.ProxyError as e:
            logger.error("serper_search proxy error: {}", e)
            return f"Proxy error: {e}"
        except Exception as e:
            logger.error("serper_search error: {}", e)
            return f"Error: {e}"
