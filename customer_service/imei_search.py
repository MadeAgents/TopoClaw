# -*- coding: utf-8 -*-
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

"""按 IMEI 从 outputs 各 JSON/jsonl 中提取相关片段（供 storage_browser 使用）。"""
from __future__ import annotations

import json
import re
from collections import deque
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

MAX_JSON_BYTES = 8 * 1024 * 1024
# unified_messages.json 易随聊天变大，沿用 8MB 会导致 IMEI 聚合「完全没有会话」却无任何提示
MAX_UNIFIED_STORE_BYTES = 64 * 1024 * 1024
UNIFIED_MSG_CAP = 60  # 单会话最多返回条数（过多则首尾截断）
JSONL_PREVIEW_LINES = 8


def _valid_imei_query(q: str) -> bool:
    s = (q or "").strip()
    if not s or len(s) > 128:
        return False
    return bool(re.fullmatch(r"[\w\-.]+", s))


def _read_json(path: Path, max_bytes: int = MAX_JSON_BYTES) -> Optional[Any]:
    if not path.is_file():
        return None
    try:
        sz = path.stat().st_size
    except OSError:
        return None
    if sz > max_bytes:
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return None


def _hit(
    hid: str,
    section: str,
    bucket: str,
    file: str,
    kind: str,
    fragment: Any,
    open_bucket: Optional[str] = None,
    open_file: Optional[str] = None,
) -> Dict[str, Any]:
    return {
        "id": hid,
        "section": section,
        "bucket": bucket,
        "file": file,
        "kind": kind,
        "fragment": fragment,
        "openBucket": open_bucket,
        "openFile": open_file,
    }


def _truncate_messages(msgs: List[Dict], cap: int = UNIFIED_MSG_CAP) -> Tuple[List[Dict], int, bool]:
    n = len(msgs)
    if n <= cap:
        return msgs, n, False
    half = cap // 2
    return msgs[:half] + msgs[-half:], n, True


def _conv_relates_to_imei(conv_id: str, imei: str, group_member: Dict[str, set]) -> bool:
    if not conv_id or not imei:
        return False
    if conv_id == imei:
        return True
    if conv_id.startswith(f"{imei}_"):
        return True
    if conv_id.startswith("friend_"):
        body = conv_id[7:]
        if body == imei:
            return True
        if imei in body.split("_"):
            return True
    if conv_id.startswith("group_"):
        gid = conv_id[6:]
        if gid in group_member and imei in group_member[gid]:
            return True
    return False


def _build_group_member_map(groups_data: Dict[str, Any]) -> Dict[str, set]:
    out: Dict[str, set] = {}
    if not isinstance(groups_data, dict):
        return out
    for gid, g in groups_data.items():
        if not isinstance(g, dict):
            continue
        mem = g.get("members")
        if isinstance(mem, list):
            out[str(gid)] = {str(x) for x in mem if x is not None}
    return out


def _profile_nicknames(outputs_root: Path) -> Dict[str, str]:
    path = outputs_root / "profiles" / "profiles_storage.json"
    prof = _read_json(path)
    out: Dict[str, str] = {}
    if not isinstance(prof, dict):
        return out
    for im, p in prof.items():
        if not isinstance(im, str) or not isinstance(p, dict):
            continue
        n = p.get("name") or p.get("nickname")
        if n is None:
            continue
        s = str(n).strip()
        if s:
            out[im] = s
    return out


def _custom_assistant_id_to_name(outputs_root: Path, imei: str) -> Dict[str, str]:
    ca = _read_json(outputs_root / "custom_assistants" / "custom_assistants.json")
    out: Dict[str, str] = {}
    if not isinstance(ca, dict):
        return out
    lst = ca.get(imei)
    if not isinstance(lst, list):
        return out
    for a in lst:
        if not isinstance(a, dict):
            continue
        aid = a.get("id")
        if not aid:
            continue
        sid = str(aid).strip()
        if not sid:
            continue
        nm = str(a.get("name") or sid).strip() or sid
        out[sid] = nm
    return out


def _group_display_name(groups_data: Any, gid: str) -> Optional[str]:
    if not isinstance(groups_data, dict):
        return None
    g = groups_data.get(gid)
    if not isinstance(g, dict):
        return None
    for k in ("name", "title", "group_name", "groupName"):
        v = g.get(k)
        if v is not None and str(v).strip():
            return str(v).strip()
    return None


