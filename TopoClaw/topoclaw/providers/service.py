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

"""Per-agent LLM provider patches and WebSocket ``set_llm_provider`` handling."""

from __future__ import annotations

import asyncio
import re
import threading
import uuid
from typing import TYPE_CHECKING, Any

from loguru import logger

from topoclaw.agent.loop import AgentLoop
from topoclaw.config.loader import get_config_path, save_config
from topoclaw.providers.custom_provider import CustomProvider
from topoclaw.providers.litellm_provider import LiteLLMProvider
from topoclaw.providers.tracked_provider import unwrap_provider
from topoclaw.service.runtime import ServiceRuntime

if TYPE_CHECKING:
    from topoclaw.config.schema import Config

_PATCH_KEYS = frozenset({"api_key", "api_base", "model", "default_model", "extra_headers"})

# WebSocket ``set_llm_provider``: only these may be applied; omitted keys leave existing values.
_SET_LLM_PROVIDER_KEYS: tuple[str, ...] = ("api_key", "api_base", "model")

_patch_lock = threading.RLock()


def _require_registry(runtime: ServiceRuntime):
    reg = runtime.agent_registry
    if reg is None:
        raise ValueError("ServiceRuntime.agent_registry is missing; cannot resolve agents")
    return reg


def _normalize_azure_base(provider: Any) -> None:
    from topoclaw.providers.azure_openai_provider import AzureOpenAIProvider

    resolved = unwrap_provider(provider)
    if isinstance(resolved, AzureOpenAIProvider):
        ab = resolved.api_base or ""
        if ab and not ab.endswith("/"):
            resolved.api_base = ab + "/"


def _refresh_litellm_after_patch(provider: LiteLLMProvider) -> None:
    if provider.api_key:
        provider._setup_env(provider.api_key, provider.api_base, provider.default_model)
    try:
        import litellm

        if provider.api_base:
            litellm.api_base = provider.api_base
    except Exception as exc:
        logger.debug("litellm global api_base refresh skipped: {}", exc)


def _rebuild_custom_client(provider: CustomProvider) -> None:
    from openai import AsyncOpenAI

    provider._client = AsyncOpenAI(
        api_key=provider.api_key or "no-key",
        base_url=provider.api_base or "http://localhost:8000/v1",
        default_headers={"x-session-affinity": uuid.uuid4().hex},
    )


def _provider_field_name(cfg: "Config", effective_model: str) -> str | None:
    """``providers`` attribute name for the block that matches *effective_model* (e.g. ``custom1`` vs ``custom``)."""
    forced = cfg.agents.defaults.provider
    if forced != "auto":
        return forced
    ml = (effective_model or "").lower()
    mp = ml.split("/", 1)[0] if "/" in ml else ""
    if mp and re.match(r"^custom\d*$", mp):
        return mp.replace("-", "_")
    return cfg.get_provider_name(effective_model)


def _write_provider_credentials(cfg: "Config", effective_model: str, patch: dict[str, Any]) -> None:
    from topoclaw.config.schema import ProviderConfig

    field = _provider_field_name(cfg, effective_model)
    if not field:
        logger.warning("persist: no provider field resolved for model {}", effective_model)
        return
    raw = getattr(cfg.providers, field, None)
    if raw is None:
        logger.warning("persist: providers.{} is missing", field)
        return
    if isinstance(raw, dict):
        d = dict(raw)
        if "api_key" in patch:
            d["api_key"] = "" if patch["api_key"] is None else str(patch["api_key"])
        if "api_base" in patch:
            d["api_base"] = patch["api_base"]
        setattr(cfg.providers, field, ProviderConfig.model_validate(d))
    elif isinstance(raw, ProviderConfig):
        if "api_key" in patch:
            raw.api_key = "" if patch["api_key"] is None else str(patch["api_key"])
        if "api_base" in patch:
            raw.api_base = patch["api_base"]
    else:
        logger.warning("persist: providers.{} has unexpected type {}", field, type(raw))


