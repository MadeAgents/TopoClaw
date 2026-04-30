package com.cloudcontrol.demo

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*

/**
 * 剪切板监视管理器
 * 负责在任务执行期间监视剪切板变化，并将内容上传到云侧
 */
object ClipboardMonitorManager {
    private const val TAG = "ClipboardMonitor"
    
    private var clipboardManager: ClipboardManager? = null
    private var isMonitoring = false
    private var lastContentHash: String? = null
    private var currentUuid: String? = null
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private val monitorScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // 防抖：1秒内只上传一次
    private var lastUploadTime = 0L
    private val DEBOUNCE_INTERVAL = 1000L // 1秒
    
    /**
     * 检查是否启用了剪切板监视
     */
    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("clipboard_monitor_enabled", false)
    }
    
    /**
     * 开始监视剪切板
     * @param context 上下文
     * @param uuid 当前任务的UUID
     */
    fun startMonitoring(context: Context, uuid: String) {
        // 检查是否启用
        if (!isEnabled(context)) {
            Log.d(TAG, "剪切板监视未启用，跳过启动")
            return
        }
        
        // 如果已经在监视，先停止
        if (isMonitoring) {
            Log.d(TAG, "剪切板监视已在运行，先停止")
            stopMonitoring(context)
        }
        
        try {
            clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (clipboardManager == null) {
                Log.e(TAG, "无法获取ClipboardManager服务")
                return
            }
            
            currentUuid = uuid
            isMonitoring = true
            lastContentHash = null
            lastUploadTime = 0L
            
            // 创建监听器
            clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
                handleClipboardChange(context, uuid)
            }
            
            // 注册监听器
            clipboardManager?.addPrimaryClipChangedListener(clipboardListener!!)
            
            Log.d(TAG, "剪切板监视已启动，uuid=$uuid")
        } catch (e: Exception) {
            Log.e(TAG, "启动剪切板监视失败: ${e.message}", e)
            isMonitoring = false
            currentUuid = null
        }
    }
    
    /**
     * 停止监视剪切板
     */
    fun stopMonitoring(context: Context) {
        if (!isMonitoring) {
            return
        }
        
        try {
            clipboardListener?.let { listener ->
                clipboardManager?.removePrimaryClipChangedListener(listener)
            }
            
            clipboardListener = null
            clipboardManager = null
            isMonitoring = false
            currentUuid = null
            lastContentHash = null
            
            Log.d(TAG, "剪切板监视已停止")
        } catch (e: Exception) {
            Log.e(TAG, "停止剪切板监视失败: ${e.message}", e)
        }
    }
    
    /**
     * 处理剪切板变化
     */
    private fun handleClipboardChange(context: Context, uuid: String) {
        if (!isMonitoring || currentUuid != uuid) {
            return
        }
        
        try {
            val clipboard = clipboardManager ?: return
            val clip = clipboard.primaryClip ?: return
            
            if (clip.itemCount == 0) {
                return
            }
            
            val item = clip.getItemAt(0)
            val text = item?.text?.toString()
            
            if (text.isNullOrEmpty()) {
                return
            }
            
            // 计算内容哈希，避免重复上传相同内容
            val contentHash = calculateHash(text)
            if (contentHash == lastContentHash) {
                Log.d(TAG, "剪切板内容未变化，跳过上传")
                return
            }
            
            // 防抖：检查距离上次上传的时间
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastUploadTime < DEBOUNCE_INTERVAL) {
                Log.d(TAG, "距离上次上传时间过短，跳过上传")
                return
            }
            
            lastContentHash = contentHash
            lastUploadTime = currentTime
            
            // 在后台协程中上传
            monitorScope.launch {
                uploadClipboardContent(context, uuid, text)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理剪切板变化失败: ${e.message}", e)
        }
    }
    
    /**
     * 上传剪切板内容到云侧
     */
    private suspend fun uploadClipboardContent(context: Context, uuid: String, content: String) {
        try {
            Log.d(TAG, "开始上传剪切板内容，uuid=$uuid, 内容长度=${content.length}")
            
            val apiService = NetworkService.getApiService()
            if (apiService == null) {
                Log.e(TAG, "ApiService未初始化，无法上传剪切板内容")
                return
            }
            
            // 生成时间戳
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            
            // 调用上传接口
            val response = apiService.uploadClipboardContent(
                uuid = uuid,
                clipboardContent = content,
                clipboardTimestamp = timestamp
            )
            
            if (response.isSuccessful) {
                Log.d(TAG, "剪切板内容上传成功")
            } else {
                Log.w(TAG, "剪切板内容上传失败: code=${response.code()}, message=${response.message()}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "上传剪切板内容异常: ${e.message}", e)
        }
    }
    
    /**
     * 计算字符串的MD5哈希值
     */
    private fun calculateHash(text: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val hashBytes = md.digest(text.toByteArray())
            hashBytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // 如果MD5不可用，使用简单的哈希
            text.hashCode().toString()
        }
    }
    
    /**
     * 检查是否正在监视
     */
    fun isMonitoring(): Boolean {
        return isMonitoring
    }
}

