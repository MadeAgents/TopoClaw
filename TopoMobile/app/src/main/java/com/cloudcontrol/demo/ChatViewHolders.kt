package com.cloudcontrol.demo

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon
import com.cloudcontrol.demo.R

// region SystemMessagesHeaderViewHolder

/**
 * 系统消息头部 ViewHolder
 */
class SystemMessagesHeaderViewHolder(
    view: View,
    private val onSystemMessagesToggle: () -> Unit
) : RecyclerView.ViewHolder(view) {

    private val textView: TextView = view as TextView

    fun bind(item: ChatItem.SystemMessagesHeader) {
        val expandedLabel = if (item.isExpanded) {
            itemView.context.getString(R.string.system_messages_expanded)
        } else {
            itemView.context.getString(R.string.system_messages_collapsed)
        }
        textView.text = itemView.context.getString(R.string.system_messages_format, item.count, expandedLabel)
        textView.setOnClickListener { onSystemMessagesToggle() }
    }
}

// endregion

// region SystemMessageViewHolder

/**
 * 系统消息 ViewHolder
 */
class SystemMessageViewHolder(
    view: View,
    private val markwon: Markwon,
    private val fragment: ChatFragment
) : RecyclerView.ViewHolder(view) {

    private val container: LinearLayout = view as LinearLayout

    fun bind(item: ChatItem.SystemMessage, isExpanded: Boolean) {
        container.removeAllViews()
        if (!isExpanded) return
        val density = fragment.resources.displayMetrics.density
        val systemTextBubbleHorizontalPadding = (14 * density).toInt()
        val systemTextBubbleVerticalPadding = (4 * density).toInt()
        val systemTextBubbleCornerRadius = 14f * density

        val bubble = TextView(fragment.requireContext()).apply {
            text = "${item.sender}: ${item.message}"
            textSize = 10f
            setPadding(
                systemTextBubbleHorizontalPadding,
                systemTextBubbleVerticalPadding,
                systemTextBubbleHorizontalPadding,
                systemTextBubbleVerticalPadding
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFE8E8E8.toInt())
                cornerRadius = systemTextBubbleCornerRadius
            }
            setTextColor(0xFF666666.toInt())
            maxWidth = (fragment.resources.displayMetrics.widthPixels * 0.85).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 4, 0, 4) }
        }
        container.addView(bubble)
    }
}

// endregion

// region TimeStampViewHolder

/**
 * 时间戳 ViewHolder
 */
class TimeStampViewHolder(
    container: View,
    private val textView: TextView,
    private val fragment: ChatFragment
) : RecyclerView.ViewHolder(container) {

    fun bind(item: ChatItem.TimeStamp) {
        textView.text = fragment.formatTime(item.timestamp)
    }
}

// endregion

// region NewTopicHintViewHolder

/**
 * 新话题提示 ViewHolder
 */
class NewTopicHintViewHolder(
    container: View,
    private val textView: TextView,
    private val fragment: ChatFragment
) : RecyclerView.ViewHolder(container) {

    fun bind(item: ChatItem.NewTopicHint) {
        textView.text = "点击此处开启新话题"
        textView.setOnClickListener {
            fragment.onNewTopicHintClicked()
        }
    }
}

// endregion

// region MessageViewHolder

/**
 * 普通消息 ViewHolder
 */
