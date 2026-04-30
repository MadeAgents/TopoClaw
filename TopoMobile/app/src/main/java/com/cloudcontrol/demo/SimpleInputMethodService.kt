package com.cloudcontrol.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.iflytek.sparkchain.core.asr.ASR
import com.iflytek.sparkchain.core.asr.AsrCallbacks
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import android.Manifest
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.app.Activity
import java.util.Locale
import kotlinx.coroutines.*
import android.graphics.Bitmap
import android.widget.Toast
import android.content.SharedPreferences

/**
 * 自定义输入法服务
 * 提供完整的键盘界面，支持用户手动输入和程序自动输入
 * 
 * 使用说明：
 * 1. 用户在"设置 -> 系统 -> 语言和输入法"中启用此输入法
 * 2. 用户切换到此输入法（可设置为默认输入法）
 * 3. 用户可以通过键盘界面手动输入
 * 4. 无障碍服务通过Broadcast发送输入请求（自动输入）
 */
class SimpleInputMethodService : InputMethodService() {
    
    companion object {
        private const val TAG = "SimpleInputMethod"
        private const val VOICE_DISABLED_MESSAGE = "语音识别功能已临时下线。你仍可使用文本输入继续聊天。"
        const val ACTION_INPUT_TEXT = "com.cloudcontrol.demo.INPUT_TEXT"
        const val EXTRA_TEXT = "text"
        const val EXTRA_FROM_MODEL = "from_model"
        
        // 发送回车键Action
        const val ACTION_SEND_ENTER = "com.cloudcontrol.demo.SEND_ENTER"
        
        // 请求隐藏键盘（模型发起输入时调用，自定义键盘收到后 requestHideSelf）
        const val ACTION_HIDE_KEYBOARD = "com.cloudcontrol.demo.HIDE_KEYBOARD"
        
        // 问字按钮相关Action
        const val ACTION_QUESTION_BUTTON_CLICKED = "com.cloudcontrol.demo.QUESTION_BUTTON_CLICKED"
        const val EXTRA_SCREENSHOT_BASE64 = "screenshot_base64"
        
        private var instance: SimpleInputMethodService? = null
        
        // 标记是否是程序输入（用于过滤程序自动输入）
        @Volatile
        private var isProgrammaticInput: Boolean = false
        
        /** 模型输入模式：为 true 时键盘显示高度为 0，避免弹出 */
        @Volatile
        var isModelInputMode: Boolean = false
            private set
        
        fun setModelInputMode(active: Boolean) {
            isModelInputMode = active
            Log.d(TAG, "setModelInputMode: $active")
        }
        
        /**
         * 获取服务实例
         */
        fun getInstance(): SimpleInputMethodService? {
            return instance
        }
        
        /**
         * 检查输入法是否已激活
         */
        fun isActive(): Boolean {
            return instance != null
        }
    }
    
    // 键盘状态
    private var isUpperCase = false  // 是否大写模式
    private var isSymbolMode = false  // 是否符号模式
    private var isMoreSymbolMode = false  // 是否更多符号模式
    private var isChineseMode = true  // 是否中文模式（默认为中文）
    private var isTogglingMode = false  // 是否正在切换模式（防抖标志）
    
    // 键盘布局
    private var keyboardView: View? = null
    private var candidatesView: View? = null  // 候选词视图（系统）
    private var candidatesContainer: LinearLayout? = null  // 候选词容器（集成在键盘中）
    private var candidatesScrollView: HorizontalScrollView? = null  // 候选词滚动视图
    private var candidatesOuterContainer: ViewGroup? = null  // 候选词外层容器
    
    // 拼音输入相关
    private var currentPinyin = ""  // 当前输入的拼音
    private var lastSelectedWord: String? = null  // 最后选择的词（用于跨词预测）
    private val pinyinDictionary: PinyinDictionary by lazy { PinyinDictionary(this) }  // 拼音词典（延迟初始化，传入context）
    
    // 语音识别相关
    private var speechRecognizer: SpeechRecognizer? = null  // Android原生（已废弃，保留用于兼容）
    private var mAsr: ASR? = null  // 科大讯飞ASR
    private var audioRecorderManager: AudioRecorderManager? = null  // 音频录制管理器
    private var isListening = false  // 是否正在识别
    private var isLongPressVoiceMode = false  // 是否处于长按语音输入模式
    private var voiceRecognitionCount: Int = 0  // 语音识别计数
    private val RECORD_AUDIO_PERMISSION_CODE = 1001
    
    // 长按检测相关
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var spaceKeyLongPressRunnable: Runnable? = null
    private var isSpaceKeyLongPressed = false
    private var deleteKeyLongPressRunnable: Runnable? = null
    private var deleteKeyRepeatRunnable: Runnable? = null
    private var isDeleteKeyLongPressed = false
    
    // 按键预览相关
    private var keyPreviewView: android.widget.TextView? = null
    private var keyboardRootView: ViewGroup? = null
    
    // 协程作用域（用于异步操作）
    // 方案B优化：使用 Dispatchers.Default 替代 Dispatchers.Main，避免阻塞主线程
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 方案B优化：缓存 SharedPreferences 值，避免频繁读取
    @Volatile
    private var cachedShowKeyboardUI: Boolean? = null
    private var lastPrefsReadTime: Long = 0
    private val PREFS_CACHE_DURATION = 5000L // 缓存5秒
    
