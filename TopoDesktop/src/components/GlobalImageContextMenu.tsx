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

import { useCallback, useEffect, useRef, useState } from 'react'
import { copyImageFromSrc, srcToDataUrlIfNeeded } from '../utils/imageClipboard'
import { saveChatImageToDisk } from './ChatInlineImage'
import './ChatInlineImage.css'

type MenuState = { x: number; y: number; src: string } | null

/**
 * 在捕获阶段拦截右键：对普通 <img> 与带 data-copy-image-src 的节点显示「复制图片 / 另存为」。
 * 排除已自带菜单的聊天缩略图（.chat-image-local-context）。
 */
export function GlobalImageContextMenu() {
  const [menu, setMenu] = useState<MenuState>(null)
  const menuRef = useRef<HTMLDivElement>(null)

  const close = useCallback(() => setMenu(null), [])

  useEffect(() => {
    const onContextMenu = (e: MouseEvent) => {
      const t = e.target
      if (!(t instanceof Element)) return
      if (t.closest('.chat-image-context-menu')) return
      if (t.closest('.chat-image-local-context')) return

      let src: string | undefined
      if (t instanceof HTMLImageElement) {
        const s = (t.currentSrc || t.src || '').trim()
        if (s && !s.startsWith('about:') && s !== window.location.href) src = s
      }
      if (!src) {
        const host = t.closest('[data-copy-image-src]')
        if (host instanceof HTMLElement) {
          const d = host.dataset.copyImageSrc?.trim()
          if (d) src = d
        }
      }
      if (!src) return

      e.preventDefault()
      e.stopPropagation()
      setMenu({ x: e.clientX, y: e.clientY, src })
    }
    document.addEventListener('contextmenu', onContextMenu, true)
    return () => document.removeEventListener('contextmenu', onContextMenu, true)
  }, [])

  useEffect(() => {
    if (!menu) return
    const onDown = (e: MouseEvent) => {
      if (menuRef.current?.contains(e.target as Node)) return
      close()
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') close()
    }
    document.addEventListener('mousedown', onDown, true)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown, true)
      document.removeEventListener('keydown', onKey)
    }
  }, [menu, close])

  const handleCopy = useCallback(async () => {
    if (!menu) return
    const { src } = menu
    close()
    const r = await copyImageFromSrc(src)
    if (!r.ok && r.error) window.alert(r.error)
  }, [menu, close])

  const handleSaveAs = useCallback(async () => {
    if (!menu) return
    const { src } = menu
    close()
    try {
      const dataUrl = src.startsWith('data:') ? src : await srcToDataUrlIfNeeded(src)
      await saveChatImageToDisk(dataUrl, '图片.png')
    } catch (err) {
      window.alert(err instanceof Error ? err.message : String(err))
    }
  }, [menu, close])

  if (!menu) return null

  const vw = typeof window !== 'undefined' ? window.innerWidth : 800
  const vh = typeof window !== 'undefined' ? window.innerHeight : 600
  const mw = 200
  const mh = 120
  const left = Math.min(menu.x, vw - mw - 8)
  const top = Math.min(menu.y, vh - mh - 8)

  return (
    <div
      ref={menuRef}
      className="chat-image-context-menu"
      style={{ left, top }}
      role="menu"
    >
      <button type="button" className="chat-image-context-item" role="menuitem" onClick={() => void handleCopy()}>
        复制图片
      </button>
      <button type="button" className="chat-image-context-item" role="menuitem" onClick={() => void handleSaveAs()}>
        图片另存为…
      </button>
    </div>
  )
}
