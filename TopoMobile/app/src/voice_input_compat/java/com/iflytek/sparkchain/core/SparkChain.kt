package com.iflytek.sparkchain.core

import android.content.Context

class SparkChain private constructor() {
    companion object {
        private val INSTANCE = SparkChain()

        @JvmStatic
        fun getInst(): SparkChain = INSTANCE
    }

    fun init(context: Context, config: SparkChainConfig): Int = -1

    fun unInit(): Int = 0
}

