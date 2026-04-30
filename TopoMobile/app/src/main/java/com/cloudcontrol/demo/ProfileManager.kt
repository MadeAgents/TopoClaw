package com.cloudcontrol.demo

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Log
import java.util.UUID

/**
 * 个性化档案管理器
 * 负责IMEI的生成、存储和个性化档案的本地管理
 */
object ProfileManager {
    private const val TAG = "ProfileManager"
    private const val PREFS_NAME = "user_profile_prefs"
    private const val KEY_IMEI = "imei"
    private const val KEY_PROFILE_NAME = "profile_name"
    private const val KEY_PROFILE_GENDER = "profile_gender"
    private const val KEY_PROFILE_ADDRESS = "profile_address"
    private const val KEY_PROFILE_PHONE = "profile_phone"
    private const val KEY_PROFILE_BIRTHDAY = "profile_birthday"
    private const val KEY_PROFILE_PREFERENCES = "profile_preferences"
    private const val KEY_PROFILE_AVATAR = "profile_avatar"
    
    /**
     * 获取或生成IMEI
     * @param context 上下文
     * @return IMEI字符串
     */
    fun getOrGenerateImei(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedImei = prefs.getString(KEY_IMEI, null)
        
        val imei = if (savedImei.isNullOrEmpty()) {
            val newImei = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                    ?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
            } catch (e: Exception) {
                Log.w(TAG, "获取ANDROID_ID失败，使用UUID: ${e.message}")
                UUID.randomUUID().toString()
            }
            prefs.edit().putString(KEY_IMEI, newImei).apply()
            Log.d(TAG, "生成新的IMEI: $newImei")
            newImei
        } else {
            Log.d(TAG, "使用已有IMEI: $savedImei")
            savedImei
        }
        
        return imei
    }
    
    /**
     * 设置 IMEI（用于 PC 端扫码绑定时，手机扫 PC 的二维码后 adopting 该 IMEI）
     * @param context 上下文
     * @param imei 要设置的 IMEI
     */
    fun setImei(context: Context, imei: String) {
        val trimmed = imei.trim()
        if (trimmed.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_IMEI, trimmed).apply()
        Log.d(TAG, "已设置 IMEI: $trimmed")
    }
    
    /**
     * 获取当前IMEI
     * @param context 上下文
     * @return IMEI字符串，如果不存在返回null
     */
    fun getImei(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_IMEI, null)
    }
    
    /**
     * 清除IMEI和所有个性化档案数据
     * @param context 上下文
     */
    fun clearProfile(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        Log.d(TAG, "已清除所有个性化档案数据")
    }
    
    /**
     * 保存个性化档案到本地（缓存）
     * @param context 上下文
     * @param profile 个性化档案
     */
    fun saveProfileLocally(context: Context, profile: UserProfile) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString(KEY_PROFILE_NAME, profile.name)
            putString(KEY_PROFILE_GENDER, profile.gender)
            putString(KEY_PROFILE_ADDRESS, profile.address)
            putString(KEY_PROFILE_PHONE, profile.phone)
            putString(KEY_PROFILE_BIRTHDAY, profile.birthday)
            putString(KEY_PROFILE_PREFERENCES, profile.preferences)
            putString(KEY_PROFILE_AVATAR, profile.avatar)
            apply()
        }
        Log.d(TAG, "已保存个性化档案到本地")
    }
    
    /**
     * 从本地加载个性化档案
     * @param context 上下文
     * @return 个性化档案，如果不存在返回null
     */
    fun loadProfileLocally(context: Context): UserProfile? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val imei = prefs.getString(KEY_IMEI, null) ?: return null
        
        val name = prefs.getString(KEY_PROFILE_NAME, null)
        val gender = prefs.getString(KEY_PROFILE_GENDER, null)
        val address = prefs.getString(KEY_PROFILE_ADDRESS, null)
        val phone = prefs.getString(KEY_PROFILE_PHONE, null)
        val birthday = prefs.getString(KEY_PROFILE_BIRTHDAY, null)
        val preferences = prefs.getString(KEY_PROFILE_PREFERENCES, null)
        val avatar = prefs.getString(KEY_PROFILE_AVATAR, null)
        
        // 如果所有字段都为空，返回null
        if (name.isNullOrEmpty() && gender.isNullOrEmpty() && address.isNullOrEmpty() &&
            phone.isNullOrEmpty() && birthday.isNullOrEmpty() && preferences.isNullOrEmpty() && avatar.isNullOrEmpty()) {
            return null
        }
        
        return UserProfile(
            imei = imei,
            name = name,
            gender = gender,
            address = address,
            phone = phone,
            birthday = birthday,
            preferences = preferences,
            avatar = avatar
        )
    }
}

