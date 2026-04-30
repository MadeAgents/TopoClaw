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

"""CLI commands for topoclaw."""

import asyncio
import os
import select
import signal
import sys
from pathlib import Path
from typing import TYPE_CHECKING, Any

# Force UTF-8 encoding for Windows console
if sys.platform == "win32":
    if sys.stdout.encoding != "utf-8":
        os.environ["PYTHONIOENCODING"] = "utf-8"
        # Re-open stdout/stderr with UTF-8 encoding
        try:
            sys.stdout.reconfigure(encoding="utf-8", errors="replace")
            sys.stderr.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass

import typer
from prompt_toolkit import PromptSession
from prompt_toolkit.formatted_text import HTML
from prompt_toolkit.history import FileHistory
from prompt_toolkit.patch_stdout import patch_stdout
from rich.console import Console
from rich.markdown import Markdown
from rich.table import Table
from rich.text import Text

from topoclaw import __logo__, __version__
from topoclaw.config.paths import get_workspace_path
from topoclaw.config.schema import Config, TopoMobileConfig
from topoclaw.utils.helpers import ensure_dir, sync_workspace_templates

if TYPE_CHECKING:
    from topoclaw.service.token_usage_service import TokenUsageService

app = typer.Typer(
    name="topoclaw",
    help=f"{__logo__} topoclaw - Personal AI Assistant",
    no_args_is_help=True,
)

console = Console()
EXIT_COMMANDS = {"exit", "quit", "/exit", "/quit", ":q"}

# ---------------------------------------------------------------------------
# CLI input: prompt_toolkit for editing, paste, history, and display
# ---------------------------------------------------------------------------

_PROMPT_SESSION: PromptSession | None = None
_SAVED_TERM_ATTRS = None  # original termios settings, restored on exit


def _flush_pending_tty_input() -> None:
    """Drop unread keypresses typed while the model was generating output."""
    try:
        fd = sys.stdin.fileno()
        if not os.isatty(fd):
            return
    except Exception:
        return

    try:
        import termios
        termios.tcflush(fd, termios.TCIFLUSH)
        return
    except Exception:
        pass

    try:
        while True:
            ready, _, _ = select.select([fd], [], [], 0)
            if not ready:
                break
            if not os.read(fd, 4096):
                break
    except Exception:
        return


def _restore_terminal() -> None:
    """Restore terminal to its original state (echo, line buffering, etc.)."""
    if _SAVED_TERM_ATTRS is None:
        return
    try:
        import termios
        termios.tcsetattr(sys.stdin.fileno(), termios.TCSADRAIN, _SAVED_TERM_ATTRS)
    except Exception:
        pass


def _init_prompt_session() -> None:
    """Create the prompt_toolkit session with persistent file history."""
    global _PROMPT_SESSION, _SAVED_TERM_ATTRS

    # Save terminal state so we can restore it on exit
    try:
        import termios
        _SAVED_TERM_ATTRS = termios.tcgetattr(sys.stdin.fileno())
    except Exception:
        pass

    from topoclaw.config.paths import get_cli_history_path

    history_file = get_cli_history_path()
    history_file.parent.mkdir(parents=True, exist_ok=True)

    _PROMPT_SESSION = PromptSession(
        history=FileHistory(str(history_file)),
        enable_open_in_editor=False,
        multiline=False,   # Enter submits (single line mode)
    )


def _print_agent_response(response: str, render_markdown: bool) -> None:
    """Render assistant response with consistent terminal styling."""
    content = response or ""
    body = Markdown(content) if render_markdown else Text(content)
    console.print()
    console.print(f"[cyan]{__logo__} topoclaw[/cyan]")
    console.print(body)
    console.print()


def _is_exit_command(command: str) -> bool:
    """Return True when input should end interactive chat."""
    return command.lower() in EXIT_COMMANDS


async def _read_interactive_input_async() -> str:
    """Read user input using prompt_toolkit (handles paste, history, display).

    prompt_toolkit natively handles:
    - Multiline paste (bracketed paste mode)
    - History navigation (up/down arrows)
    - Clean display (no ghost characters or artifacts)
    """
    if _PROMPT_SESSION is None:
        raise RuntimeError("Call _init_prompt_session() first")
    try:
        with patch_stdout():
            return await _PROMPT_SESSION.prompt_async(
                HTML("<b fg='ansiblue'>You:</b> "),
            )
    except EOFError as exc:
        raise KeyboardInterrupt from exc



def version_callback(value: bool):
    if value:
        console.print(f"{__logo__} topoclaw v{__version__}")
        raise typer.Exit()


@app.callback()
def main(
    version: bool = typer.Option(
        None, "--version", "-v", callback=version_callback, is_eager=True
    ),
):
    """topoclaw - Personal AI Assistant."""
    pass


# ============================================================================
# Onboard / Setup
# ============================================================================


@app.command()
def onboard():
    """Initialize topoclaw configuration and workspace."""
    from topoclaw.config.loader import (
        get_config_path,
        load_config,
        merge_config_to_schema_defaults,
        save_config,
    )
    from topoclaw.config.schema import Config

    config_path = get_config_path()

    if config_path.exists():
        console.print(f"[yellow]Config already exists at {config_path}[/yellow]")
        console.print("  [bold]y[/bold] = overwrite with defaults (existing values will be lost)")
        console.print("  [bold]N[/bold] = refresh config, keeping existing values and adding new fields")
        if typer.confirm("Overwrite?"):
            config = merge_config_to_schema_defaults(Config())
            save_config(config, config_path)
            console.print(f"[green]✓[/green] Config reset to defaults at {config_path}")
        else:
            config = merge_config_to_schema_defaults(load_config(config_path))
            save_config(config, config_path)
            console.print(f"[green]✓[/green] Config refreshed at {config_path} (existing values preserved)")
    else:
        config = merge_config_to_schema_defaults(Config())
        save_config(config, config_path)
        console.print(f"[green]✓[/green] Created config at {config_path}")

    console.print(
        "[dim]Template includes: agents.defaults.maxTokens / contextWindowTokens "
        "(memoryWindow is deprecated); tools.useToolcallGuard / toolcallGuardExtraAllowedDirs / "
        "restrictToWorkspace; optional path rules in toolcall_guard_path_permissions.json "
        "(same directory as config.json).[/dim]"
    )

    # Create workspace
    workspace = get_workspace_path()

    if not workspace.exists():
        workspace.mkdir(parents=True, exist_ok=True)
        console.print(f"[green]✓[/green] Created workspace at {workspace}")

    sync_workspace_templates(workspace)

    console.print(f"\n{__logo__} topoclaw is ready!")
    console.print("\nNext steps:")
    console.print("  1. Add your API key to [cyan]~/.topoclaw/config.json[/cyan]")
    console.print("     Get one at: https://openrouter.ai/keys")
    console.print("  2. Chat: [cyan]topoclaw agent -m \"Hello!\"[/cyan]")
    console.print("\n[dim]Want Telegram/WhatsApp? See: https://github.com/HKUDS/TopoClaw#-chat-apps[/dim]")





