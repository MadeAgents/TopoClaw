# TopoClaw

`topoclaw` is a lightweight personal AI Agent framework supporting:
- Local command-line conversations
- Gateway / service mode
- Multi-channel messaging (Telegram / Discord / Feishu / WeChat, etc.)
- Tool calling, MCP, cron tasks, workspace memory

---

## Table of Contents

- [Quick Start](#quick-start)
- [Installation](#installation)
- [Initialization & Configuration](#initialization--configuration)
- [Common Commands](#common-commands)
- [Channel Support](#channel-support)
- [FAQ](#faq)
- [Acknowledgments](#acknowledgments)

---

## Quick Start

### 1) Install

From the project root:

Python requirement: `>=3.11`

```bash
pip install -e .
```

Verify:

```bash
topoclaw --version
```

### 2) Initialize

```bash
topoclaw onboard
```

This creates default directories:
- `~/.topoclaw/config.json`
- `~/.topoclaw/workspace`

### 3) Configure Model & Credentials

Edit `~/.topoclaw/config.json`. Minimal working example:

```json
{
  "providers": {
    "openrouter": {
      "apiKey": "sk-or-v1-xxxx"
    }
  },
  "agents": {
    "defaults": {
      "model": "anthropic/claude-opus-4-1",
      "provider": "openrouter"
    }
  }
}
```

### 4) Start Using

Interactive conversation:

```bash
topoclaw agent
```

Single-turn message:

```bash
topoclaw agent -m "Hello, tell me what you can do"
```

---

## Installation

### Option A: Dev Mode (recommended)

```bash
cd TopoClaw
pip install -e .
```

### Option B: Using uv

```bash
uv sync
uv run topoclaw --version
```

### Option C: Package Install (if published)

```bash
pip install topoclaw-ai
topoclaw --version
```

---

## Initialization & Configuration

### Config File

- Path: `~/.topoclaw/config.json`
- First-time generation: `topoclaw onboard`

### Workspace

- Default path: `~/.topoclaw/workspace`
- Override via argument: `--workspace /path/to/workspace`

Example:

```bash
topoclaw agent --workspace ~/my-agent-workspace
```

### OAuth Provider Login

```bash
topoclaw provider login openai-codex
topoclaw provider login github-copilot
```

---

## Common Commands

View help:

```bash
topoclaw --help
```

Main commands:

- `topoclaw onboard` — Initialize config and workspace
- `topoclaw agent` — Interact with the agent directly
- `topoclaw gateway` — Start the gateway
- `topoclaw service` — Start the unified service (HTTP/WebSocket + runtime)
- `topoclaw status` — View status
- `topoclaw channels ...` — Channel management
- `topoclaw provider ...` — Provider management

### Gateway

```bash
topoclaw gateway
```

With custom config/workspace:

```bash
topoclaw gateway -c ~/.topoclaw/config.json -w ~/.topoclaw/workspace
```

### Service (recommended for frontend / API scenarios)

```bash
topoclaw service --host 0.0.0.0 --port 18790
```

---

## Channel Support

View channel status:

```bash
topoclaw channels status
```

WhatsApp login (bridge):

```bash
topoclaw channels login
```

Other channels (Telegram / Discord / Feishu / WeChat, etc.) are enabled through the `channels` section in `config.json`.

---

## FAQ

### 1) `topoclaw` command not found

- Confirm installation: `pip install -e .`
- Confirm the current Python environment matches the install environment
- Try: `python -m topoclaw --version`

### 2) Provider reports missing API key

- Check the `apiKey` for the corresponding provider in `~/.topoclaw/config.json`
- Run `topoclaw status` to view provider state

### 3) Channel fails to start

- Run `topoclaw channels status` to see enabled channels
- Verify the corresponding token / app_id / secret is correctly configured
- For WhatsApp, run `topoclaw channels login` to bind the device first

---

## Acknowledgments

TopoClaw (`topoclaw`) is built on top of **[nanobot](https://github.com/HKUDS/nanobot)**. Thanks to HKUDS and the nanobot community for open-sourcing the code and architecture, enabling rapid evolution of personal Agent, gateway, and multi-channel capabilities.

On top of nanobot, TopoClaw focuses on (but is not limited to):

- **Runtime & Configuration**: Unified service (HTTP/WebSocket), `onboard` and config template evolution, runtime model/provider switching via WebSocket `set_llm_provider` with config write-back.
- **Security & Tool Policies**: ToolcallGuard (`tools.useToolcallGuard`, etc.) and path-permission sidecar `toolcall_guard_path_permissions.json` for pre-execution policy validation.
- **Multi-Agent & Desktop Collaboration**: Server-side Agent registration/deletion, coordination with TopoDesktop and other clients via `/ws`, device registration protocols, etc.
- **GUI Execution**: Optimizations around mobile/desktop GUI task-execution pipelines and multi-device collaboration (integrated with gateway, sessions, and tool calls).
- **browser-use**: Browser automation (`tools.browserUse`, etc.) integration, parameter tuning, and UX improvements.
- **Defaults & Branding**: Default models, providers, CLI, and package names follow this repo's `topoclaw` conventions, maintaining a comparable and migratable delta from upstream.

If you use or build upon this repository, please also comply with nanobot's and its dependencies' open-source licenses.

---

## Contributing

Issues and PRs are welcome to improve:
- Documentation
- New channel support
- Tool and capability extensions
- Stability and performance

---

## Skills (merged from `topoclaw/skills/README.md`)

The `topoclaw/skills/` directory contains built-in skills. Each skill directory includes at least one `SKILL.md`:

- Uses YAML frontmatter for name, description, and metadata
- Uses Markdown for agent execution instructions

Current built-in skill examples:

- `github` — Interact with GitHub via the `gh` CLI
- `weather` — Weather queries (wttr.in / Open-Meteo)
- `summarize` — Summarize URLs, files, and video content
- `tmux` — Remote-control tmux sessions
- `clawhub` — Search and install skills from ClawHub
- `skill-creator` — Create new skills

Attribution: The skill structure and metadata conventions are adapted from and compatible with OpenClaw.
