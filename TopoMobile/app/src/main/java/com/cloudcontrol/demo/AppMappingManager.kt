package com.cloudcontrol.demo

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * 应用包名映射管理器
 * 用于通过应用名查找包名，或通过包名查找应用名
 * 支持动态添加映射到端侧文件
 */
object AppMappingManager {
    private const val TAG = "AppMappingManager"
    private var appMapping: MutableMap<String, String> = mutableMapOf() // 应用名 -> 包名
    private var reverseMapping: MutableMap<String, String> = mutableMapOf() // 包名 -> 应用名
    private var context: Context? = null
    private val mappingFileName = "app_mapping.json"
    
    /**
     * 初始化映射数据
     * @param context 上下文
     */
    fun initialize(context: Context) {
        this.context = context.applicationContext
        try {
            // 先从assets加载初始映射
            val jsonString = context.assets.open("app_mapping.json").bufferedReader().use { it.readText() }
            val jsonArray = JSONArray(jsonString)
            val mapping = mutableMapOf<String, String>()
            val reverse = mutableMapOf<String, String>()
            
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val packageName = obj.getString("packageName")
                val appName = obj.getString("appName")
                mapping[appName] = packageName
                reverse[packageName] = appName
            }
            
            // 然后从应用数据目录加载动态添加的映射（如果有）
            val dynamicMappings = loadDynamicMappings(context)
            mapping.putAll(dynamicMappings)
            dynamicMappings.forEach { (appName, packageName) ->
                reverse[packageName] = appName
            }
            
            appMapping = mapping
            reverseMapping = reverse
            Log.d(TAG, "应用映射初始化完成，共 ${appMapping.size} 个应用（其中 ${dynamicMappings.size} 个为动态添加）")
        } catch (e: IOException) {
            Log.e(TAG, "读取应用映射文件失败: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "解析应用映射文件失败: ${e.message}", e)
        }
    }
    
    /**
     * 从应用数据目录加载动态映射
     */
    private fun loadDynamicMappings(context: Context): Map<String, String> {
        val mapping = mutableMapOf<String, String>()
        try {
            val file = File(context.filesDir, mappingFileName)
            if (file.exists()) {
                val jsonString = file.readText()
                val jsonArray = JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val packageName = obj.getString("packageName")
                    val appName = obj.getString("appName")
                    mapping[appName] = packageName
                }
                Log.d(TAG, "从动态映射文件加载了 ${mapping.size} 个映射")
            }
        } catch (e: Exception) {
            Log.e(TAG, "加载动态映射失败: ${e.message}", e)
        }
        return mapping
    }
    
    /**
     * 动态添加应用映射
     * @param appName 应用名
     * @param packageName 包名
     * @return 是否添加成功
     */
    fun addMapping(appName: String, packageName: String): Boolean {
        val ctx = context ?: return false
        
        try {
            // 如果已存在，不重复添加
            if (appMapping.containsKey(appName)) {
                Log.d(TAG, "映射已存在: $appName -> $packageName")
                return true
            }
            
            // 添加到内存映射
            appMapping[appName] = packageName
            reverseMapping[packageName] = appName
            
            // 保存到文件
            val file = File(ctx.filesDir, mappingFileName)
            val jsonArray = if (file.exists()) {
                val existingJson = file.readText()
                JSONArray(existingJson)
            } else {
                JSONArray()
            }
            
            // 检查是否已存在（避免重复）
            var exists = false
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.getString("appName") == appName || obj.getString("packageName") == packageName) {
                    exists = true
                    break
                }
            }
            
            if (!exists) {
                val newObj = JSONObject()
                newObj.put("appName", appName)
                newObj.put("packageName", packageName)
                jsonArray.put(newObj)
                
                file.writeText(jsonArray.toString())
                Log.d(TAG, "成功添加映射到文件: $appName -> $packageName")
            } else {
                Log.d(TAG, "映射已存在于文件: $appName -> $packageName")
            }
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "添加映射失败: ${e.message}", e)
            return false
        }
    }
    
    /**
     * 通过应用名获取包名
     * @param appName 应用名
     * @return 包名，如果找不到返回null
     */
    fun getPackageName(appName: String): String? {
        return appMapping[appName]
    }
    
    /**
     * 通过包名获取应用名
     * @param packageName 包名
     * @return 应用名，如果找不到返回null
     */
    fun getAppName(packageName: String): String? {
        return reverseMapping[packageName]
    }
    
    /**
     * 解析open指令的参数
     * 支持包名或应用名
     * @param param 参数（可能是包名或应用名）
     * @return 包名，如果找不到返回null
     */
    fun resolvePackageName(param: String): String? {
        // 如果已经是包名格式（包含点），直接返回
        if (param.contains(".")) {
            return param
        }
        // 否则尝试通过应用名查找
        return getPackageName(param)
    }
    
    /**
     * 根据应用名查找包名（从已安装的应用中查找）
     * 用于安装后提取包名
     * @param appName 应用名
     * @param context 上下文
     * @return 包名，如果找不到返回null
     */
    fun findPackageNameByAppName(appName: String, context: Context): String? {
        try {
            val packageManager = context.packageManager
            val installedPackages = packageManager.getInstalledPackages(0)
            
            // 精确匹配
            val exactMatch = installedPackages.find { 
                packageManager.getApplicationLabel(it.applicationInfo).toString() == appName
            }
            if (exactMatch != null) {
                return exactMatch.packageName
            }
            
            // 模糊匹配（包含关键词）
            val fuzzyMatch = installedPackages.find { 
                val label = packageManager.getApplicationLabel(it.applicationInfo).toString()
                label.contains(appName) || appName.contains(label)
            }
            return fuzzyMatch?.packageName
        } catch (e: Exception) {
            Log.e(TAG, "查找包名失败: ${e.message}", e)
            return null
        }
    }
}

