package com.cloudcontrol.demo

import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import com.cloudcontrol.demo.R
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon

/**
 * 聊天消息RecyclerView Adapter
 * 使用 ListAdapter + DiffUtil 优化性能，避免全量刷新
 */
class ChatAdapter(
    private val fragment: ChatFragment,
    private val onAvatarClick: (String) -> Unit,
    private val onSystemMessagesToggle: () -> Unit,
    private val onToggleMessageSelection: ((Long) -> Unit)? = null,
    private val onIsMessageSelected: ((Long) -> Boolean)? = null,
    private val onIsMultiSelectMode: (() -> Boolean)? = null
) : ListAdapter<ChatItem, RecyclerView.ViewHolder>(ChatItemDiffCallback()) {

    private var markwon: Markwon? = null
    private var isSystemMessagesExpanded: Boolean = false

    companion object {
        // Payload 常量，用于部分更新
        const val PAYLOAD_SELECTION_CHANGED = "SELECTION_CHANGED"
        const val PAYLOAD_MULTI_SELECT_MODE_CHANGED = "MULTI_SELECT_MODE_CHANGED"

        // ViewType 常量
        private const val TYPE_SYSTEM_MESSAGES_HEADER = 0
        private const val TYPE_SYSTEM_MESSAGE = 1
        private const val TYPE_TIME_STAMP = 2
        private const val TYPE_MESSAGE = 3
        private const val TYPE_IMAGE_MESSAGE = 4
        private const val TYPE_RECEIVED_IMAGE_MESSAGE = 9
        private const val TYPE_VIDEO_ANALYSIS_MESSAGE = 5
        private const val TYPE_SKILL_MESSAGE = 10
        private const val TYPE_RECOMMENDATIONS = 6
        private const val TYPE_FEEDBACK_REQUEST = 7
        private const val TYPE_NEW_TOPIC_HINT = 8
        private const val TYPE_ACTION_BUTTONS = 11
        private const val TYPE_ASSISTANT_THINKING = 12
    }

    init {
        markwon = MarkdownRenderer.createMarkwon(fragment.requireContext())
    }

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is ChatItem.SystemMessagesHeader -> TYPE_SYSTEM_MESSAGES_HEADER
            is ChatItem.SystemMessage -> TYPE_SYSTEM_MESSAGE
            is ChatItem.TimeStamp -> TYPE_TIME_STAMP
            is ChatItem.Message -> TYPE_MESSAGE
            is ChatItem.ImageMessage -> TYPE_IMAGE_MESSAGE
            is ChatItem.ReceivedImageMessage -> TYPE_RECEIVED_IMAGE_MESSAGE
            is ChatItem.VideoAnalysisMessage -> TYPE_VIDEO_ANALYSIS_MESSAGE
            is ChatItem.SkillMessage -> TYPE_SKILL_MESSAGE
            is ChatItem.Recommendations -> TYPE_RECOMMENDATIONS
            is ChatItem.FeedbackRequest -> TYPE_FEEDBACK_REQUEST
            is ChatItem.NewTopicHint -> TYPE_NEW_TOPIC_HINT
            is ChatItem.ActionButtons -> TYPE_ACTION_BUTTONS
            is ChatItem.AssistantThinking -> TYPE_ASSISTANT_THINKING
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_SYSTEM_MESSAGES_HEADER -> {
                val view = android.widget.TextView(parent.context).apply {
                    textSize = 12f
                    setPadding(16, 12, 16, 12)
                    setBackgroundColor(0xFFE0E0E0.toInt())
                    setTextColor(0xFF666666.toInt())
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                SystemMessagesHeaderViewHolder(view, onSystemMessagesToggle)
            }
            TYPE_SYSTEM_MESSAGE -> {
                val view = android.widget.LinearLayout(parent.context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        (this as? android.view.ViewGroup.MarginLayoutParams)?.setMargins(0, 4, 0, 4)
                    }
                }
                SystemMessageViewHolder(view, markwon!!, fragment)
            }
            TYPE_TIME_STAMP -> {
                val view = android.widget.TextView(parent.context).apply {
                    textSize = 11f
                    setTextColor(0xFF999999.toInt())
                    setPadding(16, 8, 16, 8)
                    gravity = Gravity.CENTER
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xCCF9F9F9.toInt())
                        cornerRadius = 4f
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 8, 0, 8)
                    }
                }
                val container = android.widget.LinearLayout(parent.context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    addView(view)
                }
                TimeStampViewHolder(container, view, fragment)
            }
            TYPE_MESSAGE -> MessageViewHolder(parent, fragment, markwon!!, onAvatarClick)
            TYPE_IMAGE_MESSAGE -> ImageMessageViewHolder(parent, fragment, markwon!!, onAvatarClick)
            TYPE_RECEIVED_IMAGE_MESSAGE -> ReceivedImageMessageViewHolder(parent, fragment, markwon!!, onAvatarClick)
            TYPE_VIDEO_ANALYSIS_MESSAGE -> VideoAnalysisMessageViewHolder(parent, fragment, markwon!!, onAvatarClick)
            TYPE_SKILL_MESSAGE -> SkillMessageViewHolder(parent, fragment, markwon!!, onAvatarClick)
            TYPE_RECOMMENDATIONS -> RecommendationsViewHolder(parent, fragment)
            TYPE_FEEDBACK_REQUEST -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_feedback_request, parent, false)
                FeedbackRequestViewHolder(view, fragment)
            }
            TYPE_ACTION_BUTTONS -> {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_action_buttons, parent, false)
                ActionButtonsViewHolder(view, fragment)
            }
            TYPE_ASSISTANT_THINKING -> AssistantThinkingViewHolder(parent) { key ->
                toggleAssistantThinkingExpanded(key)
            }
            TYPE_NEW_TOPIC_HINT -> {
                val view = android.widget.TextView(parent.context).apply {
                    textSize = 11f
                    setTextColor(0xFF999999.toInt())
                    setPadding(16, 8, 16, 8)
                    gravity = Gravity.CENTER
                    text = "点击此处开启新话题"
                    isClickable = true
                    isFocusable = true
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xCCF9F9F9.toInt())
                        cornerRadius = 4f
                    }
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 8, 0, 8)
                    }
                }
                val container = android.widget.LinearLayout(parent.context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    addView(view)
                }
                NewTopicHintViewHolder(container, view, fragment)
            }
            else -> throw IllegalArgumentException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        bindViewHolderInternal(holder, position)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            val payload = payloads[0]
            when (payload) {
                PAYLOAD_SELECTION_CHANGED, PAYLOAD_MULTI_SELECT_MODE_CHANGED -> {
                    updateSelectionState(holder, position)
                    return
                }
            }
        }
        bindViewHolderInternal(holder, position)
    }

    private fun bindViewHolderInternal(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ChatItem.SystemMessagesHeader -> (holder as SystemMessagesHeaderViewHolder).bind(item)
            is ChatItem.SystemMessage -> (holder as SystemMessageViewHolder).bind(item, isSystemMessagesExpanded)
            is ChatItem.TimeStamp -> (holder as TimeStampViewHolder).bind(item)
            is ChatItem.Message -> {
                val hasThinkingBefore = position > 0 && getItem(position - 1) is ChatItem.AssistantThinking
                val shouldHideAvatar = item.isAnswer && !item.isUserMessage && hasThinkingBefore
                val messageHolder = holder as MessageViewHolder
                messageHolder.bind(item, showAssistantAvatar = !shouldHideAvatar)
                val isSelected = onIsMessageSelected?.invoke(item.timestamp) ?: false
                val isMultiSelectMode = onIsMultiSelectMode?.invoke() ?: false
                if (isMultiSelectMode && onToggleMessageSelection != null) {
                    holder.itemView.setOnClickListener { onToggleMessageSelection?.invoke(item.timestamp) }
                } else {
                    holder.itemView.setOnClickListener(null)
                }
                messageHolder.updateSelectionState(isSelected, isMultiSelectMode)
            }
            is ChatItem.ImageMessage -> {
                val imageHolder = holder as ImageMessageViewHolder
                imageHolder.bind(item)
                val isMultiSelect = onIsMultiSelectMode?.invoke() ?: false
                val isSelected = onIsMessageSelected?.invoke(item.timestamp) == true
                if (isMultiSelect && onToggleMessageSelection != null) {
                    holder.itemView.setOnClickListener { onToggleMessageSelection?.invoke(item.timestamp) }
                } else {
                    holder.itemView.setOnClickListener(null)
                }
                imageHolder.updateSelectionState(isSelected, isMultiSelect)
            }
            is ChatItem.ReceivedImageMessage -> (holder as ReceivedImageMessageViewHolder).bind(item)
            is ChatItem.VideoAnalysisMessage -> (holder as VideoAnalysisMessageViewHolder).bind(item)
            is ChatItem.SkillMessage -> (holder as SkillMessageViewHolder).bind(item)
            is ChatItem.Recommendations -> (holder as RecommendationsViewHolder).bind(item)
            is ChatItem.FeedbackRequest -> (holder as FeedbackRequestViewHolder).bind(item)
            is ChatItem.NewTopicHint -> (holder as NewTopicHintViewHolder).bind(item)
            is ChatItem.ActionButtons -> (holder as ActionButtonsViewHolder).bind(item)
            is ChatItem.AssistantThinking -> (holder as AssistantThinkingViewHolder).bind(item)
        }
    }

    private fun updateSelectionState(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (item) {
            is ChatItem.Message -> {
                val isSelected = onIsMessageSelected?.invoke(item.timestamp) ?: false
                val isMultiSelectMode = onIsMultiSelectMode?.invoke() ?: false
                if (isMultiSelectMode && onToggleMessageSelection != null) {
                    holder.itemView.setOnClickListener { onToggleMessageSelection?.invoke(item.timestamp) }
                } else {
                    holder.itemView.setOnClickListener(null)
                }
                if (holder is MessageViewHolder) holder.updateSelectionState(isSelected, isMultiSelectMode)
            }
            is ChatItem.ImageMessage -> {
                val isSelected = onIsMessageSelected?.invoke(item.timestamp) ?: false
                val isMultiSelectMode = onIsMultiSelectMode?.invoke() ?: false
                if (isMultiSelectMode && onToggleMessageSelection != null) {
                    holder.itemView.setOnClickListener { onToggleMessageSelection?.invoke(item.timestamp) }
                } else {
                    holder.itemView.setOnClickListener(null)
                }
                if (holder is ImageMessageViewHolder) holder.updateSelectionState(isSelected, isMultiSelectMode)
            }
            else -> {}
        }
    }

    fun addItem(item: ChatItem) {
        val newList = currentList.toMutableList()
        newList.add(item)
        submitList(newList)
    }

    fun addItems(newItems: List<ChatItem>) {
        if (newItems.isEmpty()) return
        val newList = currentList.toMutableList()
        newList.addAll(newItems)
        submitList(newList)
    }

    fun addItemAt(position: Int, item: ChatItem) {
        val newList = currentList.toMutableList()
        if (position >= 0 && position <= newList.size) {
            newList.add(position, item)
            submitList(newList)
        }
    }

    fun removeItemAt(position: Int) {
        if (position >= 0 && position < currentList.size) {
            val newList = currentList.toMutableList()
            newList.removeAt(position)
            submitList(newList)
        }
    }

    fun updateSystemMessagesHeader(count: Int, isExpanded: Boolean) {
        val newList = currentList.toMutableList()
        val index = newList.indexOfFirst { it is ChatItem.SystemMessagesHeader }
        if (index >= 0) {
            newList[index] = ChatItem.SystemMessagesHeader(count, isExpanded)
            this.isSystemMessagesExpanded = isExpanded
            submitList(newList)
        }
    }

    fun clear() = submitList(null)

    fun getItems(): List<ChatItem> = currentList

    fun hasWelcomeMessage(): Boolean = currentList.any { item ->
        item is ChatItem.Message && item.sender == "人工客服" && item.message.contains("我是人工客服") &&
            (item.message.contains("使用说明") || item.message.contains("清除数据"))
    }

    fun ensureWelcomeMessageAtTop() {
        val welcomeIndex = currentList.indexOfFirst { item ->
            item is ChatItem.Message && item.sender == "人工客服" && item.message.contains("我是人工客服") &&
                (item.message.contains("使用说明") || item.message.contains("清除数据"))
        }
        if (welcomeIndex > 0) {
            val newList = currentList.toMutableList()
            val welcomeItem = newList.removeAt(welcomeIndex)
            newList.add(0, welcomeItem)
            submitList(newList)
        }
    }

    fun addWelcomeMessageIfNeeded(welcomeMessage: ChatItem.Message): Boolean {
        if (hasWelcomeMessage()) {
            ensureWelcomeMessageAtTop()
            return false
        }
        val newList = currentList.toMutableList()
        newList.add(0, welcomeMessage)
        submitList(newList)
        return true
    }

    fun findSystemMessagesHeaderIndex(): Int = currentList.indexOfFirst { it is ChatItem.SystemMessagesHeader }

    fun getSystemMessageItems(): List<ChatItem.SystemMessage> {
        val headerIndex = findSystemMessagesHeaderIndex()
        if (headerIndex < 0) return emptyList()
        val systemMessages = mutableListOf<ChatItem.SystemMessage>()
        for (i in (headerIndex + 1) until currentList.size) {
            when (val item = currentList[i]) {
                is ChatItem.SystemMessage -> systemMessages.add(item)
                is ChatItem.TimeStamp, is ChatItem.Message -> break
                else -> {}
            }
        }
        return systemMessages
    }

    fun removeSystemMessageItems() {
        val headerIndex = findSystemMessagesHeaderIndex()
        if (headerIndex < 0) return
        val newList = currentList.toMutableList()
        var removeCount = 0
        var i = headerIndex + 1
        while (i < newList.size) {
            if (newList[i] is ChatItem.SystemMessage) {
                newList.removeAt(i)
                removeCount++
            } else break
        }
        if (removeCount > 0) submitList(newList)
    }

    fun notifyMultiSelectModeChanged() {
        for (i in 0 until itemCount) notifyItemChanged(i, PAYLOAD_MULTI_SELECT_MODE_CHANGED)
    }

    fun notifySelectionChanged() {
        for (i in 0 until itemCount) {
            val item = getItem(i)
            if (item is ChatItem.Message || item is ChatItem.ImageMessage) {
                notifyItemChanged(i, PAYLOAD_SELECTION_CHANGED)
            }
        }
    }

    fun upsertAssistantThinking(
        sender: String,
        assistantId: String?,
        avatarBase64: String?,
        text: String = "正在思考",
        details: List<String> = emptyList(),
        key: String = "assistant_thinking",
        isCompleted: Boolean = false
    ) {
        val newList = currentList.toMutableList()
        val normalizedKey = key.ifBlank { "assistant_thinking" }
        val index = newList.indexOfFirst {
            it is ChatItem.AssistantThinking && it.key == normalizedKey
        }.takeIf { it >= 0 } ?: newList.indexOfFirst { it is ChatItem.AssistantThinking }
        val previous = (if (index >= 0) newList[index] else null) as? ChatItem.AssistantThinking
        // Prevent late thinking_sync from reverting a completed indicator
        if (previous != null && previous.isCompleted && !isCompleted) {
            return
        }
        val incomingDetails = details.filter { it.isNotBlank() }
        val mergedDetails = mergeAssistantThinkingDetails(previous?.details.orEmpty(), incomingDetails)
        val newItem = ChatItem.AssistantThinking(
            sender = sender,
            text = text,
            details = mergedDetails,
            isExpanded = when {
                isCompleted -> false
                previous != null -> previous.isExpanded
                else -> true
            },
            isCompleted = isCompleted,
            assistantId = assistantId,
            avatarBase64 = avatarBase64,
            key = normalizedKey
        )
        if (index >= 0) {
            newList[index] = newItem
        } else {
            newList.add(newItem)
        }
        submitList(newList)
    }

    /**
     * 合并同一任务的思考内容：
     * - 若 incoming 已包含 previous（快照流），直接采用 incoming；
     * - 否则按增量追加，避免覆盖已展示历史。
     */
    private fun mergeAssistantThinkingDetails(previous: List<String>, incoming: List<String>): List<String> {
        if (incoming.isEmpty()) return previous
        if (previous.isEmpty()) return incoming

        val merged = if (isPrefix(previous, incoming)) {
            incoming
        } else {
            previous + incoming
        }
        return dedupePreservingOrder(merged)
    }

    private fun isPrefix(prefix: List<String>, full: List<String>): Boolean {
        if (prefix.size > full.size) return false
        return prefix.indices.all { prefix[it] == full[it] }
    }

    private fun dedupePreservingOrder(lines: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        for (line in lines) {
            if (line.isNotBlank()) seen.add(line)
        }
        return seen.toList()
    }

    fun removeAssistantThinking() {
        val newList = currentList.filterNot { it is ChatItem.AssistantThinking }
        if (newList.size != currentList.size) submitList(newList)
    }

    /**
     * Atomically removes all [ChatItem.AssistantThinking] items and appends [items]
     * in a single [submitList] call.
     *
     * [ListAdapter.submitList] is asynchronous (AsyncListDiffer): calling
     * [removeAssistantThinking] followed by [addItem] causes the second submitList
     * to read the stale currentList (before the first diff is applied), silently
     * discarding the removal. This method avoids that race.
     */
    fun addItemsClearingThinking(vararg items: ChatItem) {
        val newList = currentList.toMutableList().apply {
            removeAll { it is ChatItem.AssistantThinking }
            addAll(items)
        }
        submitList(newList)
    }

    fun completeAssistantThinking(key: String? = null) {
        val targetKey = key?.trim().orEmpty()
        val newList = currentList.map { item ->
            if (item is ChatItem.AssistantThinking) {
                if (targetKey.isEmpty() || item.key == targetKey) {
                    item.copy(
                        isCompleted = true,
                        isExpanded = false
                    )
                } else item
            } else item
        }
        if (newList != currentList) submitList(newList)
    }

    private fun toggleAssistantThinkingExpanded(key: String) {
        val newList = currentList.toMutableList()
        val index = newList.indexOfFirst {
            it is ChatItem.AssistantThinking && it.key == key
        }
        if (index < 0) return
        val old = newList[index] as? ChatItem.AssistantThinking ?: return
        newList[index] = old.copy(isExpanded = !old.isExpanded)
        submitList(newList)
    }
}
