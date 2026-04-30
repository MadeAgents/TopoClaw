// Copyright 2025 OPPO

// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at

//     http://www.apache.org/licenses/LICENSE-2.0

// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import { app, BrowserWindow, Menu, Tray, nativeImage, ipcMain, globalShortcut, screen as electronScreen, clipboard, dialog, shell } from 'electron'
import path from 'path'
import fs from 'fs'
import os from 'os'
import net from 'net'
import https from 'https'
import http from 'http'
import { URL, fileURLToPath } from 'url'
import { spawn, execSync, execFile, type ChildProcess } from 'child_process'
let pty: typeof import('node-pty') | null = null
try {
  pty = require('node-pty') as typeof import('node-pty')
} catch (_) {
  console.warn('[terminal] node-pty 未可用，请安装 Visual Studio 生成工具后执行 npm install')
}
import sharp from 'sharp'
import {
  Region,
  screen,
  mouse,
  keyboard,
  straightTo,
  Point,
  Button,
  Key,
  saveImage,
} from '@nut-tree-fork/nut-js'
import type { Image } from '@nut-tree-fork/nut-js'

/** Computer Use 截图缩放比例，0.5 表示长宽各为原始的 50% */
const COMPUTER_USE_SCREENSHOT_SCALE = 0.5
const DEFAULT_GUI_API_BASE =
  (process.env.BUILTIN_GUI_API_BASE ?? process.env.VITE_BUILTIN_GUI_API_BASE ?? '').trim() ||
  'https://example.invalid/v1'

/** nut-js Image → 与 Computer Use 相同缩放后的 PNG base64 */
async function imageNutToScaledPngBase64(img: Image): Promise<
  | { ok: true; base64: string; width: number; height: number }
  | { ok: false; error: string }
> {
  try {
    const tempDir = app.getPath('temp')
    const tempPath = path.join(tempDir, `cu-screenshot-${Date.now()}.png`)
    await saveImage({ image: img, path: tempPath })
    const newW = Math.round(img.width * COMPUTER_USE_SCREENSHOT_SCALE)
    const newH = Math.round(img.height * COMPUTER_USE_SCREENSHOT_SCALE)
    const resizedBuf = await sharp(tempPath).resize(newW, newH).png().toBuffer()
    try { fs.unlinkSync(tempPath) } catch { /* ignore */ }
    const base64 = resizedBuf.toString('base64')
    return { ok: true, base64, width: newW, height: newH }
  } catch (e) {
    console.error('imageNutToScaledPngBase64', e)
    return { ok: false, error: String(e) }
  }
}

/** 全屏截图（与 Computer Use 同源），返回缩放后的 PNG base64 及尺寸 */
async function captureComputerUseScreenshotPngBase64(): Promise<
  | { ok: true; base64: string; width: number; height: number }
  | { ok: false; error: string }
> {
  try {
    const img = await screen.grab()
    return await imageNutToScaledPngBase64(img)
  } catch (e) {
    console.error('captureComputerUseScreenshotPngBase64', e)
    return { ok: false, error: String(e) }
  }
}

/** 选区为屏幕 DIP 坐标（与 MouseEvent.screenX/Y 一致）；转为 nut-js 截图像素坐标 */
function dipScreenRectToPhysicalRect(dip: { x: number; y: number; width: number; height: number }): {
  x: number
  y: number
  width: number
  height: number
} {
  if (process.platform === 'win32') {
    try {
      return electronScreen.dipToScreenRect(null, dip)
    } catch {
      /* fall through */
    }
  }
  const cx = dip.x + dip.width / 2
  const cy = dip.y + dip.height / 2
  const display = electronScreen.getDisplayNearestPoint({ x: cx, y: cy })
  const sf = display.scaleFactor || 1
  return {
    x: Math.round(dip.x * sf),
    y: Math.round(dip.y * sf),
    width: Math.round(dip.width * sf),
    height: Math.round(dip.height * sf),
  }
}

/** 通过剪贴板 + 粘贴快捷键 输入文本，支持中文等 Unicode（nut-js keyboard.type 对中文无效） */
async function pasteText(text: string): Promise<void> {
  const prev = clipboard.readText()
  clipboard.writeText(text)
  try {
    if (process.platform === 'darwin') {
      await keyboard.type(Key.LeftCmd, Key.V)
    } else {
      await keyboard.type(Key.LeftControl, Key.V)
    }
    // 延迟恢复剪贴板，确保目标应用已读取并完成粘贴（否则会粘贴到旧内容）
    await new Promise((r) => setTimeout(r, 80))
  } finally {
    clipboard.writeText(prev)
  }
}

/** 将上传图片坐标系映射到物理屏幕：服务端返回的是缩放后图片空间坐标 */
function imageCoordsToScreen(x: number, y: number): { x: number; y: number } {
  const scale = 1 / COMPUTER_USE_SCREENSHOT_SCALE
  return { x: Math.round(x * scale), y: Math.round(y * scale) }
}

/** KEY[keyname] 映射到 nut-js Key。服务端 raw.upper() 后解析，故支持 Enter/ENTER、Ctrl+C 等 */
const KEY_NAME_MAP: Record<string, Key> = {
  ENTER: Key.Enter, RETURN: Key.Enter,
  TAB: Key.Tab,
  ESCAPE: Key.Escape, ESC: Key.Escape,
  SPACE: Key.Space,
  BACKSPACE: Key.Backspace,
  DELETE: Key.Delete, INSERT: Key.Insert,
  HOME: Key.Home, END: Key.End,
  PAGEUP: Key.PageUp, PAGEDOWN: Key.PageDown,
  UP: Key.Up, DOWN: Key.Down, LEFT: Key.Left, RIGHT: Key.Right,
  F1: Key.F1, F2: Key.F2, F3: Key.F3, F4: Key.F4, F5: Key.F5,
  F6: Key.F6, F7: Key.F7, F8: Key.F8, F9: Key.F9, F10: Key.F10,
  F11: Key.F11, F12: Key.F12,
  CTRL: Key.LeftControl, CONTROL: Key.LeftControl,
  ALT: Key.LeftAlt,
  SHIFT: Key.LeftShift,
  WIN: Key.LeftSuper, WINDOWS: Key.LeftSuper, META: Key.LeftSuper,
  CMD: Key.LeftCmd,
}
const LETTER_KEYS: Record<string, Key> = {
  A: Key.A, B: Key.B, C: Key.C, D: Key.D, E: Key.E, F: Key.F,
  G: Key.G, H: Key.H, I: Key.I, J: Key.J, K: Key.K, L: Key.L,
  M: Key.M, N: Key.N, O: Key.O, P: Key.P, Q: Key.Q, R: Key.R,
  S: Key.S, T: Key.T, U: Key.U, V: Key.V, W: Key.W, X: Key.X,
  Y: Key.Y, Z: Key.Z,
}
const DIGIT_KEYS: Record<string, Key> = {
  '0': Key.Num0, '1': Key.Num1, '2': Key.Num2, '3': Key.Num3, '4': Key.Num4,
  '5': Key.Num5, '6': Key.Num6, '7': Key.Num7, '8': Key.Num8, '9': Key.Num9,
}

function parseKeyCombo(keyname: string): Key[] {
  const s = keyname.trim().replace(/\s+/g, '')
  if (!s) return []
  const parts = s.split('+').map((p) => p.trim())
  const keys: Key[] = []
  for (const part of parts) {
    const upper = part.toUpperCase()
    if (KEY_NAME_MAP[upper] != null) {
      keys.push(KEY_NAME_MAP[upper]!)
    } else if (LETTER_KEYS[upper] != null) {
      keys.push(LETTER_KEYS[upper]!)
    } else if (DIGIT_KEYS[part] != null) {
      keys.push(DIGIT_KEYS[part]!)
    } else if (part.length === 1 && part >= '0' && part <= '9') {
      keys.push(DIGIT_KEYS[part]!)
    }
  }
  return keys
}

let mainWindow: BrowserWindow | null = null
/** 应用正在真正退出（含托盘「退出」、Cmd+Q、before-quit 流程）；为 false 时关主窗仅隐藏到托盘 */
let appIsQuitting = false
let tray: Tray | null = null
let trajectoryOverlayWindow: BrowserWindow | null = null
let screenshotAssistOverlayWindow: BrowserWindow | null = null
/** 框选截图全屏遮罩 */
let screenshotRegionOverlayWindow: BrowserWindow | null = null
let screenshotAssistAutoCloseTimer: ReturnType<typeof setTimeout> | null = null
let trajectoryMode: 'a' | 'b' = 'a'
let xiaotuoEnabled = false
let xiaotuoMonitorTimer: ReturnType<typeof setInterval> | null = null
let xiaotuoMonitorBusy = false
let xiaotuoLastTriggerAtByType: Record<string, number> = {}
let xiaotuoLastOccurrenceKey = ''
type BuiltinAssistantSlot = 'topoclaw' | 'groupmanager'
interface BuiltinRuntimeSettings {
  builtinServicesEnabled: boolean
  activeCustomerServiceUrl?: string
}

const BUILTIN_TOPOCLAW_PORT = 18790
const BUILTIN_GROUP_MANAGER_PORT = 18791
const BUILTIN_CUSTOMER_SERVICE_PORT = 8002
const BUILTIN_RUNTIME_SETTINGS_FILE = 'builtin-runtime-settings.json'
const XIAOTUO_TARGET_EXTENSIONS = new Set(['ppt', 'pptx', 'pdf', 'doc', 'docx', 'xls', 'xlsx'])
const XIAOTUO_MONITOR_INTERVAL_MS = 4200
const XIAOTUO_COOLDOWN_MS = 90_000

type ForegroundWindowSnapshot = {
  processName: string
  title: string
}

function readForegroundWindowSnapshotWindows(): Promise<ForegroundWindowSnapshot | null> {
  const script = `
$sig=@'
[System.Runtime.InteropServices.DllImport("user32.dll")] public static extern System.IntPtr GetForegroundWindow();
[System.Runtime.InteropServices.DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(System.IntPtr hWnd, out uint processId);
'@
Add-Type -MemberDefinition $sig -Name FocusWin -Namespace TopoClawNative -ErrorAction SilentlyContinue | Out-Null
$h=[TopoClawNative.FocusWin]::GetForegroundWindow()
if($h -eq [System.IntPtr]::Zero){exit 0}
$pid=0
[TopoClawNative.FocusWin]::GetWindowThreadProcessId($h,[ref]$pid) | Out-Null
$p=Get-Process -Id $pid -ErrorAction SilentlyContinue
if($null -eq $p){exit 0}
$title=([string]$p.MainWindowTitle).Trim()
if([string]::IsNullOrWhiteSpace($title)){exit 0}
$name=([string]$p.ProcessName).Trim()
Write-Output ($name + "|||" + $title)
`.trim()
  return new Promise((resolve) => {
    execFile(
      'powershell.exe',
      ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', script],
      { windowsHide: true, timeout: 7000, maxBuffer: 64 * 1024 },
      (error, stdout) => {
        if (error || !stdout) {
          resolve(null)
          return
        }
        const row = stdout
          .split(/\r?\n/)
          .map((s) => s.trim())
          .find((s) => !!s && s.includes('|||'))
        if (!row) {
          resolve(null)
          return
        }
        const idx = row.indexOf('|||')
        const processName = row.slice(0, idx).trim()
        const title = row.slice(idx + 3).trim()
        if (!processName || !title) {
          resolve(null)
          return
        }
        resolve({ processName, title })
      }
    )
  })
}

function inferXiaoTuoFileType(title: string): string | null {
  const t = String(title || '')
  const m = t.match(/\.(pptx?|pdf|docx?|xlsx?)\b/i)
  if (!m) return null
  const ext = (m[1] || '').toLowerCase()
  if (!XIAOTUO_TARGET_EXTENSIONS.has(ext)) return null
  return ext
}

function inferXiaoTuoFileTypeByProcess(processName: string, title: string): string | null {
  const p = String(processName || '').trim().toLowerCase()
  const t = String(title || '').trim().toLowerCase()
  if (!p || !t) return null
  const isPlaceholder =
    t === p ||
    t === 'powerpoint' ||
    t === 'microsoft powerpoint' ||
    t === 'word' ||
    t === 'microsoft word' ||
    t === 'wps office'
  if (isPlaceholder) return null
  if (p.includes('powerpnt') || p.includes('wpp') || p.includes('kwpp')) return 'pptx'
  if (p.includes('winword') || p.includes('wpsword') || p.includes('kwps')) return 'docx'
  if (p.includes('excel') || p === 'et' || p.includes('kett')) return 'xlsx'
  if (p === 'wps') {
    if (t.includes('演示') || t.includes('幻灯') || t.includes('presentation')) return 'pptx'
    if (t.includes('文字') || t.includes('文档') || t.includes('document')) return 'docx'
    if (t.includes('表格') || t.includes('工作簿') || t.includes('spreadsheet') || t.includes('excel')) return 'xlsx'
    return 'docx'
  }
  if (p.includes('acrord') || p.includes('acrobat') || p.includes('foxit') || p.includes('sumatrapdf')) return 'pdf'
  return null
}

function mapXiaoTuoAppAvatar(processName: string): 'ppt' | 'pdf' | 'doc' | 'file' {
  const p = processName.trim().toLowerCase()
  if (p.includes('powerpnt') || p.includes('wps')) return 'ppt'
  if (p.includes('acrord') || p.includes('foxit') || p.includes('sumatrapdf')) return 'pdf'
  if (p.includes('winword')) return 'doc'
  return 'file'
}

function emitXiaoTuoDetected(payload: {
  fileType: string
  processName: string
  title: string
  avatar: 'ppt' | 'pdf' | 'doc' | 'file'
}): void {
  if (!mainWindow || mainWindow.isDestroyed()) return
  mainWindow.webContents.send('xiaotuo:detected', payload)
}

async function tickXiaoTuoMonitor(): Promise<void> {
  if (!xiaotuoEnabled || xiaotuoMonitorBusy) return
  if (process.platform !== 'win32') return
  xiaotuoMonitorBusy = true
  try {
    const snapshot = await readForegroundWindowSnapshotWindows()
    if (!snapshot) return
    const fileType =
      inferXiaoTuoFileType(snapshot.title) ??
      inferXiaoTuoFileTypeByProcess(snapshot.processName, snapshot.title)
    if (!fileType) return
    const now = Date.now()
    const lastAt = xiaotuoLastTriggerAtByType[fileType] || 0
    const occurrenceKey = `${snapshot.processName}|${snapshot.title}|${fileType}`
    if (occurrenceKey === xiaotuoLastOccurrenceKey && now - lastAt < XIAOTUO_COOLDOWN_MS) return
    if (now - lastAt < XIAOTUO_COOLDOWN_MS) return
    xiaotuoLastOccurrenceKey = occurrenceKey
    xiaotuoLastTriggerAtByType[fileType] = now
    emitXiaoTuoDetected({
      fileType,
      processName: snapshot.processName,
      title: snapshot.title,
      avatar: mapXiaoTuoAppAvatar(snapshot.processName),
    })
  } catch (e) {
    console.warn('[xiaotuo] monitor tick error:', e)
  } finally {
    xiaotuoMonitorBusy = false
  }
}

function startXiaoTuoMonitor(): void {
  xiaotuoEnabled = true
  if (xiaotuoMonitorTimer) return
  xiaotuoMonitorTimer = setInterval(() => {
    void tickXiaoTuoMonitor()
  }, XIAOTUO_MONITOR_INTERVAL_MS)
  void tickXiaoTuoMonitor()
}

function stopXiaoTuoMonitor(): void {
  xiaotuoEnabled = false
  if (xiaotuoMonitorTimer) {
    clearInterval(xiaotuoMonitorTimer)
    xiaotuoMonitorTimer = null
  }
}

type BuiltinServiceKind = 'nanobot' | 'group_manager'

const BUILTIN_INSTANCE_SPECS: ReadonlyArray<{
  slot: BuiltinAssistantSlot
  port: number
  label: string
  kind: BuiltinServiceKind
}> = [
  { slot: 'topoclaw', port: BUILTIN_TOPOCLAW_PORT, label: 'TopoClaw', kind: 'nanobot' },
]

/** 由本应用启动的内置进程（TopoClaw=topoclaw CLI；GroupManager=SimpleChat），退出时需结束 */
const builtinAssistantProcesses: Partial<Record<BuiltinAssistantSlot, ChildProcess>> = {}
const builtinAssistantLogBuffers: Record<BuiltinAssistantSlot, string> = { topoclaw: '', groupmanager: '' }
const BUILTIN_LOG_BUFFER_MAX = 500000
const builtinPortBusyHintSent: Partial<Record<BuiltinAssistantSlot, boolean>> = {}
let builtinCustomerServiceProcess: ChildProcess | null = null
let builtinCustomerServiceLogBuffer = ''
let runtimeActiveCustomerServiceUrl = ''
/** 本会话内仅尝试一次：将 config.txt 写入 TopoClaw/config.json（等同「获取本地配置」后保存） */
let topoDesktopConfigTxtAppliedThisSession = false

function normalizeCustomerServiceUrl(rawUrl: string): string {
  const trimmed = String(rawUrl || '').trim()
  if (!trimmed) return ''
  return trimmed.endsWith('/') ? trimmed : `${trimmed}/`
}

function applyRuntimeActiveCustomerServiceUrl(rawUrl: string): void {
  const normalized = normalizeCustomerServiceUrl(rawUrl)
  runtimeActiveCustomerServiceUrl = normalized
  if (!normalized) return
  process.env.TOPO_ACTIVE_CUSTOMER_SERVICE_URL = normalized
  process.env.CUSTOMER_SERVICE_URL = normalized
  process.env.TOPO_CUSTOMER_SERVICE_URL = normalized
  process.env.VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL = normalized
}

function readOpenAIKeyFromOpenclawEnv(openclawCwd: string): string {
  try {
    const envPath = path.join(openclawCwd, '.env')
    if (fs.existsSync(envPath)) {
      const text = fs.readFileSync(envPath, 'utf-8')
      for (const line of text.split('\n')) {
        const t = line.trim()
        if (t.startsWith('OPENAI_API_KEY=')) {
          const v = t.slice('OPENAI_API_KEY='.length).trim()
          if (v) return v
        }
      }
    }
  } catch {
    // ignore
  }
  return process.env.OPENAI_API_KEY ?? ''
}

function readSerperApiKeyFromConfig(openclawCwd: string): string {
  try {
    const configPath = path.join(openclawCwd, 'config.json')
    if (!fs.existsSync(configPath)) return ''
    const cfg = JSON.parse(fs.readFileSync(configPath, 'utf-8')) as Record<string, unknown>
    const tools = (cfg.tools as Record<string, unknown> | undefined) ?? {}
    const serperBlock = (tools.serper as Record<string, unknown> | undefined) ?? {}
    const webBlock = (tools.web as Record<string, unknown> | undefined) ?? {}
    const webSearchBlock = (webBlock.search as Record<string, unknown> | undefined) ?? {}
    const raw =
      webSearchBlock.api_key
      ?? webSearchBlock.apiKey
      ?? tools.web_search_api_key
      ?? tools.serper_api_key
      ?? serperBlock.api_key
      ?? serperBlock.apiKey
      ?? cfg.serper_api_key
      ?? process.env.SERPER_API_KEY
      ?? process.env.SEARCH_API_KEY
    return typeof raw === 'string' ? raw.trim() : ''
  } catch {
    return ''
  }
}

function readSerperApiBaseFromConfig(openclawCwd: string): string {
  try {
    const configPath = path.join(openclawCwd, 'config.json')
    if (!fs.existsSync(configPath)) return ''
    const cfg = JSON.parse(fs.readFileSync(configPath, 'utf-8')) as Record<string, unknown>
    const tools = (cfg.tools as Record<string, unknown> | undefined) ?? {}
    const webBlock = (tools.web as Record<string, unknown> | undefined) ?? {}
    const webSearchBlock = (webBlock.search as Record<string, unknown> | undefined) ?? {}
    const raw =
      webSearchBlock.serper_api_base
      ?? webSearchBlock.serperApiBase
      ?? webSearchBlock.api_base
      ?? webSearchBlock.apiBase
      ?? tools.search_api_base
      ?? process.env.SERPER_API_BASE
      ?? process.env.SEARCH_API_BASE
    return typeof raw === 'string' ? raw.trim() : ''
  } catch {
    return ''
  }
}

function readEnvFileVar(filePath: string, key: string): string {
  try {
    if (!fs.existsSync(filePath)) return ''
    const lines = fs.readFileSync(filePath, 'utf-8').split(/\r?\n/)
    for (const rawLine of lines) {
      const line = rawLine.trim()
      if (!line || line.startsWith('#')) continue
      const m = line.match(/^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$/)
      if (!m) continue
      if (m[1] !== key) continue
      let value = (m[2] || '').trim()
      if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
        value = value.slice(1, -1)
      }
      return value.trim()
    }
  } catch {
    // ignore
  }
  return ''
}

function readTopoDesktopEnvLocalVar(key: string): string {
  const candidates = app.isPackaged
    ? [
        path.join(path.dirname(process.execPath), '.env.local'),
        path.join(process.resourcesPath, '.env.local'),
      ]
    : [path.join(app.getAppPath(), '.env.local')]
  for (const p of candidates) {
    const v = readEnvFileVar(p, key)
    if (v) return v
  }
  return ''
}

function resolveMobileAgentCustomerServiceUrl(): string {
  return (
    runtimeActiveCustomerServiceUrl
    || (process.env.VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL ?? '').trim()
    || readTopoDesktopEnvLocalVar('VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL')
  )
}

function applyCustomerServiceEnvDefaults(baseEnv: NodeJS.ProcessEnv): NodeJS.ProcessEnv {
  const resolved = normalizeCustomerServiceUrl(resolveMobileAgentCustomerServiceUrl())
  if (!resolved) return baseEnv
  const next = { ...baseEnv }
  next.TOPO_ACTIVE_CUSTOMER_SERVICE_URL = resolved
  if (!next.CUSTOMER_SERVICE_URL) next.CUSTOMER_SERVICE_URL = resolved
  if (!next.TOPO_CUSTOMER_SERVICE_URL) next.TOPO_CUSTOMER_SERVICE_URL = resolved
  if (!next.VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL) next.VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL = resolved
  return next
}

function toTopomobileRelayWsUrl(rawBaseUrl: string): string {
  const s = String(rawBaseUrl || '').trim()
  if (!s) return ''
  try {
    const parsed = new URL(s)
    if (parsed.protocol === 'http:') parsed.protocol = 'ws:'
    if (parsed.protocol === 'https:') parsed.protocol = 'wss:'
    let p = parsed.pathname || '/'
    if (!p.endsWith('/')) p += '/'
    if (!p.endsWith('/ws/')) {
      p += 'ws/'
    }
    parsed.pathname = p.replace(/\/{2,}/g, '/')
    parsed.search = ''
    parsed.hash = ''
    return parsed.toString().replace(/\/$/, '')
  } catch {
    return ''
  }
}

function normalizeTopomobileNodeId(raw: unknown): string {
  const s = String(raw ?? '').trim()
  if (!s) return ''
  if (s === '000') return ''
  return s
}

function ensureTopomobileChannelDefaults(
  cfg: Record<string, unknown>,
  opts?: { preferredNodeId?: string }
): void {
  const channels = ((cfg.channels as Record<string, unknown> | undefined) ?? {}) as Record<string, unknown>
  const topomobile = ((channels.topomobile as Record<string, unknown> | undefined) ?? {}) as Record<string, unknown>
  topomobile.enabled = true

  const allowFrom = topomobile.allowFrom
  if (!Array.isArray(allowFrom) || allowFrom.length === 0) {
    topomobile.allowFrom = ['*']
  }

  const preferredWsUrl = toTopomobileRelayWsUrl(resolveMobileAgentCustomerServiceUrl())
  const currentWsUrl = String(topomobile.wsUrl ?? '').trim()
  if (
    preferredWsUrl &&
    (
      !currentWsUrl ||
      /^wss?:\/\/localhost(?::\d+)?\//i.test(currentWsUrl) ||
      /^wss?:\/\/127\.0\.0\.1(?::\d+)?\//i.test(currentWsUrl)
    )
  ) {
    topomobile.wsUrl = preferredWsUrl
  } else if (!currentWsUrl) {
    topomobile.wsUrl = 'ws://localhost:8000/ws'
  }

  const preferredNodeId = normalizeTopomobileNodeId(opts?.preferredNodeId)
  const currentNodeId = normalizeTopomobileNodeId(topomobile.nodeId)
  if (preferredNodeId) {
    topomobile.nodeId = preferredNodeId
  } else if (!currentNodeId) {
    topomobile.nodeId = '000'
  }

  channels.topomobile = topomobile
  cfg.channels = channels
}

/** config.json 内由桌面端维护的多套模型（chat / GUI）与 GroupManager 当前模型 */
const TOPO_DESKTOP_KEY = 'topo_desktop'

type ModelProfileRow = { model: string; apiBase: string; apiKey: string }
type TopoDesktopState = {
  nonGuiProfiles: ModelProfileRow[]
  guiProfiles: ModelProfileRow[]
  activeNonGuiModel: string
  activeImageModel: string
  activeGuiModel: string
  activeGroupManagerModel: string
}

