package com.iflytek.sparkchain.core.asr

interface AsrCallbacks {
    fun onResult(asrResult: ASR.ASRResult, o: Any?)
    fun onError(asrError: ASR.ASRError, o: Any?)
    fun onBeginOfSpeech()
    fun onEndOfSpeech()
}

