---
name: send-owner-clone-message
description: Send a friend or group message through the same human message channel, but with the current TopoClaw owner's digital clone identity.
metadata: {"topoclaw":{"emoji":"💬","requires":{"bins":["python"]}}}
---

# Send Owner Clone Message

Use this skill when the user asks TopoClaw to send a message to a friend or group **as the current owner's digital clone**.

This skill intentionally uses the same customer_service message routes as normal user sending:
- friend: `POST /api/friends/send-message`
- group: `POST /api/groups/send-message`

## Inputs

- `TOPO_IMEI` (required): current caller IMEI (owner IMEI of this TopoClaw)
  - in TopoDesktop built-in assistant chats, runtime context auto-injects the current logged-in IMEI
- `target_type` (required): `friend` or `group`
- `target_id` (required):
  - friend mode: friend IMEI
  - group mode: group ID (also supports group name auto-resolve)
- `content` (optional): message text
- `message_type` (optional): `text` or `image` (default `text`)
- `image_base64` (optional): base64 image payload (without `data:image/...;base64,` prefix)
- `sender_label` (optional): clone display name; default `我的数字分身`
- auto-fill env (recommended):
  - `TOPO_TARGET_TYPE`
  - `TOPO_TARGET_ID`
  - `TOPO_MESSAGE_CONTENT`
  - `TOPO_MESSAGE_TYPE`
  - `TOPO_MESSAGE_IMAGE_BASE64`
  - `TOPO_SENDER_LABEL`
  - backward-compatible image env (fallback):
    - `TOPO_FILE_BASE64`
    - `TOPO_IMAGE_BASE64`
    - `FILE_BASE64`
    - `IMAGE_BASE64`
- `CUSTOMER_SERVICE_URL` (optional): service base URL override
- `VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL` (recommended): default customer_service base URL from TopoDesktop `.env.local`
  - recommendation: always configure this variable in `TopoDesktop/.env.local`
  - when missing, fail fast with explicit error instead of silently falling back to `127.0.0.1`

## Output

Structured JSON:
- `success`
- `target_type`
- `target_id`
- `owner_imei`
- `sender_label`
- `message_type`
- `has_image`
- `content_preview`
- `raw` (server response)

## Reference Script

```python
import os
import json
import requests

owner_imei = os.getenv("TOPO_IMEI", "").strip() or os.getenv("IMEI", "").strip()
base_url = (
    os.getenv("TOPO_ACTIVE_CUSTOMER_SERVICE_URL", "").strip()
    or
    os.getenv("CUSTOMER_SERVICE_URL", "").strip()
    or os.getenv("VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL", "").strip()
).rstrip("/")

# Editable inputs（优先环境变量自动填充，减少首次调用重试）
target_type = os.getenv("TOPO_TARGET_TYPE", "").strip().lower()
target_id = os.getenv("TOPO_TARGET_ID", "").strip()
content = os.getenv("TOPO_MESSAGE_CONTENT", "").strip()
message_type = os.getenv("TOPO_MESSAGE_TYPE", "").strip().lower() or "text"
image_base64 = (
    os.getenv("TOPO_MESSAGE_IMAGE_BASE64", "").strip()
    or os.getenv("TOPO_FILE_BASE64", "").strip()
    or os.getenv("TOPO_IMAGE_BASE64", "").strip()
    or os.getenv("FILE_BASE64", "").strip()
    or os.getenv("IMAGE_BASE64", "").strip()
)
sender_label = os.getenv("TOPO_SENDER_LABEL", "").strip() or "我的数字分身"

if image_base64.startswith("data:") and "," in image_base64:
    # Allow both pure base64 and data URL.
    image_base64 = image_base64.split(",", 1)[1].strip()

if not owner_imei:
    raise RuntimeError("Missing TOPO_IMEI (current caller IMEI)")
if not base_url:
    raise RuntimeError("Missing customer_service URL (TOPO_ACTIVE_CUSTOMER_SERVICE_URL / CUSTOMER_SERVICE_URL / VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL)")
if target_type not in ("friend", "group"):
    raise RuntimeError("target_type must be 'friend' or 'group' (set TOPO_TARGET_TYPE)")
if not target_id:
    raise RuntimeError("target_id is required (set TOPO_TARGET_ID)")
if message_type not in ("text", "image"):
    raise RuntimeError("message_type must be 'text' or 'image' (set TOPO_MESSAGE_TYPE)")
if image_base64 and message_type == "text":
    message_type = "image"
if not content and not image_base64:
    raise RuntimeError("either content or image_base64 is required (set TOPO_MESSAGE_CONTENT / TOPO_MESSAGE_IMAGE_BASE64)")
if message_type == "image" and not image_base64:
    raise RuntimeError("image message requires image_base64 (set TOPO_MESSAGE_IMAGE_BASE64)")
if message_type == "image" and not content:
    # customer_service schemas require content(str); give a safe placeholder when image-only.
    content = "[图片]"

common_clone_fields = {
    "senderLabel": sender_label,
    "isCloneReply": True,
    "cloneOwnerImei": owner_imei,
    "cloneOrigin": "digital_clone",
}

if target_type == "friend":
    url = f"{base_url}/api/friends/send-message"
    payload = {
        "imei": owner_imei,
        "targetImei": target_id,
        "content": content,
        "message_type": message_type,
        **({"imageBase64": image_base64} if image_base64 else {}),
        **common_clone_fields,
    }
else:
    # Group mode: tolerate passing group name as target_id.
    if not str(target_id).startswith("group_"):
        try:
            groups_resp = requests.get(
                f"{base_url}/api/groups/list",
                params={"imei": owner_imei},
                timeout=20,
            )
            groups_resp.raise_for_status()
            groups_raw = groups_resp.json()
            groups = groups_raw.get("groups") or []
            by_name = {
                str(g.get("name") or "").strip(): str(g.get("id") or "").strip()
                for g in groups
                if str(g.get("name") or "").strip() and str(g.get("id") or "").strip()
            }
            target_id = by_name.get(target_id, target_id)
        except Exception:
            # Keep original target_id and let server-side validation report the real cause.
            pass

    url = f"{base_url}/api/groups/send-message"
    payload = {
        "imei": owner_imei,
        "groupId": target_id,
        "content": content,
        "message_type": message_type,
        **({"imageBase64": image_base64} if image_base64 else {}),
        "skipServerAssistantDispatch": False,
        # 群里展示字段与单聊字段都传，兼容不同端解析
        "sender": sender_label,
        **common_clone_fields,
    }

resp = requests.post(url, json=payload, timeout=20)
if resp.status_code >= 400:
    detail = ""
    try:
        detail = str(resp.json())
    except Exception:
        detail = resp.text
    raise RuntimeError(f"send owner clone message failed: HTTP {resp.status_code}, detail={detail}")
raw = resp.json()
if not raw.get("success"):
    raise RuntimeError(f"send owner clone message failed: {raw}")

result = {
    "success": True,
    "target_type": target_type,
    "target_id": target_id,
    "owner_imei": owner_imei,
    "sender_label": sender_label,
    "message_type": message_type,
    "has_image": bool(image_base64),
    "content_preview": content[:80],
    "raw": raw,
}
print(json.dumps(result, ensure_ascii=False, indent=2))
```
