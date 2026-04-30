package com.cloudcontrol.demo

import android.content.Context
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.widget.TextView

/**
 * 应用安装确认弹窗管理器
 * 用于在应用外显示安装确认弹窗（悬浮窗格式）
 * 参考CallUserInputOverlayManager的实现
 */
object AppInstallConfirmOverlayManager {
    private const val TAG = "AppInstallConfirm"
    
    private var dialog: android.app.AlertDialog? = null
    private var isShowing = false
    private var context: Context? = null
    
    /**
     * 检查弹窗是否正在显示（供外部调用，用于阻止请求发送）
     */
    fun isDialogShowing(): Boolean {
        return isShowing
    }
    
    /**
     * 显示应用安装确认弹窗
     * @param context 上下文
     * @param appName 应用名称
     * @param onConfirm 确认回调（用户点击"允许"）
     * @param onCancel 取消回调（用户点击"不允许"或关闭弹窗）
     */
    fun show(
        context: Context,
        appName: String,
        onConfirm: () -> Unit,
        onCancel: () -> Unit
    ) {
        if (isShowing) {
            Log.d(TAG, "确认弹窗已显示，先隐藏再显示")
            hide()
        }
        
        this.context = context
        
        try {
            val dialogView = android.view.LayoutInflater.from(context).inflate(
                R.layout.dialog_app_install_confirm, null
            )
            
            // 设置标题
            val titleTextView = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
            titleTextView?.text = "是否允许下载应用"
            
            // 设置消息内容
            val messageTextView = dialogView.findViewById<TextView>(R.id.tvDialogMessage)
            messageTextView?.text = "系统将自动在软件商店中搜索并下载应用\"$appName\"，是否允许？"
            
            val btnCancel = dialogView.findViewById<android.widget.Button>(R.id.btnCancel)
            val btnConfirm = dialogView.findViewById<android.widget.Button>(R.id.btnConfirm)
            
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
                // 设置窗口动画（可选，让弹出更流畅）
                window.setWindowAnimations(android.R.style.Animation_Dialog)
                // 设置窗口边距，让对话框不贴边
                val displayMetrics = context.resources.displayMetrics
                val margin = (16 * displayMetrics.density).toInt()
                window.decorView.setPadding(margin, margin, margin, margin)
            }
            
            // 不允许按钮
            btnCancel?.setOnClickListener {
                Log.d(TAG, "用户选择不允许下载")
                // 先关闭弹窗，更新isShowing状态
                isShowing = false
                dialog?.dismiss()
                dialog = null
                // 然后调用回调
                onCancel()
            }
            
            // 允许按钮
            btnConfirm?.setOnClickListener {
                Log.d(TAG, "用户选择允许下载")
                // 先关闭弹窗，更新isShowing状态
                isShowing = false
                dialog?.dismiss()
                dialog = null
                // 然后调用回调
                onConfirm()
            }
            
            // 设置取消监听（用户点击外部区域或返回键）
            dialog?.setOnCancelListener {
                Log.d(TAG, "用户取消弹窗")
                onCancel()
            }
            
            // 显示对话框
            dialog?.show()
            
            isShowing = true
            Log.d(TAG, "应用安装确认弹窗已显示，应用名: $appName")
        } catch (e: Exception) {
            Log.e(TAG, "显示确认弹窗失败: ${e.message}", e)
            dialog = null
            isShowing = false
        }
    }
    
    /**
     * 隐藏确认弹窗
     */
    fun hide() {
        if (!isShowing) {
            return
        }
        
        try {
            dialog?.dismiss()
            dialog = null
            isShowing = false
            Log.d(TAG, "应用安装确认弹窗已隐藏")
        } catch (e: Exception) {
            Log.e(TAG, "隐藏确认弹窗失败: ${e.message}", e)
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

