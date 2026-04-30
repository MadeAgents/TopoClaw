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
 * 进入会话时拉云侧/助手历史的节流：短时间内重复进入同一会话可跳过网络，缓解卡顿。
 * 发送消息后会使该会话（及多 session 下对应 session）的冷却失效，下次进入仍会拉取。
 */

const COOLDOWN_MS = 45_000

const lastFetchAt = new Map<string, number>()

export function cloudHistoryThrottleKey(conversationId: string, sessionId?: string | null): string {
  return sessionId ? `${conversationId}::${sessionId}` : conversationId
}

export function isCloudHistoryFetchWithinCooldown(key: string): boolean {
  const t = lastFetchAt.get(key)
  return t != null && Date.now() - t < COOLDOWN_MS
}

export function markCloudHistoryFetched(key: string): void {
  lastFetchAt.set(key, Date.now())
}

/** 发送消息等场景：清除该会话键，下次进入会话会重新拉云侧历史 */
export function invalidateCloudHistoryThrottle(conversationId: string, sessionId?: string | null): void {
  const k = cloudHistoryThrottleKey(conversationId, sessionId)
  lastFetchAt.delete(k)
}

/** 清除该 conversation 下所有多 session 条目的冷却（同一助手多对话时发送后一并失效） */
export function invalidateCloudHistoryThrottleForConversation(conversationId: string): void {
  const prefix = `${conversationId}::`
  for (const k of [...lastFetchAt.keys()]) {
    if (k === conversationId || k.startsWith(prefix)) {
      lastFetchAt.delete(k)
    }
  }
}
