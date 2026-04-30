import { useMemo, useState, useEffect, useRef, useCallback } from 'react'
import html2canvas from 'html2canvas'
import ReactMarkdown, { defaultUrlTransform } from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { createJob } from '../services/scheduledJobService'
import { addQuickNote, deleteQuickNote, loadQuickNotes, updateQuickNote, QUICK_NOTES_CHANGED_EVENT } from '../services/quickNotesStorage'
import type { QuickNoteItem } from '../types/quickNote'
import { sendChatAssistantMessageStream, sendCrossDeviceMessage } from '../services/api'
import { getImei } from '../services/storage'
import {
  getBuiltinModelProfiles,
  getDefaultBuiltinUrl,
  saveBuiltinModelProfiles,
  type BuiltinModelProfileRow,
} from '../services/builtinAssistantConfig'
import { setLlmProviderViaPool } from '../services/chatWebSocketPool'
import { DEFAULT_TOPOCLAW_ASSISTANT_ID } from '../services/customAssistants'
import { ChatImageLightbox, type ImageLightboxPayload } from './ChatInlineImage'
import { QuickNoteRichComposerModal } from './QuickNoteRichComposerModal'
import {
  buildEditorInitialHtml,
  getNoteFirstImagePayload,
  getNotePlainText,
  getNotePrimaryImageDataUrl,
  normalizeBodyHtmlForStorage,
  noteUsesStandaloneImageSlot,
  sanitizeQuickNoteHtml,
} from '../utils/quickNoteRich'
import './QuickNotesView.css'

interface QuickNotesViewProps {
  search?: string
  refreshKey?: number
}

type QuickContextAction = 'copy' | 'selectAll' | 'multiSelect' | 'forward' | 'summarize'

interface QuickContextMenuState {
  noteId: string
  x: number
  y: number
}

function formatDateTime(ms: number): string {
  const d = new Date(ms)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  const ss = String(d.getSeconds()).padStart(2, '0')
  return `${y}-${m}-${day} ${hh}:${mm}:${ss}`
}

function toDatetimeLocalInput(ms: number): string {
  const d = new Date(ms)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  return `${y}-${m}-${day}T${hh}:${mm}`
}

function buildReminderMessage(note: QuickNoteItem): string {
  const text = getNotePlainText(note)
  const preview = text ? (text.length > 80 ? `${text.slice(0, 80)}...` : text) : '请查看随手记中的图片或文档记录'
  return `随手记提醒：${preview}`
}

function buildMemoryAppendBlock(note: QuickNoteItem): string {
  const lines: string[] = []
  lines.push(`### 随手记 ${formatDateTime(note.createdAt)}`)
  if (note.sourceChatLabel) lines.push(`- 会话：${note.sourceChatLabel}`)
  if (note.sourceSender) lines.push(`- 发送者：${note.sourceSender}`)
  if (note.sourceMessageAt) lines.push(`- 聊天时间：${formatDateTime(note.sourceMessageAt)}`)
  const plain = getNotePlainText(note)
  if (plain) {
    lines.push(`- 文本：${plain}`)
  }
  if (note.bodyHtml) {
    lines.push(`- 富文本：已保存排版（HTML）`)
  }
  {
    const img = getNoteFirstImagePayload(note)
    if (img) lines.push(`- 图片：${img.name || '截图'}（来源：随手记）`)
  }
  lines.push(`- 记录ID：${note.id}`)
  return lines.join('\n')
}

function buildForwardText(note: QuickNoteItem): string {
  const text = getNotePlainText(note)
  const base: string[] = []
  if (note.sourceChatLabel) base.push(`会话：${note.sourceChatLabel}`)
  if (note.sourceSender) base.push(`发送者：${note.sourceSender}`)
  if (note.sourceMessageAt) base.push(`聊天时间：${formatDateTime(note.sourceMessageAt)}`)
  base.push(`[随手记 保存于 ${formatDateTime(note.createdAt)}]`)
  if (text) base.push(text)
  if (note.bodyHtml && !text) base.push('[富文本随手记，请在电脑端查看完整排版]')
  {
    const img = getNoteFirstImagePayload(note)
    if (img) base.push(`[含图片] ${img.name}`)
  }
  return base.join('\n')
}

function wrapTextByWidth(ctx: CanvasRenderingContext2D, text: string, maxWidth: number): string[] {
  const out: string[] = []
  const raw = text.split('\n')
  for (const row of raw) {
    const line = row.trim()
    if (!line) {
      out.push('')
      continue
    }
    let cur = ''
    for (const ch of line) {
      const next = cur + ch
      if (ctx.measureText(next).width > maxWidth && cur) {
        out.push(cur)
        cur = ch
      } else {
        cur = next
      }
    }
    if (cur) out.push(cur)
  }
  return out.length > 0 ? out : ['']
}

function loadImage(dataUrl: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => resolve(img)
    img.onerror = () => reject(new Error('image load failed'))
    img.src = dataUrl
  })
}

function triggerBlobDownload(fileName: string, blob: Blob): void {
  const a = document.createElement('a')
  a.href = URL.createObjectURL(blob)
  a.download = fileName
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(a.href)
}

