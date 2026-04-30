"""Image generation/edit tool using OpenAI-compatible image endpoints."""

from __future__ import annotations

import base64
import mimetypes
import time
from pathlib import Path
from typing import Any

import httpx

from topoclaw.agent.tools.base import Tool
from topoclaw.utils.helpers import detect_image_mime, estimate_message_tokens


class ImageGenerationTool(Tool):
    """Generate or edit images via /images/generations and /images/edits."""

    def __init__(
        self,
        workspace: Path,
        app_config: Any | None = None,
        token_usage_service: Any | None = None,
    ):
        self._workspace = workspace
        self._app_config = app_config
        self._token_usage_service = token_usage_service
        self._last_effective_model: str | None = None

    @property
    def name(self) -> str:
        return "generate_image"

    @property
    def description(self) -> str:
        return (
            "Generate an image from text prompt (or edit with input images) using an "
            "OpenAI-compatible API. Saves output files locally and returns file paths."
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "prompt": {"type": "string", "description": "Text prompt describing the image."},
                "model": {
                    "type": "string",
                    "description": "Image model name. If omitted, use active non-GUI model from topo_desktop config.",
                },
                "api_base": {
                    "type": "string",
                    "description": "API base URL. Optional: auto-resolved from config if omitted.",
                },
                "api_key": {
                    "type": "string",
                    "description": "API key. Optional: auto-resolved from config if omitted.",
                },
                "size": {
                    "type": "string",
                    "description": "Target size, e.g. 1024x1024.",
                },
                "n": {"type": "integer", "minimum": 1, "maximum": 4, "description": "How many images to generate."},
                "input_images": {
                    "type": "array",
                    "description": "Optional local image paths for image edit mode.",
                    "items": {"type": "string"},
                },
                "output_dir": {
                    "type": "string",
                    "description": "Optional output directory. Defaults to workspace/generated_images.",
                },
            },
            "required": ["prompt"],
        }

    async def execute(
        self,
        prompt: str,
        model: str | None = None,
        api_base: str | None = None,
        api_key: str | None = None,
        size: str | None = None,
        n: int = 1,
        input_images: list[str] | None = None,
        output_dir: str | None = None,
        **kwargs: Any,
    ) -> str:
        self._last_effective_model = None
        selected_image_model = self._resolve_selected_image_model()
        selected_model = self._resolve_selected_non_gui_model()
        requested_model = (model or "").strip()
        # Prefer image-capable model to avoid using a text-only active chat model.
        if requested_model and self._is_likely_image_model(requested_model):
            model_name = requested_model
        elif selected_image_model and self._is_likely_image_model(selected_image_model):
            model_name = selected_image_model
        elif selected_model and self._is_likely_image_model(selected_model):
            model_name = selected_model
        else:
            model_name = self._resolve_default_model()
        resolved_key, resolved_base = self._resolve_credentials(model_name, api_key, api_base)
        if not resolved_base:
            return "Error: image api_base is empty. Please configure it in config or pass api_base."
        if resolved_key is None:
            return "Error: image api_key is missing. Please configure it in config or pass api_key."

        out_root = Path(output_dir).expanduser() if output_dir else (self._workspace / "generated_images")
        out_root.mkdir(parents=True, exist_ok=True)
        count = max(1, min(int(n or 1), 4))

        if input_images:
            result = await self._call_edits(
                api_base=resolved_base,
                api_key=resolved_key,
                model=model_name,
                prompt=prompt,
                size=size,
                n=count,
                input_images=input_images,
            )
        else:
            result = await self._call_generations(
                api_base=resolved_base,
                api_key=resolved_key,
                model=model_name,
                prompt=prompt,
                size=size,
                n=count,
            )

        if isinstance(result, str):
            if result.startswith("Error:"):
                return result
            return f"Error: invalid image generation result: {result[:200]}"

        data_items = result
        saved: list[str] = []
        ts = int(time.time() * 1000)
        for idx, item in enumerate(data_items, start=1):
            ext = "png"
            raw: bytes | None = None
            if isinstance(item.get("b64_json"), str) and item["b64_json"].strip():
                try:
                    raw = base64.b64decode(item["b64_json"])
                except Exception:
                    raw = None
            elif isinstance(item.get("image_base64"), str) and item["image_base64"].strip():
                try:
                    raw = base64.b64decode(item["image_base64"])
                except Exception:
                    raw = None
            elif isinstance(item.get("base64"), str) and item["base64"].strip():
                try:
                    raw = base64.b64decode(item["base64"])
                except Exception:
                    raw = None
            elif isinstance(item.get("url"), str) and item["url"].strip():
                raw = await self._download_binary(item["url"], resolved_key)
                if raw is None:
                    continue
            if not raw:
                continue

            file_path = out_root / f"image_{ts}_{idx}.{ext}"
            file_path.write_bytes(raw)
            saved.append(str(file_path))

        if not saved:
            return "Error: API returned no usable image data."

        effective_model = (self._last_effective_model or model_name or "").strip() or model_name
        lines = [f"Generated {len(saved)} image(s) with model '{effective_model}':"]
        lines.extend(f"- {p}" for p in saved)
        lines.append("If the user asks to send these in chat, call message tool with media=the file paths above.")
        return "\n".join(lines)

    def _resolve_selected_non_gui_model(self) -> str | None:
        """Get desktop-selected non-GUI model if present."""
        cfg = self._app_config
        topo = getattr(cfg, "topo_desktop", None) if cfg else None
        if not topo:
            return None
        active = str(getattr(topo, "activeNonGuiModel", "") or "").strip()
        return active or None

    def _resolve_selected_image_model(self) -> str | None:
        """Get desktop-selected image model (independent from chat active model)."""
        cfg = self._app_config
        topo = getattr(cfg, "topo_desktop", None) if cfg else None
        if not topo:
            return None
        active = str(getattr(topo, "activeImageModel", "") or "").strip()
        if active:
            return active
        if hasattr(topo, "model_dump"):
            raw = topo.model_dump(mode="python")
            if isinstance(raw, dict):
                active = str(
                    raw.get("activeImageModel")
                    or raw.get("active_image_model")
                    or ""
                ).strip()
                if active:
                    return active
        return None

    def _resolve_default_model(self) -> str:
        """Resolve default image model from desktop profiles."""
        cfg = self._app_config
        topo = getattr(cfg, "topo_desktop", None) if cfg else None
        if topo:
            profiles = getattr(topo, "nonGuiProfiles", None) or []
            # Prefer an explicitly image-capable model in profiles.
            for row in profiles:
                if not row:
                    continue
                if hasattr(row, "model_dump"):
                    model_val = str(row.model_dump(mode="python").get("model", "")).strip()
                elif isinstance(row, dict):
                    model_val = str(row.get("model", "")).strip()
                else:
                    model_val = str(getattr(row, "model", "")).strip()
                if model_val and self._is_likely_image_model(model_val):
                    return model_val
            # Fallback to first profile model (legacy behavior).
            for row in profiles:
                if not row:
                    continue
                if hasattr(row, "model_dump"):
                    model_val = str(row.model_dump(mode="python").get("model", "")).strip()
                elif isinstance(row, dict):
                    model_val = str(row.get("model", "")).strip()
                else:
                    model_val = str(getattr(row, "model", "")).strip()
                if model_val:
                    return model_val
        # Keep a stable fallback for older configs without topo_desktop block.
        return "gemini-nano-banana-2"

    @staticmethod
    def _is_likely_image_model(model: str) -> bool:
        m = (model or "").strip().lower()
        if not m:
            return False
        keywords = (
            "banana",
            "dall-e",
            "gpt-image",
            "stable-diffusion",
            "sdxl",
            "flux",
            "imagen",
            "image",
        )
        return any(k in m for k in keywords)

    def _resolve_credentials(
        self, model: str, api_key: str | None, api_base: str | None
    ) -> tuple[str | None, str | None]:
        key = api_key if api_key is not None else None
        base = api_base if api_base is not None else None
        # Empty strings from tool arguments should be treated as "not provided",
        # so we can still fall back to configured credentials.
        if isinstance(key, str) and not key.strip():
            key = None
        if isinstance(base, str) and not base.strip():
            base = None

        cfg = self._app_config
        if cfg and (key is None or base is None):
            # 1) Prefer topo_desktop profile rows (supports custom *Profiles via model_extra too).
            for p in self._iter_topo_desktop_profiles():
                pm = str(p.get("model") or "").strip()
                if not pm or pm != model:
                    continue
                if key is None:
                    key = self._pick_str(p, "apiKey", "api_key")
                if base is None:
                    base = self._pick_str(p, "apiBase", "api_base")
                break

            # 2) Fallback to provider routing by model.
            if key is None and hasattr(cfg, "get_api_key"):
                key = cfg.get_api_key(model)  # type: ignore[assignment]
            if base is None and hasattr(cfg, "get_api_base"):
                base = cfg.get_api_base(model)  # type: ignore[assignment]

        key = (key or "").strip()
        base = (base or "").strip().rstrip("/")
        return (key if key else None), (base if base else None)

    def _iter_topo_desktop_profiles(self) -> list[dict[str, Any]]:
        cfg = self._app_config
        state = getattr(cfg, "topo_desktop", None) if cfg else None
        if not state:
            return []
        raw = state.model_dump(mode="python") if hasattr(state, "model_dump") else {}
        profiles: list[dict[str, Any]] = []
        for k, v in raw.items():
            if not isinstance(k, str) or not k.endswith("Profiles") or not isinstance(v, list):
                continue
            for item in v:
                if isinstance(item, dict):
                    profiles.append(item)
        return profiles

    @staticmethod
    def _pick_str(obj: dict[str, Any], *keys: str) -> str | None:
        for k in keys:
            v = obj.get(k)
            if isinstance(v, str) and v.strip():
                return v.strip()
        return None

    async def _call_generations(
        self,
        *,
        api_base: str,
        api_key: str,
        model: str,
        prompt: str,
        size: str | None,
        n: int,
    ) -> list[dict[str, Any]] | str:
        payload: dict[str, Any] = {"model": model, "prompt": prompt}
        # Keep the first request minimal (as provider examples), then add optional params.
        if n and int(n) > 1:
            payload["n"] = int(n)
        if size:
            payload["size"] = size
        url = f"{api_base}/images/generations"
        try:
            async with httpx.AsyncClient(timeout=180.0) as client:
                resp = await client.post(
                    url,
                    headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
                    json=payload,
                )
            if resp.status_code >= 400:
                return f"Error: image generation failed ({resp.status_code}): {resp.text[:800]}"
            data = resp.json()
            self._record_usage(
                requested_model=model,
                prompt=prompt,
                payload=data,
                source="tool_generate_image",
            )
            items = data.get("data") if isinstance(data, dict) else None
            if isinstance(items, list):
                return [x for x in items if isinstance(x, dict)]
            return "Error: invalid response format from image generation API."
        except Exception as e:
            return (
                "Error: image generation request failed: "
                f"{type(e).__name__}: {e!r}"
            )

    async def _call_edits(
        self,
        *,
        api_base: str,
        api_key: str,
        model: str,
        prompt: str,
        size: str | None,
        n: int,
        input_images: list[str],
    ) -> list[dict[str, Any]] | str:
        data: dict[str, str] = {
            "model": model,
            "prompt": prompt,
        }
        if n and int(n) > 1:
            data["n"] = str(int(n))
        if size:
            data["size"] = size

        url = f"{api_base}/images/edits"
        try:
            files_or_error = self._build_edit_files(input_images, field_name="image")
            if isinstance(files_or_error, str):
                return files_or_error
            files = files_or_error
            async with httpx.AsyncClient(timeout=120.0) as client:
                resp = await client.post(
                    url,
                    headers={"Authorization": f"Bearer {api_key}"},
                    data=data,
                    files=files,
                )
                # Some compatible providers expect image[] in multipart edit requests.
                if resp.status_code >= 400 and self._looks_like_invalid_edit_image(resp.text):
                    alt_files_or_error = self._build_edit_files(input_images, field_name="image[]")
                    if not isinstance(alt_files_or_error, str):
                        resp = await client.post(
                            url,
                            headers={"Authorization": f"Bearer {api_key}"},
                            data=data,
                            files=alt_files_or_error,
                        )
            if resp.status_code >= 400:
                return f"Error: image edit failed ({resp.status_code}): {resp.text[:800]}"
            payload = resp.json()
            self._record_usage(
                requested_model=model,
                prompt=prompt,
                payload=payload,
                source="tool_edit_image",
            )
            items = payload.get("data") if isinstance(payload, dict) else None
            if isinstance(items, list):
                return [x for x in items if isinstance(x, dict)]
            return "Error: invalid response format from image edit API."
        except Exception as e:
            return f"Error: image edit request failed: {e}"

    def _build_edit_files(
        self, input_images: list[str], *, field_name: str
    ) -> list[tuple[str, tuple[str, bytes, str]]] | str:
        files: list[tuple[str, tuple[str, bytes, str]]] = []
        for p in input_images:
            fp = Path(str(p or "").strip()).expanduser()
            if not fp.exists() or not fp.is_file():
                return f"Error: input image not found: {p}"
            raw = fp.read_bytes()
            if not raw:
                return f"Error: input image is empty: {p}"
            mime = detect_image_mime(raw) or mimetypes.guess_type(fp.name, strict=False)[0]
            if not mime or not mime.startswith("image/"):
                return f"Error: unsupported input image format: {p}"
            files.append((field_name, (fp.name, raw, mime)))
        return files

    @staticmethod
    def _looks_like_invalid_edit_image(err_text: str) -> bool:
        t = (err_text or "").lower()
        return "invalid_image_file_for_edit" in t or ("invalid image file" in t and "edit" in t)

    async def _download_binary(self, url: str, api_key: str) -> bytes | None:
        try:
            async with httpx.AsyncClient(timeout=60.0, follow_redirects=True) as client:
                resp = await client.get(url, headers={"Authorization": f"Bearer {api_key}"})
                if resp.status_code >= 400:
                    resp = await client.get(url)
            if resp.status_code >= 400:
                return None
            return resp.content
        except Exception:
            return None

    def _record_usage(self, *, requested_model: str, prompt: str, payload: Any, source: str) -> None:
        svc = self._token_usage_service
        if svc is None:
            return

        effective_model = self._extract_effective_model(payload, fallback=requested_model)
        self._last_effective_model = effective_model
        usage_obj = self._extract_usage(payload)
        prompt_tokens = self._pick_usage_value(
            usage_obj,
            "prompt_tokens",
            "input_tokens",
            "promptTokens",
            "inputTokens",
            "prompt_token_count",
            "input_token_count",
        )
        completion_tokens = self._pick_usage_value(
            usage_obj,
            "completion_tokens",
            "output_tokens",
            "completionTokens",
            "outputTokens",
            "completion_token_count",
            "output_token_count",
        )
        total_tokens = self._pick_usage_value(
            usage_obj,
            "total_tokens",
            "totalTokens",
            "total_token_count",
        )
        is_estimated = False

        if prompt_tokens <= 0 and completion_tokens <= 0 and total_tokens > 0:
            # Some image providers only return total tokens without in/out breakdown.
            prompt_tokens = total_tokens
            completion_tokens = 0
            is_estimated = True
        elif prompt_tokens <= 0 and completion_tokens <= 0 and total_tokens <= 0:
            prompt_tokens = estimate_message_tokens({"role": "user", "content": prompt})
            completion_tokens = 0
            total_tokens = prompt_tokens
            is_estimated = True

        if total_tokens <= 0:
            total_tokens = prompt_tokens + completion_tokens

        try:
            svc.record_usage(
                model=(effective_model or "unknown").strip() or "unknown",
                input_tokens=prompt_tokens,
                output_tokens=completion_tokens,
                total_tokens=total_tokens,
                source=source,
                is_estimated=is_estimated,
            )
        except Exception:
            pass

    @staticmethod
    def _extract_usage(payload: Any) -> dict[str, Any]:
        if not isinstance(payload, dict):
            return {}

        direct = payload.get("usage")
        if isinstance(direct, dict):
            return direct

        nested = payload.get("x_groq")
        if isinstance(nested, dict):
            nested_usage = nested.get("usage")
            if isinstance(nested_usage, dict):
                return nested_usage

        return payload

    @staticmethod
    def _extract_effective_model(payload: Any, *, fallback: str) -> str:
        if not isinstance(payload, dict):
            return (fallback or "").strip() or "unknown"

        def _pick_model(candidate: Any) -> str | None:
            if isinstance(candidate, str) and candidate.strip():
                return candidate.strip()
            return None

        candidates: list[Any] = [
            payload.get("model"),
            payload.get("used_model"),
            payload.get("actual_model"),
            (payload.get("response") or {}).get("model") if isinstance(payload.get("response"), dict) else None,
            (payload.get("meta") or {}).get("model") if isinstance(payload.get("meta"), dict) else None,
            (payload.get("usage") or {}).get("model") if isinstance(payload.get("usage"), dict) else None,
            (payload.get("x_groq") or {}).get("model") if isinstance(payload.get("x_groq"), dict) else None,
        ]

        data_val = payload.get("data")
        if isinstance(data_val, list):
            for item in data_val:
                if isinstance(item, dict):
                    candidates.append(item.get("model"))
                    candidates.append(item.get("revised_model"))
                    break

        for candidate in candidates:
            picked = _pick_model(candidate)
            if picked:
                return picked

        return (fallback or "").strip() or "unknown"

    @classmethod
    def _pick_usage_value(cls, usage_obj: dict[str, Any], *keys: str) -> int:
        for key in keys:
            if key in usage_obj:
                val = cls._safe_int(usage_obj.get(key))
                if val > 0:
                    return val
        return 0

    @staticmethod
    def _safe_int(value: Any) -> int:
        try:
            return max(0, int(value))
        except Exception:
            return 0

