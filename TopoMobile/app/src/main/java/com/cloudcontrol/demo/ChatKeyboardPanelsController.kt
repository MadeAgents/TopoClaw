package com.cloudcontrol.demo

import android.view.View
import android.view.ViewTreeObserver
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.cloudcontrol.demo.databinding.FragmentChatBinding
import com.cloudcontrol.demo.R

/**
 * 点击空白收起键盘/面板、列表滑动收起键盘、[ViewTreeObserver] 监听 IME 与面板联动。
 */
object ChatKeyboardPanelsController {

    fun setupClickToDismiss(fragment: ChatFragment, binding: FragmentChatBinding) {
        with(fragment) {
        // 给RecyclerView添加触摸监听，检测滑动以收起键盘
        rvChatMessagesTouchListener = View.OnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    // 记录触摸起始位置
                    touchStartX = event.x
                    touchStartY = event.y
                    isSwipeDetected = false
                    
                    // 延迟检查，避免影响TextView的文本选择
                    view.postDelayed({
                        // 检查Fragment是否还存在
                        if (!hasChatBinding) return@postDelayed
                        // 如果还没有检测到滑动，检查是否有文本被选中
                        if (!isSwipeDetected) {
                            val hasSelection = checkHasTextSelection()
                            if (!hasSelection) {
                                // 如果没有文本被选中，关闭键盘
                                hideKeyboard()
                                // 如果媒体面板显示，也隐藏它
                                if (isMediaPanelVisible && hasChatBinding) {
                                    hideMediaPanel()
                                }
                                // 如果语音面板显示，也隐藏它
                                if (isVoicePanelVisible && hasChatBinding) {
                                    hideVoicePanel()
                                }
                            }
                        }
                    }, 100)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    // 检测滑动距离（优先检测垂直滑动，因为用户通常垂直滑动来滚动列表）
                    val deltaX = kotlin.math.abs(event.x - touchStartX)
                    val deltaY = kotlin.math.abs(event.y - touchStartY)
                    
                    // 使用更灵敏的阈值：只要垂直滑动超过15像素，或总滑动距离超过20像素，就识别为滑动
                    val minVerticalSwipe = 15f // 垂直滑动阈值（像素）
                    val minTotalSwipe = 20f // 总滑动距离阈值（像素）
                    val totalDistance = deltaX + deltaY // 简化计算，避免sqrt
                    
                    // 如果检测到滑动且键盘弹起，则收起键盘
                    if (isKeyboardVisible && !isSwipeDetected && 
                        (deltaY > minVerticalSwipe || totalDistance > minTotalSwipe)) {
                        isSwipeDetected = true
                        hideKeyboard()
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    // 重置滑动检测状态
                    isSwipeDetected = false
                }
            }
            false // 不拦截事件，让RecyclerView正常滚动
        }
        binding.rvChatMessages.setOnTouchListener(rvChatMessagesTouchListener)
        
        // 给根布局添加点击监听（作为备用，处理点击输入框区域外的空白处）
        binding.root.setOnClickListener { clickedView ->
            // 检查Fragment是否还存在
            if (!hasChatBinding) return@setOnClickListener
            
            // 如果表情面板显示，检查点击的View是否是表情面板或其子View
            if (isEmojiPanelVisible) {
                var isEmojiPanelOrChild = false
                var currentView: View? = clickedView
                while (currentView != null) {
                    if (currentView == binding.llEmojiPanel || currentView == binding.gvEmoji) {
                        isEmojiPanelOrChild = true
                        break
                    }
                    currentView = currentView.parent as? View
                }
                // 如果点击的是表情面板或其子View，不隐藏
                if (isEmojiPanelOrChild) {
                    return@setOnClickListener
                }
                // 否则隐藏表情面板
                hideEmojiPanel()
            }
            
            // 检查当前焦点是否在输入框
            val currentFocus = activity?.currentFocus
            if (currentFocus != binding.etChatInput) {
                clearTextSelection()
                hideKeyboard()
                hideMentionPopup()  // 隐藏@提及候选列表
                // 如果媒体面板显示，也隐藏它
                if (isMediaPanelVisible && hasChatBinding) {
                    hideMediaPanel()
                }
                // 如果语音面板显示，也隐藏它
                if (isVoicePanelVisible && hasChatBinding) {
                    hideVoicePanel()
                }
            }
        }
        
