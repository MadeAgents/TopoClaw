package com.cloudcontrol.demo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 电脑在线状态管理器（绑定当前账号 IMEI 的 PC 端）。
 * 提供自动轮询、手动检测、监听通知。
 */
object PcOnlineStatusManager {
    private const val TAG = "PcOnlineStatusManager"
    private const val CHECK_INTERVAL_MS = 30 * 1000L

    private var isPcOnlineState = false
    private var checkJob: Job? = null
    private var inFlight = false
    private var activeConsumers = 0
    private val listeners = mutableSetOf<PcOnlineStatusListener>()
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    interface PcOnlineStatusListener {
        fun onPcOnlineStatusChanged(isOnline: Boolean)
    }

    fun addListener(listener: PcOnlineStatusListener) {
        synchronized(listeners) {
            listeners.add(listener)
            listener.onPcOnlineStatusChanged(isPcOnlineState)
        }
    }

    fun removeListener(listener: PcOnlineStatusListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun isPcOnline(): Boolean = isPcOnlineState

    fun startChecking(context: Context) {
        activeConsumers += 1
        if (activeConsumers > 1) return
        stopJobOnly()
        checkNow(context)
        checkJob = mainScope.launch {
            while (isActive) {
                delay(CHECK_INTERVAL_MS)
                checkNow(context)
            }
        }
        Log.d(TAG, "已启动PC在线状态检查，间隔: ${CHECK_INTERVAL_MS}ms")
    }

    fun stopChecking() {
        activeConsumers = maxOf(0, activeConsumers - 1)
        if (activeConsumers > 0) return
        stopJobOnly()
        Log.d(TAG, "已停止PC在线状态检查")
    }

    fun checkNow(context: Context) {
        mainScope.launch {
            checkPcOnlineStatus(context)
        }
    }

    private fun stopJobOnly() {
        checkJob?.cancel()
        checkJob = null
    }

    private suspend fun checkPcOnlineStatus(context: Context) {
        if (inFlight) return
        inFlight = true
        try {
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val customerServiceUrl = prefs.getString(
                "customer_service_url",
                ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
            ) ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
            val imei = ProfileManager.getOrGenerateImei(context).trim()
            if (imei.isEmpty()) {
                updateState(false)
                return
            }
            CustomerServiceNetwork.initialize(customerServiceUrl)
            val apiService = CustomerServiceNetwork.getApiService()
            if (apiService == null) {
                Log.w(TAG, "无法获取API服务，跳过PC在线状态检查")
                updateState(false)
                return
            }
            val response = withContext(Dispatchers.IO) {
                apiService.getPcStatus(imei)
            }
            if (response.isSuccessful && response.body()?.success == true) {
                updateState(response.body()?.is_pc_online == true)
            } else {
                Log.w(TAG, "获取PC在线状态失败: code=${response.code()}")
                updateState(false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "检查PC在线状态失败: ${e.message}", e)
            updateState(false)
        } finally {
            inFlight = false
        }
    }

    private fun updateState(nextState: Boolean) {
        if (isPcOnlineState == nextState) return
        isPcOnlineState = nextState
        synchronized(listeners) {
            listeners.forEach { listener ->
                try {
                    listener.onPcOnlineStatusChanged(isPcOnlineState)
                } catch (e: Exception) {
                    Log.e(TAG, "通知PC在线监听器失败: ${e.message}", e)
                }
            }
        }
        Log.d(TAG, "PC在线状态已更新: $isPcOnlineState")
    }
}

