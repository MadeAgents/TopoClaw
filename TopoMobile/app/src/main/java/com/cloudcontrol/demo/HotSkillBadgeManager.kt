package com.cloudcontrol.demo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 热门技能徽章管理器
 * 用于跟踪和显示新热门技能的徽章
 */
object HotSkillBadgeManager {
    private const val TAG = "HotSkillBadgeManager"
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_KNOWN_HOT_SKILLS = "known_hot_skills" // 记录用户已知的热门技能状态
    
    private val gson = Gson()
    
    /**
     * 记录已知的热门技能状态
     * 格式: Map<技能标题, hotSetAt时间戳>
     */
    private fun getKnownHotSkills(context: Context): MutableMap<String, Long?> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_KNOWN_HOT_SKILLS, null)
        return if (json != null) {
            try {
                val type = object : TypeToken<MutableMap<String, Long?>>() {}.type
                gson.fromJson(json, type) ?: mutableMapOf()
            } catch (e: Exception) {
                Log.e(TAG, "解析已知热门技能失败: ${e.message}", e)
                mutableMapOf()
            }
        } else {
            mutableMapOf()
        }
    }
    
    /**
     * 保存已知的热门技能状态
     */
    private fun saveKnownHotSkills(context: Context, knownHotSkills: Map<String, Long?>) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val json = gson.toJson(knownHotSkills)
            prefs.edit()
                .putString(KEY_KNOWN_HOT_SKILLS, json)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "保存已知热门技能失败: ${e.message}", e)
        }
    }
    
    /**
     * 检查并更新新热门技能状态
     * 当同步技能时调用，检测是否有新的热门技能或热门状态变化
     */
    fun checkAndUpdateNewHotSkills(context: Context) {
        try {
            val communitySkills = SkillManager.loadCommunitySkills(context)
            val knownHotSkills = getKnownHotSkills(context)
            val updatedKnownHotSkills = mutableMapOf<String, Long?>()
            
            // 检查每个热门技能
            communitySkills.forEach { skill ->
                if (skill.isHot && skill.hotSetAt != null) {
                    val knownHotSetAt = knownHotSkills[skill.title]
                    // 如果这是新的热门技能，或者hotSetAt更新了，说明有新热门
                    if (knownHotSetAt == null || knownHotSetAt < skill.hotSetAt) {
                        // 有新热门，保持记录
                        updatedKnownHotSkills[skill.title] = skill.hotSetAt
                    } else {
                        // 已知的热门，更新记录
                        updatedKnownHotSkills[skill.title] = skill.hotSetAt
                    }
                } else {
                    // 不是热门，如果之前是热门，移除记录
                    if (knownHotSkills.containsKey(skill.title)) {
                        // 之前是热门，现在不是了，移除记录
                    } else {
                        // 一直不是热门，不需要记录
                    }
                }
            }
            
            // 保存更新后的已知热门技能状态
            saveKnownHotSkills(context, updatedKnownHotSkills)
            Log.d(TAG, "已更新已知热门技能状态，当前热门技能数: ${updatedKnownHotSkills.size}")
        } catch (e: Exception) {
            Log.e(TAG, "检查新热门技能失败: ${e.message}", e)
        }
    }
    
    /**
     * 获取新热门技能的数量
     * @param context Context
     * @return 新热门技能的数量（用户未知的新热门技能）
     */
    fun getNewHotSkillsCount(context: Context): Int {
        return try {
            val communitySkills = SkillManager.loadCommunitySkills(context)
            val knownHotSkills = getKnownHotSkills(context)
            
            // 统计用户未知的新热门技能数量
            val newHotSkillsCount = communitySkills.count { skill ->
                if (skill.isHot && skill.hotSetAt != null) {
                    val knownHotSetAt = knownHotSkills[skill.title]
                    // 如果这是新的热门技能，或者hotSetAt更新了，说明有新热门
                    knownHotSetAt == null || knownHotSetAt < skill.hotSetAt
                } else {
                    false
                }
            }
            
            Log.d(TAG, "新热门技能数量: $newHotSkillsCount, 已知热门技能数: ${knownHotSkills.size}")
            newHotSkillsCount
        } catch (e: Exception) {
            Log.e(TAG, "获取新热门技能数量失败: ${e.message}", e)
            0
        }
    }
    
    /**
     * 清除新热门技能徽章（当用户查看技能社区时调用）
     * 将当前所有热门技能标记为已知
     */
    fun clearBadge(context: Context) {
        try {
            val communitySkills = SkillManager.loadCommunitySkills(context)
            val knownHotSkills = mutableMapOf<String, Long?>()
            
            // 将所有当前热门技能标记为已知
            communitySkills.forEach { skill ->
                if (skill.isHot && skill.hotSetAt != null) {
                    knownHotSkills[skill.title] = skill.hotSetAt
                }
            }
            
            saveKnownHotSkills(context, knownHotSkills)
            Log.d(TAG, "已清除新热门技能徽章，标记了 ${knownHotSkills.size} 个热门技能为已知")
        } catch (e: Exception) {
            Log.e(TAG, "清除徽章失败: ${e.message}", e)
        }
    }
}

