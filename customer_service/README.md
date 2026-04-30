# 中转服务（customer_service）

与 `../customer_service` **接口与行为一致**，代码按功能模块拆分，便于维护。

## 接口清单

完整的 HTTP / WebSocket 路径表见 **[API_ENDPOINTS.md](./API_ENDPOINTS.md)**（与 `/docs` 对照维护）。

## 目录说明

| 目录/文件 | 说明 |
|-----------|------|
| `app.py` | FastAPI 入口：挂载路由与 WebSocket |
| `core/` | 全局依赖、时间工具、`output_paths.py`（`outputs/` 目录规划） |
| `storage/` | 进程内状态与 JSON 持久化（绑定、端云消息、好友、资料、版本读取） |
| `schemas/` | Pydantic 请求/响应模型 |
| `api/` | HTTP 路由（按域拆分）与 `websocket_customer.py` |
| `services/` | 业务服务（与原版相同） |
| `websocket/` | WebSocket 连接管理（与原版相同） |
| `storage_browser/` | 存储浏览静态页（`index.html`） |
| `storage_browser_server.py` | 只读浏览 `outputs/` 的本地 HTTP 服务（默认端口 8765，与 FastAPI 独立） |

### 路由模块对应关系

- **绑定**：`api/routes_binding.py`
- **人工客服 + 健康/在线/测试/全量聊天**：`api/routes_customer.py`
- **多端（端云、统一消息、session）**：`api/routes_cross_device.py`
- **自定义小助手**：`api/routes_assistants.py`
- **广场**：`api/routes_plaza.py`
- **好友**：`api/routes_friends.py`
- **群组**：`api/routes_groups.py`
- **用户资料**：`api/routes_profile.py`
- **版本检查**：`api/routes_version.py`
- **WebSocket**：`api/websocket_customer.py`（`register_websocket`）

## 安装与运行

```bash
cd customer_service2
pip install -r requirements.txt
python app.py
```

或使用 uvicorn：

```bash
uvicorn app:app --host 0.0.0.0 --port 8001 --ws wsproto
```

### 存储浏览页（可选）

在 **`customer_service2`** 目录下执行：

```bash
python storage_browser_server.py
```

浏览器访问终端提示的 `http://127.0.0.1:8765/`（支持 `/v6/` 前缀与网关部署，见脚本内说明）。该服务**仅只读** `outputs/`，与 `app.py` 端口不同，需单独启动。

存储页左上角 **IMEI 下拉**与聚合联动：选择某 IMEI 即调用 `GET /api/search-imei?imei=...`，左侧列出该 IMEI 在全库中的相关片段；选「全部文件」恢复目录树。接口从资料、好友、群组、端云消息、统一消息（按会话）、sessions、自定义助手、广场、人工客服 jsonl 等提取 JSON 子集（大列表会截断并标注 `_truncated`）。

工具栏右侧 **近1小时控制台日志** 调用 `GET /api/recent-terminal-logs?hours=1`（`hours` 可在后端允许范围内调整），读取 `outputs/server_logs/app_terminal.log*`（由 `python app.py` 与终端同步写入，单文件约 20MB 轮转共 5 个备份），解析近 1 小时内行并按时间新→旧排序（有条数与按文件尾部读取上限，响应中带 `truncated`、`logRelPath`）。若使用 `uvicorn app:app` 命令行启动且未加载与本包一致的 `log_config`，该目录下可能无文件，需改用 `python app.py` 或在启动配置中挂载相同日志文件 Handler。

## 数据目录 `outputs/`

所有持久化数据写在 **`customer_service2/outputs/`** 下，按功能分子目录（与 `core/output_paths.py` 一致）：

| 子目录 | 内容 |
|--------|------|
| `outputs/multi_device/` | 端云互发 `cross_device_messages.json` |
| `outputs/friends/` | 好友关系 `friends_storage.json` |
| `outputs/profiles/` | 用户资料 `profiles_storage.json` |
| `outputs/unified_messages/` | 统一会话消息 `unified_messages.json` |
| `outputs/groups/` | 群组 `groups_storage.json`、`user_groups.json` |
| `outputs/sessions/` | 多 session 同步 `unified_sessions.json` |
| `outputs/custom_assistants/` | 自定义小助手列表 `custom_assistants.json` |
| `outputs/plaza/` | 广场 `plaza_assistants.json` |
| `outputs/customer_service/` | 人工客服对话日志 `*.jsonl` |
| `outputs/server_logs/` | `python app.py` 控制台镜像日志 `app_terminal.log`（供存储页「近1小时控制台日志」） |
| `outputs/version/` | 运行使用的 `version_info.json`（首次从包根目录 `version_info.json` 复制） |

启动时会**自动创建**上述目录；若曾在本包根目录存放过旧版 JSON，会**自动迁移**到对应子目录（仅当目标路径尚不存在时）。

绑定令牌仍为内存存储，不落盘。离线消息队列仍在内存中。

更多 API 说明见原版 `../customer_service/README.md`（路径仍适用）。

## Mock Clients

用于联调 `customer_service` 的静态页面：

- `mock_clients/a_mobile.html`：用户 A 手机
- `mock_clients/a_pc.html`：用户 A 电脑
- `mock_clients/b_mobile.html`：用户 B 手机
- `mock_clients/b_pc.html`：用户 B 电脑
- `mock_clients/dashboard_6_clients.html`：六设备总览（A 手机/A 电脑/B 手机/B 电脑/C 手机/D 手机）

主要能力：

- 连接聊天 WS：`/ws/customer-service/{imei}`
- 连接 Agent WS：`/ws`（bridge 模式，`imei_id` 需和本地 TopoClaw `channels.topomobile.nodeId` 一致）
- 好友、私聊、群组、群消息、Agent 创建与会话
- 群助手邀请与 `@小助手` 定向回复
- 六设备同屏联调与默认好友关系初始化

推荐启动方式（在 `customer_service/mock_clients` 下）：

```bash
python -m http.server 8088
```

然后访问：

- `http://127.0.0.1:8088/a_mobile.html`
- `http://127.0.0.1:8088/a_pc.html`
- `http://127.0.0.1:8088/b_mobile.html`
- `http://127.0.0.1:8088/b_pc.html`
- `http://127.0.0.1:8088/dashboard_6_clients.html`

默认 `HTTP Base = http://127.0.0.1:8001`，如中转地址不同请在页面中手动调整。
