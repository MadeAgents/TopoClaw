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

import { fetchDesktopVersionCheck } from './api'

export type DesktopUpdatePayload = {
  currentVersion: string
  latestVersion: string
  updateMessage: string
  updateUrl: string
  forceUpdate: boolean
}

async function getDesktopAppVersion(): Promise<string> {
  try {
    const v = await window.electronAPI?.getAppVersion?.()
    if (typeof v === 'string' && v.trim()) return v.trim()
  } catch {
    /* 非 Electron 或 IPC 不可用时忽略 */
  }
  return '0.0.0'
}

/** 若有新版本则返回弹窗所需数据，否则 null（网络/接口失败亦返回 null） */
export async function probeDesktopUpdate(): Promise<DesktopUpdatePayload | null> {
  const currentVersion = await getDesktopAppVersion()
  const data = await fetchDesktopVersionCheck(currentVersion)
  if (!data?.has_update) return null
  return {
    currentVersion,
    latestVersion: data.latest_version,
    updateMessage: data.update_message,
    updateUrl: data.update_url,
    forceUpdate: data.force_update,
  }
}