function summaryMarkdownUrlTransform(value: string): string {
  const v = String(value || '')
  if (v.startsWith('topoclaw-scheduled-job:')) return v
  return defaultUrlTransform(v)
}

export function QuickNotesView({ search = '', refreshKey = 0 }: QuickNotesViewProps) {
  const [notes, setNotes] = useState<QuickNoteItem[]>(() => loadQuickNotes())
  const [localSearchOpen, setLocalSearchOpen] = useState(false)
  const [localSearch, setLocalSearch] = useState('')
  const [showFavoritesOnly, setShowFavoritesOnly] = useState(false)
  const [composer, setComposer] = useState<{
    open: boolean
    editingId: string | null
    initialHtml: string
  }>({ open: false, editingId: null, initialHtml: '' })
  const [reminderTarget, setReminderTarget] = useState<QuickNoteItem | null>(null)
  const [reminderAt, setReminderAt] = useState(() => toDatetimeLocalInput(Date.now() + 60 * 60 * 1000))
  const [savingReminder, setSavingReminder] = useState(false)
  const [savingMemoryId, setSavingMemoryId] = useState<string | null>(null)
  const [contextMenu, setContextMenu] = useState<QuickContextMenuState | null>(null)
  const [multiSelectMode, setMultiSelectMode] = useState(false)
  const [selectedNoteIds, setSelectedNoteIds] = useState<Set<string>>(new Set())
  const [summaryLoading, setSummaryLoading] = useState(false)
  const [summaryText, setSummaryText] = useState('')
  const [summaryImageItems, setSummaryImageItems] = useState<Array<{ base64: string; mime: string; name: string }>>([])
  const [summaryRequestOpen, setSummaryRequestOpen] = useState(false)
  const [summaryHint, setSummaryHint] = useState('')
  const [summaryResultOpen, setSummaryResultOpen] = useState(false)
  const pendingSummaryNotesRef = useRef<QuickNoteItem[]>([])
  const summaryMdRef = useRef<HTMLDivElement | null>(null)
  const [forwarding, setForwarding] = useState(false)
  const [summaryImageExporting, setSummaryImageExporting] = useState(false)
  const [modelProfiles, setModelProfiles] = useState<BuiltinModelProfileRow[]>([])
  const [modelValue, setModelValue] = useState('')
  const [modelSwitching, setModelSwitching] = useState(false)
  const [modelErr, setModelErr] = useState('')
  const noteTextRefs = useRef<Record<string, HTMLDivElement | null>>({})
  const [noteImageLightbox, setNoteImageLightbox] = useState<ImageLightboxPayload | null>(null)

  useEffect(() => {
    setNotes(loadQuickNotes())
  }, [refreshKey])

  useEffect(() => {
    const onChange = () => setNotes(loadQuickNotes())
    window.addEventListener(QUICK_NOTES_CHANGED_EVENT, onChange)
    return () => window.removeEventListener(QUICK_NOTES_CHANGED_EVENT, onChange)
  }, [])

  useEffect(() => {
    let cancelled = false
    void getBuiltinModelProfiles().then((r) => {
      if (cancelled) return
      if (!r.ok) {
        setModelProfiles([])
        return
      }
      setModelProfiles(r.nonGuiProfiles)
      setModelValue(r.activeNonGuiModel)
      setModelErr('')
    })
    return () => {
      cancelled = true
    }
  }, [])

  useEffect(() => {
    const close = () => setContextMenu(null)
    window.addEventListener('click', close)
    window.addEventListener('scroll', close, true)
    return () => {
      window.removeEventListener('click', close)
      window.removeEventListener('scroll', close, true)
    }
  }, [])

  const filtered = useMemo(() => {
    const q = `${search} ${localSearch}`.trim().toLowerCase()
    const base = showFavoritesOnly ? notes.filter((n) => !!n.favorite) : notes
    if (!q) return base
    return base.filter((n) => {
      const text = getNotePlainText(n).toLowerCase()
      const htmlBlob = (n.bodyHtml || '').toLowerCase()
      const imageName = (n.imageName || '').toLowerCase()
      const srcChat = (n.sourceChatLabel || '').toLowerCase()
      const srcSender = (n.sourceSender || '').toLowerCase()
      return (
        text.includes(q) ||
        htmlBlob.includes(q) ||
        imageName.includes(q) ||
        srcChat.includes(q) ||
        srcSender.includes(q)
      )
    })
  }, [notes, search, localSearch, showFavoritesOnly])

  const selectedNotes = useMemo(() => {
    if (selectedNoteIds.size === 0) return []
    return notes.filter((x) => selectedNoteIds.has(x.id))
  }, [notes, selectedNoteIds])

  const toggleSelected = (id: string) => {
    setSelectedNoteIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const exitMultiSelect = () => {
    setMultiSelectMode(false)
    setSelectedNoteIds(new Set())
  }

  const handleDelete = (id: string) => {
    const ok = window.confirm('确定删除这条随手记吗？')
    if (!ok) return
    deleteQuickNote(id)
    setNotes(loadQuickNotes())
  }

  const handleCopyContent = async (note: QuickNoteItem) => {
    const content = getNotePlainText(note).trim()
    if (!content) {
      window.alert('该随手记没有可复制的文本内容')
      return
    }
    try {
      await navigator.clipboard.writeText(content)
    } catch {
      window.alert('复制失败')
    }
  }

  const handleToggleFavorite = (note: QuickNoteItem) => {
    const nextFavorite = !note.favorite
    updateQuickNote(note.id, {
      favorite: nextFavorite,
      favoritedAt: nextFavorite ? Date.now() : undefined,
    })
    setNotes(loadQuickNotes())
  }

  const handleOpenReminder = (note: QuickNoteItem) => {
    setReminderTarget(note)
    setReminderAt(toDatetimeLocalInput(Date.now() + 60 * 60 * 1000))
  }

  const handleSaveReminder = async () => {
    if (!reminderTarget) return
    const atMs = new Date(reminderAt).getTime()
    if (!Number.isFinite(atMs) || atMs <= Date.now()) {
      window.alert('提醒时间需晚于当前时间')
      return
    }
    setSavingReminder(true)
    try {
      const { job } = await createJob({
        name: '随手记提醒',
        message: buildReminderMessage(reminderTarget),
        at: new Date(atMs).toISOString(),
        delete_after_run: true,
      })
      updateQuickNote(reminderTarget.id, { reminderJobId: job.id })
      setNotes(loadQuickNotes())
      setReminderTarget(null)
      window.alert('提醒已创建')
    } catch (e) {
      window.alert(`创建提醒失败：${e instanceof Error ? e.message : String(e)}`)
    } finally {
      setSavingReminder(false)
    }
  }

  const handleModelChange = async (value: string) => {
    if (!value || value === modelValue) return
    setModelSwitching(true)
    setModelErr('')
    try {
      const profile = modelProfiles.find((p) => p.model === value)
      if (!profile) {
        setModelErr(`未找到模型配置：${value}`)
        return
      }
      const saveRes = await saveBuiltinModelProfiles({ activeNonGuiModel: value })
      if (!saveRes.ok) {
        setModelErr(saveRes.error || '保存模型失败')
        return
      }
      const baseUrl = await getDefaultBuiltinUrl('topoclaw')
      const hot = await setLlmProviderViaPool(baseUrl, {
        model: profile.model,
        api_base: profile.apiBase,
        api_key: profile.apiKey,
      })
      if (!hot.ok) {
        const detail = hot.errors.map((e) => `${e.agent_id}: ${e.error}`).join('; ')
        setModelErr(hot.reason || detail || '模型热切换失败')
        return
      }
      setModelValue(value)
    } catch (e) {
      setModelErr(e instanceof Error ? e.message : String(e))
    } finally {
      setModelSwitching(false)
    }
  }

  const forwardNotes = async (targetNotes: QuickNoteItem[]) => {
    if (targetNotes.length === 0) return
    const imei = getImei()
    if (!imei) {
      window.alert('请先绑定设备')
      return
    }
    setForwarding(true)
    try {
      if (targetNotes.length === 1) {
        const one = targetNotes[0]
        const fwdImg = getNoteFirstImagePayload(one)
        const res = await sendCrossDeviceMessage(imei, buildForwardText(one), {
          imageBase64: fwdImg?.base64,
          file_name: fwdImg?.name,
        })
        if (!res.success) throw new Error('转发失败')
      } else {
        const posterB64 = await generatePosterFromNotes(targetNotes)
        const res = await sendCrossDeviceMessage(imei, `随手记合并转发（${targetNotes.length} 条）`, {
          imageBase64: posterB64,
          file_name: `quick-notes-${Date.now()}.png`,
        })
        if (!res.success) throw new Error('转发失败')
      }
      window.alert('已转发到跨设备通道')
    } catch (e) {
      window.alert(`转发失败：${e instanceof Error ? e.message : String(e)}`)
    } finally {
      setForwarding(false)
    }
  }

  const summarizeNotes = useCallback(async (targetNotes: QuickNoteItem[], userHint?: string) => {
    if (targetNotes.length === 0) return
    setSummaryLoading(true)
    setSummaryText('')
    try {
      const baseUrl = await getDefaultBuiltinUrl('topoclaw')
      const threadId = `quick_note_summary_${Date.now()}`
      const imagePayloads = targetNotes.map((x) => getNoteFirstImagePayload(x)).filter(Boolean) as {
        base64: string
        mime: string
        name: string
      }[]
      const images = imagePayloads.slice(0, 3).map((p) => p.base64)
      const hasMoreImages = imagePayloads.length > images.length
      const joined = targetNotes.map((n, i) => {
        const text = getNotePlainText(n).trim()
        const richTag = n.bodyHtml ? '[含富文本]' : ''
        const ip = getNoteFirstImagePayload(n)
        const imageTag = ip ? `[含图片:${ip.name}]` : '[无图片]'
        const line = text || (n.bodyHtml && /<img/i.test(n.bodyHtml) ? '(正文含内嵌图片)' : '(无文字)')
        return `${i + 1}. 时间: ${formatDateTime(n.createdAt)} ${imageTag}${richTag}\n${line}`
      }).join('\n\n')
      const hintBlock =
        userHint && userHint.trim()
          ? `\n\n用户对摘要的额外要求（请尽量满足）：\n${userHint.trim()}\n`
          : ''
      const prompt = [
        '请总结以下随手记内容，使用 Markdown 格式输出（含适当标题、列表等）：',
        '- 先给 3-5 条关键结论',
        '- 再给「行动建议」',
        '- 若提供了图片，请结合图片信息在文中明确引用「图片要点」',
        hasMoreImages ? `- 注意：原始记录图片超过 3 张，当前仅附带前 3 张。` : '',
        hintBlock,
        '',
        joined,
      ]
        .filter(Boolean)
        .join('\n')
      const { fullText } = await sendChatAssistantMessageStream(
        {
          uuid: threadId,
          query: prompt,
          images,
          imei: getImei() || '',
        },
        baseUrl,
        () => {},
        undefined,
        undefined,
        undefined,
        undefined,
        DEFAULT_TOPOCLAW_ASSISTANT_ID,
      )
      setSummaryText((fullText || '').trim() || '未生成摘要')
      setSummaryImageItems(
        targetNotes
          .map((x) => getNoteFirstImagePayload(x))
          .filter(Boolean)
          .slice(0, 3)
          .map((p) => ({
            base64: p!.base64,
            mime: p!.mime,
            name: p!.name,
          }))
      )
    } catch (e) {
      setSummaryText(`总结失败：${e instanceof Error ? e.message : String(e)}`)
      setSummaryImageItems([])
    } finally {
      setSummaryLoading(false)
    }
  }, [])

  const openSummaryRequest = useCallback((targetNotes: QuickNoteItem[]) => {
    if (targetNotes.length === 0) return
    pendingSummaryNotesRef.current = targetNotes
    setSummaryHint('')
    setSummaryRequestOpen(true)
  }, [])

  const confirmSummaryRequest = useCallback(() => {
    const notes = pendingSummaryNotesRef.current
    setSummaryRequestOpen(false)
    setSummaryResultOpen(true)
    void summarizeNotes(notes, summaryHint.trim() || undefined)
  }, [summarizeNotes, summaryHint])

  const handleSummaryCopy = useCallback(async () => {
    const t = summaryText.trim()
    if (!t) return
    try {
      await navigator.clipboard.writeText(t)
    } catch {
      window.alert('复制失败')
    }
  }, [summaryText])

  const handleSummarySaveMd = useCallback(async () => {
    const t = summaryText.trim()
    if (!t) return
    const defaultFileName = `随手记摘要-${formatDateTime(Date.now()).replace(/:/g, '-')}.md`
    const api = window.electronAPI?.saveTextAs
    if (api) {
      const r = await api(t, defaultFileName)
      if (!r.ok && !r.canceled && r.error) window.alert(`保存失败：${r.error}`)
      return
    }
    triggerBlobDownload(defaultFileName, new Blob([t], { type: 'text/markdown;charset=utf-8' }))
  }, [summaryText])

  const handleSummaryForward = useCallback(async () => {
    const t = summaryText.trim()
    if (!t) return
    const imei = getImei()
    if (!imei) {
      window.alert('请先绑定设备')
      return
    }
    setForwarding(true)
    try {
      const body = `【随手记摘要】\n\n${t}`
      const res = await sendCrossDeviceMessage(imei, body, {})
      if (!res.success) throw new Error('转发失败')
      window.alert('已转发到跨设备通道')
    } catch (e) {
      window.alert(`转发失败：${e instanceof Error ? e.message : String(e)}`)
    } finally {
      setForwarding(false)
    }
  }, [summaryText])

  const handleSummarySaveImage = useCallback(async () => {
    const el = summaryMdRef.current
    if (!el || !summaryText.trim()) return
    setSummaryImageExporting(true)
    try {
      const canvas = await html2canvas(el, {
        scale: 2,
        backgroundColor: '#ffffff',
        useCORS: true,
        logging: false,
        windowWidth: el.scrollWidth,
        windowHeight: el.scrollHeight,
      })
      const dataUrl = canvas.toDataURL('image/png')
      const name = `随手记摘要-${formatDateTime(Date.now()).replace(/:/g, '-')}.png`
      const api = window.electronAPI?.saveImageAs
      if (api) {
        const r = await api(dataUrl, name)
        if (!r.ok && !r.canceled && r.error) window.alert(`保存失败：${r.error}`)
        return
      }
      const res = await fetch(dataUrl)
      const blob = await res.blob()
      triggerBlobDownload(name, blob)
    } catch (e) {
      window.alert(`导出图片失败：${e instanceof Error ? e.message : String(e)}`)
    } finally {
      setSummaryImageExporting(false)
    }
  }, [summaryText])

  const generatePosterFromNotes = async (targetNotes: QuickNoteItem[]): Promise<string> => {
    const width = 1080
    const paddingX = 52
    const topPadding = 56
    const bottomPadding = 48
    const lineHeight = 36
    const titleGap = 18
    const imgGap = 18
    const maxImgWidth = width - paddingX * 2
    const maxImgHeight = 320

    const measureCanvas = document.createElement('canvas')
    const measureCtx = measureCanvas.getContext('2d')
    if (!measureCtx) throw new Error('无法创建画布')
    measureCtx.font = '28px "Microsoft YaHei", sans-serif'

    const imageCache = new Map<string, HTMLImageElement>()
    let totalHeight = topPadding + 56

    for (const note of targetNotes) {
      const title = `${formatDateTime(note.createdAt)}`
      totalHeight += lineHeight
      const lines = wrapTextByWidth(measureCtx, getNotePlainText(note).trim() || '(无文字)', width - paddingX * 2)
      totalHeight += lines.length * lineHeight + titleGap
      const posterDataUrl = getNotePrimaryImageDataUrl(note)
      if (posterDataUrl) {
        try {
          const img = await loadImage(posterDataUrl)
          imageCache.set(note.id, img)
          const scale = Math.min(maxImgWidth / img.width, maxImgHeight / img.height, 1)
          totalHeight += img.height * scale + imgGap
        } catch {
          // ignore broken image
        }
      }
      totalHeight += 24
      void title
    }
    totalHeight += bottomPadding

    const canvas = document.createElement('canvas')
    canvas.width = width
    canvas.height = Math.max(320, Math.ceil(totalHeight))
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('无法绘制画布')

    ctx.fillStyle = '#f8fbff'
    ctx.fillRect(0, 0, canvas.width, canvas.height)
    ctx.fillStyle = '#0f172a'
    ctx.font = 'bold 36px "Microsoft YaHei", sans-serif'
    ctx.fillText('随手记汇总', paddingX, topPadding)

    let y = topPadding + 56
    for (const note of targetNotes) {
      ctx.fillStyle = '#2563eb'
      ctx.font = 'bold 24px "Microsoft YaHei", sans-serif'
      ctx.fillText(formatDateTime(note.createdAt), paddingX, y)
      y += lineHeight

      ctx.fillStyle = '#111827'
      ctx.font = '24px "Microsoft YaHei", sans-serif'
      const lines = wrapTextByWidth(ctx, getNotePlainText(note).trim() || '(无文字)', width - paddingX * 2)
      for (const ln of lines) {
        ctx.fillText(ln || ' ', paddingX, y)
        y += lineHeight
      }
      y += titleGap

      const img = imageCache.get(note.id)
      if (img) {
        const scale = Math.min(maxImgWidth / img.width, maxImgHeight / img.height, 1)
        const drawW = img.width * scale
        const drawH = img.height * scale
        ctx.drawImage(img, paddingX, y, drawW, drawH)
        y += drawH + imgGap
      }

      ctx.strokeStyle = '#dbeafe'
      ctx.lineWidth = 2
      ctx.beginPath()
      ctx.moveTo(paddingX, y)
      ctx.lineTo(width - paddingX, y)
      ctx.stroke()
      y += 24
    }

    const dataUrl = canvas.toDataURL('image/png')
    const base64 = dataUrl.split(',', 2)[1] || ''
    if (!base64) throw new Error('生成图片失败')
    return base64
  }

  const handleContextAction = async (action: QuickContextAction) => {
    if (!contextMenu) return
    const note = notes.find((x) => x.id === contextMenu.noteId)
    setContextMenu(null)
    if (!note) return
    if (action === 'copy') {
      const copyText = buildForwardText(note)
      try {
        await navigator.clipboard.writeText(copyText)
      } catch {
        window.alert('复制失败')
      }
      return
    }
    if (action === 'selectAll') {
      const el = noteTextRefs.current[note.id]
      if (!el) return
      const sel = window.getSelection()
      const range = document.createRange()
      range.selectNodeContents(el)
      sel?.removeAllRanges()
      sel?.addRange(range)
      return
    }
    if (action === 'multiSelect') {
      setMultiSelectMode(true)
      setSelectedNoteIds(new Set([note.id]))
      return
    }
    if (action === 'forward') {
      await forwardNotes([note])
      return
    }
    openSummaryRequest([note])
  }

  const handleSaveToMemory = async (note: QuickNoteItem) => {
    const reader = window.electronAPI?.readWorkspaceProfileFile
    const writer = window.electronAPI?.writeWorkspaceProfileFile
    if (!reader || !writer) {
      window.alert('当前环境不支持写入 TopoClaw memory')
      return
    }
    setSavingMemoryId(note.id)
    try {
      const res = await reader('memory')
      if (!res?.ok) {
        window.alert(`读取 TopoClaw memory 失败：${res?.error || '未知错误'}`)
        return
      }
      const current = String(res.content || '').trimEnd()
      const append = buildMemoryAppendBlock(note)
      const next = current ? `${current}\n\n${append}\n` : `${append}\n`
      const saved = await writer('memory', next)
      if (!saved?.ok) {
        window.alert(`写入 TopoClaw memory 失败：${saved?.error || '未知错误'}`)
        return
      }
      updateQuickNote(note.id, { memorySavedAt: Date.now() })
      setNotes(loadQuickNotes())
      window.alert('已写入内置默认 TopoClaw 的 memory')
    } catch (e) {
      window.alert(`写入失败：${e instanceof Error ? e.message : String(e)}`)
    } finally {
      setSavingMemoryId(null)
    }
  }

  return (
    <div className="quick-notes-view">
      <div className="quick-notes-header">
        <div className="quick-notes-header-text">
          <h1 className="quick-notes-title">随手记</h1>
          <p className="quick-notes-desc">按时间浏览记录；点击右下角按钮可新建富文本随手记。支持定时提醒与写入 TopoClaw memory。</p>
        </div>
        <div className="quick-notes-model-toolbar">
          <label htmlFor="quick-notes-model">模型</label>
          <select
            id="quick-notes-model"
            value={modelValue}
            disabled={modelSwitching || modelProfiles.length === 0}
            onChange={(e) => void handleModelChange(e.target.value)}
          >
            {modelProfiles.map((p) => (
              <option key={p.model} value={p.model}>
                {p.model}
              </option>
            ))}
          </select>
          <button
            type="button"
            className="quick-notes-header-btn"
            onClick={() => {
              setLocalSearchOpen((prev) => {
                const next = !prev
                if (!next) setLocalSearch('')
                return next
              })
            }}
            aria-label="搜索随手记"
            title="搜索随手记"
          >
            搜索
          </button>
          <button
            type="button"
            className={`quick-notes-header-btn ${showFavoritesOnly ? 'active' : ''}`}
            onClick={() => setShowFavoritesOnly((prev) => !prev)}
            aria-label="查看收藏"
            title="查看收藏"
          >
            {showFavoritesOnly ? '显示全部' : '查看收藏'}
          </button>
        </div>
      </div>
      {localSearchOpen ? (
        <div className="quick-notes-search-row">
          <input
            type="text"
            className="quick-notes-search-input"
            value={localSearch}
            onChange={(e) => setLocalSearch(e.target.value)}
            placeholder="输入关键字搜索随手记内容"
            autoFocus
          />
        </div>
      ) : null}
      {modelErr ? <div className="quick-notes-banner-error">{modelErr}</div> : null}

      {multiSelectMode && (
        <div className="quick-notes-multi-toolbar">
          <div className="quick-notes-multi-count">已选择 {selectedNoteIds.size} 条</div>
          <div className="quick-notes-multi-actions">
            <button
              type="button"
              onClick={() => void forwardNotes(selectedNotes)}
              disabled={selectedNoteIds.size === 0 || forwarding}
            >
              {forwarding ? '转发中...' : '生成图片并转发'}
            </button>
            <button
              type="button"
              onClick={() => openSummaryRequest(selectedNotes)}
              disabled={selectedNoteIds.size === 0 || summaryLoading || summaryRequestOpen}
            >
              总结
            </button>
            <button type="button" className="ghost" onClick={exitMultiSelect}>
              退出多选
            </button>
          </div>
        </div>
      )}

      <div className="quick-notes-list">
        {filtered.length === 0 ? (
          <div className="quick-notes-empty">暂无随手记</div>
        ) : (
          filtered.map((note) => (
            <div key={note.id} className={`quick-note-timeline-item ${multiSelectMode && selectedNoteIds.has(note.id) ? 'selected' : ''}`}>
              <div className="quick-note-time-axis">
                <div className="quick-note-axis-rail" aria-hidden={true}>
                  <span className="quick-note-axis-dot" />
                  <span className="quick-note-axis-line" />
                </div>
                <div className="quick-note-time-block">
                  <div className="quick-note-time">{formatDateTime(note.createdAt)}</div>
                </div>
              </div>
              <div
                className="quick-note-card"
                onContextMenu={(e) => {
                  e.preventDefault()
                  setContextMenu({ noteId: note.id, x: e.clientX, y: e.clientY })
                }}
                onClick={() => {
                  if (!multiSelectMode) return
                  toggleSelected(note.id)
                }}
              >
                <div className="quick-note-meta">
                  {note.reminderJobId ? <span className="quick-note-badge">已设置提醒</span> : null}
                  {note.memorySavedAt ? <span className="quick-note-badge">已写入 memory</span> : null}
                  {note.bodyHtml ? <span className="quick-note-badge">文档</span> : null}
                  {getNoteFirstImagePayload(note) ? <span className="quick-note-badge">图文</span> : null}
                  {note.favorite ? <span className="quick-note-badge">已收藏</span> : null}
                </div>
                {multiSelectMode ? (
                  <label className="quick-note-multi-check">
                    <input
                      type="checkbox"
                      checked={selectedNoteIds.has(note.id)}
                      onChange={() => toggleSelected(note.id)}
                    />
                    <span>选择</span>
                  </label>
                ) : null}
                {note.sourceChatLabel || note.sourceSender || note.sourceMessageAt ? (
                  <div className="quick-note-source">
                    {note.sourceChatLabel ? (
                      <div className="quick-note-source-row">
                        <span className="quick-note-source-k">会话</span>
                        {note.sourceChatLabel}
                      </div>
                    ) : null}
                    {note.sourceSender ? (
                      <div className="quick-note-source-row">
                        <span className="quick-note-source-k">发送者</span>
                        {note.sourceSender}
                      </div>
                    ) : null}
                    {note.sourceMessageAt ? (
                      <div className="quick-note-source-row">
                        <span className="quick-note-source-k">聊天时间</span>
                        {formatDateTime(note.sourceMessageAt)}
                      </div>
                    ) : null}
                  </div>
                ) : null}
                {note.bodyHtml ? (
                  <div
                    className="quick-note-text quick-note-rich-html"
                    ref={(el) => {
                      noteTextRefs.current[note.id] = el
                    }}
                    onClick={(e) => {
                      const target = e.target as HTMLElement | null
                      const img = target?.closest?.('img') as HTMLImageElement | null
                      if (!img) return
                      e.stopPropagation()
                      if (multiSelectMode) {
                        toggleSelected(note.id)
                        return
                      }
                      const src = String(img.getAttribute('src') || '').trim()
                      if (!src) return
                      setNoteImageLightbox({
                        dataUrl: src,
                        fileName: note.imageName || 'inline.png',
                      })
                    }}
                    dangerouslySetInnerHTML={{ __html: sanitizeQuickNoteHtml(note.bodyHtml) }}
                  />
                ) : note.text ? (
                  <div
                    className="quick-note-text"
                    ref={(el) => {
                      noteTextRefs.current[note.id] = el
                    }}
                  >
                    {note.text}
                  </div>
                ) : null}
                {noteUsesStandaloneImageSlot(note) ? (
                  <div className="quick-note-image-wrap">
                    <img
                      className="quick-note-image quick-note-image-clickable"
                      src={`data:${note.imageMime || 'image/png'};base64,${note.imageBase64}`}
                      alt={note.imageName || '随手记图片'}
                      title={multiSelectMode ? undefined : '点击查看大图'}
                      onClick={(e) => {
                        e.stopPropagation()
                        if (multiSelectMode) {
                          toggleSelected(note.id)
                          return
                        }
                        setNoteImageLightbox({
                          dataUrl: `data:${note.imageMime || 'image/png'};base64,${note.imageBase64}`,
                          fileName: note.imageName,
                        })
                      }}
                    />
                  </div>
                ) : null}
                <div className="quick-note-actions">
                  <button type="button" onClick={() => void handleCopyContent(note)}>复制内容</button>
                  <button type="button" onClick={() => handleToggleFavorite(note)}>
                    {note.favorite ? '取消收藏' : '收藏'}
                  </button>
                  <button
                    type="button"
                    onClick={(e) => {
                      e.stopPropagation()
                      setComposer({
                        open: true,
                        editingId: note.id,
                        initialHtml: buildEditorInitialHtml(note),
                      })
                    }}
                  >
                    编辑
                  </button>
                  <button type="button" onClick={() => handleOpenReminder(note)}>设置提醒</button>
                  <button
                    type="button"
                    onClick={() => void handleSaveToMemory(note)}
                    disabled={!!note.memorySavedAt || savingMemoryId === note.id}
                  >
                    {savingMemoryId === note.id ? '写入中...' : (note.memorySavedAt ? '已写入 memory' : '存入记忆')}
                  </button>
                  <button type="button" className="danger" onClick={() => handleDelete(note.id)}>删除</button>
                </div>
              </div>
            </div>
          ))
        )}
      </div>

      {contextMenu && (
        <div
          className="quick-note-context-menu"
          style={{ left: contextMenu.x, top: contextMenu.y }}
          role="menu"
        >
          <button type="button" onClick={() => void handleContextAction('copy')}>复制</button>
          <button type="button" onClick={() => void handleContextAction('selectAll')}>全选</button>
          <button type="button" onClick={() => void handleContextAction('multiSelect')}>多选</button>
          <button type="button" onClick={() => void handleContextAction('forward')}>转发</button>
          <button type="button" onClick={() => void handleContextAction('summarize')}>总结</button>
        </div>
      )}

      {summaryRequestOpen ? (
        <div
          className="quick-notes-modal-overlay"
          role="presentation"
          onClick={() => setSummaryRequestOpen(false)}
        >
          <div
            className="quick-notes-modal quick-notes-summary-request-modal"
            role="dialog"
            aria-labelledby="quick-notes-summary-request-title"
            onClick={(e) => e.stopPropagation()}
          >
            <h3 id="quick-notes-summary-request-title">总结随手记</h3>
            <p className="quick-notes-modal-desc">输入您的要求（选填）</p>
            <textarea
              className="quick-notes-summary-hint-input"
              value={summaryHint}
              onChange={(e) => setSummaryHint(e.target.value)}
              rows={5}
              placeholder="例如：重点列出待办、用表格归纳、只保留与项目 A 相关的内容…"
            />
            <div className="quick-notes-modal-actions">
              <button type="button" onClick={() => setSummaryRequestOpen(false)}>
                取消
              </button>
              <button type="button" className="primary" onClick={() => confirmSummaryRequest()}>
                总结
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {summaryResultOpen ? (
        <div
          className="quick-notes-modal-overlay quick-notes-summary-result-overlay"
          role="presentation"
          onClick={() => {
            if (!summaryLoading) setSummaryResultOpen(false)
          }}
        >
          <div
            className="quick-notes-summary-result-modal"
            role="dialog"
            aria-labelledby="quick-notes-summary-result-title"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="quick-notes-summary-result-head">
              <h3 id="quick-notes-summary-result-title">随手记摘要</h3>
              <button
                type="button"
                className="quick-notes-summary-close"
                disabled={summaryLoading}
                aria-label="关闭"
                onClick={() => setSummaryResultOpen(false)}
              >
                ×
              </button>
            </div>
            <div className="quick-notes-summary-result-toolbar">
              <button
                type="button"
                onClick={() => void handleSummaryCopy()}
                disabled={summaryLoading || !summaryText.trim()}
              >
                复制
              </button>
              <button
                type="button"
                onClick={() => void handleSummarySaveMd()}
                disabled={summaryLoading || !summaryText.trim()}
              >
                保存到本地
              </button>
              <button
                type="button"
                onClick={() => void handleSummaryForward()}
                disabled={summaryLoading || !summaryText.trim() || forwarding}
              >
                {forwarding ? '转发中…' : '转发给好友'}
              </button>
              <button
                type="button"
                onClick={() => void handleSummarySaveImage()}
                disabled={summaryLoading || summaryImageExporting || !summaryText.trim()}
              >
                {summaryImageExporting ? '导出中…' : '保存为图片'}
              </button>
            </div>
            {summaryImageItems.length > 0 ? (
              <div className="quick-notes-summary-thumb-row">
                {summaryImageItems.map((img, idx) => (
                  <img
                    key={`${img.name}_${idx}`}
                    src={`data:${img.mime};base64,${img.base64}`}
                    alt={img.name}
                  />
                ))}
              </div>
            ) : null}
            <div className="quick-notes-summary-result-body">
              {summaryLoading ? (
                <div className="quick-notes-summary-loading-inline">正在生成摘要…</div>
              ) : null}
              <div ref={summaryMdRef} className="quick-notes-summary-md">
                {!summaryLoading && summaryText ? (
                  <ReactMarkdown remarkPlugins={[remarkGfm]} urlTransform={summaryMarkdownUrlTransform}>
                    {summaryText}
                  </ReactMarkdown>
                ) : null}
              </div>
            </div>
          </div>
        </div>
      ) : null}

      <button
        type="button"
        className="quick-notes-fab"
        title="新建随手记"
        aria-label="新建随手记"
        onClick={() => setComposer({ open: true, editingId: null, initialHtml: '' })}
      >
        <svg className="quick-notes-fab-icon" viewBox="0 0 24 24" aria-hidden>
          <path
            fill="currentColor"
            d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zm14.71-9.21a1 1 0 0 0 0-1.41l-2.34-2.34a1 1 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"
          />
        </svg>
      </button>

      <QuickNoteRichComposerModal
        open={composer.open}
        initialHtml={composer.initialHtml}
        editingNoteId={composer.editingId}
        onClose={() => setComposer({ open: false, editingId: null, initialHtml: '' })}
        onSave={(payload) => {
          const plain = payload.plainText.trim()
          const textLine =
            plain ||
            (payload.firstImage ? '[图文]' : '') ||
            (payload.bodyHtml.trim() ? '[文档]' : '')
          const bodyIn = normalizeBodyHtmlForStorage(payload.bodyHtml)
          if (payload.editNoteId) {
            updateQuickNote(payload.editNoteId, {
              text: textLine,
              bodyHtml: bodyIn,
              ...(bodyIn
                ? { imageBase64: undefined, imageMime: undefined, imageName: undefined }
                : {
                    imageBase64: payload.firstImage?.base64,
                    imageMime: payload.firstImage?.mime,
                    imageName: payload.firstImage?.name,
                  }),
            })
          } else {
            addQuickNote({
              text: textLine,
              bodyHtml: payload.bodyHtml,
              ...(payload.bodyHtml.trim()
                ? {}
                : {
                    imageBase64: payload.firstImage?.base64,
                    imageMime: payload.firstImage?.mime,
                    imageName: payload.firstImage?.name,
                  }),
            })
          }
          setNotes(loadQuickNotes())
        }}
      />

      <ChatImageLightbox payload={noteImageLightbox} onClose={() => setNoteImageLightbox(null)} />

      {reminderTarget && (
        <div className="quick-notes-modal-overlay" onClick={() => setReminderTarget(null)}>
          <div className="quick-notes-modal" onClick={(e) => e.stopPropagation()}>
            <h3>设置随手记提醒</h3>
            <p className="quick-notes-modal-desc">{buildReminderMessage(reminderTarget)}</p>
            <input
              type="datetime-local"
              className="quick-notes-modal-input"
              value={reminderAt}
              onChange={(e) => setReminderAt(e.target.value)}
            />
            <div className="quick-notes-modal-actions">
              <button type="button" onClick={() => setReminderTarget(null)} disabled={savingReminder}>取消</button>
              <button type="button" onClick={() => void handleSaveReminder()} disabled={savingReminder}>
                {savingReminder ? '保存中...' : '保存提醒'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}
