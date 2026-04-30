package com.cloudcontrol.demo

import android.app.Dialog
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

/**
 * 教程弹窗
 * 在应用重启时显示，仅保留最终欢迎页
 */
class TutorialDialog(context: Context) : Dialog(context) {
    
    companion object {
        private const val TAG = "TutorialDialog"
    }
    
    private lateinit var tvWelcome: TextView
    private lateinit var btnStart: Button
    private lateinit var btnClose: TextView
    private var isInitialized = false
    
    override fun show() {
        try {
            if (!isInitialized) {
                setupDialog()
                isInitialized = true
            }
            super.show()
        } catch (e: Exception) {
            Log.e(TAG, "显示教程弹窗失败: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    private fun setupDialog() {
        try {
            // 设置 Dialog 样式
            requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            window?.setBackgroundDrawableResource(android.R.color.transparent)
            
            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_tutorial, null)
            setContentView(dialogView)
            
            // 设置窗口属性
            window?.let { window ->
                window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                window.setBackgroundDrawableResource(android.R.color.transparent)
                
                // 设置窗口动画
                window.setWindowAnimations(android.R.style.Animation_Dialog)
            }
            
            // 初始化视图
            tvWelcome = dialogView.findViewById(R.id.tvTutorialWelcome)
            btnStart = dialogView.findViewById(R.id.btnStartUsing)
            btnClose = dialogView.findViewById(R.id.btnClose)
            tvWelcome.text = context.getString(R.string.tutorial_welcome)
            
            // 设置按钮点击事件
            btnClose.setOnClickListener {
                dismiss()
            }
            btnStart.setOnClickListener { dismiss() }
            
            // 设置点击外部不关闭
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        } catch (e: Exception) {
            Log.e(TAG, "设置Dialog失败: ${e.message}", e)
            e.printStackTrace()
            throw e
        }
    }
}

