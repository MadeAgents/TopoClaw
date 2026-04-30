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

"""Read media: images via a vision LLM (base64 data URL); PDFs via pypdf text extraction."""

from __future__ import annotations

import base64
import mimetypes
import re
from pathlib import Path
from typing import TYPE_CHECKING, Any

from topoclaw.agent.tools.base import Tool
from topoclaw.agent.tools.filesystem import _resolve_path
from topoclaw.utils.helpers import detect_image_mime

if TYPE_CHECKING:
    from topoclaw.providers.base import LLMProvider

_SUFFIX_TO_MIME: dict[str, str] = {
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".gif": "image/gif",
    ".webp": "image/webp",
    ".bmp": "image/bmp",
}

_MAX_IMAGE_BYTES = 20 * 1024 * 1024

# Full per-page char scan for info_only; above this, sample first pages and estimate totals.
_READ_PDF_INFO_FULL_SCAN_MAX_PAGES = 200


def _decode_pdf_meta_value(v: Any) -> str | None:
    if v is None:
        return None
    try:
        s = str(v).strip()
    except Exception:
        return None
    if not s or s in ("None", "null"):
        return None
    return s


def _pdf_document_metadata(reader: Any) -> dict[str, str]:
    """Flatten PDF /Info dictionary for display (pypdf)."""
    raw = getattr(reader, "metadata", None)
    if not raw:
        return {}
    out: dict[str, str] = {}
    try:
        pairs = list(raw.items()) if hasattr(raw, "items") else []
    except Exception:
        pairs = []
    for k, v in pairs:
        key = str(k).lstrip("/").lower().replace(" ", "_")
        dv = _decode_pdf_meta_value(v)
        if dv:
            out[key] = dv
    if not out:
        for attr in ("title", "author", "subject", "creator", "producer", "keywords"):
            if hasattr(raw, attr):
                dv = _decode_pdf_meta_value(getattr(raw, attr, None))
                if dv:
                    out[attr] = dv
    return out


def _page_char_counts_and_note(
    reader: Any, *, full_scan_max: int
) -> tuple[int, bool, str, int | None, int | None]:
    """
    Returns (total_chars, estimated, note, min_chars_per_page, max_chars_per_page).
    min/max are set only when all pages were scanned (not estimated).
    """
    total_pages = len(reader.pages)
    if total_pages <= 0:
        return 0, False, "", None, None

    if total_pages <= full_scan_max:
        run = 0
        page_lens: list[int] = []
        for i in range(total_pages):
            n = len((reader.pages[i].extract_text() or ""))
            page_lens.append(n)
            run += n
        return run, False, "", min(page_lens), max(page_lens)

    sample_n = min(40, total_pages)
    sample_lens = [
        len((reader.pages[i].extract_text() or "")) for i in range(sample_n)
    ]
    avg = sum(sample_lens) / sample_n
    est_total = int(avg * total_pages)
    note = (
        f"Estimated total_chars={est_total:,} from first {sample_n} pages (avg {avg:,.0f} chars/page). "
        f"Document has {total_pages} pages (>{full_scan_max}). "
        "Use start_page/end_page with read_pdf (info_only=false) to read a range."
    )
    return est_total, True, note, None, None


_READ_PDF_INFO_PREVIEW_CHARS = 600


def _text_preview_head(reader: Any, *, limit: int = _READ_PDF_INFO_PREVIEW_CHARS) -> str:
    """First ``limit`` characters from extract_text, scanning from page 1 until enough text."""
    buf: list[str] = []
    run = 0
    for i in range(len(reader.pages)):
        t = reader.pages[i].extract_text() or ""
        buf.append(t)
        run += len(t)
        if run >= limit:
            break
    s = "".join(buf).strip()
    if not s:
        return ""
    if len(s) <= limit:
        return s
    return s[:limit]


