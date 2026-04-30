package com.cloudcontrol.demo

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 远程控制权限确认悬浮窗管理器
 * 当应用不在前台时，显示权限确认弹窗
 */
object RemoteControlPermissionOverlayManager {
    private const val TAG = "RemoteControlPermissionOverlay"
    
    private var dialog: android.app.AlertDialog? = null
    private var isShowing = false
    private var context: Context? = null
    
    /**
     * 检查弹窗是否正在显示
     */
    fun isShowing(): Boolean {
        return isShowing
    }
    
    /**
     * 显示远程控制权限确认弹窗
     * @param context 上下文
     * @param senderName 发送者昵称
     * @param senderImei 发送者IMEI
     * @param groupId 群组ID
     * @param command 要执行的命令
     */
    fun show(
        context: Context,
        senderName: String?,
        senderImei: String,
        groupId: String,
        command: String
    ) {
        if (isShowing) {
            Log.d(TAG, "权限确认弹窗已显示，先隐藏再显示")
            hide()
        }
        
        this.context = context
        
        try {
            val displayName = senderName ?: senderImei.take(8) + "..."
            
            val dialogView = android.view.LayoutInflater.from(context).inflate(
                R.layout.dialog_remote_control_permission, null
            )
            
            // 设置消息内容
            val messageTextView = dialogView.findViewById<TextView>(R.id.tvDialogMessage)
            messageTextView?.text = "${displayName}尝试控制您的手机"
            
            val btnAccept = dialogView.findViewById<android.widget.Button>(R.id.btnAccept)
            val btnReject = dialogView.findViewById<android.widget.Button>(R.id.btnReject)
            
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
                window.setBackgroundDrawableResource(android.R.color.transparent)
                window.decorView.setBackgroundResource(android.R.color.transparent)
                val layoutParams = window.attributes
                layoutParams.dimAmount = 0.3f
                window.attributes = layoutParams
                window.setFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                )
                window.setWindowAnimations(android.R.style.Animation_Dialog)
                val displayMetrics = context.resources.displayMetrics
                val margin = (16 * displayMetrics.density).toInt()
                window.decorView.setPadding(margin, margin, margin, margin)
            }
            
            // 允许按钮
            btnAccept?.setOnClickListener {
                Log.d(TAG, "用户点击允许按钮")
                hide()
                // 跳转到应用并执行命令
                navigateToAppAndExecute(context, groupId, command, senderImei)
            }
            
            // 拒绝按钮
            btnReject?.setOnClickListener {
                Log.d(TAG, "用户点击拒绝按钮")
                hide()
            }
            
            // 设置取消监听
            dialog?.setOnCancelListener {
                Log.d(TAG, "用户取消弹窗")
                hide()
            }
            
            // 显示对话框
            dialog?.show()
            
            isShowing = true
            Log.d(TAG, "远程控制权限确认弹窗已显示")
        } catch (e: Exception) {
            Log.e(TAG, "显示权限确认弹窗失败: ${e.message}", e)
            dialog = null
            isShowing = false
        }
    }
    
    /**
     * 跳转到应用并执行命令
     */
    private fun navigateToAppAndExecute(context: Context, groupId: String, command: String, senderImei: String) {
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("action", "execute_remote_command")
                putExtra("groupId", groupId)
                putExtra("command", command)
                putExtra("senderImei", senderImei)
            }
            context.startActivity(intent)
            Log.d(TAG, "已启动MainActivity执行远程命令")
        } catch (e: Exception) {
            Log.e(TAG, "跳转到应用失败: ${e.message}", e)
        }
    }
    
    /**
     * 隐藏权限确认弹窗
     */
    fun hide() {
        if (!isShowing) {
            return
        }
        
        try {
            dialog?.dismiss()
            dialog = null
            isShowing = false
            Log.d(TAG, "远程控制权限确认弹窗已隐藏")
        } catch (e: Exception) {
            Log.e(TAG, "隐藏权限确认弹窗失败: ${e.message}", e)
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

