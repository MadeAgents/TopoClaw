# Customer Service 业务逻辑文档

本文档详细描述 customer_service 系统的业务逻辑、通信时序和接口定义。

## 目录

- [1. 系统架构概述](#1-系统架构概述)
- [2. 端侧设备与中继服务通信](#2-端侧设备与中继服务通信)
- [3. 单聊业务时序](#3-单聊业务时序)
- [4. 群聊业务时序](#4-群聊业务时序)
- [5. 群内 Agent 处理业务时序](#5-群内-agent-处理业务时序)
- [6. 跨用户跨 Agent 通信](#6-跨用户跨-agent-通信)
- [7. 接口定义](#7-接口定义)

---

## 1. 系统架构概述

### 1.1 核心组件

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              Customer Service                                │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐ │
│  │  WebSocket  │  │  HTTP API   │  │   中继服务   │  │     存储服务         │ │
│  │  /ws/...    │  │  /api/...   │  │  (TopoMobile)│  │  (JSON/内存队列)     │ │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘  └─────────────────────┘ │
└─────────┼────────────────┼────────────────┼─────────────────────────────────┘
          │                │                │
          ▼                ▼                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                                客户端设备                                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐    │
│  │   手机端     │  │    PC端      │  │  Agent助手   │  │ TopoClaw节点 │    │
│  │  (IMEI绑定)  │  │  (device=pc) │  │ (assistant)  │  │  (imei_id)   │    │
│  └──────────────┘  └──────────────┘  └──────────────┘  └──────────────┘    │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 关键概念

| 概念 | 说明 |
|------|------|
| **IMEI** | 用户设备唯一标识，用于识别用户身份 |
| **IMEI_ID** | TopoClaw 节点标识（如 `000`, `001`），用于路由到不同的本地节点 |
| **Agent** | 自定义小助手，由用户创建，托管在 TopoClaw 节点上 |
| **Assistant** | 群聊中的自动回复助手，可以是默认助手或自定义 Agent |
| **Group** | 群组，包含多个成员和多个助手 |
| **Thread** | 对话线程，用于维持上下文 |

---

## 2. 端侧设备与中继服务通信

### 2.1 连接方式

端侧设备通过 **WebSocket** 或 **HTTP** 与服务通信：

#### 2.1.1 WebSocket 连接（实时通信）

| 端点 | 用途 | 查询参数 |
|------|------|----------|
| `/ws/customer-service/{imei}` | 多端实时聊天通道 | `?device=pc` 表示 PC 端 |
| `/ws` | Agent/助手 API 中转通道 | `?imei=xxx&imei_id=000` |
| `/ws/topomobile/{imei_id}` | TopoClaw 适配器连接 | 节点直接连接 |

#### 2.1.2 HTTP API（请求-响应）

所有业务 HTTP 接口以 `/api/` 为前缀，详见 [API_ENDPOINTS.md](./API_ENDPOINTS.md)。

### 2.2 端侧向中继服务发请求流程

```
┌─────────────┐          ┌──────────────────┐          ┌─────────────────┐
│   端侧设备   │          │  Customer Service │          │  TopoClaw 节点  │
└──────┬──────┘          └────────┬─────────┘          └────────┬────────┘
       │                          │                             │
       │ ① 建立 WebSocket         │                             │
       │ /ws?imei=xxx&imei_id=000 │                             │
       ├─────────────────────────▶│                             │
       │                          │                             │
       │ ② 发送 register 帧       │                             │
       │ {                        │                             │
       │   "type": "register",    │                             │
       │   "device_id": "...",    │                             │
       │   "imei": "xxx",         │                             │
       │   "imei_id": "000"       │                             │
       │ }                        │                             │
       ├─────────────────────────▶│                             │
       │                          │                             │
       │                          │ ③ 绑定路由                   │
       │                          ├─────────────────────────────▶│
       │                          │ (imei → imei_id 映射)        │
       │                          │                             │
       │ ④ registered 确认        │                             │
       │◀─────────────────────────┤                             │
       │                          │                             │
       │ ⑤ 发送业务请求 (chat)    │                             │
       │ {                        │                             │
       │   "type": "chat",        │                             │
       │   "agent_id": "agent-a", │                             │
       │   "thread_id": "t-1",    │                             │
       │   "message": "你好"      │                             │
       │ }                        │                             │
       ├─────────────────────────▶│                             │
       │                          │                             │
       │                          │ ⑥ 转发到 TopoClaw            │
       │                          │ /ws/topomobile/{imei_id}     │
       │                          ├─────────────────────────────▶│
       │                          │                             │
       │                          │ ⑦ 流式返回 delta/done/error │
       │                          │◀─────────────────────────────┤
       │                          │                             │
       │ ⑧ 转发响应                │                             │
       │ {                        │                             │
       │   "type": "delta",       │                             │
       │   "content": "..."       │                             │
       │ }                        │                             │
       │◀─────────────────────────┤                             │
       │                          │                             │
```

### 2.3 中继服务响应类型

端侧发送请求后，可能收到以下响应：

#### 2.3.1 流式响应（Chat 请求）

| 类型 | 格式 | 说明 |
|------|------|------|
| `delta` | `{"type":"delta","content":"部分回复"}` | 流式内容块 |
| `done` | `{"type":"done","response":"最终回复"}` | 完成标记 |
| `error` | `{"type":"error","error":"错误信息"}` | 错误响应 |
| `stopped` | `{"type":"stopped"}` | 手动停止 |

#### 2.3.2 普通响应

| 类型 | 格式 | 说明 |
|------|------|------|
| `agent_created` | `{"type":"agent_created","ok":true,"agent_id":"xxx"}` | Agent 创建成功 |
| `agent_deleted` | `{"type":"agent_deleted","ok":true}` | Agent 删除成功 |
| `pong` | `{"type":"pong"}` | 心跳响应 |
| `error` | `{"type":"error","error":"..."}` | 通用错误 |

---

## 3. 单聊业务时序

### 3.1 好友单聊流程

```
用户 A (IMEI-A)                    Customer Service                    用户 B (IMEI-B)
     │                                   │                                   │
     │ ① 添加好友                       │                                   │
     │ POST /api/friends/add            │                                   │
     │ {imei: "A", friendImei: "B"}     │                                   │
     ├──────────────────────────────────▶│                                   │
     │                                   │                                   │
     │ ② 确认好友关系                    │                                   │
     │◀──────────────────────────────────┤                                   │
     │                                   │                                   │
     │ ③ 建立 WebSocket                 │                                   │
     │ /ws/customer-service/A           │                                   │
     ├──────────────────────────────────▶│                                   │
     │                                   │                                   │
     │                                   │ ④ 对方建立 WebSocket              │
     │                                   │ /ws/customer-service/B            │
     │                                   │◀──────────────────────────────────┤
     │                                   │                                   │
     │ ⑤ 发送好友消息                    │                                   │
     │ {                                │                                   │
     │   "type": "friend_message",      │                                   │
     │   "targetImei": "B",             │                                   │
     │   "content": "你好"              │                                   │
     │ }                                │                                   │
     ├──────────────────────────────────▶│                                   │
     │                                   │                                   │
     │                                   │ ⑥ 验证好友关系                    │
     │                                   │ (检查 A 和 B 是否为好友)           │
     │                                   │                                   │
     │                                   │ ⑦ 转发消息给 B                    │
     │                                   ├──────────────────────────────────▶
     │                                   │                                   │
     │                                   │ ⑧ 返回发送确认                    │
     │ {                                │◀──────────────────────────────────┤
     │   "type": "friend_message_ack",  │                                   │
     │   "message_id": "...",           │                                   │
     │   "target_online": true          │                                   │
     │ }                                │                                   │
     │◀──────────────────────────────────┤                                   │
     │                                   │                                   │
     │                                   │ ⑨ 多端同步 (PC 在线时)            │
     │ {                                ├──────────────────────────────────▶
     │   "type": "friend_sync_message", │                                   │
     │   "conversation_id": "friend_A", │                                   │
     │   "is_from_me": false            │                                   │
     │ }                                │                                   │
     │                                   │                                   │
```

### 3.2 单聊消息存储

单聊消息保存在 `outputs/unified_messages/friend_{friend_imei}.json`：

```json
{
  "messages": [
    {
      "id": "msg-uuid",
      "type": "user",
      "sender": "我",
      "content": "你好",
      "timestamp": "2024-01-15T10:30:00",
      "message_type": "text"
    },
    {
      "id": "msg-uuid-2",
      "type": "friend",
      "sender": "好友昵称",
      "content": "你好呀",
      "timestamp": "2024-01-15T10:30:05",
      "message_type": "text"
    }
  ]
}
```

### 3.3 离线消息处理

当接收方不在线时：

1. 消息存入离线队列（内存）
2. 同时持久化到统一消息存储
3. 接收方上线后，通过 `offline_messages` 帧推送

```json
{
  "type": "offline_messages",
  "messages": [...],
  "count": 5
}
```

---

## 4. 群聊业务时序

### 4.1 群组创建与管理

```
创建者                               Customer Service
   │                                        │
   │ ① 创建群组                            │
   │ POST /api/groups/create               │
   │ {                                     │
   │   "imei": "creator",                  │
   │   "name": "测试群",                   │
   │   "memberImeis": ["B", "C"],          │
   │   "assistantEnabled": true            │
   │ }                                     │
   ├───────────────────────────────────────▶│
   │                                        │
   │ ② 创建成功                            │
   │ { "groupId": "group_xxx" }            │
   │◀───────────────────────────────────────┤
   │                                        │
   │ ③ 添加自定义 Agent 到群组              │
   │ POST /api/groups/add-assistant        │
   │ {                                     │
   │   "groupId": "group_xxx",             │
   │   "imei": "creator",                  │
   │   "assistantId": "salesking",         │
   │   "name": "销售助手"                  │
   │ }                                     │
   ├───────────────────────────────────────▶│
   │                                        │
   │ ④ 确认添加                            │
   │◀───────────────────────────────────────┤
   │                                        │
```

### 4.2 群聊消息广播

```
用户 A                                Customer Service                         用户 B/C
   │                                        │                                      │
   │ ① 发送群消息                          │                                      │
   │ {                                     │                                      │
   │   "type": "group_message",            │                                      │
   │   "groupId": "group_xxx",             │                                      │
   │   "content": "大家好"                 │                                      │
   │ }                                     │                                      │
   ├───────────────────────────────────────▶│                                      │
   │                                        │                                      │
   │                                        │ ② 验证成员身份                       │
   │                                        │ (检查 A 是否在群组中)                 │
   │                                        │                                      │
   │                                        │ ③ 保存群消息                         │
   │                                        │ (outputs/groups/...)                 │
   │                                        │                                      │
   │                                        │ ④ 广播给所有成员                     │
   │                                        ├──────────────────────────────────────▶
   │                                        │                                      │
   │                                        │ ⑤ 广播给群内所有 Agent               │
   │                                        │ (每个 Agent 独立处理消息)             │
   │                                        │                                      │
   │                                        │ ⑥ Agent 回复（如有）                 │
   │                                        │◀─────────────────────────────────────┤
   │                                        │ (来自 Agent 的回复)                   │
   │                                        │                                      │
   │ ⑦ 收到群消息                          │                                      │
   │ {                                     │                                      │
   │   "type": "group_message",            │                                      │
   │   "groupId": "group_xxx",             │                                      │
   │   "senderImei": "A",                  │                                      │
   │   "content": "大家好"                 │                                      │
   │ }                                     │                                      │
   │◀───────────────────────────────────────┤                                      │
   │                                        │                                      │
```

### 4.3 群聊数据结构

群组存储在 `outputs/groups/groups_storage.json`：

```json
{
  "group_xxx": {
    "group_id": "group_xxx",
    "name": "测试群",
    "creator_imei": "user-a-imei",
    "members": ["user-a-imei", "user-b-imei", "user-c-imei"],
    "assistants": ["assistant", "salesking", "agent-custom"],
    "assistant_configs": {
      "salesking": {
        "name": "销售助手",
        "baseUrl": "topoclaw://relay",
        "capabilities": ["chat"]
      }
    },
    "created_at": "2024-01-15T10:00:00"
  }
}
```

---

## 5. 群内 Agent 处理业务时序

### 5.1 @助手触发回复流程

```
用户 A                                Customer Service                         TopoClaw 节点
   │                                        │                                      │
   │ ① 发送 @助手消息                      │                                      │
   │ {                                     │                                      │
   │   "type": "group_message",            │                                      │
   │   "groupId": "group_xxx",             │                                      │
   │   "content": "@salesking 查询订单"    │                                      │
   │ }                                     │                                      │
   ├───────────────────────────────────────▶│                                      │
   │                                        │                                      │
   │                                        │ ② 提取 @的助手                       │
   │                                        │ mentioned = "salesking"              │
   │                                        │                                      │
   │                                        │ ③ 只向 salesking 发送消息            │
   │                                        ├──────────────────────────────────────▶
   │                                        │ {                                    │
   │                                        │   "type": "chat",                   │
   │                                        │   "agent_id": "salesking",          │
   │                                        │   "thread_id": "group_xxx:salesking"
   │                                        │   "message": "查询订单"              │
   │                                        │ }                                    │
   │                                        │                                      │
   │                                        │ ④ 等待 Agent 回复                    │
   │                                        │◀──────────────────────────────────────
   │                                        │ {                                    │
   │                                        │   "type": "delta"/"done"            │
   │                                        │   "content": "订单状态是..."         │
   │                                        │ }                                    │
   │                                        │                                      │
   │                                        │ ⑤ 转发回复给所有群成员               │
   │ {                                     ├──────────────────────────────────────▶
   │   "type": "group_message",            │ (其他成员收到)                        │
   │   "sender": "销售助手",               │                                      │
   │   "content": "订单状态是...",         │                                      │
   │   "is_assistant_reply": true          │                                      │
   │ }                                     │                                      │
   │◀───────────────────────────────────────┤                                      │
   │                                        │                                      │
```

### 5.2 广播消息处理（无明确 @）

```
用户 A                                Customer Service                         Agent-1/2/3
   │                                        │                                      │
   │ ① 发送普通群消息                      │                                      │
   │ "大家好，有个问题"                    │                                      │
   ├───────────────────────────────────────▶│                                      │
   │                                        │                                      │
   │                                        │ ② 未提取到明确 @，广播给所有 Agent   │
   │                                        │                                      │
   │                                        │ ③ 并行向所有 Agent 发送              │
   │                                        ├──────────────────────────────────────▶
   │                                        ├──────────────────────────────────────▶
   │                                        ├──────────────────────────────────────▶
   │                                        │                                      │
   │                                        │ ④ 并行等待所有 Agent 回复            │
   │                                        │◀──────────────────────────────────────
   │                                        │◀──────────────────────────────────────
   │                                        │◀──────────────────────────────────────
   │                                        │                                      │
   │                                        │ ⑤ 将各 Agent 回复转发到群聊          │
   │ {                                     ├──────────────────────────────────────▶
   │   "sender": "Agent-1",                │ (所有成员收到所有回复)                │
   │   "content": "回复1"                  │                                      │
   │ }                                     │                                      │
   │ {                                     │                                      │
   │   "sender": "Agent-2",                │                                      │
   │   "content": "回复2"                  │                                      │
   │ }                                     │                                      │
   │◀───────────────────────────────────────┤                                      │
   │                                        │                                      │
```

### 5.3 Agent 回复处理对比

| 场景 | 消息类型 | 目标 Agent | 回复处理 |
|------|----------|------------|----------|
| 明确 @Agent | `chat` | 指定 Agent | 等待并转发回复 |
| 广播（无 @） | `chat` | 所有 Agent | 并行等待，分别转发 |
| 传统 @小助手 | `chat` | 默认 assistant | 等待并转发回复 |

---

## 6. 跨用户跨 Agent 通信

### 6.1 跨设备消息（端云）

用户可以在手机和 PC 之间同步消息：

```
手机端                               Customer Service                         PC端
   │                                        │                                      │
   │ ① 发送端云消息                        │                                      │
   │ {                                     │                                      │
   │   "type": "cross_device_message",     │                                      │
   │   "content": "从手机发来的消息"       │                                      │
   │ }                                     │                                      │
   ├───────────────────────────────────────▶│                                      │
   │                                        │                                      │
   │                                        │ ② 存储到端云消息                     │
   │                                        │ (outputs/multi_device/...)           │
   │                                        │                                      │
   │                                        │ ③ 推送到 PC（在线时）                │
   │                                        ├──────────────────────────────────────▶
   │                                        │ {                                    │
   │                                        │   "type": "cross_device_message",   │
   │                                        │   "from_device": "mobile"           │
   │                                        │ }                                    │
   │                                        │                                      │
   │ ④ 确认发送成功                        │                                      │
   │◀───────────────────────────────────────┤                                      │
   │                                        │                                      │
```

### 6.2 跨用户 Agent 调用

用户 A 可以调用用户 B 创建的 Agent（如果 B 的 Agent 被添加到共享群组）：

```
用户 A                               Customer Service                         用户 B的Agent
   │                                        │                                      │
   │ ① A 在群里 @B的Agent                  │                                      │
   │ "@B-agent 帮我分析"                   │                                      │
   ├───────────────────────────────────────▶│                                      │
   │                                        │                                      │
   │                                        │ ② 提取 Agent ID = "B-agent"          │
   │                                        │                                      │
   │                                        │ ③ 确定路由（谁的节点）               │
   │                                        │ resolve_user_adapter_route(A)        │
   │                                        │ → 使用 A 的 imei_id 路由             │
   │                                        │                                      │
   │                                        │ ④ 转发到 A 连接的 TopoClaw 节点      │
   │                                        ├──────────────────────────────────────▶
   │                                        │ {                                    │
   │                                        │   "agent_id": "B-agent",            │
   │                                        │   "message": "帮我分析"              │
   │                                        │ }                                    │
   │                                        │                                      │
   │                                        │ ⑤ TopoClaw 节点处理                  │
   │                                        │ (本地查找或远程调用 B-agent)          │
   │                                        │                                      │
```

### 6.3 路由解析逻辑

```python
# 路由解析示例
def resolve_route(sender_imei: str) -> str:
    """解析发送者应该使用的 TopoClaw 节点"""
    # 1. 检查是否有显式绑定的 imei_id
    if has_bound_route(sender_imei):
        return get_bound_route(sender_imei)
    
    # 2. 返回默认节点 000
    return "000"
```

---

## 7. 接口定义

### 7.1 WebSocket 消息格式

#### 7.1.1 客户端 → 服务端

**Register 帧**
```json
{
  "type": "register",
  "thread_id": "",
  "device_id": "imei-mobile-mock",
  "device_type": "mobile",
  "imei": "user-a-imei",
  "imei_id": "000"
}
```

**Chat 请求**
```json
{
  "type": "chat",
  "request_id": "req-xxx",
  "agent_id": "agent-a-pc",
  "thread_id": "thread-a-pc-1",
  "message": "请给我今天的任务摘要",
  "images": []
}
```

**好友消息**
```json
{
  "type": "friend_message",
  "targetImei": "user-b-imei",
  "content": "你好",
  "message_type": "text",
  "message_id": "fm-xxx"
}
```

**群消息**
```json
{
  "type": "group_message",
  "groupId": "group_xxx",
  "content": "大家好",
  "message_type": "text"
}
```

#### 7.1.2 服务端 → 客户端

**注册确认**
```json
{
  "type": "registered",
  "thread_id": ""
}
```

**流式响应 - Delta**
```json
{
  "type": "delta",
  "request_id": "req-xxx",
  "content": "部分回复内容"
}
```

**流式响应 - Done**
```json
{
  "type": "done",
  "request_id": "req-xxx",
  "response": "最终回复"
}
```

**好友消息确认**
```json
{
  "type": "friend_message_ack",
  "success": true,
  "message_id": "fm-xxx",
  "target_online": true
}
```

**离线消息推送**
```json
{
  "type": "offline_messages",
  "messages": [...],
  "count": 5
}
```

### 7.2 HTTP API 关键接口

#### 7.2.1 好友管理

| 接口 | 方法 | 请求体 | 响应 |
|------|------|--------|------|
| `/api/friends/add` | POST | `{"imei": "xxx", "friendImei": "yyy"}` | `{"success": true}` |
| `/api/friends/list` | GET | - | `{"friends": [{"imei": "yyy", "nickname": "..."}]}` |
| `/api/friends/send-message` | POST | `{"imei": "xxx", "friendImei": "yyy", "content": "..."}` | `{"success": true, "messageId": "..."}` |

#### 7.2.2 群组管理

| 接口 | 方法 | 请求体 | 响应 |
|------|------|--------|------|
| `/api/groups/create` | POST | `{"imei": "xxx", "name": "群名", "memberImeis": [...], "assistantEnabled": true}` | `{"groupId": "group_xxx"}` |
| `/api/groups/list` | GET | - | `{"groups": [...]}` |
| `/api/groups/add-assistant` | POST | `{"groupId": "xxx", "imei": "xxx", "assistantId": "agent-1", "name": "..."}` | `{"success": true}` |
| `/api/groups/{group_id}` | GET | - | `{"group": {...}}` |

#### 7.2.3 Agent 管理

| 接口 | 方法 | 请求体 | 响应 |
|------|------|--------|------|
| `/api/custom-assistants` | GET | - | `{"assistants": [{"id": "agent-1", "name": "..."}]}` |
| `/api/custom-assistants` | POST | `{"imei": "xxx", "assistants": [...]}` | `{"success": true}` |

### 7.3 消息类型总览

| 消息类型 | 方向 | 用途 |
|----------|------|------|
| `register` | C→S | 注册连接 |
| `registered` | S→C | 注册确认 |
| `ping/pong` | 双向 | 心跳 |
| `chat` | C→S | Agent 对话请求 |
| `delta` | S→C | 流式内容 |
| `done` | S→C | 流式完成 |
| `error` | S→C | 错误响应 |
| `create_agent` | C→S | 创建 Agent |
| `delete_agent` | C→S | 删除 Agent |
| `friend_message` | C→S | 发送好友消息 |
| `friend_sync_message` | S→C | 好友消息同步 |
| `friend_message_ack` | S→C | 好友消息确认 |
| `group_message` | 双向 | 群消息 |
| `cross_device_message` | 双向 | 端云消息 |
| `offline_messages` | S→C | 离线消息推送 |

---

## 附录：术语表

| 术语 | 英文 | 说明 |
|------|------|------|
| 端侧设备 | Client Device | 手机、PC 等用户终端 |
| 中继服务 | Relay Service | 转发客户端请求到 TopoClaw 的服务 |
| TopoClaw 节点 | TopoClaw Node | 本地运行的 Agent 服务节点 |
| Agent | Agent | 自定义小助手 |
| Assistant | Assistant | 群聊自动回复助手 |
| IMEI | IMEI | 用户设备唯一标识 |
| IMEI_ID | IMEI ID | TopoClaw 节点标识 |
| 端云 | Cross-Device | 手机与 PC 之间的消息同步 |

---

## 相关文档

- [API_ENDPOINTS.md](./API_ENDPOINTS.md) - HTTP API 详细列表
- [README.md](./README.md) - 项目概述和安装说明
