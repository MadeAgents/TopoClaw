---
name: list-groups-and-members
description: List current user's groups and members; optionally return one group's full detail from customer_service APIs.
metadata: {"topoclaw":{"emoji":"👥","requires":{"bins":["python"]}}}
---

# List Groups And Members

Use this skill to inspect group memberships.

## Inputs

- `TOPO_IMEI` (required): current caller user's IMEI
  - in TopoDesktop built-in assistant chats, runtime context auto-injects the current logged-in IMEI
- `target_group_id` (optional): if set, return only this group's detail
- `include_member_profile` (optional, default `False`): whether to fetch each member's profile
- auto-fill env (recommended):
  - `TOPO_GROUP_ID` (optional)
  - `TOPO_INCLUDE_MEMBER_PROFILE` (`1/true/yes/on`)
- `CUSTOMER_SERVICE_URL` (optional): service base URL override
- `VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL` (recommended): default customer_service base URL from TopoDesktop `.env.local`
  - recommendation: always configure this variable in `TopoDesktop/.env.local`
  - when missing, fail fast with explicit error instead of silently falling back to `127.0.0.1`

## Output

Structured JSON:
- `imei`
- `groups[]` with `groupId/name/creator_imei/members/assistants/...`
- when `include_member_profile=true`, each group includes `member_profiles[]`

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

def _env_bool(name: str, default: bool = False) -> bool:
    raw = os.getenv(name, "").strip().lower()
    if not raw:
        return default
    return raw in ("1", "true", "yes", "on")

# Editable inputs（优先环境变量自动填充）
target_group_id = os.getenv("TOPO_GROUP_ID", "").strip()
include_member_profile = _env_bool("TOPO_INCLUDE_MEMBER_PROFILE", False)

if not imei:
    raise RuntimeError("Missing TOPO_IMEI (current caller IMEI)")
if not base_url:
    raise RuntimeError("Missing customer_service URL (TOPO_ACTIVE_CUSTOMER_SERVICE_URL / CUSTOMER_SERVICE_URL / VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL)")

def get(path: str, params=None):
    url = f"{base_url}/{path.lstrip('/')}"
    r = requests.get(url, params=params, timeout=20)
    r.raise_for_status()
    return r.json()

groups_data = get("/api/groups/list", {"imei": imei})
groups = groups_data.get("groups") or []

# 若未指定目标群，且当前账号仅有一个群，则自动选中，减少二次调用
if not target_group_id and len(groups) == 1:
    target_group_id = str((groups[0] or {}).get("group_id") or "").strip()

if target_group_id.strip():
    gid = target_group_id.strip()
    detail = get(f"/api/groups/{gid}")
    if detail.get("success") and detail.get("group"):
        groups = [detail["group"]]
    else:
        groups = []

if include_member_profile:
    for g in groups:
        members = g.get("members") or []
        member_profiles = []
        for m in members:
            member_imei = str(m or "").strip()
            if not member_imei:
                continue
            profile = {}
            try:
                p = get(f"/api/profile/{member_imei}")
                profile = p.get("profile") or {}
            except Exception:
                profile = {}
            member_profiles.append(
                {
                    "imei": member_imei,
                    "name": profile.get("name") or member_imei,
                    "nickname": profile.get("nickname"),
                    "signature": profile.get("signature") or profile.get("bio") or profile.get("preferences"),
                    "preferences": profile.get("preferences"),
                }
            )
        g["member_profiles"] = member_profiles

result = {
    "success": True,
    "imei": imei,
    "group_count": len(groups),
    "groups": groups,
}
print(json.dumps(result, ensure_ascii=False, indent=2))
```
