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

"""Per-model GUI backend and coordinate policy (``gui_models.json`` + built-in substring rules).

是否做相对坐标→像素映射由 ``coordinate_policy`` 决定，应按模型实际输出约定在配置里指定，
而不是在任务 prompt 里「规定」坐标系。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Literal

from loguru import logger

GUIBackend = Literal[
    "chat_multimodal_custom",
]
CoordinatePolicy = Literal["absolute", "relative"]
AgentVariant = Literal["mobile"]

_GUI_MODELS_JSON = "gui_models.json"


@dataclass
class GUIModelProfile:
    """Resolved settings for one model id."""

    backend: GUIBackend = "chat_multimodal_custom"
    coordinate_policy: CoordinatePolicy = "absolute"
    fallback_to_chat: bool = True
    """If native API fails, fall back to chat_multimodal_custom for this step."""
    model_override: str | None = None
    """Optional API model name when different from the LiteLLM routing name."""
    native_options: dict[str, Any] = field(default_factory=dict)
    """Backend-specific: anthropic_beta, computer_tool_type, display_width_px, environment, etc."""


def _default_rules() -> list[dict[str, Any]]:
    """Built-in substring rules (lowest priority after workspace file)."""
    return [
        {
            "match_substring": "seed",
            "backend": "chat_multimodal_custom",
            "coordinate_policy": "relative",
            "fallback_to_chat": True,
        },
        {
            "match_substring": "qwen",
            "backend": "chat_multimodal_custom",
            "coordinate_policy": "relative",
            "fallback_to_chat": True,
        },
    ]


def _profile_from_rule(rule: dict[str, Any]) -> GUIModelProfile:
    backend = rule.get("backend", "chat_multimodal_custom")
    if backend not in (
        "chat_multimodal_custom",
    ):
        logger.warning("Invalid backend in gui_models rule: {}, using chat_multimodal_custom", backend)
        backend = "chat_multimodal_custom"
    coord = rule.get("coordinate_policy", "absolute")
    if coord not in ("absolute", "relative"):
        logger.warning("Invalid coordinate_policy: {}, using absolute", coord)
        coord = "absolute"
    native_opts = dict(rule.get("native_options") or {})
    return GUIModelProfile(
        backend=backend,  # type: ignore[arg-type]
        coordinate_policy=coord,  # type: ignore[arg-type]
        fallback_to_chat=bool(rule.get("fallback_to_chat", True)),
        model_override=rule.get("model_override"),
        native_options=native_opts,
    )


def load_workspace_gui_rules(workspace: Path | None) -> list[dict[str, Any]]:
    """Load rules from ``<workspace>/gui_models.json`` if present."""
    if not workspace:
        return []
    path = workspace / _GUI_MODELS_JSON
    if not path.is_file():
        return []
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        rules = data.get("rules")
        if not isinstance(rules, list):
            return []
        out: list[dict[str, Any]] = []
        for r in rules:
            if isinstance(r, dict) and r.get("match_substring"):
                out.append(r)
        logger.info("Loaded {} GUI model rules from {}", len(out), path)
        return out
    except Exception as exc:
        logger.error("Failed to load {}: {}", path, exc)
        return []


def resolve_gui_model_profile(
    model: str | None,
    *,
    variant: AgentVariant,
    workspace: Path | None = None,
    explicit: GUIModelProfile | None = None,
) -> GUIModelProfile:
    """Pick profile: explicit > workspace rules (first substring match) > built-in defaults."""
    
    def _apply_variant_overrides(prof: GUIModelProfile) -> GUIModelProfile:
        if variant == "mobile":
            # 强制 mobile 始终走基于 Prompt 的自研多模态，但保留配置好的 coordinate_policy 等参数
            prof.backend = "chat_multimodal_custom"
        return prof

    if explicit is not None:
        return _apply_variant_overrides(explicit)

    m = (model or "").lower()
    for rule in load_workspace_gui_rules(workspace):
        sub = str(rule.get("match_substring", "")).lower()
        if sub and sub in m:
            return _apply_variant_overrides(_profile_from_rule(rule))

    for rule in _default_rules():
        sub = str(rule.get("match_substring", "")).lower()
        if sub and sub in m:
            return _apply_variant_overrides(_profile_from_rule(rule))

    # 未命中任何规则时的默认：统一为不主动映射（absolute），mobile 保持逻辑一致
    return _apply_variant_overrides(GUIModelProfile(coordinate_policy="absolute"))


def needs_relative_coordinate_mapping(profile: GUIModelProfile) -> bool:
    return profile.coordinate_policy == "relative"


def effective_native_model(profile: GUIModelProfile, routing_model: str | None) -> str:
    return profile.model_override or (routing_model or "")
