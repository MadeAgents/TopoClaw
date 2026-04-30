package com.cloudcontrol.demo

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.widget.TextView

/**
 * call_user 输入框悬浮窗管理器
 * 管理左侧悬浮窗输入框的显示和隐藏
 * 使用和悬浮球点击时相同的AlertDialog样式
 */
object CallUserInputOverlayManager {
    private const val TAG = "CallUserInputOverlay"
    
    private var dialog: android.app.AlertDialog? = null
    private var isShowing = false
    private var context: Context? = null
    private var voiceInputHelper: VoiceInputHelper? = null
    
    
    /**
     * 显示输入框弹窗（使用和悬浮球点击时相同的样式）
     * @param context 上下文
     * @param questionText 问题文本（将显示在标题位置）
     * @param onSend 发送回调，参数为用户输入的内容
     * @param onCancel 取消回调
     * @param onManualTakeover 手动接管回调（可选，仅在call_user时使用）
     */
    fun show(
        context: Context,
        questionText: String,
        onSend: (String) -> Unit,
        onCancel: () -> Unit,
        onManualTakeover: (() -> Unit)? = null
    ) {
        if (isShowing) {
            Log.d(TAG, "输入框已显示，先隐藏再显示")
            hide()
        }
        
        this.context = context
        
        try {
            val dialogView = android.view.LayoutInflater.from(context).inflate(
                R.layout.dialog_companion_input, null
            )
            
            // 设置标题文本（动态设置call_user的问题文本）
            val titleTextView = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
            titleTextView?.text = questionText
            
            // 设置输入框提示文本（call_user时改为"请输入你的回答"）
            val tilInput = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilInput)
            tilInput?.hint = context.getString(R.string.companion_input_answer_hint)
            
            val etInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etCompanionInput)
            val btnCancel = dialogView.findViewById<android.widget.Button>(R.id.btnCancel)
            val btnSend = dialogView.findViewById<android.widget.Button>(R.id.btnSend)
            val btnManualTakeover = dialogView.findViewById<android.widget.Button>(R.id.btnManualTakeover)
            
            // 隐藏下拉箭头（CallUserInputOverlayManager不使用此功能）
            val btnQueryDropdown = dialogView.findViewById<android.view.View>(R.id.btnQueryDropdown)
            btnQueryDropdown?.visibility = android.view.View.GONE
            
            // 初始化语音输入功能
            val llVoicePanel = dialogView.findViewById<android.widget.LinearLayout>(R.id.llVoicePanel)
            val btnVoiceInput = dialogView.findViewById<android.widget.ImageButton>(R.id.btnVoiceInput)
            val btnVoiceRecord = dialogView.findViewById<android.widget.ImageButton>(R.id.btnVoiceRecord)
            val tvVoiceHint = dialogView.findViewById<TextView>(R.id.tvVoiceHint)
            val ivRecordingRing = dialogView.findViewById<RecordingRingView>(R.id.ivRecordingRing)
            
            if (llVoicePanel != null && btnVoiceInput != null && btnVoiceRecord != null && 
                tvVoiceHint != null && ivRecordingRing != null && etInput != null) {
                voiceInputHelper = VoiceInputHelper(
                    context,
                    etInput,
                    llVoicePanel,
                    btnVoiceInput,
                    btnVoiceRecord,
                    tvVoiceHint,
                    ivRecordingRing
                )
                
                // 确保语音输入图标在最右边（下拉箭头隐藏时，包含2dp向右偏移）
                val params = btnVoiceInput.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                params?.let {
                    it.marginEnd = (10 * context.resources.displayMetrics.density).toInt()
                    // 确保向下偏移3dp
                    it.topMargin = (3 * context.resources.displayMetrics.density).toInt()
                    btnVoiceInput.layoutParams = it
                }
            }
            
            // 设置手动接管按钮的显示和点击事件
            if (onManualTakeover != null) {
                btnManualTakeover?.visibility = android.view.View.VISIBLE
                btnManualTakeover?.setOnClickListener {
                    Log.d(TAG, "用户点击手动接管")
                    // 先隐藏键盘
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.hideSoftInputFromWindow(etInput?.windowToken, 0)
                    // 关闭弹窗
                    dialog?.dismiss()
                    // 调用手动接管回调
                    onManualTakeover()
                }
            } else {
                btnManualTakeover?.visibility = android.view.View.GONE
            }
            
            // 创建对话框
            dialog = android.app.AlertDialog.Builder(context)
                .setView(dialogView)
                .create()
            
            // 设置对话框窗口类型为悬浮窗
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dialog?.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                @Suppress("DEPRECATION")
                dialog?.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
            }
            
            // 美化对话框窗口样式
            dialog?.window?.let { window ->
                // 设置背景透明，让圆角背景显示出来
                window.setBackgroundDrawableResource(android.R.color.transparent)
                // 移除默认的窗口装饰
                window.decorView.setBackgroundResource(android.R.color.transparent)
                // 设置窗口动画（可选，让弹出更流畅）
                window.setWindowAnimations(android.R.style.Animation_Dialog)
                // 设置窗口边距，让对话框不贴边
                val displayMetrics = context.resources.displayMetrics
                val margin = (16 * displayMetrics.density).toInt()
                window.decorView.setPadding(margin, margin, margin, margin)
            }
            
            // 取消按钮
            btnCancel?.setOnClickListener {
                Log.d(TAG, "用户取消输入")
                dialog?.dismiss()
                onCancel()
            }
            
            // 发送按钮
            btnSend?.setOnClickListener {
                val userInput = etInput?.text?.toString()?.trim() ?: ""
                
                // 先隐藏键盘
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(etInput?.windowToken, 0)
                
                // 关闭弹窗
                dialog?.dismiss()
                
                if (userInput.isNotEmpty()) {
                    Log.d(TAG, "用户输入: $userInput")
                    onSend(userInput)
                }
            }
            
            // 输入框回车键发送
            etInput?.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
                    val userInput = etInput.text?.toString()?.trim() ?: ""
                    
                    // 先隐藏键盘
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                    imm?.hideSoftInputFromWindow(etInput.windowToken, 0)
                    
                    // 关闭弹窗
                    dialog?.dismiss()
                    
                    if (userInput.isNotEmpty()) {
                        Log.d(TAG, "用户输入: $userInput")
                        onSend(userInput)
                    }
                    true
                } else {
                    false
                }
            }
            
            // 显示对话框
            dialog?.show()
            
            // 自动聚焦输入框并弹出键盘
            etInput?.requestFocus()
            etInput?.post {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(etInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            
            isShowing = true
            Log.d(TAG, "输入框弹窗已显示，问题: $questionText")
        } catch (e: Exception) {
            Log.e(TAG, "显示输入框失败: ${e.message}", e)
            dialog = null
        }
    }
    
    
    /**
     * 隐藏输入框弹窗
     */
    fun hide() {
        if (!isShowing) {
            return
        }
        
        try {
            voiceInputHelper?.cleanup()
            voiceInputHelper = null
            dialog?.dismiss()
            dialog = null
            isShowing = false
            Log.d(TAG, "输入框弹窗已隐藏")
        } catch (e: Exception) {
            Log.e(TAG, "隐藏输入框失败: ${e.message}", e)
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        voiceInputHelper?.cleanup()
        voiceInputHelper = null
        hide()
        context = null
    }
}

