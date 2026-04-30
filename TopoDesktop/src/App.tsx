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

import { useCallback, useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { BindingPage } from './components/BindingPage'
import { ChatLayout, type StartupProgressEvent } from './components/ChatLayout'
import { SkillDueModal } from './components/SkillDueModal'
import { WindowControls } from './components/WindowControls'
import { GlobalImageContextMenu } from './components/GlobalImageContextMenu'
import { DesktopUpdateDialog } from './components/DesktopUpdateDialog'
import { StartupSplash } from './components/StartupSplash'
import { isBound, getCustomerServiceUrl, getServerUrl, getImei, logout } from './services/storage'
import { getBaselineTimestamp } from './services/messageStorage'
import { initApi } from './services/api'
import { startScheduleService } from './services/skillScheduleService'
import { probeDesktopUpdate, type DesktopUpdatePayload } from './services/desktopVersionCheck'
import { syncConversationSummariesWithCloud } from './services/conversationSummary'
import { syncDeeplinkCatalogToService } from './services/deeplinkCatalog'
import { setActiveCustomerServiceUrlForBuiltinAssistant } from './services/builtinAssistantConfig'
import type { Skill } from './services/api'
import './globals.css'
import './App.css'

function isValidDueSkill(skill: unknown): skill is Skill {
  if (!skill || typeof skill !== 'object') return false
  const candidate = skill as Partial<Skill>
  return typeof candidate.id === 'string' && candidate.id.trim().length > 0
}

function dropFirstValidDueSkill(queue: Skill[]): Skill[] {
  let removed = false
  return queue.filter((item) => {
    if (!isValidDueSkill(item)) return false
    if (!removed) {
      removed = true
      return false
    }
    return true
  })
}

function App() {
  const [bound, setBound] = useState(false)
  const [serverUrl] = useState(getServerUrl())
  const [dueSkillQueue, setDueSkillQueue] = useState<Skill[]>([])
  const [desktopUpdate, setDesktopUpdate] = useState<DesktopUpdatePayload | null>(null)
  const [showStartupSplash, setShowStartupSplash] = useState(false)
  const [startupPhase, setStartupPhase] = useState<StartupProgressEvent['phase']>('running')
  const [startupSubtitle, setStartupSubtitle] = useState('正在准备启动环境...')
  const [startupDetails, setStartupDetails] = useState<string[]>([])

  useEffect(() => {
    initApi(getServerUrl())
    void setActiveCustomerServiceUrlForBuiltinAssistant(getCustomerServiceUrl())
    void probeDesktopUpdate().then((p) => {
      if (p) setDesktopUpdate(p)
    })
  }, [])

  useEffect(() => {
    if (isBound()) {
      initApi(getServerUrl())
      getBaselineTimestamp()
      startScheduleService((skills) => {
        const validSkills = (skills || []).filter(isValidDueSkill)
        console.log('[Schedule] 回调执行，收到技能:', validSkills.map((s) => s.title || s.id))
        if (validSkills.length === 0) return
        setDueSkillQueue((q) => [...q, ...validSkills])
      })
      setBound(true)
    }
  }, [])

  useEffect(() => {
    if (bound && isBound()) {
      startScheduleService((skills) => {
        const validSkills = (skills || []).filter(isValidDueSkill)
        console.log('[Schedule] 回调执行，收到技能:', validSkills.map((s) => s.title || s.id))
        if (validSkills.length === 0) return
        setDueSkillQueue((q) => [...q, ...validSkills])
      })
    }
  }, [bound])

  useEffect(() => {
    if (!bound) return
    const imei = (getImei() || '').trim()
    if (!imei) return
    void syncConversationSummariesWithCloud()
    void syncDeeplinkCatalogToService()
    const builtinApi = (window as unknown as {
      builtinAssistant?: { setTopomobileNodeId?: (nodeId: string) => Promise<unknown> }
    }).builtinAssistant
    void builtinApi?.setTopomobileNodeId?.(imei)
  }, [bound])

  useEffect(() => {
    if (!bound) {
      setShowStartupSplash(false)
      setStartupPhase('running')
      setStartupSubtitle('正在准备启动环境...')
      setStartupDetails([])
      return
    }
    setShowStartupSplash(true)
    const fallbackTimer = window.setTimeout(() => {
      setStartupSubtitle('启动耗时较长，仍在处理中...')
      setStartupDetails((prev) => {
        const next = [...prev, `[${new Date().toLocaleTimeString()}] 启动耗时较长，请稍候`]
        return next.slice(-120)
      })
    }, 15000)
    let hideTimer: number | undefined
    if (startupPhase !== 'running') {
      hideTimer = window.setTimeout(() => {
        setShowStartupSplash(false)
      }, 900)
    }
    return () => {
      window.clearTimeout(fallbackTimer)
      if (hideTimer != null) window.clearTimeout(hideTimer)
    }
  }, [bound, startupPhase])

  const handleStartupProgress = useCallback((event: StartupProgressEvent) => {
    const timestamp = new Date().toLocaleTimeString()
    setStartupPhase(event.phase)
    setStartupSubtitle(event.message)
    setStartupDetails((prev) => {
      const next = [...prev, `[${timestamp}] ${event.message}`]
      return next.slice(-120)
    })
  }, [])

  useEffect(() => {
    if (dueSkillQueue.length > 0) {
      console.log('[Schedule] 弹窗队列更新，长度:', dueSkillQueue.length)
    }
  }, [dueSkillQueue.length])
  const nextDueSkill = dueSkillQueue.find(isValidDueSkill) ?? null

  const handleBound = () => {
    setBound(true)
  }

  const handleLogout = () => {
    logout()
    setBound(false)
  }

  if (!bound) {
    return (
      <>
        {desktopUpdate && (
          <DesktopUpdateDialog
            open
            forceUpdate={desktopUpdate.forceUpdate}
            currentVersion={desktopUpdate.currentVersion}
            latestVersion={desktopUpdate.latestVersion}
            message={desktopUpdate.updateMessage}
            updateUrl={desktopUpdate.updateUrl}
            onDismiss={() => setDesktopUpdate(null)}
          />
        )}
        <GlobalImageContextMenu />
        <div className="app app-binding">
          <header className="app-title-bar-binding">
            <div className="app-title-bar-binding-pane app-title-bar-binding-pane-left" />
            <div className="app-title-bar-binding-pane app-title-bar-binding-pane-right">
              <WindowControls />
            </div>
          </header>
          <div className="app-binding-content">
            <BindingPage
              serverUrl={serverUrl}
              onBound={handleBound}
            />
          </div>
        </div>
      </>
    )
  }

  return (
    <>
      {desktopUpdate && (
        <DesktopUpdateDialog
          open
          forceUpdate={desktopUpdate.forceUpdate}
          currentVersion={desktopUpdate.currentVersion}
          latestVersion={desktopUpdate.latestVersion}
          message={desktopUpdate.updateMessage}
          updateUrl={desktopUpdate.updateUrl}
          onDismiss={() => setDesktopUpdate(null)}
        />
      )}
      <GlobalImageContextMenu />
      <div className="app">
        <div className="chat-layout-wrapper">
          <ChatLayout onLogout={handleLogout} onStartupProgress={handleStartupProgress} />
        </div>
        {showStartupSplash && <StartupSplash subtitle={startupSubtitle} details={startupDetails} />}
        {nextDueSkill &&
          createPortal(
            <SkillDueModal
              skill={nextDueSkill}
              onClose={() => setDueSkillQueue((q) => dropFirstValidDueSkill(q))}
            />,
            document.body
          )}
      </div>
    </>
  )
}

export default App
