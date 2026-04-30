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

/** 将任意地址的图片复制到系统剪贴板（Electron 主进程实现，绕过 CORS） */

function blobToDataURL(blob: Blob): Promise<string> {
  return new Promise((resolve, reject) => {
    const r = new FileReader()
    r.onload = () => resolve(String(r.result))
    r.onerror = () => reject(r.error)
    r.readAsDataURL(blob)
  })
}

export async function copyImageFromSrc(src: string): Promise<{ ok: boolean; error?: string }> {
  const api = typeof window !== 'undefined' ? window.electronAPI?.copyImage : undefined
  if (!api) return { ok: false, error: '当前环境不支持复制图片到剪贴板' }
  const t = src.trim()
  if (!t) return { ok: false, error: '无图片地址' }
  if (t.startsWith('data:')) return api({ dataUrl: t })
  if (/^https?:\/\//i.test(t)) return api({ url: t })
  if (t.startsWith('file:')) return api({ fileUrl: t })
  if (t.startsWith('blob:')) {
    try {
      const blob = await (await fetch(t)).blob()
      const dataUrl = await blobToDataURL(blob)
      return api({ dataUrl })
    } catch (e) {
      return { ok: false, error: String(e) }
    }
  }
  return { ok: false, error: '不支持的图片地址' }
}

/** 将网络或 blob 地址转为 data URL，供另存为等使用（可能受 CORS 限制） */
export async function srcToDataUrlIfNeeded(src: string): Promise<string> {
  if (src.startsWith('data:')) return src
  const blob = await (await fetch(src)).blob()
  return blobToDataURL(blob)
}
