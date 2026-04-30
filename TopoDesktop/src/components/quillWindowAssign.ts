import Quill from 'quill'

/** quill-image-resize-module 依赖 window.Quill（Toolbar 内读取 parchment） */
;(globalThis as unknown as { Quill: typeof Quill }).Quill = Quill
