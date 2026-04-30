---
name: update-group-members-and-manager
description: Update group members (add/remove) and switch group manager assistant via customer_service APIs.
metadata: {"topoclaw":{"emoji":"🛠️","requires":{"bins":["python"]}}}
---

# Update Group Members And Manager

Use this skill to modify an existing group.

## Inputs

- `TOPO_IMEI` (required): group owner IMEI / caller IMEI
  - in TopoDesktop built-in assistant chats, runtime context auto-injects the current logged-in IMEI
- `group_id` (required): target group ID
- `add_member_imeis` (optional): members to add
- `remove_member_imeis` (optional): members to remove
- `group_manager_assistant_id` (optional): assistant ID to set as group manager
- `remove_group_manager` (optional, default `False`): remove current group manager tag from all assistants
- auto-fill env (recommended to avoid first-call retry):
  - `TOPO_GROUP_ID` (preferred) or `TOPO_GROUP_NAME` (fallback by name)
  - `TOPO_ADD_MEMBER_IMEIS` / `TOPO_REMOVE_MEMBER_IMEIS` (JSON array or comma-separated)
  - `TOPO_GROUP_MANAGER_ASSISTANT_ID`
  - `TOPO_REMOVE_GROUP_MANAGER` (`1/true/yes/on`)
- `CUSTOMER_SERVICE_URL` (optional): service base URL override
- `VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL` (recommended): default customer_service base URL from TopoDesktop `.env.local`
  - recommendation: always configure this variable in `TopoDesktop/.env.local`
  - when missing, fail fast with explicit error instead of silently falling back to `127.0.0.1`

## Output

Structured JSON with:
- add/remove member results
- manager update results
- final group detail

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

def _env_list(name: str):
    raw = os.getenv(name, "").strip()
    if not raw:
        return []
    try:
        val = json.loads(raw)
        if isinstance(val, list):
            return [str(x).strip() for x in val if str(x).strip()]
    except Exception:
        pass
    return [x.strip() for x in raw.split(",") if x.strip()]

def _env_bool(name: str, default: bool = False) -> bool:
    raw = os.getenv(name, "").strip().lower()
    if not raw:
        return default
    return raw in ("1", "true", "yes", "on")

# Editable inputs（优先环境变量自动填充，减少首次调用重试）
group_id = os.getenv("TOPO_GROUP_ID", "").strip()
group_name = os.getenv("TOPO_GROUP_NAME", "").strip()
add_member_imeis = _env_list("TOPO_ADD_MEMBER_IMEIS")
remove_member_imeis = _env_list("TOPO_REMOVE_MEMBER_IMEIS")
group_manager_assistant_id = os.getenv("TOPO_GROUP_MANAGER_ASSISTANT_ID", "").strip()
remove_group_manager = _env_bool("TOPO_REMOVE_GROUP_MANAGER", False)

if not owner_imei:
    raise RuntimeError("Missing TOPO_IMEI (group owner / caller IMEI)")
if not base_url:
    raise RuntimeError("Missing customer_service URL (TOPO_ACTIVE_CUSTOMER_SERVICE_URL / CUSTOMER_SERVICE_URL / VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL)")

def post(path: str, body: dict):
    url = f"{base_url}/{path.lstrip('/')}"
    r = requests.post(url, json=body, timeout=20)
    r.raise_for_status()
    return r.json()

def get(path: str, params=None):
    url = f"{base_url}/{path.lstrip('/')}"
    r = requests.get(url, params=params, timeout=20)
    r.raise_for_status()
    return r.json()

group_id = str(group_id or "").strip()
if not group_id:
    groups_data = get("/api/groups/list", {"imei": owner_imei})
    groups = groups_data.get("groups") or []
    if group_name:
        matched = [g for g in groups if str((g or {}).get("name") or "").strip() == group_name]
        if len(matched) == 1:
            group_id = str((matched[0] or {}).get("group_id") or "").strip()
    if not group_id and len(groups) == 1:
        group_id = str((groups[0] or {}).get("group_id") or "").strip()
if not group_id:
    raise RuntimeError("group_id is required (set TOPO_GROUP_ID or TOPO_GROUP_NAME)")

results = {
    "member_added": [],
    "member_removed": [],
    "manager_updates": [],
}

for m in add_member_imeis:
    member = str(m or "").strip()
    if not member:
        continue
    item = {"member_imei": member, "success": False}
    try:
        res = post("/api/groups/add-member", {"imei": owner_imei, "groupId": group_id, "memberImei": member})
        item["success"] = bool(res.get("success"))
        item["raw"] = res
    except Exception as e:
        item["error"] = str(e)
    results["member_added"].append(item)

for m in remove_member_imeis:
    member = str(m or "").strip()
    if not member:
        continue
    item = {"member_imei": member, "success": False}
    try:
        res = post("/api/groups/remove-member", {"imei": owner_imei, "groupId": group_id, "memberImei": member})
        item["success"] = bool(res.get("success"))
        item["raw"] = res
    except Exception as e:
        item["error"] = str(e)
    results["member_removed"].append(item)

detail = get(f"/api/groups/{group_id}")
group = detail.get("group") if isinstance(detail, dict) else {}
assistants = list((group or {}).get("assistants") or [])
assistant_configs = dict((group or {}).get("assistant_configs") or {})

if remove_group_manager:
    for aid in assistants:
        if str(aid or "").strip() in ("assistant", "skill_learning", "chat_assistant", "customer_service"):
            continue
        item = {"assistant_id": aid, "operation": "remove_group_manager", "success": False}
        try:
            current = assistant_configs.get(aid) or {}
            caps = [str(x).strip() for x in (current.get("capabilities") or ["chat"]) if str(x).strip()]
            caps = [c for c in caps if c != "group_manager"]
            if not caps:
                caps = ["chat"]
            res = post(
                "/api/groups/update-assistant-config",
                {"imei": owner_imei, "groupId": group_id, "assistantId": aid, "capabilities": caps},
            )
            item["success"] = bool(res.get("success"))
            item["raw"] = res
        except Exception as e:
            item["error"] = str(e)
        results["manager_updates"].append(item)

manager_id = str(group_manager_assistant_id or "").strip()
if manager_id:
    if manager_id not in assistants:
        add_item = {"assistant_id": manager_id, "operation": "add_assistant_if_missing", "success": False}
        try:
            add_res = post("/api/groups/add-assistant", {"imei": owner_imei, "groupId": group_id, "assistantId": manager_id})
            add_item["success"] = bool(add_res.get("success"))
            add_item["raw"] = add_res
        except Exception as e:
            add_item["error"] = str(e)
        results["manager_updates"].append(add_item)

    set_item = {"assistant_id": manager_id, "operation": "set_group_manager", "success": False}
    try:
        set_res = post(
            "/api/groups/update-assistant-config",
            {"imei": owner_imei, "groupId": group_id, "assistantId": manager_id, "capabilities": ["chat", "group_manager"]},
        )
        set_item["success"] = bool(set_res.get("success"))
        set_item["raw"] = set_res
    except Exception as e:
        set_item["error"] = str(e)
    results["manager_updates"].append(set_item)

final_detail = get(f"/api/groups/{group_id}")

output = {
    "success": True,
    "owner_imei": owner_imei,
    "group_id": group_id,
    "requested_group_manager_assistant_id": manager_id or None,
    "remove_group_manager": bool(remove_group_manager),
    "results": results,
    "group": final_detail.get("group") if isinstance(final_detail, dict) else final_detail,
}
print(json.dumps(output, ensure_ascii=False, indent=2))
```
