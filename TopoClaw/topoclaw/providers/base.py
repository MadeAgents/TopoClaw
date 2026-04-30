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

"""Base LLM provider interface."""

import asyncio
import json
import os
import re
import time
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime
from itertools import count
from typing import Any

from loguru import logger


@dataclass
class ToolCallRequest:
    """A tool call request from the LLM."""
    id: str
    name: str
    arguments: dict[str, Any]


@dataclass
class LLMResponse:
    """Response from an LLM provider."""
    content: str | None
    tool_calls: list[ToolCallRequest] = field(default_factory=list)
    finish_reason: str = "stop"
    usage: dict[str, int] = field(default_factory=dict)
    reasoning_content: str | None = None  # Kimi, DeepSeek-R1 etc.
    thinking_blocks: list[dict] | None = None  # Anthropic extended thinking
    
    @property
    def has_tool_calls(self) -> bool:
        """Check if response contains tool calls."""
        return len(self.tool_calls) > 0


_TOOL_CALL_XML_RE = re.compile(
    r"<tool_call>\s*(\{[^<]*\})\s*</tool_call>",
    re.DOTALL | re.IGNORECASE,
)
_DATA_URL_BASE64_RE = re.compile(
    r"(data:[^;,]+;base64,)([A-Za-z0-9+/=\s]{256,})",
    re.IGNORECASE,
)
_LONG_BASE64_RE = re.compile(r"(?<![A-Za-z0-9+/=])([A-Za-z0-9+/]{512,}={0,2})(?![A-Za-z0-9+/=])")
_MODEL_CALL_SEQ = count(1)


def merge_text_embedded_tool_calls(response: LLMResponse) -> LLMResponse:
    """
    When the model returns no native tool_calls but prints
    ``<tool_call>{...}</tool_call>`` in content (e.g. some Qwen variants),
    parse the first block only; strip all such blocks from content on success.
    Parse failure leaves the response unchanged.
    """
    if response.tool_calls or response.finish_reason == "error":
        return response
    raw = response.content
    if not raw or not str(raw).strip():
        return response
    m = _TOOL_CALL_XML_RE.search(raw)
    if not m:
        return response
    try:
        obj = json.loads(m.group(1).strip())
        name = obj.get("name") or obj.get("tool")
        if not isinstance(name, str) or not name.strip():
            return response
        name = name.strip()
        args = obj.get("arguments") or obj.get("args") or {}
        if isinstance(args, str):
            try:
                args = json.loads(args) if args.strip() else {}
            except json.JSONDecodeError:
                args = {}
        if not isinstance(args, dict):
            return response
        cleaned = _TOOL_CALL_XML_RE.sub("", raw).strip()
        clean_content: str | None = cleaned if cleaned else None
        return LLMResponse(
            content=clean_content,
            tool_calls=[ToolCallRequest(id="text_tc_0", name=name, arguments=args)],
            finish_reason=response.finish_reason,
            usage=response.usage,
            reasoning_content=response.reasoning_content,
            thinking_blocks=response.thinking_blocks,
        )
    except (json.JSONDecodeError, TypeError):
        return response


