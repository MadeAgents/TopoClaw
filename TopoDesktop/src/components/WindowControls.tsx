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

import { useState, useEffect } from 'react'
import './WindowControls.css'

declare global {
  interface Window {
    electronAPI?: {
      getAppVersion?: () => Promise<string>
      minimize: () => void
      maximize: () => void
      close: () => void
      focus: () => void
      isMaximized: () => Promise<boolean>
      onMaximizeChange: (callback: (maximized: boolean) => void) => () => void
      notifyNewMessage?: (totalUnread: number) => void
      saveImageAs?: (
        dataUrl: string,
        defaultFileName?: string
      ) => Promise<{ ok: boolean; canceled?: boolean; error?: string }>
      saveChatImageToWorkspace?: (
        dataUrl: string,
        originalFileName?: string
      ) => Promise<{ ok: boolean; path?: string; error?: string }>
      saveChatFileToWorkspace?: (
        dataUrl: string,
        originalFileName?: string,
        batchDir?: string
      ) => Promise<{ ok: boolean; path?: string; error?: string }>
      readWorkspaceProfileFile?: (
        kind: 'soul' | 'memory'
      ) => Promise<{ ok: boolean; content?: string; path?: string; error?: string }>
      readWorkspaceProfileDefaultFile?: (
        kind: 'soul' | 'memory'
      ) => Promise<{ ok: boolean; content?: string; path?: string; error?: string }>
      writeWorkspaceProfileFile?: (
        kind: 'soul' | 'memory',
        content: string
      ) => Promise<{ ok: boolean; path?: string; error?: string }>
      saveCsvAs?: (
        text: string,
        defaultFileName?: string
      ) => Promise<{ ok: boolean; canceled?: boolean; error?: string; path?: string }>
      saveTextAs?: (
        text: string,
        defaultFileName?: string
      ) => Promise<{ ok: boolean; canceled?: boolean; error?: string; path?: string }>
      copyImage?: (opts: { dataUrl?: string; url?: string; fileUrl?: string }) => Promise<{ ok: boolean; error?: string }>
      /** 同步读取剪贴板位图 PNG base64，无图时 null（仅 Electron） */
      readClipboardImageBase64Sync?: () => string | null
      openExternal?: (url: string) => Promise<{ success: boolean; error?: string }>
      showItemInFolder?: (filePath: string) => Promise<{ success: boolean; error?: string }>
      revealGeneratedFile?: (fileToken: string) => Promise<{ success: boolean; error?: string; path?: string }>
      onDesktopScreenshotPrefill?: (
        callback: (payload: {
          text: string
          autoSend?: boolean
          imageBase64?: string
          imageMime?: string
          imageName?: string
          forceTopoClaw?: boolean
          skipComposerImage?: boolean
          saveToQuickNote?: boolean
        }) => void
      ) => () => void
    }
    terminalAPI?: {
      openWindow: () => Promise<{ ok: boolean; error?: string }>
    }
  }
}

export function WindowControls() {
  const [maximized, setMaximized] = useState(false)

  useEffect(() => {
    if (typeof window === 'undefined' || !window.electronAPI) return
    const api = window.electronAPI
    api.isMaximized().then(setMaximized)
    const unsubscribe = api.onMaximizeChange(setMaximized)
    return unsubscribe
  }, [])

  if (typeof window === 'undefined' || !window.electronAPI) return null
  const handleMinimize = (e: React.MouseEvent<HTMLButtonElement>) => {
    e.preventDefault()
    e.stopPropagation()
    window.electronAPI?.minimize?.()
  }
  const handleMaximize = (e: React.MouseEvent<HTMLButtonElement>) => {
    e.preventDefault()
    e.stopPropagation()
    window.electronAPI?.maximize?.()
  }
  const handleClose = (e: React.MouseEvent<HTMLButtonElement>) => {
    e.preventDefault()
    e.stopPropagation()
    window.electronAPI?.close?.()
  }
  return (
    <div className="window-controls">
      <button type="button" className="window-control window-control-minimize" onClick={handleMinimize} title="最小化" />
      <button
        type="button"
        className={`window-control window-control-maximize ${maximized ? 'is-maximized' : ''}`}
        onClick={handleMaximize}
        title={maximized ? '还原' : '最大化'}
      />
      <button type="button" className="window-control window-control-close" onClick={handleClose} title="关闭" />
    </div>
  )
}
