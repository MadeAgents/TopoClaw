# Interactive Task Minimal Routing

## Goal

Keep `browser-use` interactive tasks working without broad changes to the main websocket/chat modules.

## What stays in the interactive layer

- `spawn_interactive_task` uses the resolved `session_key` from `AgentLoop`.
- Interactive tasks keep their original `channel`, `chat_id`, `session_key`, and task metadata.
- `TaskSupervisor` remains the owner of interactive task lifecycle:
  - `spawn`
  - `resume`
  - `cancel`
  - `close`
- `AgentLoop` records task events into session history.
- When a task needs human help, `AgentLoop` writes an `assistant` message into the session history and emits one outbound websocket message.

## What is intentionally not required anymore

- No websocket-direct push by stored `conn_id`
- No extra websocket registry metadata patching for interactive tasks
- No dependency on parsing session key back into routing targets
- No extra thread/session normalization logic added only for interactive tasks

## Live push model

Human assistance push now relies on the existing websocket thread routing path:

1. websocket chat turn already carries `thread_id`
2. existing connection service binds `thread_id` to the websocket/device path
3. interactive task stores `chat_id = original thread_id`
4. `AgentLoop` publishes outbound message with:
   - `channel = "websocket"`
   - `chat_id = task.chat_id`
   - `_interactive = true`
5. `ChannelManager` sends `assistant_push` by `thread_id`

This keeps interactive routing aligned with the original websocket push model instead of creating a second routing mechanism.

## Minimal cross-module contract

Interactive code assumes only these existing behaviors from the main modules:

- websocket chat requests include a stable `thread_id`
- websocket chat metadata passed into `AgentLoop` includes the current `thread_id` and `agent_id`
- connection service can already push by thread

## Why this is safer

- Fewer changes in `chat_service.py`
- Fewer changes in `connection_app_service.py`
- Lower risk of breaking normal websocket chat, cron push, or other channels
- Interactive behavior stays mostly inside:
  - `agent/loop.py`
  - `agent/tools/spawn_interactive.py`
  - `agent/tools/task_control.py`
  - `agent/task_supervisor.py`
  - `agent/executors/browser_use_executor.py`
