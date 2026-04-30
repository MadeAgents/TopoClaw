// Copyright 2025 OPPO
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

const SKILL_DISPLAY_NAME_MAP: Record<string, string> = {
  'contacts-assistants-profile': '好友与助手资料汇总',
  'create-group': '创建群组（含好友与助手）',
  'list-groups-and-members': '查看群组与成员',
  'update-group-members-and-manager': '修改群组成员与群管助手',
}

const DISPLAY_TO_CANONICAL: Record<string, string> = Object.fromEntries(
  Object.entries(SKILL_DISPLAY_NAME_MAP).map(([canonical, display]) => [display.toLowerCase(), canonical])
)

export function normalizeSkillName(value: string | undefined | null): string {
  return String(value || '').trim()
}

export function toCanonicalSkillName(value: string | undefined | null): string {
  const normalized = normalizeSkillName(value)
  if (!normalized) return ''
  const byDisplay = DISPLAY_TO_CANONICAL[normalized.toLowerCase()]
  return byDisplay || normalized
}

export function getSkillDisplayName(value: string | undefined | null): string {
  const canonical = toCanonicalSkillName(value)
  if (!canonical) return ''
  return SKILL_DISPLAY_NAME_MAP[canonical] || canonical
}
