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
人工客服服务（模块化入口）
行为与 customer_service/app.py 一致；路由按功能域拆分至 api/ 下各模块。
"""
import logging
import logging.config
import os
from pathlib import Path

from dotenv import load_dotenv

from core.output_paths import (
    TERMINAL_LOG_FILE,
    ensure_all_output_dirs,
    migrate_legacy_data,
    seed_version_info,
)

load_dotenv(dotenv_path=Path(__file__).resolve().parent / ".env", override=False)


def _logging_dict_config(log_path: Path) -> dict:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    p = str(log_path.resolve())
    log_level = os.getenv("LOG_LEVEL", "INFO").upper()
    return {
        "version": 1,
        "disable_existing_loggers": False,
        "formatters": {
            "default": {
                "format": "%(asctime)s | %(levelname)s | %(name)s | %(message)s",
                "datefmt": "%Y-%m-%d %H:%M:%S",
            },
        },
        "handlers": {
            "console": {
                "class": "logging.StreamHandler",
                "formatter": "default",
                "stream": "ext://sys.stderr",
            },
            "file": {
                "class": "logging.handlers.RotatingFileHandler",
                "formatter": "default",
                "filename": p,
                "maxBytes": 20971520,
                "backupCount": 5,
                "encoding": "utf-8",
            },
        },
        "loggers": {
            "uvicorn": {"handlers": ["console", "file"], "level": "INFO", "propagate": False},
            "uvicorn.error": {"handlers": ["console", "file"], "level": "INFO", "propagate": False},
            "uvicorn.access": {"handlers": ["console", "file"], "level": "INFO", "propagate": False},
        },
        "root": {"handlers": ["console", "file"], "level": log_level},
    }


ensure_all_output_dirs()
logging.config.dictConfig(_logging_dict_config(TERMINAL_LOG_FILE))
migrate_legacy_data()
seed_version_info()

# 与原版一致：依赖各模块 import 时的副作用完成数据加载
import services.group_service  # noqa: F401
import services.group_workflow_service  # noqa: F401
import services.unified_message_store  # noqa: F401
import storage.cross_device  # noqa: F401
import storage.friends  # noqa: F401
import storage.profiles  # noqa: F401

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from api.routes_assistants import router as assistants_router
from api.routes_binding import router as binding_router
from api.routes_cross_device import router as cross_device_router
from api.routes_conversation_summaries import router as conversation_summaries_router
from api.routes_customer import router as customer_router
from api.routes_friends import router as friends_router
from api.routes_groups import router as groups_router
from api.routes_inbox import router as inbox_router
from api.routes_plaza import router as plaza_router
from api.routes_skill_plaza import router as skill_plaza_router
from api.routes_profile import router as profile_router
from api.routes_user_settings import router as user_settings_router
from api.routes_version import router as version_router
from api.websocket_customer import register_websocket
from api.websocket_mobile_topoclaw import register_mobile_topoclaw_websocket
from api.websocket_topomobile import register_topomobile_websocket

logger = logging.getLogger(__name__)

app = FastAPI(title="Customer Service API")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=False,
    allow_methods=["*"],
    allow_headers=["*"],
)

for router in (
    binding_router,
    customer_router,
    cross_device_router,
    conversation_summaries_router,
    assistants_router,
    plaza_router,
    skill_plaza_router,
    friends_router,
    groups_router,
    inbox_router,
    profile_router,
    user_settings_router,
    version_router,
):
    app.include_router(router)

register_websocket(app)
register_topomobile_websocket(app)
register_mobile_topoclaw_websocket(app)

if __name__ == "__main__":
    import uvicorn

    ws_impl = os.getenv("UVICORN_WS_IMPL", "wsproto")
    uvicorn.run(
        app,
        host="0.0.0.0",
        port=8002,
        ws=ws_impl,
        log_config=_logging_dict_config(TERMINAL_LOG_FILE),
    )
