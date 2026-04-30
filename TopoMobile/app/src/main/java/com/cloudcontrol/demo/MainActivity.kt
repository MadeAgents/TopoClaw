package com.cloudcontrol.demo

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.accessibility.AccessibilityManagerCompat
import com.cloudcontrol.demo.databinding.ActivityMainBinding
import com.cloudcontrol.demo.GroupAvatarHelper
import com.cloudcontrol.demo.LanguageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import org.json.JSONObject
import java.util.UUID
import android.media.projection.MediaProjectionManager
import android.util.Base64
import kotlin.math.sqrt
import android.view.View
import android.view.ViewGroup
import android.text.TextWatcher
import android.view.inputmethod.InputMethodManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.view.WindowManager
        import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction
import com.iflytek.sparkchain.core.SparkChain
import com.iflytek.sparkchain.core.SparkChainConfig
import com.google.android.material.badge.BadgeDrawable
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.activity.OnBackPressedCallback
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

/**
 * 主界面
 * 提供UI控制，启动和停止服务
 */
class MainActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_MEDIA_PROJECTION_CHAT = 1002
        const val REQUEST_MEDIA_PROJECTION_OVERLAY = 1006  // 悬浮球录制权限请求
        /** 从轨迹采集悬浮窗点击结束采集后，跳转到轨迹采集页并显示 Query 输入弹窗 */
        const val EXTRA_SHOW_TRAJECTORY_QUERY_DIALOG = "show_trajectory_query_dialog"
        private val DEFAULT_CHAT_SERVER_URL = ServiceUrlConfig.DEFAULT_SERVER_URL
        private const val SCREENSHOT_WIDTH = 1080
        private const val SCREENSHOT_HEIGHT = 1920
        /** get_wechat_link：第 4 步点击后、第 5 步前的额外等待（不含其后步间 delayMs） */
        private const val GET_WECHAT_LINK_EXTRA_DELAY_AFTER_STEP4_MS = 10_000L
        /** WebSocket 离线包在 ChatFragment 尚未就绪时暂存，避免补发消息丢失 */
        private const val PREF_KEY_PENDING_OFFLINE_MESSAGES = "pending_offline_messages_json"
        private const val PREF_KEY_NOTIFICATION_FIRST_REQUEST_DONE = "notification_permission_first_request_done"
        private const val PREF_KEY_NOTIFICATION_LAST_REQUEST_AT = "notification_permission_last_request_at"
        private const val NOTIFICATION_PERMISSION_RETRY_INTERVAL_MS = 3L * 24 * 60 * 60 * 1000
    }
    
    private lateinit var binding: ActivityMainBinding
    private var isServiceRunning = false
    private var isTestRunning = false
    
    // 协程异常处理器
    private val coroutineExceptionHandler = CoroutineExceptionHandler { context, exception ->
        Log.e(TAG, "协程异常: ${exception.message}", exception)
        exception.printStackTrace()
        addLog("错误: 协程异常 - ${exception.javaClass.simpleName}: ${exception.message}")
        addLog("错误堆栈: ${exception.stackTraceToString()}")
    }
    
    val testScope = CoroutineScope(Dispatchers.Main + coroutineExceptionHandler)
    
    // 聊天相关
    private var chatUuid: String = ""
    private var pendingChatMessage: String? = null  // 待发送的消息（权限授权后发送）
    var isTaskRunning: Boolean = false  // 任务是否正在运行（改为public，供Fragment访问）
    
    // Tab Fragment 缓存：用 show/hide 复用，避免 replace 导致的销毁重建闪烁
    private val tabFragments = mutableMapOf<Int, Fragment>()

    // 伴随模式权限相关（用于从悬浮球发起query时的权限检查）
    private var pendingQueryFromCompanion: String? = null  // 待发送的query（权限授权后发送）
    private var pendingChatFragmentFromCompanion: ChatFragment? = null  // 待发送的ChatFragment（权限授权后使用）
    
    // 日志列表（供LogFragment显示）
    private val logMessages = mutableListOf<String>()
    private var currentQuery: String? = null  // 当前任务的query
    private var taskJob: Job? = null  // 任务循环的Job，用于取消
    
    // 批测相关（由MainActivity管理，避免Fragment销毁导致批测停止）
    var isBatchTesting: Boolean = false
    var batchTestJob: Job? = null
    var batchTestController: BatchTestController? = null  // 保存批测控制器引用，以便从任何地方停止批测
    
    // 标记：是否正在从悬浮球录制切换到技能学习小助手（用于防止触发TopoClaw）
    var isSwitchingFromOverlayRecording: Boolean = false
    
    // 任务来源相关（用于从外部应用发起任务时，任务完成后切回原应用）
    var isTaskFromExternalApp: Boolean = false  // 任务是否从外部应用发起
    var originalPackageName: String? = null  // 任务发起时的原始应用包名
    var isTaskFromCompanionMode: Boolean = false  // 任务是否从伴随模式发起（无论当前在哪个应用）
    var isCompanionAskScreenEnabled: Boolean = false  // 悬浮球“问屏”开关（默认关闭）
    
    // 切换应用提醒弹窗相关
    private var switchAppDialog: android.app.AlertDialog? = null  // 当前显示的切换应用提醒弹窗
    
    // 热门技能检查相关
    private var hotSkillCheckJob: Job? = null  // 定期检查热门技能的Job
    private val hotSkillCheckInterval = 30_000L  // 每30秒检查一次
    
    // 问字按钮Broadcast接收器
    private var questionButtonReceiver: BroadcastReceiver? = null
    
    // 通知栏按钮Broadcast接收器
    private var notificationActionReceiver: BroadcastReceiver? = null
    // 监视通知栏事件接收器
    private var monitoredNotificationReceiver: BroadcastReceiver? = null
    
    // 未读消息更新广播接收器
    private var unreadCountReceiver: BroadcastReceiver? = null
    
    // 异常检测结果广播接收器
    private var anomalyDetectionReceiver: BroadcastReceiver? = null
    
    // 人工客服WebSocket客户端
    private var customerServiceWebSocket: CustomerServiceWebSocket? = null
    // 独立地理位置变化监视器（业务逻辑在 LocationMonitorManager，不放在 MainActivity）
    private var locationMonitorManager: LocationMonitorManager? = null
    
    // 底部导航栏聊天图标的徽章
    private var chatBadge: BadgeDrawable? = null
    // 自定义徽章视图（用于显示"..."）
    private var customBadgeView: android.widget.TextView? = null
    
    // 应用生命周期状态
    private var isAppInForeground = false
    private var currentConversationId: String? = null  // 当前对话ID
    private var pendingForegroundRestoreConversation: Conversation? = null
    private var pendingForegroundRestoreAtMs: Long = 0L
    private val foregroundRestoreWindowMs = 30_000L
    
    // ChatFragment 复用缓存（按 conversationId 缓存）
    private val chatFragmentCache = mutableMapOf<String, ChatFragment>()

    // PC 端执行指令待执行（switchToChatPage 后由 ChatFragment 拉取执行）
    data class PendingPcExecute(
        val query: String,
        val uuid: String,
        val messageId: String,
        val steps: List<String>? = null,
        val assistantBaseUrl: String? = null,
        val conversationId: String? = null,
        val chatSummary: String? = null
    )
    var pendingPcExecuteCommand: PendingPcExecute? = null
    
    // 双击返回键退出应用相关
    private var firstBackPressTime: Long = 0
    private val BACK_PRESS_INTERVAL = 2000L // 2秒内再次按返回键才退出
    private var exitBackPressedCallback: OnBackPressedCallback? = null
    private var hasTriggeredStartupContactsPreload = false
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "通知权限系统弹窗结果: granted=$granted")
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // 设置ActionBar标题
        supportActionBar?.title = getString(R.string.app_name)
        // 设置ActionBar背景为白色
        supportActionBar?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE))
        
        // 设置状态栏为白色，图标为深色
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            window.statusBarColor = android.graphics.Color.WHITE
            // 同时设置导航栏（底部系统虚拟按键区域）颜色为白色
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                window.navigationBarColor = android.graphics.Color.WHITE
            }
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = android.graphics.Color.WHITE
            // 同时设置导航栏（底部系统虚拟按键区域）颜色为白色
            window.navigationBarColor = android.graphics.Color.WHITE
        }
        
        // 检查是否已经显示过教程（只在第一次打开应用时显示）
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        
        // 设置应用启动时间戳（用于检测应用重启，避免聊天记录被错误清空）
        val currentTime = System.currentTimeMillis()
        val existingStartTime = prefs.getLong("app_start_time", 0)
        // 如果app_start_time未设置或应用已重启（时间戳差异超过1小时），更新它
        if (existingStartTime == 0L || (currentTime - existingStartTime > 3600000)) {
            prefs.edit().putLong("app_start_time", currentTime).apply()
            Log.d(TAG, "设置应用启动时间戳: $currentTime")
        }
        
        val hasShownTutorial = prefs.getBoolean("tutorial_shown", false)
        
        // 如果还没有显示过教程，延迟显示教程弹窗（等待界面初始化完成）
        if (!hasShownTutorial) {
            binding.root.postDelayed({
                try {
                    // 检查 Activity 是否还在运行
                    if (!isFinishing && !isDestroyed) {
                        val tutorialDialog = TutorialDialog(this@MainActivity)
                        tutorialDialog.show()
                        // 标记教程已显示
                        prefs.edit().putBoolean("tutorial_shown", true).apply()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "显示教程弹窗失败: ${e.message}", e)
                    e.printStackTrace()
                    // 不抛出异常，避免导致应用崩溃
                }
            }, 1000) // 延迟1000ms确保界面已完全初始化
        }

        maybeRequestNotificationPermissionWithCooldown()
        
        // 初始化应用映射
        AppMappingManager.initialize(this)

        // 聊天长连接池：应用启动时仅建立默认聊天小助手连接（自定义小助手统一走中转）
        ChatWebSocketPool.connectAll(this)
        // 设置 GUI 执行请求回调：收到 gui_execute_request 时由 ChatFragment 执行
        setupChatWebSocketPoolGuiExecuteCallback()
        // 启动电脑在线状态检测（启动自动检测 + 定时轮询）
        PcOnlineStatusManager.startChecking(this)

        // 启动位置变化监视（内部会自行做权限/开关检查）
        locationMonitorManager = LocationMonitorManager(
            context = applicationContext,
            webSocketProvider = { customerServiceWebSocket },
        ).also { it.start() }
        
        // 初始化交互覆盖层（需要SYSTEM_ALERT_WINDOW权限）
        try {
            InteractionOverlayManager.initialize(this)
        } catch (e: Exception) {
            Log.w(TAG, "初始化交互覆盖层失败（可能需要授权悬浮窗权限）: ${e.message}")
        }
        
        // 初始化边框特效管理器
        try {
            BorderEffectOverlayManager.initialize(this)
        } catch (e: Exception) {
            Log.w(TAG, "初始化边框特效管理器失败: ${e.message}")
        }
        
        // 初始化任务指示器悬浮窗管理器
        TaskIndicatorOverlayManager.initialize(this)
        
        // 检查伴随模式，如果开启则显示悬浮球
        if (TaskIndicatorOverlayManager.isCompanionModeEnabled(this)) {
            TaskIndicatorOverlayManager.showCompanionMode(this)
        }
        
        // 注册无障碍服务状态监听器（监听系统级状态变化）
        registerAccessibilityStateListener()
        
        // 设置窗口软输入模式为 adjustResize，但通过代码固定标题栏位置
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        
        setupBottomNavigation()
        
        // 注册未读消息更新广播接收器
        registerUnreadCountReceiver()
        // 注册异常检测结果广播接收器
        registerAnomalyDetectionReceiver()
        
        // 延迟初始化并更新聊天图标徽章，确保BottomNavigationView布局完成
        binding.root.post {
            initChatBadge()
            updateChatBadge()
            // 启动定期检查热门技能任务
            startHotSkillCheckTask()
        }
        
        // 监听Fragment返回栈变化，确保ActionBar和底部导航栏正确显示
        supportFragmentManager.addOnBackStackChangedListener {
            // 使用post延迟执行，避免在FragmentManager执行事务时触发新的事务
            // 增加延迟，确保Fragment的onResume完成后再更新UI，避免底部导航栏闪烁
            // 100ms 延迟，确保从 NewAssistantFragment 返回 AssistantPlazaFragment 时 Fragment 已完全恢复
            binding.root.postDelayed({
                updateUIForCurrentFragment()
                // 根据返回栈状态动态启用/禁用双击退出callback
                updateExitBackPressedCallbackState()
            }, 100)
        }
        
        // 默认显示对话列表页面
        if (savedInstanceState == null) {
            // 先检查是否需要显示初始设置界面
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val profile = ProfileManager.loadProfileLocally(this)
            val hasAvatar = !profile?.avatar.isNullOrEmpty()
            val hasNickname = !profile?.name.isNullOrEmpty()
            
            // 如果需要显示初始设置界面，立即隐藏导航栏
            if (!hasAvatar || !hasNickname) {
                // 立即隐藏ActionBar和底部导航栏，避免显示后再隐藏的动画效果
                hideActionBarInstantly()
                setBottomNavigationVisibility(false)
            }
            
            // 检查并显示初始设置界面
            checkAndShowInitialSetup()
            // 首次启动时预加载通讯录相关数据，避免需手动点「通讯录」后才出现聊天对象
            preloadContactsAndGroupsOnStartup()
        } else {
            // 恢复状态时，确保底部导航栏和Fragment状态一致
            updateUIForCurrentFragment()
        }
        
        // 注册问字按钮Broadcast接收器
        registerQuestionButtonReceiver()
        
        // 注册通知栏按钮Broadcast接收器
        registerNotificationActionReceiver()
        // 注册通知监听事件接收器
        registerMonitoredNotificationReceiver()
        
        // 初始化人工客服服务并注册用户
        initializeCustomerService()
        
        // 初始化SparkChain SDK（科大讯飞语音识别）
        initializeSparkChainSDK()
        
        // 重新注册所有定时任务（应用启动时，在后台线程执行，避免阻塞主线程）
        testScope.launch {
            SkillScheduleManager.rescheduleAll(this@MainActivity)
        }
        
        // 处理从通知进入的提醒
        val reminderSkillId = intent.getStringExtra("skill_reminder_id")
        if (reminderSkillId != null) {
            binding.root.postDelayed({
                val skills = SkillManager.loadSkills(this)
                val skill = skills.find { it.id == reminderSkillId }
                if (skill != null) {
                    SkillReminderDialog.show(this, skill)
                }
            }, 500)
        }
        
        // 检查是否需要导航到TopoClaw（从任务发起弹窗的扩大图标点击）
        handleNavigateToAssistantIntent()
        
        // 检查是否需要执行远程命令
        handleExecuteRemoteCommandIntent()
        
        // 检查是否需要跳转到聊天详情页（从通知点击）
        handleConversationIntent()
        
        // 检查是否从轨迹采集结束按钮进入，需要跳转到轨迹采集页并显示 Query 弹窗
        handleTrajectoryQueryDialogIntent()
        
        // 应用启动时检查版本更新
        checkVersionUpdateOnStartup()
        
        // 注册双击返回键退出应用的回调
        setupDoubleBackPressToExit()
    }
    
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.d(TAG, "onNewIntent: 收到新的Intent")
        // 检查是否需要导航到TopoClaw
        handleNavigateToAssistantIntent()
        // 检查是否需要执行远程命令
        handleExecuteRemoteCommandIntent()
        // 检查是否需要跳转到聊天详情页（从通知点击）
        handleConversationIntent()
        
        // 检查是否从轨迹采集结束按钮进入，需要跳转到轨迹采集页并显示 Query 弹窗
        handleTrajectoryQueryDialogIntent()
    }
    
    /**
     * 处理轨迹采集结束后的 Intent：回到最新页面，显示 Query 输入弹窗
     */
    private fun handleTrajectoryQueryDialogIntent() {
        if (!intent.getBooleanExtra(EXTRA_SHOW_TRAJECTORY_QUERY_DIALOG, false)) return
        intent.removeExtra(EXTRA_SHOW_TRAJECTORY_QUERY_DIALOG)
        Log.d(TAG, "handleTrajectoryQueryDialogIntent: 回到最新页面并显示Query弹窗")
        binding.root.postDelayed({
            try {
                // 回到 TopoClaw 前台后再比对剪切板，避免后台读取被系统限制
                TrajectoryClipboardMonitor.compareAndRecordOnReturn(this)
                showTrajectoryQueryInputDialog()
            } catch (e: Exception) {
                Log.e(TAG, "handleTrajectoryQueryDialogIntent 失败: ${e.message}", e)
            }
        }, 300)
    }
    
    /**
     * 显示轨迹采集完成后的 Query 输入弹窗（输入Query，跳过/发送）
     */
    fun showTrajectoryQueryInputDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_trajectory_query_input, null)
        val etQueryInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etQueryInput)
        val btnSkip = dialogView.findViewById<android.widget.Button>(R.id.btnSkip)
        val btnSend = dialogView.findViewById<android.widget.Button>(R.id.btnSend)
        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        btnSkip.setOnClickListener {
            dialog.dismiss()
        }
        btnSend.setOnClickListener {
            val query = etQueryInput?.text?.toString()?.trim() ?: ""
            if (query.isNotBlank()) {
                val sessions = TrajectoryRecorder.getRecordedSessions(this)
                val mostRecentFile = sessions.firstOrNull()
                if (mostRecentFile != null) {
                    val oldSessionId = mostRecentFile.name.removeSuffix(".json")
                    val success = TrajectoryRecorder.appendQueryToSession(this, mostRecentFile.name, query)
                    if (success) {
                        val newSessionId = TrajectoryRecorder.getNewSessionIdWithQuery(oldSessionId, query)
                        // 云侧重命名（若启用云侧上传）
                        testScope.launch {
                            TrajectoryCloudService.renameSessionOnCloud(this@MainActivity, oldSessionId, newSessionId)
                        }
                        Toast.makeText(this, "已更新轨迹名称", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "暂不支持", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "暂不支持", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "暂不支持", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }
    
    /**
     * 设置双击返回键退出应用
     */
    private fun setupDoubleBackPressToExit() {
        exitBackPressedCallback = object : OnBackPressedCallback(false) { // 默认禁用，根据返回栈状态动态启用
            override fun handleOnBackPressed() {
                // 处理双击退出逻辑
                val currentTime = System.currentTimeMillis()
                
                if (firstBackPressTime == 0L || currentTime - firstBackPressTime > BACK_PRESS_INTERVAL) {
                    // 第一次按返回键或超过时间间隔，显示提示
                    firstBackPressTime = currentTime
                    Toast.makeText(this@MainActivity, "再退一次退出应用", Toast.LENGTH_SHORT).show()
                } else {
                    // 在时间间隔内再次按返回键，退出应用
                    finish()
                }
            }
        }
        
        // 注册回调
        onBackPressedDispatcher.addCallback(this, exitBackPressedCallback!!)
        
        // 初始化callback状态
        updateExitBackPressedCallbackState()
    }
    
    /**
     * 更新双击退出callback的启用状态
     * 只有在没有Fragment在返回栈中时才启用，避免拦截Fragment的返回键处理
     */
    private fun updateExitBackPressedCallbackState() {
        exitBackPressedCallback?.let { callback ->
            // 如果没有Fragment在返回栈中，启用callback
            // 如果有Fragment在返回栈中，禁用callback，让Fragment处理返回键
            callback.isEnabled = supportFragmentManager.backStackEntryCount == 0
        }
    }
    
    /**
     * 处理导航到TopoClaw的Intent
     */
    private fun handleNavigateToAssistantIntent() {
        if (intent.getBooleanExtra("navigate_to_assistant", false)) {
            Log.d(TAG, "handleNavigateToAssistantIntent: 检测到navigate_to_assistant标记，准备导航到TopoClaw")
            // 清除标记，避免重复导航
            intent.removeExtra("navigate_to_assistant")
            // 延迟一下确保界面初始化完成
            binding.root.postDelayed({
                try {
                    val assistantConversation = CustomAssistantManager
                        .getById(this@MainActivity, "custom_topoclaw")
                        ?.let { target ->
                            Conversation(
                                id = target.id,
                                name = target.name,
                                avatar = target.avatar,
                                lastMessage = null,
                                lastMessageTime = System.currentTimeMillis()
                            )
                        }
                        ?: run {
                            Log.w(TAG, "handleNavigateToAssistantIntent: 未找到custom_topoclaw，兜底到assistant")
                            Conversation(
                                id = ConversationListFragment.CONVERSATION_ID_ASSISTANT,
                                name = ChatConstants.ASSISTANT_DISPLAY_NAME,
                                avatar = null,
                                lastMessage = null,
                                lastMessageTime = System.currentTimeMillis()
                            )
                        }
                    switchToChatFragment(assistantConversation)
                    Log.d(TAG, "handleNavigateToAssistantIntent: 已导航到TopoClaw聊天详情页")
                } catch (e: Exception) {
                    Log.e(TAG, "handleNavigateToAssistantIntent: 导航到TopoClaw失败: ${e.message}", e)
                }
            }, 300)
        }
    }
    
    /**
     * 检查并显示初始设置界面
     */
    private fun checkAndShowInitialSetup() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        
        // 检查头像和昵称是否已设置
        val profile = ProfileManager.loadProfileLocally(this)
        val hasAvatar = !profile?.avatar.isNullOrEmpty()
        val hasNickname = !profile?.name.isNullOrEmpty()
        
        // 如果头像和昵称都已设置，标记为已完成并显示对话列表
        if (hasAvatar && hasNickname) {
            prefs.edit().putBoolean("initial_setup_completed", true).apply()
            switchToTabFragment(R.id.nav_chat) { ConversationListFragment() }
            binding.root.postDelayed({
                updateUIForCurrentFragment()
            }, 50)
            preloadContactsAndGroupsOnStartup()
            return
        }
        
        // 如果本地没有完整资料，先尝试从服务器恢复
        // 异步尝试从服务器恢复资料
        testScope.launch {
            try {
                val imei = ProfileManager.getOrGenerateImei(this@MainActivity)
                
                // 初始化 CustomerServiceNetwork
                val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                    ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                CustomerServiceNetwork.initialize(customerServiceUrl)
                
                val apiService = CustomerServiceNetwork.getApiService()
                if (apiService != null) {
                    val response = apiService.getProfile(imei)
                    
                    if (response.isSuccessful && response.body()?.success == true) {
                        val cloudProfile = response.body()?.profile
                        if (cloudProfile != null) {
                            val cloudHasAvatar = !cloudProfile.avatar.isNullOrEmpty()
                            val cloudHasNickname = !cloudProfile.name.isNullOrEmpty()
                            
                            // 如果服务器有姓名和头像，恢复本地资料
                            if (cloudHasAvatar && cloudHasNickname) {
                                // 保存到本地
                                ProfileManager.saveProfileLocally(this@MainActivity, cloudProfile)
                                Log.d(TAG, "已从服务器恢复用户资料")
                                
                                // 在主线程更新UI
                                withContext(Dispatchers.Main) {
                                    prefs.edit().putBoolean("initial_setup_completed", true).apply()
                                    switchToTabFragment(R.id.nav_chat) { ConversationListFragment() }
                                    binding.root.postDelayed({
                                        updateUIForCurrentFragment()
                                    }, 50) // 50ms延迟，确保Fragment的onResume先执行
                                    preloadContactsAndGroupsOnStartup()
                                }
                                return@launch
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "从服务器恢复用户资料失败: ${e.message}")
                // 网络错误不影响，继续显示初始设置页面
            }
            
            // 如果服务器也没有资料，显示初始设置界面
            withContext(Dispatchers.Main) {
                showInitialSetupFragment()
            }
        }
    }

    /**
     * 应用首次启动时预加载好友与群组数据，确保会话列表首屏可见最新聊天对象。
     */
    private fun preloadContactsAndGroupsOnStartup() {
        if (hasTriggeredStartupContactsPreload) {
            return
        }
        testScope.launch {
            try {
                hasTriggeredStartupContactsPreload = true

                withContext(Dispatchers.IO) {
                    val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                        ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                    CustomerServiceNetwork.initialize(customerServiceUrl)
                    try {
                        FriendManager.syncFriendsFromServer(this@MainActivity)
                    } catch (e: Exception) {
                        Log.w(TAG, "preloadContactsAndGroupsOnStartup: 同步好友失败: ${e.message}")
                    }
                    try {
                        GroupManager.syncGroupsFromServer(this@MainActivity)
                    } catch (e: Exception) {
                        Log.w(TAG, "preloadContactsAndGroupsOnStartup: 同步群组失败: ${e.message}")
                    }
                }

                refreshConversationListWhenReadyForStartup()
                Log.d(TAG, "preloadContactsAndGroupsOnStartup: 预加载完成，等待会话页就绪后刷新")
            } catch (e: Exception) {
                Log.w(TAG, "preloadContactsAndGroupsOnStartup: 预加载失败: ${e.message}")
            }
        }
    }

    /**
     * 启动预加载后等待会话页创建完成再刷新，避免首次 commit 异步导致刷新丢失。
     */
    private fun refreshConversationListWhenReadyForStartup(attempt: Int = 0) {
        val conversationListFragment = findConversationListFragment()
        if (conversationListFragment != null) {
            conversationListFragment.loadConversations()
            Log.d(TAG, "refreshConversationListWhenReadyForStartup: 会话列表刷新成功，attempt=$attempt")
            return
        }
        if (attempt >= 12) {
            Log.w(TAG, "refreshConversationListWhenReadyForStartup: 会话页未就绪，放弃重试")
            return
        }
        binding.root.postDelayed({
            refreshConversationListWhenReadyForStartup(attempt + 1)
        }, 200L)
    }
    
    /**
     * 显示初始设置界面
     */
    private fun showInitialSetupFragment() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        // 重置标记
        prefs.edit().putBoolean("initial_setup_completed", false).apply()
        
        // 立即显示初始设置界面，不使用延迟，避免先显示其他界面
        try {
            // 确保导航栏已隐藏（在onCreate中已经处理，这里再次确认）
            setBottomNavigationVisibility(false)
            hideActionBarInstantly()
            
            // 显示初始设置Fragment（无动画）
            supportFragmentManager.beginTransaction()
                .setCustomAnimations(R.anim.no_animation, R.anim.no_animation, R.anim.no_animation, R.anim.no_animation)
                .setTransition(FragmentTransaction.TRANSIT_NONE)
                .replace(R.id.fragmentContainer, InitialSetupFragment())
                .commitNowAllowingStateLoss()  // 立即执行，避免任何延迟
        } catch (e: Exception) {
            Log.e(TAG, "显示初始设置界面失败: ${e.message}", e)
            e.printStackTrace()
            // 如果出错，回退到显示对话列表
            try {
                binding.bottomNavigation.selectedItemId = R.id.nav_chat
                hideActionBarWithoutAnimation()
                switchToTabFragment(R.id.nav_chat) { ConversationListFragment() }
            } catch (e2: Exception) {
                Log.e(TAG, "回退到对话列表也失败: ${e2.message}", e2)
            }
        }
    }
    
    /**
     * 处理执行远程命令的Intent
     */
    private fun handleExecuteRemoteCommandIntent() {
        if (intent.getStringExtra("action") == "execute_remote_command") {
            val groupId = intent.getStringExtra("groupId") ?: return
            val command = intent.getStringExtra("command") ?: return
            val senderImei = intent.getStringExtra("senderImei") ?: return
            
            Log.d(TAG, "handleExecuteRemoteCommandIntent: 检测到execute_remote_command标记，准备执行远程命令")
            // 清除标记，避免重复执行
            intent.removeExtra("action")
            intent.removeExtra("groupId")
            intent.removeExtra("command")
            intent.removeExtra("senderImei")
            
            // 延迟一下确保界面初始化完成
            binding.root.postDelayed({
                try {
                    // 获取群组信息
                    val group = GroupManager.getGroup(this, groupId)
                    val groupName = group?.name ?: "群组"
                    
                    // 切换到群组对话（而不是TopoClaw）
                    val groupConversationId = "group_$groupId"
                    val groupConversation = Conversation(
                        id = groupConversationId,
                        name = groupName,
                        avatar = null,
                        lastMessage = command,
                        lastMessageTime = System.currentTimeMillis()
                    )
                    switchToChatFragment(groupConversation)
                    
                    // 等待Fragment创建完成后执行命令
                    binding.root.postDelayed({
                        val chatFragment = supportFragmentManager.fragments.find { it is ChatFragment } as? ChatFragment
                        chatFragment?.let {
                            // 直接执行命令（用户已在悬浮窗中同意，不需要再次确认）
                            it.executeRemoteCommandDirectly(groupId, command, senderImei)
                            Log.d(TAG, "handleExecuteRemoteCommandIntent: 已执行远程命令")
                        } ?: Log.e(TAG, "handleExecuteRemoteCommandIntent: 未找到ChatFragment，无法执行远程命令")
                    }, 500)
                } catch (e: Exception) {
                    Log.e(TAG, "handleExecuteRemoteCommandIntent: 执行远程命令失败: ${e.message}", e)
                }
            }, 300)
        }
    }
    
    /**
     * 处理跳转到聊天详情页的Intent（从通知点击）
     */
    private fun handleConversationIntent() {
        val conversationId = intent.getStringExtra("conversation_id")
        if (conversationId.isNullOrEmpty()) {
            return
        }
        
        Log.d(TAG, "handleConversationIntent: 检测到conversation_id=$conversationId，准备跳转到聊天详情页")
        // 清除标记，避免重复跳转
        intent.removeExtra("conversation_id")
        
        // 延迟一下确保界面初始化完成
        binding.root.postDelayed({
            try {
                val conversation = when {
                    // 端云互发
                    conversationId == ConversationListFragment.CONVERSATION_ID_ME -> {
                        Conversation(
                            id = ConversationListFragment.CONVERSATION_ID_ME,
                            name = "我的电脑",
                            avatar = null,
                            lastMessage = null,
                            lastMessageTime = System.currentTimeMillis()
                        )
                    }
                    // 人工客服
                    conversationId == ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE -> {
                        Conversation(
                            id = ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE,
                            name = "人工客服",
                            avatar = null,
                            lastMessage = null,
                            lastMessageTime = System.currentTimeMillis()
                        )
                    }
                    // 好友消息
                    conversationId.startsWith("friend_") -> {
                        val senderImei = conversationId.removePrefix("friend_")
                        val friend = FriendManager.getFriend(this, senderImei)
                        val friendName = friend?.nickname ?: senderImei.take(8) + "..."
                        Conversation(
                            id = conversationId,
                            name = friendName,
                            avatar = friend?.avatar,
                            lastMessage = null,
                            lastMessageTime = System.currentTimeMillis()
                        )
                    }
                    // 群组消息
                    conversationId.startsWith("group_") -> {
                        val groupId = conversationId.removePrefix("group_")
                        val group = GroupManager.getGroup(this, groupId)
                        val groupName = group?.name ?: "群组"
                        Conversation(
                            id = conversationId,
                            name = groupName,
                            avatar = null,
                            lastMessage = null,
                            lastMessageTime = System.currentTimeMillis()
                        )
                    }
                    else -> {
                        Log.w(TAG, "handleConversationIntent: 未知的conversation_id类型: $conversationId")
                        return@postDelayed
                    }
                }
                
                // 在Fragment切换之前立即隐藏导航栏，避免动画
                setBottomNavigationVisibility(false)
                
                // 切换到聊天Fragment
                switchToChatFragment(conversation)
                Log.d(TAG, "handleConversationIntent: 已跳转到聊天详情页: $conversationId")
            } catch (e: Exception) {
                Log.e(TAG, "handleConversationIntent: 跳转到聊天详情页失败: ${e.message}", e)
                e.printStackTrace()
            }
        }, 300)
    }
    
    /**
     * 显示远程控制权限确认悬浮窗
     */
    private fun showRemoteControlPermissionOverlay(senderImei: String, groupId: String, command: String) {
        CoroutineScope(Dispatchers.Main + coroutineExceptionHandler).launch {
            // 异步获取发送者昵称
            val senderName = withContext(Dispatchers.IO) {
                try {
                    val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                    val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                        ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                    CustomerServiceNetwork.initialize(customerServiceUrl)
                    val apiService = CustomerServiceNetwork.getApiService()
                    apiService?.getProfile(senderImei)?.let { response ->
                        if (response.isSuccessful && response.body()?.success == true) {
                            response.body()?.profile?.name
                        } else null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "获取发送者信息失败: ${e.message}")
                    null
                }
            }
            
            // 显示悬浮窗弹窗
            RemoteControlPermissionOverlayManager.show(
                context = this@MainActivity,
                senderName = senderName,
                senderImei = senderImei,
                groupId = groupId,
                command = command
            )
        }
    }
    
    /**
     * 初始化SparkChain SDK（科大讯飞语音识别）
     */
    private fun initializeSparkChainSDK() {
        try {
            val appId = BuildConfig.SPARKCHAIN_APP_ID.trim()
            val apiKey = BuildConfig.SPARKCHAIN_API_KEY.trim()
            val apiSecret = BuildConfig.SPARKCHAIN_API_SECRET.trim()
            if (appId.isEmpty() || apiKey.isEmpty() || apiSecret.isEmpty()) {
                Log.w(TAG, "SparkChain 凭据缺失，请在 local.properties 或环境变量中配置 SPARKCHAIN_APP_ID/SPARKCHAIN_API_KEY/SPARKCHAIN_API_SECRET")
                return
            }
            val config = SparkChainConfig.builder()
                .appID(appId)
                .apiKey(apiKey)
                .apiSecret(apiSecret)
                .logPath("/sdcard/iflytek/SparkChain.log")
            
            val ret = SparkChain.getInst().init(applicationContext, config)
            if (ret == 0) {
                Log.d(TAG, "SparkChain SDK初始化成功")
            } else {
                Log.e(TAG, "SparkChain SDK初始化失败，错误码: $ret")
            }
        } catch (e: Exception) {
            Log.e(TAG, "初始化SparkChain SDK异常: ${e.message}", e)
        }
    }
    
    /**
     * 初始化人工客服服务
     * 注意：连接失败不会阻塞应用启动，只在用户真正使用人工客服时才尝试连接
     */
    /**
     * 解析timestamp字段，统一处理逻辑
     * 优先使用数字格式（毫秒时间戳），兼容ISO字符串格式
     * 统一时区处理：ISO字符串无时区信息时，按本地时区解析
     */
    private fun parseTimestamp(json: org.json.JSONObject, key: String): Long {
        return try {
            if (json.has(key)) {
                val value = json.get(key)
                when (value) {
                    is Long -> value
                    is Int -> value.toLong()
                    is Double -> value.toLong()
                    is String -> {
                        // 优先尝试解析为数字字符串（毫秒时间戳）
                        value.toLongOrNull()?.let { return@parseTimestamp it }
                        
                        // 如果是ISO格式字符串，统一按本地时区解析
                        if (value.contains('T')) {
                            try {
                                val cleanValue = value.replace("Z", "").trim()
                                
                                // 检查是否包含时区偏移（+08:00, -05:00等）
                                val hasTimezoneOffset = value.contains('+') || 
                                    (value.indexOf('T') > 0 && value.substring(value.indexOf('T') + 1).matches(Regex(".*-\\d{2}:\\d{2}.*")))
                                
                                val parsedTime = if (hasTimezoneOffset) {
                                    // 包含时区偏移，使用带时区的格式解析
                                    val normalizedValue = if (value.contains('Z')) {
                                        value.replace("Z", "+00:00")
                                    } else {
                                        value
                                    }
                                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US).parse(normalizedValue)?.time
                                } else {
                                    // 不包含时区信息，按本地时区解析（服务器和客户端在同一时区）
                                    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", java.util.Locale.US).apply {
                                        timeZone = java.util.TimeZone.getDefault()
                                    }.parse(cleanValue)?.time
                                }
                                
                                parsedTime ?: System.currentTimeMillis()
                            } catch (e: Exception) {
                                Log.w(TAG, "解析ISO timestamp失败: $value, 使用当前时间", e)
                                System.currentTimeMillis()
                            }
                        } else {
                            // 非ISO格式字符串，尝试解析为数字
                            value.toLongOrNull() ?: System.currentTimeMillis()
                        }
                    }
                    else -> System.currentTimeMillis()
                }
            } else {
                System.currentTimeMillis()
            }
        } catch (e: Exception) {
            Log.w(TAG, "解析timestamp失败，使用当前时间", e)
            System.currentTimeMillis()
        }
    }
    
    private fun initializeCustomerService() {
        try {
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            
            // 优先从专用配置读取（与ServerSettingsFragment保持一致）
            val conversationId = ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE
            val specificKey = "chat_server_url_$conversationId"
            val specificUrl = prefs.getString(specificKey, null)
            
            // 如果没有专用配置，尝试读取customer_service_url（默认值为v4地址）
            val customerServiceUrl = specificUrl ?: prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
            
            // 如果都没有配置，使用默认地址
            val finalUrl = customerServiceUrl ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
            
            Log.d(TAG, "初始化人工客服服务，使用地址: $finalUrl")
            CustomerServiceNetwork.initialize(finalUrl)
            
            // 延迟连接，避免阻塞应用启动（只在后台尝试连接，失败不影响使用）
            CoroutineScope(Dispatchers.IO + coroutineExceptionHandler).launch {
                delay(2000) // 延迟2秒，让应用先启动完成
                val imei = ProfileManager.getOrGenerateImei(this@MainActivity)
                registerUserAndConnectWebSocket(imei)
            }
        } catch (e: Exception) {
            Log.e(TAG, "初始化人工客服服务失败: ${e.message}", e)
            // 不抛出异常，避免影响应用启动
        }
    }
    
    /**
     * 注册用户并连接WebSocket
     */
    private fun registerUserAndConnectWebSocket(imei: String) {
        CoroutineScope(Dispatchers.IO + coroutineExceptionHandler).launch {
            try {
                // 先注册用户
                val apiService = CustomerServiceNetwork.getApiService()
                if (apiService != null) {
                    val deviceInfo = mapOf(
                        "model" to android.os.Build.MODEL,
                        "brand" to android.os.Build.BRAND,
                        "sdk" to android.os.Build.VERSION.SDK_INT.toString()
                    )
                    
                    val baseUrl = CustomerServiceNetwork.getCurrentBaseUrl() ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                    Log.d(TAG, "尝试注册用户并连接WebSocket，地址: $baseUrl")
                    
                    val response = apiService.register(RegisterRequest(imei = imei, device_info = deviceInfo))
                    if (response.isSuccessful) {
                        Log.d(TAG, "用户注册成功: $imei")
                        
                        // 注册成功后连接WebSocket
                        withContext(Dispatchers.Main) {
                            customerServiceWebSocket = CustomerServiceWebSocket(this@MainActivity).apply {
                                // ChatFragment会设置自己的回调（onMessageReceived）来显示消息
                                connect(imei, baseUrl)
                            }
                            // 确保监听器已设置（统一使用ensureCustomerServiceWebSocketListener）
                            ensureCustomerServiceWebSocketListener()
                        }
                    } else {
                        Log.w(TAG, "用户注册失败: ${response.code()}，可能是服务未启动或地址配置错误")
                        Log.w(TAG, "提示：请在设置中配置正确的人工客服服务地址")
                    }
                } else {
                    Log.w(TAG, "CustomerServiceNetwork未初始化，无法注册用户")
                }
            } catch (e: Exception) {
                Log.w(TAG, "注册用户或连接WebSocket失败: ${e.message}")
                Log.w(TAG, "提示：请确保人工客服服务已启动，并在设置中配置正确的服务地址")
                // 不抛出异常，避免影响应用使用
            }
        }
    }
    
    override fun onResume() {
        super.onResume()
        // 标记应用在前台
        isAppInForeground = true
        Log.d(TAG, "应用进入前台")
        maybeRequestNotificationPermissionWithCooldown()
        
        // 设置页启用无障碍后返回应用，自动补触发输入弹窗
        if (MyAccessibilityService.pendingInputDialogAfterSettingsEnable) {
            MyAccessibilityService.pendingInputDialogAfterSettingsEnable = false
            Log.d(TAG, "检测到从设置页启用无障碍后返回，补触发输入弹窗")
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                TaskIndicatorOverlayManager.showInputDialogFromAccessibility(this)
            }, 500)
        }
        
        // 更新聊天图标徽章（确保每次返回应用时都能正确显示）
        binding.root.post {
            if (chatBadge == null) {
                initChatBadge()
            }
            updateChatBadge()
            consumePendingOfflineMessagesIfAny()
            // 立即检查一次热门技能（应用进入前台时）
            checkHotSkillsImmediately()
            // 确保定期检查任务正在运行
            if (hotSkillCheckJob?.isActive != true) {
                startHotSkillCheckTask()
            }
        }
        
        // 检查是否需要导航到TopoClaw（从任务发起弹窗的扩大图标点击）
        // 注意：onResume 中的处理作为备用，主要处理在 onCreate 和 onNewIntent 中
        if (intent.getBooleanExtra("navigate_to_assistant", false)) {
            Log.d(TAG, "onResume: 检测到navigate_to_assistant标记，调用handleNavigateToAssistantIntent")
            handleNavigateToAssistantIntent()
        }

        // 如果应用被系统异常切到后台后马上回前台，优先恢复离开前的聊天页，避免落回列表页/通讯录页
        if (tryRestoreConversationAfterForegroundResume()) {
            Log.d(TAG, "onResume: 已恢复离开前聊天页，跳过后续会话清理逻辑")
            return
        }
        
        // 检查当前Fragment，如果不是ChatFragment或者ChatFragment的对话ID不是人工客服，清除currentConversationId
        // 这确保弹窗能正常显示
        try {
            val currentFragment = getActiveFragment()
            
            if (currentFragment !is ChatFragment) {
                // 当前不是ChatFragment，清除currentConversationId
                if (currentConversationId != null) {
                    Log.d(TAG, "MainActivity onResume: 当前Fragment不是ChatFragment，清除currentConversationId")
                    currentConversationId = null
                }
            } else {
                // 当前是ChatFragment，检查对话ID
                val conversation = currentFragment.arguments?.getSerializable("conversation") as? Conversation
                val conversationId = conversation?.id
                if (conversationId != ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE) {
                    // 对话ID不是人工客服，清除currentConversationId
                    if (currentConversationId == ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE) {
                        Log.d(TAG, "MainActivity onResume: ChatFragment的对话ID不是人工客服，清除currentConversationId")
                        currentConversationId = null
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "检查当前Fragment失败: ${e.message}", e)
        }
        
        // 重要：确保WebSocket连接存在且globalMessageListener已设置
        // 这确保无论何时收到消息，都能正确处理
        ensureCustomerServiceWebSocketListener()
        
        // Fragment会自己管理状态
        
        // 检查悬浮窗权限（用户可能从权限设置页面返回）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (android.provider.Settings.canDrawOverlays(this)) {
                // 权限已授予，重新初始化悬浮球（如果伴随模式开启）
                if (TaskIndicatorOverlayManager.isCompanionModeEnabled(this)) {
                    // 重新显示悬浮球（如果未显示会自动显示，如果已显示会更新状态）
                    TaskIndicatorOverlayManager.showCompanionMode(this)
                }
            }
        }
        
        // 检查无障碍服务状态（用户可能从设置页面返回，伴随模式使用）
        try {
            val pendingQuery = pendingQueryFromCompanion
            val pendingFragment = pendingChatFragmentFromCompanion
            if (pendingQuery != null && pendingFragment != null) {
                // 检查截图权限和无障碍服务是否都已就绪
                val chatService = ChatScreenshotService.getInstance()
                val isScreenshotReady = chatService != null && chatService.isReady()
                val isAccessibilityReady = isAccessibilityServiceEnabled()
                
                Log.d(TAG, "onResume: 检查权限状态 - 截图权限就绪=$isScreenshotReady, 无障碍权限就绪=$isAccessibilityReady, 待发送query=$pendingQuery")
                
                if (isScreenshotReady && isAccessibilityReady) {
                    // 权限都已就绪，如果是通过悬浮球发起的任务，提示用户切换回原应用
                    if (isTaskFromCompanionMode) {
                        // 检查是否已有弹窗显示，避免重复显示
                        if (switchAppDialog == null || !switchAppDialog!!.isShowing) {
                            Log.d(TAG, "onResume: 所有权限都已就绪，通过悬浮球发起任务，显示切换提示弹窗")
                            showSwitchBackAppDialog {
                                val queryToSend = pendingQuery
                                val fragmentToUse = pendingFragment
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                    delay(800) // 等待用户切换完成
                                    Log.d(TAG, "用户已切换回原应用，开始执行任务: $queryToSend")
                                    getScreenshotAndSendTask(queryToSend, fragmentToUse)
                                }
                            }
                        }
                    } else {
                        // 不是通过悬浮球发起的，正常流程
                        val queryToSend = pendingQuery
                        val fragmentToUse = pendingFragment
                        // 注意：不要在这里清除pendingQueryFromCompanion，让getScreenshotAndSendTask来清除
                        // 这样可以确保在真正执行任务时才清除，避免重复发送
                        Log.d(TAG, "onResume: 权限都已就绪，自动继续发送消息: $queryToSend")
                        // 延迟一下确保界面已完全恢复
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            getScreenshotAndSendTask(queryToSend, fragmentToUse)
                            // 发送任务后，如果是从外部应用发起的，且不是从伴随模式发起的，切回原应用（任务会在后台执行）
                            if (isTaskFromExternalApp && originalPackageName != null) {
                                Log.d(TAG, "onResume - 检查跳转逻辑: isTaskFromExternalApp=$isTaskFromExternalApp, originalPackageName=$originalPackageName, isTaskFromCompanionMode=$isTaskFromCompanionMode")
                                if (!isTaskFromCompanionMode) {
                                    Log.d(TAG, "onResume - 非伴随模式，准备跳转回原应用: $originalPackageName")
                                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                        delay(500) // 等待任务发送完成
                                        Log.d(TAG, "onResume - 开始执行跳转回原应用")
                                        switchToOriginalApp()
                                    }
                                } else {
                                    Log.d(TAG, "onResume - 任务从伴随模式发起，不跳转回原应用")
                                }
                            } else {
                                Log.d(TAG, "onResume - 不需要跳转: isTaskFromExternalApp=$isTaskFromExternalApp, originalPackageName=$originalPackageName")
                            }
                        }, 500)
                    }
                } else {
                    Log.d(TAG, "onResume: 权限未完全就绪，等待用户授权")
                    
                    // 如果是从外部应用发起的，且没有待发送的消息，且不是从伴随模式发起的，切回原应用
                    // 这种情况可能是用户从设置页面返回但权限仍未就绪
                    // 注意：只有在明确是从外部应用发起且不是伴随模式时才切回，避免误判
                    if (isTaskFromExternalApp && originalPackageName != null && !isTaskFromCompanionMode) {
                        // 额外检查：确保当前不在ChatFragment中（避免在权限授权过程中误切回）
                        // 使用getChatFragment()检查所有Fragment（包括隐藏的）
                        val currentFragment = getActiveFragment()
                        val chatFragmentForCheck = getChatFragment()
                        val isInChatFragment = currentFragment is ChatFragment || (chatFragmentForCheck != null && !chatFragmentForCheck.isHidden)
                        
                        if (!isInChatFragment) {
                            // 不在ChatFragment中，可能是从外部应用发起的，可以切回
                            Log.d(TAG, "onResume - 权限未就绪，非伴随模式，不在ChatFragment中，准备跳转回原应用: $originalPackageName")
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                delay(500)
                                Log.d(TAG, "onResume - 权限未就绪，开始执行跳转回原应用")
                                switchToOriginalApp()
                            }
                        } else {
                            // 在ChatFragment中，可能是权限授权过程中，不切回
                            Log.d(TAG, "onResume - 权限未就绪，但在ChatFragment中，可能是权限授权过程中，不切回原应用")
                        }
                    } else {
                        Log.d(TAG, "onResume - 权限未就绪，不需要跳转: isTaskFromExternalApp=$isTaskFromExternalApp, originalPackageName=$originalPackageName, isTaskFromCompanionMode=$isTaskFromCompanionMode")
                    }
                }
            } else {
                // 如果是从外部应用发起的，且不是从伴随模式发起的，切回原应用
                // 注意：只有在明确是从外部应用发起且不是伴随模式时才切回，避免误判
                if (isTaskFromExternalApp && originalPackageName != null && !isTaskFromCompanionMode) {
                    // 额外检查：确保当前不在ChatFragment中（避免在权限授权后误切回）
                    // 使用getChatFragment()检查所有Fragment（包括隐藏的）
                    val currentFragment = getActiveFragment()
                    val chatFragmentForCheck = getChatFragment()
                    val isInChatFragment = currentFragment is ChatFragment || (chatFragmentForCheck != null && !chatFragmentForCheck.isHidden)
                    
                    if (!isInChatFragment) {
                        // 不在ChatFragment中，可能是从外部应用发起的，可以切回
                        Log.d(TAG, "onResume - 无待发送消息，非伴随模式，不在ChatFragment中，准备跳转回原应用: $originalPackageName")
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            delay(500)
                            Log.d(TAG, "onResume - 无待发送消息，开始执行跳转回原应用")
                            switchToOriginalApp()
                        }
                    } else {
                        // 在ChatFragment中，可能是权限授权后返回，不切回
                        Log.d(TAG, "onResume - 无待发送消息，但在ChatFragment中，可能是权限授权后返回，不切回原应用")
                    }
                } else {
                    Log.d(TAG, "onResume - 无待发送消息，不需要跳转: isTaskFromExternalApp=$isTaskFromExternalApp, originalPackageName=$originalPackageName, isTaskFromCompanionMode=$isTaskFromCompanionMode")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "检查权限状态失败: ${e.message}", e)
        }
        
        // 检查伴随模式
        // 如果正在从悬浮球录制切换，不执行任何操作（避免触发TopoClaw）
        if (!isSwitchingFromOverlayRecording) {
            if (TaskIndicatorOverlayManager.isCompanionModeEnabled(this)) {
                // 伴随模式开启，显示悬浮球
                TaskIndicatorOverlayManager.showCompanionMode(this)
            } else {
                // 应用回到前台时，隐藏任务指示器（如果没有任务运行）
                if (!isTaskRunning) {
                    TaskIndicatorOverlayManager.hide()
                }
            }
        } else {
            Log.d(TAG, "onResume: 正在从悬浮球录制切换，跳过伴随模式相关操作")
        }
        
        // 检查当前Fragment，决定是否显示底部导航栏
        // 只有三个主页面显示底部导航栏：ConversationListFragment, FriendFragment, ProfileFragment
        val currentFragment = getActiveFragment()
        
        // 如果当前Fragment不是ChatFragment，检查是否有隐藏的ChatFragment
        // 如果有隐藏的ChatFragment，说明用户可能在权限授权过程中，不应该显示底部导航栏
        val hasHiddenChatFragment = if (currentFragment !is ChatFragment) {
            val hiddenChatFragment = getChatFragment()?.takeIf { it.isHidden }
            hiddenChatFragment != null
        } else {
            false
        }
        
        when (currentFragment) {
            is ConversationListFragment -> {
                // ConversationListFragment有自己的标题栏和Fragment层级的底部导航栏
                // Activity层级的底部导航栏应该保持隐藏，由Fragment层级的底部导航栏替代
                hideActionBarWithoutAnimation()
                setBottomNavigationVisibility(false) // Activity层级的底部导航栏保持隐藏
            }
            is FriendFragment,
            is ProfileFragment -> {
                // 主页面有自己的标题栏，隐藏ActionBar
                hideActionBarWithoutAnimation()
                // 只有在没有隐藏的ChatFragment时才显示底部导航栏
                // 如果有隐藏的ChatFragment，说明用户可能在权限授权过程中，不应该显示底部导航栏
                if (!hasHiddenChatFragment) {
                    // 主页面显示底部导航栏
                    setBottomNavigationVisibility(true)
                }
            }
            is SettingsFragment,
            is LogFragment,
            is VersionInfoFragment -> {
                // 设置页面、日志页面、版本信息页面隐藏ActionBar和底部导航栏
                supportActionBar?.hide()
                setBottomNavigationVisibility(false)
            }
            is FriendRecordFragment -> {
                // 好友记录页面隐藏ActionBar和底部导航栏（不需要TopoClaw顶部导航栏）
                hideActionBarWithoutAnimation()
                setBottomNavigationVisibility(false)
            }
            else -> {
                // 其他页面（ChatFragment, ConversationProfileFragment, ProfileDetailFragment, TestFragment等）
                // 会自己管理底部导航栏的显示/隐藏，这里不做处理
            }
        }
        
        // 检查 ChatFragment 是否有待发送的消息（无障碍服务授权后）
        // 即使当前不在 ChatFragment，也要检查后台栈中的 ChatFragment
        // 注意：不自动切换Fragment，让ChatFragment的onResume自己处理
        try {
            val fragments = supportFragmentManager.fragments
            for (fragment in fragments) {
                if (fragment is ChatFragment && fragment.isAdded) {
                    val hasPendingQuery = fragment.checkAndHandlePendingAccessibilityQuery()
                    if (hasPendingQuery) {
                        Log.d(TAG, "onResume: 发现 ChatFragment 有待发送的消息，已触发自动发送")
                        // 不自动切换Fragment，避免从权限设置页面返回时回退到主页
                        // ChatFragment的onResume会自己处理待发送的消息
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "检查 ChatFragment 待发送消息失败: ${e.message}", e)
        }
        
        // 更新菜单，确保显示正确的菜单项
        invalidateOptionsMenu()
    }

    private fun maybeRequestNotificationPermissionWithCooldown() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val firstRequestDone = prefs.getBoolean(PREF_KEY_NOTIFICATION_FIRST_REQUEST_DONE, false)
        val lastRequestAt = prefs.getLong(PREF_KEY_NOTIFICATION_LAST_REQUEST_AT, 0L)
        val now = System.currentTimeMillis()

        if (!firstRequestDone) {
            Log.d(TAG, "首次打开应用，直接请求通知权限")
            requestNotificationPermission(now)
            return
        }

        val elapsed = now - lastRequestAt
        if (elapsed < NOTIFICATION_PERMISSION_RETRY_INTERVAL_MS) {
            Log.d(TAG, "通知权限请求冷却中，剩余 ${NOTIFICATION_PERMISSION_RETRY_INTERVAL_MS - elapsed} ms")
            return
        }

        if (!shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            Log.d(TAG, "通知权限不可再次弹窗，跳过系统权限请求")
            return
        }

        Log.d(TAG, "通知权限二次触发，满足三天冷却，发起系统权限弹窗")
        requestNotificationPermission(now)
    }

    private fun requestNotificationPermission(requestAt: Long = System.currentTimeMillis()) {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(PREF_KEY_NOTIFICATION_FIRST_REQUEST_DONE, true)
            .putLong(PREF_KEY_NOTIFICATION_LAST_REQUEST_AT, requestAt)
            .apply()
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    
    /**
     * 隐藏ActionBar（无动画版本 - 保守方案）
     * 只使用官方API + 禁用窗口动画，不使用反射或直接操作View
     * 供Fragment调用
     */
    fun hideActionBarWithoutAnimation() {
        try {
            supportActionBar?.let { actionBar ->
                // 禁用窗口动画
                val originalAnimation = window.attributes.windowAnimations
                window.setWindowAnimations(0)
                
                // 使用官方API隐藏
                actionBar.hide()
                
                // 延迟恢复窗口动画，确保隐藏完成
                window.decorView.postDelayed({
                    window.setWindowAnimations(originalAnimation)
                }, 100)
            }
        } catch (e: Exception) {
            Log.w(TAG, "隐藏ActionBar失败: ${e.message}", e)
            supportActionBar?.hide()
        }
    }
    
    /**
     * 瞬间隐藏ActionBar（无任何动画效果）
     * 使用更彻底的方式禁用动画并隐藏ActionBar
     */
    fun hideActionBarInstantly() {
        try {
            supportActionBar?.let { actionBar ->
                // 方法1：禁用所有窗口动画
                val originalAnimation = window.attributes.windowAnimations
                window.setWindowAnimations(0)
                
                // 方法2：禁用ActionBar的动画（通过设置窗口属性）
                // 先隐藏，然后立即强制布局更新
                actionBar.hide()
                
                // 方法3：强制立即更新布局，避免动画
                window.decorView.requestLayout()
                window.decorView.invalidate()
                
                // 延迟恢复窗口动画（给足够时间让隐藏完成）
                window.decorView.postDelayed({
                    window.setWindowAnimations(originalAnimation)
                }, 300) // 增加延迟时间，确保隐藏完全完成
            } ?: run {
                // 如果ActionBar不存在，直接返回
                Log.d(TAG, "ActionBar不存在，无需隐藏")
            }
        } catch (e: Exception) {
            Log.w(TAG, "瞬间隐藏ActionBar失败: ${e.message}", e)
            // 回退到普通隐藏方法
            hideActionBarWithoutAnimation()
        }
    }
    
    /**
     * 瞬间显示ActionBar（无任何动画效果）
     * 使用更彻底的方式禁用动画并显示ActionBar
     */
    fun showActionBarInstantly() {
        try {
            supportActionBar?.let { actionBar ->
                // 方法1：禁用所有窗口动画
                val originalAnimation = window.attributes.windowAnimations
                window.setWindowAnimations(0)
                
                // 方法2：禁用ActionBar的动画（通过设置窗口属性）
                // 先显示，然后立即强制布局更新
                actionBar.show()
                
                // 方法3：强制立即更新布局，避免动画
                window.decorView.requestLayout()
                window.decorView.invalidate()
                
                // 延迟恢复窗口动画（给足够时间让显示完成）
                window.decorView.postDelayed({
                    window.setWindowAnimations(originalAnimation)
                }, 300) // 增加延迟时间，确保显示完全完成
            } ?: run {
                // 如果ActionBar不存在，直接返回
                Log.d(TAG, "ActionBar不存在，无需显示")
            }
        } catch (e: Exception) {
            Log.w(TAG, "瞬间显示ActionBar失败: ${e.message}", e)
            // 回退到普通显示方法
            showActionBarWithoutAnimation()
        }
    }
    
    /**
     * 显示ActionBar（无动画版本 - 保守方案）
     * 只使用官方API + 禁用窗口动画
     */
    fun showActionBarWithoutAnimation() {
        try {
            supportActionBar?.let { actionBar ->
                // 禁用窗口动画
                val originalAnimation = window.attributes.windowAnimations
                window.setWindowAnimations(0)
                
                // 使用官方API显示
                actionBar.show()
                
                // 延迟恢复窗口动画，确保显示完成
                window.decorView.postDelayed({
                    window.setWindowAnimations(originalAnimation)
                }, 100)
            }
        } catch (e: Exception) {
            Log.w(TAG, "显示ActionBar失败: ${e.message}", e)
            supportActionBar?.show()
        }
    }
    
    /**
     * 设置底部导航栏的可见性（供Fragment调用）
     */
    fun setBottomNavigationVisibility(visible: Boolean) {
        val targetVisibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE
        // 如果已经是目标状态，不重复设置，避免闪烁
        if (binding.bottomNavigation.visibility != targetVisibility) {
            binding.bottomNavigation.visibility = targetVisibility
        }
    }
    
    /**
     * 设置底部导航栏的选中项（供Fragment调用）
     * @param itemId 导航项ID，如 R.id.nav_chat
     */
    fun setBottomNavigationSelectedItem(itemId: Int) {
        binding.bottomNavigation.selectedItemId = itemId
    }
    
    /**
     * 设置状态栏和导航栏颜色（供Fragment调用）
     * @param color 颜色值，如 android.graphics.Color.WHITE 或 0xFFF5F5F5.toInt()
     * @param lightStatusBar 是否使用浅色状态栏图标（深色图标）
     */
    fun setStatusBarColor(color: Int, lightStatusBar: Boolean = false) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            window.statusBarColor = color
            // 同时设置导航栏（底部系统虚拟按键区域）颜色
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                window.navigationBarColor = color
            }
            if (lightStatusBar) {
                window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = color
            // 同时设置导航栏（底部系统虚拟按键区域）颜色
            window.navigationBarColor = color
        }
    }
    
    /**
     * 设置底部导航栏背景颜色（供Fragment调用）
     * @param color 颜色值，如 android.graphics.Color.WHITE 或 0xFFF5F5F5.toInt()
     */
    fun setBottomNavigationBackgroundColor(color: Int) {
        binding.bottomNavigation.setBackgroundColor(color)
    }
    
    /**
     * 为Fragment的底部导航栏初始化并更新聊天图标徽章（供Fragment调用）
     * @param bottomNavigationView Fragment中的BottomNavigationView
     */
    fun initAndUpdateFragmentChatBadge(bottomNavigationView: com.google.android.material.bottomnavigation.BottomNavigationView) {
        try {
            val menuItemId = bottomNavigationView.menu.findItem(R.id.nav_chat)?.itemId
            if (menuItemId != null) {
                // 创建或获取徽章
                val badge = bottomNavigationView.getOrCreateBadge(menuItemId)
                badge.backgroundColor = android.graphics.Color.parseColor("#FF0000") // 红色背景
                badge.badgeTextColor = android.graphics.Color.WHITE // 白色文字
                badge.maxCharacterCount = 3 // 最大显示3个字符（用于显示99+）
                
                // 更新徽章显示
                val totalUnreadCount = UnreadCountHelper.getTotalUnreadCount(this)
                if (totalUnreadCount > 0) {
                    if (totalUnreadCount > 99) {
                        // 超过99，显示"99+"
                        badge.number = 999 // BadgeDrawable会自动显示为"99+"
                        badge.isVisible = true
                    } else {
                        // 99以内，显示数字
                        badge.number = totalUnreadCount
                        badge.isVisible = true
                    }
                } else {
                    // 没有未读消息，隐藏徽章
                    badge.isVisible = false
                }
                
                Log.d(TAG, "Fragment底部导航栏徽章已更新: 未读数=$totalUnreadCount")
            }
        } catch (e: Exception) {
            Log.e(TAG, "初始化Fragment底部导航栏徽章失败: ${e.message}", e)
        }
    }
    
    /**
     * 更新技能图标徽章（主Activity的底部导航栏）
     */
    fun updateSkillBadge() {
        try {
            val menuItemId = binding.bottomNavigation.menu.findItem(R.id.nav_skill)?.itemId
            if (menuItemId != null) {
                val badge = binding.bottomNavigation.getOrCreateBadge(menuItemId)
                badge.backgroundColor = android.graphics.Color.parseColor("#FF0000") // 红色背景
                badge.badgeTextColor = android.graphics.Color.WHITE // 白色文字
                badge.maxCharacterCount = 3 // 最大显示3个字符（用于显示99+）
                
                val newHotSkillsCount = HotSkillBadgeManager.getNewHotSkillsCount(this)
                if (newHotSkillsCount > 0) {
                    if (newHotSkillsCount > 99) {
                        badge.number = 999 // BadgeDrawable会自动显示为"99+"
                    } else {
                        badge.number = newHotSkillsCount
                    }
                    badge.isVisible = true
                } else {
                    badge.isVisible = false
                }
                
                Log.d(TAG, "技能图标徽章已更新: 新热门技能数=$newHotSkillsCount")
            }
        } catch (e: Exception) {
            Log.e(TAG, "更新技能图标徽章失败: ${e.message}", e)
        }
    }
    
    /**
     * 初始化并更新Fragment层级的底部导航栏技能图标徽章
     * @param bottomNavigationView Fragment中的BottomNavigationView
     */
    fun initAndUpdateFragmentSkillBadge(bottomNavigationView: com.google.android.material.bottomnavigation.BottomNavigationView) {
        try {
            val menuItemId = bottomNavigationView.menu.findItem(R.id.nav_skill)?.itemId
            if (menuItemId != null) {
                val badge = bottomNavigationView.getOrCreateBadge(menuItemId)
                badge.backgroundColor = android.graphics.Color.parseColor("#FF0000") // 红色背景
                badge.badgeTextColor = android.graphics.Color.WHITE // 白色文字
                badge.maxCharacterCount = 3 // 最大显示3个字符（用于显示99+）
                
                val newHotSkillsCount = HotSkillBadgeManager.getNewHotSkillsCount(this)
                if (newHotSkillsCount > 0) {
                    if (newHotSkillsCount > 99) {
                        badge.number = 999 // BadgeDrawable会自动显示为"99+"
                    } else {
                        badge.number = newHotSkillsCount
                    }
                    badge.isVisible = true
                } else {
                    badge.isVisible = false
                }
                
                Log.d(TAG, "Fragment技能图标徽章已更新: 新热门技能数=$newHotSkillsCount")
            }
        } catch (e: Exception) {
            Log.e(TAG, "初始化Fragment技能图标徽章失败: ${e.message}", e)
        }
    }
    
    /**
     * 立即检查热门技能（同步云端并更新徽章）
     */
    private fun checkHotSkillsImmediately() {
        CoroutineScope(Dispatchers.IO + coroutineExceptionHandler).launch {
            try {
                Log.d(TAG, "立即检查热门技能...")
                // 从云端同步技能
                val result = SkillManager.syncSkillsFromService(
                    context = this@MainActivity,
                    skillServiceUrl = ServiceUrlConfig.getSkillCommunityUrl(this@MainActivity)
                )
                
                if (result.success) {
                    Log.d(TAG, "热门技能检查完成: 同步${result.syncedCount}个, 跳过${result.skippedCount}个")
                    // 更新徽章
                    withContext(Dispatchers.Main) {
                        updateSkillBadge()
                        // 同时更新Fragment中的徽章
                        val skillFragment = supportFragmentManager.fragments.find { it is SkillFragment } as? SkillFragment
                        skillFragment?.updateHotSkillBadge()
                    }
                } else {
                    Log.w(TAG, "热门技能检查失败: ${result.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "立即检查热门技能异常: ${e.message}", e)
            }
        }
    }
    
    /**
     * 启动定期检查热门技能任务
     */
    private fun startHotSkillCheckTask() {
        stopHotSkillCheckTask() // 先停止之前的任务
        
        hotSkillCheckJob = CoroutineScope(Dispatchers.IO + coroutineExceptionHandler).launch {
            while (coroutineContext.isActive && isAppInForeground) {
                try {
                    Log.d(TAG, "定期检查热门技能...")
                    // 从云端同步技能
                    val result = SkillManager.syncSkillsFromService(
                        context = this@MainActivity,
                        skillServiceUrl = ServiceUrlConfig.getSkillCommunityUrl(this@MainActivity)
                    )
                    
                    if (result.success) {
                        Log.d(TAG, "定期检查热门技能完成: 同步${result.syncedCount}个, 跳过${result.skippedCount}个")
                        // 更新徽章
                        withContext(Dispatchers.Main) {
                            updateSkillBadge()
                            // 同时更新Fragment中的徽章
                            val skillFragment = supportFragmentManager.fragments.find { it is SkillFragment } as? SkillFragment
                            skillFragment?.updateHotSkillBadge()
                        }
                    } else {
                        Log.w(TAG, "定期检查热门技能失败: ${result.message}")
                    }
                    
                    // 等待指定间隔后再次检查
                    delay(hotSkillCheckInterval)
                } catch (e: Exception) {
                    Log.e(TAG, "定期检查热门技能异常: ${e.message}", e)
                    // 出错后等待一段时间再重试
                    delay(hotSkillCheckInterval)
                }
            }
        }
        Log.d(TAG, "定期检查热门技能任务已启动，间隔: ${hotSkillCheckInterval / 1000}秒")
    }
    
    /**
     * 停止定期检查热门技能任务
     */
    private fun stopHotSkillCheckTask() {
        hotSkillCheckJob?.cancel()
        hotSkillCheckJob = null
        Log.d(TAG, "定期检查热门技能任务已停止")
    }
    
    /**
     * 获取当前可见的 Fragment（兼容 show/hide 多 Fragment 共存场景）
     */
    fun getActiveFragment(): Fragment? {
        return supportFragmentManager.fragments.findLast { it.isAdded && !it.isHidden }
            ?: supportFragmentManager.findFragmentById(R.id.fragmentContainer)
    }

    /**
     * 切换到指定 tab Fragment，使用 show/hide 复用已有实例
     */
    fun switchToTabFragment(itemId: Int, createFragment: () -> Fragment): Boolean {
        val current = getActiveFragment()
        val target = tabFragments[itemId]

        if (target != null && target.isAdded && !target.isHidden && target == current) {
            return true
        }

        val transaction = supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.no_animation, R.anim.no_animation, R.anim.no_animation, R.anim.no_animation)

        // 隐藏所有当前可见的 Fragment（tab + 可能残留的其他 Fragment）
        supportFragmentManager.fragments.forEach { f ->
            if (f.isAdded && !f.isHidden) {
                transaction.hide(f)
            }
        }

        val fragment: Fragment
        if (target != null && target.isAdded) {
            fragment = target
            transaction.show(fragment)
        } else {
            fragment = createFragment()
            tabFragments[itemId] = fragment
            transaction.add(R.id.fragmentContainer, fragment)
        }

        transaction.commitAllowingStateLoss()
        return true
    }

    /**
     * 设置Fragment层级的底部导航栏（供Fragment调用）
     * @param bottomNavigationView Fragment中的BottomNavigationView
     * @param selectedItemId 当前选中的导航项ID
     */
    fun setupFragmentBottomNavigation(bottomNavigationView: com.google.android.material.bottomnavigation.BottomNavigationView, selectedItemId: Int) {
        val listener = com.google.android.material.navigation.NavigationBarView.OnItemSelectedListener { item ->
            val currentFragment = getActiveFragment()

            when (item.itemId) {
                R.id.nav_chat -> {
                    if (currentFragment is ConversationListFragment) return@OnItemSelectedListener true
                    hideActionBarWithoutAnimation()
                    switchToTabFragment(R.id.nav_chat) { ConversationListFragment() }
                    binding.root.post { invalidateOptionsMenu() }
                    true
                }
                R.id.nav_friend -> {
                    if (currentFragment is FriendFragment) return@OnItemSelectedListener true
                    hideActionBarWithoutAnimation()
                    switchToTabFragment(R.id.nav_friend) { FriendFragment() }
                    binding.root.post { invalidateOptionsMenu() }
                    true
                }
                R.id.nav_skill -> {
                    if (currentFragment is SkillFragment) return@OnItemSelectedListener true
                    hideActionBarWithoutAnimation()
                    switchToTabFragment(R.id.nav_skill) { SkillFragment() }
                    binding.root.post { invalidateOptionsMenu() }
                    true
                }
                R.id.nav_assistant_plaza -> {
                    if (currentFragment is AssistantPlazaFragment) return@OnItemSelectedListener true
                    hideActionBarWithoutAnimation()
                    switchToTabFragment(R.id.nav_assistant_plaza) { AssistantPlazaFragment() }
                    binding.root.post { invalidateOptionsMenu() }
                    true
                }
                R.id.nav_profile -> {
                    if (currentFragment is ServiceSettingsFragment) return@OnItemSelectedListener true
                    if (currentFragment is ProfileFragment) return@OnItemSelectedListener true
                    hideActionBarWithoutAnimation()
                    switchToTabFragment(R.id.nav_profile) { ProfileFragment() }
                    binding.root.post { invalidateOptionsMenu() }
                    true
                }
                else -> false
            }
        }

        bottomNavigationView.setOnItemSelectedListener(null)
        bottomNavigationView.selectedItemId = selectedItemId
        bottomNavigationView.setOnItemSelectedListener(listener)
    }
    
    /**
     * 设置Fragment层级的底部导航栏背景颜色（供Fragment调用）
     * @param bottomNavigationView Fragment中的BottomNavigationView
     * @param color 颜色值，如 android.graphics.Color.WHITE 或 0xFFF5F5F5.toInt()
     */
    fun setFragmentBottomNavigationBackgroundColor(bottomNavigationView: com.google.android.material.bottomnavigation.BottomNavigationView, color: Int) {
        bottomNavigationView.setBackgroundColor(color)
    }
    
    /**
     * 隐藏状态栏（全屏模式）
     */
    fun hideStatusBar() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // API 30+ 使用 WindowInsetsController
            window.insetsController?.let { controller ->
                controller.hide(android.view.WindowInsets.Type.statusBars())
            }
        } else {
            // API < 30 使用 systemUiVisibility
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }
    
    /**
     * 显示状态栏（退出全屏模式）
     */
    fun showStatusBar() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            // API 30+ 使用 WindowInsetsController
            window.insetsController?.let { controller ->
                controller.show(android.view.WindowInsets.Type.statusBars())
            }
        } else {
            // API < 30 使用 systemUiVisibility
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
        // 恢复状态栏颜色为白色
        setStatusBarColor(android.graphics.Color.WHITE, lightStatusBar = true)
    }
    
    /**
     * 根据当前Fragment更新UI（ActionBar和底部导航栏）
     */
    fun updateUIForCurrentFragment() {
        val currentFragment = getActiveFragment()
        when (currentFragment) {
            is ConversationListFragment -> {
                // ConversationListFragment有自己的标题栏，隐藏ActionBar（无动画版本）
                // Fragment层级的底部导航栏由Fragment自己管理，Activity层级的保持隐藏
                hideActionBarWithoutAnimation()
                setBottomNavigationVisibility(false) // Activity层级的底部导航栏保持隐藏
                // 设置状态栏颜色为浅灰色，与顶部导航栏保持一致
                setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
            }
            is ServiceSettingsFragment -> {
                // ServiceSettingsFragment有自己的标题栏，隐藏ActionBar（无动画版本）
                // 服务页面现在从个人主页进入，不显示底部导航栏
                hideActionBarWithoutAnimation()
                setBottomNavigationVisibility(false)
                // 设置状态栏颜色为浅灰色，与顶部导航栏保持一致
                setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
            }
            is FriendFragment -> {
                // FriendFragment有自己的标题栏，隐藏ActionBar（无动画版本）
                // Fragment层级的底部导航栏由Fragment自己管理，Activity层级的保持隐藏
                hideActionBarWithoutAnimation()
                setBottomNavigationVisibility(false) // Activity层级的底部导航栏保持隐藏
                // 设置状态栏颜色为浅灰色，与顶部导航栏保持一致
                setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
            }
            is SkillFragment -> {
                // SkillFragment有自己的标题栏，隐藏ActionBar（无动画版本）
                // Fragment层级的底部导航栏由Fragment自己管理，Activity层级的保持隐藏
                hideActionBarWithoutAnimation()
                setBottomNavigationVisibility(false) // Activity层级的底部导航栏保持隐藏
                // 设置状态栏颜色为浅灰色，与顶部导航栏保持一致
                setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
            }
            is ProfileFragment -> {
                // ProfileFragment有自己的标题栏，隐藏ActionBar（无动画版本）
                // Fragment层级的底部导航栏由Fragment自己管理，Activity层级的保持隐藏
                hideActionBarWithoutAnimation()
                setBottomNavigationVisibility(false) // Activity层级的底部导航栏保持隐藏
                // 设置状态栏颜色为浅灰色，与顶部导航栏保持一致
                setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
            }
            is AssistantPlazaFragment -> {
                // 助手广场有自己的标题栏，隐藏ActionBar（无动画版本）
                hideActionBarWithoutAnimation()
                setBottomNavigationVisibility(false)
                setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
            }
            is ChatFragment,
            is ConversationProfileFragment,
            is ProfileDetailFragment,
            is UserProfileFragment,
            is ServerSettingsFragment,
            is ChatContextHistoryFragment,
            is SettingsFragment,
            is LogFragment,
            is VersionInfoFragment -> {
                // 这些Fragment会自己管理ActionBar和底部导航栏，不做处理
            }
            is NewAssistantFragment -> {
                // 新建小助手页面隐藏应用ActionBar（页面有自有返回栏）
                hideActionBarWithoutAnimation()
                setBottomNavigationVisibility(false)
                setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
            }
            is FriendRecordFragment -> {
                // 好友记录页面隐藏ActionBar和底部导航栏
                hideActionBarWithoutAnimation()
                setBottomNavigationVisibility(false)
            }
            is TestFragment -> {
                // TestFragment有自己的标题栏，隐藏ActionBar（无动画版本）
                hideActionBarWithoutAnimation()
                setBottomNavigationVisibility(false)
            }
            is TrajectorySettingsFragment,
            is TrajectoryRecordFragment,
            is TrajectoryEventDetailFragment -> {
                // 轨迹相关页面有自己的标题栏，隐藏ActionBar（无动画版本）
                hideActionBarWithoutAnimation()
                setBottomNavigationVisibility(false)
                // 设置状态栏颜色为浅灰色，与顶部导航栏保持一致
                setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
            }
            is SkillDetailFragment -> {
                // 技能详情页隐藏ActionBar
                supportActionBar?.hide()
                setBottomNavigationVisibility(false)
            }
            is MyQRCodeFragment -> {
                // 我的二维码页面隐藏ActionBar和底部导航栏
                hideActionBarWithoutAnimation()
                setBottomNavigationVisibility(false)
            }
            is ScanQRCodeFragment -> {
                // 扫一扫页面隐藏ActionBar、底部导航栏和状态栏（全屏模式）
                hideActionBarWithoutAnimation()
                setBottomNavigationVisibility(false)
                hideStatusBar()
            }
            is AccessibilityGuideFragment,
            is FloatingBallGuideFragment,
            is ContactUsFragment -> {
                // 小贴士详情页有自己的标题栏，隐藏ActionBar
                hideActionBarWithoutAnimation()
                setBottomNavigationVisibility(false)
                // 设置状态栏颜色为浅灰色，与顶部导航栏保持一致
                setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
            }
            null -> {
                // popBackStack 过渡期间 findFragmentById 可能短暂返回 null，延迟重试避免误显示 ActionBar
                binding.root.postDelayed({ updateUIForCurrentFragment() }, 80)
            }
            else -> {
                // 默认显示ActionBar，Activity层级的底部导航栏保持隐藏（由Fragment自己管理）
                showActionBarWithoutAnimation()
                setBottomNavigationVisibility(false) // Activity层级的底部导航栏保持隐藏
                // 恢复状态栏颜色为白色
                setStatusBarColor(android.graphics.Color.WHITE, lightStatusBar = true)
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        cacheForegroundConversationForRestore()
        // 标记应用在后台
        isAppInForeground = false
        Log.d(TAG, "应用进入后台")
        // 停止定期检查热门技能任务（节省资源）
        stopHotSkillCheckTask()
        
        // 检查悬浮窗功能是否开启
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val overlayEnabled = prefs.getBoolean("task_indicator_overlay_enabled", true) // 默认true（打开）
        
        // 应用进入后台且任务正在运行且悬浮窗功能开启时，显示任务指示器
        if (isTaskRunning && overlayEnabled) {
            TaskIndicatorOverlayManager.show(this)
        }
    }

    private fun cacheForegroundConversationForRestore() {
        try {
            val currentFragment = getActiveFragment()
            if (currentFragment is ChatFragment) {
                val conversation = currentFragment.arguments?.getSerializable("conversation") as? Conversation
                if (conversation != null) {
                    pendingForegroundRestoreConversation = conversation
                    pendingForegroundRestoreAtMs = System.currentTimeMillis()
                    Log.d(TAG, "onPause: 记录待恢复会话=${conversation.id}")
                    return
                }
            }
            // 非聊天页面不保留恢复态，避免用户本来就在列表/通讯录时被误恢复
            pendingForegroundRestoreConversation = null
            pendingForegroundRestoreAtMs = 0L
        } catch (e: Exception) {
            Log.w(TAG, "cacheForegroundConversationForRestore: 记录失败: ${e.message}", e)
            pendingForegroundRestoreConversation = null
            pendingForegroundRestoreAtMs = 0L
        }
    }

    private fun tryRestoreConversationAfterForegroundResume(): Boolean {
        val pendingConversation = pendingForegroundRestoreConversation ?: return false
        val age = System.currentTimeMillis() - pendingForegroundRestoreAtMs
        if (age > foregroundRestoreWindowMs) {
            pendingForegroundRestoreConversation = null
            pendingForegroundRestoreAtMs = 0L
            return false
        }

        val currentFragment = getActiveFragment()
        if (currentFragment is ChatFragment) {
            pendingForegroundRestoreConversation = null
            pendingForegroundRestoreAtMs = 0L
            return false
        }

        // 仅在误落回主列表页时恢复，避免干扰其他正常页面
        if (currentFragment !is ConversationListFragment && currentFragment !is FriendFragment) {
            return false
        }

        return try {
            Log.w(TAG, "onResume: 检测到从后台恢复后落在${currentFragment.javaClass.simpleName}，恢复到会话=${pendingConversation.id}")
            switchToChatFragment(pendingConversation)
            pendingForegroundRestoreConversation = null
            pendingForegroundRestoreAtMs = 0L
            true
        } catch (e: Exception) {
            Log.w(TAG, "tryRestoreConversationAfterForegroundResume: 恢复失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 初始化聊天图标徽章
     */
    private fun initChatBadge() {
        try {
            val menuItem = binding.bottomNavigation.menu.findItem(R.id.nav_chat)
            val menuItemId = menuItem?.itemId
            if (menuItemId != null) {
                // 使用 getOrCreateBadge 创建或获取徽章
                chatBadge = binding.bottomNavigation.getOrCreateBadge(menuItemId)
                chatBadge?.backgroundColor = android.graphics.Color.parseColor("#FF0000") // 红色背景
                chatBadge?.badgeTextColor = android.graphics.Color.WHITE // 白色文字
                chatBadge?.maxCharacterCount = 3 // 最大显示3个字符（用于显示99）
                chatBadge?.isVisible = false // 初始隐藏，由 updateChatBadge 控制显示
                
                Log.d(TAG, "聊天图标徽章初始化成功，menuItemId=$menuItemId")
                
                // 延迟创建自定义徽章视图（用于显示"..."），确保布局完成
                binding.root.postDelayed({
                    createCustomBadgeView()
                }, 500)
            } else {
                Log.w(TAG, "未找到聊天菜单项")
            }
        } catch (e: Exception) {
            Log.e(TAG, "初始化聊天图标徽章失败: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 创建自定义徽章视图（用于显示"99+"）
     */
    private fun createCustomBadgeView() {
        try {
            val bottomNavView = binding.bottomNavigation
            // 遍历BottomNavigationView的子视图，找到聊天菜单项
            val menuItemView = findMenuItemView(bottomNavView, R.id.nav_chat)
            
            if (menuItemView != null) {
                // 查找菜单项的父容器（通常是 BottomNavigationItemView）
                val parentView = menuItemView.parent as? ViewGroup
                if (parentView != null && parentView is android.widget.FrameLayout) {
                    // 检查是否已经存在自定义徽章
                    if (customBadgeView == null) {
                        // 创建自定义徽章TextView
                        customBadgeView = android.widget.TextView(this).apply {
                            text = "99+"
                            textSize = 10f
                            setTextColor(android.graphics.Color.WHITE)
                            gravity = android.view.Gravity.CENTER
                            setPadding(
                                (6 * resources.displayMetrics.density).toInt(),
                                (2 * resources.displayMetrics.density).toInt(),
                                (6 * resources.displayMetrics.density).toInt(),
                                (2 * resources.displayMetrics.density).toInt()
                            )
                            minWidth = (20 * resources.displayMetrics.density).toInt()
                            minHeight = (16 * resources.displayMetrics.density).toInt()
                            
                            // 创建红色圆形背景
                            val drawable = android.graphics.drawable.GradientDrawable().apply {
                                shape = android.graphics.drawable.GradientDrawable.OVAL
                                setColor(android.graphics.Color.parseColor("#FF0000"))
                            }
                            background = drawable
                            
                            // 设置布局参数，定位在右上角
                            val layoutParams = android.widget.FrameLayout.LayoutParams(
                                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                            ).apply {
                                gravity = android.view.Gravity.TOP or android.view.Gravity.END
                                marginEnd = (4 * resources.displayMetrics.density).toInt()
                                topMargin = (4 * resources.displayMetrics.density).toInt()
                            }
                            
                            visibility = View.GONE
                        }
                        
                        // 将自定义徽章添加到父容器
                        parentView.addView(customBadgeView)
                        Log.d(TAG, "自定义徽章视图已创建并添加到菜单项")
                    } else {
                        Log.d(TAG, "自定义徽章视图已存在，跳过创建")
                    }
                } else {
                    Log.w(TAG, "未找到合适的父容器（需要FrameLayout），parentView=${parentView?.javaClass?.simpleName}")
                }
            } else {
                Log.w(TAG, "未找到聊天菜单项视图，延迟重试")
                // 如果找不到，延迟重试
                binding.root.postDelayed({
                    createCustomBadgeView()
                }, 200)
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建自定义徽章视图失败: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 递归查找菜单项视图
     */
    private fun findMenuItemView(parent: ViewGroup, menuItemId: Int): View? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.id == menuItemId) {
                return child
            }
            if (child is ViewGroup) {
                val found = findMenuItemView(child, menuItemId)
                if (found != null) {
                    return found
                }
            }
        }
        return null
    }
    
    /**
     * 更新聊天图标徽章显示
     */
    private fun updateChatBadge() {
        try {
            val totalUnreadCount = UnreadCountHelper.getTotalUnreadCount(this)
            val badgeText = UnreadCountHelper.formatUnreadCount(totalUnreadCount)
            
            Log.d(TAG, "更新聊天图标徽章: 未读数=$totalUnreadCount, 显示=$badgeText")
            
            if (totalUnreadCount > 0) {
                if (totalUnreadCount > 99) {
                    // 超过99，使用自定义视图显示"99+"
                    chatBadge?.isVisible = false
                    // 确保自定义徽章视图已创建
                    if (customBadgeView == null) {
                        createCustomBadgeView()
                        // 延迟显示，等待视图创建完成
                        binding.root.postDelayed({
                            customBadgeView?.text = badgeText
                            customBadgeView?.visibility = View.VISIBLE
                            Log.d(TAG, "自定义徽章视图已显示（超过99）")
                        }, 600)
                    } else {
                        customBadgeView?.text = badgeText
                        customBadgeView?.visibility = View.VISIBLE
                        Log.d(TAG, "自定义徽章视图已显示（超过99）")
                    }
                } else {
                    // 99以内，使用BadgeDrawable显示数字
                    customBadgeView?.visibility = View.GONE
                    if (chatBadge != null) {
                        chatBadge?.number = totalUnreadCount
                        chatBadge?.isVisible = true
                        Log.d(TAG, "BadgeDrawable已显示: $totalUnreadCount")
                    } else {
                        Log.w(TAG, "BadgeDrawable未初始化，重新初始化")
                        initChatBadge()
                        binding.root.postDelayed({
                            chatBadge?.number = totalUnreadCount
                            chatBadge?.isVisible = true
                        }, 100)
                    }
                }
            } else {
                // 没有未读消息，隐藏所有徽章
                chatBadge?.isVisible = false
                customBadgeView?.visibility = View.GONE
                Log.d(TAG, "隐藏所有徽章（未读数=0）")
            }
        } catch (e: Exception) {
            Log.e(TAG, "更新聊天图标徽章失败: ${e.message}", e)
            e.printStackTrace()
        }
    }
    
    /**
     * 注册未读消息更新广播接收器
     */
    private fun registerUnreadCountReceiver() {
        unreadCountReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ConversationListFragment.ACTION_UNREAD_COUNT_UPDATED) {
                    Log.d(TAG, "收到未读消息更新广播，更新聊天图标徽章")
                    // 更新MainActivity的底部导航栏徽章
                    updateChatBadge()
                    
                    // 更新当前可见Fragment的底部导航栏徽章
                    updateCurrentFragmentBadge()
                }
            }
        }
        
        val filter = IntentFilter(ConversationListFragment.ACTION_UNREAD_COUNT_UPDATED)
        LocalBroadcastManager.getInstance(this).registerReceiver(unreadCountReceiver!!, filter)
        Log.d(TAG, "已注册未读消息更新广播接收器")
    }
    
    /**
     * 注册异常检测结果广播接收器
     */
    private fun registerAnomalyDetectionReceiver() {
        anomalyDetectionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ScreenshotService.ACTION_ANOMALY_DETECTION_RESULT -> {
                        val message = intent.getStringExtra(ScreenshotService.EXTRA_DETECTION_MESSAGE)
                        if (message != null) {
                            Log.d(TAG, "收到异常检测结果广播，转发给ChatFragment")
                            // 转发给ChatFragment显示系统消息
                            runOnUiThread {
                                val fragmentManager = supportFragmentManager
                                val chatFragment = fragmentManager.fragments.find { it is ChatFragment } as? ChatFragment
                                chatFragment?.let {
                                    it.addChatMessage("系统", message)
                                } ?: run {
                                    Log.w(TAG, "未找到ChatFragment，无法显示异常检测结果")
                                }
                            }
                        }
                    }
                    ScreenshotService.ACTION_CALL_USER_FROM_ANOMALY -> {
                        val callUserText = intent.getStringExtra(ScreenshotService.EXTRA_CALL_USER_TEXT)
                        if (callUserText != null) {
                            Log.d(TAG, "收到call_user动作广播（从异常检测触发），转发给ChatFragment")
                            // 转发给ChatFragment处理call_user
                            runOnUiThread {
                                val fragmentManager = supportFragmentManager
                                val chatFragment = fragmentManager.fragments.find { it is ChatFragment } as? ChatFragment
                                chatFragment?.let {
                                    it.handleCallUserFromAnomalyDetection(callUserText)
                                } ?: run {
                                    Log.w(TAG, "未找到ChatFragment，无法处理call_user动作")
                                }
                            }
                        }
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(ScreenshotService.ACTION_ANOMALY_DETECTION_RESULT)
            addAction(ScreenshotService.ACTION_CALL_USER_FROM_ANOMALY)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(anomalyDetectionReceiver!!, filter)
        Log.d(TAG, "已注册异常检测结果广播接收器")
    }
    
    /**
     * 更新当前可见Fragment的底部导航栏徽章
     */
    private fun updateCurrentFragmentBadge() {
        try {
            // 遍历所有Fragment，找到当前可见的Fragment并更新其徽章
            supportFragmentManager.fragments.forEach { fragment ->
                when (fragment) {
                    is ConversationListFragment -> {
                        if (fragment.isVisible && fragment.isAdded) {
                            try {
                                // 使用反射访问binding（因为binding是private的）
                                val bindingField = ConversationListFragment::class.java.getDeclaredField("_binding")
                                bindingField.isAccessible = true
                                val binding = bindingField.get(fragment) as? com.cloudcontrol.demo.databinding.FragmentConversationListBinding
                                binding?.let {
                                    initAndUpdateFragmentChatBadge(it.bottomNavigation)
                                    Log.d(TAG, "已更新ConversationListFragment的底部导航栏徽章")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "无法更新ConversationListFragment徽章: ${e.message}")
                            }
                        }
                    }
                    is FriendFragment -> {
                        if (fragment.isVisible && fragment.isAdded) {
                            try {
                                val bindingField = FriendFragment::class.java.getDeclaredField("_binding")
                                bindingField.isAccessible = true
                                val binding = bindingField.get(fragment) as? com.cloudcontrol.demo.databinding.FragmentFriendBinding
                                binding?.let {
                                    initAndUpdateFragmentChatBadge(it.bottomNavigation)
                                    Log.d(TAG, "已更新FriendFragment的底部导航栏徽章")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "无法更新FriendFragment徽章: ${e.message}")
                            }
                        }
                    }
                    is SkillFragment -> {
                        if (fragment.isVisible && fragment.isAdded) {
                            try {
                                val bindingField = SkillFragment::class.java.getDeclaredField("_binding")
                                bindingField.isAccessible = true
                                val binding = bindingField.get(fragment)
                                if (binding != null) {
                                    // 使用反射访问 bottomNavigation 字段
                                    val bottomNavField = binding.javaClass.getDeclaredField("bottomNavigation")
                                    bottomNavField.isAccessible = true
                                    val bottomNav = bottomNavField.get(binding) as? com.google.android.material.bottomnavigation.BottomNavigationView
                                    bottomNav?.let {
                                        initAndUpdateFragmentChatBadge(it)
                                    Log.d(TAG, "已更新SkillFragment的底部导航栏徽章")
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "无法更新SkillFragment徽章: ${e.message}")
                            }
                        }
                    }
                    is ProfileFragment -> {
                        if (fragment.isVisible && fragment.isAdded) {
                            try {
                                val bindingField = ProfileFragment::class.java.getDeclaredField("_binding")
                                bindingField.isAccessible = true
                                val binding = bindingField.get(fragment) as? com.cloudcontrol.demo.databinding.FragmentProfileBinding
                                binding?.let {
                                    initAndUpdateFragmentChatBadge(it.bottomNavigation)
                                    Log.d(TAG, "已更新ProfileFragment的底部导航栏徽章")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "无法更新ProfileFragment徽章: ${e.message}")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "更新当前Fragment底部导航栏徽章失败: ${e.message}", e)
        }
    }
    
    /**
     * 设置底部导航
     */
    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_chat -> {
                    hideActionBarWithoutAnimation()
                    switchToTabFragment(R.id.nav_chat) { ConversationListFragment() }
                    binding.root.post { invalidateOptionsMenu() }
                    true
                }
                R.id.nav_friend -> {
                    hideActionBarWithoutAnimation()
                    switchToTabFragment(R.id.nav_friend) { FriendFragment() }
                    binding.root.post { invalidateOptionsMenu() }
                    true
                }
                R.id.nav_skill -> {
                    hideActionBarWithoutAnimation()
                    switchToTabFragment(R.id.nav_skill) { SkillFragment() }
                    binding.root.post { invalidateOptionsMenu() }
                    true
                }
                R.id.nav_assistant_plaza -> {
                    hideActionBarWithoutAnimation()
                    switchToTabFragment(R.id.nav_assistant_plaza) { AssistantPlazaFragment() }
                    binding.root.post { invalidateOptionsMenu() }
                    true
                }
                R.id.nav_profile -> {
                    val currentFragment = getActiveFragment()
                    if (currentFragment is ServiceSettingsFragment) {
                        return@setOnItemSelectedListener true
                    }
                    hideActionBarWithoutAnimation()
                    switchToTabFragment(R.id.nav_profile) { ProfileFragment() }
                    binding.root.post { invalidateOptionsMenu() }
                    true
                }
                else -> false
            }
        }
    }
    
    // 无障碍服务状态监听器
    private var accessibilityStateListener: AccessibilityManagerCompat.AccessibilityStateChangeListener? = null
    
    /**
     * 注册无障碍服务状态监听器
     */
    private fun registerAccessibilityStateListener() {
        try {
            val accessibilityManager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
            
            // 移除旧的监听器（如果存在）
            accessibilityStateListener?.let {
                AccessibilityManagerCompat.removeAccessibilityStateChangeListener(accessibilityManager, it)
            }
            
            // 创建新的监听器（使用AccessibilityManagerCompat的类型）
            accessibilityStateListener = AccessibilityManagerCompat.AccessibilityStateChangeListener { enabled ->
                Log.d(TAG, "无障碍服务状态变化: enabled=$enabled")
                if (enabled) {
                    // 权限已授予，等待服务连接
                    waitForAccessibilityServiceConnection()
                } else {
                    // 权限被用户关闭
                    Log.d(TAG, "无障碍服务权限已被用户关闭")
                }
            }
            
            // 注册监听器
            AccessibilityManagerCompat.addAccessibilityStateChangeListener(
                accessibilityManager,
                accessibilityStateListener!!
            )
            
            // 应用启动时立即检查一次
            if (isAccessibilityServiceEnabled()) {
                waitForAccessibilityServiceConnection()
            }
        } catch (e: Exception) {
            Log.e(TAG, "注册无障碍服务状态监听器失败: ${e.message}", e)
        }
    }
    
    /**
     * 等待无障碍服务连接（权限已授予但服务可能未连接）
     * 如果超时，会继续等待（不放弃），因为系统可能需要更多时间启动服务
     */
    private fun waitForAccessibilityServiceConnection() {
        var retryCount = 0
        val maxRetries = 20  // 初始重试次数（10秒）
        val delayMs = 500L
        
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                val serviceConnected = MyAccessibilityService.getInstance() != null
                
                if (serviceConnected) {
                    Log.d(TAG, "无障碍服务已连接（等待${retryCount * delayMs}ms后连接成功）")
                } else {
                    // 检查权限是否仍然授予（如果权限被撤销，停止等待）
                    if (!isAccessibilityServiceEnabled()) {
                        Log.d(TAG, "无障碍服务权限已被撤销，停止等待")
                        return
                    }
                    
                    retryCount++
                    if (retryCount <= maxRetries) {
                        // 前10秒内，每500ms检查一次
                        handler.postDelayed(this, delayMs)
                    } else if (retryCount <= maxRetries * 3) {
                        // 10秒后，每2秒检查一次（继续等待最多30秒）
                        if ((retryCount - maxRetries) % 4 == 0) {
                            Log.d(TAG, "无障碍服务仍未连接（已等待${retryCount * delayMs}ms），继续等待...")
                        }
                        handler.postDelayed(this, delayMs * 4) // 每2秒检查一次
                    } else {
                        // 30秒后，每5秒检查一次（持续等待）
                        if ((retryCount - maxRetries * 3) % 10 == 0) {
                            Log.d(TAG, "无障碍服务仍未连接（已等待${retryCount * delayMs}ms），持续等待中...")
                        }
                        handler.postDelayed(this, delayMs * 10) // 每5秒检查一次
                    }
                }
            }
        }
        
        handler.post(runnable)
    }
    
    /**
     * 检查无障碍服务权限是否已授予（系统设置级别）
     * 注意：此方法只检查权限状态，不检查服务连接状态
     */
    fun isAccessibilityServiceEnabled(): Boolean {
        val accessibilityManager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = AccessibilityManagerCompat.getEnabledAccessibilityServiceList(
            accessibilityManager,
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        )
        
        return enabledServices.any { 
            it.resolveInfo.serviceInfo.packageName == packageName 
        }
    }
    
    /**
     * 检查无障碍服务是否完全就绪（权限已授予且服务已连接）
     */
    fun isAccessibilityServiceReady(): Boolean {
        return isAccessibilityServiceEnabled() && MyAccessibilityService.getInstance() != null
    }
    
    /**
     * 判断是否为系统应用
     */
    private fun isSystemApp(packageName: String): Boolean {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            (packageInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
            (packageInfo.applicationInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 获取当前前台应用包名（不依赖无障碍服务）
     * 优先使用无障碍服务获取，如果无障碍服务不可用，则使用 ActivityManager
     * 会排除系统应用（如设置），优先返回第三方应用
     * @return 当前前台应用包名，如果无法获取则返回 null
     */
    fun getCurrentPackageNameWithoutAccessibility(): String? {
        // 方法1：优先使用无障碍服务（如果可用）
        try {
            val packageNameFromAccessibility = MyAccessibilityService.getCurrentPackageName()
            if (packageNameFromAccessibility != null && packageNameFromAccessibility != this.packageName) {
                // 如果是系统应用，继续尝试其他方法
                if (!isSystemApp(packageNameFromAccessibility)) {
                    Log.d(TAG, "通过无障碍服务获取到当前应用包名: $packageNameFromAccessibility")
                    return packageNameFromAccessibility
                } else {
                    Log.d(TAG, "通过无障碍服务获取到系统应用，继续尝试其他方法: $packageNameFromAccessibility")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "通过无障碍服务获取包名失败: ${e.message}", e)
        }
        
        // 方法2：使用 ActivityManager 获取（不依赖无障碍服务）
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                // Android 5.1+ 使用 getRunningAppProcesses
                val runningProcesses = activityManager.runningAppProcesses
                // 获取所有前台进程，优先选择非系统应用
                val foregroundProcesses = runningProcesses?.filter { processInfo ->
                    processInfo.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                } ?: emptyList()
                
                // 先尝试找非系统应用
                for (process in foregroundProcesses) {
                    val packageName = process.pkgList?.firstOrNull()
                    if (packageName != null && packageName != this.packageName && !isSystemApp(packageName)) {
                        Log.d(TAG, "通过 ActivityManager 获取到当前应用包名（非系统应用）: $packageName")
                        return packageName
                    }
                }
                
                // 如果没找到非系统应用，返回第一个前台应用（即使是系统应用）
                for (process in foregroundProcesses) {
                    val packageName = process.pkgList?.firstOrNull()
                    if (packageName != null && packageName != this.packageName) {
                        Log.d(TAG, "通过 ActivityManager 获取到当前应用包名（系统应用）: $packageName")
                        return packageName
                    }
                }
            } else {
                // Android 5.0 及以下使用 getRunningTasks（已废弃，但可用）
                @Suppress("DEPRECATION")
                val runningTasks = activityManager.getRunningTasks(5) // 获取最近5个任务
                // 先尝试找非系统应用
                for (task in runningTasks) {
                    val packageName = task.topActivity?.packageName
                    if (packageName != null && packageName != this.packageName && !isSystemApp(packageName)) {
                        Log.d(TAG, "通过 getRunningTasks 获取到当前应用包名（非系统应用）: $packageName")
                        return packageName
                    }
                }
                // 如果没找到非系统应用，返回第一个任务
                if (runningTasks.isNotEmpty()) {
                    val topActivity = runningTasks[0].topActivity
                    val packageName = topActivity?.packageName
                    if (packageName != null && packageName != this.packageName) {
                        Log.d(TAG, "通过 getRunningTasks 获取到当前应用包名（系统应用）: $packageName")
                        return packageName
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "通过 ActivityManager 获取包名失败: ${e.message}", e)
        }
        
        Log.w(TAG, "无法获取当前应用包名")
        return null
    }
    
    /**
     * 处理权限请求结果（Fragment会自己处理）
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        // 处理悬浮球录制的权限请求
        if (requestCode == REQUEST_MEDIA_PROJECTION_OVERLAY) {
            TaskIndicatorOverlayManager.handleRecordingPermissionResult(resultCode, data)
            return
        }
        
        // 处理伴随模式的截图权限请求
        // 注意：只在伴随模式时处理，否则让Fragment处理
        if (requestCode == REQUEST_MEDIA_PROJECTION_CHAT) {
            // 检查是否是伴随模式发起的权限请求
            val isCompanionModeRequest = isTaskFromCompanionMode || pendingQueryFromCompanion != null
            
            if (isCompanionModeRequest) {
                // 伴随模式：MainActivity处理
                if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                    val intent = Intent(this, ChatScreenshotService::class.java).apply {
                        action = ChatScreenshotService.ACTION_START
                        putExtra(ChatScreenshotService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(ChatScreenshotService.EXTRA_RESULT_DATA, data)
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                    
                    Log.d(TAG, "伴随模式：截图权限已授权")
                    android.widget.Toast.makeText(this, "截图权限已授权", android.widget.Toast.LENGTH_SHORT).show()
                    
                    // 如果有待发送的消息，延迟一下等待服务就绪后检查权限并自动发送
                    val pendingQuery = pendingQueryFromCompanion
                    val pendingFragment = pendingChatFragmentFromCompanion
                    if (pendingQuery != null && pendingFragment != null) {
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            // 等待服务就绪
                            delay(1000)
                            // 重新调用getScreenshotAndSendTask（现在截图权限已就绪，会检查无障碍权限）
                            // 注意：不要在这里清除pendingQueryFromCompanion和pendingChatFragmentFromCompanion，
                            // 因为如果无障碍权限还没授予，getScreenshotAndSendTask会再次保存它们
                            // 只有在真正执行任务时才会清除
                            Log.d(TAG, "截图权限已授权，重新检查所有权限并发送任务: $pendingQuery")
                            getScreenshotAndSendTask(pendingQuery, pendingFragment)
                        }
                    } else {
                        // 检查是否有ChatFragment（包括隐藏的），如果有则确保显示它
                        val chatFragment = getChatFragment()
                        if (chatFragment != null && chatFragment.isHidden) {
                            // ChatFragment存在但被隐藏了，可能是权限授权后返回，需要切换回ChatFragment
                            Log.d(TAG, "截图权限授权回调 - 无待发送消息，但发现ChatFragment被隐藏，准备切换回ChatFragment")
                            val conversation = chatFragment.arguments?.getSerializable("conversation") as? Conversation
                            if (conversation != null) {
                                // 延迟一下确保服务已启动
                                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                    delay(500)
                                    switchToChatFragment(conversation)
                                    Log.d(TAG, "截图权限授权回调 - 已切换回ChatFragment")
                                }
                            }
                        }
                        
                        // 如果没有待发送的消息，且不是从伴随模式发起的，直接切回原应用
                        if (isTaskFromExternalApp && originalPackageName != null) {
                            Log.d(TAG, "截图权限授权回调 - 无待发送消息，检查跳转逻辑: isTaskFromExternalApp=$isTaskFromExternalApp, originalPackageName=$originalPackageName, isTaskFromCompanionMode=$isTaskFromCompanionMode")
                            if (!isTaskFromCompanionMode) {
                                // 使用getChatFragment()检查所有Fragment（包括隐藏的）
                                val currentFragment = getActiveFragment()
                                val chatFragmentForCheck = getChatFragment()
                                val isInChatFragment = currentFragment is ChatFragment || (chatFragmentForCheck != null && !chatFragmentForCheck.isHidden)
                                
                                if (!isInChatFragment) {
                                    Log.d(TAG, "截图权限授权回调 - 无待发送消息，非伴随模式，不在ChatFragment中，准备跳转回原应用: $originalPackageName")
                                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                        delay(500) // 等待一下确保服务已启动
                                        Log.d(TAG, "截图权限授权回调 - 无待发送消息，开始执行跳转回原应用")
                                        switchToOriginalApp()
                                    }
                                } else {
                                    Log.d(TAG, "截图权限授权回调 - 无待发送消息，但在ChatFragment中，不跳转回原应用")
                                }
                            } else {
                                Log.d(TAG, "截图权限授权回调 - 无待发送消息，任务从伴随模式发起，不跳转回原应用")
                            }
                        } else {
                            Log.d(TAG, "截图权限授权回调 - 无待发送消息，不需要跳转: isTaskFromExternalApp=$isTaskFromExternalApp, originalPackageName=$originalPackageName")
                        }
                    }
                } else {
                    Log.w(TAG, "伴随模式：截图权限被拒绝")
                    android.widget.Toast.makeText(this, "截图权限被拒绝", android.widget.Toast.LENGTH_SHORT).show()
                    // 清除待发送的消息
                    pendingQueryFromCompanion = null
                    pendingChatFragmentFromCompanion = null
                    // 切回原应用
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        delay(500)
                        switchToOriginalApp()
                    }
                }
                // 伴随模式：MainActivity已处理，return
                return
            } else {
                // 非伴随模式：让Fragment处理，不return
                Log.d(TAG, "非伴随模式：截图权限请求，交由Fragment处理")
                // 不return，让Fragment的onActivityResult处理
            }
        }
        
        // Fragment会自己处理其他onActivityResult
    }
    
    /**
     * 添加日志（公共方法，供Fragment使用）
     * 同时记录到系统Log和LogFragment的日志UI
     */
    fun addLog(message: String) {
        Log.d(TAG, message)
        
        // 保存到日志列表
        logMessages.add(message)
        
        // 尝试将日志添加到LogFragment的日志UI
        try {
            val logFragment = supportFragmentManager.fragments.find { it is LogFragment } as? LogFragment
            logFragment?.addLog(message)
        } catch (e: Exception) {
            // 如果LogFragment不存在，只记录到系统Log
            Log.d(TAG, "LogFragment未就绪，日志已保存，将在Fragment创建时同步")
        }
    }
    
    /**
     * 获取所有日志消息（供LogFragment使用）
     */
    fun getAllLogs(): List<String> {
        return logMessages.toList()
    }
    
    /**
     * 清空日志列表（供LogFragment使用）
     */
    fun clearLogMessages() {
        logMessages.clear()
    }
    
    /**
     * 清空日志（公共方法，供Fragment使用）
     */
    fun clearLog() {
        // Fragment会自己管理日志
        Log.d(TAG, "清空日志")
    }
    
    /**
     * 执行聊天query（供TestFragment批测使用）
     * @param query 查询内容
     * @return 是否成功启动（异步执行，实际完成需要等待）
     */
    fun executeChatQuery(query: String): Boolean {
        return try {
            // 查找ChatFragment
            val chatFragment = getChatFragment()
            if (chatFragment != null) {
                // 调用ChatFragment的方法执行query
                chatFragment.executeQueryFromExternal(query)
                true
            } else {
                Log.w(TAG, "ChatFragment未找到，无法执行query")
                addLog("批测失败：ChatFragment未找到")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行聊天query异常: ${e.message}", e)
            addLog("批测异常: ${e.message}")
            false
        }
    }
    
    /**
     * 停止批测（公共方法，可以从任何地方调用，即使TestFragment被销毁）
     */
    fun stopBatchTest() {
        Log.d(TAG, "========== MainActivity.stopBatchTest 开始执行 ==========")
        addLog("MainActivity: 开始停止批测")
        
        // 强制停止批测控制器（无论状态如何）
        try {
            if (batchTestController != null) {
                Log.d(TAG, "MainActivity.stopBatchTest: 调用 batchTestController.stopBatchTest()")
                batchTestController?.stopBatchTest()
                Log.d(TAG, "MainActivity.stopBatchTest: batchTestController.stopBatchTest() 完成")
            } else {
                Log.w(TAG, "MainActivity.stopBatchTest: batchTestController 为 null，跳过")
            }
        } catch (e: Exception) {
            Log.e(TAG, "MainActivity.stopBatchTest: 停止批测控制器异常: ${e.message}", e)
            e.printStackTrace()
            addLog("停止批测控制器异常: ${e.message}")
        }
        batchTestController = null
        
        // 强制取消批测协程（无论状态如何）
        try {
            val job = batchTestJob
            if (job != null) {
                Log.d(TAG, "MainActivity.stopBatchTest: 取消 batchTestJob，job.isActive=${job.isActive}, job.isCancelled=${job.isCancelled}")
                job.cancel()
                Log.d(TAG, "MainActivity.stopBatchTest: batchTestJob 已取消")
            } else {
                Log.w(TAG, "MainActivity.stopBatchTest: batchTestJob 为 null，跳过")
            }
            batchTestJob = null
        } catch (e: Exception) {
            Log.e(TAG, "MainActivity.stopBatchTest: 取消批测协程异常: ${e.message}", e)
            e.printStackTrace()
            addLog("取消批测协程异常: ${e.message}")
        }
        
        // 强制重置批测状态
        isBatchTesting = false
        Log.d(TAG, "MainActivity.stopBatchTest: isBatchTesting 已设置为 false")
        
        // 尝试更新TestFragment的UI（如果Fragment还存在）
        try {
            val testFragment = supportFragmentManager.fragments.find { it is TestFragment } as? TestFragment
            testFragment?.updateBatchTestUI(false)
        } catch (e: Exception) {
            Log.w(TAG, "MainActivity.stopBatchTest: 更新TestFragment UI异常: ${e.message}", e)
        }
        
        addLog("批测已强制停止，isBatchTesting=$isBatchTesting")
        Log.d(TAG, "========== MainActivity.stopBatchTest 执行完成 ==========")
        
        // 显示Toast
        try {
            Toast.makeText(this, "批测已停止", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "MainActivity.stopBatchTest: 显示Toast异常: ${e.message}", e)
        }
    }
    
    /**
     * 从悬浮窗停止任务（供TaskIndicatorOverlayManager使用）
     */
    fun stopTaskFromOverlay() {
        try {
            val chatFragment = getChatFragment()
            if (chatFragment != null) {
                // 在停止任务之前保存任务的uuid和query（用于反馈评测）
                val taskUuidForFeedback = chatFragment.chatUuid
                val taskQueryForFeedback = chatFragment.currentQuery
                chatFragment.stopTaskFromOverlayByUser()
                chatFragment.notifyTaskComplete()
                // 显示异常反馈请求
                chatFragment.showFeedbackRequest(taskUuidForFeedback, taskQueryForFeedback, isException = true)
                Log.d(TAG, "从悬浮窗停止任务成功，已显示评价窗口")
            } else {
                Log.w(TAG, "ChatFragment未找到，无法从悬浮窗停止任务")
            }
        } catch (e: Exception) {
            Log.e(TAG, "从悬浮窗停止任务异常: ${e.message}", e)
        }
    }
    
    /**
     * 从悬浮窗暂停/继续任务（供TaskIndicatorOverlayManager使用）
     */
    fun toggleTaskPauseFromOverlay() {
        try {
            val chatFragment = getChatFragment()
            if (chatFragment != null) {
                if (chatFragment.isTaskPaused()) {
                    chatFragment.resumeTask()
                    Log.d(TAG, "从悬浮窗继续任务成功")
                } else {
                    chatFragment.pauseTask()
                    Log.d(TAG, "从悬浮窗暂停任务成功")
                }
            } else {
                Log.w(TAG, "ChatFragment未找到，无法从悬浮窗暂停/继续任务")
            }
        } catch (e: Exception) {
            Log.e(TAG, "从悬浮窗暂停/继续任务异常: ${e.message}", e)
        }
    }
    
    /**
     * 获取任务暂停状态（供TaskIndicatorOverlayManager使用）
     */
    fun isTaskPaused(): Boolean {
        return try {
            val chatFragment = getChatFragment()
            chatFragment?.isTaskPaused() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "获取任务暂停状态异常: ${e.message}", e)
            false
        }
    }
    
    /**
     * 获取任务运行状态（供TaskIndicatorOverlayManager使用）
     * 注意：由于isTaskRunning是属性，这里直接返回属性值
     */
    fun getTaskRunningStatus(): Boolean {
        return isTaskRunning
    }
    
    /**
     * 检查是否处于手动接管状态（供TaskIndicatorOverlayManager使用）
     */
    fun isManualTakeover(): Boolean {
        return try {
            val chatFragment = getChatFragment()
            chatFragment?.isManualTakeover() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "获取手动接管状态异常: ${e.message}", e)
            false
        }
    }
    
    /**
     * 结束手动接管（供TaskIndicatorOverlayManager使用）
     */
    fun endManualTakeover() {
        try {
            val chatFragment = getChatFragment()
            if (chatFragment != null) {
                chatFragment.endManualTakeover()
                Log.d(TAG, "从悬浮窗结束手动接管成功")
            } else {
                Log.w(TAG, "ChatFragment未找到，无法结束手动接管")
            }
        } catch (e: Exception) {
            Log.e(TAG, "从悬浮窗结束手动接管异常: ${e.message}", e)
        }
    }
    
    /**
     * 设置 ChatWebSocketPool 的 GUI 执行回调
     * 收到 gui_execute_request 时，由 ChatFragment 执行并回传 gui_execute_result
     */
    private fun setupChatWebSocketPoolGuiExecuteCallback() {
        ChatWebSocketPool.onGuiExecuteRequest = { baseUrl, threadId, requestId, query, chatSummary, sendResult ->
            val targetConversationId = "custom_topoclaw"
            var chatFragment = getChatFragment(targetConversationId)
            // 固定切到内置自定义小助手 TopoClaw（custom_topoclaw）执行 GUI 任务。
            if (chatFragment == null) {
                Log.d(TAG, "gui_execute_request: ChatFragment 未在后台，尝试固定切换到 custom_topoclaw")
                val assistant = CustomAssistantManager.getById(this, targetConversationId)
                val fallbackConversation = Conversation(
                    id = targetConversationId,
                    name = assistant?.name ?: "TopoClaw",
                    avatar = assistant?.avatar,
                    lastMessage = null,
                    lastMessageTime = System.currentTimeMillis()
                )
                try {
                    ensureChatPageThenSwitchTo(fallbackConversation)
                    supportFragmentManager.executePendingTransactions()
                    chatFragment = getChatFragment(fallbackConversation.id)
                } catch (e: Exception) {
                    Log.e(TAG, "gui_execute_request: 切换对话失败", e)
                }
            }
            if (chatFragment != null) {
                chatFragment.handleGuiExecuteRequest(baseUrl, threadId, requestId, query, chatSummary, sendResult)
            } else {
                Log.w(TAG, "gui_execute_request: 未找到 ChatFragment，conversationId=$targetConversationId")
                sendResult(null, "未找到 ChatFragment")
            }
        }
    }

    /**
     * 从悬浮窗处理补充任务（供TaskIndicatorOverlayManager使用）
     */
    fun handleTaskSupplementFromOverlay() {
        try {
            val chatFragment = getChatFragment()
            if (chatFragment != null) {
                chatFragment.handleTaskSupplement()
                Log.d(TAG, "从悬浮窗触发补充任务成功")
            } else {
                Log.w(TAG, "ChatFragment未找到，无法触发补充任务")
            }
        } catch (e: Exception) {
            Log.e(TAG, "从悬浮窗触发补充任务异常: ${e.message}", e)
        }
    }
    
    /**
     * 获取ChatFragment（供TestFragment批测、pc_execute_command 使用）
     * @param targetConversationId 目标对话ID。若为自定义小助手ID（如 custom_xxx），则优先返回该对话的 Fragment；否则返回TopoClaw的 Fragment
     * @return ChatFragment实例，如果不存在则返回null
     */
    fun getChatFragment(targetConversationId: String? = null): ChatFragment? {
        // 自定义小助手上下文：优先从缓存获取对应对话的 Fragment
        if (targetConversationId != null && CustomAssistantManager.isCustomAssistantId(targetConversationId)) {
            val customFragment = chatFragmentCache[targetConversationId]
            if (customFragment != null && customFragment.isAdded) {
                Log.d(TAG, "getChatFragment: 从缓存找到自定义小助手ChatFragment, conversationId=$targetConversationId")
                return customFragment
            }
        }
        // 方法1：从当前显示的Fragment中查找
        val currentFragment = getActiveFragment()
        if (currentFragment is ChatFragment) {
            val convId = (currentFragment.arguments?.getSerializable("conversation") as? Conversation)?.id
            if (targetConversationId == null || convId == targetConversationId) {
                Log.d(TAG, "getChatFragment: 从fragmentContainer找到ChatFragment, convId=$convId")
                return currentFragment
            }
        }
        // 方法2：从所有Fragment中查找目标对话的 Fragment
        if (targetConversationId != null) {
            val targetFragment = supportFragmentManager.fragments
                .filterIsInstance<ChatFragment>()
                .firstOrNull { (it.arguments?.getSerializable("conversation") as? Conversation)?.id == targetConversationId }
            if (targetFragment != null) {
                Log.d(TAG, "getChatFragment: 从fragments列表找到目标ChatFragment, conversationId=$targetConversationId")
                return targetFragment
            }
        }
        // 方法3：通用 fallback - 从所有 Fragment 中取第一个 ChatFragment
        val fragment = supportFragmentManager.fragments.find { it is ChatFragment } as? ChatFragment
        if (fragment != null) {
            Log.d(TAG, "getChatFragment: 从fragments列表找到ChatFragment")
            return fragment
        }
        // 方法4：从缓存中获取TopoClaw的 ChatFragment（pc_execute 时可能尚未完成 transaction）
        val assistantFragment = chatFragmentCache[ConversationListFragment.CONVERSATION_ID_ASSISTANT]
        if (assistantFragment != null && assistantFragment.isAdded) {
            Log.d(TAG, "getChatFragment: 从缓存找到TopoClawChatFragment")
            return assistantFragment
        }
        
        // 方法5：递归查找子 Fragment（应对嵌套结构）
        for (f in supportFragmentManager.fragments) {
            val child = findChatFragmentRecursive(f)
            if (child != null) {
                Log.d(TAG, "getChatFragment: 递归找到ChatFragment")
                return child
            }
        }
        
        Log.w(TAG, "getChatFragment: 未找到ChatFragment，当前Fragment=${currentFragment?.javaClass?.simpleName}")
        return null
    }
    
    private fun findChatFragmentRecursive(fragment: Fragment): ChatFragment? {
        if (fragment is ChatFragment) return fragment
        for (child in fragment.childFragmentManager.fragments) {
            val found = findChatFragmentRecursive(child)
            if (found != null) return found
        }
        return null
    }
    
    /**
     * 查找对话列表 Fragment（当前容器与递归子 Fragment），用于更新最后一条消息/全量刷新。
     * ChatFragment 与离线补发逻辑通过此方法找到列表页，避免 parentFragmentManager 找不到兄弟 Fragment。
     */
    fun findConversationListFragment(): ConversationListFragment? {
        val current = getActiveFragment()
        if (current is ConversationListFragment) return current
        for (f in supportFragmentManager.fragments) {
            findConversationListFragmentRecursive(f)?.let { return it }
        }
        return null
    }
    
    private fun findConversationListFragmentRecursive(fragment: Fragment): ConversationListFragment? {
        if (fragment is ConversationListFragment) return fragment
        for (child in fragment.childFragmentManager.fragments) {
            findConversationListFragmentRecursive(child)?.let { return it }
        }
        return null
    }
    
    /** 供离线处理、子 Fragment 调用，立即刷新会话列表（从 prefs 重建列表项与预览）。 */
    fun refreshConversationListPublic() {
        refreshConversationList()
    }
    
    /**
     * 获取或创建 ChatFragment（复用机制）
     * @param conversation 对话对象
     * @return ChatFragment 实例
     */
    fun getOrCreateChatFragment(conversation: Conversation): ChatFragment {
        val conversationId = conversation.id
        
        // 先从缓存中查找
        val cachedFragment = chatFragmentCache[conversationId]
        if (cachedFragment != null && cachedFragment.isAdded) {
            Log.d(TAG, "getOrCreateChatFragment: 从缓存中复用Fragment，conversationId=$conversationId")
            // 更新 arguments（确保对话信息是最新的）
            cachedFragment.arguments = Bundle().apply {
                putSerializable("conversation", conversation)
            }
            return cachedFragment
        }
        
        // 如果缓存中没有或Fragment已被销毁，创建新的
        Log.d(TAG, "getOrCreateChatFragment: 创建新的Fragment，conversationId=$conversationId")
        val newFragment = ChatFragment().apply {
            arguments = Bundle().apply {
                putSerializable("conversation", conversation)
            }
        }
        
        // 添加到缓存
        chatFragmentCache[conversationId] = newFragment
        return newFragment
    }
    
    /**
     * 切换到指定的 ChatFragment（使用 hide/show 和复用机制）
     * @param conversation 对话对象
     * @param extraArguments 额外的 Bundle 参数（可选）
     */
    fun switchToChatFragment(conversation: Conversation, extraArguments: Bundle? = null) {
        try {
            val conversationId = conversation.id
            
            // 注意：ActionBar和底部导航栏的隐藏已在调用处提前完成，避免"退去"动画
            
            // 获取或创建 Fragment
            val chatFragment = getOrCreateChatFragment(conversation)
            
            val currentFragment = getActiveFragment()
            
            // 检查当前Fragment是否是FriendFragment，如果是，标记来源为通讯录
            val finalExtraArguments = extraArguments ?: Bundle()
            if (currentFragment is FriendFragment) {
                finalExtraArguments.putBoolean("from_friend_fragment", true)
            }
            
            // 合并参数到 arguments 中
            val currentArgs = chatFragment.arguments ?: Bundle()
            currentArgs.putAll(finalExtraArguments)
            chatFragment.arguments = currentArgs

            // 已在目标会话且Fragment可见：避免重复事务导致hide/show抖动与回栈膨胀
            if (currentFragment == chatFragment && chatFragment.isAdded && !chatFragment.isHidden) {
                if (currentConversationId != conversationId) {
                    setCurrentConversationId(conversationId)
                }
                Log.d(TAG, "switchToChatFragment: 目标会话已显示，跳过重复切换 conversationId=$conversationId")
                return
            }
            
            val transaction = supportFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.slide_in_from_right,  // enter: 新Fragment从右侧滑入
                    R.anim.slide_out_to_left,    // exit: 旧Fragment向左滑出
                    R.anim.slide_in_from_left,   // popEnter: 返回时，新Fragment从左侧滑入
                    R.anim.slide_out_to_right    // popExit: 返回时，旧Fragment向右滑出
                )
            
            // 如果当前有Fragment显示，先隐藏它
            if (currentFragment != null && currentFragment != chatFragment) {
                transaction.hide(currentFragment)
            }
            
            // 隐藏所有其他Fragment（除了目标ChatFragment）
            val allFragments = supportFragmentManager.fragments
            allFragments.forEach { fragment ->
                if (fragment != chatFragment && fragment.isAdded && !fragment.isHidden) {
                    transaction.hide(fragment)
                }
            }
            
            // 如果目标Fragment已经添加到FragmentManager，显示它
            if (chatFragment.isAdded) {
                transaction.show(chatFragment)
            } else {
                // 如果还没有添加，添加到FragmentManager（会触发enter动画）
                transaction.add(R.id.fragmentContainer, chatFragment)
            }
            
            transaction.addToBackStack(null)
            // 注意：addToBackStack 事务不能使用 commitNow*，否则会稳定触发 IllegalStateException
            if (supportFragmentManager.isStateSaved) {
                Log.w(TAG, "switchToChatFragment: FragmentManager状态已保存，使用commitAllowingStateLoss")
                transaction.commitAllowingStateLoss()
            } else {
                transaction.commit()
            }
        } catch (e: Exception) {
            Log.e(TAG, "switchToChatFragment失败: ${e.message}", e)
        }
    }
    
    /**
     * 获取截图并发送任务（供伴随模式使用）
     */
    fun getScreenshotAndSendTask(userInput: String, chatFragment: ChatFragment) {
        // 如果正在从悬浮球录制切换到技能学习小助手，不执行此操作
        if (isSwitchingFromOverlayRecording) {
            Log.d(TAG, "getScreenshotAndSendTask: 正在从悬浮球录制切换，跳过TopoClaw")
            return
        }

        // 问屏开关关闭：直接纯文本发送，不检查截图也不附带截图
        if (!isCompanionAskScreenEnabled) {
            Log.d(TAG, "getScreenshotAndSendTask: 问屏开关关闭，走纯文本发送")
            pendingQueryFromCompanion = null
            pendingChatFragmentFromCompanion = null
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                try {
                    kotlinx.coroutines.delay(300) // 等待弹窗关闭和键盘收起
                    chatFragment.executeQueryInternal(userInput)
                    Log.d(TAG, "getScreenshotAndSendTask: 纯文本任务已发送")
                } catch (e: Exception) {
                    Log.e(TAG, "getScreenshotAndSendTask: 纯文本发送失败: ${e.message}", e)
                    android.widget.Toast.makeText(this@MainActivity, "发送任务失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            return
        }
        
        // 检查截图权限
        val chatService = ChatScreenshotService.getInstance()
        val isScreenshotReady = chatService != null && chatService.isReady()
        if (!isScreenshotReady) {
            Log.w(TAG, "getScreenshotAndSendTask: 截图权限未就绪，直接显示权限弹窗（应用外）")
            // 保存待发送的query和chatFragment
            pendingQueryFromCompanion = userInput
            pendingChatFragmentFromCompanion = chatFragment
            // 直接显示权限弹窗（可在应用外显示）
            showScreenshotPermissionDialogForCompanion {
                Log.d(TAG, "用户确认授权截图权限")
                requestChatScreenshotPermissionForCompanion()
            }
            return
        }
        
        Log.d(TAG, "getScreenshotAndSendTask: 悬浮球路径跳过无障碍服务预检查")
        
        // 权限检查通过，执行任务
        // 确保chatService非空（已经检查过）
        val service = chatService ?: run {
            Log.e(TAG, "getScreenshotAndSendTask: chatService为null，无法执行任务")
            android.widget.Toast.makeText(this, "截图服务未就绪", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        
        // 清除待发送的消息（因为现在要真正执行任务了）
        // 这样可以避免重复发送，也确保在权限授予后能正确发送
        pendingQueryFromCompanion = null
        pendingChatFragmentFromCompanion = null
        Log.d(TAG, "getScreenshotAndSendTask: 清除待发送的消息，准备执行任务: $userInput")
        
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            try {
                Log.d(TAG, "getScreenshotAndSendTask: 开始获取截图并发送任务，query='$userInput'")
                
                // 等待弹窗关闭和键盘收起（伴随模式下，弹窗关闭和键盘收起需要时间）
                Log.d(TAG, "等待弹窗关闭和键盘收起...")
                kotlinx.coroutines.delay(300) // 等待300毫秒，确保弹窗和键盘都已关闭
                
                // 获取截图
                val bitmap = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    service.captureScreenshot()
                }
                
                if (bitmap == null) {
                    Log.w(TAG, "获取截图失败")
                    android.widget.Toast.makeText(this@MainActivity, "获取截图失败", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                // 将 Bitmap 转换为 Base64
                val outputStream = java.io.ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, outputStream)
                val imageBytes = outputStream.toByteArray()
                val screenshotBase64 = "data:image/jpeg;base64," + android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP)
                
                // 发送任务
                chatFragment.sendQuestionTask(screenshotBase64, userInput)
                Log.d(TAG, "getScreenshotAndSendTask: 任务已发送")
                
                // 如果是从外部应用发起的，且不是从伴随模式发起的，发送任务后切回原应用（任务会在后台执行）
                if (isTaskFromExternalApp && originalPackageName != null) {
                    Log.d(TAG, "getScreenshotAndSendTask - 检查跳转逻辑: isTaskFromExternalApp=$isTaskFromExternalApp, originalPackageName=$originalPackageName, isTaskFromCompanionMode=$isTaskFromCompanionMode")
                    if (!isTaskFromCompanionMode) {
                        Log.d(TAG, "getScreenshotAndSendTask - 非伴随模式，准备跳转回原应用: $originalPackageName")
                        delay(500) // 等待任务发送完成
                        Log.d(TAG, "getScreenshotAndSendTask - 开始执行跳转回原应用")
                        switchToOriginalApp()
                    } else {
                        Log.d(TAG, "getScreenshotAndSendTask - 任务从伴随模式发起，不跳转回原应用")
                    }
                } else {
                    Log.d(TAG, "getScreenshotAndSendTask - 不需要跳转: isTaskFromExternalApp=$isTaskFromExternalApp, originalPackageName=$originalPackageName")
                }
            } catch (e: Exception) {
                Log.e(TAG, "getScreenshotAndSendTask失败: ${e.message}", e)
                android.widget.Toast.makeText(this@MainActivity, "发送任务失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                // 如果是从外部应用发起的，即使失败也切回原应用
                if (isTaskFromExternalApp && originalPackageName != null) {
                    delay(500)
                    switchToOriginalApp()
                }
            }
        }
    }
    
    /**
     * 显示截图权限弹窗（伴随模式使用，可在应用外显示）
     */
    private fun showScreenshotPermissionDialogForCompanion(onConfirm: () -> Unit) {
        try {
            val dialog = android.app.AlertDialog.Builder(this)
                .setTitle("需要截图权限")
                .setMessage("发送消息需要截图权限，是否立即授权？")
                .setPositiveButton("立即授权") { _, _ ->
                    onConfirm()
                }
                .setNegativeButton("取消") { _, _ ->
                    // 取消时清除待发送的消息
                    pendingQueryFromCompanion = null
                    pendingChatFragmentFromCompanion = null
                }
                .create()
            
            // 设置对话框窗口类型为悬浮窗（可以在应用外显示）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                @Suppress("DEPRECATION")
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
            }
            
            // 美化对话框窗口样式
            dialog.window?.let { window ->
                // 设置半透明背景，使用自定义drawable
                window.setBackgroundDrawableResource(R.drawable.dialog_permission_background)
                // 设置窗口动画（可选，让弹出更流畅）
                window.setWindowAnimations(android.R.style.Animation_Dialog)
                // 设置窗口边距，让对话框不贴边
                val displayMetrics = resources.displayMetrics
                val margin = (20 * displayMetrics.density).toInt()
                window.decorView.setPadding(margin, margin, margin, margin)
            }
            
            // 美化对话框内容样式
            dialog.setOnShowListener {
                // 设置标题样式
                val titleView = dialog.findViewById<android.widget.TextView>(android.R.id.title)
                titleView?.apply {
                    setTextColor(resources.getColor(android.R.color.black, null))
                    textSize = 18f
                    setPadding(
                        (16 * resources.displayMetrics.density).toInt(),
                        (20 * resources.displayMetrics.density).toInt(),
                        (16 * resources.displayMetrics.density).toInt(),
                        (8 * resources.displayMetrics.density).toInt()
                    )
                }
                
                // 设置消息样式
                val messageView = dialog.findViewById<android.widget.TextView>(android.R.id.message)
                messageView?.apply {
                    setTextColor(resources.getColor(android.R.color.black, null))
                    textSize = 14f
                    setPadding(
                        (16 * resources.displayMetrics.density).toInt(),
                        (8 * resources.displayMetrics.density).toInt(),
                        (16 * resources.displayMetrics.density).toInt(),
                        (20 * resources.displayMetrics.density).toInt()
                    )
                }
                
                // 设置按钮样式
                val positiveButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                positiveButton?.apply {
                    setTextColor(resources.getColor(R.color.wechat_green, null))
                    textSize = 16f
                    setPadding(
                        (24 * resources.displayMetrics.density).toInt(),
                        (12 * resources.displayMetrics.density).toInt(),
                        (24 * resources.displayMetrics.density).toInt(),
                        (12 * resources.displayMetrics.density).toInt()
                    )
                }
                
                val negativeButton = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                negativeButton?.apply {
                    setTextColor(resources.getColor(android.R.color.darker_gray, null))
                    textSize = 16f
                    setPadding(
                        (24 * resources.displayMetrics.density).toInt(),
                        (12 * resources.displayMetrics.density).toInt(),
                        (24 * resources.displayMetrics.density).toInt(),
                        (12 * resources.displayMetrics.density).toInt()
                    )
                }
            }
            
            dialog.show()
            Log.d(TAG, "截图权限弹窗已显示（应用外模式）")
        } catch (e: Exception) {
            Log.e(TAG, "显示截图权限弹窗失败: ${e.message}", e)
            // 如果显示失败，使用Toast提示
            android.widget.Toast.makeText(this, "需要截图权限，请进入应用授权", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * 显示无障碍服务弹窗（伴随模式使用，可在应用外显示）
     */
    private fun showAccessibilityServiceDialogForCompanion(onConfirm: () -> Unit) {
        try {
            val dialog = android.app.AlertDialog.Builder(this)
                .setTitle("需要无障碍服务")
                .setMessage("执行操作需要无障碍服务，是否前往设置开启？")
                .setPositiveButton("去设置") { _, _ ->
                    onConfirm()
                }
                .setNegativeButton("取消") { _, _ ->
                    // 取消时清除待发送的消息
                    pendingQueryFromCompanion = null
                    pendingChatFragmentFromCompanion = null
                }
                .create()
            
            // 设置对话框窗口类型为悬浮窗（可以在应用外显示）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                @Suppress("DEPRECATION")
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
            }
            
            // 美化对话框窗口样式
            dialog.window?.let { window ->
                // 设置半透明背景，使用自定义drawable
                window.setBackgroundDrawableResource(R.drawable.dialog_permission_background)
                // 设置窗口动画（可选，让弹出更流畅）
                window.setWindowAnimations(android.R.style.Animation_Dialog)
                // 设置窗口边距，让对话框不贴边
                val displayMetrics = resources.displayMetrics
                val margin = (20 * displayMetrics.density).toInt()
                window.decorView.setPadding(margin, margin, margin, margin)
            }
            
            // 美化对话框内容样式
            dialog.setOnShowListener {
                // 设置标题样式
                val titleView = dialog.findViewById<android.widget.TextView>(android.R.id.title)
                titleView?.apply {
                    setTextColor(resources.getColor(android.R.color.black, null))
                    textSize = 18f
                    setPadding(
                        (16 * resources.displayMetrics.density).toInt(),
                        (20 * resources.displayMetrics.density).toInt(),
                        (16 * resources.displayMetrics.density).toInt(),
                        (8 * resources.displayMetrics.density).toInt()
                    )
                }
                
                // 设置消息样式
                val messageView = dialog.findViewById<android.widget.TextView>(android.R.id.message)
                messageView?.apply {
                    setTextColor(resources.getColor(android.R.color.black, null))
                    textSize = 14f
                    setPadding(
                        (16 * resources.displayMetrics.density).toInt(),
                        (8 * resources.displayMetrics.density).toInt(),
                        (16 * resources.displayMetrics.density).toInt(),
                        (20 * resources.displayMetrics.density).toInt()
                    )
                }
                
                // 设置按钮样式
                val positiveButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                positiveButton?.apply {
                    setTextColor(resources.getColor(R.color.wechat_green, null))
                    textSize = 16f
                    setPadding(
                        (24 * resources.displayMetrics.density).toInt(),
                        (12 * resources.displayMetrics.density).toInt(),
                        (24 * resources.displayMetrics.density).toInt(),
                        (12 * resources.displayMetrics.density).toInt()
                    )
                }
                
                val negativeButton = dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE)
                negativeButton?.apply {
                    setTextColor(resources.getColor(android.R.color.darker_gray, null))
                    textSize = 16f
                    setPadding(
                        (24 * resources.displayMetrics.density).toInt(),
                        (12 * resources.displayMetrics.density).toInt(),
                        (24 * resources.displayMetrics.density).toInt(),
                        (12 * resources.displayMetrics.density).toInt()
                    )
                }
            }
            
            dialog.show()
            Log.d(TAG, "无障碍服务弹窗已显示（应用外模式）")
        } catch (e: Exception) {
            Log.e(TAG, "显示无障碍服务弹窗失败: ${e.message}", e)
            // 如果显示失败，使用Toast提示
            android.widget.Toast.makeText(this, "需要无障碍服务，请进入应用开启", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * 显示切换回原应用提示弹窗（伴随模式使用）
     */
    private fun showSwitchBackAppDialog(onConfirm: () -> Unit) {
        // 如果已有弹窗显示，先关闭它，避免弹窗叠加
        switchAppDialog?.dismiss()
        switchAppDialog = null
        try {
            // 创建自定义布局
            val dialogView = android.view.LayoutInflater.from(this).inflate(
                android.R.layout.simple_list_item_1, null
            )
            
            // 创建带边框的背景drawable
            val borderedBackground = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFF0F5.toInt())  // 浅粉色背景
                setStroke((2 * resources.displayMetrics.density).toInt(), 0xFFCCCCCC.toInt())  // 2dp灰色边框
                cornerRadius = (12 * resources.displayMetrics.density)  // 12dp圆角
            }
            
            // 创建主容器（添加阴影、圆角和边框）
            val mainContainer = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
                background = borderedBackground
                elevation = 8f  // 添加阴影
                // 确保只在内容区域拦截触摸事件，不拦截外部区域
                isClickable = true
                isFocusable = true
                // 不拦截被遮挡的触摸事件（避免拦截系统手势）
                setFilterTouchesWhenObscured(false)
            }
            
            // 创建标题栏（可点击收起/展开）
            val titleBar = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(
                    (16 * resources.displayMetrics.density).toInt(),
                    (14 * resources.displayMetrics.density).toInt(),
                    (16 * resources.displayMetrics.density).toInt(),
                    (14 * resources.displayMetrics.density).toInt()
                )
                background = android.graphics.drawable.ColorDrawable(0xFFFFF0F5.toInt())  // 浅粉色背景
                isClickable = true
                isFocusable = true
            }
            
            // 添加标题栏底部分隔线（更明显）
            val divider = android.view.View(this).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (2 * resources.displayMetrics.density).toInt()
                )
                background = android.graphics.drawable.ColorDrawable(0xFFCCCCCC.toInt())  // 更明显的灰色分隔线
            }
            
            val titleText = android.widget.TextView(this).apply {
                text = "切换应用提醒"
                textSize = 16f
                setTextColor(0xFF000000.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }
            
            val arrowIcon = android.widget.ImageView(this).apply {
                setImageResource(R.drawable.ic_arrow_drop_down)  // 使用自定义箭头图标，无阴影
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (32 * resources.displayMetrics.density).toInt(),  // 增大尺寸：从24dp改为32dp
                    (32 * resources.displayMetrics.density).toInt()
                )
                // 移除阴影效果
                elevation = 0f
                // 设置图标颜色为黑色
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    imageTintList = android.content.res.ColorStateList.valueOf(0xFF000000.toInt())
                }
            }
            
            titleBar.addView(titleText)
            titleBar.addView(arrowIcon)
            
            // 创建内容区域（可展开/收起）
            val contentContainer = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
                background = android.graphics.drawable.ColorDrawable(0xFFFFF0F5.toInt())  // 浅粉色背景
                setPadding(
                    (16 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt(),  // 减小上边距
                    (16 * resources.displayMetrics.density).toInt(),
                    (16 * resources.displayMetrics.density).toInt()
                )
            }
            
            val messageText = android.widget.TextView(this).apply {
                text = "由于权限限制，无法获取您刚刚发起任务时的截图。\n" +
                        "• 如果您的任务需要识别屏幕：请手动切换到原位置，然后点击\"已切换\"开始执行\n" +
                        "• 否则请点击\"直接执行\"从当前页开始执行"
                textSize = 13.5f
                setTextColor(0xFF000000.toInt())
                setLineSpacing(0f, 1.1f)  // 设置行间距倍数，让文字更紧凑
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, (12 * resources.displayMetrics.density).toInt())
            }
            
            // 创建按钮容器（靠右对齐）
            val buttonContainer = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.END  // 靠右对齐
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            
            val btnCancel = android.widget.Button(this).apply {
                text = "取消"
                textSize = 16f
                setTextColor(0xFF757575.toInt())
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,  // 改为 WRAP_CONTENT，不使用 weight
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 0, (2 * resources.displayMetrics.density).toInt(), 0)  // 减小间距：从4dp改为2dp
                }
                background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            }
            
            val btnDirect = android.widget.Button(this).apply {
                text = "直接执行"
                textSize = 16f
                setTextColor(resources.getColor(R.color.wechat_green, null))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,  // 改为 WRAP_CONTENT，不使用 weight
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins((2 * resources.displayMetrics.density).toInt(), 0, (2 * resources.displayMetrics.density).toInt(), 0)  // 减小间距：从4dp改为2dp
                }
                background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            }
            
            val btnSwitched = android.widget.Button(this).apply {
                text = "已切换"
                textSize = 16f
                setTextColor(resources.getColor(R.color.wechat_green, null))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,  // 改为 WRAP_CONTENT，不使用 weight
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins((2 * resources.displayMetrics.density).toInt(), 0, 0, 0)  // 减小间距：从4dp改为2dp
                }
                background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            }
            
            buttonContainer.addView(btnCancel)
            buttonContainer.addView(btnDirect)
            buttonContainer.addView(btnSwitched)
            
            contentContainer.addView(messageText)
            contentContainer.addView(buttonContainer)
            
            mainContainer.addView(titleBar)
            mainContainer.addView(divider)
            mainContainer.addView(contentContainer)
            
            val dialog = android.app.AlertDialog.Builder(this)
                .setView(mainContainer)
                .setCancelable(false)
                .create()
            
            var isExpanded = false  // 默认收起状态
            contentContainer.visibility = android.view.View.GONE
            arrowIcon.setImageResource(R.drawable.ic_arrow_drop_up)
            
            // 标题栏点击事件：收起/展开
            titleBar.setOnClickListener {
                isExpanded = !isExpanded
                if (isExpanded) {
                    contentContainer.visibility = android.view.View.VISIBLE
                    arrowIcon.setImageResource(R.drawable.ic_arrow_drop_down)  // 使用自定义箭头图标
                } else {
                    contentContainer.visibility = android.view.View.GONE
                    arrowIcon.setImageResource(R.drawable.ic_arrow_drop_up)  // 使用自定义箭头图标
                }
            }
            
            // 按钮点击事件
            btnCancel.setOnClickListener {
                pendingQueryFromCompanion = null
                pendingChatFragmentFromCompanion = null
                dialog.dismiss()
            }
            
            btnDirect.setOnClickListener {
                val pendingQuery = pendingQueryFromCompanion
                val pendingFragment = pendingChatFragmentFromCompanion
                dialog.dismiss()
                if (pendingQuery != null && pendingFragment != null) {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        delay(500)
                        Log.d(TAG, "用户选择直接执行，从当前页面开始执行任务: $pendingQuery")
                        getScreenshotAndSendTask(pendingQuery, pendingFragment)
                    }
                }
            }
            
            btnSwitched.setOnClickListener {
                dialog.dismiss()
                onConfirm()
            }
            
            // 禁止点击外部区域关闭（但允许点击外部区域切换应用）
            dialog.setCanceledOnTouchOutside(false)
            
            // 设置对话框窗口类型为悬浮窗（可以在应用外显示）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                @Suppress("DEPRECATION")
                dialog.window?.setType(WindowManager.LayoutParams.TYPE_PHONE)
            }
            
            // 设置弹窗位置和样式
            dialog.window?.let { window ->
                val displayMetrics = resources.displayMetrics
                
                // 设置窗口参数
                val params = window.attributes
                params.width = displayMetrics.widthPixels  // 宽度等于屏幕宽度，无左右间隔
                params.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                params.gravity = android.view.Gravity.TOP  // 位置在顶部
                params.alpha = 0.98f  // 稍微提高不透明度，让弹窗更清晰
                
                // 设置窗口标志，允许点击弹窗外部区域（不拦截触摸事件）
                // FLAG_NOT_TOUCH_MODAL: 允许触摸事件传递给后面的窗口，不拦截系统手势（如上滑查看历史应用）
                // FLAG_WATCH_OUTSIDE_TOUCH: 可以检测外部触摸事件
                // FLAG_NOT_FOCUSABLE: 不获取焦点，避免拦截触摸事件
                // 移除 FLAG_DIM_BEHIND 等可能影响触摸的标志
                params.flags = (params.flags or 
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
                
                // 确保 decorView 不拦截触摸事件（让系统手势可以正常工作）
                window.decorView.isClickable = false
                window.decorView.isFocusable = false
                window.decorView.isFocusableInTouchMode = false
                // 不拦截被遮挡的触摸事件，让系统手势可以穿透
                window.decorView.setFilterTouchesWhenObscured(false)
                
                // 确保 mainContainer 不会拦截外部触摸事件
                // 通过设置触摸事件过滤，让外部区域的触摸事件可以穿透
                mainContainer.setFilterTouchesWhenObscured(false)
                
                window.attributes = params
                window.setLayout(params.width, params.height)
                
                // 设置窗口背景为透明，让自定义背景显示
                window.setBackgroundDrawableResource(android.R.color.transparent)
                
                // 无左右内边距，但添加底部圆角效果
                window.decorView.setPadding(0, 0, 0, 0)
                
                // 添加底部圆角（通过设置clipToOutline）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mainContainer.clipToOutline = true
                    mainContainer.outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                            val cornerRadius = (12 * resources.displayMetrics.density)
                            outline.setRoundRect(0, 0, view.width, view.height, cornerRadius)
                        }
                    }
                }
                
                // 在 dialog.show() 之后，再次确保窗口设置正确
                dialog.setOnShowListener {
                    // 确保窗口标志已正确设置
                    val finalParams = window.attributes
                    finalParams.flags = (finalParams.flags or 
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and
                        WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
                    window.attributes = finalParams
                    
                    // 再次确保 decorView 不拦截触摸事件
                    window.decorView.isClickable = false
                    window.decorView.isFocusable = false
                    window.decorView.isFocusableInTouchMode = false
                    window.decorView.setFilterTouchesWhenObscured(false)
                }
            }
            
            // 保存弹窗引用，用于检查是否已显示
            switchAppDialog = dialog
            
            // 设置弹窗关闭监听器，清除引用
            dialog.setOnDismissListener {
                switchAppDialog = null
                Log.d(TAG, "切换回原应用提示弹窗已关闭")
            }
            
            dialog.show()
            Log.d(TAG, "切换回原应用提示弹窗已显示")
        } catch (e: Exception) {
            Log.e(TAG, "显示切换回原应用提示弹窗失败: ${e.message}", e)
            android.widget.Toast.makeText(this, "请手动切换回原应用", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * 请求聊天截图权限（伴随模式使用）
     */
    private fun requestChatScreenshotPermissionForCompanion() {
        // 如果 originalPackageName 已经存在（在 triggerTaskFromCompanionMode 中已保存），直接使用
        // 只有在不存在时才尝试获取（避免应用回到前台后获取到错误的包名）
        if (originalPackageName == null) {
            try {
                val currentPackageName = getCurrentPackageNameWithoutAccessibility()
                val selfPackageName = packageName
                
                if (currentPackageName != null && currentPackageName != selfPackageName) {
                    // 当前应用不是自己，说明是从外部应用发起的
                    isTaskFromExternalApp = true
                    originalPackageName = currentPackageName
                    Log.d(TAG, "伴随模式：保存原始应用包名: $currentPackageName")
                }
            } catch (e: Exception) {
                Log.w(TAG, "获取当前应用包名失败: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "伴随模式：使用已保存的原始应用包名: $originalPackageName")
        }
        
        // startActivityForResult 需要 Activity 在前台才能正常工作
        // 所以需要先确保应用在前台，然后再请求权限
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            try {
                // 先确保应用在前台
                val success = bringAppToForeground()
                if (success) {
                    Log.d(TAG, "已确保应用在前台，等待界面恢复后请求权限")
                    // 等待应用切换到前台
                    kotlinx.coroutines.delay(300)
                    
                    // 现在请求权限
                    val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
                    // 不要添加 FLAG_ACTIVITY_NEW_TASK，因为 startActivityForResult 需要 Activity 在前台
                    startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION_CHAT)
                    Log.d(TAG, "伴随模式：正在请求聊天截图权限...")
                } else {
                    Log.w(TAG, "无法将应用带到前台，无法请求权限")
                    android.widget.Toast.makeText(this@MainActivity, "无法请求截图权限，请手动进入应用授权", android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "请求截图权限失败: ${e.message}", e)
                android.widget.Toast.makeText(this@MainActivity, "请求截图权限失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 打开无障碍设置页面（伴随模式使用）
     */
    private fun openAccessibilitySettingsForCompanion() {
        // 如果 originalPackageName 已经存在（在 triggerTaskFromCompanionMode 中已保存），直接使用
        // 只有在不存在时才尝试获取（避免应用回到前台后获取到错误的包名）
        if (originalPackageName == null) {
            try {
                val currentPackageName = getCurrentPackageNameWithoutAccessibility()
                val selfPackageName = packageName
                
                if (currentPackageName != null && currentPackageName != selfPackageName) {
                    // 当前应用不是自己，说明是从外部应用发起的
                    isTaskFromExternalApp = true
                    originalPackageName = currentPackageName
                    Log.d(TAG, "伴随模式：保存原始应用包名: $currentPackageName")
                }
            } catch (e: Exception) {
                Log.w(TAG, "获取当前应用包名失败: ${e.message}", e)
            }
        } else {
            Log.d(TAG, "伴随模式：使用已保存的原始应用包名: $originalPackageName")
        }
        
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            // 添加标志，避免强制应用回到前台
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            android.widget.Toast.makeText(this, "已打开无障碍设置，请找到\"TopoClaw\"并开启", android.widget.Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "打开无障碍设置失败: ${e.message}", e)
            android.widget.Toast.makeText(this, "打开设置失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 根据 pc_execute_command 上下文切换到合适的聊天页面
     * - 若为自定义小助手（有 assistant_base_url 且 conversation_id 为自定义小助手 ID）：切换到该自定义小助手对话
     * - 否则：切换到「TopoClaw」对话（批测等场景）
     * @param pending 待执行的 PC 指令，可为 null（则等同于 switchToChatPage()）
     * @return 是否成功切换
     */
    fun switchToChatPageForPcExecute(pending: PendingPcExecute?): Boolean {
        if (pending != null && pending.conversationId != null && pending.conversationId.startsWith("group_")) {
            // PC 群组 @ TopoClaw：切换到群组对话，执行信息呈现在群组内
            val groupId = pending.conversationId.removePrefix("group_")
            val group = GroupManager.getGroup(this, groupId)
            val groupName = group?.name ?: "群组"
            Log.d(TAG, "switchToChatPageForPcExecute: 群组上下文，切换到群组 $groupName (group_$groupId)")
            if (isSwitchingFromOverlayRecording) {
                Log.d(TAG, "switchToChatPageForPcExecute: 正在从悬浮球录制切换，跳过")
                return false
            }
            val targetConv = Conversation(
                id = "group_$groupId",
                name = groupName,
                avatar = null,
                lastMessage = null,
                lastMessageTime = System.currentTimeMillis()
            )
            val currentFragment = getActiveFragment()
            if (currentFragment is ChatFragment) {
                val currentConv = currentFragment.arguments?.getSerializable("conversation") as? Conversation
                if (currentConv?.id == targetConv.id) {
                    Log.d(TAG, "switchToChatPageForPcExecute: 当前已是该群组对话，无需切换")
                    return true
                }
            }
            return ensureChatPageThenSwitchTo(targetConv)
        }
        if (pending != null && pending.assistantBaseUrl != null && pending.conversationId != null &&
            CustomAssistantManager.isCustomAssistantId(pending.conversationId)) {
            val assistant = CustomAssistantManager.getById(this, pending.conversationId)
            if (assistant != null) {
                Log.d(TAG, "switchToChatPageForPcExecute: 自定义小助手上下文，切换到 ${assistant.name} (${assistant.id})")
                if (isSwitchingFromOverlayRecording) {
                    Log.d(TAG, "switchToChatPageForPcExecute: 正在从悬浮球录制切换，跳过")
                    return false
                }
                val targetConv = Conversation(
                    id = assistant.id,
                    name = assistant.name,
                    avatar = assistant.avatar,
                    lastMessage = null,
                    lastMessageTime = System.currentTimeMillis()
                )
                // 若当前已是该自定义小助手对话，无需切换
                val currentFragment = getActiveFragment()
                if (currentFragment is ChatFragment) {
                    val currentConv = currentFragment.arguments?.getSerializable("conversation") as? Conversation
                    if (currentConv?.id == targetConv.id) {
                        Log.d(TAG, "switchToChatPageForPcExecute: 当前已是该自定义小助手对话，无需切换")
                        return true
                    }
                }
                return ensureChatPageThenSwitchTo(targetConv)
            }
        }
        return switchToChatPage()
    }

    /**
     * 确保聊天页面就绪并切换到指定对话（用于自定义小助手等）
     * 与 switchToChatPage 共用：返回栈为空时先添加对话列表、再 switchToChatFragment
     */
    private fun ensureChatPageThenSwitchTo(targetConversation: Conversation): Boolean {
        return try {
            if (supportFragmentManager.backStackEntryCount == 0) {
                switchToTabFragment(R.id.nav_chat) { ConversationListFragment() }
                supportFragmentManager.executePendingTransactions()
                Thread.sleep(100)
            }
            switchToChatFragment(targetConversation)
            supportFragmentManager.executePendingTransactions()
            var retryCount = 0
            while (retryCount < 20) {
                val frag = getActiveFragment()
                if (frag is ChatFragment) {
                    val conv = frag.arguments?.getSerializable("conversation") as? Conversation
                    if (conv?.id == targetConversation.id) {
                        Log.d(TAG, "ensureChatPageThenSwitchTo: 已切换到 ${targetConversation.name}")
                        return true
                    }
                }
                Thread.sleep(50)
                retryCount++
            }
            Log.w(TAG, "ensureChatPageThenSwitchTo: 超时，但 switchToChatFragment 已执行")
            true
        } catch (e: Exception) {
            Log.e(TAG, "ensureChatPageThenSwitchTo 异常: ${e.message}", e)
            false
        }
    }

    /**
     * 切换到聊天页面（供TestFragment批测使用）
     * 默认进入"TopoClaw"对话框
     * @return 是否成功切换
     */
    fun switchToChatPage(): Boolean {
        Log.d(TAG, "========== switchToChatPage 开始执行 ==========")
        Log.d(TAG, "switchToChatPage: Activity状态 - isFinishing=$isFinishing, isDestroyed=$isDestroyed")
        
        // 如果正在从悬浮球录制切换到技能学习小助手，不执行此操作
        if (isSwitchingFromOverlayRecording) {
            Log.d(TAG, "switchToChatPage: 正在从悬浮球录制切换，跳过TopoClaw")
            return false
        }
        
        return try {
            Log.d(TAG, "switchToChatPage: 开始切换到聊天页面（TopoClaw）")
            
            // 如果当前已经是ChatFragment，检查是否是TopoClaw
            val currentFragment = try {
                getActiveFragment()
            } catch (e: Exception) {
                Log.e(TAG, "switchToChatPage: 获取当前Fragment异常: ${e.message}", e)
                e.printStackTrace()
                null
            }
            
            Log.d(TAG, "switchToChatPage: 当前Fragment=${currentFragment?.javaClass?.simpleName}")
            
            if (currentFragment is ChatFragment) {
                // 检查当前对话是否是TopoClaw
                // 注意：ChatFragment的currentConversation是private，需要通过arguments获取
                val currentConv = try {
                    currentFragment.arguments?.getSerializable("conversation") as? Conversation
                } catch (e: Exception) {
                    Log.e(TAG, "switchToChatPage: 获取conversation异常: ${e.message}", e)
                    null
                }
                
                if (currentConv?.id == ConversationListFragment.CONVERSATION_ID_ASSISTANT) {
                    Log.d(TAG, "switchToChatPage: 当前已经是TopoClaw对话框，无需切换")
                    return true
                }
                // 如果当前是其他对话，也需要切换（因为批测需要TopoClaw）
                Log.d(TAG, "switchToChatPage: 当前是其他对话(${currentConv?.name})，切换到TopoClaw")
            }
            
            // 如果返回栈为空，先添加对话列表Fragment作为基础页面
            // 这样用户可以通过返回按钮返回到对话列表
            Log.d(TAG, "switchToChatPage: 返回栈数量=${supportFragmentManager.backStackEntryCount}")
            if (supportFragmentManager.backStackEntryCount == 0) {
                Log.d(TAG, "switchToChatPage: 返回栈为空，先确保对话列表Fragment")
                try {
                    switchToTabFragment(R.id.nav_chat) { ConversationListFragment() }
                    Log.d(TAG, "switchToChatPage: 对话列表Fragment已就绪")
                    // 等待Fragment切换完成
                    try {
                        supportFragmentManager.executePendingTransactions()
                        Thread.sleep(100)
                        Log.d(TAG, "switchToChatPage: 对话列表Fragment切换完成")
                    } catch (e: Exception) {
                        Log.w(TAG, "switchToChatPage: 等待对话列表Fragment切换完成时出错: ${e.message}", e)
                        e.printStackTrace()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "switchToChatPage: 添加对话列表Fragment异常: ${e.message}", e)
                    e.printStackTrace()
                }
            }
            
            // 创建"TopoClaw"对话
            Log.d(TAG, "switchToChatPage: 创建TopoClaw对话")
            val assistantConversation = try {
                Conversation(
                    id = ConversationListFragment.CONVERSATION_ID_ASSISTANT,
                    name = ChatConstants.ASSISTANT_DISPLAY_NAME,
                    avatar = null,
                    lastMessage = null,
                    lastMessageTime = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                Log.e(TAG, "switchToChatPage: 创建Conversation异常: ${e.message}", e)
                e.printStackTrace()
                throw e
            }
            
            // 使用复用机制切换到聊天页面
            Log.d(TAG, "switchToChatPage: 使用复用机制切换到聊天页面")
            switchToChatFragment(assistantConversation)
            
            // 等待Fragment切换完成，最多重试20次
            Log.d(TAG, "switchToChatPage: 等待Fragment切换完成")
            try {
                supportFragmentManager.executePendingTransactions()
                Log.d(TAG, "switchToChatPage: 已执行待处理事务")
            } catch (e: Exception) {
                Log.w(TAG, "switchToChatPage: executePendingTransactions失败，继续等待: ${e.message}", e)
                e.printStackTrace()
            }
            
            // 等待Fragment切换完成，最多重试20次
            var retryCount = 0
            val maxRetries = 20
            while (retryCount < maxRetries) {
                val fragmentAfterCommit = getActiveFragment()
                Log.d(TAG, "switchToChatPage: 检查Fragment (retry=$retryCount)，当前Fragment=${fragmentAfterCommit?.javaClass?.simpleName}")
                
                if (fragmentAfterCommit is ChatFragment) {
                    // 验证是否是TopoClaw
                    val currentConv = fragmentAfterCommit.arguments?.getSerializable("conversation") as? Conversation
                    if (currentConv?.id == ConversationListFragment.CONVERSATION_ID_ASSISTANT) {
                        Log.d(TAG, "switchToChatPage: 切换成功，已切换到TopoClaw")
                        return true
                    } else {
                        Log.w(TAG, "switchToChatPage: Fragment是ChatFragment但不是TopoClaw，conversation=${currentConv?.id}")
                    }
                }
                
                if (retryCount < maxRetries - 1) {
                    Thread.sleep(100) // 等待100ms后重试
                }
                retryCount++
            }
            
            // 如果重试后仍然失败
            val finalFragment = getActiveFragment()
            Log.w(TAG, "switchToChatPage: 切换失败，最终Fragment=${finalFragment?.javaClass?.simpleName}")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "switchToChatPage: 切换到聊天页面失败: ${e.message}", e)
            e.printStackTrace()
            false
        } finally {
            Log.d(TAG, "========== switchToChatPage 执行完成 ==========")
        }
    }
    
    /**
     * 杀死应用（先清除任务栈，然后彻底杀死所有相关进程）
     * 使用与 open 动作相同的逻辑来清除任务栈，确保应用回到主界面
     * 然后尝试多次杀死所有相关进程
     * @param packageName 应用包名
     * @return 是否成功执行杀死操作
     */
    private suspend fun killApp(packageName: String): Boolean {
        return try {
            val appName = AppMappingManager.getAppName(packageName) ?: 
                         try {
                             packageManager.getPackageInfo(packageName, 0)
                                 .applicationInfo.loadLabel(packageManager).toString()
                         } catch (e: Exception) {
                             packageName
                         }
            
            addLog("正在彻底清理并杀死应用: $appName ($packageName)")
            
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            
            // 步骤1：使用启动 Intent + CLEAR_TOP 来清除任务栈（复用 open 动作的逻辑）
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
                addLog("✓ 已清除应用任务栈，回到主界面")
                delay(800)  // 等待任务栈清除完成
            } else {
                addLog("⚠ 无法获取启动Intent，跳过清除任务栈步骤")
            }
            
            // 步骤2：确保应用在后台（返回主页）
            val accessibilityService = MyAccessibilityService.getInstance()
            if (accessibilityService != null) {
                accessibilityService.goHome()
                addLog("✓ 已返回主页，等待应用进入后台...")
                delay(2000)  // 增加等待时间，确保应用完全进入后台
            } else {
                addLog("⚠ 无障碍服务未连接，跳过返回主页步骤")
                delay(2000)
            }
            
            // 步骤3：获取所有相关进程并尝试杀死
            val runningApps = activityManager.getRunningAppProcesses()
            val relatedProcesses = runningApps?.filter { 
                it.processName == packageName || it.processName.startsWith("$packageName:")
            } ?: emptyList()
            
            if (relatedProcesses.isNotEmpty()) {
                addLog("找到 ${relatedProcesses.size} 个相关进程:")
                relatedProcesses.forEach { process ->
                    addLog("  - ${process.processName} (PID: ${process.pid})")
                }
            } else {
                addLog("未找到运行中的进程（应用可能已停止）")
            }
            
            // 步骤4：多次尝试杀死所有相关进程（最多5次）
            var allKilled = false
            for (attempt in 1..5) {
                addLog("第 $attempt 次尝试杀死应用进程...")
                
                // 杀死后台进程
                activityManager.killBackgroundProcesses(packageName)
                delay(800)  // 每次尝试后等待更长时间
                
                // 检查是否还有进程在运行
                val currentRunningApps = activityManager.getRunningAppProcesses()
                val stillRunning = currentRunningApps?.filter { 
                    it.processName == packageName || it.processName.startsWith("$packageName:")
                } ?: emptyList()
                
                if (stillRunning.isEmpty()) {
                    addLog("✓ 所有进程已停止: $appName")
                    allKilled = true
                    break
                } else {
                    val remainingCount = stillRunning.size
                    addLog("还有 $remainingCount 个进程在运行，继续尝试...")
                    if (attempt < 5) {
                        delay(1000)  // 继续尝试前等待更长时间
                    }
                }
            }
            
            // 步骤5：最终检查
            delay(1000)
            val finalCheck = activityManager.getRunningAppProcesses()
            val finalRunning = finalCheck?.filter { 
                it.processName == packageName || it.processName.startsWith("$packageName:")
            } ?: emptyList()
            
            if (finalRunning.isEmpty()) {
                addLog("✓ 应用已完全停止: $appName")
            } else {
                addLog("⚠ 应用仍有 ${finalRunning.size} 个进程在运行: $appName")
                finalRunning.forEach { process ->
                    addLog("  - ${process.processName} (PID: ${process.pid})")
                }
                addLog("提示：某些进程可能是系统服务或受保护的服务，无法被普通应用杀死")
                addLog("提示：这是Android系统的安全限制，普通应用无法强制停止所有进程")
            }
            
            Log.d(TAG, "杀死应用操作完成: $packageName, 完全停止: ${finalRunning.isEmpty()}")
            true
        } catch (e: Exception) {
            addLog("✗ 杀死应用失败: ${e.message}")
            Log.e(TAG, "杀死应用失败: $packageName, 错误: ${e.message}", e)
            false
        }
    }
    
    /**
     * 从Activity启动应用（用于测试功能）
     * @param packageName 应用包名
     * @return 是否成功启动
     */
    fun launchAppFromActivity(packageName: String): Boolean {
        return try {
            Log.d(TAG, "尝试启动应用: $packageName")
            addLog("正在启动应用: $packageName")
            
            // 检查应用是否安装
            val packageInfo = try {
                packageManager.getPackageInfo(packageName, 0)
            } catch (e: Exception) {
                addLog("✗ 应用未安装: $packageName")
                addLog("提示：正在搜索相似应用...")
                // 尝试查找相似的应用
                val found = findSimilarApps(packageName)
                if (!found) {
                    addLog("提示：请检查包名是否正确，或应用是否已安装")
                    addLog("提示：长按'执行测试'按钮可查看所有已安装应用")
                }
                Log.w(TAG, "应用未安装: $packageName", e)
                return false
            }
            
            addLog("✓ 应用已安装: ${packageInfo.applicationInfo.loadLabel(packageManager)}")
            
            // 读取"打开应用时回到主页"设置
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val clearTop = prefs.getBoolean("open_action_clear_top", false) // 默认false
            
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                if (clearTop) {
                    // 当前行为：清除任务栈，回到主页
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    Log.d(TAG, "使用FLAG_ACTIVITY_CLEAR_TOP：将回到应用主页")
                    addLog("✓ 应用启动Intent已发送（将回到主页）")
                } else {
                    // 新行为：检查应用是否在运行
                    val isRunning = isAppRunning(packageName)
                    if (isRunning) {
                        // 应用已运行：只带到前台，不重新启动
                        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
                        Log.d(TAG, "应用已运行，使用FLAG_ACTIVITY_REORDER_TO_FRONT：保持当前页面")
                        addLog("✓ 应用已在运行，已带到前台（保持当前页面）")
                    } else {
                        // 应用未运行：正常启动
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        Log.d(TAG, "应用未运行，正常启动")
                        addLog("✓ 应用启动Intent已发送（正常启动）")
                    }
                }
                startActivity(intent)
                Log.d(TAG, "应用启动Intent已发送: $packageName")
                true
            } else {
                addLog("✗ 无法获取启动Intent: $packageName")
                addLog("提示：该应用可能没有启动Activity")
                Log.w(TAG, "无法获取启动Intent: $packageName")
                false
            }
        } catch (e: SecurityException) {
            addLog("✗ 启动应用权限不足: ${e.message}")
            Log.e(TAG, "启动应用权限不足: $packageName, 错误: ${e.message}", e)
            false
        } catch (e: Exception) {
            addLog("✗ 启动应用失败: ${e.message}")
            Log.e(TAG, "启动应用失败: $packageName, 错误: ${e.message}", e)
            false
        }
    }
    
    /**
     * 从链接跳转启动应用（不回到主页，保持当前页面）
     * @param packageName 应用包名
     * @return 是否成功启动
     */
    fun launchAppFromLink(packageName: String): Boolean {
        return try {
            Log.d(TAG, "从链接跳转启动应用: $packageName")
            
            // 检查应用是否安装
            val packageInfo = try {
                packageManager.getPackageInfo(packageName, 0)
            } catch (e: Exception) {
                Log.w(TAG, "应用未安装: $packageName", e)
                return false
            }
            
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                // 检查应用是否在运行
                val isRunning = isAppRunning(packageName)
                if (isRunning) {
                    // 应用已运行：只带到前台，保持当前页面
                    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
                    Log.d(TAG, "应用已运行，使用FLAG_ACTIVITY_REORDER_TO_FRONT：保持当前页面")
                } else {
                    // 应用未运行：正常启动，不回到主页
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    Log.d(TAG, "应用未运行，正常启动（不回到主页）")
                }
                startActivity(intent)
                Log.d(TAG, "应用启动Intent已发送: $packageName")
                true
            } else {
                Log.w(TAG, "无法获取启动Intent: $packageName")
                false
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "启动应用权限不足: $packageName, 错误: ${e.message}", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "启动应用失败: $packageName, 错误: ${e.message}", e)
            false
        }
    }
    
    /**
     * 检查应用是否正在运行
     * @param packageName 应用包名
     * @return 是否在运行
     */
    private fun isAppRunning(packageName: String): Boolean {
        return try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Android 6.0+ 使用 getRunningAppProcesses
                val runningProcesses = activityManager.runningAppProcesses
                runningProcesses?.any { 
                    // 检查进程名是否匹配包名（进程名可能是包名或包名:进程名）
                    it.processName == packageName || it.processName.startsWith("$packageName:")
                } ?: false
            } else {
                // Android 5.x 及以下使用 getRunningTasks（已废弃，但可用）
                @Suppress("DEPRECATION")
                val runningTasks = activityManager.getRunningTasks(10) // 检查最近10个任务
                runningTasks.any { it.topActivity?.packageName == packageName }
            }
        } catch (e: Exception) {
            Log.w(TAG, "检查应用运行状态失败: ${e.message}", e)
            false // 出错时返回false，使用正常启动方式
        }
    }
    
    /**
     * 返回到当前应用（将应用带到前台，不重新启动）
     * @return 是否成功
     */
    fun bringAppToForeground(): Boolean {
        return try {
            Log.d(TAG, "尝试将应用带到前台")
            val packageName = packageName
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                // 使用 REORDER_TO_FRONT 标志，将应用带到前台而不重新启动
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Log.d(TAG, "应用已带到前台: $packageName")
                true
            } else {
                Log.w(TAG, "无法获取启动Intent: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "将应用带到前台失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 切回原应用（伴随模式使用，权限申请完成后切回）
     * @return 是否成功
     */
    private fun switchToOriginalApp(): Boolean {
        if (!isTaskFromExternalApp || originalPackageName == null) {
            Log.d(TAG, "不是从外部应用发起的任务，无需切回")
            return false
        }
        
        return try {
            Log.d(TAG, "尝试切回原应用: $originalPackageName")
            val intent = packageManager.getLaunchIntentForPackage(originalPackageName!!)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
                Log.d(TAG, "已切回原应用: $originalPackageName")
                // 清除标记，避免重复切回
                isTaskFromExternalApp = false
                isTaskFromCompanionMode = false
                originalPackageName = null
                Log.d(TAG, "switchToOriginalApp - 已清除任务来源标记: isTaskFromExternalApp=false, isTaskFromCompanionMode=false")
                true
            } else {
                Log.w(TAG, "无法获取原应用启动Intent: $originalPackageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "切回原应用失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 查找相似的应用（用于调试）
     * @param searchTerm 搜索关键词（包名或应用名）
     * @return 是否找到匹配的应用
     */
    private fun findSimilarApps(searchTerm: String): Boolean {
        return try {
            val installedPackages = packageManager.getInstalledPackages(0)
            val searchLower = searchTerm.lowercase()
            
            // 先精确匹配包名
            val exactMatches = installedPackages.filter { 
                it.packageName.equals(searchTerm, ignoreCase = true)
            }
            
            if (exactMatches.isNotEmpty()) {
                exactMatches.forEach { pkg ->
                    val appName = pkg.applicationInfo.loadLabel(packageManager)
                    addLog("✓ 找到精确匹配: $appName: ${pkg.packageName}")
                }
                return true
            }
            
            // 模糊匹配：包名或应用名包含关键词
            val matches = installedPackages.filter { 
                val appName = it.applicationInfo.loadLabel(packageManager).toString().lowercase()
                it.packageName.lowercase().contains(searchLower) ||
                appName.contains(searchLower)
            }.take(10) // 最多显示10个匹配结果
            
            if (matches.isNotEmpty()) {
                addLog("找到相似应用（${matches.size}个）：")
                matches.forEach { pkg ->
                    val appName = pkg.applicationInfo.loadLabel(packageManager)
                    addLog("  - $appName: ${pkg.packageName}")
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "查找相似应用失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 判断是否为系统应用
     * 使用最简单直接的方法：检查应用是否在系统目录
     * 如果不在系统目录，且有启动Intent，就认为是第三方应用
     */
    private fun isSystemApp(packageInfo: android.content.pm.PackageInfo): Boolean {
        val appInfo = packageInfo.applicationInfo
        
        try {
            // 方法1：检查是否在系统目录（最准确的方法）
            val sourceDir = appInfo.sourceDir ?: return false
            val isInSystemDir = sourceDir.startsWith("/system/") ||
                               sourceDir.startsWith("/vendor/") ||
                               sourceDir.startsWith("/product/") ||
                               sourceDir.startsWith("/apex/")
            
            // 如果在系统目录，肯定是系统应用
            if (isInSystemDir) {
                return true
            }
            
            // 方法2：如果不在系统目录，检查是否有启动Intent
            // 有启动Intent的应用通常是用户可启动的，更可能是第三方应用
            val packageName = packageInfo.packageName
            val hasLaunchIntent = packageManager.getLaunchIntentForPackage(packageName) != null
            
            // 如果不在系统目录且有启动Intent，认为是第三方应用
            if (hasLaunchIntent) {
                return false
            }
            
            // 方法3：检查包名是否以系统包名前缀开头（作为补充判断）
            val isSystemPackage = packageName.startsWith("android.") ||
                                 packageName.startsWith("com.android.") ||
                                 packageName.startsWith("com.google.android.") ||
                                 packageName.startsWith("com.qualcomm.") ||
                                 packageName.startsWith("com.mediatek.") ||
                                 packageName.startsWith("com.oplus.") ||
                                 packageName.startsWith("com.coloros.") ||
                                 packageName.startsWith("com.miui.") ||
                                 packageName.startsWith("com.huawei.") ||
                                 packageName.startsWith("com.samsung.") ||
                                 packageName.startsWith("com.oneplus.")
            
            // 如果是系统包名且没有启动Intent，认为是系统服务
            return isSystemPackage
        } catch (e: Exception) {
            Log.e(TAG, "判断系统应用时出错: ${e.message}", e)
            // 出错时默认认为是第三方应用（更安全，避免误判）
            return false
        }
    }
    
    /**
     * 显示已安装应用列表（用于调试，查找正确的包名）
     * 优先显示第三方应用，然后显示系统应用
     */
    fun showInstalledApps() {
        addLog("=== 正在获取所有已安装应用列表... ===")
        
        // 在后台线程执行耗时操作，避免主线程阻塞导致ANR或崩溃
        Thread {
            try {
                // 方法1：使用 getInstalledPackages 获取所有应用
                @Suppress("DEPRECATION")
                val allPackages = packageManager.getInstalledPackages(0)
                
                // 方法2：使用 queryIntentActivities 获取所有可启动的应用（重要！）
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val resolveInfos = packageManager.queryIntentActivities(intent, 0)
                
                val totalCount = allPackages.size
                val launcherAppsCount = resolveInfos.size
                
                runOnUiThread {
                    addLog("=== 调试信息 ===")
                    addLog("getInstalledPackages 获取到: $totalCount 个应用")
                    addLog("queryIntentActivities 获取到: $launcherAppsCount 个可启动应用")
                }
                
                // 创建一个包含所有应用的集合（合并两种方法的结果）
                val allAppsMap = mutableMapOf<String, android.content.pm.PackageInfo>()
                
                // 先添加 getInstalledPackages 获取到的应用
                allPackages.forEach { pkg ->
                    allAppsMap[pkg.packageName] = pkg
                }
                
                // 补充 queryIntentActivities 获取到的应用（这些是可以在桌面启动的应用）
                resolveInfos.forEach { resolveInfo ->
                    val packageName = resolveInfo.activityInfo.packageName
                    if (!allAppsMap.containsKey(packageName)) {
                        try {
                            val pkgInfo = packageManager.getPackageInfo(packageName, 0)
                            allAppsMap[packageName] = pkgInfo
                            Log.d(TAG, "通过 queryIntentActivities 找到新应用: $packageName")
                        } catch (e: Exception) {
                            Log.w(TAG, "无法获取应用信息: $packageName, 错误: ${e.message}")
                        }
                    }
                }
                
                val allApps = allAppsMap.values.toList()
                val finalCount = allApps.size
                runOnUiThread {
                    addLog("合并后总共: $finalCount 个应用")
                }
                
                // 分离第三方应用和系统应用
                val userApps = mutableListOf<android.content.pm.PackageInfo>()
                val systemApps = mutableListOf<android.content.pm.PackageInfo>()
                
                allApps.forEach { pkg ->
                    try {
                        if (isSystemApp(pkg)) {
                            systemApps.add(pkg)
                        } else {
                            userApps.add(pkg)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "处理应用时出错: ${pkg.packageName}, 错误: ${e.message}")
                    }
                }
                
                // 按名称排序（添加异常保护）
                val sortedUserApps = userApps.sortedBy { pkg ->
                    try {
                        pkg.applicationInfo.loadLabel(packageManager).toString()
                    } catch (e: Exception) {
                        Log.w(TAG, "加载应用名称失败: ${pkg.packageName}, 错误: ${e.message}")
                        pkg.packageName // 如果加载失败，使用包名作为排序依据
                    }
                }
                val sortedSystemApps = systemApps.sortedBy { pkg ->
                    try {
                        pkg.applicationInfo.loadLabel(packageManager).toString()
                    } catch (e: Exception) {
                        Log.w(TAG, "加载应用名称失败: ${pkg.packageName}, 错误: ${e.message}")
                        pkg.packageName // 如果加载失败，使用包名作为排序依据
                    }
                }
                
                // 在主线程更新UI
                runOnUiThread {
                    // 先显示第三方应用
                    addLog("=== 第三方应用列表（共 ${sortedUserApps.size} 个，按名称排序）===")
                    sortedUserApps.forEach { pkg ->
                        try {
                            val appName = pkg.applicationInfo.loadLabel(packageManager)
                            addLog("$appName: ${pkg.packageName}")
                        } catch (e: Exception) {
                            // 如果加载应用名称失败，只显示包名
                            addLog("${pkg.packageName} (无法加载应用名称)")
                            Log.w(TAG, "显示应用时出错: ${pkg.packageName}, 错误: ${e.message}")
                        }
                    }
                    
                    addLog("")
                    addLog("=== 系统应用列表（共 ${sortedSystemApps.size} 个，按名称排序）===")
                    sortedSystemApps.forEach { pkg ->
                        try {
                            val appName = pkg.applicationInfo.loadLabel(packageManager)
                            addLog("$appName: ${pkg.packageName}")
                        } catch (e: Exception) {
                            // 如果加载应用名称失败，只显示包名
                            addLog("${pkg.packageName} (无法加载应用名称)")
                            Log.w(TAG, "显示应用时出错: ${pkg.packageName}, 错误: ${e.message}")
                        }
                    }
                    
                    addLog("")
                    addLog("=== 列表显示完成 ===")
                    addLog("第三方应用: ${sortedUserApps.size} 个")
                    addLog("系统应用: ${sortedSystemApps.size} 个")
                    addLog("总计: $totalCount 个应用")
                    addLog("提示：可在日志中搜索应用名或包名关键词来查找特定应用")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    addLog("✗ 获取应用列表失败: ${e.message}")
                }
                Log.e(TAG, "获取应用列表失败: ${e.message}", e)
            }
        }.start()
    }
    
    // executeTest方法已迁移到TestFragment
    
    /**
     * 动作类型
     */
    sealed class TestAction {
        data class Click(val x: Int, val y: Int) : TestAction()
        data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int) : TestAction()
        data class LongClick(val x: Int, val y: Int) : TestAction()
        data class DoubleClick(val x: Int, val y: Int) : TestAction()
        data class Input(val text: String) : TestAction()
        data class Type(val x: Int? = null, val y: Int? = null, val text: String) : TestAction()  // 组合动作：如果有坐标则先点击坐标后输入文本，否则直接输入到当前聚焦的输入框
        data class LaunchApp(val packageName: String) : TestAction()
        data class SystemButton(val action: String) : TestAction()  // "back"、"home" 或 "enter"
        data class KillApp(val packageName: String) : TestAction()
        data class ClearApp(val packageName: String) : TestAction()  // 通过最近任务列表清理
        data class Wait(val milliseconds: Long) : TestAction()  // 等待指定毫秒数
        data class LongScreenshot(val direction: String, val steps: Int) : TestAction()  // 长截图：direction为"down"或"up"，steps为滚动次数
        data object Screenshot : TestAction()  // 截图并保存到相册
        data class Drag(val x1: Int, val y1: Int, val x2: Int, val y2: Int) : TestAction()  // 长按并拖拽：x1,y1为起始坐标，x2,y2为目标坐标
        data class GetWechatLink(val delayMs: Long) : TestAction()  // 依次点击6个位置获取微信链接，delayMs为每次点击间间隔（毫秒）
    }
    
    /**
     * 解析指令字符串
     * 格式：
     * - click[100,200] - 点击
     * - swipe[100,200,300,400] - 滑动（从x1,y1到x2,y2）
     * - longClick[100,200] - 长按
     * - doubleClick[100,200] - 双击
     * - input[文本内容] - 输入文本
     * - type[x,y,文本内容] - 组合动作：先点击坐标(x,y)，然后输入文本
     * - type[文本内容] - 直接输入到当前聚焦的输入框（无需坐标）
     * - wait[毫秒数] - 等待指定时间
     * - open[应用名或包名] - 打开应用
     * - system_button[back|home|enter] - 系统按钮（back返回上一页，home返回主页，enter发送回车键）
     * - kill[应用名或包名] - 杀死应用
     * - clear[应用名或包名] - 清理后台应用
     * - long_screenshot[direction,steps] - 长截图，direction为"down"或"up"，steps为滚动次数（1-10，默认3）
     * - screenshot - 截图并保存到相册
     * - drag[x1,y1,x2,y2] - 长按并拖拽：x1,y1为起始坐标，x2,y2为目标坐标
     * - get_wechat_link[毫秒数] - 依次点击6个固定位置获取微信链接，参数为每次点击间间隔（毫秒）
     */
    fun parseTestInstructions(instructions: String): List<TestAction> {
        val actions = mutableListOf<TestAction>()
        
        try {
            // 定义所有动作类型的正则表达式
            val patterns = listOf(
                // click[x,y]
                Pair(Regex("click\\s*\\[(\\d+)\\s*,\\s*(\\d+)\\]", RegexOption.IGNORE_CASE)) { match: MatchResult ->
                    val x = match.groupValues[1].toIntOrNull()
                    val y = match.groupValues[2].toIntOrNull()
                    if (x != null && y != null) {
                        TestAction.Click(x, y)
                    } else null
                },
                // swipe[x1,y1,x2,y2]
                Pair(Regex("swipe\\s*\\[(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\]", RegexOption.IGNORE_CASE)) { match: MatchResult ->
                    val x1 = match.groupValues[1].toIntOrNull()
                    val y1 = match.groupValues[2].toIntOrNull()
                    val x2 = match.groupValues[3].toIntOrNull()
                    val y2 = match.groupValues[4].toIntOrNull()
                    if (x1 != null && y1 != null && x2 != null && y2 != null) {
                        TestAction.Swipe(x1, y1, x2, y2)
                    } else null
                },
                // longClick[x,y]
                Pair(Regex("longClick\\s*\\[(\\d+)\\s*,\\s*(\\d+)\\]", RegexOption.IGNORE_CASE)) { match: MatchResult ->
                    val x = match.groupValues[1].toIntOrNull()
                    val y = match.groupValues[2].toIntOrNull()
                    if (x != null && y != null) {
                        TestAction.LongClick(x, y)
                    } else null
                },
                // doubleClick[x,y]
                Pair(Regex("doubleClick\\s*\\[(\\d+)\\s*,\\s*(\\d+)\\]", RegexOption.IGNORE_CASE)) { match: MatchResult ->
                    val x = match.groupValues[1].toIntOrNull()
                    val y = match.groupValues[2].toIntOrNull()
                    if (x != null && y != null) {
                        TestAction.DoubleClick(x, y)
                    } else null
                },
                // input[文本内容]
                Pair(Regex("input\\s*\\[([^\\]]+)\\]", RegexOption.IGNORE_CASE)) { match: MatchResult ->
                    val text = match.groupValues[1]
                    if (text.isNotEmpty()) {
                        TestAction.Input(text)
                    } else null
                },
                // type[x,y,文本内容] - 组合动作：先点击坐标，然后输入文本（完整格式，优先匹配）
                Pair(Regex("type\\s*\\[(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*([^\\]]+)\\]", RegexOption.IGNORE_CASE)) { match: MatchResult ->
                    val x = match.groupValues[1].toIntOrNull()
                    val y = match.groupValues[2].toIntOrNull()
                    val text = match.groupValues[3]
                    if (x != null && y != null && text.isNotEmpty()) {
                        TestAction.Type(x, y, text)
                    } else null
                },
                // type[文本内容] - 直接输入到当前聚焦的输入框（简化格式）
                Pair(Regex("type\\s*\\[([^\\]]+)\\]", RegexOption.IGNORE_CASE)) { match: MatchResult ->
                    val text = match.groupValues[1]
                    // 如果文本不为空，直接作为纯文本处理（完整格式已经在上面匹配过了）
                    if (text.isNotEmpty()) {
                        TestAction.Type(null, null, text)
                    } else null
                },
                // wait[毫秒数]
                Pair(Regex("wait\\s*\\[(\\d+)\\]", RegexOption.IGNORE_CASE)) { match: MatchResult ->
                    val milliseconds = match.groupValues[1].toLongOrNull()
                    if (milliseconds != null && milliseconds > 0) {
                        TestAction.Wait(milliseconds)
                    } else null
                },
                // open[包名或应用名]
                Pair(Regex("open\\s*\\[([^\\]]+)\\]", RegexOption.IGNORE_CASE)) { match: MatchResult ->
                    val param = match.groupValues[1]
                    if (param.isNotEmpty()) {
                        TestAction.LaunchApp(param)
                    } else null
                },
                // system_button[back] 或 system_button[home] 或 system_button[enter]
                Pair(Regex("system_button\\s*\\[(back|home|enter)\\]", RegexOption.IGNORE_CASE)) { match: MatchResult ->
                    val action = match.groupValues[1].lowercase()
                    if (action == "back" || action == "home" || action == "enter") {
                        TestAction.SystemButton(action)
                    } else null
                },
                // kill[应用名或包名]
                Pair(Regex("kill\\s*\\[([^\\]]+)\\]", RegexOption.IGNORE_CASE)) { match: MatchResult ->
                    val param = match.groupValues[1]
                    if (param.isNotEmpty()) {
                        TestAction.KillApp(param)
                    } else null
                },
                // clear[应用名或包名] - 通过最近任务列表清理
                Pair(Regex("clear\\s*\\[([^\\]]+)\\]", RegexOption.IGNORE_CASE)) { match: MatchResult ->
                    val param = match.groupValues[1]
                    if (param.isNotEmpty()) {
                        TestAction.ClearApp(param)
                    } else null
                },
                // long_screenshot[direction,steps] - 长截图
                Pair(Regex("long_screenshot\\s*\\[([^,]+)(?:\\s*,\\s*(\\d+))?\\]", RegexOption.IGNORE_CASE)) { match: MatchResult ->
                    val direction = match.groupValues[1].trim().lowercase()
                    val stepsStr = match.groupValues[2]
                    val steps = if (stepsStr.isNotEmpty()) {
                        stepsStr.toIntOrNull()?.coerceIn(1, 10) ?: 3
                    } else {
                        3  // 默认3次
                    }
                    if (direction == "down" || direction == "up") {
                        TestAction.LongScreenshot(direction, steps)
                    } else null
                },
                // screenshot - 截图并保存到相册
                Pair(Regex("screenshot", RegexOption.IGNORE_CASE)) { _: MatchResult ->
                    TestAction.Screenshot
                },
                // drag[x1,y1,x2,y2] - 长按并拖拽
                Pair(Regex("drag\\s*\\[(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)\\]", RegexOption.IGNORE_CASE)) { match: MatchResult ->
                    val x1 = match.groupValues[1].toIntOrNull()
                    val y1 = match.groupValues[2].toIntOrNull()
                    val x2 = match.groupValues[3].toIntOrNull()
                    val y2 = match.groupValues[4].toIntOrNull()
                    if (x1 != null && y1 != null && x2 != null && y2 != null) {
                        TestAction.Drag(x1, y1, x2, y2)
                    } else null
                },
                // get_wechat_link[毫秒数] - 依次点击6个固定位置获取微信链接
                Pair(Regex("get_wechat_link\\s*\\[(\\d+)\\]", RegexOption.IGNORE_CASE)) { match: MatchResult ->
                    val delayMs = match.groupValues[1].toLongOrNull()
                    if (delayMs != null && delayMs >= 0) {
                        TestAction.GetWechatLink(delayMs)
                    } else null
                }
            )
            
            // 按在字符串中出现的顺序解析所有动作
            var searchStart = 0
            var iterationCount = 0
            val maxIterations = 100  // 防止无限循环
            while (searchStart < instructions.length && iterationCount < maxIterations) {
                iterationCount++
                var found = false
                var earliestMatch: MatchResult? = null
                var earliestIndex = Int.MAX_VALUE
                var actionCreator: ((MatchResult) -> TestAction?)? = null
                
                // 找到最早出现的动作
                for ((pattern, creator) in patterns) {
                    val match = pattern.find(instructions, searchStart)
                    if (match != null && match.range.first < earliestIndex) {
                        earliestIndex = match.range.first
                        earliestMatch = match
                        actionCreator = creator
                        found = true
                    }
                }
                
                if (found && earliestMatch != null && actionCreator != null) {
                    val action = actionCreator(earliestMatch)
                    if (action != null) {
                        actions.add(action)
                        Log.d(TAG, "解析到动作: $action (位置: ${earliestMatch.range}, 匹配文本: '${instructions.substring(earliestMatch.range)}')")
                    } else {
                        Log.w(TAG, "动作创建失败 (位置: ${earliestMatch.range}, 匹配文本: '${instructions.substring(earliestMatch.range)}')")
                    }
                    // 从当前匹配结束位置继续搜索
                    searchStart = earliestMatch.range.last + 1
                } else {
                    // 没有找到更多动作，检查是否还有未解析的内容
                    if (searchStart < instructions.length) {
                        val remaining = instructions.substring(searchStart).trim()
                        if (remaining.isNotEmpty() && !remaining.matches(Regex("^[,;\\s]*$"))) {
                            Log.w(TAG, "未解析的剩余内容: '$remaining' (从位置 $searchStart 开始)")
                        }
                    }
                    // 退出循环
                    break
                }
            }
            
            if (iterationCount >= maxIterations) {
                Log.e(TAG, "解析迭代次数过多，可能存在循环问题")
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析指令失败: ${e.message}", e)
        }
        
        return actions
    }
    
    /**
     * 依次执行动作
     */
    suspend fun executeActions(actions: List<TestAction>, delayMs: Long) {
        val accessibilityService = MyAccessibilityService.getInstance()
        
        if (accessibilityService == null) {
            addLog("错误：无障碍服务未连接")
            runOnUiThread {
                Toast.makeText(this@MainActivity, "无障碍服务未连接，请重启应用", Toast.LENGTH_SHORT).show()
            }
            return
        }
        
        // 先返回home页面
        addLog("返回主页...")
        accessibilityService.goHome()
        delay(1000) // 等待1秒让home页面加载完成
        addLog("已返回主页，开始执行测试")
        
        for ((index, action) in actions.withIndex()) {
            addLog("执行动作 ${index + 1}/${actions.size}: ${action.javaClass.simpleName}")
            
            // 记录动作开始时间
            val actionStartTime = System.currentTimeMillis()
            
            val success = when (action) {
                is TestAction.Click -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val result = accessibilityService.performClick(action.x, action.y)
                        if (result) {
                            addLog("✓ 点击已发送: (${action.x}, ${action.y})")
                        } else {
                            addLog("✗ 点击发送失败: (${action.x}, ${action.y})")
                        }
                        result
                    } else {
                        addLog("✗ Android版本过低，不支持手势点击（需要Android 7.0+）")
                        false
                    }
                }
                is TestAction.Swipe -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val result = accessibilityService.performSwipe(
                            action.x1, action.y1, 
                            action.x2, action.y2
                        )
                        if (result) {
                            addLog("✓ 滑动已发送: (${action.x1}, ${action.y1}) -> (${action.x2}, ${action.y2})")
                        } else {
                            addLog("✗ 滑动发送失败: (${action.x1}, ${action.y1}) -> (${action.x2}, ${action.y2})")
                        }
                        result
                    } else {
                        addLog("✗ Android版本过低，不支持手势滑动（需要Android 7.0+）")
                        false
                    }
                }
                is TestAction.LongClick -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val result = accessibilityService.performLongClick(action.x, action.y)
                        if (result) {
                            addLog("✓ 长按已发送: (${action.x}, ${action.y})")
                        } else {
                            addLog("✗ 长按发送失败: (${action.x}, ${action.y})")
                        }
                        result
                    } else {
                        addLog("✗ Android版本过低，不支持手势长按（需要Android 7.0+）")
                        false
                    }
                }
                is TestAction.DoubleClick -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val result = accessibilityService.performDoubleClick(action.x, action.y)
                        if (result) {
                            addLog("✓ 双击已发送: (${action.x}, ${action.y})")
                        } else {
                            addLog("✗ 双击发送失败: (${action.x}, ${action.y})")
                        }
                        result
                    } else {
                        addLog("✗ Android版本过低，不支持手势双击（需要Android 7.0+）")
                        false
                    }
                }
                is TestAction.Wait -> {
                    addLog("等待 ${action.milliseconds}ms...")
                    delay(action.milliseconds)
                    addLog("✓ 等待完成")
                    true
                }
                is TestAction.Input -> {
                    val result = accessibilityService.inputText(action.text)
                    if (result) {
                        addLog("✓ 文本已输入: ${action.text}")
                    } else {
                        addLog("✗ 文本输入失败: ${action.text}（请确保已点击输入框）")
                    }
                    // input操作是同步的，需要等待一段时间确保输入完成
                    delay(200)
                    result
                }
                is TestAction.Type -> {
                    // 根据是否有坐标决定执行方式
                    if (action.x != null && action.y != null) {
                        // 有坐标：先点击坐标，等待200ms，然后输入文本
                        var clickSuccess = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            clickSuccess = accessibilityService.performClick(action.x, action.y)
                            if (clickSuccess) {
                                addLog("✓ 点击已发送: (${action.x}, ${action.y})")
                            } else {
                                addLog("✗ 点击发送失败: (${action.x}, ${action.y})")
                            }
                        } else {
                            addLog("✗ Android版本过低，不支持手势点击（需要Android 7.0+）")
                        }
                        
                        if (clickSuccess) {
                            // 等待200ms，让输入框获得焦点
                            delay(200)
                            
                            // 输入文本
                            val inputResult = accessibilityService.inputText(action.text)
                            if (inputResult) {
                                addLog("✓ 文本已输入: ${action.text}")
                            } else {
                                addLog("✗ 文本输入失败: ${action.text}")
                            }
                            // input操作后等待一段时间确保输入完成
                            delay(200)
                            inputResult
                        } else {
                            false
                        }
                    } else {
                        // 无坐标：直接输入到当前聚焦的输入框
                        addLog("直接输入文本到当前聚焦的输入框: ${action.text}")
                        val inputResult = accessibilityService.inputText(action.text)
                        if (inputResult) {
                            addLog("✓ 文本已输入: ${action.text}")
                        } else {
                            addLog("✗ 文本输入失败: ${action.text}（请确保输入框已聚焦）")
                        }
                        // input操作后等待一段时间确保输入完成
                        delay(200)
                        inputResult
                    }
                }
                is TestAction.LaunchApp -> {
                    // 解析包名（支持应用名或包名）
                    val packageName = AppMappingManager.resolvePackageName(action.packageName)
                    if (packageName != null) {
                        // 从MainActivity启动应用（而不是从AccessibilityService）
                        val result = launchAppFromActivity(packageName)
                        if (result) {
                            val appName = AppMappingManager.getAppName(packageName) ?: action.packageName
                            addLog("✓ 应用已启动: $appName ($packageName)")
                            // 等待应用启动完成
                            delay(1500)
                        } else {
                            addLog("✗ 应用启动失败: ${action.packageName}（应用不存在或无法启动）")
                        }
                        result
                    } else {
                        addLog("✗ 无法解析应用: ${action.packageName}（未找到对应的包名）")
                        false
                    }
                }
                is TestAction.SystemButton -> {
                    when (action.action.lowercase()) {
                        "back" -> {
                            accessibilityService.goBack()
                            addLog("✓ 已返回上一页")
                            delay(200)
                            true
                        }
                        "home" -> {
                            accessibilityService.goHome()
                            addLog("✓ 已返回主页")
                            delay(500)
                            true
                        }
                        "enter" -> {
                            // 通过Broadcast发送回车请求到自定义输入法
                            val enterIntent = Intent(SimpleInputMethodService.ACTION_SEND_ENTER).apply {
                                setPackage(packageName)
                            }
                            sendBroadcast(enterIntent)
                            addLog("✓ 已发送回车键事件")
                            delay(200) // 等待回车发送完成
                            true
                        }
                        else -> {
                            addLog("✗ 未知的系统按钮动作: ${action.action}")
                            false
                        }
                    }
                }
                is TestAction.KillApp -> {
                    // 解析包名（支持应用名或包名）
                    val packageName = if (action.packageName.contains(".")) {
                        action.packageName
                    } else {
                        AppMappingManager.getPackageName(action.packageName) ?: action.packageName
                    }
                    
                    // 杀死应用（内部会先清除任务栈，再返回主页，最后杀死进程）
                    val result = killApp(packageName)
                    delay(500)
                    result
                }
                is TestAction.LongScreenshot -> {
                    addLog("开始获取长截图: direction=${action.direction}, steps=${action.steps}")
                    
                    // 检查截图服务是否就绪
                    val chatService = ChatScreenshotService.getInstance()
                    if (chatService == null || !chatService.isReady()) {
                        addLog("✗ 截图服务未就绪，请先在服务页面授权截图权限")
                        false
                    } else {
                        // 检查无障碍服务
                        val accessibilityService = MyAccessibilityService.getInstance()
                        if (accessibilityService == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                            addLog("✗ 无障碍服务未就绪或Android版本过低（需要Android 7.0+）")
                            false
                        } else {
                            try {
                                addLog("✓ 截图服务和无障碍服务已就绪，开始获取长截图...")
                                
                                // 设置日志回调，将LongScreenshotHelper的日志输出到测试页面
                                LongScreenshotHelper.setLogCallback { message ->
                                    addLog(message)
                                }
                                
                                val screenshotResult = withContext(Dispatchers.IO) {
                                    LongScreenshotHelper.captureLongScreenshot(action.direction, action.steps)
                                }
                                
                                // 清除日志回调
                                LongScreenshotHelper.clearLogCallback()
                        
                        if (screenshotResult != null) {
                            val longScreenshot = screenshotResult.longScreenshot
                            val currentScreen = screenshotResult.currentScreen
                            
                            val width = longScreenshot.width
                            val longScreenshotHeight = longScreenshot.height
                            val currentScreenHeight = currentScreen.height
                            addLog("✓ 长截图获取成功，尺寸: ${width}x${longScreenshotHeight}")
                            addLog("✓ 当前屏幕截图获取成功，尺寸: ${width}x${currentScreenHeight}")
                            
                            // 转换为Base64并显示（可选：保存到文件）
                            val base64Image = ScreenshotHelper.bitmapToBase64(longScreenshot, this@MainActivity)
                            longScreenshot.recycle()
                            currentScreen.recycle()
                            
                            addLog("✓ 长截图已转换为Base64，长度: ${base64Image.length} 字符")
                            addLog("提示：长截图已获取，可以在聊天页面使用long_screenshot动作")
                            
                            // 等待操作完成
                            delay(500)
                            true
                        } else {
                            addLog("✗ 长截图获取失败")
                            false
                        }
                            } catch (e: Exception) {
                                Log.e(TAG, "获取长截图异常: ${e.message}", e)
                                addLog("✗ 获取长截图异常: ${e.message}")
                                false
                            }
                        }
                    }
                }
                is TestAction.Screenshot -> {
                    addLog("开始截图并保存到相册...")
                    
                    // 检查截图服务是否就绪
                    val chatService = ChatScreenshotService.getInstance()
                    if (chatService == null || !chatService.isReady()) {
                        addLog("✗ 截图服务未就绪，请先在服务页面授权截图权限")
                        false
                    } else {
                        try {
                            addLog("✓ 截图服务已就绪，开始获取截图...")
                            
                            // 获取截图
                            val bitmap = withContext(Dispatchers.IO) {
                                chatService.captureScreenshot()
                            }
                            
                            if (bitmap != null) {
                                val width = bitmap.width
                                val height = bitmap.height
                                addLog("✓ 截图获取成功，尺寸: ${width}x${height}")
                                
                                // 保存到相册
                                val savedUri = ScreenshotHelper.saveBitmapToGallery(
                                    this@MainActivity,
                                    bitmap,
                                    displayName = null,
                                    saveToScreenshotsFolder = true
                                )
                                
                                bitmap.recycle()
                                
                                if (savedUri != null) {
                                    addLog("✓ 截图已保存到相册: $savedUri")
                                    runOnUiThread {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "截图已保存到相册",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    delay(500)
                                    true
                                } else {
                                    addLog("✗ 保存截图到相册失败")
                                    false
                                }
                            } else {
                                addLog("✗ 截图获取失败")
                                false
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "截图并保存异常: ${e.message}", e)
                            addLog("✗ 截图并保存异常: ${e.message}")
                            false
                        }
                    }
                }
                is TestAction.Drag -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val result = accessibilityService.performDrag(action.x1, action.y1, action.x2, action.y2)
                        if (result) {
                            addLog("✓ 拖拽已发送: (${action.x1}, ${action.y1}) -> (${action.x2}, ${action.y2})")
                        } else {
                            addLog("✗ 拖拽发送失败: (${action.x1}, ${action.y1}) -> (${action.x2}, ${action.y2})")
                        }
                        result
                    } else {
                        addLog("✗ Android版本过低，不支持手势拖拽（需要Android 7.0+）")
                        false
                    }
                }
                is TestAction.ClearApp -> {
                    // 通过最近任务列表清理应用（直接点击清除按钮，不需要查找应用）
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        addLog("正在打开最近任务列表...")
                        val result = accessibilityService.clearAppFromRecentTasks()
                        if (result) {
                            addLog("✓ 已点击清除按钮，清理后台应用")
                        } else {
                            addLog("✗ 点击清除按钮失败")
                        }
                        delay(500)
                        result
                    } else {
                        addLog("✗ Android版本过低，不支持最近任务列表清理（需要Android 7.0+）")
                        false
                    }
                }
                is TestAction.GetWechatLink -> {
                    // 依次点击 6 个坐标获取微信链接（基准 1080×2376 相对坐标换算为当前整屏像素），动作间间隔 action.delayMs 毫秒
                    var allSuccess = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val points = accessibilityService.getWechatLinkClickPixels()
                        addLog("开始执行 get_wechat_link（共 6 步，间隔 ${action.delayMs}ms）")
                        for ((i, p) in points.withIndex()) {
                            val (x, y) = p
                            val result = accessibilityService.performClick(x, y)
                            if (result) {
                                addLog("✓ step${i + 1}: 点击 ($x, $y)")
                            } else {
                                addLog("✗ step${i + 1}: 点击失败 ($x, $y)")
                                allSuccess = false
                            }
                            if (i < points.size - 1) {
                                if (i == 3) {
                                    delay(GET_WECHAT_LINK_EXTRA_DELAY_AFTER_STEP4_MS)
                                }
                                delay(action.delayMs)
                            }
                        }
                        if (allSuccess) {
                            addLog("✓ get_wechat_link 完成")
                        }
                    } else {
                        addLog("✗ Android版本过低，不支持手势点击（需要Android 7.0+）")
                        allSuccess = false
                    }
                    allSuccess
                }
            }
            
            // 计算动作执行时间
            val actionDuration = System.currentTimeMillis() - actionStartTime
            addLog("动作执行耗时: ${actionDuration}ms")
            
            // 所有动作执行完成后都等待相同的间隔时间（最后一个动作执行后不需要等待）
            // 确保无论什么动作，无论前后动作是否一致，时间间隔都保持一致
            // 间隔时间从动作完成后开始计算
            // 注意：wait 动作已经包含了等待时间，所以跳过默认间隔
            val isWaitAction = action is TestAction.Wait
            if (index < actions.size - 1 && delayMs > 0 && !isWaitAction) {
                addLog("等待间隔 ${delayMs}ms...")
                delay(delayMs)
            }
        }
        
        addLog("测试执行完成")
        runOnUiThread {
            Toast.makeText(this@MainActivity, "测试执行完成", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 当「是否获取已安装应用」开关开启时，返回 MVP 格式的 install_apps JSON 字符串
     */
    private fun getInstallAppsParamForUpload(): String? {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("fetch_installed_apps_for_cloud", false)) return null
        val list = AppInstallHelper.getInstalledAppNamesForCloud(this)
        if (list.isEmpty()) return null
        return org.json.JSONArray(list).toString()
    }
    
    private fun useNextActionApi(): Boolean {
        return getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getBoolean("use_next_action_api", false)
    }
    
    private fun getImageUrlForNextAction(rawBase64: String): String {
        return when {
            rawBase64.isBlank() -> "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
            rawBase64.startsWith("data:image/") || rawBase64.startsWith("http://") || rawBase64.startsWith("https://") -> rawBase64
            else -> "data:image/png;base64,$rawBase64"
        }
    }
    
    private fun getInstallAppsListForNextAction(): List<String>? {
        val json = getInstallAppsParamForUpload() ?: return null
        return try {
            org.json.JSONArray(json).let { arr ->
                List(arr.length()) { arr.getString(it) }
            }
        } catch (e: Exception) {
            null
        }
    }
    
    // sendChatMessage方法已迁移到ChatFragment
    /*
    private fun sendChatMessage() {
        Log.d(TAG, "sendChatMessage: 被调用")
        try {
            val query = binding.etChatInput.text.toString().trim()
            Log.d(TAG, "sendChatMessage: 输入内容: $query")
            
            if (query.isEmpty()) {
                Log.d(TAG, "sendChatMessage: 输入为空")
                Toast.makeText(this, "请输入消息", Toast.LENGTH_SHORT).show()
                return
            }
            
            // 显示用户消息
            addChatMessage("我", query)
            binding.etChatInput.setText("")
            
            // 检查是否有截图权限
            val chatService = ChatScreenshotService.getInstance()
            val hasPermission = chatService != null && chatService.isReady()
            Log.d(TAG, "sendChatMessage: 检查截图权限，服务就绪=$hasPermission")
            if (!hasPermission) {
                Log.d(TAG, "sendChatMessage: 需要截图权限")
                // 保存待发送的消息
                pendingChatMessage = query
                // 请求截图权限
                requestChatScreenshotPermission()
                addChatMessage("系统", "需要截图权限，请授权")
                return
            }
            
            // 执行发送
            Log.d(TAG, "sendChatMessage: 开始执行发送")
            performSendChatMessage(query)
        } catch (e: Exception) {
            Log.e(TAG, "sendChatMessage异常: ${e.message}", e)
            e.printStackTrace()
            addChatMessage("系统", "发送消息时出错: ${e.message}")
        }
    }
    
    /**
     * 执行发送聊天消息
     */
    private fun performSendChatMessage(query: String) {
        Log.d(TAG, "performSendChatMessage: 开始，query=$query")
        addLog("performSendChatMessage: 开始，query=$query")
        
        // 如果已有任务在运行，先停止
        if (isTaskRunning) {
            Log.w(TAG, "已有任务在运行，先停止旧任务")
            stopTask()
        }
        
        // 检查版本更新（异步执行，不阻塞任务）
        checkVersionUpdate()
        
        // 开始新任务：重置UUID，保存query，设置任务状态
        chatUuid = UUID.randomUUID().toString()
        currentQuery = query
        isTaskRunning = true
        Log.d(TAG, "新任务开始，UUID: $chatUuid, Query: $query")
        addLog("新任务开始，UUID: $chatUuid")
        
        // 禁用发送按钮，启用结束任务按钮，显示加载状态
        try {
            binding.btnSendChat.isEnabled = false
            binding.btnSendChat.text = "任务进行中..."
            binding.btnStopTask.isEnabled = true
            Log.d(TAG, "performSendChatMessage: UI已更新")
        } catch (e: Exception) {
            Log.e(TAG, "更新UI失败: ${e.message}", e)
            addLog("更新UI失败: ${e.message}")
        }
        
        Log.d(TAG, "performSendChatMessage: 准备启动协程")
        addLog("准备启动协程")
        
        try {
            testScope.launch {
                try {
                    Log.d(TAG, "协程开始执行")
                    addLog("协程已启动")
                
                    // 检查截图权限
                    val chatService = ChatScreenshotService.getInstance()
                    val hasPermission = chatService != null && chatService.isReady()
                    Log.d(TAG, "检查截图权限，服务就绪=$hasPermission")
                    addLog("检查截图权限，服务就绪=$hasPermission")
                    if (!hasPermission) {
                        Log.w(TAG, "聊天截图服务未就绪，需要截图权限")
                        addLog("聊天截图服务未就绪，需要截图权限")
                        withContext(Dispatchers.Main) {
                            addChatMessage("系统", "截图权限未授权，请先授权")
                            binding.btnSendChat.isEnabled = true
                            binding.btnSendChat.text = "发送"
                        }
                        return@launch
                    }
                    
                    Log.d(TAG, "截图权限已授权，继续执行")
                    addLog("截图权限已授权，继续执行")
                
                // 先回退到home页面
                Log.d(TAG, "回退到home页面")
                addLog("回退到home页面")
                withContext(Dispatchers.Main) {
                    val accessibilityService = MyAccessibilityService.getInstance()
                    if (accessibilityService != null) {
                        accessibilityService.goHome()
                        Log.d(TAG, "已执行回退到home操作")
                    } else {
                        Log.w(TAG, "无障碍服务未就绪，无法回退到home")
                        addLog("警告：无障碍服务未就绪，无法回退到home")
                    }
                }
                
                // 等待页面切换完成（给系统一些时间完成页面切换）
                Log.d(TAG, "等待页面切换完成...")
                delay(800) // 等待800毫秒，确保页面切换完成
                Log.d(TAG, "页面切换等待完成，开始截图")
                addLog("开始截图")
                
                Log.d(TAG, "开始获取截图")
                // 获取截图
                val screenshot = chatService?.let { 
                    withContext(Dispatchers.IO) {
                        it.captureScreenshot()
                    }
                }
                if (screenshot == null) {
                    Log.w(TAG, "截图获取失败，返回null")
                    withContext(Dispatchers.Main) {
                        addChatMessage("小助手", "截图失败，请重试")
                        binding.btnSendChat.isEnabled = true
                        binding.btnSendChat.text = "发送"
                    }
                    return@launch
                }
                
                Log.d(TAG, "截图获取成功，开始转换为Base64")
                // 转换为Base64
                val base64Image = try {
                    ScreenshotHelper.bitmapToBase64(screenshot, this@MainActivity)
                } catch (e: Exception) {
                    Log.e(TAG, "Base64转换失败: ${e.message}", e)
                    withContext(Dispatchers.Main) {
                        addChatMessage("系统", "图片转换失败: ${e.message}")
                        binding.btnSendChat.isEnabled = true
                        binding.btnSendChat.text = "发送"
                    }
                    return@launch
                }
                
                // 清理Base64字符串（移除可能的换行符和空格，但保留data URI前缀）
                val cleanBase64 = base64Image.trim().replace("\n", "").replace("\r", "").replace(" ", "")
                
                // 验证Base64格式（提取纯base64部分进行验证）
                val (base64Part, isValidBase64) = try {
                    // 如果包含data URI前缀，提取base64部分
                    val part = if (cleanBase64.startsWith("data:image/")) {
                        val base64Index = cleanBase64.indexOf(";base64,")
                        if (base64Index != -1) {
                            cleanBase64.substring(base64Index + 8) // 跳过 ";base64,"
                        } else {
                            Log.e(TAG, "Base64字符串格式错误：缺少;base64,分隔符")
                            null
                        }
                    } else {
                        cleanBase64
                    }
                    
                    if (part == null) {
                        Pair(null, false)
                    } else {
                        // 验证base64字符串长度（必须是4的倍数，或者需要填充）
                        val base64Length = part.length
                        val remainder = base64Length % 4
                        if (remainder != 0) {
                            Log.e(TAG, "Base64字符串长度不正确: $base64Length, 余数: $remainder (必须是4的倍数)")
                            Pair(part, false)
                        } else {
                            // 尝试解码验证
                            Base64.decode(part, Base64.NO_WRAP)
                            Log.d(TAG, "Base64验证通过，长度: $base64Length")
                            Pair(part, true)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Base64验证失败: ${e.message}", e)
                    Pair(null, false)
                }
                
                if (!isValidBase64 || base64Part == null) {
                    Log.e(TAG, "Base64字符串无效，无法发送")
                    withContext(Dispatchers.Main) {
                        addChatMessage("系统", "Base64编码验证失败，请重试")
                        binding.btnSendChat.isEnabled = true
                        binding.btnSendChat.text = "发送"
                    }
                    return@launch
                }
                
                // 重新构建完整的data URI（确保格式正确）
                val finalBase64 = "data:image/jpeg;base64,$base64Part"
                
                // 打印发送内容（图片base64用缩写代替）
                val imagePreview = if (finalBase64.length > 50) {
                    "${finalBase64.take(50)}... (总长度: ${finalBase64.length})"
                } else {
                    finalBase64
                }
                Log.d(TAG, "=== 发送聊天消息 ===")
                Log.d(TAG, "query: $query")
                Log.d(TAG, "uuid: $chatUuid")
                Log.d(TAG, "image (base64): $imagePreview")
                Log.d(TAG, "Base64部分长度: ${base64Part.length}, 是否为4的倍数: ${base64Part.length % 4 == 0}")
                Log.d(TAG, "Base64字符检查: 包含+=${base64Part.contains('+')}, 包含/=${base64Part.contains('/')}, 包含===${base64Part.contains('=')}")
                Log.d(TAG, "完整data URI长度: ${finalBase64.length}")
                withContext(Dispatchers.Main) {
                    addLog("发送消息 - query: $query, uuid: $chatUuid, image: $imagePreview")
                }
                
                // 发送到云侧
                Log.d(TAG, "获取API服务")
                val apiService = NetworkService.getApiService()
                if (apiService == null) {
                    Log.e(TAG, "API服务为null")
                    withContext(Dispatchers.Main) {
                        addChatMessage("系统", "网络服务未初始化")
                        binding.btnSendChat.isEnabled = true
                        binding.btnSendChat.text = "发送"
                    }
                    return@launch
                }
                
                // 对base64字符串进行URL编码，防止在传输过程中被破坏
                // FastAPI会自动进行URL解码，所以我们需要先编码
                val urlEncodedBase64 = try {
                    java.net.URLEncoder.encode(finalBase64, "UTF-8")
                } catch (e: Exception) {
                    Log.e(TAG, "URL编码失败: ${e.message}", e)
                    finalBase64 // 如果编码失败，使用原始字符串
                }
                
                // 获取实际的服务器URL（用于日志）
                val actualServerUrl = NetworkService.getCurrentBaseUrl() ?: DEFAULT_CHAT_SERVER_URL
                val apiPath = if (useNextActionApi()) "next_action" else "upload"
                Log.d(TAG, "开始发送网络请求到: $actualServerUrl$apiPath")
                Log.d(TAG, "URL编码后长度: ${urlEncodedBase64.length}, 原始长度: ${finalBase64.length}")
                addLog("发送请求到: $actualServerUrl$apiPath")
                // 获取当前 Activity 信息
                val (packageName, className) = MyAccessibilityService.getCurrentActivityInfo()
                
                val imei = ProfileManager.getOrGenerateImei(this)
                
                val (response, parsedChatResponse) = try {
                    if (useNextActionApi()) {
                        // next_action 开启时不传递 activity 信息
                        val req = NextActionRequest(
                            task_id = chatUuid,
                            duid = imei,
                            image_url = getImageUrlForNextAction(finalBase64),
                            query = query,
                            install_apps = getInstallAppsListForNextAction()
                        )
                        val r = apiService.sendNextActionRequest(req)
                        Pair(r, r.body()?.toChatResponse())
                    } else {
                        val r = apiService.sendChatMessage(
                            uuid = chatUuid,
                            query = query,
                            image = urlEncodedBase64,
                            packageName = packageName,
                            className = className,
                            imei = imei,
                            outputLanguage = LanguageManager.getSavedLanguage(this),
                            installApps = getInstallAppsParamForUpload()
                        )
                        Pair(r, r.body())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "网络请求异常: ${e.message}", e)
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        addChatMessage("系统", "网络请求失败: ${e.message}")
                        addLog("网络请求异常: ${e.stackTraceToString()}")
                        binding.btnSendChat.isEnabled = true
                        binding.btnSendChat.text = "发送"
                    }
                    return@launch
                }
                
                Log.d(TAG, "收到响应，状态码: ${response.code()}")
                addLog("收到响应，状态码: ${response.code()}")
                if (response.isSuccessful) {
                    val chatResponse = parsedChatResponse
                    if (chatResponse != null) {
                        // 显示云侧回复消息
                        val reply = chatResponse.message ?: chatResponse.reason ?: "无回复内容"
                        withContext(Dispatchers.Main) {
                            addChatMessage("云侧", reply)
                        }
                        
                        // 检查是否有操作指令需要执行
                        val actionType = chatResponse.action_type
                        if (actionType != null) {
                            Log.d(TAG, "收到操作指令: action_type=$actionType, app_name=${chatResponse.app_name}")
                            addLog("收到操作指令: $actionType")
                            
                            // 根据操作类型执行相应的操作
                            when (actionType.lowercase()) {
                                "open" -> {
                                    // 打开应用
                                    val appName = chatResponse.app_name
                                    if (appName != null) {
                                        Log.d(TAG, "执行打开应用操作: $appName")
                                        addLog("执行打开应用: $appName")
                                        
                                        withContext(Dispatchers.Main) {
                                            // 通过应用名查找包名
                                            val packageName = AppMappingManager.getPackageName(appName)
                                            if (packageName != null) {
                                                val success = launchAppFromActivity(packageName)
                                                if (success) {
                                                    addChatMessage("系统", "✓ 已打开应用: $appName")
                                                    addLog("✓ 应用打开成功: $appName")
                                                    // 等待应用启动完成
                                                    delay(1500)
                                                } else {
                                                    addChatMessage("系统", "✗ 打开应用失败: $appName")
                                                    addLog("✗ 应用打开失败: $appName")
                                                }
                                            } else {
                                                addChatMessage("系统", "✗ 未找到应用: $appName（请检查应用是否已安装）")
                                                addLog("✗ 未找到应用: $appName")
                                                // 检测到未找到应用，发送安装请求到云侧
                                                Log.d(TAG, "未找到应用，准备发送安装请求: $appName")
                                                // 注意：MainActivity中的open处理可能不在任务循环中，这里暂时只记录日志
                                                // 实际的安装请求应该在ChatFragment中处理
                                            }
                                        }
                                    } else {
                                        Log.w(TAG, "open操作缺少app_name参数")
                                        addLog("警告: open操作缺少app_name参数")
                                    }
                                }
                                "click" -> {
                                    // 点击操作
                                    // 优先使用click字段（数组格式 [x, y]），如果没有则使用x和y字段
                                    val (x, y) = if (chatResponse.click != null && chatResponse.click.size >= 2) {
                                        // 使用click字段
                                        Pair(chatResponse.click[0], chatResponse.click[1])
                                    } else if (chatResponse.x != null && chatResponse.y != null) {
                                        // 使用x和y字段（旧版兼容）
                                        Pair(chatResponse.x, chatResponse.y)
                                    } else {
                                        Pair(null, null)
                                    }
                                    
                                    if (x != null && y != null) {
                                        Log.d(TAG, "执行点击操作: x=$x, y=$y")
                                        addLog("执行点击: ($x, $y)")
                                        
                                        val accessibilityService = MyAccessibilityService.getInstance()
                                        if (accessibilityService != null) {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                                // 直接调用suspend函数，因为已经在协程中
                                                val success = withContext(Dispatchers.Main) {
                                                    accessibilityService.performClick(x, y)
                                                }
                                                if (success) {
                                                    withContext(Dispatchers.Main) {
                                                        addChatMessage("系统", "✓ 已执行点击: ($x, $y)")
                                                        addLog("✓ 点击成功: ($x, $y)")
                                                    }
                                                } else {
                                                    withContext(Dispatchers.Main) {
                                                        addChatMessage("系统", "✗ 点击失败: ($x, $y)")
                                                        addLog("✗ 点击失败: ($x, $y)")
                                                    }
                                                }
                                                delay(500) // 等待点击完成
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    addChatMessage("系统", "✗ Android版本过低，不支持点击操作")
                                                    addLog("✗ Android版本过低")
                                                }
                                            }
                                        } else {
                                            withContext(Dispatchers.Main) {
                                                addChatMessage("系统", "✗ 无障碍服务未就绪，无法执行点击")
                                                addLog("✗ 无障碍服务未就绪")
                                            }
                                        }
                                    } else {
                                        Log.w(TAG, "click操作缺少坐标参数（需要click字段或x、y字段）")
                                        addLog("警告: click操作缺少坐标参数")
                                        withContext(Dispatchers.Main) {
                                            addChatMessage("系统", "✗ click操作缺少坐标参数")
                                        }
                                    }
                                }
                                "back" -> {
                                    // 返回操作
                                    Log.d(TAG, "执行返回操作")
                                    addLog("执行返回")
                                    withContext(Dispatchers.Main) {
                                        val accessibilityService = MyAccessibilityService.getInstance()
                                        if (accessibilityService != null) {
                                            accessibilityService.goBack()
                                            addChatMessage("系统", "✓ 已执行返回")
                                            addLog("✓ 返回成功")
                                            delay(500)
                                        } else {
                                            addChatMessage("系统", "✗ 无障碍服务未就绪，无法执行返回")
                                            addLog("✗ 无障碍服务未就绪")
                                        }
                                    }
                                }
                                "home" -> {
                                    // 返回主页操作
                                    Log.d(TAG, "执行返回主页操作")
                                    addLog("执行返回主页")
                                    withContext(Dispatchers.Main) {
                                        val accessibilityService = MyAccessibilityService.getInstance()
                                        if (accessibilityService != null) {
                                            accessibilityService.goHome()
                                            addChatMessage("系统", "✓ 已返回主页")
                                            addLog("✓ 返回主页成功")
                                            delay(500)
                                        } else {
                                            addChatMessage("系统", "✗ 无障碍服务未就绪，无法返回主页")
                                            addLog("✗ 无障碍服务未就绪")
                                        }
                                    }
                                }
                                "complete" -> {
                                    // 任务完成，停止循环发送
                                    Log.d(TAG, "收到complete指令，任务完成")
                                    addLog("收到complete指令，任务完成")
                                    withContext(Dispatchers.Main) {
                                        addChatMessage("系统", "✓ 任务完成")
                                        stopTask()
                                    }
                                    return@launch  // 退出当前协程，不再发送截图
                                }
                                else -> {
                                    Log.w(TAG, "未知的操作类型: $actionType")
                                    addLog("警告: 未知的操作类型: $actionType")
                                    withContext(Dispatchers.Main) {
                                        addChatMessage("系统", "未知的操作类型: $actionType")
                                    }
                                }
                            }
                            
                            // 执行完动作后（除了complete），自动截图并发送
                            if (actionType.lowercase() != "complete" && isTaskRunning) {
                                Log.d(TAG, "动作执行完成，等待后自动发送截图")
                                delay(1000) // 等待动作完成
                                // 继续循环发送截图
                                continueTaskLoop()
                            }
                        } else {
                            Log.d(TAG, "响应中没有操作指令")
                            // 如果没有操作指令，继续循环发送截图
                            if (isTaskRunning) {
                                delay(2000) // 等待2秒后继续发送
                                continueTaskLoop()
                            }
                        }
                        
                        // 如果第一次发送成功且任务仍在运行，启动循环（如果没有在动作处理中启动）
                        // 注意：如果已经有动作指令，会在动作执行后启动循环，这里不需要重复启动
                    } else {
                        Log.w(TAG, "响应体为null")
                        withContext(Dispatchers.Main) {
                            addChatMessage("系统", "收到空响应")
                        }
                    }
                } else {
                    val errorBody = response.errorBody()?.string()
                    Log.w(TAG, "请求失败: ${response.code()} - ${response.message()}")
                    Log.w(TAG, "错误响应体: $errorBody")
                    addLog("请求失败: ${response.code()} - ${response.message()}")
                    if (errorBody != null) {
                        addLog("错误详情: $errorBody")
                    }
                    withContext(Dispatchers.Main) {
                        val actualServerUrl = NetworkService.getCurrentBaseUrl() ?: DEFAULT_CHAT_SERVER_URL
                        val errorMsg = if (response.code() == 404) {
                            "API路径不存在 (404)，请检查云侧服务路径是否为: $actualServerUrl$apiPath"
                        } else {
                            "请求失败: ${response.code()} - ${response.message()}"
                        }
                        addChatMessage("系统", errorMsg)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "发送聊天消息异常: ${e.message}", e)
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    addChatMessage("系统", "发送失败: ${e.javaClass.simpleName} - ${e.message}")
                    addLog("错误详情: ${e.stackTraceToString()}")
                }
            } finally {
                // 只有在任务停止时才恢复发送按钮，禁用结束任务按钮
                if (!isTaskRunning) {
                    try {
                        withContext(Dispatchers.Main) {
                            binding.btnSendChat.isEnabled = true
                            binding.btnSendChat.text = "发送"
                            binding.btnStopTask.isEnabled = false
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "恢复UI失败: ${e.message}", e)
                    }
                }
                Log.d(TAG, "发送流程结束")
                addLog("发送流程结束")
            }
            }  // 关闭 testScope.launch
        } catch (e: Exception) {
            Log.e(TAG, "启动协程失败: ${e.message}", e)
            e.printStackTrace()
            addLog("启动协程失败: ${e.javaClass.simpleName} - ${e.message}")
            addLog("错误堆栈: ${e.stackTraceToString()}")
            addChatMessage("系统", "启动协程失败: ${e.message}")
            // 恢复发送按钮
            try {
                binding.btnSendChat.isEnabled = true
                binding.btnSendChat.text = "发送"
            } catch (ex: Exception) {
                Log.e(TAG, "恢复UI失败: ${ex.message}", ex)
            }
        }
    }
    
    
    /**
     * 继续任务循环：截图并发送到云侧
     * 使用相同的query和uuid
     */
    private fun continueTaskLoop() {
        if (!isTaskRunning || currentQuery == null) {
            Log.w(TAG, "任务未运行或query为空，不继续循环")
            return
        }
        
        // 取消之前的任务Job（如果存在）
        taskJob?.cancel()
        
        // 启动新的循环任务
        taskJob = testScope.launch {
            try {
                Log.d(TAG, "继续任务循环，query=$currentQuery, uuid=$chatUuid")
                addLog("继续发送截图...")
                
                // 检查截图权限
                val chatService = ChatScreenshotService.getInstance()
                val hasPermission = chatService != null && chatService.isReady()
                if (!hasPermission) {
                    Log.w(TAG, "截图权限未就绪，停止循环")
                    addLog("截图权限未就绪，停止循环")
                    stopTask()
                    return@launch
                }
                
                // 先回退到home页面，确保从home页面开始执行操作
                Log.d(TAG, "继续任务循环前，先回退到home页面")
                addLog("回退到home页面...")
                withContext(Dispatchers.Main) {
                    val accessibilityService = MyAccessibilityService.getInstance()
                    if (accessibilityService != null) {
                        accessibilityService.goHome()
                        Log.d(TAG, "已执行回退到home操作")
                    } else {
                        Log.w(TAG, "无障碍服务未就绪，无法回退到home")
                        addLog("警告：无障碍服务未就绪，无法回退到home")
                    }
                }
                
                // 等待页面切换完成
                delay(1000)
                
                // 获取截图
                val screenshot = withContext(Dispatchers.IO) {
                    chatService?.captureScreenshot()
                }
                
                if (screenshot == null) {
                    Log.w(TAG, "截图获取失败")
                    addLog("截图获取失败，继续重试...")
                    delay(2000)
                    continueTaskLoop() // 重试
                    return@launch
                }
                
                // 转换为Base64
                val base64Image = try {
                    ScreenshotHelper.bitmapToBase64(screenshot, this@MainActivity)
                } catch (e: Exception) {
                    Log.e(TAG, "Base64转换失败: ${e.message}", e)
                    delay(2000)
                    continueTaskLoop() // 重试
                    return@launch
                }
                
                // 清理Base64字符串
                val cleanBase64 = base64Image.trim().replace("\n", "").replace("\r", "").replace(" ", "")
                
                // 验证Base64格式
                val (base64Part, isValidBase64) = try {
                    val part = if (cleanBase64.startsWith("data:image/")) {
                        val base64Index = cleanBase64.indexOf(";base64,")
                        if (base64Index != -1) {
                            cleanBase64.substring(base64Index + 8)
                        } else {
                            null
                        }
                    } else {
                        cleanBase64
                    }
                    
                    if (part == null) {
                        Pair(null, false)
                    } else {
                        val base64Length = part.length
                        val remainder = base64Length % 4
                        if (remainder != 0) {
                            Pair(part, false)
                        } else {
                            Base64.decode(part, Base64.NO_WRAP)
                            Pair(part, true)
                        }
                    }
                } catch (e: Exception) {
                    Pair(null, false)
                }
                
                if (!isValidBase64 || base64Part == null) {
                    Log.e(TAG, "Base64验证失败，重试")
                    delay(2000)
                    continueTaskLoop()
                    return@launch
                }
                
                // 重新构建完整的data URI
                val finalBase64 = "data:image/jpeg;base64,$base64Part"
                
                // URL编码
                val urlEncodedBase64 = try {
                    java.net.URLEncoder.encode(finalBase64, "UTF-8")
                } catch (e: Exception) {
                    finalBase64
                }
                
                // 发送到云侧
                val apiService = NetworkService.getApiService()
                if (apiService == null) {
                    Log.e(TAG, "API服务未初始化")
                    stopTask()
                    return@launch
                }
                
                // 获取当前 Activity 信息
                val (packageName, className) = MyAccessibilityService.getCurrentActivityInfo()
                
                val (response, parsedChatResponse) = try {
                    val imei = ProfileManager.getOrGenerateImei(this)
                    if (useNextActionApi()) {
                        // next_action 开启时不传递 activity 信息
                        val req = NextActionRequest(
                            task_id = chatUuid,
                            duid = imei,
                            image_url = getImageUrlForNextAction(finalBase64),
                            query = currentQuery!!,
                            install_apps = getInstallAppsListForNextAction()
                        )
                        val r = apiService.sendNextActionRequest(req)
                        Pair(r, r.body()?.toChatResponse())
                    } else {
                        val r = apiService.sendChatMessage(
                            uuid = chatUuid,
                            query = currentQuery!!,
                            image = urlEncodedBase64,
                            packageName = packageName,
                            className = className,
                            imei = imei,
                            outputLanguage = LanguageManager.getSavedLanguage(this),
                            installApps = getInstallAppsParamForUpload()
                        )
                        Pair(r, r.body())
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "网络请求异常: ${e.message}", e)
                    delay(2000)
                    continueTaskLoop() // 重试
                    return@launch
                }
                
                if (response.isSuccessful) {
                    val chatResponse = parsedChatResponse
                    if (chatResponse != null) {
                        // 显示云侧回复
                        val reply = chatResponse.message ?: chatResponse.reason ?: "无回复内容"
                        withContext(Dispatchers.Main) {
                            addChatMessage("云侧", reply)
                        }
                        
                        // 检查操作指令
                        val actionType = chatResponse.action_type
                        if (actionType != null) {
                            Log.d(TAG, "收到操作指令: $actionType")
                            addLog("收到操作指令: $actionType")
                            
                            // 处理complete指令
                            if (actionType.lowercase() == "complete") {
                                Log.d(TAG, "收到complete指令，任务完成")
                                withContext(Dispatchers.Main) {
                                    val reason = chatResponse.reason ?: ""
                                    val isMaxStepTriggered = reason.contains("执行步数已达到最大限制") || 
                                                             reason.contains("超过最大执行步数限制")
                                    val displayText = when {
                                        useNextActionApi() && !chatResponse.params.isNullOrBlank() -> chatResponse.params!!
                                        isMaxStepTriggered && reason.isNotBlank() -> reason + (chatResponse.thought?.takeIf { it.isNotBlank() }?.let { "\n$it" } ?: "")
                                        !chatResponse.thought.isNullOrBlank() -> chatResponse.thought!!
                                        reason.isNotBlank() -> reason
                                        else -> "任务已完成"
                                    }
                                    if (displayText.isNotBlank() && displayText != "任务已完成") {
                                        addChatMessage("小助手", displayText)
                                    }
                                    if (!shouldSuppressCustomAssistantTaskCompletedBanner()) {
                                        addChatMessage("系统", "任务已完成")
                                    }
                                    stopTask()
                                }
                                return@launch
                            }
                            
                            // 处理answer指令（等同于complete，直接结束任务）
                            if (actionType.lowercase() == "answer") {
                                val answerText = chatResponse.text
                                if (answerText != null) {
                                    Log.d(TAG, "收到answer指令: $answerText，任务完成")
                                    withContext(Dispatchers.Main) {
                                        // 步骤1: 将TopoClaw应用带到前台（不重新启动）
                                        try {
                                            val success = bringAppToForeground()
                                            if (success) {
                                                Log.d(TAG, "已返回到TopoClaw应用页面")
                                                // 等待应用切换到前台
                                                delay(300)
                                            } else {
                                                Log.w(TAG, "返回到TopoClaw应用页面失败")
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "返回到应用页面时出错: ${e.message}", e)
                                        }
                                        
                                        // 步骤2: 显示answer消息
                                        addChatMessage("系统", answerText)
                                        
                                        // 步骤3: 显示"任务已完成 ✓"消息
                                        if (!shouldSuppressCustomAssistantTaskCompletedBanner()) {
                                            addChatMessage("系统", "任务已完成 ✓")
                                        }
                                        
                                        // 步骤4: 停止任务（不再继续循环）
                                        stopTask()
                                    }
                                    return@launch
                                } else {
                                    // 即使没有文本，也停止任务
                                    withContext(Dispatchers.Main) {
                                        // 返回到应用前台
                                        try {
                                            val success = bringAppToForeground()
                                            if (success) {
                                                delay(300)
                                            }
                                        } catch (e: Exception) {
                                            Log.e(TAG, "返回到应用页面时出错: ${e.message}", e)
                                        }
                                        
                                        if (!shouldSuppressCustomAssistantTaskCompletedBanner()) {
                                            addChatMessage("系统", "任务已完成 ✓")
                                        }
                                        stopTask()
                                    }
                                    return@launch
                                }
                            }
                            
                            // 执行其他操作（open, click, back, home等）
                            executeAction(chatResponse)
                            
                            // 执行完动作后，继续循环
                            if (isTaskRunning) {
                                delay(1000)
                                continueTaskLoop()
                            }
                        } else {
                            // 没有操作指令，继续循环
                            if (isTaskRunning) {
                                delay(2000)
                                continueTaskLoop()
                            }
                        }
                    } else {
                        // 响应体为空，继续循环
                        if (isTaskRunning) {
                            delay(2000)
                            continueTaskLoop()
                        }
                    }
                } else {
                    Log.w(TAG, "请求失败: ${response.code()}")
                    delay(2000)
                    continueTaskLoop() // 重试
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.d(TAG, "任务循环被取消")
                    return@launch
                }
                Log.e(TAG, "任务循环异常: ${e.message}", e)
                if (isTaskRunning) {
                    delay(2000)
                    continueTaskLoop() // 重试
                }
            }
        }
    }
    
    /**
     * 执行动作（从continueTaskLoop中调用，避免代码重复）
     */
    private suspend fun executeAction(chatResponse: ChatResponse) {
        val actionType = chatResponse.action_type ?: return
        
        when (actionType.lowercase()) {
            "open" -> {
                val appName = chatResponse.app_name
                if (appName != null) {
                    withContext(Dispatchers.Main) {
                        val packageName = AppMappingManager.getPackageName(appName)
                        if (packageName != null) {
                            val success = launchAppFromActivity(packageName)
                            if (success) {
                                addChatMessage("系统", "✓ 已打开应用: $appName")
                                delay(1500)
                            } else {
                                addChatMessage("系统", "✗ 打开应用失败: $appName")
                            }
                        } else {
                            addChatMessage("系统", "✗ 未找到应用: $appName")
                        }
                    }
                }
            }
            "click" -> {
                // 优先使用click字段（数组格式 [x, y]），如果没有则使用x和y字段
                val (x, y) = if (chatResponse.click != null && chatResponse.click.size >= 2) {
                    // 使用click字段
                    Pair(chatResponse.click[0], chatResponse.click[1])
                } else if (chatResponse.x != null && chatResponse.y != null) {
                    // 使用x和y字段（旧版兼容）
                    Pair(chatResponse.x, chatResponse.y)
                } else {
                    Pair(null, null)
                }
                
                if (x != null && y != null) {
                    val accessibilityService = MyAccessibilityService.getInstance()
                    if (accessibilityService != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val success = withContext(Dispatchers.Main) {
                            accessibilityService.performClick(x, y)
                        }
                        withContext(Dispatchers.Main) {
                            if (success) {
                                addChatMessage("系统", "✓ 已执行点击: ($x, $y)")
                            } else {
                                addChatMessage("系统", "✗ 点击失败: ($x, $y)")
                            }
                        }
                        delay(500)
                    }
                } else {
                    Log.w(TAG, "click操作缺少坐标参数（需要click字段或x、y字段）")
                    withContext(Dispatchers.Main) {
                        addChatMessage("系统", "✗ click操作缺少坐标参数")
                    }
                }
            }
            "back" -> {
                withContext(Dispatchers.Main) {
                    MyAccessibilityService.getInstance()?.goBack()
                    addChatMessage("系统", "✓ 已执行返回")
                }
                delay(500)
            }
            "home" -> {
                withContext(Dispatchers.Main) {
                    MyAccessibilityService.getInstance()?.goHome()
                    addChatMessage("系统", "✓ 已返回主页")
                }
                delay(500)
            }
            "type" -> {
                // TYPE操作：支持两种格式
                // 1) [x, y, text]: 先点击坐标激活输入框，然后输入文本
                // 2) [text]: 直接输入到当前聚焦的输入框
                val typeParams = chatResponse.type
                if (typeParams != null) {
                    val accessibilityService = MyAccessibilityService.getInstance()
                    if (accessibilityService != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        when (typeParams.size) {
                            1 -> {
                                // 格式：[text] - 直接输入到当前聚焦的输入框
                                val inputText = when (val textParam = typeParams[0]) {
                                    is String -> textParam
                                    is Number -> textParam.toString()
                                    else -> textParam.toString()
                                }
                                
                                Log.d(TAG, "执行TYPE操作: 直接输入文本: $inputText")
                                addLog("执行TYPE: 直接输入: $inputText")
                                
                                val inputSuccess = withContext(Dispatchers.Main) {
                                    accessibilityService.inputText(inputText)
                                }
                                
                                withContext(Dispatchers.Main) {
                                    if (inputSuccess) {
                                        addChatMessage("系统", "✓ 已执行输入: 输入\"$inputText\"")
                                        addLog("✓ TYPE操作成功")
                                    } else {
                                        addChatMessage("系统", "✗ 输入文本失败: $inputText")
                                        addLog("✗ 输入文本失败")
                                    }
                                }
                                delay(500)
                            }
                            3 -> {
                                // 格式：[x, y, text] - 先点击坐标再输入
                                val x = (typeParams[0] as? Number)?.toInt() ?: return
                                val y = (typeParams[1] as? Number)?.toInt() ?: return
                                val inputText = when (val textParam = typeParams[2]) {
                                    is String -> textParam
                                    is Number -> textParam.toString()
                                    else -> textParam.toString()
                                }
                                
                                Log.d(TAG, "执行TYPE操作: 点击($x, $y)后输入文本: $inputText")
                                addLog("执行TYPE: 点击($x, $y)后输入: $inputText")
                                
                                // 先点击坐标激活输入框
                                val clickSuccess = withContext(Dispatchers.Main) {
                                    accessibilityService.performClick(x, y)
                                }
                                
                                if (clickSuccess) {
                                    delay(300) // 等待输入框激活
                                    
                                    // 然后输入文本
                                    val inputSuccess = withContext(Dispatchers.Main) {
                                        accessibilityService.inputText(inputText)
                                    }
                                    
                                    withContext(Dispatchers.Main) {
                                        if (inputSuccess) {
                                            addChatMessage("系统", "✓ 已执行输入: 点击($x, $y)后输入\"$inputText\"")
                                            addLog("✓ TYPE操作成功")
                                        } else {
                                            addChatMessage("系统", "✗ 输入文本失败: $inputText")
                                            addLog("✗ 输入文本失败")
                                        }
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        addChatMessage("系统", "✗ 点击输入框失败: ($x, $y)")
                                        addLog("✗ 点击输入框失败")
                                    }
                                }
                                delay(500)
                            }
                            else -> {
                                Log.w(TAG, "type操作参数不正确，需要type字段包含[text]或[x, y, text]")
                                withContext(Dispatchers.Main) {
                                    addChatMessage("系统", "✗ type操作参数不正确: 期望1或3个元素，实际${typeParams.size}个")
                                }
                            }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            addChatMessage("系统", "✗ 无障碍服务未就绪或Android版本过低")
                            addLog("✗ 无障碍服务未就绪")
                        }
                    }
                } else {
                    Log.w(TAG, "type操作参数不正确，缺少type字段")
                    withContext(Dispatchers.Main) {
                        addChatMessage("系统", "✗ type操作参数不正确: 缺少type字段")
                    }
                }
            }
            "wait" -> {
                // WAIT: 暂停执行一段时间（默认0.1秒）
                Log.d(TAG, "执行WAIT操作")
                addLog("执行WAIT: 暂停0.1秒")
                delay(100)
                withContext(Dispatchers.Main) {
                    addChatMessage("系统", "✓ 已等待0.1秒")
                }
            }
            "swipe" -> {
                // SWIPE[x1,y1,x2,y2]: 滑动操作
                val swipeParams = chatResponse.swipe
                if (swipeParams != null && swipeParams.size >= 4) {
                    val x1 = swipeParams[0]
                    val y1 = swipeParams[1]
                    val x2 = swipeParams[2]
                    val y2 = swipeParams[3]
                    
                    // 验证滑动距离≥100像素
                    val distance = sqrt(
                        ((x2 - x1) * (x2 - x1) + (y2 - y1) * (y2 - y1)).toDouble()
                    ).toInt()
                    
                    if (distance < 100) {
                        Log.w(TAG, "滑动距离不足100像素: $distance")
                        withContext(Dispatchers.Main) {
                            addChatMessage("系统", "✗ 滑动距离不足100像素")
                        }
                    } else {
                        Log.d(TAG, "执行SWIPE操作: ($x1, $y1) -> ($x2, $y2), 距离: $distance")
                        addLog("执行SWIPE: ($x1, $y1) -> ($x2, $y2)")
                        
                        val accessibilityService = MyAccessibilityService.getInstance()
                        if (accessibilityService != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            val success = withContext(Dispatchers.Main) {
                                accessibilityService.performSwipe(x1, y1, x2, y2)
                            }
                            
                            withContext(Dispatchers.Main) {
                                if (success) {
                                    addChatMessage("系统", "✓ 已执行滑动: ($x1, $y1) -> ($x2, $y2)")
                                    addLog("✓ SWIPE操作成功")
                                } else {
                                    addChatMessage("系统", "✗ 滑动失败: ($x1, $y1) -> ($x2, $y2)")
                                    addLog("✗ SWIPE操作失败")
                                }
                            }
                            delay(500)
                        } else {
                            withContext(Dispatchers.Main) {
                                addChatMessage("系统", "✗ 无障碍服务未就绪或Android版本过低")
                                addLog("✗ 无障碍服务未就绪")
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "swipe操作参数不正确，需要swipe字段包含[x1, y1, x2, y2]")
                    withContext(Dispatchers.Main) {
                        addChatMessage("系统", "✗ swipe操作参数不正确")
                    }
                }
            }
            "long_press" -> {
                // LONG_PRESS[x,y]: 长按操作
                val longPressParams = chatResponse.long_press
                if (longPressParams != null && longPressParams.size >= 2) {
                    val x = longPressParams[0]
                    val y = longPressParams[1]
                    
                    Log.d(TAG, "执行LONG_PRESS操作: ($x, $y)")
                    addLog("执行LONG_PRESS: ($x, $y)")
                    
                    val accessibilityService = MyAccessibilityService.getInstance()
                    if (accessibilityService != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val success = withContext(Dispatchers.Main) {
                            accessibilityService.performLongClick(x, y)
                        }
                        
                        withContext(Dispatchers.Main) {
                            if (success) {
                                addChatMessage("系统", "✓ 已执行长按: ($x, $y)")
                                addLog("✓ LONG_PRESS操作成功")
                            } else {
                                addChatMessage("系统", "✗ 长按失败: ($x, $y)")
                                addLog("✗ LONG_PRESS操作失败")
                            }
                        }
                        delay(500)
                    } else {
                        withContext(Dispatchers.Main) {
                            addChatMessage("系统", "✗ 无障碍服务未就绪或Android版本过低")
                            addLog("✗ 无障碍服务未就绪")
                        }
                    }
                } else {
                    Log.w(TAG, "long_press操作参数不正确，需要long_press字段包含[x, y]")
                    withContext(Dispatchers.Main) {
                        addChatMessage("系统", "✗ long_press操作参数不正确")
                    }
                }
            }
        }
    }
    
    /**
     * 停止任务
     */
    private fun stopTask() {
        if (!isTaskRunning) {
            return
        }
        
        Log.d(TAG, "停止任务")
        isTaskRunning = false
        currentQuery = null
        taskJob?.cancel()
        taskJob = null
        
        // 恢复发送按钮，禁用结束任务按钮
        try {
            binding.btnSendChat.isEnabled = true
            binding.btnSendChat.text = "发送"
            binding.btnStopTask.isEnabled = false
        } catch (e: Exception) {
            Log.e(TAG, "恢复UI失败: ${e.message}", e)
        }
        
        addLog("任务已停止")
    }

    /**
     * 全局兜底：当任务结果已返回但当前会话Fragment不可用时，仍要清理右下角“执行中”图标。
     */
    private fun clearGlobalTaskIndicator(reason: String) {
        isTaskRunning = false
        TaskIndicatorOverlayManager.updateOverlayForTaskStatus(false)
        if (!TaskIndicatorOverlayManager.isCompanionModeEnabled(this)) {
            TaskIndicatorOverlayManager.hide()
        }
        Log.d(TAG, "clearGlobalTaskIndicator: reason=$reason")
    }
    
    /**
     * 请求聊天截图权限
     */
    private fun requestChatScreenshotPermission() {
        Log.d(TAG, "requestChatScreenshotPermission: 请求截图权限")
        addLog("正在请求截图权限...")
        try {
            val mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
            startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION_CHAT)
        } catch (e: Exception) {
            Log.e(TAG, "请求截图权限失败: ${e.message}", e)
            addChatMessage("系统", "请求截图权限失败: ${e.message}")
        }
    }
    
    /**
     * 更新聊天权限按钮状态
     */
    private fun updateChatPermissionButton() {
        val chatService = ChatScreenshotService.getInstance()
        val hasPermission = chatService != null && chatService.isReady()
        if (hasPermission) {
            binding.btnRequestChatPermission.text = "截图权限已授权 ✓"
            binding.btnRequestChatPermission.isEnabled = false
        } else {
            binding.btnRequestChatPermission.text = "授权截图权限"
            binding.btnRequestChatPermission.isEnabled = true
        }
    }
    
    
    /**
     * 添加聊天消息到显示区域
     */
    private fun addChatMessage(sender: String, message: String) {
        runOnUiThread {
            val currentText = binding.tvChatMessages.text.toString()
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            val newMessage = if (currentText == "等待消息...") {
                "[$timestamp] $sender: $message"
            } else {
                "$currentText\n[$timestamp] $sender: $message"
            }
            binding.tvChatMessages.text = newMessage
            
            // 滚动到底部
            binding.svChatMessages.post {
                binding.svChatMessages.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }
    */
    
    /**
     * 通知技能页面刷新（现在技能在独立的SkillFragment中）
     */
    fun notifySkillFragmentRefresh() {
        try {
            val fragmentManager = supportFragmentManager
            val skillFragment = fragmentManager.fragments.find { it is SkillFragment } as? SkillFragment
            skillFragment?.let { fragment ->
                // 使用反射调用 refreshSkillsList 方法，避免编译时依赖问题
                try {
                    val method = SkillFragment::class.java.getMethod("refreshSkillsList")
                    method.invoke(fragment)
                } catch (e: Exception) {
                    Log.w(TAG, "调用 refreshSkillsList 失败: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "通知技能页面刷新失败: ${e.message}")
        }
    }
    
    /**
     * 切换到技能页面（现在技能在独立的SkillFragment中）
     */
    fun switchToSkillPage() {
        try {
            binding.bottomNavigation.selectedItemId = R.id.nav_skill
        } catch (e: Exception) {
            Log.w(TAG, "切换到技能页面失败: ${e.message}")
        }
    }
    
    /**
     * 注册问字按钮Broadcast接收器
     */
    private fun registerQuestionButtonReceiver() {
        if (questionButtonReceiver != null) {
            return  // 已经注册
        }
        
        questionButtonReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                try {
                    if (intent?.action == SimpleInputMethodService.ACTION_QUESTION_BUTTON_CLICKED) {
                        val screenshotBase64 = intent.getStringExtra(SimpleInputMethodService.EXTRA_SCREENSHOT_BASE64)
                        val userQuery = intent.getStringExtra(SimpleInputMethodService.EXTRA_TEXT) ?: ""
                        
                        Log.d(TAG, "收到问字按钮Broadcast，query长度: ${userQuery.length}, screenshot长度: ${screenshotBase64?.length ?: 0}")
                        
                        if (screenshotBase64 != null) {
                            // 在主线程中处理（使用Handler确保在主线程执行）
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                testScope.launch {
                                    handleQuestionButtonClick(screenshotBase64, userQuery)
                                }
                            }
                        } else {
                            Log.w(TAG, "问字按钮Broadcast缺少截图数据")
                            Toast.makeText(this@MainActivity, "截图数据缺失", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "处理问字按钮Broadcast失败: ${e.message}", e)
                    Toast.makeText(this@MainActivity, "处理失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        try {
            val filter = IntentFilter(SimpleInputMethodService.ACTION_QUESTION_BUTTON_CLICKED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(questionButtonReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(questionButtonReceiver, filter)
            }
            Log.d(TAG, "问字按钮Broadcast接收器已注册")
        } catch (e: Exception) {
            Log.e(TAG, "注册问字按钮Broadcast接收器失败: ${e.message}", e)
        }
    }
    
    /**
     * 取消注册问字按钮Broadcast接收器
     */
    private fun unregisterQuestionButtonReceiver() {
        questionButtonReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
                questionButtonReceiver = null
                Log.d(TAG, "问字按钮Broadcast接收器已取消注册")
            } catch (e: Exception) {
                Log.e(TAG, "取消注册问字按钮Broadcast接收器失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 注册通知栏按钮Broadcast接收器
     */
    private fun registerNotificationActionReceiver() {
        notificationActionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent == null) return
                
                when (intent.action) {
                    TaskIndicatorOverlayManager.ACTION_BACK_TO_APP -> {
                        Log.d(TAG, "通知栏按钮：返回应用")
                        bringAppToForeground()
                    }
                    TaskIndicatorOverlayManager.ACTION_START_TASK -> {
                        // 检查是否从无障碍快捷方式触发
                        val fromAccessibilityShortcut = intent.getBooleanExtra("from_accessibility_shortcut", false)
                        if (fromAccessibilityShortcut) {
                            Log.d(TAG, "无障碍快捷方式：发起任务")
                            // 从无障碍快捷方式触发，自动打开语音面板并开始录音
                            TaskIndicatorOverlayManager.showInputDialogFromAccessibility(this@MainActivity)
                        } else {
                            Log.d(TAG, "通知栏按钮：发起任务")
                            // 从通知栏触发，正常显示输入弹窗
                            TaskIndicatorOverlayManager.showInputDialogForNotification(this@MainActivity)
                        }
                    }
                    TaskIndicatorOverlayManager.ACTION_SHOW_OVERLAY -> {
                        Log.d(TAG, "通知栏按钮：打开悬浮球")
                        // 隐藏通知栏，显示悬浮球
                        TaskIndicatorOverlayManager.hideNotificationBarFromReceiver(this@MainActivity)
                        TaskIndicatorOverlayManager.showCompanionMode(this@MainActivity)
                    }
                    TaskIndicatorOverlayManager.ACTION_CLOSE_APP -> {
                        Log.d(TAG, "通知栏按钮：关闭应用")
                        // 清理所有资源
                        TaskIndicatorOverlayManager.cleanup()
                        // 关闭应用
                        finish()
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(TaskIndicatorOverlayManager.ACTION_BACK_TO_APP)
            addAction(TaskIndicatorOverlayManager.ACTION_START_TASK)
            addAction(TaskIndicatorOverlayManager.ACTION_SHOW_OVERLAY)
            addAction(TaskIndicatorOverlayManager.ACTION_CLOSE_APP)
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(notificationActionReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(notificationActionReceiver, filter)
            }
            Log.d(TAG, "通知栏按钮Broadcast接收器已注册")
        } catch (e: Exception) {
            Log.e(TAG, "注册通知栏按钮Broadcast接收器失败: ${e.message}", e)
        }
    }

    /**
     * 注册“监视通知栏”事件接收器。
     * 由 NotificationMonitorService 广播事件，MainActivity 负责把内容转发给 TopoClaw。
     */
    private fun registerMonitoredNotificationReceiver() {
        monitoredNotificationReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != NotificationMonitorService.ACTION_MONITORED_NOTIFICATION) return
                val pkg = intent.getStringExtra(NotificationMonitorService.EXTRA_PACKAGE)
                val appName = intent.getStringExtra(NotificationMonitorService.EXTRA_APP_NAME)
                val title = intent.getStringExtra(NotificationMonitorService.EXTRA_TITLE)
                val text = intent.getStringExtra(NotificationMonitorService.EXTRA_TEXT)
                handleMonitoredNotification(pkg, appName, title, text)
            }
        }

        val filter = IntentFilter(NotificationMonitorService.ACTION_MONITORED_NOTIFICATION)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(monitoredNotificationReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(monitoredNotificationReceiver, filter)
            }
            Log.d(TAG, "监视通知栏事件接收器已注册")
        } catch (e: Exception) {
            Log.e(TAG, "注册监视通知栏事件接收器失败: ${e.message}", e)
        }
    }

    /**
     * 将通知内容转为一条对 TopoClaw 的消息并发送。
     */
    private fun handleMonitoredNotification(pkg: String?, appName: String?, title: String?, text: String?) {
        val safePkg = pkg?.trim().orEmpty()
        val safeAppName = appName?.trim().orEmpty()
        val safeTitle = title?.trim().orEmpty()
        val safeText = text?.trim().orEmpty()
        if (safePkg.isBlank() && safeTitle.isBlank() && safeText.isBlank()) return
        if (safePkg == packageName) return
        if (safePkg.equals("android", ignoreCase = true)) return
        if (!NotificationMonitorService.isEnabled(this)) return

        val message = buildString {
            append("您收到一条来自手机端的通知，请查收")
            if (safeAppName.isNotBlank() || safePkg.isNotBlank()) {
                append("\n应用: ")
                if (safeAppName.isNotBlank()) {
                    append(safeAppName)
                    if (safePkg.isNotBlank()) append(" (").append(safePkg).append(")")
                } else {
                    append(safePkg)
                }
            }
            if (safeTitle.isNotBlank()) append("\n标题: ").append(safeTitle)
            if (safeText.isNotBlank()) append("\n内容: ").append(safeText)
        }

        testScope.launch {
            try {
                val targetConversationId = "custom_topoclaw"
                val targetAssistant = CustomAssistantManager.getById(this@MainActivity, targetConversationId)
                if (targetAssistant == null) {
                    Log.w(TAG, "监视通知栏：未找到目标会话 custom_topoclaw，跳过发送")
                    return@launch
                }

                val topoClawConversation = Conversation(
                    id = targetAssistant.id,
                    name = targetAssistant.name,
                    avatar = targetAssistant.avatar,
                    lastMessage = null,
                    lastMessageTime = System.currentTimeMillis()
                )
                if (!ensureChatPageThenSwitchTo(topoClawConversation)) {
                    Log.w(TAG, "监视通知栏：切换到 custom_topoclaw 对话失败")
                    return@launch
                }

                delay(800)
                var chatFragment = findChatFragmentByConversationIdStrict(targetConversationId)
                var retry = 0
                while ((chatFragment == null || !chatFragment.isAdded || chatFragment.view == null) && retry < 10) {
                    delay(200)
                    retry++
                    chatFragment = findChatFragmentByConversationIdStrict(targetConversationId)
                }
                if (chatFragment == null || !chatFragment.isAdded || chatFragment.view == null) {
                    Log.w(TAG, "监视通知栏：ChatFragment未就绪，放弃发送")
                    return@launch
                }
                val currentConvId = (chatFragment.arguments?.getSerializable("conversation") as? Conversation)?.id
                if (currentConvId != targetConversationId) {
                    Log.w(TAG, "监视通知栏：会话不匹配，期望=$targetConversationId，实际=$currentConvId，放弃发送")
                    return@launch
                }
                chatFragment.executeQueryInternal(message)
                Log.d(TAG, "监视通知栏：已自动发送到 custom_topoclaw 对话")
            } catch (e: Exception) {
                Log.e(TAG, "监视通知栏：发送失败: ${e.message}", e)
            }
        }
    }

    /**
     * 严格按 conversationId 查找 ChatFragment，不做兜底，避免误发到其他会话。
     */
    private fun findChatFragmentByConversationIdStrict(conversationId: String): ChatFragment? {
        val current = getActiveFragment() as? ChatFragment
        val currentConvId = (current?.arguments?.getSerializable("conversation") as? Conversation)?.id
        if (current != null && currentConvId == conversationId) return current

        return supportFragmentManager.fragments
            .filterIsInstance<ChatFragment>()
            .firstOrNull {
                val id = (it.arguments?.getSerializable("conversation") as? Conversation)?.id
                id == conversationId
            }
    }
    
    /**
     * 处理问字按钮点击
     * 1. 跳转到TopoClaw界面
     * 2. 发送带图任务
     */
    private suspend fun handleQuestionButtonClick(screenshotBase64: String, userQuery: String) = withContext(Dispatchers.Main) {
        try {
            Log.d(TAG, "handleQuestionButtonClick: 开始处理，query='$userQuery'")
            
            // 步骤1: 跳转到TopoClaw界面
            val switchSuccess = switchToChatPage()
            if (!switchSuccess) {
                Log.e(TAG, "跳转到TopoClaw界面失败")
                Toast.makeText(this@MainActivity, "跳转失败，请手动进入${ChatConstants.ASSISTANT_DISPLAY_NAME}", Toast.LENGTH_SHORT).show()
                return@withContext
            }
            
            // 等待Fragment创建完成（增加等待时间，确保onViewCreated执行完毕）
            delay(800)
            
            // 步骤2: 获取ChatFragment并发送带图任务
            var chatFragment = getActiveFragment() as? ChatFragment
            if (chatFragment == null) {
                Log.d(TAG, "ChatFragment未找到，等待重试...")
                // 重试几次，每次等待更长时间
                var retryCount = 0
                while (retryCount < 15 && chatFragment == null) {
                    delay(300)
                    retryCount++
                    chatFragment = getActiveFragment() as? ChatFragment
                    if (chatFragment != null) {
                        // 找到Fragment后，再等待一下确保onViewCreated执行完毕
                        delay(200)
                    }
                }
            } else {
                // 找到Fragment后，再等待一下确保onViewCreated执行完毕
                delay(200)
            }
            
            if (chatFragment == null) {
                Log.e(TAG, "ChatFragment未找到，无法发送任务")
                Toast.makeText(this@MainActivity, "界面加载失败，请重试", Toast.LENGTH_SHORT).show()
            } else {
                // 找到Fragment，发送任务（sendQuestionTask内部会再次检查Fragment状态）
                Log.d(TAG, "找到ChatFragment，准备发送任务")
                chatFragment.sendQuestionTask(screenshotBase64, userQuery)
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理问字按钮点击失败: ${e.message}", e)
            Toast.makeText(this@MainActivity, "处理失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 获取人工客服WebSocket实例（供ChatFragment使用）
     */
    fun getCustomerServiceWebSocket(): CustomerServiceWebSocket? {
        return customerServiceWebSocket
    }

    fun triggerLocationMonitorCheckNow(onResult: ((LocationMonitorManager.CheckResult) -> Unit)? = null): Boolean {
        val manager = locationMonitorManager ?: return false
        manager.triggerCheckNow(onResult)
        return true
    }
    
    /**
     * 设置人工客服WebSocket实例（供ChatFragment在重新连接时使用）
     */
    fun setCustomerServiceWebSocket(webSocket: CustomerServiceWebSocket?) {
        // 注意：不要立即释放旧的连接，因为可能还有未处理的消息
        // 只有当ChatFragment创建了新的WebSocket时才替换
        val oldWebSocket = customerServiceWebSocket
        if (webSocket != null && webSocket != oldWebSocket) {
            Log.d(TAG, "ChatFragment创建了新的WebSocket，替换MainActivity的WebSocket")
            // 先设置新的WebSocket，确保全局监听器立即生效
            customerServiceWebSocket = webSocket
            
            // 延迟释放旧的WebSocket，给旧连接一些时间处理未完成的消息
            if (oldWebSocket != null) {
                CoroutineScope(Dispatchers.IO + coroutineExceptionHandler).launch {
                    delay(2000) // 等待2秒，让旧连接处理完未完成的消息
                    // 再次检查，确保旧连接确实已经被替换
                    if (customerServiceWebSocket != oldWebSocket) {
                        Log.d(TAG, "释放旧的WebSocket连接")
                        oldWebSocket.release()
                    }
                }
            }
        } else {
            customerServiceWebSocket = webSocket
        }
        
        // 重要：当WebSocket被替换时，重新设置MainActivity的全局消息监听器
        // 这确保无论何时收到消息，都能正确处理弹窗和未读计数
        ensureCustomerServiceWebSocketListener()
    }
    
    /**
     * 确保WebSocket的全局消息监听器已设置
     * 这个方法应该在onResume和setCustomerServiceWebSocket时调用
     */
    private fun ensureCustomerServiceWebSocketListener() {
        val webSocket = customerServiceWebSocket
        if (webSocket != null) {
            // 创建一个持久的监听器引用，避免被覆盖
            // 注意：这个监听器只处理人工客服消息（service_message），好友消息（friend_message）不应该触发此监听器
            val listener: (String, String, Int) -> Unit = { content, sender, count ->
                Log.d(TAG, "========== globalMessageListener被触发 ==========")
                Log.d(TAG, "线程: ${Thread.currentThread().name}")
                Log.d(TAG, "参数检查: content长度=${content.length}, sender=$sender, count=$count")
                Log.d(TAG, "content前50字符: ${content.take(50)}")
                
                // 使用try-catch捕获所有异常
                try {
                    Log.d(TAG, "准备调用handleCustomerServiceMessage...")
                    // 注意：只有人工客服消息才会触发此监听器，好友消息不会触发
                    handleCustomerServiceMessage(content, sender, count)
                    Log.d(TAG, "handleCustomerServiceMessage调用完成")
                } catch (e: Exception) {
                    Log.e(TAG, "handleCustomerServiceMessage调用异常: ${e.message}", e)
                    e.printStackTrace()
                } catch (e: Throwable) {
                    Log.e(TAG, "handleCustomerServiceMessage调用严重错误: ${e.message}", e)
                    e.printStackTrace()
                }
                
                Log.d(TAG, "========== globalMessageListener处理完成 ==========")
            }
            
            // 设置全局消息监听器（用于人工客服消息）
            webSocket.setGlobalMessageListener(listener)
            Log.d(TAG, "已确保WebSocket的globalMessageListener已设置，连接状态: ${webSocket.isConnected()}")
            
            // 重要：也设置onMessageReceived回调，用于处理好友消息
            // 这样即使不在ChatFragment页面，好友消息也能被接收和保存
            // 注意：如果ChatFragment已经设置了回调，这里设置的回调会覆盖它
            // 但ChatFragment的回调会在onResume时重新设置，所以不会有问题
            webSocket.setOnMessageReceived { messageText, count ->
                Log.d(TAG, "========== MainActivity收到WebSocket消息回调 ==========")
                Log.d(TAG, "消息长度: ${messageText.length}, 数量: $count")
                Log.d(TAG, "消息内容（前200字符）: ${messageText.take(200)}")
                try {
                    val json = org.json.JSONObject(messageText)
                    val type = json.getString("type")
                    Log.d(TAG, "消息类型: $type")
                    
                    when (type) {
                        "service_message" -> {
                            // 收到人工客服消息（service_message）
                            // 如果在人工客服对话页面，需要让ChatFragment处理消息
                            // 因为MainActivity的回调覆盖了ChatFragment的回调，所以需要手动转发给ChatFragment
                            val isInCustomerServiceChat = isInCustomerServiceChat()
                            Log.d(TAG, "MainActivity收到service_message，当前是否在人工客服对话页面: $isInCustomerServiceChat")
                            
                            if (isInCustomerServiceChat) {
                                // 在人工客服对话页面，转发给ChatFragment处理
                                Log.d(TAG, "MainActivity: 在人工客服对话页面，转发service_message给ChatFragment处理")
                                runOnUiThread {
                                    val fragmentManager = supportFragmentManager
                                    val chatFragment = fragmentManager.fragments.find { it is ChatFragment } as? ChatFragment
                                    chatFragment?.let {
                                        Log.d(TAG, "MainActivity: 找到ChatFragment，手动处理service_message")
                                        it.handleServiceMessageDirectly(messageText, count)
                                    } ?: run {
                                        Log.w(TAG, "MainActivity: 未找到ChatFragment，无法处理service_message")
                                    }
                                }
                                return@setOnMessageReceived
                            }
                            // 不在人工客服对话页面，消息会由globalMessageListener处理（显示弹窗等）
                            Log.d(TAG, "MainActivity: 不在人工客服对话页面，service_message由globalMessageListener处理")
                        }
                        "friend_message" -> {
                            // 收到好友消息
                            val content = json.getString("content")
                            val senderImei = json.optString("senderImei", "")
                            val messageType = json.optString("message_type", "text")
                            val imageBase64 = json.optString("imageBase64", null)
                            // 提取skillId（如果是技能分享消息）
                            val skillId = json.optString("skillId", null)
                            // 解析timestamp：可能是ISO字符串或Long数字
                            val timestamp = parseTimestamp(json, "timestamp")
                            Log.d(TAG, "MainActivity收到好友消息: senderImei=$senderImei, content=$content, messageType=$messageType, hasImage=${imageBase64 != null}, skillId=$skillId, timestamp=$timestamp")
                            
                            val hadFriendLocal = FriendManager.getFriend(this@MainActivity, senderImei) != null
                            val (nickHint, avatarHint) = FriendManager.parseFriendIdentityHintsFromMessageJson(json)
                            FriendManager.ensureFriendForIncomingMessage(this@MainActivity, senderImei, nickHint, avatarHint)
                            FriendManager.enqueueEnrichFriendFromProfile(this@MainActivity, senderImei, testScope)
                            if (!hadFriendLocal) {
                                testScope.launch {
                                    try {
                                        FriendManager.syncFriendsFromServer(this@MainActivity)
                                    } catch (_: Exception) {
                                    }
                                    runOnUiThread { refreshConversationListPublic() }
                                }
                            }
                            // 获取好友信息（ensure 后会话列表与弹窗可展示昵称/头像）
                            val friend = FriendManager.getFriend(this@MainActivity, senderImei)
                            val senderName = friend?.nickname ?: senderImei.take(8) + "..."
                            val friendConversationId = "friend_$senderImei"
                            
                            // 检查是否在当前好友对话页面
                            val isInFriendChat = currentConversationId == friendConversationId
                            Log.d(TAG, "MainActivity: 当前对话ID=$currentConversationId, 好友对话ID=$friendConversationId, 是否在当前好友对话页面=$isInFriendChat")
                            
                            if (isInFriendChat) {
                                // 在当前好友对话页面，需要让ChatFragment处理消息
                                // 因为MainActivity的回调覆盖了ChatFragment的回调，所以需要手动转发给ChatFragment
                                Log.d(TAG, "MainActivity: 在当前好友对话页面，转发消息给ChatFragment处理")
                                // 立即查找ChatFragment并手动触发消息处理
                                runOnUiThread {
                                    val fragmentManager = supportFragmentManager
                                    val chatFragment = fragmentManager.fragments.find { it is ChatFragment } as? ChatFragment
                                    chatFragment?.let {
                                        Log.d(TAG, "MainActivity: 找到ChatFragment，手动处理好友消息")
                                        it.handleFriendMessageDirectly(messageText, count)
                                    } ?: run {
                                        Log.w(TAG, "MainActivity: 未找到ChatFragment，无法手动处理好友消息")
                                    }
                                }
                                return@setOnMessageReceived
                            }
                            
                            // 不在当前好友对话页面，保存消息到该好友的对话记录中并显示弹窗
                            // 如果有图片，需要先保存图片
                            if (imageBase64 != null && messageType == "image") {
                                // 异步保存图片
                                testScope.launch {
                                    try {
                                        var didAppendToPrefs = false
                                        val imagePath = saveImageFromBase64(imageBase64)
                                        if (imagePath != null) {
                                            // 图片保存成功，保存消息记录（统一走 FriendChatMessagePrefsStore，与 ViewModel 合并写入）
                                            try {
                                                val imageFile = java.io.File(imagePath)
                                                val messageText = if (content.isNotEmpty()) {
                                                    "$content\n[图片：${imageFile.name}]"
                                                } else {
                                                    "[图片：${imageFile.name}]"
                                                }
                                                val appended = FriendChatMessagePrefsStore.appendMessage(
                                                    this@MainActivity,
                                                    friendConversationId,
                                                    org.json.JSONObject().apply {
                                                        put("sender", senderName)
                                                        put("message", messageText)
                                                        put("type", "image")
                                                        put("timestamp", timestamp)
                                                        put("uuid", java.util.UUID.randomUUID().toString())
                                                        put("imagePath", imagePath)
                                                        put("senderImei", senderImei)
                                                    }
                                                )
                                                didAppendToPrefs = appended
                                                Log.d(TAG, "MainActivity: 好友图片消息已保存，对话ID: $friendConversationId, 图片路径: $imagePath, appended=$appended")
                                                if (appended) {
                                                    updateFriendConversationLastMessage(friendConversationId, messageText)
                                                }
                                            } catch (e: Exception) {
                                                Log.e(TAG, "MainActivity: 保存好友图片消息失败: ${e.message}", e)
                                                e.printStackTrace()
                                            }
                                        } else {
                                            Log.e(TAG, "MainActivity: 图片保存失败，仅保存文本消息")
                                            try {
                                                didAppendToPrefs = FriendChatMessagePrefsStore.appendMessage(
                                                    this@MainActivity,
                                                    friendConversationId,
                                                    org.json.JSONObject().apply {
                                                        put("sender", senderName)
                                                        put("message", if (content.isNotEmpty()) content else "[图片]")
                                                        put("type", "text")
                                                        put("timestamp", timestamp)
                                                        put("uuid", java.util.UUID.randomUUID().toString())
                                                        if (senderImei.isNotEmpty()) put("senderImei", senderImei)
                                                    }
                                                )
                                            } catch (e: Exception) {
                                                Log.e(TAG, "MainActivity: 保存好友消息失败: ${e.message}", e)
                                            }
                                        }
                                        
                                        val lastMessageForList = if (imagePath != null) {
                                            val imageFile = java.io.File(imagePath)
                                            if (content.isNotEmpty()) {
                                                "$content\n[图片：${imageFile.name}]"
                                            } else {
                                                "[图片：${imageFile.name}]"
                                            }
                                        } else {
                                            if (content.isNotEmpty()) content else "[图片]"
                                        }
                                        if (didAppendToPrefs) {
                                            handleFriendMessage(senderImei, senderName, content, friendConversationId, lastMessageForList)
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "MainActivity: 处理好友图片消息异常: ${e.message}", e)
                                        e.printStackTrace()
                                        // 即使图片保存失败，也处理消息（显示弹窗等）
                                        handleFriendMessage(senderImei, senderName, content, friendConversationId)
                                    }
                                }
                            } else {
                                // 没有图片：WebSocket 在 OkHttp 后台线程回调，此处必须切主线程更新列表/UI，且 chat_messages 用 commit 避免与 Fragment 读 prefs 竞态
                                runOnUiThread {
                                    try {
                                        val appended = FriendChatMessagePrefsStore.appendMessage(
                                            this@MainActivity,
                                            friendConversationId,
                                            org.json.JSONObject().apply {
                                                put("sender", senderName)
                                                put("message", content)
                                                put("type", "text")
                                                put("timestamp", timestamp)
                                                put("uuid", java.util.UUID.randomUUID().toString())
                                                if (senderImei.isNotEmpty()) put("senderImei", senderImei)
                                                if (skillId != null && skillId.isNotEmpty()) put("skillId", skillId)
                                            }
                                        )
                                        Log.d(TAG, "MainActivity: 好友消息已保存到对话记录，对话ID: $friendConversationId, appended=$appended")
                                        if (appended) {
                                            handleFriendMessage(senderImei, senderName, content, friendConversationId, null)
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "MainActivity: 保存好友消息失败: ${e.message}", e)
                                        e.printStackTrace()
                                    }
                                }
                            }
                        }
                        "friend_sync_message" -> {
                            Log.d(TAG, "MainActivity收到 friend_sync_message（PC/多设备好友会话镜像）")
                            runOnUiThread {
                                val convId = json.optString("conversation_id", "").trim()
                                val openConv = getCurrentConversationId()
                                val chatFragment = getChatFragment(convId)
                                val fragmentConvId = (chatFragment?.arguments?.getSerializable("conversation") as? Conversation)?.id
                                val viewing =
                                    openConv == convId &&
                                        chatFragment != null &&
                                        chatFragment.isAdded &&
                                        fragmentConvId == convId
                                Log.d(
                                    TAG,
                                    "friend_sync_message 分发: openConv=$openConv, payloadConv=$convId, fragmentConv=$fragmentConvId, viewing=$viewing"
                                )
                                if (viewing) {
                                    ChatCustomerServiceWebSocketHandler.processFriendSyncMessage(
                                        chatFragment!!,
                                        json
                                    )
                                } else {
                                    ChatCustomerServiceWebSocketHandler.persistFriendSyncOffline(
                                        this@MainActivity,
                                        json
                                    )
                                }
                            }
                            return@setOnMessageReceived
                        }
                        "cross_device_message" -> {
                            // 收到端云互发消息（PC 端发送到手机）
                            val isInMeChat = currentConversationId == ConversationListFragment.CONVERSATION_ID_ME
                            Log.d(TAG, "MainActivity收到cross_device_message，当前是否在「我的电脑」对话页面: $isInMeChat")
                            if (isInMeChat) {
                                runOnUiThread {
                                    val fragmentManager = supportFragmentManager
                                    val chatFragment = fragmentManager.fragments.find { it is ChatFragment } as? ChatFragment
                                    chatFragment?.let {
                                        Log.d(TAG, "MainActivity: 找到ChatFragment，手动处理端云互发消息")
                                        it.handleCrossDeviceMessageDirectly(messageText, count)
                                    } ?: run {
                                        Log.w(TAG, "MainActivity: 未找到ChatFragment，无法处理cross_device_message")
                                    }
                                }
                                return@setOnMessageReceived
                            }
                            // 不在「我的电脑」页面，保存消息并更新对话列表缩略图 + 未读（支持图片落盘）
                            try {
                                val content = json.optString("content", "")
                                val sender = json.optString("sender", "我的电脑")
                                val timestamp = parseTimestamp(json, "timestamp")
                                if (json.optString("message_type") == "file") {
                                    val displayContent = "[文件] ${json.optString("file_name", json.optString("fileName", "文件"))}"
                                    CrossDeviceMeMessageStore.appendMessage(this@MainActivity, sender, displayContent, timestamp, null)
                                    Log.d(TAG, "MainActivity: 端云文件消息已保存（不在「我的电脑」页）")
                                    notifyInboundSessionMessage(ConversationListFragment.CONVERSATION_ID_ME, displayContent, incrementUnread = true)
                                } else {
                                    val imageB64 = ChatWebSocketJsonUtils.extractCrossDeviceImageBase64(json)
                                    if (imageB64 != null) {
                                        CoroutineScope(Dispatchers.IO + coroutineExceptionHandler).launch {
                                            val path = ChatImageUtils.saveImageFromBase64(this@MainActivity, imageB64)
                                            withContext(Dispatchers.Main) {
                                                CrossDeviceMeMessageStore.appendMessage(this@MainActivity, sender, content, timestamp, path)
                                                Log.d(TAG, "MainActivity: 端云图片消息已保存（不在「我的电脑」页）")
                                                val preview = content.ifEmpty { "[图片]" }
                                                notifyInboundSessionMessage(ConversationListFragment.CONVERSATION_ID_ME, preview, incrementUnread = true)
                                            }
                                        }
                                    } else {
                                        CrossDeviceMeMessageStore.appendMessage(this@MainActivity, sender, content, timestamp, null)
                                        Log.d(TAG, "MainActivity: 端云文本消息已保存，当前不在「我的电脑」页面")
                                        notifyInboundSessionMessage(ConversationListFragment.CONVERSATION_ID_ME, content, incrementUnread = true)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "MainActivity: 保存端云消息失败: ${e.message}", e)
                            }
                        }
                        "pc_execute_command" -> {
                            Log.d(TAG, "MainActivity收到pc_execute_command，按批测流程处理")
                            try {
                                val query = json.optString("query", "")
                                val execUuid = json.optString("uuid", "")
                                val messageId = json.optString("message_id", java.util.UUID.randomUUID().toString())
                                val stepsArr = json.optJSONArray("steps")
                                val steps = if (stepsArr != null) {
                                    (0 until stepsArr.length()).map { stepsArr.getString(it) ?: "" }.filter { it.isNotBlank() }
                                } else null
                                val conversationId = json.optString("conversation_id", "").takeIf { it.isNotBlank() }
                                val assistantBaseUrlRaw = json.optString("assistant_base_url", "").takeIf { it.isNotBlank() }
                                val assistantBaseUrl = assistantBaseUrlRaw
                                    ?: conversationId
                                        ?.takeIf { CustomAssistantManager.isCustomAssistantId(it) }
                                        ?.let { CustomAssistantManager.getById(this@MainActivity, it)?.baseUrl }
                                        ?.takeIf { it.isNotBlank() }
                                val chatSummary = json.optString("chat_summary", "").takeIf { it.isNotBlank() }
                                if (query.isBlank()) {
                                    Log.w(TAG, "pc_execute_command: query为空，忽略")
                                    return@setOnMessageReceived
                                }
                                if (assistantBaseUrlRaw == null &&
                                    conversationId != null &&
                                    CustomAssistantManager.isCustomAssistantId(conversationId)
                                ) {
                                    Log.d(
                                        TAG,
                                        "pc_execute_command: assistant_base_url 缺失，已按 conversation_id=$conversationId 本地补全为自定义小助手 baseUrl"
                                    )
                                }
                                pendingPcExecuteCommand = PendingPcExecute(query, execUuid, messageId, steps, assistantBaseUrl, conversationId, chatSummary)
                                testScope.launch {
                                    val pending = pendingPcExecuteCommand
                                    kotlinx.coroutines.withContext(Dispatchers.Main) {
                                        if (!switchToChatPageForPcExecute(pending)) {
                                            Log.e(TAG, "pc_execute: switchToChatPageForPcExecute失败")
                                            pendingPcExecuteCommand = null
                                            return@withContext
                                        }
                                    }
                                    var retryCount = 0
                                    val maxRetries = 20
                                    while (retryCount < maxRetries && pendingPcExecuteCommand != null) {
                                        kotlinx.coroutines.delay(500)
                                        retryCount++
                                        val pendingForLookup = withContext(Dispatchers.Main) { pendingPcExecuteCommand }
                                        val targetConvId = when {
                                            pendingForLookup?.assistantBaseUrl != null -> pendingForLookup.conversationId
                                            pendingForLookup?.conversationId != null && pendingForLookup.conversationId.startsWith("group_") -> pendingForLookup.conversationId
                                            else -> null
                                        }
                                        val cf = withContext(Dispatchers.Main) { getChatFragment(targetConvId) }
                                        if (cf != null && cf.isAdded && cf.view != null) {
                                            val pending = withContext(Dispatchers.Main) {
                                                val p = pendingPcExecuteCommand
                                                pendingPcExecuteCommand = null
                                                p
                                            }
                                            if (pending != null) {
                                                Log.d(TAG, "pc_execute: ChatFragment就绪，调用executePcExecuteCommand")
                                                withContext(Dispatchers.Main) {
                                                    if (pending.assistantBaseUrl != null) {
                                                        cf.executePcExecuteCommandForCustomAssistant(
                                                            pending.assistantBaseUrl,
                                                            pending.query,
                                                            pending.chatSummary,
                                                            pending.uuid,
                                                            pending.messageId,
                                                            pending.conversationId
                                                        )
                                                    } else {
                                                        cf.executePcExecuteCommand(pending.query, pending.uuid, pending.messageId, pending.steps, pending.conversationId)
                                                    }
                                                }
                                            }
                                            return@launch
                                        }
                                        if (retryCount % 2 == 0) {
                                            Log.d(TAG, "pc_execute: 等待ChatFragment... ($retryCount/$maxRetries)")
                                        }
                                    }
                                    Log.e(TAG, "pc_execute: ChatFragment超时未就绪，放弃执行")
                                    withContext(Dispatchers.Main) { pendingPcExecuteCommand = null }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "pc_execute_command异常: ${e.message}", e)
                                pendingPcExecuteCommand = null
                            }
                        }
                        "mobile_execute_pc_thinking" -> {
                            val thinking = json.optString("thinking_content", "").trim()
                            if (thinking.isBlank()) return@setOnMessageReceived
                            val convIdRaw = json.optString("conversation_id", "").trim()
                            val (targetConvId, _) = if (convIdRaw.contains("__")) {
                                val parts = convIdRaw.split("__", limit = 2)
                                parts[0] to parts.getOrNull(1)
                            } else {
                                convIdRaw to null
                            }
                            val convId = targetConvId.takeIf { it.isNotBlank() }
                            val isInTargetConv = convId == null || convId == currentConversationId
                            val targetChatFragment = getChatFragment(convId)
                            val canDispatchToChat = isInTargetConv &&
                                targetChatFragment?.canHandleRealtimeAssistantSync() == true
                            if (canDispatchToChat && targetChatFragment != null) {
                                runOnUiThread {
                                    targetChatFragment.handleAssistantThinkingSyncDirectly(messageText)
                                }
                            }
                        }
                        "assistant_thinking_sync" -> {
                            val thinking = json.optString("thinking_content", "").trim()
                            if (thinking.isBlank()) return@setOnMessageReceived
                            val convIdRaw = json.optString("conversation_id", "").trim()
                            val (targetConvId, _) = if (convIdRaw.contains("__")) {
                                val parts = convIdRaw.split("__", limit = 2)
                                parts[0] to parts.getOrNull(1)
                            } else {
                                convIdRaw to null
                            }
                            val convId = targetConvId.takeIf { it.isNotBlank() }
                            val isInTargetConv = convId == null || convId == currentConversationId
                            val targetChatFragment = getChatFragment(convId)
                            val canDispatchToChat = isInTargetConv &&
                                targetChatFragment?.canHandleRealtimeAssistantSync() == true
                            if (canDispatchToChat && targetChatFragment != null) {
                                runOnUiThread {
                                    targetChatFragment.handleAssistantThinkingSyncDirectly(messageText)
                                }
                            }
                        }
                        "mobile_execute_pc_result" -> {
                            Log.d(TAG, "MainActivity收到mobile_execute_pc_result，PC 执行完成，转发给 ChatFragment")
                            val convIdRaw = json.optString("conversation_id", "").trim()
                            val (targetConvId, _) = if (convIdRaw.contains("__")) {
                                val parts = convIdRaw.split("__", limit = 2)
                                parts[0] to parts.getOrNull(1)
                            } else {
                                convIdRaw to null
                            }
                            val convId = targetConvId.takeIf { it.isNotBlank() }
                            val isInTargetConv = convId == null || convId == currentConversationId
                            val targetChatFragment = getChatFragment(convId)
                            val canDispatchToChat = isInTargetConv &&
                                targetChatFragment?.canHandleRealtimeAssistantSync() == true
                            if (canDispatchToChat && targetChatFragment != null) {
                                runOnUiThread {
                                    targetChatFragment.handleMobileExecutePcResultDirectly(messageText)
                                }
                            } else {
                                Log.d(TAG, "MainActivity: 当前不在目标对话($convId)或ChatFragment未就绪，改为本地持久化")
                                handleMobileExecutePcResultPersistOnly(messageText)
                                // 即使不在会话页，也要同步结束任务状态，避免回到会话后仍显示运行中
                                runOnUiThread {
                                    targetChatFragment?.stopTask(delaySummary = true)
                                    if (targetChatFragment == null) {
                                        isTaskRunning = false
                                        TaskIndicatorOverlayManager.updateOverlayForTaskStatus(false)
                                        if (!TaskIndicatorOverlayManager.isCompanionModeEnabled(this@MainActivity)) {
                                            TaskIndicatorOverlayManager.hide()
                                        }
                                        Log.d(TAG, "clearGlobalTaskIndicator: reason=mobile_execute_pc_result_no_fragment")
                                    }
                                }
                            }

                            // 悬浮球场景：无论是否在会话页，都弹结果窗反馈
                            if (isTaskFromCompanionMode) {
                                val success = json.optBoolean("success", false)
                                val content = json.optString("content", "")
                                val error = json.optString("error", "")
                                val resultText = if (success) {
                                    content.takeIf { it.isNotBlank() } ?: "任务已完成"
                                } else {
                                    "执行失败：${error.takeIf { it.isNotBlank() } ?: "未知错误"}"
                                }
                                runOnUiThread {
                                    TaskResultOverlayManager.show(
                                        context = this@MainActivity,
                                        resultText = resultText
                                    )
                                }
                            }
                            // 兜底清理全局任务状态，避免后续页面沿用“运行中”
                            runOnUiThread {
                                isTaskRunning = false
                                TaskIndicatorOverlayManager.updateOverlayForTaskStatus(false)
                                if (!TaskIndicatorOverlayManager.isCompanionModeEnabled(this@MainActivity)) {
                                    TaskIndicatorOverlayManager.hide()
                                }
                                Log.d(TAG, "clearGlobalTaskIndicator: reason=mobile_execute_pc_result_done")
                            }
                        }
                        "assistant_stop_task" -> {
                            Log.d(TAG, "收到对端 assistant_stop_task，停止当前任务")
                            runOnUiThread {
                                val cf = supportFragmentManager.fragments.find { it is ChatFragment } as? ChatFragment
                                if (cf != null) {
                                    cf.handleRemoteStopTask()
                                } else {
                                    Log.w(TAG, "MainActivity: 未找到 ChatFragment，无法处理 assistant_stop_task")
                                    isTaskRunning = false
                                    TaskIndicatorOverlayManager.updateOverlayForTaskStatus(false)
                                    if (!TaskIndicatorOverlayManager.isCompanionModeEnabled(this@MainActivity)) {
                                        TaskIndicatorOverlayManager.hide()
                                    }
                                    Log.d(TAG, "clearGlobalTaskIndicator: reason=assistant_stop_task_no_fragment")
                                }
                            }
                        }
                        "gui_execute_request" -> {
                            val requestId = json.optString("request_id", "").trim()
                            val query = json.optString("query", "").trim()
                            val threadId = json.optString("thread_id", "").trim()
                            val chatSummary = json.optString("chat_summary", "").takeIf { it.isNotBlank() }
                            if (requestId.isBlank() || query.isBlank()) {
                                Log.w(TAG, "gui_execute_request 缺少 request_id 或 query，忽略")
                                return@setOnMessageReceived
                            }
                            val imei = ProfileManager.getOrGenerateImei(this@MainActivity)
                            val conversationId = when {
                                threadId == Uuid5Util.chatAssistantThreadId(imei) -> ConversationListFragment.CONVERSATION_ID_CHAT_ASSISTANT
                                else -> {
                                    val found = CustomAssistantManager.getAll(this@MainActivity).find { "${imei}_${it.id}" == threadId }
                                    found?.id ?: threadId.removePrefix("${imei}_")
                                        .takeIf { it.isNotBlank() && it.startsWith(CustomAssistantManager.PREFIX_ID) }
                                        ?: run {
                                            CustomAssistantManager.getAll(this@MainActivity)
                                                .filter { it.multiSession }
                                                .firstOrNull { assistant ->
                                                    SessionStorage.loadSessions(this@MainActivity, assistant.id).any { it.id == threadId }
                                                }
                                                ?.id
                                        }
                                }
                            }
                            val resolvedBaseUrl = (
                                conversationId
                                    ?.takeIf { CustomAssistantManager.isCustomAssistantId(it) }
                                    ?.let { CustomAssistantManager.getById(this@MainActivity, it)?.baseUrl }
                                )?.takeIf { it.isNotBlank() }
                                ?: (
                                    getSharedPreferences("app_prefs", MODE_PRIVATE)
                                        .getString("chat_server_url", ChatConstants.DEFAULT_CHAT_SERVER_URL)
                                        ?: ChatConstants.DEFAULT_CHAT_SERVER_URL
                                )

                            runOnUiThread {
                                val sendResult: (String?, String?) -> Unit = { content, err ->
                                    webSocket.sendGuiExecuteResult(
                                        requestId = requestId,
                                        success = err == null,
                                        content = content,
                                        error = err,
                                        threadId = threadId.takeIf { it.isNotBlank() },
                                    )
                                }

                                var chatFragment = getChatFragment(conversationId)
                                if (chatFragment == null) {
                                    val fallbackConversation = if (conversationId != null && CustomAssistantManager.isCustomAssistantId(conversationId)) {
                                        val assistant = CustomAssistantManager.getById(this@MainActivity, conversationId)
                                        Conversation(
                                            id = conversationId,
                                            name = assistant?.name ?: "小助手",
                                            avatar = assistant?.avatar,
                                            lastMessage = null,
                                            lastMessageTime = System.currentTimeMillis()
                                        )
                                    } else {
                                        Conversation(
                                            id = ConversationListFragment.CONVERSATION_ID_ASSISTANT,
                                            name = ChatConstants.ASSISTANT_DISPLAY_NAME,
                                            avatar = null,
                                            lastMessage = null,
                                            lastMessageTime = System.currentTimeMillis()
                                        )
                                    }
                                    try {
                                        ensureChatPageThenSwitchTo(fallbackConversation)
                                        supportFragmentManager.executePendingTransactions()
                                        chatFragment = getChatFragment(fallbackConversation.id)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "gui_execute_request: 切换对话失败", e)
                                    }
                                }

                                if (chatFragment != null) {
                                    chatFragment.handleGuiExecuteRequest(
                                        resolvedBaseUrl,
                                        threadId.ifBlank { conversationId ?: "" },
                                        requestId,
                                        query,
                                        chatSummary,
                                        sendResult,
                                    )
                                } else {
                                    Log.w(TAG, "gui_execute_request: 未找到 ChatFragment, conversationId=$conversationId")
                                    sendResult(null, "未找到 ChatFragment")
                                }
                            }
                        }
                        "mobile_tool_invoke", "mobile_tool_cancel" -> {
                            val messageTypeRaw = type
                            runOnUiThread {
                                val requestId = json.optString("request_id", "").trim()
                                val protocol = json.optString("protocol", "mobile_tool/v1").trim().ifBlank { "mobile_tool/v1" }
                                val conversationId = json.optString("conversation_id", ConversationListFragment.CONVERSATION_ID_ASSISTANT)
                                val payload = json.optJSONObject("payload")
                                val tool = payload?.optString("tool", "")?.trim() ?: ""
                                if (messageTypeRaw == "mobile_tool_invoke" && tool == "device.gui_task_probe") {
                                    // 状态检测走静默回包，避免自动切换到「自动执行小助手」页面
                                    if (requestId.isBlank()) {
                                        Log.w(TAG, "gui_task_probe 缺少 request_id，忽略")
                                        return@runOnUiThread
                                    }
                                    sendGuiTaskProbeResponseDirect(
                                        webSocket = webSocket,
                                        requestId = requestId,
                                        protocol = protocol,
                                        conversationId = conversationId,
                                        ok = true,
                                        error = null
                                    )
                                    return@runOnUiThread
                                }
                                val targetConversationId = "custom_topoclaw"
                                var chatFragment = getChatFragment(targetConversationId)
                                if (chatFragment == null) {
                                    try {
                                        // mobile_tool 请求统一固定切到 TopoClaw（custom_topoclaw）对话处理。
                                        val assistant = CustomAssistantManager.getById(this@MainActivity, targetConversationId)
                                        val conv = Conversation(
                                            id = targetConversationId,
                                            name = assistant?.name ?: "TopoClaw",
                                            avatar = assistant?.avatar,
                                            lastMessage = null,
                                            lastMessageTime = System.currentTimeMillis()
                                        )
                                        ensureChatPageThenSwitchTo(conv)
                                        supportFragmentManager.executePendingTransactions()
                                        chatFragment = getChatFragment(targetConversationId)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "mobile_tool 路由时切换到 TopoClaw(ChatFragment) 失败", e)
                                    }
                                }

                                if (chatFragment != null) {
                                    try {
                                        if (messageTypeRaw == "mobile_tool_invoke") {
                                            chatFragment?.handleMobileToolInvoke(json)
                                        } else {
                                            chatFragment?.handleMobileToolCancel(json)
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "MainActivity 处理 $messageTypeRaw 失败", e)
                                    }
                                } else {
                                    Log.w(TAG, "MainActivity: 未找到 ChatFragment，无法处理 $messageTypeRaw")
                                }
                            }
                        }
                        "group_message" -> {
                            // 收到群组消息
                            val content = json.getString("content")
                            val groupId = json.optString("groupId", "")
                            val senderImei = json.optString("senderImei", "")
                            val sender = json.optString("sender", "群成员")
                            val messageType = json.optString("message_type", "text")
                            val messageId = json.optString("message_id", "")
                            val imageBase64 = json.optString("imageBase64", null)
                            val isAssistantReply = json.optBoolean("is_assistant_reply", false)
                            val timestamp = parseTimestamp(json, "timestamp")
                            Log.d(TAG, "========== MainActivity收到群组消息 ==========")
                            Log.d(TAG, "MainActivity收到群组消息: groupId=$groupId, senderImei=$senderImei, sender=$sender, messageType=$messageType, messageId=$messageId, hasImage=${imageBase64 != null}, isAssistantReply=$isAssistantReply")
                            Log.d(TAG, "消息内容（前100字符）: ${content.take(100)}")
                            Log.d(TAG, "当前对话ID: $currentConversationId")
                            
                            val groupConversationId = "group_$groupId"
                            
                            // 检查是否在当前群组对话页面，或者当前在assistant页面（可能是@小助手的情况）
                            val isInGroupChat = currentConversationId == groupConversationId || 
                                               currentConversationId == ConversationListFragment.CONVERSATION_ID_GROUP ||
                                               currentConversationId == ConversationListFragment.CONVERSATION_ID_ASSISTANT
                            Log.d(TAG, "MainActivity: 当前对话ID=$currentConversationId, 群组对话ID=$groupConversationId, 是否在当前群组对话页面或assistant页面=$isInGroupChat, isAssistantReply=$isAssistantReply")
                            
                            if (isInGroupChat) {
                                // 在当前群组对话页面或assistant页面，优先分发给“当前可见/会话匹配”的 ChatFragment
                                val activeChatFragment = getActiveFragment() as? ChatFragment
                                val activeConvId = (activeChatFragment?.arguments?.getSerializable("conversation") as? Conversation)?.id
                                val exactGroupFragment = getChatFragment(groupConversationId)
                                val exactGroupConvId = (exactGroupFragment?.arguments?.getSerializable("conversation") as? Conversation)?.id
                                val targetChatFragment = when {
                                    activeChatFragment != null && (
                                        activeConvId == groupConversationId ||
                                            activeConvId == ConversationListFragment.CONVERSATION_ID_GROUP ||
                                            activeConvId == ConversationListFragment.CONVERSATION_ID_ASSISTANT
                                        ) -> activeChatFragment
                                    exactGroupFragment != null && exactGroupConvId == groupConversationId -> exactGroupFragment
                                    else -> null
                                }
                                if (targetChatFragment != null) {
                                    Log.d(
                                        TAG,
                                        "MainActivity: 群组消息分发到ChatFragment, activeConv=$activeConvId, exactConv=$exactGroupConvId, targetConv=$groupConversationId"
                                    )
                                    runOnUiThread {
                                        targetChatFragment.handleGroupMessageDirectly(messageText, count)
                                    }
                                    Log.d(TAG, "MainActivity: 群组消息处理完成，返回")
                                    return@setOnMessageReceived
                                } else {
                                    Log.w(
                                        TAG,
                                        "MainActivity: 群组消息分发失败，未找到可用ChatFragment，降级走离屏持久化。activeConv=$activeConvId, exactConv=$exactGroupConvId, targetConv=$groupConversationId"
                                    )
                                }
                            }
                            
                            // 不在当前群组对话页面，保存消息到该群组的对话记录中
                            // 如果有图片，需要先保存图片
                            if (imageBase64 != null && messageType == "image") {
                                // 异步保存图片
                                testScope.launch {
                                    try {
                                        val imagePath = saveImageFromBase64(imageBase64)
                                        if (imagePath != null) {
                                            // 图片保存成功，保存消息记录
                                            try {
                                                val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                                
                                                // 读取该群组的现有消息
                                                val existingMessagesJson = prefs.getString("chat_messages_$groupConversationId", null)
                                                val messagesArray = if (existingMessagesJson != null) {
                                                    org.json.JSONArray(existingMessagesJson)
                                                } else {
                                                    org.json.JSONArray()
                                                }
                                                
                                                // 添加新消息（包含图片路径）
                                                // 确定发送者名称：如果是小助手显示小助手名称，否则从好友列表获取或使用IMEI前8位
                                                val senderName = when {
                                                    ChatConstants.isMainAssistantSender(sender) ->
                                                        DisplayNameHelper.getDisplayName(this@MainActivity, sender)
                                                    senderImei.isNotEmpty() -> {
                                                        val friend = FriendManager.getFriend(this@MainActivity, senderImei)
                                                        friend?.nickname ?: (senderImei.take(8) + "...")
                                                    }
                                                    else -> sender
                                                }
                                                val imageFile = java.io.File(imagePath)
                                                val messageText = if (content.isNotEmpty()) {
                                                    "$content\n[图片: ${imageFile.name}]"
                                                } else {
                                                    "[图片: ${imageFile.name}]"
                                                }
                                                
                                                messagesArray.put(org.json.JSONObject().apply {
                                                    put("sender", senderName)
                                                    put("message", messageText)
                                                    put("type", "image")
                                                    put("timestamp", timestamp)
                                                    put("uuid", java.util.UUID.randomUUID().toString())
                                                    put("imagePath", imagePath)
                                                    put("senderImei", senderImei)
                                                })
                                                
                                                // 保存消息
                                                prefs.edit().putString("chat_messages_$groupConversationId", messagesArray.toString()).apply()
                                                Log.d(TAG, "MainActivity: 群组图片消息已保存到对话记录，对话ID: $groupConversationId, 图片路径: $imagePath")
                                                
                                                // 更新对话列表的最后消息
                                                updateGroupConversationLastMessage(groupConversationId, messageText)
                                            } catch (e: Exception) {
                                                Log.e(TAG, "MainActivity: 保存群组图片消息失败: ${e.message}", e)
                                                e.printStackTrace()
                                            }
                                        } else {
                                            Log.e(TAG, "MainActivity: 图片保存失败，仅保存文本消息")
                                            // 图片保存失败，仅保存文本消息
                                            try {
                                                val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                                
                                                val existingMessagesJson = prefs.getString("chat_messages_$groupConversationId", null)
                                                val messagesArray = if (existingMessagesJson != null) {
                                                    org.json.JSONArray(existingMessagesJson)
                                                } else {
                                                    org.json.JSONArray()
                                                }
                                                
                                                val senderName = if (ChatConstants.isMainAssistantSender(sender)) {
                                                    DisplayNameHelper.getDisplayName(this@MainActivity, sender)
                                                } else {
                                                    senderImei.take(8) + "..."
                                                }
                                                messagesArray.put(org.json.JSONObject().apply {
                                                    put("sender", senderName)
                                                    put("message", if (content.isNotEmpty()) content else "[图片]")
                                                    put("type", "text")
                                                    put("timestamp", timestamp)
                                                    put("uuid", java.util.UUID.randomUUID().toString())
                                                })
                                                
                                                prefs.edit().putString("chat_messages_$groupConversationId", messagesArray.toString()).apply()
                                            } catch (e: Exception) {
                                                Log.e(TAG, "MainActivity: 保存群组消息失败: ${e.message}", e)
                                            }
                                        }
                                        
                                        // 处理群组消息提醒（更新对话列表、未读计数、应用内弹窗等）
                                        // 如果是图片消息且已保存成功，传入格式化后的消息文本，避免覆盖
                                        val lastMessageForList = if (imagePath != null) {
                                            val imageFile = java.io.File(imagePath)
                                            if (content.isNotEmpty()) {
                                                "$content\n[图片：${imageFile.name}]"
                                            } else {
                                                "[图片：${imageFile.name}]"
                                            }
                                        } else {
                                            // 图片保存失败，显示"[图片]"
                                            if (content.isNotEmpty()) content else "[图片]"
                                        }
                                        handleGroupMessage(groupId, sender, content, groupConversationId, lastMessageForList)
                                    } catch (e: Exception) {
                                        Log.e(TAG, "MainActivity: 处理群组图片消息异常: ${e.message}", e)
                                        e.printStackTrace()
                                        // 即使图片保存失败，也处理消息（显示弹窗等）
                                        handleGroupMessage(groupId, sender, content, groupConversationId, null)
                                    }
                                }
                            } else {
                                // 没有图片，按原来的逻辑处理
                                try {
                                    val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                    
                                    // 读取该群组的现有消息
                                    val existingMessagesJson = prefs.getString("chat_messages_$groupConversationId", null)
                                    val messagesArray = if (existingMessagesJson != null) {
                                        org.json.JSONArray(existingMessagesJson)
                                    } else {
                                        org.json.JSONArray()
                                    }
                                    
                                    // 添加新消息
                                    // 确定发送者名称：如果是小助手显示小助手名称，否则从好友列表获取或使用IMEI前8位
                                    val senderName = when {
                                        ChatConstants.isMainAssistantSender(sender) ->
                                            DisplayNameHelper.getDisplayName(this@MainActivity, sender)
                                        senderImei.isNotEmpty() -> {
                                            val friend = FriendManager.getFriend(this@MainActivity, senderImei)
                                            friend?.nickname ?: (senderImei.take(8) + "...")
                                        }
                                        else -> sender
                                    }
                                    messagesArray.put(org.json.JSONObject().apply {
                                        put("sender", senderName)
                                        put("message", content)
                                        put("type", "text")
                                        put("timestamp", timestamp)
                                        put("uuid", java.util.UUID.randomUUID().toString())
                                        // 保存senderImei，以便恢复时能重新解析昵称和头像
                                        if (senderImei.isNotEmpty()) {
                                            put("senderImei", senderImei)
                                        }
                                    })
                                    
                                    // 保存消息
                                    prefs.edit().putString("chat_messages_$groupConversationId", messagesArray.toString()).apply()
                                    Log.d(TAG, "MainActivity: 群组消息已保存到对话记录，对话ID: $groupConversationId")
                                } catch (e: Exception) {
                                    Log.e(TAG, "MainActivity: 保存群组消息失败: ${e.message}", e)
                                    e.printStackTrace()
                                }
                                
                                // 处理群组消息提醒（更新对话列表、未读计数、应用内弹窗等）
                                handleGroupMessage(groupId, sender, content, groupConversationId, null)
                            }
                        }
                        "remote_assistant_command" -> {
                            // 收到远程执行指令
                            val groupId = json.optString("groupId", "")
                            val targetImei = json.optString("targetImei", "")
                            val command = json.optString("command", "")
                            val senderImei = json.optString("senderImei", "")
                            Log.d(TAG, "MainActivity收到远程执行指令: groupId=$groupId, targetImei=$targetImei, command=$command, senderImei=$senderImei")
                            
                            // 验证是否是自己
                            val currentImei = ProfileManager.getOrGenerateImei(this@MainActivity)
                            if (targetImei != currentImei) {
                                Log.d(TAG, "MainActivity: 远程指令目标不是自己，忽略: targetImei=$targetImei, currentImei=$currentImei")
                                return@setOnMessageReceived
                            }
                            
                            Log.d(TAG, "MainActivity: 确认是发给自己的远程指令，转发给ChatFragment处理")
                            // 转发给ChatFragment处理
                            runOnUiThread {
                                val fragmentManager = supportFragmentManager
                                val chatFragment = fragmentManager.fragments.find { it is ChatFragment } as? ChatFragment
                                chatFragment?.let {
                                    Log.d(TAG, "MainActivity: 找到ChatFragment，手动处理远程执行指令")
                                    it.handleRemoteAssistantCommandDirectly(messageText, count)
                                } ?: run {
                                    Log.w(TAG, "MainActivity: 未找到ChatFragment，延迟处理远程执行指令")
                                    // 如果ChatFragment不存在，延迟一下再试（可能Fragment还在创建中）
                                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                        val fragment = supportFragmentManager.fragments.find { it is ChatFragment } as? ChatFragment
                                        fragment?.let {
                                            it.handleRemoteAssistantCommandDirectly(messageText, count)
                                        } ?: run {
                                            // 延迟后仍未找到ChatFragment，检查"默认允许好友控制手机"开关
                                            val allowFriendControlByDefault = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                                                .getBoolean("allow_friend_control_by_default", false)
                                            if (allowFriendControlByDefault) {
                                                // 开关已开启：切换到群组对话并直接执行，不显示弹窗
                                                Log.d(TAG, "MainActivity: 默认允许好友控制手机已开启，切换到群组并直接执行")
                                                val finalCommand = if (!ChatConstants.containsRemoteStyleAssistantMention(command)) {
                                                    "@${ChatConstants.ASSISTANT_DISPLAY_NAME} $command"
                                                } else {
                                                    command
                                                }
                                                val group = GroupManager.getGroup(this@MainActivity, groupId)
                                                val groupName = group?.name ?: "群组"
                                                val groupConversation = Conversation(
                                                    id = "group_$groupId",
                                                    name = groupName,
                                                    avatar = null,
                                                    lastMessage = command,
                                                    lastMessageTime = System.currentTimeMillis()
                                                )
                                                switchToChatFragment(groupConversation)
                                                binding.root.postDelayed({
                                                    val chatFrag = getChatFragment()
                                                    chatFrag?.executeRemoteCommandDirectly(groupId, finalCommand, senderImei)
                                                        ?: Log.e(TAG, "MainActivity: 切换后仍未找到ChatFragment")
                                                }, 500)
                                            } else {
                                                // 开关未开启：显示悬浮窗弹窗
                                                Log.d(TAG, "MainActivity: 延迟后仍未找到ChatFragment，显示悬浮窗权限确认弹窗")
                                                showRemoteControlPermissionOverlay(senderImei, groupId, command)
                                            }
                                        }
                                    }, 500)
                                }
                            }
                        }
                        "assistant_user_message", "assistant_sync_message" -> {
                            // PC 端小助手跨设备同步：添加/保存 + 更新对话列表缩略图 + 未读红点 + Toast
                            Log.d(TAG, "MainActivity: 收到 $type，处理小助手同步消息")
                            runOnUiThread {
                                handleAssistantSyncMessage(messageText, type)
                            }
                        }
                        "offline_messages" -> {
                            // 重连补发：须按子消息类型路由，不能仅靠 globalMessageListener（否则误记入人工客服）
                            Log.d(TAG, "MainActivity: 收到 offline_messages，转发 ChatFragment")
                            runOnUiThread {
                                dispatchOfflineMessagesToChatFragment(messageText, count)
                            }
                        }
                        else -> {
                            // 其他类型的消息（如service_message）由globalMessageListener处理
                            Log.d(TAG, "MainActivity: 收到其他类型消息: $type，由globalMessageListener处理")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "MainActivity: 处理WebSocket消息失败: ${e.message}", e)
                    e.printStackTrace()
                }
                Log.d(TAG, "=========================================")
            }
            Log.d(TAG, "已确保WebSocket的onMessageReceived回调已设置")
        } else {
            Log.w(TAG, "WebSocket为null，无法设置监听器")
        }
    }
    
    /**
     * 将 [offline_messages] 交给 [ChatFragment] 按子类型路由。
     * 使用 [getChatFragment]（含容器/缓存/递归查找），冷启动多档延迟重试；仍失败则持久化待 [consumePendingOfflineMessagesIfAny] 补处理。
     */
    private fun dispatchOfflineMessagesToChatFragment(messageText: String, count: Int) {
        applyOfflineFriendMessagesFromOfflineBundle(messageText)
        val tryDispatch: () -> Boolean = {
            val fragment = getChatFragment()
            if (fragment != null && fragment.isAdded) {
                fragment.handleOfflineMessagesBundle(messageText, count)
                true
            } else {
                false
            }
        }
        if (tryDispatch()) {
            refreshConversationList()
            return
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val delays = longArrayOf(200, 400, 800, 1500, 2500)
        var attempt = 0
        fun scheduleNext() {
            if (attempt >= delays.size) {
                getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE).edit()
                    .putString(PREF_KEY_PENDING_OFFLINE_MESSAGES, messageText)
                    .apply()
                Log.w(TAG, "offline_messages 暂存至 prefs（ChatFragment 未就绪），稍后 consumePendingOfflineMessagesIfAny 补处理")
                return
            }
            val d = delays[attempt++]
            handler.postDelayed({
                if (tryDispatch()) {
                    refreshConversationList()
                    return@postDelayed
                }
                scheduleNext()
            }, d)
        }
        scheduleNext()
    }
    
    /**
     * 消费此前因 Fragment 未就绪而持久化的 [offline_messages] 整包（在 WebSocket install / onResume 等时机调用）。
     */
    fun consumePendingOfflineMessagesIfAny() {
        val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val raw = prefs.getString(PREF_KEY_PENDING_OFFLINE_MESSAGES, null) ?: return
        prefs.edit().remove(PREF_KEY_PENDING_OFFLINE_MESSAGES).apply()
        applyOfflineFriendMessagesFromOfflineBundle(raw)
        val fragment = getChatFragment()
        if (fragment != null && fragment.isAdded) {
            try {
                fragment.handleOfflineMessagesBundle(raw, 0)
                refreshConversationList()
            } catch (e: Exception) {
                Log.e(TAG, "consumePendingOfflineMessagesIfAny 失败: ${e.message}", e)
                prefs.edit().putString(PREF_KEY_PENDING_OFFLINE_MESSAGES, raw).apply()
            }
        } else {
            prefs.edit().putString(PREF_KEY_PENDING_OFFLINE_MESSAGES, raw).apply()
        }
    }
    
    /**
     * 检查应用是否在前台
     */
    fun isAppInForeground(): Boolean {
        return isAppInForeground
    }
    
    /**
     * 设置当前对话ID（供WebSocket判断是否在人工客服聊天页面）
     */
    fun setCurrentConversationId(conversationId: String?) {
        currentConversationId = conversationId
        Log.d(TAG, "当前对话ID设置为: $conversationId")
    }
    
    /**
     * 获取当前对话ID
     */
    fun getCurrentConversationId(): String? {
        return currentConversationId
    }
    
    /**
     * 检查是否在人工客服聊天页面
     */
    fun isInCustomerServiceChat(): Boolean {
        return currentConversationId == ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE
    }

    /** 与 ChatFragment 一致：自定义小助手 GUI 完成时不插入「任务已完成」类系统提示。 */
    private fun shouldSuppressCustomAssistantTaskCompletedBanner(): Boolean {
        val id = currentConversationId ?: return false
        return CustomAssistantManager.isCustomAssistantId(id)
    }

    private data class AssistantSyncImagePayload(
        val base64: String,
        val fileName: String,
        val messageType: String,
    )

    private fun parseAssistantSyncImagePayloads(json: org.json.JSONObject): List<AssistantSyncImagePayload> {
        val result = mutableListOf<AssistantSyncImagePayload>()
        val dedupe = LinkedHashSet<String>()
        val fileList = json.optJSONArray("file_list")
        if (fileList != null) {
            for (idx in 0 until fileList.length()) {
                val item = fileList.optJSONObject(idx) ?: continue
                val base64 = item.optString("file_base64", null)?.takeIf { it.isNotBlank() }
                    ?: item.optString("fileBase64", null)?.takeIf { it.isNotBlank() }
                    ?: item.optString("imageBase64", null)?.takeIf { it.isNotBlank() }
                    ?: continue
                if (!dedupe.add(base64)) continue
                val messageType = item.optString("message_type", "image").ifBlank { "image" }
                val fileName = item.optString("file_name", null)?.takeIf { it.isNotBlank() }
                    ?: item.optString("fileName", null)?.takeIf { it.isNotBlank() }
                    ?: if (messageType == "file") "文件" else "图片.png"
                result.add(
                    AssistantSyncImagePayload(
                        base64 = base64,
                        fileName = fileName,
                        messageType = messageType,
                    )
                )
            }
        }
        val fallbackBase64 = json.optString("file_base64", null)?.takeIf { it.isNotBlank() }
            ?: json.optString("fileBase64", null)?.takeIf { it.isNotBlank() }
            ?: json.optString("imageBase64", null)?.takeIf { it.isNotBlank() }
        if (fallbackBase64 != null && dedupe.add(fallbackBase64)) {
            val messageType = json.optString("message_type", "image").ifBlank { "image" }
            val fileName = json.optString("file_name", null)?.takeIf { it.isNotBlank() }
                ?: json.optString("fileName", null)?.takeIf { it.isNotBlank() }
                ?: if (messageType == "file") "文件" else "图片.png"
            result.add(
                AssistantSyncImagePayload(
                    base64 = fallbackBase64,
                    fileName = fileName,
                    messageType = messageType,
                )
            )
        }
        return result
    }

    private fun findAssistantSyncPersistIndex(
        messagesArray: org.json.JSONArray,
        syncMessageId: String,
        imageIndex: Int?,
    ): Int {
        for (i in 0 until messagesArray.length()) {
            val item = messagesArray.optJSONObject(i) ?: continue
            if (item.optString("syncMessageId", "") != syncMessageId) continue
            if (imageIndex == null) return i
            if (item.optInt("syncImageIndex", -1) == imageIndex) return i
        }
        return -1
    }

    private fun upsertAssistantSyncPersistMessage(
        messagesArray: org.json.JSONArray,
        messageObj: org.json.JSONObject,
        syncMessageId: String?,
        imageIndex: Int? = null,
    ) {
        if (syncMessageId.isNullOrBlank()) {
            messagesArray.put(messageObj)
            return
        }
        messageObj.put("syncMessageId", syncMessageId)
        imageIndex?.let { messageObj.put("syncImageIndex", it) }
        val hitIndex = findAssistantSyncPersistIndex(messagesArray, syncMessageId, imageIndex)
        if (hitIndex < 0) {
            messagesArray.put(messageObj)
            return
        }
        val existing = messagesArray.optJSONObject(hitIndex) ?: org.json.JSONObject()
        val merged = org.json.JSONObject(existing.toString())
        merged.put("sender", messageObj.optString("sender", existing.optString("sender", "")))
        val nextMessage = messageObj.optString("message", "")
        if (nextMessage.isNotBlank()) merged.put("message", nextMessage)
        val nextType = messageObj.optString("type", "")
        if (nextType.isNotBlank()) merged.put("type", nextType)
        if (messageObj.has("imagePath")) {
            merged.put("imagePath", messageObj.optString("imagePath"))
        }
        val oldTs = existing.optLong("timestamp", 0L)
        val newTs = messageObj.optLong("timestamp", oldTs)
        merged.put("timestamp", maxOf(oldTs, newTs))
        merged.put("syncMessageId", syncMessageId)
        imageIndex?.let { merged.put("syncImageIndex", it) }
        merged.put("uuid", existing.optString("uuid", java.util.UUID.randomUUID().toString()))
        messagesArray.put(hitIndex, merged)
    }
    
    /**
     * 处理小助手跨设备同步消息（assistant_user_message / assistant_sync_message）
     * 在对话外时：保存消息、更新对话列表缩略图、增加未读红点、Toast 提醒
     */
    private fun handleAssistantSyncMessage(messageText: String, type: String) {
        try {
            val json = org.json.JSONObject(messageText)
            if (json.getString("type") != type) return
            val content = json.optString("content", "")
            val rawSender = json.optString("sender", if (type == "assistant_user_message") "我" else "系统")
            val syncMessageId = json.optString("message_id", "").trim().ifBlank { null }
            val imagePayloads = parseAssistantSyncImagePayloads(json)
            val hasImage = imagePayloads.isNotEmpty()
            val convIdRaw = json.optString("conversation_id", "assistant").trim()
            val (targetConvId, targetSessionIdFromPayload) = if (convIdRaw.contains("__")) {
                val parts = convIdRaw.split("__", limit = 2)
                parts[0] to parts.getOrNull(1)
            } else {
                convIdRaw to json.optString("session_id", "").trim().ifEmpty { null }
            }
            val sender = ChatConstants.normalizeAssistantSenderForConversation(rawSender, targetConvId)
            val isViewingByConversationId = currentConversationId == targetConvId
            val chatFragmentCandidate = getChatFragment(targetConvId)
            val fragmentConversationId =
                (chatFragmentCandidate?.arguments?.getSerializable("conversation") as? Conversation)?.id
            // 仅接受会话ID匹配的 ChatFragment，避免拿到其它会话实例导致“收到但不展示”
            val chatFragment =
                chatFragmentCandidate?.takeIf { fragmentConversationId == targetConvId }
            val isViewingByFragment = chatFragment != null
            val canDispatchToChatFragment =
                (isViewingByConversationId || isViewingByFragment) &&
                    chatFragment?.canHandleRealtimeAssistantSync() == true
            Log.d(
                TAG,
                "assistant_sync 分发判定: currentConv=$currentConversationId, targetConv=$targetConvId, " +
                    "fragmentConv=$fragmentConversationId, byId=$isViewingByConversationId, " +
                    "byFragment=$isViewingByFragment, canDispatch=$canDispatchToChatFragment"
            )
            if (canDispatchToChatFragment && chatFragment != null) {
                val normalizedText = if (sender == rawSender) {
                    messageText
                } else {
                    json.put("sender", sender).toString()
                }
                chatFragment.handleAssistantSyncMessageDirectly(normalizedText)
                return
            }
            if ((isViewingByConversationId || isViewingByFragment) && !canDispatchToChatFragment) {
                Log.d(TAG, "目标会话在前台标记中，但 ChatFragment 未就绪，改为持久化保存: convId=$targetConvId")
            }
            // 不在目标对话：保存 + 更新缩略图 + 未读红点 + Toast
            CoroutineScope(Dispatchers.Main + coroutineExceptionHandler).launch {
                try {
                    val activeSessionIdFromUi = chatFragment
                        ?.currentSessionIdForMultiSession
                        ?.takeIf { it.isNotBlank() }
                    val targetSessionId = when {
                        // 1) payload 显式携带 session_id 时优先使用，确保跨端线程准确落位
                        targetSessionIdFromPayload != null -> targetSessionIdFromPayload
                        // 2) payload 未带 session_id 时，优先使用当前 ChatFragment 正在查看的 session
                        activeSessionIdFromUi != null -> activeSessionIdFromUi
                        // 3) 兜底走 helper 的最新会话策略
                        else -> null
                    }
                    val appPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    val msgKey = AssistantSyncMessageHelper.resolveAssistantMsgKey(this@MainActivity, targetConvId, targetSessionId)
                    val existingJson = appPrefs.getString(msgKey, null)
                    val messagesArray = if (existingJson != null) org.json.JSONArray(existingJson) else org.json.JSONArray()
                    val msgType = if (type == "assistant_user_message") "text" else if (
                        AssistantSyncMessageHelper.isAnswerSyncMessage(sender, targetConvId, this@MainActivity)
                    ) "answer" else "system"
                    var hasPersistedImage = false
                    if (hasImage) {
                        imagePayloads.forEachIndexed { index, payload ->
                            val imagePath = saveImageFromBase64(payload.base64)
                            if (imagePath != null) {
                                hasPersistedImage = true
                                val imgFile = java.io.File(imagePath)
                                val msgText = if (index == 0 && content.isNotEmpty()) "$content\n[图片: ${imgFile.name}]" else "[图片: ${imgFile.name}]"
                                upsertAssistantSyncPersistMessage(
                                    messagesArray,
                                    org.json.JSONObject().apply {
                                        put("sender", sender)
                                        put("message", msgText)
                                        put("type", "image")
                                        put("timestamp", System.currentTimeMillis())
                                        put("uuid", java.util.UUID.randomUUID().toString())
                                        put("imagePath", imgFile.absolutePath)
                                    },
                                    syncMessageId,
                                    imageIndex = index,
                                )
                            }
                        }
                    }
                    if (!hasPersistedImage) {
                        upsertAssistantSyncPersistMessage(
                            messagesArray,
                            org.json.JSONObject().apply {
                                put("sender", sender)
                                put("message", content.ifEmpty { if (hasImage) "[图片]" else "" })
                                put("type", msgType)
                                put("timestamp", System.currentTimeMillis())
                                put("uuid", java.util.UUID.randomUUID().toString())
                            },
                            syncMessageId,
                        )
                    }
                    appPrefs.edit().putString(msgKey, messagesArray.toString()).apply()
                    Log.d(
                        TAG,
                        "assistant sync持久化: conv=$targetConvId, isViewingById=$isViewingByConversationId, " +
                            "isViewingByFragment=$isViewingByFragment, canDispatch=$canDispatchToChatFragment, " +
                            "payloadSession=$targetSessionIdFromPayload, activeSession=$activeSessionIdFromUi, " +
                            "chosenSession=$targetSessionId, key=$msgKey, size=${messagesArray.length()}"
                    )
                    val convPrefs = getSharedPreferences("conversations", Context.MODE_PRIVATE)
                    val previewText = if (hasPersistedImage || hasImage) "[图片]" else content
                    convPrefs.edit()
                        .putString("${targetConvId}_last_message", previewText)
                        .putLong("${targetConvId}_last_time", System.currentTimeMillis())
                        .apply()
                    ConversationSessionNotifier.incrementUnread(this@MainActivity, targetConvId, 1)
                    // 若当前在对话列表页，实时更新缩略图
                    findConversationListFragment()?.updateLastMessage(targetConvId, previewText)
                    Toast.makeText(this@MainActivity, "收到小助手新消息", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "handleAssistantSyncMessage 保存失败: ${e.message}", e)
                    Toast.makeText(this@MainActivity, "收到小助手新消息", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleAssistantSyncMessage 解析失败: ${e.message}", e)
        }
    }

    /**
     * 持久化 PC 执行结果（mobile_execute_pc_result）到目标会话，
     * 确保不在会话页时返回应用仍可看到记录。
     */
    private fun handleMobileExecutePcResultPersistOnly(messageText: String) {
        try {
            val json = org.json.JSONObject(messageText)
            if (json.optString("type") != "mobile_execute_pc_result") return
            val convIdRaw = json.optString("conversation_id", "").trim()
            if (convIdRaw.isEmpty()) return
            val (targetConvId, targetSessionIdFromPayload) = if (convIdRaw.contains("__")) {
                val parts = convIdRaw.split("__", limit = 2)
                parts[0] to parts.getOrNull(1)?.takeIf { it.isNotBlank() }
            } else {
                convIdRaw to null
            }
            val success = json.optBoolean("success", false)
            val content = json.optString("content", "")
            val error = json.optString("error", "")
            val resultText = if (success) {
                content.takeIf { it.isNotBlank() } ?: "任务已完成"
            } else {
                "执行失败：${error.takeIf { it.isNotBlank() } ?: "未知错误"}"
            }
            val sender = CustomAssistantManager.getById(this, targetConvId)?.name ?: "小助手"

            CoroutineScope(Dispatchers.Main + coroutineExceptionHandler).launch {
                try {
                    val assistant = CustomAssistantManager.getById(this@MainActivity, targetConvId)
                    val activeSessionIdFromUi = getChatFragment(targetConvId)
                        ?.currentSessionIdForMultiSession
                        ?.takeIf { it.isNotBlank() }
                    val targetSessionId = when {
                        // 优先使用“当前会话详情页顶部正在使用的session”
                        activeSessionIdFromUi != null -> activeSessionIdFromUi
                        // 其次使用服务端payload携带的session
                        targetSessionIdFromPayload != null -> targetSessionIdFromPayload
                        assistant?.multiSession == true -> {
                            // payload 未带 session 时，写入当前最新 session，确保会话详情可见
                            SessionStorage.loadSessions(this@MainActivity, targetConvId)
                                .firstOrNull()
                                ?.id
                        }
                        else -> null
                    }
                    val appPrefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    val msgKey = if (targetSessionId != null) {
                        "chat_messages_${targetConvId}_$targetSessionId"
                    } else {
                        "chat_messages_$targetConvId"
                    }
                    val existingJson = appPrefs.getString(msgKey, null)
                    val messagesArray = if (existingJson != null) org.json.JSONArray(existingJson) else org.json.JSONArray()
                    messagesArray.put(org.json.JSONObject().apply {
                        put("sender", sender)
                        put("message", resultText)
                        put("type", "answer")
                        put("timestamp", System.currentTimeMillis())
                        put("uuid", java.util.UUID.randomUUID().toString())
                    })
                    appPrefs.edit().putString(msgKey, messagesArray.toString()).apply()

                    val convPrefs = getSharedPreferences("conversations", Context.MODE_PRIVATE)
                    convPrefs.edit()
                        .putString("${targetConvId}_last_message", resultText)
                        .putLong("${targetConvId}_last_time", System.currentTimeMillis())
                        .apply()

                    ConversationSessionNotifier.incrementUnread(this@MainActivity, targetConvId, 1)
                    findConversationListFragment()?.updateLastMessage(targetConvId, resultText)
                    Log.d(TAG, "mobile_execute_pc_result 已持久化保存: conv=$targetConvId, session=$targetSessionId, key=$msgKey")
                } catch (e: Exception) {
                    Log.e(TAG, "mobile_execute_pc_result 持久化失败: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleMobileExecutePcResultPersistOnly 解析失败: ${e.message}", e)
        }
    }

    /** [friend_sync_message] 己方消息仅落盘时：更新会话预览并刷新列表（无未读/弹窗） */
    internal fun onFriendSyncSelfMessagePersisted(conversationId: String, preview: String) {
        updateFriendConversationLastMessage(conversationId, preview)
        refreshConversationListPublic()
    }

    /** [friend_sync_message] 对方消息落盘后：与实时好友消息一致的未读/弹窗 */
    internal fun onFriendSyncPeerMessagePersisted(
        senderImei: String,
        senderName: String,
        content: String,
        friendConversationId: String,
        lastMessageForList: String?
    ) {
        handleFriendMessage(senderImei, senderName, content, friendConversationId, lastMessageForList)
    }

    /**
     * 处理人工客服消息
     * 当收到消息时，根据当前页面状态决定是否显示应用内弹窗和更新未读计数
     * @param content 消息内容
     * @param sender 发送者名称
     * @param count 消息数量
     */
    private fun handleCustomerServiceMessage(content: String, sender: String, count: Int) {
        Log.d(TAG, "========== handleCustomerServiceMessage 被调用 ==========")
        Log.d(TAG, "收到人工客服消息: sender=$sender, content=$content, count=$count")
        Log.d(TAG, "isAppInForeground=$isAppInForeground")
        Log.d(TAG, "currentConversationId=$currentConversationId")
        Log.d(TAG, "CONVERSATION_ID_CUSTOMER_SERVICE=${ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE}")
        
        // 检查是否为好友申请消息
        if (content.contains("收到好友请求") || content.contains("好友请求")) {
            // 处理好友申请
            handleFriendRequest(sender)
            return
        }
        
        // 检查是否在人工客服聊天页面
        val isInCustomerServiceChat = isInCustomerServiceChat()
        Log.d(TAG, "当前是否在人工客服聊天页面: $isInCustomerServiceChat")
        
        if (isInCustomerServiceChat) {
            // 在人工客服聊天页面，不显示弹窗，也不增加未读计数
            // 消息会由ChatFragment的回调处理并显示
            Log.d(TAG, "在人工客服聊天页面，跳过弹窗和未读计数（消息由ChatFragment处理）")
            return
        }
        
        // 不在人工客服聊天页面，保存消息到待处理队列（用于之后显示）
        Log.d(TAG, "不在聊天页面，开始保存消息到待处理队列")
        CustomerServiceUnreadManager.addPendingMessage(this, sender, content)
        Log.d(TAG, "消息已保存到待处理队列: sender=$sender, content=${content.take(50)}")
        
        // 更新对话列表的最新消息
        updateCustomerServiceConversationLastMessage(content)
        
        // 增加未读消息计数
        val oldCount = CustomerServiceUnreadManager.getUnreadCount(this)
        CustomerServiceUnreadManager.incrementUnreadCount(this, count)
        val newCount = CustomerServiceUnreadManager.getUnreadCount(this)
        Log.d(TAG, "未读消息计数: $oldCount -> $newCount")
        
        // 检查应用是否在前台，如果在前台则显示应用内弹窗
        Log.d(TAG, "检查应用前台状态: isAppInForeground=$isAppInForeground")
        if (isAppInForeground) {
            Log.d(TAG, "应用在前台，准备显示应用内弹窗")
            // 在主线程显示弹窗
            runOnUiThread {
                try {
                    Log.d(TAG, "正在显示应用内弹窗...")
                    Log.d(TAG, "Activity状态检查: isFinishing=${isFinishing}, isDestroyed=${if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) isDestroyed else false}")
                    InAppNotificationManager.showNotification(
                        activity = this,
                        title = DisplayNameHelper.getDisplayName(this, sender),
                        content = content,
                        avatarResId = R.drawable.ic_customer_service_avatar,
                        onClick = {
                            Log.d(TAG, "用户点击了应用内弹窗，跳转到聊天页面")
                            // 点击弹窗跳转到人工客服聊天页面
                            navigateToCustomerServiceChat()
                        }
                    )
                    Log.d(TAG, "应用内弹窗已显示")
                } catch (e: Exception) {
                    Log.e(TAG, "显示应用内弹窗失败: ${e.message}", e)
                    e.printStackTrace()
                }
            }
        } else {
            Log.d(TAG, "应用在后台，跳过应用内弹窗（系统通知已在WebSocket中处理）")
        }
        Log.d(TAG, "========== handleCustomerServiceMessage 处理完成 ==========")
    }
    
    /**
     * 在 [offline_messages] 进入 [ChatCustomerServiceWebSocketHandler.processOfflineMessages] 之前调用：
     * 会话列表页且无 ChatFragment 时也能落盘好友消息、更新会话列表/未读，并与实时 [friend_message] 一致的应用内弹窗。
     */
    fun applyOfflineFriendMessagesFromOfflineBundle(messageText: String) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { applyOfflineFriendMessagesFromOfflineBundle(messageText) }
            return
        }
        try {
            val json = org.json.JSONObject(messageText)
            if (json.optString("type") != "offline_messages") return
            val messagesArray = json.getJSONArray("messages")
            var anyNew = false
            for (i in 0 until messagesArray.length()) {
                val msg = messagesArray.getJSONObject(i)
                var msgType = msg.optString("type", "")
                if (msgType.isEmpty() &&
                    msg.optString("senderImei", "").isNotEmpty() &&
                    msg.optString("groupId", "").isEmpty()
                ) {
                    msgType = "friend_message"
                }
                if (msgType != "friend_message") continue
                val senderImei = msg.optString("senderImei", "")
                if (senderImei.isEmpty()) continue
                val friendConversationId = "friend_$senderImei"
                if (currentConversationId == friendConversationId) continue

                val content = msg.optString("content", "")
                val skillId = msg.optString("skillId", null)
                val timestamp = ChatWebSocketJsonUtils.parseMessageTimestamp(msg, "timestamp")
                val hadFriendLocal = FriendManager.getFriend(this, senderImei) != null
                val (nickHint, avatarHint) = FriendManager.parseFriendIdentityHintsFromMessageJson(msg)
                FriendManager.ensureFriendForIncomingMessage(this, senderImei, nickHint, avatarHint)
                if (!hadFriendLocal) {
                    testScope.launch {
                        try {
                            FriendManager.syncFriendsFromServer(this@MainActivity)
                        } catch (_: Exception) {
                        }
                        runOnUiThread { refreshConversationListPublic() }
                    }
                }
                val friend = FriendManager.getFriend(this, senderImei)
                val senderName = friend?.nickname ?: senderImei.take(8) + "..."

                val wasNew = OfflineFriendMessagePersistence.appendTextFriendMessageIfNew(
                    this, senderImei, senderName, content, timestamp, skillId
                )
                if (!wasNew) continue
                anyNew = true
                updateFriendConversationLastMessage(friendConversationId, content)
                FriendUnreadManager.incrementUnreadCount(this, friendConversationId, 1)
                if (isAppInForeground) {
                    showFriendInAppPopup(senderName, content, friendConversationId, senderImei)
                }
            }
            if (anyNew) {
                refreshConversationListPublic()
            }
        } catch (e: Exception) {
            Log.e(TAG, "applyOfflineFriendMessagesFromOfflineBundle: ${e.message}", e)
        }
    }

    /** 与 [handleFriendMessage] 中实时好友消息一致的应用内顶部弹窗（含头像加载）。 */
    private fun showFriendInAppPopup(senderName: String, content: String, friendConversationId: String, senderImei: String) {
        Log.d(TAG, "应用在前台，准备显示好友消息应用内弹窗")
        val friend = FriendManager.getFriend(this, senderImei)
        val friendAvatarBase64 = friend?.avatar
        testScope.launch {
            try {
                val avatarBitmap = if (!friendAvatarBase64.isNullOrEmpty()) {
                    withContext(Dispatchers.IO) {
                        GroupAvatarHelper.loadBitmapFromBase64(friendAvatarBase64)
                    }
                } else {
                    null
                }
                runOnUiThread {
                    try {
                        Log.d(TAG, "正在显示好友消息应用内弹窗...")
                        InAppNotificationManager.showNotification(
                            activity = this@MainActivity,
                            title = senderName,
                            content = content,
                            avatarResId = R.drawable.ic_system_avatar,
                            avatarBitmap = avatarBitmap,
                            onClick = {
                                Log.d(TAG, "用户点击了好友消息弹窗，跳转到好友聊天页面")
                                navigateToFriendChat(friendConversationId)
                            },
                            duration = 3000L
                        )
                        Log.d(TAG, "好友消息应用内弹窗已显示")
                    } catch (e: Exception) {
                        Log.e(TAG, "显示好友消息应用内弹窗失败: ${e.message}", e)
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载好友头像失败: ${e.message}", e)
                runOnUiThread {
                    try {
                        InAppNotificationManager.showNotification(
                            activity = this@MainActivity,
                            title = senderName,
                            content = content,
                            avatarResId = R.drawable.ic_system_avatar,
                            onClick = {
                                navigateToFriendChat(friendConversationId)
                            },
                            duration = 3000L
                        )
                    } catch (e2: Exception) {
                        Log.e(TAG, "显示好友消息应用内弹窗失败: ${e2.message}", e2)
                    }
                }
            }
        }
    }

    /**
     * 处理好友消息
     * 当收到好友消息时，根据当前页面状态决定是否显示应用内弹窗和更新对话列表
     * @param senderImei 发送者IMEI
     * @param senderName 发送者名称
     * @param content 消息内容（用于弹窗显示）
     * @param friendConversationId 好友对话ID
     * @param lastMessageForList 用于对话列表显示的最后消息（如果为null则使用content，用于图片消息格式化）
     */
    private fun handleFriendMessage(senderImei: String, senderName: String, content: String, friendConversationId: String, lastMessageForList: String? = null) {
        Log.d(TAG, "========== handleFriendMessage 被调用 ==========")
        Log.d(TAG, "收到好友消息: senderImei=$senderImei, senderName=$senderName, content=$content")
        Log.d(TAG, "isAppInForeground=$isAppInForeground")
        Log.d(TAG, "currentConversationId=$currentConversationId")
        Log.d(TAG, "friendConversationId=$friendConversationId")
        
        // 检查是否在当前好友对话页面
        val isInFriendChat = currentConversationId == friendConversationId
        Log.d(TAG, "当前是否在该好友对话页面: $isInFriendChat")
        
        if (isInFriendChat) {
            // 在当前好友对话页面，不显示弹窗，也不增加未读计数
            // 消息会由ChatFragment的回调处理并显示
            Log.d(TAG, "在当前好友对话页面，跳过弹窗和未读计数（消息由ChatFragment处理）")
            return
        }
        
        // 更新对话列表的最新消息（如果提供了格式化后的消息则使用，否则使用原始content）
        val messageToShow = lastMessageForList ?: content
        updateFriendConversationLastMessage(friendConversationId, messageToShow)
        
        // 增加好友未读消息计数
        FriendUnreadManager.incrementUnreadCount(this, friendConversationId, 1)
        Log.d(TAG, "好友未读消息计数已增加: $friendConversationId")
        
        Log.d(TAG, "检查应用前台状态: isAppInForeground=$isAppInForeground")
        if (isAppInForeground) {
            showFriendInAppPopup(senderName, content, friendConversationId, senderImei)
        } else {
            Log.d(TAG, "应用在后台，跳过应用内弹窗（系统通知已在WebSocket中处理）")
        }
        Log.d(TAG, "========== handleFriendMessage 处理完成 ==========")
    }
    
    /**
     * 从Base64字符串保存图片到本地
     */
    private suspend fun saveImageFromBase64(base64: String): String? = withContext(Dispatchers.IO) {
        try {
            val imagesDir = java.io.File(getExternalFilesDir(null), "images")
            if (!imagesDir.exists()) {
                imagesDir.mkdirs()
            }
            
            val timestamp = System.currentTimeMillis()
            
            // 清理Base64字符串：移除可能的data URI前缀
            var cleanBase64 = base64.trim()
            if (cleanBase64.startsWith("data:image/")) {
                val base64Index = cleanBase64.indexOf(";base64,")
                if (base64Index != -1) {
                    cleanBase64 = cleanBase64.substring(base64Index + 8) // 跳过 ";base64,"
                } else {
                    Log.w(TAG, "Base64字符串包含data:image前缀但缺少;base64,分隔符")
                }
            }
            
            // 移除可能的换行符和空格
            cleanBase64 = cleanBase64.replace("\n", "").replace("\r", "").replace(" ", "")
            
            // 检查Base64字符串长度（必须是4的倍数）
            val remainder = cleanBase64.length % 4
            if (remainder != 0) {
                // 添加填充
                val padding = 4 - remainder
                cleanBase64 += "=".repeat(padding)
                Log.d(TAG, "Base64字符串长度不是4的倍数，已添加${padding}个填充字符")
            }
            
            // 解码Base64
            val imageBytes = try {
                Base64.decode(cleanBase64, Base64.DEFAULT)
            } catch (e: Exception) {
                Log.e(TAG, "Base64解码失败: ${e.message}", e)
                // 尝试使用NO_WRAP模式
                try {
                    Base64.decode(cleanBase64, Base64.NO_WRAP)
                } catch (e2: Exception) {
                    Log.e(TAG, "Base64解码失败（NO_WRAP模式）: ${e2.message}", e2)
                    return@withContext null
                }
            }
            
            if (imageBytes.isEmpty()) {
                Log.e(TAG, "Base64解码后数据为空")
                return@withContext null
            }
            
            // 验证图片文件头（JPEG: FF D8 FF, PNG: 89 50 4E 47）
            val isJpeg = imageBytes.size >= 3 && imageBytes[0] == 0xFF.toByte() && 
                        imageBytes[1] == 0xD8.toByte() && imageBytes[2] == 0xFF.toByte()
            val isPng = imageBytes.size >= 4 && imageBytes[0] == 0x89.toByte() && 
                        imageBytes[1] == 0x50.toByte() && imageBytes[2] == 0x4E.toByte() && 
                        imageBytes[3] == 0x47.toByte()
            
            if (!isJpeg && !isPng) {
                Log.e(TAG, "解码后的数据不是有效的图片格式（JPEG或PNG）")
                Log.e(TAG, "文件头前4字节: ${imageBytes.take(4).joinToString { "%02X".format(it.toInt() and 0xFF) }}")
                return@withContext null
            }
            
            // 根据文件头确定文件扩展名
            val extension = if (isJpeg) "jpg" else "png"
            val imageFile = java.io.File(imagesDir, "received_image_$timestamp.$extension")
            
            imageFile.writeBytes(imageBytes)
            
            // 验证文件是否成功写入
            if (!imageFile.exists() || imageFile.length() == 0L) {
                Log.e(TAG, "图片文件保存失败或文件为空")
                return@withContext null
            }
            
            Log.d(TAG, "接收到的图片已保存到本地: ${imageFile.absolutePath}, 大小: ${imageFile.length()} bytes, 格式: $extension")
            return@withContext imageFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "从Base64保存图片失败: ${e.message}", e)
            e.printStackTrace()
            return@withContext null
        }
    }
    
    /**
     * 更新好友对话列表的最新消息
     */
    private fun updateFriendConversationLastMessage(friendConversationId: String, message: String) {
        val block = Runnable {
            try {
                val prefs = getSharedPreferences("conversations", Context.MODE_PRIVATE)
                val currentTime = System.currentTimeMillis()
                prefs.edit()
                    .putString("${friendConversationId}_last_message", message)
                    .putLong("${friendConversationId}_last_time", currentTime)
                    .apply()
                val fragmentManager = supportFragmentManager
                val conversationListFragment = fragmentManager.fragments.find { it is ConversationListFragment } as? ConversationListFragment
                conversationListFragment?.updateLastMessage(friendConversationId, message)
                Log.d(TAG, "好友对话列表已更新: $friendConversationId")
            } catch (e: Exception) {
                Log.e(TAG, "更新好友对话列表失败: ${e.message}", e)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) block.run() else runOnUiThread(block)
    }
    
    /**
     * 跳转到好友聊天页面
     */
    private fun navigateToFriendChat(friendConversationId: String) {
        try {
            // 从对话ID中提取好友IMEI（格式：friend_$senderImei）
            val senderImei = friendConversationId.removePrefix("friend_")
            
            // 获取好友信息
            val friend = FriendManager.getFriend(this, senderImei)
            val friendName = friend?.nickname ?: senderImei.take(8) + "..."
            val friendAvatar = friend?.avatar
            
            // 创建Conversation对象
            val conversation = Conversation(
                id = friendConversationId,
                name = friendName,
                avatar = friendAvatar,
                lastMessage = null,
                lastMessageTime = System.currentTimeMillis()
            )
            
            // 在Fragment切换之前立即隐藏导航栏，避免动画
            setBottomNavigationVisibility(false)
            
            // 切换到聊天Fragment
            switchToChatFragment(conversation)
            Log.d(TAG, "准备跳转到好友聊天页面: $friendConversationId")
        } catch (e: Exception) {
            Log.e(TAG, "跳转到好友聊天页面失败: ${e.message}", e)
        }
    }
    
    /**
     * 处理群组消息
     * 当收到群组消息时，根据当前页面状态决定是否显示应用内弹窗和更新对话列表
     * @param groupId 群组ID
     * @param sender 发送者名称
     * @param content 消息内容（用于弹窗显示）
     * @param groupConversationId 群组对话ID
     * @param lastMessageForList 用于对话列表显示的最后消息（如果为null则使用content，用于图片消息格式化）
     */
    private fun handleGroupMessage(groupId: String, sender: String, content: String, groupConversationId: String, lastMessageForList: String? = null) {
        Log.d(TAG, "========== handleGroupMessage 被调用 ==========")
        Log.d(TAG, "收到群组消息: groupId=$groupId, sender=$sender, content=$content")
        Log.d(TAG, "isAppInForeground=$isAppInForeground")
        Log.d(TAG, "currentConversationId=$currentConversationId")
        Log.d(TAG, "groupConversationId=$groupConversationId")
        
        // 检查是否在当前群组对话页面
        val isInGroupChat = currentConversationId == groupConversationId || 
                           currentConversationId == ConversationListFragment.CONVERSATION_ID_GROUP ||
                           currentConversationId == ConversationListFragment.CONVERSATION_ID_ASSISTANT
        Log.d(TAG, "当前是否在该群组对话页面: $isInGroupChat")
        
        if (isInGroupChat) {
            // 在当前群组对话页面，不显示弹窗，也不增加未读计数
            // 消息会由ChatFragment的回调处理并显示
            Log.d(TAG, "在当前群组对话页面，跳过弹窗和未读计数（消息由ChatFragment处理）")
            return
        }
        
        // 更新对话列表的最新消息（如果提供了格式化后的消息则使用，否则使用原始content）
        val messageToShow = lastMessageForList ?: content
        updateGroupConversationLastMessage(groupConversationId, messageToShow)
        
        // 增加群组未读消息计数
        GroupUnreadManager.incrementUnreadCount(this, groupConversationId, 1)
        Log.d(TAG, "群组未读消息计数已增加: $groupConversationId")
        
        // 检查应用是否在前台，如果在前台则显示应用内弹窗
        Log.d(TAG, "检查应用前台状态: isAppInForeground=$isAppInForeground")
        if (isAppInForeground) {
            Log.d(TAG, "应用在前台，准备显示群组消息应用内弹窗")
            // 获取群组信息以获取群组名称和成员列表
            val group = GroupManager.getGroup(this, groupId)
            val groupName = group?.name ?: "群组"
            val groupMembers = group?.members ?: emptyList()
            val groupAssistants = group?.assistants ?: emptyList()
            
            // 计算头像尺寸（在主线程中计算，避免在协程中访问resources）
            val avatarSize = (40 * resources.displayMetrics.density).toInt()
            
            // 异步加载群组头像
            testScope.launch {
                try {
                    val avatarBitmap = if (groupMembers.isNotEmpty() || groupAssistants.isNotEmpty()) {
                        // 在IO线程加载群组头像
                        withContext(Dispatchers.IO) {
                            // 先检查缓存
                            val cachedAvatar = GroupAvatarHelper.getCachedGroupAvatarFromMembers(
                                groupMembers,
                                avatarSize,
                                groupAssistants
                            )
                            if (cachedAvatar != null && !cachedAvatar.isRecycled) {
                                Log.d(TAG, "使用缓存的群组头像")
                                cachedAvatar
                            } else {
                                // 缓存未命中，生成群组头像
                                Log.d(TAG, "生成新的群组头像")
                                GroupAvatarHelper.createGroupAvatarFromMembers(
                                    this@MainActivity,
                                    groupMembers,
                                    avatarSize,
                                    groupAssistants
                                )
                            }
                        }
                    } else {
                        null
                    }
                    
                    // 在主线程显示弹窗
                    runOnUiThread {
                        try {
                            Log.d(TAG, "正在显示群组消息应用内弹窗...")
                            InAppNotificationManager.showNotification(
                                activity = this@MainActivity,
                                title = "$groupName - $sender",
                                content = content,
                                avatarResId = R.drawable.ic_system_avatar, // 默认头像（如果加载失败或没有成员）
                                avatarBitmap = avatarBitmap, // 群组头像（如果有）
                                onClick = {
                                    Log.d(TAG, "用户点击了群组消息弹窗，跳转到群组聊天页面")
                                    // 点击弹窗跳转到群组聊天页面
                                    navigateToGroupChat(groupConversationId, groupName)
                                },
                                duration = 3000L // 群组消息弹窗3秒后自动消失
                            )
                            Log.d(TAG, "群组消息应用内弹窗已显示")
                        } catch (e: Exception) {
                            Log.e(TAG, "显示群组消息应用内弹窗失败: ${e.message}", e)
                            e.printStackTrace()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "加载群组头像失败: ${e.message}", e)
                    // 即使头像加载失败，也显示弹窗（使用默认头像）
                    runOnUiThread {
                        try {
                            InAppNotificationManager.showNotification(
                                activity = this@MainActivity,
                                title = "$groupName - $sender",
                                content = content,
                                avatarResId = R.drawable.ic_system_avatar,
                                onClick = {
                                    navigateToGroupChat(groupConversationId, groupName)
                                },
                                duration = 3000L
                            )
                        } catch (e2: Exception) {
                            Log.e(TAG, "显示群组消息应用内弹窗失败: ${e2.message}", e2)
                        }
                    }
                }
            }
        } else {
            Log.d(TAG, "应用在后台，跳过应用内弹窗（系统通知已在WebSocket中处理）")
        }
        Log.d(TAG, "========== handleGroupMessage 处理完成 ==========")
    }
    
    /**
     * 更新群组对话列表的最新消息
     */
    private fun updateGroupConversationLastMessage(groupConversationId: String, message: String) {
        try {
            val prefs = getSharedPreferences("conversations", Context.MODE_PRIVATE)
            val currentTime = System.currentTimeMillis()
            
            // 保存到SharedPreferences
            prefs.edit()
                .putString("${groupConversationId}_last_message", message)
                .putLong("${groupConversationId}_last_time", currentTime)
                .apply()
            
            // 通知对话列表Fragment更新（如果存在）
            val fragmentManager = supportFragmentManager
            val conversationListFragment = fragmentManager.fragments.find { it is ConversationListFragment } as? ConversationListFragment
            conversationListFragment?.updateLastMessage(groupConversationId, message)
            
            Log.d(TAG, "群组对话列表已更新: $groupConversationId")
        } catch (e: Exception) {
            Log.e(TAG, "更新群组对话列表失败: ${e.message}", e)
        }
    }
    
    /**
     * 跳转到群组聊天页面
     */
    private fun navigateToGroupChat(groupConversationId: String, groupName: String) {
        try {
            // 获取群组ID（从conversationId中提取）
            val groupId = groupConversationId.removePrefix("group_")
            val group = GroupManager.getGroup(this, groupId)
            val finalGroupName = group?.name ?: groupName
            
            // 创建Conversation对象
            val conversation = Conversation(
                id = groupConversationId,
                name = finalGroupName,
                avatar = null,
                lastMessage = null,
                lastMessageTime = System.currentTimeMillis()
            )
            
            // 在Fragment切换之前立即隐藏导航栏，避免动画
            setBottomNavigationVisibility(false)
            
            // 切换到聊天Fragment
            switchToChatFragment(conversation)
            Log.d(TAG, "准备跳转到群组聊天页面: $groupConversationId")
        } catch (e: Exception) {
            Log.e(TAG, "跳转到群组聊天页面失败: ${e.message}", e)
        }
    }
    
    /**
     * 处理好友申请
     * @param sender 发送者名称
     */
    private fun handleFriendRequest(sender: String) {
        Log.d(TAG, "处理好友申请: sender=$sender")
        
        // 获取最新的待处理好友申请
        val pendingRequests = FriendRequestManager.getPendingRequests(this)
        val latestRequest = pendingRequests.filter { it.status == "pending" }.maxByOrNull { it.timestamp }
        
        if (latestRequest == null) {
            Log.w(TAG, "没有找到待处理的好友申请")
            return
        }
        
        // 检查应用是否在前台，如果在前台则显示弹窗
        if (isAppInForeground) {
            runOnUiThread {
                try {
                    showFriendRequestDialog(latestRequest)
                } catch (e: Exception) {
                    Log.e(TAG, "显示好友申请弹窗失败: ${e.message}", e)
                    e.printStackTrace()
                }
            }
        }
        
        // 刷新好友Fragment的红点显示（如果存在）
        refreshFriendFragmentBadge()
    }
    
    /**
     * 显示好友申请弹窗
     */
    private fun showFriendRequestDialog(request: FriendRequestManager.FriendRequest) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_friend_request, null)
        val tvSenderName = dialogView.findViewById<android.widget.TextView>(R.id.tvSenderName)
        val tvSenderImei = dialogView.findViewById<android.widget.TextView>(R.id.tvSenderImei)
        val btnAccept = dialogView.findViewById<android.widget.Button>(R.id.btnAccept)
        val btnReject = dialogView.findViewById<android.widget.Button>(R.id.btnReject)
        val btnViewInfo = dialogView.findViewById<android.widget.Button>(R.id.btnViewInfo)
        val btnClose = dialogView.findViewById<android.widget.ImageButton>(R.id.btnClose)
        
        // 设置发送者信息
        tvSenderName.text = request.senderName ?: "用户"
        tvSenderImei.text = "IMEI: ${request.senderImei.take(8)}..."
        
        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        // 设置窗口背景透明以显示圆角
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        // 设置圆角outline
        val cornerRadiusPx = (20 * resources.displayMetrics.density).toInt()
        dialogView.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx.toFloat())
            }
        }
        dialogView.clipToOutline = true
        
        // 关闭按钮
        btnClose.setOnClickListener {
            dialog.dismiss()
        }
        
        // 查看信息按钮
        btnViewInfo.setOnClickListener {
            dialog.dismiss()
            showFriendInfoDialog(request)
        }
        
        // 同意按钮
        btnAccept.setOnClickListener {
            dialog.dismiss()
            acceptFriendRequest(request)
        }
        
        // 拒绝按钮
        btnReject.setOnClickListener {
            dialog.dismiss()
            rejectFriendRequest(request)
        }
        
        dialog.show()
    }
    
    /**
     * 显示好友信息对话框
     */
    private fun showFriendInfoDialog(request: FriendRequestManager.FriendRequest) {
        // 尝试获取用户信息
        CoroutineScope(Dispatchers.IO + coroutineExceptionHandler).launch {
            try {
                // 初始化 CustomerServiceNetwork
                val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                    ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                CustomerServiceNetwork.initialize(customerServiceUrl)
                
                val apiService = CustomerServiceNetwork.getApiService()
                var profile: UserProfile? = null
                
                if (apiService != null) {
                    val response = apiService.getProfile(request.senderImei)
                    if (response.isSuccessful && response.body()?.success == true) {
                        profile = response.body()?.profile
                    }
                }
                
                // 在主线程显示对话框
                withContext(Dispatchers.Main) {
                    val dialogView = layoutInflater.inflate(R.layout.dialog_friend_info, null)
                    val tvName = dialogView.findViewById<android.widget.TextView>(R.id.tvName)
                    val tvImei = dialogView.findViewById<android.widget.TextView>(R.id.tvImei)
                    val tvGender = dialogView.findViewById<android.widget.TextView>(R.id.tvGender)
                    val tvAddress = dialogView.findViewById<android.widget.TextView>(R.id.tvAddress)
                    val tvPhone = dialogView.findViewById<android.widget.TextView>(R.id.tvPhone)
                    val tvBirthday = dialogView.findViewById<android.widget.TextView>(R.id.tvBirthday)
                    val tvPreferences = dialogView.findViewById<android.widget.TextView>(R.id.tvPreferences)
                    val btnClose = dialogView.findViewById<android.widget.ImageButton>(R.id.btnClose)
                    
                    // 设置信息
                    tvName.text = profile?.name ?: request.senderName ?: "用户"
                    tvImei.text = "IMEI: ${request.senderImei}"
                    tvGender.text = profile?.gender ?: getString(R.string.not_filled)
                    tvAddress.text = profile?.address ?: getString(R.string.not_filled)
                    tvPhone.text = profile?.phone ?: getString(R.string.not_filled)
                    tvBirthday.text = profile?.birthday ?: getString(R.string.not_filled)
                    tvPreferences.text = profile?.preferences ?: getString(R.string.not_filled)
                    
                    val dialog = android.app.AlertDialog.Builder(this@MainActivity)
                        .setView(dialogView)
                        .create()
                    
                    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
                    
                    val cornerRadiusPx = (20 * resources.displayMetrics.density).toInt()
                    dialogView.outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx.toFloat())
                        }
                    }
                    dialogView.clipToOutline = true
                    
                    btnClose.setOnClickListener {
                        dialog.dismiss()
                    }
                    
                    dialog.show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "获取好友信息失败: ${e.message}", e)
                // 如果获取失败，只显示IMEI
                withContext(Dispatchers.Main) {
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("用户信息")
                        .setMessage("IMEI: ${request.senderImei}")
                        .setPositiveButton("确定", null)
                        .show()
                }
            }
        }
    }
    
    /**
     * 同意好友申请
     */
    private fun acceptFriendRequest(request: FriendRequestManager.FriendRequest) {
        CoroutineScope(Dispatchers.IO + coroutineExceptionHandler).launch {
            try {
                val prefs = getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                    ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                
                CustomerServiceNetwork.initialize(customerServiceUrl)
                val apiService = CustomerServiceNetwork.getApiService()
                
                if (apiService == null) {
                    android.widget.Toast.makeText(this@MainActivity, "无法连接到服务器", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                val currentImei = ProfileManager.getOrGenerateImei(this@MainActivity)
                val response = apiService.addFriend(AddFriendRequest(request.senderImei, currentImei))
                
                if (response.isSuccessful && response.body()?.success == true) {
                    // 更新好友申请状态
                    FriendRequestManager.updateRequestStatus(this@MainActivity, request.senderImei, "accepted")
                    
                    // 添加好友到本地
                    val friend = Friend(
                        imei = request.senderImei,
                        nickname = null,
                        avatar = null,
                        status = "accepted",
                        addedAt = System.currentTimeMillis()
                    )
                    FriendManager.addFriend(this@MainActivity, friend)
                    
                    // 记录好友关系的发起方（对方发起的）
                    val friendPrefs = getSharedPreferences("friends_prefs", android.content.Context.MODE_PRIVATE)
                    val initiatorJson = friendPrefs.getString("friend_initiator", null)
                    val initiatorMap = if (initiatorJson != null) {
                        try {
                            val type = com.google.gson.reflect.TypeToken.getParameterized(
                                MutableMap::class.java,
                                String::class.java,
                                String::class.java
                            ).type
                            com.google.gson.Gson().fromJson<MutableMap<String, String>>(initiatorJson, type) ?: mutableMapOf()
                        } catch (e: Exception) {
                            mutableMapOf()
                        }
                    } else {
                        mutableMapOf()
                    }
                    initiatorMap[request.senderImei] = "received"
                    friendPrefs.edit().putString("friend_initiator", com.google.gson.Gson().toJson(initiatorMap)).apply()
                    
                    // 刷新对话列表和好友列表
                    refreshConversationList()
                    refreshFriendFragmentBadge()
                    
                    android.widget.Toast.makeText(this@MainActivity, "已同意好友申请", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val errorMsg = response.body()?.message ?: "同意失败"
                    android.widget.Toast.makeText(this@MainActivity, errorMsg, android.widget.Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "同意好友申请失败: ${e.message}", e)
                android.widget.Toast.makeText(this@MainActivity, "同意好友申请失败: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 拒绝好友申请
     */
    private fun rejectFriendRequest(request: FriendRequestManager.FriendRequest) {
        // 更新好友申请状态
        FriendRequestManager.updateRequestStatus(this@MainActivity, request.senderImei, "rejected")
        
        // 刷新好友Fragment的红点显示
        refreshFriendFragmentBadge()
        
        android.widget.Toast.makeText(this, "已拒绝好友申请", android.widget.Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 刷新好友Fragment的红点显示
     */
    private fun refreshFriendFragmentBadge() {
        try {
            val fragmentManager = supportFragmentManager
            val friendFragment = fragmentManager.fragments.find { it is FriendFragment } as? FriendFragment
            friendFragment?.updateBadge()
        } catch (e: Exception) {
            Log.w(TAG, "刷新好友Fragment红点失败: ${e.message}")
        }
    }
    
    /**
     * 刷新对话列表
     */
    private fun refreshConversationList() {
        try {
            findConversationListFragment()?.loadConversations()
        } catch (e: Exception) {
            Log.w(TAG, "刷新对话列表失败: ${e.message}")
        }
    }
    
    /**
     * 更新端云互发（我的电脑）对话列表的最新消息
     */
    /**
     * 会话外收到消息：写入会话缩略图并可选增加未读（端云、与 ChatFragment 离屏保存等共用）。
     */
    fun notifyInboundSessionMessage(conversationId: String, preview: String, incrementUnread: Boolean) {
        runOnUiThread {
            val prefs = getSharedPreferences("conversations", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("${conversationId}_last_message", preview)
                .putLong("${conversationId}_last_time", System.currentTimeMillis())
                .apply()
            try {
                val conversationListFragment = supportFragmentManager.fragments.find { it is ConversationListFragment } as? ConversationListFragment
                conversationListFragment?.updateLastMessage(conversationId, preview)
                Log.d(TAG, "会话列表缩略图已更新: $conversationId -> $preview")
            } catch (e: Exception) {
                Log.e(TAG, "更新会话列表失败: ${e.message}", e)
            }
            if (incrementUnread) {
                ConversationSessionNotifier.incrementUnread(this, conversationId, 1)
            }
        }
    }
    
    /**
     * 更新人工客服对话列表的最新消息
     * 当在聊天页面外收到消息时调用，用于更新对话列表显示
     */
    private fun updateCustomerServiceConversationLastMessage(message: String) {
        val conversationId = ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE
        
        // 保存到SharedPreferences
        val prefs = getSharedPreferences("conversations", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putString("${conversationId}_last_message", message)
            .putLong("${conversationId}_last_time", System.currentTimeMillis())
            .apply()
        
        // 通知对话列表Fragment更新（如果存在）
        try {
            val fragmentManager = supportFragmentManager
            val conversationListFragment = fragmentManager.fragments.find { it is ConversationListFragment } as? ConversationListFragment
            conversationListFragment?.updateLastMessage(conversationId, message)
            Log.d(TAG, "已更新对话列表最新消息: $message")
        } catch (e: Exception) {
            Log.w(TAG, "更新对话列表失败: ${e.message}")
        }
    }
    
    /**
     * 导航到人工客服聊天页面
     */
    private fun navigateToCustomerServiceChat() {
        Log.d(TAG, "导航到人工客服聊天页面")
        try {
            // 创建人工客服对话
            val conversation = Conversation(
                id = ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE,
                name = "人工客服",
                avatar = null,
                lastMessage = null,
                lastMessageTime = System.currentTimeMillis(),
                isPinned = false
            )
            
            hideActionBarWithoutAnimation()
            switchToChatFragment(conversation)
                
            // 清除未读消息计数
            CustomerServiceUnreadManager.clearUnreadCount(this)
        } catch (e: Exception) {
            Log.e(TAG, "导航到人工客服聊天页面失败: ${e.message}", e)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        PcOnlineStatusManager.stopChecking()
        locationMonitorManager?.stop()
        locationMonitorManager = null
        tabFragments.clear()
        stopHotSkillCheckTask()
        // 释放SparkChain SDK资源
        try {
            SparkChain.getInst().unInit()
            Log.d(TAG, "SparkChain SDK已释放")
        } catch (e: Exception) {
            Log.e(TAG, "释放SparkChain SDK异常: ${e.message}", e)
        }
        
        // 释放WebSocket资源
        customerServiceWebSocket?.release()
        customerServiceWebSocket = null
        
        // 取消注册问字按钮Broadcast接收器
        unregisterQuestionButtonReceiver()
        
        // 取消注册通知栏按钮Broadcast接收器
        notificationActionReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
                notificationActionReceiver = null
                Log.d(TAG, "通知栏按钮Broadcast接收器已取消注册")
            } catch (e: Exception) {
                Log.e(TAG, "取消注册通知栏按钮Broadcast接收器失败: ${e.message}", e)
            }
        }

        // 取消注册监视通知栏事件接收器
        monitoredNotificationReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
                monitoredNotificationReceiver = null
                Log.d(TAG, "监视通知栏事件接收器已取消注册")
            } catch (e: Exception) {
                Log.e(TAG, "取消注册监视通知栏事件接收器失败: ${e.message}", e)
            }
        }
        
        // 取消注册未读消息更新Broadcast接收器
        unreadCountReceiver?.let { receiver ->
            try {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
                unreadCountReceiver = null
                Log.d(TAG, "未读消息更新Broadcast接收器已取消注册")
            } catch (e: Exception) {
                Log.e(TAG, "取消注册未读消息更新Broadcast接收器失败: ${e.message}", e)
            }
        }
        
        // 取消注册异常检测结果Broadcast接收器
        anomalyDetectionReceiver?.let { receiver ->
            try {
                LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
                anomalyDetectionReceiver = null
                Log.d(TAG, "异常检测结果Broadcast接收器已取消注册")
            } catch (e: Exception) {
                Log.e(TAG, "取消注册异常检测结果Broadcast接收器失败: ${e.message}", e)
            }
        }
        
        // 停止聊天截图服务
        val intent = Intent(this, ChatScreenshotService::class.java).apply {
            action = ChatScreenshotService.ACTION_STOP
        }
        stopService(intent)
        
        // 清理交互覆盖层
        InteractionOverlayManager.cleanup()
        
        // 清理边框特效
        BorderEffectOverlayManager.cleanup()
        
        // 清理任务指示器悬浮窗
        TaskIndicatorOverlayManager.cleanup()
        
        // 移除无障碍服务状态监听器
        try {
            accessibilityStateListener?.let {
                val accessibilityManager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
                AccessibilityManagerCompat.removeAccessibilityStateChangeListener(accessibilityManager, it)
            }
            accessibilityStateListener = null
        } catch (e: Exception) {
            Log.e(TAG, "移除无障碍服务状态监听器失败: ${e.message}", e)
        }
        
        // 移除双击返回键退出应用的回调
        exitBackPressedCallback?.remove()
        exitBackPressedCallback = null
    }
    
    override fun onCreateOptionsMenu(menu: android.view.Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        // 只在对话列表页面显示搜索按钮
        val currentFragment = getActiveFragment()
        val showSearch = currentFragment is ConversationListFragment
        menu?.findItem(R.id.menu_search)?.isVisible = showSearch
        return true
    }
    
    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_search -> {
                // 显示搜索对话框
                showSearchDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    /**
     * 显示搜索对话框（供Fragment调用）
     */
    fun showSearchDialog() {
        // 创建固定对话列表
        val fixedConversations = listOf(
            Conversation(
                id = ConversationListFragment.CONVERSATION_ID_ASSISTANT,
                name = ChatConstants.ASSISTANT_DISPLAY_NAME,
                avatar = null,
                lastMessage = null,
                lastMessageTime = System.currentTimeMillis()
            ),
            Conversation(
                id = ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING,
                name = "技能学习小助手",
                avatar = null,
                lastMessage = null,
                lastMessageTime = System.currentTimeMillis()
            ),
            Conversation(
                id = ConversationListFragment.CONVERSATION_ID_CHAT_ASSISTANT,
                name = "聊天小助手",
                avatar = null,
                lastMessage = null,
                lastMessageTime = System.currentTimeMillis()
            ),
            Conversation(
                id = ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE,
                name = "人工客服",
                avatar = null,
                lastMessage = null,
                lastMessageTime = System.currentTimeMillis()
            )
        )
        
        // 获取所有好友列表并转换为Conversation对象
        val friends = FriendManager.getFriends(this)
            .filter { it.status == "accepted" } // 只显示已接受的好友
        val friendConversations = friends.map { friend ->
            Conversation(
                id = "friend_${friend.imei}",
                name = friend.nickname ?: friend.imei,
                avatar = friend.avatar,
                lastMessage = null,
                lastMessageTime = friend.addedAt
            )
        }
        
        // 合并固定对话和好友对话
        val allConversations = fixedConversations + friendConversations
        
        // 创建对话框布局
        val dialogView = layoutInflater.inflate(R.layout.dialog_search_friends, null)
        val etSearchInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etSearchInput)
        val rvSearchResults = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvSearchResults)
        val llEmptyState = dialogView.findViewById<android.widget.LinearLayout>(R.id.llEmptyState)
        val btnClose = dialogView.findViewById<android.widget.ImageButton>(R.id.btnClose)
        
        // 设置RecyclerView
        rvSearchResults.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        val searchAdapter = SearchConversationAdapter(allConversations) { conversation ->
            // 点击搜索结果，使用复用机制导航到对应的聊天页面
            switchToChatFragment(conversation)
            
            // 关闭对话框
            searchDialog?.dismiss()
        }
        rvSearchResults.adapter = searchAdapter
        
        // 初始状态：不显示结果（因为搜索框为空）
        rvSearchResults.visibility = android.view.View.GONE
        
        // 监听搜索输入
        etSearchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                if (query.isEmpty()) {
                    // 搜索框为空，隐藏结果列表和空状态
                    rvSearchResults.visibility = android.view.View.GONE
                    llEmptyState?.visibility = android.view.View.GONE
                    searchAdapter.submitList(emptyList())
                } else {
                    // 根据输入过滤对话列表（支持按名称或IMEI搜索）
                    val filtered = allConversations.filter { conversation ->
                        // 搜索名称
                        val nameMatch = conversation.name.contains(query, ignoreCase = true)
                        // 如果是好友对话，也搜索IMEI（从id中提取）
                        val imeiMatch = if (conversation.id.startsWith("friend_")) {
                            val imei = conversation.id.removePrefix("friend_")
                            imei.contains(query, ignoreCase = true)
                        } else {
                            false
                        }
                        nameMatch || imeiMatch
                    }
                    if (filtered.isEmpty()) {
                        // 没有搜索结果，显示空状态
                        rvSearchResults.visibility = android.view.View.GONE
                        llEmptyState?.visibility = android.view.View.VISIBLE
                    } else {
                        // 有搜索结果，显示结果列表
                        rvSearchResults.visibility = android.view.View.VISIBLE
                        llEmptyState?.visibility = android.view.View.GONE
                        searchAdapter.submitList(filtered)
                    }
                }
            }
            
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        
        // 创建对话框
        val dialog = android.app.AlertDialog.Builder(this)
            .setView(dialogView)
            .create()
        
        // 设置窗口背景透明以显示圆角
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        // 设置圆角outline以正确裁剪
        val cornerRadiusPx = (20 * resources.displayMetrics.density).toInt()
        dialogView.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx.toFloat())
            }
        }
        dialogView.clipToOutline = true
        
        // 设置关闭按钮点击事件
        btnClose?.setOnClickListener {
            dialog.dismiss()
        }
        
        searchDialog = dialog
        dialog.show()
        
        // 自动聚焦搜索框
        etSearchInput.requestFocus()
        // 显示软键盘
        val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(etSearchInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }
    
    private var searchDialog: android.app.AlertDialog? = null
    
    /**
     * 搜索对话适配器
     */
    private class SearchConversationAdapter(
        private val allConversations: List<Conversation>,
        private val onItemClick: (Conversation) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<SearchConversationAdapter.ViewHolder>() {
        
        private var filteredList = listOf<Conversation>()
        
        fun submitList(list: List<Conversation>) {
            filteredList = list
            notifyDataSetChanged()
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = com.cloudcontrol.demo.databinding.ItemConversationBinding.inflate(
                android.view.LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(filteredList[position])
        }
        
        override fun getItemCount() = filteredList.size
        
        inner class ViewHolder(
            private val binding: com.cloudcontrol.demo.databinding.ItemConversationBinding
        ) : androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root) {
            
            fun bind(conversation: Conversation) {
                binding.tvName.text = DisplayNameHelper.getDisplayName(binding.root.context, conversation.name)
                binding.tvLastMessage.text = ""  // 搜索结果显示中不显示最后消息
                binding.tvTime.text = ""  // 搜索结果显示中不显示时间
                
                // 设置头像
                when (conversation.id) {
                    ConversationListFragment.CONVERSATION_ID_ASSISTANT -> {
                        binding.ivAvatar.setImageResource(R.drawable.ic_assistant_avatar)
                    }
                    ConversationListFragment.CONVERSATION_ID_SKILL_LEARNING -> {
                        binding.ivAvatar.setImageResource(R.drawable.ic_skill_learning_avatar)
                    }
                    ConversationListFragment.CONVERSATION_ID_CHAT_ASSISTANT -> {
                        binding.ivAvatar.setImageResource(R.drawable.ic_chat_assistant_avatar)
                    }
                    ConversationListFragment.CONVERSATION_ID_CUSTOMER_SERVICE -> {
                        binding.ivAvatar.setImageResource(R.drawable.ic_customer_service_avatar)
                    }
                    ConversationListFragment.CONVERSATION_ID_GROUP -> {
                        // 群头像：先显示默认头像，异步加载拼接头像
                        binding.ivAvatar.setImageResource(R.drawable.ic_system_avatar)
                        // 异步加载群头像，避免阻塞UI
                        val avatarSize = (56 * binding.root.resources.displayMetrics.density).toInt()
                        val context = binding.root.context
                        val currentPosition = adapterPosition
                        CoroutineScope(Dispatchers.IO).launch {
                            val groupAvatar = GroupAvatarHelper.createFriendsGroupAvatar(context, avatarSize)
                            // 切换到主线程更新UI
                            withContext(Dispatchers.Main) {
                                if (currentPosition != androidx.recyclerview.widget.RecyclerView.NO_POSITION && adapterPosition == currentPosition) {
                                    binding.ivAvatar.setImageBitmap(groupAvatar)
                                }
                            }
                        }
                    }
                    else -> {
                        if (CustomAssistantManager.isCustomAssistantId(conversation.id)) {
                            val assistant = CustomAssistantManager.getById(binding.root.context, conversation.id)
                            if (assistant != null) {
                                val avatarSize = (56 * binding.root.resources.displayMetrics.density).toInt()
                                AvatarCacheManager.loadCustomAssistantAvatar(
                                    context = binding.root.context,
                                    imageView = binding.ivAvatar,
                                    assistant = assistant,
                                    cacheKey = "search_${conversation.id}",
                                    validationTag = conversation.id,
                                    sizePx = avatarSize
                                )
                            } else {
                                binding.ivAvatar.setImageResource(R.drawable.ic_system_avatar)
                            }
                        } else {
                            binding.ivAvatar.setImageResource(R.drawable.ic_system_avatar)
                        }
                    }
                }
                
                // 点击事件
                binding.root.setOnClickListener {
                    onItemClick(conversation)
                }
            }
        }
    }
    
    private fun sendGuiTaskProbeResponseDirect(
        webSocket: CustomerServiceWebSocket,
        requestId: String,
        protocol: String,
        conversationId: String,
        ok: Boolean,
        error: String?
    ) {
        try {
            val now = System.currentTimeMillis()
            val ack = JSONObject().apply {
                put("type", "mobile_tool_ack")
                put("protocol", protocol)
                put("request_id", requestId)
                put("conversation_id", conversationId)
                put("timestamp", now)
                put(
                    "payload",
                    JSONObject().apply {
                        put("accepted", ok)
                        put("queue_position", 0)
                    }
                )
            }
            webSocket.sendCustomMessage(ack)

            val resultPayload = JSONObject().apply {
                put("ok", ok)
                put("tool", "device.gui_task_probe")
                put(
                    "data",
                    JSONObject().apply {
                        put("ready", ok)
                        put("checked_at", now)
                    }
                )
                if (ok) {
                    put("error", JSONObject.NULL)
                } else {
                    put(
                        "error",
                        JSONObject().apply {
                            put("code", "PROBE_FAILED")
                            put("message", error ?: "gui task probe failed")
                            put("retryable", true)
                        }
                    )
                }
            }
            val result = JSONObject().apply {
                put("type", "mobile_tool_result")
                put("protocol", protocol)
                put("request_id", requestId)
                put("conversation_id", conversationId)
                put("timestamp", now)
                put("payload", resultPayload)
            }
            webSocket.sendCustomMessage(result)

            // 额外兜底：通过 cross_device_message 再回传一次，规避 relay 分支差异。
            val fallback = JSONObject().apply {
                put("type", "cross_device_message")
                put("message_type", "gui_task_probe_ack")
                put(
                    "content",
                    JSONObject().apply {
                        put("request_id", requestId)
                        put("ok", ok)
                        if (!error.isNullOrBlank()) put("error", error)
                    }.toString()
                )
                put("timestamp", now)
            }
            webSocket.sendCustomMessage(fallback)
        } catch (e: Exception) {
            Log.e(TAG, "sendGuiTaskProbeResponseDirect 失败: ${e.message}", e)
        }
    }

    /**
     * 测试端侧异常检测模型是否加载成功
     * 应用启动时自动调用
     */
    private fun testAnomalyDetectionModels() {
        testScope.launch {
            try {
                Log.i(TAG, "========== 开始测试端侧模型 ==========")
                Toast.makeText(this@MainActivity, "正在测试端侧模型...", Toast.LENGTH_SHORT).show()
                
                val manager = AnomalyDetectionManager.getInstance(this@MainActivity)
                
                // 等待一小段时间确保应用完全启动
                delay(500)
                
                // 初始化模型
                manager.initialize()
                
                // 等待模型加载完成
                delay(1000)
                
                // 检查模型状态
                val (detectionLoaded, classificationLoaded) = manager.getModelStatus()
                
                // 输出测试结果
                val result = StringBuilder()
                result.append("========== 端侧模型测试结果 ==========\n")
                result.append("检测模型: ${if (detectionLoaded) "✓ 已加载" else "✗ 未加载"}\n")
                result.append("分类模型: ${if (classificationLoaded) "✓ 已加载" else "✗ 未加载"}\n")
                result.append("=====================================")
                
                Log.i(TAG, result.toString())
                
                // 显示Toast提示
                val message = if (detectionLoaded && classificationLoaded) {
                    "✓ 端侧模型加载成功！"
                } else {
                    "✗ 端侧模型加载失败，请查看日志"
                }
                
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "测试端侧模型时出错: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "✗ 模型测试失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
    /**
     * 检查版本更新
     * 在任务启动时调用，如果发现新版本则显示更新提示弹窗
     */
    private fun checkVersionUpdate() {
        testScope.launch {
            try {
                val currentVersion = com.cloudcontrol.demo.BuildConfig.VERSION_NAME
                Log.d(TAG, "开始检查版本更新，当前版本: $currentVersion")
                
                // 初始化 CustomerServiceNetwork
                val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                    ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                CustomerServiceNetwork.initialize(customerServiceUrl)
                
                val apiService = CustomerServiceNetwork.getApiService()
                if (apiService == null) {
                    Log.w(TAG, "CustomerServiceNetwork未初始化，跳过版本检查")
                    return@launch
                }
                
                // 调用版本检查API
                val response = withContext(Dispatchers.IO) {
                    apiService.checkVersion(currentVersion)
                }
                
                if (response.isSuccessful && response.body()?.success == true) {
                    val versionInfo = response.body()!!
                    val latestVersion = versionInfo.latest_version
                    val hasUpdate = versionInfo.has_update
                    val forceUpdate = versionInfo.force_update
                    val updateMessage = versionInfo.update_message
                    val updateUrl = versionInfo.update_url
                    
                    Log.d(TAG, "版本检查结果: currentVersion=$currentVersion, latestVersion=$latestVersion, hasUpdate=$hasUpdate, forceUpdate=$forceUpdate")
                    
                    // 如果发现新版本，显示更新提示弹窗
                    if (hasUpdate) {
                        withContext(Dispatchers.Main) {
                            VersionUpdateOverlayManager.show(
                                context = this@MainActivity,
                                currentVersion = currentVersion,
                                latestVersion = latestVersion,
                                updateMessage = updateMessage,
                                updateUrl = updateUrl,
                                forceUpdate = forceUpdate,
                                onUpdate = {
                                    Log.d(TAG, "用户选择立即更新")
                                },
                                onSkip = {
                                    Log.d(TAG, "用户选择暂不更新")
                                }
                            )
                        }
                    } else {
                        Log.d(TAG, "当前版本已是最新版本")
                    }
                } else {
                    Log.w(TAG, "版本检查API调用失败: ${response.code()} - ${response.message()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "检查版本更新失败: ${e.message}", e)
                // 版本检查失败不影响任务执行，只记录日志
            }
        }
    }
    
    /**
     * 应用启动时检查版本更新
     * 延迟执行，避免阻塞应用启动
     */
    private fun checkVersionUpdateOnStartup() {
        testScope.launch {
            try {
                // 延迟2秒执行，确保应用完全启动后再检查版本
                delay(2000)
                
                val currentVersion = com.cloudcontrol.demo.BuildConfig.VERSION_NAME
                Log.d(TAG, "应用启动时检查版本更新，当前版本: $currentVersion")
                
                // 初始化 CustomerServiceNetwork
                val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                    ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                CustomerServiceNetwork.initialize(customerServiceUrl)
                
                val apiService = CustomerServiceNetwork.getApiService()
                if (apiService == null) {
                    Log.w(TAG, "CustomerServiceNetwork未初始化，跳过版本检查")
                    return@launch
                }
                
                // 调用版本检查API
                val response = withContext(Dispatchers.IO) {
                    apiService.checkVersion(currentVersion)
                }
                
                if (response.isSuccessful && response.body()?.success == true) {
                    val versionInfo = response.body()!!
                    val latestVersion = versionInfo.latest_version
                    val hasUpdate = versionInfo.has_update
                    val forceUpdate = versionInfo.force_update
                    val updateMessage = versionInfo.update_message
                    val updateUrl = versionInfo.update_url
                    
                    Log.d(TAG, "应用启动版本检查结果: currentVersion=$currentVersion, latestVersion=$latestVersion, hasUpdate=$hasUpdate, forceUpdate=$forceUpdate")
                    
                    // 如果发现新版本，显示更新提示弹窗
                    if (hasUpdate) {
                        withContext(Dispatchers.Main) {
                            VersionUpdateOverlayManager.show(
                                context = this@MainActivity,
                                currentVersion = currentVersion,
                                latestVersion = latestVersion,
                                updateMessage = updateMessage,
                                updateUrl = updateUrl,
                                forceUpdate = forceUpdate,
                                onUpdate = {
                                    Log.d(TAG, "用户选择立即更新（应用启动时）")
                                },
                                onSkip = {
                                    Log.d(TAG, "用户选择暂不更新（应用启动时）")
                                }
                            )
                        }
                    } else {
                        Log.d(TAG, "应用启动时检查：当前版本已是最新版本")
                    }
                } else {
                    Log.w(TAG, "应用启动时版本检查API调用失败: ${response.code()} - ${response.message()}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "应用启动时检查版本更新失败: ${e.message}", e)
                // 版本检查失败不影响应用启动，只记录日志
            }
        }
    }
}

