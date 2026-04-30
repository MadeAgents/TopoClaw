package com.cloudcontrol.demo

import android.app.Application

/**
 * Application 入口
 * 在 onCreate 中应用用户选择的语言
 */
class TopoClawApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        LanguageManager.applyLocale(this)
    }
}
