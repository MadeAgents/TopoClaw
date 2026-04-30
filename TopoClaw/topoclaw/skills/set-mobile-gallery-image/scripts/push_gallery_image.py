#!/usr/bin/env python3
"""Push an image to phone gallery via mobile_tool bridge.

Environment variables:
  - TOPO_IMEI (required)
  - TOPO_IMAGE_BASE64 (required unless TOPO_IMAGE_PATH is provided)
  - TOPO_IMAGE_PATH (optional, preferred for local files)
  - TOPO_GALLERY_DISPLAY_NAME (optional)
  - TOPO_GALLERY_ALBUM (optional)
  - TOPO_MOBILE_TOOL_TIMEOUT_MS (optional, default 15000)
  - TOPO_ACTIVE_CUSTOMER_SERVICE_URL / CUSTOMER_SERVICE_URL / VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL (one required)
"""

from __future__ import annotations

import base64
import json
import os
from pathlib import Path
from typing import Any

import requests


def _first_non_empty(*values: str) -> str:
    for v in values:
        s = str(v or "").strip()
        if s:
            return s
    return ""


def _load_image_base64() -> str:
    image_b64 = _first_non_empty(
        os.getenv("TOPO_IMAGE_BASE64", ""),
        os.getenv("TOPO_FILE_BASE64", ""),
        os.getenv("TOPO_MESSAGE_IMAGE_BASE64", ""),
        os.getenv("FILE_BASE64", ""),
        os.getenv("IMAGE_BASE64", ""),
    )
    if image_b64:
        return image_b64

    image_path = _first_non_empty(
        os.getenv("TOPO_IMAGE_PATH", ""),
        os.getenv("IMAGE_PATH", ""),
    )
    if not image_path:
        return ""

    p = Path(image_path).expanduser()
    if not p.is_file():
        raise RuntimeError(f"TOPO_IMAGE_PATH not found: {p}")
    return base64.b64encode(p.read_bytes()).decode("ascii")


def main() -> int:
    imei = _first_non_empty(os.getenv("TOPO_IMEI", ""), os.getenv("IMEI", ""))
    base_url = _first_non_empty(
        os.getenv("TOPO_ACTIVE_CUSTOMER_SERVICE_URL", ""),
        os.getenv("CUSTOMER_SERVICE_URL", ""),
        os.getenv("VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL", ""),
    ).rstrip("/")
    display_name = _first_non_empty(
        os.getenv("TOPO_GALLERY_DISPLAY_NAME", ""),
        os.getenv("TOPO_DISPLAY_NAME", ""),
    )
    album = _first_non_empty(
        os.getenv("TOPO_GALLERY_ALBUM", ""),
        os.getenv("TOPO_ALBUM", ""),
    )

    timeout_raw = _first_non_empty(os.getenv("TOPO_MOBILE_TOOL_TIMEOUT_MS", ""), "15000")
    try:
        timeout_ms = int(timeout_raw)
    except Exception:
        timeout_ms = 15000
    timeout_ms = max(1000, min(timeout_ms, 60000))

    if not imei:
        raise RuntimeError("Missing TOPO_IMEI (target phone IMEI)")
    if not base_url:
        raise RuntimeError(
            "Missing customer_service URL "
            "(TOPO_ACTIVE_CUSTOMER_SERVICE_URL / CUSTOMER_SERVICE_URL / VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL)"
        )

    image_b64 = _load_image_base64()
    if not image_b64:
        raise RuntimeError("Missing image payload: set TOPO_IMAGE_BASE64 or TOPO_IMAGE_PATH")

    args: dict[str, Any] = {"image_base64": image_b64}
    if display_name:
        args["display_name"] = display_name
    if album:
        args["album"] = album

    payload = {
        "imei": imei,
        "tool": "device.save_image_to_gallery",
        "args": args,
        "conversation_id": "assistant",
        "wait_result": True,
        "timeout_ms": timeout_ms,
    }
    url = f"{base_url}/api/cross-device/mobile-tool-invoke"
    resp = requests.post(url, json=payload, timeout=(10, timeout_ms / 1000.0 + 5))

    if resp.status_code >= 400:
        detail = ""
        try:
            detail = str(resp.json())
        except Exception:
            detail = resp.text
        raise RuntimeError(f"mobile gallery invoke failed: HTTP {resp.status_code}, detail={detail}")

    raw = resp.json()
    if not raw.get("success"):
        raise RuntimeError(f"mobile gallery invoke failed: {raw}")

    result = {
        "success": True,
        "imei": imei,
        "request_id": raw.get("request_id"),
        "tool": "device.save_image_to_gallery",
        "result": raw.get("result"),
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