def _friend_peer_imei(conv_id: str, imei: str) -> Optional[str]:
    if not conv_id.startswith("friend_"):
        return None
    body = conv_id[7:]
    if body == imei:
        return None
    parts = body.split("_")
    if len(parts) == 2:
        a, b = parts[0], parts[1]
        if a == imei:
            return b
        if b == imei:
            return a
    prefix = imei + "_"
    if body.startswith(prefix):
        return body[len(prefix) :]
    suffix = "_" + imei
    if body.endswith(suffix):
        return body[: -len(suffix)]
    return None


def _unified_conversation_title(
    conv_id: str,
    imei: str,
    nicknames: Dict[str, str],
    groups_data: Any,
    asst_names: Dict[str, str],
) -> Tuple[int, str]:
    """
    返回 (排序类别, 左侧展示标题)。
    类别: 0 好友 1 群 2 默认小助手 3 技能 4 人工客服 5 端云同 IMEI 6 其它/自定义
    """
    if conv_id == imei:
        return (5, "端云消息（统一存储）")

    if conv_id.startswith("friend_"):
        peer = _friend_peer_imei(conv_id, imei)
        if peer:
            nick = nicknames.get(peer)
            return (0, "好友 · " + (nick or peer))
        body = conv_id[7:]
        short = body if len(body) <= 40 else body[:37] + "…"
        return (0, "好友 · " + short)

    if conv_id.startswith("group_"):
        gid = conv_id[6:]
        gname = _group_display_name(groups_data, gid)
        return (1, "群聊 · " + (gname or gid))

    prefix = imei + "_"
    if conv_id.startswith(prefix):
        rest = conv_id[len(prefix) :]
        if rest == "assistant":
            return (2, "小助手 · 自动执行")
        if rest == "skill_learning" or rest.startswith("skill_learning"):
            return (3, "小助手 · 技能学习")
        if rest == "customer_service" or rest.startswith("customer_service"):
            return (4, "人工客服（统一会话）")
        for aid in sorted(asst_names.keys(), key=len, reverse=True):
            aname = asst_names[aid]
            if rest == aid:
                return (6, "小助手 · " + aname)
            if rest.startswith(aid + "_"):
                tail = rest[len(aid) + 1 :]
                label = "小助手 · " + aname + (" · " + tail if tail else "")
                return (6, label)
        short = rest if len(rest) <= 48 else rest[:45] + "…"
        return (6, "自定义会话 · " + short)

    short = conv_id if len(conv_id) <= 48 else conv_id[:45] + "…"
    return (6, "会话 · " + short)


