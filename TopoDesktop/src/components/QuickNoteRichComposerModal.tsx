import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import './registerQuillImageResize'
import ReactQuill from 'react-quill'
import 'react-quill/dist/quill.snow.css'
import { registerQuickNoteQuillFormats } from './quillQuickNoteSetup'
import {
  extractFirstDataImageFromHtml,
  noteHasRenderableContent,
  sanitizeQuickNoteHtml,
  stripHtml,
} from '../utils/quickNoteRich'
import './QuickNoteRichComposerModal.css'

export interface QuickNoteRichSavePayload {
  bodyHtml: string
  plainText: string
  firstImage: { base64: string; mime: string; name: string } | null
  /** 有值表示更新该条随手记，否则为新建 */
  editNoteId?: string
}

interface QuickNoteRichComposerModalProps {
  open: boolean
  /** 编辑时传入的初始 HTML（新建可为空） */
  initialHtml?: string
  /** 正在编辑的随手记 id，新建为 null */
  editingNoteId?: string | null
  onClose: () => void
  onSave: (payload: QuickNoteRichSavePayload) => void
}

export function QuickNoteRichComposerModal({
  open,
  initialHtml = '',
  editingNoteId = null,
  onClose,
  onSave,
}: QuickNoteRichComposerModalProps) {
  const quillRef = useRef<ReactQuill | null>(null)
  const [value, setValue] = useState('')
  const rid = useId().replace(/:/g, '')
  const toolbarId = `qn-tb-${rid}`

  useEffect(() => {
    if (open) {
      registerQuickNoteQuillFormats()
      const seed = initialHtml.trim() ? initialHtml : '<p><br></p>'
      setValue(seed)
    }
  }, [open, initialHtml])

  useEffect(() => {
    if (!open) return
    let cancelled = false
    let detach: (() => void) | undefined
    const tid = window.setTimeout(() => {
      if (cancelled) return
      const quill = quillRef.current?.getEditor?.()
      if (!quill) return
      const root = quill.root
      const onPaste = (e: ClipboardEvent) => {
        const items = e.clipboardData?.items
        if (!items?.length) return
        for (let i = 0; i < items.length; i++) {
          const item = items[i]
          if (item.kind === 'file' && item.type.startsWith('image/')) {
            e.preventDefault()
            const file = item.getAsFile()
            if (!file) continue
            const reader = new FileReader()
            reader.onload = () => {
              const url = String(reader.result || '')
              const range = quill.getSelection(true) ?? { index: quill.getLength(), length: 0 }
              quill.insertEmbed(range.index, 'image', url)
              quill.setSelection(range.index + 1, 0)
            }
            reader.readAsDataURL(file)
            break
          }
        }
      }
      root.addEventListener('paste', onPaste)
      detach = () => root.removeEventListener('paste', onPaste)
    }, 120)
    return () => {
      cancelled = true
      window.clearTimeout(tid)
      detach?.()
    }
  }, [open])

  const modules = useMemo(
    () => ({
      toolbar: {
        container: `#${toolbarId}`,
        handlers: {
          image: () => {
            const quill = quillRef.current?.getEditor?.()
            if (!quill) return
            const input = document.createElement('input')
            input.type = 'file'
            input.accept = 'image/*'
            input.onchange = () => {
              const file = input.files?.[0]
              if (!file) return
              const reader = new FileReader()
              reader.onload = () => {
                const url = String(reader.result || '')
                const range = quill.getSelection(true) ?? { index: quill.getLength(), length: 0 }
                quill.insertEmbed(range.index, 'image', url)
                quill.setSelection(range.index + 1, 0)
              }
              reader.readAsDataURL(file)
            }
            input.click()
          },
        },
      },
      clipboard: { matchVisual: false },
      imageResize: {
        modules: ['Resize', 'DisplaySize', 'Toolbar'],
      },
    }),
    [toolbarId]
  )

  const formats = useMemo(
    () => [
      'header',
      'font',
      'size',
      'bold',
      'italic',
      'underline',
      'strike',
      'color',
      'background',
      'script',
      'list',
      'bullet',
      'indent',
      'align',
      'blockquote',
      'code-block',
      'link',
      'image',
    ],
    []
  )

  const handleSave = useCallback(() => {
    const raw = value || ''
    if (!noteHasRenderableContent(raw)) {
      window.alert('请输入内容或插入图片后再保存')
      return
    }
    const bodyHtml = sanitizeQuickNoteHtml(raw)
    const plainText = stripHtml(bodyHtml).trim()
    const firstImage = extractFirstDataImageFromHtml(bodyHtml)
    onSave({
      bodyHtml,
      plainText,
      firstImage,
      editNoteId: editingNoteId || undefined,
    })
    onClose()
  }, [value, onSave, onClose, editingNoteId])

  if (!open) return null

  return (
    <div className="quick-note-rich-overlay" role="presentation" onClick={onClose}>
      <div
        className="quick-note-rich-dialog"
        role="dialog"
        aria-labelledby="quick-note-rich-title"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="quick-note-rich-head">
          <h2 id="quick-note-rich-title">{editingNoteId ? '编辑随手记' : '新建随手记'}</h2>
          <button type="button" className="quick-note-rich-close" aria-label="关闭" onClick={onClose}>
            ×
          </button>
        </div>
        <p className="quick-note-rich-hint">支持标题、字体、字号、颜色与排版；可插入图片或从剪贴板粘贴图片。</p>

        <div id={toolbarId} className="quick-note-rich-toolbar">
          <span className="ql-formats">
            <select className="ql-header" title="段落样式" aria-label="段落样式">
              <option value="">正文</option>
              <option value="1">标题 1</option>
              <option value="2">标题 2</option>
              <option value="3">标题 3</option>
            </select>
            <select className="ql-font" title="字体" aria-label="字体">
              <option value="">字体</option>
              <option value="yahei">微软雅黑</option>
              <option value="song">宋体</option>
              <option value="heiti">黑体</option>
              <option value="kaiti">楷体</option>
              <option value="arial">Arial</option>
            </select>
            <select className="ql-size" title="字号" aria-label="字号">
              <option value="">字号</option>
              <option value="10px">10</option>
              <option value="11px">11</option>
              <option value="12px">12</option>
              <option value="13px">13</option>
              <option value="14px">14</option>
              <option value="15px">15</option>
              <option value="16px">16</option>
              <option value="18px">18</option>
              <option value="20px">20</option>
              <option value="24px">24</option>
              <option value="28px">28</option>
              <option value="32px">32</option>
            </select>
          </span>
          <span className="quick-note-toolbar-sep" aria-hidden />
          <span className="ql-formats">
            <button type="button" className="ql-bold" aria-label="加粗" />
            <button type="button" className="ql-italic" aria-label="斜体" />
            <button type="button" className="ql-underline" aria-label="下划线" />
            <button type="button" className="ql-strike" aria-label="删除线" />
            <button type="button" className="ql-script" value="sub" aria-label="下标" />
            <button type="button" className="ql-script" value="super" aria-label="上标" />
            <select className="ql-color" title="文字颜色" aria-label="文字颜色" />
            <select className="ql-background" title="背景色" aria-label="背景色" />
          </span>
          <span className="quick-note-toolbar-sep" aria-hidden />
          <span className="ql-formats">
            <button type="button" className="ql-list" value="ordered" aria-label="编号列表" />
            <button type="button" className="ql-list" value="bullet" aria-label="项目符号" />
            <button type="button" className="ql-indent" value="-1" aria-label="减少缩进" />
            <button type="button" className="ql-indent" value="+1" aria-label="增加缩进" />
            <select className="ql-align" title="对齐" aria-label="对齐">
              <option value="" />
              <option value="center" />
              <option value="right" />
              <option value="justify" />
            </select>
          </span>
          <span className="quick-note-toolbar-sep" aria-hidden />
          <span className="ql-formats">
            <span className="quick-note-toolbar-insert-label">插入</span>
            <button type="button" className="ql-link" aria-label="链接" />
            <button type="button" className="ql-image" aria-label="插入图片" />
            <button type="button" className="ql-blockquote" aria-label="引用" />
            <button type="button" className="ql-code-block" aria-label="代码块" />
            <button type="button" className="ql-clean" aria-label="清除格式" />
          </span>
        </div>

        <div className="quick-note-rich-editor-shell">
          <ReactQuill
            ref={quillRef}
            theme="snow"
            value={value}
            onChange={setValue}
            modules={modules}
            formats={formats}
            placeholder="在此输入正文…"
            className="quick-note-rich-quill"
          />
        </div>

        <div className="quick-note-rich-footer">
          <button type="button" className="quick-note-rich-btn ghost" onClick={onClose}>
            取消
          </button>
          <button type="button" className="quick-note-rich-btn primary" onClick={handleSave}>
            {editingNoteId ? '保存修改' : '保存到随手记'}
          </button>
        </div>
      </div>
    </div>
  )
}
