package com.cloudcontrol.demo

import android.content.Context
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast

/**
 * 聊天场景下的提示音（ToneGenerator）、任务完成短音频（MediaPlayer）、播报（TTS）。
 * 在 Fragment 的 [androidx.fragment.app.Fragment.onDestroyView] 中须调用 [release]。
 *
 * 试听音调可参考 [ChatConstants.COMMON_TONES] 与 Android [ToneGenerator] 常量。
 */
class ChatAudioFeedbackController {

    private var toneGenerator: ToneGenerator? = null
    private var textToSpeech: TextToSpeech? = null
    private var taskCompleteMediaPlayer: MediaPlayer? = null

    fun playCallUserSound() {
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
            }
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200)
        } catch (e: Exception) {
            Log.e(ChatConstants.TAG, "播放call_user音效时异常: ${e.message}", e)
        }
    }

    fun playTaskCompleteSound(context: Context) {
        val app = context.applicationContext
        try {
            taskCompleteMediaPlayer?.release()
            taskCompleteMediaPlayer = null
            taskCompleteMediaPlayer = MediaPlayer.create(app, R.raw.task_complete)
            taskCompleteMediaPlayer?.apply {
                setOnCompletionListener {
                    release()
                    taskCompleteMediaPlayer = null
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(ChatConstants.TAG, "播放任务完成音频时出错: what=$what, extra=$extra")
                    release()
                    taskCompleteMediaPlayer = null
                    true
                }
                start()
            } ?: Log.e(ChatConstants.TAG, "无法创建MediaPlayer播放任务完成音频")
        } catch (e: Exception) {
            Log.e(ChatConstants.TAG, "播放任务完成音效时异常: ${e.message}", e)
            taskCompleteMediaPlayer?.release()
            taskCompleteMediaPlayer = null
        }
    }

    /** @param toneType 如 [ToneGenerator.TONE_PROP_BEEP] */
    fun testTone(toneType: Int, durationMs: Int = 500) {
        try {
            if (toneGenerator == null) {
                toneGenerator = ToneGenerator(android.media.AudioManager.STREAM_NOTIFICATION, 100)
            }
            toneGenerator?.startTone(toneType, durationMs)
            Log.d(ChatConstants.TAG, "播放测试音调: $toneType, 持续时间: ${durationMs}ms")
        } catch (e: Exception) {
            Log.e(ChatConstants.TAG, "播放测试音调时异常: ${e.message}", e)
        }
    }

    fun speakText(context: Context, text: String) {
        if (text.isBlank()) return
        val app = context.applicationContext
        try {
            if (textToSpeech == null) {
                textToSpeech = TextToSpeech(app) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
                    }
                }
            } else {
                textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
            }
        } catch (e: Exception) {
            Log.e(ChatConstants.TAG, "播报文本失败: ${e.message}", e)
            Toast.makeText(app, "播报失败", Toast.LENGTH_SHORT).show()
        }
    }

    fun release() {
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (e: Exception) {
            Log.w(ChatConstants.TAG, "释放音效播放器失败: ${e.message}")
        }
        try {
            taskCompleteMediaPlayer?.release()
            taskCompleteMediaPlayer = null
        } catch (e: Exception) {
            Log.w(ChatConstants.TAG, "释放音频播放器失败: ${e.message}")
        }
        try {
            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
        } catch (e: Exception) {
            Log.w(ChatConstants.TAG, "释放语音合成器失败: ${e.message}")
        }
    }
}