class MessageViewHolder(
    parent: ViewGroup,
    private val fragment: ChatFragment,
    private val markwon: Markwon,
    private val onAvatarClick: (String) -> Unit
) : RecyclerView.ViewHolder(createMessageContainer(parent.context)) {

    private val messageContainer: LinearLayout = itemView as LinearLayout
    private var selectionIndicator: TextView? = null

    fun bind(item: ChatItem.Message, showAssistantAvatar: Boolean = true) {
        messageContainer.removeAllViews()
        val bubble = fragment.createMessageBubbleForAdapter(
            sender = item.sender,
            message = item.message,
            isUserMessage = item.isUserMessage,
            isComplete = item.isComplete,
            isAnswer = item.isAnswer,
            senderImei = item.senderImei,
            showAssistantAvatar = showAssistantAvatar,
            markwon = markwon,
            onAvatarClick = onAvatarClick,
            timestamp = item.timestamp,
            recommendations = item.recommendations
        )
        val row = LinearLayout(itemView.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        val indicator = createSelectionIndicatorView()
        row.addView(indicator)
        bubble.layoutParams = LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        )
        row.addView(bubble)
        messageContainer.addView(row)
        selectionIndicator = indicator
        updateSelectionState(fragment.isMessageSelected(item.timestamp), fragment.isMultiSelectMode)
    }

    fun updateSelectionState(isSelected: Boolean, isMultiSelectMode: Boolean) {
        val indicator = selectionIndicator
        if (indicator != null) {
            indicator.visibility = if (isMultiSelectMode) View.VISIBLE else View.GONE
            val strokeColor = if (isSelected) 0xFF2196F3.toInt() else 0xFFBDBDBD.toInt()
            val fillColor = if (isSelected) 0xFF2196F3.toInt() else android.graphics.Color.TRANSPARENT
            indicator.text = if (isSelected) "✓" else ""
            indicator.setTextColor(if (isSelected) android.graphics.Color.WHITE else android.graphics.Color.TRANSPARENT)
            (indicator.background as? android.graphics.drawable.GradientDrawable)?.apply {
                setColor(fillColor)
                setStroke((1.5f * itemView.resources.displayMetrics.density).toInt().coerceAtLeast(1), strokeColor)
            }
        }
        itemView.alpha = if (isMultiSelectMode && isSelected) 0.7f else 1.0f
    }

    private fun createSelectionIndicatorView(): TextView {
        val density = itemView.resources.displayMetrics.density
        val size = (20 * density).toInt()
        val indicatorMarginEnd = (8 * density).toInt()
        return TextView(itemView.context).apply {
            gravity = android.view.Gravity.CENTER
            textSize = 12f
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                topMargin = (8 * density).toInt()
                marginEnd = indicatorMarginEnd
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke((1.5f * density).toInt().coerceAtLeast(1), 0xFFBDBDBD.toInt())
            }
        }
    }

    companion object {
        private fun createMessageContainer(context: Context): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 8, 0, 8) }
            }
        }
    }
}

// endregion

// region VideoAnalysisMessageViewHolder

/**
 * 视频分析消息 ViewHolder
 */
class VideoAnalysisMessageViewHolder(
    parent: ViewGroup,
    private val fragment: ChatFragment,
    private val markwon: Markwon,
    private val onAvatarClick: (String) -> Unit
) : RecyclerView.ViewHolder(createVideoAnalysisContainer(parent.context)) {

    private val messageContainer: LinearLayout = itemView as LinearLayout

    fun bind(item: ChatItem.VideoAnalysisMessage) {
        messageContainer.removeAllViews()
        val bubble = fragment.createMessageBubbleForAdapter(
            sender = "小助手",
            message = item.analysisResult.formattedMessage,
            isUserMessage = false,
            isComplete = true,
            isAnswer = true,
            markwon = markwon,
            onAvatarClick = onAvatarClick,
            timestamp = item.timestamp
        )
        messageContainer.addView(bubble)
    }

    companion object {
        private fun createVideoAnalysisContainer(context: Context): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 8, 0, 8) }
            }
        }
    }
}

// endregion

// region SkillMessageViewHolder

/**
 * 技能消息 ViewHolder
 */
class SkillMessageViewHolder(
    parent: ViewGroup,
    private val fragment: ChatFragment,
    private val markwon: Markwon,
    private val onAvatarClick: (String) -> Unit
) : RecyclerView.ViewHolder(createSkillMessageContainer(parent.context)) {

    private val messageContainer: LinearLayout = itemView as LinearLayout

    fun bind(item: ChatItem.SkillMessage) {
        messageContainer.removeAllViews()
        val bubble = fragment.createMessageBubbleForAdapter(
            sender = item.sender,
            message = "📋 ${item.skill.title}",
            isUserMessage = item.isUserMessage,
            isComplete = true,
            isAnswer = false,
            markwon = markwon,
            onAvatarClick = onAvatarClick,
            timestamp = item.timestamp
        )
        messageContainer.addView(bubble)
    }

    companion object {
        private fun createSkillMessageContainer(context: Context): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 8, 0, 8) }
            }
        }
    }
}

// endregion

// region RecommendationsViewHolder

/**
 * 推荐任务 ViewHolder
 */