    private val inputReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            try {
                if (intent?.action == ACTION_INPUT_TEXT) {
                    val text = intent.getStringExtra(EXTRA_TEXT)
                    val fromModel = intent.getBooleanExtra(EXTRA_FROM_MODEL, false)
                    if (text != null) {
                        Log.d(TAG, "收到Broadcast输入请求: $text, fromModel=$fromModel")
                        handleInputText(text, fromModel)
                    } else {
                        Log.w(TAG, "收到输入请求但文本为空")
                    }
                } else if (intent?.action == ACTION_HIDE_KEYBOARD) {
                    Log.d(TAG, "收到Broadcast: 请求隐藏键盘（模型发起）")
                    requestHideSelf(0)
                } else if (intent?.action == ACTION_SEND_ENTER) {
                    Log.d(TAG, "========== 收到Broadcast发送回车请求 ==========")
                    Log.d(TAG, "Intent action: ${intent.action}")
                    Log.d(TAG, "Intent package: ${intent.`package`}")
                    sendEnter()
                    Log.d(TAG, "========== sendEnter() 调用完成 ==========")
                }
            } catch (e: Exception) {
                Log.e(TAG, "BroadcastReceiver onReceive异常: ${e.message}", e)
                e.printStackTrace()
                // 不重新抛出，避免闪退
            }
        }
    }
    
    override fun onCreate() {
        try {
            super.onCreate()
        } catch (e: Exception) {
            Log.e(TAG, "super.onCreate()异常: ${e.message}", e)
        }
        
        try {
            instance = this
            Log.d(TAG, "SimpleInputMethodService 已创建")
            Log.d(TAG, "当前线程: ${Thread.currentThread().name}")
            
            // 方案B优化：异步初始化科大讯飞语音识别器，避免阻塞 onCreate
            initXunfeiASRAsync()
            
            // 注册Broadcast接收器
            try {
                val filter = IntentFilter().apply {
                    addAction(ACTION_INPUT_TEXT)
                    addAction(ACTION_SEND_ENTER)
                    addAction(ACTION_HIDE_KEYBOARD)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Android 13+ 需要指定标志
                    registerReceiver(inputReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    registerReceiver(inputReceiver, filter)
                }
                Log.d(TAG, "已注册Broadcast接收器 (ACTION_INPUT_TEXT, ACTION_SEND_ENTER, ACTION_HIDE_KEYBOARD)")
            } catch (e: SecurityException) {
                Log.e(TAG, "注册Broadcast接收器SecurityException: ${e.message}", e)
                // 可能是权限问题，但不影响服务运行
            } catch (e: Exception) {
                Log.e(TAG, "注册Broadcast接收器失败: ${e.message}", e)
                e.printStackTrace()
                // 不抛出异常，允许服务继续运行
            }
        } catch (e: Exception) {
            Log.e(TAG, "onCreate异常: ${e.message}", e)
            e.printStackTrace()
            // 不重新抛出，避免闪退
        }
    }
    
    override fun onDestroy() {
        try {
            instance = null
            // 停止语音识别
            stopSpeechRecognition()
            // 销毁语音识别器
            speechRecognizer?.destroy()
            speechRecognizer = null
            // 停止科大讯飞ASR
            audioRecorderManager?.stopRecord()
            audioRecorderManager = null
            mAsr?.stop(false)
            mAsr = null
            // 取消协程作用域
            serviceScope.cancel()
            
            try {
                unregisterReceiver(inputReceiver)
                Log.d(TAG, "已取消注册Broadcast接收器")
            } catch (e: Exception) {
                Log.e(TAG, "取消注册Broadcast接收器失败: ${e.message}", e)
                // 忽略错误，可能已经取消注册
            }
            Log.d(TAG, "SimpleInputMethodService 已销毁")
        } catch (e: Exception) {
            Log.e(TAG, "onDestroy异常: ${e.message}", e)
            // 不重新抛出，避免闪退
        } finally {
            super.onDestroy()
        }
    }
    
    /**
     * 创建高度为 0 的占位视图（模型输入模式时使用，键盘不弹出）
     */
    private fun createZeroHeightView(): View {
        return android.widget.FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0  // 高度为 0
            )
            visibility = View.GONE
        }
    }
    
    /**
     * 创建输入视图（完整键盘）
     */
    override fun onCreateInputView(): View? {
        return try {
            Log.d(TAG, "onCreateInputView: 创建完整键盘视图, isModelInputMode=${isModelInputMode}")
            
            // 模型输入模式：不显示键盘，避免弹出
            if (isModelInputMode) {
                Log.d(TAG, "模型输入模式，返回零高度视图")
                return createZeroHeightView()
            }
            
            // 检查是否显示键盘UI
            val showKeyboardUI = shouldShowKeyboardUI()
            Log.d(TAG, "显示键盘UI设置: $showKeyboardUI")
            
            if (showKeyboardUI) {
                // 正常创建并显示键盘
                keyboardView = createKeyboardView()
                keyboardView
            } else {
                // 键盘UI关闭：保留键盘视图实例，但输入视图返回零高度占位，避免 View parent 冲突
                Log.d(TAG, "键盘UI已禁用，返回零高度占位视图")
                keyboardView = createKeyboardView()
                createZeroHeightView()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onCreateInputView异常: ${e.message}", e)
            e.printStackTrace()
            null
        }
    }
    
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 每次输入会话开始时，根据当前模式/设置切换视图，避免复用已有 parent 的 View 触发崩溃
        if (isModelInputMode) {
            Log.d(TAG, "onStartInputView: 模型输入模式，切换为零高度视图")
            setInputView(createZeroHeightView())
        } else {
            val showKeyboardUI = shouldShowKeyboardUI()
            if (showKeyboardUI) {
                val view = keyboardView ?: createKeyboardView().also { keyboardView = it }
                setInputView(view)
            } else {
                Log.d(TAG, "onStartInputView: 键盘UI关闭，切换为零高度视图")
                if (keyboardView == null) {
                    keyboardView = createKeyboardView()
                }
                setInputView(createZeroHeightView())
            }
        }
    }
    
    /**
     * 检查是否应该显示键盘UI
     * 方案B优化：使用缓存机制，避免频繁读取 SharedPreferences
     * @return true表示显示键盘UI，false表示隐藏键盘UI（但功能仍可用）
     */
    private fun shouldShowKeyboardUI(): Boolean {
        return try {
            val currentTime = System.currentTimeMillis()
            // 如果缓存有效且未过期，直接返回缓存值
            if (cachedShowKeyboardUI != null && (currentTime - lastPrefsReadTime) < PREFS_CACHE_DURATION) {
                return cachedShowKeyboardUI!!
            }
            
            // 读取并更新缓存
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val value = prefs.getBoolean("show_keyboard_ui", true)  // 默认开启
            cachedShowKeyboardUI = value
            lastPrefsReadTime = currentTime
            value
        } catch (e: Exception) {
            Log.e(TAG, "读取键盘UI设置失败: ${e.message}", e)
            // 出错时返回缓存值或默认值
            cachedShowKeyboardUI ?: true
        }
    }
    
    /**
     * 创建键盘视图
     */
    private fun createKeyboardView(): View {
        try {
            Log.d(TAG, "========== createKeyboardView 开始 ==========")
            Log.d(TAG, "当前模式: 中文=${isChineseMode}, 大写=${isUpperCase}, 符号=${isSymbolMode}")
            Log.d(TAG, "当前线程: ${Thread.currentThread().name}")
        } catch (e: Exception) {
            Log.e(TAG, "createKeyboardView日志记录异常: ${e.message}", e)
        }
        
        // 使用FrameLayout作为根容器，以便预览视图可以覆盖在其他视图之上
        val rootFrameLayout = android.widget.FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setBackgroundColor(Color.parseColor("#E0E0E0"))
            setPadding(dpToPx(4), dpToPx(0), dpToPx(4), dpToPx(4))  // 减少上padding到0dp，使候选区更靠近按键
        }
        
        // 保存根视图引用，用于添加预览视图
        // 如果预览视图已存在，先从旧的父视图中移除
        val oldPreviewView = keyPreviewView
        val oldParent = oldPreviewView?.parent as? ViewGroup
        oldParent?.removeView(oldPreviewView)
        
        keyboardRootView = rootFrameLayout
        
        // 如果预览视图已存在，将其添加到新的根视图中
        if (oldPreviewView != null && keyboardRootView != null) {
            keyboardRootView?.addView(oldPreviewView, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        
        // 将主布局添加到根容器
        rootFrameLayout.addView(mainLayout)
        
        // 候选词区域（集成到键盘顶部）
        // 移除elevation，避免产生阴影边框效果
        val candidatesContainer = createCandidatesContainer()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            candidatesContainer.elevation = 0f  // 移除阴影，使候选区与键盘完全融合
        }
        mainLayout.addView(candidatesContainer)
        
        // 根据符号模式决定显示内容
        if (isSymbolMode) {
            if (isMoreSymbolMode) {
                // 更多符号模式：显示更多符号（不显示数字行）
                // 第一行：更多符号（根据中英文模式切换）
                val moreSymbolsRow1 = if (isChineseMode) {
                    listOf("￥", "€", "£", "¥", "¢", "§", "©", "®", "™", "°")
                } else {
                    listOf("$", "€", "£", "¥", "¢", "§", "©", "®", "™", "°")
                }
                mainLayout.addView(createSymbolRow(moreSymbolsRow1))
                
                // 第二行：希腊字母（减少一个按键，居中排列）
                val moreSymbolsRow2 = if (isChineseMode) {
                    listOf("α", "β", "γ", "δ", "ε", "π", "θ", "λ", "μ")  // 减少一个，从10个变为9个
                } else {
                    listOf("α", "β", "γ", "δ", "ε", "π", "θ", "λ", "μ")  // 减少一个，从10个变为9个
                }
                
                // 创建第二行，居中排列（与字母模式第二行保持一致）
                val moreRow2 = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, dpToPx(2), 0, dpToPx(2))
                    }
                    gravity = Gravity.CENTER_HORIZONTAL  // 居中对齐
                }
                
                // 计算左右留空：第一行有10个按键，第二行有9个，需要留出1个按键的空间
                val leftSpacer = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                        weight = 0.5f  // 左侧留空
                    }
                }
                moreRow2.addView(leftSpacer)
                
                // 添加符号按键（与第一行大小一致）
                moreSymbolsRow2.forEach { symbol ->
                    val button = createKeyButton(symbol)
                    button.textSize = 14f  // 符号按键文字小一号
                    button.setOnClickListener {
                        typeChar(symbol)
                    }
                    moreRow2.addView(button)
                }
                
                // 右侧留空
                val rightSpacer = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                        weight = 0.5f  // 右侧留空
                    }
                }
                moreRow2.addView(rightSpacer)
                
                mainLayout.addView(moreRow2)
                
                // 第三行：更多符号（只保留7个），左侧添加返回按钮，右侧添加删除按键
                val moreSymbolsRow3Symbols = if (isChineseMode) {
                    listOf("×", "÷", "±", "≠", "≤", "≥", "≈")
            } else {
                    listOf("×", "÷", "±", "≠", "≤", "≥", "≈")
                }
                
                // 创建第三行，包含返回按键、7个符号、删除按键
                val moreRow3 = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, dpToPx(2), 0, dpToPx(2))
                    }
                }
                
                // 左侧：返回按键（用于返回普通符号模式）
                val backSymbolKey = createKeyButton("符号", isFunctionKey = true).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dpToPx(42)).apply {
                        weight = 1.2f
                        setMargins(dpToPx(2), 0, dpToPx(2), 0)
                    }
                    setOnClickListener {
                        toggleMoreSymbolMode()
                    }
                }
                moreRow3.addView(backSymbolKey)
                
                // 中间：7个符号
                moreSymbolsRow3Symbols.forEach { symbol ->
                    val button = createKeyButton(symbol)
                    button.setOnClickListener {
                        typeChar(symbol)
                    }
                    moreRow3.addView(button)
                }
                
                // 右侧：删除按键（支持长按连续删除）
                val deleteKeyInMoreSymbol = createKeyButton("⌫", isFunctionKey = true).apply {
                    textSize = 18f  // 增大两号
                    layoutParams = LinearLayout.LayoutParams(0, dpToPx(42)).apply {
                        weight = 1.2f
                        setMargins(dpToPx(2), 0, dpToPx(2), 0)
                    }
                    
                    // 保存原始背景用于触摸事件处理
                    val originalDeleteDrawable = GradientDrawable().apply {
                        setColor(Color.parseColor("#BDBDBD"))  // 功能键颜色
                        cornerRadius = dpToPx(8).toFloat()
                        setStroke(1, Color.parseColor("#9E9E9E"))
                    }
                    background = originalDeleteDrawable
                    
                    // 使用TouchListener处理点击和长按
                    setOnTouchListener { view, motionEvent ->
                        try {
                            when (motionEvent.action) {
                                android.view.MotionEvent.ACTION_DOWN -> {
                                    view.alpha = 0.7f
                                    // 按下时背景变为白色
                                    val whiteDrawable = GradientDrawable().apply {
                                        setColor(Color.WHITE)
                                        cornerRadius = dpToPx(8).toFloat()
                                        setStroke(1, Color.parseColor("#9E9E9E"))
                                    }
                                    view.background = whiteDrawable
                                    
                                    isDeleteKeyLongPressed = false
                                    // 取消之前的延迟任务
                                    deleteKeyLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
                                    deleteKeyRepeatRunnable?.let { mainHandler.removeCallbacks(it) }
                                    
                                    // 延迟检测长按（500ms）
                                    deleteKeyLongPressRunnable = Runnable {
                                        try {
                                            if (!isDeleteKeyLongPressed) {
                                                isDeleteKeyLongPressed = true
                                                Log.d(TAG, "长按删除键，开始连续删除")
                                                startContinuousDelete()
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "长按删除处理异常: ${e.message}", e)
                                            e.printStackTrace()
                                        }
                                    }
                                    mainHandler.postDelayed(deleteKeyLongPressRunnable!!, 500)
                                    true  // 消费事件，避免触发点击
                                }
                                android.view.MotionEvent.ACTION_UP,
                                android.view.MotionEvent.ACTION_CANCEL -> {
                                    view.alpha = 1.0f
                                    // 恢复原始背景
                                    view.background = originalDeleteDrawable
                                    
                                    try {
                                        // 取消长按检测和连续删除
                                        deleteKeyLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
                                        deleteKeyLongPressRunnable = null
                                        deleteKeyRepeatRunnable?.let { mainHandler.removeCallbacks(it) }
                                        deleteKeyRepeatRunnable = null
                                        
                                        if (isDeleteKeyLongPressed) {
                                            // 如果是长按，停止连续删除
                                            Log.d(TAG, "抬起手指，停止连续删除")
                                            isDeleteKeyLongPressed = false
                                        } else {
                                            // 如果是点击，执行单次删除
                                            Log.d(TAG, "点击删除键")
                                            deleteText()
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "抬起处理异常: ${e.message}", e)
                                        e.printStackTrace()
                                    }
                                    true  // 消费事件
                                }
                                else -> false
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Touch事件处理异常: ${e.message}", e)
                            e.printStackTrace()
                            false
                        }
                    }
                }
                moreRow3.addView(deleteKeyInMoreSymbol)
                
                mainLayout.addView(moreRow3)
            } else {
                // 普通符号模式：第一行显示数字，第二行显示原第三行的符号，第三行显示原第一行符号（带符号按键和删除按键）
                // 第一行：数字
                mainLayout.addView(createRow(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")))
                
                // 第二行：原第三行的符号（根据中英文模式切换）
                val row1Symbols = if (isChineseMode) {
                listOf("，", "。", "、", "…", "·", "—", "～", "·", "·", "·")
            } else {
                listOf(",", ".", "/", "\\", "-", "_", "=", "+", "|", "&")
            }
                // 第一行需要居中对齐（与字母模式保持一致）
                val row1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, dpToPx(2), 0, dpToPx(2))
                }
                gravity = Gravity.CENTER_HORIZONTAL  // 居中对齐
            }
            
                // 计算左右留空：第一行有10个按键，最多放9个符号，需要留出1个按键的空间
            val leftSpacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    weight = 0.5f  // 左侧留空
                }
            }
                row1.addView(leftSpacer)
            
            // 添加符号按键（只显示前9个，与字母行A-L保持一致，位置不够就删除靠后的）
                row1Symbols.take(9).forEach { symbol ->
                val button = createKeyButton(symbol)
                button.textSize = 14f  // 符号按键文字小一号
                button.setOnClickListener {
                    typeChar(symbol)
                }
                    row1.addView(button)
            }
            
            // 右侧留空
            val rightSpacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    weight = 0.5f  // 右侧留空
                }
            }
                row1.addView(rightSpacer)
                
                mainLayout.addView(row1)
                
                // 第三行：原第一行的符号（只保留7个），左侧添加符号按键，右侧添加删除按键
                val row2Symbols = if (isChineseMode) {
                    listOf("！", "？", "：", "；", "（", "）", "【")
                } else {
                    listOf("!", "?", ":", ";", "(", ")", "[")
                }
                
                // 创建第二行，包含符号按键、7个符号、删除按键
                val row2 = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, dpToPx(2), 0, dpToPx(2))
                    }
                }
                
                // 左侧：符号按键（用于进入更多符号层）
                val moreSymbolKey = createKeyButton("符号", isFunctionKey = true).apply {
                    layoutParams = LinearLayout.LayoutParams(0, dpToPx(42)).apply {
                        weight = 1.2f
                        setMargins(dpToPx(2), 0, dpToPx(2), 0)
                    }
                    setOnClickListener {
                        toggleMoreSymbolMode()
                    }
                }
                row2.addView(moreSymbolKey)
                
                // 中间：7个符号
                row2Symbols.forEach { symbol ->
                    val button = createKeyButton(symbol)
                    button.textSize = 14f  // 符号按键文字小一号
                    button.setOnClickListener {
                        typeChar(symbol)
                    }
                    row2.addView(button)
                }
                
                // 右侧：删除按键（支持长按连续删除）
                val deleteKeyInSymbol = createKeyButton("⌫", isFunctionKey = true).apply {
                    textSize = 18f  // 增大两号
                    layoutParams = LinearLayout.LayoutParams(0, dpToPx(42)).apply {
                        weight = 1.2f
                        setMargins(dpToPx(2), 0, dpToPx(2), 0)
                    }
                    
                    // 保存原始背景用于触摸事件处理
                    val originalDeleteDrawable = GradientDrawable().apply {
                        setColor(Color.parseColor("#BDBDBD"))  // 功能键颜色
                        cornerRadius = dpToPx(8).toFloat()
                        setStroke(1, Color.parseColor("#9E9E9E"))
                    }
                    background = originalDeleteDrawable
                    
                    // 使用TouchListener处理点击和长按
                    setOnTouchListener { view, motionEvent ->
                        try {
                            when (motionEvent.action) {
                                android.view.MotionEvent.ACTION_DOWN -> {
                                    view.alpha = 0.7f
                                    // 按下时背景变为白色
                                    val whiteDrawable = GradientDrawable().apply {
                                        setColor(Color.WHITE)
                                        cornerRadius = dpToPx(8).toFloat()
                                        setStroke(1, Color.parseColor("#9E9E9E"))
                                    }
                                    view.background = whiteDrawable
                                    
                                    isDeleteKeyLongPressed = false
                                    // 取消之前的延迟任务
                                    deleteKeyLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
                                    deleteKeyRepeatRunnable?.let { mainHandler.removeCallbacks(it) }
                                    
                                    // 延迟检测长按（500ms）
                                    deleteKeyLongPressRunnable = Runnable {
                                        try {
                                            if (!isDeleteKeyLongPressed) {
                                                isDeleteKeyLongPressed = true
                                                Log.d(TAG, "长按删除键，开始连续删除")
                                                startContinuousDelete()
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "长按删除处理异常: ${e.message}", e)
                                            e.printStackTrace()
                                        }
                                    }
                                    mainHandler.postDelayed(deleteKeyLongPressRunnable!!, 500)
                                    true  // 消费事件，避免触发点击
                                }
                                android.view.MotionEvent.ACTION_UP,
                                android.view.MotionEvent.ACTION_CANCEL -> {
                                    view.alpha = 1.0f
                                    // 恢复原始背景
                                    view.background = originalDeleteDrawable
                                    
                                    try {
                                        // 取消长按检测和连续删除
                                        deleteKeyLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
                                        deleteKeyLongPressRunnable = null
                                        deleteKeyRepeatRunnable?.let { mainHandler.removeCallbacks(it) }
                                        deleteKeyRepeatRunnable = null
                                        
                                        if (isDeleteKeyLongPressed) {
                                            // 如果是长按，停止连续删除
                                            Log.d(TAG, "抬起手指，停止连续删除")
                                            isDeleteKeyLongPressed = false
                                        } else {
                                            // 如果是点击，执行单次删除
                                            Log.d(TAG, "点击删除键")
                                            deleteText()
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "抬起处理异常: ${e.message}", e)
                                        e.printStackTrace()
                                    }
                                    true  // 消费事件
                                }
                                else -> false
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Touch事件处理异常: ${e.message}", e)
                            e.printStackTrace()
                            false
                        }
                    }
                }
                row2.addView(deleteKeyInSymbol)
                
                mainLayout.addView(row2)
            }
        } else {
            // 普通模式：去掉数字行，只显示字母
            // 第一行：字母 Q-P
            mainLayout.addView(createRow(listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")))
            
            // 第二行：字母 A-L（居中对齐）
            val row2 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, dpToPx(2), 0, dpToPx(2))
                }
                gravity = Gravity.CENTER_HORIZONTAL  // 居中对齐
            }
            
            // 计算左右留空：第一行有10个按键，第二行有9个，需要留出1个按键的空间
            // 每个按键的weight是1.0，所以左右各留0.5的weight
            val leftSpacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    weight = 0.5f  // 左侧留空
                }
            }
            row2.addView(leftSpacer)
            
            // 添加字母按键（与第一行大小一致）
            val row2Letters = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
            row2Letters.forEach { letter ->
                row2.addView(createLetterButton(letter))
            }
            
            // 右侧留空
            val rightSpacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                    weight = 0.5f  // 右侧留空
                }
            }
            row2.addView(rightSpacer)
            
            mainLayout.addView(row2)
            
            // 第四行：大小写切换 + 字母 Z-M + 删除（左右对齐第一行和第五行，仅普通模式）
            val row4 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, dpToPx(2), 0, dpToPx(2))
                }
            }
            
            // 计算：第一行有10个按键，每个weight=1.0，总共weight=10.0
            // 第四行：1个功能键 + 7个字母键 + 1个功能键 = 9个按键
            // 为了与第一行和第五行左右对齐，需要总weight=10.0
            // - 7个字母键每个weight=1.0，总共7.0
            // - 2个功能键总共3.0，每个weight=1.5（变长以对齐边缘）
            // 总weight=1.5+7.0+1.5=10.0，与第一行一致
            
            // 大小写切换键（变长以对齐左边缘）
            val shiftKeyContainer = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(39)).apply {
                    weight = 1.5f  // 变长以与第1、2、5行左边缘对齐
                    setMargins(dpToPx(2), 0, dpToPx(2), 0)  // 保持间距不变
                }
                
                // 设置背景（功能键颜色）
                val shiftKeyDrawable = GradientDrawable().apply {
                    setColor(Color.parseColor("#BDBDBD"))  // 功能键颜色
                    cornerRadius = dpToPx(8).toFloat()
                    setStroke(1, Color.parseColor("#9E9E9E"))
                }
                background = shiftKeyDrawable
                
                // 添加大小写切换图标
                val shiftIcon = android.widget.ImageView(this@SimpleInputMethodService).apply {
                    try {
                        val shiftIconResId = resources.getIdentifier("ic_shift", "drawable", packageName)
                        if (shiftIconResId != 0) {
                            val shiftIconDrawable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                resources.getDrawable(shiftIconResId, null)
                            } else {
                                @Suppress("DEPRECATION")
                                resources.getDrawable(shiftIconResId)
                            }
                            if (shiftIconDrawable != null) {
                                setImageDrawable(shiftIconDrawable)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "无法加载大小写切换图标: ${e.message}")
                    }
                    layoutParams = LinearLayout.LayoutParams(dpToPx(24), dpToPx(24))
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                }
                addView(shiftIcon)
                
                // 设置按下效果
                val originalShiftDrawable = shiftKeyDrawable
                setOnTouchListener { view, motionEvent ->
                    when (motionEvent.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            view.alpha = 0.7f
                            // 按下时背景变为白色
                            val whiteDrawable = GradientDrawable().apply {
                                setColor(Color.WHITE)
                                cornerRadius = dpToPx(8).toFloat()
                                setStroke(1, Color.parseColor("#9E9E9E"))
                            }
                            view.background = whiteDrawable
                            true
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            view.alpha = 1.0f
                            view.background = originalShiftDrawable
                            // 在ACTION_UP时直接调用toggleShift，确保点击有效
                    toggleShift()
                            true
                        }
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            view.alpha = 1.0f
                            view.background = originalShiftDrawable
                            true
                        }
                        else -> false
                    }
                }
            }
            row4.addView(shiftKeyContainer)
            
            // 字母 Z-M（与第一、二行大小一致，weight=1.0）
            val letters = listOf("z", "x", "c", "v", "b", "n", "m")
            letters.forEach { letter ->
                row4.addView(createLetterButton(letter))
            }
            
            // 删除键（变长以对齐右边缘）
            val deleteKey = createKeyButton("⌫", isFunctionKey = true).apply {
                textSize = 18f  // 增大两号，从14f增加到18f
                layoutParams = LinearLayout.LayoutParams(0, dpToPx(39)).apply {
                    weight = 1.5f  // 变长以与第1、2、5行右边缘对齐
                    setMargins(dpToPx(2), 0, dpToPx(2), 0)  // 保持间距不变
                }
                
                // 保存原始背景用于触摸事件处理
                val originalDeleteDrawable = GradientDrawable().apply {
                    setColor(Color.parseColor("#BDBDBD"))  // 功能键颜色
                    cornerRadius = dpToPx(8).toFloat()
                    setStroke(1, Color.parseColor("#9E9E9E"))
                }
                background = originalDeleteDrawable
                
                // 使用TouchListener处理点击和长按
                setOnTouchListener { view, motionEvent ->
                    try {
                        when (motionEvent.action) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                view.alpha = 0.7f
                                // 按下时背景变为白色
                                val whiteDrawable = GradientDrawable().apply {
                                    setColor(Color.WHITE)
                                    cornerRadius = dpToPx(8).toFloat()
                                    setStroke(1, Color.parseColor("#9E9E9E"))
                                }
                                view.background = whiteDrawable
                                
                                isDeleteKeyLongPressed = false
                                // 取消之前的延迟任务
                                deleteKeyLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
                                deleteKeyRepeatRunnable?.let { mainHandler.removeCallbacks(it) }
                                
                                // 延迟检测长按（500ms）
                                deleteKeyLongPressRunnable = Runnable {
                                    try {
                                        if (!isDeleteKeyLongPressed) {
                                            isDeleteKeyLongPressed = true
                                            Log.d(TAG, "长按删除键，开始连续删除")
                                            
                                            // 开始连续删除
                                            startContinuousDelete()
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "长按删除处理异常: ${e.message}", e)
                                        e.printStackTrace()
                                    }
                                }
                                mainHandler.postDelayed(deleteKeyLongPressRunnable!!, 500)
                                true  // 消费事件，避免触发点击
                            }
                            android.view.MotionEvent.ACTION_UP,
                            android.view.MotionEvent.ACTION_CANCEL -> {
                                view.alpha = 1.0f
                                // 恢复原始背景
                                view.background = originalDeleteDrawable
                                
                                try {
                                    // 取消长按检测和连续删除
                                    deleteKeyLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
                                    deleteKeyLongPressRunnable = null
                                    deleteKeyRepeatRunnable?.let { mainHandler.removeCallbacks(it) }
                                    deleteKeyRepeatRunnable = null
                                    
                                    if (isDeleteKeyLongPressed) {
                                        // 如果是长按，停止连续删除
                                        Log.d(TAG, "抬起手指，停止连续删除")
                                        isDeleteKeyLongPressed = false
                                    } else {
                                        // 如果是点击，执行单次删除
                                        Log.d(TAG, "点击删除键")
                    deleteText()
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "抬起处理异常: ${e.message}", e)
                                    e.printStackTrace()
                                }
                                true  // 消费事件
                            }
                            else -> false
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Touch事件处理异常: ${e.message}", e)
                        e.printStackTrace()
                        false
                    }
                }
            }
            row4.addView(deleteKey)
            
            mainLayout.addView(row4)
        }
        
        // 第五行：符号切换 + 空格 + 中英文切换 + 回车
        val row5 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(2), 0, dpToPx(2))  // 统一上下间距为2dp，与左右间距一致
            }
        }
        
        // 符号切换键
        val symbolKey = createKeyButton("?123", isFunctionKey = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(39)).apply {  // 降低高度
                weight = 1.2f
                setMargins(dpToPx(2), 0, dpToPx(2), 0)  // 按键左右间距为2dp
            }
            setOnClickListener {
                // 如果在更多符号模式，先退出更多符号模式
                if (isMoreSymbolMode) {
                    toggleMoreSymbolMode()
                } else {
                toggleSymbolMode()
                }
            }
        }
        row5.addView(symbolKey)
        
        // 逗号句号按键（在"？123"和空格之间）
        val commaPeriodKeyDrawable = GradientDrawable().apply {
            setColor(Color.WHITE)  // 白色背景
            cornerRadius = dpToPx(8).toFloat()
            setStroke(1, Color.parseColor("#9E9E9E"))
        }
        
        var commaPeriodKeyStartY = 0f
        var commaPeriodKeyIsSwipe = false
        
        val commaPeriodKey = Button(this).apply {
            // 根据当前模式显示对应的逗号和句号（水平排列）
            text = if (isChineseMode) "，。" else ",."
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 0)
            
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(39)).apply {
                weight = 1.2f
                setMargins(dpToPx(2), 0, dpToPx(2), 0)
            }
            
            // 设置背景（白色）
            background = commaPeriodKeyDrawable
            
            // 触摸监听，检测点击和上滑
            setOnTouchListener { view, motionEvent ->
                when (motionEvent.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        view.alpha = 0.7f
                        // 按下时背景变为功能键颜色（回车那样的颜色）
                        val functionKeyDrawable = GradientDrawable().apply {
                            setColor(Color.parseColor("#BDBDBD"))  // 功能键颜色
                            cornerRadius = dpToPx(8).toFloat()
                            setStroke(1, Color.parseColor("#9E9E9E"))
                        }
                        view.background = functionKeyDrawable
                        commaPeriodKeyStartY = motionEvent.y
                        commaPeriodKeyIsSwipe = false
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        // 检测是否上滑（Y坐标减小）
                        val deltaY = commaPeriodKeyStartY - motionEvent.y
                        if (deltaY > dpToPx(20)) {  // 上滑超过20dp认为是上滑
                            commaPeriodKeyIsSwipe = true
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        view.alpha = 1.0f
                        // 恢复原始背景
                        view.background = commaPeriodKeyDrawable
                        
                        if (commaPeriodKeyIsSwipe) {
                            // 上滑输出句号
                            val period = if (isChineseMode) "。" else "."
                            Log.d(TAG, "上滑逗号句号键，输出句号: $period")
                            typeChar(period)
                        } else {
                            // 点击输出逗号
                            val comma = if (isChineseMode) "，" else ","
                            Log.d(TAG, "点击逗号句号键，输出逗号: $comma")
                            typeChar(comma)
                        }
                        commaPeriodKeyIsSwipe = false
                        true
                    }
                    else -> false
                }
            }
        }
        row5.addView(commaPeriodKey)
        
        // 空格键（合并语音输入功能：点击空格，长按语音输入）
        // 使用自定义布局，同时显示语音图标和空格图标
        val spaceKeyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(42)).apply {
                weight = 2.5f
                setMargins(dpToPx(2), 0, dpToPx(2), 0)
            }
            setPadding(dpToPx(8), 0, dpToPx(8), 0)
            
            // 设置背景（与普通字母键一样，白色背景）
            val originalSpaceDrawable = GradientDrawable().apply {
                setColor(Color.WHITE)  // 普通字母键颜色
                cornerRadius = dpToPx(8).toFloat()
                setStroke(1, Color.parseColor("#9E9E9E"))
            }
            background = originalSpaceDrawable
        }
        
        // 添加语音图标
        val micIconView = android.widget.ImageView(this).apply {
            try {
                val micResId = resources.getIdentifier("ic_mic", "drawable", packageName)
                if (micResId != 0) {
                    val micDrawable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        resources.getDrawable(micResId, null)
                    } else {
                        @Suppress("DEPRECATION")
                        resources.getDrawable(micResId)
                    }
                    if (micDrawable != null) {
                        // 设置图标颜色为黑色
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            micDrawable.setTint(Color.BLACK)
                        }
                        setImageDrawable(micDrawable)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "无法加载麦克风图标: ${e.message}")
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(22), dpToPx(22)).apply {
                setMargins(0, dpToPx(2), dpToPx(6), 0)  // 上边距2dp向下移动，右边距与空格图标分开
            }
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        }
        spaceKeyContainer.addView(micIconView)
        
        // 空格键只显示语音图标，不显示文字
        
        // 保存原始背景drawable，用于触摸事件处理
        val originalSpaceDrawable = GradientDrawable().apply {
            setColor(Color.WHITE)  // 普通字母键颜色
            cornerRadius = dpToPx(8).toFloat()
            setStroke(1, Color.parseColor("#9E9E9E"))
        }
        spaceKeyContainer.background = originalSpaceDrawable
        
        // 使用TouchListener处理点击和长按（同时处理按下效果）
        spaceKeyContainer.setOnTouchListener { view, motionEvent ->
            try {
                when (motionEvent.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            view.alpha = 0.7f  // 按下效果
                            // 空格键按下时背景变为功能键颜色
                            val functionKeyDrawable = GradientDrawable().apply {
                                setColor(Color.parseColor("#BDBDBD"))  // 功能键颜色
                                cornerRadius = dpToPx(8).toFloat()
                                setStroke(1, Color.parseColor("#9E9E9E"))
                            }
                            view.background = functionKeyDrawable
                            // 空格键不显示预览
                            isSpaceKeyLongPressed = false
                            // 取消之前的延迟任务
                            spaceKeyLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
                            
                            // 延迟检测长按（500ms）
                            spaceKeyLongPressRunnable = Runnable {
                                try {
                                    if (!isSpaceKeyLongPressed) {
                                        isSpaceKeyLongPressed = true
                                        isLongPressVoiceMode = true  // 进入长按模式
                                        Log.d(TAG, "长按空格键，开始语音输入（持续模式）")
                                        
                                        // 确保在主线程执行
                                        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                                            // 开始语音识别
                                            startSpeechRecognition()
                                        } else {
                                            // 如果不在主线程，切换到主线程
                                            mainHandler.post {
                                                try {
                                                    startSpeechRecognition()
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "主线程执行语音识别异常: ${e.message}", e)
                                                    e.printStackTrace()
                                                }
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "长按处理异常: ${e.message}", e)
                                    e.printStackTrace()
                                }
                            }
                            mainHandler.postDelayed(spaceKeyLongPressRunnable!!, 500)
                            true  // 消费事件，避免触发点击
                        }
                        android.view.MotionEvent.ACTION_UP, 
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            view.alpha = 1.0f  // 恢复透明度
                            // 空格键抬起时恢复原始背景
                            view.background = originalSpaceDrawable
                            // 空格键不显示预览
                            try {
                                // 取消长按检测
                                spaceKeyLongPressRunnable?.let { mainHandler.removeCallbacks(it) }
                                spaceKeyLongPressRunnable = null
                                
                                if (isSpaceKeyLongPressed) {
                                    // 如果是长按，停止语音识别
                                    Log.d(TAG, "抬起手指，停止语音输入")
                                    isLongPressVoiceMode = false  // 退出长按模式
                                    isSpaceKeyLongPressed = false
                                    
                                    // 确保在主线程执行
                                    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                                        stopSpeechRecognition()
                                        // 长按结束后更新键盘视图
                                        updateVoiceButtonInKeyboard()
                                    } else {
                                        mainHandler.post {
                                            try {
                                                stopSpeechRecognition()
                                                // 长按结束后更新键盘视图
                                                updateVoiceButtonInKeyboard()
                                            } catch (e: Exception) {
                                                Log.e(TAG, "主线程执行停止识别异常: ${e.message}", e)
                                                e.printStackTrace()
                                            }
                                        }
                                    }
                                } else {
                                    // 如果是点击，执行空格功能
                                    Log.d(TAG, "点击空格键")
                                    try {
                                        if (isChineseMode && currentPinyin.isNotEmpty()) {
                                            // 中文模式下，空格确认第一个候选词
                                            confirmFirstCandidate()
                                        } else {
                                            // 英文模式下，输入空格
                                            typeChar(" ")
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "点击空格处理异常: ${e.message}", e)
                                        e.printStackTrace()
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "抬起处理异常: ${e.message}", e)
                                e.printStackTrace()
                            }
                            true  // 消费事件
                        }
                        else -> false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Touch事件处理异常: ${e.message}", e)
                e.printStackTrace()
                false
            }
        }
        row5.addView(spaceKeyContainer)
        
        // 中英文切换键（移到空格和回车之间，默认白色背景）
        // 先创建背景drawable，以便在lambda中使用
        val langKeyDrawable = GradientDrawable().apply {
            setColor(Color.WHITE)  // 默认白色
            cornerRadius = dpToPx(8).toFloat()
            setStroke(1, Color.parseColor("#9E9E9E"))
        }
        
        val langKey = Button(this).apply {
            text = if (isChineseMode) "中" else "EN"
            textSize = 14f
            setTextColor(Color.BLACK)
            setPadding(0, 0, 0, 0)
            
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(42)).apply {
                weight = 1.2f
                setMargins(dpToPx(2), 0, dpToPx(2), 0)
            }
            
            // 设置背景（默认白色背景，不是功能键颜色）
            background = langKeyDrawable
            
            // 点击时变色
            setOnTouchListener { view, motionEvent ->
                when (motionEvent.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        view.alpha = 0.7f
                        // 按下时背景变为功能键颜色
                        val functionKeyDrawable = GradientDrawable().apply {
                            setColor(Color.parseColor("#BDBDBD"))
                            cornerRadius = dpToPx(8).toFloat()
                            setStroke(1, Color.parseColor("#9E9E9E"))
                        }
                        view.background = functionKeyDrawable
                        true
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        try {
                            // 防抖：如果正在切换，忽略本次点击
                            if (isTogglingMode) {
                                Log.w(TAG, "正在切换模式，忽略重复点击")
                                view.alpha = 1.0f
                                view.background = langKeyDrawable
                                return@setOnTouchListener true
                            }
                            
                            Log.d(TAG, "========== 中英文切换按钮点击 ==========")
                            Log.d(TAG, "当前模式: ${if (isChineseMode) "中文" else "英文"}")
                            Log.d(TAG, "线程: ${Thread.currentThread().name}")
                            view.alpha = 1.0f
                            view.background = langKeyDrawable
                            // 点击切换中英文模式
                            Log.d(TAG, "准备调用toggleChineseMode()")
                            toggleChineseMode()
                            Log.d(TAG, "toggleChineseMode()调用完成")
                        } catch (e: Exception) {
                            Log.e(TAG, "中英文切换按钮点击异常: ${e.message}", e)
                            e.printStackTrace()
                            // 确保异常时重置标志
                            isTogglingMode = false
                        }
                        true
                    }
                    else -> false
                }
            }
        }
        row5.addView(langKey)
        
        // 回车键
        val enterKey = Button(this).apply {
            text = "换行"
            textSize = 14f  // 功能键文字大小
            setTextColor(Color.BLACK)
            // 减小padding，让背景更小更紧凑
            setPadding(dpToPx(2), dpToPx(2), dpToPx(2), dpToPx(2))
            minHeight = dpToPx(42)
            minimumHeight = dpToPx(42)
            // 去除字体默认内边距，确保文字垂直居中
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                includeFontPadding = false
            }
            
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(42)).apply {
                weight = 1.2f
                setMargins(dpToPx(2), 0, dpToPx(2), 0)  // 按键左右间距为2dp
            }
            
            // 设置背景（功能键颜色）
            val enterKeyDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#BDBDBD"))  // 功能键颜色
                cornerRadius = dpToPx(8).toFloat()
                setStroke(1, Color.parseColor("#9E9E9E"))
            }
            background = enterKeyDrawable
            
            // 设置按下效果
            val originalEnterDrawable = enterKeyDrawable
            setOnTouchListener { view, motionEvent ->
                when (motionEvent.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        view.alpha = 0.7f
                        // 按下时背景变为白色
                        val whiteDrawable = GradientDrawable().apply {
                            setColor(Color.WHITE)
                            cornerRadius = dpToPx(8).toFloat()
                            setStroke(1, Color.parseColor("#9E9E9E"))
                        }
                        view.background = whiteDrawable
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        view.alpha = 1.0f
                        view.background = originalEnterDrawable
                        // 在ACTION_UP时直接调用sendEnter，确保点击有效
                sendEnter()
                        true
                    }
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        view.alpha = 1.0f
                        view.background = originalEnterDrawable
                        true
                    }
                    else -> false
                }
            }
        }
        row5.addView(enterKey)
        
        mainLayout.addView(row5)
        
        Log.d(TAG, "已创建完整键盘视图")
        Log.d(TAG, "========== createKeyboardView 完成 ==========")
        keyboardView = rootFrameLayout
        return rootFrameLayout
    }
    
    /**
     * 创建候选词容器（集成在键盘顶部，支持左右滑动）
     */
    private fun createCandidatesContainer(): View {
        // 创建外层容器（一直显示）
        val outerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(46)  // 降低高度到46dp，进一步减小键盘高度
            ).apply {
                setMargins(0, dpToPx(0), 0, dpToPx(3))  // 减少上下间距，使候选区更靠近按键区域
            }
            setBackgroundColor(Color.parseColor("#E0E0E0"))  // 与键盘背景色一致
            visibility = View.VISIBLE  // 一直显示
            // 移除elevation，避免产生阴影边框效果
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                elevation = 0f  // 移除阴影，使候选区与键盘完全融合
            }
        }
        
        // 创建横向滚动视图
        val scrollView = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            isHorizontalScrollBarEnabled = false  // 隐藏滚动条
            isFillViewport = true
        }
        
        // 创建内部容器（用于放置候选词）
        val innerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(dpToPx(12), dpToPx(1), dpToPx(12), dpToPx(1))  // 减少上下padding到1dp，使候选区更紧凑
            gravity = Gravity.CENTER_VERTICAL
        }
        
        candidatesContainer = innerContainer
        candidatesScrollView = scrollView
        candidatesOuterContainer = outerContainer
        
        scrollView.addView(innerContainer)
        outerContainer.addView(scrollView)
        
        updateCandidatesContainer(innerContainer)
        return outerContainer
    }
    
    /**
     * 更新候选词容器
     */
    private fun updateCandidatesContainer(container: LinearLayout) {
        container.removeAllViews()
        
        // 外层容器一直显示
        candidatesOuterContainer?.visibility = View.VISIBLE
        
        // 获取候选词（如果有拼音输入）
        val candidates = if (isChineseMode && currentPinyin.isNotEmpty()) {
            pinyinDictionary.getCandidates(currentPinyin, lastSelectedWord)
        } else {
            emptyList()
        }
        
        // 判断是否应该显示语音和返回应用按钮
        // 条件：只要没有候选词，就显示语音和返回应用按钮
        val hasNoCandidates = candidates.isEmpty()
        
        // 在候选框最左边添加语音输入按钮（小话筒图标 + "点击说话"文字）
        // 只要没有候选词的时候，都显示语音和返回应用这俩快捷键
        // 已取消语音输入图标显示
        /*
        if (hasNoCandidates) {
            val voiceInputButton = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    setMargins(0, 0, dpToPx(8), 0)  // 右边距
                }
                setPadding(dpToPx(8), 0, dpToPx(8), 0)
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                
                setOnClickListener {
                    Log.d(TAG, "点击语音输入按钮")
                    startSpeechRecognition()
                }
                
                // 添加小话筒图标
                val micIcon = android.widget.ImageView(this@SimpleInputMethodService).apply {
                    try {
                        val micResId = resources.getIdentifier("ic_mic", "drawable", packageName)
                        if (micResId != 0) {
                            val micDrawable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                resources.getDrawable(micResId, null)
                            } else {
                                @Suppress("DEPRECATION")
                                resources.getDrawable(micResId)
                            }
                            if (micDrawable != null) {
                                // 设置图标颜色为灰色
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                    micDrawable.setTint(Color.parseColor("#666666"))
                                }
                                setImageDrawable(micDrawable)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "无法加载麦克风图标: ${e.message}")
                    }
                    layoutParams = LinearLayout.LayoutParams(dpToPx(30), dpToPx(30)).apply {
                        setMargins(0, dpToPx(2), dpToPx(4), 0)  // 上边距2dp向下移动，图标和文字之间的间距
                    }
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                }
                addView(micIcon)
                
                // 只显示语音图标，不显示文字
            }
            container.addView(voiceInputButton)
        }
        */
        
        if (hasNoCandidates) {
            // 添加跳转到应用的按钮（在语音按钮旁边）
            val launchAppButton = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    setMargins(0, 0, dpToPx(8), 0)  // 右边距
                }
                setPadding(dpToPx(8), 0, dpToPx(8), 0)
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                
                setOnClickListener {
                    Log.d(TAG, "点击跳转到应用按钮")
                    launchApp()
                }
                
                // 添加应用图标
                val appIcon = android.widget.ImageView(this@SimpleInputMethodService).apply {
                    try {
                        val appIconResId = resources.getIdentifier("ic_app_launcher", "drawable", packageName)
                        if (appIconResId != 0) {
                            val appIconDrawable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                resources.getDrawable(appIconResId, null)
                            } else {
                                @Suppress("DEPRECATION")
                                resources.getDrawable(appIconResId)
                            }
                            if (appIconDrawable != null) {
                                setImageDrawable(appIconDrawable)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "无法加载应用图标: ${e.message}")
                    }
                    layoutParams = LinearLayout.LayoutParams(dpToPx(26), dpToPx(26)).apply {
                        setMargins(0, 0, dpToPx(4), 0)  // 图标和文字之间的间距
                    }
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                }
                addView(appIcon)
                
                // 只显示应用图标，不显示文字
            }
            container.addView(launchAppButton)
        }
        
        if (isChineseMode && currentPinyin.isNotEmpty()) {
            // 先显示拼音
            val pinyinText = android.widget.TextView(this).apply {
                text = currentPinyin
                textSize = 14f
                setTextColor(Color.parseColor("#999999"))
                // 增加padding扩大可点击区域
                setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    setMargins(0, 0, dpToPx(8), 0)  // 右边距，与候选词分开
                }
                gravity = Gravity.CENTER_VERTICAL
                
                // 添加点击效果和事件：按下时改变颜色，松开时输入拼音
                setOnTouchListener { view, motionEvent ->
                    when (motionEvent.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            setTextColor(Color.parseColor("#666666"))  // 按下时颜色变深
                            true
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            setTextColor(Color.parseColor("#999999"))  // 恢复原色
                            // 在ACTION_UP时直接处理点击，确保点击有效
                            Log.d(TAG, "点击拼音，直接输入: $currentPinyin")
                            val pinyinToCommit = currentPinyin
                            commitText(pinyinToCommit)
                            currentPinyin = ""
                            updateCandidatesDisplay()
                            true
                        }
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            setTextColor(Color.parseColor("#999999"))  // 恢复原色
                            true
                        }
                        else -> false
                    }
                }
                
                // 确保TextView可点击
                isClickable = true
                isFocusable = true
            }
            container.addView(pinyinText)
            
            // 候选词已在前面获取，直接使用
            if (candidates.isNotEmpty()) {
                Log.d(TAG, "在键盘中显示 ${candidates.size} 个候选词")
                // 显示所有候选词（不再限制数量，因为可以滑动）
                candidates.forEachIndexed { index, candidate ->
                    val candidateButton = Button(this).apply {
                        text = candidate  // 去掉编号
                        textSize = 16f
                        setTextColor(Color.parseColor("#333333"))
                        // 减少内边距，确保在候选框内完全显示，文字不被遮挡
                        val touchPadding = dpToPx(2)  // 保持触摸区域padding为2dp
                        setPadding(dpToPx(8) + touchPadding, dpToPx(2) + touchPadding, 
                                  dpToPx(8) + touchPadding, dpToPx(2) + touchPadding)  // 减少上下padding到2dp，使按钮更紧凑
                        
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            dpToPx(37)  // 降低按钮高度到37dp，减少3dp使候选区更紧凑
                        ).apply {
                            setMargins(dpToPx(2), 0, dpToPx(2), 0)  // 进一步缩小间距
                        }
                        
                        // 移除阴影效果
                        elevation = 0f
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            stateListAnimator = null
                        }
                        
                        // 设置背景，颜色和键盘背景一致
                        val drawable = GradientDrawable().apply {
                            setColor(Color.parseColor("#E0E0E0"))  // 与键盘背景色一致
                            cornerRadius = dpToPx(4).toFloat()
                            setStroke(0, Color.TRANSPARENT)  // 边框不可见
                        }
                        background = drawable
                        
                        // 保存原始背景用于点击效果
                        val originalDrawable = drawable
                        var isPressed = false
                        setOnTouchListener { view, motionEvent ->
                            when (motionEvent.action) {
                                android.view.MotionEvent.ACTION_DOWN -> {
                                    isPressed = true
                                    view.alpha = 0.7f
                                    // 按下时背景变为功能键颜色，提供视觉反馈（与其他按键一致）
                                    val pressedDrawable = GradientDrawable().apply {
                                        setColor(Color.parseColor("#BDBDBD"))  // 功能键颜色，与其他按键一致
                                        cornerRadius = dpToPx(4).toFloat()
                                        setStroke(0, Color.TRANSPARENT)  // 边框不可见
                                    }
                                    view.background = pressedDrawable
                                    true  // 消费DOWN事件，开始触摸反馈
                                }
                                android.view.MotionEvent.ACTION_UP -> {
                                    view.alpha = 1.0f
                                    view.background = originalDrawable
                                    // 如果确实按下了，触发点击
                                    if (isPressed) {
                                        isPressed = false
                                        Log.d(TAG, "========== 点击候选词按钮 ==========")
                                        Log.d(TAG, "候选词: $candidate, 索引: $index")
                                        selectCandidate(candidate)
                                    }
                                    false  // 不消费事件，让系统也能处理
                                }
                                android.view.MotionEvent.ACTION_CANCEL -> {
                                    isPressed = false
                                    view.alpha = 1.0f
                                    view.background = originalDrawable
                                    false
                                }
                                else -> false
                            }
                        }
                        
                        // 设置最小宽度，避免按钮太宽
                        minWidth = 0
                        minimumWidth = 0
                        
                        // 确保可点击
                        isClickable = true
                        isFocusable = true
                        
                        // 同时设置onClickListener作为备用
                        setOnClickListener {
                            Log.d(TAG, "========== onClick候选词按钮 ==========")
                            Log.d(TAG, "候选词: $candidate, 索引: $index")
                            selectCandidate(candidate)
                        }
                    }
                    container.addView(candidateButton)
                }
                
                // 在候选词右侧添加跳转到应用的按钮
                val launchAppButton = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.MATCH_PARENT
                    ).apply {
                        setMargins(dpToPx(8), 0, 0, 0)  // 左边距，与候选词分开
                    }
                    setPadding(dpToPx(8), 0, dpToPx(8), 0)
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    
                    setOnClickListener {
                        Log.d(TAG, "点击跳转到应用按钮（候选词区域）")
                        launchApp()
                    }
                    
                    // 添加应用图标
                    val appIcon = android.widget.ImageView(this@SimpleInputMethodService).apply {
                        try {
                            val appIconResId = resources.getIdentifier("ic_app_launcher", "drawable", packageName)
                            if (appIconResId != 0) {
                                val appIconDrawable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                    resources.getDrawable(appIconResId, null)
                                } else {
                                    @Suppress("DEPRECATION")
                                    resources.getDrawable(appIconResId)
                                }
                                if (appIconDrawable != null) {
                                    setImageDrawable(appIconDrawable)
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "无法加载应用图标: ${e.message}")
                        }
                        layoutParams = LinearLayout.LayoutParams(dpToPx(26), dpToPx(26)).apply {
                            setMargins(0, 0, dpToPx(4), 0)  // 图标和文字之间的间距
                        }
                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    }
                    addView(appIcon)
                    
                    // 只显示应用图标，不显示文字
                }
                container.addView(launchAppButton)
                
                // 在应用按钮右侧添加设置按钮（取代"问"按钮）
                val settingsButton = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    val touchPadding = dpToPx(1)
                    setPadding(dpToPx(4) + touchPadding, dpToPx(1) + touchPadding, 
                              dpToPx(4) + touchPadding, dpToPx(1) + touchPadding)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dpToPx(32)
                    ).apply {
                        setMargins(dpToPx(6), 0, 0, 0)  // 左边距，与应用按钮分开
                    }
                    // 移除阴影效果
                    elevation = 0f
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        stateListAnimator = null
                    }
                    val drawable = GradientDrawable().apply {
                        setColor(Color.parseColor("#E0E0E0"))
                        cornerRadius = dpToPx(4).toFloat()
                        setStroke(0, Color.TRANSPARENT)  // 边框不可见
                    }
                    background = drawable
                    val originalDrawable = drawable
                    
                    // 添加交换图标
                    val iconText = android.widget.TextView(this@SimpleInputMethodService).apply {
                        text = "⇄"
                        textSize = 14f
                        setTextColor(Color.parseColor("#333333"))
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                    }
                    addView(iconText)
                    
                    // 添加"输入法切换"文字
                    val labelText = android.widget.TextView(this@SimpleInputMethodService).apply {
                        text = "输入法切换"
                        textSize = 12f
                        setTextColor(Color.parseColor("#666666"))
                        gravity = Gravity.CENTER
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            topMargin = dpToPx(-3)  // 减小与图标的间距
                        }
                    }
                    addView(labelText)
                    
                    var isPressed = false
                    setOnTouchListener { view, motionEvent ->
                        when (motionEvent.action) {
                            android.view.MotionEvent.ACTION_DOWN -> {
                                isPressed = true
                                view.alpha = 0.7f
                                val pressedDrawable = GradientDrawable().apply {
                                    setColor(Color.parseColor("#BDBDBD"))
                                    cornerRadius = dpToPx(4).toFloat()
                                    setStroke(0, Color.TRANSPARENT)  // 边框不可见
                                }
                                view.background = pressedDrawable
                                true
                            }
                            android.view.MotionEvent.ACTION_UP -> {
                                view.alpha = 1.0f
                                view.background = originalDrawable
                                if (isPressed) {
                                    isPressed = false
                                    Log.d(TAG, "点击设置按钮")
                                    try {
                                        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        startActivity(intent)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "跳转到键盘设置页面失败: ${e.message}", e)
                                        Toast.makeText(this@SimpleInputMethodService, "打开设置失败", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                false
                            }
                            android.view.MotionEvent.ACTION_CANCEL -> {
                                isPressed = false
                                view.alpha = 1.0f
                                view.background = originalDrawable
                                false
                            }
                            else -> false
                        }
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        Log.d(TAG, "点击设置按钮（onClick）")
                        try {
                            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            Log.e(TAG, "跳转到键盘设置页面失败: ${e.message}", e)
                            Toast.makeText(this@SimpleInputMethodService, "打开设置失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                container.addView(settingsButton)
            }
            // 注意：如果有拼音输入但候选词为空，语音和返回应用按钮已经在开头显示了，这里不需要重复显示
        } else {
            // 没有拼音输入时，如果没有候选词，语音和返回应用按钮已经在开头显示了
            // 这里只需要显示设置按钮和收起键盘按钮
            // 没有候选内容时，显示向下箭头按钮，点击可以收起键盘
            // 先添加一个占位视图，让箭头右对齐
            val spacer = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    weight = 1f  // 占据剩余空间
                }
            }
            container.addView(spacer)
            
            // 添加设置按钮（在没有候选内容时也显示，取代"问"按钮）
            val settingsButtonNoCandidates = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                val touchPadding = dpToPx(1)
                setPadding(dpToPx(4) + touchPadding, dpToPx(1) + touchPadding, 
                          dpToPx(4) + touchPadding, dpToPx(1) + touchPadding)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dpToPx(32)
                ).apply {
                    setMargins(0, 0, dpToPx(4), 0)  // 右边距，减小与收起键盘图标的距离
                }
                // 移除阴影效果
                elevation = 0f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    stateListAnimator = null
                }
                val drawable = GradientDrawable().apply {
                    setColor(Color.parseColor("#E0E0E0"))
                    cornerRadius = dpToPx(4).toFloat()
                    setStroke(0, Color.TRANSPARENT)  // 边框不可见
                }
                background = drawable
                val originalDrawable = drawable
                
                // 添加交换图标
                val iconText = android.widget.TextView(this@SimpleInputMethodService).apply {
                    text = "⇄"
                    textSize = 14f
                    setTextColor(Color.parseColor("#333333"))
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                addView(iconText)
                
                // 添加"输入法切换"文字
                val labelText = android.widget.TextView(this@SimpleInputMethodService).apply {
                    text = "输入法切换"
                    textSize = 10f
                    setTextColor(Color.parseColor("#666666"))
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dpToPx(-3)  // 减小与图标的间距
                    }
                }
                addView(labelText)
                
                var isPressed = false
                setOnTouchListener { view, motionEvent ->
                    when (motionEvent.action) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            isPressed = true
                            view.alpha = 0.7f
                            val pressedDrawable = GradientDrawable().apply {
                                setColor(Color.parseColor("#BDBDBD"))
                                cornerRadius = dpToPx(4).toFloat()
                                setStroke(0, Color.TRANSPARENT)  // 边框不可见
                            }
                            view.background = pressedDrawable
                            true
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            view.alpha = 1.0f
                            view.background = originalDrawable
                            if (isPressed) {
                                isPressed = false
                                Log.d(TAG, "点击设置按钮（无候选内容时）")
                                try {
                                    val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    startActivity(intent)
                                } catch (e: Exception) {
                                    Log.e(TAG, "跳转到键盘设置页面失败: ${e.message}", e)
                                    Toast.makeText(this@SimpleInputMethodService, "打开设置失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                            false
                        }
                        android.view.MotionEvent.ACTION_CANCEL -> {
                            isPressed = false
                            view.alpha = 1.0f
                            view.background = originalDrawable
                            false
                        }
                        else -> false
                    }
                }
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    Log.d(TAG, "点击设置按钮（onClick，无候选内容时）")
                    try {
                        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(intent)
                    } catch (e: Exception) {
                        Log.e(TAG, "跳转到键盘设置页面失败: ${e.message}", e)
                        Toast.makeText(this@SimpleInputMethodService, "打开设置失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            container.addView(settingsButtonNoCandidates)
            
            // 使用ImageView显示向下箭头图标
            val hideKeyboardButton = android.widget.ImageView(this).apply {
                try {
                    val hideIconResId = resources.getIdentifier("ic_keyboard_hide", "drawable", packageName)
                    if (hideIconResId != 0) {
                        val hideIconDrawable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                            resources.getDrawable(hideIconResId, null)
                        } else {
                            @Suppress("DEPRECATION")
                            resources.getDrawable(hideIconResId)
                        }
                        if (hideIconDrawable != null) {
                            setImageDrawable(hideIconDrawable)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "无法加载收起键盘图标: ${e.message}")
                }
                layoutParams = LinearLayout.LayoutParams(dpToPx(26), dpToPx(26)).apply {
                    setMargins(0, dpToPx(2), dpToPx(12), 0)  // 上边距2dp向下移动，右边距
                }
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                
                // 完全去掉背景，无框
                background = null
                
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    Log.d(TAG, "点击收起键盘按钮")
                    requestHideSelf(0)  // 收起输入法键盘
                }
            }
            container.addView(hideKeyboardButton)
        }
    }
    
    /**
     * 创建一行按键
     */
    private fun createRow(keys: List<String>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(2), 0, dpToPx(2))  // 统一上下间距为2dp，与左右间距一致
            }
        }
        
        keys.forEach { key ->
            // 注意：符号模式下的数字和符号行由createSymbolRow处理，这里只处理普通模式
            if (key.matches(Regex("[a-z]"))) {
                // 字母键
                row.addView(createLetterButton(key))
            } else {
                // 数字键（只在普通模式下使用）
                val button = createKeyButton(key)
                button.setOnClickListener {
                    if (isChineseMode && currentPinyin.isNotEmpty()) {
                        // 中文模式下，数字键用于选择候选词
                        val candidates = pinyinDictionary.getCandidates(currentPinyin, lastSelectedWord)
                        val index = key.toIntOrNull()?.minus(1) ?: -1
                        Log.d(TAG, "数字键点击: $key, 索引: $index, 候选词数量: ${candidates.size}")
                        if (index >= 0 && index < candidates.size) {
                            Log.d(TAG, "选择候选词索引 $index: ${candidates[index]}")
                            selectCandidate(candidates[index])
                        } else {
                            // 如果数字超出范围，直接输入数字
                            Log.d(TAG, "数字超出范围，直接输入数字: $key")
                            typeChar(key)
                        }
                    } else {
                        // 英文模式下，直接输入数字
                        typeChar(key)
                    }
                }
                row.addView(button)
            }
        }
        
        return row
    }
    
    /**
     * 创建符号行（用于符号模式）
     */
    private fun createSymbolRow(symbols: List<String>): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dpToPx(2), 0, dpToPx(2))  // 统一上下间距为2dp，与左右间距一致
            }
        }
        
        symbols.forEach { symbol ->
            val button = createKeyButton(symbol)
            button.textSize = 14f  // 符号按键文字小一号
            button.setOnClickListener {
                typeChar(symbol)
            }
            row.addView(button)
        }
        
        return row
    }
    
    /**
     * 创建字母按键
     */
    private fun createLetterButton(letter: String): Button {
        // 根据大小写模式显示字母（中文模式下也支持大写）
        val displayLetter = if (isUpperCase) letter.uppercase() else letter.lowercase()
        return createKeyButton(displayLetter).apply {
            setOnClickListener {
                if (isChineseMode) {
                    // 中文模式下，如果大写模式则输入大写字母，否则输入拼音
                    if (isUpperCase) {
                        typeChar(displayLetter)
                    } else {
                    inputPinyin(letter.lowercase())
                    }
                } else {
                    // 英文模式下，直接输入字母
                    typeChar(displayLetter)
                }
            }
        }
    }
    
    /**
     * 创建普通按键
     */
    private fun createKeyButton(text: String, isFunctionKey: Boolean = false): Button {
        return Button(this).apply {
            this.text = text
            textSize = if (isFunctionKey) 14f else 16f
            setTextColor(Color.BLACK)
            // 增加内边距，扩大可点击区域
            setPadding(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
            minHeight = dpToPx(42)  // 确保最小高度一致
            minimumHeight = dpToPx(42)  // 兼容旧版本
            // 去除字体默认内边距，确保文字垂直居中
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                includeFontPadding = false
            }
            
            layoutParams = LinearLayout.LayoutParams(
                0,
                dpToPx(42)  // 降低按键高度，从48dp减少到42dp
            ).apply {
                weight = 1f
                // 减少边距，扩大可点击区域（边距从2dp减少到1dp）
                setMargins(dpToPx(1), 0, dpToPx(1), 0)
            }
            
            // 设置背景（更圆滑）
            val drawable = GradientDrawable().apply {
                setColor(if (isFunctionKey) Color.parseColor("#BDBDBD") else Color.WHITE)
                cornerRadius = dpToPx(8).toFloat()  // 增加圆角，使其更圆滑
                setStroke(1, Color.parseColor("#9E9E9E"))
            }
            background = drawable
            
            // 设置按下效果和预览动画（所有非功能键显示预览）
            val originalDrawable = drawable  // 保存原始背景
            setOnTouchListener { view, motionEvent ->
                when (motionEvent.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        view.alpha = 0.7f
                        // 功能键按下时背景变为白色
                        if (isFunctionKey) {
                            val whiteDrawable = GradientDrawable().apply {
                                setColor(Color.WHITE)
                                cornerRadius = dpToPx(8).toFloat()
                                setStroke(1, Color.parseColor("#9E9E9E"))
                            }
                            view.background = whiteDrawable
                        } else {
                            // 非功能键按下时背景变为功能键颜色
                            val functionKeyDrawable = GradientDrawable().apply {
                                setColor(Color.parseColor("#BDBDBD"))  // 功能键颜色
                                cornerRadius = dpToPx(8).toFloat()
                                setStroke(1, Color.parseColor("#9E9E9E"))
                            }
                            view.background = functionKeyDrawable
                        }
                        // 所有非功能键都显示预览
                        if (!isFunctionKey) {
                            showKeyPreview(view, text)
                        }
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        view.alpha = 1.0f
                        // 恢复原始背景
                        view.background = originalDrawable
                        // 所有非功能键都隐藏预览
                        if (!isFunctionKey) {
                            hideKeyPreview()
                        }
                    }
                }
                false
            }
        }
    }
    
    /**
     * 显示按键预览
     */
    private fun showKeyPreview(keyView: View, keyText: String) {
        try {
            // 如果预览视图不存在，创建它
            if (keyPreviewView == null) {
                keyPreviewView = android.widget.TextView(this).apply {
                    textSize = 30f  // 稍微大一点：从24f改为30f
                    setTextColor(Color.BLACK)  // 黑色字符
                    gravity = Gravity.CENTER
                    setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))  // 保持padding不变
                    
                    // 设置白色圆角矩形背景
                    val backgroundDrawable = GradientDrawable().apply {
                        setColor(Color.WHITE)
                        cornerRadius = dpToPx(12).toFloat()
                        setElevation(dpToPx(8).toFloat())  // 添加阴影效果
                    }
                    background = backgroundDrawable
                    
                    // 初始状态不可见
                    visibility = View.GONE
                }
            }
            
            // 如果预览视图不在正确的父视图中，将其添加到键盘根视图
            val currentParent = keyPreviewView?.parent as? ViewGroup
            if (keyPreviewView != null && (currentParent == null || currentParent != keyboardRootView)) {
                // 先从旧的父视图中移除（如果存在）
                currentParent?.removeView(keyPreviewView)
                // 添加到新的根视图
                keyboardRootView?.addView(keyPreviewView, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
            
            // 设置预览文本（字母显示大写，其他字符保持原样）
            keyPreviewView?.text = if (keyText.length == 1 && keyText[0].isLetter()) {
                keyText.uppercase()
            } else {
                keyText
            }
            
            // 计算按键在屏幕上的位置
            val location = IntArray(2)
            keyView.getLocationOnScreen(location)
            val keyX = location[0]
            val keyY = location[1]
            val keyWidth = keyView.width
            val keyHeight = keyView.height
            
            // 计算预览视图的位置（按键上方居中）
            keyPreviewView?.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val previewWidth = keyPreviewView?.measuredWidth ?: 0
            val previewHeight = keyPreviewView?.measuredHeight ?: 0
            
            // 获取键盘根视图在屏幕上的位置
            val rootLocation = IntArray(2)
            keyboardRootView?.getLocationOnScreen(rootLocation)
            val rootX = rootLocation[0]
            val rootY = rootLocation[1]
            
            // 计算相对于键盘根视图的位置（按键上方居中，像气泡一样）
            val previewX = keyX - rootX + (keyWidth - previewWidth) / 2
            val previewY = keyY - rootY - previewHeight - dpToPx(12)  // 按键上方12dp，更像气泡
            
            // 使用FrameLayout.LayoutParams进行绝对定位
            keyPreviewView?.layoutParams = android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = previewX
                topMargin = previewY
                gravity = Gravity.NO_GRAVITY  // 使用绝对定位
            }
            
            keyPreviewView?.visibility = View.VISIBLE
            keyPreviewView?.alpha = 0f
            keyPreviewView?.animate()?.alpha(1f)?.setDuration(100)?.start()  // 淡入动画
        } catch (e: Exception) {
            Log.e(TAG, "显示按键预览失败: ${e.message}", e)
        }
    }
    
    /**
     * 隐藏按键预览
     */
    private fun hideKeyPreview() {
        try {
            keyPreviewView?.animate()?.alpha(0f)?.setDuration(100)?.withEndAction {
                keyPreviewView?.visibility = View.GONE
            }?.start()  // 淡出动画
        } catch (e: Exception) {
            Log.e(TAG, "隐藏按键预览失败: ${e.message}", e)
            keyPreviewView?.visibility = View.GONE
        }
    }
    
    /**
     * dp转px
     */
    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
    }
    
    /**
     * 切换大小写
     */
    private fun toggleShift() {
        try {
            isUpperCase = !isUpperCase
            Log.d(TAG, "切换大小写模式: ${if (isUpperCase) "大写" else "小写"}")
            
            // 如果切换到中文模式的大写，清空未完成的拼音
            if (isChineseMode && isUpperCase && currentPinyin.isNotEmpty()) {
                Log.d(TAG, "切换到中文大写模式，清空未完成的拼音: $currentPinyin")
                currentPinyin = ""
                updateCandidatesDisplay()
            }
            
            // 使用setInputView方法安全地更新键盘视图
            keyboardView = createKeyboardView()
            setInputView(keyboardView)
        } catch (e: Exception) {
            Log.e(TAG, "切换大小写模式异常: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 切换中文模式
     */
    private fun toggleChineseMode() {
        // 防抖：如果正在切换，直接返回
        if (isTogglingMode) {
            Log.w(TAG, "toggleChineseMode: 正在切换中，忽略重复调用")
            return
        }
        
        try {
            // 设置切换标志，防止重复执行
            isTogglingMode = true
            
            Log.d(TAG, "========== toggleChineseMode 开始 ==========")
            Log.d(TAG, "切换前模式: ${if (isChineseMode) "中文" else "英文"}")
            Log.d(TAG, "当前线程: ${Thread.currentThread().name}")
            Log.d(TAG, "keyboardView状态: ${if (keyboardView != null) "存在" else "null"}")
            
            // 切换模式
            isChineseMode = !isChineseMode
            currentPinyin = ""  // 切换模式时清空拼音
            lastSelectedWord = null  // 切换模式时清空前一个词（开始新的输入）
            Log.d(TAG, "切换后模式: ${if (isChineseMode) "中文" else "英文"}")
            
            // 使用setInputView方法安全地更新键盘视图
            Log.d(TAG, "开始创建新键盘视图...")
            keyboardView = createKeyboardView()
            Log.d(TAG, "键盘视图创建完成，开始调用setInputView...")
            
            setInputView(keyboardView)
            Log.d(TAG, "setInputView调用完成")
            
            // 更新候选词显示
            Log.d(TAG, "开始更新候选词显示...")
            updateCandidatesDisplay()
            Log.d(TAG, "候选词显示更新完成")
            
            Log.d(TAG, "========== toggleChineseMode 完成 ==========")
        } catch (e: Exception) {
            Log.e(TAG, "========== toggleChineseMode 异常 ==========")
            Log.e(TAG, "异常类型: ${e.javaClass.simpleName}")
            Log.e(TAG, "异常消息: ${e.message}")
            Log.e(TAG, "异常堆栈:", e)
            e.printStackTrace()
        } finally {
            // 确保无论成功还是失败都重置标志
            isTogglingMode = false
            Log.d(TAG, "重置切换标志: isTogglingMode = false")
        }
    }
    
    /**
     * 切换符号模式
     */
    private fun toggleSymbolMode() {
        try {
            isSymbolMode = !isSymbolMode
            isMoreSymbolMode = false  // 切换符号模式时，退出更多符号模式
            Log.d(TAG, "切换符号模式: ${if (isSymbolMode) "符号" else "数字"}")
            // 使用setInputView方法安全地更新键盘视图
            keyboardView = createKeyboardView()
            setInputView(keyboardView)
        } catch (e: Exception) {
            Log.e(TAG, "切换符号模式异常: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 切换更多符号模式
     */
    private fun toggleMoreSymbolMode() {
        try {
            isMoreSymbolMode = !isMoreSymbolMode
            Log.d(TAG, "切换更多符号模式: ${if (isMoreSymbolMode) "更多符号" else "普通符号"}")
            // 使用setInputView方法安全地更新键盘视图
            keyboardView = createKeyboardView()
            setInputView(keyboardView)
        } catch (e: Exception) {
            Log.e(TAG, "切换更多符号模式异常: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 输入拼音
     */
    private fun inputPinyin(char: String) {
        currentPinyin += char
        Log.d(TAG, "========== 输入拼音 ==========")
        Log.d(TAG, "当前拼音: $currentPinyin")
        Log.d(TAG, "中文模式: $isChineseMode")
        
        // 确保候选词视图显示
        setCandidatesViewShown(true)
        
        // 检查是否有匹配的拼音（传递前一个词用于跨词预测）
        val candidates = pinyinDictionary.getCandidates(currentPinyin, lastSelectedWord)
        Log.d(TAG, "拼音 '$currentPinyin' 的候选词数量: ${candidates.size}, 前一个词: $lastSelectedWord")
        if (candidates.isNotEmpty()) {
            Log.d(TAG, "候选词: ${candidates.take(5).joinToString(", ")}")
        }
        
        // 如果拼音太长且无匹配，重置（限制扩大到20个字符）
        if (candidates.isEmpty() && currentPinyin.length > 20) {
            Log.d(TAG, "拼音太长且无匹配，重置为: $char")
            currentPinyin = char
        }
        
        // 更新候选词显示
        updateCandidatesDisplay()
        Log.d(TAG, "========== 拼音输入完成 ==========")
    }
    
    /**
     * 选择候选词
     */
    private fun selectCandidate(candidate: String) {
        Log.d(TAG, "========== 选择候选词 ==========")
        Log.d(TAG, "候选词: $candidate")
        Log.d(TAG, "当前拼音: $currentPinyin")
        Log.d(TAG, "前一个词: $lastSelectedWord")
        
        // 记录用户输入的词（用于热度排序和跨词预测）
        if (currentPinyin.isNotEmpty()) {
            // 传递前一个词，用于记录词对关系
            pinyinDictionary.recordUserInput(currentPinyin, candidate, lastSelectedWord)
        }
        
        // 提交候选词到输入框
        val success = commitText(candidate)
        if (success) {
            Log.d(TAG, "✓ 候选词输入成功: $candidate")
            // 更新最后选择的词（用于下一次跨词预测）
            lastSelectedWord = candidate
        } else {
            Log.w(TAG, "✗ 候选词输入失败: $candidate")
        }
        
        // 清空拼音
        currentPinyin = ""
        
        // 更新候选词显示（隐藏）
        updateCandidatesDisplay()
        Log.d(TAG, "========== 选择候选词完成 ==========")
    }
    
    /**
     * 确认第一个候选词
     */
    private fun confirmFirstCandidate() {
        if (currentPinyin.isNotEmpty()) {
            val candidates = pinyinDictionary.getCandidates(currentPinyin, lastSelectedWord)
            if (candidates.isNotEmpty()) {
                selectCandidate(candidates[0])
            } else {
                // 没有候选词，输入拼音本身
                commitText(currentPinyin)
                currentPinyin = ""
                updateCandidatesDisplay()
            }
        } else {
            // 没有拼音，输入空格
            typeChar(" ")
        }
    }
    
    /**
     * 更新候选词显示
     */
    private fun updateCandidatesDisplay() {
        // 优先更新集成在键盘中的候选词容器
        candidatesContainer?.let { container ->
            updateCandidatesContainer(container)
            Log.d(TAG, "候选词容器已更新，可见性: ${container.visibility}, 拼音: $currentPinyin")
        }
        
        // 不再更新系统候选词视图，因为我们使用集成在键盘内的候选词容器
        // 候选词容器已经在updateCandidatesContainer()中更新了
    }
    
    /**
     * 获取数字对应的符号
     */
    private fun getSymbolForNumber(number: String): String {
        return when (number) {
            "1" -> "!"
            "2" -> "@"
            "3" -> "#"
            "4" -> "$"
            "5" -> "%"
            "6" -> "^"
            "7" -> "&"
            "8" -> "*"
            "9" -> "("
            "0" -> ")"
            else -> number
        }
    }
    
    /**
     * 提交文本到输入框（内部方法，用于键盘按键和Broadcast）
     */
    private fun commitText(text: String): Boolean {
        val ic = currentInputConnection
        return if (ic != null) {
            val success = ic.commitText(text, 1)
            if (success) {
                Log.d(TAG, "输入文本: $text")
                
                // 已改用输入会话机制，不再在输入法层面记录
                // 文本输入记录统一在 AccessibilityService 层面通过输入会话管理
            } else {
                Log.w(TAG, "输入文本失败: $text")
            }
            success
        } else {
            Log.w(TAG, "无法输入文本: InputConnection为null")
            false
        }
    }
    
    /**
     * 记录从输入法输入的文本（用户手动输入）
     */
    private fun recordTextInputFromIME(text: String) {
        try {
            // 获取当前输入框的位置（通过 InputConnection）
            val ic = currentInputConnection
            if (ic == null) {
                return
            }
            
            // 尝试获取当前应用的包名（输入法服务的包名就是当前应用的包名）
            val packageName = packageName
            
            // 记录文本输入事件（使用 TEXT_INPUT 类型，表示用户主动输入）
            // 注意：输入法层面无法获取输入框的精确位置和View信息，这些信息会在AccessibilityService层面补充
            val trajectoryEvent = TrajectoryEvent(
                type = TrajectoryEventType.TEXT_INPUT,
                timestamp = System.currentTimeMillis(),
                x = null, // 输入法层面无法获取精确位置，由AccessibilityService补充
                y = null,
                text = text,
                packageName = packageName,
                className = null,
                viewId = null,
                contentDescription = null
            )
            
            TrajectoryRecorder.recordEvent(trajectoryEvent)
            Log.d(TAG, "记录输入法文本输入事件: 文本=\"$text\"")
        } catch (e: Exception) {
            Log.e(TAG, "记录输入法文本输入事件失败: ${e.message}", e)
        }
    }
    
    /**
     * 输入文本（键盘按键调用）
     */
    private fun typeChar(text: String) {
        commitText(text)
    }
    
    /**
     * 删除文本
     */
    private fun deleteText() {
        if (isChineseMode && currentPinyin.isNotEmpty()) {
            // 中文模式下，先删除拼音
            currentPinyin = currentPinyin.dropLast(1)
            Log.d(TAG, "删除拼音: $currentPinyin")
            updateCandidatesDisplay()
        } else {
            // 英文模式下，删除文本
            val ic = currentInputConnection
            if (ic != null) {
                val selectedText = ic.getSelectedText(0)
                if (selectedText != null && selectedText.isNotEmpty()) {
                    // 如果有选中文本，删除选中文本
                    ic.deleteSurroundingText(0, selectedText.length)
                } else {
                    // 删除光标前一个字符
                    ic.deleteSurroundingText(1, 0)
                }
                Log.d(TAG, "删除文本")
            } else {
                Log.w(TAG, "无法删除文本: InputConnection为null")
            }
        }
    }
    
    /**
     * 开始连续删除（长按删除键时调用）
     */
    private fun startContinuousDelete() {
        // 先执行一次删除
        deleteText()
        
        // 然后每隔一定时间重复删除（初始间隔较长，之后加快）
        var deleteInterval = 150L  // 初始间隔150ms
        
        deleteKeyRepeatRunnable = object : Runnable {
            override fun run() {
                if (isDeleteKeyLongPressed) {
                    deleteText()
                    // 逐渐加快删除速度，最快50ms一次
                    deleteInterval = maxOf(50L, deleteInterval - 10L)
                    mainHandler.postDelayed(this, deleteInterval)
                }
            }
        }
        mainHandler.postDelayed(deleteKeyRepeatRunnable!!, deleteInterval)
    }
    
    /**
     * 发送回车
     * 如果中文模式下有未完成的拼音，则输入拼音本身
     * 方案B优化：确保回车操作在主线程执行（InputConnection 需要在主线程操作）
     */
    private fun sendEnter() {
        Log.d(TAG, "========== sendEnter() 开始执行 ==========")
        Log.d(TAG, "isChineseMode: $isChineseMode, currentPinyin: $currentPinyin")
        
        // InputConnection 操作必须在主线程执行
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            executeSendEnterInternal()
        } else {
            mainHandler.post {
                executeSendEnterInternal()
            }
        }
    }
    
    /**
     * 内部方法：执行发送回车逻辑（必须在主线程调用）
     */
    private fun executeSendEnterInternal() {
        // 如果中文模式下有未完成的拼音，输入拼音本身
        if (isChineseMode && currentPinyin.isNotEmpty()) {
            Log.d(TAG, "中文输入途中点击换行，输入拼音: $currentPinyin")
            commitText(currentPinyin)
            currentPinyin = ""
            updateCandidatesDisplay()
            Log.d(TAG, "========== sendEnter() 完成（输入拼音） ==========")
            return
        }
        
        // 否则正常发送回车
        val ic = currentInputConnection
        Log.d(TAG, "currentInputConnection: ${if (ic != null) "不为null" else "为null"}")
        if (ic != null) {
            try {
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
                ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                Log.d(TAG, "✓✓✓ 已发送回车键事件 (KEYCODE_ENTER)")
                Log.d(TAG, "========== sendEnter() 完成（发送回车） ==========")
            } catch (e: Exception) {
                Log.e(TAG, "发送回车键事件时出错: ${e.message}", e)
                Log.d(TAG, "========== sendEnter() 完成（异常） ==========")
            }
        } else {
            Log.w(TAG, "❌ 无法发送回车: InputConnection为null")
            Log.w(TAG, "   可能原因: 输入框已失去焦点或输入法未激活")
            Log.d(TAG, "========== sendEnter() 完成（失败） ==========")
        }
    }
    
    /**
     * 创建候选词视图
     */
    override fun onCreateCandidatesView(): View? {
        // 返回null，因为我们已经在键盘视图内部集成了候选词容器
        // 这样可以避免层级冲突，确保候选词显示在键盘上方
        Log.d(TAG, "onCreateCandidatesView: 返回null，使用集成在键盘内的候选词容器")
        return null
    }
    
    /**
     * 创建候选词视图
     */
    private fun createCandidatesView(): View {
        val candidatesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dpToPx(60)  // 进一步增加高度以便更好地显示候选词
            )
            setBackgroundColor(Color.parseColor("#E8E8E8"))  // 更明显的背景色
            setPadding(12, 12, 12, 12)  // 增加内边距
            gravity = Gravity.CENTER_VERTICAL
            // 确保视图可见
            visibility = View.VISIBLE
            // 添加边框以便调试
            val borderDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#E8E8E8"))
                setStroke(2, Color.parseColor("#CCCCCC"))
            }
            background = borderDrawable
        }
        
        updateCandidatesView(candidatesLayout)
        return candidatesLayout
    }
    
    /**
     * 更新候选词视图
     */
    private fun updateCandidatesView(candidatesLayout: LinearLayout) {
        candidatesLayout.removeAllViews()
        
        if (isChineseMode && currentPinyin.isNotEmpty()) {
            val candidates = pinyinDictionary.getCandidates(currentPinyin, lastSelectedWord)
            
            if (candidates.isNotEmpty()) {
                Log.d(TAG, "显示 ${candidates.size} 个候选词")
                // 显示候选词（最多9个）
                candidates.take(9).forEachIndexed { index, candidate ->
                    val candidateButton = Button(this).apply {
                        text = "${index + 1}.$candidate"
                        textSize = 16f  // 增大字体
                        setTextColor(Color.BLACK)
                        // 增加内边距，扩大可点击区域
                        val touchPadding = dpToPx(6)  // 扩大触摸区域
                        setPadding(16 + touchPadding, 12 + touchPadding, 
                                 16 + touchPadding, 12 + touchPadding)
                        
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            dpToPx(36)  // 固定高度
                        ).apply {
                            setMargins(6, 0, 6, 0)  // 增加边距
                        }
                        
                        // 设置背景
                        val drawable = GradientDrawable().apply {
                            setColor(Color.WHITE)
                            cornerRadius = 6f
                            setStroke(2, Color.parseColor("#4A90E2"))  // 蓝色边框
                        }
                        background = drawable
                        
                        // 确保可点击
                        isClickable = true
                        isFocusable = true
                        
                        setOnClickListener {
                            Log.d(TAG, "========== 点击候选词按钮 ==========")
                            Log.d(TAG, "候选词: $candidate, 索引: $index")
                            selectCandidate(candidate)
                        }
                        
                        // 添加长按支持（可选）
                        setOnLongClickListener {
                            Log.d(TAG, "长按候选词按钮: $candidate")
                            false
                        }
                    }
                    candidatesLayout.addView(candidateButton)
                }
            }
            
            // 确保视图可见
            candidatesLayout.visibility = View.VISIBLE
        } else {
            // 非中文模式，隐藏候选词
            candidatesLayout.visibility = View.GONE
        }
    }
    
    /**
     * 输入文本（公共方法，用于Broadcast自动输入）
     * 
     * @param text 要输入的文本
     * @return 是否成功
     */
    fun inputText(text: String): Boolean {
        // 设置程序输入标记，避免记录程序自动输入
        isProgrammaticInput = true
        return try {
            Log.d(TAG, "========== 开始通过输入法输入文本 ==========")
            Log.d(TAG, "输入文本: $text")
            Log.d(TAG, "文本长度: ${text.length} 字符")
            
            val ic = currentInputConnection
            if (ic == null) {
                Log.e(TAG, "❌ currentInputConnection 为 null")
                Log.e(TAG, "   可能原因: 输入框未获得焦点")
                return false
            }
            
            Log.d(TAG, "✓ 获取到 InputConnection")
            
            // 直接提交文本
            val success = commitText(text)
            
            if (success) {
                Log.d(TAG, "✓✓✓ 文本输入成功: $text")
                Log.d(TAG, "========== 输入完成（成功） ==========")
            } else {
                Log.w(TAG, "❌ commitText 返回 false")
                Log.d(TAG, "========== 输入完成（失败） ==========")
            }
            
            success
        } catch (e: Exception) {
            Log.e(TAG, "❌❌❌ 输入文本时发生异常: ${e.message}", e)
            Log.e(TAG, "异常类型: ${e.javaClass.simpleName}")
            e.printStackTrace()
            false
        } finally {
            // 延迟清除程序输入标记，确保文本变化事件不会被记录
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                isProgrammaticInput = false
                Log.d(TAG, "程序输入标记已清除")
            }, 500) // 等待500ms，确保文本变化事件处理完成
        }
    }
    
    /**
     * 处理输入文本请求（通过Broadcast调用）
     * @param text 要输入的文本
     * @param fromModel 是否为模型发起的输入。若为true，隐藏键盘UI后直接输入（模型不需手动敲键盘）
     */
    private fun handleInputText(text: String, fromModel: Boolean = false) {
        Log.d(TAG, "handleInputText: 开始处理输入请求, fromModel=$fromModel")
        val runInput = {
            if (fromModel) {
                // 模型发起：隐藏键盘UI后直接输入（模型不需手动敲键盘）
                requestHideSelf(0)
                Log.d(TAG, "fromModel=true，已隐藏键盘UI，直接输入")
            }
            inputText(text)
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            runInput()
        } else {
            mainHandler.post { runInput() }
        }
    }
    
    // ==================== 语音识别相关方法 ====================
    
    /**
     * 初始化语音识别器
     */
    private fun initSpeechRecognizer() {
        try {
            Log.d(TAG, "开始初始化语音识别器...")
            
            // 检查是否可用
            val isAvailable = SpeechRecognizer.isRecognitionAvailable(this)
            Log.d(TAG, "语音识别是否可用: $isAvailable")
            
            if (isAvailable) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                if (speechRecognizer != null) {
                    speechRecognizer?.setRecognitionListener(createRecognitionListener())
                    Log.d(TAG, "✓ 语音识别器初始化成功")
                } else {
                    Log.w(TAG, "✗ SpeechRecognizer.createSpeechRecognizer() 返回 null")
                    Log.w(TAG, "可能原因：设备未安装语音识别服务（如Google Speech Services）")
                }
            } else {
                Log.w(TAG, "✗ 设备不支持语音识别")
                Log.w(TAG, "可能原因：")
                Log.w(TAG, "  1. 设备未安装语音识别服务")
                Log.w(TAG, "  2. 某些定制系统可能未预装Google语音服务")
                Log.w(TAG, "  3. 建议：安装Google语音服务或使用第三方语音识别SDK")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "✗ 初始化语音识别器权限错误: ${e.message}", e)
            e.printStackTrace()
        } catch (e: Exception) {
            Log.e(TAG, "✗ 初始化语音识别器失败: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 创建语音识别监听器
     */
    private fun createRecognitionListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "语音识别准备就绪")
                isListening = true
                updateVoiceButtonInKeyboard()
                showVoiceStatus("正在听...")
            }
            
            override fun onBeginningOfSpeech() {
                Log.d(TAG, "开始说话")
                showVoiceStatus("正在识别...")
            }
            
            override fun onRmsChanged(rmsdB: Float) {
                // 音量变化，可用于显示音量指示器
            }
            
            override fun onBufferReceived(buffer: ByteArray?) {
                // 接收音频缓冲区
            }
            
            override fun onEndOfSpeech() {
                Log.d(TAG, "说话结束")
                showVoiceStatus("处理中...")
            }
            
            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "音频错误"
                    SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                    SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                    SpeechRecognizer.ERROR_NO_MATCH -> "无匹配结果"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"
                    SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "说话超时"
                    else -> "未知错误: $error"
                }
                Log.w(TAG, "语音识别错误: $errorMessage")
                isListening = false
                updateVoiceButtonInKeyboard()
                hideVoiceStatus()
                
                // 如果是权限错误，提示用户
                if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    showVoiceStatus("需要麦克风权限")
                }
            }
            
            override fun onResults(results: Bundle?) {
                Log.d(TAG, "语音识别结果")
                isListening = false
                updateVoiceButtonInKeyboard()
                
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val recognizedText = matches[0]
                    Log.d(TAG, "识别结果: $recognizedText")
                    Log.d(TAG, "所有结果: ${matches.joinToString(", ")}")
                    
                    // 直接输入识别结果
                    commitText(recognizedText)
                    hideVoiceStatus()
                } else {
                    Log.w(TAG, "未识别到任何内容")
                    showVoiceStatus("未识别到内容")
                }
            }
            
            override fun onPartialResults(partialResults: Bundle?) {
                // 部分结果（实时识别）
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val partialText = matches[0]
                    Log.d(TAG, "部分识别结果: $partialText")
                    // 可以实时显示部分结果
                }
            }
            
            override fun onEvent(eventType: Int, params: Bundle?) {
                // 其他事件
            }
        }
    }
    
    /**
     * 切换语音识别
     */
    private fun toggleVoiceRecognition() {
        if (isListening) {
            stopSpeechRecognition()
        } else {
            startSpeechRecognition()
        }
    }
    
    /**
     * 开始语音识别（使用科大讯飞ASR）
     * 方案B优化：处理异步初始化未完成的情况
     */
    private fun startSpeechRecognition() {
        try {
            if (!BuildConfig.SPARKCHAIN_ENABLED) {
                showVoiceStatus(VOICE_DISABLED_MESSAGE)
                return
            }
            // 检查权限
            if (!checkAudioPermission()) {
                Log.w(TAG, "没有录音权限，无法启动语音识别")
                showVoiceStatus("需要麦克风权限")
                return
            }
            
            // 方案B优化：如果ASR未初始化，尝试同步初始化（作为后备方案）
            if (mAsr == null) {
                Log.w(TAG, "ASR未初始化，尝试同步初始化...")
                initXunfeiASR()
                // 如果同步初始化仍然失败，等待一小段时间后重试
                if (mAsr == null) {
                    Log.w(TAG, "同步初始化失败，等待异步初始化完成...")
                    // 等待最多1秒，让异步初始化有机会完成
                    var waitCount = 0
                    while (mAsr == null && waitCount < 10) {
                        Thread.sleep(100)
                        waitCount++
                    }
                }
            }
            
            if (mAsr == null) {
                Log.w(TAG, "科大讯飞ASR未初始化，无法启动语音识别")
                showVoiceStatus("识别器未就绪，请稍后重试")
                return
            }
            
            // 配置ASR参数
            mAsr?.language("zh_cn")  // 中文（根据isChineseMode可以调整，但科大讯飞主要支持中文）
            mAsr?.domain("iat")  // 日常用语
            mAsr?.accent("mandarin")  // 普通话
            mAsr?.vinfo(true)  // 返回子句结果对应的起始和结束的端点帧偏移值
            mAsr?.dwa("wpgs")  // 动态修正
            
            // 启动识别
            voiceRecognitionCount++
            val ret = mAsr?.start(voiceRecognitionCount.toString())
            
            if (ret == 0) {
                isListening = true
                // 长按模式下不更新键盘视图，避免重新创建导致触摸事件丢失
                if (!isLongPressVoiceMode) {
                    updateVoiceButtonInKeyboard()
                }
                showVoiceStatus("正在听...")
                
                // 初始化录音管理器
                if (audioRecorderManager == null) {
                    audioRecorderManager = AudioRecorderManager.getInstance()
                }
                audioRecorderManager?.startRecord()
                audioRecorderManager?.registerCallBack(audioDataCallback)
                
                Log.d(TAG, "科大讯飞语音识别已启动")
            } else {
                Log.e(TAG, "语音识别启动失败，错误码: $ret")
                isListening = false
                if (!isLongPressVoiceMode) {
                    updateVoiceButtonInKeyboard()
                }
                showVoiceStatus("启动失败: $ret")
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动语音识别失败: ${e.message}", e)
            e.printStackTrace()
            isListening = false
            // 长按模式下不更新键盘视图，避免重新创建导致触摸事件丢失
            if (!isLongPressVoiceMode) {
                updateVoiceButtonInKeyboard()
            }
            showVoiceStatus("启动失败: ${e.message}")
        }
    }
    
    /**
     * 尝试初始化语音识别器
     */
    private fun tryInitSpeechRecognizer(): Boolean {
        if (speechRecognizer == null) {
            Log.d(TAG, "语音识别器未初始化，开始初始化...")
            initSpeechRecognizer()
        }
        return speechRecognizer != null
    }
    
    /**
     * 使用 SpeechRecognizer 方式开始识别
     */
    private fun startSpeechRecognitionWithRecognizer() {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                
                // 根据当前输入模式设置语言
                val language = if (isChineseMode) {
                    Locale.SIMPLIFIED_CHINESE.toString()  // 中文
                } else {
                    Locale.ENGLISH.toString()  // 英文
                }
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
                
                // 可选：设置其他参数
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)  // 最多返回5个结果
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)  // 启用部分结果
            }
            
            // 开始识别
            speechRecognizer?.startListening(intent)
            Log.d(TAG, "使用SpeechRecognizer开始识别，语言: ${if (isChineseMode) "中文" else "英文"}")
        } catch (e: Exception) {
            Log.e(TAG, "SpeechRecognizer方式失败: ${e.message}", e)
            // 失败时回退到Intent方式
            startSpeechRecognitionWithIntent()
        }
    }
    
    /**
     * 使用 Intent 方式开始识别
     */
    private fun startSpeechRecognitionWithIntent() {
        try {
            Log.d(TAG, "========== 使用Intent方式启动语音识别 ==========")
            
            // 首先尝试标准的语音识别Intent
            var intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                
                // 根据当前输入模式设置语言
                val language = if (isChineseMode) {
                    "zh-CN"  // 简体中文
                } else {
                    "en-US"  // 英文
                }
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "请说话...")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            }
            
            // 检查是否有应用可以处理这个Intent
            var activities = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            Log.d(TAG, "标准Intent找到 ${activities.size} 个可以处理语音识别的应用")
            
            // 如果标准Intent没有找到应用，尝试特定的语音识别
            if (activities.isEmpty()) {
                Log.d(TAG, "标准Intent不可用，尝试查找语音识别服务...")
                
                // 尝试查找可能的语音识别包
                val possiblePackages = listOf(
                    "com.heytap.speechassist",  //
                    "com.oplus.speech",
                    "com.oppo.speech",
                    "com.coloros.speech",
                    "com.oplus.voicerecognition",
                    "com.oppo.voicerecognition"
                )
                
                Log.d(TAG, "开始检查 ${possiblePackages.size} 个可能的语音识别包...")
                var foundPackage: String? = null
                for (pkg in possiblePackages) {
                    try {
                        packageManager.getPackageInfo(pkg, 0)
                        Log.d(TAG, "✓ 找到可能的语音识别包: $pkg")
                        foundPackage = pkg
                        break
                    } catch (e: PackageManager.NameNotFoundException) {
                        Log.d(TAG, "  - 包不存在: $pkg")
                        // 包不存在，继续查找
                    } catch (e: Exception) {
                        Log.w(TAG, "  - 检查包 $pkg 时出错: ${e.message}")
                    }
                }
                
                if (foundPackage == null) {
                    Log.d(TAG, "未找到已安装的ColorOS语音识别包")
                }
                
                // 尝试直接启动
                if (foundPackage != null) {
                    Log.d(TAG, "尝试启动语音识别: $foundPackage")
                    try {
                        val colorOSIntent = packageManager.getLaunchIntentForPackage(foundPackage)
                        if (colorOSIntent != null) {
                            colorOSIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            startActivity(colorOSIntent)
                            Log.d(TAG, "✓ 已启动语音识别应用: $foundPackage")
                            isListening = false
                            updateVoiceButtonInKeyboard()
                            showVoiceStatus("已启动语音助手")
                            return
                        } else {
                            Log.w(TAG, "无法获取 $foundPackage 的启动Intent")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "启动语音识别失败: ${e.message}", e)
                    }
                }
                
                // 尝试使用其他可能的Intent Action
                val alternativeActions = listOf(
                    "android.speech.action.VOICE_SEARCH_HANDS_FREE",
                    "android.speech.action.WEB_SEARCH"
                )
                
                for (action in alternativeActions) {
                    val altIntent = Intent(action)
                    val altActivities = packageManager.queryIntentActivities(altIntent, PackageManager.MATCH_DEFAULT_ONLY)
                    if (altActivities.isNotEmpty()) {
                        Log.d(TAG, "找到替代Intent: $action (${altActivities.size}个应用)")
                        intent = altIntent
                        activities = altActivities
                        break
                    }
                }
            }
            
            if (activities.isNotEmpty()) {
                activities.forEachIndexed { index, resolveInfo ->
                    Log.d(TAG, "  应用 ${index + 1}: ${resolveInfo.activityInfo.packageName}/${resolveInfo.activityInfo.name}")
                }
            }
            
            if (activities.isEmpty()) {
                Log.w(TAG, "✗ 没有应用可以处理语音识别Intent")
                Log.w(TAG, "系统说明：")
                Log.w(TAG, "  - 没有单独开放系统内置语音识别的原生API")
                Log.w(TAG, "  - 需要通过OPPO开放平台的官方SDK实现")
                Log.w(TAG, "  - 或使用第三方语音输入法（如讯飞、百度输入法）")
                
                // 尝试启动语音助手
                var xiaobuStarted = false
                try {
                    val xiaobuIntent = packageManager.getLaunchIntentForPackage("com.heytap.speechassist")
                    if (xiaobuIntent != null) {
                        xiaobuIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(xiaobuIntent)
                        Log.d(TAG, "✓ 已启动语音助手")
                        showVoiceStatus("已启动小布助手")
                        isListening = false
                        updateVoiceButtonInKeyboard()
                        xiaobuStarted = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "启动小布助手失败: ${e.message}")
                }
                
                if (!xiaobuStarted) {
                    // 显示详细的提示信息
                    showVoiceStatus("ColorOS需使用OPPO开放平台SDK")
                }
                return
            }
            
            // 启动语音识别Activity（会弹出系统语音识别界面）
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            startActivity(intent)
            
            isListening = false  // Intent方式不需要保持listening状态
            updateVoiceButtonInKeyboard()
            Log.d(TAG, "✓ 已启动语音识别Intent（系统界面），语言: ${if (isChineseMode) "中文" else "英文"}")
            Log.d(TAG, "提示：识别结果将在系统界面显示，用户可选择输入")
            Log.d(TAG, "========== Intent方式启动完成 ==========")
        } catch (e: android.content.ActivityNotFoundException) {
            Log.e(TAG, "✗ 启动语音识别Activity失败: 未找到Activity", e)
            isListening = false
            updateVoiceButtonInKeyboard()
            showVoiceStatus("未找到语音识别应用")
        } catch (e: SecurityException) {
            Log.e(TAG, "✗ 启动语音识别Activity失败: 权限不足", e)
            isListening = false
            updateVoiceButtonInKeyboard()
            showVoiceStatus("权限不足")
        } catch (e: Exception) {
            Log.e(TAG, "✗ 启动语音识别Intent失败: ${e.message}", e)
            e.printStackTrace()
            isListening = false
            updateVoiceButtonInKeyboard()
            showVoiceStatus("启动失败: ${e.message}")
        }
    }
    
    /**
     * 停止语音识别
     */
    private fun stopSpeechRecognition() {
        try {
            if (isListening) {
                // 停止录音
                audioRecorderManager?.stopRecord()
                audioRecorderManager = null
                
                // 停止识别
                mAsr?.stop(false)  // false: 等云端最后一包下发后结束
                
                Log.d(TAG, "停止语音识别")
            }
            isListening = false
            // 长按模式下不更新键盘视图，避免重新创建导致触摸事件丢失
            // 在长按结束后，会在ACTION_UP中处理状态更新
            if (!isLongPressVoiceMode) {
                updateVoiceButtonInKeyboard()
            }
            hideVoiceStatus()
        } catch (e: Exception) {
            Log.e(TAG, "停止语音识别失败: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 初始化科大讯飞语音识别器（同步版本，保留用于兼容）
     */
    private fun initXunfeiASR() {
        try {
            Log.d(TAG, "初始化科大讯飞ASR...")
            if (mAsr == null) {
                mAsr = ASR()
                mAsr?.registerCallbacks(asrCallbacks)
            }
            Log.d(TAG, "✓ 科大讯飞ASR初始化成功")
        } catch (e: Exception) {
            Log.e(TAG, "初始化科大讯飞ASR失败: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 方案B优化：异步初始化科大讯飞语音识别器，避免阻塞主线程
     * 添加超时机制，防止初始化时间过长
     */
    private fun initXunfeiASRAsync() {
        serviceScope.launch {
            try {
                Log.d(TAG, "开始异步初始化科大讯飞ASR...")
                
                // 使用 withTimeout 添加超时机制（5秒超时）
                withTimeout(5000L) {
                    if (mAsr == null) {
                        // 在 IO 线程执行耗时操作
                        withContext(Dispatchers.IO) {
                            mAsr = ASR()
                            mAsr?.registerCallbacks(asrCallbacks)
                        }
                        Log.d(TAG, "✓ 科大讯飞ASR异步初始化成功")
                    } else {
                        Log.d(TAG, "科大讯飞ASR已初始化，跳过")
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e(TAG, "初始化科大讯飞ASR超时（5秒）", e)
                // 超时后不阻塞，允许服务继续运行
            } catch (e: Exception) {
                Log.e(TAG, "异步初始化科大讯飞ASR失败: ${e.message}", e)
                e.printStackTrace()
                // 不重新抛出，允许服务继续运行
            }
        }
    }
    
    /**
     * ASR回调
     */
    private val asrCallbacks = object : AsrCallbacks {
        override fun onResult(asrResult: ASR.ASRResult, o: Any?) {
            val status = asrResult.status
            val result = asrResult.bestMatchText
            
            Log.d(TAG, "语音识别结果: status=$status, result=$result")
            
            // 在主线程更新UI
            mainHandler.post {
                when (status) {
                    0 -> {
                        // 识别的第一块结果（中间结果）
                        if (result.isNotEmpty()) {
                            showVoiceStatus("识别中: $result")
                        }
                    }
                    1 -> {
                        // 识别中间结果
                        if (result.isNotEmpty()) {
                            showVoiceStatus("识别中: $result")
                        }
                    }
                    2 -> {
                        // 识别最后一块结果（最终结果）
                        if (result.isNotEmpty()) {
                            // 输入识别结果
                            commitText(result)
                            hideVoiceStatus()
                        } else {
                            showVoiceStatus("未识别到内容")
                        }
                        
                        // 如果处于长按语音模式，继续识别
                        if (isLongPressVoiceMode && isListening) {
                            Log.d(TAG, "长按模式：继续识别")
                            // 重新启动识别
                            try {
                                voiceRecognitionCount++
                                mAsr?.start(voiceRecognitionCount.toString())
                                if (audioRecorderManager == null) {
                                    audioRecorderManager = AudioRecorderManager.getInstance()
                                }
                                audioRecorderManager?.startRecord()
                                audioRecorderManager?.registerCallBack(audioDataCallback)
                            } catch (e: Exception) {
                                Log.e(TAG, "长按模式继续识别失败: ${e.message}", e)
                                isListening = false
                                if (!isLongPressVoiceMode) {
                                    updateVoiceButtonInKeyboard()
                                }
                            }
                        } else {
                            // 非长按模式，正常结束
                            isListening = false
                            if (!isLongPressVoiceMode) {
                                updateVoiceButtonInKeyboard()
                            }
                        }
                    }
                }
            }
        }
        
        override fun onError(asrError: ASR.ASRError, o: Any?) {
            val code = asrError.code
            val msg = asrError.errMsg
            
            Log.e(TAG, "语音识别错误: code=$code, msg=$msg")
            
            // 在主线程显示错误提示
            mainHandler.post {
                isListening = false
                if (!isLongPressVoiceMode) {
                    updateVoiceButtonInKeyboard()
                }
                showVoiceStatus("识别失败: $msg")
            }
        }
        
        override fun onBeginOfSpeech() {
            Log.d(TAG, "开始说话")
            mainHandler.post {
                showVoiceStatus("正在识别...")
            }
        }
        
        override fun onEndOfSpeech() {
            Log.d(TAG, "结束说话")
            mainHandler.post {
                showVoiceStatus("处理中...")
            }
        }
    }
    
    /**
     * 音频数据回调
     */
    private val audioDataCallback = object : AudioRecorderManager.AudioDataCallback {
        override fun onAudioData(data: ByteArray, size: Int) {
            // 音频数据会通过ASR自动处理，这里不需要额外操作
        }
        
        override fun onAudioVolume(db: Double, volume: Int) {
            // 可以在这里显示音量指示，但键盘中暂时不需要
        }
    }
    
    /**
     * 检查录音权限
     */
    private fun checkAudioPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val hasPermission = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                Log.w(TAG, "缺少录音权限")
            }
            hasPermission
        } else {
            true  // Android 6.0以下默认有权限
        }
    }
    
    /**
     * 更新键盘中的语音按钮状态
     */
    private fun updateVoiceButtonInKeyboard() {
        try {
            // 由于键盘视图是动态创建的，我们需要在重新创建时更新状态
            // 使用setInputView方法安全地更新键盘视图
            keyboardView = createKeyboardView()
            setInputView(keyboardView)
        } catch (e: Exception) {
            Log.e(TAG, "更新语音按钮状态异常: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 更新语音按钮状态（用于创建按钮时）
     */
    private fun updateVoiceButtonState(button: Button, isListening: Boolean) {
        val drawable = GradientDrawable().apply {
            if (isListening) {
                setColor(Color.parseColor("#FF5722"))  // 红色表示正在识别
            } else {
                setColor(Color.parseColor("#BDBDBD"))  // 灰色表示未识别
            }
            cornerRadius = 4f
            setStroke(1, Color.parseColor("#9E9E9E"))
        }
        button.background = drawable
    }
    
    /**
     * 显示语音识别状态（在候选词区域）
     */
    private fun showVoiceStatus(status: String) {
        candidatesContainer?.let { container ->
            container.removeAllViews()
            
            if (status.contains("权限")) {
                // 如果是权限相关，显示可点击的按钮
                val statusButton = Button(this).apply {
                    text = "🎤 $status (点击授权)"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setPadding(16, 12, 16, 12)
                    
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(8, 0, 8, 0)
                    }
                    
                    // 设置背景
                    val drawable = GradientDrawable().apply {
                        setColor(Color.parseColor("#FF5722"))
                        cornerRadius = 6f
                        setStroke(2, Color.parseColor("#D84315"))
                    }
                    background = drawable
                    
                    setOnClickListener {
                        openPermissionSettings()
                    }
                }
                container.addView(statusButton)
            } else if (status.contains("OPPO") || status.contains("SDK")) {
                // 相关提示
                val statusText = android.widget.TextView(this).apply {
                    text = "🎤 $status\n提示：ColorOS需集成OPPO开放平台语音SDK\n或使用第三方语音输入法"
                    textSize = 12f
                    setTextColor(Color.parseColor("#FF5722"))
                    setPadding(12, 8, 12, 8)
                    gravity = Gravity.CENTER_VERTICAL
                }
                container.addView(statusText)
            } else if (status.contains("不可用") || status.contains("未安装")) {
                // 如果是服务不可用，显示提示信息
                val statusText = android.widget.TextView(this).apply {
                    text = "🎤 $status\n提示：需要安装语音识别服务（如Google语音服务）"
                    textSize = 14f
                    setTextColor(Color.parseColor("#FF5722"))
                    setPadding(12, 8, 12, 8)
                    gravity = Gravity.CENTER_VERTICAL
                }
                container.addView(statusText)
            } else {
                // 普通状态显示
                val statusText = android.widget.TextView(this).apply {
                    text = "🎤 $status"
                    textSize = 16f
                    setTextColor(Color.parseColor("#FF5722"))
                    setPadding(12, 8, 12, 8)
                    gravity = Gravity.CENTER_VERTICAL
                }
                container.addView(statusText)
            }
            
            container.visibility = View.VISIBLE
        }
    }
    
    /**
     * 打开应用权限设置页面
     */
    private fun openPermissionSettings() {
        try {
            // 显示对话框让用户确认
            val dialog = android.app.AlertDialog.Builder(this).apply {
                setTitle("需要麦克风权限")
                setMessage("语音输入需要麦克风权限。\n\n点击\"去设置\"跳转到权限设置页面，请开启\"麦克风\"权限。")
                setPositiveButton("去设置") { _, _ ->
                    try {
                        Log.d(TAG, "打开应用权限设置页面")
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(intent)
                        Log.d(TAG, "已打开应用权限设置页面")
                    } catch (e: Exception) {
                        Log.e(TAG, "打开权限设置页面失败: ${e.message}", e)
                        e.printStackTrace()
                        // 如果打开失败，尝试打开应用设置
                        try {
                            val intent = Intent(Settings.ACTION_APPLICATION_SETTINGS).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                            }
                            startActivity(intent)
                        } catch (e2: Exception) {
                            Log.e(TAG, "打开应用设置也失败: ${e2.message}", e2)
                        }
                    }
                }
                setNegativeButton("取消", null)
            }.create()
            
            // 设置对话框窗口类型，确保在输入法服务中能显示
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                @Suppress("DEPRECATION")
                dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT)
            }
            
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "显示权限对话框失败: ${e.message}", e)
            e.printStackTrace()
            // 如果对话框显示失败，直接跳转
            try {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "直接跳转也失败: ${e2.message}", e2)
            }
        }
    }
    
    /**
     * 隐藏语音识别状态
     */
    private fun hideVoiceStatus() {
        // 恢复候选词显示
        updateCandidatesDisplay()
    }
    
    /**
     * 跳转到应用（将应用带到前台，如果未运行则启动）
     * @return 是否成功
     */
    private fun launchApp(): Boolean {
        return try {
            Log.d(TAG, "尝试跳转到应用")
            val appPackageName = packageName  // 获取当前应用的包名
            val intent = packageManager.getLaunchIntentForPackage(appPackageName)
            if (intent != null) {
                // 使用 REORDER_TO_FRONT 标志，将应用带到前台而不重新启动
                // 如果应用未运行，FLAG_ACTIVITY_NEW_TASK 会启动它
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Log.d(TAG, "应用已跳转: $appPackageName")
                // 跳转后收起键盘
                requestHideSelf(0)
                true
            } else {
                Log.w(TAG, "无法获取启动Intent: $appPackageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "跳转到应用失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 处理问字按钮点击
     * 1. 截图
     * 2. 显示弹窗询问用户
     * 3. 用户确认后跳转到TopoClaw并发送带图任务
     */
    private fun handleQuestionButtonClick() {
        Log.d(TAG, "handleQuestionButtonClick: 开始处理问字按钮点击")
        
        // 在协程中执行异步操作
        serviceScope.launch {
            try {
                // 步骤1: 截图
                Log.d(TAG, "开始截图...")
                val screenshot = captureScreenshotForQuestion()
                
                if (screenshot == null) {
                    Log.w(TAG, "截图失败")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@SimpleInputMethodService, "截图失败，请重试", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                // 步骤2: 转换为Base64
                val screenshotBase64 = ScreenshotHelper.bitmapToBase64(screenshot, this@SimpleInputMethodService)
                screenshot.recycle() // 释放内存
                
                Log.d(TAG, "截图成功，Base64长度: ${screenshotBase64.length}")
                
                // 步骤3: 显示弹窗
                withContext(Dispatchers.Main) {
                    showQuestionDialog(screenshotBase64)
                }
            } catch (e: Exception) {
                Log.e(TAG, "处理问字按钮点击失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SimpleInputMethodService, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * 截图（用于问字按钮）
     */
    private suspend fun captureScreenshotForQuestion(): Bitmap? = withContext(Dispatchers.IO) {
        return@withContext try {
            val chatService = ChatScreenshotService.getInstance()
            if (chatService == null) {
                Log.w(TAG, "ChatScreenshotService未就绪")
                return@withContext null
            }
            
            if (!chatService.isReady()) {
                Log.w(TAG, "ChatScreenshotService未就绪（isReady=false）")
                return@withContext null
            }
            
            val bitmap = chatService.captureScreenshot()
            if (bitmap == null) {
                Log.w(TAG, "截图返回null")
            }
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "截图异常: ${e.message}", e)
            null
        }
    }
    
    /**
     * 显示问字弹窗
     */
    private fun showQuestionDialog(screenshotBase64: String) {
        try {
            Log.d(TAG, "显示问字弹窗")
            
            // 使用CallUserInputOverlayManager显示弹窗
            CallUserInputOverlayManager.show(
                context = this,
                questionText = "我有什么可以帮你的吗",
                onSend = { userInput ->
                    Log.d(TAG, "用户确认，准备跳转并发送任务")
                    // 用户确认后，发送Broadcast通知MainActivity
                    sendQuestionButtonBroadcast(screenshotBase64, userInput)
                    // 收起键盘
                    requestHideSelf(0)
                },
                onCancel = {
                    Log.d(TAG, "用户取消")
                    // 收起键盘
                    requestHideSelf(0)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "显示问字弹窗失败: ${e.message}", e)
            Toast.makeText(this, "显示弹窗失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 发送Broadcast通知MainActivity
     */
    private fun sendQuestionButtonBroadcast(screenshotBase64: String, userQuery: String) {
        try {
            Log.d(TAG, "发送问字按钮Broadcast，query长度: ${userQuery.length}, screenshot长度: ${screenshotBase64.length}")
            val intent = Intent(ACTION_QUESTION_BUTTON_CLICKED).apply {
                putExtra(EXTRA_SCREENSHOT_BASE64, screenshotBase64)
                putExtra(EXTRA_TEXT, userQuery)  // 复用EXTRA_TEXT传递用户输入
                setPackage(packageName)  // 设置包名，确保Android 8.0+能正确接收
            }
            sendBroadcast(intent)
            Log.d(TAG, "Broadcast已发送，包名: $packageName")
        } catch (e: Exception) {
            Log.e(TAG, "发送Broadcast失败: ${e.message}", e)
            e.printStackTrace()
        }
    }
}

