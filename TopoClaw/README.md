# TopoClaw

`topoclaw` 是一个轻量的个人 AI Agent 框架，支持：
- 本地命令行对话
- 网关/服务模式运行
- 多渠道消息接入（Telegram / Discord / Feishu / 微信等）
- Tool 调用、MCP、定时任务（cron）、工作区记忆

---

## 目录

- [快速开始](#快速开始)
- [安装方式](#安装方式)
- [初始化与配置](#初始化与配置)
- [常用命令](#常用命令)
- [渠道能力](#渠道能力)
- [常见问题](#常见问题)
- [致谢](#致谢)

---

## 快速开始

### 1) 安装

在项目根目录执行：

Python 版本要求：`>=3.11`

```bash
pip install -e .
```

安装完成后验证：

```bash
topoclaw --version
```

### 2) 初始化

```bash
topoclaw onboard
```

初始化后会创建默认目录：
- `~/.topoclaw/config.json`
- `~/.topoclaw/workspace`

### 3) 配置模型与密钥

编辑 `~/.topoclaw/config.json`，最小可用示例：

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

### 4) 开始使用

交互式对话：

```bash
topoclaw agent
```

单轮消息：

```bash
topoclaw agent -m "你好，介绍一下你能做什么"
```

---

## 安装方式

### 方式 A：开发模式（推荐）

```bash
cd TopoClaw
pip install -e .
```

### 方式 B：使用 uv

```bash
uv sync
uv run topoclaw --version
```

### 方式 C：从包安装（如果已发布）

```bash
pip install topoclaw-ai
topoclaw --version
```

---

## 初始化与配置

### 配置文件

- 路径：`~/.topoclaw/config.json`
- 首次生成：`topoclaw onboard`

### 工作区

- 默认路径：`~/.topoclaw/workspace`
- 可通过命令参数覆盖：`--workspace /path/to/workspace`

示例：

```bash
topoclaw agent --workspace ~/my-agent-workspace
```

### OAuth Provider 登录

```bash
topoclaw provider login openai-codex
topoclaw provider login github-copilot
```

---

## 常用命令

查看帮助：

```bash
topoclaw --help
```

当前主要命令：

- `topoclaw onboard`：初始化配置和工作区
- `topoclaw agent`：直接与 agent 交互
- `topoclaw gateway`：启动网关
- `topoclaw service`：启动统一服务（HTTP/WebSocket + runtime）
- `topoclaw status`：查看状态
- `topoclaw channels ...`：渠道管理
- `topoclaw provider ...`：Provider 管理

### Gateway

```bash
topoclaw gateway
```

指定配置/工作区：

```bash
topoclaw gateway -c ~/.topoclaw/config.json -w ~/.topoclaw/workspace
```

### Service（推荐给前端/API 场景）

```bash
topoclaw service --host 0.0.0.0 --port 18790
```

---

## 渠道能力

查看渠道状态：

```bash
topoclaw channels status
```

WhatsApp 登录（桥接）：

```bash
topoclaw channels login
```

其他渠道（Telegram/Discord/Feishu/微信等）通过 `config.json` 的 `channels` 配置项启用。

---

## 常见问题

### 1) `topoclaw` 命令找不到

- 确认已执行安装：`pip install -e .`
- 确认当前 Python 环境和安装环境一致
- 可用 `python -m topoclaw --version` 验证

### 2) Provider 报 API key 缺失

- 检查 `~/.topoclaw/config.json` 中对应 provider 的 `apiKey`
- 运行 `topoclaw status` 看 provider 状态

### 3) 渠道启动失败

- 先执行 `topoclaw channels status` 查看启用项
- 确认对应渠道 token / app_id / secret 已正确填写
- 对 WhatsApp，先执行 `topoclaw channels login` 进行设备绑定

---

## 致谢

TopoClaw（`topoclaw`）建立在 **[nanobot](https://github.com/HKUDS/nanobot)** 开源项目之上。感谢 HKUDS 与 nanobot 社区开放代码与架构，使个人 Agent、网关与多渠道能力得以快速演进。

在 nanobot 的基础上，TopoClaw 侧重做了包括但不限于：

- **运行时与配置**：统一服务（HTTP/WebSocket）、`onboard` 与配置模板演进、运行时通过 WebSocket `set_llm_provider` 调整模型与 Provider 并写回配置等能力。
- **安全与工具策略**：ToolcallGuard（`tools.useToolcallGuard` 等）与路径权限侧车 `toolcall_guard_path_permissions.json`，在工具执行前做策略校验。
- **多 Agent 与桌面协同**：服务侧 Agent 注册/删除、与 TopoDesktop 等客户端通过 `/ws`、设备注册等协议协同（具体以仓库实现为准）。
- **GUI 执行**：围绕移动端 / 桌面端图形界面任务执行链路的优化与多端协同（与网关、会话、工具调用衔接）。
- **browser-use**：浏览器自动化（`tools.browserUse` 等）相关集成、参数与运行体验的优化。
- **默认与品牌**：默认模型、Provider、CLI 与包名等以本仓库 `topoclaw` 为准，与上游保持可对比、可迁移的差异。

若你使用或二次开发本仓库，也请同时遵守 nanobot 及其依赖的开源许可证。

---

## 贡献

欢迎提交 Issue / PR 改进：
- 文档说明
- 新渠道支持
- 工具与能力扩展
- 稳定性和性能优化

---

## Skills（已合并 `topoclaw/skills/README.md`）

`topoclaw/skills/` 目录包含内置技能，每个技能目录至少包含一个 `SKILL.md`：

- 使用 YAML frontmatter 描述名称、简介与元信息
- 使用 Markdown 编写对 agent 的执行指令

当前内置技能示例：

- `github`：通过 `gh` CLI 访问 GitHub
- `weather`：天气查询（`wttr.in` / Open-Meteo）
- `summarize`：URL、文件、视频内容摘要
- `tmux`：远程控制 tmux 会话
- `clawhub`：从 ClawHub 搜索/安装技能
- `skill-creator`：创建新技能

致谢：技能结构与元数据约定参考并兼容 OpenClaw。

