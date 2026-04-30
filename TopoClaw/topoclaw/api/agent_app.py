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

"""Unified service app factory for topoclaw service mode."""

from __future__ import annotations

import asyncio
import time
from pathlib import Path
from typing import Any

from fastapi import FastAPI
from loguru import logger

from topoclaw.connection.device_registry import DeviceRegistry
from topoclaw.connection.thread_binding_registry import ThreadBindingRegistry
from topoclaw.connection.ws_registry import WSConnectionRegistry
from topoclaw.service.binding_service import BindingService
from topoclaw.service.chat_service import ChatService
from topoclaw.service.connection_app_service import ConnectionAppService
from topoclaw.service.gui_mobile_service import (
    MobileGUIService,
    dispatch_gui_execute_request,
    set_default_gui_service,
)
from topoclaw.config.schema import Config
from topoclaw.service.runtime import ServiceRuntime
from topoclaw.providers.service import ProviderService
from topoclaw.service.skills_service import SkillsService
from topoclaw.service.orchestration_service import OrchestrationService


def create_agent_service_app(
    runtime: ServiceRuntime,
    workspace: Path,
    *,
    topoclaw_config: Config | None = None,
    mobile_agent: Any | None = None,
    device_manager: Any | None = None,
    connection_registry: WSConnectionRegistry | None = None,
    device_registry: DeviceRegistry | None = None,
    thread_binding_registry: ThreadBindingRegistry | None = None,
    chat_service: ChatService | None = None,
) -> FastAPI:
    """Create FastAPI app for unified topoclaw service APIs."""
    app = FastAPI(title="topoclaw Service API", version="1.0.0")
    device_log_interval_s = 30

    @app.middleware("http")
    async def _http_access_log(request, call_next):
        started_at = time.perf_counter()
        client = request.client.host if request.client else "-"
        logger.info(
            "[http] request method={} path={} query={} client={}",
            request.method,
            request.url.path,
            request.url.query,
            client,
        )
        try:
            response = await call_next(request)
        except Exception:
            elapsed_ms = int((time.perf_counter() - started_at) * 1000)
            logger.exception(
                "[http] error method={} path={} elapsed_ms={}",
                request.method,
                request.url.path,
                elapsed_ms,
            )
            raise

        elapsed_ms = int((time.perf_counter() - started_at) * 1000)
        logger.info(
            "[http] response method={} path={} status={} elapsed_ms={}",
            request.method,
            request.url.path,
            response.status_code,
            elapsed_ms,
        )
        return response

    # Legacy runtime objects still needed by mobile routers.
    app.state.mobile_agent = mobile_agent
    app.state.device_manager = device_manager
    app.state.runtime = runtime
    app.state.topoclaw_config = topoclaw_config

    # Infrastructure layer
    app.state.connection_registry = connection_registry or WSConnectionRegistry()
    app.state.device_registry = device_registry or DeviceRegistry(workspace=workspace)
    app.state.thread_binding_registry = thread_binding_registry or ThreadBindingRegistry()

    # Business hub
    app.state.chat_service = chat_service or ChatService(
        runtime=runtime,
        workspace=workspace,
        registry=app.state.connection_registry,
        topoclaw_config=topoclaw_config,
    )

    # Application services
    app.state.binding_service = BindingService(runtime=runtime)
    app.state.provider_service = ProviderService(runtime=runtime, config=topoclaw_config)
    app.state.skills_service = SkillsService(workspace=workspace)
    app.state.token_usage_service = getattr(runtime, "token_usage_service", None)
    app.state.connection_app_service = ConnectionAppService(
        registry=app.state.connection_registry,
        device_registry=app.state.device_registry,
        thread_binding_registry=app.state.thread_binding_registry,
    )
    app.state.mobile_gui_service = MobileGUIService(
        connection_registry=app.state.connection_registry,
        device_registry=app.state.device_registry,
        release_connection=app.state.connection_app_service.release_connection,
        outbound_publish=runtime.bus.publish_outbound if runtime and getattr(runtime, "bus", None) else None,
        mobile_agent=mobile_agent,
    )
    # Orchestration service - uses registries from runtime
    node_reg = getattr(runtime, "node_registry", None) if runtime else None
    orch_reg = getattr(runtime, "orchestration_registry", None) if runtime else None
    agent_reg = getattr(runtime, "agent_registry", None) if runtime else None
    if node_reg and orch_reg and agent_reg:
        app.state.orchestration_service = OrchestrationService(
            node_registry=node_reg,
            orchestration_registry=orch_reg,
            agent_registry=agent_reg,
            config=topoclaw_config,
        )
    else:
        app.state.orchestration_service = None

    # Backward-compatible alias during transition.
    app.state.gui_service = app.state.mobile_gui_service
    set_default_gui_service(app.state.mobile_gui_service)

    async def _device_online_log_loop() -> None:
        while True:
            try:
                devices = await app.state.connection_app_service.list_online_devices()
                if devices:
                    brief = [
                        {
                            "device_id": d.get("device_id"),
                            "device_type": d.get("device_type"),
                            "conn_id": d.get("conn_id"),
                        }
                        for d in devices
                    ]
                    logger.info("[ws] online devices count={} devices={}", len(brief), brief)
                else:
                    logger.info("[ws] online devices count=0 devices=[]")
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.opt(exception=True).warning("[ws] failed to log online devices")
            await asyncio.sleep(device_log_interval_s)

    @app.on_event("startup")
    async def _start_device_online_logger() -> None:
        app.state.device_online_log_task = asyncio.create_task(_device_online_log_loop())

    @app.on_event("shutdown")
    async def _stop_device_online_logger() -> None:
        task = getattr(app.state, "device_online_log_task", None)
        if not task:
            return
        task.cancel()
        try:
            await task
        except asyncio.CancelledError:
            pass

    # Route layer
    from topoclaw.api.routes.bindings_events import router as bindings_events_router
    from topoclaw.api.routes.chat_http import router as chat_http_router
    from topoclaw.api.routes.chat_ws import router as chat_ws_router
    from topoclaw.api.routes.cron_jobs import router as cron_jobs_router
    from topoclaw.api.routes.gui_upload import router as gui_upload_router
    from topoclaw.api.routes.skills import router as skills_router
    from topoclaw.api.routes.token_usage import router as token_usage_router

    app.include_router(chat_http_router)
    app.include_router(chat_ws_router)
    app.include_router(cron_jobs_router)
    app.include_router(gui_upload_router)
    app.include_router(bindings_events_router)
    app.include_router(skills_router)
    app.include_router(token_usage_router)
    return app


__all__ = ["create_agent_service_app", "dispatch_gui_execute_request"]
