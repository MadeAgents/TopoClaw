"""Single chat call client for topoclaw /ws endpoint."""

from __future__ import annotations

import argparse
import asyncio
import json
import time
import uuid
from dataclasses import asdict, dataclass
from urllib.parse import urlparse, urlunparse

import websockets


@dataclass
class SingleCallResult:
    session_id: str
    ok: bool
    latency_sec: float
    response: str
    error: str = ""


def to_ws_url(base_url: str) -> str:
    parsed = urlparse(base_url.strip())
    if parsed.scheme not in {"http", "https", "ws", "wss"}:
        raise ValueError(f"Unsupported URL scheme: {parsed.scheme or '(empty)'}")
    scheme = "wss" if parsed.scheme in {"https", "wss"} else "ws"
    path = (parsed.path or "").rstrip("/")
    if path.endswith("/ws"):
        ws_path = path
    else:
        ws_path = f"{path}/ws" if path else "/ws"
    return urlunparse((scheme, parsed.netloc, ws_path, "", "", ""))


async def run_single_chat_call(
    *,
    ws_url: str,
    message: str,
    agent_id: str = "topoclaw",
    timeout_sec: float = 120.0,
    print_delta: bool = False,
    session_id: str | None = None,
) -> SingleCallResult:
    sid = session_id or f"session-{uuid.uuid4().hex}"
    payload = {
        "type": "chat",
        "thread_id": sid,
        "session_id": sid,
        "agent_id": agent_id,
        "message": message,
    }

    start = time.perf_counter()
    final_response = ""
    error = ""

    try:
        async with websockets.connect(ws_url, max_size=20 * 1024 * 1024) as ws:
            await ws.send(json.dumps(payload, ensure_ascii=False))

            while True:
                raw = await asyncio.wait_for(ws.recv(), timeout=timeout_sec)
                data = json.loads(raw)
                msg_type = str(data.get("type") or "")
                if msg_type == "delta":
                    chunk = str(data.get("content") or "")
                    if print_delta and chunk:
                        print(chunk, end="", flush=True)
                    final_response += chunk
                    continue
                if msg_type == "done":
                    done_resp = str(data.get("response") or "")
                    if done_resp:
                        final_response = done_resp
                    break
                if msg_type == "error":
                    error = str(data.get("error") or "unknown error")
                    break
    except asyncio.TimeoutError:
        error = f"timeout after {timeout_sec:.1f}s"
    except Exception as exc:  # noqa: BLE001
        error = str(exc)

    latency = time.perf_counter() - start
    return SingleCallResult(
        session_id=sid,
        ok=(error == ""),
        latency_sec=latency,
        response=final_response,
        error=error,
    )


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run one websocket chat call.")
    parser.add_argument("--base-url", default="ws://127.0.0.1:18790/ws")
    parser.add_argument("--agent-id", default="topoclaw")
    parser.add_argument("--message", default="请简短回复：测试通过。")
    parser.add_argument("--timeout-sec", type=float, default=120.0)
    parser.add_argument("--print-delta", action="store_true")
    parser.add_argument(
        "--session-id",
        default="",
        help="Optional session id. If empty, random id is generated.",
    )
    parser.add_argument(
        "--json",
        action="store_true",
        help="Print result as JSON only.",
    )
    return parser


async def _main_async(args: argparse.Namespace) -> int:
    ws_url = to_ws_url(args.base_url)
    result = await run_single_chat_call(
        ws_url=ws_url,
        message=args.message,
        agent_id=args.agent_id,
        timeout_sec=args.timeout_sec,
        print_delta=args.print_delta,
        session_id=args.session_id.strip() or None,
    )
    payload = asdict(result)
    if args.json:
        print(json.dumps(payload, ensure_ascii=False))
    else:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    return 0 if result.ok else 1


def main() -> int:
    parser = _build_parser()
    args = parser.parse_args()
    return asyncio.run(_main_async(args))


if __name__ == "__main__":
    raise SystemExit(main())
