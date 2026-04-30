package com.cloudcontrol.demo

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon
import kotlinx.coroutines.*
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.core.content.ContextCompat

/**
 * 接收到的图片消息ViewHolder（左侧对齐）
 */
class ReceivedImageMessageViewHolder(
    parent: ViewGroup,
    private val fragment: ChatFragment,
    private val markwon: Markwon,
    private val onAvatarClick: (String) -> Unit
) : RecyclerView.ViewHolder(createReceivedImageMessageContainer(parent.context)) {
    private enum class ImagePlacement {
        BEFORE_TEXT,
        AFTER_TEXT
    }

    private data class ParsedCaptionLayout(
        val captionText: String,
        val placement: ImagePlacement
    )
    
    private val messageContainer: android.widget.LinearLayout = itemView as android.widget.LinearLayout
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    fun bind(item: ChatItem.ReceivedImageMessage) {
        // 清除现有视图
        messageContainer.removeAllViews()

        val conversationId = fragment.currentConversation?.id
        val isGroupMessage = conversationId?.startsWith("group_") == true || conversationId == ConversationListFragment.CONVERSATION_ID_GROUP
        if (isGroupMessage && item.senderName.isNotBlank() && item.senderName != "我") {
            val senderNameView = android.widget.TextView(fragment.requireContext()).apply {
                text = item.senderName
                textSize = 12f
                setTextColor(0xFF8A8A8A.toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(
                        (44 * fragment.resources.displayMetrics.density).toInt(),
                        0,
                        0,
                        (4 * fragment.resources.displayMetrics.density).toInt()
                    )
                }
            }
            messageContainer.addView(senderNameView)
        }
        
        val imageFile = ChatImagePathGuard.resolveSafeLocalImageFile(fragment.requireContext(), item.imagePath)
        if (imageFile == null) {
            Log.e("ReceivedImageMessageViewHolder", "图片文件不存在: ${item.imagePath}")
            return
        }
        
        // 头像尺寸
        val avatarSize = (36 * fragment.resources.displayMetrics.density).toInt()
        val avatarMargin = (8 * fragment.resources.displayMetrics.density).toInt()
        val sideMargin = (16 * fragment.resources.displayMetrics.density).toInt()
        val leftRightFixedWidth = avatarSize + avatarMargin + sideMargin
        
        // 接收到的消息：创建内部容器（头像 + 图片），整体左对齐
        val contentContainer = android.widget.LinearLayout(fragment.requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = android.view.Gravity.TOP
        }
        
        // 添加发送者头像（在左侧）
        val avatarImageView = android.widget.ImageView(fragment.requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
                setMargins(0, 0, avatarMargin, 0)
            }
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            visibility = android.view.View.VISIBLE
            minimumWidth = avatarSize
            minimumHeight = avatarSize
            
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
            clipToOutline = true
            
            isClickable = true
            isFocusable = true
            setOnClickListener { onAvatarClick(item.senderName) }
            
            // 与左侧文字气泡一致：「我的电脑」会话固定电脑图标（非好友 IMEI，不能走 FriendManager）
            if (fragment.currentConversation?.id == ConversationListFragment.CONVERSATION_ID_ME) {
                setImageResource(R.drawable.ic_computer_avatar)
            } else {
                val senderNameForAvatar = item.senderName.replace(Regex("（[^（）]*）$"), "").trim().ifBlank { item.senderName }
                val customAssistantByName = CustomAssistantManager.getAll(fragment.requireContext()).find { it.name == senderNameForAvatar }
                val customAssistantById = if (customAssistantByName == null) CustomAssistantManager.getById(fragment.requireContext(), senderNameForAvatar) else null
                val customAssistant = customAssistantByName ?: customAssistantById
                when {
                    customAssistant != null -> {
                        AvatarCacheManager.loadCustomAssistantAvatar(
                            context = fragment.requireContext(),
                            imageView = this,
                            assistant = customAssistant,
                            cacheKey = "custom_assistant_${customAssistant.id}",
                            validationTag = customAssistant.id,
                            sizePx = avatarSize
                        )
                    }
                    ChatConstants.isMainAssistantSender(senderNameForAvatar) -> {
                        setImageResource(R.drawable.ic_assistant_avatar)
                    }
                    else -> {
                        // 从好友列表获取头像（使用AvatarCacheManager缓存，避免闪烁）
                        val friendImei = item.senderImei ?: item.senderName  // 优先使用IMEI，如果没有则使用senderName
                        val friend = FriendManager.getFriend(fragment.requireContext(), friendImei)
                        val avatarBase64 = friend?.avatar
                        val cacheKey = "friend_${friendImei}"
                        AvatarCacheManager.loadBase64Avatar(
                            context = fragment.requireContext(),
                            imageView = this,
                            base64String = avatarBase64,
                            defaultResId = R.drawable.ic_person,
                            cacheKey = cacheKey,
                            validationTag = friendImei
                        )
                    }
                }
            }
            background = ContextCompat.getDrawable(fragment.requireContext(), R.drawable.circle_background)
        }
        contentContainer.addView(avatarImageView)
        
        // 图文气泡圆角与主会话文字气泡一致（16dp）
        val bubbleCornerPx = 16f * fragment.resources.displayMetrics.density
        
        // 图文统一气泡容器（同一条消息内图片和文字共用一个气泡）
        val bubbleContainer = android.widget.LinearLayout(fragment.requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF0F0F0.toInt())
                cornerRadius = bubbleCornerPx
            }
        }
        contentContainer.addView(bubbleContainer)

        // 解析图文排版规则：未指定时默认“文字在前、图片在后”
        val parsedLayout = parseCaptionLayout(item.query)
        val captionText = parsedLayout.captionText
        val hasCaption = captionText.isNotEmpty() && !ChatConstants.isImageOnlyPlaceholderCaption(captionText)
        val bubbleHorizontalPadding = (12 * fragment.resources.displayMetrics.density).toInt()
        val bubbleVerticalPadding = (8 * fragment.resources.displayMetrics.density).toInt()

        // 图片容器
        val originalSize = (fragment.resources.displayMetrics.widthPixels * 0.6).toInt()
        val imageHeight = originalSize / 2
        val defaultImageWidth = imageHeight
        
        val imageContainer = android.widget.FrameLayout(fragment.requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                defaultImageWidth,
                imageHeight
            ).apply {
                val topMargin = when {
                    !hasCaption -> bubbleVerticalPadding
                    parsedLayout.placement == ImagePlacement.AFTER_TEXT -> 0
                    else -> bubbleVerticalPadding
                }
                val bottomMargin = when {
                    !hasCaption -> bubbleVerticalPadding
                    parsedLayout.placement == ImagePlacement.AFTER_TEXT -> bubbleVerticalPadding
                    else -> 0
                }
                setMargins(bubbleHorizontalPadding, topMargin, bubbleHorizontalPadding, bottomMargin)
            }
            
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF0F0F0.toInt())
                cornerRadius = bubbleCornerPx
            }
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, bubbleCornerPx)
                }
            }
            clipToOutline = true
        }
        
        // 图片占位符（显示缩略图）
        val placeholderContainer = android.widget.FrameLayout(fragment.requireContext()).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val imageUri = ChatImagePathGuard.toSafeViewUri(fragment.requireContext(), imageFile)
                if (imageUri != null) {
                    fragment.showImageFullScreen(imageUri)
                }
            }
        }
        
        // 图片缩略图
        val thumbnailImageView = android.widget.ImageView(fragment.requireContext()).apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            clipToOutline = false
        }
        
        // 异步加载图片缩略图
        mainScope.launch {
            try {
                Log.d("ReceivedImageMessageViewHolder", "开始加载图片: ${item.imagePath}")
                val thumbnail = withContext(Dispatchers.IO) {
                    fragment.loadImageThumbnail(item.imagePath, imageHeight)
                }
                if (thumbnail != null && !thumbnail.isRecycled && fragment.isAdded) {
                    Log.d("ReceivedImageMessageViewHolder", "图片加载成功: ${thumbnail.width}x${thumbnail.height}")
                    thumbnailImageView.setImageBitmap(thumbnail)
                } else {
                    Log.e("ReceivedImageMessageViewHolder", "图片加载失败: thumbnail=${thumbnail != null}, isRecycled=${thumbnail?.isRecycled}, isAdded=${fragment.isAdded}")
                    thumbnailImageView.setBackgroundColor(0xFFCCCCCC.toInt())
                    // 显示错误提示
                    thumbnailImageView.contentDescription = "图片加载失败"
                }
            } catch (e: Exception) {
                Log.e("ReceivedImageMessageViewHolder", "加载图片异常: ${e.message}", e)
                thumbnailImageView.setBackgroundColor(0xFFCCCCCC.toInt())
                thumbnailImageView.contentDescription = "图片加载异常: ${e.message}"
            }
        }
        
        placeholderContainer.addView(thumbnailImageView)
        imageContainer.addView(placeholderContainer)
        val captionView = if (hasCaption) {
            val captionView = android.widget.TextView(fragment.requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(
                        bubbleHorizontalPadding,
                        bubbleVerticalPadding,
                        bubbleHorizontalPadding,
                        bubbleVerticalPadding
                    )
                }
                textSize = 15f
                setTextColor(0xFF111111.toInt())
                setLineSpacing(0f, 1.15f)
                maxWidth = (fragment.resources.displayMetrics.widthPixels * 0.76f).toInt()
            }
            markwon.setMarkdown(captionView, captionText)
            captionView
        } else {
            null
        }

        if (captionView != null && parsedLayout.placement == ImagePlacement.AFTER_TEXT) {
            bubbleContainer.addView(captionView)
            bubbleContainer.addView(imageContainer)
        } else {
            bubbleContainer.addView(imageContainer)
            if (captionView != null) {
                bubbleContainer.addView(captionView)
            }
        }
        
        // 添加左侧占位符
        val leftSpacer = android.view.View(fragment.requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                leftRightFixedWidth,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        messageContainer.addView(leftSpacer)
        messageContainer.addView(contentContainer)
    }
    
    companion object {
        private fun parseCaptionLayout(rawCaption: String): ParsedCaptionLayout {
            var text = rawCaption.trim()
            var placement = ImagePlacement.AFTER_TEXT

            val firstMarkers = listOf(
                "[[image-first]]",
                "[layout:image-first]",
                "[图片在前]",
                "【图片在前】",
                "[图在前]",
                "【图在前】"
            )
            val lastMarkers = listOf(
                "[[image-last]]",
                "[layout:image-last]",
                "[图片在后]",
                "【图片在后】",
                "[图在后]",
                "【图在后】"
            )

            for (marker in firstMarkers) {
                if (text.contains(marker)) {
                    placement = ImagePlacement.BEFORE_TEXT
                    text = text.replace(marker, "")
                }
            }
            for (marker in lastMarkers) {
                if (text.contains(marker)) {
                    placement = ImagePlacement.AFTER_TEXT
                    text = text.replace(marker, "")
                }
            }

            return ParsedCaptionLayout(
                captionText = text.trim(),
                placement = placement
            )
        }

        private fun createReceivedImageMessageContainer(context: Context): android.widget.LinearLayout {
            return android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8, 0, 8)
                }
                // 接收到的消息左对齐
                gravity = android.view.Gravity.START or android.view.Gravity.TOP
            }
        }
    }
}

