package com.cloudcontrol.demo

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

/**
 * 版本更新提示弹窗管理器
 * 显示版本更新提示弹窗，支持立即更新和稍后提醒
 * 弹窗可以在应用外显示
 */
object VersionUpdateOverlayManager {
    private const val TAG = "VersionUpdateOverlay"
    
    private var dialog: android.app.AlertDialog? = null
    private var isShowing = false
    private var context: Context? = null
    private var updateUrl: String? = null
    
    /**
     * 检查弹窗是否正在显示
     */
    fun isShowing(): Boolean {
        return isShowing
    }
    
    /**
     * 显示版本更新提示弹窗
     * @param context 上下文
     * @param currentVersion 当前版本号
     * @param latestVersion 最新版本号
     * @param updateMessage 更新消息
     * @param updateUrl 更新链接
     * @param forceUpdate 是否强制更新
     * @param onUpdate 点击立即更新时的回调
     * @param onSkip 点击暂不更新时的回调
     */
    fun show(
        context: Context,
        currentVersion: String,
        latestVersion: String,
        updateMessage: String,
        updateUrl: String?,
        forceUpdate: Boolean = false,
        onUpdate: (() -> Unit)? = null,
        onSkip: (() -> Unit)? = null
    ) {
        if (isShowing) {
            Log.d(TAG, "版本更新弹窗已显示，先隐藏再显示")
            hide()
        }
        
        this.context = context
        this.updateUrl = updateUrl
        
        try {
            val dialogView = android.view.LayoutInflater.from(context).inflate(
                R.layout.dialog_version_update, null
            )
            
            // 设置标题
            val titleTextView = dialogView.findViewById<TextView>(R.id.tvDialogTitle)
            titleTextView?.text = if (forceUpdate) "检测到版本更新" else "检测到版本更新"
            
            // 设置版本信息
            val currentVersionTextView = dialogView.findViewById<TextView>(R.id.tvCurrentVersion)
            currentVersionTextView?.text = "当前版本：$currentVersion"
            
            val latestVersionTextView = dialogView.findViewById<TextView>(R.id.tvLatestVersion)
            latestVersionTextView?.text = "最新版本：$latestVersion"
            
            // 设置更新消息（将每行前面加上点号变成无序列表）
            val updateMessageTextView = dialogView.findViewById<TextView>(R.id.tvUpdateMessage)
            val formattedMessage = if (updateMessage.isNotEmpty()) {
                // 按换行符分割，每行前面加上"• "
                updateMessage.lines().joinToString("\n") { line ->
                    if (line.trim().isNotEmpty()) {
                        "• ${line.trim()}"
                    } else {
                        ""
                    }
                }.trim()
            } else {
                updateMessage
            }
            updateMessageTextView?.text = formattedMessage
            
            // 按钮
            val btnSkip = dialogView.findViewById<Button>(R.id.btnSkip)
            val btnUpdate = dialogView.findViewById<Button>(R.id.btnUpdate)
            
            // 调试日志
            Log.d(TAG, "按钮查找结果: btnSkip=${btnSkip != null}, btnUpdate=${btnUpdate != null}, forceUpdate=$forceUpdate")
            
            // 确保"暂不更新"按钮始终可见
            btnSkip?.visibility = android.view.View.VISIBLE
            btnSkip?.isEnabled = true
            Log.d(TAG, "显示暂不更新按钮")
            
            // "暂不更新"按钮 - 点击后关闭弹窗
            btnSkip?.setOnClickListener {
                Log.d(TAG, "用户点击暂不更新按钮")
                onSkip?.invoke()
                hide()
            }
            
            // 调试：检查按钮的可见性
            Log.d(TAG, "按钮可见性: btnSkip.visibility=${btnSkip?.visibility}, btnSkip.isShown=${btnSkip?.isShown}")
            
            // 创建对话框
            dialog = android.app.AlertDialog.Builder(context)
                .setView(dialogView)
                .setCancelable(!forceUpdate) // 强制更新时不可取消
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
            
            // "立即更新"按钮
            btnUpdate?.setOnClickListener {
                Log.d(TAG, "用户点击立即更新按钮")
                onUpdate?.invoke()
                
                // 打开更新链接
                updateUrl?.let { url ->
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        Log.d(TAG, "已打开更新链接: $url")
                    } catch (e: Exception) {
                        Log.e(TAG, "打开更新链接失败: ${e.message}", e)
                        android.widget.Toast.makeText(
                            context,
                            "无法打开更新链接",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                
                hide()
            }
            
            // 设置取消监听（用户点击外部区域或返回键）
            dialog?.setOnCancelListener {
                Log.d(TAG, "用户取消弹窗")
                if (!forceUpdate) {
                    onSkip?.invoke()
                    hide()
                }
            }
            
            // 显示对话框
            dialog?.show()
            
            isShowing = true
            Log.d(TAG, "版本更新弹窗已显示: currentVersion=$currentVersion, latestVersion=$latestVersion")
        } catch (e: Exception) {
            Log.e(TAG, "显示版本更新弹窗失败: ${e.message}", e)
            dialog = null
            isShowing = false
        }
    }
    
    /**
     * 隐藏版本更新弹窗
     */
    fun hide() {
        if (!isShowing) {
            return
        }
        
        try {
            dialog?.dismiss()
            dialog = null
            isShowing = false
            updateUrl = null
            Log.d(TAG, "版本更新弹窗已隐藏")
        } catch (e: Exception) {
            Log.e(TAG, "隐藏版本更新弹窗失败: ${e.message}", e)
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