def _format_pdf_info_summary(pdf_path: Path, reader: Any) -> str:
    meta = _pdf_document_metadata(reader)
    n = len(reader.pages)
    try:
        size_b = pdf_path.stat().st_size
    except OSError:
        size_b = -1

    total_chars, estimated, est_note, min_cp, max_cp = _page_char_counts_and_note(
        reader, full_scan_max=_READ_PDF_INFO_FULL_SCAN_MAX_PAGES
    )

    lines: list[str] = [
        "=== PDF metadata (read_pdf info_only) ===",
        f"path: {pdf_path}",
        f"pages: {n}",
    ]
    if size_b >= 0:
        lines.append(f"file_size_bytes: {size_b:,}")

    label_map = (
        ("title", "title"),
        ("author", "author"),
        ("subject", "subject"),
        ("keywords", "keywords"),
        ("creator", "creator"),
        ("producer", "producer"),
        ("creationdate", "creation_date"),
        ("moddate", "mod_date"),
    )
    for mk, label in label_map:
        if mk in meta:
            lines.append(f"{label}: {meta[mk]}")

    lines.append("")
    lines.append("--- text extraction stats (pypdf extract_text; image-only pages may be 0) ---")
    if estimated:
        lines.append(f"total_chars (estimated): {total_chars:,}")
        lines.append(est_note)
    else:
        lines.append(f"total_chars (sum over all pages): {total_chars:,}")
        if n > 0:
            avg = total_chars / n
            lines.append(f"avg_chars_per_page: {avg:,.1f}")
            if min_cp is not None and max_cp is not None:
                lines.append(f"min_chars_per_page: {min_cp}")
                lines.append(f"max_chars_per_page: {max_cp}")

    preview = _text_preview_head(reader, limit=_READ_PDF_INFO_PREVIEW_CHARS)
    lines.append("")
    lines.append(
        f"--- text preview (first {_READ_PDF_INFO_PREVIEW_CHARS} chars from extract_text; "
        "may span pages) ---"
    )
    if preview:
        lines.append(preview)
    else:
        lines.append(
            "(no extractable text in opening pages; PDF may be image-based or empty)"
        )

    lines.append("")
    lines.append(
        "Next: call read_pdf with info_only=false, path=..., start_page, end_page, max_chars "
        "to read a slice."
    )
    return "\n".join(lines)


def _mime_for_path(path: Path) -> str | None:
    suf = path.suffix.lower()
    return _SUFFIX_TO_MIME.get(suf)


class ReadImageTool(Tool):
    """Encode a local image as base64, send it to the configured LLM, return the description."""

    def __init__(
        self,
        provider: LLMProvider,
        model: str | None = None,
        workspace: Path | None = None,
        allowed_dir: Path | None = None,
        *,
        temperature: float = 0.2,
        max_tokens: int = 2048,
    ):
        self._provider = provider
        self._model = model
        self._workspace = workspace
        self._allowed_dir = allowed_dir
        self._temperature = temperature
        self._max_tokens = max(256, min(max_tokens, 8192))

    @property
    def name(self) -> str:
        return "read_image"

    @property
    def description(self) -> str:
        return (
            "Read and understand a local image file: encodes it and asks the vision model to "
            "describe contents (and text in the image if any). Path is resolved relative to the "
            "workspace when not absolute. Requires a vision-capable model."
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "path": {
                    "type": "string",
                    "description": "Filesystem path to the image file (.png, .jpeg, .gif, .webp, .bmp)",
                },
                "prompt": {
                    "type": "string",
                    "description": (
                        "Optional instruction for what to extract (e.g. OCR, summarize diagram). "
                        "If omitted, a general description is requested."
                    ),
                },
            },
            "required": ["path"],
        }

    async def execute(self, path: str, prompt: str | None = None, **kwargs: Any) -> str:

        if not path or not str(path).strip():
            return "Error: path is empty"

        try:
            image_path = _resolve_path(path, self._workspace, self._allowed_dir)
        except PermissionError as e:
            return f"Error: {e}"

        if not image_path.exists():
            return f"Error: File not found: {path}"
        if not image_path.is_file():
            return f"Error: Not a file: {path}"

        try:
            size = image_path.stat().st_size
        except OSError as e:
            return f"Error: cannot stat file: {e}"

        if size > _MAX_IMAGE_BYTES:
            return (
                f"Error: Image too large ({size:,} bytes). "
                f"Maximum {_MAX_IMAGE_BYTES // (1024 * 1024)} MiB."
            )

        try:
            raw = image_path.read_bytes()
        except OSError as e:
            return f"Error: cannot read file: {e}"

        ext_mime = _mime_for_path(image_path)
        guessed = mimetypes.guess_type(str(image_path), strict=False)[0]
        mime = detect_image_mime(raw) or ext_mime
        if (not mime or not mime.startswith("image/")) and guessed and guessed.startswith("image/"):
            mime = guessed
        # If magic failed but path has a known image extension, trust the extension (e.g. guess_type
        # returned application/octet-stream on some Windows paths).
        if (not mime or not mime.startswith("image/")) and ext_mime:
            mime = ext_mime
        if not mime or not mime.startswith("image/"):
            return (
                f"Error: Could not determine image MIME type for {image_path.suffix!r}. "
                f"Use a common raster format (.png, .jpeg, .webp, …)."
            )

        # b64 = base64.b64encode(raw).decode("utf-8")
        import re
        b64 = base64.b64encode(raw).decode("utf-8").replace("\n", "")
        if not re.match(r'^[A-Za-z0-9+/=]+$', b64):
            raise ValueError("Base64包含非法字符")

        data_url = f"data:{mime};base64,{b64}"

        user_text = (prompt or "").strip() or (
            "Describe this image in detail. If there is visible text, transcribe it faithfully."
        )

        # Match ContextBuilder._build_user_content: image parts first, then text (many OpenAI-compatible
        # vision backends expect this order). Use the same image_url shape as the rest of the agent.
        messages: list[dict[str, Any]] = [
            {
                "role": "user",
                "content": [
                    {
                        "type": "image_url",
                        "image_url": {"url": data_url},
                    },
                    {"type": "text", "text": user_text},
                ],
            },
        ]

        model = self._model or self._provider.get_default_model()
        response = await self._provider.chat_with_retry(
            messages=messages,
            tools=None,
            model=model,
            max_tokens=self._max_tokens,
            temperature=self._temperature,
        )

        if response.finish_reason == "error":
            return (response.content or "Error: LLM request failed").strip()

        if response.has_tool_calls and not (response.content or "").strip():
            return "Error: model returned tool calls instead of a text description; try a vision chat model."

        out = (response.content or "").strip()
        if not out:
            return "Error: empty response from model (ensure the model supports images)."
        return out


