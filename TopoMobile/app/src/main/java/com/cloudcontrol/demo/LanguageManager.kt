package com.cloudcontrol.demo

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * 语言管理
 * 支持运行时切换 简体中文 / English
 */
object LanguageManager {

    const val PREF_LANGUAGE = "app_language"
    const val LANG_ZH = "zh"
    const val LANG_EN = "en"

    /**
     * 获取当前保存的语言
     */
    fun getSavedLanguage(context: Context): String {
        return context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getString(PREF_LANGUAGE, LANG_ZH) ?: LANG_ZH
    }

    /**
     * 保存语言设置并应用
     */
    fun setLanguage(context: Context, language: String) {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_LANGUAGE, language)
            .apply()
        applyLocale(context, language)
    }

    /**
     * 应用语言设置（启动时调用）
     */
    fun applyLocale(context: Context, language: String? = null) {
        val lang = language ?: getSavedLanguage(context)
        val localeTag = when (lang) {
            LANG_EN -> "en"
            else -> "zh-CN"
        }
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(localeTag))
    }

    /**
     * 获取当前 Locale（用于需要 Locale 的场景）
     */
    fun getCurrentLocale(context: Context): Locale {
        return when (getSavedLanguage(context)) {
            LANG_EN -> Locale.ENGLISH
            else -> Locale.SIMPLIFIED_CHINESE
        }
    }

    /**
     * 是否为英文
     */
    fun isEnglish(context: Context): Boolean = getSavedLanguage(context) == LANG_EN
}