        // 给输入框添加焦点变化监听
        binding.etChatInput.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                // 获得焦点时，延迟滚动到底部，确保键盘弹起后布局调整完成
                view.postDelayed({
                    if (hasChatBinding) {
                        scrollToBottom()
                    }
                }, 200) // 延迟200ms，等待键盘弹起和布局调整
                // 延迟更新按钮可见性，确保键盘弹起后布局调整完成（特别是管理员模式下）
                binding.llInputContainer.postDelayed({
                    if (hasChatBinding) {
                        updateButtonVisibility()
                    }
                }, 250) // 延迟250ms，等待键盘完全弹起和布局调整完成
            } else {
                hideKeyboard()
            }
        }
        
        // 点击输入框时，如果媒体面板或语音面板或表情面板显示，先隐藏面板，并滚动到底部
        binding.etChatInput.setOnClickListener {
            if (isMediaPanelVisible && hasChatBinding) {
                hideMediaPanel()
            }
            if (isVoicePanelVisible && hasChatBinding) {
                hideVoicePanel()
            }
            if (isEmojiPanelVisible && hasChatBinding) {
                hideEmojiPanel()
            }
            // 点击输入框时自动滚动到底部
            scrollToBottom()
            // 确保输入框获得焦点并显示键盘
            binding.etChatInput.requestFocus()
            binding.etChatInput.post {
                val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(binding.etChatInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            // 延迟更新按钮可见性，确保键盘弹起后布局调整完成（特别是管理员模式下）
            binding.llInputContainer.postDelayed({
                if (hasChatBinding) {
                    updateButtonVisibility()
                }
            }, 200) // 延迟200ms，等待键盘弹起和布局调整完成
        }
        }
    }

    fun setupKeyboardListener(fragment: ChatFragment, binding: FragmentChatBinding) {
        with(fragment) {
        keyboardLayoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            // 性能优化：限制检查频率，避免频繁调用
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastKeyboardCheckTime < ChatConstants.KEYBOARD_CHECK_THROTTLE_MS) {
                return@OnGlobalLayoutListener
            }
            lastKeyboardCheckTime = currentTime
            
            // 检查Fragment是否还存在
            val currentBinding = peekChatBinding() ?: return@OnGlobalLayoutListener
            val rootView = currentBinding.root
            
            // 使用WindowInsets获取键盘高度（更准确）
            val insets = androidx.core.view.ViewCompat.getRootWindowInsets(rootView)
            val imeHeight = insets?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0
            
            // 如果WindowInsets不可用，使用布局高度差作为备选方案
            val currentHeight = rootView.height
            if (rootViewHeight == 0) {
                rootViewHeight = currentHeight
                return@OnGlobalLayoutListener
            }
            
            val heightDiff = rootViewHeight - currentHeight
            val keyboardNowVisible = (imeHeight > 0) || (heightDiff > 200) // 键盘高度通常超过200dp
            
            // 使用WindowInsets的高度，如果没有则使用高度差
            val actualKeyboardHeight = if (imeHeight > 0) imeHeight else heightDiff
            
            // 只在键盘状态变化时处理，避免重复操作
            if (keyboardNowVisible != isKeyboardVisible || (isKeyboardVisible && actualKeyboardHeight != keyboardHeight)) {
                val wasKeyboardVisible = isKeyboardVisible
                isKeyboardVisible = keyboardNowVisible
                keyboardHeight = actualKeyboardHeight
                
                if (isKeyboardVisible) {
                    // 键盘弹起时
                    if (isMediaPanelVisible && hasChatBinding) {
                        hideMediaPanel()
                    }
                    if (isVoicePanelVisible && hasChatBinding) {
                        hideVoicePanel()
                    }
                    if (isEmojiPanelVisible && hasChatBinding) {
                        hideEmojiPanel()
                    }
                    
                    // 确保窗口软输入模式正确设置
                    activity?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                    
                    // 固定标题栏位置：确保标题栏不被推起
                    val titleBar = currentBinding.root.findViewById<android.view.View>(R.id.llTitleBar)
                    titleBar?.translationY = 0f // 确保标题栏保持在顶部
                    
                    // 键盘弹起时自动滚动到底部，确保最新消息可见
                    // 使用多次延迟，确保布局调整完成后再滚动
                    binding.rvChatMessages.post {
                        if (hasChatBinding) {
                            scrollToBottom()
                        }
                    }
                    // 再次延迟滚动，确保键盘完全弹起后布局调整完成
                    binding.rvChatMessages.postDelayed({
                        if (hasChatBinding) {
                            scrollToBottom()
                        }
                    }, 100)
                    
                    // 键盘弹起后，延迟更新按钮可见性，确保布局调整完成后按钮状态正确
                    // 这对于管理员模式下输入回复内容时特别重要
                    binding.llInputContainer.postDelayed({
                        if (hasChatBinding) {
                            updateButtonVisibility()
                        }
                    }, 150) // 延迟150ms，等待键盘完全弹起和布局调整完成
                } else {
                    // 键盘收起时
                    keyboardHeight = 0
                    
                    // 确保窗口软输入模式保持正确
                    activity?.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                }
            }
        }
        binding.root.viewTreeObserver.addOnGlobalLayoutListener(keyboardLayoutListener)
        }
    }
}