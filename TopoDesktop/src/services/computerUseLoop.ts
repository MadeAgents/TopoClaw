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
 * PC 端 Computer Use 执行循环
 * 收到 computer_use_execute_request 后：截图 -> POST /upload(device_type=pc) -> 执行动作 -> 循环直到 complete/answer
 * 注：开始前回退桌面( Win+D ) 已暂时关闭，因会导致窗口最小化引发上传异常
 */
import {
  uploadScreenshotAndGetAction,
  click,
  doubleClick,
  rightClick,
  typeText,
  clickAndType,
  scroll,
  pressKey,
  move,
  keyDown,
  keyUp,
  drag,
  bringWindowToFront,
} from './computerUseApi'

const MAX_STEPS = 20

interface ActionResponse {
  action_type: string
  click?: number[]
  double_click?: number[]
  right_click?: number[]
  text?: string
  /** TYPE[x,y,文本] 或 click_and_type，格式 [x, y, text] */
  type?: (number | string)[]
  click_and_type?: (number | string)[]
  scroll_delta?: number
  /** scroll[x,y,delta] 时，滚动发生的位置 [x, y] */
  scroll_target?: number[]
  key?: string
  /** move 目标坐标 [x, y] */
  move?: number[]
  /** drag 起点终点 [x1, y1, x2, y2] */
  drag?: number[]
  params?: string | { content?: string }
}

export async function runComputerUseLoop(
  baseUrl: string,
  requestId: string,
  query: string,
  chatSummary?: string | null,
  signal?: AbortSignal
): Promise<{ success: boolean; content?: string; error?: string }> {
  const uploadUrl = baseUrl.replace(/\/+$/, '') + '/upload'
  // showDesktop() 已暂时关闭：会触发 Win+D 导致本窗口最小化，引发上传异常
/*  */  // await showDesktop().catch(() => {})
  // await new Promise((r) => setTimeout(r, DESKTOP_WAIT_MS))
  try {
    for (let step = 0; step < MAX_STEPS; step++) {
      if (signal?.aborted) {
        return { success: false, error: '任务已被用户停止' }
      }
      const uploadResult = await uploadScreenshotAndGetAction(
        uploadUrl,
        requestId,
        query,
        chatSummary
      )
      if (!uploadResult.ok || !uploadResult.action) {
        return {
          success: false,
          error: uploadResult.error || '截图或上传失败',
        }
      }
      const action = uploadResult.action as unknown as ActionResponse
      const at = (action.action_type || '').toLowerCase()
      console.info('[computerUse] 收到动作:', at, { type: action.type, click_and_type: action.click_and_type })
      if (at === 'complete' || at === 'answer') {
        const content = typeof action.params === 'string' ? action.params : action.params?.content
        return { success: true, content: content || '' }
      }
      if (at === 'call_user') {
        const content = typeof action.params === 'string' ? action.params : action.params?.content
        return { success: true, content: content || '[已询问用户]' }
      }
      if (at === 'click' && action.click && action.click.length >= 2) {
        await click(action.click[0], action.click[1])
      } else if (at === 'double_click' && action.double_click && action.double_click.length >= 2) {
        await doubleClick(action.double_click[0], action.double_click[1])
      } else if (at === 'right_click' && action.right_click && action.right_click.length >= 2) {
        await rightClick(action.right_click[0], action.right_click[1])
      } else if (at === 'type') {
        // TYPE[x,y,文本]：type 或 click_and_type 为 [x, y, text]
        const typeWithCoords = action.type ?? action.click_and_type
        if (typeWithCoords && typeWithCoords.length >= 3) {
          const [x, y, t] = typeWithCoords
          console.info('[computerUse] 执行 TYPE:', { x, y, text: t })
          await clickAndType(Number(x), Number(y), String(t))
        } else if (action.text) {
          console.info('[computerUse] 执行 type(text):', action.text)
          await typeText(action.text)
        } else {
          console.warn('[computerUse] type 动作无有效数据:', { type: action.type, click_and_type: action.click_and_type, text: action.text })
        }
      } else if (at === 'click_and_type' && action.click_and_type && action.click_and_type.length >= 3) {
        const [x, y, t] = action.click_and_type
        await clickAndType(Number(x), Number(y), String(t))
      } else if (at === 'scroll' && action.scroll_delta != null) {
        const x = action.scroll_target?.[0]
        const y = action.scroll_target?.[1]
        await scroll(action.scroll_delta, x, y)
      } else if (at === 'move' && action.move && action.move.length >= 2) {
        await move(action.move[0], action.move[1])
      } else if (at === 'keydown' && action.key) {
        await keyDown(action.key)
      } else if (at === 'keyup' && action.key) {
        await keyUp(action.key)
      } else if (at === 'drag' && action.drag && action.drag.length >= 4) {
        await drag(action.drag[0], action.drag[1], action.drag[2], action.drag[3])
      } else if (at === 'key' && action.key) {
        await pressKey(action.key)
      } else if (at === 'wait') {
        const sec = typeof action.params === 'string' ? parseFloat(action.params) : parseFloat(action.params?.content || '1')
        await new Promise((r) => setTimeout(r, Math.min(sec, 5) * 1000))
      } else {
        console.warn('[computerUse] 未识别的动作类型，跳过:', at, 'action keys:', Object.keys(action))
      }
      await new Promise((r) => setTimeout(r, 300))
    }
    return { success: false, error: '达到最大步数' }
  } finally {
    bringWindowToFront()
  }
}
