package com.cloudcontrol.demo

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * call_user 输入框悬浮窗视图
 * 显示问题文本、输入框和发送按钮
 */
class CallUserInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var questionText: TextView? = null
    private var inputEditText: EditText? = null
    private var sendButton: TextView? = null
    private var cancelButton: TextView? = null
    
    var onSendClickListener: ((String) -> Unit)? = null
    var onCancelClickListener: (() -> Unit)? = null
    
    private val backgroundColor = 0xFFF5F5F5.toInt() // 浅灰色背景
    private val questionTextColor = 0xFF333333.toInt() // 深灰色文字
    private val buttonTextColor = 0xFFFFFFFF.toInt() // 白色按钮文字
    private val sendButtonColor = 0xFF2196F3.toInt() // 蓝色发送按钮
    private val cancelButtonColor = 0xFF9E9E9E.toInt() // 灰色取消按钮
    
    init {
        orientation = VERTICAL
        setPadding(
            (16 * resources.displayMetrics.density).toInt(),
            (16 * resources.displayMetrics.density).toInt(),
            (16 * resources.displayMetrics.density).toInt(),
            (16 * resources.displayMetrics.density).toInt()
        )
        
        // 设置圆角背景
        val cornerRadiusValue = 12f * resources.displayMetrics.density
        val backgroundDrawable = GradientDrawable().apply {
            setColor(backgroundColor)
            cornerRadius = cornerRadiusValue
        }
        background = backgroundDrawable
        
        setupViews()
    }
    
    /**
     * 设置视图
     */
    private fun setupViews() {
        // 问题文本
        questionText = TextView(context).apply {
            textSize = 15f
            setTextColor(questionTextColor)
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
            maxLines = 3
            isSingleLine = false
        }
        addView(questionText, LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        
        // 输入框
        inputEditText = EditText(context).apply {
            hint = "请输入..."
            textSize = 14f
            setPadding(
                (12 * resources.displayMetrics.density).toInt(),
                (10 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(),
                (10 * resources.displayMetrics.density).toInt()
            )
            background = ContextCompat.getDrawable(context, android.R.drawable.edit_text)
            minHeight = (40 * resources.displayMetrics.density).toInt()
        }
        addView(inputEditText, LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        
        // 按钮容器
        val buttonContainer = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        
        // 取消按钮
        cancelButton = TextView(context).apply {
            text = "取消"
            textSize = 14f
            setTextColor(buttonTextColor)
            setPadding(
                (20 * resources.displayMetrics.density).toInt(),
                (10 * resources.displayMetrics.density).toInt(),
                (20 * resources.displayMetrics.density).toInt(),
                (10 * resources.displayMetrics.density).toInt()
            )
            setBackgroundColor(cancelButtonColor)
            setOnClickListener {
                onCancelClickListener?.invoke()
            }
        }
        buttonContainer.addView(cancelButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        
        // 发送按钮
        sendButton = TextView(context).apply {
            text = "发送"
            textSize = 14f
            setTextColor(buttonTextColor)
            setPadding(
                (20 * resources.displayMetrics.density).toInt(),
                (10 * resources.displayMetrics.density).toInt(),
                (20 * resources.displayMetrics.density).toInt(),
                (10 * resources.displayMetrics.density).toInt()
            )
            setBackgroundColor(sendButtonColor)
            setOnClickListener {
                val input = inputEditText?.text?.toString()?.trim() ?: ""
                if (input.isNotEmpty()) {
                    onSendClickListener?.invoke(input)
                }
            }
        }
        buttonContainer.addView(sendButton, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = (8 * resources.displayMetrics.density).toInt()
        })
        
        addView(buttonContainer, LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }
    
    /**
     * 设置问题文本
     */
    fun setQuestionText(text: String) {
        questionText?.text = text
    }
    
    /**
     * 获取输入内容
     */
    fun getInputText(): String {
        return inputEditText?.text?.toString()?.trim() ?: ""
    }
    
    /**
     * 清空输入框
     */
    fun clearInput() {
        inputEditText?.setText("")
    }
    
    /**
     * 请求输入框焦点并显示键盘
     */
    fun requestInputFocus() {
        inputEditText?.requestFocus()
        inputEditText?.post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(inputEditText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }
}

