---
name: create-group-and-send-message
description: Unified group creation skill supporting multiple friends and assistants, with optional first message sending.
metadata: {"topoclaw":{"emoji":"lightning","requires":{"bins":["python"]}}}
---

# Create Group And Send Message

Use this as the only group-creation skill. It supersedes legacy `create-group`.

## Hard Safety Constraints

- API-only execution; do not use shell orchestration for this workflow.
- Do not call `write_file` to generate temporary `.py` files.
- Do not run `exec` with script-file paths.
- Do not use shell redirection like `>nul`, `2>nul`, `> /dev/null`.
- Do not write under packaged runtime paths such as `release/win-unpacked/resources/...`.

## Inputs

- `TOPO_IMEI` (required): caller IMEI
- `TOPO_OWNER_IMEI` (optional): owner IMEI override, default caller IMEI
- `TOPO_GROUP_NAME` (optional): group name, default `\u6211\u7684\u65b0\u7fa4\u7ec4`
- `TOPO_FRIEND_IMEIS` (optional): JSON array or comma-separated IMEIs
- `TOPO_TARGET_FRIEND` (optional, backward-compatible): one selector (`nickname/name/IMEI`)
- `TOPO_TARGET_FRIENDS` (optional): JSON array or comma-separated selectors (`nickname/name/IMEI`)
- `TOPO_ASSISTANT_DISPLAY_IDS` (optional): JSON array or comma-separated display IDs
- `TOPO_ASSISTANT_IDS` (optional, deprecated): alias for display IDs
- `TOPO_GROUP_MANAGER_ASSISTANT_DISPLAY_ID` (optional): display ID to enable GroupManager capability
- `TOPO_GROUP_MANAGER_ASSISTANT_ID` (optional, deprecated): alias
- `TOPO_AUTO_ADD_FRIEND_TOPOCLAW` (optional, default `true`; when friend clone is missing, trigger `/api/profile/{imei}` once and retry)
- `TOPO_GROUP_MESSAGE` (optional): first message content
- `TOPO_SEND_FIRST_MESSAGE` (optional bool): defaults to true only when message exists
- `TOPO_GROUP_SENDER` (optional): first message sender label, default `TopoClaw`
- `CUSTOMER_SERVICE_URL` (optional)
- `VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL` (recommended)

## Canonical API Flow

1. Resolve owner/group/friends/assistants from inputs.
2. `POST /api/groups/create`.
3. Ensure `free_discovery=true`; if false, call `POST /api/groups/update-config`.
4. Resolve auto assistants for owner + friend members (with profile ensure + retry when needed).
5. Add assistants with `POST /api/groups/add-assistant`.
6. If `group_manager_assistant_display_id` is set, call `POST /api/groups/update-assistant-config`.
7. If enabled, send first message via `POST /api/groups/send-assistant-message`.
8. Fetch final state via `GET /api/groups/{groupId}` and return structured JSON.

## Result Reporting Rule (Critical)

When reporting to user:

- You must only claim an assistant was added if its corresponding `assistant_add_results[i].success == true`.
- `auto_friend_topoclaw_candidates` means "resolved candidate assistants", not "added successfully".
- `auto_friend_topoclaw_ensure_attempts` records profile ensure/retry attempts for missing default clones.
- If add failed or resolve failed, explicitly report failure reason from `auto_friend_topoclaw_errors` or `assistant_add_results[].error`.

## Error Handling

- Missing owner IMEI -> fail fast
- Missing service URL -> fail fast
- Empty group name -> fail fast
- `TOPO_SEND_FIRST_MESSAGE=true` with empty message -> fail fast
- Group create failure -> fail fast
- First message send failure -> fail fast
- Assistant add failures -> include per-assistant result in output

## Reference Script

