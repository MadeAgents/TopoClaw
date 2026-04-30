"""LLM provider wrapper that records token usage."""

from __future__ import annotations

from typing import Any

from topoclaw.providers.base import LLMProvider, LLMResponse
from topoclaw.service.token_usage_service import TokenUsageService
from topoclaw.utils.helpers import estimate_message_tokens, estimate_prompt_tokens


class TrackedLLMProvider(LLMProvider):
    """Wrap another provider and persist usage after each call."""

    def __init__(
        self,
        base_provider: LLMProvider,
        usage_service: TokenUsageService,
        *,
        source: str = "llm",
    ) -> None:
        object.__setattr__(self, "base_provider", base_provider)
        super().__init__(api_key=base_provider.api_key, api_base=base_provider.api_base)
        self.usage_service = usage_service
        self.source = source

    @property
    def api_key(self) -> str | None:  # type: ignore[override]
        return self.base_provider.api_key

    @api_key.setter
    def api_key(self, value: str | None) -> None:  # type: ignore[override]
        self.base_provider.api_key = value

    @property
    def api_base(self) -> str | None:  # type: ignore[override]
        return self.base_provider.api_base

    @api_base.setter
    def api_base(self, value: str | None) -> None:  # type: ignore[override]
        self.base_provider.api_base = value

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
        response = await self.base_provider.chat(
            messages=messages,
            tools=tools,
            model=model,
            max_tokens=max_tokens,
            temperature=temperature,
            reasoning_effort=reasoning_effort,
            **kwargs,
        )
        self._record_usage(
            messages=messages,
            tools=tools,
            model=model,
            response=response,
        )
        return response

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
        response = await self.base_provider.chat_with_retry(
            messages=messages,
            tools=tools,
            model=model,
            max_tokens=max_tokens,
            temperature=temperature,
            reasoning_effort=reasoning_effort,
            **kwargs,
        )
        self._record_usage(
            messages=messages,
            tools=tools,
            model=model,
            response=response,
        )
        return response

    def get_default_model(self) -> str:
        return self.base_provider.get_default_model()

    def __getattr__(self, item: str) -> Any:
        return getattr(self.base_provider, item)

    def _record_usage(
        self,
        *,
        messages: list[dict[str, Any]],
        tools: list[dict[str, Any]] | None,
        model: str | None,
        response: LLMResponse,
    ) -> None:
        usage = response.usage or {}
        prompt_tokens = _safe_int(usage.get("prompt_tokens"))
        completion_tokens = _safe_int(usage.get("completion_tokens"))
        total_tokens = _safe_int(usage.get("total_tokens"))

        is_estimated = False
        if prompt_tokens <= 0 and completion_tokens <= 0 and total_tokens <= 0:
            prompt_tokens = estimate_prompt_tokens(messages, tools)
            completion_tokens = estimate_message_tokens({"role": "assistant", "content": response.content or ""})
            total_tokens = prompt_tokens + completion_tokens
            is_estimated = True

        if total_tokens <= 0:
            total_tokens = prompt_tokens + completion_tokens

        try:
            self.usage_service.record_usage(
                model=(model or self.get_default_model() or "unknown"),
                input_tokens=prompt_tokens,
                output_tokens=completion_tokens,
                total_tokens=total_tokens,
                source=self.source,
                is_estimated=is_estimated,
            )
        except Exception:
            # Usage collection must never break model calls.
            pass


def _safe_int(value: Any) -> int:
    try:
        return max(0, int(value))
    except Exception:
        return 0


def unwrap_provider(provider: LLMProvider) -> LLMProvider:
    """Unwrap tracked provider to get underlying concrete provider."""
    current = provider
    while isinstance(current, TrackedLLMProvider):
        current = current.base_provider
    return current

