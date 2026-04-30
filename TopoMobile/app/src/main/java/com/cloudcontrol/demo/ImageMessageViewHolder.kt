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
 * 图片消息ViewHolder
 */
class ImageMessageViewHolder(
    parent: ViewGroup,
    private val fragment: ChatFragment,
    private val markwon: Markwon,
    private val onAvatarClick: (String) -> Unit
) : RecyclerView.ViewHolder(createImageMessageContainer(parent.context)) {
    
    private val messageContainer: android.widget.LinearLayout = itemView as android.widget.LinearLayout
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var selectionIndicator: android.widget.TextView? = null
    
    fun bind(item: ChatItem.ImageMessage) {
        // 清除现有视图
        messageContainer.removeAllViews()
        
        val imageFile = ChatImagePathGuard.resolveSafeLocalImageFile(fragment.requireContext(), item.imagePath)
        if (imageFile == null) {
            Log.e("ImageMessageViewHolder", "图片文件不存在: ${item.imagePath}")
            return
        }
        
        // 头像尺寸
        val avatarSize = (36 * fragment.resources.displayMetrics.density).toInt()
        val avatarMargin = (8 * fragment.resources.displayMetrics.density).toInt()
        val userAvatarRightMargin = 0
        val sideMargin = (16 * fragment.resources.displayMetrics.density).toInt()
        val leftRightFixedWidth = avatarSize + avatarMargin + sideMargin
        
        // 用户消息：创建内部容器（图片 + 头像），整体右对齐
        val contentContainer = android.widget.LinearLayout(fragment.requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = android.view.Gravity.TOP
        }
        
        // 图片容器
        val originalSize = (fragment.resources.displayMetrics.widthPixels * 0.6).toInt()
        val imageHeight = originalSize / 2
        val defaultImageWidth = imageHeight
        
        val imageContainer = android.widget.FrameLayout(fragment.requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                defaultImageWidth,
                imageHeight
            )
            
            val cornerPx = 16f * fragment.resources.displayMetrics.density
            val drawable = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF95EC69.toInt())
                cornerRadius = cornerPx
            }
            background = drawable
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
            val thumbnail = withContext(Dispatchers.IO) {
                fragment.loadImageThumbnail(item.imagePath, imageHeight)
            }
            if (thumbnail != null && fragment.isAdded) {
                thumbnailImageView.setImageBitmap(thumbnail)
            } else {
                thumbnailImageView.setBackgroundColor(0xFFCCCCCC.toInt())
            }
        }
        
        placeholderContainer.addView(thumbnailImageView)
        imageContainer.addView(placeholderContainer)
        contentContainer.addView(imageContainer)
        
        // 添加用户头像（在右侧）
        val avatarImageView = android.widget.ImageView(fragment.requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
                setMargins(avatarMargin, 0, userAvatarRightMargin, 0)
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
            val displayName = item.senderName ?: "我"
            setOnClickListener { onAvatarClick(displayName) }
            
            // 用户头像：从个人主页获取
            val userProfile = ProfileManager.loadProfileLocally(fragment.requireContext())
            val avatarBase64 = userProfile?.avatar
            if (!avatarBase64.isNullOrEmpty()) {
                try {
                    val decodedBytes = Base64.decode(avatarBase64, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                    if (bitmap != null) {
                        setImageBitmap(bitmap)
                    } else {
                        setImageResource(R.drawable.ic_person)
                        background = ContextCompat.getDrawable(fragment.requireContext(), R.drawable.circle_background)
                    }
                } catch (e: Exception) {
                    Log.e("ImageMessageViewHolder", "加载用户头像失败: ${e.message}", e)
                    setImageResource(R.drawable.ic_person)
                    background = ContextCompat.getDrawable(fragment.requireContext(), R.drawable.circle_background)
                }
            } else {
                setImageResource(R.drawable.ic_person)
                background = ContextCompat.getDrawable(fragment.requireContext(), R.drawable.circle_background)
            }
        }
        contentContainer.addView(avatarImageView)
        
        // 消息内容行（左侧多选圆圈 + 原有内容）
        val messageRow = android.widget.LinearLayout(fragment.requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val indicator = createSelectionIndicatorView()
        messageRow.addView(indicator)
        selectionIndicator = indicator

        val bubbleRow = android.widget.LinearLayout(fragment.requireContext()).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.TOP
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        // 添加左侧占位符
        val leftSpacer = android.view.View(fragment.requireContext()).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                leftRightFixedWidth,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        bubbleRow.addView(leftSpacer)
        bubbleRow.addView(contentContainer)
        messageRow.addView(bubbleRow)
        messageContainer.addView(messageRow)

        // 有说明文字时在图片下方追加气泡（占位符 [图片] 不再重复展示）
        if (item.query.isNotEmpty() && !ChatConstants.isImageOnlyPlaceholderCaption(item.query)) {
            // 添加间距（在图片和文本消息之间）
            val spacing = android.view.View(fragment.requireContext()).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    (8 * fragment.resources.displayMetrics.density).toInt()  // 8dp间距
                )
            }
            messageContainer.addView(spacing)

            val displayName = item.senderName ?: "我"
            val textBubble = fragment.createMessageBubbleForAdapter(
                sender = displayName,
                message = item.query,
                isUserMessage = true,
                isComplete = false,
                isAnswer = false,
                markwon = markwon,
                onAvatarClick = onAvatarClick
            )
            messageContainer.addView(textBubble)
        }

        updateSelectionState(fragment.isMessageSelected(item.timestamp), fragment.isMultiSelectMode)
    }

    fun updateSelectionState(isSelected: Boolean, isMultiSelectMode: Boolean) {
        val indicator = selectionIndicator
        if (indicator != null) {
            indicator.visibility = if (isMultiSelectMode) android.view.View.VISIBLE else android.view.View.GONE
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

    private fun createSelectionIndicatorView(): android.widget.TextView {
        val density = itemView.resources.displayMetrics.density
        val size = (20 * density).toInt()
        val indicatorMarginEnd = (8 * density).toInt()
        return android.widget.TextView(itemView.context).apply {
            gravity = android.view.Gravity.CENTER
            textSize = 12f
            visibility = android.view.View.GONE
            layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
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
        private fun createImageMessageContainer(context: Context): android.widget.LinearLayout {
            return android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 8, 0, 8)
                }
                // 用户消息右对齐
                gravity = android.view.Gravity.END or android.view.Gravity.TOP
            }
        }
    }
}

