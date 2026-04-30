# -*- coding: utf-8 -*-
# Copyright 2025 OPPO
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""群组编排（workflow）共享存储服务。"""

import json
import logging
from copy import deepcopy
from threading import RLock
from typing import Any, Dict, Optional

from core.output_paths import GROUP_WORKFLOWS_FILE
from core.time_utils import get_now_isoformat

logger = logging.getLogger(__name__)

_lock = RLock()
group_workflows_storage: Dict[str, Dict[str, Any]] = {}


def load_group_workflows_storage() -> None:
    """从文件加载群组编排数据。"""
    global group_workflows_storage
    try:
        if GROUP_WORKFLOWS_FILE.is_file():
            with open(GROUP_WORKFLOWS_FILE, "r", encoding="utf-8") as f:
                loaded = json.load(f)
            if isinstance(loaded, dict):
                group_workflows_storage = loaded
            else:
                group_workflows_storage = {}
        else:
            group_workflows_storage = {}
        logger.info("加载群组编排数据成功: %s 条", len(group_workflows_storage))
    except Exception as e:
        logger.error("加载群组编排数据失败: %s", e)
        group_workflows_storage = {}


def save_group_workflows_storage() -> None:
    """保存群组编排数据到文件。"""
    try:
        GROUP_WORKFLOWS_FILE.parent.mkdir(parents=True, exist_ok=True)
        with open(GROUP_WORKFLOWS_FILE, "w", encoding="utf-8") as f:
            json.dump(group_workflows_storage, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logger.error("保存群组编排数据失败: %s", e)


def _normalize_workflow_payload(workflow: Dict[str, Any]) -> Dict[str, Any]:
    """最小归一化，避免写入明显异常结构。"""
    payload = deepcopy(workflow) if isinstance(workflow, dict) else {}
    payload.setdefault("schemaVersion", 1)
    payload.setdefault("meta", {})
    payload.setdefault("graph", {"nodes": [], "edges": []})
    payload.setdefault("ui", {})
    payload.setdefault("extras", {})
    return payload


def get_group_workflow(group_id: str) -> Optional[Dict[str, Any]]:
    """获取群组发布版编排。"""
    gid = str(group_id or "").strip()
    if not gid:
        return None
    with _lock:
        data = group_workflows_storage.get(gid)
        return deepcopy(data) if isinstance(data, dict) else None


def save_group_workflow(
    group_id: str,
    imei: str,
    workflow: Dict[str, Any],
    expected_version: Optional[int] = None,
) -> Dict[str, Any]:
    """
    保存群组发布版编排（乐观锁）。

    expected_version:
      - None: 不检查版本，按当前版本 +1 保存
      - int : 必须与当前版本一致，否则返回 conflict
    """
    gid = str(group_id or "").strip()
    operator = str(imei or "").strip()
    if not gid:
        return {"success": False, "message": "group_id 不能为空"}
    if not operator:
        return {"success": False, "message": "imei 不能为空"}

    with _lock:
        current = group_workflows_storage.get(gid) or {}
        current_version = int(current.get("version") or 0)
        if expected_version is not None and int(expected_version) != current_version:
            return {
                "success": False,
                "conflict": True,
                "message": "编排版本冲突，请先刷新后重试",
                "currentVersion": current_version,
                "updatedAt": current.get("updated_at"),
                "updatedBy": current.get("updated_by"),
            }

        normalized_workflow = _normalize_workflow_payload(workflow)
        next_version = current_version + 1
        meta = normalized_workflow.get("meta") if isinstance(normalized_workflow.get("meta"), dict) else {}
        item = {
            "group_id": gid,
            "version": next_version,
            "workflow": normalized_workflow,
            "name": str(meta.get("name") or "").strip(),
            "updated_at": get_now_isoformat(),
            "updated_by": operator,
        }
        group_workflows_storage[gid] = item
        save_group_workflows_storage()

    return {
        "success": True,
        "version": next_version,
        "updatedAt": item["updated_at"],
        "updatedBy": operator,
        "workflow": deepcopy(item["workflow"]),
    }


# 启动时加载
load_group_workflows_storage()
