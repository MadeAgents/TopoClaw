# Relay Service (customer_service)

Identical in API and behavior to `../customer_service`, with code split by functional module for easier maintenance.

## API Reference

See **[API_ENDPOINTS.md](./API_ENDPOINTS.md)** for the full HTTP / WebSocket path table (maintained alongside `/docs`).

## Directory Overview

| Directory / File | Description |
|------------------|-------------|
| `app.py` | FastAPI entry point: mounts routes and WebSocket |
| `core/` | Global dependencies, time utilities, `output_paths.py` (`outputs/` directory layout) |
| `storage/` | In-process state and JSON persistence (bindings, device-cloud messages, friends, profiles, version reads) |
| `schemas/` | Pydantic request/response models |
| `api/` | HTTP routes (split by domain) and `websocket_customer.py` |
| `services/` | Business services (same as original) |
| `websocket/` | WebSocket connection management (same as original) |
| `storage_browser/` | Storage browser static page (`index.html`) |
| `storage_browser_server.py` | Read-only HTTP server for browsing `outputs/` (default port 8765, independent of FastAPI) |

### Route Module Mapping

- **Binding**: `api/routes_binding.py`
- **Customer service + health / online / test / full chat**: `api/routes_customer.py`
- **Multi-device (device-cloud, unified messages, sessions)**: `api/routes_cross_device.py`
- **Custom assistants**: `api/routes_assistants.py`
- **Marketplace**: `api/routes_plaza.py`
- **Friends**: `api/routes_friends.py`
- **Groups**: `api/routes_groups.py`
- **User profiles**: `api/routes_profile.py`
- **Version check**: `api/routes_version.py`
- **WebSocket**: `api/websocket_customer.py` (`register_websocket`)

## Installation & Running

```bash
cd customer_service2
pip install -r requirements.txt
python app.py
```

Or with uvicorn:

```bash
uvicorn app:app --host 0.0.0.0 --port 8001 --ws wsproto
```

### Storage Browser (optional)

Run from the **`customer_service2`** directory:

```bash
python storage_browser_server.py
```

Visit the URL shown in the terminal at `http://127.0.0.1:8765/` (supports `/v6/` prefix and gateway deployment — see inline comments in the script). This server is **read-only** for `outputs/`, runs on a different port from `app.py`, and must be started separately.

The **IMEI dropdown** in the top-left corner of the storage page links to aggregated data: selecting an IMEI calls `GET /api/search-imei?imei=...`, listing relevant fragments across the entire store; selecting "All Files" restores the directory tree. The endpoint extracts JSON subsets from profiles, friends, groups, device-cloud messages, unified messages (by conversation), sessions, custom assistants, marketplace, and customer-service JSONL files (large lists are truncated with a `_truncated` flag).

The **Recent 1-Hour Console Log** button in the toolbar calls `GET /api/recent-terminal-logs?hours=1` (`hours` is adjustable within back-end limits), reading `outputs/server_logs/app_terminal.log*` (written by `python app.py` in sync with the terminal, ~20 MB per file with 5 rotated backups), parsing lines from the past hour in newest-first order (with row limits and tail-read caps; the response includes `truncated` and `logRelPath`). If you start via `uvicorn app:app` without loading the same `log_config`, this directory may be empty — use `python app.py` or attach the same log-file handler in your startup configuration.

## Data Directory `outputs/`

All persisted data is written under **`customer_service2/outputs/`**, organized by feature (matching `core/output_paths.py`):

| Subdirectory | Contents |
|--------------|----------|
| `outputs/multi_device/` | Device-cloud messages `cross_device_messages.json` |
| `outputs/friends/` | Friend relationships `friends_storage.json` |
| `outputs/profiles/` | User profiles `profiles_storage.json` |
| `outputs/unified_messages/` | Unified conversation messages `unified_messages.json` |
| `outputs/groups/` | Groups `groups_storage.json`, `user_groups.json` |
| `outputs/sessions/` | Multi-session sync `unified_sessions.json` |
| `outputs/custom_assistants/` | Custom assistant list `custom_assistants.json` |
| `outputs/plaza/` | Marketplace `plaza_assistants.json` |
| `outputs/customer_service/` | Customer service conversation logs `*.jsonl` |
| `outputs/server_logs/` | Console mirror log `app_terminal.log` (for the storage page "Recent 1-Hour Console Log") |
| `outputs/version/` | Runtime `version_info.json` (initially copied from the package root `version_info.json`) |

These directories are **auto-created** on startup. If legacy JSON files exist in the package root, they are **auto-migrated** to the corresponding subdirectories (only when the target path does not yet exist).

Binding tokens remain in-memory only and are not persisted. Offline message queues are also in-memory.

For more API details, see the original `../customer_service/README.md` (paths still apply).

## Mock Clients

Static pages for integration testing with `customer_service`:

- `mock_clients/a_mobile.html` — User A mobile
- `mock_clients/a_pc.html` — User A desktop
- `mock_clients/b_mobile.html` — User B mobile
- `mock_clients/b_pc.html` — User B desktop
- `mock_clients/dashboard_6_clients.html` — Six-device overview (A mobile / A desktop / B mobile / B desktop / C mobile / D mobile)

Key capabilities:

- Connect to Chat WS: `/ws/customer-service/{imei}`
- Connect to Agent WS: `/ws` (bridge mode; `imei_id` must match the local TopoClaw `channels.topomobile.nodeId`)
- Friends, DMs, groups, group messages, Agent creation and conversations
- Group assistant invitation and `@assistant` directed replies
- Six-device simultaneous debugging with default friend relationships pre-initialized

Recommended startup (from `customer_service/mock_clients`):

```bash
python -m http.server 8088
```

Then visit:

- `http://127.0.0.1:8088/a_mobile.html`
- `http://127.0.0.1:8088/a_pc.html`
- `http://127.0.0.1:8088/b_mobile.html`
- `http://127.0.0.1:8088/b_pc.html`
- `http://127.0.0.1:8088/dashboard_6_clients.html`

Default `HTTP Base = http://127.0.0.1:8001`. Adjust manually on the page if the relay address differs.