```python
import json
import os
import requests

caller_imei = os.getenv("TOPO_IMEI", "").strip() or os.getenv("IMEI", "").strip()
owner_imei = os.getenv("TOPO_OWNER_IMEI", "").strip() or caller_imei
group_name = os.getenv("TOPO_GROUP_NAME", "").strip() or "\u6211\u7684\u65b0\u7fa4\u7ec4"
base_url = (
    os.getenv("TOPO_ACTIVE_CUSTOMER_SERVICE_URL", "").strip()
    or os.getenv("CUSTOMER_SERVICE_URL", "").strip()
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

def _env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name, "").strip().lower()
    if not raw:
        return default
    return raw in ("1", "true", "yes", "on")

def _norm(s: str) -> str:
    return str(s or "").strip().lower()

def _normalize_base_url(url: str) -> str:
    return str(url or "").strip().lower().rstrip("/")

if not owner_imei:
    raise RuntimeError("Missing TOPO_IMEI/TOPO_OWNER_IMEI")
if not base_url:
    raise RuntimeError("Missing customer_service URL (TOPO_ACTIVE_CUSTOMER_SERVICE_URL / CUSTOMER_SERVICE_URL / VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL)")
if not group_name.strip():
    raise RuntimeError("group_name cannot be empty")

friend_imeis = _env_list("TOPO_FRIEND_IMEIS")
target_friend = os.getenv("TOPO_TARGET_FRIEND", "").strip()
target_friends = _env_list("TOPO_TARGET_FRIENDS")
assistant_display_ids = _env_list("TOPO_ASSISTANT_DISPLAY_IDS") or _env_list("TOPO_ASSISTANT_IDS")
group_manager_display_id = (
    os.getenv("TOPO_GROUP_MANAGER_ASSISTANT_DISPLAY_ID", "").strip()
    or os.getenv("TOPO_GROUP_MANAGER_ASSISTANT_ID", "").strip()
)
auto_add_friend_topoclaw = _env_bool("TOPO_AUTO_ADD_FRIEND_TOPOCLAW", True)
group_message = os.getenv("TOPO_GROUP_MESSAGE", "").strip()
send_env_raw = os.getenv("TOPO_SEND_FIRST_MESSAGE", "").strip()
send_first_message = _env_bool("TOPO_SEND_FIRST_MESSAGE", bool(group_message)) if send_env_raw else bool(group_message)
group_sender = os.getenv("TOPO_GROUP_SENDER", "").strip() or "TopoClaw"
if send_first_message and not group_message:
    raise RuntimeError("TOPO_SEND_FIRST_MESSAGE=true but TOPO_GROUP_MESSAGE is empty")

def get(path: str, params=None):
    url = f"{base_url}/{path.lstrip('/')}"
    r = requests.get(url, params=params, timeout=20)
    r.raise_for_status()
    return r.json()

def post(path: str, body: dict):
    url = f"{base_url}/{path.lstrip('/')}"
    r = requests.post(url, json=body, timeout=20)
    r.raise_for_status()
    return r.json()

def _is_customer_topoclaw_assistant(item: dict) -> bool:
    aid = str(item.get("id") or "").strip().lower()
    did = str(item.get("displayId") or "").strip().lower()
    name = str(item.get("name") or "").strip().lower()
    bu = _normalize_base_url(item.get("baseUrl") or "")
    if "customer_topoclaw" in aid or "customer_topoclaw" in did:
        return True
    return ("topoclaw" in aid or "topoclaw" in name) and bu != "topoclaw://relay"

def _fetch_custom_assistants(target_imei: str):
    data = get("/api/custom-assistants", {"imei": target_imei})
    arr = data.get("assistants") or []
    return arr if isinstance(arr, list) else []

def _resolve_default_topoclaw_assistant(target_imei: str):
    assistants = _fetch_custom_assistants(target_imei)
    preferred, relay_fallback = [], []
    for item in assistants:
        if not isinstance(item, dict):
            continue
        if _is_customer_topoclaw_assistant(item):
            preferred.append(item)
        elif _normalize_base_url(item.get("baseUrl") or "") == "topoclaw://relay":
            relay_fallback.append(item)
    for item in (preferred or relay_fallback):
        did = str(item.get("displayId") or "").strip()
        if not did:
            continue
        return {
            "owner_imei": target_imei,
            "display_id": did,
            "assistant_id_internal": str(item.get("id") or "").strip(),
            "name": str(item.get("name") or "TopoClaw").strip() or "TopoClaw",
            "base_url": str(item.get("baseUrl") or "topoclaw://relay"),
            "intro": str(item.get("intro") or ""),
            "avatar": str(item.get("avatar") or ""),
            "capabilities": item.get("capabilities") or ["chat", "skills", "cron"],
            "multi_session": bool(item.get("multiSessionEnabled", True)),
        }
    return None

def _ensure_default_topoclaw_via_profile(target_imei: str):
    imei = str(target_imei or "").strip()
    if not imei:
        return False, "empty_member_imei", None
    try:
        url = f"{base_url}/api/profile/{imei}"
        r = requests.post(url, data={}, timeout=20)
        r.raise_for_status()
        data = r.json()
        if isinstance(data, dict) and data.get("success"):
            return True, None, data
        return False, f"profile_update_unsuccess: {data}", data
    except Exception as e:
        return False, f"ensure_default_topoclaw_via_profile_failed: {e}", None

def _resolve_assistant_by_display_id(target_imei: str, display_id: str):
    did = str(display_id or "").strip()
    if not did:
        return None
    for item in _fetch_custom_assistants(target_imei):
        if not isinstance(item, dict):
            continue
        if str(item.get("displayId") or "").strip() != did:
            continue
        return {
            "owner_imei": target_imei,
            "display_id": did,
            "assistant_id_internal": str(item.get("id") or "").strip(),
            "name": str(item.get("name") or did),
            "base_url": str(item.get("baseUrl") or ""),
            "intro": str(item.get("intro") or ""),
            "avatar": str(item.get("avatar") or ""),
            "capabilities": item.get("capabilities") or ["chat"],
            "multi_session": bool(item.get("multiSessionEnabled", True)),
        }
    return None

def _resolve_friend_selectors(selectors: list[str]):
    if not selectors:
        return [], []
    data = get("/api/friends/list", {"imei": owner_imei})
    friends = data.get("friends") or []
    if not isinstance(friends, list):
        friends = []
    resolved, errors = [], []
    for selector in selectors:
        q = _norm(selector)
        exact, contains = [], []
        for f in friends:
            if not isinstance(f, dict):
                continue
            imei = str(f.get("imei") or "").strip()
            nickname = str(f.get("nickname") or "").strip()
            candidates = [_norm(imei), _norm(nickname)]
            if q in candidates:
                exact.append({"imei": imei, "nickname": nickname or imei, "query": selector})
            elif any(q and q in c for c in candidates if c):
                contains.append({"imei": imei, "nickname": nickname or imei, "query": selector})
        if len(exact) == 1:
            resolved.append(exact[0])
        elif len(exact) > 1:
            errors.append({"query": selector, "error": "ambiguous_exact", "matches": exact})
        elif len(contains) == 1:
            resolved.append(contains[0])
        elif len(contains) > 1:
            errors.append({"query": selector, "error": "ambiguous_contains", "matches": contains})
        else:
            errors.append({"query": selector, "error": "not_found"})
    return resolved, errors

friend_selectors = ([target_friend] if target_friend else []) + [x for x in target_friends if str(x).strip()]
resolved_friends, friend_selector_errors = _resolve_friend_selectors(friend_selectors)
member_imeis = list(dict.fromkeys(
    [str(x).strip() for x in friend_imeis if str(x).strip()]
    + [str(x.get("imei") or "").strip() for x in resolved_friends if str(x.get("imei") or "").strip()]
))

assistant_spec_map = {}
assistant_display_resolve_errors = []
for did in [str(x).strip() for x in assistant_display_ids if str(x).strip()]:
    spec = _resolve_assistant_by_display_id(owner_imei, did)
    if spec:
        assistant_spec_map[spec["display_id"]] = spec
    else:
        assistant_display_resolve_errors.append({"display_id": did, "error": "display_id_not_found"})

auto_friend_topoclaw_candidates = []
auto_friend_topoclaw_errors = []
auto_friend_topoclaw_ensure_attempts = []
if auto_add_friend_topoclaw:
    for m_imei in list(dict.fromkeys([owner_imei, *member_imeis])):
        try:
            spec = _resolve_default_topoclaw_assistant(m_imei)
            if not spec:
                ensured, ensure_err, ensure_raw = _ensure_default_topoclaw_via_profile(m_imei)
                ensure_item = {
                    "member_imei": m_imei,
                    "ensure_called": True,
                    "ensure_success": bool(ensured),
                }
                if ensure_err:
                    ensure_item["ensure_error"] = ensure_err
                if ensure_raw is not None:
                    ensure_item["ensure_raw"] = ensure_raw
                if ensured:
                    spec = _resolve_default_topoclaw_assistant(m_imei)
                    ensure_item["retry_success"] = bool(spec)
                    if spec:
                        ensure_item["retry_display_id"] = spec.get("display_id")
                    else:
                        ensure_item["retry_error"] = "default_topoclaw_not_found_after_ensure"
                auto_friend_topoclaw_ensure_attempts.append(ensure_item)
                if not spec:
                    auto_friend_topoclaw_errors.append({"member_imei": m_imei, "error": "default_topoclaw_not_found"})
                    continue
            assistant_spec_map[spec["display_id"]] = spec
            auto_friend_topoclaw_candidates.append({
                "member_imei": m_imei,
                "display_id": spec["display_id"],
                "assistant_id_internal": spec["assistant_id_internal"],
            })
        except Exception as e:
            auto_friend_topoclaw_errors.append({"member_imei": m_imei, "error": str(e)})

if group_manager_display_id and group_manager_display_id not in assistant_spec_map:
    spec = _resolve_assistant_by_display_id(owner_imei, group_manager_display_id)
    if spec:
        assistant_spec_map[spec["display_id"]] = spec
    else:
        assistant_display_resolve_errors.append({"display_id": group_manager_display_id, "error": "group_manager_resolve_failed"})

assistant_display_ids_final = list(assistant_spec_map.keys())
create_res = post("/api/groups/create", {
    "imei": owner_imei,
    "name": group_name.strip(),
    "memberImeis": member_imeis,
    "assistantEnabled": bool(assistant_display_ids_final),
})
if not create_res.get("success"):
    raise RuntimeError(f"Create group failed: {create_res}")
group_id = str(create_res.get("groupId") or "").strip()
if not group_id:
    raise RuntimeError(f"Missing groupId from create result: {create_res}")

group = create_res.get("group") or {}
free_discovery = bool(group.get("free_discovery", False))
if not free_discovery:
    cfg_res = post("/api/groups/update-config", {"imei": owner_imei, "groupId": group_id, "freeDiscovery": True})
    if not cfg_res.get("success"):
        raise RuntimeError(f"Enable freeDiscovery failed: {cfg_res}")
    refreshed = get(f"/api/groups/{group_id}")
    group = (refreshed or {}).get("group") or group
    free_discovery = bool(group.get("free_discovery", False))

assistant_results = []
auto_friend_topoclaw_assistants = []
auto_candidate_set = {
    (str(x.get("member_imei") or "").strip(), str(x.get("display_id") or "").strip())
    for x in auto_friend_topoclaw_candidates
}

for display_id in assistant_display_ids_final:
    spec = assistant_spec_map.get(display_id) or {}
    internal_id = str(spec.get("assistant_id_internal") or "").strip() or display_id
    item = {"displayId": display_id, "assistantId_internal": internal_id, "owner_imei": spec.get("owner_imei"), "success": False}
    try:
        add_res = post("/api/groups/add-assistant", {
            "imei": owner_imei,
            "groupId": group_id,
            "assistantId": internal_id,
            "creatorImei": spec.get("owner_imei") or owner_imei,
            "displayId": display_id,
            "baseUrl": spec.get("base_url") or "",
            "name": spec.get("name") or display_id,
            "capabilities": spec.get("capabilities") or ["chat"],
            "intro": spec.get("intro") or "",
            "avatar": spec.get("avatar") or "",
            "multiSession": bool(spec.get("multi_session", True)),
        })
        item["success"] = bool(add_res.get("success"))
        item["add_raw"] = add_res
        if item["success"]:
            owner_key = str(spec.get("owner_imei") or "").strip()
            if (owner_key, display_id) in auto_candidate_set:
                auto_friend_topoclaw_assistants.append({
                    "member_imei": owner_key,
                    "display_id": display_id,
                    "assistant_id_internal": internal_id,
                    "success": True,
                })
        elif (str(spec.get("owner_imei") or "").strip(), display_id) in auto_candidate_set:
            auto_friend_topoclaw_errors.append({
                "member_imei": str(spec.get("owner_imei") or "").strip() or None,
                "display_id": display_id,
                "error": "add_assistant_failed",
                "raw": add_res,
            })

        if group_manager_display_id and display_id == group_manager_display_id and item["success"]:
            gm_res = post("/api/groups/update-assistant-config", {
                "imei": owner_imei,
                "groupId": group_id,
                "assistantId": internal_id,
                "capabilities": ["chat", "group_manager"],
            })
            item["group_manager_config_updated"] = bool(gm_res.get("success"))
            item["group_manager_update_raw"] = gm_res
    except Exception as e:
        item["error"] = str(e)
        if (str(spec.get("owner_imei") or "").strip(), display_id) in auto_candidate_set:
            auto_friend_topoclaw_errors.append({
                "member_imei": str(spec.get("owner_imei") or "").strip() or None,
                "display_id": display_id,
                "error": str(e),
            })
    assistant_results.append(item)

send_raw = None
if send_first_message:
    send_raw = post("/api/groups/send-assistant-message", {
        "imei": owner_imei,
        "groupId": group_id,
        "content": group_message,
        "sender": group_sender,
    })
    if not send_raw.get("success"):
        raise RuntimeError(f"Send first group message failed: {send_raw}")

group_detail = get(f"/api/groups/{group_id}") if group_id else {}
result = {
    "success": True,
    "caller_imei": caller_imei or None,
    "owner_imei": owner_imei,
    "friend_selectors": friend_selectors,
    "friend_selector_errors": friend_selector_errors,
    "friend_imeis_final": member_imeis,
    "assistant_display_resolve_errors": assistant_display_resolve_errors,
    "auto_add_friend_topoclaw": bool(auto_add_friend_topoclaw),
    "auto_friend_topoclaw_candidates": auto_friend_topoclaw_candidates,
    "auto_friend_topoclaw_assistants": auto_friend_topoclaw_assistants,
    "auto_friend_topoclaw_ensure_attempts": auto_friend_topoclaw_ensure_attempts,
    "auto_friend_topoclaw_errors": auto_friend_topoclaw_errors,
    "assistant_display_ids_final": assistant_display_ids_final,
    "group_manager_assistant_display_id": group_manager_display_id or None,
    "groupId": group_id,
    "groupName": (group_detail.get("group") or {}).get("name") if isinstance(group_detail, dict) else group_name,
    "free_discovery": free_discovery,
    "create": create_res,
    "assistant_add_results": assistant_results,
    "send_first_message": bool(send_first_message),
    "send_raw": send_raw,
    "group": group_detail.get("group") if isinstance(group_detail, dict) else group_detail,
}
print(json.dumps(result, ensure_ascii=False, indent=2))
```
