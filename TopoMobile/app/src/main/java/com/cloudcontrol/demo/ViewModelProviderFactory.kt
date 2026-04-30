package com.cloudcontrol.demo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * ViewModelProviderFactory
 * 用于为每个对话创建独立的 ChatViewModel
 */
class ViewModelProviderFactory(
    private val conversationId: String
) : ViewModelProvider.Factory {
    
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            val viewModel = ChatViewModel()
            viewModel.initialize(conversationId)
            return viewModel as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

