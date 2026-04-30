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

"""Thread binding and active push stream routes."""

from __future__ import annotations

from fastapi import APIRouter, Request

from topoclaw.models.api import BindRequest
from topoclaw.service.binding_service import BindingService

router = APIRouter(prefix="", tags=["bindings"])


def _binding_service(request: Request) -> BindingService:
    return request.app.state.binding_service


@router.post("/bindings")
async def bind(req: BindRequest, request: Request):
    return await _binding_service(request).bind(req)


@router.get("/bindings/{thread_id}")
async def get_binding(thread_id: str, request: Request):
    return await _binding_service(request).get_binding(thread_id)


@router.delete("/bindings/{thread_id}")
async def delete_binding(thread_id: str, request: Request):
    return await _binding_service(request).delete_binding(thread_id)


@router.get("/events/stream")
async def events_stream(request: Request, thread_id: str):
    return await _binding_service(request).events_stream(request, thread_id)
