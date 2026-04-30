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

import { useState, useEffect, useCallback, useMemo, useRef } from 'react'
import { fetchSkillsFromCommunity, fetchSkillsFromPublicHub, getPlazaSkills, submitSkillToPlaza, sendExecuteCommand, installPublicHubSkill, fetchInstalledSkillsFromService, updatePublicHubSkills, removePublicHubSkillFromService, isSkillSyncedFromService, publicHubSlugFromSkillId, PUBLIC_HUB_MIRROR_OPTIONS, getFriends, getGroups, sendFriendMessage, sendGroupMessageViaWebSocket, type Skill, type Friend, type GroupInfo, type PlazaSkillItem } from '../services/api'
import { getImei, getAutoExecuteCode, getPublicHubMirror, setPublicHubMirror, type PublicHubMirrorId } from '../services/storage'
import {
  loadAllMySkills,
  addCollectedSkill,
  addMySkill,
  removeSkill,
  isCollected,
  updateSkill,
  compactSkillStorage,
} from '../services/skillStorage'
import { SkillCard } from './SkillCard'
import { SkillDetailModal } from './SkillDetailModal'
import { SkillEditModal } from './SkillEditModal'
import { SkillScheduleSettingModal } from './SkillScheduleSettingModal'
import { SkillScheduleListModal } from './SkillScheduleListModal'
import { getSkillDisplayName, toCanonicalSkillName } from '../services/skillNames'
import { showInstallOverlay, hideInstallOverlay } from '../utils/installOverlay'
import { buildSkillShareCardContent } from '../services/skillShareCard'
import { buildSkillPackageBase64, parseSkillPackageBase64 } from '../services/skillPackage'
import './SkillsView.css'

interface SkillsViewProps {
  /** 顶部导航栏搜索词，用于过滤技能或社区搜索 */
  search?: string
}

const BUILTIN_PC_RUN_CODE_SKILL_ID = 'builtin_pc_run_python'
const BUILTIN_WEB_SEARCH_SKILL_ID = 'builtin_web_search_serper'
const BUILTIN_GENERATE_IMAGE_SKILL_ID = 'builtin_generate_image_openai_compatible'
const BUILTIN_CONTACTS_AND_ASSISTANTS_SKILL_ID = 'contacts-assistants-profile'
const BUILTIN_CREATE_GROUP_SKILL_ID = 'create-group'

const BUILTIN_PC_RUN_CODE_SKILL: Skill = {
  id: BUILTIN_PC_RUN_CODE_SKILL_ID,
  title: 'PC Python 执行器',
  originalPurpose: '在电脑端执行 Python 代码，适合文件处理、数据处理、自动化脚本。',
  steps: [
    "# 在这里写你的 Python 代码",
    "print('Hello from PC skill')",
  ],
  createdAt: 0,
  executionPlatform: 'pc',
}

const BUILTIN_WEB_SEARCH_SKILL: Skill = {
  id: BUILTIN_WEB_SEARCH_SKILL_ID,
  title: '联网搜索（Serper）',
  originalPurpose: '使用 Serper API 联网搜索最新信息。执行前可先编辑技能，把 query 改成你要查的内容。',
  steps: [
    "import requests",
    "import os",
    "api_key = os.getenv('SERPER_API_KEY', '').strip()",
    "if not api_key:",
    "    raise RuntimeError('SERPER_API_KEY 未配置，请在 TopoDesktop config.json 的 tools.serper_api_key 中设置')",
    "query = '最新 AI 新闻'",
    "url = 'https://google.serper.dev/search'",
    "headers = {'X-API-KEY': api_key, 'Content-Type': 'application/json'}",
    "payload = {'q': query, 'num': 5, 'hl': 'zh-cn'}",
    "resp = requests.post(url, headers=headers, json=payload, timeout=30)",
    "resp.raise_for_status()",
    "data = resp.json()",
    "items = data.get('organic') or []",
    "if not items:",
    "    print('未找到结果')",
    "else:",
    "    for i, item in enumerate(items[:5], 1):",
    "        title = item.get('title', '')",
    "        link = item.get('link', '')",
    "        snippet = item.get('snippet', '')",
    "        print(f'{i}. {title}\\n{link}\\n{snippet}\\n')",
  ],
  createdAt: 0,
  executionPlatform: 'pc',
}

const BUILTIN_GENERATE_IMAGE_SKILL: Skill = {
  id: BUILTIN_GENERATE_IMAGE_SKILL_ID,
  title: 'generate_image（OpenAI 兼容）',
  originalPurpose: '根据文本提示词生成图片。请先填写 MODEL / BASE_URL / API_KEY。',
  steps: [
    'import os',
    'import base64',
    'import time',
    'import requests',
    '',
    '# ===== 必填配置（按你的模型服务商修改） =====',
    "MODEL = 'gpt-image-1'",
    "BASE_URL = 'https://api.openai.com/v1'",
    "API_KEY = '请替换成你的Key'",
    '# ========================================',
    '',
    '# 生成参数',
    "PROMPT = '一只戴宇航员头盔的橘猫，电影级光影，超高细节'",
    "SIZE = '1024x1024'",
    "OUTPUT_PATH = 'generated_image.png'",
    '',
    'base_url = BASE_URL.rstrip("/")',
    'api_key = "".join((API_KEY or "").split())',
    'model = (MODEL or "").strip()',
    '',
    'if not api_key or api_key == "请替换成你的Key":',
    '    raise RuntimeError("请先填写 API_KEY")',
    'if not model:',
    '    raise RuntimeError("请先填写 MODEL")',
    'if not base_url:',
    '    raise RuntimeError("请先填写 BASE_URL")',
    '',
    "url = f'{base_url}/images/generations'",
    'headers = {',
    "    'Authorization': f'Bearer {api_key}',",
    "    'Content-Type': 'application/json',",
    '}',
    'payload = {',
    "    'model': model,",
    "    'prompt': PROMPT,",
    "    'size': SIZE,",
    '}',
    '',
    'last_error = None',
    'for attempt in range(3):',
    '    try:',
    '        resp = requests.post(url, headers=headers, json=payload, timeout=120)',
    '        if resp.status_code >= 400:',
    '            msg = resp.text',
    '            retryable = resp.status_code in (404, 429, 500, 502, 503, 504)',
    '            if retryable and attempt < 2:',
    '                time.sleep(1.2 * (attempt + 1))',
    '                continue',
    "            raise RuntimeError(f'图片生成失败(HTTP {resp.status_code}): {msg}')",
    '        data = resp.json()',
    '        break',
    '    except Exception as e:',
    '        last_error = e',
    '        if attempt >= 2:',
    '            raise',
    '        time.sleep(1.2 * (attempt + 1))',
    "if 'data' not in locals():",
    "    raise RuntimeError(f'图片生成失败: {last_error}')",
    '',
    "items = data.get('data') or []",
    'if not items:',
    "    raise RuntimeError(f'接口返回为空: {data}')",
    '',
    "b64 = items[0].get('b64_json')",
    "image_url = items[0].get('url')",
    '',
    'if b64:',
    "    with open(OUTPUT_PATH, 'wb') as f:",
    '        f.write(base64.b64decode(b64))',
    'elif image_url:',
    '    img_resp = requests.get(image_url, timeout=120)',
    '    img_resp.raise_for_status()',
    "    with open(OUTPUT_PATH, 'wb') as f:",
    '        f.write(img_resp.content)',
    'else:',
    "    raise RuntimeError(f'未返回图片数据: {items[0]}')",
    '',
    "print(f'图片已生成: {os.path.abspath(OUTPUT_PATH)}')",
  ],
  createdAt: 0,
  executionPlatform: 'pc',
  source: 'local',
}

