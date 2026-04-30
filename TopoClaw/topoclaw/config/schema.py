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

"""Configuration schema using Pydantic."""

from pathlib import Path
from typing import Literal, Any

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel
from pydantic_settings import BaseSettings


class Base(BaseModel):
    """Base model that accepts both camelCase and snake_case keys."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class WhatsAppConfig(Base):
    """WhatsApp channel configuration."""

    enabled: bool = False
    bridge_url: str = "ws://localhost:3001"
    bridge_token: str = ""  # Shared token for bridge auth (optional, recommended)
    allow_from: list[str] = Field(default_factory=list)  # Allowed phone numbers


class TelegramConfig(Base):
    """Telegram channel configuration."""

    enabled: bool = False
    token: str = ""  # Bot token from @BotFather
    allow_from: list[str] = Field(default_factory=list)  # Allowed user IDs or usernames
    proxy: str | None = (
        None  # HTTP/SOCKS5 proxy URL, e.g. "http://127.0.0.1:7890" or "socks5://127.0.0.1:1080"
    )
    reply_to_message: bool = False  # If true, bot replies quote the original message
    group_policy: Literal["open", "mention"] = "mention"  # "mention" responds when @mentioned or replied to, "open" responds to all


class FeishuConfig(Base):
    """Feishu/Lark channel configuration using WebSocket long connection."""

    enabled: bool = False
    app_id: str = ""  # App ID from Feishu Open Platform
    app_secret: str = ""  # App Secret from Feishu Open Platform
    encrypt_key: str = ""  # Encrypt Key for event subscription (optional)
    verification_token: str = ""  # Verification Token for event subscription (optional)
    allow_from: list[str] = Field(default_factory=list)  # Allowed user open_ids
    react_emoji: str = (
        "THUMBSUP"  # Emoji type for message reactions (e.g. THUMBSUP, OK, DONE, SMILE)
    )


class DingTalkConfig(Base):
    """DingTalk channel configuration using Stream mode."""

    enabled: bool = False
    client_id: str = ""  # AppKey
    client_secret: str = ""  # AppSecret
    allow_from: list[str] = Field(default_factory=list)  # Allowed staff_ids


class DiscordConfig(Base):
    """Discord channel configuration."""

    enabled: bool = False
    token: str = ""  # Bot token from Discord Developer Portal
    allow_from: list[str] = Field(default_factory=list)  # Allowed user IDs
    gateway_url: str = "wss://gateway.discord.gg/?v=10&encoding=json"
    intents: int = 37377  # GUILDS + GUILD_MESSAGES + DIRECT_MESSAGES + MESSAGE_CONTENT
    group_policy: Literal["mention", "open"] = "mention"


class MatrixConfig(Base):
    """Matrix (Element) channel configuration."""

    enabled: bool = False
    homeserver: str = "https://matrix.org"
    access_token: str = ""
    user_id: str = ""  # @bot:matrix.org
    device_id: str = ""
    e2ee_enabled: bool = True  # Enable Matrix E2EE support (encryption + encrypted room handling).
    sync_stop_grace_seconds: int = (
        2  # Max seconds to wait for sync_forever to stop gracefully before cancellation fallback.
    )
    max_media_bytes: int = (
        20 * 1024 * 1024
    )  # Max attachment size accepted for Matrix media handling (inbound + outbound).
    allow_from: list[str] = Field(default_factory=list)
    group_policy: Literal["open", "mention", "allowlist"] = "open"
    group_allow_from: list[str] = Field(default_factory=list)
    allow_room_mentions: bool = False


class EmailConfig(Base):
    """Email channel configuration (IMAP inbound + SMTP outbound)."""

    enabled: bool = False
    consent_granted: bool = False  # Explicit owner permission to access mailbox data

    # IMAP (receive)
    imap_host: str = ""
    imap_port: int = 993
    imap_username: str = ""
    imap_password: str = ""
    imap_mailbox: str = "INBOX"
    imap_use_ssl: bool = True

    # SMTP (send)
    smtp_host: str = ""
    smtp_port: int = 587
    smtp_username: str = ""
    smtp_password: str = ""
    smtp_use_tls: bool = True
    smtp_use_ssl: bool = False
    from_address: str = ""

    # Behavior
    auto_reply_enabled: bool = (
        True  # If false, inbound email is read but no automatic reply is sent
    )
    poll_interval_seconds: int = 30
    mark_seen: bool = True
    max_body_chars: int = 12000
    subject_prefix: str = "Re: "
    allow_from: list[str] = Field(default_factory=list)  # Allowed sender email addresses


class MochatMentionConfig(Base):
    """Mochat mention behavior configuration."""

    require_in_groups: bool = False


class MochatGroupRule(Base):
    """Mochat per-group mention requirement."""

    require_mention: bool = False


class MochatConfig(Base):
    """Mochat channel configuration."""

    enabled: bool = False
    base_url: str = "https://mochat.io"
    socket_url: str = ""
    socket_path: str = "/socket.io"
    socket_disable_msgpack: bool = False
    socket_reconnect_delay_ms: int = 1000
    socket_max_reconnect_delay_ms: int = 10000
    socket_connect_timeout_ms: int = 10000
    refresh_interval_ms: int = 30000
    watch_timeout_ms: int = 25000
    watch_limit: int = 100
    retry_delay_ms: int = 500
    max_retry_attempts: int = 0  # 0 means unlimited retries
    claw_token: str = ""
    agent_user_id: str = ""
    sessions: list[str] = Field(default_factory=list)
    panels: list[str] = Field(default_factory=list)
    allow_from: list[str] = Field(default_factory=list)
    mention: MochatMentionConfig = Field(default_factory=MochatMentionConfig)
    groups: dict[str, MochatGroupRule] = Field(default_factory=dict)
    reply_delay_mode: str = "non-mention"  # off | non-mention
    reply_delay_ms: int = 120000


class SlackDMConfig(Base):
    """Slack DM policy configuration."""

    enabled: bool = True
    policy: str = "open"  # "open" or "allowlist"
    allow_from: list[str] = Field(default_factory=list)  # Allowed Slack user IDs


class SlackConfig(Base):
    """Slack channel configuration."""

    enabled: bool = False
    mode: str = "socket"  # "socket" supported
    webhook_path: str = "/slack/events"
    bot_token: str = ""  # xoxb-...
    app_token: str = ""  # xapp-...
    user_token_read_only: bool = True
    reply_in_thread: bool = True
    react_emoji: str = "eyes"
    allow_from: list[str] = Field(default_factory=list)  # Allowed Slack user IDs (sender-level)
    group_policy: str = "mention"  # "mention", "open", "allowlist"
    group_allow_from: list[str] = Field(default_factory=list)  # Allowed channel IDs if allowlist
    dm: SlackDMConfig = Field(default_factory=SlackDMConfig)


class QQConfig(Base):
    """QQ channel configuration using botpy SDK."""

    enabled: bool = False
    app_id: str = ""  # 机器人 ID (AppID) from q.qq.com
    secret: str = ""  # 机器人密钥 (AppSecret) from q.qq.com
    allow_from: list[str] = Field(
        default_factory=list
    )  # Allowed user openids (empty = public access)


class WeixinConfig(Base):
    """Weixin iLink Bot API (ClawBot) channel — long-poll getUpdates + sendmessage."""

    enabled: bool = False
    bot_token: str = ""  # Bearer token from QR login (`bot_token` in get_qrcode_status)
    base_url: str = "https://ilinkai.weixin.qq.com"  # API host from login (`baseurl`); often same as ilink root
    cdn_base_url: str = "https://novac2c.cdn.weixin.qq.com/c2c"
    bot_type: str = "3"  # get_bot_qrcode query param (see docs/weixin-api.md)
    allow_from: list[str] = Field(default_factory=list)  # Weixin IDs, e.g. xxx@im.wechat; ["*"] for all
    long_poll_timeout_ms: int = 35_000
    sk_route_tag: str = ""  # Optional SKRouteTag header (routing / pairing)
    account_id: str = "default"  # Sync-buf file suffix and session-pause key
    get_updates_buf_path: str = ""  # If empty: ~/.topoclaw/weixin/{account_id}_get_updates.buf
    channel_version: str = "1.0.2"  # Sent as base_info.channel_version



class TopoMobileConfig(Base):
    """TopoMobile cloud relay websocket adapter configuration."""

    enabled: bool = False
    # Same semantics as other channels: empty list denies all; ["*"] allows any sender (IMEI) on relay.
    allow_from: list[str] = Field(default_factory=lambda: ["*"])
    ws_url: str = "ws://localhost:8000/ws"
    # 注册到 customer_service 的 /ws/topomobile/{node_id}；多实例并行时需各不相同（如 000 / 001）
    node_id: str = "000"
    auth_token: str = ""
    reconnect_delay_seconds: float = 5.0
    open_timeout_seconds: float = 20.0
    recv_timeout_seconds: float = 60.0
    ping_interval_seconds: float | None = 20.0
    ping_timeout_seconds: float | None = 20.0
    extra_headers: dict[str, str] = Field(default_factory=dict)


class ChannelsConfig(Base):
    """Configuration for chat channels."""

    send_progress: bool = True  # stream agent's text progress to the channel
    send_tool_hints: bool = False  # stream tool-call hints (e.g. read_file("…"))
    whatsapp: WhatsAppConfig = Field(default_factory=WhatsAppConfig)
    telegram: TelegramConfig = Field(default_factory=TelegramConfig)
    discord: DiscordConfig = Field(default_factory=DiscordConfig)
    feishu: FeishuConfig = Field(default_factory=FeishuConfig)
    mochat: MochatConfig = Field(default_factory=MochatConfig)
    dingtalk: DingTalkConfig = Field(default_factory=DingTalkConfig)
    email: EmailConfig = Field(default_factory=EmailConfig)
    slack: SlackConfig = Field(default_factory=SlackConfig)
    qq: QQConfig = Field(default_factory=QQConfig)
    matrix: MatrixConfig = Field(default_factory=MatrixConfig)
    weixin: WeixinConfig = Field(default_factory=WeixinConfig)
    topomobile: TopoMobileConfig = Field(default_factory=TopoMobileConfig)


class AgentDefaults(Base):
    """Default agent configuration."""

    workspace: str = "~/.topoclaw/workspace"
    model: str = "deepseek-v3.2"
    provider: str = (
        "custom"  # e.g. "custom", "anthropic", "deepseek", or "auto" for auto-detection
    )
    max_tokens: int = 8192
    context_window_tokens: int = 65_536
    temperature: float = 0.1
    max_tool_iterations: int = 40
    # Deprecated compatibility field: accepted from old configs but ignored at runtime.
    memory_window: int | None = Field(default=None, exclude=True)
    reasoning_effort: str | None = None  # low / medium / high — enables LLM thinking mode
    provider_kwargs: dict[str, Any] = Field(default_factory=dict)  # extra parameters to pass to the LLM (e.g. thinking budget)

    @property
    def should_warn_deprecated_memory_window(self) -> bool:
        """Return True when old memoryWindow is present without contextWindowTokens."""
        return self.memory_window is not None and "context_window_tokens" not in self.model_fields_set


class GuiAgentConfig(Base):
    """GUI Agent configuration."""

    model: str = "qwen3-vl-32b-instruct"
    provider: str | None = None
    api_key: str = ""
    api_base: str | None = None
    provider_kwargs: dict[str, Any] = Field(default_factory=dict)
    models_needing_mapping: list[str] = Field(
        default_factory=lambda: ["seed", "qwen3"],
        description="List of model name substrings that require relative to absolute coordinate mapping"
    )


class NamedAgentEntry(Base):
    """Extra service agent with its own workspace (address with WebSocket ``agent_id``)."""

    id: str = Field(..., description="Stable id used as WS agent_id (not 'default')")
    workspace: str = Field(
        ...,
        description="Directory for AGENTS.md, memory/, sessions/ for this agent",
    )
    skills_exclude: list[str] = Field(default_factory=list)
    skills_include: list[str] = Field(
        default_factory=list,
        description="If non-empty, only these skill names appear in context",
    )


class AgentsConfig(Base):
    """Agent configuration."""

    defaults: AgentDefaults = Field(default_factory=AgentDefaults)
    gui: GuiAgentConfig | None = None
    named_agents: list[NamedAgentEntry] = Field(default_factory=list)


class ProviderConfig(Base):
    """LLM provider configuration."""

    api_key: str = ""
    api_base: str | None = None
    extra_headers: dict[str, str] | None = None  # Custom headers (e.g. APP-Code for AiHubMix)


class ProvidersConfig(Base):
    """Configuration for LLM providers.
    
    Supports multiple custom providers via custom1, custom2, etc. fields.
    Use model names like custom1/model, custom2/model to access them.
    """

    model_config = ConfigDict(extra="allow")  # Allow dynamic fields like custom1, custom2, etc.

    custom: ProviderConfig = Field(default_factory=ProviderConfig)  # Any OpenAI-compatible endpoint
    azure_openai: ProviderConfig = Field(default_factory=ProviderConfig)  # Azure OpenAI (model = deployment name)
    anthropic: ProviderConfig = Field(default_factory=ProviderConfig)
    openai: ProviderConfig = Field(default_factory=ProviderConfig)
    openrouter: ProviderConfig = Field(default_factory=ProviderConfig)
    deepseek: ProviderConfig = Field(default_factory=ProviderConfig)
    groq: ProviderConfig = Field(default_factory=ProviderConfig)
    zhipu: ProviderConfig = Field(default_factory=ProviderConfig)
    dashscope: ProviderConfig = Field(default_factory=ProviderConfig)  # 阿里云通义千问
    vllm: ProviderConfig = Field(default_factory=ProviderConfig)
    gemini: ProviderConfig = Field(default_factory=ProviderConfig)
    moonshot: ProviderConfig = Field(default_factory=ProviderConfig)
    minimax: ProviderConfig = Field(default_factory=ProviderConfig)
    aihubmix: ProviderConfig = Field(default_factory=ProviderConfig)  # AiHubMix API gateway
    siliconflow: ProviderConfig = Field(default_factory=ProviderConfig)  # SiliconFlow (硅基流动)
    volcengine: ProviderConfig = Field(default_factory=ProviderConfig)  # VolcEngine (火山引擎)
    openai_codex: ProviderConfig = Field(default_factory=ProviderConfig)  # OpenAI Codex (OAuth)
    github_copilot: ProviderConfig = Field(default_factory=ProviderConfig)  # Github Copilot (OAuth)


class HeartbeatConfig(Base):
    """Heartbeat service configuration."""

    enabled: bool = True
    interval_s: int = 30 * 60  # 30 minutes


class GatewayConfig(Base):
    """Gateway/server configuration."""

    host: str = "0.0.0.0"
    port: int = 18790
    heartbeat: HeartbeatConfig = Field(default_factory=HeartbeatConfig)


class WebSearchConfig(Base):
    """Web search tool configuration."""

    api_key: str = ""  # Serper-compatible API key
    api_base: str = ""  # Base URL for web_search API gateway (recommended via env: TOPOCLAW_TOOLS__WEB__SEARCH__API_BASE or SEARCH_API_BASE)
    serper_api_base: str = ""  # Base URL for serper_search root (recommended via env: TOPOCLAW_TOOLS__WEB__SEARCH__SERPER_API_BASE or SERPER_API_BASE)
    max_results: int = 5


class WebToolsConfig(Base):
    """Web tools configuration."""

    proxy: str | None = (
        None  # HTTP/SOCKS5 proxy URL, e.g. "http://127.0.0.1:7890" or "socks5://127.0.0.1:1080"
    )
    search: WebSearchConfig = Field(default_factory=WebSearchConfig)


class ExecToolConfig(Base):
    """Shell exec tool configuration."""

    timeout: int = 60
    path_append: str = ""


class BrowserUseToolConfig(Base):
    """Optional browser-use (PyPI) integration: real browser automation via AgentLoop tool."""

    enabled: bool = True
    cdp_url: str | None = Field(
        default=None,
        description=(
            "If set, attach to an existing Chrome CDP endpoint (user must start Chrome with "
            "--remote-debugging-port). If unset, browser-use launches a local browser itself (no manual CDP)."
        ),
    )
    default_timeout_s: int = 600
    user_data_dir: str = Field(
        default="",
        description=(
            "Chrome user-data-dir. Empty + no cdpUrl: use <workspace>/browser-use-profile. "
            "Empty + cdpUrl: do not set (attach uses remote browser profile)."
        ),
    )
    chrome_executable_path: str = ""
    headless: bool = False
    start_maximized: bool = Field(
        default=True,
        description=(
            "When true and not headless and not using cdpUrl, pass --start-maximized so the window "
            "uses OS maximized state (works alongside browser-use's --window-size)."
        ),
    )
    max_steps: int = 100
    flash_mode: bool = Field(
        default=True,
        description="Fast mode that skips evaluation, next goal, and thinking, using only memory.",
    )
    minimum_wait_page_load_time: float = Field(
        default=0.1,
        description="Minimum time to wait for page load (seconds). Lower means faster.",
    )
    wait_between_actions: float = Field(
        default=0.5,
        description="Wait time between browser actions (seconds). Lower means faster.",
    )
    log_steps: bool = Field(
        default=False,
        description=(
            "When true, log each browser-use step (URL, title, goals, thinking, actions) at INFO via loguru. "
            "CLI users need `topoclaw agent --logs` to see output (topoclaw loggers are disabled otherwise)."
        ),
    )
    log_steps_max_chars: int = Field(
        default=3000,
        ge=200,
        le=50_000,
        description="Per-field / actions JSON cap when log_steps is enabled (avoids huge base64 in logs).",
    )


class MCPServerConfig(Base):
    """MCP server connection configuration (stdio or HTTP)."""

    type: Literal["stdio", "sse", "streamableHttp"] | None = None  # auto-detected if omitted
    command: str = ""  # Stdio: command to run (e.g. "npx")
    args: list[str] = Field(default_factory=list)  # Stdio: command arguments
    env: dict[str, str] = Field(default_factory=dict)  # Stdio: extra env vars
    url: str = ""  # HTTP/SSE: endpoint URL
    headers: dict[str, str] = Field(default_factory=dict)  # HTTP/SSE: custom headers
    tool_timeout: int = 30  # seconds before a tool call is cancelled


class InteractiveSubagentConfig(Base):
    """Interactive subagent configuration."""

    enabled: bool = True
    max_concurrent_tasks: int = 5
    default_timeout_seconds: int = 600
    default_headless: bool = False
    human_assistance_timeout: int = 600
    # Default False: user message goes to main LLM, model decides when to resume
    human_assistance_skip_llm: bool = False
    notify_on_complete: bool = True
    notify_on_pause: bool = True
    notify_on_fail: bool = True


class DeeplinkLookupToolConfig(Base):
    """Optional deeplink catalog lookup tool."""

    enabled: bool = False
    auto_execute_mobile: bool = True


class MobileLocationReverseGeocodeConfig(Base):
    """Desktop-side reverse geocode configuration for mobile location skill."""

    api_key: str = ""
    regeo_url: str = "https://restapi.amap.com/v3/geocode/regeo"
    timeout_seconds: int = 10


class MobileLocationToolConfig(Base):
    """Mobile location skill config."""

    enabled: bool = True
    reverse_geocode: MobileLocationReverseGeocodeConfig = Field(default_factory=MobileLocationReverseGeocodeConfig)


class ToolsConfig(Base):
    """Tools configuration."""

    web: WebToolsConfig = Field(default_factory=WebToolsConfig)
    exec: ExecToolConfig = Field(default_factory=ExecToolConfig)
    browser_use: BrowserUseToolConfig = Field(default_factory=BrowserUseToolConfig)
    interactive: InteractiveSubagentConfig = Field(default_factory=InteractiveSubagentConfig)
    deeplink_lookup: DeeplinkLookupToolConfig = Field(default_factory=DeeplinkLookupToolConfig)
    mobile_location: MobileLocationToolConfig = Field(default_factory=MobileLocationToolConfig)
    restrict_to_workspace: bool = False  # If true, restrict all tool access to workspace directory
    mcp_servers: dict[str, MCPServerConfig] = Field(default_factory=dict)
    # When false, tool execution skips pre-execute policy checks (ToolcallGuard).
    use_toolcall_guard: bool = True
    toolcall_guard_extra_allowed_dirs: list[str] = Field(
        default_factory=list,
        description="Extra writable roots (in addition to agents.defaults.workspace) when use_toolcall_guard is true.",
    )


class TopoDesktopModelProfile(BaseModel):
    """One GUI/non-GUI model row under topo_desktop (TopoClaw Electron client)."""

    model_config = ConfigDict(extra="allow")

    model: str = ""
    apiBase: str = ""
    apiKey: str = ""


class TopoDesktopState(BaseModel):
    """Extension block in config.json maintained by the desktop app; nanobot ignores it at runtime but must parse."""

    model_config = ConfigDict(extra="allow")

    nonGuiProfiles: list[TopoDesktopModelProfile] = Field(default_factory=list)
    guiProfiles: list[TopoDesktopModelProfile] = Field(default_factory=list)
    activeNonGuiModel: str = ""
    activeImageModel: str = ""
    activeGuiModel: str = ""
    activeGroupManagerModel: str = ""


class Config(BaseSettings):
    """Root configuration for topoclaw."""

    agents: AgentsConfig = Field(default_factory=AgentsConfig)
    channels: ChannelsConfig = Field(default_factory=ChannelsConfig)
    providers: ProvidersConfig = Field(default_factory=ProvidersConfig)
    gateway: GatewayConfig = Field(default_factory=GatewayConfig)
    tools: ToolsConfig = Field(default_factory=ToolsConfig)
    topo_desktop: TopoDesktopState | None = None

    @property
    def workspace_path(self) -> Path:
        """Get expanded workspace path."""
        return Path(self.agents.defaults.workspace).expanduser()

    def _match_provider(
        self, model: str | None = None
    ) -> tuple["ProviderConfig | None", str | None]:
        """Match provider config and its registry name. Returns (config, spec_name)."""
        from topoclaw.providers.registry import PROVIDERS

        forced = self.agents.defaults.provider
        if forced != "auto":
            p = getattr(self.providers, forced, None)
            return (p, forced) if p else (None, None)

        model_lower = (model or self.agents.defaults.model).lower()
        model_normalized = model_lower.replace("-", "_")
        model_prefix = model_lower.split("/", 1)[0] if "/" in model_lower else ""
        normalized_prefix = model_prefix.replace("-", "_")

        def _kw_matches(kw: str) -> bool:
            kw = kw.lower()
            return kw in model_lower or kw.replace("-", "_") in model_normalized

        # Support multiple custom providers via model prefix (e.g. custom1/model, custom2/model)
        # Check if model prefix matches custom, custom1, custom2, etc.
        import re
        if model_prefix and re.match(r"^custom\d*$", model_prefix):
            custom_name = normalized_prefix  # custom, custom1, custom2, etc.
            p = getattr(self.providers, custom_name, None)
            # Handle dynamic fields from extra="allow" - convert dict to ProviderConfig if needed
            if p and not isinstance(p, ProviderConfig):
                if isinstance(p, dict):
                    try:
                        p = ProviderConfig(**p)
                    except Exception:
                        p = None
                else:
                    p = None
            if p and isinstance(p, ProviderConfig):
                # Allow empty string or any non-None api_key value (including "empty" for custom deployments)
                if p.api_key is not None:
                    return p, "custom"  # Return "custom" as provider_name for all custom variants

        # Explicit provider prefix wins — prevents `github-copilot/...codex` matching openai_codex.
        for spec in PROVIDERS:
            p = getattr(self.providers, spec.name, None)
            # Handle dynamic fields from extra="allow" - convert dict to ProviderConfig if needed
            if p and not isinstance(p, ProviderConfig):
                if isinstance(p, dict):
                    try:
                        p = ProviderConfig(**p)
                    except Exception:
                        p = None
                else:
                    p = None
            if p and isinstance(p, ProviderConfig) and model_prefix and normalized_prefix == spec.name:
                if spec.is_oauth or p.api_key:
                    return p, spec.name

        # Match by keyword (order follows PROVIDERS registry)
        for spec in PROVIDERS:
            p = getattr(self.providers, spec.name, None)
            # Handle dynamic fields from extra="allow" - convert dict to ProviderConfig if needed
            if p and not isinstance(p, ProviderConfig):
                if isinstance(p, dict):
                    try:
                        p = ProviderConfig(**p)
                    except Exception:
                        p = None
                else:
                    p = None
            if p and isinstance(p, ProviderConfig) and any(_kw_matches(kw) for kw in spec.keywords):
                if spec.is_oauth or p.api_key:
                    return p, spec.name

        # Fallback: gateways first, then others (follows registry order)
        # OAuth providers are NOT valid fallbacks — they require explicit model selection
        for spec in PROVIDERS:
            if spec.is_oauth:
                continue
            p = getattr(self.providers, spec.name, None)
            # Handle dynamic fields from extra="allow" - convert dict to ProviderConfig if needed
            if p and not isinstance(p, ProviderConfig):
                if isinstance(p, dict):
                    try:
                        p = ProviderConfig(**p)
                    except Exception:
                        p = None
                else:
                    p = None
            if p and isinstance(p, ProviderConfig) and p.api_key:
                return p, spec.name
        return None, None

    def get_provider(self, model: str | None = None) -> ProviderConfig | None:
        """Get matched provider config (api_key, api_base, extra_headers). Falls back to first available."""
        p, _ = self._match_provider(model)
        return p

    def get_provider_name(self, model: str | None = None) -> str | None:
        """Get the registry name of the matched provider (e.g. "deepseek", "openrouter")."""
        _, name = self._match_provider(model)
        return name

    def get_api_key(self, model: str | None = None) -> str | None:
        """Get API key for the given model. Falls back to first available key."""
        p = self.get_provider(model)
        # Ensure p is a ProviderConfig instance (handle dynamic fields from extra="allow")
        if p and not isinstance(p, ProviderConfig):
            if isinstance(p, dict):
                try:
                    p = ProviderConfig(**p)
                except Exception:
                    return None
            else:
                return None
        return p.api_key if (p and isinstance(p, ProviderConfig)) else None

    def get_api_base(self, model: str | None = None) -> str | None:
        """Get API base URL for the given model. Applies default URLs for known gateways."""
        from topoclaw.providers.registry import find_by_name

        p, name = self._match_provider(model)
        # Ensure p is a ProviderConfig instance (handle dynamic fields from extra="allow")
        if p and not isinstance(p, ProviderConfig):
            if isinstance(p, dict):
                try:
                    p = ProviderConfig(**p)
                except Exception:
                    p = None
            else:
                p = None
        if p and isinstance(p, ProviderConfig) and p.api_base:
            return p.api_base
        # Only gateways get a default api_base here. Standard providers
        # (like Moonshot) set their base URL via env vars in _setup_env
        # to avoid polluting the global litellm.api_base.
        if name:
            spec = find_by_name(name)
            if spec and spec.is_gateway and spec.default_api_base:
                return spec.default_api_base
        return None

    model_config = ConfigDict(env_prefix="TOPOCLAW_", env_nested_delimiter="__")
