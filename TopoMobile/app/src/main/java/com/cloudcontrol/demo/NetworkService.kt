package com.cloudcontrol.demo

import android.content.Context
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 普通 HTTP 请求（含聊天上传/拉取指令）的 **读取超时**，与 [NetworkService.initialize] 中 OkHttp readTimeout 对应。
 * 存于 [PREFS_NAME]，默认 [DEFAULT_READ_SEC] 秒（与历史硬编码一致）。
 */
object CloudApiTimeoutPrefs {
    const val PREFS_NAME = "app_prefs"
    private const val KEY_READ_SEC = "cloud_api_read_timeout_sec"
    const val DEFAULT_READ_SEC = 120
    private const val MIN_READ_SEC = 10
    private const val MAX_READ_SEC = 600

    fun getReadTimeoutSeconds(context: Context): Int {
        val v = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_READ_SEC, DEFAULT_READ_SEC)
        return v.coerceIn(MIN_READ_SEC, MAX_READ_SEC)
    }

    fun putReadTimeoutSeconds(context: Context, seconds: Int) {
        val clamped = seconds.coerceIn(MIN_READ_SEC, MAX_READ_SEC)
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putInt(KEY_READ_SEC, clamped).apply()
    }
}

/**
 * 网络服务管理类
 * 负责创建和管理Retrofit实例
 */
object NetworkService {
    private const val TAG = "NetworkService"
    
    private var retrofit: Retrofit? = null
    private var apiService: ApiService? = null
    
    // 专门用于视频上传的Retrofit实例（无timeout限制）
    private var videoUploadRetrofit: Retrofit? = null
    private var videoUploadApiService: ApiService? = null
    
    // OkHttp客户端（用于SSE流式请求）
    private var okHttpClient: OkHttpClient? = null
    
    // 存储当前的base URL
    private var currentBaseUrl: String? = null
    
    /**
     * 初始化网络服务
     * @param baseUrl 云侧服务的地址，如 "http://192.168.1.100:8000"
     * @param context 若提供则从偏好读取「云侧 API 读取超时」；否则使用 [CloudApiTimeoutPrefs.DEFAULT_READ_SEC]
     */
    fun initialize(baseUrl: String, context: Context? = null) {
        // 确保URL以/结尾
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        
        // 保存当前的base URL
        currentBaseUrl = url

        val readSec = context?.let { CloudApiTimeoutPrefs.getReadTimeoutSeconds(it) }
            ?: CloudApiTimeoutPrefs.DEFAULT_READ_SEC
        
        // 创建日志拦截器（用于调试）
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        
        // 创建普通OkHttp客户端（用于常规请求）
        val client = OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)  // 连接超时120秒
            .readTimeout(readSec.toLong(), TimeUnit.SECONDS)    // 读取超时（等云侧响应体），可配置
            .writeTimeout(120, TimeUnit.SECONDS)    // 写入超时120秒
            .addInterceptor(loggingInterceptor)
            .build()
        