const BUILTIN_CONTACTS_AND_ASSISTANTS_SKILL: Skill = {
  id: BUILTIN_CONTACTS_AND_ASSISTANTS_SKILL_ID,
  title: BUILTIN_CONTACTS_AND_ASSISTANTS_SKILL_ID,
  originalPurpose: '获取当前用户全部好友的名称、个性签名、偏好等资料，以及助手昵称、简介和域名。',
  steps: [
    "import os",
    "import json",
    "import requests",
    "",
    "# TOPO_IMEI: 当前发起请求用户自己的 IMEI（必填）",
    "imei = os.getenv('TOPO_IMEI', '').strip() or os.getenv('IMEI', '').strip()",
    "# 后端地址：优先使用 TopoDesktop/.env.local 的 VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL",
    "base_url = (",
    "    os.getenv('CUSTOMER_SERVICE_URL', '').strip()",
    "    or os.getenv('VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL', '').strip()",
    ").rstrip('/')",
    "",
    "if not imei:",
    "    raise RuntimeError('缺少 IMEI：请设置 TOPO_IMEI（当前发起请求用户自己的 IMEI）')",
    "if not base_url:",
    "    raise RuntimeError('缺少 customer_service 地址：请在 TopoDesktop/.env.local 配置 VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL')",
    "",
    "def safe_get(path, params=None):",
    "    url = f'{base_url}/{path.lstrip('/')}'",
    "    resp = requests.get(url, params=params, timeout=20)",
    "    resp.raise_for_status()",
    "    return resp.json()",
    "",
    "friends_data = safe_get('/api/friends/list', {'imei': imei})",
    "friends = friends_data.get('friends') or []",
    "",
    "friend_profiles = []",
    "for f in friends:",
    "    f_imei = (f.get('imei') or '').strip()",
    "    profile = {}",
    "    if f_imei:",
    "        try:",
    "            p = safe_get(f'/api/profile/{f_imei}')",
    "            profile = p.get('profile') or {}",
    "        except Exception:",
    "            profile = {}",
    "    friend_profiles.append({",
    "        'imei': f_imei,",
    "        'name': profile.get('name') or f.get('nickname') or f_imei,",
    "        'nickname': f.get('nickname'),",
    "        'signature': profile.get('signature') or profile.get('bio') or profile.get('preferences'),",
    "        'preferences': profile.get('preferences'),",
    "        'phone': profile.get('phone'),",
    "        'address': profile.get('address'),",
    "    })",
    "",
    "assistants_data = safe_get('/api/custom-assistants', {'imei': imei})",
    "assistants = assistants_data.get('assistants') or []",
    "assistant_profiles = [{",
    "    'id': a.get('id'),",
    "    'nickname': a.get('name'),",
    "    'intro': a.get('intro'),",
    "    'baseUrl': a.get('baseUrl'),",
    "    'source': 'custom',",
    "} for a in assistants]",
    "",
    "# 补充系统自动执行小助手（不在 /api/custom-assistants 内时）",
    "known_ids = {str(item.get('id') or '').strip() for item in assistant_profiles}",
    "if 'assistant' not in known_ids:",
    "    assistant_profiles.append({",
    "        'id': 'assistant',",
    "        'nickname': '自动执行小助手',",
    "        'intro': '系统内置自动执行助手（群组默认助手）',",
    "        'baseUrl': '',",
    "        'source': 'system',",
    "    })",
    "",
    "result = {",
    "    'imei': imei,",
    "    'friends': friend_profiles,",
    "    'assistants': assistant_profiles,",
    "}",
    "print(json.dumps(result, ensure_ascii=False, indent=2))",
  ],
  createdAt: 0,
  executionPlatform: 'pc',
}

const BUILTIN_CREATE_GROUP_SKILL: Skill = {
  id: BUILTIN_CREATE_GROUP_SKILL_ID,
  title: BUILTIN_CREATE_GROUP_SKILL_ID,
  originalPurpose: '按给定群组名、好友 IMEI 列表和助手 ID 列表创建群组，并返回创建后的群组信息。',
  steps: [
    "import os",
    "import json",
    "import requests",
    "",
    "# 当前发起调用账号的 IMEI（必填）",
    "caller_imei = os.getenv('TOPO_IMEI', '').strip() or os.getenv('IMEI', '').strip()",
    "# 后端地址：优先使用 TopoDesktop/.env.local 的 VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL",
    "base_url = (",
    "    os.getenv('CUSTOMER_SERVICE_URL', '').strip()",
    "    or os.getenv('VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL', '').strip()",
    ").rstrip('/')",
    "",
    "# ===== 可编辑输入 =====",
    "# 群主 IMEI（通常等于调用账号 IMEI；为空则自动使用 caller_imei）",
    "owner_imei = ''",
    "group_name = '我的新群组'",
    "friend_imeis = [",
    "    # '867xxxxxxxxxxxxx',",
    "]",
    "assistant_ids = [",
    "    # 'assistant',",
    "    # 'custom_topoclaw',",
    "]",
    "# 群组管理助手 ID（可空；仅能填写 1 个）",
    "group_manager_assistant_id = ''",
    "# ====================",
    "",
    "if not caller_imei:",
    "    raise RuntimeError('缺少 IMEI：请设置 TOPO_IMEI（当前发起请求用户自己的 IMEI）')",
    "if not base_url:",
    "    raise RuntimeError('缺少 customer_service 地址：请在 TopoDesktop/.env.local 配置 VITE_MOBILE_AGENT_CUSTOMER_SERVICE_URL')",
    "if not group_name.strip():",
    "    raise RuntimeError('group_name 不能为空')",
    "",
    "owner_imei = str(owner_imei or '').strip() or caller_imei",
    "group_manager_assistant_id = str(group_manager_assistant_id or '').strip()",
    "",
    "def post(path, body):",
    "    url = f'{base_url}/{path.lstrip('/')}'",
    "    resp = requests.post(url, json=body, timeout=20)",
    "    resp.raise_for_status()",
    "    return resp.json()",
    "",
    "def get(path, params=None):",
    "    url = f'{base_url}/{path.lstrip('/')}'",
    "    resp = requests.get(url, params=params, timeout=20)",
    "    resp.raise_for_status()",
    "    return resp.json()",
    "",
    "member_imeis = [str(x).strip() for x in friend_imeis if str(x).strip()]",
    "assistant_ids_norm = [str(x).strip() for x in assistant_ids if str(x).strip()]",
    "if group_manager_assistant_id and group_manager_assistant_id not in assistant_ids_norm:",
    "    assistant_ids_norm.append(group_manager_assistant_id)",
    "",
    "create_res = post('/api/groups/create', {",
    "    'imei': owner_imei,",
    "    'name': group_name.strip(),",
    "    'memberImeis': member_imeis,",
    "    'assistantEnabled': bool(assistant_ids_norm),",
    "})",
    "if not create_res.get('success'):",
    "    raise RuntimeError(f\"创建群组失败: {create_res}\")",
    "",
    "group_id = create_res.get('groupId')",
    "add_results = []",
    "for aid in assistant_ids_norm:",
    "    try:",
    "        payload = {",
    "            'imei': owner_imei,",
    "            'groupId': group_id,",
    "            'assistantId': aid,",
    "        }",
    "        r = post('/api/groups/add-assistant', payload)",
    "        item = {'assistantId': aid, 'success': bool(r.get('success')), 'raw': r}",
    "        if group_manager_assistant_id and aid == group_manager_assistant_id and bool(r.get('success')):",
    "            try:",
    "                gm_res = post('/api/groups/update-assistant-config', {",
    "                    'imei': owner_imei,",
    "                    'groupId': group_id,",
    "                    'assistantId': aid,",
    "                    'capabilities': ['chat', 'group_manager'],",
    "                })",
    "                item['group_manager_config_updated'] = bool(gm_res.get('success'))",
    "                item['group_manager_update_raw'] = gm_res",
    "            except Exception as ge:",
    "                item['group_manager_config_updated'] = False",
    "                item['group_manager_update_error'] = str(ge)",
    "        add_results.append(item)",
    "    except Exception as e:",
    "        add_results.append({'assistantId': aid, 'success': False, 'error': str(e)})",
    "",
    "group_detail = get(f'/api/groups/{group_id}') if group_id else {}",
    "",
    "result = {",
    "    'success': True,",
    "    'caller_imei': caller_imei,",
    "    'owner_imei': owner_imei,",
    "    'group_manager_assistant_id': group_manager_assistant_id or None,",
    "    'groupId': group_id,",
    "    'create': create_res,",
    "    'assistant_add_results': add_results,",
    "    'group': group_detail.get('group') if isinstance(group_detail, dict) else group_detail,",
    "}",
    "print(json.dumps(result, ensure_ascii=False, indent=2))",
  ],
  createdAt: 0,
  executionPlatform: 'pc',
}

