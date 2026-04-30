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

"""扫码绑定：令牌与 IMEI 的临时映射（内存）"""
from datetime import datetime
from typing import Dict

from core.time_utils import TZ_UTC_PLUS_8

_binding_store: Dict[str, dict] = {}
BINDING_TTL_SECONDS = 300  # 5分钟有效


def clean_expired_bindings():
    """清理过期绑定记录"""
    now = datetime.now(TZ_UTC_PLUS_8).timestamp()
    expired = [k for k, v in _binding_store.items() if v.get("expires_at", 0) < now]
    for k in expired:
        del _binding_store[k]


def get_binding_store() -> Dict[str, dict]:
    return _binding_store
