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

import { useCallback, useEffect, useMemo, useRef, useState, type WheelEvent } from 'react'
import { copyImageFromSrc } from '../utils/imageClipboard'
import './ChatInlineImage.css'

export type ImageLightboxItem = { dataUrl: string; fileName?: string }

export type ImageLightboxPayload = {
  dataUrl: string
  fileName?: string
  gallery?: ImageLightboxItem[]
  initialIndex?: number
}

export function ChatImageLightbox({
  payload,
  onClose,
}: {
  payload: ImageLightboxPayload | null
  onClose: () => void
}) {
  const lightboxItems = useMemo<ImageLightboxItem[]>(() => {
    if (!payload) return []
    if (payload.gallery?.length) return payload.gallery
    return [{ dataUrl: payload.dataUrl, fileName: payload.fileName }]
  }, [payload])

  const [currentIndex, setCurrentIndex] = useState(0)
  const [zoomScale, setZoomScale] = useState(1)
  const [panOffset, setPanOffset] = useState({ x: 0, y: 0 })
  const [isDraggingImage, setIsDraggingImage] = useState(false)
  const dragStateRef = useRef<{
    startX: number
    startY: number
    originX: number
    originY: number
    moved: boolean
  } | null>(null)
  const suppressCloseClickRef = useRef(false)

  useEffect(() => {
    if (!payload) return
    const total = lightboxItems.length
    if (total <= 0) {
      setCurrentIndex(0)
      return
    }
    let nextIndex = typeof payload.initialIndex === 'number' ? payload.initialIndex : -1
    if (nextIndex < 0) {
      nextIndex = lightboxItems.findIndex((item) => item.dataUrl === payload.dataUrl && item.fileName === payload.fileName)
    }
    if (nextIndex < 0) nextIndex = 0
    if (nextIndex >= total) nextIndex = total - 1
    setCurrentIndex(nextIndex)
  }, [payload, lightboxItems])

  const safeIndex = useMemo(() => {
    if (lightboxItems.length <= 0) return 0
    return Math.max(0, Math.min(currentIndex, lightboxItems.length - 1))
  }, [currentIndex, lightboxItems.length])

  const activeItem = lightboxItems[safeIndex] || null
  const hasPrev = safeIndex > 0
  const hasNext = safeIndex < lightboxItems.length - 1
  const zoomPercent = Math.round(zoomScale * 100)

  const handlePrev = useCallback(() => {
    setCurrentIndex((prev) => Math.max(0, prev - 1))
  }, [])

  const handleNext = useCallback(() => {
    setCurrentIndex((prev) => Math.min(lightboxItems.length - 1, prev + 1))
  }, [lightboxItems.length])

  useEffect(() => {
    setZoomScale(1)
    setPanOffset({ x: 0, y: 0 })
    setIsDraggingImage(false)
    dragStateRef.current = null
  }, [payload, safeIndex])

  useEffect(() => {
    if (zoomScale <= 1) setPanOffset({ x: 0, y: 0 })
  }, [zoomScale])

  const handleCtrlWheelZoom = useCallback((e: WheelEvent<HTMLDivElement>) => {
    if (!e.ctrlKey) return
    e.preventDefault()
    e.stopPropagation()
    const zoomFactor = e.deltaY < 0 ? 1.12 : 1 / 1.12
    setZoomScale((prev) => {
      const next = prev * zoomFactor
      if (next < 0.2) return 0.2
      if (next > 6) return 6
      return next
    })
  }, [])

  const handleStartDrag = useCallback((e: React.MouseEvent<HTMLDivElement>) => {
    if (e.button !== 0) return
    if (zoomScale <= 1) return
    e.preventDefault()
    e.stopPropagation()
    dragStateRef.current = {
      startX: e.clientX,
      startY: e.clientY,
      originX: panOffset.x,
      originY: panOffset.y,
      moved: false,
    }
    setIsDraggingImage(true)
  }, [panOffset.x, panOffset.y, zoomScale])

  const handleStopDrag = useCallback(() => {
    const state = dragStateRef.current
    if (state?.moved) suppressCloseClickRef.current = true
    dragStateRef.current = null
    setIsDraggingImage(false)
  }, [])

  const handleDragMove = useCallback((e: MouseEvent) => {
    const state = dragStateRef.current
    if (!state) return
    const deltaX = e.clientX - state.startX
    const deltaY = e.clientY - state.startY
    if (!state.moved && (Math.abs(deltaX) > 2 || Math.abs(deltaY) > 2)) state.moved = true
    setPanOffset({
      x: state.originX + deltaX,
      y: state.originY + deltaY,
    })
  }, [])

  useEffect(() => {
    if (!isDraggingImage) return
    const onMove = (e: MouseEvent) => handleDragMove(e)
    const onUp = () => handleStopDrag()
    window.addEventListener('mousemove', onMove)
    window.addEventListener('mouseup', onUp)
    return () => {
      window.removeEventListener('mousemove', onMove)
      window.removeEventListener('mouseup', onUp)
    }
  }, [handleDragMove, handleStopDrag, isDraggingImage])

  useEffect(() => {
    if (!payload) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
      if (e.key === 'ArrowLeft' && hasPrev) {
        e.preventDefault()
        handlePrev()
      }
      if (e.key === 'ArrowRight' && hasNext) {
        e.preventDefault()
        handleNext()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [payload, onClose, handleNext, handlePrev, hasNext, hasPrev])

  if (!payload) return null

  return (
    <div
      className="chat-image-lightbox"
      onClick={() => {
        if (suppressCloseClickRef.current) {
          suppressCloseClickRef.current = false
          return
        }
        onClose()
      }}
      role="presentation"
    >
      <button
        type="button"
        className="chat-image-lightbox-nav chat-image-lightbox-nav-left"
        aria-label="查看上一张图片"
        disabled={!hasPrev}
        onClick={(e) => {
          e.stopPropagation()
          handlePrev()
        }}
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="m15 18-6-6 6-6" />
        </svg>
      </button>
      <button
        type="button"
        className="chat-image-lightbox-nav chat-image-lightbox-nav-right"
        aria-label="查看下一张图片"
        disabled={!hasNext}
        onClick={(e) => {
          e.stopPropagation()
          handleNext()
        }}
      >
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="m9 18 6-6-6-6" />
        </svg>
      </button>
      <button
        type="button"
        className="chat-image-lightbox-close"
        aria-label="关闭"
        onClick={(e) => {
          e.stopPropagation()
          onClose()
        }}
      >
        ×
      </button>
      <div className="chat-image-lightbox-zoom-indicator" aria-live="polite">
        {zoomPercent}%
      </div>
      <div
        className={`chat-image-lightbox-inner${zoomScale > 1 ? ' is-draggable' : ''}${isDraggingImage ? ' is-dragging' : ''}`}
        onClick={(e) => e.stopPropagation()}
        onWheel={handleCtrlWheelZoom}
        onMouseDown={handleStartDrag}
        onDoubleClick={(e) => {
          e.stopPropagation()
          setZoomScale(1)
          setPanOffset({ x: 0, y: 0 })
        }}
      >
        {activeItem ? (
          <img
            src={activeItem.dataUrl}
            alt=""
            className="chat-image-lightbox-img"
            style={{ transform: `translate(${panOffset.x}px, ${panOffset.y}px) scale(${zoomScale})` }}
            draggable={false}
            onDragStart={(e) => e.preventDefault()}
          />
        ) : null}
      </div>
    </div>
  )
}

interface ChatInlineImageProps {
  dataUrl: string
  fileName?: string
  className?: string
  onOpenLightbox: (payload: ImageLightboxPayload) => void
  /** 在图片右键菜单中提供「记入随手记」 */
  onAddToQuickNote?: () => void
}

export async function saveChatImageToDisk(dataUrl: string, fileName?: string): Promise<void> {
  const api = typeof window !== 'undefined' ? window.electronAPI : undefined
  if (api?.saveImageAs) {
    const r = await api.saveImageAs(dataUrl, fileName || '图片.png')
    if (!r.ok && !r.canceled && r.error) console.warn('[ChatInlineImage] 保存失败:', r.error)
    return
  }
  const a = document.createElement('a')
  a.href = dataUrl
  a.download = fileName || 'image.png'
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
}

export function ChatInlineImage({ dataUrl, fileName, className, onOpenLightbox, onAddToQuickNote }: ChatInlineImageProps) {
  const [menu, setMenu] = useState<{ x: number; y: number } | null>(null)
  const menuRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!menu) return
    const onDown = (e: MouseEvent) => {
      if (menuRef.current?.contains(e.target as Node)) return
      setMenu(null)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setMenu(null)
    }
    document.addEventListener('mousedown', onDown, true)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDown, true)
      document.removeEventListener('keydown', onKey)
    }
  }, [menu])

  const openLightbox = useCallback(() => {
    onOpenLightbox({ dataUrl, fileName })
  }, [dataUrl, fileName, onOpenLightbox])

  const handleSaveAs = useCallback(async () => {
    setMenu(null)
    await saveChatImageToDisk(dataUrl, fileName)
  }, [dataUrl, fileName])

  const handleCopy = useCallback(async () => {
    setMenu(null)
    const r = await copyImageFromSrc(dataUrl)
    if (!r.ok && r.error) window.alert(r.error)
  }, [dataUrl])

  if (!dataUrl) return null

  return (
    <>
      <img
        src={dataUrl}
        alt=""
        role="button"
        tabIndex={0}
        className={['chat-image-local-context', className].filter(Boolean).join(' ')}
        title="点击查看大图 · 右键可复制或另存"
        onClick={(e) => {
          e.stopPropagation()
          openLightbox()
        }}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault()
            openLightbox()
          }
        }}
        onContextMenu={(e) => {
          e.preventDefault()
          e.stopPropagation()
          setMenu({ x: e.clientX, y: e.clientY })
        }}
      />
      {menu && (
        <div
          ref={menuRef}
          className="chat-image-context-menu"
          style={{ left: menu.x, top: menu.y }}
          role="menu"
        >
          <button type="button" className="chat-image-context-item" role="menuitem" onClick={openLightbox}>
            查看大图
          </button>
          {onAddToQuickNote ? (
            <button
              type="button"
              className="chat-image-context-item"
              role="menuitem"
              onClick={() => {
                setMenu(null)
                onAddToQuickNote()
              }}
            >
              记入随手记
            </button>
          ) : null}
          <button type="button" className="chat-image-context-item" role="menuitem" onClick={() => void handleCopy()}>
            复制图片
          </button>
          <button type="button" className="chat-image-context-item" role="menuitem" onClick={handleSaveAs}>
            图片另存为…
          </button>
        </div>
      )}
    </>
  )
}
