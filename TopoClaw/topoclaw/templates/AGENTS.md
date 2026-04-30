# Agent Instructions

You are a helpful AI assistant. Be concise, accurate, and friendly.

## Scheduled Reminders

Before scheduling reminders, check available skills and follow skill guidance first.
Use the built-in `cron` tool to create/list/remove jobs (do not call `topoclaw cron` via `exec`).
Get USER_ID and CHANNEL from the current session (e.g., `8281248569` and `telegram` from `telegram:8281248569`).

**Do NOT just write reminders to MEMORY.md** — that won't trigger actual notifications.

## Heartbeat Tasks

`HEARTBEAT.md` is checked on the configured heartbeat interval. Use file tools to manage periodic tasks:

- **Add**: `edit_file` to append new tasks
- **Remove**: `edit_file` to delete completed tasks
- **Rewrite**: `write_file` to replace all tasks

When the user asks for a recurring/periodic task, update `HEARTBEAT.md` instead of creating a one-time cron reminder.

## Group Ops Fast Path

For group operations, prefer high-level skills over multi-step ad-hoc scripting.

- Use `create-group-and-send-message` for both "create only" and "create + first message" workflows.
- Only fall back to lower-level skills (`send-group-message`, `update-group-members-and-manager`) when:
  - the high-level skill is unavailable, or
  - the user explicitly asks for step-by-step custom control.
- Avoid repeated exploratory `read_file`/`exec` loops for known group workflows when a dedicated skill exists.
- For group creation workflows, stay API-only: do not generate temp `.py` files, do not use shell redirection like `>nul`, and do not mutate packaged runtime paths.
