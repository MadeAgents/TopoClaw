#!/usr/bin/env python3
"""Search and score images from Serper-compatible image endpoint.

Environment variables:
  - SERPER_API_KEY  (required)
  - SERPER_API_BASE (optional, recommended for private gateway)

Example:
  python scripts/collect_images.py --query "oppo find 手机" --top 2
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import struct
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any

DEFAULT_COUNT = 10
DEFAULT_TOP = 2
MAX_DOWNLOAD_BYTES = 4 * 1024 * 1024
TIMEOUT_SEC = 15
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
)


@dataclass
class Candidate:
    title: str
    source: str
    page_url: str
    image_url: str
    thumbnail_url: str


def _norm_base(base: str) -> str:
    b = (base or "").strip().rstrip("/")
    if not b:
        return ""
    if b.endswith("/search"):
        return b[: -len("/search")]
    return b


def _resolve_image_endpoints(api_base: str) -> list[str]:
    roots: list[str] = []
    base = _norm_base(api_base)
    if base:
        if base.endswith("/v1"):
            roots.append(base[: -len("/v1")] + "/search/serper_search/images")
        if "/serper_search" in base:
            roots.append(base + "/images")
        roots.append(base + "/search/serper_search/images")
        roots.append(base + "/images")
        roots.append(base + "/search/images")
        if "google.serper.dev" in base and "/serper_search" not in base:
            roots.append(base + "/images")
    roots.append("https://google.serper.dev/images")
    out: list[str] = []
    seen: set[str] = set()
    for u in roots:
        if u not in seen:
            out.append(u)
            seen.add(u)
    return out


def _headers_for(endpoint: str, api_key: str) -> dict[str, str]:
    h = {"Content-Type": "application/json", "User-Agent": USER_AGENT}
    if "google.serper.dev" in endpoint:
        h["X-API-KEY"] = api_key
    else:
        h["Authorization"] = f"Bearer {api_key}"
    return h


def _alternate_headers_for(endpoint: str, api_key: str) -> dict[str, str]:
    """Fallback auth style for gateways with different header conventions."""
    h = {"Content-Type": "application/json", "User-Agent": USER_AGENT}
    if "google.serper.dev" in endpoint:
        h["Authorization"] = f"Bearer {api_key}"
    else:
        h["X-API-KEY"] = api_key
    return h


def _request_json(url: str, *, body: dict[str, Any], headers: dict[str, str]) -> dict[str, Any]:
    data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url=url, data=data, headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=TIMEOUT_SEC) as resp:
        raw = resp.read()
    return json.loads(raw.decode("utf-8", errors="replace"))


def _search(query: str, api_key: str, api_base: str, count: int) -> tuple[list[Candidate], str]:
    payload = {"q": query, "num": max(1, min(count, 20)), "hl": "zh-cn", "gl": "cn"}
    errors: list[str] = []
    unauthorized_hits = 0
    for endpoint in _resolve_image_endpoints(api_base):
        header_candidates = [_headers_for(endpoint, api_key), _alternate_headers_for(endpoint, api_key)]
        for headers in header_candidates:
            try:
                data = _request_json(url=endpoint, body=payload, headers=headers)
                imgs = data.get("images")
                if not isinstance(imgs, list):
                    continue
                out: list[Candidate] = []
                for item in imgs:
                    if not isinstance(item, dict):
                        continue
                    image_url = str(item.get("imageUrl") or item.get("url") or "").strip()
                    thumb = str(item.get("thumbnailUrl") or item.get("thumbnail") or "").strip()
                    if not image_url and not thumb:
                        continue
                    out.append(
                        Candidate(
                            title=str(item.get("title") or "").strip(),
                            source=str(item.get("source") or item.get("domain") or "").strip(),
                            page_url=str(item.get("link") or item.get("pageUrl") or "").strip(),
                            image_url=image_url,
                            thumbnail_url=thumb,
                        )
                    )
                if out:
                    return out, endpoint
            except urllib.error.HTTPError as exc:
                if exc.code == 401:
                    unauthorized_hits += 1
                errors.append(f"{endpoint}: HTTP {exc.code} {exc.reason}")
            except Exception as exc:  # noqa: BLE001
                errors.append(f"{endpoint}: {exc}")
    if unauthorized_hits > 0:
        raise RuntimeError(
            "image search authentication failed (401 Unauthorized). "
            "Please configure SERPER_API_KEY (or SEARCH_API_KEY) for image endpoint access, "
            "and ensure SERPER_API_BASE matches your gateway."
        )
    raise RuntimeError("image search failed: " + " | ".join(errors[-4:]))


def _read_png_size(data: bytes) -> tuple[int, int]:
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n":
        return 0, 0
    w, h = struct.unpack(">II", data[16:24])
    return int(w), int(h)


def _read_gif_size(data: bytes) -> tuple[int, int]:
    if len(data) < 10 or data[:6] not in (b"GIF87a", b"GIF89a"):
        return 0, 0
    w, h = struct.unpack("<HH", data[6:10])
    return int(w), int(h)


def _read_jpeg_size(data: bytes) -> tuple[int, int]:
    i = 2
    n = len(data)
    while i + 9 < n:
        if data[i] != 0xFF:
            i += 1
            continue
        marker = data[i + 1]
        i += 2
        if marker in (0xD8, 0xD9):
            continue
        seg_len = struct.unpack(">H", data[i:i + 2])[0]
        if seg_len < 2 or i + seg_len > n:
            break
        if marker in (0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF):
            h = struct.unpack(">H", data[i + 3:i + 5])[0]
            w = struct.unpack(">H", data[i + 5:i + 7])[0]
            return int(w), int(h)
        i += seg_len
    return 0, 0


def _read_webp_size(data: bytes) -> tuple[int, int]:
    if len(data) < 30 or data[:4] != b"RIFF" or data[8:12] != b"WEBP":
        return 0, 0
    kind = data[12:16]
    if kind == b"VP8X" and len(data) >= 30:
        w = 1 + int.from_bytes(data[24:27], "little")
        h = 1 + int.from_bytes(data[27:30], "little")
        return w, h
    if kind == b"VP8 " and len(data) >= 30:
        w = struct.unpack("<H", data[26:28])[0] & 0x3FFF
        h = struct.unpack("<H", data[28:30])[0] & 0x3FFF
        return int(w), int(h)
    return 0, 0


def _mime_and_size(data: bytes, content_type: str) -> tuple[str, int, int]:
    mime = (content_type or "").split(";")[0].strip().lower()
    if data[:3] == b"\xFF\xD8\xFF":
        w, h = _read_jpeg_size(data)
        return "image/jpeg", w, h
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        w, h = _read_png_size(data)
        return "image/png", w, h
    if data[:6] in (b"GIF87a", b"GIF89a"):
        w, h = _read_gif_size(data)
        return "image/gif", w, h
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        w, h = _read_webp_size(data)
        return "image/webp", w, h
    return (mime or "application/octet-stream"), 0, 0


def _score(c: Candidate, mime: str, w: int, h: int, size: int, query: str) -> tuple[int, list[str]]:
    score = 0
    reasons: list[str] = []
    blob = f"{query} {c.title} {c.source} {c.page_url} {c.image_url}".lower()
    if mime.startswith("image/"):
        score += 12
        reasons.append("mime=image")
    if w > 0 and h > 0:
        reasons.append(f"size={w}x{h}")
        area = w * h
        if area >= 700_000:
            score += 22
            reasons.append("resolution=high")
        elif area >= 250_000:
            score += 10
            reasons.append("resolution=medium")
        else:
            score -= 8
            reasons.append("resolution=low")
        ratio = max(w / max(h, 1), h / max(w, 1))
        if ratio <= 2.4:
            score += 8
            reasons.append("aspect=normal")
        else:
            score -= 8
            reasons.append("aspect=extreme")
    else:
        score -= 6
        reasons.append("size=unknown")

    if size >= 80_000:
        score += 8
        reasons.append("bytes=good")
    elif size < 20_000:
        score -= 10
        reasons.append("bytes=too_small")

    query_tokens = [t for t in re.findall(r"[\w\u4e00-\u9fff]+", query.lower()) if len(t) >= 2]
    if query_tokens:
        hit_query = sum(1 for t in query_tokens if t in blob)
        if hit_query:
            score += min(20, hit_query * 4)
            reasons.append(f"query_keywords={hit_query}")

    neg = ("logo", "icon", "emoji", "thumbnail", "thumb", "banner")
    hit_neg = sum(1 for k in neg if k in blob)
    if hit_neg:
        score -= min(20, hit_neg * 7)
        reasons.append(f"noise_keywords={hit_neg}")
    return score, reasons


def _safe_name(text: str) -> str:
    s = re.sub(r"[^\w\-.]+", "_", (text or "").strip(), flags=re.U)
    return s.strip("._")[:80] or "image"


def _download(url: str) -> tuple[bytes, str]:
    req = urllib.request.Request(url=url, headers={"User-Agent": USER_AGENT, "Accept": "image/*,*/*;q=0.8"})
    with urllib.request.urlopen(req, timeout=TIMEOUT_SEC) as resp:
        ctype = str(resp.headers.get("Content-Type") or "")
        data = resp.read(MAX_DOWNLOAD_BYTES + 1)
    if len(data) > MAX_DOWNLOAD_BYTES:
        raise ValueError("image too large")
    return data, ctype


def run(query: str, count: int, top: int, output_dir: str, api_key: str, api_base: str) -> dict[str, Any]:
    ts = dt.datetime.now().strftime("%Y%m%d_%H%M%S")
    out_dir = Path(output_dir).expanduser().resolve() if output_dir else (Path.cwd() / "tmp" / "image_collect_search" / ts)
    out_dir.mkdir(parents=True, exist_ok=True)

    candidates, endpoint = _search(query=query, api_key=api_key, api_base=api_base, count=count)
    rows: list[dict[str, Any]] = []
    failures: list[dict[str, str]] = []

    for idx, c in enumerate(candidates, 1):
        primary = c.image_url or c.thumbnail_url
        fallback = c.thumbnail_url if primary == c.image_url else c.image_url
        urls = [u for u in [primary, fallback] if u]
        downloaded = False
        last_error = ""
        for used_url in urls:
            try:
                raw, ctype = _download(used_url)
                mime, w, h = _mime_and_size(raw, ctype)
                sc, reasons = _score(c, mime, w, h, len(raw), query)
                ext = {
                    "image/jpeg": ".jpg",
                    "image/png": ".png",
                    "image/webp": ".webp",
                    "image/gif": ".gif",
                }.get(mime, ".bin")
                file_name = f"{idx:02d}_{_safe_name(c.title)}{ext}"
                local_path = out_dir / file_name
                local_path.write_bytes(raw)
                rows.append(
                    {
                        "title": c.title,
                        "source": c.source,
                        "page_url": c.page_url,
                        "image_url": c.image_url,
                        "thumbnail_url": c.thumbnail_url,
                        "used_url": used_url,
                        "local_path": str(local_path),
                        "mime": mime,
                        "width": w,
                        "height": h,
                        "byte_size": len(raw),
                        "score": sc,
                        "reasons": reasons,
                    }
                )
                downloaded = True
                break
            except Exception as exc:  # noqa: BLE001
                last_error = str(exc)
        if not downloaded:
            failures.append({"title": c.title, "image_url": c.image_url, "error": last_error or "download failed"})

    rows.sort(key=lambda x: int(x.get("score") or 0), reverse=True)
    selected = rows[: max(1, min(top, len(rows)))] if rows else []

    report = {
        "query": query,
        "endpoint": endpoint,
        "download_dir": str(out_dir),
        "total_candidates": len(candidates),
        "downloaded_ok": len(rows),
        "selected_count": len(selected),
        "selected": selected,
        "failed": failures,
    }
    (out_dir / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description="Search and score collected images (Serper-compatible).")
    parser.add_argument("--query", required=True, help="search query")
    parser.add_argument("--count", type=int, default=DEFAULT_COUNT, help="candidate image count (default: 10)")
    parser.add_argument("--top", type=int, default=DEFAULT_TOP, help="selected image count (default: 2)")
    parser.add_argument("--output-dir", default="", help="output directory")
    parser.add_argument(
        "--api-key",
        default=(os.environ.get("SERPER_API_KEY", "") or os.environ.get("SEARCH_API_KEY", "")),
        help="override SERPER_API_KEY/SEARCH_API_KEY",
    )
    parser.add_argument("--api-base", default=os.environ.get("SERPER_API_BASE", ""), help="override SERPER_API_BASE")
    args = parser.parse_args()

    api_key = str(args.api_key or "").strip()
    if not api_key:
        print("ERROR: missing API key. Set SERPER_API_KEY or pass --api-key.")
        return 2

    try:
        report = run(
            query=str(args.query or "").strip(),
            count=max(1, min(int(args.count), 20)),
            top=max(1, min(int(args.top), 6)),
            output_dir=str(args.output_dir or "").strip(),
            api_key=api_key,
            api_base=str(args.api_base or "").strip(),
        )
    except Exception as exc:  # noqa: BLE001
        print(f"ERROR: {exc}")
        return 1

    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
