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

"""Academic paper search tools.

- **Semantic Scholar**: ``GET /graph/v1/paper/search`` only (paper relevance search).
- **OpenAlex**: ``GET /works`` search API — see https://developers.openalex.org/guides/searching
"""

from __future__ import annotations

import asyncio
import os
import time
from typing import Any

import httpx
from loguru import logger

from topoclaw.agent.tools.base import Tool

__all__ = ["SemanticScholarPaperSearchTool", "OpenAlexPaperSearchTool"]

SEMANTIC_SCHOLAR_BASE = "https://api.semanticscholar.org"
SEMANTIC_SCHOLAR_SEARCH_PATH = "/graph/v1/paper/search"
SEMANTIC_SCHOLAR_SEARCH_URL = f"{SEMANTIC_SCHOLAR_BASE}{SEMANTIC_SCHOLAR_SEARCH_PATH}"

# FullPaper fields useful for search snippets (no citations/references/embedding).
_DEFAULT_FIELDS = (
    "paperId,title,year,abstract,citationCount,authors,venue,url,externalIds"
)
_MAX_OUTPUT_CHARS = 10_000
_MAX_ABSTRACT_CHARS = 600
# Swagger: limit must be <= 100; default in API is 100.
_MAX_LIMIT = 100


def _author_names(authors: Any) -> str:
    if not isinstance(authors, list):
        return ""
    names: list[str] = []
    for a in authors:
        if isinstance(a, dict) and a.get("name"):
            names.append(str(a["name"]))
        elif isinstance(a, str):
            names.append(a)
    return ", ".join(names)


def _truncate_text(text: str, max_len: int) -> str:
    text = (text or "").strip()
    if len(text) <= max_len:
        return text
    return text[: max_len - 1].rstrip() + "…"


def _format_external_ids(ext: Any) -> str:
    if not isinstance(ext, dict):
        return ""
    parts: list[str] = []
    for k in ("DOI", "ArXiv", "PubMed", "CorpusId"):
        v = ext.get(k)
        if v:
            parts.append(f"{k}:{v}")
    return " · ".join(parts)


