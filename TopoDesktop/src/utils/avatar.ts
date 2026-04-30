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
 * 将头像字符串统一转换为可用的 img src
 * 支持：data URL、http(s) URL、相对路径、纯 base64
 */
export function toAvatarSrc(avatar: string | undefined): string | undefined {
  if (!avatar) return undefined
  if (
    avatar.startsWith('data:') ||
    avatar.startsWith('http') ||
    avatar.startsWith('/') ||
    avatar.startsWith('.')
  ) {
    return avatar
  }
  return `data:image/png;base64,${avatar}`
}

/** 与通讯录 customAssistants 一致：支持 data/http/相对路径与 base64。 */
export function toAvatarSrcLikeContacts(avatar: string | undefined): string | undefined {
  if (!avatar) return undefined
  if (
    avatar.startsWith('data:') ||
    avatar.startsWith('http') ||
    avatar.startsWith('/') ||
    avatar.startsWith('.')
  ) {
    return avatar
  }
  return `data:image/png;base64,${avatar}`
}
