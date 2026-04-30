"""Token usage persistence and aggregation service."""

from __future__ import annotations

import sqlite3
import threading
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any

from topoclaw.utils.helpers import ensure_dir


def _to_int(value: Any) -> int:
    try:
        return max(0, int(value))
    except Exception:
        return 0


@dataclass
class TokenUsageRecord:
    model: str
    input_tokens: int
    output_tokens: int
    total_tokens: int
    source: str
    is_estimated: bool
    timestamp_ms: int
    local_date: str


class TokenUsageService:
    """SQLite-backed token usage recorder and aggregator."""

    def __init__(self, db_path: Path) -> None:
        self.db_path = db_path
        ensure_dir(db_path.parent)
        self._lock = threading.RLock()
        self._init_db()

    def _connect(self) -> sqlite3.Connection:
        conn = sqlite3.connect(self.db_path, timeout=10.0)
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA synchronous=NORMAL")
        return conn

    def _init_db(self) -> None:
        with self._lock:
            with self._connect() as conn:
                conn.execute(
                    """
                    CREATE TABLE IF NOT EXISTS token_usage_events (
                      id INTEGER PRIMARY KEY AUTOINCREMENT,
                      ts_ms INTEGER NOT NULL,
                      local_date TEXT NOT NULL,
                      model TEXT NOT NULL,
                      input_tokens INTEGER NOT NULL DEFAULT 0,
                      output_tokens INTEGER NOT NULL DEFAULT 0,
                      total_tokens INTEGER NOT NULL DEFAULT 0,
                      source TEXT NOT NULL DEFAULT '',
                      is_estimated INTEGER NOT NULL DEFAULT 0
                    )
                    """
                )
                conn.execute(
                    "CREATE INDEX IF NOT EXISTS idx_token_usage_events_date ON token_usage_events(local_date)"
                )
                conn.execute(
                    "CREATE INDEX IF NOT EXISTS idx_token_usage_events_model ON token_usage_events(model)"
                )
                conn.execute(
                    "CREATE INDEX IF NOT EXISTS idx_token_usage_events_ts ON token_usage_events(ts_ms)"
                )
                conn.commit()

    def record_usage(
        self,
        *,
        model: str,
        input_tokens: int,
        output_tokens: int,
        total_tokens: int | None = None,
        source: str = "unknown",
        is_estimated: bool = False,
    ) -> None:
        in_tokens = _to_int(input_tokens)
        out_tokens = _to_int(output_tokens)
        summed = in_tokens + out_tokens
        final_total = _to_int(total_tokens) or summed
        now = datetime.now()
        record = TokenUsageRecord(
            model=(model or "unknown").strip() or "unknown",
            input_tokens=in_tokens,
            output_tokens=out_tokens,
            total_tokens=final_total,
            source=(source or "unknown").strip() or "unknown",
            is_estimated=bool(is_estimated),
            timestamp_ms=int(now.timestamp() * 1000),
            local_date=now.date().isoformat(),
        )
        with self._lock:
            with self._connect() as conn:
                conn.execute(
                    """
                    INSERT INTO token_usage_events (
                      ts_ms, local_date, model, input_tokens, output_tokens, total_tokens, source, is_estimated
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    (
                        record.timestamp_ms,
                        record.local_date,
                        record.model,
                        record.input_tokens,
                        record.output_tokens,
                        record.total_tokens,
                        record.source,
                        1 if record.is_estimated else 0,
                    ),
                )
                conn.commit()

    def get_summary(self, days: int = 30) -> dict[str, Any]:
        safe_days = max(1, min(3650, _to_int(days) or 30))
        cutoff = (date.today() - timedelta(days=safe_days - 1)).isoformat()
        with self._lock:
            with self._connect() as conn:
                total_row = conn.execute(
                    """
                    SELECT
                      COALESCE(SUM(input_tokens), 0) AS input_tokens,
                      COALESCE(SUM(output_tokens), 0) AS output_tokens,
                      COALESCE(SUM(total_tokens), 0) AS total_tokens,
                      COALESCE(SUM(is_estimated), 0) AS estimated_events,
                      COUNT(*) AS events
                    FROM token_usage_events
                    """
                ).fetchone()

                by_model_rows = conn.execute(
                    """
                    SELECT
                      model,
                      COALESCE(SUM(input_tokens), 0) AS input_tokens,
                      COALESCE(SUM(output_tokens), 0) AS output_tokens,
                      COALESCE(SUM(total_tokens), 0) AS total_tokens,
                      COALESCE(SUM(is_estimated), 0) AS estimated_events,
                      COUNT(*) AS events
                    FROM token_usage_events
                    GROUP BY model
                    ORDER BY total_tokens DESC, model ASC
                    """
                ).fetchall()

                by_day_rows = conn.execute(
                    """
                    SELECT
                      local_date,
                      COALESCE(SUM(input_tokens), 0) AS input_tokens,
                      COALESCE(SUM(output_tokens), 0) AS output_tokens,
                      COALESCE(SUM(total_tokens), 0) AS total_tokens,
                      COALESCE(SUM(is_estimated), 0) AS estimated_events,
                      COUNT(*) AS events
                    FROM token_usage_events
                    WHERE local_date >= ?
                    GROUP BY local_date
                    ORDER BY local_date DESC
                    """,
                    (cutoff,),
                ).fetchall()

        return {
            "days": safe_days,
            "total": {
                "input_tokens": _to_int(total_row["input_tokens"]) if total_row else 0,
                "output_tokens": _to_int(total_row["output_tokens"]) if total_row else 0,
                "total_tokens": _to_int(total_row["total_tokens"]) if total_row else 0,
                "events": _to_int(total_row["events"]) if total_row else 0,
                "estimated_events": _to_int(total_row["estimated_events"]) if total_row else 0,
            },
            "by_model": [
                {
                    "model": str(r["model"] or "unknown"),
                    "input_tokens": _to_int(r["input_tokens"]),
                    "output_tokens": _to_int(r["output_tokens"]),
                    "total_tokens": _to_int(r["total_tokens"]),
                    "events": _to_int(r["events"]),
                    "estimated_events": _to_int(r["estimated_events"]),
                }
                for r in by_model_rows
            ],
            "by_day": [
                {
                    "date": str(r["local_date"]),
                    "input_tokens": _to_int(r["input_tokens"]),
                    "output_tokens": _to_int(r["output_tokens"]),
                    "total_tokens": _to_int(r["total_tokens"]),
                    "events": _to_int(r["events"]),
                    "estimated_events": _to_int(r["estimated_events"]),
                }
                for r in by_day_rows
            ],
            "generated_at": datetime.now().isoformat(timespec="seconds"),
        }