const REQUIRED_BUILTIN_MY_SKILLS: Skill[] = [
  BUILTIN_PC_RUN_CODE_SKILL,
  BUILTIN_WEB_SEARCH_SKILL,
  BUILTIN_GENERATE_IMAGE_SKILL,
  BUILTIN_CONTACTS_AND_ASSISTANTS_SKILL,
  BUILTIN_CREATE_GROUP_SKILL,
]

function isBuiltinDefaultSkill(skill: Skill): boolean {
  return (
    skill.id === BUILTIN_PC_RUN_CODE_SKILL_ID ||
    skill.id === BUILTIN_WEB_SEARCH_SKILL_ID ||
    skill.id === BUILTIN_GENERATE_IMAGE_SKILL_ID ||
    toCanonicalSkillName(skill.title) === BUILTIN_CONTACTS_AND_ASSISTANTS_SKILL_ID ||
    toCanonicalSkillName(skill.title) === BUILTIN_CREATE_GROUP_SKILL_ID
  )
}

function canEditMySkill(skill: Skill, isServerSkill: boolean): boolean {
  if (isServerSkill) return false
  // Builtin generate_image needs user-side persistent configuration.
  if (skill.id === BUILTIN_GENERATE_IMAGE_SKILL_ID) return true
  return !isBuiltinDefaultSkill(skill)
}

type CommunitySource = 'tophub' | 'publichub' | 'plaza'

function sourcesFromCommunityFilter(f: 'all' | 'tophub' | 'publichub' | 'plaza'): Set<CommunitySource> {
  if (f === 'all') return new Set(['tophub', 'publichub', 'plaza'])
  if (f === 'tophub') return new Set(['tophub'])
  if (f === 'publichub') return new Set(['publichub'])
  return new Set(['plaza'])
}

function mapPlazaSkillToUiSkill(item: PlazaSkillItem): Skill {
  return {
    id: item.id,
    title: item.title,
    steps: item.steps || [],
    createdAt: item.created_at ? Date.parse(item.created_at) || Date.now() : Date.now(),
    originalPurpose: item.originalPurpose,
    executionPlatform: item.executionPlatform,
    author: item.author,
    tags: item.tags,
    source: 'plaza',
    packageBase64: item.package_base64,
    packageFileName: item.package_file_name,
  }
}

function mapInstalledServiceSkillToUiSkill(item: {
  name: string
  description: string
  source: string
}): Skill {
  const normalizedSource = item.source === 'clawhub' ? 'publichub' : item.source
  return {
    id: `publichub_${item.name}`,
    title: item.name,
    steps: [],
    createdAt: Date.now(),
    source: normalizedSource as Skill['source'],
    originalPurpose: item.description,
  }
}