def _make_provider(
    config: Config,
    *,
    usage_service: "TokenUsageService | None" = None,
    usage_source: str = "llm",
):
    """Create the appropriate LLM provider from config."""
    from topoclaw.providers.openai_codex_provider import OpenAICodexProvider
    from topoclaw.providers.azure_openai_provider import AzureOpenAIProvider
    from topoclaw.config.schema import ProviderConfig
    from topoclaw.providers.tracked_provider import TrackedLLMProvider

    model = config.agents.defaults.model
    provider_name = config.get_provider_name(model)
    p = config.get_provider(model)
    
    # Ensure p is a ProviderConfig instance (handle dynamic fields from extra="allow")
    if p and not isinstance(p, ProviderConfig):
        if isinstance(p, dict):
            try:
                p = ProviderConfig(**p)
            except Exception:
                p = None
        else:
            p = None

    # OpenAI Codex (OAuth)
    if provider_name == "openai_codex" or model.startswith("openai-codex/"):
        provider: Any = OpenAICodexProvider(default_model=model)
        return TrackedLLMProvider(provider, usage_service, source=usage_source) if usage_service else provider

    # Custom: direct OpenAI-compatible endpoint, bypasses LiteLLM
    # Supports multiple custom providers via provider_name/model prefix
    # (e.g. provider=custom2, or model=custom2/xxx).
    from topoclaw.providers.custom_provider import CustomProvider
    import re
    model_lower = model.lower() if model else ""
    model_prefix = model_lower.split("/", 1)[0] if "/" in model_lower else ""
    is_custom_provider = bool(provider_name) and re.match(r"^custom\d*$", provider_name) is not None
    is_custom_model_prefix = bool(model_prefix) and re.match(r"^custom\d*$", model_prefix) is not None
    if is_custom_provider or is_custom_model_prefix:
        # For custom variants (custom1, custom2, etc.), get the actual provider config
        # _match_provider already matched it, so p should have the right config
        api_key = p.api_key if (p and isinstance(p, ProviderConfig)) else "no-key"
        provider = CustomProvider(
            api_key=api_key,
            api_base=config.get_api_base(model) or "http://localhost:8000/v1",
            default_model=model,
        )
        return TrackedLLMProvider(provider, usage_service, source=usage_source) if usage_service else provider

    # Azure OpenAI: direct Azure OpenAI endpoint with deployment name
    if provider_name == "azure_openai":
        if not p or not isinstance(p, ProviderConfig) or not p.api_key or not p.api_base:
            console.print("[red]Error: Azure OpenAI requires api_key and api_base.[/red]")
            console.print("Set them in ~/.topoclaw/config.json under providers.azure_openai section")
            console.print("Use the model field to specify the deployment name.")
            raise typer.Exit(1)
        
        provider = AzureOpenAIProvider(
            api_key=p.api_key,
            api_base=p.api_base,
            default_model=model,
        )
        return TrackedLLMProvider(provider, usage_service, source=usage_source) if usage_service else provider

    from topoclaw.providers.litellm_provider import LiteLLMProvider
    from topoclaw.providers.registry import find_by_name
    spec = find_by_name(provider_name)
    # Allow empty string or any non-None api_key value (including "empty" for custom deployments)
    has_api_key = p and isinstance(p, ProviderConfig) and (p.api_key is not None)
    if not model.startswith("bedrock/") and not has_api_key and not (spec and spec.is_oauth):
        console.print("[red]Error: No API key configured.[/red]")
        console.print("Set one in ~/.topoclaw/config.json under providers section")
        raise typer.Exit(1)

    provider = LiteLLMProvider(
        api_key=p.api_key if (p and isinstance(p, ProviderConfig)) else None,
        api_base=config.get_api_base(model),
        default_model=model,
        extra_headers=p.extra_headers if (p and isinstance(p, ProviderConfig)) else None,
        provider_name=provider_name,
    )
    return TrackedLLMProvider(provider, usage_service, source=usage_source) if usage_service else provider


def _load_runtime_config(config: str | None = None, workspace: str | None = None) -> Config:
    """Load config and optionally override the active workspace."""
    from topoclaw.config.loader import load_config, normalize_config_path, set_config_path

    config_path = None
    if config:
        try:
            config_path = normalize_config_path(config)
        except ValueError as e:
            console.print(f"[red]Error: Invalid config path: {e}[/red]")
            raise typer.Exit(1) from e
        if not config_path.exists():
            console.print(f"[red]Error: Config file not found: {config_path}[/red]")
            raise typer.Exit(1)
        set_config_path(config_path)
        console.print(f"[dim]Using config: {config_path}[/dim]")

    loaded = load_config(config_path)
    if workspace:
        loaded.agents.defaults.workspace = workspace
    return loaded


def _load_env_files() -> None:
    """Best-effort load .env files for service/gateway startup."""
    try:
        from dotenv import load_dotenv
    except Exception:
        return

    cwd = Path.cwd()
    module_root = Path(__file__).resolve().parents[1]  # .../topoclaw
    for env_path in (cwd / ".env", module_root / ".env"):
        if env_path.exists():
            load_dotenv(env_path, override=False)


def _get_env_str(name: str) -> str | None:
    """Read env var and normalize surrounding quotes/whitespace."""
    raw = os.getenv(name)
    if raw is None:
        return None
    value = raw.strip()
    if not value:
        return None
    if (value.startswith('"') and value.endswith('"')) or (value.startswith("'") and value.endswith("'")):
        value = value[1:-1].strip()
    return value or None


def _print_deprecated_memory_window_notice(config: Config) -> None:
    """Warn when running with old memoryWindow-only config."""
    if config.agents.defaults.should_warn_deprecated_memory_window:
        console.print(
            "[yellow]Hint:[/yellow] Detected deprecated `memoryWindow` without "
            "`contextWindowTokens`. `memoryWindow` is ignored; run "
            "[cyan]topoclaw onboard[/cyan] to refresh your config template."
        )


# ============================================================================
# Gateway / Server
# ============================================================================


