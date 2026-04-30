package com.cloudcontrol.demo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 临时技能管理器
 * 用于存储从好友或群组接收到的技能卡片，这些技能不在"我的技能"或"技能社区"中
 * 确保退出重进聊天详情页时，技能卡片不会丢失
 */
object TemporarySkillManager {
    private const val TAG = "TemporarySkillManager"
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_TEMPORARY_SKILLS = "temporary_skills"
    
    private val gson = Gson()
    
    /**
     * 保存临时技能
     * @param context Context
     * @param skill 技能对象（必须包含有效的skillId）
     * @param conversationId 所属对话ID（可选，用于清理）
     * @return 是否保存成功
     */
    fun saveTemporarySkill(context: Context, skill: Skill, conversationId: String? = null): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existingSkills = loadTemporarySkills(context).toMutableMap()
            
            // 使用skillId作为key，确保唯一性
            existingSkills[skill.id] = skill
            
            // 保存到 SharedPreferences
            val skillsJson = gson.toJson(existingSkills)
            prefs.edit()
                .putString(KEY_TEMPORARY_SKILLS, skillsJson)
                .apply()
            
            Log.d(TAG, "临时技能保存成功: skillId=${skill.id}, title=${skill.title}, 总临时技能数: ${existingSkills.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存临时技能失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 根据技能ID查找临时技能
     * @param context Context
     * @param skillId 技能ID
     * @return 找到的技能，如果找不到则返回null
     */
    fun findTemporarySkill(context: Context, skillId: String): Skill? {
        return try {
            val temporarySkills = loadTemporarySkills(context)
            val skill = temporarySkills[skillId]
            if (skill != null) {
                Log.d(TAG, "在临时存储中找到技能: skillId=$skillId, title=${skill.title}")
            }
            skill
        } catch (e: Exception) {
            Log.e(TAG, "查找临时技能失败: ${e.message}", e)
            null
        }
    }
    
    /**
     * 加载所有临时技能
     * @param context Context
     * @return 临时技能Map，key为skillId，value为Skill对象
     */
    fun loadTemporarySkills(context: Context): Map<String, Skill> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val skillsJson = prefs.getString(KEY_TEMPORARY_SKILLS, null)
            
            if (skillsJson.isNullOrEmpty()) {
                return emptyMap()
            }
            
            val type = object : TypeToken<Map<String, Skill>>() {}.type
            val skills = gson.fromJson<Map<String, Skill>>(skillsJson, type)
            skills ?: emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "加载临时技能失败: ${e.message}", e)
            emptyMap()
        }
    }
    
    /**
     * 删除临时技能
     * @param context Context
     * @param skillId 技能ID
     * @return 是否删除成功
     */
    fun deleteTemporarySkill(context: Context, skillId: String): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existingSkills = loadTemporarySkills(context).toMutableMap()
            
            val removed = existingSkills.remove(skillId)
            if (removed == null) {
                Log.w(TAG, "临时技能不存在: $skillId")
                return false
            }
            
            // 保存更新后的Map
            val skillsJson = gson.toJson(existingSkills)
            prefs.edit()
                .putString(KEY_TEMPORARY_SKILLS, skillsJson)
                .apply()
            
            Log.d(TAG, "临时技能删除成功: skillId=$skillId, 剩余临时技能数: ${existingSkills.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "删除临时技能失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 检查临时技能是否存在
     * @param context Context
     * @param skillId 技能ID
     * @return 是否存在
     */
    fun hasTemporarySkill(context: Context, skillId: String): Boolean {
        return findTemporarySkill(context, skillId) != null
    }
    
    /**
     * 清理所有临时技能（谨慎使用）
     * @param context Context
     * @return 是否清理成功
     */
    fun clearAllTemporarySkills(context: Context): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .remove(KEY_TEMPORARY_SKILLS)
                .apply()
            
            Log.d(TAG, "所有临时技能已清理")
            true
        } catch (e: Exception) {
            Log.e(TAG, "清理临时技能失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 获取临时技能数量
     * @param context Context
     * @return 临时技能数量
     */
    fun getTemporarySkillCount(context: Context): Int {
        return loadTemporarySkills(context).size
    }
}