        // 创建专门用于SSE流式请求的OkHttp客户端（readTimeout无限制）
        val sseClient = OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS)  // 连接超时120秒
            .readTimeout(0, TimeUnit.SECONDS)       // 读取超时无限制（SSE流式响应需要长时间保持连接）
            .writeTimeout(120, TimeUnit.SECONDS)    // 写入超时120秒
            .addInterceptor(loggingInterceptor)
            .build()
        
        // 保存SSE专用的OkHttp客户端
        okHttpClient = sseClient
        
        // 创建视频上传专用的OkHttp客户端（无timeout限制，用于视频上传和解析）
        val videoUploadClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)  // 连接超时30秒（视频文件较大，需要更长的连接时间）
            .readTimeout(0, TimeUnit.SECONDS)       // 读取超时无限制（视频解析可能需要很长时间）
            .writeTimeout(0, TimeUnit.SECONDS)     // 写入超时无限制（视频上传可能需要很长时间）
            .addInterceptor(loggingInterceptor)
            .build()
        
        // 创建Gson转换器
        val gson: Gson = GsonBuilder()
            .setLenient()
            .create()
        
        // 创建普通Retrofit实例
        retrofit = Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        
        // 创建视频上传专用的Retrofit实例
        videoUploadRetrofit = Retrofit.Builder()
            .baseUrl(url)
            .client(videoUploadClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        
        // 创建API服务
        apiService = retrofit?.create(ApiService::class.java)
        videoUploadApiService = videoUploadRetrofit?.create(ApiService::class.java)
        
        Log.d(TAG, "网络服务初始化完成，Base URL: $url")
        Log.d(TAG, "普通请求timeout配置: connectTimeout=120s, readTimeout=${readSec}s, writeTimeout=120s")
        Log.d(TAG, "视频上传服务已配置（无timeout限制）")
    }
    
    /**
     * 获取API服务实例（用于常规请求）
     */
    fun getApiService(): ApiService? {
        return apiService
    }
    
    /**
     * 获取视频上传专用的API服务实例（无timeout限制）
     */
    fun getVideoUploadApiService(): ApiService? {
        return videoUploadApiService
    }
    
    /**
     * 检查服务是否已初始化
     */
    fun isInitialized(): Boolean {
        return apiService != null
    }
    
    /**
     * 获取当前的base URL
     * @return 当前的base URL，如果未初始化则返回null
     */
    fun getCurrentBaseUrl(): String? {
        return currentBaseUrl
    }
    
    /**
     * 获取OkHttp客户端（用于SSE流式请求）
     */
    fun getOkHttpClient(): OkHttpClient? {
        return okHttpClient
    }
    
    /**
     * 发送SSE流式请求到/upload接口
     * @param params 请求参数
     * @return Flow<SSEEvent> SSE事件流
     */
    fun sendStreamingRequest(
        params: Map<String, String>
    ): Flow<SSEEvent> = flow {
        val client = okHttpClient ?: run {
            Log.e(TAG, "OkHttp客户端未初始化")
            emit(SSEEvent.Error("网络服务未初始化"))
            return@flow
        }
        
        val baseUrl = currentBaseUrl ?: run {
            Log.e(TAG, "Base URL未设置")
            emit(SSEEvent.Error("服务器地址未设置"))
            return@flow
        }
        
        // 构建请求体（FormUrlEncoded）
        val formBody = FormBody.Builder().apply {
            params.forEach { (key, value) ->
                add(key, value)
            }
        }.build()
        
        // 构建请求
        val request = Request.Builder()
            .url("${baseUrl}upload")
            .post(formBody)
            .addHeader("Accept", "text/event-stream")
            .build()
        
        try {
            Log.d(TAG, "发送SSE请求: ${baseUrl}upload")
            val response = client.newCall(request).execute()
            Log.d(TAG, "收到SSE响应，状态码: ${response.code}, 成功: ${response.isSuccessful}")
            
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "未知错误"
                Log.e(TAG, "SSE请求失败: ${response.code} - $errorBody")
                emit(SSEEvent.Error("请求失败: ${response.code} - $errorBody"))
                return@flow
            }
            
            // 读取SSE流
            val body = response.body ?: run {
                Log.e(TAG, "响应体为空")
                emit(SSEEvent.Error("响应体为空"))
                return@flow
            }
            
            val reader = BufferedReader(body.charStream())
            var line: String?
            var currentEventData: String? = null
            
            try {
                var lineCount = 0
                while (reader.readLine().also { line = it } != null) {
                    lineCount++
                    line?.let { currentLine ->
                        Log.d(TAG, "SSE读取第${lineCount}行: ${currentLine.take(100)}")
                        if (currentLine.startsWith("data: ")) {
                            // 开始新的事件，保存之前的事件数据（如果有）
                            if (currentEventData != null) {
                                Log.d(TAG, "处理之前的事件数据: ${currentEventData!!.take(100)}")
                                processSSEData(currentEventData!!, this@flow)
                                currentEventData = null
                            }
                            // 提取data后的内容
                            val newEventData = currentLine.substring(6) // 移除 "data: " 前缀
                            currentEventData = newEventData
                            Log.d(TAG, "新事件数据开始: ${newEventData.take(100)}")
                        } else if (currentLine.isEmpty() && currentEventData != null) {
                            // 空行表示事件结束，处理当前事件数据
                            val dataToProcess = currentEventData!!
                            Log.d(TAG, "遇到空行，开始处理事件数据，长度: ${dataToProcess.length}")
                            Log.d(TAG, "完整事件数据: $dataToProcess")
                            processSSEData(dataToProcess, this@flow)
                            Log.d(TAG, "事件数据处理完成")
                            currentEventData = null
                        } else if (currentLine.isNotEmpty() && currentEventData != null) {
                            // 多行数据，追加到当前事件
                            val existingData = currentEventData
                            currentEventData = existingData + "\n" + currentLine
                        } else {
                            // 其他情况，忽略
                        }
                    }
                }
                
                Log.d(TAG, "SSE流读取完成，共读取${lineCount}行")
                
                // 处理最后一个事件（如果没有空行结尾）
                if (currentEventData != null) {
                    Log.d(TAG, "处理最后一个事件数据: ${currentEventData!!.take(100)}")
                    processSSEData(currentEventData!!, this@flow)
                } else {
                    Log.d(TAG, "没有未处理的事件数据")
                }
            } finally {
                reader.close()
                Log.d(TAG, "SSE流读取器已关闭")
            }
        } catch (e: Exception) {
            Log.e(TAG, "SSE请求异常: ${e.message}", e)
            emit(SSEEvent.Error("请求异常: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)  // 确保在IO线程中执行阻塞的网络操作
    
    /**
     * 处理SSE数据
     */
    private suspend fun processSSEData(jsonData: String, collector: FlowCollector<SSEEvent>) {
        Log.d(TAG, "processSSEData开始，数据长度: ${jsonData.length}")
        try {
            val jsonObject = Gson().fromJson(jsonData, Map::class.java) as Map<*, *>
            val status = jsonObject["status"] as? String
            Log.d(TAG, "解析得到status: $status")
            
            when (status) {
                "searching" -> {
                    val message = jsonObject["message"] as? String ?: "正在联网搜索..."
                    Log.d(TAG, "SSE事件: searching - $message")
                    collector.emit(SSEEvent.StatusUpdate("searching", message))
                    Log.d(TAG, "searching事件已emit")
                }
                "planning" -> {
                    val message = jsonObject["message"] as? String ?: "正在思考中..."
                    Log.d(TAG, "SSE事件: planning - $message")
                    collector.emit(SSEEvent.StatusUpdate("planning", message))
                    Log.d(TAG, "planning事件已emit")
                }
                "complete" -> {
                    val actionJson = jsonObject["action"]
                    Log.d(TAG, "SSE事件: complete, action类型: ${actionJson?.javaClass?.simpleName}")
                    Log.d(TAG, "SSE事件: complete, action内容: $actionJson")
                    collector.emit(SSEEvent.Complete(actionJson))
                    Log.d(TAG, "complete事件已emit")
                }
                "error" -> {
                    val message = jsonObject["message"] as? String ?: "未知错误"
                    Log.e(TAG, "SSE事件: error - $message")
                    collector.emit(SSEEvent.Error(message))
                    Log.d(TAG, "error事件已emit")
                }
                else -> {
                    Log.d(TAG, "SSE事件: 未知状态 - $status")
                }
            }
            Log.d(TAG, "processSSEData完成")
        } catch (e: Exception) {
            Log.e(TAG, "解析SSE数据失败: ${e.message}, 数据: ${jsonData.take(200)}", e)
            Log.e(TAG, "异常堆栈", e)
            // 继续处理下一行，不中断流
        }
    }
    
    /**
     * SSE事件数据类
     */
    sealed class SSEEvent {
        data class StatusUpdate(val status: String, val message: String) : SSEEvent()
        data class Complete(val action: Any?) : SSEEvent()
        data class Error(val message: String) : SSEEvent()
    }
}

