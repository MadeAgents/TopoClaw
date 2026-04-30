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

"""Unified GUI upload route (/upload)."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, File, Form, Request, UploadFile
from loguru import logger

from topoclaw.models.gui_mobile import MobileNextActionRequest, MobileNextActionResponse
from topoclaw.service.gui_mobile_service import MobileGUIService

router = APIRouter(prefix="", tags=["gui-upload"])


def _mobile_gui_service(request: Request) -> MobileGUIService:
    return request.app.state.mobile_gui_service


@router.post("/upload")
async def upload(
    request: Request,
    uuid: str | None = Form(None),
    session_id: str | None = Form(None),
    task_id: str | None = Form(None),
    query: str = Form(""),
    feedback: int | None = Form(None),
    feedback_text: str | None = Form(None),
    user_response: str | None = Form(None),
    chat_summary: str | None = Form(None),
    package_name: str | None = Form(None),
    class_name: str | None = Form(None),
    imei: str | None = Form(None),
    pro_mode: bool = Form(False),
    batch_test_mode: bool = Form(False),
    enable_app_links: bool = Form(True),
    screen_width: int | None = Form(None),
    screen_height: int | None = Form(None),
    device_type: str | None = Form(None),
    images: list[UploadFile] | None = File(None),
    screenshot: str | None = Form(None),
    video: UploadFile | None = File(None),
    video_url: str | None = Form(None),
    audio: UploadFile | None = File(None),
    audio_url: str | None = Form(None),
    skills: str | None = Form(None),
) -> dict[str, Any]:
    """Unified upload endpoint."""
    _ = (
        chat_summary,
        class_name,
        imei,
        pro_mode,
        batch_test_mode,
        enable_app_links,
        video,
        video_url,
        audio,
        audio_url,
        skills,
    )
    # import pdb; pdb.set_trace()
    dt = (device_type or "").strip().lower()
    logger.info(
        "[GUI][upload.route] device_type={} task_id={} session_id={} uuid={} query_len={} has_images={} has_screenshot={}",
        dt or "(none)",
        task_id or "(none)",
        session_id or "(none)",
        uuid or "(none)",
        len(query or ""),
        bool(images),
        bool(screenshot),
    )

    service = _mobile_gui_service(request)
    return await service.handle_mobile_upload(
        request=request,
        uuid_value=uuid or session_id,
        task_id=task_id,
        query=query,
        feedback=feedback,
        feedback_text=feedback_text,
        user_response=user_response,
        package_name=package_name,
        images=images,
    )


@router.post("/mobile/next_action", response_model=MobileNextActionResponse)
async def mobile_next_action(payload: MobileNextActionRequest, request: Request) -> MobileNextActionResponse:
    """Unified route file for mobile next_action."""
    return await _mobile_gui_service(request).handle_mobile_next_action(request, payload)