class RecommendationsViewHolder(
    parent: ViewGroup,
    private val fragment: ChatFragment
) : RecyclerView.ViewHolder(createRecommendationsContainer(parent.context)) {

    private val contentContainer: LinearLayout = itemView as LinearLayout

    fun bind(item: ChatItem.Recommendations) {
        contentContainer.removeAllViews()
        item.recommendations.forEach { recommendation ->
            if (recommendation.isNotBlank()) {
                val button = LinearLayout(fragment.requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(
                        (10 * fragment.resources.displayMetrics.density).toInt(),
                        (6 * fragment.resources.displayMetrics.density).toInt(),
                        (10 * fragment.resources.displayMetrics.density).toInt(),
                        (6 * fragment.resources.displayMetrics.density).toInt()
                    )
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFFF9F9F9.toInt())
                        cornerRadius = 6f
                        setStroke(
                            (1 * fragment.resources.displayMetrics.density).toInt(),
                            0xFFE0E0E0.toInt()
                        )
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { fragment.startTaskFromRecommendation(recommendation) }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 2, 0, 2) }
                    addView(TextView(fragment.requireContext()).apply {
                        text = recommendation
                        textSize = 13f
                        setTextColor(0xFF666666.toInt())
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    })
                }
                contentContainer.addView(button)
            }
        }
    }

    companion object {
        private fun createRecommendationsContainer(context: Context): LinearLayout {
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 8, 0, 8) }
            }
        }
    }
}

// endregion

// region FeedbackRequestViewHolder

/**
 * 评价请求 ViewHolder
 */
class FeedbackRequestViewHolder(
    view: View,
    private val fragment: ChatFragment
) : RecyclerView.ViewHolder(view) {

    private val btnStar1: ImageButton = view.findViewById(R.id.btnStar1)
    private val btnStar2: ImageButton = view.findViewById(R.id.btnStar2)
    private val btnStar3: ImageButton = view.findViewById(R.id.btnStar3)
    private val btnStar4: ImageButton = view.findViewById(R.id.btnStar4)
    private val btnStar5: ImageButton = view.findViewById(R.id.btnStar5)
    private val llStarRating: android.widget.LinearLayout = view.findViewById(R.id.llStarRating)
    private val tvFeedbackTitle: TextView = view.findViewById(R.id.tvFeedbackTitle)
    private val etFeedbackText: EditText = view.findViewById(R.id.etFeedbackText)
    private val btnCancelFeedback: android.widget.Button = view.findViewById(R.id.btnCancelFeedback)
    private val btnCustomerService: android.widget.Button = view.findViewById(R.id.btnCustomerService)
    private val btnSendFeedback: android.widget.Button = view.findViewById(R.id.btnSendFeedback)

    fun bind(item: ChatItem.FeedbackRequest) {
        Log.d("FeedbackRequestViewHolder", "bind方法被调用，item.taskUuid=${item.taskUuid}, item.taskQuery=${item.taskQuery}, item.isException=${item.isException}")

        var selectedRating = 0
        val stars = listOf(btnStar1, btnStar2, btnStar3, btnStar4, btnStar5)

        // 根据是否为异常情况设置标题
        if (item.isException) {
            llStarRating.visibility = View.GONE
            tvFeedbackTitle.text = fragment.getString(R.string.feedback_title_exception)
        } else {
            llStarRating.visibility = View.VISIBLE
            tvFeedbackTitle.text = fragment.getString(R.string.feedback_title)
        }
        val starOn = android.R.drawable.btn_star_big_on
        val starOff = android.R.drawable.btn_star_big_off

        fun updateStars(rating: Int) {
            stars.forEachIndexed { index, btn ->
                btn.setImageResource(if (index < rating) starOn else starOff)
            }
        }

        // 异常情况：隐藏星星，使用-1作为评分
        if (item.isException) {
            stars.forEach { it.visibility = View.GONE }
            selectedRating = -1
        } else {
            stars.forEach { it.visibility = View.VISIBLE }
        }

        stars.forEachIndexed { index, btn ->
            btn.setOnClickListener {
                selectedRating = index + 1
                updateStars(selectedRating)
            }
        }
        updateStars(if (item.isException) -1 else 0)
        etFeedbackText.setText("")

        btnCancelFeedback.setOnClickListener {
            fragment.cancelFeedbackRequest(adapterPosition)
        }

        btnCustomerService.setOnClickListener {
            val rating = if (item.isException) -1 else selectedRating
            fragment.handleCustomerServiceButtonClick(rating, etFeedbackText.text.toString().trim(), adapterPosition, item.taskUuid, item.taskQuery)
        }

        btnSendFeedback.setOnClickListener {
            val rating = if (item.isException) -1 else selectedRating
            fragment.sendFeedback(rating, etFeedbackText.text.toString().trim(), adapterPosition, item.taskUuid, item.taskQuery, removeItem = true)
        }
    }
}

// endregion

// region ActionButtonsViewHolder

/**
 * 操作按钮 ViewHolder（复制、播报、收藏、分享、重新执行）
 */
