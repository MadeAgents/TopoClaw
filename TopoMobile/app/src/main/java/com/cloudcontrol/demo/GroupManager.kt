package com.cloudcontrol.demo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 群组管理器
 * 负责群组列表的本地存储和管理
 */
object GroupManager {
    private const val TAG = "GroupManager"
    private const val PREFS_NAME = "groups_prefs"
    private const val KEY_GROUPS = "groups_list"
    private val gson = Gson()
    
    /**
     * 群组数据类
     */
    data class Group(
        val groupId: String,
        val name: String,
        val creatorImei: String,
        val members: List<String>,
        val createdAt: String,
        val assistantEnabled: Boolean = true,
        val assistants: List<String> = emptyList(),  // 群组内小助手ID列表
        val isDefaultGroup: Boolean = false
    ) : java.io.Serializable
    
    /**
     * 获取所有群组列表
     */
    fun getGroups(context: Context): List<Group> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val groupsJson = prefs.getString(KEY_GROUPS, null) ?: return emptyList()
        
        return try {
            val type = object : TypeToken<List<Group>>() {}.type
            gson.fromJson<List<Group>>(groupsJson, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "解析群组列表失败: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * 保存群组列表
     */
    private fun saveGroups(context: Context, groups: List<Group>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val groupsJson = gson.toJson(groups)
        prefs.edit().putString(KEY_GROUPS, groupsJson).apply()
    }
    
    /**
     * 添加群组
     */
    fun addGroup(context: Context, group: Group) {
        val groups = getGroups(context).toMutableList()
        // 检查是否已存在
        if (groups.any { it.groupId == group.groupId }) {
            Log.d(TAG, "群组已存在: ${group.groupId}")
            // 更新现有群组
            val index = groups.indexOfFirst { it.groupId == group.groupId }
            if (index >= 0) {
                groups[index] = group
            }
        } else {
            groups.add(group)
        }
        saveGroups(context, groups)
        Log.d(TAG, "添加群组成功: ${group.groupId}, 名称: ${group.name}")
    }
    
    /**
     * 更新群组信息
     */
    fun updateGroup(context: Context, group: Group) {
        val groups = getGroups(context).toMutableList()
        val index = groups.indexOfFirst { it.groupId == group.groupId }
        if (index >= 0) {
            groups[index] = group
            saveGroups(context, groups)
            Log.d(TAG, "更新群组成功: ${group.groupId}")
        }
    }
    
    /**
     * 删除群组
     */
    fun removeGroup(context: Context, groupId: String) {
        val groups = getGroups(context).toMutableList()
        groups.removeAll { it.groupId == groupId }
        saveGroups(context, groups)
        Log.d(TAG, "删除群组成功: $groupId")
    }
    
    /**
     * 获取群组信息
     */
    fun getGroup(context: Context, groupId: String): Group? {
        return getGroups(context).find { it.groupId == groupId }
    }
    
    /**
     * 同步群组列表（从服务器）
     */
    suspend fun syncGroupsFromServer(context: Context): Boolean = withContext(Dispatchers.IO) {
        try {
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
            
            CustomerServiceNetwork.initialize(customerServiceUrl)
            val apiService = CustomerServiceNetwork.getApiService()
            
            if (apiService == null) {
                Log.w(TAG, "API服务未初始化，无法同步群组列表")
                return@withContext false
            }
            
            val currentImei = ProfileManager.getOrGenerateImei(context)
            val response = apiService.getGroups(currentImei)
            
            if (response.isSuccessful && response.body()?.success == true) {
                val serverGroups = response.body()?.groups ?: emptyList()
                
                // 转换为本地Group格式
                val localGroups = serverGroups.map { groupInfo ->
                    val assistants = groupInfo.assistants
                        ?: if (groupInfo.assistant_enabled) listOf("assistant") else emptyList()
                    Group(
                        groupId = groupInfo.group_id,
                        name = groupInfo.name,
                        creatorImei = groupInfo.creator_imei,
                        members = groupInfo.members,
                        createdAt = groupInfo.created_at,
                        assistantEnabled = groupInfo.assistant_enabled,
                        assistants = assistants,
                        isDefaultGroup = groupInfo.is_default_group == true
                    )
                }
                
                // 保存到本地
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val groupsJson = gson.toJson(localGroups)
                prefs.edit().putString(KEY_GROUPS, groupsJson).apply()
                
                Log.d(TAG, "同步群组列表成功，共 ${localGroups.size} 个群组")
                return@withContext true
            } else {
                Log.w(TAG, "同步群组列表失败: HTTP ${response.code()}")
                return@withContext false
            }
        } catch (e: Exception) {
            Log.e(TAG, "同步群组列表异常: ${e.message}", e)
            return@withContext false
        }
    }
}

