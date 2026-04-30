package com.cloudcontrol.demo

import android.graphics.Canvas
import android.graphics.Paint
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ImageSpan
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cloudcontrol.demo.databinding.FragmentChatBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 聊天页 UI 装配：背景、未读角标、列表在 Fragment 显隐时的动画优化。
 * 从 [ChatFragment] 拆出以降低单文件体积。
 */
object ChatUiSetupController {
    private const val TOPOCLAW_CUSTOM_ASSISTANT_ID = "custom_topoclaw"

    fun applyChatBackground(fragment: ChatFragment, binding: FragmentChatBinding) {
        with(fragment) {
            try {
                val conversationId = currentConversation?.id ?: "default"
                val prefs = requireContext().getSharedPreferences("chat_settings", android.content.Context.MODE_PRIVATE)
                val backgroundType = prefs.getString("chat_background_type_$conversationId", "default")

                when (backgroundType) {
                    "default" -> {
                        binding.rvChatMessages.setBackgroundColor(0xFFF9F9F9.toInt())
                    }
                    "preset" -> {
                        prefs.edit()
                            .putString("chat_background_type_$conversationId", "default")
                            .remove("chat_background_preset_$conversationId")
                            .apply()
                        binding.rvChatMessages.setBackgroundColor(0xFFF9F9F9.toInt())
                    }
                    "custom" -> {
                        val backgroundPath = prefs.getString("chat_background_path_$conversationId", null)
                        val backgroundUriString = prefs.getString("chat_background_uri_$conversationId", null)

                        if (backgroundPath != null) {
                            val backgroundFile = java.io.File(backgroundPath)
                            if (backgroundFile.exists()) {
                                mainScope.launch {
                                    try {
                                        val bitmap = withContext(Dispatchers.IO) {
                                            android.graphics.BitmapFactory.decodeFile(backgroundFile.absolutePath)
                                        }

                                        if (bitmap != null) {
                                            withContext(Dispatchers.Main) {
                                                val drawable = CenterCropDrawable(resources, bitmap)
                                                binding.rvChatMessages.background = drawable
                                            }
                                        } else {
                                            Log.w(ChatConstants.TAG, "无法加载背景图片，使用默认背景")
                                            withContext(Dispatchers.Main) {
                                                binding.rvChatMessages.setBackgroundColor(0xFFF9F9F9.toInt())
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(ChatConstants.TAG, "加载背景图片失败: ${e.message}", e)
                                        withContext(Dispatchers.Main) {
                                            binding.rvChatMessages.setBackgroundColor(0xFFF9F9F9.toInt())
                                        }
                                    }
                                }
                            } else {
                                Log.w(ChatConstants.TAG, "背景图片文件不存在: $backgroundPath")
                                binding.rvChatMessages.setBackgroundColor(0xFFF9F9F9.toInt())
                            }
                        } else if (backgroundUriString != null) {
                            try {
                                val backgroundUri = android.net.Uri.parse(backgroundUriString)
                                mainScope.launch {
                                    try {
                                        val bitmap = withContext(Dispatchers.IO) {
                                            requireContext().contentResolver.openInputStream(backgroundUri)?.use { inputStream ->
                                                android.graphics.BitmapFactory.decodeStream(inputStream)
                                            }
                                        }

                                        if (bitmap != null) {
                                            withContext(Dispatchers.Main) {
                                                val drawable = CenterCropDrawable(resources, bitmap)
                                                binding.rvChatMessages.background = drawable
                                            }
                                        } else {
                                            Log.w(ChatConstants.TAG, "无法加载背景图片，使用默认背景")
                                            withContext(Dispatchers.Main) {
                                                binding.rvChatMessages.setBackgroundColor(0xFFF9F9F9.toInt())
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.e(ChatConstants.TAG, "加载背景图片失败: ${e.message}", e)
                                        withContext(Dispatchers.Main) {
                                            binding.rvChatMessages.setBackgroundColor(0xFFF9F9F9.toInt())
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(ChatConstants.TAG, "解析背景URI失败: ${e.message}", e)
                                binding.rvChatMessages.setBackgroundColor(0xFFF9F9F9.toInt())
                            }
                        } else {
                            binding.rvChatMessages.setBackgroundColor(0xFFF9F9F9.toInt())
                        }
                    }
                    else -> {
                        binding.rvChatMessages.setBackgroundColor(0xFFF9F9F9.toInt())
                    }
                }
            } catch (e: Exception) {
                Log.e(ChatConstants.TAG, "应用聊天背景失败: ${e.message}", e)
                binding.rvChatMessages.setBackgroundColor(0xFFF9F9F9.toInt())
            }
        }
    }

    fun setupUnreadBadge(fragment: ChatFragment, binding: FragmentChatBinding) {
        with(fragment) {
            if (currentConversation?.id != ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE) {
                return
            }

            val titleBar = binding.llTitleBar
            val titleTextView = binding.tvConversationName

            val badgeTag = "unread_badge"
            var badgeView = titleBar.findViewWithTag<android.widget.TextView>(badgeTag)

            if (badgeView == null) {
                badgeView = android.widget.TextView(requireContext()).apply {
                    tag = badgeTag
                    textSize = 10f
                    setTextColor(android.graphics.Color.WHITE)
                    gravity = android.view.Gravity.CENTER
                    minWidth = (24 * resources.displayMetrics.density).toInt()
                    minHeight = (24 * resources.displayMetrics.density).toInt()
                    setPadding(
                        (8 * resources.displayMetrics.density).toInt(),
                        (4 * resources.displayMetrics.density).toInt(),
                        (8 * resources.displayMetrics.density).toInt(),
                        (4 * resources.displayMetrics.density).toInt()
                    )
                    visibility = android.view.View.GONE
                }

                val drawable = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0xFFFF0000.toInt())
                }
                badgeView.background = drawable

                val titleIndex = titleBar.indexOfChild(titleTextView)
                val layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginStart = (8 * resources.displayMetrics.density).toInt()
                }
                badgeView.layoutParams = layoutParams
                titleBar.addView(badgeView, titleIndex + 1)
            }

            updateUnreadBadge(fragment, binding)
        }
    }

    fun updateUnreadBadge(fragment: ChatFragment, binding: FragmentChatBinding) {
        with(fragment) {
            if (currentConversation?.id != ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE) {
                return
            }

            val titleBar = binding.llTitleBar
            val badgeView = titleBar.findViewWithTag<android.widget.TextView>("unread_badge") ?: return

            val unreadCount = CustomerServiceUnreadManager.getUnreadCount(requireContext())

            if (unreadCount > 0) {
                badgeView.text = if (unreadCount > 99) "99+" else unreadCount.toString()
                badgeView.visibility = android.view.View.VISIBLE
                Log.d(ChatConstants.TAG, "显示未读消息红点: $unreadCount")
            } else {
                badgeView.visibility = android.view.View.GONE
                Log.d(ChatConstants.TAG, "隐藏未读消息红点")
            }
        }
    }

    fun optimizeRecyclerViewForTransition(fragment: ChatFragment, isHiding: Boolean) {
        val binding = fragment.peekChatBinding() ?: return
        val recyclerView = binding.rvChatMessages

        if (isHiding) {
            recyclerView.itemAnimator = null
            recyclerView.setHasFixedSize(true)
        } else {
            recyclerView.postDelayed({
                if (fragment.hasChatBinding && fragment.isAdded) {
                    recyclerView.itemAnimator = DefaultItemAnimator().apply {
                        addDuration = 200
                        removeDuration = 200
                        moveDuration = 200
                        changeDuration = 200
                    }
                    recyclerView.setHasFixedSize(false)
                }
            }, 100)
        }
    }

    /**
     * 原 [ChatFragment.setupUI] 主体：标题栏、按钮、录屏回调、RecyclerView、背景等。
     */
    fun setupMainUi(fragment: ChatFragment, binding: FragmentChatBinding) {
        with(fragment) {
            currentConversation?.let { conversation ->
                binding.tvConversationName.text = DisplayNameHelper.getDisplayName(requireContext(), conversation.name)
            }
            updateTopoClawPcOnlineIndicator(fragment, binding)

            binding.tvConversationName.setOnClickListener {
                val isTopoClawConversation = currentConversation?.id == TOPOCLAW_CUSTOM_ASSISTANT_ID
                if (!isTopoClawConversation) return@setOnClickListener
                PcOnlineStatusManager.checkNow(requireContext())
                val hint = if (PcOnlineStatusManager.isPcOnline()) {
                    "正在检测电脑在线状态（当前：在线）"
                } else {
                    "正在检测电脑在线状态（当前：离线）"
                }
                Toast.makeText(requireContext(), hint, Toast.LENGTH_SHORT).show()
            }

            if (currentConversation?.id == ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE) {
                setupUnreadBadge()
            }

            binding.btnBack.setOnClickListener {
                handleBackPress()
            }

            binding.btnMore.setOnClickListener {
                if (peekChatBinding() == null || !isAdded) return@setOnClickListener
                currentConversation?.let { conversation ->
                    try {
                        val profileFragment = ConversationProfileFragment.newInstance(conversation)
                        parentFragmentManager.beginTransaction()
                            .setCustomAnimations(
                                R.anim.slide_in_from_right,
                                R.anim.slide_out_to_left,
                                R.anim.slide_in_from_left,
                                R.anim.slide_out_to_right
                            )
                            .hide(fragment)
                            .add(R.id.fragmentContainer, profileFragment)
                            .addToBackStack(null)
                            .commitAllowingStateLoss()
                    } catch (e: Exception) {
                        Log.e(ChatConstants.TAG, "导航到聊天对象主页失败: ${e.message}", e)
                    }
                }
            }

            binding.btnSendChat.setOnClickListener {
                if (isTaskRunning || isCustomAssistantProcessing) {
                    addChatMessage("小助手", "用户中断任务", isAnswer = true)
                    val taskUuidForFeedback = chatUuid
                    val taskQueryForFeedback = currentQuery
                    val convId = currentConversation?.id ?: ""
                    if (convId.isNotBlank()) {
                        (activity as? MainActivity)?.getCustomerServiceWebSocket()?.sendAssistantStopTask(convId)
                    }
                    if (isCustomAssistantProcessing && !isTaskRunning) {
                        isCustomAssistantProcessing = false
                        updateButtonVisibility()
                    }
                    stopTask()
                    notifyTaskComplete()
                    showFeedbackRequest(taskUuidForFeedback, taskQueryForFeedback, isException = true)
                } else {
                    sendChatMessage()
                }
            }

            binding.btnStopTask.setOnClickListener {
                addChatMessage("小助手", "用户中断任务", isAnswer = true)
                val taskUuidForFeedback = chatUuid
                val taskQueryForFeedback = currentQuery
                val convId = currentConversation?.id ?: ""
                if (convId.isNotBlank()) {
                    (activity as? MainActivity)?.getCustomerServiceWebSocket()?.sendAssistantStopTask(convId)
                }
                stopTask()
                notifyTaskComplete()
                showFeedbackRequest(taskUuidForFeedback, taskQueryForFeedback, isException = true)
            }

            binding.llRecommendationsHeader.setOnClickListener {
                vibrate()
                toggleRecommendationsPanel()
                updateRecommendationsHeaderState()
            }

            binding.tvProButton.setOnClickListener {
                vibrate()
                val wasEnabled = isProModeEnabled
                isProModeEnabled = !isProModeEnabled
                updateProButtonState()
                if (!wasEnabled && isProModeEnabled) {
                    showBetaWelcomePopup(binding.tvProButton)
                }
            }

            binding.btnNewTopic.setOnClickListener {
                vibrate()
                clearHistoryAndSummary()
                Toast.makeText(requireContext(), "开始新话题", Toast.LENGTH_SHORT).show()
            }

            binding.btnCustomAssistantMore.setOnClickListener {
                vibrate()
                setupCustomAssistantDrawerContent()
                binding.overlayCustomAssistantDrawer.visibility = View.VISIBLE
            }
            binding.customAssistantDrawerScrim.setOnClickListener {
                binding.overlayCustomAssistantDrawer.visibility = View.GONE
            }

            binding.btnFilterUsers.setOnClickListener {
                vibrate()
                showUserFilterDialog()
            }

            binding.btnScrollToBottom.setOnClickListener {
                scrollToBottom()
            }

            updateProButtonState()
            updateRecommendationsHeaderState()
            updateRecommendationsButtonsVisibility()
            initRecommendationsPanel()

            binding.btnQueryDropdown.setOnClickListener {
                if (shouldUseEmojiInputButton()) {
                    if (isEmojiPanelVisible) {
                        hideEmojiPanel()
                        showKeyboard()
                    } else {
                        toggleEmojiPanel()
                    }
                } else {
                    showPresetQueryMenu(it)
                }
            }

            initEmojiPanel()

            binding.btnAddMedia.setOnClickListener {
                toggleMediaPanel()
            }

            binding.btnVoiceInput.setOnClickListener {
                toggleVoicePanel()
            }

            binding.btnVoiceRecord.setOnClickListener {
                if (!isVoiceRecording) {
                    startVoiceRecognition()
                } else {
                    stopVoiceRecognition()
                }
            }

            binding.llImageOption.setOnClickListener {
                pickImage()
            }

            systemMessagesContainer = binding.llSystemMessagesContainer
            systemMessagesTitle = binding.tvSystemMessagesTitle
            systemMessagesScrollView = binding.svSystemMessagesContent
            systemMessagesContent = binding.llSystemMessagesContent

            systemMessagesTitle?.visibility = View.GONE

            systemMessagesTitle?.let { titleView ->
                val originalBackground = titleView.background
                titleView.setOnTouchListener { view, motionEvent ->
                    when (motionEvent.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            view.alpha = 0.7f
                            val pressedDrawable = android.graphics.drawable.GradientDrawable().apply {
                                setColor(0xFFE8E8E8.toInt())
                                cornerRadius = 0f
                            }
                            view.background = pressedDrawable
                            true
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            view.alpha = 1.0f
                            view.background = originalBackground
                            toggleSystemMessages()
                            true
                        }
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            view.alpha = 1.0f
                            view.background = originalBackground
                            true
                        }
                        else -> false
                    }
                }
                titleView.isClickable = true
                titleView.isFocusable = true
            }

            binding.btnRemoveImage.setOnClickListener {
                removeSelectedImage()
            }

            binding.ivSelectedImage.setOnClickListener {
                selectedImageUri?.let { uri ->
                    showImageFullScreen(uri)
                }
            }

            binding.llVideoOption.setOnClickListener {
                if (isRecording) {
                    stopScreenRecording()
                } else {
                    startScreenRecording()
                }
            }

            binding.llSkillOption.setOnClickListener {
                hideMediaPanel()
                val conversationId = currentConversation?.id
                if (conversationId == ConversationListFragment.CONVERSATION_ID_ASSISTANT ||
                    conversationId == ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING) {
                    try {
                        val skillSelectDialog = SkillSelectDialog(
                            requireContext(),
                            parentFragmentManager,
                            onSkillSend = { skill -> sendSkillAsQuery(skill) }
                        )
                        skillSelectDialog.show()
                    } catch (e: Exception) {
                        Log.e(ChatConstants.TAG, "显示技能选择弹窗失败: ${e.message}", e)
                        Toast.makeText(requireContext(), "显示技能选择弹窗失败", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(requireContext(), "功能正在开发中", Toast.LENGTH_SHORT).show()
                }
            }

            recordingCallback = object : RecordingCallback {
                override fun onRecordingStarted(videoPath: String) {
                    activity?.runOnUiThread {
                        isRecording = true
                        hideMediaPanel()
                        Toast.makeText(requireContext(), "录屏已开始", Toast.LENGTH_SHORT).show()
                        Log.d(ChatConstants.TAG, "录屏已开始: $videoPath")
                        activity?.let { act ->
                            Log.d(ChatConstants.TAG, "准备显示录屏悬浮窗，activity=$act")
                            RecordingOverlayManager.show(act) {
                                mainScope.launch {
                                    try {
                                        withContext(Dispatchers.Main) {
                                            try {
                                                val success = (activity as? MainActivity)?.bringAppToForeground() ?: false
                                                if (success) {
                                                    Log.d(ChatConstants.TAG, "已返回到TopoClaw应用页面")
                                                    delay(300)
                                                } else {
                                                    Log.w(ChatConstants.TAG, "返回到TopoClaw应用页面失败")
                                                }
                                            } catch (e: Exception) {
                                                Log.e(ChatConstants.TAG, "返回到应用页面时出错: ${e.message}", e)
                                            }
                                        }
                                        stopScreenRecording()
                                    } catch (e: Exception) {
                                        Log.e(ChatConstants.TAG, "点击悬浮窗处理失败: ${e.message}", e)
                                    }
                                }
                            }
                        }
                    }
                }

                override fun onRecordingStopped(videoPath: String, screenWidth: Int, screenHeight: Int) {
                    mainScope.launch {
                        try {
                            isRecording = false
                            Log.d(ChatConstants.TAG, "录屏已停止: $videoPath")
                            RecordingOverlayManager.hide()
                            withContext(Dispatchers.Main) {
                                try {
                                    val success = (activity as? MainActivity)?.bringAppToForeground() ?: false
                                    if (success) {
                                        Log.d(ChatConstants.TAG, "已返回到TopoClaw应用页面")
                                        delay(300)
                                    } else {
                                        Log.w(ChatConstants.TAG, "返回到TopoClaw应用页面失败")
                                    }
                                } catch (e: Exception) {
                                    Log.e(ChatConstants.TAG, "返回到应用页面时出错: ${e.message}", e)
                                }
                            }
                            withContext(Dispatchers.Main) {
                                addVideoMessage(videoPath)
                            }
                            uploadVideoToCloud(videoPath, screenWidth, screenHeight)
                        } catch (e: Exception) {
                            Log.e(ChatConstants.TAG, "处理录屏停止失败: ${e.message}", e)
                            withContext(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "处理录屏失败: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                override fun onRecordingError(error: String) {
                    activity?.runOnUiThread {
                        isRecording = false
                        Toast.makeText(requireContext(), "录屏错误: $error", Toast.LENGTH_SHORT).show()
                        Log.e(ChatConstants.TAG, "录屏错误: $error")
                        RecordingOverlayManager.hide()
                    }
                }
            }
            ScreenRecordingService.setCallback(recordingCallback)

            setupKeyboardListener()
            setupInputTextWatcher()
            setupClickToDismiss()
            updateButtonVisibility()
            setupRecyclerView()

            applyChatBackground()
        }
    }

    fun updateTopoClawPcOnlineIndicator(fragment: ChatFragment, binding: FragmentChatBinding) {
        with(fragment) {
            val conversationId = currentConversation?.id
            val showLamp = conversationId == TOPOCLAW_CUSTOM_ASSISTANT_ID && PcOnlineStatusManager.isPcOnline()
            val titleView = binding.tvConversationName
            val title = currentConversation?.let { conversation ->
                DisplayNameHelper.getDisplayName(requireContext(), conversation.name)
            } ?: titleView.text?.toString().orEmpty()

            // 确保不再使用 compoundDrawable，避免图标被布局挤到标题控件最右侧。
            titleView.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0)
            if (showLamp) {
                val lightDrawable = ContextCompat.getDrawable(requireContext(), R.drawable.ic_lightbulb)
                if (lightDrawable == null) {
                    titleView.text = title
                    return
                }
                val sizePx = (14 * resources.displayMetrics.density).toInt()
                val verticalOffsetPx = (3 * resources.displayMetrics.density).toInt()
                lightDrawable.setBounds(0, 0, sizePx, sizePx)
                val titleWithLamp = SpannableString("$title ")
                val iconStart = titleWithLamp.length - 1
                titleWithLamp.setSpan(
                    object : ImageSpan(lightDrawable, ImageSpan.ALIGN_BOTTOM) {
                        override fun draw(
                            canvas: Canvas,
                            text: CharSequence,
                            start: Int,
                            end: Int,
                            x: Float,
                            top: Int,
                            y: Int,
                            bottom: Int,
                            paint: Paint
                        ) {
                            canvas.save()
                            canvas.translate(0f, -verticalOffsetPx.toFloat())
                            super.draw(canvas, text, start, end, x, top, y, bottom, paint)
                            canvas.restore()
                        }
                    },
                    iconStart,
                    titleWithLamp.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                titleView.text = titleWithLamp
            } else {
                titleView.text = title
            }
        }
    }

    /**
     * 原 [ChatFragment.setupRecyclerView]：适配器、滚动与加载更多。
     */
    fun setupRecyclerView(fragment: ChatFragment, binding: FragmentChatBinding) {
        with(fragment) {
            if (swipeThresholdPx == 0f) {
                val density = resources.displayMetrics.density
                swipeThresholdPx = 30f * density
            }

            chatAdapter = ChatAdapter(
                fragment = this,
                onAvatarClick = { sender ->
                    when (sender) {
                        "我", "用户" -> {
                            val userProfileFragment = UserProfileFragment.newInstance()
                            parentFragmentManager.beginTransaction()
                                .setCustomAnimations(
                                    R.anim.slide_in_from_right,
                                    R.anim.slide_out_to_left,
                                    R.anim.slide_in_from_left,
                                    R.anim.slide_out_to_right
                                )
                                .hide(fragment)
                                .add(R.id.fragmentContainer, userProfileFragment)
                                .addToBackStack(null)
                                .commitAllowingStateLoss()
                        }
                        else -> {
                            currentConversation?.let { conversation ->
                                val profileFragment = ConversationProfileFragment.newInstance(conversation)
                                parentFragmentManager.beginTransaction()
                                    .setCustomAnimations(
                                        R.anim.slide_in_from_right,
                                        R.anim.slide_out_to_left,
                                        R.anim.slide_in_from_left,
                                        R.anim.slide_out_to_right
                                    )
                                    .hide(fragment)
                                    .add(R.id.fragmentContainer, profileFragment)
                                    .addToBackStack(null)
                                    .commitAllowingStateLoss()
                            }
                        }
                    }
                },
                onSystemMessagesToggle = {
                    toggleSystemMessages()
                },
                onToggleMessageSelection = { timestamp ->
                    toggleMessageSelection(timestamp)
                },
                onIsMessageSelected = { isMessageSelected(it) },
                onIsMultiSelectMode = { isMultiSelectMode }
            )

            binding.rvChatMessages.layoutManager = LinearLayoutManager(requireContext()).apply {
                reverseLayout = false
                stackFromEnd = true
                isItemPrefetchEnabled = false
            }
            binding.rvChatMessages.adapter = chatAdapter

            binding.rvChatMessages.setItemViewCacheSize(2)
            binding.rvChatMessages.setHasFixedSize(false)

            binding.rvChatMessages.setRecycledViewPool(RecyclerView.RecycledViewPool().apply {
                setMaxRecycledViews(0, 10)
                setMaxRecycledViews(1, 10)
                setMaxRecycledViews(2, 10)
                setMaxRecycledViews(3, 15)
                setMaxRecycledViews(4, 5)
                setMaxRecycledViews(5, 5)
            })

            binding.rvChatMessages.isNestedScrollingEnabled = false

            binding.rvChatMessages.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val firstVisiblePosition = layoutManager.findFirstVisibleItemPosition()
                    if (firstVisiblePosition == 0 && hasMoreMessages && !isLoadingMore) {
                        loadMoreHistoryMessages()
                    }
                    if (isKeyboardVisible && dy != 0) {
                        hideKeyboard()
                    }
                    checkScrollToBottomButtonVisibility()
                }
            })

            binding.rvChatMessages.post {
                checkScrollToBottomButtonVisibility()
            }
        }
    }
}
