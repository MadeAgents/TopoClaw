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

import { Terminal } from 'xterm'
import { FitAddon } from '@xterm/addon-fit'
import 'xterm/css/xterm.css'

const api = typeof window !== 'undefined' ? (window as Window & { terminalAPI?: TerminalAPI }).terminalAPI : undefined

interface TerminalAPI {
  create: () => Promise<{ ok: boolean; error?: string }>
  write: (data: string) => void
  resize: (cols: number, rows: number) => void
  onData: (callback: (data: string) => void) => () => void
}

function run() {
  const root = document.getElementById('terminal-root')
  if (!root) return

  if (!api) {
    root.innerHTML = '<pre style="padding:16px;color:#f00;">需在 Electron 环境中运行</pre>'
    return
  }

  const term = new Terminal({
    cursorBlink: true,
    fontSize: 14,
    fontFamily: 'Consolas, "Courier New", monospace',
  })
  const fitAddon = new FitAddon()
  term.loadAddon(fitAddon)

  root.style.cssText = 'width:100%;height:100%;padding:8px;box-sizing:border-box;background:#1e1e1e;'
  term.open(root)

  const doFit = () => {
    fitAddon.fit()
    api.resize(term.cols, term.rows)
  }

  let unsub: (() => void) | null = null
  let created = false

  api.create().then((r) => {
    if (!r.ok) {
      term.writeln(`\r\n\x1b[31m错误: ${r.error || '创建终端失败'}\x1b[0m`)
      return
    }
    created = true
    doFit()
    unsub = api.onData((data) => term.write(data))
    term.writeln('\x1b[32mPython 内置环境终端（PATH 已包含内置 python/pip）\x1b[0m')
    term.writeln('输入 exit 或关闭窗口退出。\r\n')
  })

  term.onData((data) => {
    if (created) api.write(data)
  })

  window.addEventListener('resize', doFit)

  term.onDispose?.() || term.element?.addEventListener?.('destroy', () => {
    window.removeEventListener('resize', doFit)
    unsub?.()
  })
}

run()