@app.command()
def gateway(
    workspace: str | None = typer.Option(None, "--workspace", "-w", help="Workspace directory"),
    verbose: bool = typer.Option(False, "--verbose", "-v", help="Verbose output"),
    config: str | None = typer.Option(None, "--config", "-c", help="Path to config file"),
):
    """Start the topoclaw gateway."""
    from topoclaw.agent.agent_loop_factory import toolcall_guard_from_config
    from topoclaw.agent.loop import AgentLoop
    from topoclaw.bus.queue import MessageBus
    from topoclaw.channels.manager import ChannelManager
    from topoclaw.config.paths import get_cron_dir
    from topoclaw.cron.service import CronService
    from topoclaw.cron.types import CronJob
    from topoclaw.heartbeat.service import HeartbeatService
    from topoclaw.session.manager import SessionManager
    from topoclaw.config.paths import get_runtime_subdir
    from topoclaw.service.token_usage_service import TokenUsageService

    if verbose:
        import logging
        logging.basicConfig(level=logging.DEBUG)

    config = _load_runtime_config(config, workspace)
    _print_deprecated_memory_window_notice(config)

    console.print(f"{__logo__} Starting topoclaw gateway...")
    sync_workspace_templates(config.workspace_path)
    bus = MessageBus()
    usage_service = TokenUsageService(get_runtime_subdir("metrics") / "token_usage.db")
    provider = _make_provider(config, usage_service=usage_service, usage_source="gateway")
    session_manager = SessionManager(config.workspace_path)

    # Create cron service first (callback set after agent creation)
    cron_store_path = get_cron_dir() / "jobs.json"
    cron = CronService(cron_store_path)

    # Create agent with cron service
    agent = AgentLoop(
        bus=bus,
        provider=provider,
        workspace=config.workspace_path,
        model=config.agents.defaults.model,
        temperature=config.agents.defaults.temperature,
        max_tokens=config.agents.defaults.max_tokens,
        max_iterations=config.agents.defaults.max_tool_iterations,
        reasoning_effort=config.agents.defaults.reasoning_effort,
        provider_kwargs=config.agents.defaults.provider_kwargs,
        context_window_tokens=config.agents.defaults.context_window_tokens,
        brave_api_key=config.tools.web.search.api_key or None,
        web_proxy=config.tools.web.proxy or None,
        exec_config=config.tools.exec,
        cron_service=cron,
        restrict_to_workspace=config.tools.restrict_to_workspace,
        session_manager=session_manager,
        mcp_servers=config.tools.mcp_servers,
        channels_config=config.channels,
        app_config=config,
        toolcall_guard=toolcall_guard_from_config(config, config.workspace_path),
    )

    # Set cron callback (needs agent)
    async def on_cron_job(job: CronJob) -> str | None:
        """Execute a cron job through the agent."""
        from topoclaw.agent.session_keys import normalize_agent_id, websocket_session_key
        from topoclaw.agent.tools.cron import CronTool
        from topoclaw.agent.tools.message import MessageTool
        reminder_note = (
            "[Scheduled Task] Timer finished.\n\n"
            f"Task '{job.name}' has been triggered.\n"
            f"Scheduled instruction: {job.payload.message}"
        )

        def _resolve_cron_target() -> tuple[str, str, str]:
            explicit_channel = str(job.payload.channel or "").strip()
            explicit_chat_id = str(job.payload.to or "").strip()
            if explicit_channel and explicit_chat_id:
                if explicit_channel == "websocket":
                    return (
                        explicit_channel,
                        explicit_chat_id,
                        websocket_session_key(job.payload.agent_id, explicit_chat_id),
                    )
                if explicit_channel == "topomobile":
                    return (
                        "topomobile",
                        explicit_chat_id,
                        websocket_session_key(job.payload.agent_id, explicit_chat_id),
                    )
                return explicit_channel, explicit_chat_id, f"{explicit_channel}:{explicit_chat_id}"

            wanted_agent = normalize_agent_id(job.payload.agent_id)
            for item in session_manager.list_sessions():
                key = str(item.get("key") or "").strip()
                if not key or key.startswith("cron:") or key == "heartbeat":
                    continue
                if key.startswith("websocket:"):
                    parts = key.split(":", 2)
                    if len(parts) != 3:
                        continue
                    _, agent_id, thread_id = parts
                    if thread_id and normalize_agent_id(agent_id) == wanted_agent:
                        return "websocket", thread_id, key
                    continue
                if key.startswith("api:"):
                    thread_id = key.split(":", 1)[1].strip()
                    if thread_id:
                        return "api", thread_id, key
            return "cli", "direct", f"cron:{job.id}"

        target_channel, target_chat_id, target_session_key = _resolve_cron_target()

        # Prevent the agent from scheduling new cron jobs during execution
        cron_tool = agent.tools.get("cron")
        cron_token = None
        if isinstance(cron_tool, CronTool):
            cron_token = cron_tool.set_cron_context(True)
        try:
            response = await agent.process_direct(
                reminder_note,
                session_key=target_session_key,
                channel=target_channel,
                chat_id=target_chat_id,
            )
        finally:
            if isinstance(cron_tool, CronTool) and cron_token is not None:
                cron_tool.reset_cron_context(cron_token)

        message_tool = agent.tools.get("message")
        if (
            (not response or not str(response).strip())
            and isinstance(message_tool, MessageTool)
            and bool(getattr(message_tool, "_sent_in_turn", False))
        ):
            fallback = getattr(message_tool, "_last_sent_content", None)
            if isinstance(fallback, str) and fallback.strip():
                response = fallback
        if isinstance(message_tool, MessageTool) and message_tool._sent_in_turn:
            return response

        if job.payload.deliver and response:
            from topoclaw.bus.events import OutboundMessage
            await bus.publish_outbound(OutboundMessage(
                channel=target_channel,
                chat_id=target_chat_id,
                content=response
            ))
        return response
    cron.on_job = on_cron_job

    
    # Create channel manager with API managers
    channels = ChannelManager(
        config, 
        bus
    )

    def _pick_heartbeat_target() -> tuple[str, str]:
        """Pick a routable channel/chat target for heartbeat-triggered messages."""
        enabled = set(channels.enabled_channels)
        # Prefer the most recently updated non-internal session on an enabled channel.
        for item in session_manager.list_sessions():
            key = item.get("key") or ""
            if ":" not in key:
                continue
            channel, chat_id = key.split(":", 1)
            if channel in {"cli", "system"}:
                continue
            if channel in enabled and chat_id:
                return channel, chat_id
        # Fallback keeps prior behavior but remains explicit.
        return "cli", "direct"

    # Create heartbeat service
    async def on_heartbeat_execute(tasks: str) -> str:
        """Phase 2: execute heartbeat tasks through the full agent loop."""
        channel, chat_id = _pick_heartbeat_target()

        async def _silent(*_args, **_kwargs):
            pass

        return await agent.process_direct(
            tasks,
            session_key="heartbeat",
            channel=channel,
            chat_id=chat_id,
            on_progress=_silent,
        )

    async def on_heartbeat_notify(response: str) -> None:
        """Deliver a heartbeat response to the user's channel."""
        from topoclaw.bus.events import OutboundMessage
        channel, chat_id = _pick_heartbeat_target()
        if channel == "cli":
            return  # No external channel available to deliver to
        await bus.publish_outbound(OutboundMessage(channel=channel, chat_id=chat_id, content=response))

    hb_cfg = config.gateway.heartbeat
    heartbeat = HeartbeatService(
        workspace=config.workspace_path,
        provider=provider,
        model=agent.model,
        on_execute=on_heartbeat_execute,
        on_notify=on_heartbeat_notify,
        interval_s=hb_cfg.interval_s,
        enabled=hb_cfg.enabled,
    )

    if channels.enabled_channels:
        console.print(f"[green]✓[/green] Channels enabled: {', '.join(channels.enabled_channels)}")
    else:
        console.print("[yellow]Warning: No channels enabled[/yellow]")

    cron_status = cron.status()
    if cron_status["jobs"] > 0:
        console.print(f"[green]✓[/green] Cron: {cron_status['jobs']} scheduled jobs")

    console.print(f"[green]✓[/green] Heartbeat: every {hb_cfg.interval_s}s")

    async def run():
        try:
            await cron.start()
            await heartbeat.start()

            await asyncio.gather(
                agent.run(),
                channels.start_all(),
            )
        except KeyboardInterrupt:
            console.print("\nShutting down...")
        finally:
            await agent.close_mcp()
            heartbeat.stop()
            cron.stop()
            agent.stop()
            await channels.stop_all()

    asyncio.run(run())


def _resolve_service_listen_port(cli_port: int | None, config: Config) -> int:
    """CLI --port 优先；否则读 TOPOCLAW_SERVICE_PORT / TOPOCLAW_PORT；最后用 config.gateway.port。"""
    if cli_port is not None:
        return cli_port
    for key in ("TOPOCLAW_SERVICE_PORT", "TOPOCLAW_PORT"):
        raw = (os.getenv(key) or "").strip()
        if not raw:
            continue
        try:
            return int(raw)
        except ValueError:
            console.print(f"[yellow]忽略无效环境变量 {key}={raw!r}（需要整数端口）[/yellow]")
    return config.gateway.port


def _resolve_topomobile_node_id_for_service_port(port: int, tm_cfg: TopoMobileConfig) -> str:
    """当 HTTP 监听 1879 时，若未在配置中显式指定 nodeId，TopoMobile 使用 001（与 B 实例约定一致）。"""
    raw = (tm_cfg.node_id or "").strip() or "000"
    if port == 1879 and "node_id" not in tm_cfg.model_fields_set:
        return "001"
    return raw