function resolveOpenclawMain2BaseDir(): string | null {
  const appPath = app.getAppPath()
  const candidates = app.isPackaged
    ? [
        path.join(process.resourcesPath, 'TopoClaw'),
        path.join(appPath, 'resources', 'TopoClaw'),
      ]
    : [
        path.join(appPath, 'resources', 'TopoClaw'),
        path.join(appPath, '..', 'resources', 'TopoClaw'),
        path.join(appPath, '..', 'TopoClaw'),
        path.join(process.cwd(), 'resources', 'TopoClaw'),
        path.join(process.cwd(), 'TopoClaw'),
      ]
  for (const p of candidates) {
    try {
      if (fs.existsSync(p) && fs.statSync(p).isDirectory()) return p
    } catch {
      // ignore invalid candidate
    }
  }
  return null
}

function resolveOpenclawWorkspaceDir(): string | null {
  const baseDir = resolveOpenclawMain2BaseDir()
  if (!baseDir) return null
  return path.join(baseDir, 'workspace')
}

function isLikelyTextBuffer(buf: Buffer): boolean {
  if (!buf || buf.length === 0) return true
  const sample = buf.subarray(0, Math.min(buf.length, 4096))
  for (let i = 0; i < sample.length; i++) {
    if (sample[i] === 0) return false
  }
  return true
}

type WorkspaceProfileKind = 'soul' | 'memory'

function resolveWorkspaceProfileFilePath(kind: WorkspaceProfileKind): string | null {
  const workspaceDir = resolveOpenclawWorkspaceDir()
  if (!workspaceDir) return null
  if (kind === 'soul') return path.join(workspaceDir, 'SOUL.md')
  return path.join(workspaceDir, 'memory', 'MEMORY.md')
}

function resolveWorkspaceProfileDefaultFilePath(kind: WorkspaceProfileKind): string | null {
  const baseDir = resolveOpenclawMain2BaseDir()
  if (!baseDir) return null
  if (kind === 'soul') return path.join(baseDir, 'topoclaw', 'templates', 'SOUL.md')
  return path.join(baseDir, 'topoclaw', 'templates', 'memory', 'MEMORY.md')
}

function resolveConversationSummaryFilePath(): string | null {
  const workspaceDir = resolveOpenclawWorkspaceDir()
  if (!workspaceDir) return null
  return path.join(workspaceDir, 'CONVERSATION_SUMMARIES.md')
}

function readProviderBlock(p: Record<string, unknown> | undefined): { apiBase: string; apiKey: string } {
  if (!p) return { apiBase: '', apiKey: '' }
  const apiBase = p.api_base ?? p.apiBase
  const apiKey = p.api_key ?? p.apiKey
  return {
    apiBase: typeof apiBase === 'string' ? apiBase : '',
    apiKey: typeof apiKey === 'string' ? apiKey : '',
  }
}

function coerceProfileRow(x: unknown): ModelProfileRow | null {
  if (!x || typeof x !== 'object') return null
  const o = x as Record<string, unknown>
  const model = typeof o.model === 'string' ? o.model.trim() : ''
  if (!model) return null
  const apiBaseRaw = o.apiBase ?? o.api_base
  const apiKeyRaw = o.apiKey ?? o.api_key
  return {
    model,
    apiBase: typeof apiBaseRaw === 'string' ? apiBaseRaw.trim() : '',
    apiKey: typeof apiKeyRaw === 'string' ? apiKeyRaw : '',
  }
}

function coerceProfiles(arr: unknown): ModelProfileRow[] {
  if (!Array.isArray(arr)) return []
  const out: ModelProfileRow[] = []
  for (const a of arr) {
    const r = coerceProfileRow(a)
    if (r) out.push(r)
  }
  return out
}

/** 兼容 TopoDesktop/config.txt 中尾随逗号等非严格 JSON */
function stripJsonTrailingCommas(json: string): string {
  return json.replace(/,\s*(\]|\})/g, '$1')
}

function mapTxtConfigEntry(x: unknown): ModelProfileRow | null {
  if (!x || typeof x !== 'object') return null
  const r = x as Record<string, unknown>
  const model = typeof r.model_name === 'string' ? r.model_name.trim() : ''
  if (!model) return null
  const url = typeof r.url === 'string' ? r.url.trim() : ''
  const key = typeof r.key === 'string' ? r.key : ''
  return { model, apiBase: url, apiKey: key }
}

function profilesFromTxtConfigArray(arr: unknown): ModelProfileRow[] {
  if (!Array.isArray(arr)) return []
  const seen = new Set<string>()
  const out: ModelProfileRow[] = []
  for (const item of arr) {
    const row = mapTxtConfigEntry(item)
    if (!row || seen.has(row.model)) continue
    seen.add(row.model)
    out.push(row)
  }
  return out
}

type TxtOptionalString = { hasValue: boolean; value: string }

function readTxtOptionalString(obj: Record<string, unknown>, key: string): TxtOptionalString {
  if (!Object.prototype.hasOwnProperty.call(obj, key)) return { hasValue: false, value: '' }
  const raw = obj[key]
  return { hasValue: true, value: typeof raw === 'string' ? raw.trim() : '' }
}

function readTxtOptionalStringAny(obj: Record<string, unknown>, keys: string[]): TxtOptionalString {
  for (const key of keys) {
    const got = readTxtOptionalString(obj, key)
    if (got.hasValue) return got
  }
  return { hasValue: false, value: '' }
}

function parseLocalTopoConfigTxt(raw: string):
  | {
      ok: true
      nonGui: ModelProfileRow[]
      gui: ModelProfileRow[]
      serperApiKey?: string
      webSearchApiBase?: string
      serperApiBase?: string
      reverseGeocodeApiKey?: string
      reverseGeocodeUrl?: string
    }
  | { ok: false; error: string } {
  const trimmed = raw.replace(/^\uFEFF/, '').trim()
  if (!trimmed) return { ok: false, error: '配置文件为空' }
  try {
    const obj = JSON.parse(stripJsonTrailingCommas(trimmed)) as Record<string, unknown>
    const nonGui = profilesFromTxtConfigArray(obj.non_gui)
    const gui = profilesFromTxtConfigArray(obj.gui)
    if (nonGui.length === 0 && gui.length === 0) {
      return { ok: false, error: '配置文件中 non_gui 与 gui 均为空' }
    }
    const serperApiKey = readTxtOptionalString(obj, 'serper_api_key')
    const webSearchApiBase = readTxtOptionalString(obj, 'search_api_base')
    const serperApiBase = readTxtOptionalString(obj, 'serper_api_base')
    const reverseGeocodeApiKey = readTxtOptionalStringAny(obj, [
      'reverse_geocode_api_key',
      'mobile_location_api_key',
      'API_KEY',
    ])
    const reverseGeocodeUrl = readTxtOptionalStringAny(obj, [
      'reverse_geocode_url',
      'mobile_location_regeocode_url',
      'REGEOCODE_URL',
    ])
    const out: {
      ok: true
      nonGui: ModelProfileRow[]
      gui: ModelProfileRow[]
      serperApiKey?: string
      webSearchApiBase?: string
      serperApiBase?: string
      reverseGeocodeApiKey?: string
      reverseGeocodeUrl?: string
    } = { ok: true, nonGui, gui }
    if (serperApiKey.hasValue) out.serperApiKey = serperApiKey.value
    if (webSearchApiBase.hasValue) out.webSearchApiBase = webSearchApiBase.value
    if (serperApiBase.hasValue) out.serperApiBase = serperApiBase.value
    if (reverseGeocodeApiKey.hasValue) out.reverseGeocodeApiKey = reverseGeocodeApiKey.value
    if (reverseGeocodeUrl.hasValue) out.reverseGeocodeUrl = reverseGeocodeUrl.value
    return out
  } catch {
    return { ok: false, error: '配置文件格式无效' }
  }
}

function resolveFirstExistingTopoDesktopConfigTxtPath(): string | null {
  const candidates = app.isPackaged
    ? [path.join(process.resourcesPath, 'config.txt'), path.join(path.dirname(process.execPath), 'config.txt')]
    : [path.join(app.getAppPath(), 'config.txt')]
  for (const p of candidates) {
    try {
      if (fs.existsSync(p) && fs.statSync(p).isFile()) return p
    } catch {
      // ignore
    }
  }
  return null
}

function resolvePreferredTopoDesktopConfigTxtPath(): string {
  const existing = resolveFirstExistingTopoDesktopConfigTxtPath()
  if (existing) return existing
  if (app.isPackaged) {
    return path.join(path.dirname(process.execPath), 'config.txt')
  }
  return path.join(app.getAppPath(), 'config.txt')
}

function saveTopoDesktopProfilesToLocalConfigTxt(td: TopoDesktopState): { ok: true; path: string } | { ok: false; error: string } {
  try {
    const txtPath = resolvePreferredTopoDesktopConfigTxtPath()
    const payload = {
      non_gui: td.nonGuiProfiles.map((row) => ({
        model_name: row.model,
        url: row.apiBase,
        key: row.apiKey,
      })),
      gui: td.guiProfiles.map((row) => ({
        model_name: row.model,
        url: row.apiBase,
        key: row.apiKey,
      })),
    }
    fs.writeFileSync(txtPath, JSON.stringify(payload, null, 2) + '\n', 'utf-8')
    return { ok: true, path: txtPath }
  } catch (e) {
    return { ok: false, error: String(e) }
  }
}

function migrateTopoDesktopFromCfg(cfg: Record<string, unknown>): TopoDesktopState {
  const agents = cfg.agents as Record<string, unknown> | undefined
  const defaults = agents?.defaults as Record<string, unknown> | undefined
  const gui = agents?.gui as Record<string, unknown> | undefined
  const providers = cfg.providers as Record<string, Record<string, unknown>> | undefined
  const main = readProviderBlock(providers?.custom)
  const g = readProviderBlock(providers?.custom2)
  const model = typeof defaults?.model === 'string' ? defaults.model.trim() : 'gpt-4o-mini'
  const guiModel = typeof gui?.model === 'string' ? gui.model.trim() : 'Qwen3-VL-32B-Instruct-rl'
  const nonGuiProfiles: ModelProfileRow[] = [
    {
      model,
      apiBase: main.apiBase || 'https://api.openai.com/v1',
      apiKey: main.apiKey,
    },
  ]
  return {
    nonGuiProfiles,
    guiProfiles: [
      {
        model: guiModel,
        apiBase: g.apiBase || DEFAULT_GUI_API_BASE,
        apiKey: g.apiKey,
      },
    ],
    activeNonGuiModel: model,
    activeImageModel: pickFirstImageModel(nonGuiProfiles),
    activeGuiModel: guiModel,
    activeGroupManagerModel: model,
  }
}

function parseTopoDesktopFromCfg(cfg: Record<string, unknown>): TopoDesktopState | null {
  const raw = cfg[TOPO_DESKTOP_KEY]
  if (!raw || typeof raw !== 'object') return null
  const o = raw as Record<string, unknown>
  const nonGui = coerceProfiles(o.nonGuiProfiles ?? o.non_gui_profiles)
  const gui = coerceProfiles(o.guiProfiles ?? o.gui_profiles)
  if (nonGui.length === 0 || gui.length === 0) return null
  const activeNonGui = typeof o.activeNonGuiModel === 'string' ? o.activeNonGuiModel.trim() : ''
  const activeImageRaw =
    typeof o.activeImageModel === 'string'
      ? o.activeImageModel
      : (typeof o.active_image_model === 'string' ? o.active_image_model : '')
  const activeImage = activeImageRaw.trim()
  const activeGui = typeof o.activeGuiModel === 'string' ? o.activeGuiModel.trim() : ''
  const activeGm = typeof o.activeGroupManagerModel === 'string' ? o.activeGroupManagerModel.trim() : ''
  const firstImage = pickFirstImageModel(nonGui)
  return {
    nonGuiProfiles: nonGui,
    guiProfiles: gui,
    activeNonGuiModel: activeNonGui || nonGui[0]!.model,
    activeImageModel: findProfileByModel(nonGui, activeImage) ? activeImage : firstImage,
    activeGuiModel: activeGui || gui[0]!.model,
    activeGroupManagerModel: activeGm || nonGui[0]!.model,
  }
}

function hasExplicitActiveModelSelectionsInCfg(cfg: Record<string, unknown>): boolean {
  const raw = cfg[TOPO_DESKTOP_KEY]
  if (!raw || typeof raw !== 'object') return false
  const o = raw as Record<string, unknown>
  const hasNonGui = typeof o.activeNonGuiModel === 'string' && o.activeNonGuiModel.trim().length > 0
  const hasGui = typeof o.activeGuiModel === 'string' && o.activeGuiModel.trim().length > 0
  return hasNonGui && hasGui
}

function ensureTopoDesktopInCfg(cfg: Record<string, unknown>): TopoDesktopState {
  let td = parseTopoDesktopFromCfg(cfg)
  if (!td) {
    td = migrateTopoDesktopFromCfg(cfg)
  }
  cfg[TOPO_DESKTOP_KEY] = td
  return td
}

function findProfileByModel(profiles: ModelProfileRow[], modelName: string): ModelProfileRow | undefined {
  const needle = modelName.trim()
  return profiles.find((p) => p.model === needle)
}

function hasDuplicateModelNames(profiles: ModelProfileRow[]): boolean {
  const seen = new Set<string>()
  for (const p of profiles) {
    if (seen.has(p.model)) return true
    seen.add(p.model)
  }
  return false
}

function isLikelyImageModel(modelName: string): boolean {
  const name = String(modelName || '').trim().toLowerCase()
  if (!name) return false
  const keywords = ['banana', 'dall-e', 'gpt-image', 'stable-diffusion', 'sdxl', 'flux', 'imagen', 'image']
  return keywords.some((k) => name.includes(k))
}

function pickFirstImageModel(profiles: ModelProfileRow[]): string {
  for (const row of profiles) {
    if (isLikelyImageModel(row.model)) return row.model
  }
  return ''
}

function applyNanobotProvidersFromProfiles(cfg: Record<string, unknown>, td: TopoDesktopState): void {
  const ng = findProfileByModel(td.nonGuiProfiles, td.activeNonGuiModel) ?? td.nonGuiProfiles[0]
  const g = findProfileByModel(td.guiProfiles, td.activeGuiModel) ?? td.guiProfiles[0]
  if (!ng || !g) return

  const agents = (cfg.agents as Record<string, unknown>) ?? {}
  const defaults = (agents.defaults as Record<string, unknown>) ?? {}
  const guiAgent = (agents.gui as Record<string, unknown>) ?? {}
  const providers = (cfg.providers as Record<string, Record<string, unknown>>) ?? {}
  const customProvider: Record<string, unknown> = { ...(providers.custom ?? {}) }
  const custom2Provider: Record<string, unknown> = { ...(providers.custom2 ?? {}) }
  delete customProvider.apiKey
  delete customProvider.apiBase
  delete custom2Provider.apiKey
  delete custom2Provider.apiBase

  defaults.model = ng.model
  defaults.provider = 'custom'
  customProvider.api_base = ng.apiBase
  customProvider.api_key = ng.apiKey

  guiAgent.model = g.model
  guiAgent.provider = 'custom2'
  custom2Provider.api_base = g.apiBase
  custom2Provider.api_key = g.apiKey

  agents.defaults = defaults
  agents.gui = guiAgent
  providers.custom = customProvider
  providers.custom2 = custom2Provider
  cfg.agents = agents
  cfg.providers = providers
}

function writeOpenclawEnvKey(openclawCwd: string, apiKey: string) {
  const envPath = path.join(openclawCwd, '.env')
  const envLines = ['# 内置小助手环境变量（由应用写入）', 'OPENAI_API_KEY=' + (apiKey ?? ''), '']
  fs.writeFileSync(envPath, envLines.join('\n'), 'utf-8')
}

/**
 * 若存在 config.txt（开发：TopoDesktop/config.txt；安装包：resources 或 exe 同目录），
 * 解析并合并进 TopoClaw 的 config.json topo_desktop。
 * 注意：不再在启动/读取模型时自动应用，避免覆盖用户在界面中的修改；
 * 仅在用户主动执行「获取本地配置」时读取并保存。
 */
function tryApplyLocalConfigTxtToOpenclawConfig(options?: { force?: boolean }): void {
  if (!options?.force && topoDesktopConfigTxtAppliedThisSession) return
  topoDesktopConfigTxtAppliedThisSession = true
  const baseDir = resolveOpenclawMain2BaseDir()
  if (!baseDir) return
  const txtPath = resolveFirstExistingTopoDesktopConfigTxtPath()
  if (!txtPath) return
  let raw: string
  try {
    raw = fs.readFileSync(txtPath, 'utf-8')
  } catch (e) {
    console.warn('[builtin] read config.txt failed:', e)
    return
  }
  const parsed = parseLocalTopoConfigTxt(raw)
  if (!parsed.ok) {
    const reason = 'error' in parsed ? parsed.error : '配置文件格式无效'
    console.warn('[builtin] config.txt parse skipped:', reason)
    return
  }
  const nonGui = parsed.nonGui.filter((r) => r.model)
  const gui = parsed.gui.filter((r) => r.model)
  if (nonGui.length === 0 || gui.length === 0) {
    console.warn('[builtin] config.txt skipped: non_gui and gui each need at least one valid entry')
    return
  }
  if (hasDuplicateModelNames(nonGui) || hasDuplicateModelNames(gui)) {
    console.warn('[builtin] config.txt skipped: duplicate model names')
    return
  }
  const configPath = path.join(baseDir, 'config.json')
  let cfg: Record<string, unknown> = {}
  if (fs.existsSync(configPath)) {
    try {
      cfg = JSON.parse(fs.readFileSync(configPath, 'utf-8')) as Record<string, unknown>
    } catch {
      cfg = {}
    }
  }
  const existing = parseTopoDesktopFromCfg(cfg)
  const hasExplicitActiveSelection = hasExplicitActiveModelSelectionsInCfg(cfg)
  const td: TopoDesktopState = {
    nonGuiProfiles: nonGui,
    guiProfiles: gui,
    activeNonGuiModel: nonGui[0]!.model,
    activeImageModel: pickFirstImageModel(nonGui),
    activeGuiModel: gui[0]!.model,
    activeGroupManagerModel: nonGui[0]!.model,
  }
  if (existing && hasExplicitActiveSelection) {
    if (findProfileByModel(nonGui, existing.activeNonGuiModel)) td.activeNonGuiModel = existing.activeNonGuiModel
    if (findProfileByModel(nonGui, existing.activeImageModel)) td.activeImageModel = existing.activeImageModel
    if (findProfileByModel(gui, existing.activeGuiModel)) td.activeGuiModel = existing.activeGuiModel
    if (findProfileByModel(nonGui, existing.activeGroupManagerModel)) {
      td.activeGroupManagerModel = existing.activeGroupManagerModel
    }
  }
  cfg[TOPO_DESKTOP_KEY] = td
  applyNanobotProvidersFromProfiles(cfg, td)
  const tools = ((cfg.tools as Record<string, unknown> | undefined) ?? {}) as Record<string, unknown>
  const webTools = ((tools.web as Record<string, unknown> | undefined) ?? {}) as Record<string, unknown>
  const webSearch = ((webTools.search as Record<string, unknown> | undefined) ?? {}) as Record<string, unknown>
  if (parsed.serperApiKey != null) webSearch.api_key = parsed.serperApiKey
  if (parsed.webSearchApiBase != null) webSearch.api_base = parsed.webSearchApiBase
  if (parsed.serperApiBase != null) webSearch.serper_api_base = parsed.serperApiBase
  webTools.search = webSearch
  const mobileLocation = ((tools.mobile_location as Record<string, unknown> | undefined) ?? {}) as Record<string, unknown>
  const reverseGeocode = ((mobileLocation.reverse_geocode as Record<string, unknown> | undefined) ?? {}) as Record<string, unknown>
  if (parsed.reverseGeocodeApiKey != null) reverseGeocode.api_key = parsed.reverseGeocodeApiKey
  if (parsed.reverseGeocodeUrl != null) reverseGeocode.regeo_url = parsed.reverseGeocodeUrl
  mobileLocation.reverse_geocode = reverseGeocode
  tools.mobile_location = mobileLocation
  tools.web = webTools
  cfg.tools = tools
  ensureTopomobileChannelDefaults(cfg)
  const ng = findProfileByModel(td.nonGuiProfiles, td.activeNonGuiModel) ?? td.nonGuiProfiles[0]
  if (ng) writeOpenclawEnvKey(baseDir, ng.apiKey)
  try {
    fs.writeFileSync(configPath, JSON.stringify(cfg, null, 2) + '\n', 'utf-8')
    console.log('[builtin] applied config.txt ->', configPath, '(from', txtPath + ')')
  } catch (e) {
    console.warn('[builtin] write config.json failed:', e)
  }
}

function readBuiltinDefaultsFromConfigObject(cfg: Record<string, unknown>): {
  model: string
  apiBase: string
  apiKey: string
  guiModel: string
  guiApiBase: string
  guiApiKey: string
  qqEnabled: boolean
  qqAppId: string
  qqAppSecret: string
  qqAllowFrom: string
  weixinEnabled: boolean
  weixinBotToken: string
  weixinBaseUrl: string
  weixinAllowFrom: string
} {
  const defaults = {
    model: 'gpt-4o-mini',
    apiBase: 'https://api.openai.com/v1',
    apiKey: '',
    guiModel: 'Qwen3-VL-32B-Instruct-rl',
    guiApiBase: DEFAULT_GUI_API_BASE,
    guiApiKey: '',
    qqEnabled: false,
    qqAppId: '',
    qqAppSecret: '',
    qqAllowFrom: '*',
    weixinEnabled: false,
    weixinBotToken: '',
    weixinBaseUrl: 'https://ilinkai.weixin.qq.com',
    weixinAllowFrom: '*',
  }
  const channels = (cfg.channels as Record<string, unknown> | undefined) ?? {}
  const qq = (channels.qq as Record<string, unknown> | undefined) ?? {}
  const qqEnabled = qq.enabled
  if (typeof qqEnabled === 'boolean') defaults.qqEnabled = qqEnabled
  const qqAppId = qq.appId ?? qq.app_id
  if (typeof qqAppId === 'string') defaults.qqAppId = qqAppId
  const qqAppSecret = qq.secret
  if (typeof qqAppSecret === 'string') defaults.qqAppSecret = qqAppSecret
  const qqAllowRaw = qq.allowFrom ?? qq.allow_from
  if (Array.isArray(qqAllowRaw)) {
    const vals = qqAllowRaw.map((v) => String(v).trim()).filter(Boolean)
    defaults.qqAllowFrom = vals.length > 0 ? vals.join(',') : '*'
  } else if (typeof qqAllowRaw === 'string') {
    defaults.qqAllowFrom = qqAllowRaw.trim() || '*'
  }
  const weixin = (channels.weixin as Record<string, unknown> | undefined) ?? {}
  const weixinEnabled = weixin.enabled
  if (typeof weixinEnabled === 'boolean') defaults.weixinEnabled = weixinEnabled
  const weixinBotToken = weixin.botToken ?? weixin.bot_token
  if (typeof weixinBotToken === 'string') defaults.weixinBotToken = weixinBotToken
  const weixinBaseUrl = weixin.baseUrl ?? weixin.base_url
  if (typeof weixinBaseUrl === 'string' && weixinBaseUrl.trim()) defaults.weixinBaseUrl = weixinBaseUrl.trim()
  const weixinAllowRaw = weixin.allowFrom ?? weixin.allow_from
  if (Array.isArray(weixinAllowRaw)) {
    const vals = weixinAllowRaw.map((v) => String(v).trim()).filter(Boolean)
    defaults.weixinAllowFrom = vals.length > 0 ? vals.join(',') : '*'
  } else if (typeof weixinAllowRaw === 'string') {
    defaults.weixinAllowFrom = weixinAllowRaw.trim() || '*'
  }
  const td = parseTopoDesktopFromCfg(cfg)
  if (td) {
    const ng = findProfileByModel(td.nonGuiProfiles, td.activeNonGuiModel) ?? td.nonGuiProfiles[0]
    const g = findProfileByModel(td.guiProfiles, td.activeGuiModel) ?? td.guiProfiles[0]
    if (ng) {
      defaults.model = ng.model
      defaults.apiBase = ng.apiBase
      defaults.apiKey = ng.apiKey
    }
    if (g) {
      defaults.guiModel = g.model
      defaults.guiApiBase = g.apiBase
      defaults.guiApiKey = g.apiKey
    }
    return defaults
  }
  const main = readProviderBlock((cfg.providers as Record<string, Record<string, unknown>> | undefined)?.custom)
  if ((cfg.agents as Record<string, unknown> | undefined)?.defaults) {
    const d = (cfg.agents as Record<string, unknown>).defaults as Record<string, unknown>
    if (typeof d.model === 'string') defaults.model = d.model
  }
  if (main.apiBase) defaults.apiBase = main.apiBase
  if (main.apiKey) defaults.apiKey = main.apiKey
  const gui = readProviderBlock((cfg.providers as Record<string, Record<string, unknown>> | undefined)?.custom2)
  const guiAgent = (cfg.agents as Record<string, unknown> | undefined)?.gui as Record<string, unknown> | undefined
  if (guiAgent && typeof guiAgent.model === 'string') defaults.guiModel = guiAgent.model
  if (gui.apiBase) defaults.guiApiBase = gui.apiBase
  if (gui.apiKey) defaults.guiApiKey = gui.apiKey
  return defaults
}