def _apply_model_sync(loop: AgentLoop, patch: dict[str, Any]) -> None:
    prov = unwrap_provider(loop.provider)
    if "default_model" in patch:
        dm = patch["default_model"]
        if hasattr(prov, "default_model"):
            prov.default_model = dm
        loop.model = dm
    if "model" in patch:
        m = patch["model"]
        if hasattr(prov, "default_model"):
            prov.default_model = m
        loop.model = m


def patch_agent_provider(runtime: ServiceRuntime, agent_id: str | None, patch: dict[str, Any]) -> None:
    """
    Apply in-memory credential / model fields to one agent's ``AgentLoop.provider``.

    *patch* may include any of:
    ``api_key``, ``api_base``, ``model``, ``default_model``, ``extra_headers`` (LiteLLM only).

    ``model`` and ``default_model`` both update the loop's active model and the provider's
    ``default_model`` when that attribute exists. If both are present, ``default_model`` is
    applied first, then ``model`` (so *model* wins for ``loop.model`` / provider default).

    Thread-safe for concurrent callers (single lock for the whole update).

    Note: ``CustomProvider`` embeds an ``AsyncOpenAI`` client; *api_key* / *api_base* changes
    rebuild that client here. Other provider types may still have process-wide side effects
    (e.g. LiteLLM env); multi-agent different bases in one process are inherently limited.
    """
    unknown = set(patch) - _PATCH_KEYS
    if unknown:
        raise ValueError(f"Unknown patch keys: {sorted(unknown)}")
    if not patch:
        return

    with _patch_lock:
        reg = _require_registry(runtime)
        loop = reg.resolve_loop(agent_id)
        prov = unwrap_provider(loop.provider)

        if "api_key" in patch:
            prov.api_key = patch["api_key"]
        if "api_base" in patch:
            prov.api_base = patch["api_base"]

        if "default_model" in patch or "model" in patch:
            _apply_model_sync(loop, patch)

        if "extra_headers" in patch and hasattr(prov, "extra_headers"):
            prov.extra_headers = dict(patch["extra_headers"] or {})

        _normalize_azure_base(prov)

        if isinstance(prov, CustomProvider) and ("api_key" in patch or "api_base" in patch):
            _rebuild_custom_client(prov)
        elif isinstance(prov, LiteLLMProvider) and (
            "api_key" in patch or "api_base" in patch or "default_model" in patch or "model" in patch
        ):
            _refresh_litellm_after_patch(prov)


def patch_gui_agent_provider(agent: Any, patch: dict[str, Any]) -> None:
    """Apply in-memory provider/model patch to one GUI agent instance."""
    if not agent:
        return
    unknown = set(patch) - _PATCH_KEYS
    if unknown:
        raise ValueError(f"Unknown patch keys: {sorted(unknown)}")
    if not patch:
        return

    with _patch_lock:
        prov = getattr(agent, "provider", None)
        if prov is None:
            raise ValueError("GUI agent provider is missing")
        prov = unwrap_provider(prov)

        if "api_key" in patch:
            prov.api_key = patch["api_key"]
        if "api_base" in patch:
            prov.api_base = patch["api_base"]

        if "default_model" in patch:
            dm = patch["default_model"]
            if hasattr(prov, "default_model"):
                prov.default_model = dm
            agent.model = dm
        if "model" in patch:
            model = patch["model"]
            if hasattr(prov, "default_model"):
                prov.default_model = model
            agent.model = model

        if "extra_headers" in patch and hasattr(prov, "extra_headers"):
            prov.extra_headers = dict(patch["extra_headers"] or {})

        _normalize_azure_base(prov)
        if isinstance(prov, CustomProvider) and ("api_key" in patch or "api_base" in patch):
            _rebuild_custom_client(prov)
        elif isinstance(prov, LiteLLMProvider) and (
            "api_key" in patch or "api_base" in patch or "default_model" in patch or "model" in patch
        ):
            _refresh_litellm_after_patch(prov)