@app.command()
def service(
    port: int | None = typer.Option(
        None,
        "--port",
        "-p",
        help="HTTP 监听端口；省略时依次尝试 TOPOCLAW_SERVICE_PORT、TOPOCLAW_PORT，再用配置 gateway.port（默认 18790）",
    ),
    host: str = typer.Option("0.0.0.0", "--host", help="Service API host"),
    workspace: str | None = typer.Option(None, "--workspace", "-w", help="Workspace directory"),
    verbose: bool = typer.Option(False, "--verbose", "-v", help="Verbose output"),
    config: str | None = typer.Option(None, "--config", "-c", help="Path to config file"),
    skip_weixin_login: bool = typer.Option(
        False,
        "--skip-weixin-login",
        help="启用微信通道但未配置 botToken 时，不执行交互式扫码（适合无 TTY/自动化；未填 token 时微信无法连接）。",
    ),
):
    """Start unified service: /chat APIs + gateway runtime."""
    import uvicorn

    from topoclaw.agent.loop import AgentLoop
    from topoclaw.agent.gui.mobile import MobileGUIAgent
    from topoclaw.agent.agent_loop_factory import build_service_agent_loop
    from topoclaw.agent.memory import MemoryStore
    from topoclaw.api.agent_app import create_agent_service_app
    from topoclaw.api.device_manager import DeviceManager
    from topoclaw.bus.events import OutboundMessage
    from topoclaw.bus.queue import MessageBus
    from topoclaw.channels.manager import ChannelManager
    from topoclaw.connection.device_registry import DeviceRegistry
    from topoclaw.connection.thread_binding_registry import ThreadBindingRegistry
    from topoclaw.connection.ws_registry import WSConnectionRegistry
    from topoclaw.config.paths import get_cron_dir, get_runtime_subdir
    from topoclaw.cron.service import CronService
    from topoclaw.cron.types import CronJob
    from topoclaw.heartbeat.service import HeartbeatService
    from topoclaw.service.chat_service import ChatService
    from topoclaw.service.connection_app_service import ConnectionAppService
    from topoclaw.service.runtime import EventHub, ServiceRuntime, ThreadBindingStore
    from topoclaw.service.token_usage_service import TokenUsageService
    from topoclaw.session.manager import SessionManager

    if verbose:
        import logging
        logging.basicConfig(level=logging.DEBUG)

    # Ensure GUI_* env vars in .env are visible at startup.
    _load_env_files()
    config = _load_runtime_config(config, workspace)
    _print_deprecated_memory_window_notice(config)
    port = _resolve_service_listen_port(port, config)
    config.gateway.port = port

    wx_cfg = config.channels.weixin
    if wx_cfg.enabled and not (wx_cfg.bot_token or "").strip():
        if skip_weixin_login:
            console.print(
                "[yellow]Weixin 已启用但 botToken 为空；已按 --skip-weixin-login 跳过扫码。"
                " 请在配置中填写 channels.weixin.botToken，或去掉该选项以在启动时交互登录。[/yellow]"
            )
        else:
            import httpx

            from topoclaw.channels.weixin import run_weixin_login_interactive
            from topoclaw.config.loader import get_config_path, save_config

            try:
                asyncio.run(run_weixin_login_interactive(config, console=console))
                save_config(config)
                console.print(f"[green]微信登录信息已写入 {get_config_path()}[/green]")
            except (TimeoutError, RuntimeError, httpx.HTTPError) as e:
                console.print(f"[red]微信扫码登录失败: {e}[/red]")
                raise typer.Exit(1) from e

    console.print(f"{__logo__} Starting topoclaw service on {host}:{port}...")
    sync_workspace_templates(config.workspace_path)

    bus = MessageBus()
    token_usage_service = TokenUsageService(get_runtime_subdir("metrics") / "token_usage.db")
    provider = _make_provider(config, usage_service=token_usage_service, usage_source="service")
    session_manager = SessionManager(config.workspace_path)
    cron_store_path = get_cron_dir() / "jobs.json"
    cron = CronService(cron_store_path)

    # Initialize GUI components for HTTP API endpoints.
    device_manager = DeviceManager()
    from topoclaw.config.schema import GuiAgentConfig

    # Use config file for GUI model, fallback to schema default
    gui_config = config.agents.gui or GuiAgentConfig()
    gui_model = _get_env_str("GUI_MODEL") or gui_config.model
    gui_api_key = _get_env_str("GUI_AGENT_API_KEY")
    gui_api_base = _get_env_str("GUI_AGENT_API_BASE")
    
    # Unified provider configuration: use providers section like AgentLoop
    # Priority: config.providers.{provider} > environment variables
    try:
        import copy
        gui_temp_config = copy.deepcopy(config)
        gui_temp_config.agents.defaults.model = gui_model
        gui_temp_config.agents.defaults.provider = gui_config.provider or "auto"
        
        gui_provider = _make_provider(
            gui_temp_config,
            usage_service=token_usage_service,
            usage_source="gui_mobile",
        )
        
        # Allow override via env vars for backward compatibility
        if os.getenv("GUI_AGENT_API_KEY"):
            gui_provider.api_key = os.getenv("GUI_AGENT_API_KEY")
        if os.getenv("GUI_AGENT_API_BASE"):
            gui_provider.api_base = os.getenv("GUI_AGENT_API_BASE")
            
        mobile_agent = MobileGUIAgent(
            provider=gui_provider,
            workspace=config.workspace_path,
            model=gui_model,
            memory_store=MemoryStore(config.workspace_path),
            session_manager=session_manager,
            provider_kwargs=gui_config.provider_kwargs,
            models_needing_mapping=gui_config.models_needing_mapping,
        )
    except (ValueError, Exception) as e:
        mobile_agent = None
        console.print(f"[yellow]Warning: Failed to initialize GUI Agents: {e}[/yellow]")
        console.print(
            "[dim]Tip: Configure GUI provider in ~/.topoclaw/config.json under providers section, "
            "or set GUI_AGENT_API_KEY environment variable.[/dim]"
        )

    if mobile_agent:
        console.print("[green]✓[/green] MobileGUIAgent initialized")

    agent = build_service_agent_loop(
        config=config,
        bus=bus,
        provider=provider,
        workspace=config.workspace_path,
        cron_service=cron,
        session_manager=session_manager,
    )

    from topoclaw.agent.agent_registry import AgentRegistry

    _agent_loops: dict[str, AgentLoop] = {"default": agent}
    _agent_workspaces: dict[str, Path] = {"default": config.workspace_path}
    for entry in config.agents.named_agents:
        aid = (entry.id or "").strip()
        if not aid or aid.lower() == "default":
            console.print(f"[yellow]Skipping named agent with invalid id: {entry.id!r}[/yellow]")
            continue
        if aid in _agent_loops:
            console.print(f"[yellow]Duplicate named agent id '{aid}', skipping[/yellow]")
            continue
        wpath = Path(entry.workspace).expanduser().resolve()
        ensure_dir(wpath)
        sync_workspace_templates(wpath)
        extra = build_service_agent_loop(
            config=config,
            bus=bus,
            provider=provider,
            workspace=wpath,
            cron_service=cron,
            session_manager=SessionManager(wpath),
            skill_exclude=entry.skills_exclude if entry.skills_exclude else None,
            skill_include=entry.skills_include if entry.skills_include else None,
        )
        _agent_loops[aid] = extra
        _agent_workspaces[aid] = wpath
        console.print(f"[green]✓[/green] Named agent [bold]{aid}[/bold] workspace={wpath}")

    agent_registry = AgentRegistry(
        default_id="default",
        loops=_agent_loops,
        workspaces=_agent_workspaces,
    )

    # Set global agent registry for OrchestrateAgentsTool
    from topoclaw.agent.agent_registry import set_agent_registry
    set_agent_registry(agent_registry)

    # Register orchestrate_agents tool for all existing agents
    try:
        from topoclaw.agent.agent_registry import get_agent_registry
        from topoclaw.agent.tools.agent_manage import OrchestrateAgentsTool
        for aid, loop in _agent_loops.items():
            loop.tools.register(OrchestrateAgentsTool(
                agent_registry=get_agent_registry(),
                config=config,
                bus=bus,
                provider=provider,
                cron_service=cron,
            ))
        console.print(f"[green]✓[/green] OrchestrateAgentsTool registered for {len(_agent_loops)} agents")
    except Exception as e:
        console.print(f"[yellow]Warning:[/yellow] Failed to register OrchestrateAgentsTool: {e}")

    # Load orchestrations from disk and initialize node registry
    from topoclaw.config.paths import get_orchestration_dir
    from topoclaw.orchestrator import NodeRegistry, get_registry, OrchestrationRegistry, get_orchestration_registry

    node_registry = get_registry()
    orchestration_registry = get_orchestration_registry()

    # Load existing DAG files from orchestration directory (sync)
    orchestration_dir = get_orchestration_dir()
    console.print(f"[dim]Loading orchestrations from {orchestration_dir}[/dim]")
    loaded_dags = orchestration_registry.load_from_dir(orchestration_dir)
    for dag in loaded_dags:
        console.print(f"[green]✓[/green] Loaded orchestration [bold]{dag.name}[/bold]")

    # Register all loaded agents as orchestration nodes
    for aid, loop in _agent_loops.items():
        from topoclaw.orchestrator.base import NodeAdapter
        adapter = NodeAdapter(
            agent_loop=loop,
            name=aid,
            description=f"Agent: {aid}",
            default_session_key=f"orchestrator:{aid}",
        )
        try:
            node_registry.register(aid, adapter)
        except ValueError:
            pass  # Already registered
        console.print(f"[green]✓[/green] Registered agent [bold]{aid}[/bold] as orchestration node")

    console.print(f"[dim]Orchestration system ready: {len(orchestration_registry)} DAGs, {len(node_registry)} nodes[/dim]")
    console.print("Initial NodeRegistry nodes after startup: {}", node_registry.list_nodes())
    console.print("Initial OrchestrationRegistry DAGs after startup: {}", orchestration_registry.list_orchestrations())

    channels = ChannelManager(config, bus)
    bindings = ThreadBindingStore()
    events = EventHub()
    runtime: ServiceRuntime | None = None
    connection_registry = WSConnectionRegistry()
    device_registry = DeviceRegistry(workspace=config.workspace_path)
    thread_binding_registry = ThreadBindingRegistry()
    chat_service_ref: ChatService | None = None
    connection_app_service: ConnectionAppService | None = None

    async def on_cron_job(job: CronJob) -> str | None:
        """Execute cron job through agent loop."""
        from topoclaw.agent.session_keys import normalize_agent_id, websocket_session_key
        from topoclaw.agent.tools.cron import CronTool
        from topoclaw.agent.tools.message import MessageTool

        reminder_note = (
            "[Scheduled Task] Timer finished.\n\n"
            f"Task '{job.name}' has been triggered.\n"
            f"Scheduled instruction: {job.payload.message}"
        )

        def _resolve_cron_target() -> tuple[str, str, str]:
            explicit_channel = str(job.payload.channel or "").strip()
            explicit_chat_id = str(job.payload.to or "").strip()
            if explicit_channel and explicit_chat_id:
                if explicit_channel == "websocket":
                    return (
                        explicit_channel,
                        explicit_chat_id,
                        websocket_session_key(job.payload.agent_id, explicit_chat_id),
                    )
                if explicit_channel == "topomobile":
                    return (
                        "topomobile",
                        explicit_chat_id,
                        websocket_session_key(job.payload.agent_id, explicit_chat_id),
                    )
                return explicit_channel, explicit_chat_id, f"{explicit_channel}:{explicit_chat_id}"

            wanted_agent = normalize_agent_id(job.payload.agent_id)
            for item in session_manager.list_sessions():
                key = str(item.get("key") or "").strip()
                if not key or key.startswith("cron:") or key == "heartbeat":
                    continue
                if key.startswith("websocket:"):
                    parts = key.split(":", 2)
                    if len(parts) != 3:
                        continue
                    _, agent_id, thread_id = parts
                    if thread_id and normalize_agent_id(agent_id) == wanted_agent:
                        return "websocket", thread_id, key
                    continue
                if key.startswith("api:"):
                    thread_id = key.split(":", 1)[1].strip()
                    if thread_id:
                        return "api", thread_id, key
            return "cli", "direct", f"cron:{job.id}"

        cron_loop, _, _ = await agent_registry.materialize(job.payload.agent_id)
        cron_tool = cron_loop.tools.get("cron")
        cron_token = None
        target_channel, target_chat_id, target_session_key = _resolve_cron_target()
        should_push_ws = target_channel in {"api", "websocket"} and bool(target_chat_id)
        should_push_topomobile = target_channel == "topomobile" and bool(target_chat_id)

        def _infer_imei_from_thread_id(thread_id: str) -> str:
            tid = (thread_id or "").strip()
            if not tid:
                return ""
            prefix = tid.split("_", 1)[0].strip()
            if len(prefix) >= 8:
                return prefix
            return ""

        async def _fallback_topomobile_push(
            content: str,
            *,
            progress: bool,
            tool_hint: bool = False,
        ) -> None:
            if not target_chat_id:
                return
            metadata = {
                "request_id": f"cron-{job.id}" if progress else f"cron-{job.id}-done",
                "source": "cron",
            }
            imei = _infer_imei_from_thread_id(target_chat_id)
            if imei:
                metadata["imei"] = imei
            if progress:
                metadata["_progress"] = True
                metadata["_tool_hint"] = tool_hint
            await bus.publish_outbound(
                OutboundMessage(
                    channel="topomobile",
                    chat_id=target_chat_id,
                    content=content,
                    metadata=metadata,
                )
            )

        async def _cron_progress(
            content: str,
            *,
            tool_hint: bool = False,
            tool_guard: bool = False,
            reasoning: bool = False,
        ) -> str | None:
            from topoclaw.models.constant import TOOL_GUARD_CONFIRM_TYPE_TIMEOUT

            if tool_guard:
                return TOOL_GUARD_CONFIRM_TYPE_TIMEOUT
            if should_push_ws:
                sent = await connection_app_service.push_cron_progress(
                    target_chat_id,
                    content,
                    tool_hint=tool_hint,
                    job_id=job.id,
                    job_name=job.name,
                    job_query=job.payload.message,
                )
                if sent == 0:
                    await _fallback_topomobile_push(content, progress=True, tool_hint=tool_hint)
            elif should_push_topomobile:
                await _fallback_topomobile_push(content, progress=True, tool_hint=tool_hint)
            return None

        if should_push_ws:
            await connection_app_service.push_cron_progress(
                target_chat_id,
                "",
                tool_hint=False,
                job_id=job.id,
                job_name=job.name,
                job_query=job.payload.message,
            )

        if isinstance(cron_tool, CronTool):
            cron_token = cron_tool.set_cron_context(True)
        try:
            response = await cron_loop.process_direct(
                reminder_note,
                session_key=target_session_key,
                channel=target_channel,
                chat_id=target_chat_id,
                on_progress=_cron_progress if (should_push_ws or should_push_topomobile) else None,
            )
        finally:
            if isinstance(cron_tool, CronTool) and cron_token is not None:
                cron_tool.reset_cron_context(cron_token)

        message_tool = cron_loop.tools.get("message")
        if (
            (not response or not str(response).strip())
            and isinstance(message_tool, MessageTool)
            and bool(getattr(message_tool, "_sent_in_turn", False))
        ):
            fallback = getattr(message_tool, "_last_sent_content", None)
            if isinstance(fallback, str) and fallback.strip():
                response = fallback
        if (
            isinstance(message_tool, MessageTool)
            and message_tool._sent_in_turn
            and not should_push_ws
            and not should_push_topomobile
        ):
            return response

        if not response or not job.payload.deliver:
            return response

        if should_push_ws:
            sent = await connection_app_service.push_cron_done(
                target_chat_id,
                response,
                job_id=job.id,
                job_name=job.name,
                job_query=job.payload.message,
            )
            # 无订阅时兜底到 topomobile；有订阅时也镜像到 topomobile，保证手机/电脑双端同步可见
            if sent == 0:
                await _fallback_topomobile_push(response, progress=False)
            else:
                await _fallback_topomobile_push(response, progress=False)
        elif target_channel == "api":
            if runtime:
                await runtime.deliver(target_chat_id, response)
        elif target_channel == "topomobile":
            await _fallback_topomobile_push(response, progress=False)
            # topomobile 为主通道时，同步镜像到 websocket 侧（若有绑定）
            if target_chat_id:
                await connection_app_service.push_cron_done(
                    target_chat_id,
                    response,
                    job_id=job.id,
                    job_name=job.name,
                    job_query=job.payload.message,
                )
        else:
            await bus.publish_outbound(
                OutboundMessage(
                    channel=target_channel,
                    chat_id=target_chat_id,
                    content=response,
                )
            )
        return response

    cron.on_job = on_cron_job

    def _pick_heartbeat_target() -> tuple[str, str]:
        """Pick a routable target for heartbeat-triggered messages."""
        enabled = set(channels.enabled_channels)
        for item in session_manager.list_sessions():
            key = item.get("key") or ""
            if ":" not in key:
                continue
            channel, chat_id = key.split(":", 1)
            if channel == "system":
                continue
            if channel == "api":
                return channel, chat_id
            if channel in enabled and chat_id:
                return channel, chat_id
        return "cli", "direct"

    async def on_heartbeat_execute(tasks: str) -> str:
        channel, chat_id = _pick_heartbeat_target()

        async def _silent(*_args, **_kwargs):
            pass

        return await agent.process_direct(
            tasks,
            session_key="heartbeat",
            channel=channel,
            chat_id=chat_id,
            on_progress=_silent,
        )

    async def on_heartbeat_notify(response: str) -> None:
        channel, chat_id = _pick_heartbeat_target()
        if channel == "api":
            if runtime:
                await runtime.deliver(chat_id, response)
            return
        if channel == "cli":
            return
        await bus.publish_outbound(OutboundMessage(channel=channel, chat_id=chat_id, content=response))

    hb_cfg = config.gateway.heartbeat
    heartbeat = HeartbeatService(
        workspace=config.workspace_path,
        provider=provider,
        model=agent.model,
        on_execute=on_heartbeat_execute,
        on_notify=on_heartbeat_notify,
        interval_s=hb_cfg.interval_s,
        enabled=hb_cfg.enabled,
    )

    runtime = ServiceRuntime(
        bus=bus,
        agent=agent,
        channels=channels,
        cron=cron,
        heartbeat=heartbeat,
        sessions=session_manager,
        bindings=bindings,
        events=events,
        agent_registry=agent_registry,
        node_registry=node_registry,
        orchestration_registry=orchestration_registry,
    )
    runtime.token_usage_service = token_usage_service
    chat_service_ref = ChatService(
        runtime=runtime,
        workspace=config.workspace_path,
        registry=connection_registry,
    )
    connection_app_service = ConnectionAppService(
        registry=connection_registry,
        device_registry=device_registry,
        thread_binding_registry=thread_binding_registry,
    )
    channels.connection_app_service = connection_app_service
    api_app = create_agent_service_app(
        runtime,
        config.workspace_path,
        topoclaw_config=config,
        mobile_agent=mobile_agent,
        device_manager=device_manager,
        connection_registry=connection_registry,
        device_registry=device_registry,
        thread_binding_registry=thread_binding_registry,
        chat_service=chat_service_ref,
    )
    if config.channels.topomobile.enabled:
        tm_cfg = config.channels.topomobile
        tm_node_id = _resolve_topomobile_node_id_for_service_port(port, tm_cfg)
        tm_cfg.node_id = tm_node_id
        channels.register_topomobile_channel(
            runtime=runtime,
            skills_service=api_app.state.skills_service,
            topoclaw_config=config,
        )
        topomobile_ch = channels.channels.get("topomobile")
        if topomobile_ch and hasattr(api_app.state, "mobile_gui_service"):
            api_app.state.mobile_gui_service._direct_channel_send = topomobile_ch.send
        console.print(
            f"[green]✓[/green] TopoMobile channel enabled: {tm_cfg.ws_url} (node_id={tm_node_id})"
        )

    if channels.enabled_channels:
        console.print(f"[green]✓[/green] Channels enabled: {', '.join(channels.enabled_channels)}")
    else:
        console.print("[yellow]Warning: No channels enabled[/yellow]")

    async def run():
        try:
            await cron.start()
            await heartbeat.start()
            server_config = uvicorn.Config(
                api_app,
                host=host,
                port=port,
                log_level="info" if verbose else "warning",
            )
            server = uvicorn.Server(server_config)

            await asyncio.gather(
                agent.run(),
                channels.start_all(),
                server.serve(),
            )
        except KeyboardInterrupt:
            console.print("\nShutting down...")
        finally:
            for lp in agent_registry.loops.values():
                await lp.close_mcp()
                lp.stop()
            heartbeat.stop()
            cron.stop()
            await channels.stop_all()

    asyncio.run(run())




