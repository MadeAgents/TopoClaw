"""Token usage metrics API routes."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, FastAPI, Query, Request

from topoclaw.service.token_usage_service import TokenUsageService

router = APIRouter(prefix="", tags=["metrics"])


def _token_usage_service(app: FastAPI) -> TokenUsageService | None:
    return getattr(app.state, "token_usage_service", None)


@router.get("/metrics/token-usage")
async def token_usage_summary(
    request: Request,
    days: int = Query(default=30, ge=1, le=3650),
) -> dict[str, Any]:
    svc = _token_usage_service(request.app)
    if svc is None:
        return {
            "ok": False,
            "error": "token_usage_disabled",
            "days": days,
            "total": {"input_tokens": 0, "output_tokens": 0, "total_tokens": 0, "events": 0, "estimated_events": 0},
            "by_model": [],
            "by_day": [],
        }
    return {"ok": True, **svc.get_summary(days=days)}

