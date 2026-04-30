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

"""端云互发消息存储 {imei: [{id, from_device, content, ...}]}"""
import json
import logging
from pathlib import Path
from typing import Dict, List

from core.output_paths import CROSS_DEVICE_MESSAGES_FILE

logger = logging.getLogger(__name__)

CROSS_DEVICE_STORAGE_FILE: Path = CROSS_DEVICE_MESSAGES_FILE
_cross_device_messages: Dict[str, List[Dict]] = {}


def load_cross_device_messages():
    global _cross_device_messages
    try:
        if CROSS_DEVICE_STORAGE_FILE.is_file():
            with open(CROSS_DEVICE_STORAGE_FILE, "r", encoding="utf-8") as f:
                _cross_device_messages = json.load(f)
            logger.info(f"加载端云互发消息成功，用户数: {len(_cross_device_messages)}")
        else:
            _cross_device_messages = {}
    except Exception as e:
        logger.error(f"加载端云互发消息失败: {e}")
        _cross_device_messages = {}


def save_cross_device_messages():
    try:
        with open(CROSS_DEVICE_STORAGE_FILE, "w", encoding="utf-8") as f:
            json.dump(_cross_device_messages, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logger.error(f"保存端云互发消息失败: {e}")


def get_cross_device_messages() -> Dict[str, List[Dict]]:
    return _cross_device_messages


load_cross_device_messages()
