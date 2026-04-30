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

import JSZip from 'jszip'
import type { Skill } from './api'

const SKILL_PACKAGE_VERSION = 1
const SKILL_MANIFEST_FILE = 'skill.json'
const SKILL_README_FILE = 'README.md'

interface SkillPackagePayload {
  version: number
  exportedAt: number
  skill: Skill
  servicePackageBase64?: string
  servicePackageFileName?: string
  serviceSkillName?: string
  serviceSkillSource?: Skill['source']
}

export interface ParsedSkillPackage {
  skill: Skill
  servicePackageBase64?: string
  servicePackageFileName?: string
  serviceSkillName?: string
  serviceSkillSource?: Skill['source']
}

function normalizeSkill(skill: Skill): Skill {
  const title = String(skill.title || '').trim()
  const normalizedSteps = Array.isArray(skill.steps)
    ? skill.steps.map((x) => String(x || '').trim()).filter(Boolean)
    : []
  return {
    ...skill,
    id: String(skill.id || '').trim() || `shared_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
    title,
    steps: normalizedSteps.length > 0 ? normalizedSteps : [''],
    originalPurpose: (skill.originalPurpose || '').trim() || undefined,
    author: (skill.author || '').trim() || undefined,
    tags: Array.isArray(skill.tags) ? skill.tags.map((x) => String(x || '').trim()).filter(Boolean) : undefined,
    packageBase64: undefined,
    packageFileName: undefined,
    createdAt: Number.isFinite(skill.createdAt) ? skill.createdAt : Date.now(),
  }
}

export async function buildSkillPackageBase64(
  skill: Skill,
  options?: {
    servicePackageBase64?: string
    servicePackageFileName?: string
    serviceSkillName?: string
    serviceSkillSource?: Skill['source']
  }
): Promise<{ base64: string; fileName: string }> {
  const normalized = normalizeSkill(skill)
  const zip = new JSZip()
  const payload: SkillPackagePayload = {
    version: SKILL_PACKAGE_VERSION,
    exportedAt: Date.now(),
    skill: normalized,
    servicePackageBase64: (options?.servicePackageBase64 || '').trim() || undefined,
    servicePackageFileName: (options?.servicePackageFileName || '').trim() || undefined,
    serviceSkillName: (options?.serviceSkillName || '').trim() || undefined,
    serviceSkillSource: options?.serviceSkillSource,
  }
  zip.file(SKILL_MANIFEST_FILE, JSON.stringify(payload, null, 2))
  zip.file(
    SKILL_README_FILE,
    [
      `# ${normalized.title || '技能包'}`,
      '',
      normalized.originalPurpose ? `## 用途\n${normalized.originalPurpose}\n` : '',
      '## 说明',
      '- 本包由 TopoDesktop 导出。',
      '- skill.json 保存完整技能元数据与步骤。',
    ].filter(Boolean).join('\n')
  )
  const rawName = (normalized.title || 'skill').trim().replace(/[\\/:*?"<>|]+/g, '_')
  const fileName = `${rawName || 'skill'}_${new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-')}.zip`
  const base64 = await zip.generateAsync({ type: 'base64', compression: 'DEFLATE', compressionOptions: { level: 9 } })
  return { base64, fileName }
}

export async function parseSkillPackageBundleBase64(base64: string): Promise<ParsedSkillPackage | null> {
  const raw = String(base64 || '').trim()
  if (!raw) return null
  try {
    const zip = await JSZip.loadAsync(raw, { base64: true })
    const manifestRaw = await zip.file(SKILL_MANIFEST_FILE)?.async('string')
    if (!manifestRaw) return null
    const parsed = JSON.parse(manifestRaw) as SkillPackagePayload
    if (!parsed || typeof parsed !== 'object' || !parsed.skill) return null
    return {
      skill: normalizeSkill(parsed.skill),
      servicePackageBase64: (parsed.servicePackageBase64 || '').trim() || undefined,
      servicePackageFileName: (parsed.servicePackageFileName || '').trim() || undefined,
      serviceSkillName: (parsed.serviceSkillName || '').trim() || undefined,
      serviceSkillSource: parsed.serviceSkillSource,
    }
  } catch {
    return null
  }
}

export async function parseSkillPackageBase64(base64: string): Promise<Skill | null> {
  const parsed = await parseSkillPackageBundleBase64(base64)
  return parsed?.skill ?? null
}