# ============================================================================
# Agent Commands
# ============================================================================


@app.command()
def agent(
    message: str = typer.Option(None, "--message", "-m", help="Message to send to the agent"),
    session_id: str = typer.Option("cli:direct", "--session", "-s", help="Session ID"),
    workspace: str | None = typer.Option(None, "--workspace", "-w", help="Workspace directory"),
    config: str | None = typer.Option(None, "--config", "-c", help="Config file path"),
    markdown: bool = typer.Option(True, "--markdown/--no-markdown", help="Render assistant output as Markdown"),
    logs: bool = typer.Option(False, "--logs/--no-logs", help="Show topoclaw runtime logs during chat"),
):
    """Interact with the agent directly."""
    from loguru import logger

    from topoclaw.agent.agent_loop_factory import toolcall_guard_from_config
    from topoclaw.agent.loop import AgentLoop
    from topoclaw.bus.queue import MessageBus
    from topoclaw.config.paths import get_cron_dir, get_runtime_subdir
    from topoclaw.cron.service import CronService
    from topoclaw.service.token_usage_service import TokenUsageService

    config = _load_runtime_config(config, workspace)
    _print_deprecated_memory_window_notice(config)
    sync_workspace_templates(config.workspace_path)

    bus = MessageBus()
    usage_service = TokenUsageService(get_runtime_subdir("metrics") / "token_usage.db")
    provider = _make_provider(config, usage_service=usage_service, usage_source="cli_agent")

    # Create cron service for tool usage (no callback needed for CLI unless running)
    cron_store_path = get_cron_dir() / "jobs.json"
    cron = CronService(cron_store_path)

    if logs:
        logger.enable("topoclaw")
    else:
        logger.disable("topoclaw")

    agent_loop = AgentLoop(
        bus=bus,
        provider=provider,
        workspace=config.workspace_path,
        model=config.agents.defaults.model,
        temperature=config.agents.defaults.temperature,
        max_tokens=config.agents.defaults.max_tokens,
        max_iterations=config.agents.defaults.max_tool_iterations,
        reasoning_effort=config.agents.defaults.reasoning_effort,
        provider_kwargs=config.agents.defaults.provider_kwargs,
        context_window_tokens=config.agents.defaults.context_window_tokens,
        brave_api_key=config.tools.web.search.api_key or None,
        web_proxy=config.tools.web.proxy or None,
        exec_config=config.tools.exec,
        cron_service=cron,
        restrict_to_workspace=config.tools.restrict_to_workspace,
        mcp_servers=config.tools.mcp_servers,
        channels_config=config.channels,
        app_config=config,
        toolcall_guard=toolcall_guard_from_config(config, config.workspace_path),
    )

    # Show spinner when logs are off (no output to miss); skip when logs are on
    def _thinking_ctx():
        if logs:
            from contextlib import nullcontext
            return nullcontext()
        # Animated spinner is safe to use with prompt_toolkit input handling
        return console.status("[dim]topoclaw is thinking...[/dim]", spinner="dots")

    async def _cli_progress(
        content: str,
        *,
        tool_hint: bool = False,
        tool_guard: bool = False,
        reasoning: bool = False,
    ) -> str | None:
        from topoclaw.models.constant import TOOL_GUARD_CONFIRM_TYPE_TIMEOUT

        if tool_guard:
            return TOOL_GUARD_CONFIRM_TYPE_TIMEOUT
        ch = agent_loop.channels_config
        if ch and tool_hint and not ch.send_tool_hints:
            return None
        if ch and reasoning and not ch.send_progress:
            return None
        if ch and not tool_hint and not ch.send_progress:
            return None
        prefix = "thinking" if reasoning else "↳"
        console.print(f"  [dim]{prefix} {content}[/dim]")
        return None

    if message:
        # Single message mode — direct call, no bus needed
        async def run_once():
            with _thinking_ctx():
                response = await agent_loop.process_direct(message, session_id, on_progress=_cli_progress)
            _print_agent_response(response, render_markdown=markdown)
            await agent_loop.close_mcp()

        asyncio.run(run_once())
    else:
        # Interactive mode — route through bus like other channels
        from topoclaw.bus.events import InboundMessage
        _init_prompt_session()
        console.print(f"{__logo__} Interactive mode (type [bold]exit[/bold] or [bold]Ctrl+C[/bold] to quit)\n")

        if ":" in session_id:
            cli_channel, cli_chat_id = session_id.split(":", 1)
        else:
            cli_channel, cli_chat_id = "cli", session_id

        def _handle_signal(signum, frame):
            sig_name = signal.Signals(signum).name
            _restore_terminal()
            console.print(f"\nReceived {sig_name}, goodbye!")
            sys.exit(0)

        signal.signal(signal.SIGINT, _handle_signal)
        signal.signal(signal.SIGTERM, _handle_signal)
        # SIGHUP is not available on Windows
        if hasattr(signal, 'SIGHUP'):
            signal.signal(signal.SIGHUP, _handle_signal)
        # Ignore SIGPIPE to prevent silent process termination when writing to closed pipes
        # SIGPIPE is not available on Windows
        if hasattr(signal, 'SIGPIPE'):
            signal.signal(signal.SIGPIPE, signal.SIG_IGN)

        async def run_interactive():
            bus_task = asyncio.create_task(agent_loop.run())
            turn_done = asyncio.Event()
            turn_done.set()
            turn_response: list[str] = []

            async def _consume_outbound():
                while True:
                    try:
                        msg = await asyncio.wait_for(bus.consume_outbound(), timeout=1.0)
                        if msg.metadata.get("_progress"):
                            is_tool_hint = msg.metadata.get("_tool_hint", False)
                            ch = agent_loop.channels_config
                            if ch and is_tool_hint and not ch.send_tool_hints:
                                pass
                            elif ch and not is_tool_hint and not ch.send_progress:
                                pass
                            else:
                                console.print(f"  [dim]↳ {msg.content}[/dim]")
                        elif not turn_done.is_set():
                            if msg.content:
                                turn_response.append(msg.content)
                            turn_done.set()
                        elif msg.content:
                            console.print()
                            _print_agent_response(msg.content, render_markdown=markdown)
                    except asyncio.TimeoutError:
                        continue
                    except asyncio.CancelledError:
                        break

            outbound_task = asyncio.create_task(_consume_outbound())

            try:
                while True:
                    try:
                        _flush_pending_tty_input()
                        user_input = await _read_interactive_input_async()
                        command = user_input.strip()
                        if not command:
                            continue

                        if _is_exit_command(command):
                            _restore_terminal()
                            console.print("\nGoodbye!")
                            break

                        turn_done.clear()
                        turn_response.clear()

                        await bus.publish_inbound(InboundMessage(
                            channel=cli_channel,
                            sender_id="user",
                            chat_id=cli_chat_id,
                            content=user_input,
                        ))

                        with _thinking_ctx():
                            await turn_done.wait()

                        if turn_response:
                            _print_agent_response(turn_response[0], render_markdown=markdown)
                    except KeyboardInterrupt:
                        _restore_terminal()
                        console.print("\nGoodbye!")
                        break
                    except EOFError:
                        _restore_terminal()
                        console.print("\nGoodbye!")
                        break
            finally:
                agent_loop.stop()
                outbound_task.cancel()
                await asyncio.gather(bus_task, outbound_task, return_exceptions=True)
                await agent_loop.close_mcp()

        asyncio.run(run_interactive())


