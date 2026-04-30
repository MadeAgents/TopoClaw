package com.cloudcontrol.demo

import android.util.Log
import com.cloudcontrol.demo.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * 人工客服服务网络管理类
 */
object CustomerServiceNetwork {
    private const val TAG = "CustomerServiceNetwork"
    private val DEFAULT_BASE_URL = ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
    
    private var retrofit: Retrofit? = null
    private var apiService: CustomerServiceApi? = null
    private var currentBaseUrl: String? = null
    
    /**
     * 初始化人工客服服务网络
     */
    fun initialize(baseUrl: String = DEFAULT_BASE_URL) {
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        currentBaseUrl = url
        
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
        // BODY 会打印完整 JSON（广场列表含大量 base64），极易拖慢请求与解析；仅 Debug 打行级/头信息。
        if (BuildConfig.DEBUG) {
            clientBuilder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
        }
        val client = clientBuilder.build()
        
        val gson = GsonBuilder().setLenient().create()
        
        retrofit = Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        
        apiService = retrofit?.create(CustomerServiceApi::class.java)
        Log.d(TAG, "人工客服服务网络初始化完成: $url")
    }
    
    /**
     * 获取API服务实例
     */
    fun getApiService(): CustomerServiceApi? {
        return apiService
    }
    
    /**
     * 获取当前base URL
     */
    fun getCurrentBaseUrl(): String? {
        return currentBaseUrl
    }
    
    /**
     * 检查服务是否已初始化
     */
    fun isInitialized(): Boolean {
        return apiService != null
    }
}

