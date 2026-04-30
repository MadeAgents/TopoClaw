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
from typing import Dict, List

from core.output_paths import FRIENDS_STORAGE_FILE as _FRIENDS_PATH

logger = logging.getLogger(__name__)

friends_storage: Dict[str, List[str]] = {}
FRIENDS_STORAGE_FILE: Path = _FRIENDS_PATH


def load_friends_storage():
    global friends_storage
    try:
        if FRIENDS_STORAGE_FILE.is_file():
            with open(FRIENDS_STORAGE_FILE, "r", encoding="utf-8") as f:
                friends_storage = json.load(f)
                logger.info(f"从文件加载好友关系成功，共 {len(friends_storage)} 个用户")
        else:
            logger.info("好友关系文件不存在，使用空存储")
            friends_storage = {}
    except Exception as e:
        logger.error(f"加载好友关系失败: {e}")
        friends_storage = {}


def save_friends_storage():
    try:
        with open(FRIENDS_STORAGE_FILE, "w", encoding="utf-8") as f:
            json.dump(friends_storage, f, ensure_ascii=False, indent=2)
        logger.debug("保存好友关系到文件成功")
    except Exception as e:
        logger.error(f"保存好友关系失败: {e}")


load_friends_storage()
