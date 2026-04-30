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

"""广场小助手对外展示：根据用户资料丰富创建者信息"""

from typing import Any, Dict, Optional

from storage.profiles import profiles_storage
from services.plaza_assistant_store import _creator_imei_key


def enrich_plaza_item_for_client(item: Dict[str, Any], viewer_imei: Optional[str] = None) -> Dict[str, Any]:
    """
    在 JSON 存储中 creator_imei 为创建者原始 imei。
    对外返回：creator_imei 为展示用文案（短 imei · 昵称）；creator_avatar 为创建者头像（若有资料）。
    """
    out = dict(item)
    raw = (item.get("creator_imei") or "").strip()
    imei_key = raw.split(" · ", 1)[0].strip() if " · " in raw else raw
    profile = profiles_storage.get(imei_key, {}) if imei_key else {}
    nick = (profile.get("name") or "").strip()
    av = profile.get("avatar")

    prefix = (imei_key[:16] if len(imei_key) > 16 else imei_key) if imei_key else ""

    if nick:
        out["creator_imei"] = f"{prefix} · {nick}" if prefix else nick
    elif " · " in raw:
        out["creator_imei"] = raw
    else:
        out["creator_imei"] = prefix or raw

    if av:
        out["creator_avatar"] = av
    else:
        out.pop("creator_avatar", None)

    v = (viewer_imei or "").strip()
    raw_creator = (item.get("creator_imei") or "").strip()
    out["is_creator"] = bool(v and _creator_imei_key(raw_creator) == v)

    liked = list(item.get("liked_imeis") or [])
    if not isinstance(liked, list):
        liked = []
    out["likes_count"] = len(liked)
    out["liked_by_me"] = bool(v and v in liked)
    out.pop("liked_imeis", None)

    return out
