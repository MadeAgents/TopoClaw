package com.cloudcontrol.demo

import androidx.recyclerview.widget.DiffUtil

/**
 * ChatItem 的 DiffUtil.ItemCallback 实现
 * 用于 ListAdapter 的差异计算
 */
class ChatItemDiffCallback : DiffUtil.ItemCallback<ChatItem>() {
    override fun areItemsTheSame(oldItem: ChatItem, newItem: ChatItem): Boolean {
        return oldItem.getUniqueId() == newItem.getUniqueId()
    }

    override fun areContentsTheSame(oldItem: ChatItem, newItem: ChatItem): Boolean {
        return oldItem == newItem
    }

    override fun getChangePayload(oldItem: ChatItem, newItem: ChatItem): Any? {
        if (oldItem == newItem) {
            return "SELECTION_CHANGED"
        }
        return null
    }
}
