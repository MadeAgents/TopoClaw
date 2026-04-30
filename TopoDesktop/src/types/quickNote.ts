export interface QuickNoteItem {
  id: string
  text: string
  /** 富文本 HTML（手动小文档）；与 text 可同时存在，text 多为纯文本检索用 */
  bodyHtml?: string
  imageBase64?: string
  imageMime?: string
  imageName?: string
  createdAt: number
  updatedAt: number
  reminderJobId?: string
  memorySavedAt?: number
  /** 来自聊天：与「x」的私聊 / 在「y」群组的群聊 等 */
  sourceChatLabel?: string
  /** 来自聊天：该条消息的发送时间 */
  sourceMessageAt?: number
  /** 来自聊天：发送者显示名 */
  sourceSender?: string
  /** 是否收藏 */
  favorite?: boolean
  /** 收藏时间 */
  favoritedAt?: number
}

export interface QuickNoteCreateInput {
  text?: string
  bodyHtml?: string
  imageBase64?: string
  imageMime?: string
  imageName?: string
  sourceChatLabel?: string
  sourceMessageAt?: number
  sourceSender?: string
}
