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

import { useState, useEffect, useRef, useCallback, useMemo } from 'react'
import { createPortal } from 'react-dom'
import { isBuiltinAssistantLogPipeActive, type BuiltinAssistantSlot } from '../services/builtinAssistantConfig'
import './BuiltinAssistantLogModal.css'

interface BuiltinAssistantLogModalProps {
  slot: BuiltinAssistantSlot
  onClose: () => void
}

const SLOT_TITLES: Record<BuiltinAssistantSlot, string> = {
  topoclaw: 'TopoClaw日志',
  groupmanager: 'GroupManager（SimpleChat）日志',
}

export function BuiltinAssistantLogModal({ slot, onClose }: BuiltinAssistantLogModalProps) {
  const [lines, setLines] = useState<string[]>([])
  const [filterMode, setFilterMode] = useState<'all' | 'io' | 'error'>('all')
  const [isFullscreen, setIsFullscreen] = useState(false)
  const [searchOpen, setSearchOpen] = useState(false)
  const [searchKeyword, setSearchKeyword] = useState('')
  const [logPipeActive, setLogPipeActive] = useState<boolean | null>(null)
  const [refreshing, setRefreshing] = useState(false)
  const [refreshError, setRefreshError] = useState('')
  const containerRef = useRef<HTMLDivElement>(null)
  const MAX_LINES = 3000

  const toolCallPattern = /Tool call:\s*(.+)$/i
  const toolReturnPattern = /Tool\s+([\w-]+)\s+returned\s+(error|result):\s*(.+)$/i
  const modelPattern = /调用模型:\s*([^|(]+).*$/i
  const modelIoPattern = /模型调用\s*\d+\s*[：:]/i
  const modelIoDetailPattern = /模型调用\s*\d+\s*[：:][\s\S]*【输入内容\/[\s\S]*【输出内容\//i
  const autoSendPattern = /Auto-sent generated images via message tool:\s*(.+)$/i
  const errorLinePattern =
    /(error|warning|exception|traceback|failed|失败|invalid|not found|timed out)/i

  const decodeEscapedMultilineText = useCallback((text: string): string => {
    return String(text || '')
      .replace(/\\r\\n/g, '\n')
      .replace(/\\n/g, '\n')
      .replace(/\\t/g, '\t')
      .trim()
  }, [])

  const formatModelIoDetailLine = useCallback((line: string): string => {
    const idx = line.indexOf('模型调用')
    const body = (idx >= 0 ? line.slice(idx) : line).trim()
    const callNo = body.match(/模型调用\s*(\d+)\s*[：:]/i)?.[1] ?? '?'
    const duration = body.match(/【持续时间\/([^】]+)】/)?.[1]?.trim() ?? ''
    const input = body.match(/【输入内容\/([\s\S]*?)】(?:\s*【输出内容\/|$)/)?.[1] ?? ''
    const output = body.match(/【输出内容\/([\s\S]*?)】(?:\s*【持续时间\/|$)/)?.[1] ?? ''
    const decodedInput = decodeEscapedMultilineText(input)
    const decodedOutput = decodeEscapedMultilineText(output)
    if (!decodedInput && !decodedOutput) return body
    const title = `模型调用${callNo}${duration ? `（持续时间 ${duration}）` : ''}`
    return [
      title,
      '--- 输入 ---',
      decodedInput || '(空)',
      '--- 输出 ---',
      decodedOutput || '(空)',
    ].join('\n')
  }, [decodeEscapedMultilineText])

  const toIoDisplayLine = useCallback((line: string): string | null => {
    const timeMatch = line.match(/^(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})/)
    const ts = timeMatch?.[1] ?? ''
    const prefix = ts ? `[${ts}] ` : ''

    const toolCall = line.match(toolCallPattern)
    if (toolCall?.[1]) return `${prefix}IN  ${toolCall[1]}`

    const toolRet = line.match(toolReturnPattern)
    if (toolRet?.[1] && toolRet?.[2] && toolRet?.[3]) {
      const kind = toolRet[2].toLowerCase() === 'error' ? 'OUT ERROR' : 'OUT'
      return `${prefix}${kind} ${toolRet[1]}: ${toolRet[3]}`
    }

    const modelCall = line.match(modelPattern)
    if (modelCall?.[1]) return `${prefix}MODEL ${modelCall[1].trim()}`

    if (modelIoPattern.test(line)) {
      const detail = formatModelIoDetailLine(line)
      return `${prefix}${detail}`
    }

    const autoSend = line.match(autoSendPattern)
    if (autoSend?.[1]) return `${prefix}OUT message_tool: ${autoSend[1]}`

    return null
  }, [formatModelIoDetailLine])

  const toNonIoDisplayLine = useCallback((line: string): string => {
    if (!modelIoDetailPattern.test(line)) return line
    const callNo = line.match(/模型调用\s*(\d+)\s*：/i)?.[1] ?? '?'
    const duration = line.match(/【持续时间\/([^】]+)】/)?.[1] ?? ''
    const suffix = duration ? ` 持续时间=${duration}` : ''
    return `模型调用${callNo}：输入输出详情已隐藏（切换到“输入输出”可查看）${suffix}`
  }, [])

  useEffect(() => {
    let cancelled = false
    isBuiltinAssistantLogPipeActive(slot).then((v) => {
      if (!cancelled) setLogPipeActive(v)
    })
    return () => {
      cancelled = true
    }
  }, [slot])

  const refreshLogBuffer = useCallback(async () => {
    const api = (window as unknown as {
      builtinAssistant?: {
        getLogBuffer: (s?: BuiltinAssistantSlot) => Promise<string>
      }
    }).builtinAssistant
    if (!api?.getLogBuffer) return
    setRefreshing(true)
    setRefreshError('')
    try {
      const buf = await api.getLogBuffer(slot)
      const next = (buf || '').split(/\r?\n/).filter(Boolean)
      setLines(next.length > MAX_LINES ? next.slice(-MAX_LINES) : next)
    } catch (e) {
      setRefreshError(e instanceof Error ? e.message : '刷新失败')
    } finally {
      setRefreshing(false)
    }
  }, [slot])

  useEffect(() => {
    setLines([])
    void refreshLogBuffer()
  }, [slot, refreshLogBuffer])

  useEffect(() => {
    if (containerRef.current) {
      containerRef.current.scrollTop = containerRef.current.scrollHeight
    }
  }, [lines, filterMode])

  const displayedLines = useMemo(() => {
    if (filterMode === 'all') {
      return lines.map(toNonIoDisplayLine)
    }
    if (filterMode === 'io') {
      return lines
        .map(toIoDisplayLine)
        .filter((line): line is string => !!line)
    }
    return lines
      .filter((line) => errorLinePattern.test(line))
      .map(toNonIoDisplayLine)
  }, [lines, filterMode, toIoDisplayLine, toNonIoDisplayLine])

  const searchedLines = useMemo(() => {
    const keyword = searchKeyword.trim().toLowerCase()
    if (!keyword) return displayedLines
    return displayedLines.filter((line) => line.toLowerCase().includes(keyword))
  }, [displayedLines, searchKeyword])

  return createPortal(
    <div className="builtin-log-overlay">
      <div className={`builtin-log-modal ${isFullscreen ? 'builtin-log-modal-fullscreen' : ''}`}>
        <div className="builtin-log-header">
          <span className="builtin-log-title">{SLOT_TITLES[slot]}</span>
          <div className="builtin-log-header-actions">
            <button
              type="button"
              className="builtin-log-search-btn"
              onClick={() => {
                setSearchOpen((v) => !v)
                if (searchOpen) setSearchKeyword('')
              }}
            >
              搜索
            </button>
            <button
              type="button"
              className="builtin-log-fullscreen-btn"
              onClick={() => setIsFullscreen((v) => !v)}
            >
              {isFullscreen ? '退出全屏' : '全屏'}
            </button>
            <div className="builtin-log-filters" role="group" aria-label="日志过滤">
              <button
                type="button"
                className={`builtin-log-filter-btn ${filterMode === 'io' ? 'active' : ''}`}
                onClick={() => setFilterMode((m) => (m === 'io' ? 'all' : 'io'))}
              >
                输入输出
              </button>
              <button
                type="button"
                className={`builtin-log-filter-btn ${filterMode === 'error' ? 'active' : ''}`}
                onClick={() => setFilterMode((m) => (m === 'error' ? 'all' : 'error'))}
              >
                Error
              </button>
            </div>
            <button type="button" className="builtin-log-refresh" onClick={() => void refreshLogBuffer()} disabled={refreshing}>
              {refreshing ? '刷新中...' : '刷新'}
            </button>
            <button type="button" className="builtin-log-close" onClick={onClose} aria-label="关闭">
              ×
            </button>
          </div>
        </div>
        {searchOpen && (
          <div className="builtin-log-search-row">
            <input
              type="text"
              className="builtin-log-search-input"
              placeholder="输入关键字搜索日志"
              value={searchKeyword}
              onChange={(e) => setSearchKeyword(e.target.value)}
            />
            <span className="builtin-log-search-count">匹配 {searchedLines.length} 行</span>
          </div>
        )}
        <div ref={containerRef} className="builtin-log-content">
          {logPipeActive === false && (
            <p className="builtin-log-banner">
              当前该内置服务不是由本应用子进程提供（例如端口已被占用），下方仅为已缓冲的提示或历史输出。
            </p>
          )}
          {refreshError ? <p className="builtin-log-banner">日志刷新失败：{refreshError}</p> : null}
          {searchedLines.length === 0 ? (
            <p className="builtin-log-empty">等待日志输出...</p>
          ) : (
            <pre className="builtin-log-pre">{searchedLines.join('\n')}</pre>
          )}
        </div>
      </div>
    </div>,
    document.body
  )
}
