package com.iflytek.sparkchain.core

class SparkChainConfig private constructor() {
    companion object {
        @JvmStatic
        fun builder(): SparkChainConfig = SparkChainConfig()
    }

    fun appID(value: String): SparkChainConfig = this

    fun apiKey(value: String): SparkChainConfig = this

    fun apiSecret(value: String): SparkChainConfig = this

    fun logPath(value: String): SparkChainConfig = this
}