class LLMProvider(ABC):
    """
    Abstract base class for LLM providers.
    
    Implementations should handle the specifics of each provider's API
    while maintaining a consistent interface.
    """

    _CHAT_RETRY_DELAYS = (1, 2, 4)
    _TRANSIENT_ERROR_MARKERS = (
        "429",
        "rate limit",
        "500",
        "502",
        "503",
        "504",
        "overloaded",
        "timeout",
        "timed out",
        "connection",
        "server error",
        "temporarily unavailable",
    )
    # <= 0 means no truncation (print full model I/O in logs).
    _raw_model_log_max_chars = str(os.getenv("TOPOCLAW_MODEL_LOG_MAX_CHARS", "0")).strip()
    try:
        _MODEL_LOG_MAX_CHARS = int(_raw_model_log_max_chars or "0")
    except ValueError:
        _MODEL_LOG_MAX_CHARS = 0
    _VERBOSE_MODEL_IO = str(os.getenv("TOPOCLAW_LOG_MODEL_IO", "")).strip().lower() in {
        "1",
        "true",
        "yes",
        "on",
    }

    def __init__(self, api_key: str | None = None, api_base: str | None = None):
        self.api_key = api_key
        self.api_base = api_base

    @staticmethod
    def _sanitize_empty_content(messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
        """Replace empty text content that causes provider 400 errors.

        Empty content can appear when MCP tools return nothing. Most providers
        reject empty-string content or empty text blocks in list content.
        """
        result: list[dict[str, Any]] = []
        for msg in messages:
            content = msg.get("content")

            if isinstance(content, str) and not content:
                clean = dict(msg)
                clean["content"] = None if (msg.get("role") == "assistant" and msg.get("tool_calls")) else "(empty)"
                result.append(clean)
                continue

            if isinstance(content, list):
                filtered = [
                    item for item in content
                    if not (
                        isinstance(item, dict)
                        and item.get("type") in ("text", "input_text", "output_text")
                        and not item.get("text")
                    )
                ]
                if len(filtered) != len(content):
                    clean = dict(msg)
                    if filtered:
                        clean["content"] = filtered
                    elif msg.get("role") == "assistant" and msg.get("tool_calls"):
                        clean["content"] = None
                    else:
                        clean["content"] = "(empty)"
                    result.append(clean)
                    continue

            if isinstance(content, dict):
                clean = dict(msg)
                clean["content"] = [content]
                result.append(clean)
                continue

            result.append(msg)
        return result

    @staticmethod
    def _sanitize_request_messages(
        messages: list[dict[str, Any]],
        allowed_keys: frozenset[str],
    ) -> list[dict[str, Any]]:
        """Keep only provider-safe message keys and normalize assistant content."""
        sanitized = []
        for msg in messages:
            clean = {k: v for k, v in msg.items() if k in allowed_keys}
            if clean.get("role") == "assistant" and "content" not in clean:
                clean["content"] = None
            sanitized.append(clean)
        return sanitized

    @abstractmethod
    async def chat(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None = None,
        model: str | None = None,
        max_tokens: int = 4096,
        temperature: float = 0.7,
        reasoning_effort: str | None = None,
        **kwargs: Any,
    ) -> LLMResponse:
        """
        Send a chat completion request.
        
        Args:
            messages: List of message dicts with 'role' and 'content'.
            tools: Optional list of tool definitions.
            model: Model identifier (provider-specific).
            max_tokens: Maximum tokens in response.
            temperature: Sampling temperature.
        
        Returns:
            LLMResponse with content and/or tool calls.
        """
        pass

    @classmethod
    def _is_transient_error(cls, content: str | None) -> bool:
        err = (content or "").lower()
        return any(marker in err for marker in cls._TRANSIENT_ERROR_MARKERS)

    @classmethod
    def _truncate_for_log(cls, value: str) -> str:
        if cls._MODEL_LOG_MAX_CHARS <= 0:
            return value
        if len(value) <= cls._MODEL_LOG_MAX_CHARS:
            return value
        return value[: cls._MODEL_LOG_MAX_CHARS] + "... (truncated)"

    @staticmethod
    def _summarize_long_string(value: str) -> str:
        keep = 24
        if len(value) <= keep * 2:
            return f"<truncated len={len(value)}>"
        return f"<truncated len={len(value)} prefix={value[:keep]} suffix={value[-keep:]}>"

    @classmethod
    def _redact_base64_text(cls, text: str) -> str:
        def _replace_data_url(match: re.Match[str]) -> str:
            prefix = match.group(1)
            raw = re.sub(r"\s+", "", match.group(2))
            return prefix + cls._summarize_long_string(raw)

        redacted = _DATA_URL_BASE64_RE.sub(_replace_data_url, text)

        def _replace_long_base64(match: re.Match[str]) -> str:
            raw = match.group(1)
            return cls._summarize_long_string(raw)

        return _LONG_BASE64_RE.sub(_replace_long_base64, redacted)

    @classmethod
    def _sanitize_payload_for_log(cls, value: Any) -> Any:
        if isinstance(value, str):
            return cls._redact_base64_text(value)
        if isinstance(value, dict):
            return {k: cls._sanitize_payload_for_log(v) for k, v in value.items()}
        if isinstance(value, list):
            return [cls._sanitize_payload_for_log(v) for v in value]
        if isinstance(value, tuple):
            return tuple(cls._sanitize_payload_for_log(v) for v in value)
        return value

    @classmethod
    def _format_model_call_input(
        cls,
        *,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None,
        model: str | None,
        max_tokens: int,
        temperature: float,
        reasoning_effort: str | None,
        kwargs: dict[str, Any],
    ) -> str:
        payload = {
            "model": model,
            "max_tokens": max_tokens,
            "temperature": temperature,
            "reasoning_effort": reasoning_effort,
            "tools_count": len(tools or []),
            "messages": messages,
            "extra_kwargs": kwargs,
        }
        sanitized_payload = cls._sanitize_payload_for_log(payload)
        return cls._truncate_for_log(json.dumps(sanitized_payload, ensure_ascii=False, default=str))

    @classmethod
    def _format_model_call_output(cls, response: LLMResponse) -> str:
        payload = {
            "finish_reason": response.finish_reason,
            "content": response.content,
            "tool_calls": [
                {"id": tc.id, "name": tc.name, "arguments": tc.arguments}
                for tc in response.tool_calls
            ],
            "usage": response.usage,
            "reasoning_content": response.reasoning_content,
        }
        sanitized_payload = cls._sanitize_payload_for_log(payload)
        return cls._truncate_for_log(json.dumps(sanitized_payload, ensure_ascii=False, default=str))

    async def chat_with_retry(
        self,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None = None,
        model: str | None = None,
        max_tokens: int = 4096,
        temperature: float = 0.7,
        reasoning_effort: str | None = None,
        **kwargs: Any,
    ) -> LLMResponse:
        """Call chat() with retry on transient provider failures."""
        async def _call_once() -> LLMResponse:
            call_no = next(_MODEL_CALL_SEQ)
            input_ts = datetime.now()
            input_payload = self._format_model_call_input(
                messages=messages,
                tools=tools,
                model=model,
                max_tokens=max_tokens,
                temperature=temperature,
                reasoning_effort=reasoning_effort,
                kwargs=kwargs,
            )
            started = time.perf_counter()
            try:
                response_inner = await self.chat(
                    messages=messages,
                    tools=tools,
                    model=model,
                    max_tokens=max_tokens,
                    temperature=temperature,
                    reasoning_effort=reasoning_effort,
                    **kwargs,
                )
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                response_inner = LLMResponse(
                    content=f"Error calling LLM: {exc}",
                    finish_reason="error",
                )
            elapsed_ms = int((time.perf_counter() - started) * 1000)
            output_ts = datetime.now()
            if self._VERBOSE_MODEL_IO:
                output_payload = self._format_model_call_output(response_inner)
                logger.info(
                    "模型调用{}：【输入时间/{}】【输入内容/{}】【输出时间/{}】【输出内容/{}】【持续时间/{}ms】",
                    call_no,
                    input_ts.isoformat(timespec="milliseconds"),
                    input_payload,
                    output_ts.isoformat(timespec="milliseconds"),
                    output_payload,
                    elapsed_ms,
                )
            else:
                logger.info(
                    "模型调用{}：model={} messages={} tools={} finish_reason={} tool_calls={} 持续时间={}ms",
                    call_no,
                    model or "(default)",
                    len(messages or []),
                    len(tools or []),
                    response_inner.finish_reason,
                    len(response_inner.tool_calls or []),
                    elapsed_ms,
                )
            return response_inner

        for attempt, delay in enumerate(self._CHAT_RETRY_DELAYS, start=1):
            response = await _call_once()

            if response.finish_reason != "error":
                return merge_text_embedded_tool_calls(response)
            if not self._is_transient_error(response.content):
                return response

            err = (response.content or "").lower()
            logger.warning(
                "LLM transient error (attempt {}/{}), retrying in {}s: {}",
                attempt,
                len(self._CHAT_RETRY_DELAYS),
                delay,
                err[:120],
            )
            await asyncio.sleep(delay)

        response = await _call_once()
        return merge_text_embedded_tool_calls(response)

    @abstractmethod
    def get_default_model(self) -> str:
        """Get the default model for this provider."""
        pass
