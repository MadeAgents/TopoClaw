package com.cloudcontrol.demo

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.cloudcontrol.demo.databinding.FragmentChatSummaryBinding
import android.util.Log

/**
 * 历史总结Fragment
 * 显示聊天历史的总结内容
 */
class ChatSummaryFragment : Fragment() {
    
    companion object {
        private const val TAG = "ChatSummaryFragment"
        private const val ARG_CONVERSATION = "conversation"
        private const val ARG_SUMMARY = "summary"
        
        fun newInstance(conversation: Conversation, summary: String?): ChatSummaryFragment {
            return ChatSummaryFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_CONVERSATION, conversation)
                    putString(ARG_SUMMARY, summary)
                }
            }
        }
    }
    
    private var _binding: FragmentChatSummaryBinding? = null
    private val binding get() = _binding!!
    
    private var conversation: Conversation? = null
    private var summary: String? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatSummaryBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        conversation = arguments?.getSerializable(ARG_CONVERSATION) as? Conversation
        summary = arguments?.getString(ARG_SUMMARY)
        
        setupUI()
        displaySummary()
    }
    
    private fun setupUI() {
        // 返回按钮
        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        
        // 设置标题
        conversation?.let { conv ->
            binding.tvTitle.text = "${conv.name} - 历史总结"
        }
    }
    
    private fun displaySummary() {
        if (summary != null && summary!!.isNotBlank()) {
            binding.tvSummary.text = summary
        } else {
            binding.tvSummary.text = "暂无总结内容"
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

