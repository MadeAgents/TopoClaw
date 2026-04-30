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

/**
 * PC 端模拟点击 API - 封装 preload 暴露的 computerUse
 */

function getApi(): Window['computerUse'] {
  return window.computerUse
}

export async function showDesktop(): Promise<{ ok: boolean; error?: string }> {
  const api = getApi()
  if (!api) return { ok: false, error: 'computerUse 不可用' }
  return api.showDesktop()
}

export async function captureScreenshot(): Promise<{
  ok: boolean
  base64?: string
  width?: number
  height?: number
  error?: string
}> {
  const api = getApi()
  if (!api) return { ok: false, error: 'computerUse 不可用' }
  return api.screenshot()
}

/**
 * 主进程内完成截图+上传，避免窗口最小化时渲染进程被节流导致 fetch 失败
 */
export async function uploadScreenshotAndGetAction(
  uploadUrl: string,
  requestId: string,
  query: string,
  chatSummary?: string | null
): Promise<{ ok: boolean; action?: Record<string, unknown>; error?: string }> {
  const api = getApi()
  if (!api || !api.uploadScreenshotAndGetAction) {
    return { ok: false, error: 'computerUse 不可用' }
  }
  return api.uploadScreenshotAndGetAction(uploadUrl, requestId, query, chatSummary)
}

export async function click(x: number, y: number): Promise<{ ok: boolean; error?: string }> {
  const api = getApi()
  if (!api) return { ok: false, error: 'computerUse 不可用' }
  return api.click(x, y)
}

export async function doubleClick(x: number, y: number): Promise<{ ok: boolean; error?: string }> {
  const api = getApi()
  if (!api) return { ok: false, error: 'computerUse 不可用' }
  return api.doubleClick(x, y)
}

export async function rightClick(x: number, y: number): Promise<{ ok: boolean; error?: string }> {
  const api = getApi()
  if (!api) return { ok: false, error: 'computerUse 不可用' }
  return api.rightClick(x, y)
}

export async function typeText(text: string): Promise<{ ok: boolean; error?: string }> {
  const api = getApi()
  if (!api) return { ok: false, error: 'computerUse 不可用' }
  return api.type(text)
}

export async function getCursorPosition(): Promise<{
  ok: boolean
  x?: number
  y?: number
  error?: string
}> {
  const api = getApi()
  if (!api) return { ok: false, error: 'computerUse 不可用' }
  return api.getCursorPosition()
}

export async function clickAndType(
  x: number,
  y: number,
  text: string
): Promise<{ ok: boolean; error?: string }> {
  const api = getApi()
  if (!api) return { ok: false, error: 'computerUse 不可用' }
  return api.clickAndType(x, y, text)
}

export async function scroll(
  delta: number,
  x?: number,
  y?: number
): Promise<{ ok: boolean; error?: string }> {
  const api = getApi()
  if (!api) return { ok: false, error: 'computerUse 不可用' }
  return api.scroll(delta, x, y)
}

export async function pressKey(keyname: string): Promise<{ ok: boolean; error?: string }> {
  const api = getApi()
  if (!api || !api.pressKey) return { ok: false, error: 'computerUse 不可用' }
  return api.pressKey(keyname)
}

export async function move(x: number, y: number): Promise<{ ok: boolean; error?: string }> {
  const api = getApi()
  if (!api || !api.move) return { ok: false, error: 'computerUse 不可用' }
  return api.move(x, y)
}

export async function keyDown(keyname: string): Promise<{ ok: boolean; error?: string }> {
  const api = getApi()
  if (!api || !api.keyDown) return { ok: false, error: 'computerUse 不可用' }
  return api.keyDown(keyname)
}

export async function keyUp(keyname: string): Promise<{ ok: boolean; error?: string }> {
  const api = getApi()
  if (!api || !api.keyUp) return { ok: false, error: 'computerUse 不可用' }
  return api.keyUp(keyname)
}

export async function drag(
  x1: number,
  y1: number,
  x2: number,
  y2: number
): Promise<{ ok: boolean; error?: string }> {
  const api = getApi()
  if (!api || !api.drag) return { ok: false, error: 'computerUse 不可用' }
  return api.drag(x1, y1, x2, y2)
}

/** 将主窗口激活到前台，Computer Use 任务结束后回到 TopoClaw 页面 */
export function bringWindowToFront(): void {
  if (typeof window === 'undefined') return
  ;(window as unknown as { electronAPI?: { focus?: () => void } }).electronAPI?.focus?.()
}
