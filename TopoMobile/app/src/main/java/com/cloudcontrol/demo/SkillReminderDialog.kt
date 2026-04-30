package com.cloudcontrol.demo

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 技能提醒对话框
 */
object SkillReminderDialog {
    private const val TAG = "SkillReminderDialog"
    
    fun show(context: Context, skill: Skill) {
        try {
            // 检查context是否是Activity
            val activity = if (context is android.app.Activity) context else null
            if (activity != null && !activity.isFinishing && !activity.isDestroyed) {
                showDialog(activity, skill)
            } else {
                // 如果应用在后台，先打开应用，然后显示对话框
                showNotificationAndOpenApp(context, skill)
            }
        } catch (e: Exception) {
            Log.e(TAG, "显示提醒对话框失败: ${e.message}", e)
            showNotificationAndOpenApp(context, skill)
        }
    }
    
    private fun showDialog(context: Context, skill: Skill) {
        val density = context.resources.displayMetrics.density
        fun dpToPx(dp: Int) = (dp * density).toInt()
        
        // 创建自定义视图
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(20), dpToPx(24), dpToPx(20))
        }
        
        // 标题
        val titleView = TextView(context).apply {
            text = "⏰ 技能提醒"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF333333.toInt())
            setPadding(0, 0, 0, dpToPx(16))
            gravity = android.view.Gravity.CENTER
        }
        container.addView(titleView)
        
        // 技能名称
        val skillNameView = TextView(context).apply {
            text = skill.title
            textSize = 16f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, dpToPx(8))
            gravity = android.view.Gravity.CENTER
        }
        container.addView(skillNameView)
        
        // 提示文字
        val hintView = TextView(context).apply {
            text = "到时间执行技能了，是否立即执行？"
            textSize = 14f
            setTextColor(0xFF999999.toInt())
            setPadding(0, 0, 0, dpToPx(20))
            gravity = android.view.Gravity.CENTER
        }
        container.addView(hintView)
        
        // 创建自定义按钮容器
        val buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(dpToPx(24), dpToPx(16), dpToPx(24), dpToPx(16))
        }
        
        // 先创建dialog
        val dialog = AlertDialog.Builder(context)
            .setView(container)
            .setCancelable(false)
            .create()
        
        // 稍后提醒按钮（第一个）
        val snoozeButton = Button(context).apply {
            text = "稍后提醒"
            textSize = 15f
            setTextColor(0xFF2196F3.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFE3F2FD.toInt())
                cornerRadius = dpToPx(12).toFloat()
            }
            setPadding(dpToPx(24), dpToPx(12), dpToPx(24), dpToPx(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dpToPx(12)
            }
            setOnClickListener {
                dialog.dismiss()
                showSnoozeDialog(context, skill)
            }
        }
        
        // 执行按钮（第二个）
        val executeButton = Button(context).apply {
            text = "执行"
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF2196F3.toInt())
                cornerRadius = dpToPx(12).toFloat()
            }
            setPadding(dpToPx(24), dpToPx(12), dpToPx(24), dpToPx(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                dialog.dismiss()
                executeSkill(context, skill)
            }
        }
        
        buttonContainer.addView(snoozeButton)
        buttonContainer.addView(executeButton)
        container.addView(buttonContainer)
        
        // 设置圆角背景
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = dpToPx(20).toFloat()
        })
        
        dialog.show()
    }
    
    private fun showSnoozeDialog(context: Context, skill: Skill) {
        val density = context.resources.displayMetrics.density
        fun dpToPx(dp: Int) = (dp * density).toInt()
        
        val options = arrayOf("5分钟", "30分钟", "1小时")
        val delays = arrayOf(5 * 60 * 1000L, 30 * 60 * 1000L, 60 * 60 * 1000L)
        
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(20), dpToPx(24), dpToPx(20))
        }
        
        val titleView = TextView(context).apply {
            text = "选择稍后提醒时间"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF333333.toInt())
            setPadding(0, 0, 0, dpToPx(20))
            gravity = android.view.Gravity.CENTER
        }
        container.addView(titleView)
        
        val dialog = AlertDialog.Builder(context)
            .setView(container)
            .setNegativeButton("取消", null)
            .create()
        
        // 设置圆角背景
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = dpToPx(20).toFloat()
        })
        
        options.forEachIndexed { index, option ->
            val button = Button(context).apply {
                text = option
                textSize = 16f
                setTextColor(0xFF2196F3.toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFFE3F2FD.toInt())
                    cornerRadius = dpToPx(12).toFloat()
                }
                setPadding(dpToPx(24), dpToPx(14), dpToPx(24), dpToPx(14))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(12)
                }
                setOnClickListener {
                    val delay = delays[index]
                    val config = skill.scheduleConfig?.copy(
                        targetTime = System.currentTimeMillis() + delay
                    )
                    if (config != null) {
                        val updatedSkill = skill.copy(scheduleConfig = config)
                        SkillManager.updateSkill(context, updatedSkill)
                        SkillScheduleManager.scheduleSkill(context, updatedSkill)
                        android.widget.Toast.makeText(context, "已设置${option}后提醒", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    dialog.dismiss()
                }
            }
            container.addView(button)
        }
        
        dialog.show()
        
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
            textSize = 16f
            setTextColor(0xFF666666.toInt())
            background = null
        }
    }
    
    private fun showNotificationAndOpenApp(context: Context, skill: Skill) {
        // 创建通知并打开应用
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("skill_reminder_id", skill.id)
        }
        context.startActivity(intent)
        
        // 延迟一点显示对话框，确保Activity已启动
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                val activity = (context as? android.app.Activity) ?: return@postDelayed
                showDialog(activity, skill)
            } catch (e: Exception) {
                Log.e(TAG, "延迟显示对话框失败: ${e.message}", e)
            }
        }, 500)
    }
    
    private fun executeSkill(context: Context, skill: Skill) {
        try {
            val mainActivity = context as? MainActivity
            if (mainActivity != null) {
                // 切换到TopoClaw对话
                val assistantConv = Conversation(
                    id = ConversationListFragment.CONVERSATION_ID_ASSISTANT,
                    name = ChatConstants.ASSISTANT_DISPLAY_NAME,
                    avatar = null,
                    lastMessage = null,
                    lastMessageTime = System.currentTimeMillis()
                )
                
                // 切换到TopoClaw对话
                mainActivity.switchToChatFragment(assistantConv)
                
                // 等待Fragment切换完成后再发送技能
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        val chatFragment = mainActivity.getChatFragment()
                        if (chatFragment != null && chatFragment.isAdded) {
                            // 直接调用公开的sendSkillAsQuery方法
                            chatFragment.sendSkillAsQuery(skill)
                            Log.d(TAG, "技能已发送到TopoClaw: ${skill.title}")
                        } else {
                            Log.w(TAG, "ChatFragment未就绪，延迟重试")
                            // 如果Fragment还没准备好，再延迟一点
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                try {
                                    val retryFragment = mainActivity.getChatFragment()
                                    if (retryFragment != null && retryFragment.isAdded) {
                                        retryFragment.sendSkillAsQuery(skill)
                                        Log.d(TAG, "技能已发送到TopoClaw（重试）: ${skill.title}")
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "重试发送技能失败: ${e.message}", e)
                                }
                            }, 500)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "发送技能到TopoClaw失败: ${e.message}", e)
                    }
                }, 300)
            } else {
                // 如果不是MainActivity，尝试启动MainActivity
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("skill_reminder_id", skill.id)
                }
                context.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行技能失败: ${e.message}", e)
        }
    }
}