# ============================================================================
# Channel Commands
# ============================================================================


channels_app = typer.Typer(help="Manage channels")
app.add_typer(channels_app, name="channels")


@channels_app.command("status")
def channels_status():
    """Show channel status."""
    from topoclaw.config.loader import load_config

    config = load_config()

    table = Table(title="Channel Status")
    table.add_column("Channel", style="cyan")
    table.add_column("Enabled", style="green")
    table.add_column("Configuration", style="yellow")

    # WhatsApp
    wa = config.channels.whatsapp
    table.add_row(
        "WhatsApp",
        "✓" if wa.enabled else "✗",
        wa.bridge_url
    )

    dc = config.channels.discord
    table.add_row(
        "Discord",
        "✓" if dc.enabled else "✗",
        dc.gateway_url
    )

    # Feishu
    fs = config.channels.feishu
    fs_config = f"app_id: {fs.app_id[:10]}..." if fs.app_id else "[dim]not configured[/dim]"
    table.add_row(
        "Feishu",
        "✓" if fs.enabled else "✗",
        fs_config
    )

    # Mochat
    mc = config.channels.mochat
    mc_base = mc.base_url or "[dim]not configured[/dim]"
    table.add_row(
        "Mochat",
        "✓" if mc.enabled else "✗",
        mc_base
    )

    # Telegram
    tg = config.channels.telegram
    tg_config = f"token: {tg.token[:10]}..." if tg.token else "[dim]not configured[/dim]"
    table.add_row(
        "Telegram",
        "✓" if tg.enabled else "✗",
        tg_config
    )

    # Slack
    slack = config.channels.slack
    slack_config = "socket" if slack.app_token and slack.bot_token else "[dim]not configured[/dim]"
    table.add_row(
        "Slack",
        "✓" if slack.enabled else "✗",
        slack_config
    )

    # DingTalk
    dt = config.channels.dingtalk
    dt_config = f"client_id: {dt.client_id[:10]}..." if dt.client_id else "[dim]not configured[/dim]"
    table.add_row(
        "DingTalk",
        "✓" if dt.enabled else "✗",
        dt_config
    )

    # QQ
    qq = config.channels.qq
    qq_config = f"app_id: {qq.app_id[:10]}..." if qq.app_id else "[dim]not configured[/dim]"
    table.add_row(
        "QQ",
        "✓" if qq.enabled else "✗",
        qq_config
    )

    # Email
    em = config.channels.email
    em_config = em.imap_host if em.imap_host else "[dim]not configured[/dim]"
    table.add_row(
        "Email",
        "✓" if em.enabled else "✗",
        em_config
    )

    # TopoMobile
    tm = config.channels.topomobile
    tm_config = tm.ws_url if tm.ws_url else "[dim]not configured[/dim]"
    table.add_row(
        "TopoMobile",
        "✓" if tm.enabled else "✗",
        tm_config,
    )

    console.print(table)


