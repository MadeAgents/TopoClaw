package com.cloudcontrol.demo

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * 在线状态管理器
 * 统一管理好友在线状态，供多个Fragment共享使用
 */
object OnlineStatusManager {
    private const val TAG = "OnlineStatusManager"
    private const val CHECK_INTERVAL_MS = 30 * 1000L // 30秒检查一次
    
    private val onlineFriendsSet = ConcurrentHashMap.newKeySet<String>()
    private var checkJob: Job? = null
    private val listeners = mutableSetOf<OnlineStatusListener>()
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    /**
     * 在线状态监听器
     */
    interface OnlineStatusListener {
        fun onOnlineStatusChanged(onlineFriends: Set<String>)
    }
    
    /**
     * 添加监听器
     */
    fun addListener(listener: OnlineStatusListener) {
        synchronized(listeners) {
            listeners.add(listener)
            // 立即通知当前状态
            listener.onOnlineStatusChanged(onlineFriendsSet.toSet())
        }
    }
    
    /**
     * 移除监听器
     */
    fun removeListener(listener: OnlineStatusListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }
    
    /**
     * 检查指定好友是否在线
     */
    fun isFriendOnline(friendImei: String): Boolean {
        return friendImei in onlineFriendsSet
    }
    
    /**
     * 获取所有在线好友IMEI集合
     */
    fun getOnlineFriends(): Set<String> {
        return onlineFriendsSet.toSet()
    }
    
    /**
     * 开始定期检查在线状态
     */
    fun startChecking(context: android.content.Context) {
        // 如果已经有任务在运行，先取消
        stopChecking()
        
        // 立即检查一次
        checkOnlineStatus(context)
        
        // 启动定期检查任务
        checkJob = mainScope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                checkOnlineStatus(context)
            }
        }
        Log.d(TAG, "已启动在线状态定期检查，间隔: ${CHECK_INTERVAL_MS}ms")
    }
    
    /**
     * 停止定期检查
     */
    fun stopChecking() {
        checkJob?.cancel()
        checkJob = null
        Log.d(TAG, "已停止在线状态定期检查")
    }
    
    /**
     * 检查好友在线状态并更新
     */
    private fun checkOnlineStatus(context: android.content.Context) {
        mainScope.launch {
            try {
                val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                    ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                
                CustomerServiceNetwork.initialize(customerServiceUrl)
                val apiService = CustomerServiceNetwork.getApiService()
                
                if (apiService == null) {
                    Log.w(TAG, "无法获取API服务，跳过在线状态检查")
                    return@launch
                }
                
                // 调用API获取在线用户列表
                val response = withContext(Dispatchers.IO) {
                    apiService.getOnlineUsers()
                }
                
                if (response.isSuccessful && response.body()?.success == true) {
                    val allOnlineUsers = response.body()?.users ?: emptyList()
                    
                    // 获取当前用户的好友列表
                    val friends = FriendManager.getFriends(context)
                        .filter { it.status == "accepted" }
                    val friendImeis = friends.map { it.imei }.toSet()
                    
                    // 过滤出在线的好友
                    val onlineFriends = allOnlineUsers.filter { it in friendImeis }.toSet()
                    
                    // 检查是否有变化
                    val hasChanged = onlineFriendsSet != onlineFriends
                    
                    if (hasChanged) {
                        onlineFriendsSet.clear()
                        onlineFriendsSet.addAll(onlineFriends)
                        
                        // 通知所有监听器
                        synchronized(listeners) {
                            listeners.forEach { listener ->
                                try {
                                    listener.onOnlineStatusChanged(onlineFriendsSet.toSet())
                                } catch (e: Exception) {
                                    Log.e(TAG, "通知监听器失败: ${e.message}", e)
                                }
                            }
                        }
                        
                        Log.d(TAG, "在线状态已更新，在线好友数: ${onlineFriendsSet.size}, 在线好友: $onlineFriendsSet")
                    }
                } else {
                    val errorBody = try {
                        response.errorBody()?.string()
                    } catch (e: Exception) {
                        "无法读取错误信息"
                    }
                    Log.w(TAG, "获取在线用户列表失败: success=${response.body()?.success}, code=${response.code()}, errorBody=$errorBody")
                }
            } catch (e: Exception) {
                Log.e(TAG, "检查在线状态失败: ${e.message}", e)
            }
        }
    }
}

