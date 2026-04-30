---
name: send-group-message
description: Send a message to a specified group by group_id via customer_service APIs. Use when user asks topoclaw to post content into a group.
metadata: {"topoclaw":{"emoji":"📣","requires":{"bins":["python"]}}}
---

# Send Group Message

Use this skill when the user wants topoclaw to send a message to a specific group.

## Inputs

- `TOPO_IMEI` (required): current logged-in user IMEI
  - in TopoDesktop built-in assistant chats, runtime context auto-injects the current logged-in IMEI
- `group_id` (required): target group ID
- `content` (required): message text to send
- `sender` (optional, default `topoclaw`): assistant display name in group
- auto-fill env (recommended):
  - `TOPO_GROUP_ID`
  - `TOPO_GROUP_MESSAGE`
  - `TOPO_GROUP_SENDER`
- `CUSTOMER_SERVICE_URL` (optional): service base URL override
- `VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL` (recommended): default customer_service base URL from TopoDesktop `.env.local`
  - recommendation: always configure this variable in `TopoDesktop/.env.local`
  - when missing, fail fast with explicit error instead of silently falling back to `127.0.0.1`

## Output

Structured JSON:
- `success`
- `imei`
- `group_id`
- `content_preview`
- `sender`
- `raw` (server response)

## Reference Script

```python
import os
import json
import requests

imei = os.getenv("TOPO_IMEI", "").strip() or os.getenv("IMEI", "").strip()
base_url = (
    os.getenv("TOPO_ACTIVE_CUSTOMER_SERVICE_URL", "").strip()
    or
    os.getenv("CUSTOMER_SERVICE_URL", "").strip()
    or os.getenv("VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL", "").strip()
).rstrip("/")

# Editable inputs（优先环境变量自动填充，减少首次调用重试）
group_id = os.getenv("TOPO_GROUP_ID", "").strip()
content = os.getenv("TOPO_GROUP_MESSAGE", "").strip()
sender = os.getenv("TOPO_GROUP_SENDER", "").strip() or "topoclaw"

if not imei:
    raise RuntimeError("Missing TOPO_IMEI (current caller IMEI)")
if not base_url:
    raise RuntimeError("Missing customer_service URL (TOPO_ACTIVE_CUSTOMER_SERVICE_URL / CUSTOMER_SERVICE_URL / VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL)")
if not group_id:
    raise RuntimeError("group_id is required (set TOPO_GROUP_ID)")
if not content:
    raise RuntimeError("content is required (set TOPO_GROUP_MESSAGE)")

url = f"{base_url}/api/groups/send-assistant-message"
payload = {
    "imei": imei,
    "groupId": group_id,
    "content": content,
    "sender": sender,
}

resp = requests.post(url, json=payload, timeout=20)
resp.raise_for_status()
raw = resp.json()

if not raw.get("success"):
    raise RuntimeError(f"send group message failed: {raw}")

result = {
    "success": True,
    "imei": imei,
    "group_id": group_id,
    "content_preview": content[:80],
    "sender": sender,
    "raw": raw,
}
print(json.dumps(result, ensure_ascii=False, indent=2))
```
