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
import { getImei } from '../services/storage'
import { getProfile, type UserProfile } from '../services/api'
import { ProfileModal } from './ProfileModal'
import './LeftNav.css'

export type NavTab = 'messages' | 'contacts' | 'skills' | 'assistantPlaza' | 'scheduledTasks' | 'quickNotes' | 'settings'

interface LeftNavProps {
  activeTab: NavTab
  onTabChange: (tab: NavTab) => void
  onLogout?: () => void
}

export function LeftNav({ activeTab, onTabChange, onLogout }: LeftNavProps) {
  const [showProfile, setShowProfile] = useState(false)
  const [userAvatar, setUserAvatar] = useState<string | undefined>()
  const [userName, setUserName] = useState('')

  const imei = getImei()
  const avatarLetter = userName ? userName.slice(0, 1) : (imei ? imei.slice(0, 1).toUpperCase() : '?')

  useEffect(() => {
    if (!imei) return
    getProfile(imei).then((p) => {
      setUserAvatar(p?.avatar)
      setUserName(p?.name ?? '')
    })
  }, [imei])

  const handleProfileSaved = (profile: UserProfile) => {
    setUserAvatar(profile.avatar)
    setUserName(profile.name ?? '')
  }

  return (
    <>
      <nav className="left-nav">
        <div className="nav-avatar" onClick={() => setShowProfile(true)} title="个人信息">
          {userAvatar ? (
            <img src={userAvatar.startsWith('data:') ? userAvatar : `data:image/png;base64,${userAvatar}`} alt="" />
          ) : (
            <span>{avatarLetter}</span>
          )}
        </div>
        <div className="nav-icons">
          <button
            className={`nav-icon ${activeTab === 'messages' ? 'active' : ''}`}
            onClick={() => onTabChange('messages')}
            title="消息"
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width="24" height="24">
              <path d="M20 2H4c-1.1 0-2 .9-2 2v18l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm0 14H6l-2 2V4h16v12z" />
            </svg>
          </button>
          <button
            className={`nav-icon ${activeTab === 'contacts' ? 'active' : ''}`}
            onClick={() => onTabChange('contacts')}
            title="通讯录"
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width="24" height="24">
              <path d="M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z" />
            </svg>
          </button>
          <button
            className={`nav-icon ${activeTab === 'skills' ? 'active' : ''}`}
            onClick={() => onTabChange('skills')}
            title="技能"
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width="24" height="24">
              <path d="M12,2 L14.76,8.76 L21.76,9.24 L16.88,13.24 L18.47,20.18 L12,16.76 L5.53,20.18 L7.12,13.24 L2.24,9.24 L9.24,8.76 Z" />
            </svg>
          </button>
          <button
            className={`nav-icon ${activeTab === 'assistantPlaza' ? 'active' : ''}`}
            onClick={() => onTabChange('assistantPlaza')}
            title="助手广场"
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width="24" height="24">
              <path d="M21.9 8.89l-1.05-4.37c-.26-1.09-1.25-1.89-2.39-1.89H5.54c-1.14 0-2.13.8-2.39 1.89l-1.05 4.37c-.14.59.22 1.17.82 1.17 1 0 1.5-.68 2.25-.68.59 0 1.24.57 1.82 1.31.59.73 1.32 1.23 2.18 1.23.6 0 1.07-.58.93-1.17zM20.25 10h-1.5v9H20.25v-9zM12.75 10h-1.5v9h1.5v-9zM5.25 10h-1.5v9h1.5v-9zM3.75 6h16.5l1.05 4H2.7l1.05-4zM2 20h20v2H2v-2z" />
            </svg>
          </button>
          <button
            className={`nav-icon ${activeTab === 'scheduledTasks' ? 'active' : ''}`}
            onClick={() => onTabChange('scheduledTasks')}
            title="定时任务"
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width="24" height="24">
              <path d="M22 5.72l-4.6-3.86-1.29 1.53 4.6 3.86L22 5.72zM7.88 4.02L6.6 2.54 2 5.72l1.29 1.53 4.59-3.85zM12.5 8H11v6l4.75 2.85.75-1.23-4-2.37V8zM12 4c-4.97 0-9 4.03-9 9s4.02 9 9 9c4.97 0 9-4.03 9-9s-4.03-9-9-9zm0 16c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z" />
            </svg>
          </button>
          <button
            className={`nav-icon ${activeTab === 'quickNotes' ? 'active' : ''}`}
            onClick={() => onTabChange('quickNotes')}
            title="随手记"
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width="24" height="24">
              <path d="M6 2h9l5 5v13a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2zm8 1.5V8h4.5L14 3.5zM8 10.5h8v1H8v-1zm0 3h8v1H8v-1zm0 3h6v1H8v-1z" />
            </svg>
          </button>
        </div>
        <div className="nav-icons-bottom">
          <button
            className={`nav-icon ${activeTab === 'settings' ? 'active' : ''}`}
            onClick={() => onTabChange('settings')}
            title="设置"
          >
            <svg viewBox="0 0 24 24" fill="currentColor" width="24" height="24">
              <path d="M19.14 12.94c.04-.31.06-.63.06-.94 0-.31-.02-.63-.06-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.04.31-.06.63-.06.94s.02.63.06.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z" />
            </svg>
          </button>
        </div>
      </nav>
      {showProfile && (
        <ProfileModal
          avatar={userAvatar}
          name={userName}
          onProfileSaved={handleProfileSaved}
          onClose={() => setShowProfile(false)}
          onLogout={() => {
            setShowProfile(false)
            onLogout?.()
          }}
        />
      )}
    </>
  )
}
