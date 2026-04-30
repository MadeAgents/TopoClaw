package com.cloudcontrol.demo

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast

/**
 * 输入法切换引导弹窗管理器
 * 用于在输入法设置页面显示引导弹窗
 */
object IMESwitchGuideOverlayManager {
    private const val TAG = "IMESwitchGuide"
    
    private var dialog: android.app.AlertDialog? = null
    private var isShowing = false
    private var context: Context? = null
    private var onContinueCallback: (() -> Unit)? = null
    private var onSettingsCallback: (() -> Unit)? = null
    
    /**
     * 检查弹窗是否正在显示
     */
    fun isDialogShowing(): Boolean {
        return isShowing
    }
    
    /**
     * 显示输入法切换引导弹窗
     * @param context 上下文
     * @param onSettings 设置回调（用户点击"设置"）
     * @param onContinue 继续回调（用户点击"继续"）
     * @param onCancel 取消回调（用户点击"取消"或关闭弹窗）
     */
    fun show(
        context: Context,
        onSettings: () -> Unit,
        onContinue: () -> Unit,
        onCancel: () -> Unit
    ) {
        if (isShowing) {
            Log.d(TAG, "引导弹窗已显示，先隐藏再显示")
            hide()
        }
        
        this.context = context
        this.onContinueCallback = onContinue
        this.onSettingsCallback = onSettings
        
        try {
            val dialogView = android.view.LayoutInflater.from(context).inflate(
                R.layout.dialog_ime_switch_guide, null
            )
            
            val btnCancel = dialogView.findViewById<android.widget.Button>(R.id.btnCancel)
            val btnSettings = dialogView.findViewById<android.widget.Button>(R.id.btnSettings)
            val btnContinue = dialogView.findViewById<android.widget.Button>(R.id.btnContinue)
            
            // 初始状态：显示"设置"按钮，隐藏"继续"按钮
            btnSettings?.visibility = android.view.View.VISIBLE
            btnContinue?.visibility = android.view.View.GONE
            
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
            
            // 设置弹窗位置为底部
            dialog?.window?.let { window ->
                window.setBackgroundDrawableResource(android.R.color.transparent)
                window.decorView.setBackgroundResource(android.R.color.transparent)
                window.setWindowAnimations(android.R.style.Animation_Dialog)
                
                val layoutParams = window.attributes
                layoutParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                layoutParams.y = (32 * context.resources.displayMetrics.density).toInt()
                window.attributes = layoutParams
                
                val displayMetrics = context.resources.displayMetrics
                val margin = (16 * displayMetrics.density).toInt()
                window.decorView.setPadding(margin, margin, margin, margin)
            }
            
            // 取消按钮
            btnCancel?.setOnClickListener {
                Log.d(TAG, "用户取消输入法切换")
                hide()
                onCancel()
            }
            
            // 设置按钮
            btnSettings?.setOnClickListener {
                Log.d(TAG, "用户点击设置")
                // 打开输入法设置页面
                try {
                    val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    // 切换按钮显示：隐藏"设置"，显示"继续"
                    btnSettings?.visibility = android.view.View.GONE
                    btnContinue?.visibility = android.view.View.VISIBLE
                    // 更新提示文本
                    val messageView = dialogView.findViewById<android.widget.TextView>(R.id.tvDialogMessage)
                    messageView?.text = "请切换到 TopoClaw 键盘\n切换完成后回退到操作页面，然后点击继续"
                    onSettings()
                } catch (e: Exception) {
                    Log.e(TAG, "打开设置页面失败: ${e.message}", e)
                    Toast.makeText(context, "打开设置失败", Toast.LENGTH_SHORT).show()
                }
            }
            
            // 继续按钮
            btnContinue?.setOnClickListener {
                Log.d(TAG, "========== 用户点击继续按钮 ==========")
                
                val accessibilityService = MyAccessibilityService.getInstance()
                if (accessibilityService == null) {
                    Log.e(TAG, "无障碍服务未启用")
                    Toast.makeText(context, "无障碍服务未启用", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                // 检查是否还在设置页面
                val isInSettings = accessibilityService.isInIMESettingsPage()
                val currentPackageName = MyAccessibilityService.getCurrentPackageName()
                val currentClassName = MyAccessibilityService.getCurrentClassName()
                Log.d(TAG, "当前页面检测: isInSettings=$isInSettings, package=$currentPackageName, class=$currentClassName")
                
                if (isInSettings) {
                    Log.w(TAG, "仍在设置页面，提示用户退出")
                    Toast.makeText(context, "请先退出设置页面", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                // 检查输入法是否已切换
                val currentIME = accessibilityService.getCurrentIME()
                val customIMEId = "${context.packageName}/${SimpleInputMethodService::class.java.name}"
                Log.d(TAG, "输入法检测: currentIME=$currentIME, customIMEId=$customIMEId")
                
                // 如果输入法未切换，给出警告但允许继续（用户可能已经切换但检测延迟）
                if (currentIME != customIMEId) {
                    Log.w(TAG, "输入法检测未切换，但允许用户继续（可能检测延迟）")
                    Log.w(TAG, "currentIME=$currentIME, expected=$customIMEId")
                    // 不阻止，允许用户继续，如果确实没切换会在重试时再次提示
                }
                
                // 验证通过，关闭弹窗并继续
                Log.d(TAG, "✓✓✓ 关闭弹窗并继续")
                hide()
                onContinue()
            }
            
            // 设置取消监听
            dialog?.setOnCancelListener {
                Log.d(TAG, "用户取消弹窗")
                hide()
                onCancel()
            }
            
            // 显示对话框
            dialog?.show()
            
            isShowing = true
            Log.d(TAG, "输入法切换引导弹窗已显示")
        } catch (e: Exception) {
            Log.e(TAG, "显示引导弹窗失败: ${e.message}", e)
            dialog = null
            isShowing = false
        }
    }
    
    /**
     * 隐藏引导弹窗
     */
    fun hide() {
        if (!isShowing) {
            return
        }
        
        try {
            dialog?.dismiss()
            dialog = null
            isShowing = false
            context = null
            onContinueCallback = null
            onSettingsCallback = null
            Log.d(TAG, "输入法切换引导弹窗已隐藏")
        } catch (e: Exception) {
            Log.e(TAG, "隐藏引导弹窗失败: ${e.message}", e)
        }
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        hide()
    }
}

