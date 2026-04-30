import type { QuickNoteCreateInput, QuickNoteItem } from '../types/quickNote'

const QUICK_NOTE_STORAGE_KEY = 'topodesktop_quick_notes_v1'

/** 列表变更时派发，随手记面板等可据此从 localStorage 重新加载 */
export const QUICK_NOTES_CHANGED_EVENT = 'topodesktop-quick-notes-changed'

function emitQuickNotesChanged(): void {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new CustomEvent(QUICK_NOTES_CHANGED_EVENT))
}

function canUseStorage(): boolean {
  return typeof window !== 'undefined' && !!window.localStorage
}

function normalizeQuickNote(raw: unknown): QuickNoteItem | null {
  if (!raw || typeof raw !== 'object') return null
  const rec = raw as Record<string, unknown>
  const id = String(rec.id || '').trim()
  if (!id) return null
  const text = String(rec.text || '')
  const createdAt = Number(rec.createdAt || 0)
  const updatedAt = Number(rec.updatedAt || createdAt || 0)
  if (!Number.isFinite(createdAt) || createdAt <= 0) return null
  const sourceMessageAt = Number(rec.sourceMessageAt)
  const bodyHtmlRaw = typeof rec.bodyHtml === 'string' ? rec.bodyHtml : ''
  const bodyHtml =
    bodyHtmlRaw.includes('<') && bodyHtmlRaw.trim().length > 10 ? bodyHtmlRaw.trim() : undefined
  const favoritedAt = Number(rec.favoritedAt)
  return {
    id,
    text,
    bodyHtml,
    imageBase64: typeof rec.imageBase64 === 'string' && rec.imageBase64.length > 20 ? rec.imageBase64 : undefined,
    imageMime: typeof rec.imageMime === 'string' ? rec.imageMime : undefined,
    imageName: typeof rec.imageName === 'string' ? rec.imageName : undefined,
    createdAt,
    updatedAt: Number.isFinite(updatedAt) && updatedAt > 0 ? updatedAt : createdAt,
    reminderJobId: typeof rec.reminderJobId === 'string' ? rec.reminderJobId : undefined,
    memorySavedAt: Number.isFinite(Number(rec.memorySavedAt)) ? Number(rec.memorySavedAt) : undefined,
    sourceChatLabel:
      typeof rec.sourceChatLabel === 'string' && rec.sourceChatLabel.trim() ? rec.sourceChatLabel.trim() : undefined,
    sourceMessageAt: Number.isFinite(sourceMessageAt) && sourceMessageAt > 0 ? sourceMessageAt : undefined,
    sourceSender:
      typeof rec.sourceSender === 'string' && rec.sourceSender.trim() ? rec.sourceSender.trim() : undefined,
    favorite: !!rec.favorite,
    favoritedAt: Number.isFinite(favoritedAt) && favoritedAt > 0 ? favoritedAt : undefined,
  }
}

function saveAll(items: QuickNoteItem[]): void {
  if (!canUseStorage()) return
  window.localStorage.setItem(QUICK_NOTE_STORAGE_KEY, JSON.stringify(items))
  emitQuickNotesChanged()
}

export function loadQuickNotes(): QuickNoteItem[] {
  if (!canUseStorage()) return []
  try {
    const raw = window.localStorage.getItem(QUICK_NOTE_STORAGE_KEY)
    if (!raw) return []
    const arr = JSON.parse(raw) as unknown
    if (!Array.isArray(arr)) return []
    return arr
      .map(normalizeQuickNote)
      .filter((x): x is QuickNoteItem => !!x)
      .sort((a, b) => b.createdAt - a.createdAt)
  } catch {
    return []
  }
}

export function addQuickNote(input: QuickNoteCreateInput): QuickNoteItem {
  const now = Date.now()
  const bodyIn =
    typeof input.bodyHtml === 'string' && input.bodyHtml.includes('<') && input.bodyHtml.trim().length > 10
      ? input.bodyHtml.trim()
      : undefined
  const note: QuickNoteItem = {
    id: `quick_note_${now}_${Math.random().toString(36).slice(2, 8)}`,
    text: String(input.text || '').trim(),
    bodyHtml: bodyIn,
    imageBase64: typeof input.imageBase64 === 'string' && input.imageBase64.length > 20 ? input.imageBase64 : undefined,
    imageMime: typeof input.imageMime === 'string' ? input.imageMime : 'image/png',
    imageName: typeof input.imageName === 'string' && input.imageName.trim() ? input.imageName.trim() : undefined,
    createdAt: now,
    updatedAt: now,
    sourceChatLabel:
      typeof input.sourceChatLabel === 'string' && input.sourceChatLabel.trim()
        ? input.sourceChatLabel.trim()
        : undefined,
    sourceMessageAt:
      typeof input.sourceMessageAt === 'number' && Number.isFinite(input.sourceMessageAt) && input.sourceMessageAt > 0
        ? input.sourceMessageAt
        : undefined,
    sourceSender:
      typeof input.sourceSender === 'string' && input.sourceSender.trim() ? input.sourceSender.trim() : undefined,
  }
  const list = [note, ...loadQuickNotes()]
  saveAll(list)
  return note
}

export function updateQuickNote(id: string, patch: Partial<QuickNoteItem>): QuickNoteItem | null {
  const targetId = String(id || '').trim()
  if (!targetId) return null
  const list = loadQuickNotes()
  const idx = list.findIndex((x) => x.id === targetId)
  if (idx < 0) return null
  const next: QuickNoteItem = {
    ...list[idx],
    ...patch,
    id: list[idx].id,
    updatedAt: Date.now(),
  }
  list[idx] = next
  saveAll(list)
  return next
}

export function deleteQuickNote(id: string): void {
  const targetId = String(id || '').trim()
  if (!targetId) return
  const list = loadQuickNotes().filter((x) => x.id !== targetId)
  saveAll(list)
}
