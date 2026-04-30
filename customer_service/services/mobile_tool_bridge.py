# -*- coding: utf-8 -*-
"""Bridge helper for synchronous mobile_tool invoke/result waiting."""

from __future__ import annotations

import asyncio
import uuid
from typing import Any

_pending_mobile_tool_results: dict[tuple[str, str], asyncio.Future] = {}


def new_request_id(prefix: str = "mt") -> str:
    return f"{prefix}_{uuid.uuid4().hex}"


def register_pending_mobile_tool_result(imei: str, request_id: str) -> asyncio.Future:
    key = (str(imei).strip(), str(request_id).strip())
    fut = asyncio.get_running_loop().create_future()
    _pending_mobile_tool_results[key] = fut
    return fut


def pop_pending_mobile_tool_result(imei: str, request_id: str) -> asyncio.Future | None:
    key = (str(imei).strip(), str(request_id).strip())
    return _pending_mobile_tool_results.pop(key, None)


def resolve_pending_mobile_tool_result(imei: str, request_id: str, payload: dict[str, Any]) -> bool:
    fut = pop_pending_mobile_tool_result(imei, request_id)
    if fut is None:
        return False
    if not fut.done():
        fut.set_result(payload)
    return True
