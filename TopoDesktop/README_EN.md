# TopoClaw

A desktop chat client that syncs with the mobile app. Supports binding via IMEI input or QR code scanning, sharing the same chat history with the mobile side.

## Features

- **Binding**: IMEI input / QR code binding (PC generates the QR code, phone scans it)
- **Chat**: Send messages, receive AI replies, synced with the mobile side
- **No execution**: The PC side only handles chat — it does not perform screen operations

## Development

Daily development commands can run in **Windows PowerShell**; desktop packaging is recommended in **Windows CMD** (path: `TopoDesktop/`).

```powershell
npm install
```

### Built-in Service Resources (required after first clone or submodule update)

The desktop embeds **TopoClaw** and the **relay service** (`customer_service`). Sync source into the tree and install dependencies under `resources/python-embed`:

| Script | Purpose |
|--------|---------|
| `npm run setup:assistant` | Sync `TopoClaw/topoclaw` (backward-compatible with legacy `nanobot`) to `resources/TopoClaw` |
| `npm run setup:customer-service` | Sync repo-root `customer_service/` to `resources/customer-service` (same API as root) |
| `npm run setup:python` | Download/verify the embedded Python and `pip install` dependencies for TopoClaw and the relay service |

Run all three in one step (recommended):

```powershell
npm run setup:builtin
```

### Starting the Dev Environment

```powershell
npm run electron:dev
```

Note: `electron:dev` does **not** run `setup:builtin` automatically. If `resources/TopoClaw`, `resources/customer-service`, or their dependencies are missing, run `npm run setup:builtin` first.

Web-only development (no Electron, no embedded Python):

```powershell
npm run dev
```

## Packaging (Windows CMD, choose one)

### Path A: one-click script (recommended)

```cmd
build-desktop-core-plus-browser.cmd
```

### Path B: manual step-by-step (equivalent to the script)

```cmd
npm install
npm run setup:assistant
npm run setup:customer-service
npm run setup:python
cd resources\TopoClaw
..\python-embed\python.exe -m pip install browser-use==0.12.0 --no-deps --prefer-binary
cd ..\..
npm run round-icon
npm run licenses:generate
npx tsc -p tsconfig.electron.json
npx vite build
npx electron-builder --config electron-builder.config.cjs
```

Output goes to the `release/` directory.

## Icons

- App icon: `apk5/Image_20251124184821.png`, copied to `public/icon.png`
- Assistant avatars: Matching the mobile-side drawables, located at `public/avatars/` (ic_assistant_avatar.png, ic_skill_learning_avatar.png, ic_customer_service_avatar.png)

## Server

By default reads `VITE_MOBILE_AGENT_BASE_URL` from `TopoDesktop/.env.local`.

## Built-in Resource Details (merged from `resources/*/README.md`)

### `resources/TopoClaw`

Built-in `topoclaw` runtime resources. Synced from repo source via `npm run setup:assistant`, invoked by the desktop embedded Python environment.

### `resources/customer-service`

Embedded relay service: same HTTP/WebSocket surface as repo-root `customer_service/` (modular copy). Synced via `npm run setup:customer-service`. See `README.md` and `API_ENDPOINTS.md` in that directory.

Example run (after sync):

```bash
cd resources/customer-service
pip install -r requirements.txt
python app.py
```

### `resources/python-embed`

Stores the Windows Python Embeddable Package for desktop execution capabilities.

- Recommended: run `npm run setup:python` to auto-download/verify/install dependencies
- Packaging copies this directory into the installer's `resources`
- For manual setup, ensure `python.exe`, `python3xx._pth`, `Lib/`, etc. exist in this directory
