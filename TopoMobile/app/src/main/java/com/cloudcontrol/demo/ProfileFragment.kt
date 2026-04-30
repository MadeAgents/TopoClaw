package com.cloudcontrol.demo

import android.Manifest
import android.app.DatePickerDialog
import android.content.BroadcastReceiver
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.fragment.app.FragmentTransaction
import com.cloudcontrol.demo.databinding.FragmentProfileBinding
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import com.cloudcontrol.demo.R

/**
 * 个人主页Fragment
 * 用于管理用户的个性化档案
 */
class ProfileFragment : Fragment() {
    
    companion object {
        private const val TAG = "ProfileFragment"
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        private const val REQUEST_CODE_PICK_IMAGE = 1001
        private const val AVATAR_MAX_SIZE = 200  // 头像最大尺寸（像素�?
        private const val REQUEST_MEDIA_PROJECTION_CHAT = 1002
        private const val REQUEST_OVERLAY_PERMISSION = 1003
        private const val REQUEST_NOTIFICATION_PERMISSION = 1004
        private const val REQUEST_MICROPHONE_PERMISSION = 1005
        private const val REQUEST_NETWORK_LOCATION_PERMISSION = 1006
    }
    
    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentImei: String? = null
    private var currentAvatarBase64: String? = null
    private var isEditMode = false
    private var currentProfile: UserProfile? = null
    private var isServiceDomainReceiverRegistered = false
    private val serviceDomainChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ConversationListFragment.ACTION_SERVICE_DOMAIN_CHANGED) {
                if (isEditMode) {
                    showViewMode()
                }
                loadProfile()
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 不在这里设置过渡动画，由MainActivity统一控制
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 设置Fragment层级的底部导航栏
        (activity as? MainActivity)?.let { mainActivity ->
            mainActivity.setupFragmentBottomNavigation(binding.bottomNavigation, R.id.nav_profile)
            mainActivity.setFragmentBottomNavigationBackgroundColor(binding.bottomNavigation, 0xFFF5F5F5.toInt())
            // 初始化并更新聊天图标徽章
            binding.root.post {
                mainActivity.initAndUpdateFragmentChatBadge(binding.bottomNavigation)
            }
        }
        
        setupAvatarClipping()
        setupUI()
        loadProfile()
    }
    
    /**
     * 设置头像的圆形裁�?
     */
    private fun setupAvatarClipping() {
        // 为查看模式的头像设置圆形裁剪
        binding.ivAvatar.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        binding.ivAvatar.clipToOutline = true
        
        // 为编辑模式的头像设置圆形裁剪
        binding.ivAvatarEdit.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        binding.ivAvatarEdit.clipToOutline = true
    }
    
    override fun onResume() {
        super.onResume()
        if (!isAdded || activity == null || isHidden) return
        // 确保ActionBar和底部导航栏显示（从详情页返回时需要恢复）
        try {
            (activity as? MainActivity)?.let { mainActivity ->
                mainActivity.supportActionBar?.hide()  // 个人主页隐藏ActionBar
                // 设置状态栏颜色为浅灰色，与顶部导航栏保持一致
                mainActivity.setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
                // 设置Fragment层级的底部导航栏背景颜色为浅灰色，与顶部导航栏保持一致
                mainActivity.setFragmentBottomNavigationBackgroundColor(binding.bottomNavigation, 0xFFF5F5F5.toInt())
                // 更新Fragment层级的底部导航栏选中状态
                binding.bottomNavigation.selectedItemId = R.id.nav_profile
                // 更新聊天图标徽章
                binding.root.post {
                    mainActivity.initAndUpdateFragmentChatBadge(binding.bottomNavigation)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "恢复UI失败: ${e.message}", e)
        }
        // 更新快捷设置按钮状态
        updateQuickSettingsButtons()
        // 页面重新可见时主动刷新，避免继续显示内存中的旧资料
        loadProfile()
    }

    override fun onStart() {
        super.onStart()
        registerServiceDomainChangedReceiver()
    }

    override fun onStop() {
        unregisterServiceDomainChangedReceiver()
        super.onStop()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded && _binding != null) {
            try {
                (activity as? MainActivity)?.let { mainActivity ->
                    mainActivity.supportActionBar?.hide()
                    mainActivity.setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
                    binding.bottomNavigation.selectedItemId = R.id.nav_profile
                    binding.root.post {
                        if (_binding != null) {
                            mainActivity.initAndUpdateFragmentChatBadge(binding.bottomNavigation)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "onHiddenChanged恢复UI失败: ${e.message}", e)
            }
            updateQuickSettingsButtons()
            // 从其他页面切回个人页时重新加载资料
            loadProfile()
        }
    }

    override fun onPause() {
        super.onPause()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        unregisterServiceDomainChangedReceiver()
        _binding = null
        mainScope.cancel()
    }

    private fun registerServiceDomainChangedReceiver() {
        if (isServiceDomainReceiverRegistered || !isAdded) return
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            serviceDomainChangedReceiver,
            IntentFilter(ConversationListFragment.ACTION_SERVICE_DOMAIN_CHANGED)
        )
        isServiceDomainReceiverRegistered = true
    }

    private fun unregisterServiceDomainChangedReceiver() {
        if (!isServiceDomainReceiverRegistered || !isAdded) return
        try {
            LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(serviceDomainChangedReceiver)
        } catch (_: Exception) {
            // ignore: receiver may already be unregistered in edge lifecycle paths
        } finally {
            isServiceDomainReceiverRegistered = false
        }
    }
    
    private fun setupUI() {
        // 返回按钮已移除，因为个人主页现在是底部导航栏的一个tab
        // 左侧使用占位View保持标题居中
        
        // 设置按钮
        binding.btnSettings.setOnClickListener {
            navigateToSettings()
        }
        
        // 更换头像按钮（仅在编辑模式显示）
        binding.btnChangeAvatar.setOnClickListener {
            pickImage()
        }
        
        // 生日选择�?
        binding.etBirthday.setOnClickListener {
            showDatePicker()
        }
        
        // 保存按钮
        binding.btnSave.setOnClickListener {
            saveProfile()
        }
        
        // 清除个性化档案按钮
        binding.btnClear.setOnClickListener {
            showClearConfirmDialog()
        }
        
        
        // 初始状态：显示查看模式
        showViewMode()
        
        // 设置服务入口
        setupServiceEntry()
        
        // 设置快捷设置按钮
        setupQuickSettings()
    }
    
    /**
     * 显示查看模式
     */
    private fun showViewMode() {
        isEditMode = false
        binding.viewModeLayout.visibility = View.VISIBLE
        binding.editModeLayout.visibility = View.GONE
        // 查看模式下，整个viewModeLayout区域可点击进入详情页（包括头像、名字等所有位置）
        binding.viewModeLayout.setOnClickListener {
            navigateToDetailPage()
        }
        // 箭头也可以点击进入详情页（需要阻止事件冒泡到viewModeLayout�?
        binding.ivArrowForward.setOnClickListener { view ->
            view.isClickable = true
            navigateToDetailPage()
        }
        
        // avatarClickArea也可以点击进入详情页
        binding.avatarClickArea.setOnClickListener { view ->
            view.isClickable = true
            navigateToDetailPage()
        }
        updateViewModeContent()
    }
    
    /**
     * 导航到设置页�?
     */
    private fun navigateToSettings() {
        val settingsFragment = SettingsFragment()
        parentFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_from_right,  // enter: 设置页面从右侧滑入
                R.anim.slide_out_to_left,    // exit: 个人主页向左滑出
                R.anim.slide_in_from_left_slow,   // popEnter: 返回时，个人主页从左侧滑入（慢速）
                R.anim.slide_out_to_right_slow    // popExit: 返回时，设置页面向右滑出（慢速）
            )
            .replace(R.id.fragmentContainer, settingsFragment)
            .addToBackStack(null)
            .commitAllowingStateLoss()
    }
    
    /**
     * 导航到详情页
     */
    private fun navigateToDetailPage() {
        // 如果 currentProfile �?null，从本地同步加载（避免异步问题）
        val profile = currentProfile ?: ProfileManager.loadProfileLocally(requireContext())
        
        // 如果本地也没有，创建一个默认的 profile
        val profileToShow = profile ?: UserProfile(
            imei = ProfileManager.getOrGenerateImei(requireContext()),
            name = null,
            gender = null,
            address = null,
            phone = null,
            birthday = null,
            preferences = null,
            avatar = null
        )
        
        val detailFragment = ProfileDetailFragment.newInstance(profileToShow)
        parentFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_from_right,  // enter: 新Fragment从右侧滑�?
                R.anim.slide_out_to_left,    // exit: 旧Fragment向左滑出
                R.anim.slide_in_from_left,   // popEnter: 返回时，新Fragment从左侧滑�?
                R.anim.slide_out_to_right    // popExit: 返回时，旧Fragment向右滑出
            )
            .replace(R.id.fragmentContainer, detailFragment)
            .addToBackStack(null)
            .commit()
    }
    
    
    /**
     * 进入编辑模式
     */
    private fun enterEditMode() {
        isEditMode = true
        binding.viewModeLayout.visibility = View.GONE
        binding.editModeLayout.visibility = View.VISIBLE
        
        // 同步头像到编辑模�?
        if (currentAvatarBase64 != null) {
            val bitmap = base64ToBitmap(currentAvatarBase64!!)
            if (bitmap != null) {
                binding.ivAvatarEdit.setImageBitmap(bitmap)
            }
        } else {
            // 同步查看模式的头像到编辑模式
            val drawable = binding.ivAvatar.drawable
            if (drawable != null) {
                binding.ivAvatarEdit.setImageDrawable(drawable)
            }
        }
    }
    
    /**
     * 从详情页进入编辑模式（公共方法）
     */
    fun enterEditModeFromDetail(profile: UserProfile) {
        try {
            // 确保 view 已经创建
            if (_binding == null) {
                Log.w(TAG, "enterEditModeFromDetail: binding is null, Fragment view not created yet")
                return
            }
            
            // 重新加载最新数�?
            currentProfile = profile
            currentAvatarBase64 = profile.avatar
            fillForm(profile)
            enterEditMode()
        } catch (e: Exception) {
            Log.e(TAG, "enterEditModeFromDetail 失败: ${e.message}", e)
        }
    }
    
    /**
     * 取消编辑
     */
    private fun cancelEdit() {
        // 恢复原始数据
        currentProfile?.let {
            fillForm(it)
        }
        showViewMode()
    }
    
    /**
     * 更新查看模式的内容显�?
     */
    private fun updateViewModeContent() {
        val profile = currentProfile
        val imei = currentImei ?: ProfileManager.getOrGenerateImei(requireContext())
        
        if (profile == null) {
            binding.tvName.text = imei  // 默认显示IMEI
            binding.tvGender.text = getString(R.string.not_filled)
            binding.tvAddress.visibility = View.GONE
            binding.tvPhone.visibility = View.GONE
            binding.tvBirthday.visibility = View.GONE
            binding.tvPreferences.visibility = View.GONE
            return
        }
        
        // 更新头像
        if (!profile.avatar.isNullOrEmpty()) {
            val bitmap = base64ToBitmap(profile.avatar)
            if (bitmap != null) {
                binding.ivAvatar.setImageBitmap(bitmap)
            } else {
                binding.ivAvatar.setImageResource(R.drawable.ic_person)
            }
        } else {
            binding.ivAvatar.setImageResource(R.drawable.ic_person)
        }
        
        // 更新姓名（如果为空则显示IMEI�?
        binding.tvName.text = profile.name ?: imei
        
        // 更新性别
        binding.tvGender.text = profile.gender ?: getString(R.string.not_filled)
        
        // 更新地址
        if (!profile.address.isNullOrEmpty()) {
            binding.tvAddress.text = "地址：${profile.address}"
            binding.tvAddress.visibility = View.VISIBLE
        } else {
            binding.tvAddress.visibility = View.GONE
        }
        
        // 更新电话
        if (!profile.phone.isNullOrEmpty()) {
            binding.tvPhone.text = "电话：${profile.phone}"
            binding.tvPhone.visibility = View.VISIBLE
        } else {
            binding.tvPhone.visibility = View.GONE
        }
        
        // 更新生日
        if (!profile.birthday.isNullOrEmpty()) {
            binding.tvBirthday.text = "生日：${profile.birthday}"
            binding.tvBirthday.visibility = View.VISIBLE
        } else {
            binding.tvBirthday.visibility = View.GONE
        }
        
        // 更新喜好
        if (!profile.preferences.isNullOrEmpty()) {
            binding.tvPreferences.text = "喜好：${profile.preferences}"
            binding.tvPreferences.visibility = View.VISIBLE
        } else {
            binding.tvPreferences.visibility = View.GONE
        }
    }
    
    /**
     * 选择图片
     */
    private fun pickImage() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE)
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            REQUEST_CODE_PICK_IMAGE -> {
                if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                    val imageUri: Uri? = data.data
                    if (imageUri != null) {
                        loadAndSetAvatar(imageUri)
                    }
                }
            }
            REQUEST_MEDIA_PROJECTION_CHAT -> {
                // 处理聊天截图权限
                if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                    val intent = Intent(requireContext(), ChatScreenshotService::class.java).apply {
                        action = ChatScreenshotService.ACTION_START
                        putExtra(ChatScreenshotService.EXTRA_RESULT_CODE, resultCode)
                        putExtra(ChatScreenshotService.EXTRA_RESULT_DATA, data)
                    }
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        requireContext().startForegroundService(intent)
                    } else {
                        requireContext().startService(intent)
                    }
                    
                    (activity as? MainActivity)?.addLog("聊天截图权限已授权")
                    Toast.makeText(requireContext(), "聊天截图权限已授权", Toast.LENGTH_SHORT).show()
                    updateQuickSettingsButtons()
                } else {
                    (activity as? MainActivity)?.addLog("聊天截图权限授权失败")
                    Toast.makeText(requireContext(), "聊天截图权限授权失败", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_OVERLAY_PERMISSION -> {
                // 处理悬浮窗权限
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Settings.canDrawOverlays(requireContext())) {
                        (activity as? MainActivity)?.addLog("悬浮窗权限已授权")
                        Toast.makeText(requireContext(), "悬浮窗权限已授权", Toast.LENGTH_SHORT).show()
                        InteractionOverlayManager.initialize(requireContext())
                    } else {
                        (activity as? MainActivity)?.addLog("悬浮窗权限授权失败")
                        Toast.makeText(requireContext(), "悬浮窗权限授权失败，请手动在设置中开启", Toast.LENGTH_LONG).show()
                    }
                    updateQuickSettingsButtons()
                }
            }
        }
    }
    
    /**
     * 加载并设置头�?
     */
    private fun loadAndSetAvatar(uri: Uri) {
        mainScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    loadBitmapFromUri(uri)
                }
                
                if (bitmap != null) {
                    // 压缩并转换为Base64
                    val compressedBitmap = resizeBitmap(bitmap, AVATAR_MAX_SIZE)
                    currentAvatarBase64 = bitmapToBase64(compressedBitmap)
                    
                    // 显示头像（根据当前模式）
                    if (isEditMode) {
                        binding.ivAvatarEdit.setImageBitmap(compressedBitmap)
                    } else {
                        binding.ivAvatar.setImageBitmap(compressedBitmap)
                    }
                    
                    Toast.makeText(requireContext(), "头像已更新", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "加载图片失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载头像失败: ${e.message}", e)
                Toast.makeText(requireContext(), "加载头像失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 从URI加载Bitmap
     */
    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream? = requireContext().contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "从URI加载Bitmap失败: ${e.message}", e)
            null
        }
    }
    
    /**
     * 调整Bitmap大小
     */
    private fun resizeBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        if (width <= maxSize && height <= maxSize) {
            return bitmap
        }
        
        val scale = minOf(maxSize.toFloat() / width, maxSize.toFloat() / height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
    
    /**
     * Bitmap转Base64
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }
    
    /**
     * Base64转Bitmap
     */
    private fun base64ToBitmap(base64: String): Bitmap? {
        return try {
            val byteArray = Base64.decode(base64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
        } catch (e: Exception) {
            Log.e(TAG, "Base64转Bitmap失败: ${e.message}", e)
            null
        }
    }
    
    /**
     * 显示日期选择�?
     */
    private fun showDatePicker() {
        val calendar = Calendar.getInstance()
        // 如果有已选择的日期，解析�?
        val currentDate = binding.etBirthday.text.toString()
        if (!TextUtils.isEmpty(currentDate)) {
            try {
                val date = DATE_FORMAT.parse(currentDate)
                if (date != null) {
                    calendar.time = date
                }
            } catch (e: Exception) {
                Log.w(TAG, "解析日期失败: $currentDate", e)
            }
        }
        
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        
        DatePickerDialog(
            requireContext(),
            { _, selectedYear, selectedMonth, selectedDay ->
                val selectedDate = Calendar.getInstance().apply {
                    set(selectedYear, selectedMonth, selectedDay)
                }
                binding.etBirthday.setText(DATE_FORMAT.format(selectedDate.time))
            },
            year,
            month,
            day
        ).show()
    }
    
    /**
     * 加载个性化档案
     */
    private fun loadProfile() {
        mainScope.launch {
            try {
                // 获取或生成IMEI
                currentImei = ProfileManager.getOrGenerateImei(requireContext())
                val imei = currentImei ?: return@launch
                
                // 先尝试从本地加载
                val localProfile = ProfileManager.loadProfileLocally(requireContext())
                if (localProfile != null) {
                    // 如果用户名为空，设置为IMEI
                    val profileWithDefaultName = if (localProfile.name.isNullOrEmpty()) {
                        localProfile.copy(name = imei)
                    } else {
                        localProfile
                    }
                    currentProfile = profileWithDefaultName
                    fillForm(profileWithDefaultName)
                    updateViewModeContent()
                } else {
                    // 本地没有数据，创建一个默认profile，姓名为IMEI
                    val defaultProfile = UserProfile(
                        imei = imei,
                        name = imei,  // 默认姓名为IMEI
                        gender = null,
                        address = null,
                        phone = null,
                        birthday = null,
                        preferences = null,
                        avatar = null
                    )
                    currentProfile = defaultProfile
                    fillForm(defaultProfile)
                    updateViewModeContent()
                }
                
                // 然后从云侧加载最新数�?
                loadProfileFromCloud(imei)
            } catch (e: Exception) {
                Log.e(TAG, "加载个性化档案失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 从云侧加载个性化档案
     */
    private suspend fun loadProfileFromCloud(imei: String) = withContext(Dispatchers.IO) {
        try {
            // 初始�?CustomerServiceNetwork
            val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
            CustomerServiceNetwork.initialize(customerServiceUrl)
            
            val apiService = CustomerServiceNetwork.getApiService()
            if (apiService == null) {
                Log.w(TAG, "CustomerServiceNetwork未初始化，跳过云侧加载")
                return@withContext
            }
            
            val response = apiService.getProfile(imei)
            
            if (response.isSuccessful && response.body()?.success == true) {
                val profile = response.body()?.profile
                val profileForDisplay = if (profile != null) {
                    if (profile.name.isNullOrEmpty()) profile.copy(name = imei) else profile
                } else {
                    UserProfile(imei = imei, name = imei)
                }
                withContext(Dispatchers.Main) {
                    currentProfile = profileForDisplay
                    fillForm(profileForDisplay)
                    // 切域名后应以当前域名云侧数据为准，避免沿用旧域名头像/昵称
                    ProfileManager.saveProfileLocally(requireContext(), profileForDisplay)
                    if (!isEditMode) {
                        updateViewModeContent()
                    }
                }
            } else {
                if (response.code() == 404 || response.body()?.success == false) {
                    val emptyProfile = UserProfile(imei = imei, name = imei)
                    withContext(Dispatchers.Main) {
                        currentProfile = emptyProfile
                        fillForm(emptyProfile)
                        ProfileManager.saveProfileLocally(requireContext(), emptyProfile)
                        if (!isEditMode) {
                            updateViewModeContent()
                        }
                    }
                } else {
                    Log.d(TAG, "云侧没有个性化档案数据")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "从云侧加载个性化档案失败: ${e.message}", e)
            // 网络错误不影响本地数据的使用
        }
    }
    
    /**
     * 填充表单（编辑模式）
     */
    private fun fillForm(profile: UserProfile) {
        currentProfile = profile
        val imei = currentImei ?: ProfileManager.getOrGenerateImei(requireContext())
        
        // 设置姓名（如果为空则使用IMEI作为默认值）
        binding.etName.setText(profile.name ?: imei)
        
        when (profile.gender) {
            "男" -> binding.rgGender.check(R.id.rbMale)
            "女" -> binding.rgGender.check(R.id.rbFemale)
            "其他" -> binding.rgGender.check(R.id.rbOther)
            else -> binding.rgGender.clearCheck()
        }
        
        binding.etAddress.setText(profile.address ?: "")
        binding.etPhone.setText(profile.phone ?: "")
        binding.etBirthday.setText(profile.birthday ?: "")
        binding.etPreferences.setText(profile.preferences ?: "")
        
        // 加载头像
        currentAvatarBase64 = profile.avatar
        if (!profile.avatar.isNullOrEmpty()) {
            val bitmap = base64ToBitmap(profile.avatar)
            if (bitmap != null) {
                if (isEditMode) {
                    binding.ivAvatarEdit.setImageBitmap(bitmap)
                } else {
                    binding.ivAvatar.setImageBitmap(bitmap)
                }
            } else {
                // Base64解码失败，使用默认图�?
                if (isEditMode) {
                    binding.ivAvatarEdit.setImageResource(R.drawable.ic_person)
                } else {
                    binding.ivAvatar.setImageResource(R.drawable.ic_person)
                }
                // 清除无效的avatar数据
                currentAvatarBase64 = null
            }
        } else {
            // 没有头像，使用默认图�?
            if (isEditMode) {
                binding.ivAvatarEdit.setImageResource(R.drawable.ic_person)
            } else {
                binding.ivAvatar.setImageResource(R.drawable.ic_person)
            }
        }
    }
    
    /**
     * 保存个性化档案
     */
    private fun saveProfile() {
        val imei = currentImei ?: ProfileManager.getOrGenerateImei(requireContext())
        
        // 获取表单数据
        val name = binding.etName.text.toString().trim()
        val gender = when (binding.rgGender.checkedRadioButtonId) {
            R.id.rbMale -> "男"
            R.id.rbFemale -> "女"
            R.id.rbOther -> "其他"
            else -> null
        }
        val address = binding.etAddress.text.toString().trim()
        val phone = binding.etPhone.text.toString().trim()
        val birthday = binding.etBirthday.text.toString().trim()
        val preferences = binding.etPreferences.text.toString().trim()
        
        // 验证生日格式（如果填写了�?
        if (birthday.isNotEmpty()) {
            try {
                DATE_FORMAT.parse(birthday)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "生日格式不正确，请使用 YYYY-MM-DD 格式", Toast.LENGTH_SHORT).show()
                return
            }
        }
        
        // 构建UserProfile对象（如果姓名为空，使用IMEI作为默认值）
        val newProfile = UserProfile(
            imei = imei,
            name = if (name.isEmpty()) imei else name,
            gender = gender,
            address = if (address.isEmpty()) null else address,
            phone = if (phone.isEmpty()) null else phone,
            birthday = if (birthday.isEmpty()) null else birthday,
            preferences = if (preferences.isEmpty()) null else preferences,
            avatar = currentAvatarBase64
        )
        
        // 检查数据是否有变化
        val oldProfile = currentProfile
        val hasChanges = oldProfile == null || !profilesEqual(oldProfile, newProfile)
        val avatarChanged = oldProfile?.avatar != currentAvatarBase64
        
        // 如果头像变化，清除群头像缓存
        if (avatarChanged) {
            GroupAvatarHelper.clearGroupAvatarCache()
        }
        
        // 保存到本地（无论是否有变化，本地都要保存�?
        ProfileManager.saveProfileLocally(requireContext(), newProfile)
        currentProfile = newProfile  // 立即更新currentProfile，确保头像显示正�?
        
        // 只有在数据有变化时才同步到云�?
        if (hasChanges) {
            // 同步到云�?
            mainScope.launch {
                binding.btnSave.isEnabled = false
                binding.btnSave.text = "保存中..."
                
                try {
                    val success = saveProfileToCloud(newProfile)
                    if (success) {
                        Toast.makeText(requireContext(), "保存成功", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "本地保存成功，云侧同步失败", Toast.LENGTH_SHORT).show()
                    }
                    // 无论云侧是否成功，都退出编辑模式（因为本地已保存）
                    showViewMode()
                } catch (e: Exception) {
                    Log.e(TAG, "保存个性化档案失败: ${e.message}", e)
                    Toast.makeText(requireContext(), "本地保存成功，云侧同步失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    // 即使云侧失败，也退出编辑模式（因为本地已保存）
                    showViewMode()
                } finally {
                    binding.btnSave.isEnabled = true
                    binding.btnSave.text = "保存"
                }
            }
        } else {
            // 数据没有变化，直接退出编辑模�?
            Toast.makeText(requireContext(), "没有更改", Toast.LENGTH_SHORT).show()
            showViewMode()
        }
    }
    
    /**
     * 比较两个 UserProfile 是否相等（忽�?updatedAt 字段�?
     */
    private fun profilesEqual(profile1: UserProfile, profile2: UserProfile): Boolean {
        return profile1.name == profile2.name &&
                profile1.gender == profile2.gender &&
                profile1.address == profile2.address &&
                profile1.phone == profile2.phone &&
                profile1.birthday == profile2.birthday &&
                profile1.preferences == profile2.preferences &&
                profile1.avatar == profile2.avatar
    }
    
    /**
     * 保存个性化档案到云�?
     */
    private suspend fun saveProfileToCloud(profile: UserProfile): Boolean = withContext(Dispatchers.IO) {
        try {
            // 初始�?CustomerServiceNetwork
            val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
            CustomerServiceNetwork.initialize(customerServiceUrl)
            
            val apiService = CustomerServiceNetwork.getApiService()
            if (apiService == null) {
                Log.w(TAG, "CustomerServiceNetwork未初始化")
                return@withContext false
            }
            
            val response = apiService.updateProfile(
                imei = profile.imei,
                name = profile.name,
                gender = profile.gender,
                address = profile.address,
                phone = profile.phone,
                birthday = profile.birthday,
                preferences = profile.preferences,
                avatar = profile.avatar
            )
            
            response.isSuccessful && response.body()?.success == true
        } catch (e: Exception) {
            Log.e(TAG, "保存到云侧失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * 显示清除确认对话�?
     */
    private fun showClearConfirmDialog() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("清除个性化档案")
            .setMessage("确定要清除个性化档案吗？此操作不可恢复，将清除所有个人信息并重置IMEI。")
            .setPositiveButton("确定") { _, _ ->
                clearProfile()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 清除个性化档案
     */
    private fun clearProfile() {
        mainScope.launch {
            binding.btnClear.isEnabled = false
            binding.btnClear.text = "清除中..."
            
            try {
                val imei = currentImei ?: ProfileManager.getImei(requireContext())
                
                // 删除云侧档案
                if (imei != null) {
                    deleteProfileFromCloud(imei)
                }
                
                // 清除本地数据
                ProfileManager.clearProfile(requireContext())
                
                // 清除群头像缓存（因为用户头像已清除）
                GroupAvatarHelper.clearGroupAvatarCache()
                
                // 生成新的IMEI
                currentImei = ProfileManager.getOrGenerateImei(requireContext())
                
                // 重置表单
                resetForm()
                currentProfile = null
                
                // 更新查看模式
                if (!isEditMode) {
                    updateViewModeContent()
                }
                
                Toast.makeText(requireContext(), "个性化档案已清除", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "清除个性化档案失败: ${e.message}", e)
                Toast.makeText(requireContext(), "清除失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnClear.isEnabled = true
                binding.btnClear.text = "清除个性化档案"
            }
        }
    }
    
    /**
     * 删除云侧个性化档案
     */
    private suspend fun deleteProfileFromCloud(imei: String) = withContext(Dispatchers.IO) {
        try {
            // 初始�?CustomerServiceNetwork
            val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
            CustomerServiceNetwork.initialize(customerServiceUrl)
            
            val apiService = CustomerServiceNetwork.getApiService()
            if (apiService == null) {
                Log.w(TAG, "CustomerServiceNetwork未初始化")
                return@withContext
            }
            
            val response = apiService.deleteProfile(imei)
            
            if (response.isSuccessful && response.body()?.success == true) {
                Log.d(TAG, "云侧个性化档案已删除")
            } else {
                Log.w(TAG, "删除云侧个性化档案失败")
            }
        } catch (e: Exception) {
            Log.w(TAG, "删除云侧个性化档案失败: ${e.message}", e)
            // 即使云侧删除失败，也继续清除本地数据
        }
    }
    
    /**
     * 重置表单
     */
    private fun resetForm() {
        val imei = currentImei ?: ProfileManager.getOrGenerateImei(requireContext())
        binding.etName.setText(imei)  // 默认显示IMEI
        binding.rgGender.clearCheck()
        binding.etAddress.setText("")
        binding.etPhone.setText("")
        binding.etBirthday.setText("")
        binding.etPreferences.setText("")
        binding.ivAvatar.setImageResource(R.drawable.ic_person)
        binding.ivAvatarEdit.setImageResource(R.drawable.ic_person)
        currentAvatarBase64 = null
    }
    
    /**
     * 设置服务入口
     */
    private fun setupServiceEntry() {
        binding.llServiceEntry.setOnClickListener {
            navigateToService()
        }
    }
    
    /**
     * 导航到服务页�?
     */
    private fun navigateToService() {
        val serviceFragment = ServiceSettingsFragment()
        parentFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_from_right,  // enter: 服务页面从右侧滑入
                R.anim.slide_out_to_left,    // exit: 个人主页向左滑出
                R.anim.slide_in_from_left_slow,   // popEnter: 返回时，个人主页从左侧滑入（慢速）
                R.anim.slide_out_to_right_slow    // popExit: 返回时，服务页面向右滑出（慢速）
            )
            .replace(R.id.fragmentContainer, serviceFragment)
            .addToBackStack(null)
            .commitAllowingStateLoss()
    }
    
    /**
     * 设置快捷设置按钮
     */
    private fun setupQuickSettings() {
        binding.btnOpenAccessibility.setOnClickListener {
            openAccessibilitySettings()
        }
        
        binding.btnRequestChatPermission.setOnClickListener {
            requestChatScreenshotPermission()
        }
        
        binding.btnRequestOverlayPermission.setOnClickListener {
            requestOverlayPermission()
        }
        
        binding.btnRequestNotificationPermission.setOnClickListener {
            requestNotificationPermission()
        }
        
        binding.btnRequestMicrophonePermission.setOnClickListener {
            requestMicrophonePermission()
        }
        binding.btnRequestNetworkLocationPermission.setOnClickListener {
            requestNetworkLocationPermission()
        }
        
        // 更新按钮状态
        updateQuickSettingsButtons()
    }
    
    /**
     * 打开无障碍设置
     */
    private fun openAccessibilitySettings() {
        try {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            (activity as? MainActivity)?.addLog("已打开无障碍设置，请找到\"TopoClaw\"并开启")
            Toast.makeText(requireContext(), "请找到\"TopoClaw\"并开启", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "打开无障碍设置失败: ${e.message}", e)
            Toast.makeText(requireContext(), "打开设置失败", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 请求聊天截图权限
     */
    private fun requestChatScreenshotPermission() {
        val mediaProjectionManager = requireContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION_CHAT)
        (activity as? MainActivity)?.addLog("正在请求聊天截图权限...")
    }
    
    /**
     * 请求悬浮窗权限
     */
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(requireContext())) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
                (activity as? MainActivity)?.addLog("正在请求悬浮窗权限...")
                Toast.makeText(requireContext(), "请在设置中开启'显示在其他应用的上层'权限", Toast.LENGTH_LONG).show()
            } else {
                (activity as? MainActivity)?.addLog("悬浮窗权限已授权")
                Toast.makeText(requireContext(), "悬浮窗权限已授权", Toast.LENGTH_SHORT).show()
                InteractionOverlayManager.initialize(requireContext())
            }
        } else {
            (activity as? MainActivity)?.addLog("Android版本过低，不需要悬浮窗权限")
            Toast.makeText(requireContext(), "Android版本过低，不需要悬浮窗权限", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 请求通知权限
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) 
                == PackageManager.PERMISSION_GRANTED) {
                (activity as? MainActivity)?.addLog("通知权限已授权")
                Toast.makeText(requireContext(), "通知权限已授权", Toast.LENGTH_SHORT).show()
                updateQuickSettingsButtons()
            } else {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION)
                (activity as? MainActivity)?.addLog("正在请求通知权限...")
            }
        } else {
            // Android 12及以下，跳转到应用通知设置
            try {
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, requireContext().packageName)
                }
                startActivity(intent)
                (activity as? MainActivity)?.addLog("已打开通知设置页面")
                Toast.makeText(requireContext(), "请在设置中开启通知权限", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "打开通知设置失败: ${e.message}", e)
                Toast.makeText(requireContext(), "无法打开通知设置", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 请求麦克风权限
     */
    private fun requestMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) 
            == PackageManager.PERMISSION_GRANTED) {
            (activity as? MainActivity)?.addLog("麦克风权限已授权")
            Toast.makeText(requireContext(), "麦克风权限已授权", Toast.LENGTH_SHORT).show()
            // 更新快捷设置按钮状态
            updateQuickSettingsButtons()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE_PERMISSION)
            (activity as? MainActivity)?.addLog("正在请求麦克风权限...")
            Toast.makeText(requireContext(), "请在弹窗中授予麦克风权限", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 请求地理位置权限（粗定位，基于网络）
     */
    private fun requestNetworkLocationPermission() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
            == PackageManager.PERMISSION_GRANTED
        ) {
            (activity as? MainActivity)?.addLog("地理位置权限已授权")
            Toast.makeText(requireContext(), "地理位置权限已授权", Toast.LENGTH_SHORT).show()
            updateQuickSettingsButtons()
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_NETWORK_LOCATION_PERMISSION)
            (activity as? MainActivity)?.addLog("正在请求地理位置权限...")
            Toast.makeText(requireContext(), "请在弹窗中授予地理位置权限", Toast.LENGTH_LONG).show()
        }
    }
    
    /**
     * 更新快捷设置按钮状态
     */
    private fun updateQuickSettingsButtons() {
        // 更新无障碍服务按钮
        val isAccessibilityEnabled = (activity as? MainActivity)?.isAccessibilityServiceEnabled() ?: false
        binding.btnOpenAccessibility.isEnabled = !isAccessibilityEnabled
        binding.btnOpenAccessibility.alpha = if (isAccessibilityEnabled) 0.5f else 1.0f
        
        // 更新截图权限按钮
        val chatService = ChatScreenshotService.getInstance()
        val isScreenshotReady = chatService != null && chatService.isReady()
        binding.btnRequestChatPermission.isEnabled = !isScreenshotReady
        binding.btnRequestChatPermission.alpha = if (isScreenshotReady) 0.5f else 1.0f
        
        // 更新悬浮窗权限按钮
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val hasOverlayPermission = Settings.canDrawOverlays(requireContext())
            binding.btnRequestOverlayPermission.isEnabled = !hasOverlayPermission
            binding.btnRequestOverlayPermission.alpha = if (hasOverlayPermission) 0.5f else 1.0f
        } else {
            binding.btnRequestOverlayPermission.isEnabled = false
            binding.btnRequestOverlayPermission.alpha = 0.5f
        }
        
        // 更新通知权限按钮
        val hasNotificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            val notificationManager = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            notificationManager?.areNotificationsEnabled() ?: false
        }
        binding.btnRequestNotificationPermission.isEnabled = !hasNotificationPermission
        binding.btnRequestNotificationPermission.alpha = if (hasNotificationPermission) 0.5f else 1.0f
        
        // 更新麦克风权限按钮
        val hasMicrophonePermission = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        binding.btnRequestMicrophonePermission.isEnabled = !hasMicrophonePermission
        binding.btnRequestMicrophonePermission.alpha = if (hasMicrophonePermission) 0.5f else 1.0f

        // 更新地理位置权限按钮（粗定位）
        val hasLocationPermission =
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        binding.btnRequestNetworkLocationPermission.isEnabled = !hasLocationPermission
        binding.btnRequestNetworkLocationPermission.alpha = if (hasLocationPermission) 0.5f else 1.0f
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                (activity as? MainActivity)?.addLog("通知权限已授权")
                Toast.makeText(requireContext(), "通知权限已授权", Toast.LENGTH_SHORT).show()
            } else {
                (activity as? MainActivity)?.addLog("通知权限授权失败")
                Toast.makeText(requireContext(), "通知权限授权失败，请手动在设置中开启", Toast.LENGTH_LONG).show()
            }
            updateQuickSettingsButtons()
        } else if (requestCode == REQUEST_MICROPHONE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                (activity as? MainActivity)?.addLog("麦克风权限已授权")
                Toast.makeText(requireContext(), "麦克风权限已授权", Toast.LENGTH_SHORT).show()
            } else {
                (activity as? MainActivity)?.addLog("麦克风权限授权失败")
                Toast.makeText(requireContext(), "麦克风权限授权失败，请手动在设置中开启", Toast.LENGTH_LONG).show()
            }
            updateQuickSettingsButtons()
        } else if (requestCode == REQUEST_NETWORK_LOCATION_PERMISSION) {
            val granted = ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                (activity as? MainActivity)?.addLog("地理位置权限已授权")
                Toast.makeText(requireContext(), "地理位置权限已授权", Toast.LENGTH_SHORT).show()
            } else {
                (activity as? MainActivity)?.addLog("地理位置权限授权失败")
                Toast.makeText(requireContext(), "地理位置权限授权失败，请手动在设置中开启", Toast.LENGTH_LONG).show()
            }
            updateQuickSettingsButtons()
        }
    }
    
}
