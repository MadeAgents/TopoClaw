import Quill from 'quill'

let registered = false

/** 注册中文字体与像素字号（Quill 1.x），在挂载编辑器前调用一次即可 */
export function registerQuickNoteQuillFormats(): void {
  if (registered) return
  registered = true
  try {
    const Font = Quill.import('formats/font') as { whitelist: string[] }
    Font.whitelist = ['yahei', 'song', 'heiti', 'kaiti', 'arial']
    Quill.register(Font, true)
  } catch {
    /* 已注册或版本差异时忽略 */
  }
  try {
    const SizeStyle = Quill.import('attributors/style/size') as { whitelist: string[] }
    SizeStyle.whitelist = ['10px', '11px', '12px', '13px', '14px', '15px', '16px', '18px', '20px', '24px', '28px', '32px']
    Quill.register(SizeStyle, true)
  } catch {
    /* ignore */
  }
}