class ReadPdfTool(Tool):
    """Extract text from PDF files using pypdf."""

    _DEFAULT_MAX_CHARS = 128_000

    def __init__(self, workspace: Path | None = None, allowed_dir: Path | None = None):
        self._workspace = workspace
        self._allowed_dir = allowed_dir

    @property
    def name(self) -> str:
        return "read_pdf"

    @property
    def description(self) -> str:
        return (
            "PDF reader. Prefer info_only=true first for title/metadata, page count, char totals, "
            "and a short text preview; then set info_only=false with start_page/end_page/max_chars "
            "to read more. Supports page ranges and output truncation."
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "PDF file path"},
                "info_only": {
                    "type": "boolean",
                    "description": (
                        "If true, return metadata, char totals, and a ~600-char text preview from the "
                        "start of the document (no full-body extract). Use before read_pdf with ranges."
                    ),
                },
                "start_page": {
                    "type": "integer",
                    "description": "1-based start page (inclusive). Defaults to 1. Ignored when info_only=true.",
                    "minimum": 1,
                },
                "end_page": {
                    "type": "integer",
                    "description": "1-based end page (inclusive). Defaults to last page. Ignored when info_only=true.",
                    "minimum": 1,
                },
                "include_page_markers": {
                    "type": "boolean",
                    "description": "Whether to include page separators in output. Ignored when info_only=true.",
                },
                "max_chars": {
                    "type": "integer",
                    "description": "Maximum number of characters to return. Ignored when info_only=true.",
                    "minimum": 100,
                },
            },
            "required": ["path"],
        }

    async def execute(
        self,
        path: str,
        start_page: int | None = None,
        end_page: int | None = None,
        include_page_markers: bool = True,
        max_chars: int | None = None,
        **kwargs: Any,
    ) -> str:
        try:
            from pypdf import PdfReader
        except Exception as exc:
            return f"Error: pypdf is not available: {exc}"

        info_only = kwargs.get("info_only", False)
        if isinstance(info_only, str):
            info_only = info_only.strip().lower() in ("1", "true", "yes", "y", "on")

        try:
            pdf_path = _resolve_path(path, self._workspace, self._allowed_dir)
            if not pdf_path.exists():
                return f"Error: File not found: {path}"
            if not pdf_path.is_file():
                return f"Error: Not a file: {path}"

            reader = PdfReader(str(pdf_path))
            if reader.is_encrypted:
                try:
                    unlocked = reader.decrypt("")
                except Exception:
                    unlocked = 0
                if not unlocked:
                    return "Error: PDF is encrypted and cannot be read without a password."

            total_pages = len(reader.pages)
            if total_pages <= 0:
                return f"PDF has no pages: {pdf_path}"

            if info_only:
                return _format_pdf_info_summary(pdf_path, reader)

            start = start_page or 1
            end = end_page or total_pages
            if start > end:
                return f"Error: start_page ({start}) cannot be greater than end_page ({end})"
            if start < 1 or end < 1:
                return "Error: start_page and end_page must be >= 1"
            if start > total_pages:
                return (
                    f"Error: start_page ({start}) is out of range. "
                    f"The PDF has {total_pages} pages."
                )
            end = min(end, total_pages)

            chunks: list[str] = []
            for page_idx in range(start - 1, end):
                text = (reader.pages[page_idx].extract_text() or "").strip()
                if include_page_markers:
                    chunks.append(f"--- Page {page_idx + 1} ---\n{text}")
                else:
                    chunks.append(text)

            content = "\n\n".join(chunks).strip()
            if not content:
                return (
                    "No extractable text found in the selected pages. "
                    "The PDF may be image-based/scanned."
                )

            prefix = (
                f"PDF: {pdf_path}\n"
                f"Pages: {start}-{end} of {total_pages}\n\n"
            )
            result = prefix + content

            limit = max_chars or self._DEFAULT_MAX_CHARS
            if len(result) > limit:
                result = (
                    result[:limit]
                    + f"\n\n... (truncated — extracted {len(result):,} chars, limit {limit:,})"
                )

            return result
        except PermissionError as exc:
            return f"Error: {exc}"
        except Exception as exc:
            return f"Error reading PDF: {exc}"
