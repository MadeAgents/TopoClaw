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

from datetime import datetime, timezone, timedelta

TZ_UTC_PLUS_8 = timezone(timedelta(hours=8))


def get_now_isoformat() -> str:
    """获取当前时间的ISO格式字符串（东八区）"""
    return datetime.now(TZ_UTC_PLUS_8).isoformat()


def get_now_timestamp_ms() -> int:
    """获取当前时间的毫秒时间戳（东八区）"""
    return int(datetime.now(TZ_UTC_PLUS_8).timestamp() * 1000)
