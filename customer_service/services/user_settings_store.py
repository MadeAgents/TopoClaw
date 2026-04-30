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

"""用户设置存储服务 - 存储用户级别的配置选项"""

import json
import logging
from typing import Dict, Any

from core.output_paths import USER_SETTINGS_FILE

logger = logging.getLogger(__name__)
_store: Dict[str, Dict[str, Any]] = {}

# 设置项默认值
DEFAULT_SETTINGS = {
    "all_agents_reply": False,  # 默认关闭：只有默认助手接收广播，其他助手只有被@才回复
    "digital_clone_enabled": False,  # 数字分身：好友消息自动回复（全局默认）
    "digital_clone_friend_overrides": {},  # 好友级覆盖：{friend_imei: bool}
}


def _load():
    """从文件加载用户设置"""
    global _store
    try:
        if USER_SETTINGS_FILE.is_file():
            with open(USER_SETTINGS_FILE, 'r', encoding='utf-8') as f:
                _store = json.load(f)
            logger.info(f"加载用户设置成功，用户数: {len(_store)}")
        else:
            _store = {}
    except Exception as e:
        logger.error(f"加载用户设置失败: {e}")
        _store = {}


def _save():
    """保存用户设置到文件"""
    try:
        with open(USER_SETTINGS_FILE, 'w', encoding='utf-8') as f:
            json.dump(_store, f, ensure_ascii=False, indent=2)
    except Exception as e:
        logger.error(f"保存用户设置失败: {e}")


def get_user_settings(imei: str) -> Dict[str, Any]:
    """获取用户的设置（带默认值）"""
    _load()
    imei = imei.strip()
    user_settings = _store.get(imei, {})
    # 合并默认值
    result = dict(DEFAULT_SETTINGS)
    result.update(user_settings)
    return result


def get_user_setting(imei: str, key: str, default=None) -> Any:
    """获取用户的单个设置项"""
    settings = get_user_settings(imei)
    return settings.get(key, default)


def update_user_settings(imei: str, settings: Dict[str, Any]) -> None:
    """更新用户的设置"""
    _load()
    imei = imei.strip()
    if imei not in _store:
        _store[imei] = {}
    _store[imei].update(settings)
    _save()
    logger.info(f"更新用户设置: imei={imei[:8]}..., settings={settings}")


def set_user_setting(imei: str, key: str, value: Any) -> None:
    """设置用户的单个设置项"""
    update_user_settings(imei, {key: value})


# 启动时加载
_load()