/** GroupManager 进程环境：从 config.json 的 topo_desktop chat 配置读取 */
function buildGroupManagerProcessEnv(openclawCwd: string): NodeJS.ProcessEnv {
  const configPath = path.join(openclawCwd, 'config.json')
  let cfg: Record<string, unknown> = {}
  if (fs.existsSync(configPath)) {
    try {
      cfg = JSON.parse(fs.readFileSync(configPath, 'utf-8')) as Record<string, unknown>
    } catch {
      // ignore
    }
  }
  const td = parseTopoDesktopFromCfg(cfg) ?? migrateTopoDesktopFromCfg(cfg)
  const row =
    findProfileByModel(td.nonGuiProfiles, td.activeGroupManagerModel) ?? td.nonGuiProfiles[0] ?? null
  const key =
    row?.apiKey || readOpenAIKeyFromOpenclawEnv(openclawCwd) || process.env.OPENAI_API_KEY || ''
  const baseUrl = row?.apiBase || 'https://dashscope.aliyuncs.com/compatible-mode/v1'
  const modelName = row?.model || 'qwen3.5-397b-a17b'
  return applyCustomerServiceEnvDefaults({
    ...process.env,
    OPENAI_API_KEY: key,
    OPENAI_BASE_URL: baseUrl,
    OPENAI_MODEL_NAME: modelName,
    PYTHONIOENCODING: 'utf-8',
    PYTHONUNBUFFERED: '1',
  })
}

function upsertProfileByModel(profiles: ModelProfileRow[], row: ModelProfileRow): ModelProfileRow[] {
  const idx = profiles.findIndex((p) => p.model === row.model)
  if (idx >= 0) {
    const next = [...profiles]
    next[idx] = { ...next[idx], ...row }
    return next
  }
  return [...profiles, row]
}

function mergeTopoFromSaveConfig(
  cfg: Record<string, unknown>,
  config: {
    model?: string
    apiBase?: string
    apiKey?: string
    guiModel?: string
    guiApiBase?: string
    guiApiKey?: string
  }
): void {
  const td = ensureTopoDesktopInCfg(cfg)
  if (config.model != null || config.apiBase != null || config.apiKey != null) {
    const modelKey = (config.model ?? td.activeNonGuiModel).trim()
    const cur = findProfileByModel(td.nonGuiProfiles, modelKey) ?? {
      model: modelKey,
      apiBase: '',
      apiKey: '',
    }
    td.nonGuiProfiles = upsertProfileByModel(td.nonGuiProfiles, {
      model: modelKey,
      apiBase: config.apiBase != null ? String(config.apiBase).trim() : cur.apiBase,
      apiKey: config.apiKey != null ? String(config.apiKey) : cur.apiKey,
    })
    td.activeNonGuiModel = modelKey
  }
  if (config.guiModel != null || config.guiApiBase != null || config.guiApiKey != null) {
    const modelKey = (config.guiModel ?? td.activeGuiModel).trim()
    const cur = findProfileByModel(td.guiProfiles, modelKey) ?? {
      model: modelKey,
      apiBase: '',
      apiKey: '',
    }
    td.guiProfiles = upsertProfileByModel(td.guiProfiles, {
      model: modelKey,
      apiBase: config.guiApiBase != null ? String(config.guiApiBase).trim() : cur.apiBase,
      apiKey: config.guiApiKey != null ? String(config.guiApiKey) : cur.apiKey,
    })
    td.activeGuiModel = modelKey
  }
  cfg[TOPO_DESKTOP_KEY] = td
}

function parseAllowFromInput(raw: string | string[] | undefined): string[] {
  if (Array.isArray(raw)) {
    const vals = raw.map((v) => String(v).trim()).filter(Boolean)
    return vals.length > 0 ? vals : ['*']
  }
  if (typeof raw !== 'string') return ['*']
  const vals = raw
    .split(/[,\n;]/g)
    .map((v) => v.trim())
    .filter(Boolean)
  return vals.length > 0 ? vals : ['*']
}

function mergeQQChannelFromSaveConfig(
  cfg: Record<string, unknown>,
  config: {
    qqEnabled?: boolean
    qqAppId?: string
    qqAppSecret?: string
    qqAllowFrom?: string | string[]
  }
): void {
  const shouldUpdate =
    config.qqEnabled != null ||
    config.qqAppId != null ||
    config.qqAppSecret != null ||
    config.qqAllowFrom != null
  if (!shouldUpdate) return

  const channels = ((cfg.channels as Record<string, unknown> | undefined) ?? {}) as Record<string, unknown>
  const qq = ((channels.qq as Record<string, unknown> | undefined) ?? {}) as Record<string, unknown>

  if (config.qqEnabled != null) qq.enabled = Boolean(config.qqEnabled)
  if (config.qqAppId != null) qq.appId = String(config.qqAppId).trim()
  if (config.qqAppSecret != null) qq.secret = String(config.qqAppSecret)
  if (config.qqAllowFrom != null) qq.allowFrom = parseAllowFromInput(config.qqAllowFrom)

  const hasCreds = String(qq.appId ?? '').trim() && String(qq.secret ?? '').trim()
  if (config.qqEnabled == null && hasCreds) {
    qq.enabled = true
  }
  if (!Array.isArray(qq.allowFrom) || qq.allowFrom.length === 0) {
    qq.allowFrom = ['*']
  }

  channels.qq = qq
  cfg.channels = channels
}

function mergeWeixinChannelFromSaveConfig(
  cfg: Record<string, unknown>,
  config: {
    weixinEnabled?: boolean
    weixinBotToken?: string
    weixinBaseUrl?: string
    weixinAllowFrom?: string | string[]
  }
): void {
  const shouldUpdate =
    config.weixinEnabled != null ||
    config.weixinBotToken != null ||
    config.weixinBaseUrl != null ||
    config.weixinAllowFrom != null
  if (!shouldUpdate) return

  const channels = ((cfg.channels as Record<string, unknown> | undefined) ?? {}) as Record<string, unknown>
  const weixin = ((channels.weixin as Record<string, unknown> | undefined) ?? {}) as Record<string, unknown>

  if (config.weixinEnabled != null) weixin.enabled = Boolean(config.weixinEnabled)
  if (config.weixinBotToken != null) weixin.botToken = String(config.weixinBotToken).trim()
  if (config.weixinBaseUrl != null) weixin.baseUrl = String(config.weixinBaseUrl).trim()
  if (config.weixinAllowFrom != null) weixin.allowFrom = parseAllowFromInput(config.weixinAllowFrom)

  const hasToken = String(weixin.botToken ?? '').trim()
  if (config.weixinEnabled == null && hasToken) {
    weixin.enabled = true
  }
  if (!Array.isArray(weixin.allowFrom) || weixin.allowFrom.length === 0) {
    weixin.allowFrom = ['*']
  }
  if (!String(weixin.baseUrl ?? '').trim()) {
    weixin.baseUrl = 'https://ilinkai.weixin.qq.com'
  }

  channels.weixin = weixin
  cfg.channels = channels
}

function appendBuiltinLogChunk(slot: BuiltinAssistantSlot, chunk: string) {
  if (!chunk) return
  let buf = builtinAssistantLogBuffers[slot] + chunk
  if (buf.length > BUILTIN_LOG_BUFFER_MAX) {
    buf = buf.slice(-BUILTIN_LOG_BUFFER_MAX)
  }
  builtinAssistantLogBuffers[slot] = buf
  const win = mainWindow
  if (!win || win.isDestroyed()) return
  try {
    const wc = win.webContents
    if (!wc || wc.isDestroyed()) return
    wc.send('builtin-assistant:log', { slot, chunk })
  } catch {
    // 退出过程中子进程 stdout/exit 仍可能回调，此时 WebContents 已销毁
  }
}

function appendBuiltinCustomerServiceLogChunk(chunk: string) {
  if (!chunk) return
  let buf = builtinCustomerServiceLogBuffer + chunk
  if (buf.length > BUILTIN_LOG_BUFFER_MAX) {
    buf = buf.slice(-BUILTIN_LOG_BUFFER_MAX)
  }
  builtinCustomerServiceLogBuffer = buf
}

function isUsableCustomerServiceDir(dir: string): boolean {
  if (!dir) return false
  const appPy = path.join(dir, 'app.py')
  const coreOutput = path.join(dir, 'core', 'output_paths.py')
  return fs.existsSync(appPy) && fs.existsSync(coreOutput)
}

function resolveCustomerServiceCwdFallback(defaultDir: string): string {
  if (isUsableCustomerServiceDir(defaultDir)) return defaultDir
  const expandUpwardCandidates = (base: string, maxUp: number): string[] => {
    const out: string[] = []
    let cur = path.resolve(base)
    for (let i = 0; i <= maxUp; i += 1) {
      out.push(path.join(cur, 'customer_service'))
      cur = path.dirname(cur)
    }
    return out
  }
  const candidates = [
    defaultDir,
    ...expandUpwardCandidates(process.cwd(), 8),
    ...expandUpwardCandidates(process.resourcesPath, 8),
    ...expandUpwardCandidates(path.dirname(process.execPath), 8),
    ...expandUpwardCandidates(__dirname, 8),
  ]
  const found = candidates.find((p) => isUsableCustomerServiceDir(path.resolve(p)))
  return found ? path.resolve(found) : defaultDir
}

function killAllBuiltinAssistantProcesses() {
  if (builtinCustomerServiceProcess) {
    try {
      builtinCustomerServiceProcess.kill()
    } catch {
      // ignore
    }
    builtinCustomerServiceProcess = null
  }
  for (const spec of BUILTIN_INSTANCE_SPECS) {
    const p = builtinAssistantProcesses[spec.slot]
    if (p) {
      try {
        p.kill()
      } catch {
        // ignore
      }
      delete builtinAssistantProcesses[spec.slot]
    }
  }
}

const GLOBAL_RECORD_HOTKEYS = {
  click: 'CommandOrControl+Shift+R',
  doubleClick: 'CommandOrControl+Shift+D',
  rightClick: 'CommandOrControl+Shift+X',
}

function getAppIconPath(): string {
  const preferredPath = path.join(__dirname, '../TopoClaw7.png')
  const roundedPath = path.join(__dirname, '../Image_PC_rounded.png')
  return app.isPackaged
    ? path.join(process.resourcesPath, 'icon.png')
    : (fs.existsSync(roundedPath) ? roundedPath : (fs.existsSync(preferredPath) ? preferredPath : path.join(__dirname, '../Image_PC.png')))
}

function getTrayIconPath(): string {
  const trayPath = path.join(__dirname, '../Image_PC_tray.png')
  return app.isPackaged
    ? path.join(process.resourcesPath, 'tray-icon.png')
    : (fs.existsSync(trayPath) ? trayPath : getAppIconPath())
}

function showMainWindow(): void {
  if (!mainWindow || mainWindow.isDestroyed()) {
    createWindow()
    return
  }
  if (mainWindow.isMinimized()) mainWindow.restore()
  mainWindow.show()
  mainWindow.focus()
}

function createTray(): void {
  if (tray) return
  try {
    const iconPath = getTrayIconPath()
    let image = nativeImage.createFromPath(iconPath)
    if (image.isEmpty()) {
      image = nativeImage.createFromPath(getAppIconPath())
    }
    if (image.isEmpty()) {
      image = nativeImage.createFromPath(path.join(__dirname, '../TopoClaw6.png'))
    }
    if (image.isEmpty()) {
      image = nativeImage.createFromPath(path.join(__dirname, '../Image_PC.png'))
    }
    if (!image.isEmpty() && process.platform === 'win32') {
      const scaleFactor = Math.max(1, Math.round((electronScreen.getPrimaryDisplay()?.scaleFactor || 1) * 100) / 100)
      const targetSize = Math.round(16 * scaleFactor)
      image = image.resize({ width: targetSize, height: targetSize, quality: 'best' })
    }
    tray = new Tray(image)
    tray.setToolTip('TopoClaw')
    const contextMenu = Menu.buildFromTemplate([
      { label: '打开主界面', click: () => showMainWindow() },
      { type: 'separator' },
      { label: '退出', click: () => app.quit() },
    ])
    tray.setContextMenu(contextMenu)
    tray.on('double-click', () => showMainWindow())
  } catch (e) {
    console.warn('[tray] 无法创建托盘图标（部分 Linux 环境无托盘支持）:', e)
    tray = null
  }
}

