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

/** 全局安装包进度弹窗：安装 pip 包时让用户感知正在执行 */

const OVERLAY_ID = 'install-package-overlay'

export function showInstallOverlay(pkg: string): void {
  let el = document.getElementById(OVERLAY_ID)
  if (!el) {
    el = document.createElement('div')
    el.id = OVERLAY_ID
    el.style.cssText =
      'position:fixed;inset:0;background:rgba(0,0,0,0.5);display:flex;align-items:center;justify-content:center;z-index:99999;'
    document.body.appendChild(el)
  }
  const spinnerStyle =
    'width:32px;height:32px;border:3px solid #555;border-top-color:#fff;border-radius:50%;animation:install-overlay-spin .8s linear infinite;margin:0 auto 16px;'
  const keyframes = document.getElementById('install-overlay-keyframes')
  if (!keyframes) {
    const style = document.createElement('style')
    style.id = 'install-overlay-keyframes'
    style.textContent = `@keyframes install-overlay-spin{to{transform:rotate(360deg)}}`
    document.head.appendChild(style)
  }
  el.innerHTML = `
    <div style="background:#2d2d2d;color:#fff;padding:24px 32px;border-radius:12px;text-align:center;box-shadow:0 4px 20px rgba(0,0,0,0.3);min-width:280px;">
      <div style="${spinnerStyle}"></div>
      <p style="margin:0;font-size:15px;">正在安装 <strong>${escapeHtml(pkg)}</strong> 包，请稍候…</p>
      <p style="margin:8px 0 0;font-size:12px;color:#999;">需要网络连接</p>
    </div>
  `
  el.style.display = 'flex'
}

export function hideInstallOverlay(): void {
  const el = document.getElementById(OVERLAY_ID)
  if (el) el.style.display = 'none'
}

function escapeHtml(s: string): string {
  const div = document.createElement('div')
  div.textContent = s
  return div.innerHTML
}
