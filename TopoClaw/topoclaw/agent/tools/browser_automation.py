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

"""browser-use (PyPI) as an AgentLoop tool: one call runs until the library finishes or times out."""

from __future__ import annotations

import asyncio
import json
import os
import sys
from pathlib import Path
from typing import Any

from loguru import logger

from topoclaw.agent.browser_use_llm import resolve_gui_llm_for_browser_use
from topoclaw.agent.tools.base import Tool
from topoclaw.config.schema import Config

_RESULT_MAX_CHARS = 16_000

_execution_lock = asyncio.Lock()


def _cdp_connection_likely_failed(exc: BaseException) -> bool:
    t = f"{type(exc).__name__} {exc!s}".lower()
    return any(
        s in t
        for s in (
            "connect",
            "connection",
            "refused",
            "cdp",
            "websocket",
            "all connection attempts failed",
            "root cdp client",
        )
    )


def _format_history(history: Any) -> str:
    lines: list[str] = []
    final_text = history.final_result()
    if final_text:
        lines.append("--- extracted / final ---")
        lines.append(str(final_text))
    done = history.is_done()
    ok = history.is_successful()
    lines.append(f"--- status: done={done} success={ok} ---")
    if history.has_errors():
        err_list = [e for e in history.errors() if e]
        if err_list:
            lines.append("--- errors ---")
            lines.extend(str(e) for e in err_list[:20])
    body = "\n".join(lines) if lines else str(history)
    if len(body) > _RESULT_MAX_CHARS:
        return body[:_RESULT_MAX_CHARS] + "\n... (truncated)"
    return body


_DEFAULT_PROFILE_DIRNAME = "browser-use-profile"


def _deep_truncate_strings(obj: Any, max_len: int) -> Any:
    if isinstance(obj, str):
        if len(obj) > max_len:
            return f"{obj[:max_len]}… (truncated, len={len(obj)})"
        return obj
    if isinstance(obj, dict):
        return {k: _deep_truncate_strings(v, max_len) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_deep_truncate_strings(v, max_len) for v in obj]
    return obj


def _make_browser_use_step_logger(max_chars: int):
    """Async callback for browser_use.Agent(register_new_step_callback=...)."""

    per_field = min(max_chars, 4000)
    action_str_cap = min(max_chars, 1200)

    async def on_step(browser_state_summary: Any, agent_output: Any, step_idx: int) -> None:
        st = browser_state_summary
        out = agent_output
        lines: list[str] = [
            f"[browser_use step {step_idx}] url={st.url!s}",
            f"title={st.title!s}",
        ]
        for label, text in (
            ("eval_prev", getattr(out, "evaluation_previous_goal", None) or ""),
            ("memory", getattr(out, "memory", None) or ""),
            ("next_goal", getattr(out, "next_goal", None) or ""),
            ("thinking", getattr(out, "thinking", None) or ""),
        ):
            if text:
                chunk = text if len(text) <= per_field else f"{text[:per_field]}… (len={len(text)})"
                lines.append(f"{label}: {chunk}")
        try:
            acts: list[Any] = []
            for a in out.action:
                d = a.model_dump(mode="python", exclude_none=True)
                acts.append(_deep_truncate_strings(d, action_str_cap))
            dumped = json.dumps(acts, ensure_ascii=False, default=str)
            if len(dumped) > max_chars:
                dumped = f"{dumped[:max_chars]}… (truncated)"
            lines.append(f"actions: {dumped}")
        except Exception as exc:
            lines.append(f"actions: <serialize error: {exc}>")
        logger.info("\n".join(lines))

    return on_step


