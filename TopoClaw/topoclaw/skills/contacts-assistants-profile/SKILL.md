---
name: contacts-assistants-profile
description: Retrieve current user's friends and assistant profiles (friend name/signature/preferences + assistant nickname/intro/baseUrl) from customer_service APIs. Use when user asks to inspect or export social/contact profile data.
metadata: {"topoclaw":{"emoji":"👥","requires":{"bins":["python"]}}}
---

# Contacts & Assistants Profile

Use this skill to fetch profile-style data for:
- all friends of a user
- all custom assistants of the same user

## Inputs

- `TOPO_IMEI` (required): current caller user's IMEI
  - in TopoDesktop built-in assistant chats, runtime context auto-injects the current logged-in IMEI
- `CUSTOMER_SERVICE_URL` (optional): customer_service base URL override
- `VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL` (recommended): default customer_service base URL from TopoDesktop `.env.local`
  - recommendation: always configure this variable in `TopoDesktop/.env.local`
  - when missing, fail fast with explicit error instead of silently falling back to `127.0.0.1`

## Output

Return structured JSON containing:
- `imei`
- `friends[]` with `imei/name/nickname/signature/preferences/phone/address`
- `assistants[]` with `id/nickname/intro/baseUrl`

Notes:
- hide internal relay assistant entries (`baseUrl = topoclaw://relay`) from user-facing assistant lists
- keep system auto assistant (`assistant`) in output for group operations

## Reference Script

Run in shell (or adapt as tool call logic):

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

if not imei:
    raise RuntimeError("Missing TOPO_IMEI (current caller IMEI)")
if not base_url:
    raise RuntimeError("Missing customer_service URL (TOPO_ACTIVE_CUSTOMER_SERVICE_URL / CUSTOMER_SERVICE_URL / VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL)")

def get(path: str, params=None):
    url = f"{base_url}/{path.lstrip('/')}"
    r = requests.get(url, params=params, timeout=20)
    r.raise_for_status()
    return r.json()

friends_data = get("/api/friends/list", {"imei": imei})
friends = friends_data.get("friends") or []

friend_profiles = []
for f in friends:
    f_imei = str(f.get("imei") or "").strip()
    profile = {}
    if f_imei:
        try:
            p = get(f"/api/profile/{f_imei}")
            profile = p.get("profile") or {}
        except Exception:
            profile = {}
    friend_profiles.append(
        {
            "imei": f_imei,
            "name": profile.get("name") or f.get("nickname") or f_imei,
            "nickname": f.get("nickname"),
            "signature": profile.get("signature") or profile.get("bio") or profile.get("preferences"),
            "preferences": profile.get("preferences"),
            "phone": profile.get("phone"),
            "address": profile.get("address"),
        }
    )

assistants_data = get("/api/custom-assistants", {"imei": imei})
assistants = assistants_data.get("assistants") or []

def _normalize_base_url(url: str) -> str:
    return str(url or "").strip().lower().rstrip("/")

assistant_profiles = [
    {
        "id": a.get("id"),
        "nickname": a.get("name"),
        "intro": a.get("intro"),
        "baseUrl": a.get("baseUrl"),
        "source": "custom",
    }
    for a in assistants
    if _normalize_base_url(a.get("baseUrl") or "") != "topoclaw://relay"
]

# 补充系统自动执行小助手（不在 /api/custom-assistants 内时）
known_ids = {str(item.get("id") or "").strip() for item in assistant_profiles}
if "assistant" not in known_ids:
    assistant_profiles.append(
        {
            "id": "assistant",
            "nickname": "自动执行小助手",
            "intro": "系统内置自动执行助手（群组默认助手）",
            "baseUrl": "",
            "source": "system",
        }
    )

result = {"imei": imei, "friends": friend_profiles, "assistants": assistant_profiles}
print(json.dumps(result, ensure_ascii=False, indent=2))
```
