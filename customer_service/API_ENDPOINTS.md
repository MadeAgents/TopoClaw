# customer_service2 — HTTP / WebSocket 接口参考

本文档罗列本服务暴露的 **REST 路径** 与 **WebSocket**。行为与上级目录 `../customer_service` 一致；代码按模块拆在 `api/` 下。

- **默认端口**：`8001`（见 `app.py`）
- **交互式文档**：服务启动后访问 `/docs`（Swagger）、`/redoc`
- **目录与 `outputs/` 说明**：见 [README.md](./README.md)

---

## 绑定 — `api/routes_binding.py`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/binding/{token}` | 扫码后上报 IMEI，完成绑定 |
| GET | `/api/binding/{token}` | 查询绑定状态 |

---

## 人工客服 — `api/routes_customer.py`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/customer-service/register` | 用户上线注册 |
| POST | `/api/customer-service/send-message` | 客服向用户发消息（含离线队列） |
| GET | `/api/customer-service/health` | 健康检查 |
| GET | `/api/customer-service/online-users` | 在线用户列表 |
| GET | `/api/customer-service/user-status/{imei}` | 指定用户在线状态 |
| GET | `/api/customer-service/pc-status/{imei}` | 指定用户 PC 在线状态 |
| POST | `/api/customer-service/test-connection/{imei}` | 测试向用户推送 |
| GET | `/api/customer-service/all-chats` | 全量聊天记录（供管理/调试） |

---

## 多端 / 统一消息 / Session — `api/routes_cross_device.py`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/cross-device/send` | 端云互发消息 |
| POST | `/api/cross-device/execute` | PC 执行类指令 |
| GET | `/api/cross-device/messages` | 端云消息列表 |
| GET | `/api/messages` | 统一会话消息（查询参数区分会话类型等） |
| GET | `/api/sessions` | 多 session 列表 |
| POST | `/api/sessions/sync` | session 同步 |

---

## 自定义小助手 — `api/routes_assistants.py`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/custom-assistants` | 拉取列表，查询参数：`imei` |
| POST | `/api/custom-assistants` | 同步保存列表 |
| POST | `/api/custom-assistant-chat/append` | 追加单条聊天记录 |

---

## 小助手广场 — `api/routes_plaza.py`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/plaza-assistants` | 分页列表，查询参数：`page`、`limit` |
| POST | `/api/plaza-assistants` | 上架到广场 |
| POST | `/api/plaza-assistants/{plaza_id}/add` | 从广场添加到我的助手 |
| PATCH | `/api/plaza-assistants/{plaza_id}` | 更新广场条目 |

---

## 好友 — `api/routes_friends.py`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/friends/add` | 添加好友 |
| POST | `/api/friends/accept` | 接受好友请求 |
| GET | `/api/friends/list` | 好友列表 |
| POST | `/api/friends/send-message` | 好友消息 |
| POST | `/api/friends/remove` | 移除好友 |

---

## 群组 — `api/routes_groups.py`

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/groups/create` | 创建群 |
| POST | `/api/groups/add-member` | 添加成员 |
| POST | `/api/groups/remove-member` | 移除成员 |
| POST | `/api/groups/set-assistant` | 设置/开关群小助手 |
| POST | `/api/groups/add-assistant` | 添加群小助手 |
| POST | `/api/groups/remove-assistant` | 移除群小助手 |
| POST | `/api/groups/update-assistant-config` | 更新小助手配置 |
| GET | `/api/groups/list` | 用户的群列表 |
| POST | `/api/groups/send-assistant-message` | 发送小助手相关群消息 |
| GET | `/api/groups/{group_id}` | 群详情 |

---

## 用户资料 — `api/routes_profile.py`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/profile/{imei}` | 获取资料 |
| POST | `/api/profile/{imei}` | 更新资料（`Form` 表单字段） |
| DELETE | `/api/profile/{imei}` | 删除资料 |

---

## 版本检查 — `api/routes_version.py`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/version/check` | 版本比对；可选查询参数：`current_version` |

---

## 用户设置 — `api/routes_user_settings.py`

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/user-settings` | 获取用户所有设置（带默认值）；查询参数：`imei` |
| POST | `/api/user-settings` | 批量更新用户设置；请求体：`{imei, settings}` |
| GET | `/api/user-settings/{key}` | 获取单个设置项；查询参数：`imei` |
| POST | `/api/user-settings/{key}` | 设置单个设置项；请求体：`{imei, key, value}` |

### 设置项说明

| 设置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| `all_agents_reply` | bool | `false` | 群聊广播策略：`false`只广播给默认助手(topoclaw)，`true`广播给所有助手 |

---

## WebSocket — `api/websocket_customer.py`

| 类型 | 路径 | 说明 |
|------|------|------|
| WebSocket | `/ws/customer-service/{imei}` | 多端实时通道；查询串含 `device=pc` 时表示 PC 端 |
| WebSocket | `/ws` | 手机侧 TopoClaw API 中转通道（通过已连接 TopoMobile adapter 转发；支持 register） |

---

## 非路由模块说明

- **`api/assistant_reply.py`**：群组 @ 小助手时的服务端回复逻辑，供内部调用；**未**单独注册 HTTP 路径。

---

## 维护说明

新增或修改路由后，请同步更新本文件与 `/docs` 对照，避免文档漂移。