class SemanticScholarPaperSearchTool(Tool):
    """Paper relevance search only: ``GET /graph/v1/paper/search``."""

    name = "semantic_scholar_search"
    description = (
        "Semantic Scholar **paper relevance search**. "
        "Returns titles, years, citations, authors, short abstracts, and URLs. "
        "**Prefer this tool first** for academic paper search, but the public API is **prone to rate limits**; "
        "if the call fails or returns an error, **switch to another academic search tool promptly** (e.g. openalex_search)."
    )
    parameters = {
        "type": "object",
        "properties": {
            "query": {
                "type": "string",
                "description": "Plain-text search query (no special syntax; avoid hyphenated terms or use spaces)",
            },
            "limit": {
                "type": "integer",
                "description": "Max results per request (1–100)",
                "minimum": 1,
                "maximum": _MAX_LIMIT,
            },
            "offset": {
                "type": "integer",
                "description": "Pagination offset (0-based)",
                "minimum": 0,
            },
            "publicationTypes": {
                "type": "string",
                "description": "Comma-separated publication types, e.g. Review,JournalArticle,Conference",
            },
            "openAccessPdf": {
                "type": "boolean",
                "description": "If true, only papers with a public PDF",
            },
            "minCitationCount": {
                "type": "string",
                "description": "Minimum citations (API expects string, e.g. '200')",
            },
            "publicationDateOrYear": {
                "type": "string",
                "description": "Date/year range, e.g. 2016-03-05:2020-06-06 or 2015:2020",
            },
            "year": {
                "type": "string",
                "description": "Publication year or range, e.g. 2019 or 2016-2020 or 2010-",
            },
            "venue": {
                "type": "string",
                "description": "Comma-separated venue names or ISO4 abbreviations",
            },
            "fieldsOfStudy": {
                "type": "string",
                "description": "Comma-separated fields, e.g. Computer Science,Physics",
            },
        },
        "required": ["query"],
    }

    def __init__(
        self,
        api_key: str | None = None,
        default_limit: int = 10,
        proxy: str | None = None,
        fields: str | None = None,
    ) -> None:
        self._init_api_key = api_key
        self.default_limit = default_limit
        self.proxy = proxy
        self._fields = fields or _DEFAULT_FIELDS

    @property
    def api_key(self) -> str:
        return (
            self._init_api_key
            or os.environ.get("S2_API_KEY", "")
            or os.environ.get("SEMANTIC_SCHOLAR_API_KEY", "")
        )

    def _build_params(
        self,
        q: str,
        limit: int,
        offset: int,
        *,
        publication_types: str | None = None,
        open_access_pdf: bool | None = None,
        min_citation_count: str | None = None,
        publication_date_or_year: str | None = None,
        year: str | None = None,
        venue: str | None = None,
        fields_of_study: str | None = None,
    ) -> dict[str, Any]:
        params: dict[str, Any] = {
            "query": q,
            "limit": limit,
            "offset": offset,
            "fields": self._fields,
        }
        if publication_types and str(publication_types).strip():
            params["publicationTypes"] = str(publication_types).strip()
        if open_access_pdf is True:
            # Flag parameter: presence only (swagger: does not accept values).
            params["openAccessPdf"] = ""
        if min_citation_count is not None and str(min_citation_count).strip():
            params["minCitationCount"] = str(min_citation_count).strip()
        if publication_date_or_year and str(publication_date_or_year).strip():
            params["publicationDateOrYear"] = str(publication_date_or_year).strip()
        if year is not None and str(year).strip():
            params["year"] = str(year).strip()
        if venue and str(venue).strip():
            params["venue"] = str(venue).strip()
        if fields_of_study and str(fields_of_study).strip():
            params["fieldsOfStudy"] = str(fields_of_study).strip()
        return params

    async def execute(
        self,
        query: str,
        limit: int | None = None,
        offset: int | None = None,
        publicationTypes: str | None = None,
        openAccessPdf: bool | None = None,
        minCitationCount: str | int | None = None,
        publicationDateOrYear: str | None = None,
        year: str | None = None,
        venue: str | None = None,
        fieldsOfStudy: str | None = None,
        **kwargs: Any,
    ) -> str:
        q = (query or "").strip()
        if not q:
            return "Error: query must be a non-empty string."

        n = limit if limit is not None else self.default_limit
        n = max(1, min(int(n), _MAX_LIMIT))
        off = 0 if offset is None else max(0, int(offset))

        min_cc: str | None
        if minCitationCount is None:
            min_cc = None
        else:
            min_cc = str(minCitationCount).strip()

        params = self._build_params(
            q,
            n,
            off,
            publication_types=publicationTypes,
            open_access_pdf=openAccessPdf,
            min_citation_count=min_cc,
            publication_date_or_year=publicationDateOrYear,
            year=year,
            venue=venue,
            fields_of_study=fieldsOfStudy,
        )

        headers: dict[str, str] = {"Accept": "application/json"}
        if self.api_key:
            headers["x-api-key"] = self.api_key

        try:
            logger.debug(
                "SemanticScholar paper/search: {}",
                "with API key" if self.api_key else "anonymous (lower rate limit)",
            )
            async with httpx.AsyncClient(proxy=self.proxy) as client:
                r = await client.get(
                    SEMANTIC_SCHOLAR_SEARCH_URL,
                    params=params,
                    headers=headers,
                    timeout=30.0,
                )
            if r.status_code == 429:
                return (
                    "Semantic Scholar rate limit exceeded. Wait and retry, or set "
                    "S2_API_KEY (or SEMANTIC_SCHOLAR_API_KEY) for higher limits. "
                    "See https://www.semanticscholar.org/product/api"
                )
            r.raise_for_status()
            payload = r.json()
        except httpx.ProxyError as e:
            logger.error("SemanticScholar proxy error: {}", e)
            return f"Proxy error: {e}"
        except httpx.HTTPStatusError as e:
            logger.error("SemanticScholar HTTP error: {}", e)
            return f"HTTP error: {e.response.status_code} {e.response.text[:500]}"
        except Exception as e:
            logger.error("SemanticScholar error: {}", e)
            return f"Error: {e}"

        total_raw = payload.get("total")
        try:
            total = int(total_raw) if total_raw is not None else 0
        except (TypeError, ValueError):
            total = 0
        rows = payload.get("data")
        if not isinstance(rows, list) or not rows:
            return f"No papers found for: {q}"

        lines: list[str] = [
            f"Semantic Scholar (paper/search): {total} total matches (showing {len(rows)} from offset {off})\n",
        ]
        for i, paper in enumerate(rows, 1):
            if not isinstance(paper, dict):
                continue
            title = paper.get("title") or "(no title)"
            pyear = paper.get("year")
            cite = paper.get("citationCount")
            v = paper.get("venue") or ""
            url = paper.get("url") or ""
            authors = _author_names(paper.get("authors"))
            abstract = _truncate_text(
                str(paper.get("abstract") or ""), _MAX_ABSTRACT_CHARS
            )
            ext_line = _format_external_ids(paper.get("externalIds"))

            meta_parts: list[str] = []
            if pyear is not None:
                meta_parts.append(str(pyear))
            if cite is not None:
                meta_parts.append(f"citations={cite}")
            if v:
                meta_parts.append(v)
            meta = " · ".join(meta_parts)

            block = [f"{i}. {title}"]
            if meta:
                block.append(f"   {meta}")
            if authors:
                block.append(f"   Authors: {authors}")
            if abstract:
                block.append(f"   {abstract}")
            if ext_line:
                block.append(f"   IDs: {ext_line}")
            if url:
                block.append(f"   {url}")
            lines.append("\n".join(block))

        out = "\n\n".join(lines)
        if len(out) > _MAX_OUTPUT_CHARS:
            out = out[: _MAX_OUTPUT_CHARS - 1].rstrip() + "…"
        return out