class ActionButtonsViewHolder(
    view: View,
    private val fragment: ChatFragment
) : RecyclerView.ViewHolder(view) {

    private val btnCopy: ImageButton = view.findViewById(R.id.btnCopy)
    private val btnSpeak: ImageButton = view.findViewById(R.id.btnSpeak)
    private val btnFavorite: ImageButton = view.findViewById(R.id.btnFavorite)
    private val btnShare: ImageButton = view.findViewById(R.id.btnShare)
    private val btnRerun: ImageButton = view.findViewById(R.id.btnRerun)

    init {
        btnCopy.setOnClickListener {
            val message = (itemView.tag as? ChatItem.ActionButtons)?.message ?: ""
            copyToClipboard(message, "消息")
            Toast.makeText(fragment.requireContext(), "已复制", Toast.LENGTH_SHORT).show()
        }
        btnSpeak.setOnClickListener {
            Toast.makeText(fragment.requireContext(), "播报功能暂不支持", Toast.LENGTH_SHORT).show()
        }
        btnFavorite.setOnClickListener {
            Toast.makeText(fragment.requireContext(), "收藏功能暂不支持", Toast.LENGTH_SHORT).show()
        }
        btnShare.setOnClickListener {
            val message = (itemView.tag as? ChatItem.ActionButtons)?.message ?: ""
            if (message.isEmpty()) {
                Toast.makeText(fragment.requireContext(), "消息内容为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val skill = parseSOPFromMessage(message)
            if (skill != null) {
                showShareSkillDialog(skill)
            } else {
                Toast.makeText(fragment.requireContext(), fragment.getString(R.string.no_skill_card_in_message), Toast.LENGTH_SHORT).show()
            }
        }
        btnRerun.setOnClickListener {
            val query = (itemView.tag as? ChatItem.ActionButtons)?.query ?: ""
            if (query.isNotEmpty()) {
                fragment.executeQueryFromExternal(query)
            } else {
                Toast.makeText(fragment.requireContext(), "无法重新执行：查询为空", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun bind(item: ChatItem.ActionButtons) {
        itemView.tag = item
    }

    private fun parseSOPFromMessage(message: String): Skill? {
        try {
            if (!message.contains("###SOP###")) return null
            val sopPattern = Regex("###SOP###\\s*\\n(.+?)\\s*\\n(\\[[\\s\\S]*?\\])", RegexOption.DOT_MATCHES_ALL)
            val match = sopPattern.find(message) ?: return null
            val query = match.groupValues[1].trim()
            val stepsJsonStr = match.groupValues[2].trim()
            val steps = try {
                val gson = com.google.gson.Gson()
                val type = object : com.google.gson.reflect.TypeToken<List<String>>() {}.type
                gson.fromJson<List<String>>(stepsJsonStr, type)?.takeIf { it.isNotEmpty() } ?: return null
            } catch (e: Exception) {
                Log.e("ActionButtonsViewHolder", "解析步骤JSON失败: ${e.message}", e)
                return null
            }
            return Skill(
                id = java.util.UUID.randomUUID().toString(),
                title = query,
                steps = steps,
                createdAt = System.currentTimeMillis(),
                originalPurpose = query
            )
        } catch (e: Exception) {
            Log.e("ActionButtonsViewHolder", "解析SOP失败: ${e.message}", e)
            return null
        }
    }

    private fun showShareSkillDialog(skill: Skill) {
        android.app.AlertDialog.Builder(fragment.requireContext())
            .setTitle("选择分享方式")
            .setItems(arrayOf("分享给好友", "分享给群组", "我的技能")) { _, which ->
                when (which) {
                    0 -> shareSkillToFriend(skill)
                    1 -> shareSkillToGroup(skill)
                    2 -> saveSkillToMySkills(skill)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun shareSkillToFriend(skill: Skill) {
        val friends = FriendManager.getFriends(fragment.requireContext()).filter { it.status == "accepted" }
        if (friends.isEmpty()) {
            Toast.makeText(fragment.requireContext(), "暂无好友可分享", Toast.LENGTH_SHORT).show()
            return
        }
        val options = friends.map { it.nickname ?: it.imei.take(8) + "..." }.toTypedArray()
        android.app.AlertDialog.Builder(fragment.requireContext())
            .setTitle("选择好友")
            .setItems(options) { _, which ->
                shareSkillToFriend(friends[which].imei, skill)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun shareSkillToFriend(imei: String, skill: Skill) {
        fragment.shareSkillToFriend(imei, skill)
    }

    private fun shareSkillToGroup(skill: Skill) {
        fragment.shareSkillToGroup(skill)
    }

    private fun saveSkillToMySkills(skill: Skill) {
        fragment.saveSkillToMySkills(skill)
    }

    private fun copyToClipboard(text: String, label: String) {
        val clipboard = ContextCompat.getSystemService(itemView.context, ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(itemView.context, "已复制", Toast.LENGTH_SHORT).show()
    }
}

// endregion

// region AssistantThinkingViewHolder

/**
 * 小助手思考中状态 ViewHolder（非普通气泡）
 */
class AssistantThinkingViewHolder(
    parent: ViewGroup,
    private val onToggleExpanded: (String) -> Unit
) : RecyclerView.ViewHolder(createThinkingContainer(parent.context)) {
    private val avatarView: ImageView = (itemView as ViewGroup).findViewWithTag("thinking_avatar")
    private val titleView: TextView = (itemView as ViewGroup).findViewWithTag("thinking_title")
    private val arrowView: TextView = (itemView as ViewGroup).findViewWithTag("thinking_arrow")
    private val detailsView: TextView = (itemView as ViewGroup).findViewWithTag("thinking_details")
    private val headerRow: LinearLayout = (itemView as ViewGroup).findViewWithTag("thinking_header_row")

    fun bind(item: ChatItem.AssistantThinking) {
        val details = item.details.filter { it.isNotBlank() }
        val canExpand = details.isNotEmpty()
        titleView.text = if (item.isCompleted) "思考完成" else item.text
        arrowView.visibility = if (canExpand) View.VISIBLE else View.GONE
        arrowView.text = if (item.isExpanded) "⌄" else "›"
        detailsView.visibility = if (canExpand && item.isExpanded) View.VISIBLE else View.GONE
        detailsView.text = details.joinToString("\n") { "• $it" }
        headerRow.setOnClickListener {
            if (canExpand) onToggleExpanded(item.key)
        }

        val context = itemView.context
        val density = context.resources.displayMetrics.density
        val avatarSize = (36 * density).toInt()

        val assistant = item.assistantId
            ?.takeIf { CustomAssistantManager.isCustomAssistantId(it) }
            ?.let { CustomAssistantManager.getById(context, it) }

        if (assistant != null) {
            AvatarCacheManager.loadCustomAssistantAvatar(
                context = context,
                imageView = avatarView,
                assistant = assistant,
                cacheKey = "assistant_thinking_${assistant.id}",
                validationTag = assistant.id,
                sizePx = avatarSize
            )
        } else if (!item.avatarBase64.isNullOrBlank()) {
            AvatarCacheManager.loadBase64Avatar(
                context = context,
                imageView = avatarView,
                base64String = item.avatarBase64,
                defaultResId = R.drawable.ic_assistant_avatar,
                cacheKey = "assistant_thinking_${item.sender}"
            )
        } else {
            avatarView.setImageResource(R.drawable.ic_assistant_avatar)
        }
    }

    companion object {
        private fun createThinkingContainer(context: Context): android.widget.LinearLayout {
            val container = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.START or android.view.Gravity.TOP
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 4, 0, 4) }
                setPadding(8, 0, 8, 0)
            }

            val avatar = ImageView(context).apply {
                tag = "thinking_avatar"
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (36 * context.resources.displayMetrics.density).toInt(),
                    (36 * context.resources.displayMetrics.density).toInt()
                ).apply {
                    marginEnd = (8 * context.resources.displayMetrics.density).toInt()
                }
                scaleType = ImageView.ScaleType.CENTER_CROP
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setOval(0, 0, view.width, view.height)
                    }
                }
                clipToOutline = true
                setImageResource(R.drawable.ic_assistant_avatar)
            }
            container.addView(avatar)

            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setPadding(12, 8, 12, 8)
            }

            val headerRow = LinearLayout(context).apply {
                tag = "thinking_header_row"
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val title = TextView(context).apply {
                tag = "thinking_title"
                textSize = 13f
                setTextColor(0xFF666666.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            headerRow.addView(title)

            val arrow = TextView(context).apply {
                tag = "thinking_arrow"
                textSize = 14f
                setTextColor(0xFF888888.toInt())
                text = "⌄"
                setPadding(4, 0, 0, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            headerRow.addView(arrow)
            content.addView(headerRow)

            val details = TextView(context).apply {
                tag = "thinking_details"
                textSize = 12f
                setTextColor(0xFF8A8A8A.toInt())
                setPadding(0, 8, 0, 0)
                setLineSpacing(4f * context.resources.displayMetrics.density, 1f)
            }
            content.addView(details)
            container.addView(content)
            return container
        }
    }
}

// endregion
