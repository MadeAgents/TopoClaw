---
name: set-mobile-clipboard-image
description: Push an image (base64) to the current phone clipboard through customer_service mobile_tool bridge.
metadata: {"topoclaw":{"emoji":"📋","requires":{"bins":["python"]}}}
---

# Set Mobile Clipboard Image

Use this skill when the user wants TopoClaw (desktop/agent side) to write an image into the **phone clipboard**.

This skill calls customer_service HTTP bridge:
- `POST /api/cross-device/mobile-tool-invoke`
- `tool = device.set_clipboard_image`

## Inputs

- `TOPO_IMEI` (required): target phone IMEI
  - in TopoDesktop built-in assistant chats, runtime context auto-injects the current logged-in IMEI
- `TOPO_IMAGE_BASE64` (required): image base64
  - supports raw base64 or `data:image/...;base64,...`
- `TOPO_CLIPBOARD_LABEL` (optional): clipboard label, default `topoclaw_image`
- `TOPO_MOBILE_TOOL_TIMEOUT_MS` (optional): wait timeout in ms, default `15000`
- `CUSTOMER_SERVICE_URL` (optional): service base URL override
- `VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL` (recommended): default customer_service base URL from TopoDesktop `.env.local`

## Output

Structured JSON:
- `success`
- `imei`
- `request_id`
- `tool`
- `result` (raw mobile_tool_result envelope)

## Reference Script

```python
import json
import os
import requests

imei = os.getenv("TOPO_IMEI", "").strip() or os.getenv("IMEI", "").strip()
image_b64 = (
    os.getenv("TOPO_IMAGE_BASE64", "").strip()
    or os.getenv("TOPO_FILE_BASE64", "").strip()
    or os.getenv("TOPO_MESSAGE_IMAGE_BASE64", "").strip()
    or os.getenv("FILE_BASE64", "").strip()
    or os.getenv("IMAGE_BASE64", "").strip()
)
label = os.getenv("TOPO_CLIPBOARD_LABEL", "").strip() or "topoclaw_image"
timeout_ms_raw = os.getenv("TOPO_MOBILE_TOOL_TIMEOUT_MS", "").strip() or "15000"
base_url = (
    os.getenv("TOPO_ACTIVE_CUSTOMER_SERVICE_URL", "").strip()
    or
    os.getenv("CUSTOMER_SERVICE_URL", "").strip()
    or os.getenv("VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL", "").strip()
).rstrip("/")

if not imei:
    raise RuntimeError("Missing TOPO_IMEI (target phone IMEI)")
if not base_url:
    raise RuntimeError("Missing customer_service URL (TOPO_ACTIVE_CUSTOMER_SERVICE_URL / CUSTOMER_SERVICE_URL / VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL)")
if not image_b64:
    raise RuntimeError("Missing image base64: set TOPO_IMAGE_BASE64")

try:
    timeout_ms = int(timeout_ms_raw)
except Exception:
    timeout_ms = 15000
timeout_ms = max(1000, min(timeout_ms, 60000))

url = f"{base_url}/api/cross-device/mobile-tool-invoke"
payload = {
    "imei": imei,
    "tool": "device.set_clipboard_image",
    "args": {
        "image_base64": image_b64,
        "label": label,
    },
    "conversation_id": "assistant",
    "wait_result": True,
    "timeout_ms": timeout_ms,
}

resp = requests.post(url, json=payload, timeout=(10, timeout_ms / 1000.0 + 5))
if resp.status_code >= 400:
    detail = ""
    try:
        detail = str(resp.json())
    except Exception:
        detail = resp.text
    raise RuntimeError(f"mobile clipboard invoke failed: HTTP {resp.status_code}, detail={detail}")

raw = resp.json()
if not raw.get("success"):
    raise RuntimeError(f"mobile clipboard invoke failed: {raw}")

result = {
    "success": True,
    "imei": imei,
    "request_id": raw.get("request_id"),
    "tool": "device.set_clipboard_image",
    "result": raw.get("result"),
}
print(json.dumps(result, ensure_ascii=False, indent=2))
```
