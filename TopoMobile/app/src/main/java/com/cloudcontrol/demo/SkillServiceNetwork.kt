package com.cloudcontrol.demo

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * 技能服务网络管理类
 * 负责创建和管理技能服务的Retrofit实例
 */
object SkillServiceNetwork {
    private const val TAG = "SkillServiceNetwork"
    
    private var retrofit: Retrofit? = null
    private var apiService: SkillServiceApi? = null
    
    /**
     * 初始化技能服务网络
     * @param baseUrl 技能服务的地址，如 "http://192.168.1.100:5001"
     */
    fun initialize(baseUrl: String) {
        // 确保URL以/结尾
        val url = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        
        // 创建日志拦截器（用于调试）
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        
        // 创建OkHttp客户端
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(loggingInterceptor)
            .build()
        
        // 创建Gson转换器
        val gson: Gson = GsonBuilder()
            .setLenient()
            .create()
        
        // 创建Retrofit实例
        retrofit = Retrofit.Builder()
            .baseUrl(url)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
        
        // 创建API服务
        apiService = retrofit?.create(SkillServiceApi::class.java)
        
        Log.d(TAG, "技能服务网络初始化完成，Base URL: $url")
    }
    
    /**
     * 获取技能服务API实例
     */
    fun getApiService(): SkillServiceApi? {
        return apiService
    }
    
    /**
     * 检查服务是否已初始化
     */
    fun isInitialized(): Boolean {
        return apiService != null
    }
    
    /**
     * 使用默认地址初始化（localhost:5001）
     */
    fun initializeDefault() {
        initialize("http://localhost:5001")
    }
    
    /**
     * 使用IP地址初始化（用于Android设备访问PC上的服务）
     * @param ip IP地址，如 "192.168.1.100"
     */
    fun initializeWithIp(ip: String) {
        initialize("http://$ip:5001")
    }
}

