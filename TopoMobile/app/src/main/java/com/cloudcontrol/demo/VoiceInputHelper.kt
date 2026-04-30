package com.cloudcontrol.demo

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.iflytek.sparkchain.core.asr.ASR
import com.iflytek.sparkchain.core.asr.AsrCallbacks

/**
 * 语音输入辅助类
 * 用于弹窗中的语音输入功能
 */
class VoiceInputHelper(
    private val context: Context,
    private val etInput: android.widget.EditText,
    private val llVoicePanel: LinearLayout,
    private val btnVoiceInput: ImageButton,
    private val btnVoiceRecord: ImageButton,
    private val tvVoiceHint: TextView,
    private val ivRecordingRing: RecordingRingView
) {
    private val TAG = "VoiceInputHelper"
    private val VOICE_DISABLED_MESSAGE = "语音识别功能已临时下线。你仍可使用文本输入继续聊天。"
    
    private var mAsr: ASR? = null
    private var audioRecorderManager: AudioRecorderManager? = null
    private var isVoiceRecording = false
    private var isVoicePanelVisible = false
    private var voiceRecognitionCount = 0
    private val handler = Handler(Looper.getMainLooper())
    
    private val asrCallbacks = object : AsrCallbacks {
        override fun onResult(asrResult: ASR.ASRResult, o: Any?) {
            val status = asrResult.status
            val result = asrResult.bestMatchText
            
            Log.d(TAG, "语音识别结果: status=$status, result=$result")
            
            handler.post {
                when (status) {
                    0, 1 -> {
                        // 中间结果：实时显示
                        etInput.setText(result)
                        etInput.setSelection(result.length)
                    }
                    2 -> {
                        // 最终结果
                        etInput.setText(result)
                        etInput.setSelection(result.length)
                        stopVoiceRecognition()
                        hideVoicePanel()
                        showKeyboard()
                    }
                }
            }
        }
        
        override fun onError(asrError: ASR.ASRError, o: Any?) {
            Log.e(TAG, "语音识别错误: code=${asrError.code}, msg=${asrError.errMsg}")
            handler.post {
                android.widget.Toast.makeText(context, "语音识别错误: ${asrError.errMsg}", android.widget.Toast.LENGTH_SHORT).show()
                stopVoiceRecognition()
            }
        }
        
        override fun onBeginOfSpeech() {
            Log.d(TAG, "开始说话")
        }
        
        override fun onEndOfSpeech() {
            Log.d(TAG, "结束说话")
        }
    }
    
    private val audioDataCallback = object : AudioRecorderManager.AudioDataCallback {
        override fun onAudioData(data: ByteArray, size: Int) {
            if (isVoiceRecording && mAsr != null) {
                val ret = mAsr?.write(data)
                if (ret != null && ret != 0) {
                    Log.e(TAG, "写入音频数据失败，错误码: $ret")
                }
            }
        }
        
        override fun onAudioVolume(db: Double, volume: Int) {
            // 音量回调，可用于显示音量指示器
        }
    }
    
    init {
        // 设置语音输入图标点击事件（优化：直接内联逻辑，减少函数调用开销）
        btnVoiceInput.setOnClickListener {
            try {
                if (!BuildConfig.SPARKCHAIN_ENABLED) {
                    android.widget.Toast.makeText(context, VOICE_DISABLED_MESSAGE, android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // 立即触发震动反馈，提升响应速度
                vibrate()
                
                if (isVoicePanelVisible) {
                    hideVoicePanel()
                } else {
                    showVoicePanel()
                }
            } catch (e: Exception) {
                Log.e(TAG, "语音输入图标点击异常: ${e.message}", e)
            }
        }
        
        // 设置录音按钮点击事件
        btnVoiceRecord.setOnClickListener {
            try {
                if (!BuildConfig.SPARKCHAIN_ENABLED) {
                    android.widget.Toast.makeText(context, VOICE_DISABLED_MESSAGE, android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!isVoiceRecording) {
                    startVoiceRecognition()
                } else {
                    // 先停止录音，再隐藏面板和显示键盘
                    stopVoiceRecognition()
                    // 延迟一下确保停止操作完成
                    handler.postDelayed({
                        try {
                            hideVoicePanel()
                            showKeyboard()
                        } catch (e: Exception) {
                            Log.e(TAG, "隐藏面板或显示键盘异常: ${e.message}", e)
                        }
                    }, 100)
                }
            } catch (e: Exception) {
                Log.e(TAG, "录音按钮点击异常: ${e.message}", e)
            }
        }
    }
    
    /**
     * 预先初始化ASR和录音管理器，减少启动延迟
     * 用于无障碍快捷方式触发时提前准备
     */
    fun preInitialize() {
        try {
            // 检查权限
            if (android.content.pm.PackageManager.PERMISSION_GRANTED == 
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)) {
                // 预先初始化ASR
                if (mAsr == null) {
                    mAsr = ASR()
                    mAsr?.registerCallbacks(asrCallbacks)
                    // 预先配置ASR参数
                    mAsr?.language("zh_cn")
                    mAsr?.domain("iat")
                    mAsr?.accent("mandarin")
                    mAsr?.vinfo(true)
                    mAsr?.dwa("wpgs")
                    Log.d(TAG, "ASR已预先初始化")
                }
                
                // 预先初始化录音管理器
                if (audioRecorderManager == null) {
                    audioRecorderManager = AudioRecorderManager.getInstance()
                    Log.d(TAG, "录音管理器已预先初始化")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "预先初始化失败: ${e.message}", e)
        }
    }
    
    /**
     * 切换语音面板显示/隐藏
     */
    private fun toggleVoicePanel() {
        try {
            if (isVoicePanelVisible) {
                hideVoicePanel()
            } else {
                showVoicePanel()
            }
        } catch (e: Exception) {
            Log.e(TAG, "切换语音面板异常: ${e.message}", e)
        }
    }
    
    /**
     * 显示语音面板
     */
    private fun showVoicePanel() {
        if (isVoicePanelVisible) return
        
        // 先设置标志，避免重复调用
        isVoicePanelVisible = true
        
        // 隐藏键盘（异步执行，不阻塞UI）
        hideKeyboard()
        
        // 立即设置可见，提升响应速度
        llVoicePanel.visibility = View.VISIBLE
        
        // 收集所有子视图并设置为可见
        val childViews = mutableListOf<View>()
        for (i in 0 until llVoicePanel.childCount) {
            val child = llVoicePanel.getChildAt(i)
            child.visibility = View.VISIBLE
            child.alpha = 1f // 直接设置为完全不透明，避免闪烁
            childViews.add(child)
        }
        
        // 设置父容器不裁剪子视图，确保动画过程中子视图也能显示
        val parentView = llVoicePanel.parent as? ViewGroup
        val originalClipChildren = parentView?.clipChildren
        val originalClipToPadding = parentView?.clipToPadding
        parentView?.clipChildren = false
        parentView?.clipToPadding = false
        
        // 使用post立即执行，而不是延迟100ms
        llVoicePanel.post {
            try {
                // 测量目标高度（使用父容器宽度）
                val parentWidth = (llVoicePanel.parent as? View)?.width ?: llVoicePanel.width
                if (parentWidth <= 0) {
                    // 如果父容器宽度还未确定，延迟一下再试
                    handler.postDelayed({
                        showVoicePanelInternal(parentView, originalClipChildren, originalClipToPadding)
                    }, 50)
                    return@post
                }
                
                showVoicePanelInternal(parentView, originalClipChildren, originalClipToPadding)
            } catch (e: Exception) {
                Log.e(TAG, "显示语音面板异常: ${e.message}", e)
                isVoicePanelVisible = false
            }
        }
    }
    
    /**
     * 显示语音面板的内部实现（已确保布局完成）
     */
    private fun showVoicePanelInternal(
        parentView: ViewGroup?,
        originalClipChildren: Boolean?,
        originalClipToPadding: Boolean?
    ) {
        try {
            // 测量目标高度
            val parentWidth = (llVoicePanel.parent as? View)?.width ?: llVoicePanel.width
            llVoicePanel.measure(
                View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val targetHeight = llVoicePanel.measuredHeight
            
            if (targetHeight <= 0) {
                // 如果测量失败，直接设置为WRAP_CONTENT
                val layoutParams = llVoicePanel.layoutParams
                layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                llVoicePanel.layoutParams = layoutParams
                return
            }
            
            // 从0高度开始动画
            val layoutParams = llVoicePanel.layoutParams
            layoutParams.height = 0
            llVoicePanel.layoutParams = layoutParams
            
            // 使用优化的ValueAnimator，减少布局操作
            android.animation.ValueAnimator.ofInt(0, targetHeight).apply {
                duration = 200 // 缩短动画时间，提升响应速度
                interpolator = android.view.animation.DecelerateInterpolator(1.5f) // 使用减速插值器，更自然
                
                // 缓存layoutParams，避免每次获取
                val params = llVoicePanel.layoutParams
                
                addUpdateListener { animation ->
                    try {
                        if (llVoicePanel != null && llVoicePanel.parent != null) {
                            val height = animation.animatedValue as Int
                            params.height = height
                            llVoicePanel.layoutParams = params
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "动画更新异常: ${e.message}", e)
                        cancel()
                    }
                }
                
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        try {
                            // 动画结束后，恢复父容器的裁剪设置
                            parentView?.clipChildren = originalClipChildren ?: true
                            parentView?.clipToPadding = originalClipToPadding ?: true
                        } catch (e: Exception) {
                            Log.e(TAG, "恢复裁剪设置异常: ${e.message}", e)
                        }
                    }
                })
                
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "显示语音面板内部实现异常: ${e.message}", e)
            isVoicePanelVisible = false
        }
    }
    
    /**
     * 公开方法：显示语音面板并开始录音
     * 用于无障碍快捷方式触发时自动打开语音输入
     * 直接显示面板（无动画）并立即开始录音，跳过"点击说话"状态
     */
    fun showVoicePanelAndStartRecording() {
        Log.d(TAG, "showVoicePanelAndStartRecording: 直接显示语音面板并立即启动录音")
        
        if (isVoicePanelVisible && isVoiceRecording) {
            // 如果已经在录音，不需要重复操作
            return
        }
        
        try {
            // 安全检查：确保视图已附加到窗口
            if (llVoicePanel.parent == null || !llVoicePanel.isAttachedToWindow) {
                Log.w(TAG, "视图未附加到窗口，延迟执行")
                // 使用post等待视图附加完成
                llVoicePanel.post {
                    showVoicePanelAndStartRecordingInternal()
                }
                return
            }
            
            showVoicePanelAndStartRecordingInternal()
        } catch (e: Exception) {
            Log.e(TAG, "showVoicePanelAndStartRecording异常: ${e.message}", e)
            // 如果出现异常，延迟重试
            handler.postDelayed({
                try {
                    showVoicePanelAndStartRecordingInternal()
                } catch (e2: Exception) {
                    Log.e(TAG, "延迟重试失败: ${e2.message}", e2)
                }
            }, 100)
        }
    }
    
    /**
     * 显示语音面板并开始录音的内部实现
     */
    private fun showVoicePanelAndStartRecordingInternal() {
        try {
            // 再次检查视图状态
            if (llVoicePanel.parent == null || !llVoicePanel.isAttachedToWindow) {
                Log.w(TAG, "视图仍未附加到窗口，无法继续")
                return
            }
            
            hideKeyboard()
            
            // 直接显示面板，无动画
            if (!isVoicePanelVisible) {
                isVoicePanelVisible = true
                llVoicePanel.visibility = View.VISIBLE
                
                // 安全地获取父视图宽度
                val parentWidth = try {
                    val parent = llVoicePanel.parent
                    if (parent is View) {
                        parent.width
                    } else {
                        llVoicePanel.width
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "获取父视图宽度失败: ${e.message}")
                    llVoicePanel.width
                }
                
                // 如果面板已经有高度（预先准备好的），直接使用
                val currentHeight = llVoicePanel.height
                if (currentHeight <= 0 && parentWidth > 0) {
                    try {
                        // 需要测量目标高度
                        llVoicePanel.measure(
                            View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                        )
                        val targetHeight = llVoicePanel.measuredHeight
                        
                        if (targetHeight > 0) {
                            // 直接设置为目标高度，无动画
                            val layoutParams = llVoicePanel.layoutParams
                            if (layoutParams != null) {
                                layoutParams.height = targetHeight
                                llVoicePanel.layoutParams = layoutParams
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "测量面板高度失败: ${e.message}", e)
                        // 如果测量失败，使用WRAP_CONTENT
                        try {
                            val layoutParams = llVoicePanel.layoutParams
                            if (layoutParams != null) {
                                layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                                llVoicePanel.layoutParams = layoutParams
                            }
                        } catch (e2: Exception) {
                            Log.e(TAG, "设置布局参数失败: ${e2.message}", e2)
                        }
                    }
                }
                
                // 确保所有子视图可见
                try {
                    for (i in 0 until llVoicePanel.childCount) {
                        val child = llVoicePanel.getChildAt(i)
                        if (child != null) {
                            child.visibility = View.VISIBLE
                            child.alpha = 1.0f
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "设置子视图可见性失败: ${e.message}", e)
                }
            }
            
            // 延迟一小段时间确保布局完成后再开始录音
            handler.postDelayed({
                try {
                    // 再次检查视图状态
                    if (llVoicePanel.parent == null || !llVoicePanel.isAttachedToWindow) {
                        Log.w(TAG, "视图已分离，取消开始录音")
                        return@postDelayed
                    }
                    
                    // 立即开始录音，跳过"点击说话"状态
                    if (!isVoiceRecording) {
                        startVoiceRecognition()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "延迟开始录音失败: ${e.message}", e)
                }
            }, 50) // 延迟50ms确保布局完成
        } catch (e: Exception) {
            Log.e(TAG, "showVoicePanelAndStartRecordingInternal异常: ${e.message}", e)
            isVoicePanelVisible = false
        }
    }
    
    /**
     * 隐藏语音面板
     */
    private fun hideVoicePanel() {
        if (!isVoicePanelVisible) return
        
        try {
            // 检查视图是否可用
            if (llVoicePanel == null || llVoicePanel.parent == null) {
                isVoicePanelVisible = false
                return
            }
            
            if (isVoiceRecording) {
                stopVoiceRecognition()
            }
            
            isVoicePanelVisible = false
            val currentHeight = llVoicePanel.height
            
            if (currentHeight <= 0) {
                try {
                    llVoicePanel.visibility = View.GONE
                } catch (e: Exception) {
                    Log.e(TAG, "隐藏面板异常: ${e.message}", e)
                }
                return
            }
            
            // 使用优化的ValueAnimator，减少布局操作
            try {
                android.animation.ValueAnimator.ofInt(currentHeight, 0).apply {
                    duration = 200 // 缩短动画时间
                    interpolator = android.view.animation.AccelerateInterpolator(1.5f) // 使用加速插值器
                    
                    // 缓存layoutParams，避免每次获取
                    val params = llVoicePanel.layoutParams
                    
                    addUpdateListener { animation ->
                        try {
                            if (llVoicePanel != null && llVoicePanel.parent != null) {
                                val height = animation.animatedValue as Int
                                params.height = height
                                llVoicePanel.layoutParams = params
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "动画更新异常: ${e.message}", e)
                            cancel()
                        }
                    }
                    
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            try {
                                if (llVoicePanel != null && llVoicePanel.parent != null) {
                                    llVoicePanel.visibility = View.GONE
                                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                                    llVoicePanel.layoutParams = params
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "动画结束异常: ${e.message}", e)
                            }
                        }
                    })
                    
                    start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "启动隐藏动画异常: ${e.message}", e)
                // 如果动画失败，直接隐藏
                try {
                    llVoicePanel.visibility = View.GONE
                } catch (e2: Exception) {
                    Log.e(TAG, "直接隐藏面板异常: ${e2.message}", e2)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "隐藏语音面板异常: ${e.message}", e)
            isVoicePanelVisible = false
        }
    }
    
    /**
     * 开始语音识别
     */
    fun startVoiceRecognition() {
        if (!BuildConfig.SPARKCHAIN_ENABLED) {
            android.widget.Toast.makeText(context, VOICE_DISABLED_MESSAGE, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // 检查权限
        if (android.content.pm.PackageManager.PERMISSION_GRANTED != 
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO)) {
            android.widget.Toast.makeText(context, "需要录音权限才能使用语音输入", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        try {
            // 初始化ASR
            if (mAsr == null) {
                mAsr = ASR()
                mAsr?.registerCallbacks(asrCallbacks)
            }
            
            // 配置ASR参数
            mAsr?.language("zh_cn")
            mAsr?.domain("iat")
            mAsr?.accent("mandarin")
            mAsr?.vinfo(true)
            mAsr?.dwa("wpgs")
            
            // 启动识别
            voiceRecognitionCount++
            val ret = mAsr?.start(voiceRecognitionCount.toString())
            
            if (ret == 0) {
                isVoiceRecording = true
                
                // 初始化录音管理器
                if (audioRecorderManager == null) {
                    audioRecorderManager = AudioRecorderManager.getInstance()
                }
                audioRecorderManager?.startRecord()
                audioRecorderManager?.registerCallBack(audioDataCallback)
                
                // 更新UI（在主线程执行，并检查视图状态）
                handler.post {
                    try {
                        // 检查视图是否已附加到窗口
                        if (tvVoiceHint != null && tvVoiceHint.parent != null && tvVoiceHint.isAttachedToWindow &&
                            btnVoiceRecord != null && btnVoiceRecord.parent != null && btnVoiceRecord.isAttachedToWindow) {
                            updateVoiceButtonState()
                        } else {
                            Log.w(TAG, "视图未就绪，跳过更新按钮状态")
                        }
                        
                        // 检查录音环视图是否已附加到窗口
                        if (ivRecordingRing != null && ivRecordingRing.parent != null && ivRecordingRing.isAttachedToWindow) {
                            ivRecordingRing.startAnimation()
                        } else {
                            Log.w(TAG, "录音环视图未就绪，跳过启动动画")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "更新UI异常: ${e.message}", e)
                    }
                }
                
                Log.d(TAG, "语音识别已启动")
            } else {
                Log.e(TAG, "语音识别启动失败，错误码: $ret")
                android.widget.Toast.makeText(context, "语音识别启动失败", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动语音识别异常: ${e.message}", e)
            android.widget.Toast.makeText(context, "启动语音识别失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 停止语音识别
     */
    private fun stopVoiceRecognition() {
        try {
            // 先设置标志，避免重复调用
            if (!isVoiceRecording) {
                return
            }
            isVoiceRecording = false
            
            // 停止录音（需要先停止录音，再停止ASR）
            try {
                audioRecorderManager?.stopRecord()
            } catch (e: Exception) {
                Log.e(TAG, "停止录音异常: ${e.message}", e)
            } finally {
                audioRecorderManager = null
            }
            
            // 停止识别（需要在主线程执行）
            try {
                handler.post {
                    try {
                        mAsr?.stop(false)
                    } catch (e: Exception) {
                        Log.e(TAG, "停止ASR异常: ${e.message}", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "停止ASR调度异常: ${e.message}", e)
            }
            
            // 停止动画（需要在主线程执行，并检查视图是否可用）
            handler.post {
                try {
                    if (ivRecordingRing != null && ivRecordingRing.parent != null) {
                        ivRecordingRing.stopAnimation()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "停止动画异常: ${e.message}", e)
                }
            }
            
            // 更新UI（需要在主线程执行，并检查视图是否可用）
            handler.post {
                try {
                    if (tvVoiceHint != null && tvVoiceHint.parent != null &&
                        btnVoiceRecord != null && btnVoiceRecord.parent != null) {
                        updateVoiceButtonState()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "更新UI异常: ${e.message}", e)
                }
            }
            
            Log.d(TAG, "语音识别已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止语音识别异常: ${e.message}", e)
            // 确保状态被重置
            isVoiceRecording = false
        }
    }
    
    /**
     * 更新语音按钮状态
     */
    private fun updateVoiceButtonState() {
        try {
            // 安全检查：确保视图已附加到窗口
            if (tvVoiceHint == null || tvVoiceHint.parent == null || !tvVoiceHint.isAttachedToWindow) {
                Log.w(TAG, "tvVoiceHint未就绪，跳过更新")
                return
            }
            if (btnVoiceRecord == null || btnVoiceRecord.parent == null || !btnVoiceRecord.isAttachedToWindow) {
                Log.w(TAG, "btnVoiceRecord未就绪，跳过更新")
                return
            }
            
            if (isVoiceRecording) {
                tvVoiceHint.text = context.getString(R.string.tap_again_to_stop_recording)
                btnVoiceRecord.alpha = 0.8f
            } else {
                tvVoiceHint.text = context.getString(R.string.tap_to_speak)
                btnVoiceRecord.alpha = 1.0f
            }
        } catch (e: Exception) {
            Log.e(TAG, "更新语音按钮状态异常: ${e.message}", e)
        }
    }
    
    /**
     * 隐藏键盘
     */
    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(etInput.windowToken, 0)
    }
    
    /**
     * 显示键盘
     */
    private fun showKeyboard() {
        etInput.requestFocus()
        handler.postDelayed({
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(etInput, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }
    
    /**
     * 震动反馈（点击按钮时）
     */
    private fun vibrate() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            
            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Android 8.0及以上使用VibrationEffect
                    val vibrationEffect = VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                    it.vibrate(vibrationEffect)
                } else {
                    // Android 8.0以下使用旧API
                    @Suppress("DEPRECATION")
                    it.vibrate(50)
                }
            }
        } catch (e: Exception) {
            // 如果震动失败（例如设备不支持），静默处理
            Log.d(TAG, "震动失败: ${e.message}")
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        try {
            // 先停止录音
            if (isVoiceRecording) {
                stopVoiceRecognition()
                // 等待停止操作完成
                handler.postDelayed({
                    try {
                        // 停止动画
                        if (ivRecordingRing != null && ivRecordingRing.parent != null) {
                            ivRecordingRing.stopAnimation()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "清理时停止动画异常: ${e.message}", e)
                    }
                }, 200)
            } else {
                // 即使没有录音，也确保停止动画
                try {
                    if (ivRecordingRing != null && ivRecordingRing.parent != null) {
                        ivRecordingRing.stopAnimation()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "清理时停止动画异常: ${e.message}", e)
                }
            }
            
            // 隐藏面板
            if (isVoicePanelVisible) {
                try {
                    hideVoicePanel()
                } catch (e: Exception) {
                    Log.e(TAG, "清理时隐藏面板异常: ${e.message}", e)
                }
            }
            
            // 清理资源
            try {
                mAsr?.stop(false)
            } catch (e: Exception) {
                Log.e(TAG, "清理时停止ASR异常: ${e.message}", e)
            }
            mAsr = null
            
            try {
                audioRecorderManager?.stopRecord()
            } catch (e: Exception) {
                Log.e(TAG, "清理时停止录音异常: ${e.message}", e)
            }
            audioRecorderManager = null
            
            // 重置状态
            isVoiceRecording = false
            isVoicePanelVisible = false
            
            Log.d(TAG, "资源清理完成")
        } catch (e: Exception) {
            Log.e(TAG, "清理资源异常: ${e.message}", e)
            // 确保状态被重置
            isVoiceRecording = false
            isVoicePanelVisible = false
            mAsr = null
            audioRecorderManager = null
        }
    }
}

