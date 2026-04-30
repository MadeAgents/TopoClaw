package com.cloudcontrol.demo

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.cloudcontrol.demo.databinding.FragmentInitialSetupBinding
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * 初始设置Fragment
 * 用于引导用户设置头像和昵称
 */
class InitialSetupFragment : Fragment() {
    
    companion object {
        private const val TAG = "InitialSetupFragment"
        private const val REQUEST_CODE_PICK_IMAGE = 1001
        private const val AVATAR_MAX_SIZE = 200  // 头像最大尺寸（像素）
        
        fun newInstance(): InitialSetupFragment {
            return InitialSetupFragment()
        }
    }
    
    private var _binding: FragmentInitialSetupBinding? = null
    private val binding get() = _binding!!
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentAvatarBase64: String? = null
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentInitialSetupBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupUI()
        loadExistingProfile()
    }
    
    override fun onResume() {
        super.onResume()
        // 隐藏底部导航栏
        (activity as? MainActivity)?.setBottomNavigationVisibility(false)
        // 设置状态栏颜色为页面背景色 #F5F5F5
        (activity as? MainActivity)?.setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
    }
    
    override fun onPause() {
        super.onPause()
        // 显示底部导航栏
        (activity as? MainActivity)?.setBottomNavigationVisibility(true)
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        mainScope.cancel()
        _binding = null
    }
    
    private fun setupUI() {
        // 头像点击事件
        binding.ivAvatar.setOnClickListener {
            pickImage()
        }
        
        // 更换头像按钮点击事件
        binding.btnChangeAvatar.setOnClickListener {
            pickImage()
        }
        
        // 完成按钮点击事件
        binding.btnComplete.setOnClickListener {
            saveProfile()
        }
        
        // 暂时跳过按钮点击事件
        binding.tvSkip.setOnClickListener {
            skipSetup()
        }
        
        // 设置圆形头像
        binding.ivAvatar.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        binding.ivAvatar.clipToOutline = true
    }
    
    /**
     * 加载已有资料（如果有）
     */
    private fun loadExistingProfile() {
        mainScope.launch {
            try {
                val profile = ProfileManager.loadProfileLocally(requireContext())
                if (profile != null) {
                    // 如果有昵称，填充到输入框
                    if (!profile.name.isNullOrEmpty()) {
                        binding.etNickname.setText(profile.name)
                    }
                    
                    // 如果有头像，显示头像
                    if (!profile.avatar.isNullOrEmpty()) {
                        loadAvatar(profile.avatar)
                        currentAvatarBase64 = profile.avatar
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载已有资料失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 加载头像
     */
    private fun loadAvatar(avatarBase64: String?) {
        if (!avatarBase64.isNullOrEmpty()) {
            try {
                val decodedBytes = Base64.decode(avatarBase64, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                if (bitmap != null) {
                    binding.ivAvatar.setImageBitmap(bitmap)
                    Log.d(TAG, "头像已加载")
                } else {
                    binding.ivAvatar.setImageResource(R.drawable.ic_person)
                    binding.ivAvatar.background = ContextCompat.getDrawable(requireContext(), R.drawable.circle_background)
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载头像失败: ${e.message}", e)
                binding.ivAvatar.setImageResource(R.drawable.ic_person)
                binding.ivAvatar.background = ContextCompat.getDrawable(requireContext(), R.drawable.circle_background)
            }
        } else {
            binding.ivAvatar.setImageResource(R.drawable.ic_person)
            binding.ivAvatar.background = ContextCompat.getDrawable(requireContext(), R.drawable.circle_background)
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
        
        if (requestCode == REQUEST_CODE_PICK_IMAGE && resultCode == android.app.Activity.RESULT_OK && data != null) {
            val imageUri: Uri? = data.data
            if (imageUri != null) {
                loadAndSetAvatar(imageUri)
            }
        }
    }
    
    /**
     * 加载并设置头像
     */
    private fun loadAndSetAvatar(uri: Uri) {
        mainScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    loadBitmapFromUri(uri)
                }
                
                // 检查Fragment是否还存在
                if (_binding == null || !isAdded) return@launch
                
                if (bitmap != null) {
                    // 压缩并转换为Base64
                    val compressedBitmap = resizeBitmap(bitmap, AVATAR_MAX_SIZE)
                    currentAvatarBase64 = bitmapToBase64(compressedBitmap)
                    
                    // 再次检查Fragment是否还存在
                    if (_binding == null || !isAdded) return@launch
                    
                    // 更新头像显示
                    binding.ivAvatar.setImageBitmap(compressedBitmap)
                    
                    Toast.makeText(requireContext(), "头像已更新", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "加载图片失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载头像失败: ${e.message}", e)
                if (_binding != null && isAdded) {
                    Toast.makeText(requireContext(), "加载头像失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * 保存资料
     */
    private fun saveProfile() {
        val nickname = binding.etNickname.text.toString().trim()
        
        // 如果昵称为空，提示用户
        if (nickname.isEmpty()) {
            Toast.makeText(requireContext(), "请输入昵称", Toast.LENGTH_SHORT).show()
            return
        }
        
        mainScope.launch {
            try {
                val imei = ProfileManager.getOrGenerateImei(requireContext())
                
                // 加载已有资料（如果有）
                val existingProfile = ProfileManager.loadProfileLocally(requireContext())
                
                // 构建UserProfile对象
                val newProfile = UserProfile(
                    imei = imei,
                    name = nickname,
                    gender = existingProfile?.gender,
                    address = existingProfile?.address,
                    phone = existingProfile?.phone,
                    birthday = existingProfile?.birthday,
                    preferences = existingProfile?.preferences,
                    avatar = currentAvatarBase64
                )
                
                // 保存到本地
                ProfileManager.saveProfileLocally(requireContext(), newProfile)
                
                // 同步到云侧
                try {
                    val success = saveProfileToCloud(newProfile)
                    if (success) {
                        Log.d(TAG, "资料已同步到云侧")
                    } else {
                        Log.w(TAG, "资料本地保存成功，云侧同步失败")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "保存资料到云侧失败: ${e.message}", e)
                }
                
                // 标记初始设置已完成
                val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                prefs.edit().putBoolean("initial_setup_completed", true).apply()
                
                // 关闭Fragment，返回主界面
                if (_binding != null && isAdded) {
                    navigateToMain()
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存资料失败: ${e.message}", e)
                if (_binding != null && isAdded) {
                    Toast.makeText(requireContext(), "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * 暂时跳过
     */
    private fun skipSetup() {
        // 标记用户已跳过初始设置（但可以稍后再设置）
        val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putBoolean("initial_setup_completed", true).apply()
        
        // 关闭Fragment，返回主界面
        navigateToMain()
    }
    
    /**
     * 导航到主界面
     */
    private fun navigateToMain() {
        try {
            (activity as? MainActivity)?.let { mainActivity ->
                mainActivity.switchToTabFragment(R.id.nav_chat) { ConversationListFragment() }
                mainActivity.supportActionBar?.show()
                mainActivity.setBottomNavigationVisibility(true)
                mainActivity.setBottomNavigationSelectedItem(R.id.nav_chat)
            }
        } catch (e: Exception) {
            Log.e(TAG, "导航到主界面失败: ${e.message}", e)
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
        
        val scale = maxSize.toFloat() / Math.max(width, height)
        val newWidth = (width * scale).toInt()
        val newHeight = (height * scale).toInt()
        
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
    
    /**
     * Bitmap转Base64
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.DEFAULT)
    }
    
    /**
     * 保存到云侧
     */
    private suspend fun saveProfileToCloud(profile: UserProfile): Boolean = withContext(Dispatchers.IO) {
        try {
            // 初始化 CustomerServiceNetwork
            val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
            CustomerServiceNetwork.initialize(customerServiceUrl)
            
            val apiService = CustomerServiceNetwork.getApiService()
            if (apiService == null) {
                Log.w(TAG, "CustomerServiceNetwork未初始化")
                return@withContext false
            }
            
            // 调用API保存资料
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
            
            if (response.isSuccessful && response.body()?.success == true) {
                Log.d(TAG, "资料已同步到云侧")
                true
            } else {
                Log.w(TAG, "资料本地保存成功，云侧同步失败")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存资料到云侧失败: ${e.message}", e)
            false
        }
    }
}

