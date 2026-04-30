package com.cloudcontrol.demo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 技能管理工具类
 * 负责技能的保存、加载、删除等功能
 */
object SkillManager {
    private const val TAG = "SkillManager"
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_MY_SKILLS = "my_skills"
    private const val KEY_COMMUNITY_SKILLS = "community_skills"
    
    private val gson = Gson()
    
    /**
     * 生成技能唯一ID
     * 格式：IMEI_TIMESTAMP_RANDOM
     * - IMEI: 设备/用户唯一标识
     * - TIMESTAMP: 毫秒时间戳
     * - RANDOM: 8位随机码（UUID前8位）
     * 
     * 唯一性保障：
     * 1. IMEI 确保不同设备/用户不会冲突
     * 2. 时间戳确保时间维度唯一性
     * 3. 随机码（8位十六进制，约43亿种可能）确保同一毫秒内创建多个技能时几乎不会重复
     * 
     * @param context Context
     * @return 技能唯一ID
     */
    private fun generateSkillId(context: Context): String {
        val imei = ProfileManager.getOrGenerateImei(context)
        val timestamp = System.currentTimeMillis()
        val random = UUID.randomUUID().toString().replace("-", "").take(8)
        return "${imei}_${timestamp}_${random}"
    }
    
    /**
     * 保存技能到【我的技能】
     * @param context Context
     * @param skill 技能对象
     * @return 是否保存成功
     */
    fun saveSkill(context: Context, skill: Skill): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existingSkills = loadSkills(context).toMutableList()
            
            // 检查是否已存在相同技能（根据ID判断，因为ID是唯一标识）
            val isDuplicate = existingSkills.any { it.id == skill.id }
            if (isDuplicate) {
                Log.w(TAG, "技能已存在（ID重复）: ${skill.id}")
                return false
            }
            
            // 添加新技能（允许同名技能，因为ID是唯一标识）
            existingSkills.add(skill)
            
            // 保存到 SharedPreferences
            val skillsJson = gson.toJson(existingSkills)
            prefs.edit()
                .putString(KEY_MY_SKILLS, skillsJson)
                .apply()
            
