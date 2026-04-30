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

import { useState } from 'react'
import axios from 'axios'
import { addFriend, getFriends, getProfile, type UserProfile } from '../services/api'
import { getImei } from '../services/storage'
import './AddFriendModal.css'

interface AddFriendModalProps {
  onClose: () => void
  onAdded?: () => void
}

export function AddFriendModal({ onClose, onAdded }: AddFriendModalProps) {
  const [targetImei, setTargetImei] = useState('')
  const [error, setError] = useState('')
  const [searching, setSearching] = useState(false)
  const [adding, setAdding] = useState(false)
  const [searchedProfile, setSearchedProfile] = useState<UserProfile | null>(null)
  const [searchedImei, setSearchedImei] = useState('')
  const [alreadyAdded, setAlreadyAdded] = useState(false)

  const handleSearch = async () => {
    const trimmed = targetImei.trim()
    if (!trimmed) {
      setError('请输入对方 IMEI')
      return
    }
    const imei = getImei()
    if (!imei) {
      setError('请先绑定设备')
      return
    }
    if (trimmed === imei) {
      setError('不能添加自己为好友')
      return
    }
    setError('')
    setSearching(true)
    try {
      const [profile, friends] = await Promise.all([
        getProfile(trimmed, { timeoutMs: 8000 }),
        getFriends(imei, { timeoutMs: 8000 }),
      ])
      if (!profile) {
        setError('未找到该 IMEI 对应的用户')
        setSearchedProfile(null)
        setSearchedImei('')
        setAlreadyAdded(false)
        return
      }
      setSearchedProfile(profile)
      setSearchedImei(trimmed)
      setAlreadyAdded(friends.some((f) => f.imei === trimmed && f.status === 'accepted'))
    } catch (e) {
      if (axios.isAxiosError(e)) {
        const data = e.response?.data as { detail?: unknown; message?: string } | undefined
        if (typeof data?.message === 'string' && data.message.trim()) {
          setError(data.message.trim())
        } else if (typeof data?.detail === 'string' && data.detail.trim()) {
          setError(data.detail.trim())
        } else if (Array.isArray(data?.detail) && data.detail.length > 0) {
          const first = data.detail[0] as { msg?: string }
          setError(first?.msg || `请求参数错误（${e.response?.status ?? ''}）`)
        } else {
          setError(`请求失败（${e.response?.status ?? ''}）`)
        }
      } else {
        setError(e instanceof Error ? e.message : '搜索失败')
      }
    } finally {
      setSearching(false)
    }
  }

  const handleAdd = async () => {
    if (!searchedImei || alreadyAdded) return
    const imei = getImei()
    if (!imei) {
      setError('请先绑定设备')
      return
    }
    setError('')
    setAdding(true)
    try {
      const res = await addFriend(imei, searchedImei)
      if (res.success) {
        onAdded?.()
        onClose()
      } else {
        setError(res.message ?? '添加失败')
      }
    } catch (e) {
      if (axios.isAxiosError(e)) {
        const data = e.response?.data as { detail?: unknown; message?: string } | undefined
        if (typeof data?.message === 'string' && data.message.trim()) {
          setError(data.message.trim())
        } else if (typeof data?.detail === 'string' && data.detail.trim()) {
          setError(data.detail.trim())
        } else if (Array.isArray(data?.detail) && data.detail.length > 0) {
          const first = data.detail[0] as { msg?: string }
          setError(first?.msg || `请求参数错误（${e.response?.status ?? ''}）`)
        } else {
          setError(`请求失败（${e.response?.status ?? ''}）`)
        }
      } else {
        setError(e instanceof Error ? e.message : '添加失败')
      }
    } finally {
      setAdding(false)
    }
  }

  const avatarSrc = (() => {
    const avatar = searchedProfile?.avatar
    if (!avatar) return ''
    return avatar.startsWith('data:') ? avatar : `data:image/png;base64,${avatar}`
  })()

  const displayName = (searchedProfile?.name || '').trim() || searchedImei
  const showProfile = !!searchedProfile

  return (
    <div className="add-friend-overlay" onClick={onClose}>
      <div className="add-friend-modal" onClick={(e) => e.stopPropagation()}>
        <div className="add-friend-header">
          <span className="add-friend-title">添加好友</span>
          <button className="add-friend-close" onClick={onClose} aria-label="关闭">×</button>
        </div>
        <p className="add-friend-hint">{showProfile ? '对方资料' : '输入对方设备的 IMEI 进行搜索'}</p>
        {!showProfile && (
          <input
            className="add-friend-input"
            placeholder="请输入 IMEI"
            value={targetImei}
            onChange={(e) => {
              setTargetImei(e.target.value)
              setError('')
            }}
          />
        )}
        {showProfile && (
          <div className="add-friend-profile-card">
            <div className="add-friend-profile-avatar">
              {avatarSrc ? <img src={avatarSrc} alt="" /> : <span>{displayName.slice(0, 1)}</span>}
            </div>
            <div className="add-friend-profile-meta">
              <div className="add-friend-profile-name">{displayName}</div>
              <div className="add-friend-profile-imei">IMEI: {searchedImei}</div>
            </div>
          </div>
        )}
        {error && <p className="add-friend-error">{error}</p>}
        <div className="add-friend-actions">
          <button className="add-friend-cancel" onClick={onClose}>取消</button>
          {!showProfile ? (
            <button
              className="add-friend-add"
              onClick={handleSearch}
              disabled={searching}
            >
              {searching ? '搜索中...' : '搜索'}
            </button>
          ) : (
            <button
              className="add-friend-add"
              onClick={handleAdd}
              disabled={adding || alreadyAdded}
            >
              {alreadyAdded ? '已添加' : (adding ? '添加中...' : '添加')}
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