# --- OpenAlex: https://api.openalex.org/works ---

OPENALEX_WORKS_URL = "https://api.openalex.org/works"
_OPENALEX_SEMANTIC_MAX_PER_PAGE = 50
_OPENALEX_MAX_PER_PAGE = 200


def _openalex_author_names(data: dict[str, Any]) -> str:
    names: list[str] = []
    for authorship in data.get("authorships") or []:
        if not isinstance(authorship, dict):
            continue
        author = authorship.get("author") or {}
        if isinstance(author, dict):
            n = author.get("display_name")
            if n:
                names.append(str(n))
    return ", ".join(names[:30])


def _openalex_short_id(work: dict[str, Any]) -> str:
    oid = work.get("id") or ""
    return str(oid).replace("https://openalex.org/", "")


def _openalex_format_work(work: dict[str, Any], index: int, *, abstract_max: int) -> list[str]:
    title = work.get("display_name") or "(no title)"
    year = work.get("publication_year")
    cites = work.get("cited_by_count")
    score = work.get("relevance_score")
    authors = _openalex_author_names(work)
    abstract = _truncate_text(str(work.get("abstract") or ""), abstract_max)
    doi = work.get("doi") or ""
    landing = work.get("landing_page_url") or ""
    oa = (work.get("open_access") or {}) if isinstance(work.get("open_access"), dict) else {}
    pdf_url = oa.get("pdf_url") or ""
    is_oa = bool(oa.get("is_oa"))
    wtype = work.get("type") or ""

    block = [f"{index}. {title}"]
    meta: list[str] = []
    if year is not None:
        meta.append(str(year))
    if cites is not None:
        meta.append(f"cited_by={cites}")
    if score is not None:
        try:
            meta.append(f"relevance={float(score):.4f}")
        except (TypeError, ValueError):
            pass
    if wtype:
        meta.append(str(wtype))
    if is_oa:
        meta.append("OA")
    if meta:
        block.append(f"   {' · '.join(meta)}")
    if authors:
        block.append(f"   Authors: {authors}")
    if abstract:
        block.append(f"   {abstract}")
    ids: list[str] = []
    if doi:
        ids.append(f"doi:{doi}")
    short = _openalex_short_id(work)
    if short:
        ids.append(f"id:{short}")
    if ids:
        block.append(f"   {' · '.join(ids)}")
    if pdf_url:
        block.append(f"   PDF: {pdf_url}")
    if landing:
        block.append(f"   {landing}")
    return block