export function SkillsView({ search = '' }: SkillsViewProps) {
  type SkillShareTarget = 'friend' | 'group' | 'community'
  const [activeTab, setActiveTab] = useState<'my' | 'community'>('my')
  const [mySkills, setMySkills] = useState<Skill[]>([])
  const [serviceInstalledSkills, setServiceInstalledSkills] = useState<Skill[]>([])
  const [topHubSkills, setTopHubSkills] = useState<Skill[]>([])
  const [publicHubSkills, setPublicHubSkills] = useState<Skill[]>([])
  const [plazaSkills, setPlazaSkills] = useState<Skill[]>([])
  const [loading, setLoading] = useState(false)
  const [refreshKey, setRefreshKey] = useState(0)
  const [syncing, setSyncing] = useState(false)
  const [updating, setUpdating] = useState(false)
  const [compacting, setCompacting] = useState(false)
  const [detailSkill, setDetailSkill] = useState<Skill | null>(null)
  const [detailSource, setDetailSource] = useState<'my' | 'community'>('community')
  const [editSkill, setEditSkill] = useState<Skill | null>(null)
  const [editIsCreate, setEditIsCreate] = useState(false)
  const [scheduleSettingSkill, setScheduleSettingSkill] = useState<Skill | null>(null)
  const [scheduleListOpen, setScheduleListOpen] = useState(false)
  const [sourceFilter, setSourceFilter] = useState<'all' | 'tophub' | 'publichub' | 'plaza'>('all')
  // Toast 提示
  const [toastMessage, setToastMessage] = useState('')
  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  const showToast = (msg: string) => {
    if (toastTimerRef.current) clearTimeout(toastTimerRef.current)
    setToastMessage(msg)
    toastTimerRef.current = setTimeout(() => setToastMessage(''), 4000)
  }

  // 搜索相关状态
  const [publicHubMirrorModalOpen, setPublicHubMirrorModalOpen] = useState(false)
  const [mirrorDraft, setMirrorDraft] = useState<PublicHubMirrorId>(() => getPublicHubMirror())
  const [sharingSkill, setSharingSkill] = useState<Skill | null>(null)
  const [shareTarget, setShareTarget] = useState<SkillShareTarget | null>(null)
  const [shareFriends, setShareFriends] = useState<Friend[]>([])
  const [shareGroups, setShareGroups] = useState<GroupInfo[]>([])
  const [shareLoadingFriends, setShareLoadingFriends] = useState(false)
  const [shareLoadingGroups, setShareLoadingGroups] = useState(false)
  const [selectedFriendImeis, setSelectedFriendImeis] = useState<string[]>([])
  const [selectedGroupIds, setSelectedGroupIds] = useState<string[]>([])
  const [shareSending, setShareSending] = useState(false)

  const [searchModalOpen, setSearchModalOpen] = useState(false)
  const [searchKeyword, setSearchKeyword] = useState('')
  const [searchSources, setSearchSources] = useState<Set<CommunitySource>>(new Set())
  const [activeSearchKeyword, setActiveSearchKeyword] = useState('')
  const [activeSearchSources, setActiveSearchSources] = useState<Set<CommunitySource>>(new Set())
  const topHubSkillsRef = useRef<Skill[]>([])
  const publicHubSkillsRef = useRef<Skill[]>([])
  const plazaSkillsRef = useRef<Skill[]>([])
  const imei = getImei()

  const refreshMySkills = useCallback(() => {
    // 只读取本地技能；内置技能通过 displayedMySkills 在页面层注入，避免 localStorage 配额溢出。
    setMySkills(loadAllMySkills())
  }, [])

  const handleScheduleSave = (skill: Skill, config: Skill['scheduleConfig'] | null) => {
    const updated = { ...skill, scheduleConfig: config ?? undefined }
    updateSkill(updated)
    setRefreshKey((k) => k + 1)
    setScheduleSettingSkill(null)
    if (detailSkill?.id === skill.id) setDetailSkill(updated)
    if (editSkill?.id === skill.id) setEditSkill(updated)
  }

  const handleEditScheduleChange = (config: Skill['scheduleConfig']) => {
    if (!editSkill) return
    const updated = { ...editSkill, scheduleConfig: config ?? undefined }
    updateSkill(updated)
    setRefreshKey((k) => k + 1)
    setEditSkill(updated)
  }

  const syncMyPublicHubSkills = useCallback(async () => {
    setSyncing(true)
    try {
      const serverSkills = await fetchInstalledSkillsFromService()
      setServiceInstalledSkills(serverSkills.map(mapInstalledServiceSkillToUiSkill))
      refreshMySkills()
    } catch (e) {
      console.error('同步 PublicHub 技能失败:', e)
    } finally {
      setSyncing(false)
    }
  }, [refreshMySkills])

  const handleRefreshPublicHub = async () => {
    await syncMyPublicHubSkills()
  }

  const handleUpdatePublicHub = async () => {
    setUpdating(true)
    try {
      const result = await updatePublicHubSkills()
      if (result.success) {
        window.alert('更新成功')
        await syncMyPublicHubSkills()
      } else {
        window.alert(`更新失败：${result.error || '未知错误'}`)
      }
    } catch (e) {
      window.alert(`更新失败：${e instanceof Error ? e.message : String(e)}`)
    } finally {
      setUpdating(false)
    }
  }

  const handleCompactSkillCache = () => {
    if (compacting) return
    setCompacting(true)
    try {
      const result = compactSkillStorage()
      refreshMySkills()
      window.alert(
        `清理完成：共移除 ${result.removedTotal} 条冗余缓存（我的技能 ${result.myBefore}→${result.myAfter}，收藏 ${result.collectedBefore}→${result.collectedAfter}）`
      )
    } catch (e) {
      window.alert(`清理失败：${e instanceof Error ? e.message : String(e)}`)
    } finally {
      setCompacting(false)
    }
  }

  // 合并两个数据源的技能列表，并根据筛选条件过滤
  const communitySkills = useMemo(() => {
    const allSkills = [...topHubSkills, ...publicHubSkills, ...plazaSkills]
    if (sourceFilter === 'all') {
      return allSkills
    } else if (sourceFilter === 'tophub') {
      return allSkills.filter(skill => skill.source === 'tophub')
    } else if (sourceFilter === 'publichub') {
      return allSkills.filter(skill => skill.source === 'publichub')
    } else if (sourceFilter === 'plaza') {
      return allSkills.filter(skill => skill.source === 'plaza')
    }
    return allSkills
  }, [topHubSkills, publicHubSkills, plazaSkills, sourceFilter])

  /** 弹窗提交搜索、或有搜索词时切换 TopHub/PublicHub 筛选时调用 */
  const runCommunitySearch = useCallback(
    async (keyword: string, sources: Set<CommunitySource>) => {
      setLoading(true)
      const snapTop = topHubSkillsRef.current
      const snapPub = publicHubSkillsRef.current
      const snapPlaza = plazaSkillsRef.current
      let topHubError = false
      let publicHubError = false
      let plazaError = false
      const tasks: Promise<void>[] = []

      if (sources.has('tophub')) {
        tasks.push(
          fetchSkillsFromCommunity({ name: keyword, desc: keyword })
            .then((skills) => {
              setTopHubSkills(skills)
              topHubSkillsRef.current = skills
            })
            .catch((reason) => {
              console.error('获取 TopHub 技能失败:', reason)
              topHubError = true
              setTopHubSkills(snapTop)
              topHubSkillsRef.current = snapTop
            })
        )
      } else {
        setTopHubSkills([])
        topHubSkillsRef.current = []
      }

      if (sources.has('publichub')) {
        tasks.push(
          fetchSkillsFromPublicHub({ query: keyword })
            .then((skills) => {
              setPublicHubSkills(skills)
              publicHubSkillsRef.current = skills
            })
            .catch((reason) => {
              console.error('获取 PublicHub 技能失败:', reason)
              publicHubError = true
              setPublicHubSkills(snapPub)
              publicHubSkillsRef.current = snapPub
            })
        )
      } else {
        setPublicHubSkills([])
        publicHubSkillsRef.current = []
      }

      if (sources.has('plaza')) {
        tasks.push(
          getPlazaSkills({ page: 1, limit: 50, query: keyword || undefined, imei: getImei() || undefined })
            .then(({ skills }) => {
              const mapped = skills.map(mapPlazaSkillToUiSkill)
              setPlazaSkills(mapped)
              plazaSkillsRef.current = mapped
            })
            .catch((reason) => {
              console.error('获取技能广场技能失败:', reason)
              plazaError = true
              setPlazaSkills(snapPlaza)
              plazaSkillsRef.current = snapPlaza
            })
        )
      } else {
        setPlazaSkills([])
        plazaSkillsRef.current = []
      }

      await Promise.allSettled(tasks)
      setLoading(false)

      const errors: string[] = []
      if (topHubError) errors.push('TopHub')
      if (publicHubError) errors.push('PublicHub')
      if (plazaError) errors.push('技能广场')
      if (errors.length > 0) {
        showToast(`${errors.join('、')} 数据源请求失败，已保留搜索前的列表`)
      }
    },
    [showToast]
  )

  useEffect(() => {
    refreshMySkills()
  }, [refreshMySkills, refreshKey])

  useEffect(() => {
    syncMyPublicHubSkills()
  }, [syncMyPublicHubSkills])

  const deleteMySkillAfterConfirm = async (skill: Skill) => {
    if (skill.source === 'publichub') {
      const slug = publicHubSlugFromSkillId(skill.id)
      if (!slug) {
        window.alert('无法解析 PublicHub 技能标识')
        return
      }

      try {
        const result = await removePublicHubSkillFromService(slug)
        if (result.success) {
          removeSkill(skill)
          refreshMySkills()
        } else {
          window.alert(`删除失败：${result.error || '未知错误'}`)
        }
      } catch (e) {
        window.alert(`删除失败：${e instanceof Error ? e.message : String(e)}`)
      }
    } else {
      removeSkill(skill)
      refreshMySkills()
    }
  }

  const handleDeleteMySkill = async (skill: Skill) => {
    if (!window.confirm(`确定要删除技能「${skill.title}」吗？`)) return
    await deleteMySkillAfterConfirm(skill)
  }

  const handleCollectSkill = async (skill: Skill, closeDetail = false) => {
    if (skill.source === 'plaza') {
      if (!skill.packageBase64) {
        window.alert('该技能缺少技能包，暂时无法添加')
        return
      }
      try {
        const parsed = await parseSkillPackageBase64(skill.packageBase64)
        if (!parsed) {
          window.alert('技能包损坏，无法添加')
          return
        }
        addCollectedSkill({
          ...parsed,
          source: 'plaza',
          packageBase64: skill.packageBase64,
          packageFileName: skill.packageFileName,
          createdAt: Date.now(),
        })
        setRefreshKey((k) => k + 1)
        if (closeDetail) setDetailSkill(null)
        window.alert('已从技能包添加到我的技能')
      } catch (e) {
        window.alert(`添加失败：${e instanceof Error ? e.message : String(e)}`)
      }
      return
    }

    // PublicHub 技能需要先安装
    if (skill.source === 'publichub') {
      try {
        const result = await installPublicHubSkill(skill)
        if (result.success) {
          addCollectedSkill(skill)
          setRefreshKey((k) => k + 1)
          if (closeDetail) {
            setDetailSkill(null)
          }
          window.alert('安装成功，已加入我的技能')
        } else {
          window.alert(`安装失败：${result.error || '未知错误'}`)
        }
      } catch (e) {
        window.alert(`安装失败：${e instanceof Error ? e.message : String(e)}`)
      }
    } else {
      // 非 PublicHub 技能保持原有逻辑
      addCollectedSkill(skill)
      setRefreshKey((k) => k + 1)
      if (closeDetail) {
        setDetailSkill(null)
      }
    }
  }

  const handleCollect = (skill: Skill) => {
    handleCollectSkill(skill, false)
  }

  const handleCollectFromDetail = (skill: Skill) => {
    handleCollectSkill(skill, true)
  }

  const handleDeleteFromDetail = (skill: Skill) => {
    void deleteMySkillAfterConfirm(skill)
  }

  const handleEditSave = (updated: Skill) => {
    if (editIsCreate) {
      addMySkill(updated)
      setEditIsCreate(false)
    } else {
      updateSkill(updated)
    }
    setRefreshKey((k) => k + 1)
    setEditSkill(null)
    setDetailSkill(null)
  }

  const handleCreateNew = () => {
    const emptySkill: Skill = {
      id: `manual_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`,
      title: '',
      originalPurpose: '',
      steps: [''],
      createdAt: Date.now(),
    }
    setEditSkill(emptySkill)
    setEditIsCreate(true)
  }

  const resetShareState = () => {
    setSharingSkill(null)
    setShareTarget(null)
    setSelectedFriendImeis([])
    setSelectedGroupIds([])
    setShareSending(false)
  }

  const buildSkillShareCardWithPackage = useCallback(async (skill: Skill) => {
    const pkg = await buildSkillPackageBase64(skill)
    return buildSkillShareCardContent({
      id: skill.id,
      title: skill.title,
      originalPurpose: skill.originalPurpose,
      steps: skill.steps,
      executionPlatform: skill.executionPlatform,
      source: skill.source,
      author: skill.author,
      tags: skill.tags,
      packageBase64: pkg.base64,
      packageFileName: pkg.fileName,
    })
  }, [])

  const handleOpenShareTarget = async (target: SkillShareTarget) => {
    if (!sharingSkill) return
    if (target === 'friend') {
      const imei = getImei()
      if (!imei) {
        window.alert('请先绑定手机设备')
        return
      }
      setShareTarget('friend')
      setShareLoadingFriends(true)
      try {
        const friends = await getFriends(imei).catch(() => [])
        setShareFriends(friends.filter((f) => f.status === 'accepted'))
      } finally {
        setShareLoadingFriends(false)
      }
      return
    }
    if (target === 'group') {
      const imei = getImei()
      if (!imei) {
        window.alert('请先绑定手机设备')
        return
      }
      setShareTarget('group')
      setShareLoadingGroups(true)
      try {
        const groups = await getGroups(imei).catch(() => [])
        setShareGroups(groups)
      } finally {
        setShareLoadingGroups(false)
      }
      return
    }
    if (!imei) {
      window.alert('请先绑定手机设备')
      return
    }
    try {
      const pkg = await buildSkillPackageBase64(sharingSkill)
      const res = await submitSkillToPlaza(imei, sharingSkill, {
        packageBase64: pkg.base64,
        packageFileName: pkg.fileName,
      })
      if (!res.success) {
        window.alert('发布失败，请稍后重试')
      } else {
        window.alert('已发布到技能广场')
        if (activeTab === 'community') {
          setSourceFilter((prev) => (prev === 'all' ? prev : 'plaza'))
          setActiveSearchSources(new Set(['plaza']))
          void runCommunitySearch(activeSearchKeyword.trim(), new Set(['plaza']))
        }
      }
    } catch {
      window.alert('发布失败，请检查网络后重试')
    }
    resetShareState()
  }

  const handleSendSkillShareToFriends = async () => {
    const skill = sharingSkill
    if (!skill) return
    const imei = getImei()
    if (!imei) {
      window.alert('请先绑定手机设备')
      return
    }
    if (selectedFriendImeis.length === 0) {
      window.alert('请至少选择一位好友')
      return
    }
    setShareSending(true)
    try {
      const cardContent = await buildSkillShareCardWithPackage(skill)
      const tasks = selectedFriendImeis.map((targetImei) =>
        sendFriendMessage(imei, targetImei, cardContent, { messageType: 'skill_card' })
      )
      const result = await Promise.allSettled(tasks)
      const successCount = result.filter((x) => x.status === 'fulfilled' && x.value.success).length
      const failCount = selectedFriendImeis.length - successCount
      window.alert(failCount > 0 ? `已分享给 ${successCount} 位好友，${failCount} 位发送失败` : `已分享给 ${successCount} 位好友`)
      resetShareState()
    } catch {
      window.alert('分享失败，请检查网络后重试')
    } finally {
      setShareSending(false)
    }
  }

  const handleSendSkillShareToGroups = async () => {
    const skill = sharingSkill
    if (!skill) return
    const imei = getImei()
    if (!imei) {
      window.alert('请先绑定手机设备')
      return
    }
    if (selectedGroupIds.length === 0) {
      window.alert('请至少选择一个群组')
      return
    }
    setShareSending(true)
    try {
      const cardContent = await buildSkillShareCardWithPackage(skill)
      const tasks = selectedGroupIds.map((groupId) =>
        sendGroupMessageViaWebSocket(imei, groupId, cardContent, { messageType: 'skill_card' })
      )
      const result = await Promise.allSettled(tasks)
      const successCount = result.filter((x) => x.status === 'fulfilled' && x.value.success).length
      const failCount = selectedGroupIds.length - successCount
      window.alert(failCount > 0 ? `已分享到 ${successCount} 个群组，${failCount} 个发送失败` : `已分享到 ${successCount} 个群组`)
      resetShareState()
    } catch {
      window.alert('分享失败，请检查网络后重试')
    } finally {
      setShareSending(false)
    }
  }

  const handleExecute = useCallback(async (skill: Skill) => {
    const platform = skill.executionPlatform ?? 'mobile'
    if (platform === 'pc') {
      const code = (skill.steps ?? []).join('\n').trim()
      if (!code) {
        window.alert('该 PC 技能没有可执行代码')
        return
      }

      const autoExecute = getAutoExecuteCode()
      if (!autoExecute) {
        const ok = window.confirm('当前“是否自动执行代码”已关闭，是否继续执行该 PC 技能？')
        if (!ok) return
      }

      const codeExec = typeof window !== 'undefined'
        ? (window as Window & {
          codeExec?: {
            run: (code: string) => Promise<{ success: boolean; stdout?: string; stderr?: string; error?: string; missingPackage?: string }>
            installPackage?: (name: string) => Promise<{ success: boolean; error?: string; stderr?: string }>
          }
        }).codeExec
        : undefined
      if (!codeExec?.run) {
        window.alert('当前环境不支持 PC 代码执行（需在桌面应用中运行）')
        return
      }

      try {
        let res = await codeExec.run(code)
        if (!res.success && res.missingPackage && codeExec.installPackage && typeof window !== 'undefined') {
          const ok = window.confirm(`代码执行需要安装 ${res.missingPackage} 包，是否允许安装？（需要网络）`)
          if (ok) {
            try {
              showInstallOverlay(res.missingPackage)
              const installRes = await codeExec.installPackage(res.missingPackage)
              if (installRes.success) {
                res = await codeExec.run(code)
              } else {
                res = { success: false, error: `安装失败: ${installRes.error || installRes.stderr || '未知错误'}` }
              }
            } finally {
              hideInstallOverlay()
            }
          }
        }

        if (res.success) {
          const output = [res.stdout?.trim(), res.stderr?.trim()].filter(Boolean).join('\n')
          window.alert(output ? `执行完成：\n${output}` : '执行完成（无输出）')
        } else {
          window.alert(`执行失败：${res.error || res.stderr || '未知错误'}`)
        }
      } catch (e) {
        window.alert('执行失败：' + (e instanceof Error ? e.message : String(e)))
      }
      return
    }
    const imei = getImei()
    if (!imei) {
      window.alert('请先绑定手机')
      return
    }
    try {
      const uuid = `skill_${skill.id}_${Date.now()}`
      const res = await sendExecuteCommand(imei, skill.title, uuid, skill.steps)
      if (!res.success) {
        window.alert('手机端不在线，请确保手机已打开应用并保持连接')
        return
      }
      window.alert('已向手机发送执行指令')
    } catch (e) {
      window.alert('执行失败：' + (e instanceof Error ? e.message : '网络错误'))
    }
  }, [])

  // 打开搜索弹窗
  const handleOpenSearchModal = () => {
    setSearchKeyword('')
    setSearchSources(new Set())
    setSearchModalOpen(true)
  }

  // 切换数据源选择
  const handleToggleSearchSource = (source: CommunitySource) => {
    setSearchSources(prev => {
      const newSet = new Set(prev)
      if (newSet.has(source)) {
        newSet.delete(source)
      } else {
        newSet.add(source)
      }
      return newSet
    })
  }

  // 提交搜索
  const handleSubmitSearch = () => {
    const kw = searchKeyword.trim()
    if (!kw) {
      window.alert('请输入搜索关键词')
      return
    }
    const sources: Set<CommunitySource> =
      searchSources.size === 0
        ? new Set(['tophub', 'publichub', 'plaza'])
        : new Set(searchSources)

    setActiveSearchKeyword(kw)
    setActiveSearchSources(sources)

    if (sources.size !== 1) {
      setSourceFilter('all')
    } else if (sources.has('tophub')) {
      setSourceFilter('tophub')
    } else if (sources.has('publichub')) {
      setSourceFilter('publichub')
    } else if (sources.has('plaza')) {
      setSourceFilter('plaza')
    }

    setSearchModalOpen(false)
    void runCommunitySearch(kw, sources)
  }

  // 关闭搜索词条：清空列表回到默认空态
  const handleCloseSearchTag = () => {
    setActiveSearchKeyword('')
    setActiveSearchSources(new Set())
    setTopHubSkills([])
    setPublicHubSkills([])
    setPlazaSkills([])
    topHubSkillsRef.current = []
    publicHubSkillsRef.current = []
    plazaSkillsRef.current = []
  }

  const handleCommunityTopHubFilterClick = () => {
    const next: 'all' | 'tophub' | 'publichub' | 'plaza' = sourceFilter === 'tophub' ? 'all' : 'tophub'
    setSourceFilter(next)
    const kw = activeSearchKeyword.trim()
    if (kw) {
      const src = sourcesFromCommunityFilter(next)
      setActiveSearchSources(src)
      void runCommunitySearch(kw, src)
    }
  }

  const handleCommunityPublicHubFilterClick = () => {
    const next: 'all' | 'tophub' | 'publichub' | 'plaza' = sourceFilter === 'publichub' ? 'all' : 'publichub'
    setSourceFilter(next)
    const kw = activeSearchKeyword.trim()
    if (kw) {
      const src = sourcesFromCommunityFilter(next)
      setActiveSearchSources(src)
      void runCommunitySearch(kw, src)
    }
  }

  const handleCommunityPlazaFilterClick = () => {
    const next: 'all' | 'tophub' | 'publichub' | 'plaza' = sourceFilter === 'plaza' ? 'all' : 'plaza'
    setSourceFilter(next)
    const kw = activeSearchKeyword.trim()
    if (kw) {
      const src = sourcesFromCommunityFilter(next)
      setActiveSearchSources(src)
      void runCommunitySearch(kw, src)
    }
  }

  const handleRefreshCommunity = useCallback(() => {
    const kw = activeSearchKeyword.trim()
    const sources =
      kw && activeSearchSources.size > 0
        ? new Set(activeSearchSources)
        : sourcesFromCommunityFilter(sourceFilter)
    void runCommunitySearch(kw, sources)
  }, [activeSearchKeyword, activeSearchSources, sourceFilter, runCommunitySearch])

  useEffect(() => {
    if (activeTab !== 'community') return
    // 进入社区页时先自动拉一次，避免“发布后看起来仍是空白”。
    const kw = activeSearchKeyword.trim()
    const sources =
      kw && activeSearchSources.size > 0
        ? new Set(activeSearchSources)
        : sourcesFromCommunityFilter(sourceFilter)
    void runCommunitySearch(kw, sources)
  }, [activeTab, sourceFilter, activeSearchKeyword, activeSearchSources, runCommunitySearch])

  const handleOpenPublicHubMirrorModal = () => {
    setMirrorDraft(getPublicHubMirror())
    setPublicHubMirrorModalOpen(true)
  }

  const handleSavePublicHubMirror = () => {
    setPublicHubMirror(mirrorDraft)
    setPublicHubMirrorModalOpen(false)
  }

  const displayedMySkills = useMemo(() => {
    const list = [...mySkills]
    const existingKeys = new Set<string>()
    list.forEach((s) => {
      const key = toCanonicalSkillName(s.title).trim().toLowerCase() || s.id.trim().toLowerCase()
      if (key) existingKeys.add(key)
    })

    serviceInstalledSkills.forEach((s) => {
      const key = toCanonicalSkillName(s.title).trim().toLowerCase() || s.id.trim().toLowerCase()
      if (!key || existingKeys.has(key)) return
      existingKeys.add(key)
      list.push(s)
    })

    const hasBuiltinById = (id: string) => list.some((s) => s.id === id)
    const hasBuiltinByCanonical = (canonical: string) =>
      list.some((s) => toCanonicalSkillName(s.title) === canonical)

    REQUIRED_BUILTIN_MY_SKILLS.forEach((builtin) => {
      const canonical = toCanonicalSkillName(builtin.title)
      const exists =
        hasBuiltinById(builtin.id) || (canonical ? hasBuiltinByCanonical(canonical) : false)
      if (!exists) {
        list.unshift({
          ...builtin,
          createdAt: 0,
        })
      }
    })

    return list.map((s) => {
      if (toCanonicalSkillName(s.title) === BUILTIN_CONTACTS_AND_ASSISTANTS_SKILL_ID) {
        return {
          ...s,
          executionPlatform: 'pc' as const,
          originalPurpose: s.originalPurpose || BUILTIN_CONTACTS_AND_ASSISTANTS_SKILL.originalPurpose,
          steps: s.steps && s.steps.length > 0 ? s.steps : BUILTIN_CONTACTS_AND_ASSISTANTS_SKILL.steps,
        }
      }
      if (toCanonicalSkillName(s.title) === BUILTIN_CREATE_GROUP_SKILL_ID) {
        return {
          ...s,
          executionPlatform: 'pc' as const,
          originalPurpose: s.originalPurpose || BUILTIN_CREATE_GROUP_SKILL.originalPurpose,
          steps: s.steps && s.steps.length > 0 ? s.steps : BUILTIN_CREATE_GROUP_SKILL.steps,
        }
      }
      return s
    })
  }, [mySkills, serviceInstalledSkills])

  const searchQuery = search.trim()
  const filteredMySkills = searchQuery
    ? displayedMySkills.filter(
        (s) =>
          getSkillDisplayName(s.title).toLowerCase().includes(searchQuery.toLowerCase()) ||
          s.title.toLowerCase().includes(searchQuery.toLowerCase()) ||
          (s.originalPurpose ?? '').toLowerCase().includes(searchQuery.toLowerCase())
      )
    : displayedMySkills

  return (
    <div className="skills-view">
      <div className="skills-nav">
        <button
          type="button"
          className={`skills-nav-btn ${activeTab === 'my' ? 'active' : ''}`}
          onClick={() => setActiveTab('my')}
        >
          我的技能
        </button>
        <button
          type="button"
          className={`skills-nav-btn ${activeTab === 'community' ? 'active' : ''}`}
          onClick={() => setActiveTab('community')}
        >
          技能社区
        </button>
        <div className={`skills-nav-indicator ${activeTab === 'community' ? 'right' : 'left'}`} />
      </div>

      <div className="skills-toolbar">
        {activeTab === 'my' && (
          <>
            <button
              type="button"
              className="skills-create-btn"
              onClick={handleCreateNew}
            >
              + 新建技能
            </button>
            <button
              type="button"
              className="skills-schedule-list-btn"
              onClick={() => setScheduleListOpen(true)}
            >
              ⏰ 定时任务
            </button>
            <button
              type="button"
              className="skills-refresh-btn"
              onClick={handleRefreshPublicHub}
              disabled={syncing}
            >
              {syncing ? '同步中...' : '🔄 刷新'}
            </button>
            <button
              type="button"
              className="skills-update-btn"
              onClick={handleUpdatePublicHub}
              disabled={updating}
            >
              {updating ? '更新中...' : '⬆️ 更新技能'}
            </button>
            <button
              type="button"
              className="skills-refresh-btn"
              onClick={handleCompactSkillCache}
              disabled={compacting}
              title="清理重复与历史冗余缓存"
            >
              {compacting ? '清理中...' : '🧹 清理缓存'}
            </button>
          </>
        )}
        {activeTab === 'community' && (
          <div className="skills-toolbar-community">
            <div className="skills-toolbar-main">
            {activeSearchKeyword && (
              <div className="search-tag">
                <span className="search-tag-keyword">{activeSearchKeyword}</span>
                <span className="search-tag-sources">
                  {activeSearchSources.has('tophub') && <span className="search-tag-source">TopHub</span>}
                  {activeSearchSources.has('publichub') && <span className="search-tag-source">PublicHub</span>}
                  {activeSearchSources.has('plaza') && <span className="search-tag-source">技能广场</span>}
                </span>
                <button type="button" className="search-tag-close" onClick={handleCloseSearchTag}>×</button>
              </div>
            )}
            
            <button
              type="button"
              className="skills-search-btn"
              onClick={handleOpenSearchModal}
            >
              🔍 搜索
            </button>
            
            <button
              type="button"
              className={`skills-filter-btn ${sourceFilter === 'tophub' ? 'active' : ''}`}
              onClick={handleCommunityTopHubFilterClick}
            >
              TopHub
            </button>
            <button
              type="button"
              className={`skills-filter-btn ${sourceFilter === 'publichub' ? 'active' : ''}`}
              onClick={handleCommunityPublicHubFilterClick}
            >
              PublicHub
            </button>
            <button
              type="button"
              className={`skills-filter-btn ${sourceFilter === 'plaza' ? 'active' : ''}`}
              onClick={handleCommunityPlazaFilterClick}
            >
              技能广场
            </button>
            <button
              type="button"
              className="skills-refresh-btn"
              onClick={handleRefreshCommunity}
              disabled={loading}
            >
              {loading ? '刷新中...' : '🔄 刷新'}
            </button>
            </div>
            <button
              type="button"
              className="skills-mirror-settings-btn"
              onClick={handleOpenPublicHubMirrorModal}
              aria-label="PublicHub 镜像设置"
              title="PublicHub 镜像"
            >
              ⚙
            </button>
          </div>
        )}
      </div>

      <div className="skills-content">
        {activeTab === 'my' && (
          <div className="skills-list">
            {filteredMySkills.length === 0 ? (
              <div className="skills-empty">
                {searchQuery ? '未找到匹配的技能' : '暂无技能，可从技能社区收藏后显示在此'}
              </div>
            ) : (
              filteredMySkills.map((skill) => (
                <SkillCard
                  key={skill.id}
                  skill={skill}
                  variant="my"
                  onDelete={isBuiltinDefaultSkill(skill) ? undefined : handleDeleteMySkill}
                  onExecute={handleExecute}
                  onShare={(s) => { setSharingSkill(s); setShareTarget(null); setSelectedFriendImeis([]) }}
                  onCardClick={(s) => { setDetailSkill(s); setDetailSource('my'); }}
                />
              ))
            )}
          </div>
        )}

        {activeTab === 'community' && (
          <div className="skills-list">
            {loading && communitySkills.length === 0 ? (
              <div className="skills-empty">加载中…</div>
            ) : communitySkills.length === 0 ? (
              <div className="skills-empty">
                {activeSearchKeyword ? '未找到匹配的技能' : '暂无可显示技能，可点击「刷新」或「搜索」'}
              </div>
            ) : (
              communitySkills.map((skill) => (
                <SkillCard
                  key={skill.id}
                  skill={skill}
                  variant="community"
                  onCollect={handleCollect}
                  onCardClick={(s) => { setDetailSkill(s); setDetailSource('community'); }}
                  isCollected={isCollected(skill.title)}
                />
              ))
            )}
          </div>
        )}
      </div>

      {detailSkill && (() => {
        const isServerSkill = isSkillSyncedFromService(detailSkill)

        return (
          <SkillDetailModal
            skill={detailSkill}
            onClose={() => setDetailSkill(null)}
            onCollect={detailSource === 'community' ? handleCollectFromDetail : undefined}
            onDelete={detailSource === 'my' && !isBuiltinDefaultSkill(detailSkill) ? handleDeleteFromDetail : undefined}
            onSchedule={detailSource === 'my' ? (s) => setScheduleSettingSkill(s) : undefined}
            onEdit={detailSource === 'my' && canEditMySkill(detailSkill, isServerSkill) ? (s) => { setEditSkill(s); setEditIsCreate(false); } : undefined}
            onExecute={detailSource === 'my' && !isServerSkill ? handleExecute : undefined}
            isCollected={isCollected(detailSkill.title)}
          />
        )
      })()}

      {editSkill && (
        <SkillEditModal
          skill={editSkill}
          createMode={editIsCreate}
          onClose={() => { setEditSkill(null); setEditIsCreate(false); }}
          onSave={handleEditSave}
          onSchedule={(s) => setScheduleSettingSkill(s)}
          onScheduleChange={handleEditScheduleChange}
        />
      )}

      {scheduleSettingSkill && (
        <SkillScheduleSettingModal
          skill={scheduleSettingSkill}
          onClose={() => setScheduleSettingSkill(null)}
          onSave={(config) => handleScheduleSave(scheduleSettingSkill, config)}
        />
      )}

      {scheduleListOpen && (
        <SkillScheduleListModal onClose={() => setScheduleListOpen(false)} />
      )}

      {publicHubMirrorModalOpen && (
        <div className="search-modal-overlay" onClick={() => setPublicHubMirrorModalOpen(false)}>
          <div className="search-modal publichub-mirror-modal" onClick={(e) => e.stopPropagation()}>
            <div className="search-modal-header">
              <h3>PublicHub 镜像</h3>
              <button type="button" className="search-modal-close" onClick={() => setPublicHubMirrorModalOpen(false)}>
                ×
              </button>
            </div>
            <div className="search-modal-body">
              <p className="publichub-mirror-hint">选择 PublicHub 列表与详情链接使用的数据源（与本地 Skills 服务无关）。</p>
              <div className="publichub-mirror-options">
                {PUBLIC_HUB_MIRROR_OPTIONS.map((opt) => (
                  <label key={opt.id} className="publichub-mirror-option">
                    <input
                      type="radio"
                      name="publichub-mirror"
                      checked={mirrorDraft === opt.id}
                      onChange={() => setMirrorDraft(opt.id)}
                    />
                    <span>{opt.label}</span>
                  </label>
                ))}
              </div>
              <button type="button" className="search-modal-submit" onClick={handleSavePublicHubMirror}>
                保存
              </button>
            </div>
          </div>
        </div>
      )}

      {/* 搜索弹窗 */}
      {searchModalOpen && (
        <div className="search-modal-overlay" onClick={() => setSearchModalOpen(false)}>
          <div className="search-modal" onClick={(e) => e.stopPropagation()}>
            <div className="search-modal-header">
              <h3>搜索技能</h3>
              <button className="search-modal-close" onClick={() => setSearchModalOpen(false)}>×</button>
            </div>
            
            <div className="search-modal-body">
              <input
                type="text"
                className="search-modal-input"
                placeholder="输入搜索关键词..."
                value={searchKeyword}
                onChange={(e) => setSearchKeyword(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleSubmitSearch()}
                autoFocus
              />
              
              <div className="search-modal-sources">
                <label>选择数据源</label>
                <button
                  type="button"
                  className={`search-source-btn ${searchSources.has('tophub') ? 'active' : ''}`}
                  onClick={() => handleToggleSearchSource('tophub')}
                >
                  TopHub
                </button>
                <button
                  type="button"
                  className={`search-source-btn ${searchSources.has('publichub') ? 'active' : ''}`}
                  onClick={() => handleToggleSearchSource('publichub')}
                >
                  PublicHub
                </button>
                <button
                  type="button"
                  className={`search-source-btn ${searchSources.has('plaza') ? 'active' : ''}`}
                  onClick={() => handleToggleSearchSource('plaza')}
                >
                  技能广场
                </button>
              </div>
              
              <button type="button" className="search-modal-submit" onClick={handleSubmitSearch}>
                搜索
              </button>
            </div>
          </div>
        </div>
      )}

      {sharingSkill && !shareTarget && (
        <div className="search-modal-overlay" onClick={resetShareState}>
          <div className="search-modal skill-share-modal" onClick={(e) => e.stopPropagation()}>
            <div className="search-modal-header">
              <h3>分享技能</h3>
              <button className="search-modal-close" onClick={resetShareState}>×</button>
            </div>
            <div className="search-modal-body">
              <div className="skill-share-skill-name">{getSkillDisplayName(sharingSkill.title)}</div>
              <button type="button" className="search-modal-submit" onClick={() => void handleOpenShareTarget('friend')}>
                分享给好友
              </button>
              <button type="button" className="search-modal-submit skill-share-submit-secondary" onClick={() => void handleOpenShareTarget('group')}>
                分享到群组
              </button>
              <button type="button" className="search-modal-submit skill-share-submit-secondary" onClick={() => void handleOpenShareTarget('community')}>
                分享到技能社区
              </button>
            </div>
          </div>
        </div>
      )}

      {sharingSkill && shareTarget === 'friend' && (
        <div className="search-modal-overlay" onClick={resetShareState}>
          <div className="search-modal skill-share-modal" onClick={(e) => e.stopPropagation()}>
            <div className="search-modal-header">
              <h3>分享给好友</h3>
              <button className="search-modal-close" onClick={resetShareState}>×</button>
            </div>
            <div className="search-modal-body">
              <div className="skill-share-skill-name">{getSkillDisplayName(sharingSkill.title)}</div>
              <div className="skill-share-friends-list">
                {shareLoadingFriends ? (
                  <div className="skills-empty">加载中…</div>
                ) : shareFriends.length === 0 ? (
                  <div className="skills-empty">暂无可分享的好友</div>
                ) : (
                  shareFriends.map((f) => (
                    <label key={f.imei} className="skill-share-friend-item">
                      <input
                        type="checkbox"
                        checked={selectedFriendImeis.includes(f.imei)}
                        onChange={(e) => {
                          const checked = e.target.checked
                          setSelectedFriendImeis((prev) => checked ? [...prev, f.imei] : prev.filter((x) => x !== f.imei))
                        }}
                      />
                      <span>{f.nickname || f.imei}</span>
                    </label>
                  ))
                )}
              </div>
              <button type="button" className="search-modal-submit" disabled={shareSending} onClick={() => void handleSendSkillShareToFriends()}>
                {shareSending ? '发送中...' : '发送技能包'}
              </button>
            </div>
          </div>
        </div>
      )}
      {sharingSkill && shareTarget === 'group' && (
        <div className="search-modal-overlay" onClick={resetShareState}>
          <div className="search-modal skill-share-modal" onClick={(e) => e.stopPropagation()}>
            <div className="search-modal-header">
              <h3>分享到群组</h3>
              <button className="search-modal-close" onClick={resetShareState}>×</button>
            </div>
            <div className="search-modal-body">
              <div className="skill-share-skill-name">{getSkillDisplayName(sharingSkill.title)}</div>
              <div className="skill-share-friends-list">
                {shareLoadingGroups ? (
                  <div className="skills-empty">加载中…</div>
                ) : shareGroups.length === 0 ? (
                  <div className="skills-empty">暂无可分享的群组</div>
                ) : (
                  shareGroups.map((g) => (
                    <label key={g.group_id} className="skill-share-friend-item">
                      <input
                        type="checkbox"
                        checked={selectedGroupIds.includes(g.group_id)}
                        onChange={(e) => {
                          const checked = e.target.checked
                          setSelectedGroupIds((prev) => checked ? [...prev, g.group_id] : prev.filter((x) => x !== g.group_id))
                        }}
                      />
                      <span>{g.name || g.group_id}</span>
                    </label>
                  ))
                )}
              </div>
              <button type="button" className="search-modal-submit" disabled={shareSending} onClick={() => void handleSendSkillShareToGroups()}>
                {shareSending ? '发送中...' : '发送技能包'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Toast 提示 */}
      {toastMessage && (
        <div className="skills-toast">{toastMessage}</div>
      )}
    </div>
  )
}
