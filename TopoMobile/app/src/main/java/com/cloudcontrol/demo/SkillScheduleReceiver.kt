package com.cloudcontrol.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 技能定时提醒广播接收器
 */
class SkillScheduleReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SkillScheduleReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "com.cloudcontrol.demo.ACTION_SKILL_REMINDER") return
        
        val skillId = intent.getStringExtra("skill_id") ?: return
        Log.d(TAG, "收到定时提醒: skillId=$skillId")
        
        // 加载技能
        val skills = SkillManager.loadSkills(context)
        val skill = skills.find { it.id == skillId } ?: return
        
        // 显示提醒对话框
        SkillReminderDialog.show(context, skill)
        
        // 如果是重复任务，重新计算下次触发时间并注册
        val config = skill.scheduleConfig
        if (config != null && config.isEnabled && config.scheduleType != ScheduleType.ONCE) {
            SkillScheduleManager.scheduleSkill(context, skill)
        } else if (config != null && config.scheduleType == ScheduleType.ONCE) {
            // 单次任务执行后禁用
            val updatedSkill = skill.copy(
                scheduleConfig = config.copy(isEnabled = false)
            )
            SkillManager.updateSkill(context, updatedSkill)
        }
    }
}