def search_imei(imei: str, outputs_root: Path) -> Tuple[bool, str, List[Dict[str, Any]]]:
    if not _valid_imei_query(imei):
        return False, "IMEI 格式无效（仅字母数字、下划线、连字符，最长 128）", []
    imei = imei.strip()
    hits_head: List[Dict[str, Any]] = []
    hits_um: List[Tuple[Tuple[int, str], Dict[str, Any]]] = []
    hits_mid: List[Dict[str, Any]] = []
    hits_tail: List[Dict[str, Any]] = []
    oid = 0

    def nid(prefix: str) -> str:
        nonlocal oid
        oid += 1
        return f"{prefix}_{oid}"

    nicknames = _profile_nicknames(outputs_root)
    asst_names = _custom_assistant_id_to_name(outputs_root, imei)

    # ---------- profiles ----------
    prof_path = outputs_root / "profiles" / "profiles_storage.json"
    prof = _read_json(prof_path)
    if isinstance(prof, dict) and imei in prof and isinstance(prof[imei], dict):
        hits_head.append(
            _hit(
                nid("prof"),
                "用户资料",
                "profiles",
                "profiles_storage.json",
                "json",
                {imei: prof[imei]},
            )
        )

    # ---------- friends ----------
    friends_path = outputs_root / "friends" / "friends_storage.json"
    friends = _read_json(friends_path)
    if isinstance(friends, dict):
        frag: Dict[str, Any] = {}
        if imei in friends:
            frag["我的好友列表"] = friends[imei]
        reverse = [k for k, v in friends.items() if isinstance(v, list) and imei in v]
        if reverse:
            frag["将我列为好友的用户"] = reverse
        if frag:
            hits_head.append(
                _hit(
                    nid("fr"),
                    "好友关系",
                    "friends",
                    "friends_storage.json",
                    "json",
                    frag,
                )
            )

    # ---------- groups (load for unified + hits) ----------
    groups_path = outputs_root / "groups" / "groups_storage.json"
    ug_path = outputs_root / "groups" / "user_groups.json"
    groups_data = _read_json(groups_path)
    user_groups_data = _read_json(ug_path)
    group_member = _build_group_member_map(groups_data if isinstance(groups_data, dict) else {})

    if isinstance(groups_data, dict):
        related_groups = {
            gid: g
            for gid, g in groups_data.items()
            if isinstance(g, dict) and imei in (g.get("members") or [])
        }
        if related_groups:
            hits_head.append(
                _hit(
                    nid("grp"),
                    "群组详情（成员含该 IMEI）",
                    "groups",
                    "groups_storage.json",
                    "json",
                    related_groups,
                )
            )

    if isinstance(user_groups_data, dict) and imei in user_groups_data:
        hits_head.append(
            _hit(
                nid("ug"),
                "用户所在群组 ID 列表",
                "groups",
                "user_groups.json",
                "json",
                {imei: user_groups_data[imei]},
            )
        )

    cd_path = outputs_root / "multi_device" / "cross_device_messages.json"
    cd_full = _read_json(cd_path)

    # ---------- unified_messages（每会话一条，标题区分好友/群/助手） ----------
    um_path = outputs_root / "unified_messages" / "unified_messages.json"
    um: Optional[Dict[str, Any]] = None
    um_conv_ids: set[str] = set()
    um_oversized = False
    if um_path.is_file():
        try:
            um_sz = um_path.stat().st_size
            if um_sz > MAX_UNIFIED_STORE_BYTES:
                um_oversized = True
        except OSError:
            pass
    if um_oversized:
        hits_um.append(
            (
                (8, "统一消息文件过大", str(um_path)),
                _hit(
                    nid("um"),
                    f"统一消息（文件>{MAX_UNIFIED_STORE_BYTES // (1024 * 1024)}MB 未加载）",
                    "unified_messages",
                    "unified_messages.json",
                    "json",
                    {
                        "error": (
                            f"unified_messages.json 超过浏览上限（{MAX_UNIFIED_STORE_BYTES // (1024 * 1024)}MB），"
                            "IMEI 聚合无法解析。请本地直接打开该文件或缩小文件后再试。"
                        ),
                        "path": str(um_path),
                    },
                ),
            )
        )
    else:
        raw_um = _read_json(um_path, MAX_UNIFIED_STORE_BYTES)
        if isinstance(raw_um, dict):
            um = raw_um
            for conv_id, msgs in um.items():
                if not isinstance(msgs, list):
                    continue
                if not _conv_relates_to_imei(str(conv_id), imei, group_member):
                    continue
                shown, total, trunc = _truncate_messages([m for m in msgs if isinstance(m, dict)])
                cat, section_title = _unified_conversation_title(
                    str(conv_id), imei, nicknames, groups_data, asst_names
                )
                frag = {
                    "conversation_id": conv_id,
                    "message_count": total,
                    "messages": shown,
                    "_truncated": trunc,
                }
                hit = _hit(
                    nid("um"),
                    section_title,
                    "unified_messages",
                    "unified_messages.json",
                    "unified_chat",
                    frag,
                )
                sort_key = (cat, section_title, str(conv_id))
                hits_um.append((sort_key, hit))
                um_conv_ids.add(str(conv_id))

    # 历史端云只写在 cross_device_messages.json、未进 unified 时，补一条气泡会话（与双写后的 imei 键等价）
    if isinstance(cd_full, dict) and imei in cd_full:
        raw_cd = cd_full[imei]
        if isinstance(raw_cd, list) and raw_cd and str(imei) not in um_conv_ids:
            shown, total, trunc = _truncate_messages([m for m in raw_cd if isinstance(m, dict)])
            frag = {
                "conversation_id": imei,
                "message_count": total,
                "messages": shown,
                "_truncated": trunc,
                "_note": "数据仅来自 multi_device/cross_device_messages.json；新服务端已双写到 unified_messages，后续会合并显示。",
            }
            cat, title_base = _unified_conversation_title(
                str(imei), imei, nicknames, groups_data, asst_names
            )
            hit = _hit(
                nid("um"),
                title_base + "（仅 cross_device）",
                "multi_device",
                "cross_device_messages.json",
                "unified_chat",
                frag,
            )
            hits_um.append(((cat, title_base + "（仅 cross_device）", imei), hit))

    hits_um.sort(key=lambda x: x[0])

    # ---------- cross_device ----------
    if isinstance(cd_full, dict) and imei in cd_full:
        hits_mid.append(
            _hit(
                nid("cd"),
                "端云互发消息（独立文件）",
                "multi_device",
                "cross_device_messages.json",
                "json",
                {imei: cd_full[imei]},
            )
        )

    # ---------- custom assistants ----------
    ca_path = outputs_root / "custom_assistants" / "custom_assistants.json"
    ca = _read_json(ca_path)
    if isinstance(ca, dict) and imei in ca:
        hits_mid.append(
            _hit(
                nid("ca"),
                "自定义小助手列表",
                "custom_assistants",
                "custom_assistants.json",
                "json",
                {imei: ca[imei]},
            )
        )

    # ---------- plaza ----------
    pz_path = outputs_root / "plaza" / "plaza_assistants.json"
    pz = _read_json(pz_path)
    if isinstance(pz, list):
        mine = [x for x in pz if isinstance(x, dict) and str(x.get("creator_imei", "")) == imei]
        if mine:
            hits_mid.append(
                _hit(
                    nid("pz"),
                    "广场小助手（创建者为该 IMEI）",
                    "plaza",
                    "plaza_assistants.json",
                    "json",
                    mine,
                )
            )

    # ---------- sessions（合并一条，避免每个 conversation 键一条且标题相同导致刷屏） ----------
    # 说明：unified_sessions.json 存的是各会话的 session 列表元数据，与 unified_messages 里的聊天正文不同。
    ss_path = outputs_root / "sessions" / "unified_sessions.json"
    ss = _read_json(ss_path)
    if isinstance(ss, dict):
        subset: Dict[str, Any] = {}
        for key, sessions in ss.items():
            if not isinstance(sessions, list):
                continue
            sk = str(key)
            if sk == imei or sk.startswith(f"{imei}_"):
                subset[str(key)] = sessions
        if subset:
            n = len(subset)
            hits_tail.append(
                _hit(
                    nid("ss"),
                    f"多 Session 元数据（{n} 个键）",
                    "sessions",
                    "unified_sessions.json",
                    "json",
                    {
                        "_note": "此为各会话的 session 同步列表，不是聊天气泡。好友/群/助手聊天记录请看上方「好友 ·」「群聊 ·」「小助手 ·」等条目（来自 unified_messages.json）。",
                        "sessions_by_conversation_key": subset,
                    },
                )
            )

    # ---------- customer_service jsonl ----------
    cs_dir = outputs_root / "customer_service"
    if cs_dir.is_dir():
        jsonl = cs_dir / f"{imei}.jsonl"
        if jsonl.is_file():
            first: List[Any] = []
            last_deque: deque[Any] = deque(maxlen=JSONL_PREVIEW_LINES)
            total_lines = 0
            try:
                with jsonl.open("r", encoding="utf-8", errors="replace") as fp:
                    for raw in fp:
                        ln = raw.strip()
                        if not ln:
                            continue
                        total_lines += 1

                        def _parse(line: str) -> Any:
                            try:
                                return json.loads(line)
                            except json.JSONDecodeError:
                                return {"_raw": line[:200]}

                        if len(first) < JSONL_PREVIEW_LINES:
                            first.append(_parse(ln))
                        last_deque.append(_parse(ln))
            except OSError:
                total_lines = -1
            last = list(last_deque) if total_lines > JSONL_PREVIEW_LINES else []
            frag = {
                "type": "jsonl_preview",
                "file": jsonl.name,
                "line_count": total_lines,
                "first_messages": first,
                "last_messages": last if total_lines > JSONL_PREVIEW_LINES else [],
            }
            hits_tail.append(
                _hit(
                    nid("cs"),
                    "人工客服对话（摘要）",
                    "customer_service",
                    jsonl.name,
                    "jsonl_meta",
                    frag,
                    open_bucket="customer_service",
                    open_file=jsonl.name,
                )
            )

    hits = hits_head + [h for _, h in hits_um] + hits_mid + hits_tail
    return True, "", hits