class BrowserAutomationTool(Tool):
    """Run a natural-language browser task via the optional ``browser-use`` package."""

    def __init__(self, config: Config, workspace: Path | None = None) -> None:
        self._config = config
        self._workspace = workspace if workspace is not None else config.workspace_path

    @property
    def name(self) -> str:
        return "browser_automation"

    @property
    def description(self) -> str:
        return (
            "Run a multi-step web task in a real browser using the browser-use agent. "
            "When tools.browserUse.cdpUrl is unset, the library starts a local browser for you (no manual CDP). "
            "Use for clicking, forms, logins (optional userDataDir for persistent cookies), "
            "or pages web_fetch cannot handle. Requires tools.browserUse.enabled and the browser-use package; "
            "model/API keys follow agents.gui. Prefer web_search or web_fetch for simple lookup."
        )

    @property
    def parameters(self) -> dict[str, Any]:
        return {
            "type": "object",
            "properties": {
                "task": {
                    "type": "string",
                    "description": "Clear natural-language goal for the browser agent.",
                    "minLength": 1,
                },
                "timeout_s": {
                    "type": "integer",
                    "description": "Max wall-clock seconds (default from tools.browserUse.defaultTimeoutS).",
                    "minimum": 30,
                    "maximum": 7200,
                },
            },
            "required": ["task"],
        }

    async def execute(self, task: str, timeout_s: int | None = None) -> str:
        bu = self._config.tools.browser_use
        effective_timeout = timeout_s if timeout_s is not None else bu.default_timeout_s

        prev_log = os.environ.get("BROWSER_USE_SETUP_LOGGING")
        os.environ["BROWSER_USE_SETUP_LOGGING"] = "false"
        try:
            try:
                from browser_use import Agent, Browser
            except ImportError:
                return (
                    "browser-use is not installed. Install the browser extra with "
                    "`pip install .[browser-strict]` (recommended) or `pip install .[browser-compat]`."
                )

            try:
                llm = resolve_gui_llm_for_browser_use(self._config)
            except ValueError as e:
                return f"Browser automation configuration error: {e}"

            browser_kw: dict[str, Any] = {}
            if bu.cdp_url and str(bu.cdp_url).strip():
                browser_kw["cdp_url"] = str(bu.cdp_url).strip()
            ud = (bu.user_data_dir or "").strip()
            if ud:
                browser_kw["user_data_dir"] = str(Path(ud).expanduser())
            elif not browser_kw.get("cdp_url"):
                default_ud = (self._workspace / _DEFAULT_PROFILE_DIRNAME).expanduser().resolve()
                default_ud.mkdir(parents=True, exist_ok=True)
                browser_kw["user_data_dir"] = str(default_ud)
            ex = (bu.chrome_executable_path or "").strip()
            if ex:
                browser_kw["executable_path"] = str(Path(ex).expanduser())
            browser_kw["headless"] = bool(bu.headless)

            # browser-use sets window_size ≈ primary monitor; add OS maximize when launching locally.
            if (
                bu.start_maximized
                and not browser_kw["headless"]
                and not browser_kw.get("cdp_url")
            ):
                extra_args = list(browser_kw["args"]) if browser_kw.get("args") else []
                if "--start-maximized" not in extra_args:
                    extra_args.append("--start-maximized")
                browser_kw["args"] = extra_args

            browser = Browser(**browser_kw)
            agent_kw: dict[str, Any] = {"task": task, "llm": llm, "browser": browser}
            if bu.log_steps:
                agent_kw["register_new_step_callback"] = _make_browser_use_step_logger(
                    bu.log_steps_max_chars
                )
            agent = Agent(**agent_kw)

            if bu.log_steps:
                print(
                    "[browser_automation] logSteps=true: 每步详情通过 loguru INFO 输出；"
                    "若使用 `topoclaw agent` 且看不到日志，请加 `--logs`。",
                    file=sys.stderr,
                    flush=True,
                )

            logger.info(
                "browser_automation start: timeout_s={} max_steps={} cdp={} user_data_dir={} log_steps={}",
                effective_timeout,
                bu.max_steps,
                bool(browser_kw.get("cdp_url")),
                browser_kw.get("user_data_dir", ""),
                bu.log_steps,
            )

            async with _execution_lock:
                try:
                    history = await asyncio.wait_for(
                        agent.run(max_steps=bu.max_steps),
                        timeout=float(effective_timeout),
                    )
                except asyncio.TimeoutError:
                    return (
                        f"Browser automation timed out after {effective_timeout}s. "
                        "Increase timeout_s or tools.browserUse.defaultTimeoutS, or simplify the task."
                    )
                except Exception as e:
                    logger.exception("browser_automation failed")
                    msg = f"Browser automation failed: {e!s}"
                    if browser_kw.get("cdp_url") and _cdp_connection_likely_failed(e):
                        msg += (
                            "\n\n(CDP 提示) 当前 tools.browserUse.cdpUrl 无法连上，请在本机先用远程调试方式启动 Chrome，"
                            "例如: chrome.exe --remote-debugging-port=9222 （端口与 cdpUrl 一致），"
                            "并确认配置里是 http://127.0.0.1:9222 。也可用浏览器打开该地址看是否返回 JSON。"
                            "若暂不需要接已有 Chrome，可把 config 里 cdpUrl 留空，让库自行启动浏览器。"
                        )
                    return msg

            return _format_history(history)
        finally:
            if prev_log is None:
                os.environ.pop("BROWSER_USE_SETUP_LOGGING", None)
            else:
                os.environ["BROWSER_USE_SETUP_LOGGING"] = prev_log
