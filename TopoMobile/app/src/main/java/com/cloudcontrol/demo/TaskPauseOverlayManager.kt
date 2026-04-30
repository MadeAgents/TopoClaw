package com.cloudcontrol.demo

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 任务暂停提示弹窗管理器
 * 显示暂停提示弹窗，2秒后自动关闭，或点击"知道了"按钮关闭
 * 弹窗背景高透明度，圆润设计
 */
object TaskPauseOverlayManager {
    private const val TAG = "TaskPauseOverlay"
    private const val AUTO_HIDE_DELAY_MS = 2000L // 2秒自动关闭
    
    private var dialog: android.app.AlertDialog? = null
    private var isShowing = false
    private var context: Context? = null
    private var countdownJob: Job? = null
    private var countdownTextView: TextView? = null
    private var remainingSeconds = 2
    
    /**
     * 检查弹窗是否正在显示
     */
    fun isShowing(): Boolean {
        return isShowing
    }
    
    /**
     * 显示暂停提示弹窗
     * @param context 上下文
     */
    fun show(context: Context) {
        if (isShowing) {
            Log.d(TAG, "暂停提示弹窗已显示，先隐藏再显示")
            hide()
        }
        
        this.context = context
        remainingSeconds = 2
        
        try {
            val dialogView = android.view.LayoutInflater.from(context).inflate(
                R.layout.dialog_task_pause, null
            )
            
            // 设置消息内容
            val messageTextView = dialogView.findViewById<TextView>(R.id.tvDialogMessage)
            messageTextView?.text = "任务已暂停，再次点击悬浮球可继续任务"
            
            // 倒计时文本
            countdownTextView = dialogView.findViewById<TextView>(R.id.tvCountdown)
            countdownTextView?.text = "2秒后自动关闭"
            
            val btnKnow = dialogView.findViewById<android.widget.Button>(R.id.btnKnow)
            
            // 创建对话框
            dialog = android.app.AlertDialog.Builder(context)
                .setView(dialogView)
                .setCancelable(true)
                .create()
            
            // 设置对话框窗口类型为悬浮窗（可以在应用外显示）
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
                // 移除遮罩层（设置dimAmount为0，这样弹窗外区域不会变灰）
                val layoutParams = window.attributes
                layoutParams.dimAmount = 0.0f
                window.attributes = layoutParams
                // 设置窗口标志，允许点击弹窗外的区域（不阻塞其他区域）
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                )
                // 设置窗口动画（可选，让弹出更流畅）
                window.setWindowAnimations(android.R.style.Animation_Dialog)
                // 设置窗口边距，让对话框不贴边
                val displayMetrics = context.resources.displayMetrics
                val margin = (16 * displayMetrics.density).toInt()
                window.decorView.setPadding(margin, margin, margin, margin)
            }
            
            // "知道了"按钮
            btnKnow?.setOnClickListener {
                Log.d(TAG, "用户点击知道了按钮")
                hide()
            }
            
            // 设置取消监听（用户点击外部区域或返回键）
            dialog?.setOnCancelListener {
                Log.d(TAG, "用户取消弹窗")
                hide()
            }
            
            // 显示对话框
            dialog?.show()
            
            isShowing = true
            Log.d(TAG, "暂停提示弹窗已显示")
            
            // 启动倒计时
            startCountdown()
        } catch (e: Exception) {
            Log.e(TAG, "显示暂停提示弹窗失败: ${e.message}", e)
            dialog = null
            isShowing = false
        }
    }
    
    /**
     * 启动倒计时
     */
    private fun startCountdown() {
        // 取消之前的倒计时
        stopCountdown()
        
        remainingSeconds = 2
        countdownTextView?.text = "2秒后自动关闭"
        
        // 使用协程进行倒计时
        countdownJob = CoroutineScope(Dispatchers.Main).launch {
            for (i in 2 downTo 1) {
                delay(1000)
                if (!isShowing) {
                    return@launch
                }
                remainingSeconds = i
                countdownTextView?.text = "${i}秒后自动关闭"
            }
            
            // 倒计时结束，自动关闭
            delay(1000)
            if (isShowing) {
                Log.d(TAG, "倒计时结束，自动关闭弹窗")
                hide()
            }
        }
    }
    
    /**
     * 停止倒计时
     */
    private fun stopCountdown() {
        countdownJob?.cancel()
        countdownJob = null
    }
    
    /**
     * 隐藏暂停提示弹窗
     */
    fun hide() {
        if (!isShowing) {
            return
        }
        
        // 停止倒计时
        stopCountdown()
        
        try {
            dialog?.dismiss()
            dialog = null
            isShowing = false
            countdownTextView = null
            Log.d(TAG, "暂停提示弹窗已隐藏")
        } catch (e: Exception) {
            Log.e(TAG, "隐藏暂停提示弹窗失败: ${e.message}", e)
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        hide()
        context = null
    }
}

