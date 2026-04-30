package com.cloudcontrol.demo

import android.util.Log
import com.cloudcontrol.demo.ChatFragment
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.selects.select

/**
 * 批测控制器
 * 负责管理批测流程、状态和消息展示
 */
class BatchTestController(
    private val mainActivity: MainActivity,
    private val chatFragment: ChatFragment
) {
    companion object {
        private const val TAG = "BatchTestController"
        private const val DEFAULT_QUERY_TIMEOUT = 1800000L // 3分钟超时
    }
    
    // 批测状态
    data class BatchTestState(
        val isRunning: Boolean = false,
        val isPaused: Boolean = false,
        val currentIndex: Int = 0,
        val totalCount: Int = 0,
        val successCount: Int = 0,
        val failCount: Int = 0,
        val currentQuery: String? = null
    )
    
    private val _batchTestState = MutableStateFlow(BatchTestState())
    val batchTestState: StateFlow<BatchTestState> = _batchTestState
    
    private var batchTestJob: Job? = null
    private var currentBatchTestId: String? = null
    private var messageListener: ChatFragment.MessageListener? = null
    
    // 批测监听器
    interface BatchTestListener {
        fun onQueryStart(index: Int, query: String)
        fun onAnswerReceived(index: Int, query: String, answer: String)
        fun onQueryComplete(index: Int, success: Boolean, error: String? = null)
        fun onBatchTestComplete(successCount: Int, failCount: Int, totalCount: Int)
    }
    
    private var batchTestListener: BatchTestListener? = null
    
    /**
     * 设置批测监听器
     */
    fun setBatchTestListener(listener: BatchTestListener?) {
        batchTestListener = listener
    }
    
    /**
     * 开始批测
     * @param queries 查询列表
     * @param batchTestId 批测ID（可选，用于标识批测会话）
     */
    suspend fun startBatchTest(queries: List<String>, batchTestId: String? = null) {
        Log.d(TAG, "========== BatchTestController.startBatchTest 开始执行 ==========")
        Log.d(TAG, "startBatchTest: queries数量=${queries.size}, batchTestId=$batchTestId")
        
        try {
            if (_batchTestState.value.isRunning) {
                Log.w(TAG, "startBatchTest: 批测已在运行中")
                return
            }
            
            if (queries.isEmpty()) {
                Log.w(TAG, "startBatchTest: 查询列表为空")
                return
            }
            
            // 检查MainActivity状态
            Log.d(TAG, "startBatchTest: 检查MainActivity状态 - isFinishing=${mainActivity.isFinishing}, isDestroyed=${mainActivity.isDestroyed}")
            if (mainActivity.isFinishing || mainActivity.isDestroyed) {
                Log.e(TAG, "startBatchTest: MainActivity已销毁，无法启动批测")
                _batchTestState.value = _batchTestState.value.copy(isRunning = false)
                mainActivity.isBatchTesting = false
                return
            }
            
            currentBatchTestId = batchTestId ?: "batch_${System.currentTimeMillis()}"
            Log.d(TAG, "startBatchTest: 批测ID=$currentBatchTestId")
            
            // 更新状态
            _batchTestState.value = BatchTestState(
                isRunning = true,
                isPaused = false,
                currentIndex = 0,
                totalCount = queries.size,
                successCount = 0,
                failCount = 0,
                currentQuery = null
            )
            Log.d(TAG, "startBatchTest: 状态已更新")
            
            // 检查Fragment状态
            Log.d(TAG, "startBatchTest: 检查Fragment状态 - isAdded=${chatFragment.isAdded}, view=${chatFragment.view}")
            if (!chatFragment.isAdded || chatFragment.view == null) {
                Log.e(TAG, "startBatchTest: Fragment未就绪，无法启动批测")
                // 不抛出异常，而是记录错误并返回，避免崩溃
                // 调用者应该已经检查过Fragment状态，这里只是双重保险
                Log.e(TAG, "startBatchTest: ChatFragment未就绪，view未创建完成，停止批测")
                _batchTestState.value = _batchTestState.value.copy(isRunning = false)
                mainActivity.isBatchTesting = false
                return
            }
            
            // 注册消息监听器
            Log.d(TAG, "startBatchTest: 准备注册消息监听器")
            try {
                registerMessageListener()
                Log.d(TAG, "startBatchTest: 消息监听器注册成功")
            } catch (e: Exception) {
                Log.e(TAG, "startBatchTest: 注册消息监听器异常: ${e.message}", e)
                e.printStackTrace()
                // 不中断批测，继续执行
            }
        } catch (e: Exception) {
            Log.e(TAG, "startBatchTest: 初始化异常: ${e.message}", e)
            e.printStackTrace()
            _batchTestState.value = _batchTestState.value.copy(isRunning = false)
            mainActivity.isBatchTesting = false
            return
        }
        
        // 在聊天界面显示批测开始消息
        Log.d(TAG, "startBatchTest: 准备显示批测开始消息")
        try {
            withContext(Dispatchers.Main) {
                if (chatFragment.isAdded && chatFragment.view != null) {
                    Log.d(TAG, "startBatchTest: Fragment状态正常，显示批测开始消息")
                    try {
                        postChatMessage(
                            "系统",
                            "🚀 批测开始：共 ${queries.size} 个查询",
                            skipSystemMessageContainer = false
                        )
                        Log.d(TAG, "startBatchTest: 批测开始消息已显示")
                    } catch (e: Exception) {
                        Log.e(TAG, "startBatchTest: 添加消息异常: ${e.message}", e)
                        e.printStackTrace()
                    }
                } else {
                    Log.w(TAG, "startBatchTest: Fragment状态无效，跳过显示批测开始消息 - isAdded=${chatFragment.isAdded}, view=${chatFragment.view}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startBatchTest: 显示批测开始消息异常: ${e.message}", e)
            e.printStackTrace()
            // 不抛出异常，继续执行
        }
        
        // 启动批测协程
        Log.d(TAG, "startBatchTest: 准备启动批测协程")
        try {
            // 检查testScope是否可用
            val testScope = mainActivity.testScope
            Log.d(TAG, "startBatchTest: testScope=$testScope")
            if (testScope == null) {
                Log.e(TAG, "startBatchTest: testScope为null，无法启动批测")
                _batchTestState.value = _batchTestState.value.copy(isRunning = false)
                mainActivity.isBatchTesting = false
                return
            }
            
            if (!testScope.coroutineContext.isActive) {
                Log.e(TAG, "startBatchTest: testScope不可用，无法启动批测")
                _batchTestState.value = _batchTestState.value.copy(isRunning = false)
                mainActivity.isBatchTesting = false
                return
            }
            
            Log.d(TAG, "startBatchTest: testScope检查通过，启动批测协程")
            batchTestJob = testScope.launch {
                try {
                    Log.d(TAG, "startBatchTest: 批测协程已启动，开始执行查询")
                    executeBatchQueries(queries)
                    Log.d(TAG, "startBatchTest: 批测协程执行完成")
                } catch (e: Exception) {
                    Log.e(TAG, "startBatchTest: 批测执行异常: ${e.message}", e)
                    e.printStackTrace()
                    try {
                        withContext(Dispatchers.Main) {
                            if (chatFragment.isAdded && chatFragment.view != null) {
                                try {
                                    postChatMessage("系统", "❌ 批测异常: ${e.message}")
                                } catch (ex: Exception) {
                                    Log.e(TAG, "startBatchTest: 添加异常消息失败: ${ex.message}", ex)
                                }
                            }
                        }
                    } catch (ex: Exception) {
                        Log.e(TAG, "startBatchTest: 显示批测异常消息失败: ${ex.message}", ex)
                        ex.printStackTrace()
                    }
                    try {
                        stopBatchTest()
                    } catch (stopEx: Exception) {
                        Log.e(TAG, "startBatchTest: 停止批测异常: ${stopEx.message}", stopEx)
                        stopEx.printStackTrace()
                    }
                }
            }
            Log.d(TAG, "startBatchTest: 批测协程已创建，job=$batchTestJob")
        } catch (e: Exception) {
            Log.e(TAG, "startBatchTest: 启动批测协程异常: ${e.message}", e)
            e.printStackTrace()
            // 不抛出异常，而是重置状态并记录错误
            _batchTestState.value = _batchTestState.value.copy(isRunning = false)
            mainActivity.isBatchTesting = false
            try {
                withContext(Dispatchers.Main) {
                    if (chatFragment.isAdded && chatFragment.view != null) {
                        try {
                            postChatMessage("系统", "❌ 批测启动失败: ${e.message}")
                        } catch (ex: Exception) {
                            Log.e(TAG, "startBatchTest: 添加启动失败消息异常: ${ex.message}", ex)
                        }
                    }
                }
            } catch (ex: Exception) {
                Log.e(TAG, "startBatchTest: 显示批测启动失败消息异常: ${ex.message}", ex)
                ex.printStackTrace()
            }
        }
        
        Log.d(TAG, "========== BatchTestController.startBatchTest 执行完成 ==========")
    }
    
    /**
     * 执行批测查询列表
     */
    private suspend fun executeBatchQueries(queries: List<String>) = withContext(Dispatchers.IO) {
        val totalQueries = queries.size
        var successCount = 0
        var failCount = 0
        
        for ((index, query) in queries.withIndex()) {
            // 检查是否被中断
            if (!_batchTestState.value.isRunning) {
                Log.d(TAG, "批测被中断，已执行 ${index}/${totalQueries}")
                break
            }
            
            // 检查是否暂停
            while (_batchTestState.value.isPaused && _batchTestState.value.isRunning) {
                delay(500)
            }
            
            if (!_batchTestState.value.isRunning) {
                break
            }
            
            val currentIndex = index + 1
            
            // 更新状态
            _batchTestState.value = _batchTestState.value.copy(
                currentIndex = currentIndex,
                currentQuery = query
            )
            
            // 在聊天界面显示查询（用户消息格式）
            try {
                withContext(Dispatchers.Main) {
                    if (chatFragment.isAdded && chatFragment.view != null) {
                        postChatMessage(
                            "我",
                            "[批测 $currentIndex/$totalQueries] $query"
                        )
                    } else {
                        Log.w(TAG, "executeBatchQueries: Fragment状态无效，跳过显示查询消息")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "显示查询消息异常: ${e.message}", e)
                // 不中断批测，继续执行
            }
            
            // 通知监听器
            batchTestListener?.onQueryStart(currentIndex, query)
            
            try {
                // 执行查询
                val result = executeSingleQuery(query, currentIndex, totalQueries)
                
                if (result.success) {
                    successCount++
                    _batchTestState.value = _batchTestState.value.copy(
                        successCount = successCount
                    )
                    batchTestListener?.onQueryComplete(currentIndex, true)
                } else {
                    failCount++
                    _batchTestState.value = _batchTestState.value.copy(
                        failCount = failCount
                    )
                    batchTestListener?.onQueryComplete(currentIndex, false, result.error)
                }
            } catch (e: Exception) {
                Log.e(TAG, "执行查询异常: ${e.message}", e)
                failCount++
                _batchTestState.value = _batchTestState.value.copy(
                    failCount = failCount
                )
                
                try {
                    withContext(Dispatchers.Main) {
                        if (chatFragment.isAdded && chatFragment.view != null) {
                            postChatMessage(
                                "系统",
                                "❌ 查询 $currentIndex 执行异常: ${e.message}"
                            )
                        }
                    }
                } catch (ex: Exception) {
                    Log.e(TAG, "显示查询异常消息失败: ${ex.message}", ex)
                }
                
                batchTestListener?.onQueryComplete(currentIndex, false, e.message)
            }
            
            // 等待一段时间再执行下一个（给系统一些时间）
            delay(2000)
        }
        
        // 批测完成
        val finalState = _batchTestState.value
        try {
            withContext(Dispatchers.Main) {
                if (chatFragment.isAdded && chatFragment.view != null) {
                    val message = "✅ 批测完成：成功 $successCount，失败 $failCount，总计 $totalQueries"
                    postChatMessage("系统", message)
                } else {
                    Log.w(TAG, "executeBatchQueries: Fragment状态无效，跳过显示完成消息")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "显示批测完成消息异常: ${e.message}", e)
        }
        
        batchTestListener?.onBatchTestComplete(successCount, failCount, totalQueries)
        
        // 停止批测
        stopBatchTest()
    }
    
    /**
     * 执行单个查询
     * @return 执行结果
     */
    private suspend fun executeSingleQuery(
        query: String,
        index: Int,
        totalCount: Int
    ): QueryResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "========== executeSingleQuery 开始执行 ==========")
        Log.d(TAG, "executeSingleQuery: 查询 $index/$totalCount: $query")
        Log.d(TAG, "executeSingleQuery: Fragment状态 - isAdded=${chatFragment.isAdded}, view=${chatFragment.view}")
        
        // 检查Fragment状态
        if (!chatFragment.isAdded) {
            Log.e(TAG, "executeSingleQuery: Fragment未添加，无法执行查询")
            return@withContext QueryResult(success = false, answer = null, error = "Fragment未就绪")
        }
        
        // 使用CompletableDeferred来等待answer消息
        val answerDeferred = CompletableDeferred<String?>()
        val errorDeferred = CompletableDeferred<String?>()
        
        Log.d(TAG, "executeSingleQuery: 创建临时监听器")
        // 临时监听器，用于接收这个查询的answer
        val tempListener = object : ChatFragment.MessageListener {
                override fun onAnswerReceived(answer: String) {
                    Log.d(TAG, "收到answer: $answer")
                    if (!answerDeferred.isCompleted) {
                        answerDeferred.complete(answer)
                    }
                    // 移除临时监听器
                    try {
                        if (chatFragment.isAdded) {
                            chatFragment.removeMessageListener(this)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "移除临时监听器异常: ${e.message}", e)
                    }
                }
                
                override fun onTaskComplete() {
                    // 任务完成，但没有answer，也算成功
                    if (!answerDeferred.isCompleted) {
                        answerDeferred.complete(null)
                    }
                    try {
                        if (chatFragment.isAdded) {
                            chatFragment.removeMessageListener(this)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "移除临时监听器异常: ${e.message}", e)
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
                        Log.e(TAG, "移除临时监听器异常: ${e.message}", e)
                    }
                }
            }
            
        Log.d(TAG, "executeSingleQuery: 准备添加临时监听器")
        try {
            chatFragment.addMessageListener(tempListener)
            Log.d(TAG, "executeSingleQuery: 临时监听器已添加")
        } catch (e: Exception) {
            Log.e(TAG, "executeSingleQuery: 添加临时监听器异常: ${e.message}", e)
            e.printStackTrace()
            return@withContext QueryResult(success = false, answer = null, error = "添加监听器失败: ${e.message}")
        }
        
        try {
            // 调用ChatFragment的发送方法
            Log.d(TAG, "executeSingleQuery: 准备发送消息")
            Log.d(TAG, "executeSingleQuery: 检查Fragment状态 - isAdded=${chatFragment.isAdded}, view=${chatFragment.view}")
            
            // 先检查Fragment状态
            if (!chatFragment.isAdded) {
                Log.e(TAG, "executeSingleQuery: Fragment未添加，无法发送消息")
                return@withContext QueryResult(success = false, answer = null, error = "Fragment未添加")
            }
            
            if (chatFragment.view == null) {
                Log.e(TAG, "executeSingleQuery: Fragment view为null，无法发送消息")
                return@withContext QueryResult(success = false, answer = null, error = "Fragment view为null")
            }
            
            val canSend = try {
                withContext(Dispatchers.Main) {
                    // 再次检查Fragment状态（可能在切换线程时状态改变）
                    if (!chatFragment.isAdded || chatFragment.view == null) {
                        Log.e(TAG, "executeSingleQuery: 切换到主线程后Fragment状态无效 - isAdded=${chatFragment.isAdded}, view=${chatFragment.view}")
                        false
                    } else {
                        Log.d(TAG, "executeSingleQuery: Fragment状态正常，调用sendMessageForBatchTest")
                        try {
                            if (sendMessageForBatchTestCompat(query, currentBatchTestId)) {
                                Log.d(TAG, "executeSingleQuery: sendMessageForBatchTest调用成功")
                                true
                            } else {
                                Log.e(TAG, "executeSingleQuery: sendMessageForBatchTest调用失败")
                                false
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "executeSingleQuery: sendMessageForBatchTest异常: ${e.message}", e)
                            e.printStackTrace()
                            false
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "executeSingleQuery: 切换到主线程异常: ${e.message}", e)
                e.printStackTrace()
                false
            }
            
            // 如果无法发送消息，清理监听器并返回错误
            if (!canSend) {
                try {
                    chatFragment.removeMessageListener(tempListener)
                } catch (e: Exception) {
                    Log.e(TAG, "移除监听器异常: ${e.message}", e)
                }
                return@withContext QueryResult(success = false, answer = null, error = "Fragment状态无效")
            }
            
            // 等待answer或超时
            val timeout = DEFAULT_QUERY_TIMEOUT
            Log.d(TAG, "executeSingleQuery: 开始等待answer，超时时间=${timeout}ms")
            val answer = try {
                withTimeoutOrNull(timeout) {
                    select<String?> {
                        answerDeferred.onAwait { 
                            Log.d(TAG, "executeSingleQuery: 收到answer")
                            it 
                        }
                        errorDeferred.onAwait { 
                            Log.d(TAG, "executeSingleQuery: 收到error")
                            null // 错误时返回null，由errorDeferred处理
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "executeSingleQuery: 等待answer超时")
                null
            } catch (e: Exception) {
                Log.e(TAG, "executeSingleQuery: 等待answer异常: ${e.message}", e)
                e.printStackTrace()
                null
            }
            
            Log.d(TAG, "executeSingleQuery: answer等待完成，answer=${answer != null}")
            
            // 检查是否有错误（先检查错误，因为错误优先级更高）
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
                // answer已经在ChatFragment中显示了，这里只需要通知监听器
                batchTestListener?.onAnswerReceived(index, query, answer)
                return@withContext QueryResult(success = true, answer = answer, error = null)
            } else {
                // 超时或没有answer
                val errorMsg = "查询超时（${timeout / 1000}秒）"
                try {
                    withContext(Dispatchers.Main) {
                        if (chatFragment.isAdded && chatFragment.view != null) {
                            postChatMessage("系统", "⏱️ $errorMsg")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "显示超时消息异常: ${e.message}", e)
                }
                return@withContext QueryResult(success = false, answer = null, error = errorMsg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeSingleQuery: 执行查询异常: ${e.message}", e)
            e.printStackTrace()
            // 确保移除临时监听器
            try {
                if (chatFragment.isAdded) {
                    chatFragment.removeMessageListener(tempListener)
                    Log.d(TAG, "executeSingleQuery: 临时监听器已移除")
                } else {
                    Log.w(TAG, "executeSingleQuery: Fragment未添加，跳过移除监听器")
                }
            } catch (ex: Exception) {
                Log.e(TAG, "executeSingleQuery: 移除监听器异常: ${ex.message}", ex)
                ex.printStackTrace()
            }
            return@withContext QueryResult(success = false, answer = null, error = e.message)
        } finally {
            Log.d(TAG, "========== executeSingleQuery 执行完成 ==========")
        }
    }
    
    /**
     * 注册消息监听器
     */
    private fun registerMessageListener() {
        try {
            // 检查Fragment状态
            if (!chatFragment.isAdded) {
                Log.w(TAG, "registerMessageListener: Fragment未添加，跳过注册")
                return
            }
            
            messageListener = object : ChatFragment.MessageListener {
                override fun onAnswerReceived(answer: String) {
                    // 这个监听器主要用于全局监听，单个查询的监听在executeSingleQuery中处理
                    Log.d(TAG, "全局监听器收到answer: $answer")
                }
                
                override fun onTaskComplete() {
                    Log.d(TAG, "全局监听器：任务完成")
                }
                
                override fun onTaskError(error: String) {
                    Log.d(TAG, "全局监听器：任务错误: $error")
                }
            }
            
            chatFragment.addMessageListener(messageListener!!)
        } catch (e: Exception) {
            Log.e(TAG, "注册消息监听器异常: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 停止批测
     * 强制停止批测，无论当前状态如何
     */
    fun stopBatchTest() {
        Log.d(TAG, "========== BatchTestController.stopBatchTest 开始执行 ==========")
        
        val wasRunning = _batchTestState.value.isRunning
        val currentState = _batchTestState.value
        Log.d(TAG, "停止批测: 当前状态 - isRunning=$wasRunning, isPaused=${currentState.isPaused}, batchTestJob=$batchTestJob")
        
        if (!wasRunning) {
            Log.d(TAG, "停止批测: 批测未运行，但继续执行清理操作")
        } else {
            Log.d(TAG, "停止批测: 开始停止")
        }
        
        // 强制取消批测协程（无论状态如何）
        try {
            val job = batchTestJob
            if (job != null) {
                Log.d(TAG, "停止批测: 取消 batchTestJob，job.isActive=${job.isActive}, job.isCancelled=${job.isCancelled}")
                job.cancel()
                Log.d(TAG, "停止批测: batchTestJob 已取消")
            } else {
                Log.w(TAG, "停止批测: batchTestJob 为 null，跳过")
            }
            batchTestJob = null
        } catch (e: Exception) {
            Log.e(TAG, "停止批测: 取消协程异常: ${e.message}", e)
            e.printStackTrace()
        }
        
        // 移除消息监听器
        try {
            if (chatFragment.isAdded) {
                messageListener?.let {
                    Log.d(TAG, "停止批测: 移除消息监听器")
                    chatFragment.removeMessageListener(it)
                    Log.d(TAG, "停止批测: 消息监听器已移除")
                } ?: run {
                    Log.w(TAG, "停止批测: messageListener 为 null，跳过")
                }
            } else {
                Log.w(TAG, "停止批测: chatFragment 未添加，跳过移除监听器")
            }
        } catch (e: Exception) {
            Log.e(TAG, "移除消息监听器异常: ${e.message}", e)
            e.printStackTrace()
        }
        messageListener = null
        
        // 强制更新状态（无论之前状态如何）
        _batchTestState.value = _batchTestState.value.copy(
            isRunning = false,
            isPaused = false,
            currentQuery = null
        )
        Log.d(TAG, "停止批测: 状态已更新 - isRunning=${_batchTestState.value.isRunning}")
        
        // 强制重置MainActivity的批测状态
        mainActivity.isBatchTesting = false
        Log.d(TAG, "停止批测: MainActivity.isBatchTesting 已设置为 false")
        
        Log.d(TAG, "========== BatchTestController.stopBatchTest 执行完成 ==========")
    }
    
    /**
     * 暂停批测
     */
    suspend fun pauseBatchTest() {
        if (!_batchTestState.value.isRunning || _batchTestState.value.isPaused) {
            return
        }
        
        Log.d(TAG, "暂停批测")
        _batchTestState.value = _batchTestState.value.copy(isPaused = true)
        
        withContext(Dispatchers.Main) {
            postChatMessage("系统", "⏸️ 批测已暂停")
        }
    }
    
    /**
     * 继续批测
     */
    suspend fun resumeBatchTest() {
        if (!_batchTestState.value.isRunning || !_batchTestState.value.isPaused) {
            return
        }
        
        Log.d(TAG, "继续批测")
        _batchTestState.value = _batchTestState.value.copy(isPaused = false)
        
        withContext(Dispatchers.Main) {
            postChatMessage("系统", "▶️ 批测已继续")
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

    private fun postChatMessage(
        sender: String,
        message: String,
        skipSystemMessageContainer: Boolean = false
    ) {
        try {
            val method = chatFragment.javaClass.getMethod(
                "addChatMessage",
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                List::class.java,
                java.lang.Long::class.java,
                String::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType
            )
            method.invoke(
                chatFragment,
                sender,
                message,
                false,
                false,
                skipSystemMessageContainer,
                null,
                null,
                null,
                null,
                false
            )
        } catch (e: Exception) {
            Log.e(TAG, "postChatMessage failed: ${e.message}", e)
        }
    }

    private fun sendMessageForBatchTestCompat(query: String, batchTestId: String?): Boolean {
        return try {
            val method = chatFragment.javaClass.getMethod(
                "sendMessageForBatchTest",
                String::class.java,
                String::class.java
            )
            method.invoke(chatFragment, query, batchTestId)
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendMessageForBatchTestCompat failed: ${e.message}", e)
            false
        }
    }
}