            Log.d(TAG, "技能保存成功: ${skill.title}, 总技能数: ${existingSkills.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存技能失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 从技能数据创建并保存技能
     * @param context Context
     * @param title 技能标题（简化后的用户操作目的）
     * @param steps 操作步骤列表
     * @param originalPurpose 原始操作目的（可选）
     * @return 是否保存成功
     */
    fun saveSkillFromData(
        context: Context,
        title: String,
        steps: List<String>,
        originalPurpose: String? = null
    ): Boolean {
        val skill = Skill(
            id = generateSkillId(context),
            title = title,
            steps = steps,
            createdAt = System.currentTimeMillis(),
            originalPurpose = originalPurpose
        )
        return saveSkill(context, skill)
    }
    
    /**
     * 加载所有技能
     * @param context Context
     * @return 技能列表
     */
    fun loadSkills(context: Context): List<Skill> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val skillsJson = prefs.getString(KEY_MY_SKILLS, null)
            
            if (skillsJson.isNullOrEmpty()) {
                return emptyList()
            }
            
            val type = object : TypeToken<List<Skill>>() {}.type
            val skills = gson.fromJson<List<Skill>>(skillsJson, type)
            skills ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "加载技能失败: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * 删除技能
     * @param context Context
     * @param skillId 技能ID
     * @return 是否删除成功
     */
    fun deleteSkill(context: Context, skillId: String): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existingSkills = loadSkills(context).toMutableList()
            
            val removed = existingSkills.removeAll { it.id == skillId }
            if (!removed) {
                Log.w(TAG, "技能不存在: $skillId")
                return false
            }
            
            // 保存更新后的列表
            val skillsJson = gson.toJson(existingSkills)
            prefs.edit()
                .putString(KEY_MY_SKILLS, skillsJson)
                .apply()
            
            Log.d(TAG, "技能删除成功: $skillId, 剩余技能数: ${existingSkills.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "删除技能失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 检查技能是否已存在
     * @param context Context
     * @param title 技能标题
     * @return 是否存在
     */
    fun isSkillExists(context: Context, title: String): Boolean {
        val skills = loadSkills(context)
        return skills.any { it.title == title }
    }
    
    /**
     * 清空所有技能（用于测试或重置）
     * @param context Context
     */
    fun clearAllSkills(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_MY_SKILLS)
            .apply()
        Log.d(TAG, "已清空所有技能")
    }
    
    /**
     * 更新技能（用于编辑技能标题）
     * @param context Context
     * @param skillId 技能ID
     * @param newTitle 新的标题
     * @return 是否更新成功
     */
    fun updateSkillTitle(context: Context, skillId: String, newTitle: String): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existingSkills = loadSkills(context).toMutableList()
            
            val index = existingSkills.indexOfFirst { it.id == skillId }
            if (index < 0) {
                Log.w(TAG, "技能不存在: $skillId")
                return false
            }
            
            // 更新技能标题
            val updatedSkill = existingSkills[index].copy(title = newTitle)
            existingSkills[index] = updatedSkill
            
            // 保存更新后的列表
            val skillsJson = gson.toJson(existingSkills)
            prefs.edit()
                .putString(KEY_MY_SKILLS, skillsJson)
                .apply()
            
            Log.d(TAG, "技能标题更新成功: $skillId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "更新技能标题失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 更新技能（通用方法，支持更新整个技能对象）
     * @param context Context
     * @param updatedSkill 更新后的技能对象
     * @return 是否更新成功
     */
    fun updateSkill(context: Context, updatedSkill: Skill): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existingSkills = loadSkills(context).toMutableList()
            
            val index = existingSkills.indexOfFirst { it.id == updatedSkill.id }
            if (index < 0) {
                Log.w(TAG, "技能不存在: ${updatedSkill.id}")
                return false
            }
            
            existingSkills[index] = updatedSkill
            
            // 保存更新后的列表
            val skillsJson = gson.toJson(existingSkills)
            prefs.edit()
                .putString(KEY_MY_SKILLS, skillsJson)
                .apply()
            
            Log.d(TAG, "技能更新成功: ${updatedSkill.id}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "更新技能失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 保存技能到技能社区
     * @param context Context
     * @param skill 技能对象
     * @return 是否保存成功
     */
    fun saveSkillToCommunity(context: Context, skill: Skill): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existingCommunitySkills = loadCommunitySkills(context).toMutableList()
            
            // 检查是否已存在相同技能（根据ID判断，因为ID是唯一标识）
            val isDuplicate = existingCommunitySkills.any { it.id == skill.id }
            if (isDuplicate) {
                Log.w(TAG, "技能社区中已存在（ID重复）: ${skill.id}")
                return false
            }
            
            // 添加新技能（创建新ID，表示这是社区技能）
            val communitySkill = skill.copy(
                id = generateSkillId(context),
                createdAt = System.currentTimeMillis()
            )
            existingCommunitySkills.add(communitySkill)
            
            // 保存到 SharedPreferences
            val skillsJson = gson.toJson(existingCommunitySkills)
            prefs.edit()
                .putString(KEY_COMMUNITY_SKILLS, skillsJson)
                .apply()
            
            Log.d(TAG, "技能已保存到技能社区: ${skill.title}, 总技能数: ${existingCommunitySkills.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "保存技能到技能社区失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 加载技能社区的所有技能
     * @param context Context
     * @return 技能列表（热门技能在前）
     */
    fun loadCommunitySkills(context: Context): List<Skill> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val skillsJson = prefs.getString(KEY_COMMUNITY_SKILLS, null)
            
            if (skillsJson.isNullOrEmpty()) {
                return emptyList()
            }
            
            val type = object : TypeToken<List<Skill>>() {}.type
            val skills = gson.fromJson<List<Skill>>(skillsJson, type) ?: emptyList()
            
            // 排序：热门技能在前，然后按创建时间倒序
            skills.sortedWith(compareByDescending<Skill> { it.isHot }
                .thenByDescending { it.createdAt })
        } catch (e: Exception) {
            Log.e(TAG, "加载技能社区技能失败: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * 设置技能的热门状态（仅限技能社区）
     * @param context Context
     * @param skillId 技能ID
     * @param isHot 是否为热门
     * @return 是否设置成功
     */
    fun setSkillHotStatus(context: Context, skillId: String, isHot: Boolean): Boolean {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existingSkills = loadCommunitySkills(context).toMutableList()
            
            val index = existingSkills.indexOfFirst { it.id == skillId }
            if (index < 0) {
                Log.w(TAG, "技能不存在: $skillId")
                return false
            }
            
            // 更新技能的热门状态
            // 如果设置为热门且之前不是热门，记录时间；如果取消热门，清除时间
            val currentSkill = existingSkills[index]
            val updatedSkill = currentSkill.copy(
                isHot = isHot,
                hotSetAt = when {
                    isHot && !currentSkill.isHot -> System.currentTimeMillis() // 首次设置为热门
                    !isHot -> null // 取消热门
                    else -> currentSkill.hotSetAt // 保持原有时间
                }
            )
            existingSkills[index] = updatedSkill
            
            // 保存更新后的列表
            val skillsJson = gson.toJson(existingSkills)
            prefs.edit()
                .putString(KEY_COMMUNITY_SKILLS, skillsJson)
                .apply()
            
            Log.d(TAG, "技能热门状态更新成功（本地）: $skillId, isHot=$isHot")
            
            // 同步到云端
            syncHotStatusToService(context, skillId, isHot)
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "更新技能热门状态失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 同步热门状态到云端服务
     * @param context Context
     * @param skillId 技能ID（本地）
     * @param isHot 是否为热门
     */
    private fun syncHotStatusToService(context: Context, skillId: String, isHot: Boolean) {
        // 在后台协程中异步同步到云端
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 获取技能信息（用于获取标题）
                val communitySkills = loadCommunitySkills(context)
                val skill = communitySkills.find { it.id == skillId }
                if (skill == null) {
                    Log.w(TAG, "未找到技能，无法同步热门状态: $skillId")
                    return@launch
                }
                
                val skillServiceUrl = ServiceUrlConfig.getSkillCommunityUrl(context)
                SkillServiceNetwork.initialize(skillServiceUrl)
                val apiService = SkillServiceNetwork.getApiService()
                
                if (apiService != null) {
                    // 使用技能标题来匹配云端技能（因为本地ID和云端ID可能不一致）
                    val response = apiService.setSkillHot(
                        skillId = null,
                        skillTitle = skill.title,
                        isHot = isHot
                    )
                    if (response.isSuccessful) {
                        Log.d(TAG, "技能热门状态同步到云端成功: title=${skill.title}, isHot=$isHot")
                    } else {
                        val errorBody = response.errorBody()?.string()
                        Log.w(TAG, "技能热门状态同步到云端失败: code=${response.code()}, errorBody=$errorBody")
                    }
                } else {
                    Log.w(TAG, "技能服务未初始化，无法同步热门状态")
                }
            } catch (e: Exception) {
                Log.e(TAG, "同步热门状态到云端异常: ${e.message}", e)
            }
        }
    }
    
    /**
     * 从技能服务同步技能到技能社区
     * @param context Context
     * @param skillServiceUrl 技能服务地址（可选，如果为null则使用默认地址）
     * @param name 按名称搜索（可选）
     * @param desc 按描述搜索（可选）
     * @param type 按类型搜索（可选）
     * @return 同步结果，包含成功数量和失败信息
     */
    suspend fun syncSkillsFromService(
        context: Context,
        skillServiceUrl: String? = null,
        name: String? = null,
        desc: String? = null,
        type: String? = null
    ): SyncResult {
        return withContext(Dispatchers.IO) {
            try {
                // 初始化技能服务网络
                if (skillServiceUrl != null) {
                    SkillServiceNetwork.initialize(skillServiceUrl)
                } else {
                    // 尝试使用默认地址，如果失败则返回错误
                    SkillServiceNetwork.initializeDefault()
                }
                
                val apiService = SkillServiceNetwork.getApiService()
                if (apiService == null) {
                    return@withContext SyncResult(
                        success = false,
                        message = "技能服务未初始化",
                        syncedCount = 0,
                        skippedCount = 0
                    )
                }
                
                // 从技能服务获取技能列表
                Log.d(TAG, "开始从技能服务获取技能列表...")
                
                // 先尝试使用新端点
                var response = apiService.getSkillsForMobile(name, desc, type)
                var skillServiceResponse: SkillServiceResponse? = null
                
                // 如果新端点返回404，尝试使用旧端点并转换数据
                if (!response.isSuccessful && response.code() == 404) {
                    Log.d(TAG, "新端点不可用，尝试使用旧端点...")
                    val oldResponse = apiService.getSkillList(name, desc, type)
                    
                    if (oldResponse.isSuccessful) {
                        val oldResponseBody = oldResponse.body()
                        if (oldResponseBody != null) {
                            // 转换旧格式到新格式
                            skillServiceResponse = convertOldSkillsToNew(oldResponseBody)
                            Log.d(TAG, "成功从旧端点获取并转换了 ${skillServiceResponse.skills.size} 个技能")
                        }
                    } else {
                        Log.e(TAG, "旧端点也失败: code=${oldResponse.code()}")
                    }
                } else if (response.isSuccessful) {
                    skillServiceResponse = response.body()
                    Log.d(TAG, "从新端点获取到 ${skillServiceResponse?.skills?.size ?: 0} 个技能")
                }
                
                Log.d(TAG, "技能服务响应: code=${response.code()}, isSuccessful=${response.isSuccessful}")
                
                if (skillServiceResponse == null) {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "获取技能列表失败: code=${response.code()}, errorBody=$errorBody")
                    return@withContext SyncResult(
                        success = false,
                        message = "获取技能失败: HTTP ${response.code()}${if (errorBody != null) " - $errorBody" else ""}。请确保技能服务已重启以支持新端点。",
                        syncedCount = 0,
                        skippedCount = 0
                    )
                }
                
                if (skillServiceResponse.skills.isEmpty()) {
                    Log.d(TAG, "技能服务中没有可同步的技能")
                    return@withContext SyncResult(
                        success = true,
                        message = "技能服务中没有可同步的技能",
                        syncedCount = 0,
                        skippedCount = 0
                    )
                }
                
                Log.d(TAG, "获取到 ${skillServiceResponse.skills.size} 个技能")
                
                // 加载现有的社区技能
                val existingSkills = loadCommunitySkills(context).toMutableList()
                val existingTitles = existingSkills.map { it.title }.toSet()
                
                var syncedCount = 0
                var skippedCount = 0
                
                // 同步技能到社区
                for (skill in skillServiceResponse.skills) {
                    // 检查是否已存在（根据标题判断）
                    val existingIndex = existingSkills.indexOfFirst { it.title == skill.title }
                    if (existingIndex >= 0) {
                        // 如果已存在，更新热门状态（保留云端的热门状态）
                        val existingSkill = existingSkills[existingIndex]
                        val updatedSkill = existingSkill.copy(
                            isHot = skill.isHot,
                            hotSetAt = skill.hotSetAt
                        )
                        existingSkills[existingIndex] = updatedSkill
                        skippedCount++
                        continue
                    }
                    
                    // 添加新技能（创建新ID，表示这是社区技能，但保留云端的热门状态）
                    val communitySkill = skill.copy(
                        id = generateSkillId(context),
                        createdAt = System.currentTimeMillis(),
                        isHot = skill.isHot,
                        hotSetAt = skill.hotSetAt
                    )
                    existingSkills.add(communitySkill)
                    syncedCount++
                }
                
                // 保存更新后的列表
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val skillsJson = gson.toJson(existingSkills)
                prefs.edit()
                    .putString(KEY_COMMUNITY_SKILLS, skillsJson)
                    .apply()
                
                Log.d(TAG, "技能同步完成: 成功 $syncedCount 个, 跳过 $skippedCount 个")
                
                // 检查并更新新热门技能状态（用于显示徽章）
                HotSkillBadgeManager.checkAndUpdateNewHotSkills(context)
                
                SyncResult(
                    success = true,
                    message = "同步完成",
                    syncedCount = syncedCount,
                    skippedCount = skippedCount
                )
            } catch (e: java.net.UnknownHostException) {
                Log.e(TAG, "同步技能失败: 无法解析主机地址", e)
                SyncResult(
                    success = false,
                    message = "连接失败：无法访问技能服务，请检查网络和地址配置",
                    syncedCount = 0,
                    skippedCount = 0
                )
            } catch (e: java.net.ConnectException) {
                Log.e(TAG, "同步技能失败: 连接被拒绝", e)
                SyncResult(
                    success = false,
                    message = "连接失败：无法连接到技能服务，请确保服务正在运行",
                    syncedCount = 0,
                    skippedCount = 0
                )
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "同步技能失败: 连接超时", e)
                SyncResult(
                    success = false,
                    message = "连接超时：技能服务响应时间过长",
                    syncedCount = 0,
                    skippedCount = 0
                )
            } catch (e: Exception) {
                Log.e(TAG, "同步技能失败: ${e.message}", e)
                e.printStackTrace()
                SyncResult(
                    success = false,
                    message = "同步失败: ${e.javaClass.simpleName} - ${e.message}",
                    syncedCount = 0,
                    skippedCount = 0
                )
            }
        }
    }
    
    /**
     * 将旧格式技能转换为新格式
     */
    private fun convertOldSkillsToNew(oldResponse: OldSkillServiceResponse): SkillServiceResponse {
        val convertedSkills = oldResponse.skills.map { oldSkill ->
            // 将skill_actions转换为steps列表
            val steps = mutableListOf<String>()
            for (action in oldSkill.skill_actions) {
                val stepDesc = when (action.action_type) {
                    "open" -> {
                        if (!action.app_name.isNullOrEmpty()) {
                            "打开${action.app_name}"
                        } else {
                            action.reason.ifEmpty { "打开应用" }
                        }
                    }
                    "click" -> action.reason.ifEmpty { "点击" }
                    "type" -> {
                        if (!action.text.isNullOrEmpty()) {
                            "输入：${action.text}"
                        } else {
                            action.reason.ifEmpty { "输入文本" }
                        }
                    }
                    "swipe" -> action.reason.ifEmpty { "滑动" }
                    "wait" -> action.reason.ifEmpty { "等待" }
                    "scroll" -> action.reason.ifEmpty { "滚动" }
                    else -> action.reason.ifEmpty { "执行${action.action_type}操作" }
                }
                if (stepDesc.isNotEmpty()) {
                    steps.add(stepDesc)
                }
            }
            
            // 如果没有步骤，使用技能描述作为步骤
            val finalSteps = if (steps.isEmpty()) {
                listOf(oldSkill.skill_desc.ifEmpty { "无详细步骤" })
            } else {
                steps
            }
            
            Skill(
                id = oldSkill.skill_id,
                title = oldSkill.skill_name.ifEmpty { "未命名技能" },
                steps = finalSteps,
                createdAt = System.currentTimeMillis(),
                originalPurpose = oldSkill.skill_desc,
                isHot = false,  // 旧格式不支持热门状态
                hotSetAt = null
            )
        }
        
        return SkillServiceResponse(convertedSkills)
    }
    
    /**
     * 将端侧技能转换为服务端格式
     */
    private fun convertSkillToServiceFormat(skill: Skill): SkillUploadRequest {
        // 将steps转换为skill_actions
        val actions = skill.steps.map { step ->
            convertStepToAction(step)
        }
        
        return SkillUploadRequest(
            skill_name = skill.title,
            skill_type = null, // 可以后续扩展
            skill_desc = skill.originalPurpose ?: skill.title,
            skill_actions = actions
        )
    }
    
    /**
     * 将步骤文本转换为动作对象
     */
    private fun convertStepToAction(step: String): SkillAction {
        // 移除步骤编号
        val cleanStep = step.replace(Regex("^(\\d+[.、]?|step\\d+[:：]?)\\s*"), "").trim()
        
        // 检测动作类型
        val lowerStep = cleanStep.lowercase()
        val actionType = when {
            lowerStep.contains("打开") || lowerStep.contains("启动") || lowerStep.startsWith("open") -> "open"
            lowerStep.contains("输入") || lowerStep.contains("输入文本") || lowerStep.contains("填写") || 
            lowerStep.startsWith("type") || lowerStep.startsWith("input") -> "type"
            lowerStep.contains("滑动") || lowerStep.contains("划") || lowerStep.startsWith("swipe") -> "swipe"
            lowerStep.contains("滚动") || lowerStep.startsWith("scroll") -> "scroll"
            lowerStep.contains("等待") || lowerStep.contains("暂停") || lowerStep.startsWith("wait") -> "wait"
            else -> "click" // 默认为点击
        }
        
        // 提取应用名称（如果是open动作）
        val appName = if (actionType == "open") {
            extractAppNameFromStep(cleanStep)
        } else {
            null
        }
        
        // 提取输入文本（如果是type动作）
        val text = if (actionType == "type") {
            extractTextFromStep(cleanStep)
        } else {
            null
        }
        
        return SkillAction(
            action_type = actionType,
            reason = cleanStep,
            app_name = appName,
            click = null,
            text = text,
            wait = null,
            swipe = null
        )
    }
    
    /**
     * 从步骤中提取应用名称
     */
    private fun extractAppNameFromStep(step: String): String? {
        // 尝试提取"打开XXX"中的XXX
        val regex = Regex("打开([^，,。.\\s]+)")
        val match = regex.find(step)
        return match?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
    }
    
    /**
     * 从步骤中提取输入文本
     */
    private fun extractTextFromStep(step: String): String? {
        // 尝试提取"输入：XXX"或"输入XXX"中的XXX
        val regex = Regex("输入[：:]?([^，,。.\\n]+)")
        val match = regex.find(step)
        return match?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
    }
    
    /**
     * 检查技能服务中是否存在指定名称的技能
     * @param context Context
     * @param skillName 技能名称
     * @param skillServiceUrl 技能服务地址（可选）
     * @return 如果存在返回true，否则返回false
     */
    suspend fun checkSkillExistsInService(
        context: Context,
        skillName: String,
        skillServiceUrl: String? = null
    ): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 初始化技能服务网络
                if (skillServiceUrl != null) {
                    SkillServiceNetwork.initialize(skillServiceUrl)
                } else {
                    SkillServiceNetwork.initializeDefault()
                }
                
                val apiService = SkillServiceNetwork.getApiService()
                if (apiService == null) {
                    Log.w(TAG, "技能服务未初始化，无法检查技能是否存在")
                    return@withContext false
                }
                
                // 使用精确匹配查询（通过name参数）
                val response = apiService.getSkillList(name = skillName)
                if (response.isSuccessful) {
                    val skills = response.body()?.skills ?: emptyList()
                    // 检查是否有完全匹配的技能名称（不区分大小写）
                    val exists = skills.any { it.skill_name.equals(skillName, ignoreCase = true) }
                    Log.d(TAG, "检查技能是否存在: $skillName, 结果: $exists")
                    exists
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "检查技能是否存在时出错: ${e.message}", e)
                false // 出错时返回false，允许继续上传
            }
        }
    }
    
    /**
     * 上传技能到技能服务
     * @param context Context
     * @param skill 要上传的技能
     * @param skillServiceUrl 技能服务地址（可选）
     * @param skipIfExists 如果技能已存在是否跳过（默认true）
     * @return 上传结果
     */
    suspend fun uploadSkillToService(
        context: Context,
        skill: Skill,
        skillServiceUrl: String? = null,
        skipIfExists: Boolean = true
    ): UploadResult {
        return withContext(Dispatchers.IO) {
            try {
                // 初始化技能服务网络
                if (skillServiceUrl != null) {
                    SkillServiceNetwork.initialize(skillServiceUrl)
                } else {
                    SkillServiceNetwork.initializeDefault()
                }
                
                val apiService = SkillServiceNetwork.getApiService()
                if (apiService == null) {
                    Log.e(TAG, "技能服务未初始化，无法上传")
                    return@withContext UploadResult(
                        success = false,
                        message = "技能服务未初始化",
                        skillId = null
                    )
                }
                
                // 如果skipIfExists=true，才检查技能是否已存在
                if (skipIfExists) {
                    Log.d(TAG, "检查技能是否已存在: ${skill.title}")
                    val exists = try {
                        val response = apiService.getSkillList(name = skill.title)
                        if (response.isSuccessful) {
                            val skills = response.body()?.skills ?: emptyList()
                            val existsResult = skills.any { it.skill_name.equals(skill.title, ignoreCase = true) }
                            Log.d(TAG, "技能存在检查结果: $existsResult (找到 ${skills.size} 个技能)")
                            existsResult
                        } else {
                            Log.w(TAG, "检查技能是否存在失败: code=${response.code()}")
                            false
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "检查技能是否存在时出错: ${e.message}", e)
                        false // 出错时返回false，允许继续上传
                    }
                    
                    if (exists) {
                        Log.d(TAG, "技能已存在，跳过上传: ${skill.title}")
                        return@withContext UploadResult(
                            success = false,
                            message = "技能已存在，已跳过上传",
                            skillId = null,
                            skipped = true
                        )
                    }
                } else {
                    Log.d(TAG, "skipIfExists=false，跳过存在性检查，直接上传")
                }
                
                // 转换技能格式
                val uploadRequest = convertSkillToServiceFormat(skill)
                Log.d(TAG, "上传技能: ${skill.title}, 转换为服务端格式")
                Log.d(TAG, "上传请求数据: skill_name=${uploadRequest.skill_name}, skill_desc=${uploadRequest.skill_desc}, actions=${uploadRequest.skill_actions.size}")
                
                // 上传技能
                Log.d(TAG, "开始发送上传请求到: skill_upload")
                val response = apiService.uploadSkill(uploadRequest)
                Log.d(TAG, "上传请求响应: code=${response.code()}, isSuccessful=${response.isSuccessful}")
                
                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, "上传技能失败: code=${response.code()}, errorBody=$errorBody")
                    return@withContext UploadResult(
                        success = false,
                        message = "上传失败: HTTP ${response.code()}${if (errorBody != null) " - $errorBody" else ""}",
                        skillId = null
                    )
                }
                
                val uploadResponse = response.body()
                if (uploadResponse == null) {
                    Log.e(TAG, "上传技能响应体为null")
                    return@withContext UploadResult(
                        success = false,
                        message = "上传失败：响应体为空",
                        skillId = null
                    )
                }
                
                // 检查响应消息，判断是成功上传还是已存在
                val isExisting = uploadResponse.message.contains("已存在") || uploadResponse.message.contains("exists")
                if (isExisting) {
                    Log.d(TAG, "技能已存在于云端: ${skill.title}, skill_id=${uploadResponse.skill_id}")
                    return@withContext UploadResult(
                        success = true,  // 已存在也算成功，因为技能已经在云端了
                        message = uploadResponse.message,
                        skillId = uploadResponse.skill_id,
                        skipped = true
                    )
                }
                
                Log.d(TAG, "技能上传成功: ${skill.title}, skill_id=${uploadResponse.skill_id}")
                
                UploadResult(
                    success = true,
                    message = "上传成功",
                    skillId = uploadResponse.skill_id
                )
            } catch (e: java.net.UnknownHostException) {
                Log.e(TAG, "上传技能失败: 无法解析主机地址", e)
                UploadResult(
                    success = false,
                    message = "连接失败：无法访问技能服务，请检查网络和地址配置",
                    skillId = null
                )
            } catch (e: java.net.ConnectException) {
                Log.e(TAG, "上传技能失败: 连接被拒绝", e)
                UploadResult(
                    success = false,
                    message = "连接失败：无法连接到技能服务，请确保服务正在运行",
                    skillId = null
                )
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "上传技能失败: 连接超时", e)
                UploadResult(
                    success = false,
                    message = "连接超时：技能服务响应时间过长",
                    skillId = null
                )
            } catch (e: Exception) {
                Log.e(TAG, "上传技能失败: ${e.message}", e)
                e.printStackTrace()
                UploadResult(
                    success = false,
                    message = "上传失败: ${e.javaClass.simpleName} - ${e.message}",
                    skillId = null
                )
            }
        }
    }
}

/**
 * 同步结果数据类
 */
data class SyncResult(
    val success: Boolean,
    val message: String,
    val syncedCount: Int,
    val skippedCount: Int
)

/**
 * 上传结果数据类
 */
data class UploadResult(
    val success: Boolean,
    val message: String,
    val skillId: String?,
    val skipped: Boolean = false  // 是否因为已存在而跳过
)