def _merge_set_llm_provider_message(msg: dict[str, Any]) -> dict[str, Any]:
    """Read ``model`` / ``api_key`` / ``api_base`` from the message root only (same level as ``type``)."""
    merged: dict[str, Any] = {}
    for k in _SET_LLM_PROVIDER_KEYS:
        if k in msg:
            merged[k] = msg[k]
    return merged


def _should_omit_optional_field_from_patch(value: Any) -> bool:
    """``None`` 或空/纯空白字符串表示不修改该字段（``model`` / ``api_key`` / ``api_base``）。"""
    if value is None:
        return True
    if isinstance(value, str) and not value.strip():
        return True
    return False


def _build_optional_patch_from_set_llm_payload(payload: dict[str, Any]) -> dict[str, Any]:
    """仅把「有实质内容」的 ``model`` / ``api_key`` / ``api_base`` 写入 patch。"""
    patch: dict[str, Any] = {}
    for k in _SET_LLM_PROVIDER_KEYS:
        if k not in payload:
            continue
        if _should_omit_optional_field_from_patch(payload[k]):
            continue
        patch[k] = payload[k]
    return patch


class ProviderService:
    """Routes ``set_llm_provider`` WS frames to per-agent provider patches and persists to config."""

    def __init__(self, runtime: ServiceRuntime, config: "Config | None" = None) -> None:
        self._runtime = runtime
        self._config = config

    def _persist_patch_to_config(self, patch: dict[str, Any]) -> tuple[bool, str]:
        """Write ``agents.defaults.model`` and matching ``providers.*`` credentials, then ``save_config``."""
        if self._config is None:
            return True, ""
        cfg = self._config
        try:
            if "model" in patch:
                cfg.agents.defaults.model = (
                    str(patch["model"]) if patch["model"] is not None else ""
                )
            eff = cfg.agents.defaults.model
            if "api_key" in patch or "api_base" in patch:
                _write_provider_credentials(cfg, eff, patch)
            save_config(cfg, get_config_path())
            return True, ""
        except Exception as exc:
            return False, str(exc)

    def _persist_gui_patch_to_config(self, patch: dict[str, Any]) -> tuple[bool, str]:
        """Write ``agents.gui`` model/api fields, then ``save_config``."""
        if self._config is None:
            return True, ""
        from topoclaw.config.schema import GuiAgentConfig

        cfg = self._config
        try:
            if cfg.agents.gui is None:
                cfg.agents.gui = GuiAgentConfig()
            gui_cfg = cfg.agents.gui
            if gui_cfg is None:
                return False, "agents.gui init failed"
            if "model" in patch:
                gui_cfg.model = str(patch["model"]) if patch["model"] is not None else ""
            if "api_key" in patch:
                gui_cfg.api_key = "" if patch["api_key"] is None else str(patch["api_key"])
            if "api_base" in patch:
                gui_cfg.api_base = patch["api_base"]
            save_config(cfg, get_config_path())
            return True, ""
        except Exception as exc:
            return False, str(exc)

    def _patch_all_registered_agents(self, patch: dict[str, Any]) -> dict[str, Any]:
        unknown = set(patch) - _PATCH_KEYS
        if unknown:
            return {
                "ok": False,
                "applied": False,
                "reason": f"unknown patch keys: {sorted(unknown)}",
                "patch_keys": [],
                "updated_agent_ids": [],
                "errors": [],
                "config_saved": False,
            }
        if not patch:
            return {
                "ok": True,
                "applied": False,
                "reason": "no_llm_fields_in_request",
                "patch_keys": [],
                "updated_agent_ids": [],
                "errors": [],
                "config_saved": False,
            }

        with _patch_lock:
            config_saved = False
            if self._config is not None:
                ok, err = self._persist_patch_to_config(patch)
                if not ok:
                    return {
                        "ok": False,
                        "applied": False,
                        "reason": f"config_save_failed: {err}",
                        "patch_keys": list(patch.keys()),
                        "updated_agent_ids": [],
                        "errors": [],
                        "config_saved": False,
                    }
                config_saved = True

            reg = _require_registry(self._runtime)
            updated: list[str] = []
            errors: list[dict[str, str]] = []
            for aid in list(reg.loops.keys()):
                try:
                    patch_agent_provider(self._runtime, aid, patch)
                    updated.append(aid)
                except Exception as exc:
                    logger.opt(exception=True).warning("patch_agent_provider failed agent_id={} err={}", aid, exc)
                    errors.append({"agent_id": aid, "error": str(exc)})

            return {
                "ok": len(errors) == 0,
                "applied": True,
                "patch_keys": list(patch.keys()),
                "updated_agent_ids": updated,
                "errors": errors,
                "config_saved": config_saved,
            }

    def _patch_gui_agents(
        self,
        patch: dict[str, Any],
        *,
        mobile_agent: Any | None,
        computer_agent: Any | None = None,
    ) -> dict[str, Any]:
        unknown = set(patch) - _PATCH_KEYS
        if unknown:
            return {
                "ok": False,
                "applied": False,
                "reason": f"unknown patch keys: {sorted(unknown)}",
                "patch_keys": [],
                "updated_targets": [],
                "errors": [],
                "config_saved": False,
            }
        if not patch:
            return {
                "ok": True,
                "applied": False,
                "reason": "no_llm_fields_in_request",
                "patch_keys": [],
                "updated_targets": [],
                "errors": [],
                "config_saved": False,
            }

        with _patch_lock:
            config_saved = False
            if self._config is not None:
                ok, err = self._persist_gui_patch_to_config(patch)
                if not ok:
                    return {
                        "ok": False,
                        "applied": False,
                        "reason": f"config_save_failed: {err}",
                        "patch_keys": list(patch.keys()),
                        "updated_targets": [],
                        "errors": [],
                        "config_saved": False,
                    }
                config_saved = True

            targets: list[tuple[str, Any | None]] = [
                ("mobile", mobile_agent),
                ("computer", computer_agent),
            ]
            updated_targets: list[str] = []
            errors: list[dict[str, str]] = []
            for target_name, target_agent in targets:
                if target_agent is None:
                    continue
                try:
                    patch_gui_agent_provider(target_agent, patch)
                    updated_targets.append(target_name)
                except Exception as exc:
                    logger.opt(exception=True).warning(
                        "patch_gui_agent_provider failed target={} err={}",
                        target_name,
                        exc,
                    )
                    errors.append({"target": target_name, "error": str(exc)})

            return {
                "ok": len(errors) == 0,
                "applied": True,
                "patch_keys": list(patch.keys()),
                "updated_targets": updated_targets,
                "errors": errors,
                "config_saved": config_saved,
            }

    async def handle_set_llm_provider(self, msg: dict[str, Any]) -> dict[str, Any]:
        merged = _merge_set_llm_provider_message(msg)
        patch = _build_optional_patch_from_set_llm_payload(merged)
        if not patch:
            return {
                "type": "set_llm_provider_result",
                "ok": True,
                "applied": False,
                "reason": "no_llm_fields_in_request",
                "patch_keys": [],
                "updated_agent_ids": [],
                "errors": [],
                "config_saved": False,
            }

        result = await asyncio.to_thread(self._patch_all_registered_agents, patch)
        out: dict[str, Any] = {"type": "set_llm_provider_result", **result}
        return out

    async def handle_set_gui_provider(
        self,
        msg: dict[str, Any],
        *,
        mobile_agent: Any | None,
        computer_agent: Any | None = None,
    ) -> dict[str, Any]:
        merged = _merge_set_llm_provider_message(msg)
        patch = _build_optional_patch_from_set_llm_payload(merged)
        if not patch:
            return {
                "type": "set_gui_provider_result",
                "ok": True,
                "applied": False,
                "reason": "no_llm_fields_in_request",
                "patch_keys": [],
                "updated_targets": [],
                "errors": [],
                "config_saved": False,
            }
        result = await asyncio.to_thread(
            self._patch_gui_agents,
            patch,
            mobile_agent=mobile_agent,
            computer_agent=computer_agent,
        )
        out: dict[str, Any] = {"type": "set_gui_provider_result", **result}
        return out
