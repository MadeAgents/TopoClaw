package com.cloudcontrol.demo

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.selects.select
import retrofit2.Response

/**
 * 动态批测控制器
 * 每次执行任务前都从云侧获取第一个任务
 */
class DynamicBatchTestController(
    private val mainActivity: MainActivity,
    private val chatFragment: ChatFragment
) {
    companion object {
        private const val TAG = "DynamicBatchTestController"
        private const val DEFAULT_QUERY_TIMEOUT = 1800000L // 30分钟超时
    }
    
    // 动态批测状态
    data class DynamicBatchTestState(
        val isRunning: Boolean = false,
        val isPaused: Boolean = false,
        val currentIndex: Int = 0,
        val successCount: Int = 0,
        val failCount: Int = 0,
        val currentQuery: String? = null
    )
    
    private val _state = MutableStateFlow(DynamicBatchTestState())
    val state: StateFlow<DynamicBatchTestState> = _state
    
    private var job: Job? = null
    private var currentTestId: String? = null
    
    /**
     * 开始动态批测
     */
    suspend fun start() {
        Log.d(TAG, "开始动态批测")
        
        if (_state.value.isRunning) {
            Log.w(TAG, "动态批测已在运行中")
            return
        }
        
        if (mainActivity.isFinishing || mainActivity.isDestroyed) {
            Log.e(TAG, "MainActivity已销毁，无法启动动态批测")
            return
        }
        
        if (!chatFragment.isAdded || chatFragment.view == null) {
            Log.e(TAG, "ChatFragment未就绪，无法启动动态批测")
            return
        }
        
        currentTestId = "dynamic_${System.currentTimeMillis()}"
        _state.value = DynamicBatchTestState(
            isRunning = true,
            isPaused = false,
            currentIndex = 0,
            successCount = 0,
            failCount = 0,
            currentQuery = null
        )
        
        withContext(Dispatchers.Main) {
            if (chatFragment.isAdded && chatFragment.view != null) {
                chatFragment.addChatMessage("系统", "🚀 动态批测开始", skipSystemMessageContainer = false)
            }
        }
        
        val testScope = mainActivity.testScope
        if (testScope == null || !testScope.coroutineContext.isActive) {
            Log.e(TAG, "testScope不可用，无法启动动态批测")
            _state.value = _state.value.copy(isRunning = false)
            return
        }
        
        job = testScope.launch {
            try {
                executeLoop()
            } catch (e: Exception) {
                Log.e(TAG, "动态批测执行异常: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    if (chatFragment.isAdded && chatFragment.view != null) {
                        chatFragment.addChatMessage("系统", "❌ 动态批测异常: ${e.message}")
                    }
                }
                stop()
            }
        }
    }
    
    /**
     * 执行循环：每次获取第一个任务并执行
     */
    private suspend fun executeLoop() = withContext(Dispatchers.IO) {
        val apiService = NetworkService.getApiService()
        if (apiService == null) {
            Log.e(TAG, "apiService为null")
            withContext(Dispatchers.Main) {
                if (chatFragment.isAdded && chatFragment.view != null) {
                    chatFragment.addChatMessage("系统", "❌ 动态批测失败：网络服务未初始化")
                }
            }
            stop()
            return@withContext
        }
        
        while (_state.value.isRunning) {
            // 检查是否暂停
            while (_state.value.isPaused && _state.value.isRunning) {
                delay(500)
            }
            
            if (!_state.value.isRunning) {
                break
            }
            
            // 从云侧获取第一个任务
            val response: Response<FirstTaskResponse> = try {
                apiService.getFirstTask()
            } catch (e: Exception) {
                Log.e(TAG, "获取第一个任务失败: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    if (chatFragment.isAdded && chatFragment.view != null) {
                        chatFragment.addChatMessage("系统", "❌ 获取任务失败: ${e.message}")
                    }
                }
                delay(5000) // 等待5秒后重试
                continue
            }
            
            if (!response.isSuccessful || response.body() == null) {
                Log.w(TAG, "获取第一个任务失败: ${response.code()}")
                withContext(Dispatchers.Main) {
                    if (chatFragment.isAdded && chatFragment.view != null) {
                        chatFragment.addChatMessage("系统", "❌ 获取任务失败: HTTP ${response.code()}")
                    }
                }
                delay(5000)
                continue
            }
            
            val taskResponse = response.body()!!
            val query = taskResponse.query
            
            if (query == null) {
                Log.w(TAG, "任务列表为空，动态批测结束")
                withContext(Dispatchers.Main) {
                    if (chatFragment.isAdded && chatFragment.view != null) {
                        chatFragment.addChatMessage("系统", "✅ 任务列表为空，动态批测结束")
                    }
                }
                stop()
                break
            }
            
            val currentIndex = _state.value.currentIndex + 1
            _state.value = _state.value.copy(
                currentIndex = currentIndex,
                currentQuery = query
            )
            
            // 显示当前任务
            withContext(Dispatchers.Main) {
                if (chatFragment.isAdded && chatFragment.view != null) {
                    chatFragment.addChatMessage("我", "[动态批测 $currentIndex] $query")
                }
            }
            
            // 执行任务
            val result = executeSingleQuery(query, currentIndex)
            
            if (result.success) {
                val newSuccessCount = _state.value.successCount + 1
                _state.value = _state.value.copy(successCount = newSuccessCount)
            } else {
                val newFailCount = _state.value.failCount + 1
                _state.value = _state.value.copy(failCount = newFailCount)
                withContext(Dispatchers.Main) {
                    if (chatFragment.isAdded && chatFragment.view != null) {
                        chatFragment.addChatMessage("系统", "❌ 任务 $currentIndex 执行失败: ${result.error}")
                    }
                }
            }
            
            // 等待一段时间再执行下一个
            delay(2000)
        }
        
        // 显示完成消息
        val finalState = _state.value
        withContext(Dispatchers.Main) {
            if (chatFragment.isAdded && chatFragment.view != null) {
                val message = "✅ 动态批测完成：成功 ${finalState.successCount}，失败 ${finalState.failCount}，总计 ${finalState.currentIndex}"
                chatFragment.addChatMessage("系统", message)
            }
        }
        
        stop()
    }
    
    /**
     * 执行单个查询（复用BatchTestController的逻辑）
     */
    private suspend fun executeSingleQuery(query: String, index: Int): QueryResult = withContext(Dispatchers.IO) {
        if (!chatFragment.isAdded) {
            return@withContext QueryResult(success = false, answer = null, error = "Fragment未就绪")
        }
        
        val answerDeferred = CompletableDeferred<String?>()
        val errorDeferred = CompletableDeferred<String?>()
        
        val tempListener = object : ChatFragment.MessageListener {
            override fun onAnswerReceived(answer: String) {
                if (!answerDeferred.isCompleted) {
                    answerDeferred.complete(answer)
                }
                try {
                    if (chatFragment.isAdded) {
                        chatFragment.removeMessageListener(this)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "移除监听器异常: ${e.message}", e)
                }
            }
            
            override fun onTaskComplete() {
                if (!answerDeferred.isCompleted) {
                    answerDeferred.complete(null)
                }
                try {
                    if (chatFragment.isAdded) {
                        chatFragment.removeMessageListener(this)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "移除监听器异常: ${e.message}", e)
                }
            }
            
            override fun onTaskError(error: String) {
                if (!errorDeferred.isCompleted) {
                    errorDeferred.complete(error)
                }
                try {
                    if (chatFragment.isAdded) {
                        chatFragment.removeMessageListener(this)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "移除监听器异常: ${e.message}", e)
                }
            }
        }
        
        try {
            chatFragment.addMessageListener(tempListener)
        } catch (e: Exception) {
            Log.e(TAG, "添加监听器异常: ${e.message}", e)
            return@withContext QueryResult(success = false, answer = null, error = "添加监听器失败: ${e.message}")
        }
        
        try {
            val canSend = withContext(Dispatchers.Main) {
                if (!chatFragment.isAdded || chatFragment.view == null) {
                    false
                } else {
                    try {
                        chatFragment.sendMessageForBatchTest(query, currentTestId)
                        true
                    } catch (e: Exception) {
                        Log.e(TAG, "发送消息异常: ${e.message}", e)
                        false
                    }
                }
            }
            
            if (!canSend) {
                try {
                    chatFragment.removeMessageListener(tempListener)
                } catch (e: Exception) {
                    Log.e(TAG, "移除监听器异常: ${e.message}", e)
                }
                return@withContext QueryResult(success = false, answer = null, error = "Fragment状态无效")
            }
            
            val answer = try {
                withTimeoutOrNull(DEFAULT_QUERY_TIMEOUT) {
                    select<String?> {
                        answerDeferred.onAwait { it }
                        errorDeferred.onAwait { null }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "等待answer异常: ${e.message}", e)
                null
            }
            
            val error = if (errorDeferred.isCompleted) {
                try {
                    errorDeferred.getCompleted()
                } catch (e: Exception) {
                    null
                }
            } else {
                null
            }
            
            if (error != null) {
                return@withContext QueryResult(success = false, answer = null, error = error)
            }
            
            if (answer != null) {
                return@withContext QueryResult(success = true, answer = answer, error = null)
            } else {
                val errorMsg = "查询超时（${DEFAULT_QUERY_TIMEOUT / 1000}秒）"
                return@withContext QueryResult(success = false, answer = null, error = errorMsg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行查询异常: ${e.message}", e)
            try {
                if (chatFragment.isAdded) {
                    chatFragment.removeMessageListener(tempListener)
                }
            } catch (ex: Exception) {
                Log.e(TAG, "移除监听器异常: ${ex.message}", ex)
            }
            return@withContext QueryResult(success = false, answer = null, error = e.message)
        }
    }
    
    /**
     * 停止动态批测
     */
    fun stop() {
        Log.d(TAG, "停止动态批测")
        job?.cancel()
        job = null
        _state.value = _state.value.copy(isRunning = false, isPaused = false, currentQuery = null)
    }
    
    /**
     * 暂停动态批测
     */
    suspend fun pause() {
        if (!_state.value.isRunning || _state.value.isPaused) {
            return
        }
        Log.d(TAG, "暂停动态批测")
        _state.value = _state.value.copy(isPaused = true)
        withContext(Dispatchers.Main) {
            if (chatFragment.isAdded && chatFragment.view != null) {
                chatFragment.addChatMessage("系统", "⏸️ 动态批测已暂停")
            }
        }
    }
    
    /**
     * 继续动态批测
     */
    suspend fun resume() {
        if (!_state.value.isRunning || !_state.value.isPaused) {
            return
        }
        Log.d(TAG, "继续动态批测")
        _state.value = _state.value.copy(isPaused = false)
        withContext(Dispatchers.Main) {
            if (chatFragment.isAdded && chatFragment.view != null) {
                chatFragment.addChatMessage("系统", "▶️ 动态批测已继续")
            }
        }
    }
    
    /**
     * 查询结果
     */
    private data class QueryResult(
        val success: Boolean,
        val answer: String?,
        val error: String?
    )
}

