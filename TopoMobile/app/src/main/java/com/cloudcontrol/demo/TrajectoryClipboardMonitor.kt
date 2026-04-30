package com.cloudcontrol.demo

import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import java.security.MessageDigest

/**
 * 轨迹采集期间的剪切板监视器。
 * 仅负责把剪切板文本变化写入轨迹事件，不做云侧上传。
 */
object TrajectoryClipboardMonitor {
    private const val TAG = "TrajectoryClipboardMon"
    private const val PREFS_NAME = "app_prefs"
    private const val KEY_ENABLED = "trajectory_clipboard_monitor_enabled"
    private const val MAX_TEXT_LENGTH = 1000

    @Volatile private var baselineHash: String? = null
    @Volatile private var pendingSessionId: String? = null

    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * 在开始轨迹采集时记录剪切板基线，并绑定会话ID。
     */
    fun startForSession(context: Context, sessionId: String?) {
        if (!isEnabled(context)) {
            Log.d(TAG, "剪切板监视未启用，跳过启动")
            return
        }
        baselineHash = readCurrentClipboardHash(context)
        pendingSessionId = sessionId
        Log.d(TAG, "已记录轨迹剪切板基线: sessionId=$sessionId, baselineHash=${baselineHash ?: "null"}")
    }

    /**
     * 结束轨迹采集时仅标记会话结束，不立即读取剪切板（后台读取可能被系统限制）。
     */
    fun markSessionFinished(sessionId: String?) {
        if (!sessionId.isNullOrBlank()) {
            pendingSessionId = sessionId
        }
        Log.d(TAG, "轨迹采集已结束，等待回到应用前台后比对剪切板: sessionId=$pendingSessionId")
    }

    /**
     * 手动关闭剪切板监视时调用，清理待比对状态。
     */
    fun stop() {
        clearPending()
        Log.d(TAG, "轨迹剪切板监视已停止并清理状态")
    }

    /**
     * 回到 TopoClaw 前台时调用：对比开始前后的剪切板内容，变化则写入该会话。
     */
    fun compareAndRecordOnReturn(context: Context) {
        if (!isEnabled(context)) return

        val sessionId = pendingSessionId
        if (sessionId.isNullOrBlank()) return

        try {
            val currentText = readCurrentClipboardText(context).orEmpty()
            if (currentText.isBlank()) {
                Log.d(TAG, "回到前台时剪切板为空，跳过记录")
                clearPending()
                return
            }

            val currentHash = md5(currentText)
            if (currentHash == baselineHash) {
                Log.d(TAG, "回到前台时剪切板未变化，跳过记录")
                clearPending()
                return
            }

            val event = TrajectoryEvent(
                type = TrajectoryEventType.CLIPBOARD_CHANGE,
                timestamp = System.currentTimeMillis(),
                text = if (currentText.length > MAX_TEXT_LENGTH) {
                    currentText.take(MAX_TEXT_LENGTH) + "...(truncated)"
                } else {
                    currentText
                },
                packageName = MyAccessibilityService.getCurrentPackageName(),
                className = MyAccessibilityService.getCurrentClassName()
            )

            val written = TrajectoryRecorder.recordEventForCompletedSession(context, sessionId, event)
            Log.d(TAG, "回到前台比对剪切板完成: changed=true, sessionId=$sessionId, written=$written")
        } catch (e: Exception) {
            Log.e(TAG, "回到前台比对剪切板失败: ${e.message}", e)
        } finally {
            clearPending()
        }
    }

    private fun md5(text: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(text.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            text.hashCode().toString()
        }
    }

    private fun readCurrentClipboardText(context: Context): String? {
        return try {
            val manager = context.applicationContext
                .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return null
            val clip = manager.primaryClip ?: return null
            if (clip.itemCount <= 0) return null
            clip.getItemAt(0)?.coerceToText(null)?.toString()?.trim()
        } catch (e: Exception) {
            null
        }
    }

    private fun readCurrentClipboardHash(context: Context): String? {
        val text = readCurrentClipboardText(context).orEmpty()
        if (text.isBlank()) return null
        return md5(text)
    }

    private fun clearPending() {
        pendingSessionId = null
        baselineHash = null
    }
}
