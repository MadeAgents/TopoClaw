export interface ToolGuardPromptPayload {
  content?: string
}

export const TOOL_GUARD_DENIED_BUBBLE = '用户拒绝授权，任务已结束。'
export type ToolGuardConfirmDecision = 'allow_once' | 'allow_for_task' | 'deny'

const STYLE_ID = 'tool-guard-confirm-style'

interface ParsedPromptDetail {
  toolName: string
  reason: string
  detail: string
  args?: unknown
}

let autoAllowRoots: string[] = []

function normalizePathForCompare(input: string): string {
  return String(input || '')
    .trim()
    .replace(/\\/g, '/')
    .replace(/\/+$/g, '')
    .toLowerCase()
}

function toCandidatePathsFromText(text: string): string[] {
  const s = String(text || '')
  if (!s) return []
  const out = new Set<string>()
  const windowsMatches = s.match(/[a-zA-Z]:[\\/][^"'`\s)>,;]+/g) || []
  windowsMatches.forEach((m) => out.add(m))
  const unixMatches = s.match(/\/[^"'`\s)>,;]+/g) || []
  unixMatches.forEach((m) => out.add(m))
  return Array.from(out)
}

function collectStringLeaves(input: unknown, out: string[], depth = 0): void {
  if (depth > 8 || input == null) return
  if (typeof input === 'string') {
    const v = input.trim()
    if (v) out.push(v)
    return
  }
  if (Array.isArray(input)) {
    input.forEach((v) => collectStringLeaves(v, out, depth + 1))
    return
  }
  if (typeof input === 'object') {
    for (const v of Object.values(input as Record<string, unknown>)) {
      collectStringLeaves(v, out, depth + 1)
    }
  }
}

function shouldAutoAllowForPayload(payload: ToolGuardPromptPayload): boolean {
  if (autoAllowRoots.length === 0) return false
  const parsed = parsePromptDetail(payload)
  const texts: string[] = []
  collectStringLeaves(parsed, texts)
  if (payload.content) texts.push(payload.content)
  const candidates = new Set<string>()
  for (const text of texts) {
    for (const p of toCandidatePathsFromText(text)) candidates.add(p)
    if (/^[a-zA-Z]:[\\/]/.test(text) || text.startsWith('/')) candidates.add(text)
  }
  if (candidates.size === 0) return false
  const normalizedRoots = autoAllowRoots.map((r) => normalizePathForCompare(r)).filter(Boolean)
  for (const candidate of candidates) {
    const c = normalizePathForCompare(candidate)
    if (!c) continue
    if (normalizedRoots.some((root) => c === root || c.startsWith(`${root}/`))) {
      return true
    }
  }
  return false
}

function parsePromptDetail(payload: ToolGuardPromptPayload): ParsedPromptDetail {
  const fallback: ParsedPromptDetail = {
    toolName: '',
    reason: '',
    detail: payload.content || '',
    args: undefined,
  }
  if (!payload.content) return fallback
  try {
    const obj = JSON.parse(payload.content) as {
      tool_name?: string
      arguments?: unknown
      reason?: string
      detail?: string
    }
    return {
      toolName: obj.tool_name || '',
      reason: obj.reason || '',
      detail: obj.detail || '',
      args: obj.arguments,
    }
  } catch {
    return fallback
  }
}

export function setToolGuardAutoAllowRoots(roots: string[]): void {
  const uniq: string[] = []
  const seen = new Set<string>()
  for (const root of roots || []) {
    const normalized = normalizePathForCompare(root)
    if (!normalized || seen.has(normalized)) continue
    seen.add(normalized)
    uniq.push(root.trim())
  }
  autoAllowRoots = uniq
}

function ensureStyle() {
  if (document.getElementById(STYLE_ID)) return
  const style = document.createElement('style')
  style.id = STYLE_ID
  style.textContent = `
.tool-guard-overlay{
  position:fixed;inset:0;z-index:9999;
  background:rgba(15,18,28,.45);backdrop-filter:blur(4px);
  display:flex;align-items:center;justify-content:center;padding:20px;
}
.tool-guard-card{
  width:min(460px,calc(100vw - 48px));max-height:82vh;overflow:auto;
  border-radius:12px;border:1px solid var(--border-subtle,#d8dce6);
  background:var(--surface-elevated,#fff);
  box-shadow:0 16px 48px rgba(0,0,0,.18);
  color:var(--text-primary,#1a1d26);padding:24px 28px;
}
.tool-guard-title{font-size:1.1rem;font-weight:600;line-height:1.35;margin-bottom:10px;color:var(--text-primary,#1a1d26);}
.tool-guard-subtitle{font-size:.92rem;line-height:1.55;color:var(--text-secondary,#47506a);margin-bottom:14px;}
.tool-guard-grid{display:grid;grid-template-columns:64px 1fr;gap:8px 10px;margin-bottom:18px;font-size:.9rem;}
.tool-guard-k{color:var(--text-secondary,#47506a);}
.tool-guard-v{color:var(--text-primary,#1a1d26);word-break:break-word;white-space:pre-wrap;}
.tool-guard-actions{display:flex;justify-content:flex-end;gap:10px;}
.tool-guard-btn{border:1px solid transparent;border-radius:8px;padding:8px 16px;font-size:.9rem;font-weight:500;cursor:pointer;transition:all .15s ease;}
.tool-guard-btn-deny{background:transparent;border-color:var(--border-subtle,#d8dce6);color:var(--text-primary,#1a1d26);}
.tool-guard-btn-deny:hover{background:rgba(0,0,0,.04);}
.tool-guard-btn-allow-all{background:#2447cf;color:#fff;border-color:#2447cf;}
.tool-guard-btn-allow-all:hover{filter:brightness(1.06);}
.tool-guard-btn-allow{background:var(--accent,#3b6cff);color:#fff;border-color:var(--accent,#3b6cff);}
.tool-guard-btn-allow:hover{filter:brightness(1.05);}
`
  document.head.appendChild(style)
}

export function showToolGuardConfirmModal(payload: ToolGuardPromptPayload): Promise<ToolGuardConfirmDecision> {
  if (shouldAutoAllowForPayload(payload)) {
    return Promise.resolve('allow_once')
  }
  if (typeof document === 'undefined') {
    return Promise.resolve(window.confirm('检测到越权文件删改请求，是否本次允许？') ? 'allow_once' : 'deny')
  }
  ensureStyle()
  const detail = parsePromptDetail(payload)
  return new Promise<ToolGuardConfirmDecision>((resolve) => {
    const overlay = document.createElement('div')
    overlay.className = 'tool-guard-overlay'

    const card = document.createElement('div')
    card.className = 'tool-guard-card'

    const title = document.createElement('div')
    title.className = 'tool-guard-title'
    title.textContent = '需要授权：越权文件删改'

    const subtitle = document.createElement('div')
    subtitle.className = 'tool-guard-subtitle'
    subtitle.textContent = '当前操作将尝试修改工作区之外的文件。你可以本次允许，或拒绝并结束任务。'

    const grid = document.createElement('div')
    grid.className = 'tool-guard-grid'
    const rows: Array<[string, string]> = [
      ['工具', detail.toolName || '-'],
      ['原因', detail.reason || '-'],
      ['详情', detail.detail || '-'],
    ]
    for (const [k, v] of rows) {
      const key = document.createElement('div')
      key.className = 'tool-guard-k'
      key.textContent = k
      const value = document.createElement('div')
      value.className = 'tool-guard-v'
      value.textContent = v
      grid.appendChild(key)
      grid.appendChild(value)
    }

    card.appendChild(title)
    card.appendChild(subtitle)
    card.appendChild(grid)

    const actions = document.createElement('div')
    actions.className = 'tool-guard-actions'
    const denyBtn = document.createElement('button')
    denyBtn.className = 'tool-guard-btn tool-guard-btn-deny'
    denyBtn.type = 'button'
    denyBtn.textContent = '拒绝并结束任务'
    const allowAllBtn = document.createElement('button')
    allowAllBtn.className = 'tool-guard-btn tool-guard-btn-allow-all'
    allowAllBtn.type = 'button'
    allowAllBtn.textContent = '允许本次任务的所有操作'
    const allowBtn = document.createElement('button')
    allowBtn.className = 'tool-guard-btn tool-guard-btn-allow'
    allowBtn.type = 'button'
    allowBtn.textContent = '本次允许'
    actions.appendChild(denyBtn)
    actions.appendChild(allowAllBtn)
    actions.appendChild(allowBtn)
    card.appendChild(actions)
    overlay.appendChild(card)
    document.body.appendChild(overlay)

    let settled = false
    const finish = (decision: ToolGuardConfirmDecision) => {
      if (settled) return
      settled = true
      window.removeEventListener('keydown', onKeydown)
      overlay.remove()
      resolve(decision)
    }

    const onKeydown = (ev: KeyboardEvent) => {
      if (ev.key === 'Escape') finish('deny')
    }

    allowBtn.addEventListener('click', () => finish('allow_once'))
    allowAllBtn.addEventListener('click', () => finish('allow_for_task'))
    denyBtn.addEventListener('click', () => finish('deny'))
    window.addEventListener('keydown', onKeydown)
    allowBtn.focus()
  })
}
