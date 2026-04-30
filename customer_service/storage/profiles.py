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
from pathlib import Path
from typing import Dict

from core.output_paths import PROFILES_STORAGE_FILE as _PROFILES_PATH

logger = logging.getLogger(__name__)

profiles_storage: Dict[str, Dict] = {}
PROFILES_STORAGE_FILE: Path = _PROFILES_PATH


def load_profiles_storage():
    global profiles_storage
    try:
        if PROFILES_STORAGE_FILE.is_file():
            with open(PROFILES_STORAGE_FILE, "r", encoding="utf-8") as f:
                profiles_storage = json.load(f)
                logger.info(f"从文件加载用户资料成功，共 {len(profiles_storage)} 个用户")
        else:
            logger.info("用户资料文件不存在，使用空存储")
            profiles_storage = {}
    except Exception as e:
        logger.error(f"加载用户资料失败: {e}")
        profiles_storage = {}


def save_profiles_storage():
    try:
        with open(PROFILES_STORAGE_FILE, "w", encoding="utf-8") as f:
            json.dump(profiles_storage, f, ensure_ascii=False, indent=2)
        logger.debug("保存用户资料到文件成功")
    except Exception as e:
        logger.error(f"保存用户资料失败: {e}")


load_profiles_storage()
