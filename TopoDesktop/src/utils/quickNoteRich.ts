import DOMPurify from 'dompurify'
import type { QuickNoteItem } from '../types/quickNote'

export function stripHtml(html: string): string {
  if (!html) return ''
  const d = document.createElement('div')
  d.innerHTML = html
  return (d.textContent || d.innerText || '').replace(/\u00a0/g, ' ')
}

export function sanitizeQuickNoteHtml(html: string): string {
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    ADD_ATTR: ['style', 'class', 'width', 'height'],
  })
}

export function extractFirstDataImageFromHtml(html: string): { base64: string; mime: string; name: string } | null {
  const re = /src=["']data:([^;]+);base64,([^"']+)["']/i
  const m = html.match(re)
  if (!m?.[2]) return null
  return { mime: (m[1] || 'image/png').trim(), base64: m[2], name: 'inline.png' }
}

/** 独立图片区仅用于「纯 text + imageBase64」；富文本内嵌图只在 bodyHtml 里展示，避免重复 */
export function noteUsesStandaloneImageSlot(note: { bodyHtml?: string; imageBase64?: string }): boolean {
  return !!note.imageBase64 && !note.bodyHtml
}

/** 首张可发送/摘要的图片：优先独立字段，否则从富文本 HTML 里取第一张 data URL */
export function getNoteFirstImagePayload(note: {
  bodyHtml?: string
  imageBase64?: string
  imageMime?: string
  imageName?: string
}): { base64: string; mime: string; name: string } | null {
  if (note.imageBase64) {
    return {
      base64: note.imageBase64,
      mime: (note.imageMime || 'image/png').trim(),
      name: (note.imageName || '图片').trim() || '图片',
    }
  }
  return note.bodyHtml ? extractFirstDataImageFromHtml(note.bodyHtml) : null
}

export function getNotePrimaryImageDataUrl(note: {
  bodyHtml?: string
  imageBase64?: string
  imageMime?: string
}): string | null {
  const p = getNoteFirstImagePayload(note)
  if (!p) return null
  return `data:${p.mime};base64,${p.base64}`
}

export function getNotePlainText(note: { text?: string; bodyHtml?: string }): string {
  const fromHtml = note.bodyHtml ? stripHtml(note.bodyHtml).trim() : ''
  if (fromHtml) return fromHtml
  return String(note.text || '').trim()
}

export function noteHasRenderableContent(html: string): boolean {
  const plain = stripHtml(html).trim()
  if (plain.length > 0) return true
  return /<img\s/i.test(html)
}

function escapeHtmlText(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/** 纯文本转 Quill 可用的段落 HTML（无内容时返回空串） */
export function plainTextToEditorHtml(text: string): string {
  const raw = String(text ?? '')
  const lines = raw.split(/\n/)
  if (lines.length === 1 && lines[0] === '') return ''
  return lines
    .map((line) => {
      const esc = escapeHtmlText(line)
      return `<p>${esc.length ? esc : '<br>'}</p>`
    })
    .join('')
}

/** 打开编辑器时的初始 HTML：富文本用 bodyHtml，否则由 text + 独立配图拼出 */
export function buildEditorInitialHtml(note: QuickNoteItem): string {
  if (note.bodyHtml) {
    return sanitizeQuickNoteHtml(note.bodyHtml)
  }
  const chunks: string[] = []
  const textHtml = plainTextToEditorHtml(note.text || '')
  if (textHtml) chunks.push(textHtml)
  if (noteUsesStandaloneImageSlot(note) && note.imageBase64) {
    const url = `data:${note.imageMime || 'image/png'};base64,${note.imageBase64}`
    chunks.push(`<p><img src="${url}" alt="" /></p>`)
  }
  const combined = chunks.length > 0 ? chunks.join('') : '<p><br></p>'
  return sanitizeQuickNoteHtml(combined)
}

/** 与存储层一致：满足条件的正文才持久化为 bodyHtml */
export function normalizeBodyHtmlForStorage(html: string): string | undefined {
  const t = String(html || '').trim()
  if (t.includes('<') && t.length > 10) return t
  return undefined
}
