"""Deeplink catalog storage, sync, and millisecond-level retrieval."""

from __future__ import annotations

import json
import re
import threading
import time
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from topoclaw.config.paths import get_data_dir

_TOKEN_RE = re.compile(r"[a-z0-9]+|[\u4e00-\u9fff]+")


def _normalize_text(value: str) -> str:
    text = unicodedata.normalize("NFKC", str(value or "")).lower()
    return re.sub(r"\s+", "", text)


def _extract_tokens(value: str) -> set[str]:
    normalized = _normalize_text(value)
    if not normalized:
        return set()
    out: set[str] = set()
    for part in _TOKEN_RE.findall(normalized):
        out.add(part)
        if re.fullmatch(r"[\u4e00-\u9fff]+", part):
            out.update(ch for ch in part if ch.strip())
    return out


def _extract_ngrams(value: str, n: int = 2) -> set[str]:
    normalized = _normalize_text(value)
    if not normalized:
        return set()
    if len(normalized) <= n:
        return {normalized}
    return {normalized[i : i + n] for i in range(len(normalized) - n + 1)}


@dataclass(slots=True)
class DeeplinkCatalogItem:
    id: int
    app_name: str
    feature_name: str
    deeplink: str
    call_method: str
    description: str
    parameters: list[str]
    notes: str
    app_norm: str
    feature_norm: str
    description_norm: str
    deeplink_norm: str
    tokens: set[str]
    ngrams: set[str]


