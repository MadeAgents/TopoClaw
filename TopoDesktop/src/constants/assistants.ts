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
 * 小助手头像 - 与端侧 apk5/res/drawable 保持一致
 * 使用端侧同一套 PNG 资源
 */
const base = import.meta.env.BASE_URL || './'

/** 自动执行小助手 - ic_assistant_avatar.png */
export const ASSISTANT_AVATAR = `${base}avatars/ic_assistant_avatar.png`

/** 技能学习小助手 - ic_skill_learning_avatar.png */
export const SKILL_LEARNING_AVATAR = `${base}avatars/ic_skill_learning_avatar.png`

/** 人工客服 - ic_customer_service_avatar.png */
export const CUSTOMER_SERVICE_AVATAR = `${base}avatars/ic_customer_service_avatar.png`

/** 聊天小助手 - 生成聊天小助手头像.png（ic_chat_assistant_avatar.png） */
export const CHAT_ASSISTANT_AVATAR = `${base}avatars/ic_chat_assistant_avatar.png`

/** 群组管理者小助手 - ic_groupmanager_avatar.png */
export const GROUP_MANAGER_AVATAR = `${base}avatars/ic_groupmanager_avatar.png`

/** 我（端云互发）- 手机图标 SVG */
export const ME_AVATAR = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='%236200ee'%3E%3Cpath d='M17 1.01L7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14z'/%3E%3C/svg%3E"
