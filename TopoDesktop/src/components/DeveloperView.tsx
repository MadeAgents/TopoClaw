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

import { useState, useRef, useCallback, useEffect } from 'react'
import {
  captureScreenshot,
  click,
  doubleClick,
  rightClick,
  clickAndType,
  typeText,
  scroll,
  getCursorPosition,
} from '../services/computerUseApi'
import './DeveloperView.css'

type ClickMode = 'left' | 'double' | 'right' | 'type' | 'command'

const RECORD_STORAGE_KEY = 'pc-click-recorded-actions'
const TRAJECTORY_STORAGE_KEY = 'trajectory-recorded-actions'

interface RecordedAction {
  action: string
  x?: number
  y?: number
  text?: string
  delta?: number
}

function actionToCommand(a: RecordedAction): string {
  if (a.action === 'click' && a.x != null && a.y != null) return `click[${a.x},${a.y}]`
  if (a.action === 'double_click' && a.x != null && a.y != null) return `double_click[${a.x},${a.y}]`
  if (a.action === 'right_click' && a.x != null && a.y != null) return `right_click[${a.x},${a.y}]`
  if (a.action === 'clickAndType' && a.x != null && a.y != null && a.text != null)
    return `type[${a.x},${a.y},${a.text}]`
  if (a.action === 'type' && a.text != null) return `type[${a.text}]`
  if (a.action === 'scroll' && a.delta != null) return `scroll[${a.delta}]`
  return ''
}

