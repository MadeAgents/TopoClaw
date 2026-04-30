// Copyright 2025 OPPO
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import {
  getBuiltinCustomerServiceLogBuffer,
  isBuiltinCustomerServiceLogPipeActive,
} from '../services/builtinAssistantConfig'
import './CustomerServiceLogModal.css'

interface CustomerServiceLogModalProps {
  onClose: () => void
}

export function CustomerServiceLogModal({ onClose }: CustomerServiceLogModalProps) {
  const [text, setText] = useState('')
  const [refreshing, setRefreshing] = useState(false)
  const [refreshError, setRefreshError] = useState('')
  const [pipeActive, setPipeActive] = useState<boolean | null>(null)
  const containerRef = useRef<HTMLDivElement>(null)

  const refresh = async () => {
    setRefreshing(true)
    setRefreshError('')
    try {
      const [buf, active] = await Promise.all([
        getBuiltinCustomerServiceLogBuffer(),
        isBuiltinCustomerServiceLogPipeActive(),
      ])
      setText(buf || '')
      setPipeActive(active)
    } catch (e) {
      setRefreshError(e instanceof Error ? e.message : '刷新失败')
    } finally {
      setRefreshing(false)
    }
  }

  useEffect(() => {
    void refresh()
  }, [])

  useEffect(() => {
    if (!containerRef.current) return
    containerRef.current.scrollTop = containerRef.current.scrollHeight
  }, [text])

  return createPortal(
    <div className="cs-log-overlay">
      <div className="cs-log-modal">
        <div className="cs-log-header">
          <span className="cs-log-title">中转服务日志（customer_service）</span>
          <div className="cs-log-actions">
            <button type="button" className="cs-log-btn" onClick={() => void refresh()} disabled={refreshing}>
              {refreshing ? '刷新中...' : '刷新'}
            </button>
            <button type="button" className="cs-log-btn cs-log-close" onClick={onClose}>
              关闭
            </button>
          </div>
        </div>
        {pipeActive === false ? (
          <p className="cs-log-hint">
            当前不是本应用拉起的 customer_service 进程（可能是外部服务占用端口），仅展示本进程已缓冲日志。
          </p>
        ) : null}
        {refreshError ? <p className="cs-log-hint">日志刷新失败：{refreshError}</p> : null}
        <div className="cs-log-content" ref={containerRef}>
          <pre>{text || '暂无日志输出。可先点击“保存”或重新切换一次本地内置服务触发启动。'}</pre>
        </div>
      </div>
    </div>,
    document.body
  )
}

