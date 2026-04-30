"""Parallel runner that dispatches multiple single-call chat tasks."""

from __future__ import annotations

import argparse
import asyncio
import statistics
from dataclasses import dataclass

from ws_chat_single_call import SingleCallResult, run_single_chat_call, to_ws_url


@dataclass
class TaskResult:
    index: int
    call: SingleCallResult


async def _run_all(
    *,
    ws_url: str,
    agent_id: str,
    message: str,
    total_calls: int,
    concurrency: int,
    timeout_sec: float,
    print_delta: bool,
) -> list[TaskResult]:
    semaphore = asyncio.Semaphore(concurrency)
    results: list[TaskResult] = []

    async def _bounded_call(i: int) -> None:
        async with semaphore:
            single_result = await run_single_chat_call(
                ws_url=ws_url,
                agent_id=agent_id,
                message=message,
                timeout_sec=timeout_sec,
                print_delta=print_delta,
            )
            result = TaskResult(index=i, call=single_result)
            results.append(result)
            status = "OK" if result.call.ok else f"FAIL: {result.call.error}"
            print(
                f"[{result.index:03d}] {status} | session_id={result.call.session_id} "
                f"| latency={result.call.latency_sec:.2f}s"
            )

    await asyncio.gather(*(_bounded_call(i) for i in range(1, total_calls + 1)))
    return sorted(results, key=lambda x: x.index)


def _print_summary(results: list[TaskResult]) -> None:
    success = [r for r in results if r.call.ok]
    failed = [r for r in results if not r.call.ok]
    latencies = [r.call.latency_sec for r in success]

    print("\n=== Summary ===")
    print(f"Total calls:   {len(results)}")
    print(f"Success:       {len(success)}")
    print(f"Failed:        {len(failed)}")
    if latencies:
        print(f"Avg latency:   {statistics.mean(latencies):.2f}s")
        print(f"P95 latency:   {statistics.quantiles(latencies, n=20)[18]:.2f}s" if len(latencies) >= 20 else "P95 latency:   N/A (<20 successful calls)")
        print(f"Max latency:   {max(latencies):.2f}s")

    if failed:
        print("\nFailed details:")
        for item in failed:
            print(
                f"- [{item.index:03d}] session_id={item.call.session_id} "
                f"error={item.call.error}"
            )


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Concurrent /ws chat caller for topoclaw.")
    parser.add_argument(
        "--base-url",
        default="ws://127.0.0.1:8000/ws",
        help="HTTP/HTTPS/WS/WSS base URL of topoclaw service.",
    )
    parser.add_argument(
        "--agent-id",
        default="topoclaw",
        help="Agent id to use. Defaults to topoclaw.",
    )
    parser.add_argument(
        "--message",
        default="请简短回复：测试通过。",
        help="Chat message sent in each call.",
    )
    parser.add_argument(
        "--calls",
        type=int,
        default=10,
        help="Total number of chat calls.",
    )
    parser.add_argument(
        "--concurrency",
        type=int,
        default=5,
        help="Number of concurrent websocket calls.",
    )
    parser.add_argument(
        "--timeout-sec",
        type=float,
        default=120.0,
        help="Per-call timeout in seconds.",
    )
    parser.add_argument(
        "--print-delta",
        action="store_true",
        help="Print streaming delta chunks.",
    )
    return parser


async def _main_async(args: argparse.Namespace) -> int:
    if args.calls <= 0:
        raise ValueError("--calls must be > 0")
    if args.concurrency <= 0:
        raise ValueError("--concurrency must be > 0")

    ws_url = to_ws_url(args.base_url)
    print(f"Target ws url: {ws_url}")
    print(f"Agent id:      {args.agent_id}")
    print(f"Total calls:   {args.calls}")
    print(f"Concurrency:   {args.concurrency}")

    results = await _run_all(
        ws_url=ws_url,
        agent_id=args.agent_id,
        message=args.message,
        total_calls=args.calls,
        concurrency=args.concurrency,
        timeout_sec=args.timeout_sec,
        print_delta=args.print_delta,
    )
    _print_summary(results)
    return 0 if all(r.call.ok for r in results) else 1


def main() -> int:
    parser = _build_parser()
    args = parser.parse_args()
    return asyncio.run(_main_async(args))


if __name__ == "__main__":
    raise SystemExit(main())
