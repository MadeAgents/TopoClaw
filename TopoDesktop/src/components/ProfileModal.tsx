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

import { useEffect, useMemo, useRef, useState } from 'react'
import { getImei } from '../services/storage'
import { getProfile, updateProfile, type UserProfile } from '../services/api'
import { toAvatarSrc } from '../utils/avatar'
import { ChatImageLightbox, type ImageLightboxPayload } from './ChatInlineImage'
import './ProfileModal.css'

interface ProfileModalProps {
  avatar?: string
  name?: string
  onClose: () => void
  onLogout?: () => void
  onProfileSaved?: (profile: UserProfile) => void
}

type EditableProfileFields = {
  name: string
  signature: string
  avatar: string
  gender: string
  address: string
  phone: string
  birthday: string
  preferences: string
}

function normalizeFormValue(v?: string): string {
  return (v ?? '').trim()
}

export function ProfileModal({ avatar, name, onClose, onLogout, onProfileSaved }: ProfileModalProps) {
  const imei = getImei()
  const avatarInputRef = useRef<HTMLInputElement | null>(null)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [isEditing, setIsEditing] = useState(false)
  const [error, setError] = useState('')
  const [saveMessage, setSaveMessage] = useState('')
  const [imageLightbox, setImageLightbox] = useState<ImageLightboxPayload | null>(null)
  const [form, setForm] = useState<EditableProfileFields>({
    name: name ?? '',
    signature: '',
    avatar: avatar ?? '',
    gender: '',
    address: '',
    phone: '',
    birthday: '',
    preferences: '',
  })

  const avatarSrc = useMemo(() => toAvatarSrc(form.avatar), [form.avatar])
  const displayName = form.name.trim()
  const avatarLetter = displayName ? displayName.slice(0, 1) : (imei ? imei.slice(0, 1).toUpperCase() : '?')

  useEffect(() => {
    let cancelled = false
    if (!imei) return
    setLoading(true)
    setError('')
    getProfile(imei)
      .then((profile) => {
        if (cancelled || !profile) return
        setForm({
          name: profile.name ?? name ?? '',
          signature: profile.signature ?? '',
          avatar: profile.avatar ?? avatar ?? '',
          gender: profile.gender ?? '',
          address: profile.address ?? '',
          phone: profile.phone ?? '',
          birthday: profile.birthday ?? '',
          preferences: profile.preferences ?? '',
        })
      })
      .catch(() => {
        if (!cancelled) setError('加载资料失败')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [imei, avatar, name])

  const handleLogout = () => {
    onClose()
    onLogout?.()
  }

  const onFieldChange = (key: keyof EditableProfileFields, value: string) => {
    setForm((prev) => ({ ...prev, [key]: value }))
    if (saveMessage) setSaveMessage('')
    if (error) setError('')
  }

  const handleAvatarFileChange: React.ChangeEventHandler<HTMLInputElement> = (e) => {
    const file = e.target.files?.[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = () => {
      const dataUrl = typeof reader.result === 'string' ? reader.result : ''
      if (dataUrl) onFieldChange('avatar', dataUrl)
    }
    reader.readAsDataURL(file)
    e.currentTarget.value = ''
  }

  const handleAvatarClick = () => {
    if (avatarSrc) {
      setImageLightbox({ dataUrl: avatarSrc, fileName: `${displayName || 'avatar'}.png` })
      return
    }
    if (isEditing && !saving) avatarInputRef.current?.click()
  }

  const handleSave = async () => {
    if (!imei) {
      setError('未检测到 IMEI，无法保存')
      return
    }
    setSaving(true)
    setError('')
    setSaveMessage('')
    const payload = {
      name: normalizeFormValue(form.name),
      signature: normalizeFormValue(form.signature),
      avatar: normalizeFormValue(form.avatar),
      gender: normalizeFormValue(form.gender),
      address: normalizeFormValue(form.address),
      phone: normalizeFormValue(form.phone),
      birthday: normalizeFormValue(form.birthday),
      preferences: normalizeFormValue(form.preferences),
    }
    const updated = await updateProfile(imei, payload)
    setSaving(false)
    if (!updated) {
      setError('保存失败，请稍后重试')
      return
    }
    setForm({
      name: updated.name ?? '',
      signature: updated.signature ?? '',
      avatar: updated.avatar ?? '',
      gender: updated.gender ?? '',
      address: updated.address ?? '',
      phone: updated.phone ?? '',
      birthday: updated.birthday ?? '',
      preferences: updated.preferences ?? '',
    })
    setSaveMessage('保存成功')
    setIsEditing(false)
    onProfileSaved?.(updated)
  }

  return (
    <>
      <div className="profile-modal-overlay" onClick={onClose}>
        <div className="profile-modal" onClick={(e) => e.stopPropagation()}>
        <div className="profile-modal-header">
          <h3>个人信息</h3>
          <button className="profile-modal-close" onClick={onClose}>×</button>
        </div>
        <div className="profile-modal-body">
          <div
            className={`profile-avatar-lg ${avatarSrc ? 'profile-avatar-previewable' : ''} ${isEditing && !saving ? 'profile-avatar-editable' : ''}`}
            onClick={handleAvatarClick}
            role={avatarSrc || (isEditing && !saving) ? 'button' : undefined}
            tabIndex={avatarSrc || (isEditing && !saving) ? 0 : undefined}
            onKeyDown={(e) => {
              if (e.key === 'Enter' || e.key === ' ') {
                e.preventDefault()
                handleAvatarClick()
              }
            }}
            title={avatarSrc ? '点击查看大图' : isEditing ? '点击上传头像' : undefined}
          >
            {avatarSrc ? (
              <img src={avatarSrc} alt="" />
            ) : (
              <span>{avatarLetter}</span>
            )}
          </div>
          {isEditing && (
            <div className="profile-avatar-tip-wrap">
              <div className="profile-avatar-tip">{avatarSrc ? '点击头像查看大图' : '点击头像上传图片'}</div>
              <button
                type="button"
                className="profile-btn profile-btn-outline profile-btn-sm"
                onClick={() => avatarInputRef.current?.click()}
                disabled={saving}
              >
                更换头像
              </button>
            </div>
          )}
          <input
            ref={avatarInputRef}
            className="profile-avatar-file-input"
            type="file"
            accept="image/*"
            onChange={handleAvatarFileChange}
            disabled={saving}
          />
          <div className="profile-edit-toggle-wrap">
            <button
              className="profile-btn profile-btn-outline profile-btn-sm"
              onClick={() => setIsEditing((v) => !v)}
              disabled={loading || saving}
            >
              {isEditing ? '取消编辑' : '编辑资料'}
            </button>
          </div>
          <div className="profile-field">
            <label>IMEI</label>
            <div className="profile-value">{imei || '-'}</div>
          </div>
          {!isEditing && (
            <>
              <div className="profile-field">
                <label>昵称</label>
                <div className="profile-value">{displayName || '-'}</div>
              </div>
              <div className="profile-field">
                <label>个性签名</label>
                <div className="profile-value">{form.signature || '-'}</div>
              </div>
              <div className="profile-field">
                <label>性别</label>
                <div className="profile-value">{form.gender || '-'}</div>
              </div>
              <div className="profile-field">
                <label>地址</label>
                <div className="profile-value">{form.address || '-'}</div>
              </div>
              <div className="profile-field">
                <label>电话</label>
                <div className="profile-value">{form.phone || '-'}</div>
              </div>
              <div className="profile-field">
                <label>生日</label>
                <div className="profile-value">{form.birthday || '-'}</div>
              </div>
              <div className="profile-field">
                <label>偏好</label>
                <div className="profile-value">{form.preferences || '-'}</div>
              </div>
            </>
          )}
          {isEditing && (
            <div className="profile-edit-form">
              <label className="profile-input-label">
                昵称
                <input
                  className="profile-input"
                  value={form.name}
                  onChange={(e) => onFieldChange('name', e.target.value)}
                  placeholder="请输入昵称"
                  disabled={saving}
                />
              </label>
              <label className="profile-input-label">
                个性签名
                <textarea
                  className="profile-textarea"
                  value={form.signature}
                  onChange={(e) => onFieldChange('signature', e.target.value)}
                  placeholder="说点什么吧"
                  rows={2}
                  disabled={saving}
                />
              </label>
              <label className="profile-input-label">
                性别
                <input
                  className="profile-input"
                  value={form.gender}
                  onChange={(e) => onFieldChange('gender', e.target.value)}
                  placeholder="如：男 / 女"
                  disabled={saving}
                />
              </label>
              <label className="profile-input-label">
                地址
                <input
                  className="profile-input"
                  value={form.address}
                  onChange={(e) => onFieldChange('address', e.target.value)}
                  placeholder="请输入地址"
                  disabled={saving}
                />
              </label>
              <label className="profile-input-label">
                电话
                <input
                  className="profile-input"
                  value={form.phone}
                  onChange={(e) => onFieldChange('phone', e.target.value)}
                  placeholder="请输入联系电话"
                  disabled={saving}
                />
              </label>
              <label className="profile-input-label">
                生日
                <input
                  className="profile-input"
                  value={form.birthday}
                  onChange={(e) => onFieldChange('birthday', e.target.value)}
                  placeholder="如：1998-01-01"
                  disabled={saving}
                />
              </label>
              <label className="profile-input-label">
                偏好
                <textarea
                  className="profile-textarea"
                  value={form.preferences}
                  onChange={(e) => onFieldChange('preferences', e.target.value)}
                  placeholder="请输入偏好描述"
                  rows={3}
                  disabled={saving}
                />
              </label>
              <button className="profile-btn profile-btn-primary" onClick={handleSave} disabled={saving}>
                {saving ? '保存中...' : '保存资料'}
              </button>
            </div>
          )}
          {(loading || error || saveMessage) && (
            <div className={`profile-status ${error ? 'is-error' : saveMessage ? 'is-success' : ''}`}>
              {loading ? '正在加载资料...' : error || saveMessage}
            </div>
          )}
          <p className="profile-hint">与手机端绑定同一 IMEI 可打通聊天记录</p>
          <div className="profile-actions">
            <button className="profile-btn profile-btn-outline" onClick={handleLogout}>
              退出当前账号
            </button>
            <button className="profile-btn profile-btn-primary" onClick={handleLogout}>
              切换账号
            </button>
          </div>
        </div>
      </div>
      </div>
      <ChatImageLightbox payload={imageLightbox} onClose={() => setImageLightbox(null)} />
    </>
  )
}