class OpenAlexPaperSearchTool(Tool):
    """Search works via OpenAlex ``GET /works`` (keyword, semantic, or exact search)."""

    name = "openalex_search"
    description = (
        "Search scholarly works via OpenAlex (https://openalex.org). "
        "Supports keyword / semantic / exact search, filters (year, OA, min citations), "
        "and optional sort. Returns titles, authors, years, citations, abstracts, links."
    )
    parameters = {
        "type": "object",
        "properties": {
            "query": {
                "type": "string",
                "description": "Search query (plain text; use searchMode=exact for phrase-style matching)",
            },
            "searchMode": {
                "type": "string",
                "enum": ["keyword", "semantic", "exact"],
                "description": "keyword=default fulltext; semantic=vector similarity (max 50 per page); exact=phrase search",
            },
            "perPage": {
                "type": "integer",
                "description": "Results per page (1–200; semantic search capped at 50)",
                "minimum": 1,
                "maximum": _OPENALEX_MAX_PER_PAGE,
            },
            "page": {
                "type": "integer",
                "description": "Page number (1-based)",
                "minimum": 1,
            },
            "sort": {
                "type": "string",
                "description": "Optional sort, e.g. cited_by_count:desc (ignored when searchMode=semantic)",
            },
            "yearFrom": {
                "type": "integer",
                "description": "Filter: minimum publication year (inclusive)",
            },
            "yearTo": {
                "type": "integer",
                "description": "Filter: maximum publication year (inclusive)",
            },
            "openAccessOnly": {
                "type": "boolean",
                "description": "If true, only open-access works",
            },
            "minCitations": {
                "type": "integer",
                "description": "Filter: minimum cited_by_count",
                "minimum": 0,
            },
        },
        "required": ["query"],
    }

    def __init__(
        self,
        api_key: str | None = None,
        proxy: str | None = None,
        default_per_page: int = 20,
        timeout_s: float = 30.0,
        rate_limit_delay_s: float = 0.1,
        user_agent: str | None = None,
    ) -> None:
        self._init_api_key = api_key
        self.proxy = proxy
        self.default_per_page = default_per_page
        self.timeout_s = timeout_s
        self.rate_limit_delay_s = rate_limit_delay_s
        self._init_user_agent = user_agent
        self._last_request_monotonic: float = 0.0

    @property
    def api_key(self) -> str:
        return self._init_api_key or os.environ.get("OPENALEX_API_KEY", "")

    def _build_works_params(
        self,
        q: str,
        *,
        search_mode: str,
        per_page: int,
        page: int,
        sort: str | None,
        year_from: int | None,
        year_to: int | None,
        open_access_only: bool | None,
        min_citations: int | None,
    ) -> dict[str, Any]:
        mode = (search_mode or "keyword").lower().strip()
        if mode not in ("keyword", "semantic", "exact"):
            mode = "keyword"

        params: dict[str, Any] = {"page": max(1, page)}

        if mode == "semantic":
            params["search.semantic"] = q
            cap = _OPENALEX_SEMANTIC_MAX_PER_PAGE
        elif mode == "exact":
            params["search.exact"] = q
            cap = _OPENALEX_MAX_PER_PAGE
        else:
            params["search"] = q
            cap = _OPENALEX_MAX_PER_PAGE

        pp = max(1, min(int(per_page), cap))
        params["per-page"] = pp

        if sort and mode != "semantic":
            params["sort"] = sort

        filter_parts: list[str] = []
        if year_from is not None or year_to is not None:
            yf = int(year_from) if year_from is not None else None
            yt = int(year_to) if year_to is not None else None
            if yf is not None and yt is not None:
                filter_parts.append(f"publication_year:{yf}-{yt}")
            elif yf is not None:
                filter_parts.append(f"publication_year:{yf}-")
            else:
                filter_parts.append(f"publication_year:-{yt}")
        if open_access_only is True:
            filter_parts.append("is_oa:true")
        if min_citations is not None:
            filter_parts.append(f"cited_by_count:>{int(min_citations)}")

        if filter_parts:
            params["filter"] = ",".join(filter_parts)

        return params

    async def _get_works_json(self, params: dict[str, Any]) -> dict[str, Any]:
        now = time.monotonic()
        elapsed = now - self._last_request_monotonic
        if elapsed < self.rate_limit_delay_s and self._last_request_monotonic > 0:
            await asyncio.sleep(self.rate_limit_delay_s - elapsed)

        headers: dict[str, str] = {
            "Accept": "application/json",
        }
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"

        async with httpx.AsyncClient(proxy=self.proxy, timeout=self.timeout_s) as client:
            r = await client.get(OPENALEX_WORKS_URL, params=params, headers=headers)
        self._last_request_monotonic = time.monotonic()
        r.raise_for_status()
        return r.json()

    async def execute(
        self,
        query: str,
        searchMode: str = "keyword",
        perPage: int | None = None,
        page: int | None = None,
        sort: str | None = None,
        yearFrom: int | None = None,
        yearTo: int | None = None,
        openAccessOnly: bool | None = None,
        minCitations: int | None = None,
        **kwargs: Any,
    ) -> str:
        q = (query or "").strip()
        if not q:
            return "Error: query must be a non-empty string."

        pp = perPage if perPage is not None else self.default_per_page
        pg = page if page is not None else 1
        mode = (searchMode or "keyword").lower().strip()
        if mode == "semantic":
            pp = min(max(1, int(pp)), _OPENALEX_SEMANTIC_MAX_PER_PAGE)
        else:
            pp = min(max(1, int(pp)), _OPENALEX_MAX_PER_PAGE)

        params = self._build_works_params(
            q,
            search_mode=mode,
            per_page=pp,
            page=max(1, int(pg)),
            sort=sort,
            year_from=yearFrom,
            year_to=yearTo,
            open_access_only=openAccessOnly,
            min_citations=minCitations,
        )

        try:
            logger.debug("OpenAlex works search: mode={} per-page={}", mode, params.get("per-page"))
            payload = await self._get_works_json(params)
        except httpx.ProxyError as e:
            logger.error("OpenAlex proxy error: {}", e)
            return f"Proxy error: {e}"
        except httpx.HTTPStatusError as e:
            logger.error("OpenAlex HTTP error: {}", e)
            return f"HTTP error: {e.response.status_code} {e.response.text[:500]}"
        except Exception as e:
            logger.error("OpenAlex error: {}", e)
            return f"Error: {e}"

        meta = payload.get("meta") if isinstance(payload.get("meta"), dict) else {}
        total = meta.get("count")
        results = payload.get("results")
        if not isinstance(results, list) or not results:
            return f"No works found for: {q}"

        try:
            total_s = str(int(total)) if total is not None else "?"
        except (TypeError, ValueError):
            total_s = str(total) if total is not None else "?"

        lines: list[str] = [
            f"OpenAlex (works): ~{total_s} matches (page {meta.get('page', pg)}, "
            f"per-page {meta.get('per_page', pp)}, mode={mode})\n",
        ]
        for i, item in enumerate(results, 1):
            if not isinstance(item, dict):
                continue
            lines.append("\n".join(_openalex_format_work(item, i, abstract_max=_MAX_ABSTRACT_CHARS)))

        out = "\n\n".join(lines)
        if len(out) > _MAX_OUTPUT_CHARS:
            out = out[: _MAX_OUTPUT_CHARS - 1].rstrip() + "…"
        return out