def _get_bridge_dir() -> Path:
    """Get the bridge directory, setting it up if needed."""
    import shutil
    import subprocess

    # User's bridge location
    from topoclaw.config.paths import get_bridge_install_dir
    from topoclaw.utils.path_guard import ensure_within, resolve_path

    user_bridge = resolve_path(get_bridge_install_dir())
    bridge_root = resolve_path(user_bridge.parent)
    user_bridge = ensure_within(user_bridge, bridge_root)

    # Check if already built
    if (user_bridge / "dist" / "index.js").exists():
        return user_bridge

    # Check for npm
    if not shutil.which("npm"):
        console.print("[red]npm not found. Please install Node.js >= 18.[/red]")
        raise typer.Exit(1)

    # Find source bridge: first check package data, then source dir
    pkg_bridge = Path(__file__).parent.parent / "bridge"  # topoclaw/bridge (installed)
    src_bridge = Path(__file__).parent.parent.parent / "bridge"  # repo root/bridge (dev)

    source = None
    if (pkg_bridge / "package.json").exists():
        source = pkg_bridge
    elif (src_bridge / "package.json").exists():
        source = src_bridge

    if not source:
        console.print("[red]Bridge source not found.[/red]")
        console.print("Try reinstalling: pip install --force-reinstall topoclaw-ai")
        raise typer.Exit(1)

    console.print(f"{__logo__} Setting up bridge...")

    # Copy to user directory
    user_bridge.parent.mkdir(parents=True, exist_ok=True)
    if user_bridge.exists():
        user_bridge = ensure_within(user_bridge, bridge_root)
        shutil.rmtree(user_bridge)
    shutil.copytree(source, user_bridge, ignore=shutil.ignore_patterns("node_modules", "dist"))

    # Install and build
    try:
        console.print("  Installing dependencies...")
        subprocess.run(["npm", "install"], cwd=user_bridge, check=True, capture_output=True)

        console.print("  Building...")
        subprocess.run(["npm", "run", "build"], cwd=user_bridge, check=True, capture_output=True)

        console.print("[green]✓[/green] Bridge ready\n")
    except subprocess.CalledProcessError as e:
        console.print(f"[red]Build failed: {e}[/red]")
        if e.stderr:
            console.print(f"[dim]{e.stderr.decode()[:500]}[/dim]")
        raise typer.Exit(1)

    return user_bridge


