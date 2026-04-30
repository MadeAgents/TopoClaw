package com.cloudcontrol.demo

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 音频录制管理器（用于语音识别）
 * 从SparkChain SDK示例代码移植
 */
class AudioRecorderManager private constructor(
    private val sampleRateInHz: Int = 16000,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val channels: Int = AudioFormat.CHANNEL_IN_MONO
) {
    private val TAG = "AudioRecorder"
    private val bufferSize: Int
    private var mRecorder: AudioRecord? = null
    private val isStart = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private val path = "/sdcard/iflytek/audio.wav"
    private var callback: AudioDataCallback? = null

    init {
        bufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channels, audioFormat)
        mRecorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRateInHz, channels, audioFormat, bufferSize)
    }

    fun registerCallBack(callback: AudioDataCallback) {
        this.callback = callback
    }

    companion object {
        @Volatile
        private var mInstance: AudioRecorderManager? = null

        fun getInstance(): AudioRecorderManager {
            if (mInstance == null) {
                synchronized(AudioRecorderManager::class.java) {
                    if (mInstance == null) {
                        mInstance = AudioRecorderManager()
                    }
                }
            }
            return mInstance!!
        }
    }

    /**
     * 销毁线程方法
     */
    private fun destroyThread() {
        synchronized(this) {
            try {
                isStart.set(false)
                recordThread?.let { thread ->
                    if (thread.isAlive) {
                        try {
                            thread.interrupt()
                            thread.join() // 确保线程已终止
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            recordThread = null
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                recordThread = null
            }
        }
    }

    /**
     * 启动录音线程
     */
    private fun startThread() {
        destroyThread()
        isStart.set(true)
        Log.i(TAG, "recordThread：${recordThread == null}")
        if (recordThread == null) {
            recordThread = Thread(recordRunnable)
            recordThread?.start()
        }
    }

    /**
     * 录音线程
     */
    private val recordRunnable = Runnable {
        try {
            mRecorder?.let { recorder ->
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
                val tempBuffer = ByteArray(bufferSize)
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    stopRecord()
                    return@Runnable
                }
                recorder.startRecording()
                while (isStart.get()) {
                    val bytesRecord = synchronized(this) {
                        recorder.read(tempBuffer, 0, bufferSize)
                    }
                    
                    if (bytesRecord == AudioRecord.ERROR_INVALID_OPERATION || bytesRecord == AudioRecord.ERROR_BAD_VALUE) {
                        // 继续下一次循环
                        continue
                    }
                    if (bytesRecord == 0 || bytesRecord == -1 || !isStart.get()) {
                        // 退出循环
                        break
                    }
                    
                    // 使用RMS方法计算音量
                    var sumSquares = 0.0
                    val sampleCount = bytesRecord / 2  // 每个样本16位(2字节)

                    for (i in 0 until bytesRecord step 2) {
                        // 将两个字节转换为一个16位短整型
                        val sample = ((tempBuffer[i].toInt() and 0xFF) or
                                ((tempBuffer[i + 1].toInt() and 0xFF) shl 8)).toShort()
                        // 计算平方和
                        sumSquares += sample.toDouble() * sample.toDouble()
                    }

                    // 计算RMS (均方根)
                    val rms = Math.sqrt(sumSquares / sampleCount)

                    // 转换为分贝值 (防止除以0)
                    var db = -120.0 // 默认极低值
                    if (rms > 1e-10) {  // 避免log(0)
                        db = 20 * Math.log10(rms / 32767.0)
                    }

                    // 映射到0-9音量等级
                    var volume = 0
                    if (db > -60) {
                        // 更符合人耳感知的映射：-60dB(0级)到-20dB(9级)
                        volume = Math.min(9, Math.max(0, ((db + 60) * 9 / 40.0).toInt()))
                    }
                    callback?.onAudioVolume(db, volume)
                    // 我们这里直接将pcm音频原数据写入文件 这里可以直接发送至服务器 对方采用AudioTrack进行播放原数据
                    callback?.onAudioData(tempBuffer, bytesRecord)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "录音异常: ${e.toString()}")
            e.printStackTrace()
        } finally {
            mRecorder?.let { recorder ->
                try {
                    recorder.stop()
                    recorder.release()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            mRecorder = null
        }
    }

    /**
     * 启动录音
     */
    fun startRecord() {
        try {
            startThread()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 停止录音
     */
    fun stopRecord() {
        destroyThread()
        synchronized(this) {
            try {
                callback = null
                mRecorder?.let { recorder ->
                    if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                        recorder.stop()
                    }
                    recorder.release()
                }
                mRecorder = null
                mInstance = null
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                mInstance = null // 确保单例实例被释放
            }
        }
    }

    protected fun writeDataToFile(path: String, bytes: ByteArray, append: Boolean) {
        try {
            val file = File(path)
            if (!file.exists()) {
                file.createNewFile()
            }
            FileOutputStream(path, append).use { out ->
                val fileChannel = out.channel
                fileChannel.write(ByteBuffer.wrap(bytes)) // 将字节流写入文件中
                fileChannel.force(true) // 强制刷新
                fileChannel.close()
            }
        } catch (e: FileNotFoundException) {
            e.printStackTrace()
        } catch (e: IOException) {
            Log.e(TAG, "writeFile: ${e.toString()}")
        }
    }

    interface AudioDataCallback {
        fun onAudioData(data: ByteArray, size: Int)
        fun onAudioVolume(db: Double, volume: Int)
    }
}

