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

import json
import logging
import os

from core.output_paths import PACKAGE_ROOT, VERSION_INFO_FILE

logger = logging.getLogger(__name__)

DEFAULT_UPDATE_URL = (
    os.getenv("CUSTOMER_SERVICE_UPDATE_URL", "").strip()
)


def _version_info_candidates():
    """优先 outputs/version，其次包根目录自带的 version_info.json（便于维护）。"""
    return (VERSION_INFO_FILE, PACKAGE_ROOT / "version_info.json")


def load_version_info():
    """从文件加载版本信息"""
    try:
        for path in _version_info_candidates():
            if path.is_file():
                with open(path, "r", encoding="utf-8") as f:
                    version_info = json.load(f)
                logger.info(
                    "从文件加载版本信息成功，最新版本: %s",
                    version_info.get("latest_version", "unknown"),
                )
                return version_info
        logger.warning("版本信息文件不存在，使用默认值")
        return {
            "latest_version": "2.1.1",
            "min_supported_version": "1.0",
            "update_url": DEFAULT_UPDATE_URL,
            "update_info": {
                "has_update": False,
                "force_update": False,
                "update_message": "开源版本",
            },
        }
    except Exception as e:
        logger.error(f"加载版本信息失败: {e}")
        return {
            "latest_version": "2.1.1",
            "min_supported_version": "1.0",
            "update_url": DEFAULT_UPDATE_URL,
            "update_info": {
                "has_update": False,
                "force_update": False,
                "update_message": "开源版本",
            },
        }