class DeeplinkCatalogService:
    """Store deeplink catalog and perform low-latency top-k retrieval."""

    def __init__(self, catalog_path: Path | None = None) -> None:
        self.catalog_path = catalog_path or (get_data_dir() / "deeplink_catalog.json")
        self._lock = threading.RLock()
        self._loaded = False
        self._source = ""
        self._schema_version = ""
        self._catalog_version = ""
        self._source_version = ""
        self._items: list[DeeplinkCatalogItem] = []
        self._app_to_indices: dict[str, list[int]] = {}
        self._index_built_ms = 0

    def _build_index(self, doc: dict[str, Any], source_version: str | None = None) -> None:
        items_raw = doc.get("items")
        if not isinstance(items_raw, list):
            raise ValueError("items must be a list")

        items: list[DeeplinkCatalogItem] = []
        app_to_indices: dict[str, list[int]] = {}

        for idx, raw in enumerate(items_raw, start=1):
            if not isinstance(raw, dict):
                continue
            app_name = str(raw.get("app_name") or "").strip()
            feature_name = str(raw.get("feature_name") or "").strip()
            deeplink = str(raw.get("deeplink") or "").strip()
            if not (app_name and feature_name and deeplink):
                continue

            description = str(raw.get("description") or feature_name).strip()
            call_method = str(raw.get("call_method") or "").strip()
            notes = str(raw.get("notes") or "").strip()
            params_raw = raw.get("parameters")
            parameters = (
                [str(x).strip() for x in params_raw if str(x).strip()]
                if isinstance(params_raw, list)
                else []
            )
            item_id_raw = raw.get("id")
            item_id = int(item_id_raw) if isinstance(item_id_raw, int) else idx

            app_norm = _normalize_text(app_name)
            feature_norm = _normalize_text(feature_name)
            description_norm = _normalize_text(description)
            deeplink_norm = _normalize_text(deeplink)

            search_blob = " ".join([app_name, feature_name, description, deeplink, " ".join(parameters)])
            tokens = _extract_tokens(search_blob)
            ngrams = _extract_ngrams(search_blob, n=2)

            item = DeeplinkCatalogItem(
                id=item_id,
                app_name=app_name,
                feature_name=feature_name,
                deeplink=deeplink,
                call_method=call_method,
                description=description,
                parameters=parameters,
                notes=notes,
                app_norm=app_norm,
                feature_norm=feature_norm,
                description_norm=description_norm,
                deeplink_norm=deeplink_norm,
                tokens=tokens,
                ngrams=ngrams,
            )
            items.append(item)
            app_to_indices.setdefault(app_norm, []).append(len(items) - 1)

        self._items = items
        self._app_to_indices = app_to_indices
        self._schema_version = str(doc.get("schema_version") or "").strip()
        self._catalog_version = str(doc.get("catalog_version") or "").strip()
        self._source = str(doc.get("source") or "").strip()
        self._source_version = str(source_version or doc.get("source_version") or "").strip()
        self._index_built_ms = int(time.time() * 1000)
        self._loaded = True

    def _load_if_needed(self) -> None:
        if self._loaded:
            return
        with self._lock:
            if self._loaded:
                return
            if not self.catalog_path.is_file():
                self._loaded = True
                self._items = []
                return
            doc = json.loads(self.catalog_path.read_text(encoding="utf-8"))
            self._build_index(doc)

    def sync_catalog(self, payload: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(payload, dict):
            return {"ok": False, "error": "payload must be an object"}
        if not isinstance(payload.get("items"), list):
            return {"ok": False, "error": "payload.items must be a list"}

        schema_version = str(payload.get("schema_version") or "1.0.0").strip()
        catalog_version = str(payload.get("catalog_version") or "").strip()
        source = str(payload.get("source") or "TopoDesktop").strip()
        source_version = str(payload.get("source_version") or "").strip()

        doc = {
            "schema_version": schema_version,
            "catalog_version": catalog_version,
            "source": source,
            "source_version": source_version,
            "total": len(payload.get("items") or []),
            "items": payload.get("items") or [],
        }

        with self._lock:
            self.catalog_path.parent.mkdir(parents=True, exist_ok=True)
            self.catalog_path.write_text(
                json.dumps(doc, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            self._build_index(doc, source_version=source_version)

        return {
            "ok": True,
            "catalog_path": str(self.catalog_path),
            "total": len(self._items),
            "schema_version": self._schema_version,
            "catalog_version": self._catalog_version,
            "source": self._source,
            "source_version": self._source_version,
            "index_built_ms": self._index_built_ms,
        }

    def status(self) -> dict[str, Any]:
        self._load_if_needed()
        return {
            "ok": True,
            "catalog_path": str(self.catalog_path),
            "loaded": bool(self._loaded),
            "total": len(self._items),
            "schema_version": self._schema_version,
            "catalog_version": self._catalog_version,
            "source": self._source,
            "source_version": self._source_version,
            "index_built_ms": self._index_built_ms,
        }

    def search(self, *, query: str, app_name: str | None = None, top_k: int = 3) -> dict[str, Any]:
        started = time.perf_counter()
        self._load_if_needed()

        q = str(query or "").strip()
        if not q:
            return {"ok": False, "error": "query is required"}
        if not self._items:
            return {"ok": False, "error": "deeplink catalog is empty; sync from TopoDesktop first"}

        k = max(1, min(int(top_k or 3), 5))
        app_filter = str(app_name or "").strip()
        app_norm = _normalize_text(app_filter) if app_filter else ""

        q_norm = _normalize_text(q)
        q_tokens = _extract_tokens(q)
        q_ngrams = _extract_ngrams(q, n=2)

        candidate_indices: list[int]
        app_filter_applied = False
        if app_norm and app_norm in self._app_to_indices:
            candidate_indices = self._app_to_indices[app_norm]
            app_filter_applied = True
        else:
            candidate_indices = list(range(len(self._items)))

        scored: list[tuple[float, DeeplinkCatalogItem, list[str]]] = []
        for idx in candidate_indices:
            item = self._items[idx]
            score = 0.0
            reasons: list[str] = []

            if q_norm and q_norm in item.feature_norm:
                score += 140
                reasons.append("feature_hit")
            if q_norm and q_norm in item.description_norm:
                score += 90
                reasons.append("description_hit")
            if q_norm and q_norm in item.deeplink_norm:
                score += 70
                reasons.append("deeplink_hit")
            if q_norm and q_norm in item.app_norm:
                score += 50
                reasons.append("app_hit")
            if q_norm and item.feature_norm.startswith(q_norm):
                score += 35
                reasons.append("feature_prefix")

            if q_tokens:
                overlap = q_tokens & item.tokens
                if overlap:
                    score += len(overlap) * 14
                    reasons.append(f"token_overlap:{len(overlap)}")

            if q_ngrams:
                overlap_ng = q_ngrams & item.ngrams
                if overlap_ng:
                    score += min(len(overlap_ng), 24) * 3
                    reasons.append(f"ngram_overlap:{len(overlap_ng)}")

            if app_filter_applied:
                score += 20
                reasons.append("app_filter")

            if score <= 0:
                continue
            scored.append((score, item, reasons))

        scored.sort(key=lambda x: (-x[0], x[1].id))
        top = scored[:k]

        results: list[dict[str, Any]] = []
        for rank, (score, item, reasons) in enumerate(top, start=1):
            results.append(
                {
                    "rank": rank,
                    "id": item.id,
                    "app_name": item.app_name,
                    "feature_name": item.feature_name,
                    "deeplink": item.deeplink,
                    "call_method": item.call_method,
                    "description": item.description,
                    "parameters": item.parameters,
                    "notes": item.notes,
                    "score": round(score, 2),
                    "match_reason": ",".join(reasons),
                }
            )

        latency_ms = int((time.perf_counter() - started) * 1000)
        return {
            "ok": True,
            "query": q,
            "app_name": app_filter or None,
            "app_filter_applied": app_filter_applied,
            "top_k": k,
            "total_candidates": len(candidate_indices),
            "returned": len(results),
            "results": results,
            "latency_ms": latency_ms,
            "catalog_version": self._catalog_version,
            "schema_version": self._schema_version,
            "source_version": self._source_version,
        }


_DEFAULT_SERVICE: DeeplinkCatalogService | None = None


def get_default_deeplink_catalog_service() -> DeeplinkCatalogService:
    global _DEFAULT_SERVICE
    if _DEFAULT_SERVICE is None:
        _DEFAULT_SERVICE = DeeplinkCatalogService()
    return _DEFAULT_SERVICE

