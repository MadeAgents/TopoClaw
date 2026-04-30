---
name: memory
description: Layered workspace memory — facts, structured event/focus, and grepable history.
always: true
---

# Memory

## Structure

| File | Role | In system prompt |
|------|------|------------------|
| `memory/MEMORY.md` | Long-term facts (preferences, project context, relationships). | **Yes** — always loaded when non-empty. |
| `memory/EVENT_MEMORY.md` | Structured **event / focus memory** (显性/隐性关注点、时间线、演化). | **Yes** — always loaded when non-empty (same pipeline as `MEMORY.md`). |
| `memory/HISTORY.md` | Append-only event/decision log. | **No** — search on demand (grep / `exec`). Each entry should start with `[YYYY-MM-DD HH:MM]`. |

- **Bootstrap EVENT_MEMORY**: copy the template from `templates/memory/EVENT_MEMORY.md` into `memory/EVENT_MEMORY.md` when the user (or you) wants structured tracking of *what the user is focused on* across sessions — not only raw facts in `MEMORY.md`.

### EVENT_MEMORY.md (when used)

Follow the template sections:

1. **Current focus** — Explicit vs implicit user goals; update only when aligned with the current thread (关注ID `CF-*`).
2. **Historical focus log** — Append-only; **recent** vs **archived** early focuses; shorten old items over time; merge many small focuses into one line when the list grows.
3. **Focus evolution tracker** — Short changelog: 新增 / 深化 / 转移 / 消退 / 撤销 (use the diff-style block in the template).

- **`compress_session` tool**: summarizes the unconsolidated chat tail and replaces it in the session JSONL only — it does **not** write **MEMORY.md** or **HISTORY.md**.
- **Token-based archival** (when the prompt exceeds the context window): still archives chunks into **MEMORY.md** and **HISTORY.md** via the internal `save_memory` pipeline. It does **not** fill `EVENT_MEMORY.md`. You maintain `EVENT_MEMORY.md` with `edit_file` / `write_file` when the skill applies.

## Search past content

`HISTORY.md` is not injected into the prompt; use search when it grows.

Choose the method by file size:

- Small files: `read_file`, then filter in memory.
- Large or long-lived `HISTORY.md`: use `exec` for targeted search.

Examples:

- **Linux/macOS:** `grep -i "keyword" memory/HISTORY.md`
- **Windows:** `findstr /i "keyword" memory\HISTORY.md`
- **Cross-platform:** `python -c "from pathlib import Path; p=Path('memory/HISTORY.md'); ..."`

Prefer targeted CLI search for large logs. For `EVENT_MEMORY.md`, the model usually already sees it in context; use grep only if you must search a copy or an archived export.

## When to update what

**MEMORY.md** — Important durable facts as soon as they are stable:

- User preferences (“I prefer dark mode”)
- Project facts (“The API uses OAuth2”)
- Stable relationships (“Alice is the project lead”)
- Valuable execution experience

**HISTORY.md** — Session archival and grep-friendly narrative (often filled by consolidation). You normally do not hand-edit unless adding a manual note.

**EVENT_MEMORY.md** — When you need a *focus-centric* view (topic shifts, multi-session threads, explicit vs inferred intent):

- After a **topic pivot**, consider updating current focus and appending to the evolution tracker.
- When **early focuses** pile up, summarize per template (近期 → 早期/归档).
- Keep **§2** append-only except for explicit conflicts or staleness, as described in the template.

## Auto-consolidation

When the session grows large, **token-based archival** writes to **HISTORY.md** and **MEMORY.md**. The `compress_session` tool only trims the session log; use `memory_update` or `edit_file` if you want those files updated manually. You do not need to duplicate archival into `EVENT_MEMORY.md` unless you are maintaining the structured focus model.