function createWindow() {
  const iconPath = getAppIconPath()
  mainWindow = new BrowserWindow({
    width: 1000,
    height: 700,
    minWidth: 800,
    minHeight: 600,
    frame: false,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      webSecurity: true,
      preload: path.join(__dirname, 'preload.js'),
    },
    icon: iconPath,
    show: false,
  })

  ipcMain.on('window:minimize', () => mainWindow?.minimize())
  ipcMain.on('window:maximize', () => (mainWindow?.isMaximized() ? mainWindow.unmaximize() : mainWindow?.maximize()))
  ipcMain.on('window:close', () => mainWindow?.close())
  ipcMain.handle('window:isMaximized', () => mainWindow?.isMaximized() ?? false)
  ipcMain.handle('app:get-version', () => app.getVersion())
  ipcMain.handle('app:notify-new-message', (_e, totalUnread: number) => {
    const n = Math.max(0, Math.floor(Number(totalUnread) || 0))
    if (n > 0) applyTaskbarNotification(n)
    else clearTaskbarNotification()
  })

  ipcMain.handle(
    'app:save-image-data-url',
    async (
      _e,
      payload: { dataUrl?: string; defaultFileName?: string }
    ): Promise<{ ok: boolean; canceled?: boolean; error?: string }> => {
      const dataUrl = payload?.dataUrl
      const defaultFileName = payload?.defaultFileName || 'image.png'
      if (typeof dataUrl !== 'string' || !dataUrl.startsWith('data:')) {
        return { ok: false, error: 'invalid data URL' }
      }
      const comma = dataUrl.indexOf(',')
      if (comma < 0) return { ok: false, error: 'invalid data URL' }
      const header = dataUrl.slice(0, comma)
      const b64raw = dataUrl.slice(comma + 1).replace(/\s/g, '')
      let ext = 'png'
      if (header.includes('image/jpeg')) ext = 'jpg'
      else if (header.includes('image/webp')) ext = 'webp'
      else if (header.includes('image/gif')) ext = 'gif'
      const baseName = defaultFileName.replace(/[/\\?%*:|"<>]/g, '_')
      let defaultPath = baseName
      if (!/\.(png|jpe?g|webp|gif)$/i.test(baseName)) {
        const noExt = baseName.replace(/\.[^.]+$/, '')
        defaultPath = `${noExt || 'image'}.${ext}`
      }
      const win = BrowserWindow.getFocusedWindow() ?? mainWindow
      if (!win || win.isDestroyed()) return { ok: false, error: 'no window' }
      const { canceled, filePath } = await dialog.showSaveDialog(win, {
        title: '图片另存为',
        defaultPath,
        filters: [
          { name: '图片', extensions: ['png', 'jpg', 'jpeg', 'webp', 'gif'] },
          { name: '所有文件', extensions: ['*'] },
        ],
      })
      if (canceled || !filePath) return { ok: false, canceled: true }
      try {
        const buf = Buffer.from(b64raw, 'base64')
        await fs.promises.writeFile(filePath, buf)
        return { ok: true }
      } catch (err) {
        return { ok: false, error: String(err) }
      }
    }
  )

  ipcMain.handle(
    'app:save-chat-image-to-workspace',
    async (
      _e,
      payload: { dataUrl?: string; originalFileName?: string }
    ): Promise<{ ok: boolean; path?: string; error?: string }> => {
      const dataUrl = payload?.dataUrl
      if (typeof dataUrl !== 'string' || !dataUrl.startsWith('data:')) {
        return { ok: false, error: 'invalid data URL' }
      }
      const comma = dataUrl.indexOf(',')
      if (comma < 0) return { ok: false, error: 'invalid data URL' }
      const header = dataUrl.slice(0, comma)
      const b64raw = dataUrl.slice(comma + 1).replace(/\s/g, '')
      let ext = 'png'
      if (header.includes('image/jpeg')) ext = 'jpg'
      else if (header.includes('image/webp')) ext = 'webp'
      else if (header.includes('image/gif')) ext = 'gif'
      else if (header.includes('image/bmp')) ext = 'bmp'

      const workspaceDir = resolveOpenclawWorkspaceDir()
      if (!workspaceDir) {
        return { ok: false, error: 'workspace 目录不存在' }
      }
      const uploadDir = path.join(workspaceDir, 'chat_uploads')
      const rawName = (payload?.originalFileName || 'image').replace(/[/\\?%*:|"<>]/g, '_')
      const stem = (path.parse(rawName).name || 'image').slice(0, 64)
      const stamp = new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14)
      const fileName = `${stamp}_${Math.random().toString(36).slice(2, 8)}_${stem}.${ext}`
      const filePath = path.join(uploadDir, fileName)
      try {
        await fs.promises.mkdir(uploadDir, { recursive: true })
        const buf = Buffer.from(b64raw, 'base64')
        await fs.promises.writeFile(filePath, buf)
        return { ok: true, path: filePath }
      } catch (e) {
        return { ok: false, error: String(e) }
      }
    }
  )

  ipcMain.handle(
    'app:save-chat-file-to-workspace',
    async (
      _e,
      payload: { dataUrl?: string; originalFileName?: string; batchDir?: string }
    ): Promise<{ ok: boolean; path?: string; error?: string }> => {
      const dataUrl = payload?.dataUrl
      if (typeof dataUrl !== 'string' || !dataUrl.startsWith('data:')) {
        return { ok: false, error: 'invalid data URL' }
      }
      const comma = dataUrl.indexOf(',')
      if (comma < 0) return { ok: false, error: 'invalid data URL' }
      const b64raw = dataUrl.slice(comma + 1).replace(/\s/g, '')

      const workspaceDir = resolveOpenclawWorkspaceDir()
      if (!workspaceDir) {
        return { ok: false, error: 'workspace 目录不存在' }
      }
      const rawBatch = typeof payload?.batchDir === 'string' ? payload.batchDir.trim() : ''
      const safeBatch = rawBatch.replace(/[^a-zA-Z0-9_-]/g, '').slice(0, 48)
      const uploadDir = safeBatch
        ? path.join(workspaceDir, 'chat_uploads', safeBatch)
        : path.join(workspaceDir, 'chat_uploads')
      const rawName = (payload?.originalFileName || 'file.bin').replace(/[/\\?%*:|"<>]/g, '_')
      const parsed = path.parse(rawName)
      const stem = (parsed.name || 'file').slice(0, 64)
      const extFromName = (parsed.ext || '').replace(/^\./, '')
      const ext = (extFromName || 'bin').slice(0, 16)
      const stamp = new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14)
      const fileName = `${stamp}_${Math.random().toString(36).slice(2, 8)}_${stem}.${ext}`
      const filePath = path.join(uploadDir, fileName)
      try {
        await fs.promises.mkdir(uploadDir, { recursive: true })
        const buf = Buffer.from(b64raw, 'base64')
        await fs.promises.writeFile(filePath, buf)
        return { ok: true, path: filePath }
      } catch (e) {
        return { ok: false, error: String(e) }
      }
    }
  )

  ipcMain.handle(
    'app:read-workspace-profile-file',
    async (
      _e,
      payload: { kind?: WorkspaceProfileKind }
    ): Promise<{ ok: boolean; content?: string; path?: string; error?: string }> => {
      const kind = payload?.kind
      if (kind !== 'soul' && kind !== 'memory') {
        return { ok: false, error: 'invalid kind' }
      }
      const filePath = resolveWorkspaceProfileFilePath(kind)
      if (!filePath) {
        return { ok: false, error: 'workspace 目录不存在' }
      }
      try {
        if (!fs.existsSync(filePath)) {
          return { ok: true, content: '', path: filePath }
        }
        const content = await fs.promises.readFile(filePath, 'utf-8')
        return { ok: true, content, path: filePath }
      } catch (e) {
        return { ok: false, error: String(e), path: filePath }
      }
    }
  )

  ipcMain.handle(
    'app:write-workspace-profile-file',
    async (
      _e,
      payload: { kind?: WorkspaceProfileKind; content?: string }
    ): Promise<{ ok: boolean; path?: string; error?: string }> => {
      const kind = payload?.kind
      if (kind !== 'soul' && kind !== 'memory') {
        return { ok: false, error: 'invalid kind' }
      }
      const filePath = resolveWorkspaceProfileFilePath(kind)
      if (!filePath) {
        return { ok: false, error: 'workspace 目录不存在' }
      }
      try {
        await fs.promises.mkdir(path.dirname(filePath), { recursive: true })
        await fs.promises.writeFile(filePath, String(payload?.content ?? ''), 'utf-8')
        return { ok: true, path: filePath }
      } catch (e) {
        return { ok: false, error: String(e), path: filePath }
      }
    }
  )

  ipcMain.handle(
    'app:read-workspace-profile-default-file',
    async (
      _e,
      payload: { kind?: WorkspaceProfileKind }
    ): Promise<{ ok: boolean; content?: string; path?: string; error?: string }> => {
      const kind = payload?.kind
      if (kind !== 'soul' && kind !== 'memory') {
        return { ok: false, error: 'invalid kind' }
      }
      const filePath = resolveWorkspaceProfileDefaultFilePath(kind)
      if (!filePath) {
        return { ok: false, error: 'workspace 默认模板目录不存在' }
      }
      try {
        if (!fs.existsSync(filePath)) {
          return { ok: false, error: '默认模板文件不存在', path: filePath }
        }
        const content = await fs.promises.readFile(filePath, 'utf-8')
        return { ok: true, content, path: filePath }
      } catch (e) {
        return { ok: false, error: String(e), path: filePath }
      }
    }
  )

  ipcMain.handle(
    'app:read-conversation-summary-file',
    async (): Promise<{ ok: boolean; content?: string; path?: string; error?: string }> => {
      const filePath = resolveConversationSummaryFilePath()
      if (!filePath) {
        return { ok: false, error: 'workspace 目录不存在' }
      }
      try {
        if (!fs.existsSync(filePath)) {
          return { ok: true, content: '', path: filePath }
        }
        const content = await fs.promises.readFile(filePath, 'utf-8')
        return { ok: true, content, path: filePath }
      } catch (e) {
        return { ok: false, error: String(e), path: filePath }
      }
    }
  )

  ipcMain.handle(
    'app:write-conversation-summary-file',
    async (
      _e,
      payload: { content?: string }
    ): Promise<{ ok: boolean; path?: string; error?: string }> => {
      const filePath = resolveConversationSummaryFilePath()
      if (!filePath) {
        return { ok: false, error: 'workspace 目录不存在' }
      }
      try {
        await fs.promises.mkdir(path.dirname(filePath), { recursive: true })
        await fs.promises.writeFile(filePath, String(payload?.content ?? ''), 'utf-8')
        return { ok: true, path: filePath }
      } catch (e) {
        return { ok: false, error: String(e), path: filePath }
      }
    }
  )

  ipcMain.handle(
    'app:list-workspace-files',
    async (
      _e,
      payload?: { maxFiles?: number; maxBytes?: number }
    ): Promise<{
      ok: boolean
      workspaceDir?: string
      files?: Array<{ relativePath: string; content: string }>
      error?: string
    }> => {
      const workspaceDir = resolveOpenclawWorkspaceDir()
      if (!workspaceDir) return { ok: false, error: 'workspace 目录不存在' }
      const maxFiles = Math.min(5000, Math.max(100, Number(payload?.maxFiles || 1200)))
      const maxBytes = Math.min(1024 * 1024, Math.max(16 * 1024, Number(payload?.maxBytes || 256 * 1024)))
      const skipDir = new Set(['.git', '.hg', '.svn', '.idea', '.vscode', 'node_modules', '__pycache__', '.venv', 'venv'])
      const out: Array<{ relativePath: string; content: string }> = []
      const stack: string[] = [workspaceDir]
      try {
        while (stack.length > 0 && out.length < maxFiles) {
          const dir = stack.pop() as string
          const entries = await fs.promises.readdir(dir, { withFileTypes: true })
          for (const entry of entries) {
            const fullPath = path.join(dir, entry.name)
            if (entry.isDirectory()) {
              if (!skipDir.has(entry.name.toLowerCase())) stack.push(fullPath)
              continue
            }
            if (!entry.isFile()) continue
            const rel = path.relative(workspaceDir, fullPath).replace(/\\/g, '/')
            if (!rel) continue
            let content = ''
            try {
              const stat = await fs.promises.stat(fullPath)
              if (stat.size > maxBytes) {
                content = '[文件较大，已跳过自动加载，请手动打开查看]'
              } else {
                const raw = await fs.promises.readFile(fullPath)
                content = isLikelyTextBuffer(raw)
                  ? raw.toString('utf-8')
                  : '[二进制文件，暂不支持文本预览]'
              }
            } catch (err) {
              content = `[读取文件失败] ${err instanceof Error ? err.message : String(err)}`
            }
            out.push({ relativePath: `workspace/${rel}`, content })
            if (out.length >= maxFiles) break
          }
        }
        return { ok: true, workspaceDir, files: out }
      } catch (err) {
        return { ok: false, error: err instanceof Error ? err.message : String(err), workspaceDir }
      }
    }
  )

  ipcMain.handle(
    'app:pick-folder-files',
    async (
      _e,
      payload?: { maxFiles?: number; maxBytes?: number }
    ): Promise<{
      ok: boolean
      canceled?: boolean
      folderPath?: string
      files?: Array<{ relativePath: string; content: string }>
      error?: string
    }> => {
      const win = BrowserWindow.getFocusedWindow() ?? mainWindow
      if (!win || win.isDestroyed()) return { ok: false, error: 'no window' }
      const picked = await dialog.showOpenDialog(win, {
        title: '选择文件夹',
        properties: ['openDirectory'],
      })
      if (picked.canceled || !picked.filePaths?.[0]) {
        return { ok: false, canceled: true }
      }
      const folderPath = picked.filePaths[0]
      const maxFiles = Math.max(100, Math.min(5000, payload?.maxFiles ?? 1200))
      const maxBytes = Math.max(8 * 1024, Math.min(2 * 1024 * 1024, payload?.maxBytes ?? 256 * 1024))
      const out: Array<{ relativePath: string; content: string }> = []
      const stack: string[] = [folderPath]
      try {
        while (stack.length > 0 && out.length < maxFiles) {
          const dir = stack.pop() as string
          const entries = await fs.promises.readdir(dir, { withFileTypes: true })
          for (const entry of entries) {
            const fullPath = path.join(dir, entry.name)
            if (entry.isDirectory()) {
              stack.push(fullPath)
              continue
            }
            if (!entry.isFile()) continue
            const rel = path.relative(folderPath, fullPath).replace(/\\/g, '/')
            if (!rel) continue
            let content = ''
            try {
              const stat = await fs.promises.stat(fullPath)
              if (stat.size > maxBytes) {
                content = '[文件过大，已跳过内容读取]'
              } else {
                const raw = await fs.promises.readFile(fullPath)
                content = isLikelyTextBuffer(raw) ? raw.toString('utf-8') : '[二进制文件，暂不支持文本预览]'
              }
            } catch (err) {
              content = `[读取文件失败] ${err instanceof Error ? err.message : String(err)}`
            }
            out.push({ relativePath: rel, content })
            if (out.length >= maxFiles) break
          }
        }
        return { ok: true, folderPath, files: out }
      } catch (err) {
        return {
          ok: false,
          folderPath,
          error: err instanceof Error ? err.message : String(err),
        }
      }
    }
  )

  ipcMain.handle(
    'app:list-folder-files',
    async (
      _e,
      payload?: { folderPath?: string; maxFiles?: number; maxBytes?: number }
    ): Promise<{
      ok: boolean
      folderPath?: string
      files?: Array<{ relativePath: string; content: string }>
      error?: string
    }> => {
      const folderPath = String(payload?.folderPath || '').trim()
      if (!folderPath) return { ok: false, error: 'invalid folderPath' }
      const maxFiles = Math.max(100, Math.min(5000, payload?.maxFiles ?? 1200))
      const maxBytes = Math.max(8 * 1024, Math.min(2 * 1024 * 1024, payload?.maxBytes ?? 256 * 1024))
      const out: Array<{ relativePath: string; content: string }> = []
      const stack: string[] = [folderPath]
      try {
        while (stack.length > 0 && out.length < maxFiles) {
          const dir = stack.pop() as string
          const entries = await fs.promises.readdir(dir, { withFileTypes: true })
          for (const entry of entries) {
            const fullPath = path.join(dir, entry.name)
            if (entry.isDirectory()) {
              stack.push(fullPath)
              continue
            }
            if (!entry.isFile()) continue
            const rel = path.relative(folderPath, fullPath).replace(/\\/g, '/')
            if (!rel) continue
            let content = ''
            try {
              const stat = await fs.promises.stat(fullPath)
              if (stat.size > maxBytes) {
                content = '[文件过大，已跳过内容读取]'
              } else {
                const raw = await fs.promises.readFile(fullPath)
                content = isLikelyTextBuffer(raw) ? raw.toString('utf-8') : '[二进制文件，暂不支持文本预览]'
              }
            } catch (err) {
              content = `[读取文件失败] ${err instanceof Error ? err.message : String(err)}`
            }
            out.push({ relativePath: rel, content })
            if (out.length >= maxFiles) break
          }
        }
        return { ok: true, folderPath, files: out }
      } catch (err) {
        return {
          ok: false,
          folderPath,
          error: err instanceof Error ? err.message : String(err),
        }
      }
    }
  )

  ipcMain.handle(
    'app:save-csv',
    async (
      _e,
      payload: { text?: string; defaultFileName?: string }
    ): Promise<{ ok: boolean; canceled?: boolean; error?: string; path?: string }> => {
      const win = BrowserWindow.getFocusedWindow() ?? mainWindow
      if (!win || win.isDestroyed()) return { ok: false, error: 'no window' }
      const rawName = String(payload?.defaultFileName || 'table.csv').trim() || 'table.csv'
      const safeName = rawName.replace(/[/\\?%*:|"<>]/g, '_')
      const defaultPath = /\.csv$/i.test(safeName) ? safeName : `${safeName}.csv`
      const { canceled, filePath } = await dialog.showSaveDialog(win, {
        title: '保存表格为 CSV',
        defaultPath,
        filters: [
          { name: 'CSV 文件', extensions: ['csv'] },
          { name: '所有文件', extensions: ['*'] },
        ],
      })
      if (canceled || !filePath) return { ok: false, canceled: true }
      try {
        // Add UTF-8 BOM so Excel on Windows can detect encoding correctly.
        await fs.promises.writeFile(filePath, `\uFEFF${String(payload?.text ?? '')}`, 'utf-8')
        return { ok: true, path: filePath }
      } catch (err) {
        return { ok: false, error: String(err) }
      }
    }
  )

  ipcMain.handle(
    'app:save-text-as',
    async (
      _e,
      payload: { text?: string; defaultFileName?: string }
    ): Promise<{ ok: boolean; canceled?: boolean; error?: string; path?: string }> => {
      const win = BrowserWindow.getFocusedWindow() ?? mainWindow
      if (!win || win.isDestroyed()) return { ok: false, error: 'no window' }
      const rawName = String(payload?.defaultFileName || 'document.md').trim() || 'document.md'
      const safeName = rawName.replace(/[/\\?%*:|"<>]/g, '_')
      const defaultPath = /\.(md|markdown|txt)$/i.test(safeName) ? safeName : `${safeName}.md`
      const { canceled, filePath } = await dialog.showSaveDialog(win, {
        title: '保存文件',
        defaultPath,
        filters: [
          { name: 'Markdown', extensions: ['md', 'markdown'] },
          { name: '文本', extensions: ['txt'] },
          { name: '所有文件', extensions: ['*'] },
        ],
      })
      if (canceled || !filePath) return { ok: false, canceled: true }
      try {
        await fs.promises.writeFile(filePath, String(payload?.text ?? ''), 'utf-8')
        return { ok: true, path: filePath }
      } catch (err) {
        return { ok: false, error: String(err) }
      }
    }
  )

  ipcMain.handle(
    'app:write-text-file',
    async (
      _e,
      payload: { filePath?: string; text?: string }
    ): Promise<{ ok: boolean; path?: string; error?: string }> => {
      const filePath = String(payload?.filePath || '').trim()
      if (!filePath) return { ok: false, error: 'invalid filePath' }
      try {
        await fs.promises.mkdir(path.dirname(filePath), { recursive: true })
        await fs.promises.writeFile(filePath, String(payload?.text ?? ''), 'utf-8')
        return { ok: true, path: filePath }
      } catch (err) {
        return { ok: false, error: String(err), path: filePath }
      }
    }
  )

  ipcMain.handle(
    'app:read-text-file',
    async (
      _e,
      payload: { filePath?: string }
    ): Promise<{ ok: boolean; path?: string; content?: string; error?: string }> => {
      const filePath = String(payload?.filePath || '').trim()
      if (!filePath) return { ok: false, error: 'invalid filePath' }
      try {
        const stat = await fs.promises.stat(filePath)
        const maxBytes = 256 * 1024
        if (stat.size > maxBytes) {
          return { ok: true, path: filePath, content: '[文件过大，已跳过内容读取]' }
        }
        const raw = await fs.promises.readFile(filePath)
        const content = isLikelyTextBuffer(raw)
          ? raw.toString('utf-8')
          : '[二进制文件，暂不支持文本预览]'
        return { ok: true, path: filePath, content }
      } catch (err) {
        return { ok: false, error: String(err), path: filePath }
      }
    }
  )

  ipcMain.handle(
    'app:read-binary-file',
    async (
      _e,
      payload: { filePath?: string }
    ): Promise<{ ok: boolean; path?: string; base64?: string; error?: string }> => {
      const filePath = String(payload?.filePath || '').trim()
      if (!filePath) return { ok: false, error: 'invalid filePath' }
      try {
        const raw = await fs.promises.readFile(filePath)
        return { ok: true, path: filePath, base64: raw.toString('base64') }
      } catch (err) {
        return { ok: false, error: String(err), path: filePath }
      }
    }
  )

  ipcMain.handle(
    'app:copy-image',
    async (
      _e,
      opts: { dataUrl?: string; url?: string; fileUrl?: string }
    ): Promise<{ ok: boolean; error?: string }> => {
      try {
        if (typeof opts?.dataUrl === 'string' && opts.dataUrl.startsWith('data:')) {
          const img = nativeImage.createFromDataURL(opts.dataUrl)
          if (img.isEmpty()) return { ok: false, error: '无法解析图片' }
          clipboard.writeImage(img)
          return { ok: true }
        }
        if (typeof opts?.fileUrl === 'string' && opts.fileUrl.startsWith('file:')) {
          const p = fileURLToPath(opts.fileUrl)
          const img = nativeImage.createFromPath(p)
          if (img.isEmpty()) return { ok: false, error: '无法读取文件' }
          clipboard.writeImage(img)
          return { ok: true }
        }
        if (typeof opts?.url === 'string' && /^https?:\/\//i.test(opts.url)) {
          const res = await fetch(opts.url)
          if (!res.ok) return { ok: false, error: `下载失败 HTTP ${res.status}` }
          const buf = Buffer.from(await res.arrayBuffer())
          const img = nativeImage.createFromBuffer(buf)
          if (img.isEmpty()) return { ok: false, error: '无法解析图片' }
          clipboard.writeImage(img)
          return { ok: true }
        }
        return { ok: false, error: '不支持的图片地址' }
      } catch (e) {
        return { ok: false, error: String(e) }
      }
    }
  )

  /** 供渲染进程 paste 同步读取系统剪贴板位图（writeImage 复制的图在 textarea 的 clipboardData 里常拿不到） */
  ipcMain.on('app:read-clipboard-image-sync', (event) => {
    try {
      const img = clipboard.readImage()
      if (img.isEmpty()) {
        event.returnValue = null
        return
      }
      const png = img.toPNG()
      event.returnValue = png.length > 0 ? png.toString('base64') : null
    } catch {
      event.returnValue = null
    }
  })

  const sendMaximizedState = () => {
    mainWindow?.webContents.send('window:maximized-change', mainWindow.isMaximized())
  }
  mainWindow.on('maximize', sendMaximizedState)
  mainWindow.on('unmaximize', sendMaximizedState)
  /** 将主窗口激活到前台，Computer Use 任务结束后回到应用 */
  ipcMain.on('window:focus', () => {
    showMainWindow()
  })
  ipcMain.handle('xiaotuo:open', async () => {
    try {
      startXiaoTuoMonitor()
      return { ok: true, enabled: true }
    } catch (e) {
      return { ok: false, enabled: false, error: String(e) }
    }
  })
  ipcMain.handle('xiaotuo:close', async () => {
    try {
      stopXiaoTuoMonitor()
      return { ok: true, enabled: false }
    } catch (e) {
      return { ok: false, enabled: xiaotuoEnabled, error: String(e) }
    }
  })
  ipcMain.handle('xiaotuo:status', async () => {
    return { ok: true, enabled: xiaotuoEnabled }
  })
  ipcMain.handle(
    'xiaotuo:ask-topoclaw',
    async (_e, payload?: { prompt?: string; fileType?: string; appName?: string }): Promise<{ ok: boolean; error?: string }> => {
      const fileType = String(payload?.fileType || '').trim().toLowerCase()
      const appName = String(payload?.appName || '').trim()
      const userPrompt = String(payload?.prompt || '').trim()
      const noun = fileType ? `.${fileType}` : '文档'
      const prefix = appName ? `我正在使用 ${appName} 处理 ${noun} 文件。` : `我正在处理 ${noun} 文件。`
      const text = userPrompt ? `${prefix}\n${userPrompt}` : `${prefix}\n请先帮我分析这个文件可以怎么处理。`
      if (mainWindow && !mainWindow.isDestroyed()) {
        mainWindow.webContents.send('desktop-screenshot:prefill', {
          text,
          autoSend: false,
          forceTopoClaw: true,
          skipComposerImage: true,
        })
        showMainWindow()
      }
      return { ok: true }
    }
  )

  if (process.env.NODE_ENV === 'development' || !app.isPackaged) {
    mainWindow.loadURL('http://localhost:5173')
    try { mainWindow.webContents.openDevTools() } catch (_) { /* ignore */ }
  } else {
    // 打包后：asarUnpack 将 dist 解压到 app.asar.unpacked，需从该路径加载
    const appPath = app.getAppPath()
    const indexPath = appPath.includes('.asar')
      ? path.join(appPath.replace(/app\.asar$/, 'app.asar.unpacked'), 'dist', 'index.html')
      : path.join(__dirname, '../dist/index.html')
    mainWindow.loadFile(indexPath)
  }

  mainWindow.once('ready-to-show', () => {
    if (mainWindow && !mainWindow.isDestroyed() && !mainWindow.isMaximized()) {
      mainWindow.maximize()
    }
    mainWindow?.show()
  })

  mainWindow.on('close', (e) => {
    if (appIsQuitting) return
    // 托盘创建失败时仍允许正常关窗退出，避免界面被 hide 后无法唤回
    if (!tray) return
    e.preventDefault()
    if (mainWindow && !mainWindow.isDestroyed()) mainWindow.hide()
  })

  /** 新消息任务栏提示：窗口获得焦点时清除（方案 A） */
  mainWindow.on('focus', () => {
    clearTaskbarNotification()
  })
}

function clearTaskbarNotification(): void {
  if (!mainWindow || mainWindow.isDestroyed()) return
  mainWindow.flashFrame(false)
}

function applyTaskbarNotification(count: number): void {
  if (!mainWindow || mainWindow.isDestroyed()) return
  if (mainWindow.isFocused()) return
  mainWindow.flashFrame(true)
  if (process.platform === 'darwin') {
    app.dock?.bounce('informational')
  }
}

/** 将 DIP 转为物理像素（nut-js 使用物理像素） */
function dipToPhysical(x: number, y: number): { x: number; y: number } {
  if (process.platform === 'darwin') return { x, y }  // macOS 无此 API
  try {
    const p = electronScreen.dipToScreenPoint({ x, y })
    return { x: Math.round(p.x), y: Math.round(p.y) }
  } catch {
    return { x, y }
  }
}

function registerGlobalRecordShortcuts() {
  const send = (action: string, x: number, y: number) => {
    mainWindow?.webContents.send('pc-record:action', { action, x, y })
    if (trajectoryOverlayWindow && !trajectoryOverlayWindow.isDestroyed()) {
      trajectoryOverlayWindow.webContents.send('trajectory:point', { action, x, y })
    }
  }
  globalShortcut.register(GLOBAL_RECORD_HOTKEYS.click, () => {
    const dip = electronScreen.getCursorScreenPoint()
    const p = dipToPhysical(dip.x, dip.y)
    send('click', p.x, p.y)
  })
  globalShortcut.register(GLOBAL_RECORD_HOTKEYS.doubleClick, () => {
    const dip = electronScreen.getCursorScreenPoint()
    const p = dipToPhysical(dip.x, dip.y)
    send('double_click', p.x, p.y)
  })
  globalShortcut.register(GLOBAL_RECORD_HOTKEYS.rightClick, () => {
    const dip = electronScreen.getCursorScreenPoint()
    const p = dipToPhysical(dip.x, dip.y)
    send('right_click', p.x, p.y)
  })
}

function unregisterGlobalRecordShortcuts() {
  globalShortcut.unregister(GLOBAL_RECORD_HOTKEYS.click)
  globalShortcut.unregister(GLOBAL_RECORD_HOTKEYS.doubleClick)
  globalShortcut.unregister(GLOBAL_RECORD_HOTKEYS.rightClick)
}

/** 获取虚拟屏幕的完整边界（覆盖所有显示器） */
function getVirtualScreenBounds(): { x: number; y: number; width: number; height: number } {
  const displays = electronScreen.getAllDisplays()
  let minX = 0
  let minY = 0
  let maxX = 0
  let maxY = 0
  for (const d of displays) {
    const b = d.bounds
    minX = Math.min(minX, b.x)
    minY = Math.min(minY, b.y)
    maxX = Math.max(maxX, b.x + b.width)
    maxY = Math.max(maxY, b.y + b.height)
  }
  return { x: minX, y: minY, width: maxX - minX, height: maxY - minY }
}

function clearScreenshotAssistAutoCloseTimer(): void {
  if (screenshotAssistAutoCloseTimer) {
    clearTimeout(screenshotAssistAutoCloseTimer)
    screenshotAssistAutoCloseTimer = null
  }
}

function getClipboardImagePngBase64(): string | null {
  try {
    const image = clipboard.readImage()
    if (!image || image.isEmpty()) return null
    const png = image.toPNG()
    if (!png || png.length === 0) return null
    return png.toString('base64')
  } catch {
    return null
  }
}

function closeScreenshotAssistOverlay(): void {
  clearScreenshotAssistAutoCloseTimer()
  if (screenshotAssistOverlayWindow && !screenshotAssistOverlayWindow.isDestroyed()) {
    screenshotAssistOverlayWindow.close()
  }
  screenshotAssistOverlayWindow = null
}

function resolveScreenshotAssistOverlayPath(): string {
  if (app.isPackaged) {
    const appPath = app.getAppPath()
    const distBase = appPath.includes('.asar')
      ? path.join(appPath.replace(/app\.asar$/, 'app.asar.unpacked'), 'dist')
      : path.join(__dirname, '../dist')
    return path.join(distBase, 'screenshot-assist-overlay.html')
  }
  return path.join(__dirname, '../public/screenshot-assist-overlay.html')
}

function showScreenshotAssistOverlay(): void {
  const cursor = electronScreen.getCursorScreenPoint()
  const display = electronScreen.getDisplayNearestPoint(cursor)
  const area = display.workArea
  const width = 320
  const height = 180
  // 外部截图工具不暴露选区坐标，这里以“当前光标点”为中心做最佳估计。
  const x = Math.min(Math.max(Math.round(cursor.x - width / 2), area.x), area.x + area.width - width)
  const y = Math.min(Math.max(Math.round(cursor.y - height / 2), area.y), area.y + area.height - height)

  if (!screenshotAssistOverlayWindow || screenshotAssistOverlayWindow.isDestroyed()) {
    screenshotAssistOverlayWindow = new BrowserWindow({
      width,
      height,
      x,
      y,
      frame: false,
      transparent: true,
      /** 与透明窗口配合，避免出现整块半透明灰蒙层盖住桌面 */
      backgroundColor: '#00000000',
      alwaysOnTop: true,
      skipTaskbar: true,
      resizable: false,
      minimizable: false,
      maximizable: false,
      hasShadow: false,
      fullscreenable: false,
      webPreferences: {
        nodeIntegration: false,
        contextIsolation: true,
        preload: path.join(__dirname, 'screenshotAssistPreload.js'),
      },
    })
    screenshotAssistOverlayWindow.setAlwaysOnTop(true, 'screen-saver')
    screenshotAssistOverlayWindow.loadFile(resolveScreenshotAssistOverlayPath())
    screenshotAssistOverlayWindow.on('blur', () => {
      // 与需求一致：点击其他地方后输入框应消失，直接关闭整个浮窗更明确
      closeScreenshotAssistOverlay()
    })
    screenshotAssistOverlayWindow.on('closed', () => {
      screenshotAssistOverlayWindow = null
      clearScreenshotAssistAutoCloseTimer()
    })
  } else {
    screenshotAssistOverlayWindow.setBounds({ x, y, width, height })
    screenshotAssistOverlayWindow.show()
    screenshotAssistOverlayWindow.focus()
  }
  clearScreenshotAssistAutoCloseTimer()
  screenshotAssistAutoCloseTimer = setTimeout(() => {
    closeScreenshotAssistOverlay()
  }, 20000)
}

function resolveScreenshotRegionOverlayPath(): string {
  if (app.isPackaged) {
    const appPath = app.getAppPath()
    const distBase = appPath.includes('.asar')
      ? path.join(appPath.replace(/app\.asar$/, 'app.asar.unpacked'), 'dist')
      : path.join(__dirname, '../dist')
    return path.join(distBase, 'screenshot-region-overlay.html')
  }
  return path.join(__dirname, '../public/screenshot-region-overlay.html')
}

function closeScreenshotRegionOverlay(): void {
  if (screenshotRegionOverlayWindow && !screenshotRegionOverlayWindow.isDestroyed()) {
    screenshotRegionOverlayWindow.close()
  }
  screenshotRegionOverlayWindow = null
}

function openScreenshotRegionOverlay(): void {
  const bounds = getVirtualScreenBounds()
  if (screenshotRegionOverlayWindow && !screenshotRegionOverlayWindow.isDestroyed()) {
    screenshotRegionOverlayWindow.setBounds(bounds)
    screenshotRegionOverlayWindow.show()
    screenshotRegionOverlayWindow.focus()
    return
  }
  screenshotRegionOverlayWindow = new BrowserWindow({
    x: bounds.x,
    y: bounds.y,
    width: bounds.width,
    height: bounds.height,
    transparent: true,
    backgroundColor: '#00000000',
    frame: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    resizable: false,
    hasShadow: false,
    focusable: true,
    fullscreenable: false,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      preload: path.join(__dirname, 'screenshotRegionPreload.js'),
    },
  })
  screenshotRegionOverlayWindow.setAlwaysOnTop(true, 'screen-saver')
  screenshotRegionOverlayWindow.loadFile(resolveScreenshotRegionOverlayPath())
  screenshotRegionOverlayWindow.on('closed', () => {
    screenshotRegionOverlayWindow = null
  })
  screenshotRegionOverlayWindow.show()
  screenshotRegionOverlayWindow.focus()
}

async function handleScreenshotRegionConfirm(dipRect: { x: number; y: number; width: number; height: number }): Promise<void> {
  closeScreenshotRegionOverlay()
  await new Promise((r) => setTimeout(r, 100))
  const physical = dipScreenRectToPhysicalRect(dipRect)
  const w = Math.max(1, Math.round(physical.width))
  const h = Math.max(1, Math.round(physical.height))
  const x = Math.round(physical.x)
  const y = Math.round(physical.y)
  try {
    const img = await screen.grabRegion(new Region(x, y, w, h))
    const tempDir = app.getPath('temp')
    const tempPath = path.join(tempDir, `region-shot-${Date.now()}.png`)
    await saveImage({ image: img, path: tempPath })
    try {
      const pngBuf = fs.readFileSync(tempPath)
      const nImg = nativeImage.createFromBuffer(pngBuf)
      if (!nImg.isEmpty()) {
        clipboard.writeImage(nImg)
      }
    } finally {
      try {
        fs.unlinkSync(tempPath)
      } catch {
        /* ignore */
      }
    }
    showScreenshotAssistOverlay()
  } catch (e) {
    console.error('handleScreenshotRegionConfirm', e)
  }
}

function createTrajectoryOverlay(mode: 'a' | 'b') {
  if (trajectoryOverlayWindow && !trajectoryOverlayWindow.isDestroyed()) return
  trajectoryMode = mode
  const bounds = getVirtualScreenBounds()
  trajectoryOverlayWindow = new BrowserWindow({
    x: bounds.x,
    y: bounds.y,
    width: bounds.width,
    height: bounds.height,
    transparent: true,
    frame: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    resizable: false,
    hasShadow: false,
    focusable: false,
    webPreferences: {
      nodeIntegration: false,
      contextIsolation: true,
      preload: path.join(__dirname, 'trajectoryPreload.js'),
    },
  })
  trajectoryOverlayWindow.setIgnoreMouseEvents(mode === 'a', { forward: true })
  const overlayPath =
    app.isPackaged
      ? (() => {
          const appPath = app.getAppPath()
          const distBase = appPath.includes('.asar')
            ? path.join(appPath.replace(/app\.asar$/, 'app.asar.unpacked'), 'dist')
            : path.join(__dirname, '../dist')
          return path.join(distBase, 'trajectory-overlay.html')
        })()
      : path.join(__dirname, '../public/trajectory-overlay.html')
  trajectoryOverlayWindow.loadFile(overlayPath)
  trajectoryOverlayWindow.on('closed', () => {
    trajectoryOverlayWindow = null
  })
  trajectoryOverlayWindow.webContents.once('did-finish-load', () => {
    trajectoryOverlayWindow?.webContents.send('trajectory:init', {
      offsetX: bounds.x,
      offsetY: bounds.y,
      width: bounds.width,
      height: bounds.height,
      mode,
    })
  })
}

function setTrajectoryOverlayMode(mode: 'a' | 'b') {
  trajectoryMode = mode
  if (trajectoryOverlayWindow && !trajectoryOverlayWindow.isDestroyed()) {
    trajectoryOverlayWindow.setIgnoreMouseEvents(mode === 'a', { forward: true })
    trajectoryOverlayWindow.webContents.send('trajectory:set-mode', { mode })
  }
}

function destroyTrajectoryOverlay() {
  if (trajectoryOverlayWindow && !trajectoryOverlayWindow.isDestroyed()) {
    trajectoryOverlayWindow.close()
    trajectoryOverlayWindow = null
  }
}

/** PC 端模拟点击 - IPC 处理 */
function registerComputerUseHandlers() {
  /** 回到桌面（类似 Win+D），Computer Use 开始前统一初始状态 */
  ipcMain.handle('computer-use:show-desktop', async () => {
    try {
      if (process.platform === 'win32') {
        await keyboard.type(Key.LeftSuper, Key.D)
      } else if (process.platform === 'darwin') {
        await keyboard.type(Key.LeftSuper, Key.F3)
      } else {
        await keyboard.type(Key.LeftSuper, Key.D)
      }
      return { ok: true }
    } catch (e) {
      console.error('computer-use:show-desktop', e)
      return { ok: false, error: String(e) }
    }
  })

  ipcMain.handle('computer-use:screenshot', async () => {
    const res = await captureComputerUseScreenshotPngBase64()
    if (res.ok === false) {
      console.error('computer-use:screenshot', res.error)
      return { ok: false, error: res.error }
    }
    return { ok: true, base64: res.base64, width: res.width, height: res.height }
  })

  /** 主进程发起 upload，避免 Win+D 后窗口最小化导致渲染进程 fetch 被节流 */
  ipcMain.handle('computer-use:upload-screenshot', async (_e, params: { uploadUrl: string; requestId: string; query: string; chatSummary?: string | null }) => {
    const { uploadUrl, requestId, query, chatSummary } = params
    try {
      const img = await screen.grab()
      const tempDir = app.getPath('temp')
      const tempPath = path.join(tempDir, `cu-screenshot-${Date.now()}.png`)
      await saveImage({ image: img, path: tempPath })
      const newW = Math.round(img.width * COMPUTER_USE_SCREENSHOT_SCALE)
      const newH = Math.round(img.height * COMPUTER_USE_SCREENSHOT_SCALE)
      const resizedBuf = await sharp(tempPath).resize(newW, newH).png().toBuffer()
      try { fs.unlinkSync(tempPath) } catch { /* ignore */ }
      const base64 = resizedBuf.toString('base64')

      const form = new FormData()
      form.append('uuid', requestId)
      form.append('query', query)
      form.append('images[0]', base64)
      form.append('device_type', 'pc')
      if (chatSummary) form.append('chat_summary', chatSummary)

      const res = await fetch(uploadUrl, { method: 'POST', body: form })
      const action = await res.json()
      if (!res.ok) {
        const detail = (action as { detail?: string })?.detail || `HTTP ${res.status}`
        throw new Error(detail)
      }
      return { ok: true, action }
    } catch (e) {
      console.error('computer-use:upload-screenshot', e)
      return { ok: false, error: String(e) }
    }
  })

  ipcMain.handle('computer-use:click', async (_e, { x, y }: { x: number; y: number }) => {
    try {
      const { x: sx, y: sy } = imageCoordsToScreen(x, y)
      await mouse.move(straightTo(new Point(sx, sy)))
      await mouse.click(Button.LEFT)
      return { ok: true }
    } catch (e) {
      console.error('computer-use:click', e)
      return { ok: false, error: String(e) }
    }
  })

  ipcMain.handle('computer-use:double-click', async (_e, { x, y }: { x: number; y: number }) => {
    try {
      const { x: sx, y: sy } = imageCoordsToScreen(x, y)
      await mouse.move(straightTo(new Point(sx, sy)))
      await mouse.doubleClick(Button.LEFT)
      return { ok: true }
    } catch (e) {
      console.error('computer-use:double-click', e)
      return { ok: false, error: String(e) }
    }
  })

  ipcMain.handle('computer-use:right-click', async (_e, { x, y }: { x: number; y: number }) => {
    try {
      const { x: sx, y: sy } = imageCoordsToScreen(x, y)
      await mouse.move(straightTo(new Point(sx, sy)))
      await mouse.click(Button.RIGHT)
      return { ok: true }
    } catch (e) {
      console.error('computer-use:right-click', e)
      return { ok: false, error: String(e) }
    }
  })

  ipcMain.handle('computer-use:type', async (_e, { text }: { text: string }) => {
    try {
      await pasteText(text)
      return { ok: true }
    } catch (e) {
      console.error('computer-use:type', e)
      return { ok: false, error: String(e) }
    }
  })

  /** 当前光标位置（返回物理像素，与 nut-js 一致） */
  ipcMain.handle('computer-use:cursor-position', () => {
    try {
      const dip = electronScreen.getCursorScreenPoint()
      const p = dipToPhysical(dip.x, dip.y)
      return { ok: true, x: p.x, y: p.y }
    } catch (e) {
      console.error('computer-use:cursor-position', e)
      return { ok: false, error: String(e) }
    }
  })

  ipcMain.handle(
    'computer-use:click-and-type',
    async (_e, { x, y, text }: { x: number; y: number; text: string }) => {
      try {
        const { x: sx, y: sy } = imageCoordsToScreen(x, y)
        await mouse.move(straightTo(new Point(sx, sy)))
        await mouse.click(Button.LEFT)
        await new Promise((r) => setTimeout(r, 150))
        await pasteText(text)
        return { ok: true }
      } catch (e) {
        console.error('computer-use:click-and-type', e)
        return { ok: false, error: String(e) }
      }
    }
  )

  ipcMain.handle('computer-use:move', async (_e, { x, y }: { x: number; y: number }) => {
    try {
      const { x: sx, y: sy } = imageCoordsToScreen(x, y)
      await mouse.move(straightTo(new Point(sx, sy)))
      return { ok: true }
    } catch (e) {
      console.error('computer-use:move', e)
      return { ok: false, error: String(e) }
    }
  })

  ipcMain.handle('computer-use:key-down', async (_e, { keyname }: { keyname: string }) => {
    try {
      const keys = parseKeyCombo(keyname)
      if (keys.length === 0) return { ok: false, error: `无法解析按键: ${keyname}` }
      await keyboard.pressKey(...keys)
      return { ok: true }
    } catch (e) {
      console.error('computer-use:key-down', e)
      return { ok: false, error: String(e) }
    }
  })

  ipcMain.handle('computer-use:key-up', async (_e, { keyname }: { keyname: string }) => {
    try {
      const keys = parseKeyCombo(keyname)
      if (keys.length === 0) return { ok: false, error: `无法解析按键: ${keyname}` }
      await keyboard.releaseKey(...keys)
      return { ok: true }
    } catch (e) {
      console.error('computer-use:key-up', e)
      return { ok: false, error: String(e) }
    }
  })

  ipcMain.handle(
    'computer-use:drag',
    async (_e, { x1, y1, x2, y2 }: { x1: number; y1: number; x2: number; y2: number }) => {
      try {
        const p1 = imageCoordsToScreen(x1, y1)
        const p2 = imageCoordsToScreen(x2, y2)
        const path = [new Point(p1.x, p1.y), new Point(p2.x, p2.y)]
        await mouse.drag(path)
        return { ok: true }
      } catch (e) {
        console.error('computer-use:drag', e)
        return { ok: false, error: String(e) }
      }
    }
  )

  ipcMain.on('pc-record:start', () => {
    try {
      registerGlobalRecordShortcuts()
    } catch (e) {
      console.error('pc-record:start', e)
    }
  })
  ipcMain.on('pc-record:stop', () => {
    try {
      unregisterGlobalRecordShortcuts()
    } catch (e) {
      console.error('pc-record:stop', e)
    }
  })

  ipcMain.on('trajectory:start', (_e, mode?: 'a' | 'b') => {
    try {
      const m = mode === 'b' ? 'b' : 'a'
      createTrajectoryOverlay(m)
      if (m === 'a') registerGlobalRecordShortcuts()
    } catch (e) {
      console.error('trajectory:start', e)
    }
  })
  ipcMain.on('trajectory:set-mode', (_e, mode: 'a' | 'b') => {
    try {
      const m = mode === 'b' ? 'b' : 'a'
      if (trajectoryOverlayWindow && !trajectoryOverlayWindow.isDestroyed()) {
        setTrajectoryOverlayMode(m)
        if (m === 'a') registerGlobalRecordShortcuts()
        else unregisterGlobalRecordShortcuts()
      }
    } catch (e) {
      console.error('trajectory:set-mode', e)
    }
  })
  ipcMain.handle('trajectory:intercepted-click', async (_e, { x, y, action }: { x: number; y: number; action: string }) => {
    try {
      const phys = dipToPhysical(x, y)
      mainWindow?.webContents.send('pc-record:action', { action, x: phys.x, y: phys.y })
      if (trajectoryOverlayWindow && !trajectoryOverlayWindow.isDestroyed()) {
        trajectoryOverlayWindow.webContents.send('trajectory:point', { action, x, y })
        trajectoryOverlayWindow.setIgnoreMouseEvents(true, { forward: true })
      }
      if (action === 'click') {
        await mouse.move(straightTo(new Point(phys.x, phys.y)))
        await mouse.click(Button.LEFT)
      } else if (action === 'double_click') {
        await mouse.move(straightTo(new Point(phys.x, phys.y)))
        await mouse.doubleClick(Button.LEFT)
      } else if (action === 'right_click') {
        await mouse.move(straightTo(new Point(phys.x, phys.y)))
        await mouse.click(Button.RIGHT)
      }
      if (trajectoryOverlayWindow && !trajectoryOverlayWindow.isDestroyed()) {
        setTimeout(() => {
          if (trajectoryOverlayWindow && !trajectoryOverlayWindow.isDestroyed() && trajectoryMode === 'b') {
            trajectoryOverlayWindow.setIgnoreMouseEvents(false, { forward: true })
          }
        }, 80)
      }
      return { ok: true }
    } catch (e) {
      console.error('trajectory:intercepted-click', e)
      if (trajectoryOverlayWindow && !trajectoryOverlayWindow.isDestroyed() && trajectoryMode === 'b') {
        trajectoryOverlayWindow.setIgnoreMouseEvents(false, { forward: true })
      }
      return { ok: false, error: String(e) }
    }
  })
  ipcMain.on('trajectory:stop', () => {
    try {
      unregisterGlobalRecordShortcuts()
      destroyTrajectoryOverlay()
    } catch (e) {
      console.error('trajectory:stop', e)
    }
  })
  ipcMain.on('trajectory:clear', () => {
    try {
      if (trajectoryOverlayWindow && !trajectoryOverlayWindow.isDestroyed()) {
        trajectoryOverlayWindow.webContents.send('trajectory:clear')
      }
    } catch (e) {
      console.error('trajectory:clear', e)
    }
  })

  /** 是否为常见私有局域网段（优先选用，排除 Docker/WSL 等虚拟网卡） */
  function isPrivateLan(addr: string): boolean {
    if (!addr) return false
    if (addr.startsWith('192.168.')) return true   // 192.168.0.0/16
    if (addr.startsWith('10.')) return true        // 10.0.0.0/8
    if (/^172\.(1[6-9]|2[0-9]|3[0-1])\./.test(addr)) return true  // 172.16.0.0/12
    return false
  }

  function extractIpv4FromLine(line: string): string | null {
    const m = line.match(/(\d{1,3}(?:\.\d{1,3}){3})/)
    return m?.[1] ?? null
  }

  /**
   * Windows 下优先读取 ipconfig，与用户在命令行看到的 IPv4 保持一致。
   * 规则：优先「已连接 + 有默认网关 + 有 IPv4」的首个网卡；否则退化到首个「已连接 + 有 IPv4」网卡。
   */
  function getWindowsPreferredIpFromIpconfig(): string | null {
    if (process.platform !== 'win32') return null
    try {
      const output = execSync('chcp 65001>nul && ipconfig', {
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'ignore'],
        shell: 'cmd.exe',
      })
      if (!output.trim()) return null

      type Adapter = {
        connected: boolean
        ipv4: string | null
        hasGateway: boolean
      }

      const adapters: Adapter[] = []
      let current: Adapter | null = null
      let waitingGatewayValue = false
      const pushCurrent = () => {
        if (current) adapters.push(current)
      }

      for (const rawLine of output.split(/\r?\n/)) {
        const line = rawLine
        const trimmed = line.trim()

        // 适配器块标题：无缩进且以冒号结尾
        if (/^\S.*:$/.test(line)) {
          pushCurrent()
          current = { connected: true, ipv4: null, hasGateway: false }
          waitingGatewayValue = false
          continue
        }

        if (!current) continue

        if (/媒体已断开连接|Media disconnected/i.test(trimmed)) {
          current.connected = false
          waitingGatewayValue = false
          continue
        }

        if (/IPv4\s*地址|IPv4\s*Address/i.test(trimmed)) {
          const ip = extractIpv4FromLine(trimmed)
          if (ip) current.ipv4 = ip
          continue
        }

        if (/默认网关|Default Gateway/i.test(trimmed)) {
          const gw = extractIpv4FromLine(trimmed)
          current.hasGateway = !!gw
          waitingGatewayValue = !gw
          continue
        }

        if (waitingGatewayValue) {
          const gw = extractIpv4FromLine(trimmed)
          if (gw) current.hasGateway = true
          waitingGatewayValue = false
        }
      }
      pushCurrent()

      const withGateway = adapters.find((a) => a.connected && !!a.ipv4 && a.hasGateway)
      if (withGateway?.ipv4) return withGateway.ipv4

      const connected = adapters.find((a) => a.connected && !!a.ipv4)
      if (connected?.ipv4) return connected.ipv4
    } catch (_) {
      // fallback 到 networkInterfaces
    }
    return null
  }

  /** 获取本机局域网 IP（用于小助手默认域名，便于手机连 PC；优先私有 LAN，避免虚拟网卡） */
  function getLocalNetworkIP(): string | null {
    const fromIpconfig = getWindowsPreferredIpFromIpconfig()
    if (fromIpconfig) return fromIpconfig

    try {
      const ifaces = os.networkInterfaces()
      let fallback: string | null = null
      for (const name of Object.keys(ifaces)) {
        const addrs = ifaces[name] ?? []
        for (const a of addrs) {
          if (a.family !== 'IPv4' || a.internal || !a.address) continue
          if (isPrivateLan(a.address)) return a.address
          if (!fallback) fallback = a.address
        }
      }
      return fallback
    } catch (_) {
      // ignore
    }
    return null
  }

  /** 内置助手默认 baseUrl：按 slot（TopoClaw / GroupManager）返回不同端口 */
  ipcMain.handle('builtin-assistant:get-default-url', async (_e, slot?: BuiltinAssistantSlot): Promise<string> => {
    const spec = BUILTIN_INSTANCE_SPECS.find((s) => s.slot === (slot ?? 'topoclaw')) ?? BUILTIN_INSTANCE_SPECS[0]
    const ip = getLocalNetworkIP()
    const host = ip ?? 'localhost'
    return `http://${host}:${spec.port}/`
  })

  ipcMain.handle(
    'builtin-assistant:get-default-urls',
    async (): Promise<{ topoclaw: string; groupmanager: string }> => {
      const ip = getLocalNetworkIP()
      const host = ip ?? 'localhost'
      return {
        topoclaw: `http://${host}:${BUILTIN_TOPOCLAW_PORT}/`,
        groupmanager: `http://${host}:${BUILTIN_GROUP_MANAGER_PORT}/`,
      }
    }
  )

  /** 读取 TopoDesktop/config.txt（开发：项目根；安装包：resources 或 exe 同目录） */
  ipcMain.handle(
    'builtin-assistant:read-local-config-txt',
    async (): Promise<
      | { ok: true; nonGuiProfiles: ModelProfileRow[]; guiProfiles: ModelProfileRow[] }
      | { ok: false; error: string }
    > => {
      const p = resolveFirstExistingTopoDesktopConfigTxtPath()
      if (!p) return { ok: false, error: '本地配置不存在' }
      try {
        const raw = fs.readFileSync(p, 'utf-8')
        const parsed = parseLocalTopoConfigTxt(raw)
        if (!parsed.ok) return { ok: false, error: 'error' in parsed ? parsed.error : '配置文件格式无效' }
        return { ok: true, nonGuiProfiles: parsed.nonGui, guiProfiles: parsed.gui }
      } catch (e) {
        return { ok: false, error: String(e) }
      }
    }
  )

  ipcMain.handle(
    'builtin-assistant:save-local-config-txt',
    async (
      _e,
      payload: { nonGuiProfiles?: ModelProfileRow[]; guiProfiles?: ModelProfileRow[] }
    ): Promise<{ ok: boolean; path?: string; error?: string }> => {
      try {
        const nonGui = (payload.nonGuiProfiles ?? [])
          .map((r) => ({
            model: String(r.model ?? '').trim(),
            apiBase: String(r.apiBase ?? '').trim(),
            apiKey: typeof r.apiKey === 'string' ? r.apiKey : '',
          }))
          .filter((r) => r.model)
        const gui = (payload.guiProfiles ?? [])
          .map((r) => ({
            model: String(r.model ?? '').trim(),
            apiBase: String(r.apiBase ?? '').trim(),
            apiKey: typeof r.apiKey === 'string' ? r.apiKey : '',
          }))
          .filter((r) => r.model)
        if (nonGui.length === 0 || gui.length === 0) {
          return { ok: false, error: 'chat 与 GUI 至少各保留一条有效配置' }
        }
        if (hasDuplicateModelNames(nonGui)) return { ok: false, error: 'chat 配置中模型名不能重复' }
        if (hasDuplicateModelNames(gui)) return { ok: false, error: 'GUI 配置中模型名不能重复' }
        const txtPath = resolvePreferredTopoDesktopConfigTxtPath()
        const next = {
          non_gui: nonGui,
          gui,
        }
        fs.writeFileSync(txtPath, JSON.stringify(next, null, 2) + '\n', 'utf-8')
        return { ok: true, path: txtPath }
      } catch (e) {
        return { ok: false, error: String(e) }
      }
    }
  )

  /** 检测端口是否已被占用 */
  function isPortInUse(port: number): Promise<boolean> {
    return new Promise((resolve) => {
      const s = net.createServer()
      s.once('error', () => resolve(true))
      s.once('listening', () => {
        s.close()
        resolve(false)
      })
      s.listen(port, '127.0.0.1')
    })
  }

  function resolveBundledPythonPaths():
    | { pythonExe: string; openclawCwd: string; groupManagerCwd: string; customerServiceCwd: string }
    | { error: string } {
    let pythonExe: string
    let openclawCwd: string
    let groupManagerCwd: string
    let customerServiceCwd: string
    if (app.isPackaged) {
      pythonExe = path.join(process.resourcesPath, 'python-embed', 'python.exe')
      openclawCwd = path.join(process.resourcesPath, 'TopoClaw')
      groupManagerCwd = path.join(process.resourcesPath, 'group-manager')
      customerServiceCwd = path.join(process.resourcesPath, 'customer-service')
    } else {
      const projectRoot = path.join(__dirname, '..')
      pythonExe = path.join(projectRoot, 'resources', 'python-embed', 'python.exe')
      openclawCwd = path.join(projectRoot, 'resources', 'TopoClaw')
      groupManagerCwd = path.join(projectRoot, 'resources', 'group-manager')
      customerServiceCwd = path.join(projectRoot, 'resources', 'customer-service')
    }
    if (!fs.existsSync(pythonExe)) {
      return { error: '未找到内置 Python，请执行 npm run setup:python' }
    }
    return { pythonExe, openclawCwd, groupManagerCwd, customerServiceCwd }
  }

  async function ensureBuiltinSlotStarted(spec: (typeof BUILTIN_INSTANCE_SPECS)[number]): Promise<{
    ok: boolean
    alreadyRunning?: boolean
    error?: string
  }> {
    if (!isBuiltinServicesEnabled()) {
      return { ok: false, error: '已关闭“开启所有内置服务”，请在设置中重新开启' }
    }
    const { slot, port, label, kind } = spec
    if (builtinAssistantProcesses[slot]) {
      return { ok: true, alreadyRunning: true }
    }
    if (await isPortInUse(port)) {
      if (!builtinPortBusyHintSent[slot]) {
        builtinPortBusyHintSent[slot] = true
        appendBuiltinLogChunk(
          slot,
          `\n[内置${label}] 端口 ${port} 已被占用，本应用未启动子进程，无法采集实时 stdout/stderr。\n` +
            `若需本应用拉起：请关闭占用进程后在联系人中对该助手点「重启」。\n`
        )
      }
      return { ok: true, alreadyRunning: true }
    }

    const paths = resolveBundledPythonPaths()
    if ('error' in paths) {
      return { ok: false, error: paths.error }
    }
    const { pythonExe, openclawCwd, groupManagerCwd } = paths

    if (kind === 'nanobot') {
      if (!fs.existsSync(path.join(openclawCwd, 'pyproject.toml'))) {
        return { ok: false, error: '未找到 TopoClaw，请执行 npm run setup:assistant' }
      }
      const topoclawEntry = path.join(openclawCwd, 'topoclaw', 'cli', 'commands.py')
      const nanobotEntry = path.join(openclawCwd, 'nanobot', 'cli', 'commands.py')
      let assistantModule: string
      let assistantLogName: string
      if (fs.existsSync(topoclawEntry)) {
        assistantModule = 'topoclaw.cli.commands'
        assistantLogName = 'topoclaw'
      } else if (fs.existsSync(nanobotEntry)) {
        assistantModule = 'nanobot.cli.commands'
        assistantLogName = 'nanobot'
      } else {
        return {
          ok: false,
          error:
            'TopoClaw 缺少 topoclaw（或旧版 nanobot）源码。请执行 npm run setup:assistant 后重新打包。',
        }
      }
      const pythonPathExtra = [openclawCwd, process.env.PYTHONPATH].filter(Boolean).join(path.delimiter)
      const serperApiKey = readSerperApiKeyFromConfig(openclawCwd) || process.env.SERPER_API_KEY || ''
      const serperApiBase = readSerperApiBaseFromConfig(openclawCwd) || process.env.SERPER_API_BASE || process.env.SEARCH_API_BASE || ''
      const env: NodeJS.ProcessEnv = {
        ...process.env,
        PYTHONIOENCODING: 'utf-8',
        PYTHONUNBUFFERED: '1',
        PYTHONPATH: pythonPathExtra,
        SERPER_API_KEY: serperApiKey,
        ...(serperApiBase ? { SERPER_API_BASE: serperApiBase, SEARCH_API_BASE: serperApiBase } : {}),
      }
      // Ensure built-in TopoClaw always emits full model I/O logs so
      // the desktop "输入输出" view can show complete prompts/responses.
      if (!env.TOPOCLAW_LOG_MODEL_IO) env.TOPOCLAW_LOG_MODEL_IO = '1'
      if (!env.TOPOCLAW_MODEL_LOG_MAX_CHARS) env.TOPOCLAW_MODEL_LOG_MAX_CHARS = '0'
      const effectiveEnv = applyCustomerServiceEnvDefaults(env)
      try {
        builtinPortBusyHintSent[slot] = false
        const proc = spawn(
          pythonExe,
          [
            '-m',
            assistantModule,
            'service',
            '--config',
            'config.json',
            '--port',
            String(port),
            '--workspace',
            'workspace',
          ],
          {
            cwd: openclawCwd,
            env: effectiveEnv,
            stdio: ['ignore', 'pipe', 'pipe'],
          }
        )
        builtinAssistantProcesses[slot] = proc
        appendBuiltinLogChunk(slot, `[${label} / ${assistantLogName}] 进程已启动，监听端口 ${port}\n`)
        proc.stdout?.on('data', (d: Buffer) => appendBuiltinLogChunk(slot, d.toString()))
        proc.stderr?.on('data', (d: Buffer) => appendBuiltinLogChunk(slot, d.toString()))
        proc.on('exit', (code) => {
          appendBuiltinLogChunk(slot, `\n[${label} / ${assistantLogName}] 进程已退出，code=${code}\n`)
          delete builtinAssistantProcesses[slot]
        })
        return { ok: true, alreadyRunning: false }
      } catch (e) {
        return { ok: false, error: String(e) }
      }
    }

    // GroupManager：仓库 GroupManager（SimpleChat），非 nanobot
    const mainPy = path.join(groupManagerCwd, 'main.py')
    if (!fs.existsSync(mainPy)) {
      return {
        ok: false,
        error: '未找到内置 GroupManager（resources/group-manager）。请执行 npm run setup:group-manager',
      }
    }
    const env = buildGroupManagerProcessEnv(openclawCwd)
    try {
      builtinPortBusyHintSent[slot] = false
      const proc = spawn(
        pythonExe,
        [mainPy, '--host', '0.0.0.0', '--port', String(port)],
        {
          cwd: groupManagerCwd,
          env,
          stdio: ['ignore', 'pipe', 'pipe'],
        }
      )
      builtinAssistantProcesses[slot] = proc
      appendBuiltinLogChunk(slot, `[${label} / SimpleChat] 进程已启动，监听端口 ${port}\n`)
      proc.stdout?.on('data', (d: Buffer) => appendBuiltinLogChunk(slot, d.toString()))
      proc.stderr?.on('data', (d: Buffer) => appendBuiltinLogChunk(slot, d.toString()))
      proc.on('exit', (code) => {
        appendBuiltinLogChunk(slot, `\n[${label} / SimpleChat] 进程已退出，code=${code}\n`)
        delete builtinAssistantProcesses[slot]
      })
      return { ok: true, alreadyRunning: false }
    } catch (e) {
      return { ok: false, error: String(e) }
    }
  }

  async function ensureBuiltinAssistantStarted(): Promise<{ ok: boolean; alreadyRunning?: boolean; error?: string }> {
    if (!isBuiltinServicesEnabled()) {
      return { ok: false, error: '内置服务总开关已关闭' }
    }
    let firstError: string | undefined
    let allAlready = true
    for (const spec of BUILTIN_INSTANCE_SPECS) {
      const r = await ensureBuiltinSlotStarted(spec)
      if (!r.ok) {
        firstError ??= r.error
      }
      if (r.alreadyRunning !== true) {
        allAlready = false
      }
    }
    if (firstError) {
      const anyUp = BUILTIN_INSTANCE_SPECS.some((s) => Boolean(builtinAssistantProcesses[s.slot]))
      if (!anyUp) {
        return { ok: false, error: firstError }
      }
    }
    return { ok: true, alreadyRunning: allAlready }
  }

  async function ensureBuiltinCustomerServiceStarted(options?: { restart?: boolean }): Promise<{
    ok: boolean
    alreadyRunning?: boolean
    restarted?: boolean
    error?: string
  }> {
    if (!isBuiltinServicesEnabled()) {
      return { ok: false, error: '内置服务总开关已关闭' }
    }
    const shouldRestart = options?.restart === true
    if (builtinCustomerServiceProcess && shouldRestart) {
      appendBuiltinCustomerServiceLogChunk('\n[customer_service] 收到重启请求，正在结束旧进程...\n')
      try {
        builtinCustomerServiceProcess.kill()
      } catch {
        // ignore
      }
      builtinCustomerServiceProcess = null
      builtinCustomerServiceLogBuffer = ''
      await new Promise((r) => setTimeout(r, 800))
    }
    if (builtinCustomerServiceProcess) {
      appendBuiltinCustomerServiceLogChunk('[customer_service] 进程已在运行中\n')
      return { ok: true, alreadyRunning: true, restarted: false }
    }
    if (await isPortInUse(BUILTIN_CUSTOMER_SERVICE_PORT)) {
      appendBuiltinCustomerServiceLogChunk(
        `[customer_service] 端口 ${BUILTIN_CUSTOMER_SERVICE_PORT} 已被占用，复用外部已运行服务\n`
      )
      return { ok: true, alreadyRunning: true, restarted: shouldRestart }
    }

    const paths = resolveBundledPythonPaths()
    if ('error' in paths) {
      return { ok: false, error: paths.error }
    }
    const { pythonExe } = paths
    const customerServiceCwd = resolveCustomerServiceCwdFallback(paths.customerServiceCwd)
    const appPy = path.join(customerServiceCwd, 'app.py')
    const coreOutput = path.join(customerServiceCwd, 'core', 'output_paths.py')
    if (!fs.existsSync(appPy) || !fs.existsSync(coreOutput)) {
      appendBuiltinCustomerServiceLogChunk(
        `[customer_service] 无法找到完整服务目录。\n` +
          `  app.py: ${appPy} (${fs.existsSync(appPy) ? 'ok' : 'missing'})\n` +
          `  core/output_paths.py: ${coreOutput} (${fs.existsSync(coreOutput) ? 'ok' : 'missing'})\n`
      )
      return {
        ok: false,
        error:
          '未找到完整的 customer_service（缺少 core 模块）。请执行 npm run setup:customer-service 后重新打包，或检查 release/resources/customer-service 目录内容。',
      }
    }
    const env: NodeJS.ProcessEnv = {
      ...buildEmbedOnlyEnv(),
      PYTHONIOENCODING: 'utf-8',
      PYTHONUNBUFFERED: '1',
      // python-embed 开启隔离模式时不会可靠继承脚本目录到 sys.path，
      // 这里显式注入 customer_service 根目录，确保可导入 core/api/services 等同级包。
      PYTHONPATH: [customerServiceCwd, process.env.PYTHONPATH].filter(Boolean).join(path.delimiter),
      CUSTOMER_SERVICE_URL: `http://127.0.0.1:${BUILTIN_CUSTOMER_SERVICE_PORT}/v4/`,
    }
    const normalizedCustomerServiceCwd = customerServiceCwd.replace(/\\/g, '/')
    const normalizedAppPy = appPy.replace(/\\/g, '/')
    const bootstrapCode =
      `import runpy, sys; ` +
      `sys.path.insert(0, r"${normalizedCustomerServiceCwd}"); ` +
      `runpy.run_path(r"${normalizedAppPy}", run_name="__main__")`
    try {
      const proc = spawn(pythonExe, ['-c', bootstrapCode], {
        cwd: customerServiceCwd,
        env,
        stdio: ['ignore', 'pipe', 'pipe'],
      })
      builtinCustomerServiceProcess = proc
      appendBuiltinCustomerServiceLogChunk(
        `[customer_service] 进程已启动，监听端口 ${BUILTIN_CUSTOMER_SERVICE_PORT}\n` +
          `[customer_service] cwd=${customerServiceCwd}\n`
      )
      proc.stdout?.on('data', (d: Buffer) => {
        const text = d.toString()
        console.log(`[customer_service] ${text}`)
        appendBuiltinCustomerServiceLogChunk(text)
      })
      proc.stderr?.on('data', (d: Buffer) => {
        const text = d.toString()
        console.error(`[customer_service] ${text}`)
        appendBuiltinCustomerServiceLogChunk(text)
      })
      proc.on('exit', (code) => {
        console.log(`[customer_service] process exited, code=${code}`)
        appendBuiltinCustomerServiceLogChunk(`\n[customer_service] 进程已退出，code=${code}\n`)
        builtinCustomerServiceProcess = null
      })
      return { ok: true, alreadyRunning: false, restarted: shouldRestart }
    } catch (e) {
      return { ok: false, error: String(e) }
    }
  }

  ipcMain.handle('builtin-assistant:ensure-started', ensureBuiltinAssistantStarted)
  ipcMain.handle(
    'builtin-assistant:customer-service-start',
    (_e, params?: { restart?: boolean }) => ensureBuiltinCustomerServiceStarted({ restart: params?.restart === true })
  )
  ipcMain.handle('builtin-assistant:customer-service-log-pipe-active', () =>
    Boolean(builtinCustomerServiceProcess)
  )
  ipcMain.handle('builtin-assistant:customer-service-get-log-buffer', () =>
    builtinCustomerServiceLogBuffer
  )

  function parseBuiltinSlot(arg: unknown): BuiltinAssistantSlot {
    return arg === 'groupmanager' ? 'groupmanager' : 'topoclaw'
  }

  async function restartBuiltinAssistant(_slot?: BuiltinAssistantSlot): Promise<{ ok: boolean; error?: string }> {
    if (!isBuiltinServicesEnabled()) {
      return { ok: false, error: '内置服务总开关已关闭' }
    }
    const specs = _slot ? BUILTIN_INSTANCE_SPECS.filter((s) => s.slot === _slot) : [...BUILTIN_INSTANCE_SPECS]
    if (_slot && specs.length === 0) {
      return { ok: false, error: `内置 ${_slot} 已下线` }
    }
    for (const spec of specs) {
      const p = builtinAssistantProcesses[spec.slot]
      if (p) {
        try {
          p.kill()
        } catch {
          // ignore
        }
        delete builtinAssistantProcesses[spec.slot]
      }
      builtinAssistantLogBuffers[spec.slot] = ''
    }
    await new Promise((r) => setTimeout(r, 1500))
    for (const spec of specs) {
      if (await isPortInUse(spec.port)) {
        return { ok: false, error: `端口 ${spec.port} 已被占用，请先关闭占用该端口的进程` }
      }
    }
    for (const spec of specs) {
      const r = await ensureBuiltinSlotStarted(spec)
      if (!r.ok) {
        return r
      }
    }
    return { ok: true }
  }
  ipcMain.handle('builtin-assistant:restart', (_e, slot?: BuiltinAssistantSlot) => restartBuiltinAssistant(slot))
  ipcMain.handle('builtin-assistant:get-global-enabled', async (): Promise<{ ok: boolean; enabled: boolean; error?: string }> => {
    try {
      return { ok: true, enabled: isBuiltinServicesEnabled() }
    } catch (e) {
      return { ok: false, enabled: true, error: String(e) }
    }
  })
  ipcMain.handle('builtin-assistant:set-global-enabled', async (_e, enabledInput: boolean): Promise<{ ok: boolean; enabled: boolean; error?: string }> => {
    try {
      const enabled = enabledInput !== false
      saveBuiltinRuntimeSettings({ builtinServicesEnabled: enabled })
      if (!enabled) {
        killAllBuiltinAssistantProcesses()
        killAllTerminalSessions()
        if (terminalWindow && !terminalWindow.isDestroyed()) {
          terminalWindow.close()
        }
      }
      return { ok: true, enabled }
    } catch (e) {
      return { ok: false, enabled: isBuiltinServicesEnabled(), error: String(e) }
    }
  })

  ipcMain.handle('builtin-assistant:get-model-profiles', async (): Promise<
    | {
        ok: true
        nonGuiProfiles: ModelProfileRow[]
        guiProfiles: ModelProfileRow[]
        activeNonGuiModel: string
        activeImageModel: string
        activeGuiModel: string
        activeGroupManagerModel: string
      }
    | { ok: false; error: string }
  > => {
    // 首次登录会话页模型下拉需要与本地 config.txt 保持一致，启动后自动同步一次。
    tryApplyLocalConfigTxtToOpenclawConfig()
    const baseDir = resolveOpenclawMain2BaseDir()
    if (!baseDir) return { ok: false, error: 'TopoClaw 目录不存在' }
    const configPath = path.join(baseDir, 'config.json')
    let cfg: Record<string, unknown> = {}
    if (fs.existsSync(configPath)) {
      try {
        cfg = JSON.parse(fs.readFileSync(configPath, 'utf-8')) as Record<string, unknown>
      } catch {
        return { ok: false, error: 'config.json 解析失败' }
      }
    }
    let migrated = false
    if (!parseTopoDesktopFromCfg(cfg)) {
      migrated = true
    }
    const td = ensureTopoDesktopInCfg(cfg)
    if (migrated) {
      try {
        fs.writeFileSync(configPath, JSON.stringify(cfg, null, 2) + '\n', 'utf-8')
      } catch (e) {
        return { ok: false, error: String(e) }
      }
    }
    return {
      ok: true,
      nonGuiProfiles: td.nonGuiProfiles,
      guiProfiles: td.guiProfiles,
      activeNonGuiModel: td.activeNonGuiModel,
      activeImageModel: td.activeImageModel,
      activeGuiModel: td.activeGuiModel,
      activeGroupManagerModel: td.activeGroupManagerModel,
    }
  })

  ipcMain.handle(
    'builtin-assistant:save-model-profiles',
    async (
      _e,
      payload: {
        nonGuiProfiles?: ModelProfileRow[]
        guiProfiles?: ModelProfileRow[]
        activeNonGuiModel?: string
        activeImageModel?: string
        activeGuiModel?: string
        activeGroupManagerModel?: string
      }
    ): Promise<{ ok: boolean; error?: string }> => {
      try {
        const baseDir = resolveOpenclawMain2BaseDir()
        if (!baseDir) return { ok: false, error: 'TopoClaw 目录不存在' }
        const configPath = path.join(baseDir, 'config.json')
        let cfg: Record<string, unknown> = {}
        if (fs.existsSync(configPath)) {
          cfg = JSON.parse(fs.readFileSync(configPath, 'utf-8')) as Record<string, unknown>
        }
        let td = parseTopoDesktopFromCfg(cfg) ?? migrateTopoDesktopFromCfg(cfg)
        const prevTd = {
          nonGuiProfiles: td.nonGuiProfiles.map((x) => ({ ...x })),
          guiProfiles: td.guiProfiles.map((x) => ({ ...x })),
          activeNonGuiModel: td.activeNonGuiModel,
          activeImageModel: td.activeImageModel,
          activeGuiModel: td.activeGuiModel,
          activeGroupManagerModel: td.activeGroupManagerModel,
        }
        if (payload.nonGuiProfiles) {
          td.nonGuiProfiles = payload.nonGuiProfiles.map((r) => ({
            model: String(r.model ?? '').trim(),
            apiBase: String(r.apiBase ?? '').trim(),
            apiKey: typeof r.apiKey === 'string' ? r.apiKey : '',
          })).filter((r) => r.model)
        }
        if (payload.guiProfiles) {
          td.guiProfiles = payload.guiProfiles.map((r) => ({
            model: String(r.model ?? '').trim(),
            apiBase: String(r.apiBase ?? '').trim(),
            apiKey: typeof r.apiKey === 'string' ? r.apiKey : '',
          })).filter((r) => r.model)
        }
        if (payload.activeNonGuiModel != null) td.activeNonGuiModel = String(payload.activeNonGuiModel).trim()
        if (payload.activeImageModel != null) td.activeImageModel = String(payload.activeImageModel).trim()
        if (payload.activeGuiModel != null) td.activeGuiModel = String(payload.activeGuiModel).trim()
        if (payload.activeGroupManagerModel != null) {
          td.activeGroupManagerModel = String(payload.activeGroupManagerModel).trim()
        }
        if (td.nonGuiProfiles.length === 0) {
          return { ok: false, error: '至少保留一条 chat 模型配置' }
        }
        if (td.guiProfiles.length === 0) {
          return { ok: false, error: '至少保留一条 GUI 模型配置' }
        }
        if (hasDuplicateModelNames(td.nonGuiProfiles)) {
          return { ok: false, error: 'chat 配置中模型名不能重复' }
        }
        if (hasDuplicateModelNames(td.guiProfiles)) {
          return { ok: false, error: 'GUI 配置中模型名不能重复' }
        }
        if (!findProfileByModel(td.nonGuiProfiles, td.activeNonGuiModel)) {
          td.activeNonGuiModel = td.nonGuiProfiles[0]!.model
        }
        if (!findProfileByModel(td.guiProfiles, td.activeGuiModel)) {
          td.activeGuiModel = td.guiProfiles[0]!.model
        }
        if (!findProfileByModel(td.nonGuiProfiles, td.activeGroupManagerModel)) {
          td.activeGroupManagerModel = td.nonGuiProfiles[0]!.model
        }
        if (!findProfileByModel(td.nonGuiProfiles, td.activeImageModel)) {
          td.activeImageModel = pickFirstImageModel(td.nonGuiProfiles)
        }
        cfg[TOPO_DESKTOP_KEY] = td
        applyNanobotProvidersFromProfiles(cfg, td)
        const ng = findProfileByModel(td.nonGuiProfiles, td.activeNonGuiModel) ?? td.nonGuiProfiles[0]
        writeOpenclawEnvKey(baseDir, ng.apiKey)
        fs.writeFileSync(configPath, JSON.stringify(cfg, null, 2) + '\n', 'utf-8')
        const localSave = saveTopoDesktopProfilesToLocalConfigTxt(td)
        if ('error' in localSave) {
          return { ok: false, error: `保存本地配置失败: ${localSave.error}` }
        }

        const profileSig = (rows: ModelProfileRow[], model: string): string => {
          const hit = findProfileByModel(rows, model)
          if (!hit) return ''
          return `${hit.model}@@${hit.apiBase}@@${hit.apiKey}`
        }
        const topoclawModelChanged =
          prevTd.activeNonGuiModel !== td.activeNonGuiModel || prevTd.activeGuiModel !== td.activeGuiModel
        const imageModelChanged = prevTd.activeImageModel !== td.activeImageModel
        const topoclawCredChanged =
          profileSig(prevTd.nonGuiProfiles, prevTd.activeNonGuiModel) !== profileSig(td.nonGuiProfiles, td.activeNonGuiModel) ||
          profileSig(prevTd.guiProfiles, prevTd.activeGuiModel) !== profileSig(td.guiProfiles, td.activeGuiModel)
        const groupModelChanged = prevTd.activeGroupManagerModel !== td.activeGroupManagerModel
        const groupCredChanged =
          profileSig(prevTd.nonGuiProfiles, prevTd.activeGroupManagerModel) !==
          profileSig(td.nonGuiProfiles, td.activeGroupManagerModel)

        // 热切换策略：
        // - 仅保存配置，不在主进程触发内置服务重启。
        // - 运行时切换由前端通过 set_llm_provider / set_gui_provider websocket 消息完成。
        // 这里保留差异计算仅用于后续可能的诊断扩展。
        void topoclawModelChanged
        void imageModelChanged
        void topoclawCredChanged
        void groupModelChanged
        void groupCredChanged
        return { ok: true }
      } catch (e) {
        console.error('builtin-assistant:save-model-profiles', e)
        return { ok: false, error: String(e) }
      }
    }
  )

  ipcMain.handle(
    'builtin-assistant:apply-model-selection',
    async (
      _e,
      params: { slot: BuiltinAssistantSlot; nonGuiModel: string; guiModel?: string }
    ): Promise<{ ok: boolean; error?: string }> => {
      try {
        const baseDir = resolveOpenclawMain2BaseDir()
        if (!baseDir) return { ok: false, error: 'TopoClaw 目录不存在' }
        const configPath = path.join(baseDir, 'config.json')
        let cfg: Record<string, unknown> = {}
        if (fs.existsSync(configPath)) {
          cfg = JSON.parse(fs.readFileSync(configPath, 'utf-8')) as Record<string, unknown>
        }
        const td = ensureTopoDesktopInCfg(cfg)
        const slot = params.slot === 'groupmanager' ? 'groupmanager' : 'topoclaw'
        if (slot === 'topoclaw') {
          const ngName = String(params.nonGuiModel ?? '').trim()
          const guiName = String(params.guiModel ?? td.activeGuiModel).trim()
          if (!findProfileByModel(td.nonGuiProfiles, ngName)) {
            return { ok: false, error: `未找到 chat 模型：${ngName}` }
          }
          if (!findProfileByModel(td.guiProfiles, guiName)) {
            return { ok: false, error: `未找到 GUI 模型：${guiName}` }
          }
          td.activeNonGuiModel = ngName
          td.activeGuiModel = guiName
          cfg[TOPO_DESKTOP_KEY] = td
          applyNanobotProvidersFromProfiles(cfg, td)
          const ng = findProfileByModel(td.nonGuiProfiles, td.activeNonGuiModel) ?? td.nonGuiProfiles[0]
          writeOpenclawEnvKey(baseDir, ng.apiKey)
        } else {
          const gmName = String(params.nonGuiModel ?? '').trim()
          if (!findProfileByModel(td.nonGuiProfiles, gmName)) {
            return { ok: false, error: `未找到模型：${gmName}` }
          }
          td.activeGroupManagerModel = gmName
          cfg[TOPO_DESKTOP_KEY] = td
        }
        fs.writeFileSync(configPath, JSON.stringify(cfg, null, 2) + '\n', 'utf-8')
        // 仅持久化选择；运行时热切换由上层 websocket 调用完成。
        return { ok: true }
      } catch (e) {
        console.error('builtin-assistant:apply-model-selection', e)
        return { ok: false, error: String(e) }
      }
    }
  )

  ipcMain.handle('builtin-assistant:has-log-stream', (_e, slot?: BuiltinAssistantSlot) =>
    Boolean(builtinAssistantProcesses[parseBuiltinSlot(slot)])
  )
  ipcMain.handle('builtin-assistant:log-pipe-active', (_e, slot?: BuiltinAssistantSlot) =>
    Boolean(builtinAssistantProcesses[parseBuiltinSlot(slot)])
  )
  ipcMain.handle('builtin-assistant:get-log-buffer', (_e, slot?: BuiltinAssistantSlot) =>
    builtinAssistantLogBuffers[parseBuiltinSlot(slot)]
  )

  ipcMain.handle(
    'builtin-assistant:export-log',
    async (_e, text: string): Promise<{ ok: boolean; error?: string; canceled?: boolean; path?: string }> => {
      const win = BrowserWindow.getFocusedWindow() ?? mainWindow
      if (!win || win.isDestroyed()) return { ok: false, error: 'no window' }
      const pad = (n: number) => String(n).padStart(2, '0')
      const d = new Date()
      const defaultName = `builtin-assistant-log-${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}.txt`
      const { canceled, filePath } = await dialog.showSaveDialog(win, {
        title: '导出内置小助手日志',
        defaultPath: defaultName,
        filters: [
          { name: '文本文件', extensions: ['txt'] },
          { name: '所有文件', extensions: ['*'] },
        ],
      })
      if (canceled || !filePath) return { ok: false, canceled: true }
      try {
        await fs.promises.writeFile(filePath, text ?? '', 'utf-8')
        return { ok: true, path: filePath }
      } catch (err) {
        return { ok: false, error: String(err) }
      }
    }
  )

  /** 内置小助手配置：从 resources/TopoClaw 或工作区 TopoClaw 子目录 / 根目录 / openclaw_main3 / main2 读取默认值 */
  ipcMain.handle(
    'builtin-assistant:get-defaults',
    async (): Promise<{
      model: string
      apiBase: string
      apiKey: string
      guiModel: string
      guiApiBase: string
      guiApiKey: string
      qqEnabled: boolean
      qqAppId: string
      qqAppSecret: string
      qqAllowFrom: string
      weixinEnabled: boolean
      weixinBotToken: string
      weixinBaseUrl: string
      weixinAllowFrom: string
    }> => {
    const fallback = {
      model: 'gpt-4o-mini',
      apiBase: 'https://api.openai.com/v1',
      apiKey: '',
      guiModel: 'Qwen3-VL-32B-Instruct-rl',
      guiApiBase: DEFAULT_GUI_API_BASE,
      guiApiKey: '',
      qqEnabled: false,
      qqAppId: '',
      qqAppSecret: '',
      qqAllowFrom: '*',
      weixinEnabled: false,
      weixinBotToken: '',
      weixinBaseUrl: 'https://ilinkai.weixin.qq.com',
      weixinAllowFrom: '*',
    }
    try {
      const primary = resolveOpenclawMain2BaseDir()
      const primaryConfig = primary ? path.join(primary, 'config.json') : null
      if (primaryConfig && fs.existsSync(primaryConfig)) {
        const raw = fs.readFileSync(primaryConfig, 'utf-8')
        const cfg = JSON.parse(raw) as Record<string, unknown>
        return readBuiltinDefaultsFromConfigObject(cfg)
      }
      let configPath: string | undefined
      if (app.isPackaged) {
        configPath = path.join(process.resourcesPath, 'TopoClaw', 'config.json')
      } else {
        const projectRoot = path.join(__dirname, '..')
        const workspaceRoot = path.join(projectRoot, '..')
        const candidates = [
          path.join(projectRoot, 'resources', 'TopoClaw', 'config.json'),
          path.join(workspaceRoot, 'TopoClaw', 'config.json'),
          path.join(workspaceRoot, 'config.json'),
          path.join(workspaceRoot, 'openclaw_main3', 'config.json'),
          path.join(workspaceRoot, 'openclaw_main2', 'config.json'),
        ]
        configPath = candidates.find((p) => fs.existsSync(p))
      }
      if (!configPath || !fs.existsSync(configPath)) return fallback
      const raw = fs.readFileSync(configPath, 'utf-8')
      const cfg = JSON.parse(raw) as Record<string, unknown>
      return readBuiltinDefaultsFromConfigObject(cfg)
    } catch (e) {
      console.warn('builtin-assistant:get-defaults', e)
    }
    return fallback
  }
  )

  ipcMain.handle(
    'builtin-assistant:weixin-get-qr',
    async (
      _e,
      input?: { baseUrl?: string; botType?: string; skRouteTag?: string }
    ): Promise<{ ok: boolean; qrcodeTicket?: string; payload?: string; baseUrl?: string; error?: string }> => {
      try {
        const baseUrl = String(input?.baseUrl ?? 'https://ilinkai.weixin.qq.com').trim().replace(/\/+$/, '')
        const botType = String(input?.botType ?? '3').trim() || '3'
        const skRouteTag = String(input?.skRouteTag ?? '').trim()
        const headers: Record<string, string> = {}
        if (skRouteTag) headers.SKRouteTag = skRouteTag
        const url = `${baseUrl}/ilink/bot/get_bot_qrcode?bot_type=${encodeURIComponent(botType)}`
        const res = await fetch(url, { method: 'GET', headers })
        if (!res.ok) return { ok: false, error: `请求二维码失败 HTTP ${res.status}` }
        const data = (await res.json()) as Record<string, unknown>
        const qrcodeTicket = String(data.qrcode ?? '').trim()
        const payload = String(data.qrcode_img_content ?? qrcodeTicket).trim()
        if (!qrcodeTicket || !payload) return { ok: false, error: '二维码响应缺少有效内容' }
        return { ok: true, qrcodeTicket, payload, baseUrl }
      } catch (e) {
        return { ok: false, error: String(e) }
      }
    }
  )

  ipcMain.handle(
    'builtin-assistant:weixin-poll-qr-status',
    async (
      _e,
      input: { baseUrl?: string; qrcodeTicket?: string; skRouteTag?: string }
    ): Promise<{
      ok: boolean
      status?: string
      botToken?: string
      baseUrl?: string
      accountId?: string
      userId?: string
      error?: string
    }> => {
      try {
        const baseUrl = String(input?.baseUrl ?? 'https://ilinkai.weixin.qq.com').trim().replace(/\/+$/, '')
        const qrcodeTicket = String(input?.qrcodeTicket ?? '').trim()
        if (!qrcodeTicket) return { ok: false, error: '缺少 qrcodeTicket' }
        const skRouteTag = String(input?.skRouteTag ?? '').trim()
        const headers: Record<string, string> = { 'iLink-App-ClientVersion': '1' }
        if (skRouteTag) headers.SKRouteTag = skRouteTag
        const url = `${baseUrl}/ilink/bot/get_qrcode_status?qrcode=${encodeURIComponent(qrcodeTicket)}`
        const res = await fetch(url, { method: 'GET', headers })
        if (!res.ok) return { ok: false, error: `查询扫码状态失败 HTTP ${res.status}` }
        const data = (await res.json()) as Record<string, unknown>
        const status = String(data.status ?? 'wait').trim() || 'wait'
        return {
          ok: true,
          status,
          botToken: String(data.bot_token ?? '').trim() || undefined,
          baseUrl: String(data.baseurl ?? '').trim().replace(/\/+$/, '') || undefined,
          accountId: String(data.ilink_bot_id ?? '').trim() || undefined,
          userId: String(data.ilink_user_id ?? '').trim() || undefined,
        }
      } catch (e) {
        return { ok: false, error: String(e) }
      }
    }
  )

  ipcMain.handle(
    'builtin-assistant:get-im-local-history',
    async (
      _e,
      input: { channel: 'qq' | 'weixin'; limit?: number }
    ): Promise<{
      ok: boolean
      channel?: 'qq' | 'weixin'
      messages?: Array<{ id: string; role: 'user' | 'assistant'; content: string; timestamp: number }>
      error?: string
    }> => {
      try {
        const channel = input?.channel === 'weixin' ? 'weixin' : 'qq'
        const limit = Math.max(20, Math.min(Number(input?.limit ?? 300) || 300, 2000))
        const workspaceDir = resolveOpenclawWorkspaceDir()
        if (!workspaceDir) return { ok: false, error: 'TopoClaw workspace 不存在' }
        const sessionsDir = path.join(workspaceDir, 'sessions')
        if (!fs.existsSync(sessionsDir)) return { ok: true, channel, messages: [] }
        const filePrefix = `${channel}_`
        const files = fs
          .readdirSync(sessionsDir)
          .filter((f) => f.startsWith(filePrefix) && f.endsWith('.jsonl'))
          .sort()

        const out: Array<{ id: string; role: 'user' | 'assistant'; content: string; timestamp: number }> = []
        for (const file of files) {
          const fullPath = path.join(sessionsDir, file)
          let raw = ''
          try {
            raw = fs.readFileSync(fullPath, 'utf-8')
          } catch {
            continue
          }
          const lines = raw.split(/\r?\n/g)
          let row = 0
          for (const line of lines) {
            const s = line.trim()
            if (!s) continue
            let item: Record<string, unknown>
            try {
              item = JSON.parse(s) as Record<string, unknown>
            } catch {
              continue
            }
            if (item._type === 'metadata') continue
            const roleRaw = String(item.role ?? '').trim()
            const role = roleRaw === 'assistant' ? 'assistant' : roleRaw === 'user' ? 'user' : null
            if (!role) continue
            let content = ''
            if (typeof item.content === 'string') {
              content = item.content
            } else if (Array.isArray(item.content)) {
              const parts = item.content
                .map((p) => {
                  const po = p as Record<string, unknown>
                  if (po?.type === 'text' && typeof po.text === 'string') return po.text
                  return ''
                })
                .filter(Boolean)
              content = parts.join('\n').trim()
            }
            const normalized = content.trim()
            if (!normalized) continue
            const tsRaw = String(item.timestamp ?? item.created_at ?? '').trim()
            const parsedTs = Date.parse(tsRaw)
            const timestamp = Number.isFinite(parsedTs) ? parsedTs : Date.now()
            out.push({
              id: `${file}:${row}`,
              role,
              content: normalized,
              timestamp,
            })
            row += 1
          }
        }
        out.sort((a, b) => a.timestamp - b.timestamp)
        const messages = out.length > limit ? out.slice(out.length - limit) : out
        return { ok: true, channel, messages }
      } catch (e) {
        return { ok: false, error: String(e) }
      }
    }
  )

  /** 保存内置小助手配置到 TopoClaw（config.json + .env） */
  ipcMain.handle(
    'builtin-assistant:save-config',
    async (
      _e,
      config: {
        model?: string
        apiBase?: string
        apiKey?: string
        guiModel?: string
        guiApiBase?: string
        guiApiKey?: string
        qqEnabled?: boolean
        qqAppId?: string
        qqAppSecret?: string
        qqAllowFrom?: string | string[]
        weixinEnabled?: boolean
        weixinBotToken?: string
        weixinBaseUrl?: string
        weixinAllowFrom?: string | string[]
      }
    ): Promise<{ ok: boolean; error?: string }> => {
    try {
      let baseDir: string
      if (app.isPackaged) {
        baseDir = path.join(process.resourcesPath, 'TopoClaw')
      } else {
        baseDir = path.join(app.getAppPath(), 'resources', 'TopoClaw')
      }
      if (!fs.existsSync(baseDir)) return { ok: false, error: 'TopoClaw 目录不存在' }
      const configPath = path.join(baseDir, 'config.json')
      let cfg: Record<string, unknown> = {}
      if (fs.existsSync(configPath)) {
        cfg = JSON.parse(fs.readFileSync(configPath, 'utf-8')) as Record<string, unknown>
      }
      const agents = (cfg.agents as Record<string, unknown>) ?? {}
      const defaults = (agents.defaults as Record<string, unknown>) ?? {}
      const gui = (agents.gui as Record<string, unknown>) ?? {}
      const providers = (cfg.providers as Record<string, Record<string, unknown>>) ?? {}
      const customProvider: Record<string, unknown> = { ...(providers.custom ?? {}) }
      const custom2Provider: Record<string, unknown> = { ...(providers.custom2 ?? {}) }
      delete customProvider.apiKey
      delete customProvider.apiBase
      delete custom2Provider.apiKey
      delete custom2Provider.apiBase
      if (config.model != null) defaults.model = config.model
      if (config.apiBase != null) customProvider.api_base = config.apiBase
      if (config.apiKey != null) customProvider.api_key = config.apiKey
      if (config.guiModel != null) gui.model = config.guiModel
      if (config.guiApiBase != null) custom2Provider.api_base = config.guiApiBase
      if (config.guiApiKey != null) custom2Provider.api_key = config.guiApiKey
      // 固定走 custom / custom2，避免模型名含 gemini 等关键词时被匹配到空的 providers.gemini
      defaults.provider = 'custom'
      gui.provider = 'custom2'
      agents.defaults = defaults
      agents.gui = gui
      providers.custom = customProvider
      providers.custom2 = custom2Provider
      cfg.agents = agents
      cfg.providers = providers
      mergeTopoFromSaveConfig(cfg, config)
      mergeQQChannelFromSaveConfig(cfg, config)
      mergeWeixinChannelFromSaveConfig(cfg, config)
      ensureTopomobileChannelDefaults(cfg)
      const td = parseTopoDesktopFromCfg(cfg)
      if (td) {
        const ng = findProfileByModel(td.nonGuiProfiles, td.activeNonGuiModel) ?? td.nonGuiProfiles[0]
        if (ng) writeOpenclawEnvKey(baseDir, ng.apiKey)
      } else {
        writeOpenclawEnvKey(baseDir, config.apiKey ?? '')
      }
      fs.writeFileSync(configPath, JSON.stringify(cfg, null, 2) + '\n', 'utf-8')
      return { ok: true }
    } catch (e) {
      console.error('builtin-assistant:save-config', e)
      return { ok: false, error: String(e) }
    }
  }
  )

  ipcMain.handle(
    'builtin-assistant:set-topomobile-node-id',
    async (_e, nodeIdInput: string): Promise<{ ok: boolean; error?: string }> => {
      try {
        const nodeId = normalizeTopomobileNodeId(nodeIdInput)
        if (!nodeId) return { ok: false, error: 'nodeId 为空' }
        const baseDir = resolveOpenclawMain2BaseDir()
        if (!baseDir) return { ok: false, error: 'TopoClaw 目录不存在' }
        const configPath = path.join(baseDir, 'config.json')
        let cfg: Record<string, unknown> = {}
        if (fs.existsSync(configPath)) {
          try {
            cfg = JSON.parse(fs.readFileSync(configPath, 'utf-8')) as Record<string, unknown>
          } catch {
            cfg = {}
          }
        }
        const channels = ((cfg.channels as Record<string, unknown> | undefined) ?? {}) as Record<string, unknown>
        const topomobile = ((channels.topomobile as Record<string, unknown> | undefined) ?? {}) as Record<string, unknown>
        const beforeNodeId = normalizeTopomobileNodeId(topomobile.nodeId)
        ensureTopomobileChannelDefaults(cfg, { preferredNodeId: nodeId })
        fs.writeFileSync(configPath, JSON.stringify(cfg, null, 2) + '\n', 'utf-8')
        if (beforeNodeId !== nodeId) {
          if (!isBuiltinServicesEnabled()) {
            return { ok: true }
          }
          const p = builtinAssistantProcesses.topoclaw
          if (p) {
            try {
              p.kill()
            } catch {
              // ignore
            }
            delete builtinAssistantProcesses.topoclaw
            const spec = BUILTIN_INSTANCE_SPECS.find((x) => x.slot === 'topoclaw')
            if (spec) {
              await ensureBuiltinSlotStarted(spec)
            }
          }
        }
        return { ok: true }
      } catch (e) {
        return { ok: false, error: String(e) }
      }
    }
  )

  ipcMain.handle(
    'builtin-assistant:set-active-customer-service-url',
    async (
      _e,
      customerServiceUrlInput: string
    ): Promise<{ ok: boolean; customerServiceUrl?: string; restarted?: boolean; error?: string }> => {
      try {
        const customerServiceUrl = normalizeCustomerServiceUrl(String(customerServiceUrlInput ?? ''))
        if (!customerServiceUrl) return { ok: false, error: 'customer_service 地址为空' }
        if (!toTopomobileRelayWsUrl(customerServiceUrl)) {
          return { ok: false, error: 'customer_service 地址格式非法' }
        }

        const before = runtimeActiveCustomerServiceUrl
        applyRuntimeActiveCustomerServiceUrl(customerServiceUrl)
        const currentRuntime = loadBuiltinRuntimeSettings()
        if (currentRuntime.activeCustomerServiceUrl !== customerServiceUrl) {
          saveBuiltinRuntimeSettings({ ...currentRuntime, activeCustomerServiceUrl: customerServiceUrl })
        }

        let restarted = false
        if (before !== customerServiceUrl && isBuiltinServicesEnabled()) {
          const p = builtinAssistantProcesses.topoclaw
          if (p) {
            try {
              p.kill()
            } catch {
              // ignore
            }
            delete builtinAssistantProcesses.topoclaw
            const spec = BUILTIN_INSTANCE_SPECS.find((x) => x.slot === 'topoclaw')
            if (spec) {
              await ensureBuiltinSlotStarted(spec)
              restarted = true
            }
          }
        }
        return { ok: true, customerServiceUrl, restarted }
      } catch (e) {
        return { ok: false, error: String(e) }
      }
    }
  )

  ipcMain.handle(
    'builtin-assistant:sync-topomobile-ws-url',
    async (
      _e,
      customerServiceUrlInput: string
    ): Promise<{ ok: boolean; wsUrl?: string; restarted?: boolean; error?: string }> => {
      try {
        const customerServiceUrl = normalizeCustomerServiceUrl(String(customerServiceUrlInput ?? ''))
        if (!customerServiceUrl) return { ok: false, error: 'customer_service 地址为空' }
        const nextWsUrl = toTopomobileRelayWsUrl(customerServiceUrl)
        if (!nextWsUrl) return { ok: false, error: 'customer_service 地址格式非法' }
        const beforeCustomerServiceUrl = runtimeActiveCustomerServiceUrl
        applyRuntimeActiveCustomerServiceUrl(customerServiceUrl)
        const currentRuntime = loadBuiltinRuntimeSettings()
        if (currentRuntime.activeCustomerServiceUrl !== customerServiceUrl) {
          saveBuiltinRuntimeSettings({ ...currentRuntime, activeCustomerServiceUrl: customerServiceUrl })
        }

        const baseDir = resolveOpenclawMain2BaseDir()
        if (!baseDir) return { ok: false, error: 'TopoClaw 目录不存在' }
        const configPath = path.join(baseDir, 'config.json')
        let cfg: Record<string, unknown> = {}
        if (fs.existsSync(configPath)) {
          try {
            cfg = JSON.parse(fs.readFileSync(configPath, 'utf-8')) as Record<string, unknown>
          } catch {
            cfg = {}
          }
        }
        ensureTopomobileChannelDefaults(cfg)
        const channels = ((cfg.channels as Record<string, unknown> | undefined) ?? {}) as Record<string, unknown>
        const topomobile = ((channels.topomobile as Record<string, unknown> | undefined) ?? {}) as Record<string, unknown>
        const beforeWsUrl = String(topomobile.wsUrl ?? '').trim()
        topomobile.wsUrl = nextWsUrl
        channels.topomobile = topomobile
        cfg.channels = channels

        const changed = beforeWsUrl !== nextWsUrl
        if (changed) {
          fs.writeFileSync(configPath, JSON.stringify(cfg, null, 2) + '\n', 'utf-8')
        }

        let restarted = false
        if ((changed || beforeCustomerServiceUrl !== customerServiceUrl) && isBuiltinServicesEnabled()) {
          const p = builtinAssistantProcesses.topoclaw
          if (p) {
            try {
              p.kill()
            } catch {
              // ignore
            }
            delete builtinAssistantProcesses.topoclaw
            const spec = BUILTIN_INSTANCE_SPECS.find((x) => x.slot === 'topoclaw')
            if (spec) {
              await ensureBuiltinSlotStarted(spec)
              restarted = true
            }
          }
        }
        return { ok: true, wsUrl: nextWsUrl, restarted }
      } catch (e) {
        return { ok: false, error: String(e) }
      }
    }
  )

  ipcMain.handle(
    'computer-use:scroll',
    async (_e, { delta, x, y }: { delta: number; x?: number; y?: number }) => {
      try {
        if (x != null && y != null) {
          const { x: sx, y: sy } = imageCoordsToScreen(x, y)
          await mouse.move(straightTo(new Point(sx, sy)))
        }
        const amount = Math.round(Math.abs(delta)) || 1
        if (delta > 0) {
          await mouse.scrollUp(amount)
        } else {
          await mouse.scrollDown(amount)
        }
        return { ok: true }
      } catch (e) {
        console.error('computer-use:scroll', e)
        return { ok: false, error: String(e) }
      }
    }
  )

  ipcMain.handle('computer-use:key', async (_e, { keyname }: { keyname: string }) => {
    try {
      const keys = parseKeyCombo(keyname)
      if (keys.length === 0) {
        return { ok: false, error: `无法解析按键: ${keyname}` }
      }
      await keyboard.type(...keys)
      return { ok: true }
    } catch (e) {
      console.error('computer-use:key', e)
      return { ok: false, error: String(e) }
    }
  })
}

const CODE_EXEC_TIMEOUT_MS = 30_000
const CODE_EXEC_MAX_OUTPUT = 10_000

function getPythonExecPath(): string | null {
  if (app.isPackaged) {
    const embedPath = path.join(process.resourcesPath, 'python-embed', 'python.exe')
    if (fs.existsSync(embedPath)) return embedPath
    return null
  }
  // 开发环境：先尝试内置，再回退到系统 python
  const projectRoot = path.join(__dirname, '..')
  const embedPath = path.join(projectRoot, 'resources', 'python-embed', 'python.exe')
  if (fs.existsSync(embedPath)) return embedPath
  // Windows 上常见为 python 或 py
  return 'python'
}

/** 导入名 -> pip 包名映射（import xxx 中的 xxx 与 pip install 名不一致时） */
const IMPORT_TO_PIP_PACKAGE: Record<string, string> = {
  docx: 'python-docx',
  PIL: 'Pillow',
  cv2: 'opencv-python',
  sklearn: 'scikit-learn',
  dateutil: 'python-dateutil',
  yaml: 'PyYAML',
  OpenCV: 'opencv-python',
  Crypto: 'pycryptodome',
  bs4: 'beautifulsoup4',
  serial: 'pyserial',
  typing_extensions: 'typing-extensions',
}

function mapImportNameToPipPackage(importName: string): string {
  const lower = importName.toLowerCase()
  for (const [k, v] of Object.entries(IMPORT_TO_PIP_PACKAGE)) {
    if (k.toLowerCase() === lower) return v
  }
  return importName
}

/** 从 stderr/error 中解析 ModuleNotFoundError，提取缺少的包名 */
function parseMissingPackage(errOutput: string): string | null {
  if (!errOutput || typeof errOutput !== 'string') return null
  const m = errOutput.match(/ModuleNotFoundError:\s*No module named\s+['"]([a-zA-Z0-9_-]+)['"]/i)
    || errOutput.match(/ImportError:\s*No module named\s+['"]([a-zA-Z0-9_-]+)['"]/i)
  return m ? m[1]! : null
}

/** 获取内置 Python 目录，用于终端 PATH 前置 */
function getPythonEmbedDir(): string | null {
  if (app.isPackaged) {
    const dir = path.join(process.resourcesPath, 'python-embed')
    if (fs.existsSync(path.join(dir, 'python.exe'))) return dir
    return null
  }
  const projectRoot = path.join(__dirname, '..')
  const dir = path.join(projectRoot, 'resources', 'python-embed')
  if (fs.existsSync(path.join(dir, 'python.exe'))) return dir
  return null
}

/** 构建仅含内置 Python 的环境，与终端完全对齐 */
function buildEmbedOnlyEnv(): NodeJS.ProcessEnv {
  const openclawBase = resolveOpenclawMain2BaseDir()
  const serperApiKey = (openclawBase ? readSerperApiKeyFromConfig(openclawBase) : '')
    || (process.env.SERPER_API_KEY ?? '').trim()
  const serperApiBase = (openclawBase ? readSerperApiBaseFromConfig(openclawBase) : '')
    || (process.env.SERPER_API_BASE ?? '').trim()
    || (process.env.SEARCH_API_BASE ?? '').trim()
  const base: NodeJS.ProcessEnv = { ...process.env, PYTHONIOENCODING: 'utf-8' }
  if (serperApiKey) {
    base.SERPER_API_KEY = serperApiKey
  }
  if (serperApiBase) {
    base.SERPER_API_BASE = serperApiBase
    base.SEARCH_API_BASE = serperApiBase
  }
  const embedDir = getPythonEmbedDir()
  if (!embedDir) return applyCustomerServiceEnvDefaults(base)
  const scriptsDir = path.join(embedDir, 'Scripts')
  const pathVal = fs.existsSync(scriptsDir) ? `${embedDir}${path.delimiter}${scriptsDir}` : embedDir
  return applyCustomerServiceEnvDefaults({ ...base, PATH: pathVal, Path: pathVal })
}

/** PublicHub 代理：浏览器风格公共头；Referer/Origin 按目标 URL 动态设置 */
const PUBLIC_HUB_PROXY_HEADERS_BASE: Record<string, string> = {
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
  'Accept': 'application/json, text/plain, */*',
  'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
  'sec-ch-ua': '"Chromium";v="120", "Not_A Brand";v="24"',
  'sec-ch-ua-mobile': '?0',
  'sec-ch-ua-platform': '"Windows"',
  'Sec-Fetch-Dest': 'empty',
  'Sec-Fetch-Mode': 'cors',
  'Sec-Fetch-Site': 'cross-site',
  'Priority': 'u=1, i',
  'Cache-Control': 'no-cache',
  'Pragma': 'no-cache',
}

function publicHubProxyHeadersForUrl(targetUrl: string): Record<string, string> {
  const origin = new URL(targetUrl).origin
  return {
    ...PUBLIC_HUB_PROXY_HEADERS_BASE,
    Referer: `${origin}/`,
    Origin: origin,
  }
}

function registerPublicHubFetchHandler() {
  ipcMain.handle('publichub:fetch', async (_e, url: string): Promise<{ success: boolean; data?: unknown; error?: string }> => {
    return new Promise((resolve) => {
      try {
        const urlObj = new URL(url)

        const options = {
          hostname: urlObj.hostname,
          port: urlObj.port || 443,
          path: urlObj.pathname + urlObj.search,
          method: 'GET',
          headers: publicHubProxyHeadersForUrl(url),
          timeout: 10000,
        }

        const req = https.request(options, (res) => {
          let data = ''
          res.setEncoding('utf8')
          res.on('data', (chunk) => {
            data += chunk
          })
          res.on('end', () => {
            if (res.statusCode === 200) {
              try {
                const jsonData = JSON.parse(data)
                resolve({ success: true, data: jsonData })
              } catch (e) {
                resolve({ success: false, error: `JSON解析失败: ${e}` })
              }
            } else if (res.statusCode === 429) {
              resolve({ success: false, error: '请求被限流 (429)' })
            } else {
              resolve({ success: false, error: `HTTP ${res.statusCode}` })
            }
          })
        })

        req.on('error', (err) => {
          resolve({ success: false, error: `请求失败: ${err.message}` })
        })

        req.on('timeout', () => {
          req.destroy()
          resolve({ success: false, error: '请求超时' })
        })

        req.end()
      } catch (e) {
        resolve({ success: false, error: `URL解析失败: ${e}` })
      }
    })
  })
}

function registerColorClawServiceHandler() {
  ipcMain.handle('colorclaw:get', async (_e, { path: urlPath }: { path: string }): Promise<{ success: boolean; data?: unknown; error?: string }> => {
    return new Promise((resolve) => {
      try {
        const urlObj = new URL(`http://127.0.0.1:18790${urlPath}`)

        const options = {
          hostname: urlObj.hostname,
          port: Number(urlObj.port) || 18790,
          path: urlObj.pathname + urlObj.search,
          method: 'GET',
          timeout: 60000,
        }

        const req = http.request(options, (res) => {
          let data = ''
          res.setEncoding('utf8')
          res.on('data', (chunk) => {
            data += chunk
          })
          res.on('end', () => {
            try {
              const json = JSON.parse(data)
              if (res.statusCode && res.statusCode >= 200 && res.statusCode < 300) {
                resolve({ success: true, data: json })
              } else {
                resolve({ success: false, error: (json as { detail?: string }).detail || `HTTP ${res.statusCode}` })
              }
            } catch (e) {
              resolve({ success: false, error: `响应解析失败: ${e}` })
            }
          })
        })

        req.on('error', (err) => {
          resolve({ success: false, error: `请求失败: ${err.message}` })
        })

        req.on('timeout', () => {
          req.destroy()
          resolve({ success: false, error: '请求超时' })
        })

        req.end()
      } catch (e) {
        resolve({ success: false, error: `请求异常: ${e}` })
      }
    })
  })

  ipcMain.handle('colorclaw:post', async (_e, { path: urlPath, body }: { path: string; body: unknown }): Promise<{ success: boolean; data?: unknown; error?: string }> => {
    return new Promise((resolve) => {
      try {
        const urlObj = new URL(`http://127.0.0.1:18790${urlPath}`)
        const bodyStr = JSON.stringify(body)

        const options = {
          hostname: urlObj.hostname,
          port: Number(urlObj.port) || 18790,
          path: urlObj.pathname + urlObj.search,
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Content-Length': Buffer.byteLength(bodyStr),
          },
          timeout: 60000,
        }

        const req = http.request(options, (res) => {
          let data = ''
          res.setEncoding('utf8')
          res.on('data', (chunk) => {
            data += chunk
          })
          res.on('end', () => {
            try {
              const json = JSON.parse(data)
              if (res.statusCode && res.statusCode >= 200 && res.statusCode < 300) {
                resolve({ success: true, data: json })
              } else {
                resolve({ success: false, error: (json as { detail?: string }).detail || `HTTP ${res.statusCode}` })
              }
            } catch (e) {
              resolve({ success: false, error: `响应解析失败: ${e}` })
            }
          })
        })

        req.on('error', (err) => {
          resolve({ success: false, error: `请求失败: ${err.message}` })
        })

        req.on('timeout', () => {
          req.destroy()
          resolve({ success: false, error: '请求超时' })
        })

        req.write(bodyStr)
        req.end()
      } catch (e) {
        resolve({ success: false, error: `请求异常: ${e}` })
      }
    })
  })

  ipcMain.handle('colorclaw:delete', async (_e, { path: urlPath }: { path: string }): Promise<{ success: boolean; data?: unknown; error?: string }> => {
    return new Promise((resolve) => {
      try {
        const urlObj = new URL(`http://127.0.0.1:18790${urlPath.startsWith('/') ? urlPath : `/${urlPath}`}`)

        const options = {
          hostname: urlObj.hostname,
          port: Number(urlObj.port) || 18790,
          path: urlObj.pathname + urlObj.search,
          method: 'DELETE',
          timeout: 60000,
        }

        const req = http.request(options, (res) => {
          let raw = ''
          res.setEncoding('utf8')
          res.on('data', (chunk) => {
            raw += chunk
          })
          res.on('end', () => {
            const ok = res.statusCode != null && res.statusCode >= 200 && res.statusCode < 300
            const trimmed = raw.trim()
            if (!trimmed) {
              if (ok) {
                resolve({ success: true, data: {} })
              } else {
                resolve({ success: false, error: `HTTP ${res.statusCode}` })
              }
              return
            }
            try {
              const json = JSON.parse(trimmed) as { detail?: string }
              if (ok) {
                resolve({ success: true, data: json })
              } else {
                resolve({ success: false, error: json.detail || `HTTP ${res.statusCode}` })
              }
            } catch (e) {
              resolve({ success: false, error: ok ? `响应解析失败: ${e}` : `HTTP ${res.statusCode}` })
            }
          })
        })

        req.on('error', (err) => {
          resolve({ success: false, error: `请求失败: ${err.message}` })
        })

        req.on('timeout', () => {
          req.destroy()
          resolve({ success: false, error: '请求超时' })
        })

        req.end()
      } catch (e) {
        resolve({ success: false, error: `请求异常: ${e}` })
      }
    })
  })
}

function registerCodeExecHandlers() {
  ipcMain.handle('code-exec:run', async (_e, { code }: { code: string }): Promise<{ success: boolean; stdout?: string; stderr?: string; error?: string; missingPackage?: string }> => {
    const pythonPath = getPythonExecPath()
    if (!pythonPath) {
      return { success: false, error: '未找到内置 Python，请将 Python Embeddable 解压到 resources/python-embed/ 目录。参见 resources/python-embed/README.md' }
    }
    if (!code || typeof code !== 'string') {
      return { success: false, error: '代码为空' }
    }
    const tempDir = app.getPath('temp')
    const tempFile = path.join(tempDir, `code-exec-${Date.now()}.py`)
    try {
      fs.writeFileSync(tempFile, code, 'utf-8')
      const execEnv = buildEmbedOnlyEnv()
      return await new Promise((resolve) => {
        const proc = spawn(pythonPath, [tempFile], {
          cwd: tempDir,
          stdio: ['ignore', 'pipe', 'pipe'],
          env: execEnv,
        })
        let stdout = ''
        let stderr = ''
        let resolved = false
        const finish = (result: { success: boolean; stdout?: string; stderr?: string; error?: string; missingPackage?: string }) => {
          if (resolved) return
          resolved = true
          clearTimeout(timer)
          if (!result.success) {
            const combined = [result.stderr, result.error].filter(Boolean).join('\n')
            const pkg = parseMissingPackage(combined)
            if (pkg) (result as { missingPackage?: string }).missingPackage = pkg
          }
          resolve(result)
        }
        const timer = setTimeout(() => {
          if (!resolved) {
            proc.kill('SIGTERM')
            finish({ success: false, error: '执行超时（30秒）' })
          }
        }, CODE_EXEC_TIMEOUT_MS)
        proc.stdout?.on('data', (chunk) => {
          stdout += String(chunk)
          if (stdout.length > CODE_EXEC_MAX_OUTPUT) {
            proc.kill()
            stdout = stdout.slice(0, CODE_EXEC_MAX_OUTPUT) + '\n...[输出已截断]'
          }
        })
        proc.stderr?.on('data', (chunk) => {
          stderr += String(chunk)
          if (stderr.length > CODE_EXEC_MAX_OUTPUT) {
            proc.kill()
            stderr = stderr.slice(0, CODE_EXEC_MAX_OUTPUT) + '\n...[输出已截断]'
          }
        })
        proc.on('error', (err) => {
          finish({ success: false, error: `启动失败: ${err.message}` })
        })
        proc.on('close', (codeExit, signal) => {
          if (resolved) return
          if (codeExit === 0 && !signal) {
            finish({ success: true, stdout: stdout.trim(), stderr: stderr.trim() || undefined })
          } else {
            const msg = signal ? `已超时或终止 (${signal})` : `退出码 ${codeExit}`
            finish({ success: false, stdout: stdout.trim(), stderr: (stderr.trim() || msg) as string })
          }
        })
      })
    } catch (e) {
      return { success: false, error: String(e) }
    } finally {
      try { fs.unlinkSync(tempFile) } catch (_) { /* ignore */ }
    }
  })

  ipcMain.handle('code-exec:install-package', async (_e, { packageName }: { packageName: string }): Promise<{ success: boolean; stdout?: string; stderr?: string; error?: string }> => {
    const pythonPath = getPythonExecPath()
    if (!pythonPath) {
      return { success: false, error: '未找到内置 Python' }
    }
    const name = String(packageName || '').trim()
    if (!name || !/^[a-zA-Z0-9_.-]+$/.test(name)) {
      return { success: false, error: '无效的包名' }
    }
    const pipName = mapImportNameToPipPackage(name)
    const execEnv = buildEmbedOnlyEnv()
    return new Promise((resolve) => {
      const proc = spawn(pythonPath, ['-m', 'pip', 'install', pipName], {
        stdio: ['ignore', 'pipe', 'pipe'],
        env: execEnv,
      })
      let stdout = ''
      let stderr = ''
      proc.stdout?.on('data', (chunk) => { stdout += String(chunk) })
      proc.stderr?.on('data', (chunk) => { stderr += String(chunk) })
      proc.on('error', (err) => resolve({ success: false, error: `启动失败: ${err.message}` }))
      proc.on('close', (code) => {
        if (code === 0) {
          resolve({ success: true, stdout: stdout.trim(), stderr: stderr.trim() || undefined })
        } else {
          resolve({ success: false, stdout: stdout.trim(), stderr: stderr.trim() || `退出码 ${code}` })
        }
      })
    })
  })
}

let terminalWindow: BrowserWindow | null = null
const ptyByWebContents = new Map<number, { write: (s: string) => void; resize: (c: number, r: number) => void; kill: (s?: string) => void }>()
let builtinRuntimeSettingsCache: BuiltinRuntimeSettings | null = null

function getBuiltinRuntimeSettingsPath(): string {
  return path.join(app.getPath('userData'), BUILTIN_RUNTIME_SETTINGS_FILE)
}

function loadBuiltinRuntimeSettings(): BuiltinRuntimeSettings {
  if (builtinRuntimeSettingsCache) return builtinRuntimeSettingsCache
  const defaults: BuiltinRuntimeSettings = { builtinServicesEnabled: true, activeCustomerServiceUrl: '' }
  try {
    const p = getBuiltinRuntimeSettingsPath()
    if (!fs.existsSync(p)) {
      builtinRuntimeSettingsCache = defaults
      return defaults
    }
    const raw = fs.readFileSync(p, 'utf-8')
    const parsed = JSON.parse(raw) as Partial<BuiltinRuntimeSettings>
    builtinRuntimeSettingsCache = {
      builtinServicesEnabled: parsed.builtinServicesEnabled !== false,
      activeCustomerServiceUrl: normalizeCustomerServiceUrl(String(parsed.activeCustomerServiceUrl || '')),
    }
    return builtinRuntimeSettingsCache
  } catch {
    builtinRuntimeSettingsCache = defaults
    return defaults
  }
}

function saveBuiltinRuntimeSettings(next: BuiltinRuntimeSettings): void {
  const normalized: BuiltinRuntimeSettings = {
    builtinServicesEnabled: next.builtinServicesEnabled !== false,
    activeCustomerServiceUrl: normalizeCustomerServiceUrl(String(next.activeCustomerServiceUrl || '')),
  }
  const p = getBuiltinRuntimeSettingsPath()
  fs.mkdirSync(path.dirname(p), { recursive: true })
  fs.writeFileSync(p, JSON.stringify(normalized, null, 2) + '\n', 'utf-8')
  builtinRuntimeSettingsCache = normalized
}

function isBuiltinServicesEnabled(): boolean {
  return loadBuiltinRuntimeSettings().builtinServicesEnabled !== false
}

function restoreActiveCustomerServiceUrlFromRuntimeSettings(): void {
  const saved = normalizeCustomerServiceUrl(loadBuiltinRuntimeSettings().activeCustomerServiceUrl || '')
  if (saved) {
    applyRuntimeActiveCustomerServiceUrl(saved)
    return
  }
  const fallback = normalizeCustomerServiceUrl(resolveMobileAgentCustomerServiceUrl())
  if (fallback) {
    applyRuntimeActiveCustomerServiceUrl(fallback)
    const cur = loadBuiltinRuntimeSettings()
    saveBuiltinRuntimeSettings({ ...cur, activeCustomerServiceUrl: fallback })
  }
}

function killAllTerminalSessions(): void {
  ptyByWebContents.forEach((p, wcId) => {
    try {
      p.kill()
    } catch {
      // ignore
    }
    ptyByWebContents.delete(wcId)
  })
}

function registerTerminalHandlers() {
  ipcMain.handle('terminal:open-window', () => {
    if (!isBuiltinServicesEnabled()) {
      return { ok: false, error: '内置服务总开关已关闭，终端不可用' }
    }
    if (terminalWindow && !terminalWindow.isDestroyed()) {
      terminalWindow.focus()
      return { ok: true }
    }
    const win = new BrowserWindow({
      width: 800,
      height: 500,
      minWidth: 400,
      minHeight: 300,
      frame: true,
      title: 'Python 终端 - TopoClaw',
      webPreferences: {
        nodeIntegration: false,
        contextIsolation: true,
        preload: path.join(__dirname, 'preload.js'),
      },
    })
    terminalWindow = win
    const wcId = win.webContents.id
    win.on('closed', () => {
      const p = ptyByWebContents.get(wcId)
      if (p) {
        try { p.kill() } catch (_) { /* ignore */ }
        ptyByWebContents.delete(wcId)
      }
      terminalWindow = null
    })

    if (process.env.NODE_ENV === 'development' || !app.isPackaged) {
      terminalWindow.loadURL('http://localhost:5173/terminal.html')
    } else {
      const appPath = app.getAppPath()
      const distBase = appPath.includes('.asar')
        ? path.join(appPath.replace(/app\.asar$/, 'app.asar.unpacked'), 'dist')
        : path.join(__dirname, '../dist')
      terminalWindow.loadFile(path.join(distBase, 'terminal.html'))
    }
    return { ok: true }
  })

  ipcMain.handle('terminal:create', async (event, payload?: { cwd?: string }) => {
    if (!isBuiltinServicesEnabled()) {
      return { ok: false, error: '内置服务总开关已关闭，终端不可用' }
    }
    if (!pty) {
      return { ok: false, error: 'node-pty 不可用。请安装 Visual Studio 生成工具后执行 npm install。' }
    }
    const wc = event.sender
    if (ptyByWebContents.has(wc.id)) {
      return { ok: true }
    }
    const embedDir = getPythonEmbedDir()
    const baseEnv: NodeJS.ProcessEnv = { ...process.env, PYTHONIOENCODING: 'utf-8' }
    if (embedDir) {
      // 仅使用内置 Python 环境，与 code-exec 完全对齐，不使用系统 PATH 中的 Python
      const scriptsDir = path.join(embedDir, 'Scripts')
      baseEnv.PATH = fs.existsSync(scriptsDir) ? `${embedDir}${path.delimiter}${scriptsDir}` : embedDir
      baseEnv.Path = baseEnv.PATH
    }

    try {
      const shell = process.platform === 'win32' ? 'cmd.exe' : process.env.SHELL || '/bin/bash'
      const reqCwdRaw = typeof payload?.cwd === 'string' ? payload.cwd.trim() : ''
      const reqCwd = reqCwdRaw ? path.resolve(reqCwdRaw) : ''
      const spawnCwd = reqCwd && fs.existsSync(reqCwd)
        ? reqCwd
        : (process.env.HOME || process.env.USERPROFILE || process.cwd())
      const p = pty.spawn(shell, [], {
        name: 'xterm-256color',
        cols: 80,
        rows: 24,
        cwd: spawnCwd,
        env: baseEnv,
      })
      ptyByWebContents.set(wc.id, p)
      p.onData((data: string) => {
        if (!wc.isDestroyed()) wc.send('terminal:data', data)
      })
      p.onExit(() => {
        ptyByWebContents.delete(wc.id)
      })
      return { ok: true }
    } catch (e) {
      return { ok: false, error: String(e) }
    }
  })

  ipcMain.on('terminal:write', (event, data: string) => {
    if (!isBuiltinServicesEnabled()) return
    const p = ptyByWebContents.get(event.sender.id)
    if (p) p.write(data)
  })

  ipcMain.on('terminal:resize', (event, { cols, rows }: { cols: number; rows: number }) => {
    if (!isBuiltinServicesEnabled()) return
    const p = ptyByWebContents.get(event.sender.id)
    if (p) p.resize(cols, rows)
  })
}

ipcMain.handle('shell:openExternal', async (_e, url: string) => {
  try {
    await shell.openExternal(url)
    return { success: true }
  } catch (e) {
    console.error('shell:openExternal', e)
    return { success: false, error: String(e) }
  }
})

ipcMain.handle('app:copy-file-to-clipboard', async (_e, filePath: string) => {
  try {
    const target = path.normalize(String(filePath || '').trim())
    if (!target) return { success: false, error: 'invalid path' }
    if (!fs.existsSync(target)) return { success: false, error: 'file not found' }
    const stat = fs.statSync(target)
    if (!stat.isFile()) return { success: false, error: 'target is not file' }
    if (process.platform !== 'win32') return { success: false, error: 'unsupported platform' }

    const escapedTarget = target.replace(/'/g, "''")
    const script = `$p='${escapedTarget}'; if([string]::IsNullOrWhiteSpace($p)){throw "invalid path"}; Set-Clipboard -LiteralPath $p`
    await new Promise<void>((resolve, reject) => {
      execFile(
        'powershell.exe',
        ['-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-Command', script],
        { windowsHide: true, timeout: 7000, maxBuffer: 64 * 1024 },
        (error) => {
          if (error) {
            reject(error)
            return
          }
          resolve()
        }
      )
    })
    return { success: true }
  } catch (e) {
    console.error('app:copy-file-to-clipboard', e)
    return { success: false, error: String(e) }
  }
})

ipcMain.handle('shell:openPath', async (_e, filePath: string) => {
  try {
    const target = String(filePath || '').trim()
    if (!target) return { success: false, error: 'invalid path' }
    const err = await shell.openPath(target)
    if (err) return { success: false, error: err }
    return { success: true }
  } catch (e) {
    console.error('shell:openPath', e)
    return { success: false, error: String(e) }
  }
})

ipcMain.handle('shell:showItemInFolder', async (_e, filePath: string) => {
  try {
    const target = String(filePath || '').trim()
    if (!target) return { success: false, error: 'invalid path' }
    shell.showItemInFolder(target)
    return { success: true }
  } catch (e) {
    console.error('shell:showItemInFolder', e)
    return { success: false, error: String(e) }
  }
})

function normalizeGeneratedFileTokenVariants(fileToken: string): string[] {
  const raw = String(fileToken || '').trim()
  if (!raw) return []

  const stripWrap = (input: string): string => {
    let s = String(input || '').trim()
    const pairs: Array<[string, string]> = [
      ['"', '"'],
      ["'", "'"],
      ['`', '`'],
      ['(', ')'],
      ['[', ']'],
      ['<', '>'],
      ['（', '）'],
      ['【', '】'],
      ['「', '」'],
      ['『', '』'],
    ]
    let changed = true
    while (changed && s.length >= 2) {
      changed = false
      for (const [l, r] of pairs) {
        if (s.startsWith(l) && s.endsWith(r)) {
          s = s.slice(l.length, s.length - r.length).trim()
          changed = true
          break
        }
      }
    }
    return s
  }

  const maybeDecodeFileUrl = (input: string): string => {
    const s = String(input || '').trim()
    if (!/^file:\/\//i.test(s)) return s
    try {
      return fileURLToPath(s)
    } catch {
      return s
    }
  }

  const stripSuffix = (input: string): string => {
    let s = String(input || '').trim()
    if (!s) return s
    s = s.replace(/[，。；：、！？,.!?;:）】』」>]+$/g, '')
    s = s.replace(/:(\d+)(?::\d+)?$/g, '')
    return s.trim()
  }

  const out = new Set<string>()
  const push = (value: string) => {
    const s = String(value || '').trim()
    if (!s) return
    out.add(s)
  }

  const base = stripWrap(raw)
  push(raw)
  push(base)
  push(maybeDecodeFileUrl(raw))
  push(maybeDecodeFileUrl(base))

  const withSuffixRemoved = stripSuffix(base)
  push(withSuffixRemoved)
  push(maybeDecodeFileUrl(withSuffixRemoved))
  push(stripWrap(withSuffixRemoved))

  return Array.from(out)
}

function resolveGeneratedFilePathCandidates(fileToken: string): { candidates: string[]; normalizedRaw: string } {
  const tokenVariants = normalizeGeneratedFileTokenVariants(fileToken)
  const normalizedRaw = tokenVariants[0] || String(fileToken || '').trim()
  const candidates: string[] = []
  const workspaceDir = resolveOpenclawWorkspaceDir()
  const appPath = app.getAppPath()
  const cwd = process.cwd()
  const bases = [workspaceDir, path.join(workspaceDir || '', 'chat_uploads'), appPath, cwd].filter(Boolean) as string[]
  for (const token of tokenVariants) {
    if (!token) continue
    if (path.isAbsolute(token)) {
      candidates.push(path.normalize(token))
      continue
    }
    for (const base of bases) {
      candidates.push(path.normalize(path.join(base, token)))
    }
  }
  const unique: string[] = []
  const seen = new Set<string>()
  for (const item of candidates) {
    const key = item.toLowerCase()
    if (seen.has(key)) continue
    seen.add(key)
    unique.push(item)
  }
  return { candidates: unique, normalizedRaw }
}

ipcMain.handle('shell:resolve-generated-file', async (_e, fileToken: string) => {
  try {
    const raw = String(fileToken || '').trim()
    if (!raw) return { success: false, error: 'invalid file token' }
    const { candidates, normalizedRaw } = resolveGeneratedFilePathCandidates(raw)
    for (const p of candidates) {
      if (fs.existsSync(p)) return { success: true, path: p }
    }
    return { success: false, error: `文件不存在：${normalizedRaw}` }
  } catch (e) {
    console.error('shell:resolve-generated-file', e)
    return { success: false, error: String(e) }
  }
})

ipcMain.handle('shell:reveal-generated-file', async (_e, fileToken: string) => {
  try {
    const raw = String(fileToken || '').trim()
    if (!raw) return { success: false, error: 'invalid file token' }
    const { candidates, normalizedRaw } = resolveGeneratedFilePathCandidates(raw)
    for (const p of candidates) {
      if (fs.existsSync(p)) {
        if (fs.statSync(p).isDirectory()) {
          const err = await shell.openPath(p)
          if (err) return { success: false, error: err }
        } else {
          shell.showItemInFolder(p)
        }
        return { success: true, path: p }
      }
    }

    return { success: false, error: `文件不存在：${normalizedRaw}` }
  } catch (e) {
    console.error('shell:reveal-generated-file', e)
    return { success: false, error: String(e) }
  }
})

ipcMain.on('screenshot-region:cancel', () => {
  closeScreenshotRegionOverlay()
})

ipcMain.on(
  'screenshot-region:confirm',
  (_e, dipRect: { x?: number; y?: number; width?: number; height?: number }) => {
    const x = Number(dipRect?.x)
    const y = Number(dipRect?.y)
    const width = Number(dipRect?.width)
    const height = Number(dipRect?.height)
    if (![x, y, width, height].every((n) => Number.isFinite(n))) {
      closeScreenshotRegionOverlay()
      return
    }
    void handleScreenshotRegionConfirm({ x, y, width, height })
  }
)

ipcMain.on('screenshot-assist-overlay:close', () => {
  closeScreenshotAssistOverlay()
})

ipcMain.on('screenshot-assist-overlay:submit', (_e, payload: { text?: string }) => {
  const text = String(payload?.text || '').trim()
  if (!text) return
  const imageBase64 = getClipboardImagePngBase64()
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('desktop-screenshot:prefill', {
      text,
      autoSend: true,
      imageBase64: imageBase64 || undefined,
      imageMime: imageBase64 ? 'image/png' : undefined,
      imageName: imageBase64 ? `screenshot-${Date.now()}.png` : undefined,
      forceTopoClaw: true,
      skipComposerImage: true,
    })
    showMainWindow()
  }
  closeScreenshotAssistOverlay()
})

ipcMain.on('screenshot-assist-overlay:save-quick-note', (_e, payload: { text?: string }) => {
  const text = String(payload?.text || '').trim()
  const imageBase64 = getClipboardImagePngBase64()
  if (!text && !imageBase64) {
    closeScreenshotAssistOverlay()
    return
  }
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('desktop-screenshot:prefill', {
      text,
      autoSend: false,
      imageBase64: imageBase64 || undefined,
      imageMime: imageBase64 ? 'image/png' : undefined,
      imageName: imageBase64 ? `screenshot-${Date.now()}.png` : undefined,
      skipComposerImage: true,
      saveToQuickNote: true,
    })
    showMainWindow()
  }
  closeScreenshotAssistOverlay()
})

app.whenReady().then(() => {
  Menu.setApplicationMenu(null)  // 隐藏顶部菜单栏 (File, Edit, View, Window, Help)
  restoreActiveCustomerServiceUrlFromRuntimeSettings()
  // 应用启动时自动读取一次本地 config.txt，避免首次登录前端模型下拉与本地不一致。
  tryApplyLocalConfigTxtToOpenclawConfig()
  registerComputerUseHandlers()
  registerPublicHubFetchHandler()
  registerColorClawServiceHandler()
  registerCodeExecHandlers()
  registerTerminalHandlers()
  createWindow()
  createTray()

  // 注册 F12 / Ctrl+Shift+I 打开开发者工具（菜单隐藏后系统快捷键不可用）
  globalShortcut.register('F12', () => {
    const w = BrowserWindow.getFocusedWindow()
    if (w?.webContents) w.webContents.toggleDevTools()
  })
  globalShortcut.register('CommandOrControl+Shift+I', () => {
    const w = BrowserWindow.getFocusedWindow()
    if (w?.webContents) w.webContents.toggleDevTools()
  })

  /** Windows/Linux：Ctrl+Alt+Q；macOS：Cmd+Alt+Q — 框选截图后写入剪贴板并弹出「需要我帮忙吗」 */
  const HOTKEY_DESKTOP_SCREENSHOT = 'CommandOrControl+Alt+Q'
  const okDesktopScreenshot = globalShortcut.register(HOTKEY_DESKTOP_SCREENSHOT, () => {
    openScreenshotRegionOverlay()
  })
  if (!okDesktopScreenshot) {
    console.warn('[globalShortcut] 截图快捷键注册失败（可能已被占用）:', HOTKEY_DESKTOP_SCREENSHOT)
  }

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow()
    } else {
      showMainWindow()
    }
  })

  /** 任意真正退出路径会先走此处，保证主窗 close 不再 preventDefault；并结束内置小助手 */
  app.on('before-quit', () => {
    appIsQuitting = true
    stopXiaoTuoMonitor()
    closeScreenshotAssistOverlay()
    closeScreenshotRegionOverlay()
    killAllBuiltinAssistantProcesses()
  })
})

app.on('window-all-closed', () => {
  globalShortcut.unregisterAll()
  // 主窗口关窗仅 hide，故通常仍有窗口存活；若用户关掉所有子窗口且主窗已销毁，则退出应用
  if (process.platform !== 'darwin' && BrowserWindow.getAllWindows().length === 0) {
    app.quit()
  }
})
