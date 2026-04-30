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

"""Build browser-use LLM from ``agents.gui`` + ``providers`` (aligned with GUI service startup)."""

from __future__ import annotations

import copy
import os
import re
from typing import Any

import litellm

from topoclaw.config.schema import Config, GuiAgentConfig, ProviderConfig
from topoclaw.providers.litellm_provider import LiteLLMProvider


def _is_openai_compatible_custom(provider_name: str | None, model: str) -> bool:
    """Same notion as ``commands._make_provider``: Custom OpenAI-compatible gateway."""
    model_lower = (model or "").lower()
    prefix = model_lower.split("/", 1)[0] if "/" in model_lower else ""
    is_custom_provider = bool(provider_name) and re.match(r"^custom\d*$", provider_name) is not None
    is_custom_model_prefix = bool(prefix) and re.match(r"^custom\d*$", prefix) is not None
    return is_custom_provider or is_custom_model_prefix


def resolve_gui_llm_for_browser_use(config: Config) -> Any:
    """
    Return a browser-use chat model for the GUI-configured endpoint.

    - **OpenAI-compatible custom gateway** (``CustomProvider`` in TopoClaw): ``ChatOpenAI`` with the
      same ``model`` + ``base_url`` as the main stack (LiteLLM is not used).
    - **LiteLLM-backed providers** (DashScope, OpenRouter, etc.): ``ChatLiteLLM`` with
      ``model`` passed through ``LiteLLMProvider._resolve_model`` and env setup from
      ``LiteLLMProvider.__init__``, so names like ``qwen3.5-plus`` become ``dashscope/qwen3.5-plus``.
    """
    from browser_use.llm.litellm.chat import ChatLiteLLM
    from browser_use.llm.openai.chat import ChatOpenAI

    gui = config.agents.gui or GuiAgentConfig()
    model = (os.environ.get("GUI_MODEL") or "").strip() or gui.model

    temp = copy.deepcopy(config)
    temp.agents.defaults.model = model
    temp.agents.defaults.provider = gui.provider or "auto"

    api_key = temp.get_api_key(model)
    api_base = temp.get_api_base(model)

    if (os.environ.get("GUI_AGENT_API_KEY") or "").strip():
        api_key = os.environ.get("GUI_AGENT_API_KEY", "").strip()
    if (os.environ.get("GUI_AGENT_API_BASE") or "").strip():
        api_base = os.environ.get("GUI_AGENT_API_BASE", "").strip()

    if (gui.api_key or "").strip():
        api_key = gui.api_key.strip()
    if (gui.api_base or "").strip():
        api_base = gui.api_base.strip()

    if not api_key:
        raise ValueError(
            "Browser automation needs an API key for the GUI model: configure providers.*, "
            "or agents.gui.apiKey, or GUI_AGENT_API_KEY."
        )

    p = temp.get_provider(model)
    provider_name = temp.get_provider_name(model)
    extra_headers: dict[str, str] | None = None
    if p and isinstance(p, ProviderConfig) and p.extra_headers:
        extra_headers = dict(p.extra_headers)

    max_tokens = min(config.agents.defaults.max_tokens, 8192)
    temperature = config.agents.defaults.temperature

    if _is_openai_compatible_custom(provider_name, model):
        base = api_base or "http://localhost:8000/v1"
        return ChatOpenAI(
            model=model,
            api_key=api_key,
            base_url=base,
            default_headers=extra_headers,
            temperature=temperature,
            max_completion_tokens=max_tokens,
        )

    metadata: dict[str, Any] = {}
    if gui.provider_kwargs:
        metadata.update(dict(gui.provider_kwargs))

    prev_api_base = getattr(litellm, "api_base", None)
    try:
        lp = LiteLLMProvider(
            api_key=api_key,
            api_base=api_base,
            default_model=model,
            extra_headers=extra_headers,
            provider_name=provider_name,
        )
        resolved_model = lp._resolve_model(model)
    finally:
        litellm.api_base = prev_api_base

    return ChatLiteLLM(
        model=resolved_model,
        api_key=api_key,
        api_base=api_base,
        temperature=temperature,
        max_tokens=max_tokens,
        metadata=metadata if metadata else None,
    )
