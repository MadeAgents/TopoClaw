package com.cloudcontrol.demo

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 好友申请管理器
 * 负责管理待处理的好友申请
 */
object FriendRequestManager {
    private const val TAG = "FriendRequestManager"
    private const val PREFS_NAME = "friends_prefs"
    private const val KEY_PENDING_REQUESTS = "pending_friend_requests"
    private val gson = Gson()
    
    /**
     * 好友申请数据类
     */
    data class FriendRequest(
        val senderImei: String,
        val senderName: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
        val status: String = "pending" // pending/accepted/rejected
    ) : java.io.Serializable
    
    /**
     * 获取所有待处理的好友申请
     */
    fun getPendingRequests(context: Context): List<FriendRequest> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val requestsJson = prefs.getString(KEY_PENDING_REQUESTS, null) ?: return emptyList()
        
        return try {
            val type = object : TypeToken<List<FriendRequest>>() {}.type
            gson.fromJson<List<FriendRequest>>(requestsJson, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "解析好友申请列表失败: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * 获取未读的好友申请数量
     */
    fun getUnreadCount(context: Context): Int {
        return getPendingRequests(context).count { it.status == "pending" }
    }
    
    /**
     * 保存好友申请列表
     */
    private fun saveRequests(context: Context, requests: List<FriendRequest>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val requestsJson = gson.toJson(requests)
        prefs.edit().putString(KEY_PENDING_REQUESTS, requestsJson).apply()
    }
    
    /**
     * 添加好友申请
     */
    fun addRequest(context: Context, request: FriendRequest) {
        val requests = getPendingRequests(context).toMutableList()
        // 检查是否已存在（避免重复）
        if (requests.any { it.senderImei == request.senderImei && it.status == "pending" }) {
            Log.d(TAG, "好友申请已存在: ${request.senderImei}")
            return
        }
        // 移除已存在的相同IMEI的旧申请（如果状态不是pending）
        requests.removeAll { it.senderImei == request.senderImei && it.status != "pending" }
        requests.add(request)
        saveRequests(context, requests)
        Log.d(TAG, "添加好友申请成功: ${request.senderImei}")
    }
    
    /**
     * 更新好友申请状态
     */
    fun updateRequestStatus(context: Context, senderImei: String, status: String) {
        val requests = getPendingRequests(context).toMutableList()
        val index = requests.indexOfFirst { it.senderImei == senderImei && it.status == "pending" }
        if (index >= 0) {
            val request = requests[index]
            requests[index] = request.copy(status = status)
            saveRequests(context, requests)
            Log.d(TAG, "更新好友申请状态成功: $senderImei -> $status")
        }
    }
    
    /**
     * 删除好友申请
     */
    fun removeRequest(context: Context, senderImei: String) {
        val requests = getPendingRequests(context).toMutableList()
        requests.removeAll { it.senderImei == senderImei }
        saveRequests(context, requests)
        Log.d(TAG, "删除好友申请成功: $senderImei")
    }
    
    /**
     * 获取好友申请
     */
    fun getRequest(context: Context, senderImei: String): FriendRequest? {
        return getPendingRequests(context).find { it.senderImei == senderImei && it.status == "pending" }
    }
}

