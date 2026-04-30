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

"""Data models for mobile GUI routes."""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel


class MobileActionResponse(BaseModel):
    """Unified action response model."""

    action: str
    arguments: list[Any] | str
    reason: str
    thought: str


class MobileNextActionRequest(BaseModel):
    """JSON request for mobile next_action."""

    duid: str
    image_url: str
    query: str
    task_id: str | None = None
    app_version: str | None = None
    user_response: str | None = None
    package_name: str | None = None
    class_name: str | None = None
    install_apps: list[str] | None = None


class MobileNextActionResponse(BaseModel):
    """JSON response for mobile next_action."""

    status: bool
    message: list[MobileActionResponse]
    task_id: str | None = None
