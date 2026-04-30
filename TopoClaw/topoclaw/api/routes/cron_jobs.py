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

"""Cron jobs management routes."""

from __future__ import annotations

from datetime import datetime
from typing import Any

from fastapi import APIRouter, HTTPException, Request
from pydantic import BaseModel

from topoclaw.cron.types import CronJob, CronSchedule
from topoclaw.service.runtime import ServiceRuntime

router = APIRouter(prefix="", tags=["cron"])


class CreateCronJobRequest(BaseModel):
    """Create cron job payload."""

    message: str
    name: str | None = None
    every_seconds: int | None = None
    cron_expr: str | None = None
    tz: str | None = None
    at: str | None = None
    deliver: bool = False
    channel: str | None = None
    to: str | None = None
    delete_after_run: bool | None = None
    agent_id: str | None = None


def _runtime(request: Request) -> ServiceRuntime:
    return request.app.state.runtime


def _job_to_dict(job: CronJob) -> dict[str, Any]:
    return {
        "id": job.id,
        "name": job.name,
        "enabled": job.enabled,
        "schedule": {
            "kind": job.schedule.kind,
            "at_ms": job.schedule.at_ms,
            "every_ms": job.schedule.every_ms,
            "expr": job.schedule.expr,
            "tz": job.schedule.tz,
        },
        "payload": {
            "kind": job.payload.kind,
            "message": job.payload.message,
            "deliver": job.payload.deliver,
            "channel": job.payload.channel,
            "to": job.payload.to,
            "agent_id": job.payload.agent_id,
        },
        "state": {
            "next_run_at_ms": job.state.next_run_at_ms,
            "last_run_at_ms": job.state.last_run_at_ms,
            "last_status": job.state.last_status,
            "last_error": job.state.last_error,
        },
        "created_at_ms": job.created_at_ms,
        "updated_at_ms": job.updated_at_ms,
        "delete_after_run": job.delete_after_run,
    }


def _build_schedule(req: CreateCronJobRequest) -> tuple[CronSchedule, bool]:
    kinds = int(bool(req.every_seconds)) + int(bool(req.cron_expr)) + int(bool(req.at))
    if kinds != 1:
        raise ValueError("exactly one of every_seconds, cron_expr, at is required")

    if req.every_seconds is not None:
        if req.every_seconds <= 0:
            raise ValueError("every_seconds must be > 0")
        if req.tz:
            raise ValueError("tz can only be used with cron_expr")
        return CronSchedule(kind="every", every_ms=req.every_seconds * 1000), False

    if req.cron_expr:
        return CronSchedule(kind="cron", expr=req.cron_expr, tz=req.tz), False

    try:
        dt = datetime.fromisoformat(str(req.at))
    except ValueError as exc:
        raise ValueError("at must be ISO datetime like 2026-02-12T10:30:00") from exc
    return CronSchedule(kind="at", at_ms=int(dt.timestamp() * 1000)), True


@router.get("/cron/jobs")
async def list_cron_jobs(request: Request, include_disabled: bool = False):
    cron = _runtime(request).cron
    jobs = cron.list_jobs(include_disabled=include_disabled)
    return {"jobs": [_job_to_dict(job) for job in jobs], "count": len(jobs)}


@router.post("/cron/jobs")
async def create_cron_job(req: CreateCronJobRequest, request: Request):
    cron = _runtime(request).cron
    name = (req.name or req.message[:30] or "").strip()
    if not name:
        raise HTTPException(status_code=400, detail="message cannot be empty")
    try:
        schedule, delete_after = _build_schedule(req)
        job = cron.add_job(
            name=name,
            schedule=schedule,
            message=req.message,
            deliver=bool(req.deliver),
            channel=(req.channel or None),
            to=(req.to or None),
            delete_after_run=bool(req.delete_after_run) if req.delete_after_run is not None else delete_after,
            agent_id=(req.agent_id.strip() if isinstance(req.agent_id, str) and req.agent_id.strip() else None),
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return _job_to_dict(job)


@router.delete("/cron/jobs/{job_id}")
async def delete_cron_job(job_id: str, request: Request):
    cron = _runtime(request).cron
    removed = cron.remove_job(job_id.strip())
    if not removed:
        raise HTTPException(status_code=404, detail=f"job '{job_id}' not found")
    return {"ok": True, "job_id": job_id}
