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

"""
customer_service2 包内数据目录：outputs/<功能>/
启动时创建目录，并将旧版（包根目录下的 *.json）迁移到新位置。
"""
import logging
import shutil
from pathlib import Path
from typing import List, Tuple

logger = logging.getLogger(__name__)

PACKAGE_ROOT = Path(__file__).resolve().parent.parent
OUTPUT_ROOT = PACKAGE_ROOT / "outputs"

MULTI_DEVICE_DIR = OUTPUT_ROOT / "multi_device"
FRIENDS_DIR = OUTPUT_ROOT / "friends"
PROFILES_DIR = OUTPUT_ROOT / "profiles"
UNIFIED_MESSAGES_DIR = OUTPUT_ROOT / "unified_messages"
GROUPS_DIR = OUTPUT_ROOT / "groups"
SESSIONS_DIR = OUTPUT_ROOT / "sessions"
CUSTOM_ASSISTANTS_DIR = OUTPUT_ROOT / "custom_assistants"
PLAZA_DIR = OUTPUT_ROOT / "plaza"
CUSTOMER_SERVICE_DIR = OUTPUT_ROOT / "customer_service"
VERSION_DIR = OUTPUT_ROOT / "version"
SERVER_LOGS_DIR = OUTPUT_ROOT / "server_logs"
TERMINAL_LOG_FILE = SERVER_LOGS_DIR / "app_terminal.log"

CROSS_DEVICE_MESSAGES_FILE = MULTI_DEVICE_DIR / "cross_device_messages.json"
FRIENDS_STORAGE_FILE = FRIENDS_DIR / "friends_storage.json"
PROFILES_STORAGE_FILE = PROFILES_DIR / "profiles_storage.json"
UNIFIED_MESSAGES_FILE = UNIFIED_MESSAGES_DIR / "unified_messages.json"
GROUPS_STORAGE_FILE = GROUPS_DIR / "groups_storage.json"
USER_GROUPS_FILE = GROUPS_DIR / "user_groups.json"
GROUP_WORKFLOWS_FILE = GROUPS_DIR / "group_workflows.json"
SESSIONS_STORAGE_FILE = SESSIONS_DIR / "unified_sessions.json"
ACTIVE_SESSIONS_STORAGE_FILE = SESSIONS_DIR / "unified_active_sessions.json"
CUSTOM_ASSISTANTS_FILE = CUSTOM_ASSISTANTS_DIR / "custom_assistants.json"
PLAZA_ASSISTANTS_FILE = PLAZA_DIR / "plaza_assistants.json"
PLAZA_SKILLS_FILE = PLAZA_DIR / "plaza_skills.json"
USER_SETTINGS_FILE = OUTPUT_ROOT / "user_settings.json"
CONVERSATION_SUMMARIES_FILE = CUSTOMER_SERVICE_DIR / "conversation_summaries.json"
VERSION_INFO_FILE = VERSION_DIR / "version_info.json"


def ensure_all_output_dirs() -> None:
    for d in (
        MULTI_DEVICE_DIR,
        FRIENDS_DIR,
        PROFILES_DIR,
        UNIFIED_MESSAGES_DIR,
        GROUPS_DIR,
        SESSIONS_DIR,
        CUSTOM_ASSISTANTS_DIR,
        PLAZA_DIR,
        CUSTOMER_SERVICE_DIR,
        VERSION_DIR,
        SERVER_LOGS_DIR,
    ):
        d.mkdir(parents=True, exist_ok=True)


def migrate_legacy_data() -> None:
    """将此前存放在 customer_service2 根目录下的数据文件移入 outputs/。"""
    moves: List[Tuple[Path, Path]] = [
        (PACKAGE_ROOT / "cross_device_messages.json", CROSS_DEVICE_MESSAGES_FILE),
        (PACKAGE_ROOT / "friends_storage.json", FRIENDS_STORAGE_FILE),
        (PACKAGE_ROOT / "profiles_storage.json", PROFILES_STORAGE_FILE),
        (PACKAGE_ROOT / "unified_messages.json", UNIFIED_MESSAGES_FILE),
        (PACKAGE_ROOT / "groups_storage.json", GROUPS_STORAGE_FILE),
        (PACKAGE_ROOT / "user_groups.json", USER_GROUPS_FILE),
        (PACKAGE_ROOT / "group_workflows.json", GROUP_WORKFLOWS_FILE),
        (PACKAGE_ROOT / "unified_sessions.json", SESSIONS_STORAGE_FILE),
        (PACKAGE_ROOT / "custom_assistants.json", CUSTOM_ASSISTANTS_FILE),
        (PACKAGE_ROOT / "plaza_assistants.json", PLAZA_ASSISTANTS_FILE),
        (PACKAGE_ROOT / "plaza_skills.json", PLAZA_SKILLS_FILE),
        (PACKAGE_ROOT / "user_settings.json", USER_SETTINGS_FILE),
        (PACKAGE_ROOT / "conversation_summaries.json", CONVERSATION_SUMMARIES_FILE),
    ]
    for src, dst in moves:
        try:
            if src.is_file() and not dst.exists():
                dst.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(src), str(dst))
                logger.info("已迁移遗留数据: %s -> %s", src.name, dst)
        except Exception as e:
            logger.warning("迁移 %s 失败: %s", src, e)


def seed_version_info() -> None:
    """若 outputs/version 下尚无版本文件，则从包内自带的 version_info.json 复制一份。"""
    bundled = PACKAGE_ROOT / "version_info.json"
    try:
        VERSION_DIR.mkdir(parents=True, exist_ok=True)
        if not VERSION_INFO_FILE.is_file() and bundled.is_file():
            shutil.copy2(bundled, VERSION_INFO_FILE)
            logger.info("已初始化版本文件: %s", VERSION_INFO_FILE)
    except Exception as e:
        logger.warning("初始化 version_info 失败: %s", e)
