package com.cloudcontrol.demo

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.cloudcontrol.demo.databinding.FragmentTestBinding
import kotlinx.coroutines.*

/**
 * 测试Fragment
 * 负责测试功能的执行
 */
class TestFragment : Fragment() {
    
    companion object {
        private const val TAG = "TestFragment"
    }
    
    private var _binding: FragmentTestBinding? = null
    private val binding get() = _binding!!
    
    private var isTestRunning = false
    private val testScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTestBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 立即隐藏ActionBar（测试页面有自己的标题栏）
        (activity as? MainActivity)?.let { mainActivity ->
            if (mainActivity.supportActionBar?.isShowing == true) {
                mainActivity.hideActionBarInstantly()
            }
        }
        
        setupUI()
    }
    
    private fun setupUI() {
        // 执行测试按钮
        binding.btnExecuteTest.setOnClickListener {
            executeTest()
        }
        
        // 长按执行测试按钮可以查看已安装应用列表（用于调试）
        binding.btnExecuteTest.setOnLongClickListener {
            (activity as? MainActivity)?.showInstalledApps()
            true
        }
        
        // 批测按钮
        binding.btnStartBatchTest.setOnClickListener {
            startBatchTest()
        }
        
        // 结束批测按钮
        binding.btnStopBatchTest.setOnClickListener {
            Log.d(TAG, "结束批测按钮被点击")
            val mainActivity = activity as? MainActivity
            mainActivity?.addLog("用户点击结束批测按钮")
            // 调用MainActivity的公共方法，确保即使Fragment被销毁也能停止批测
            mainActivity?.stopBatchTest()
            // 同时调用本地方法更新UI
            stopBatchTest()
        }
        
        // 动态批测按钮
        binding.btnStartDynamicBatchTest.setOnClickListener {
            startDynamicBatchTest()
        }
        
        // 结束动态批测按钮
        binding.btnStopDynamicBatchTest.setOnClickListener {
            Log.d(TAG, "结束动态批测按钮被点击")
            stopDynamicBatchTest()
        }
    }
    
    /**
     * 执行测试
     */
    private fun executeTest() {
        // 使用安全的context获取方式，避免Fragment未attached时崩溃
        val safeContext = context ?: activity
        if (safeContext == null) {
            Log.e(TAG, "executeTest: context和activity都为null，无法执行")
            return
        }
        
        if (isTestRunning) {
            Toast.makeText(safeContext, getString(R.string.test_running_wait), Toast.LENGTH_SHORT).show()
            return
        }
        
        val instructions = binding.etTestInstructions.text.toString().trim()
        if (instructions.isEmpty()) {
            Toast.makeText(safeContext, getString(R.string.enter_test_instructions), Toast.LENGTH_SHORT).show()
            return
        }
        
        val delayText = binding.etTestDelay.text.toString().trim()
        val delayMs = if (delayText.isEmpty()) 1000L else delayText.toLongOrNull() ?: 1000L
        
        if (delayMs < 0) {
            Toast.makeText(safeContext, getString(R.string.action_interval_negative), Toast.LENGTH_SHORT).show()
            return
        }
        
        // 检查无障碍服务
        val accessibilityService = MyAccessibilityService.getInstance()
        if (accessibilityService == null) {
            Toast.makeText(safeContext, getString(R.string.accessibility_not_connected), Toast.LENGTH_SHORT).show()
            return
        }
        
        isTestRunning = true
        binding.btnExecuteTest.isEnabled = false
        binding.btnExecuteTest.text = getString(R.string.test_running)
        
        // 清空日志
        (activity as? MainActivity)?.clearLog()
        (activity as? MainActivity)?.addLog("开始解析测试指令...")
        
        // 解析指令
        val actions = (activity as? MainActivity)?.parseTestInstructions(instructions) ?: emptyList()
        
        if (actions.isEmpty()) {
            (activity as? MainActivity)?.addLog("错误：未解析到任何动作，请检查指令格式")
            isTestRunning = false
            binding.btnExecuteTest.isEnabled = true
            binding.btnExecuteTest.text = getString(R.string.execute_test)
            context?.let {
                Toast.makeText(it, it.getString(R.string.no_actions_parsed), Toast.LENGTH_SHORT).show()
            }
            return
        }
        
        (activity as? MainActivity)?.addLog("解析到 ${actions.size} 个动作，开始执行...")
        
        // 执行动作
        testScope.launch {
            try {
                (activity as? MainActivity)?.executeActions(actions, delayMs)
            } catch (e: Exception) {
                Log.e(TAG, "执行测试异常: ${e.message}", e)
                (activity as? MainActivity)?.addLog("错误: ${e.message}")
            } finally {
                isTestRunning = false
                activity?.runOnUiThread {
                    binding.btnExecuteTest.isEnabled = true
                    binding.btnExecuteTest.text = getString(R.string.execute_test)
                }
            }
        }
    }
    
    // 批测控制器
    private var batchTestController: BatchTestController? = null
    
    // 动态批测控制器
    private var dynamicBatchTestController: DynamicBatchTestController? = null
    
    /**
     * 启动批测
     */
    private fun startBatchTest() {
        Log.d(TAG, "========== startBatchTest 开始执行 ==========")
        Log.d(TAG, "startBatchTest: Fragment状态 - isAdded=$isAdded, isDetached=$isDetached, view=$view")
        
        try {
            val mainActivity = activity as? MainActivity
            if (mainActivity == null) {
                // 使用context而不是requireContext，避免Fragment未attached时崩溃
                Log.e(TAG, "startBatchTest: MainActivity为null, context=$context, activity=$activity")
                try {
                    context?.let {
                        Toast.makeText(it, it.getString(R.string.batch_test_main_activity_null), Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "startBatchTest: 显示Toast异常: ${e.message}", e)
                }
                return
            }
            
            Log.d(TAG, "startBatchTest: MainActivity已获取 - isFinishing=${mainActivity.isFinishing}, isDestroyed=${mainActivity.isDestroyed}")
            Log.d(TAG, "startBatchTest: 检查批测状态 - isBatchTesting=${mainActivity.isBatchTesting}, batchTestJob=${mainActivity.batchTestJob}")
            
            if (mainActivity.isBatchTesting) {
                // 使用mainActivity作为context，因为已经确认mainActivity不为null
                Log.w(TAG, "startBatchTest: 批测正在进行中，无法再次启动")
                mainActivity.addLog("批测正在进行中，无法再次启动")
                try {
                    Toast.makeText(mainActivity, getString(R.string.batch_test_in_progress), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "startBatchTest: 显示Toast异常: ${e.message}", e)
                }
                return
            }
            
            // 额外检查：如果状态不一致，强制清理
            if (mainActivity.batchTestJob != null && mainActivity.batchTestJob!!.isActive) {
                Log.w(TAG, "startBatchTest: 发现状态不一致，batchTestJob 仍处于活动状态，强制清理")
                mainActivity.addLog("发现状态不一致，强制清理旧的批测任务")
                try {
                    mainActivity.batchTestJob?.cancel()
                    mainActivity.batchTestJob = null
                    mainActivity.isBatchTesting = false
                } catch (e: Exception) {
                    Log.e(TAG, "startBatchTest: 清理旧任务异常: ${e.message}", e)
                    e.printStackTrace()
                }
            }
            
            // 检查网络服务是否初始化
            val apiService = NetworkService.getApiService()
            if (apiService == null) {
                Log.e(TAG, "startBatchTest: 网络服务未初始化")
                // 使用mainActivity作为context
                try {
                    Toast.makeText(mainActivity, getString(R.string.network_not_initialized), Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e(TAG, "startBatchTest: 显示Toast异常: ${e.message}", e)
                }
                mainActivity.addLog("批测失败：网络服务未初始化")
                return
            }
            
            Log.d(TAG, "startBatchTest: 网络服务已就绪")
            
            // 检查binding是否可用，避免Fragment view被销毁时崩溃
            if (_binding == null) {
                Log.e(TAG, "startBatchTest: _binding为null，无法启动批测")
                try {
                    Toast.makeText(mainActivity, "界面未就绪，请稍后重试", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "startBatchTest: 显示Toast异常: ${e.message}", e)
                }
                mainActivity.addLog("批测失败：Fragment view未就绪 (_binding为null)")
                return
            }
            
            if (!isAdded) {
                Log.e(TAG, "startBatchTest: Fragment未添加，无法启动批测")
                try {
                    Toast.makeText(mainActivity, "界面未就绪，请稍后重试", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "startBatchTest: 显示Toast异常: ${e.message}", e)
                }
                mainActivity.addLog("批测失败：Fragment未添加")
                return
            }
            
            if (view == null) {
                Log.e(TAG, "startBatchTest: Fragment view为null，无法启动批测")
                try {
                    Toast.makeText(mainActivity, "界面未就绪，请稍后重试", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "startBatchTest: 显示Toast异常: ${e.message}", e)
                }
                mainActivity.addLog("批测失败：Fragment view为null")
                return
            }
            
            Log.d(TAG, "startBatchTest: Fragment状态检查通过，开始更新UI")
            
            mainActivity.isBatchTesting = true
            try {
                // 安全地更新UI，使用_binding而不是binding，避免空指针异常
                _binding?.let { safeBinding ->
                    safeBinding.btnStartBatchTest.isEnabled = false
                    safeBinding.btnStartBatchTest.text = getString(R.string.batch_test_running)
                    safeBinding.btnStopBatchTest.isEnabled = true
                    Log.d(TAG, "startBatchTest: UI更新成功")
                } ?: run {
                    Log.e(TAG, "startBatchTest: _binding为null，无法更新UI")
                }
            } catch (e: Exception) {
                Log.e(TAG, "startBatchTest: 更新UI异常: ${e.message}", e)
                e.printStackTrace()
                // 即使UI更新失败，也继续执行批测流程
            }
        
            mainActivity.addLog("开始批测：正在切换到聊天页面...")
            
            // 检查testScope是否可用
            val testScope = mainActivity.testScope
            if (testScope == null) {
                Log.e(TAG, "startBatchTest: testScope为null，无法启动批测")
                mainActivity.addLog("批测失败：协程作用域未初始化")
                try {
                    Toast.makeText(mainActivity, "批测失败：系统未就绪", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "startBatchTest: 显示Toast异常: ${e.message}", e)
                }
                mainActivity.isBatchTesting = false
                return
            }
            
            if (!testScope.coroutineContext.isActive) {
                Log.e(TAG, "startBatchTest: testScope不可用，无法启动批测")
                mainActivity.addLog("批测失败：协程作用域不可用")
                try {
                    Toast.makeText(mainActivity, "批测失败：系统未就绪", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "startBatchTest: 显示Toast异常: ${e.message}", e)
                }
                mainActivity.isBatchTesting = false
                return
            }
            
            Log.d(TAG, "startBatchTest: testScope检查通过，准备启动协程")
            
            // 在协程中执行批测（使用MainActivity的协程作用域，避免Fragment销毁导致停止）
            mainActivity.batchTestJob = testScope.launch {
                try {
                    Log.d(TAG, "批测协程开始执行")
                    mainActivity.addLog("批测：协程已启动")
                    
                    // 先切换到聊天页面，确保ChatFragment存在
                    mainActivity.addLog("批测：开始切换到聊天页面...")
                    val switchSuccess = try {
                        withContext(Dispatchers.Main) {
                            val act = activity as? MainActivity
                            if (act == null) {
                                Log.e(TAG, "批测：activity为null")
                                false
                            } else {
                                Log.d(TAG, "批测：调用switchToChatPage()，Activity状态 - isFinishing=${act.isFinishing}, isDestroyed=${act.isDestroyed}")
                                try {
                                    act.switchToChatPage()
                                } catch (e: Exception) {
                                    Log.e(TAG, "批测：switchToChatPage()异常: ${e.message}", e)
                                    e.printStackTrace()
                                    false
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "批测：切换页面异常: ${e.message}", e)
                        e.printStackTrace()
                        false
                    }
                    
                    Log.d(TAG, "批测：switchToChatPage返回=$switchSuccess")
                    mainActivity.addLog("批测：切换页面结果=$switchSuccess")
                    
                    if (!switchSuccess) {
                        Log.e(TAG, "批测：切换页面失败")
                        try {
                            withContext(Dispatchers.Main) {
                                try {
                                    context?.let {
                                        Toast.makeText(it, "批测失败：无法切换到聊天页面", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "批测：显示Toast异常: ${e.message}", e)
                                }
                                mainActivity.addLog("批测失败：无法切换到聊天页面")
                                stopBatchTest()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "批测：停止批测异常: ${e.message}", e)
                            e.printStackTrace()
                        }
                        return@launch
                    }
                
                // 等待Fragment切换完成，并轮询检查ChatFragment是否存在
                var chatFragment: ChatFragment? = null
                var retryCount = 0
                val maxRetries = 20 // 增加到20次，因为Fragment创建可能需要更长时间
                
                mainActivity.addLog("批测：等待ChatFragment创建...")
                Log.d(TAG, "批测：开始等待ChatFragment创建，maxRetries=$maxRetries")
                
                while (retryCount < maxRetries && mainActivity.isBatchTesting) {
                    delay(500) // 每次等待500ms，给Fragment更多时间创建
                    retryCount++
                    chatFragment = mainActivity.getChatFragment()
                    if (chatFragment != null) {
                        Log.d(TAG, "ChatFragment已找到，重试次数: $retryCount")
                        mainActivity.addLog("批测：ChatFragment已就绪（重试${retryCount}次，耗时${retryCount * 500}ms）")
                        break
                    }
                    if (retryCount % 2 == 0) { // 每2次重试记录一次日志，避免日志过多
                        Log.d(TAG, "ChatFragment未找到，重试 $retryCount/$maxRetries")
                        mainActivity.addLog("批测：等待ChatFragment... ($retryCount/$maxRetries)")
                    }
                }
                
                if (chatFragment == null) {
                    Log.e(TAG, "批测失败：ChatFragment未找到（已重试${maxRetries}次）")
                    withContext(Dispatchers.Main) {
                        context?.let {
                            Toast.makeText(it, "批测失败：ChatFragment未找到（已重试${maxRetries}次）", Toast.LENGTH_SHORT).show()
                        }
                        mainActivity.addLog("批测失败：ChatFragment未找到（已重试${maxRetries}次）")
                        stopBatchTest()
                    }
                    return@launch
                }
                
                // 确保Fragment的view已经创建完成
                if (!chatFragment.isAdded || chatFragment.view == null) {
                    Log.e(TAG, "批测失败：ChatFragment的view未创建完成")
                    mainActivity.addLog("批测失败：ChatFragment的view未创建完成")
                    withContext(Dispatchers.Main) {
                        context?.let {
                            Toast.makeText(it, "批测失败：界面未就绪，请稍后重试", Toast.LENGTH_SHORT).show()
                        }
                        stopBatchTest()
                    }
                    return@launch
                }
                
                    // 创建批测控制器
                    Log.d(TAG, "批测：准备创建BatchTestController")
                    try {
                        batchTestController = BatchTestController(mainActivity, chatFragment)
                        // 将控制器保存到MainActivity，以便即使Fragment被销毁也能停止批测
                        mainActivity.batchTestController = batchTestController
                        Log.d(TAG, "批测：已创建BatchTestController并保存到MainActivity")
                    } catch (e: Exception) {
                        Log.e(TAG, "批测：创建BatchTestController异常: ${e.message}", e)
                        e.printStackTrace()
                        mainActivity.addLog("批测失败：创建批测控制器异常: ${e.message}")
                        try {
                            withContext(Dispatchers.Main) {
                                try {
                                    context?.let {
                                        Toast.makeText(it, "批测失败：创建批测控制器异常", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (toastEx: Exception) {
                                    Log.e(TAG, "批测：显示Toast异常: ${toastEx.message}", toastEx)
                                }
                                stopBatchTest()
                            }
                        } catch (ex: Exception) {
                            Log.e(TAG, "批测：停止批测异常: ${ex.message}", ex)
                            ex.printStackTrace()
                        }
                        return@launch
                    }
                
                // 设置批测监听器（可选，用于日志记录）
                batchTestController?.setBatchTestListener(object : BatchTestController.BatchTestListener {
                    override fun onQueryStart(index: Int, query: String) {
                        Log.d(TAG, "批测：查询 $index 开始: $query")
                        mainActivity.addLog("批测：查询 $index 开始: $query")
                    }
                    
                    override fun onAnswerReceived(index: Int, query: String, answer: String) {
                        Log.d(TAG, "批测：查询 $index 收到answer: $answer")
                        mainActivity.addLog("批测：查询 $index 收到answer")
                    }
                    
                    override fun onQueryComplete(index: Int, success: Boolean, error: String?) {
                        if (success) {
                            Log.d(TAG, "批测：查询 $index 完成（成功）")
                            mainActivity.addLog("批测：查询 $index 完成（成功）")
                        } else {
                            Log.w(TAG, "批测：查询 $index 完成（失败）: $error")
                            mainActivity.addLog("批测：查询 $index 完成（失败）: $error")
                        }
                    }
                    
                    override fun onBatchTestComplete(successCount: Int, failCount: Int, totalCount: Int) {
                        val message = "批测完成：成功 $successCount，失败 $failCount，总计 $totalCount"
                        Log.d(TAG, message)
                        mainActivity.addLog(message)
                        // 使用runOnUiThread而不是withContext，因为这不是suspend函数
                        try {
                            mainActivity.runOnUiThread {
                                context?.let {
                                    Toast.makeText(it, message, Toast.LENGTH_LONG).show()
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "显示批测完成Toast异常: ${e.message}", e)
                        }
                    }
                })
                
                mainActivity.addLog("批测：已切换到聊天页面，正在从云侧获取查询列表...")
                Log.d(TAG, "批测：开始从云侧获取查询列表")
                
                // 确保NetworkService使用正确的服务器URL初始化
                val serverUrl = withContext(Dispatchers.Main) {
                    val prefs = context?.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                    prefs?.getString("chat_server_url", ServiceUrlConfig.DEFAULT_SERVER_URL) 
                        ?: ServiceUrlConfig.DEFAULT_SERVER_URL
                }
                Log.d(TAG, "批测：初始化NetworkService，使用URL: $serverUrl")
                mainActivity.addLog("批测：初始化网络服务，URL: $serverUrl")
                NetworkService.initialize(serverUrl, requireContext())
                
                // 检查apiService是否可用
                val apiService = NetworkService.getApiService()
                if (apiService == null) {
                    Log.e(TAG, "批测：apiService为null")
                    mainActivity.addLog("批测失败：网络服务未初始化")
                    withContext(Dispatchers.Main) {
                        context?.let {
                            Toast.makeText(it, "批测失败：网络服务未初始化", Toast.LENGTH_SHORT).show()
                        }
                        stopBatchTest()
                    }
                    return@launch
                }
                
                    // 从云侧获取批测查询列表
                    mainActivity.addLog("批测：正在发送HTTP请求...")
                    val response = try {
                        withContext(Dispatchers.IO) {
                            try {
                                Log.d(TAG, "批测：发送HTTP请求到 get_batch_queries")
                                apiService.getBatchQueries()
                            } catch (e: Exception) {
                                Log.e(TAG, "批测：HTTP请求异常: ${e.message}", e)
                                e.printStackTrace()
                                throw e
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "批测：HTTP请求失败: ${e.message}", e)
                        e.printStackTrace()
                        mainActivity.addLog("批测失败：HTTP请求异常: ${e.message}")
                        try {
                            withContext(Dispatchers.Main) {
                                try {
                                    context?.let {
                                        Toast.makeText(it, "批测失败：网络请求异常", Toast.LENGTH_SHORT).show()
                                    }
                                } catch (toastEx: Exception) {
                                    Log.e(TAG, "批测：显示Toast异常: ${toastEx.message}", toastEx)
                                }
                                stopBatchTest()
                            }
                        } catch (ex: Exception) {
                            Log.e(TAG, "批测：停止批测异常: ${ex.message}", ex)
                            ex.printStackTrace()
                        }
                        return@launch
                    }
                    
                    Log.d(TAG, "批测：收到HTTP响应，状态码: ${response.code()}, 成功: ${response.isSuccessful}")
                    mainActivity.addLog("批测：收到HTTP响应，状态码: ${response.code()}")
                
                if (response.isSuccessful) {
                    Log.d(TAG, "批测：开始解析响应体...")
                    mainActivity.addLog("批测：开始解析响应体...")
                    
                    val batchResponse = try {
                        response.body()
                    } catch (e: Exception) {
                        Log.e(TAG, "批测：解析响应体异常: ${e.message}", e)
                        mainActivity.addLog("批测失败：解析响应体异常: ${e.message}")
                        withContext(Dispatchers.Main) {
                            context?.let {
                                Toast.makeText(it, "批测失败：解析响应体异常", Toast.LENGTH_SHORT).show()
                            }
                            stopBatchTest()
                        }
                        return@launch
                    }
                    
                    Log.d(TAG, "批测：响应体解析完成: $batchResponse")
                    mainActivity.addLog("批测：响应体解析完成")
                    
                    if (batchResponse != null) {
                        Log.d(TAG, "批测：batchResponse不为null，queries数量=${batchResponse.queries.size}")
                        mainActivity.addLog("批测：响应体解析成功，queries数量=${batchResponse.queries.size}")
                        
                        if (batchResponse.queries.isNotEmpty()) {
                            val queries = batchResponse.queries
                            Log.d(TAG, "批测：获取到 ${queries.size} 个查询: $queries")
                            mainActivity.addLog("批测：获取到 ${queries.size} 个查询，开始执行...")
                            
                            // 再次检查Fragment状态，确保安全
                            val currentChatFragment = mainActivity.getChatFragment()
                            if (currentChatFragment == null || !currentChatFragment.isAdded || currentChatFragment.view == null) {
                                Log.e(TAG, "批测：ChatFragment状态无效，无法启动批测")
                                mainActivity.addLog("批测失败：ChatFragment状态无效")
                                withContext(Dispatchers.Main) {
                                    context?.let {
                                        Toast.makeText(it, "批测失败：界面未就绪", Toast.LENGTH_SHORT).show()
                                    }
                                    stopBatchTest()
                                }
                                return@launch
                            }
                            
                            // 使用批测控制器执行批测
                            val controller = batchTestController
                            if (controller != null && currentChatFragment != null) {
                                try {
                                    // 再次检查Activity和Fragment状态，确保安全
                                    val activity = this@TestFragment.activity as? MainActivity
                                    if (activity == null || activity.isFinishing || activity.isDestroyed) {
                                        Log.e(TAG, "批测：Activity状态无效，无法启动批测")
                                        mainActivity.addLog("批测失败：Activity状态无效")
                                        withContext(Dispatchers.Main) {
                                            try {
                                                context?.let {
                                                    Toast.makeText(it, "批测失败：界面未就绪", Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (toastEx: Exception) {
                                                Log.e(TAG, "显示Toast异常: ${toastEx.message}", toastEx)
                                            }
                                            stopBatchTest()
                                        }
                                        return@launch
                                    }
                                    
                                    // 检查testScope是否可用
                                    if (activity.testScope == null || !activity.testScope.coroutineContext.isActive) {
                                        Log.e(TAG, "批测：testScope不可用，无法启动批测")
                                        mainActivity.addLog("批测失败：协程作用域不可用")
                                        withContext(Dispatchers.Main) {
                                            try {
                                                context?.let {
                                                    Toast.makeText(it, "批测失败：系统未就绪", Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (toastEx: Exception) {
                                                Log.e(TAG, "显示Toast异常: ${toastEx.message}", toastEx)
                                            }
                                            stopBatchTest()
                                        }
                                        return@launch
                                    }
                                    
                                    // 确保在主线程调用suspend函数
                                    Log.d(TAG, "批测：准备启动批测控制器，queries数量=${queries.size}")
                                    mainActivity.addLog("批测：准备启动批测控制器...")
                                    
                                    // 再次检查Activity和Fragment状态
                                    val finalActivity = this@TestFragment.activity as? MainActivity
                                    val finalChatFragment = mainActivity.getChatFragment()
                                    
                                    if (finalActivity == null || finalActivity.isFinishing || finalActivity.isDestroyed) {
                                        Log.e(TAG, "批测：Activity状态无效，无法启动批测")
                                        mainActivity.addLog("批测失败：Activity状态无效")
                                        try {
                                            withContext(Dispatchers.Main) {
                                                try {
                                                    context?.let {
                                                        Toast.makeText(it, "批测失败：界面未就绪", Toast.LENGTH_SHORT).show()
                                                    }
                                                } catch (toastEx: Exception) {
                                                    Log.e(TAG, "显示Toast异常: ${toastEx.message}", toastEx)
                                                }
                                                stopBatchTest()
                                            }
                                        } catch (ex: Exception) {
                                            Log.e(TAG, "批测：停止批测异常: ${ex.message}", ex)
                                            ex.printStackTrace()
                                        }
                                        return@launch
                                    }
                                    
                                    if (finalChatFragment == null || !finalChatFragment.isAdded || finalChatFragment.view == null) {
                                        Log.e(TAG, "批测：ChatFragment状态无效，无法启动批测")
                                        mainActivity.addLog("批测失败：ChatFragment状态无效")
                                        try {
                                            withContext(Dispatchers.Main) {
                                                try {
                                                    context?.let {
                                                        Toast.makeText(it, "批测失败：界面未就绪", Toast.LENGTH_SHORT).show()
                                                    }
                                                } catch (toastEx: Exception) {
                                                    Log.e(TAG, "显示Toast异常: ${toastEx.message}", toastEx)
                                                }
                                                stopBatchTest()
                                            }
                                        } catch (ex: Exception) {
                                            Log.e(TAG, "批测：停止批测异常: ${ex.message}", ex)
                                            ex.printStackTrace()
                                        }
                                        return@launch
                                    }
                                    
                                    try {
                                        controller.startBatchTest(queries)
                                        Log.d(TAG, "批测：批测控制器启动成功")
                                        mainActivity.addLog("批测：批测控制器启动成功")
                                    } catch (e: Exception) {
                                        Log.e(TAG, "批测：启动批测控制器异常: ${e.message}", e)
                                        e.printStackTrace()
                                        mainActivity.addLog("批测异常：启动失败: ${e.message}")
                                        mainActivity.addLog("批测异常堆栈: ${e.stackTraceToString()}")
                                        try {
                                            withContext(Dispatchers.Main) {
                                                try {
                                                    context?.let {
                                                        Toast.makeText(it, "批测异常: ${e.message}", Toast.LENGTH_SHORT).show()
                                                    }
                                                } catch (toastEx: Exception) {
                                                    Log.e(TAG, "显示Toast异常: ${toastEx.message}", toastEx)
                                                }
                                                stopBatchTest()
                                            }
                                        } catch (ex: Exception) {
                                            Log.e(TAG, "批测：停止批测异常: ${ex.message}", ex)
                                            ex.printStackTrace()
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "批测：启动批测控制器外层异常: ${e.message}", e)
                                    e.printStackTrace()
                                    mainActivity.addLog("批测异常：启动失败: ${e.message}")
                                    mainActivity.addLog("批测异常堆栈: ${e.stackTraceToString()}")
                                    try {
                                        withContext(Dispatchers.Main) {
                                            try {
                                                context?.let {
                                                    Toast.makeText(it, "批测异常: ${e.message}", Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (toastEx: Exception) {
                                                Log.e(TAG, "显示Toast异常: ${toastEx.message}", toastEx)
                                            }
                                            stopBatchTest()
                                        }
                                    } catch (ex: Exception) {
                                        Log.e(TAG, "批测：停止批测异常: ${ex.message}", ex)
                                        ex.printStackTrace()
                                    }
                                }
                            } else {
                                Log.e(TAG, "批测：批测控制器或ChatFragment为null，controller=$controller, fragment=$currentChatFragment")
                                mainActivity.addLog("批测失败：批测控制器未初始化")
                                withContext(Dispatchers.Main) {
                                    try {
                                        context?.let {
                                            Toast.makeText(it, "批测失败：批测控制器未初始化", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (toastEx: Exception) {
                                        Log.e(TAG, "显示Toast异常: ${toastEx.message}", toastEx)
                                    }
                                    stopBatchTest()
                                }
                            }
                        } else {
                            Log.w(TAG, "批测：查询列表为空")
                            mainActivity.addLog("批测失败：查询列表为空")
                            withContext(Dispatchers.Main) {
                                context?.let {
                                    Toast.makeText(it, "批测：查询列表为空", Toast.LENGTH_SHORT).show()
                                }
                                stopBatchTest()
                            }
                        }
                    } else {
                        Log.w(TAG, "批测：响应体为空或查询列表为空，batchResponse=$batchResponse")
                        if (batchResponse != null) {
                            Log.w(TAG, "批测：queries=${batchResponse.queries}, isEmpty=${batchResponse.queries.isEmpty()}")
                        }
                        mainActivity.addLog("批测失败：未获取到查询列表（响应体为空或查询列表为空）")
                        withContext(Dispatchers.Main) {
                            context?.let {
                                Toast.makeText(it, "批测：未获取到查询列表", Toast.LENGTH_SHORT).show()
                            }
                            stopBatchTest()
                        }
                    }
                } else {
                    Log.w(TAG, "批测：HTTP请求失败，状态码: ${response.code()}")
                    val errorBody = try {
                        response.errorBody()?.string()
                    } catch (e: Exception) {
                        "无法读取错误响应体: ${e.message}"
                    }
                    Log.w(TAG, "批测：错误响应体: $errorBody")
                    mainActivity.addLog("批测失败：HTTP ${response.code()}, 错误: $errorBody")
                    withContext(Dispatchers.Main) {
                        context?.let {
                            Toast.makeText(it, "批测失败：${response.code()}", Toast.LENGTH_SHORT).show()
                        }
                        stopBatchTest()
                    }
                }
                } catch (e: Exception) {
                    Log.e(TAG, "批测异常: ${e.message}", e)
                    e.printStackTrace()
                    try {
                        withContext(Dispatchers.Main) {
                            try {
                                context?.let {
                                    Toast.makeText(it, "批测异常: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            } catch (toastEx: Exception) {
                                Log.e(TAG, "批测：显示Toast异常: ${toastEx.message}", toastEx)
                            }
                            try {
                                (activity as? MainActivity)?.addLog("批测异常: ${e.message}")
                                (activity as? MainActivity)?.addLog("批测异常堆栈: ${e.stackTraceToString()}")
                            } catch (logEx: Exception) {
                                Log.e(TAG, "批测：添加日志异常: ${logEx.message}", logEx)
                            }
                            try {
                                stopBatchTest()
                            } catch (stopEx: Exception) {
                                Log.e(TAG, "批测：停止批测异常: ${stopEx.message}", stopEx)
                                stopEx.printStackTrace()
                            }
                        }
                    } catch (ex: Exception) {
                        Log.e(TAG, "批测：异常处理失败: ${ex.message}", ex)
                        ex.printStackTrace()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "startBatchTest: 外层异常: ${e.message}", e)
            e.printStackTrace()
            try {
                val mainActivity = activity as? MainActivity
                mainActivity?.addLog("批测失败：启动异常: ${e.message}")
                mainActivity?.addLog("批测异常堆栈: ${e.stackTraceToString()}")
                mainActivity?.isBatchTesting = false
                try {
                    context?.let {
                        Toast.makeText(it, "批测失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } catch (toastEx: Exception) {
                    Log.e(TAG, "startBatchTest: 显示Toast异常: ${toastEx.message}", toastEx)
                }
            } catch (ex: Exception) {
                Log.e(TAG, "startBatchTest: 异常处理失败: ${ex.message}", ex)
                ex.printStackTrace()
            }
        }
        
        Log.d(TAG, "========== startBatchTest 执行完成 ==========")
    }
    
    /**
     * 更新批测UI状态（供MainActivity调用）
     */
    fun updateBatchTestUI(isRunning: Boolean) {
        try {
            val activity = activity
            if (activity != null && _binding != null && isAdded) {
                activity.runOnUiThread {
                    try {
                        if (_binding != null) {
                            if (isRunning) {
                                binding.btnStartBatchTest.isEnabled = false
                                binding.btnStartBatchTest.text = getString(R.string.batch_test_running)
                                binding.btnStopBatchTest.isEnabled = true
                            } else {
                                binding.btnStartBatchTest.isEnabled = true
                                binding.btnStartBatchTest.text = getString(R.string.batch_test)
                                binding.btnStopBatchTest.isEnabled = false
                            }
                            Log.d(TAG, "updateBatchTestUI: UI已更新，isRunning=$isRunning")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "updateBatchTestUI: 更新UI异常: ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateBatchTestUI: 异常: ${e.message}", e)
        }
    }
    
    /**
     * 停止批测
     * 强制停止批测，无论当前状态如何
     */
    private fun stopBatchTest() {
        Log.d(TAG, "========== stopBatchTest 开始执行 ==========")
        
        val mainActivity = activity as? MainActivity
        if (mainActivity == null) {
            Log.e(TAG, "stopBatchTest: MainActivity为null，无法停止批测")
            Toast.makeText(context, "无法停止批测：MainActivity未找到", Toast.LENGTH_SHORT).show()
            return
        }
        
        val oldState = mainActivity.isBatchTesting
        Log.d(TAG, "stopBatchTest: 当前状态 - isBatchTesting=$oldState, batchTestJob=${mainActivity.batchTestJob}, batchTestController=$batchTestController")
        mainActivity.addLog("开始停止批测，当前状态: isBatchTesting=$oldState")
        
        // 强制停止批测控制器（无论状态如何）
        try {
            // 优先使用MainActivity保存的控制器引用
            val controller = mainActivity.batchTestController ?: batchTestController
            if (controller != null) {
                Log.d(TAG, "stopBatchTest: 调用 batchTestController.stopBatchTest()")
                controller.stopBatchTest()
                Log.d(TAG, "stopBatchTest: batchTestController.stopBatchTest() 完成")
            } else {
                Log.w(TAG, "stopBatchTest: batchTestController 为 null，跳过")
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopBatchTest: 停止批测控制器异常: ${e.message}", e)
            e.printStackTrace()
            mainActivity.addLog("停止批测控制器异常: ${e.message}")
        }
        batchTestController = null
        mainActivity.batchTestController = null
        
        // 强制取消批测协程（无论状态如何）
        try {
            val job = mainActivity.batchTestJob
            if (job != null) {
                Log.d(TAG, "stopBatchTest: 取消 batchTestJob，job.isActive=${job.isActive}, job.isCancelled=${job.isCancelled}")
                job.cancel()
                Log.d(TAG, "stopBatchTest: batchTestJob 已取消")
            } else {
                Log.w(TAG, "stopBatchTest: batchTestJob 为 null，跳过")
            }
            mainActivity.batchTestJob = null
        } catch (e: Exception) {
            Log.e(TAG, "stopBatchTest: 取消批测协程异常: ${e.message}", e)
            e.printStackTrace()
            mainActivity.addLog("取消批测协程异常: ${e.message}")
        }
        
        // 强制重置批测状态
        mainActivity.isBatchTesting = false
        Log.d(TAG, "stopBatchTest: isBatchTesting 已设置为 false")
        
        // 更新UI（确保在主线程执行，如果Fragment还存在）
        try {
            val activity = activity
            if (activity != null && _binding != null) {
                activity.runOnUiThread {
                    try {
                        if (_binding != null) {
                            Log.d(TAG, "stopBatchTest: 更新UI - 启用开始按钮，禁用结束按钮")
                            binding.btnStartBatchTest.isEnabled = true
                            binding.btnStartBatchTest.text = getString(R.string.batch_test)
                            binding.btnStopBatchTest.isEnabled = false
                            Log.d(TAG, "stopBatchTest: UI更新完成")
                        } else {
                            Log.w(TAG, "stopBatchTest: _binding 为 null，无法更新UI")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "stopBatchTest: 更新UI异常: ${e.message}", e)
                        e.printStackTrace()
                    }
                }
            } else {
                Log.w(TAG, "stopBatchTest: activity 或 _binding 为 null，无法更新UI - activity=$activity, _binding=$_binding")
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopBatchTest: 更新UI异常: ${e.message}", e)
            e.printStackTrace()
        }
        
        mainActivity.addLog("批测已强制停止，isBatchTesting=${mainActivity.isBatchTesting}")
        Log.d(TAG, "stopBatchTest: 批测已强制停止，最终状态: isBatchTesting=${mainActivity.isBatchTesting}")
        
        // 显示Toast（如果Fragment还存在且已attached）
        try {
            val ctx = context ?: mainActivity
            Toast.makeText(ctx, "批测已停止", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "stopBatchTest: Toast 已显示")
        } catch (e: Exception) {
            Log.e(TAG, "stopBatchTest: 显示Toast异常: ${e.message}", e)
            e.printStackTrace()
        }
        
        Log.d(TAG, "========== stopBatchTest 执行完成 ==========")
    }
    
    /**
     * 启动动态批测
     */
    private fun startDynamicBatchTest() {
        Log.d(TAG, "========== startDynamicBatchTest 开始执行 ==========")
        
        val mainActivity = activity as? MainActivity
        if (mainActivity == null) {
            Log.e(TAG, "startDynamicBatchTest: MainActivity为null")
            context?.let {
                Toast.makeText(it, "动态批测失败：MainActivity未找到", Toast.LENGTH_SHORT).show()
            }
            return
        }
        
        if (dynamicBatchTestController?.state?.value?.isRunning == true) {
            Log.w(TAG, "startDynamicBatchTest: 动态批测正在进行中")
            Toast.makeText(mainActivity, "动态批测正在进行中", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 切换到聊天页面
        if (!mainActivity.switchToChatPage()) {
            Log.e(TAG, "startDynamicBatchTest: 切换到聊天页面失败")
            Toast.makeText(mainActivity, "动态批测失败：无法切换到聊天页面", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 等待Fragment就绪
        testScope.launch {
            delay(500)
            val chatFragment = mainActivity.getChatFragment()
            if (chatFragment == null || !chatFragment.isAdded || chatFragment.view == null) {
                Log.e(TAG, "startDynamicBatchTest: ChatFragment未就绪")
                withContext(Dispatchers.Main) {
                    Toast.makeText(mainActivity, "动态批测失败：聊天界面未就绪", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            
            // 创建控制器并启动
            dynamicBatchTestController = DynamicBatchTestController(mainActivity, chatFragment)
            try {
                dynamicBatchTestController?.start()
                updateDynamicBatchTestUI()
            } catch (e: Exception) {
                Log.e(TAG, "startDynamicBatchTest: 启动异常: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(mainActivity, "动态批测启动失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * 停止动态批测
     */
    private fun stopDynamicBatchTest() {
        Log.d(TAG, "========== stopDynamicBatchTest 开始执行 ==========")
        
        dynamicBatchTestController?.stop()
        dynamicBatchTestController = null
        updateDynamicBatchTestUI()
        
        context?.let {
            Toast.makeText(it, "动态批测已停止", Toast.LENGTH_SHORT).show()
        }
        
        Log.d(TAG, "========== stopDynamicBatchTest 执行完成 ==========")
    }
    
    /**
     * 更新动态批测UI状态
     */
    private fun updateDynamicBatchTestUI() {
        try {
            val activity = activity
            if (activity != null && _binding != null && isAdded) {
                activity.runOnUiThread {
                    try {
                        if (_binding != null) {
                            val isRunning = dynamicBatchTestController?.state?.value?.isRunning == true
                            if (isRunning) {
                                binding.btnStartDynamicBatchTest.isEnabled = false
                                binding.btnStartDynamicBatchTest.text = getString(R.string.dynamic_batch_test_running)
                                binding.btnStopDynamicBatchTest.isEnabled = true
                            } else {
                                binding.btnStartDynamicBatchTest.isEnabled = true
                                binding.btnStartDynamicBatchTest.text = getString(R.string.dynamic_batch_test)
                                binding.btnStopDynamicBatchTest.isEnabled = false
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "updateDynamicBatchTestUI: 更新UI异常: ${e.message}", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "updateDynamicBatchTestUI: 异常: ${e.message}", e)
        }
    }
    
    /**
     * 执行批测查询列表（已废弃，使用BatchTestController代替）
     * @deprecated 使用BatchTestController代替
     */
    @Deprecated("使用BatchTestController代替")
    private suspend fun executeBatchQueries(queries: List<String>) {
        // 立即输出日志，确保方法被调用
        Log.d(TAG, "executeBatchQueries: ========== 方法开始执行 ==========")
        Log.d(TAG, "executeBatchQueries: 查询数量=${queries.size}")
        
        // 获取MainActivity（在suspend函数中，activity可能为null）
        val mainActivity = activity as? MainActivity
        if (mainActivity == null) {
            Log.e(TAG, "executeBatchQueries: MainActivity为null，无法继续执行")
            return
        }
        
        mainActivity.addLog("批测：executeBatchQueries方法开始执行，查询数量=${queries.size}")
        
        try {
            Log.d(TAG, "executeBatchQueries: try块开始")
            
            Log.d(TAG, "executeBatchQueries: MainActivity已获取")
            mainActivity.addLog("批测：executeBatchQueries方法已进入")
            
            // 检查无障碍服务
            Log.d(TAG, "executeBatchQueries: 开始检查无障碍服务")
            val accessibilityService = MyAccessibilityService.getInstance()
            Log.d(TAG, "executeBatchQueries: accessibilityService=$accessibilityService")
            
            if (accessibilityService == null) {
                Log.e(TAG, "executeBatchQueries: 无障碍服务未连接")
                mainActivity.addLog("批测失败：无障碍服务未连接，请开启无障碍服务")
                withContext(Dispatchers.Main) {
                    context?.let {
                        Toast.makeText(it, "批测失败：无障碍服务未连接", Toast.LENGTH_SHORT).show()
                    }
                    stopBatchTest()
                }
                return
            }
            
            Log.d(TAG, "executeBatchQueries: 无障碍服务已就绪，开始执行")
            mainActivity.addLog("批测：开始执行查询列表，共 ${queries.size} 个查询（使用模拟点击方式）")
            Log.d(TAG, "executeBatchQueries: 准备开始循环，totalQueries=${queries.size}")
            
            val totalQueries = queries.size
            var successCount = 0
            var failCount = 0
            
            for ((index, query) in queries.withIndex()) {
                Log.d(TAG, "executeBatchQueries: 开始处理第 ${index + 1} 个查询: $query")
                
                // 检查是否被中断
                if (!mainActivity.isBatchTesting) {
                    Log.d(TAG, "executeBatchQueries: 批测被中断")
                    mainActivity.addLog("批测被中断，已执行 ${index}/${totalQueries}")
                    break
                }
                
                val currentIndex = index + 1
                Log.d(TAG, "executeBatchQueries: 处理查询 $currentIndex/$totalQueries: $query")
                mainActivity.addLog("批测进度：${currentIndex}/${totalQueries} - $query")
                
                try {
                    // 检查无障碍服务
                    val accessibilityService = MyAccessibilityService.getInstance()
                    if (accessibilityService == null) {
                        Log.e(TAG, "批测：查询 $currentIndex - 无障碍服务未连接")
                        mainActivity.addLog("批测：查询 $currentIndex 失败 - 无障碍服务未连接")
                        failCount++
                        continue
                    }
                    
                    // 批测操作的坐标
                    val click1X = 449  // 第一个点击位置
                    val click1Y = 986
                    val click2X = 429  // 激活输入框
                    val click2Y = 1840
                    val sendX = 278   // 发送按钮
                    val sendY = 1148
                    
                    // 步骤1: 点击第一个位置 (449, 986)
                    Log.d(TAG, "批测：查询 $currentIndex - 步骤1 - 点击 ($click1X, $click1Y)")
                    mainActivity.addLog("批测：查询 $currentIndex - 步骤1 - 点击 ($click1X, $click1Y)")
                    val click1Success = withContext(Dispatchers.Main) {
                        accessibilityService.performClick(click1X, click1Y)
                    }
                    if (!click1Success) {
                        Log.w(TAG, "批测：查询 $currentIndex - 步骤1失败")
                        mainActivity.addLog("批测：查询 $currentIndex 步骤1失败")
                        failCount++
                        continue
                    }
                    delay(500) // 等待界面响应
                    
                    // 步骤2: 点击输入框位置 (429, 1840) 激活输入框
                    Log.d(TAG, "批测：查询 $currentIndex - 步骤2 - 点击输入框 ($click2X, $click2Y)")
                    mainActivity.addLog("批测：查询 $currentIndex - 步骤2 - 激活输入框 ($click2X, $click2Y)")
                    val click2Success = withContext(Dispatchers.Main) {
                        accessibilityService.performClick(click2X, click2Y)
                    }
                    if (!click2Success) {
                        Log.w(TAG, "批测：查询 $currentIndex - 步骤2失败")
                        mainActivity.addLog("批测：查询 $currentIndex 步骤2失败")
                        failCount++
                        continue
                    }
                    delay(500) // 等待输入框激活
                    
                    // 步骤3: 输入查询文本
                    Log.d(TAG, "批测：查询 $currentIndex - 步骤3 - 输入文本: $query")
                    mainActivity.addLog("批测：查询 $currentIndex - 步骤3 - 输入文本: $query")
                    val inputSuccess = withContext(Dispatchers.Main) {
                        accessibilityService.inputText(query)
                    }
                    if (!inputSuccess) {
                        Log.w(TAG, "批测：查询 $currentIndex - 步骤3失败")
                        mainActivity.addLog("批测：查询 $currentIndex 步骤3失败")
                        failCount++
                        continue
                    }
                    delay(500) // 等待文本输入完成
                    
                    // 步骤4: 点击发送按钮 (278, 1148)
                    Log.d(TAG, "批测：查询 $currentIndex - 步骤4 - 点击发送按钮 ($sendX, $sendY)")
                    mainActivity.addLog("批测：查询 $currentIndex - 步骤4 - 点击发送按钮 ($sendX, $sendY)")
                    val sendSuccess = withContext(Dispatchers.Main) {
                        accessibilityService.performClick(sendX, sendY)
                    }
                    if (!sendSuccess) {
                        Log.w(TAG, "批测：查询 $currentIndex - 步骤4失败")
                        mainActivity.addLog("批测：查询 $currentIndex 步骤4失败")
                        failCount++
                        continue
                    }
                    
                    mainActivity.addLog("批测：查询 $currentIndex 已启动，等待完成...")
                    
                    // 步骤5: 等待任务完成（通过检查ChatFragment的isTaskRunning状态）
                    var waitTime = 0
                    val maxWaitTime = 60000 // 60秒
                    val checkInterval = 1000L // 每秒检查一次
                    
                    while (waitTime < maxWaitTime && mainActivity.isBatchTesting) {
                        delay(checkInterval)
                        waitTime += checkInterval.toInt()
                        
                        // 检查任务是否完成
                        val chatFragment = mainActivity.getChatFragment()
                        val isTaskRunning = chatFragment?.isTaskRunning() ?: false
                        if (!isTaskRunning) {
                            successCount++
                            mainActivity.addLog("批测：查询 $currentIndex 执行完成（耗时${waitTime/1000}秒）")
                            break
                        }
                    }
                    
                    if (waitTime >= maxWaitTime) {
                        mainActivity.addLog("批测：查询 $currentIndex 超时（60秒）")
                        failCount++
                    }
                    
                    // 等待一段时间再执行下一个（给系统一些时间）
                    delay(2000)
                } catch (e: Exception) {
                    Log.e(TAG, "执行批测查询异常: ${e.message}", e)
                    failCount++
                    mainActivity.addLog("批测：查询 $currentIndex 执行异常: ${e.message}")
                }
            }
            
            // 批测完成
            Log.d(TAG, "executeBatchQueries: 批测完成，成功=$successCount，失败=$failCount")
            withContext(Dispatchers.Main) {
                val message = "批测完成：成功 $successCount，失败 $failCount，总计 $totalQueries"
                mainActivity.addLog(message)
                context?.let {
                    Toast.makeText(it, message, Toast.LENGTH_LONG).show()
                }
                stopBatchTest()
            }
        } catch (e: Exception) {
            Log.e(TAG, "executeBatchQueries: 方法执行异常: ${e.message}", e)
            e.printStackTrace()
            val mainActivity = activity as? MainActivity
            mainActivity?.addLog("批测异常：executeBatchQueries执行失败: ${e.message}")
            withContext(Dispatchers.Main) {
                context?.let {
                    Toast.makeText(it, "批测异常: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                stopBatchTest()
            }
        }
        
        Log.d(TAG, "executeBatchQueries: ========== 方法执行结束 ==========")
    }
    
    override fun onResume() {
        super.onResume()
        // 检查Fragment是否已附加到Activity
        if (!isAdded || context == null) return
        
        // 确保ActionBar隐藏（测试页面有自己的标题栏，不需要ActionBar）
        (activity as? MainActivity)?.let { mainActivity ->
            if (mainActivity.supportActionBar?.isShowing == true) {
                mainActivity.hideActionBarInstantly()
            }
        }
        
        // 隐藏底部导航栏
        (activity as? MainActivity)?.setBottomNavigationVisibility(false)
        
        // 同步批测按钮状态（如果Fragment被销毁后重新创建，需要恢复状态）
        val mainActivity = activity as? MainActivity
        if (mainActivity != null && _binding != null) {
            updateBatchTestUI(mainActivity.isBatchTesting)
            updateDynamicBatchTestUI()
        }
    }
    
    override fun onPause() {
        super.onPause()
        // 显示底部导航栏
        (activity as? MainActivity)?.setBottomNavigationVisibility(true)
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        // 注意：不在这里停止批测，因为切换到聊天页面时Fragment会被销毁
        // 批测状态由MainActivity管理，避免Fragment销毁导致批测停止
        // 动态批测也类似，不在这里停止
        testScope.cancel()
        _binding = null
    }
}

