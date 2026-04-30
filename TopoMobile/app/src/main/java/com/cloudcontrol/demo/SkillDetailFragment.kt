package com.cloudcontrol.demo

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentTransaction

/**
 * 技能详情Fragment
 * 显示技能的详细步骤信息
 */
class SkillDetailFragment : Fragment() {
    
    companion object {
        private const val TAG = "SkillDetailFragment"
        private const val ARG_SKILL_ID = "skill_id"
        private const val ARG_SKILL_OBJECT = "skill_object"
        private const val ARG_ALLOW_EDIT = "allow_edit"
        
        /**
         * 创建技能详情Fragment实例（通过技能ID）
         * @param skillId 技能ID
         * @param allowEdit 是否允许编辑（默认为true）
         */
        fun newInstance(skillId: String, allowEdit: Boolean = true): SkillDetailFragment {
            return SkillDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SKILL_ID, skillId)
                    putBoolean(ARG_ALLOW_EDIT, allowEdit)
                }
            }
        }
        
        /**
         * 创建技能详情Fragment实例（直接传递技能对象，用于临时技能）
         * @param skill 技能对象
         * @param allowEdit 是否允许编辑（默认为false，因为临时技能通常不允许编辑）
         */
        fun newInstance(skill: Skill, allowEdit: Boolean = false): SkillDetailFragment {
            return SkillDetailFragment().apply {
                arguments = Bundle().apply {
                    putSerializable(ARG_SKILL_OBJECT, skill)
                    putBoolean(ARG_ALLOW_EDIT, allowEdit)
                }
            }
        }
    }

    private var skill: Skill? = null
    private var allowEdit: Boolean = true
    private var isEditingSteps: Boolean = false
    private val stepEditTexts = mutableListOf<android.widget.EditText>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_skill_detail, container, false)

        try {
            // 获取技能对象、技能ID和编辑权限
            allowEdit = arguments?.getBoolean(ARG_ALLOW_EDIT, true) ?: true
            
            // 优先从参数中获取技能对象（用于临时技能）
            val skillObject = arguments?.getSerializable(ARG_SKILL_OBJECT) as? Skill
            if (skillObject != null) {
                // 验证技能对象有效性
                if (skillObject.id.isNullOrEmpty() || skillObject.title.isNullOrEmpty()) {
                    Log.e(TAG, "从参数获取的技能对象无效: skillId=${skillObject.id}, skillTitle=${skillObject.title}")
                    skill = null
                } else {
                    skill = skillObject
                    Log.d(TAG, "从参数获取技能对象成功: skillId=${skillObject.id}, skillTitle=${skillObject.title}")
                }
            } else {
                // 如果没有技能对象，尝试通过ID查找
                val skillId = arguments?.getString(ARG_SKILL_ID)
                if (skillId != null && skillId.isNotEmpty()) {
                    try {
                        // 性能优化：先尝试从临时技能存储查找（最快），然后才是"我的技能"和"技能社区"
                        skill = TemporarySkillManager.findTemporarySkill(requireContext(), skillId)
                        
                        if (skill == null) {
                            // 1. 先从"我的技能"加载
                            val mySkills = SkillManager.loadSkills(requireContext())
                            skill = mySkills.find { it.id == skillId }
                        }
                        
                        if (skill == null) {
                            // 2. 如果"我的技能"中找不到，从"技能社区"加载
                            val communitySkills = SkillManager.loadCommunitySkills(requireContext())
                            skill = communitySkills.find { it.id == skillId }
                        }
                        
                        val foundSkill = skill
                        if (foundSkill == null) {
                            Log.w(TAG, "通过skillId未找到技能: skillId=$skillId")
                        } else {
                            Log.d(TAG, "通过skillId找到技能: skillId=$skillId, skillTitle=${foundSkill.title}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "通过skillId查找技能失败: skillId=$skillId, error=${e.message}", e)
                        skill = null
                    }
                } else {
                    Log.w(TAG, "skillId为空或null，无法查找技能")
                    skill = null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "onCreateView中获取技能对象失败: ${e.message}", e)
            skill = null
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 使用局部变量保存skill的值，避免智能转换问题
        val currentSkill = skill
        if (currentSkill == null) {
            Log.e(TAG, "技能不存在，返回上一页")
            try {
                // 显示错误提示
                Toast.makeText(requireContext(), "技能信息不存在或已删除", Toast.LENGTH_SHORT).show()
                // 返回上一页
                if (parentFragmentManager.backStackEntryCount > 0) {
                    parentFragmentManager.popBackStack()
                } else {
                    // 如果没有返回栈，尝试通过Activity返回
                    activity?.onBackPressed()
                }
            } catch (e: Exception) {
                Log.e(TAG, "返回上一页失败: ${e.message}", e)
            }
            return
        }
        
        // 再次验证技能对象有效性
        if (currentSkill.id.isNullOrEmpty() || currentSkill.title.isNullOrEmpty()) {
            Log.e(TAG, "技能对象无效: skillId=${currentSkill.id}, skillTitle=${currentSkill.title}")
            try {
                Toast.makeText(requireContext(), "技能信息无效", Toast.LENGTH_SHORT).show()
                if (parentFragmentManager.backStackEntryCount > 0) {
                    parentFragmentManager.popBackStack()
                } else {
                    activity?.onBackPressed()
                }
            } catch (e: Exception) {
                Log.e(TAG, "返回上一页失败: ${e.message}", e)
            }
            return
        }
        
        try {
            // 隐藏ActionBar
            val activity = activity as? androidx.appcompat.app.AppCompatActivity
            activity?.supportActionBar?.hide()
            
            setupViews()
        } catch (e: Exception) {
            Log.e(TAG, "onViewCreated设置视图失败: ${e.message}", e)
            Toast.makeText(requireContext(), "加载技能详情失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 恢复ActionBar（如果需要）
        val activity = activity as? androidx.appcompat.app.AppCompatActivity
        activity?.supportActionBar?.show()
    }
    
    /**
     * 设置视图内容
     */
    private fun setupViews() {
        val view = view ?: return
        val skill = this.skill ?: return

        // 设置标题
        val titleView = view.findViewById<TextView>(R.id.tvSkillTitle)
        titleView.text = skill.title
        
        // 设置技能ID
        val skillIdView = view.findViewById<TextView>(R.id.tvSkillId)
        skillIdView.text = skill.id
        
        // 标题双击编辑功能已移除，改为通过编辑按钮编辑

        // 设置返回按钮
        val backButton = view.findViewById<View>(R.id.ivBack)
        backButton.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        // 设置编辑按钮（编辑技能名和步骤）
        val editButton = view.findViewById<TextView>(R.id.btnEditSteps)
        editButton.visibility = if (allowEdit) View.VISIBLE else View.GONE
        editButton.setOnClickListener {
            toggleEditMode()
        }
        
        // 设置步骤列表
        refreshStepsDisplay()
        
        // 设置执行按钮
        val executeButton = view.findViewById<android.widget.ImageButton>(R.id.btnExecute)
        executeButton.setOnClickListener {
            // 执行技能：跳转到技能学习小助手并执行
            Log.d(TAG, "点击执行按钮: ${skill.title}")
            executeSkill()
        }
        
        // 设置定时按钮（仅我的技能显示）
        if (allowEdit) {
            val buttonContainer = executeButton.parent as? android.view.ViewGroup
            val scheduleButton = android.widget.ImageButton(requireContext()).apply {
                id = android.view.View.generateViewId()
                setImageResource(android.R.drawable.ic_menu_recent_history)
                contentDescription = "定时设置"
                // 使用与执行和分享按钮相同的样式
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    56.dpToPx()
                ).apply {
                    weight = 1f
                    marginEnd = 8.dpToPx()
                }
                setPadding(12.dpToPx(), 12.dpToPx(), 12.dpToPx(), 12.dpToPx())
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                background = requireContext().getDrawable(R.drawable.btn_light_background)
            }
            // 插入到执行按钮和分享按钮之间
            val insertIndex = buttonContainer?.indexOfChild(executeButton)?.plus(1) ?: 1
            buttonContainer?.addView(scheduleButton, insertIndex)
            scheduleButton.setOnClickListener {
                SkillScheduleSettingDialog.show(requireContext(), skill) { config ->
                    if (config == null) {
                        // 如果config为null，说明用户取消了设置
                        return@show
                    }
                    
                    val updatedSkill = skill.copy(scheduleConfig = config)
                    if (SkillManager.updateSkill(requireContext(), updatedSkill)) {
                        this.skill = updatedSkill
                        if (config.isEnabled) {
                            SkillScheduleManager.scheduleSkill(requireContext(), updatedSkill)
                            Toast.makeText(requireContext(), "定时设置已保存", Toast.LENGTH_SHORT).show()
                        } else {
                            SkillScheduleManager.cancelSchedule(requireContext(), skill.id)
                            Toast.makeText(requireContext(), "定时已禁用", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(requireContext(), "保存失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        
        // 设置分享/添加按钮
        val shareButton = view.findViewById<android.widget.ImageButton>(R.id.btnShare)
        if (allowEdit) {
            // 我的技能：显示分享按钮
            shareButton.setImageResource(android.R.drawable.ic_menu_share)
            shareButton.contentDescription = "分享"
            shareButton.setOnClickListener {
                showShareOptionsDialog(skill)
            }
        } else {
            // 技能社区：显示加号按钮
            shareButton.setImageResource(android.R.drawable.ic_input_add)
            shareButton.contentDescription = "添加到我的技能"
            shareButton.setOnClickListener {
                // 创建新ID的技能，避免ID冲突
                val newSkill = skill.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    createdAt = System.currentTimeMillis()
                )
                val success = SkillManager.saveSkill(requireContext(), newSkill)
                if (success) {
                    // 如果该技能在临时存储中，删除它（因为已经添加到"我的技能"了）
                    TemporarySkillManager.deleteTemporarySkill(requireContext(), skill.id)
                    Toast.makeText(requireContext(), "技能已添加到我的技能", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "添加失败，该技能可能已存在", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        // 设置上热门按钮（仅在管理者模式且是技能社区时显示）
        val setHotButton = view.findViewById<android.widget.Button>(R.id.btnSetHot)
        val isAdminMode = AdminModeManager.isAdminModeEnabled(requireContext())
        val isCommunitySkill = !allowEdit // 技能社区不允许编辑
        
        if (isAdminMode && isCommunitySkill) {
            setHotButton.visibility = View.VISIBLE
            setHotButton.text = if (skill.isHot) getString(R.string.cancel_hot) else getString(R.string.set_hot)
            setHotButton.setOnClickListener {
                val newHotStatus = !skill.isHot
                val success = SkillManager.setSkillHotStatus(requireContext(), skill.id, newHotStatus)
                if (success) {
                    // 更新本地技能对象
                    this.skill = skill.copy(isHot = newHotStatus)
                    setHotButton.text = if (newHotStatus) getString(R.string.cancel_hot) else getString(R.string.set_hot)
                    Toast.makeText(requireContext(), if (newHotStatus) getString(R.string.set_hot_success) else getString(R.string.cancel_hot_success), Toast.LENGTH_SHORT).show()
                    
                    // 通知SkillFragment刷新技能社区列表和徽章
                    try {
                        val fragmentManager = parentFragmentManager
                        val skillFragment = fragmentManager.fragments.find { it is SkillFragment } as? SkillFragment
                        skillFragment?.refreshCommunitySkillsList()
                        skillFragment?.updateHotSkillBadge()
                        
                        // 更新MainActivity的底部导航栏徽章
                        (activity as? MainActivity)?.updateSkillBadge()
                    } catch (e: Exception) {
                        Log.w(TAG, "通知SkillFragment刷新失败: ${e.message}")
                    }
                } else {
                    Toast.makeText(requireContext(), getString(R.string.operation_failed), Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            setHotButton.visibility = View.GONE
        }
    }
    
    /**
     * 启用编辑模式
     */
    private fun enableEditing(textView: TextView, onSave: (String) -> Unit) {
        val originalViewId = textView.id
        val editText = android.widget.EditText(requireContext()).apply {
            id = originalViewId  // 保持 id，以便 saveSteps 能通过 findViewById 获取当前文本（即使用户未失焦就点保存）
            setText(textView.text)
            setSelection(textView.text.length)
            textSize = textView.textSize / requireContext().resources.displayMetrics.scaledDensity
            setTextColor(textView.currentTextColor)
            setTypeface(textView.typeface)
            setPadding(
                textView.paddingLeft,
                textView.paddingTop,
                textView.paddingRight,
                textView.paddingBottom
            )
            background = null
            maxLines = if (originalViewId == R.id.tvSkillTitle) 2 else 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        
        val parent = textView.parent as? android.view.ViewGroup
        if (parent != null) {
            val index = parent.indexOfChild(textView)
            val layoutParams = textView.layoutParams
            
            parent.removeView(textView)
            parent.addView(editText, index, layoutParams)
            
            editText.requestFocus()
            editText.post {
                val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
                imm.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }
            
            editText.setOnFocusChangeListener { view, hasFocus ->
                if (!hasFocus) {
                    val newText = (view as android.widget.EditText).text.toString().trim()
                    val finalText = if (newText.isNotEmpty()) newText else textView.text.toString()
                    
                    if (newText.isNotEmpty() && newText != textView.text.toString()) {
                        onSave(newText)
                    }
                    
                    restoreTextView(parent, editText, finalText, textView, index, layoutParams, onSave)
                }
            }
            
            editText.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    editText.clearFocus()
                    true
                } else {
                    false
                }
            }
        }
    }
    
    /**
     * 恢复TextView
     */
    private fun restoreTextView(
        parent: android.view.ViewGroup,
        editText: android.widget.EditText,
        text: String,
        originalTextView: TextView,
        index: Int,
        layoutParams: android.view.ViewGroup.LayoutParams,
        onSave: (String) -> Unit
    ) {
        val textView = TextView(requireContext()).apply {
            id = originalTextView.id  // 保持 id，确保 saveSteps 中 findViewById 能正确找到视图
            this.text = text
            textSize = originalTextView.textSize / requireContext().resources.displayMetrics.scaledDensity
            setTypeface(originalTextView.typeface)
            setTextColor(originalTextView.currentTextColor)
            maxLines = originalTextView.maxLines
            ellipsize = originalTextView.ellipsize
            setPadding(
                editText.paddingLeft,
                editText.paddingTop,
                editText.paddingRight,
                editText.paddingBottom
            )
            this.layoutParams = editText.layoutParams
        }
        
        parent.removeView(editText)
        parent.addView(textView, index, layoutParams)
        
        val imm = requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(editText.windowToken, 0)
    }
    
    /**
     * 执行技能
     */
    private fun executeSkill() {
        val skill = this.skill ?: return
        try {
            Log.d(TAG, "executeSkill: 开始执行技能 - ${skill.title}")
            
            val mainActivity = activity as? MainActivity
            if (mainActivity != null) {
                // 切换到TopoClaw对话
                val assistantConv = Conversation(
                    id = ConversationListFragment.CONVERSATION_ID_ASSISTANT,
                    name = ChatConstants.ASSISTANT_DISPLAY_NAME,
                    avatar = null,
                    lastMessage = null,
                    lastMessageTime = System.currentTimeMillis()
                )
                
                // 切换到TopoClaw对话
                mainActivity.switchToChatFragment(assistantConv)
                
                // 等待Fragment切换完成后再发送技能
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        val chatFragment = mainActivity.getChatFragment()
                        if (chatFragment != null && chatFragment.isAdded) {
                            // 直接调用公开的sendSkillAsQuery方法
                            chatFragment.sendSkillAsQuery(skill)
                            Log.d(TAG, "技能已发送到TopoClaw: ${skill.title}")
                        } else {
                            Log.w(TAG, "ChatFragment未就绪，延迟重试")
                            // 如果Fragment还没准备好，再延迟一点
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                try {
                                    val retryFragment = mainActivity.getChatFragment()
                                    if (retryFragment != null && retryFragment.isAdded) {
                                        retryFragment.sendSkillAsQuery(skill)
                                        Log.d(TAG, "技能已发送到TopoClaw（重试）: ${skill.title}")
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "重试发送技能失败: ${e.message}", e)
                                }
                            }, 500)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "发送技能到TopoClaw失败: ${e.message}", e)
                    }
                }, 300)
            } else {
                Log.e(TAG, "executeSkill: MainActivity为空")
                Toast.makeText(requireContext(), "执行技能失败: 无法获取MainActivity", Toast.LENGTH_SHORT).show()
            }
                
        } catch (e: Exception) {
            Log.e(TAG, "executeSkill异常: ${e.message}", e)
            Toast.makeText(requireContext(), "执行技能失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 显示分享选项对话框
     */
    private fun showShareOptionsDialog(skill: Skill) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("选择分享方式")
            .setItems(arrayOf("分享到技能社区", "分享到好友/群组")) { _, which ->
                when (which) {
                    0 -> shareSkillToCommunity(skill)
                    1 -> shareSkillToFriendOrGroup(skill)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 分享技能到技能社区
     */
    private fun shareSkillToCommunity(skill: Skill) {
        val success = SkillManager.saveSkillToCommunity(requireContext(), skill)
        if (success) {
            Toast.makeText(requireContext(), "技能已分享到技能社区", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "分享失败，该技能可能已在技能社区中", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 分享技能到好友/群组
     */
    private fun shareSkillToFriendOrGroup(skill: Skill) {
        // 获取好友列表和群组列表
        val friends = FriendManager.getFriends(requireContext())
            .filter { it.status == "accepted" }
        val groups = GroupManager.getGroups(requireContext())
        
        // 构建选项列表
        val options = mutableListOf<String>()
        val optionTypes = mutableListOf<String>() // "friend" 或 "group"
        
        // 添加好友选项
        friends.forEach { friend ->
            val friendName = friend.nickname ?: friend.imei.take(8) + "..."
            options.add("好友: $friendName")
            optionTypes.add("friend_${friend.imei}")
        }
        
        // 添加群组选项
        groups.forEach { group ->
            options.add("群组: ${group.name}")
            optionTypes.add("group_${group.groupId}")
        }
        
        if (options.isEmpty()) {
            Toast.makeText(requireContext(), "暂无好友或群组可分享", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 显示选择对话框
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("选择分享对象")
            .setItems(options.toTypedArray()) { _, which ->
                val selectedType = optionTypes[which]
                val skillMessage = formatSkillAsMessage(skill)
                
                if (selectedType.startsWith("friend_")) {
                    val targetImei = selectedType.removePrefix("friend_")
                    sendSkillToFriend(targetImei, skill, skillMessage)
                } else if (selectedType.startsWith("group_")) {
                    val groupId = selectedType.removePrefix("group_")
                    sendSkillToGroup(groupId, skill, skillMessage)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 将技能格式化为消息内容
     */
    private fun formatSkillAsMessage(skill: Skill): String {
        val sb = StringBuilder()
        sb.append(getString(R.string.skill_share_format, skill.title))
        sb.append(getString(R.string.action_steps_label))
        sb.append("\n")
        skill.steps.forEachIndexed { index, step ->
            sb.append("${index + 1}. $step\n")
        }
        if (skill.originalPurpose != null) {
            sb.append(getString(R.string.original_purpose_format, skill.originalPurpose))
        }
        return sb.toString()
    }
    
    /**
     * 发送技能到好友
     */
    private fun sendSkillToFriend(targetImei: String, skill: Skill, message: String) {
        try {
            val mainActivity = activity as? MainActivity
            val webSocket = mainActivity?.getCustomerServiceWebSocket()
            
            if (webSocket != null && webSocket.isConnected()) {
                // 传递skillId，以便接收端能正确识别和显示技能卡片
                webSocket.sendFriendMessage(targetImei, message, skillId = skill.id)
                
                // 切换到好友对话页面并添加技能消息到聊天记录
                val friendConversationId = "friend_$targetImei"
                val friendConversation = Conversation(
                    id = friendConversationId,
                    name = "好友",
                    avatar = null,
                    lastMessage = null,
                    lastMessageTime = System.currentTimeMillis()
                )
                val chatFragment = mainActivity?.getOrCreateChatFragment(friendConversation)
                chatFragment?.addSkillMessage(skill, "我", isUserMessage = true)
                mainActivity?.switchToChatFragment(friendConversation)
                
                Toast.makeText(requireContext(), "技能已分享给好友", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "无法连接到服务器，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "分享技能到好友失败: ${e.message}", e)
            Toast.makeText(requireContext(), "分享失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 发送技能到群组
     */
    private fun sendSkillToGroup(groupId: String, skill: Skill, message: String) {
        try {
            val mainActivity = activity as? MainActivity
            val webSocket = mainActivity?.getCustomerServiceWebSocket()
            
            if (webSocket != null && webSocket.isConnected()) {
                webSocket.sendGroupMessage(groupId, message)
                
                // 切换到群组对话页面并添加技能消息到聊天记录
                val groupConversationId = if (groupId == "friends") {
                    ConversationListFragment.CONVERSATION_ID_GROUP
                } else {
                    "group_$groupId"
                }
                val groupConversation = Conversation(
                    id = groupConversationId,
                    name = "群组",
                    avatar = null,
                    lastMessage = null,
                    lastMessageTime = System.currentTimeMillis()
                )
                val chatFragment = mainActivity?.getOrCreateChatFragment(groupConversation)
                chatFragment?.addSkillMessage(skill, "我", isUserMessage = true)
                mainActivity?.switchToChatFragment(groupConversation)
                
                Toast.makeText(requireContext(), "技能已分享到群组", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "无法连接到服务器，请稍后重试", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "分享技能到群组失败: ${e.message}", e)
            Toast.makeText(requireContext(), "分享失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    
    /**
     * 切换编辑模式
     */
    private fun toggleEditMode() {
        val skill = this.skill ?: return
        
        if (isEditingSteps) {
            // 保存编辑
            saveSteps()
        } else {
            // 进入编辑模式：同时编辑技能名和步骤
            isEditingSteps = true
            val editButton = view?.findViewById<TextView>(R.id.btnEditSteps)
            editButton?.text = "保存"
            
            // 让技能标题变为可编辑状态（但不自动保存，等点击保存按钮时再保存）
            val titleView = view?.findViewById<TextView>(R.id.tvSkillTitle)
            if (titleView != null) {
                enableEditing(titleView) { newTitle ->
                    // 编辑模式下不自动保存，只更新本地显示
                    if (newTitle.isNotEmpty()) {
                        this.skill = this.skill?.copy(title = newTitle)
                        titleView.text = newTitle
                    }
                }
            }
            
            refreshStepsDisplay()
        }
    }
    
    /**
     * 保存步骤
     */
    private fun saveSteps() {
        val skill = this.skill ?: return
        val updatedSteps = mutableListOf<String>()
        
        stepEditTexts.forEachIndexed { index, editText ->
            val content = editText.text.toString().trim()
            if (content.isNotEmpty()) {
                updatedSteps.add(formatStepWithNumber(index, content))
            }
        }
        
        if (updatedSteps.isEmpty()) {
            Toast.makeText(requireContext(), "至少需要保留一个步骤", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 获取当前技能名（可能已编辑）
        // 优先从skill对象获取（因为enableEditing的回调会更新它），如果titleView是EditText则从EditText获取
        val titleView = view?.findViewById<View>(R.id.tvSkillTitle)
        val currentTitle = when {
            titleView is android.widget.EditText -> {
                // 如果还在编辑状态，从EditText获取
                titleView.text.toString().trim()
            }
            this.skill != null -> {
                // 优先使用已更新的skill对象中的title
                this.skill!!.title
            }
            else -> {
                // 最后使用原始skill的title
                skill.title
            }
        }
        
        val updatedSkill = skill.copy(
            title = currentTitle,
            steps = updatedSteps
        )
        
        // 更新技能（包括标题和步骤）
        if (updateSkill(updatedSkill)) {
            this.skill = updatedSkill
            isEditingSteps = false
            val editButton = view?.findViewById<TextView>(R.id.btnEditSteps)
            editButton?.text = "编辑"
            
            // 恢复标题显示（确保显示最新的标题）
            val finalTitleView = view?.findViewById<View>(R.id.tvSkillTitle)
            if (finalTitleView is TextView) {
                finalTitleView.text = currentTitle
            } else if (finalTitleView is android.widget.EditText) {
                // 如果还在编辑状态（用户未失焦就点保存），先恢复为TextView
                val parent = finalTitleView.parent as? android.view.ViewGroup
                if (parent != null) {
                    val index = parent.indexOfChild(finalTitleView)
                    val layoutParams = finalTitleView.layoutParams
                    val textView = TextView(requireContext()).apply {
                        id = R.id.tvSkillTitle
                        this.text = currentTitle
                        textSize = 18f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(0xFF000000.toInt())
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    }
                    textView.layoutParams = layoutParams
                    parent.removeView(finalTitleView)
                    parent.addView(textView, index, layoutParams)
                }
            }
            
            refreshStepsDisplay()
            Toast.makeText(requireContext(), "已保存", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "保存失败", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 更新技能（包括标题和步骤）
     */
    private fun updateSkill(updatedSkill: Skill): Boolean {
        return try {
            val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val existingSkills = SkillManager.loadSkills(requireContext()).toMutableList()
            
            val index = existingSkills.indexOfFirst { it.id == updatedSkill.id }
            if (index < 0) {
                return false
            }
            
            existingSkills[index] = updatedSkill
            
            val gson = com.google.gson.Gson()
            val skillsJson = gson.toJson(existingSkills)
            prefs.edit()
                .putString("my_skills", skillsJson)
                .apply()
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "更新技能失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 刷新步骤显示
     */
    private fun refreshStepsDisplay() {
        val view = view ?: return
        val skill = this.skill ?: return
        val stepsContainer = view.findViewById<LinearLayout>(R.id.llSkillSteps)
        stepsContainer.removeAllViews()
        stepEditTexts.clear()
        
        skill.steps.forEachIndexed { index, step ->
            if (isEditingSteps && allowEdit) {
                createEditableStepRow(stepsContainer, index, step)
            } else {
                createReadOnlyStepRow(stepsContainer, index, step)
            }
            
            // 添加分隔线（除了最后一项）
            if (index < skill.steps.size - 1) {
                val divider = android.view.View(requireContext()).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1
                    ).apply {
                        setMargins(0, 4, 0, 4)
                    }
                    setBackgroundColor(0xFFE0E0E0.toInt())
                }
                stepsContainer.addView(divider)
            }
        }
    }
    
    /**
     * 创建只读步骤行
     */
    private fun createReadOnlyStepRow(container: LinearLayout, index: Int, step: String) {
        // 格式化步骤显示文本，确保显示 "step1." 格式
        val displayText = formatStepForDisplay(index, step)
        
        val stepView = TextView(requireContext()).apply {
            text = displayText
            textSize = 15f
            setTextColor(0xFF333333.toInt())
            setPadding(0, 12, 0, 12)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(stepView)
    }
    
    /**
     * 创建可编辑步骤行
     */
    private fun createEditableStepRow(container: LinearLayout, index: Int, step: String) {
        val rowLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8, 0, 8)
            }
        }
        
        // 步骤前缀（不可编辑）
        val prefix = formatStepPrefix(index)
        val prefixView = TextView(requireContext()).apply {
            text = prefix
            textSize = 15f
            setTextColor(0xFF666666.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        rowLayout.addView(prefixView)
        
        // 步骤内容（可编辑）
        val content = extractStepContent(step)
        val editText = android.widget.EditText(requireContext()).apply {
            setText(content)
            textSize = 15f
            setTextColor(0xFF333333.toInt())
            setPadding(8, 12, 8, 12)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF5F5F5.toInt())
                cornerRadius = 4f
                setStroke(1, 0xFFE0E0E0.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                weight = 1f
            }
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        stepEditTexts.add(editText)
        rowLayout.addView(editText)
        
        // 删除按钮
        val deleteButton = android.widget.ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            contentDescription = "删除"
            layoutParams = LinearLayout.LayoutParams(
                40.dpToPx(),
                40.dpToPx()
            ).apply {
                setMargins(8, 0, 0, 0)
            }
            setOnClickListener {
                deleteStep(index)
            }
        }
        rowLayout.addView(deleteButton)
        
        // 添加按钮
        val addButton = android.widget.ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_input_add)
            background = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
            contentDescription = "添加"
            layoutParams = LinearLayout.LayoutParams(
                40.dpToPx(),
                40.dpToPx()
            ).apply {
                setMargins(4, 0, 0, 0)
            }
            setOnClickListener {
                showAddStepDialog(index)
            }
        }
        rowLayout.addView(addButton)
        
        container.addView(rowLayout)
    }
    
    /**
     * 删除步骤
     */
    private fun deleteStep(index: Int) {
        val skill = this.skill ?: return
        
        if (skill.steps.size <= 1) {
            Toast.makeText(requireContext(), "至少需要保留一个步骤", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 先收集当前所有步骤内容
        val currentContents = stepEditTexts.map { it.text.toString().trim() }
        val updatedContents = currentContents.toMutableList()
        updatedContents.removeAt(index)
        
        // 重新编号并格式化
        val reindexedSteps = updatedContents.mapIndexed { idx, content ->
            formatStepWithNumber(idx, content)
        }
        
        // 更新步骤显示（不保存，等点击保存按钮时再保存）
        this.skill = skill.copy(steps = reindexedSteps)
        refreshStepsDisplay()
        Toast.makeText(requireContext(), "步骤已删除", Toast.LENGTH_SHORT).show()
    }
    
    /**
     * 显示添加步骤对话框
     */
    private fun showAddStepDialog(currentIndex: Int) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("添加步骤")
            .setItems(arrayOf("上方加一行", "下方加一行")) { _, which ->
                when (which) {
                    0 -> addStep(currentIndex, true)
                    1 -> addStep(currentIndex, false)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 添加步骤
     */
    private fun addStep(currentIndex: Int, above: Boolean) {
        val skill = this.skill ?: return
        
        // 先收集当前所有步骤内容
        val currentContents = stepEditTexts.map { it.text.toString().trim() }
        val insertIndex = if (above) currentIndex else currentIndex + 1
        val updatedContents = currentContents.toMutableList()
        updatedContents.add(insertIndex, "")
        
        // 重新编号并格式化
        val reindexedSteps = updatedContents.mapIndexed { idx, content ->
            formatStepWithNumber(idx, content)
        }
        
        // 更新步骤显示（不保存，等点击保存按钮时再保存）
        this.skill = skill.copy(steps = reindexedSteps)
        refreshStepsDisplay()
    }
    
    /**
     * 格式化步骤前缀（如 "step1: "）
     */
    private fun formatStepPrefix(index: Int): String {
        return "step${index + 1}: "
    }
    
    /**
     * 提取步骤内容（去除前缀）
     */
    private fun extractStepContent(step: String): String {
        return step.replace(Regex("^(step\\d+[:：]?\\s*)", RegexOption.IGNORE_CASE), "").trim()
    }
    
    /**
     * 格式化步骤（带编号）
     */
    private fun formatStepWithNumber(index: Int, content: String): String {
        return "step${index + 1}: $content"
    }
    
    /**
     * 格式化步骤用于显示（使用 "step1." 格式）
     */
    private fun formatStepForDisplay(index: Int, step: String): String {
        // 提取步骤内容（去除可能存在的 "step1: " 前缀）
        val content = extractStepContent(step)
        // 使用 "step1." 格式显示
        return "step${index + 1}. $content"
    }
    
    
    /**
     * dp转px
     */
    private fun Int.dpToPx(): Int {
        return (this * requireContext().resources.displayMetrics.density).toInt()
    }
}
