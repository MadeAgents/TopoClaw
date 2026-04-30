package com.iflytek.sparkchain.core.asr

class ASR {
    data class ASRResult(
        val status: Int = 2,
        val bestMatchText: String = ""
    )

    data class ASRError(
        val code: Int = -1,
        val errMsg: String = "语音识别功能已临时下线。你仍可使用文本输入继续聊天。"
    )

    private var callbacks: AsrCallbacks? = null

    fun registerCallbacks(cb: AsrCallbacks) {
        callbacks = cb
    }

    fun language(value: String): ASR = this

    fun domain(value: String): ASR = this

    fun accent(value: String): ASR = this

    fun vinfo(value: Boolean): ASR = this

    fun dwa(value: String): ASR = this

    fun start(sessionId: String): Int {
        callbacks?.onError(ASRError(), null)
        return -1
    }

    fun write(data: ByteArray): Int = -1

    fun stop(waitLastPacket: Boolean): Int = 0
}