function loadRecordedActions(): RecordedAction[] {
  try {
    const s = localStorage.getItem(RECORD_STORAGE_KEY)
    if (!s) return []
    const arr = JSON.parse(s) as RecordedAction[]
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}

function saveRecordedActions(actions: RecordedAction[]) {
  try {
    localStorage.setItem(RECORD_STORAGE_KEY, JSON.stringify(actions))
  } catch {}
}

function loadTrajectoryActions(): RecordedAction[] {
  try {
    const s = localStorage.getItem(TRAJECTORY_STORAGE_KEY)
    if (!s) return []
    const arr = JSON.parse(s) as RecordedAction[]
    return Array.isArray(arr) ? arr : []
  } catch {
    return []
  }
}

function saveTrajectoryActions(actions: RecordedAction[]) {
  try {
    localStorage.setItem(TRAJECTORY_STORAGE_KEY, JSON.stringify(actions))
  } catch {}
}

/** 解析单条动作命令，返回 { action, params } 或 null */
function parseCommand(line: string): { action: string; params: unknown[] } | null {
  const trimmed = line.trim()
  if (!trimmed) return null
  const match = trimmed.match(/^(\w+)\s*\[(.*)\]$/i)
  if (!match) return null
  const [, action, content] = match
  const actionLower = action.toLowerCase()
  if (actionLower === 'click') {
    const m = content.match(/^\s*(\d+)\s*,\s*(\d+)\s*$/)
    if (!m) return null
    return { action: 'click', params: [parseInt(m[1], 10), parseInt(m[2], 10)] }
  }
  if (actionLower === 'double_click' || actionLower === 'doubleclick') {
    const m = content.match(/^\s*(\d+)\s*,\s*(\d+)\s*$/)
    if (!m) return null
    return { action: 'doubleClick', params: [parseInt(m[1], 10), parseInt(m[2], 10)] }
  }
  if (actionLower === 'right_click' || actionLower === 'rightclick') {
    const m = content.match(/^\s*(\d+)\s*,\s*(\d+)\s*$/)
    if (!m) return null
    return { action: 'rightClick', params: [parseInt(m[1], 10), parseInt(m[2], 10)] }
  }
  if (actionLower === 'type') {
    const parts = content.split(',')
    if (parts.length >= 3) {
      const x = parseInt(parts[0].trim(), 10)
      const y = parseInt(parts[1].trim(), 10)
      const text = parts.slice(2).join(',').trim()
      if (!isNaN(x) && !isNaN(y)) return { action: 'clickAndType', params: [x, y, text] }
    }
    return { action: 'type', params: [content.trim()] }
  }
  if (actionLower === 'scroll') {
    const delta = parseInt(content.trim(), 10)
    if (!isNaN(delta)) return { action: 'scroll', params: [delta] }
    return null
  }
  return null
}

interface DeveloperViewProps {
  /** 顶部导航栏搜索词（开发者页面暂无过滤功能，保留接口一致） */
  search?: string
  /** 嵌入到设置页时隐藏标题 */
  hideHeader?: boolean
}

export function DeveloperView({ hideHeader, ..._props }: DeveloperViewProps = {}) {
  const [showPcClickTest, setShowPcClickTest] = useState(false)
  const [showTrajectoryCollect, setShowTrajectoryCollect] = useState(false)
  const [screenshot, setScreenshot] = useState<string | null>(null)
  const [screenW, setScreenW] = useState(0)
  const [screenH, setScreenH] = useState(0)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [clickMode, setClickMode] = useState<ClickMode>('left')
  const [typeInput, setTypeInput] = useState('')
  const [commandInput, setCommandInput] = useState('')
  const [commandRunning, setCommandRunning] = useState(false)
  const [showCoordPicker, setShowCoordPicker] = useState(false)
  const [cursorPos, setCursorPos] = useState<{ x: number; y: number } | null>(null)
  const [isRecording, setIsRecording] = useState(false)
  const [recordedActions, setRecordedActions] = useState<RecordedAction[]>(() => loadRecordedActions())
  const [isTrajectoryRecording, setIsTrajectoryRecording] = useState(false)
  const [trajectoryMode, setTrajectoryMode] = useState<'a' | 'b'>('a')
  const [trajectoryActions, setTrajectoryActions] = useState<RecordedAction[]>(() => loadTrajectoryActions())
  const screenshotRef = useRef<HTMLDivElement>(null)

  const addRecordedAction = useCallback((action: RecordedAction) => {
    setRecordedActions((prev) => {
      const next = [...prev, action]
      saveRecordedActions(next)
      return next
    })
  }, [])

  const addTrajectoryAction = useCallback((action: RecordedAction) => {
    setTrajectoryActions((prev) => {
      const next = [...prev, action]
      saveTrajectoryActions(next)
      return next
    })
  }, [])

  /** 轨迹采集：透明层 + 方案A(快捷键) 或 方案B(点击即记录) */
  useEffect(() => {
    if (!showTrajectoryCollect || !window.trajectory) return
    if (isTrajectoryRecording) {
      window.trajectory.start(trajectoryMode)
      const unsub = window.trajectory.onAction(({ action, x, y }) => {
        if (action === 'click') addTrajectoryAction({ action: 'click', x, y })
        else if (action === 'double_click') addTrajectoryAction({ action: 'double_click', x, y })
        else if (action === 'right_click') addTrajectoryAction({ action: 'right_click', x, y })
      })
      return () => {
        unsub()
        window.trajectory?.stop()
      }
    } else {
      window.trajectory.stop()
    }
  }, [showTrajectoryCollect, isTrajectoryRecording, trajectoryMode, addTrajectoryAction])

  /** 录制中切换方案时同步 overlay 模式 */
  useEffect(() => {
    if (!showTrajectoryCollect || !isTrajectoryRecording || !window.trajectory) return
    window.trajectory.setMode(trajectoryMode)
  }, [showTrajectoryCollect, isTrajectoryRecording, trajectoryMode])

  /** 全局录制：开启时注册快捷键，任意窗口下按快捷键可记录当前光标位置 */
  useEffect(() => {
    if (!showPcClickTest) return
    if (isRecording && window.pcRecord) {
      window.pcRecord.start()
      const unsub = window.pcRecord.onAction(({ action, x, y }) => {
        if (action === 'click') addRecordedAction({ action: 'click', x, y })
        else if (action === 'double_click') addRecordedAction({ action: 'double_click', x, y })
        else if (action === 'right_click') addRecordedAction({ action: 'right_click', x, y })
      })
      return () => {
        unsub()
        window.pcRecord?.stop()
      }
    } else if (!isRecording && window.pcRecord) {
      window.pcRecord.stop()
    }
  }, [showPcClickTest, isRecording, addRecordedAction])

  useEffect(() => {
    if (!showCoordPicker || !showPcClickTest) return
    const tick = async () => {
      const res = await getCursorPosition()
      if (res.ok && res.x != null && res.y != null) {
        setCursorPos({ x: res.x, y: res.y })
      }
    }
    tick()
    const id = setInterval(tick, 100)
    return () => clearInterval(id)
  }, [showCoordPicker, showPcClickTest])

  const handlePcClickTest = () => {
    setShowPcClickTest(true)
    setScreenshot(null)
    setError(null)
    setShowCoordPicker(false)
  }

  const handleRefreshScreenshot = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const res = await captureScreenshot()
      if (!res.ok || !res.base64) {
        setError(res.error || '截图失败')
        return
      }
      setScreenshot(res.base64)
      setScreenW(res.width ?? 0)
      setScreenH(res.height ?? 0)
    } catch (e) {
      setError(e instanceof Error ? e.message : '截图异常')
    } finally {
      setLoading(false)
    }
  }, [])

  const mapToScreenCoords = useCallback(
    (offsetX: number, offsetY: number): { x: number; y: number } | null => {
      const el = screenshotRef.current
      if (!el || !screenW || !screenH) return null
      const rect = el.getBoundingClientRect()
      const divW = rect.width
      const divH = rect.height
      const scale = Math.min(divW / screenW, divH / screenH)
      const renderedW = screenW * scale
      const renderedH = screenH * scale
      const leftPad = (divW - renderedW) / 2
      const topPad = (divH - renderedH) / 2
      const localX = offsetX - leftPad
      const localY = offsetY - topPad
      if (localX < 0 || localX > renderedW || localY < 0 || localY > renderedH) {
        return null
      }
      const x = Math.round((localX / renderedW) * screenW)
      const y = Math.round((localY / renderedH) * screenH)
      return { x, y }
    },
    [screenW, screenH]
  )

  const handleScreenshotClick = useCallback(
    async (e: React.MouseEvent<HTMLDivElement>) => {
      if (!screenshot) return
      const offsetX = e.nativeEvent.offsetX
      const offsetY = e.nativeEvent.offsetY
      const coords = mapToScreenCoords(offsetX, offsetY)
      if (!coords) return
      setError(null)
      let res
      if (clickMode === 'left') {
        res = await click(coords.x, coords.y)
        if (res.ok && isRecording) addRecordedAction({ action: 'click', x: coords.x, y: coords.y })
      } else if (clickMode === 'double') {
        res = await doubleClick(coords.x, coords.y)
        if (res.ok && isRecording) addRecordedAction({ action: 'double_click', x: coords.x, y: coords.y })
      } else if (clickMode === 'right') {
        res = await rightClick(coords.x, coords.y)
        if (res.ok && isRecording) addRecordedAction({ action: 'right_click', x: coords.x, y: coords.y })
      } else if (clickMode === 'type') {
        if (!typeInput.trim()) {
          setError('请先在输入框输入要键入的文本')
          return
        }
        res = await clickAndType(coords.x, coords.y, typeInput.trim())
        if (res.ok && isRecording)
          addRecordedAction({ action: 'clickAndType', x: coords.x, y: coords.y, text: typeInput.trim() })
      } else {
        return
      }
      if (!res.ok) setError(res.error || '操作失败')
    },
    [screenshot, clickMode, typeInput, mapToScreenCoords, isRecording, addRecordedAction]
  )

  const handleScroll = useCallback(
    async (delta: number) => {
      setError(null)
      const res = await scroll(delta)
      if (res.ok && isRecording) addRecordedAction({ action: 'scroll', delta })
      if (!res.ok) setError(res.error || '滚动失败')
    },
    [isRecording, addRecordedAction]
  )

  const handleExecuteCommand = useCallback(async () => {
    const lines = commandInput.split(/\n/).map((l) => l.trim()).filter(Boolean)
    const commands: { action: string; params: unknown[] }[] = []
    for (const line of lines) {
      const parsed = parseCommand(line)
      if (!parsed) {
        setError(`无法解析: ${line}`)
        return
      }
      commands.push(parsed)
    }
    if (commands.length === 0) {
      setError('请输入有效命令')
      return
    }
    setError(null)
    setCommandRunning(true)
    try {
      for (const cmd of commands) {
        let res
        if (cmd.action === 'click') {
          res = await click(cmd.params[0] as number, cmd.params[1] as number)
        } else if (cmd.action === 'doubleClick') {
          res = await doubleClick(cmd.params[0] as number, cmd.params[1] as number)
        } else if (cmd.action === 'rightClick') {
          res = await rightClick(cmd.params[0] as number, cmd.params[1] as number)
        } else if (cmd.action === 'type') {
          res = await typeText(cmd.params[0] as string)
        } else if (cmd.action === 'clickAndType') {
          res = await clickAndType(
            cmd.params[0] as number,
            cmd.params[1] as number,
            cmd.params[2] as string
          )
        } else if (cmd.action === 'scroll') {
          res = await scroll(cmd.params[0] as number)
        } else {
          continue
        }
        if (!res.ok) {
          setError(res.error || '执行失败')
          return
        }
        if (isRecording && res.ok) {
          if (cmd.action === 'click') addRecordedAction({ action: 'click', x: cmd.params[0] as number, y: cmd.params[1] as number })
          else if (cmd.action === 'doubleClick') addRecordedAction({ action: 'double_click', x: cmd.params[0] as number, y: cmd.params[1] as number })
          else if (cmd.action === 'rightClick') addRecordedAction({ action: 'right_click', x: cmd.params[0] as number, y: cmd.params[1] as number })
          else if (cmd.action === 'type') addRecordedAction({ action: 'type', text: cmd.params[0] as string })
          else if (cmd.action === 'clickAndType') addRecordedAction({ action: 'clickAndType', x: cmd.params[0] as number, y: cmd.params[1] as number, text: cmd.params[2] as string })
          else if (cmd.action === 'scroll') addRecordedAction({ action: 'scroll', delta: cmd.params[0] as number })
        }
      }
    } finally {
      setCommandRunning(false)
    }
  }, [commandInput, isRecording, addRecordedAction])

  return (
    <div className="developer-view">
      {!hideHeader && (
        <div className="developer-header">
          <h2>开发者</h2>
        </div>
      )}
      <div className="developer-content">
        <div className="developer-entry" onClick={handlePcClickTest}>
          <span className="developer-entry-label">PC端模拟点击测试</span>
          <svg className="developer-entry-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M9 18l6-6-6-6" />
          </svg>
        </div>
        <div className="developer-entry" onClick={() => setShowTrajectoryCollect(true)}>
          <span className="developer-entry-label">轨迹采集</span>
          <svg className="developer-entry-arrow" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M9 18l6-6-6-6" />
          </svg>
        </div>
      </div>
      {showTrajectoryCollect && (
        <div className="developer-pc-click-float developer-trajectory-float">
          <div className="developer-modal developer-modal-pc-click developer-modal-pc-click-float">
            <div className="developer-modal-header">
              <h3>轨迹采集</h3>
              <button
                  className="developer-modal-close"
                  onClick={() => {
                    if (isTrajectoryRecording) window.trajectory?.stop()
                    setShowTrajectoryCollect(false)
                  }}
                >
                  ×
                </button>
            </div>
            <div className="developer-modal-body developer-modal-body-pc-click">
              <div className="pc-click-toolbar">
                <span className="pc-click-mode-label">方案：</span>
                <div className="pc-click-mode-group">
                  <label className="pc-click-radio">
                    <input
                      type="radio"
                      name="trajectoryMode"
                      checked={trajectoryMode === 'a'}
                      onChange={() => setTrajectoryMode('a')}
                    />
                    方案A 快捷键
                  </label>
                  <label className="pc-click-radio">
                    <input
                      type="radio"
                      name="trajectoryMode"
                      checked={trajectoryMode === 'b'}
                      onChange={() => setTrajectoryMode('b')}
                    />
                    方案B 点击即记
                  </label>
                </div>
                <button
                  className={`pc-click-btn ${isTrajectoryRecording ? 'pc-click-btn-active' : 'pc-click-btn-primary'}`}
                  onClick={() => setIsTrajectoryRecording(!isTrajectoryRecording)}
                  title={
                    trajectoryMode === 'a'
                      ? '开启后全屏透明图层显示轨迹，按 Ctrl+Shift+R(左键)/D(双击)/X(右键) 记录当前光标位置'
                      : '开启后直接在屏幕上点击即可记录，点击会同时下发到底层应用'
                  }
                >
                  {isTrajectoryRecording ? '录制中' : '开始录制'}
                </button>
                {!isTrajectoryRecording && (
                  <button
                    className="pc-click-btn"
                    onClick={() => {
                      setTrajectoryActions([])
                      saveTrajectoryActions([])
                      window.trajectory?.clear()
                    }}
                  >
                    清空
                  </button>
                )}
              </div>
              <div className="pc-click-hint pc-click-hint-trajectory">
                {trajectoryMode === 'a'
                  ? '按 Ctrl+Shift+R 记录左键点击 | Ctrl+Shift+D 记录双击 | Ctrl+Shift+X 记录右键'
                  : '直接在屏幕上左键/双击/右键，自动记录并下发到底层应用'}
              </div>
              {(trajectoryActions.length > 0 || isTrajectoryRecording) && (
                <div className="pc-click-recorded">
                  <div className="pc-click-recorded-header">
                    <span>
                      {trajectoryActions.length > 0 ? `已记录 ${trajectoryActions.length} 条` : '录制中，按快捷键记录'}
                    </span>
                    {trajectoryActions.length > 0 && (
                      <div className="pc-click-recorded-actions">
                        <button
                          className="pc-click-btn pc-click-btn-sm"
                          onClick={() => {
                            const text = trajectoryActions.map(actionToCommand).filter(Boolean).join('\n')
                            void navigator.clipboard.writeText(text)
                          }}
                        >
                          复制为命令
                        </button>
                      </div>
                    )}
                  </div>
                  <div className="pc-click-recorded-list">
                    {trajectoryActions.length > 0 ? (
                      trajectoryActions.map((a, i) => (
                        <code key={`t-${i}`} className="pc-click-recorded-item">
                          {actionToCommand(a) || `${a.action}`}
                        </code>
                      ))
                    ) : (
                      <div className="pc-click-recorded-placeholder">
                        {trajectoryMode === 'a'
                          ? '在任意窗口下将光标移到目标位置，按快捷键记录'
                          : '直接在屏幕上点击即可记录'}
                      </div>
                    )}
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>
      )}
      {showPcClickTest && (
        <div className="developer-pc-click-float">
          <div className="developer-modal developer-modal-pc-click developer-modal-pc-click-float">
            <div className="developer-modal-header">
              <h3>PC端模拟点击测试</h3>
              <button className="developer-modal-close" onClick={() => setShowPcClickTest(false)}>×</button>
            </div>
            <div className="developer-modal-body developer-modal-body-pc-click">
              <div className="pc-click-toolbar">
                <button
                  className="pc-click-btn pc-click-btn-primary"
                  onClick={handleRefreshScreenshot}
                  disabled={loading}
                >
                  {loading ? '截图中...' : '刷新截图'}
                </button>
                <button
                  className={`pc-click-btn ${showCoordPicker ? 'pc-click-btn-active' : ''}`}
                  onClick={() => setShowCoordPicker(!showCoordPicker)}
                  title="开启后移动鼠标可查看实时屏幕坐标"
                >
                  坐标取点
                </button>
                {showCoordPicker && cursorPos && (
                  <span className="pc-click-coord-display" title="点击复制为 click[x,y]">
                    <code
                      onClick={() => {
                        const s = `click[${cursorPos.x},${cursorPos.y}]`
                        void navigator.clipboard.writeText(s)
                      }}
                    >
                      ({cursorPos.x}, {cursorPos.y})
                    </code>
                  </span>
                )}
                <span className="pc-click-mode-label">点击模式：</span>
                <div className="pc-click-mode-group">
                  <label className="pc-click-radio">
                    <input
                      type="radio"
                      name="clickMode"
                      checked={clickMode === 'left'}
                      onChange={() => setClickMode('left')}
                    />
                    左键
                  </label>
                  <label className="pc-click-radio">
                    <input
                      type="radio"
                      name="clickMode"
                      checked={clickMode === 'double'}
                      onChange={() => setClickMode('double')}
                    />
                    双击
                  </label>
                  <label className="pc-click-radio">
                    <input
                      type="radio"
                      name="clickMode"
                      checked={clickMode === 'right'}
                      onChange={() => setClickMode('right')}
                    />
                    右键
                  </label>
                  <label className="pc-click-radio">
                    <input
                      type="radio"
                      name="clickMode"
                      checked={clickMode === 'type'}
                      onChange={() => setClickMode('type')}
                    />
                    输入
                  </label>
                  <label className="pc-click-radio">
                    <input
                      type="radio"
                      name="clickMode"
                      checked={clickMode === 'command'}
                      onChange={() => setClickMode('command')}
                    />
                    命令
                  </label>
                </div>
                {clickMode !== 'command' && (
                  <div className="pc-click-type-row">
                    <input
                      type="text"
                      className="pc-click-type-input"
                      placeholder="输入模式下：在此输入文本，再点击截图上目标输入框"
                      value={typeInput}
                      onChange={(e) => setTypeInput(e.target.value)}
                    />
                  </div>
                )}
                {clickMode === 'command' && (
                  <div className="pc-click-command-row">
                    <textarea
                      className="pc-click-command-input"
                      placeholder={`每条命令一行，例如：
click[100,200]
double_click[300,400]
right_click[500,600]
type[hello]
type[100,200,点击后输入]
scroll[3]
scroll[-5]`}
                      value={commandInput}
                      onChange={(e) => setCommandInput(e.target.value)}
                      rows={5}
                    />
                    <button
                      className="pc-click-btn pc-click-btn-primary"
                      onClick={handleExecuteCommand}
                      disabled={commandRunning || !commandInput.trim()}
                    >
                      {commandRunning ? '执行中...' : '执行'}
                    </button>
                  </div>
                )}
                <div className="pc-click-scroll-row">
                  <span className="pc-click-scroll-label">滚动：</span>
                  <button className="pc-click-btn pc-click-btn-sm" onClick={() => handleScroll(3)}>▲</button>
                  <button className="pc-click-btn pc-click-btn-sm" onClick={() => handleScroll(-3)}>▼</button>
                </div>
                <button
                  className={`pc-click-btn ${isRecording ? 'pc-click-btn-active' : ''}`}
                  onClick={() => setIsRecording(!isRecording)}
                  title="开启后记录所有操作：截图点击、命令执行、以及任意窗口下按 Ctrl+Shift+R(左键)/D(双击)/X(右键) 记录当前光标位置"
                >
                  {isRecording ? '录制中' : '录制'}
                </button>
              </div>
              {(recordedActions.length > 0 || isRecording) && (
                <div className="pc-click-recorded">
                  <div className="pc-click-recorded-header">
                    <span>
                      {recordedActions.length > 0 ? `已记录 ${recordedActions.length} 条` : '录制中，操作将在此显示'}
                    </span>
                    {recordedActions.length > 0 && (
                      <div className="pc-click-recorded-actions">
                        <button
                          className="pc-click-btn pc-click-btn-sm"
                          onClick={() => {
                            const text = recordedActions.map(actionToCommand).filter(Boolean).join('\n')
                            void navigator.clipboard.writeText(text)
                          }}
                        >
                          复制为命令
                        </button>
                        <button
                          className="pc-click-btn pc-click-btn-sm"
                          onClick={() => {
                            setCommandInput(recordedActions.map(actionToCommand).filter(Boolean).join('\n'))
                            setClickMode('command')
                          }}
                        >
                          填入命令
                        </button>
                        <button
                          className="pc-click-btn pc-click-btn-sm"
                          onClick={() => {
                            setRecordedActions([])
                            saveRecordedActions([])
                          }}
                        >
                          清空
                        </button>
                      </div>
                    )}
                  </div>
                  <div className="pc-click-recorded-list">
                    {recordedActions.length > 0 ? (
                      recordedActions.map((a, i) => (
                        <code key={`rec-${i}`} className="pc-click-recorded-item">
                          {actionToCommand(a) || `${a.action}`}
                        </code>
                      ))
                    ) : (
                      <div className="pc-click-recorded-placeholder">
                        在截图上点击、执行命令，或在任意窗口下按 Ctrl+Shift+R/D/X 记录当前光标位置
                      </div>
                    )}
                  </div>
                </div>
              )}
              <div className="pc-click-screenshot-wrap">
                {error && <div className="pc-click-error">{error}</div>}
                {screenshot ? (
                  <div
                    ref={screenshotRef}
                    className="pc-click-screenshot"
                    data-copy-image-src={`data:image/png;base64,${screenshot}`}
                    style={{
                      backgroundImage: `url(data:image/png;base64,${screenshot})`,
                      aspectRatio: screenW && screenH ? `${screenW}/${screenH}` : '16/9',
                    }}
                    onClick={handleScreenshotClick}
                  />
                ) : (
                  <div className="pc-click-screenshot-placeholder">
                    点击「刷新截图」捕获当前屏幕
                  </div>
                )}
              </div>
              <div className="pc-click-hint">
                {clickMode === 'type'
                  ? '① 在输入框输入文本 ② 点击截图上目标输入框位置，将自动聚焦并输入'
                  : clickMode === 'command'
                  ? '格式: action[param]。点击「坐标取点」可查看鼠标位置，点击坐标复制为 click[x,y]'
                  : '在截图上点击可执行对应操作，坐标会自动映射到真实屏幕'}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