@channels_app.command("login")
def channels_login():
    """Link device via QR code."""
    import subprocess

    from topoclaw.config.loader import load_config
    from topoclaw.config.paths import get_runtime_subdir

    config = load_config()
    bridge_dir = _get_bridge_dir()

    console.print(f"{__logo__} Starting bridge...")
    console.print("Scan the QR code to connect.\n")

    env = {**os.environ}
    if config.channels.whatsapp.bridge_token:
        env["BRIDGE_TOKEN"] = config.channels.whatsapp.bridge_token
    env["AUTH_DIR"] = str(get_runtime_subdir("whatsapp-auth"))

    try:
        subprocess.run(["npm", "start"], cwd=bridge_dir, check=True, env=env)
    except subprocess.CalledProcessError as e:
        console.print(f"[red]Bridge failed: {e}[/red]")
    except FileNotFoundError:
        console.print("[red]npm not found. Please install Node.js.[/red]")


# ============================================================================
# Status Commands
# ============================================================================


@app.command()
def status():
    """Show topoclaw status."""
    from topoclaw.config.loader import get_config_path, load_config

    config_path = get_config_path()
    config = load_config()
    workspace = config.workspace_path

    console.print(f"{__logo__} topoclaw Status\n")

    console.print(f"Config: {config_path} {'[green]✓[/green]' if config_path.exists() else '[red]✗[/red]'}")
    console.print(f"Workspace: {workspace} {'[green]✓[/green]' if workspace.exists() else '[red]✗[/red]'}")

    if config_path.exists():
        from topoclaw.providers.registry import PROVIDERS

        console.print(f"Model: {config.agents.defaults.model}")

        # Check API keys from registry
        from topoclaw.config.schema import ProviderConfig
        for spec in PROVIDERS:
            p = getattr(config.providers, spec.name, None)
            if p is None:
                continue
            # Handle dynamic fields from extra="allow" - convert dict to ProviderConfig if needed
            if not isinstance(p, ProviderConfig):
                if isinstance(p, dict):
                    try:
                        p = ProviderConfig(**p)
                    except Exception:
                        continue
                else:
                    continue
            if spec.is_oauth:
                console.print(f"{spec.label}: [green]✓ (OAuth)[/green]")
            elif spec.is_local:
                # Local deployments show api_base instead of api_key
                if p.api_base:
                    console.print(f"{spec.label}: [green]✓ {p.api_base}[/green]")
                else:
                    console.print(f"{spec.label}: [dim]not set[/dim]")
            else:
                has_key = bool(p.api_key)
                console.print(f"{spec.label}: {'[green]✓[/green]' if has_key else '[dim]not set[/dim]'}")


# ============================================================================
# OAuth Login
# ============================================================================

provider_app = typer.Typer(help="Manage providers")
app.add_typer(provider_app, name="provider")


_LOGIN_HANDLERS: dict[str, callable] = {}


def _register_login(name: str):
    def decorator(fn):
        _LOGIN_HANDLERS[name] = fn
        return fn
    return decorator


@provider_app.command("login")
def provider_login(
    provider: str = typer.Argument(..., help="OAuth provider (e.g. 'openai-codex', 'github-copilot')"),
):
    """Authenticate with an OAuth provider."""
    from topoclaw.providers.registry import PROVIDERS

    key = provider.replace("-", "_")
    spec = next((s for s in PROVIDERS if s.name == key and s.is_oauth), None)
    if not spec:
        names = ", ".join(s.name.replace("_", "-") for s in PROVIDERS if s.is_oauth)
        console.print(f"[red]Unknown OAuth provider: {provider}[/red]  Supported: {names}")
        raise typer.Exit(1)

    handler = _LOGIN_HANDLERS.get(spec.name)
    if not handler:
        console.print(f"[red]Login not implemented for {spec.label}[/red]")
        raise typer.Exit(1)

    console.print(f"{__logo__} OAuth Login - {spec.label}\n")
    handler()


@_register_login("openai_codex")
def _login_openai_codex() -> None:
    try:
        from oauth_cli_kit import get_token, login_oauth_interactive
        token = None
        try:
            token = get_token()
        except Exception:
            pass
        if not (token and token.access):
            console.print("[cyan]Starting interactive OAuth login...[/cyan]\n")
            token = login_oauth_interactive(
                print_fn=lambda s: console.print(s),
                prompt_fn=lambda s: typer.prompt(s),
            )
        if not (token and token.access):
            console.print("[red]✗ Authentication failed[/red]")
            raise typer.Exit(1)
        console.print(f"[green]✓ Authenticated with OpenAI Codex[/green]  [dim]{token.account_id}[/dim]")
    except ImportError:
        console.print("[red]oauth_cli_kit not installed. Run: pip install oauth-cli-kit[/red]")
        raise typer.Exit(1)


@_register_login("github_copilot")
def _login_github_copilot() -> None:
    import asyncio

    console.print("[cyan]Starting GitHub Copilot device flow...[/cyan]\n")

    async def _trigger():
        from litellm import acompletion
        await acompletion(model="github_copilot/gpt-4o", messages=[{"role": "user", "content": "hi"}], max_tokens=1)

    try:
        asyncio.run(_trigger())
        console.print("[green]✓ Authenticated with GitHub Copilot[/green]")
    except Exception as e:
        console.print(f"[red]Authentication error: {e}[/red]")
        raise typer.Exit(1)


if __name__ == "__main__":
    app()
