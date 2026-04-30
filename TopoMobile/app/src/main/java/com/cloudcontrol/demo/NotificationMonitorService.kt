package com.cloudcontrol.demo

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * 监听系统通知并转发给应用内处理器。
 * 仅负责采集与基础去重/节流，不直接操作聊天页面。
 */
class NotificationMonitorService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationMonitorSvc"
        private const val PREFS_NAME = "app_prefs"
        const val KEY_ENABLED = "notification_monitor_enabled"
        private const val KEY_WHITELIST_PACKAGES = "notification_monitor_whitelist_packages"

        const val ACTION_MONITORED_NOTIFICATION = "com.cloudcontrol.demo.ACTION_MONITORED_NOTIFICATION"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_TEXT = "extra_text"

        @Volatile private var lastContentHash: String? = null
        @Volatile private var lastDispatchTimeMs: Long = 0L
        private const val MIN_DISPATCH_INTERVAL_MS = 1500L

        fun isEnabled(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_ENABLED, false)
        }

        fun setEnabled(context: Context, enabled: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun getWhitelistedPackages(context: Context): Set<String> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getStringSet(KEY_WHITELIST_PACKAGES, emptySet()) ?: emptySet()
        }

        fun setWhitelistedPackages(context: Context, packages: Set<String>) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putStringSet(KEY_WHITELIST_PACKAGES, packages).apply()
        }

        fun isNotificationAccessEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            return flat.contains(context.packageName)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (!isEnabled(applicationContext)) return

        try {
            val pkg = sbn.packageName ?: return
            if (pkg == packageName) {
                // 过滤自身通知，避免循环触发。
                return
            }
            if (pkg.equals("android", ignoreCase = true)) {
                // 用户要求：来源为 android 的通知不处理。
                return
            }
            // 白名单模式：仅监视用户勾选的应用；未勾选应用不处理。
            val whitelist = getWhitelistedPackages(applicationContext)
            if (whitelist.isEmpty() || !whitelist.contains(pkg)) {
                return
            }

            val extras = sbn.notification.extras
            val appName = try {
                val pm = applicationContext.packageManager
                val ai = pm.getApplicationInfo(pkg, 0)
                pm.getApplicationLabel(ai)?.toString()?.trim().orEmpty()
            } catch (_: Exception) {
                ""
            }
            val title = extras?.getCharSequence("android.title")?.toString()?.trim().orEmpty()
            val text = (
                extras?.getCharSequence("android.bigText")
                    ?: extras?.getCharSequence("android.text")
                )?.toString()?.trim().orEmpty()

            if (title.isBlank() && text.isBlank()) return

            val now = System.currentTimeMillis()
            val contentHash = "$pkg|$title|$text".hashCode().toString()
            if (contentHash == lastContentHash && (now - lastDispatchTimeMs) < 30_000L) {
                return
            }
            if (now - lastDispatchTimeMs < MIN_DISPATCH_INTERVAL_MS) {
                return
            }

            lastContentHash = contentHash
            lastDispatchTimeMs = now

            val eventIntent = Intent(ACTION_MONITORED_NOTIFICATION).apply {
                setPackage(packageName)
                putExtra(EXTRA_PACKAGE, pkg)
                putExtra(EXTRA_APP_NAME, appName)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_TEXT, text)
            }
            sendBroadcast(eventIntent)
            Log.d(TAG, "已转发通知: app=$appName, pkg=$pkg, title=${title.take(40)}, text=${text.take(60)}")
        } catch (e: Exception) {
            Log.e(TAG, "处理通知失败: ${e.message}", e)
        }
    }
}
