package com.cloudcontrol.demo

import android.app.DatePickerDialog
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
import androidx.fragment.app.Fragment
import com.cloudcontrol.demo.databinding.FragmentProfileDetailBinding
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.roundToInt

/**
 * 用户资料详情页Fragment
 */
class ProfileDetailFragment : Fragment() {
    
    companion object {
        private const val TAG = "ProfileDetailFragment"
        private const val ARG_PROFILE = "profile"
        private const val REQUEST_CODE_PICK_IMAGE = 1001
        private const val AVATAR_MAX_SIZE = 200  // 头像最大尺寸（像素）
        private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        
        fun newInstance(profile: UserProfile): ProfileDetailFragment {
            val fragment = ProfileDetailFragment()
            val args = Bundle()
            args.putSerializable(ARG_PROFILE, profile)
            fragment.arguments = args
            return fragment
        }
    }
    
    private var _binding: FragmentProfileDetailBinding? = null
    private val binding get() = _binding!!
    
    private var profile: UserProfile? = null
    private var originalProfile: UserProfile? = null  // 保存原始数据，用于取消编辑
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentAvatarBase64: String? = null
    private var isEditMode = false
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileDetailBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 获取传递的profile数据
        profile = arguments?.getSerializable(ARG_PROFILE) as? UserProfile
        originalProfile = profile?.let { 
            UserProfile(
                imei = it.imei,
                name = it.name,
                gender = it.gender,
                address = it.address,
                phone = it.phone,
                birthday = it.birthday,
                preferences = it.preferences,
                avatar = it.avatar
            )
        }
        currentAvatarBase64 = profile?.avatar
        
        // 隐藏ActionBar，因为详情页有自己的标题栏
        (activity as? MainActivity)?.supportActionBar?.hide()
        
        setupUI()
        setupAvatarClipping()
        displayProfile()
    }
    
    override fun onResume() {
        super.onResume()
        // 检查Fragment是否还存在
        if (!isAdded || activity == null) return
        // 隐藏底部导航栏
        try {
            (activity as? MainActivity)?.setBottomNavigationVisibility(false)
        } catch (e: Exception) {
            Log.w(TAG, "隐藏底部导航栏失败: ${e.message}", e)
        }
    }
    
    override fun onPause() {
        super.onPause()
        // 检查Fragment是否还存在
        if (!isAdded || activity == null) return
        // 显示底部导航栏
        try {
            (activity as? MainActivity)?.setBottomNavigationVisibility(true)
        } catch (e: Exception) {
            Log.w(TAG, "显示底部导航栏失败: ${e.message}", e)
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        mainScope.cancel()
        _binding = null
    }
    
    private fun setupUI() {
        // 返回按钮
        binding.btnBack.setOnClickListener {
            // 检查Fragment是否还存在
            if (_binding == null || !isAdded) return@setOnClickListener
            if (isEditMode) {
                // 如果在编辑模式，取消编辑
                cancelEdit()
            } else {
                // 否则返回
                try {
                    parentFragmentManager.popBackStack()
                } catch (e: Exception) {
                    Log.e(TAG, "返回失败: ${e.message}", e)
                }
            }
        }
        
        // 编辑按钮
        binding.btnEdit.setOnClickListener {
            if (isEditMode) {
                // 如果已经在编辑模式，保存
                saveProfile()
            } else {
                // 进入编辑模式
                enterEditMode()
            }
        }
        
        // 保存按钮
        binding.btnSave.setOnClickListener {
            saveProfile()
        }
        
        // 取消按钮
        binding.btnCancel.setOnClickListener {
            cancelEdit()
        }
        
        // 更换头像按钮（编辑模式）
        binding.btnChangeAvatar.setOnClickListener {
            pickImage()
        }
        
        // 查看模式下的头像点击事件：直接换头像
        binding.ivAvatar.setOnClickListener {
            pickImage()
        }
        
        // 生日选择器
        binding.etBirthday.setOnClickListener {
            showDatePicker()
        }
        
        // 清空资料按钮
        binding.btnClear.setOnClickListener {
            showClearConfirmDialog()
        }
        
        // 我的二维码入口
        binding.llMyQRCode.setOnClickListener {
            // 检查Fragment是否还存在
            if (_binding == null || !isAdded) return@setOnClickListener
            try {
                val qrCodeFragment = MyQRCodeFragment()
                parentFragmentManager.beginTransaction()
                    .setCustomAnimations(
                        R.anim.slide_in_from_right_slow,  // enter: 二维码页面从右侧滑入（慢速）
                        R.anim.slide_out_to_left,          // exit: 详情页向左滑出
                        R.anim.slide_in_from_left,         // popEnter: 返回时，详情页从左侧滑入
                        R.anim.slide_out_to_right          // popExit: 返回时，二维码页面向右滑出
                    )
                    .replace(R.id.fragmentContainer, qrCodeFragment)
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
            } catch (e: Exception) {
                Log.e(TAG, "打开我的二维码页面失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 设置头像的圆形裁剪
     */
    private fun setupAvatarClipping() {
        binding.ivAvatar.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        binding.ivAvatar.clipToOutline = true
        
        binding.ivAvatarEdit.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        binding.ivAvatarEdit.clipToOutline = true
    }
    
    /**
     * 显示用户资料（查看模式）
     */
    private fun displayProfile() {
        val p = profile ?: return
        
        // 显示头像和背景
        if (!p.avatar.isNullOrEmpty()) {
            val bitmap = base64ToBitmap(p.avatar)
            if (bitmap != null) {
                binding.ivAvatar.setImageBitmap(bitmap)
                // 设置模糊背景
                setBlurredBackground(bitmap, binding.ivAvatarBackground)
            } else {
                binding.ivAvatar.setImageResource(R.drawable.ic_person)
                binding.ivAvatarBackground.setImageResource(R.drawable.ic_person)
            }
        } else {
            binding.ivAvatar.setImageResource(R.drawable.ic_person)
            binding.ivAvatarBackground.setImageResource(R.drawable.ic_person)
        }
        
        // 显示姓名（头部和详情）
        val name = p.name ?: getString(R.string.not_filled)
        binding.tvNameHeader.text = name
        
        // 显示性别
        binding.tvGender.text = when (p.gender) {
            "男" -> getString(R.string.male)
            "女" -> getString(R.string.female)
            "其他" -> getString(R.string.other)
            else -> p.gender ?: getString(R.string.not_filled)
        }
        
        // 显示IMEI
        binding.tvImei.text = p.imei ?: getString(R.string.not_filled)
        
        // 显示地址
        binding.tvAddress.text = p.address ?: getString(R.string.not_filled)
        
        // 显示电话
        binding.tvPhone.text = p.phone ?: getString(R.string.not_filled)
        
        // 显示生日
        binding.tvBirthday.text = p.birthday ?: getString(R.string.not_filled)
        
        // 显示喜好
        binding.tvPreferences.text = p.preferences ?: getString(R.string.not_filled)
    }
    
    /**
     * 进入编辑模式
     */
    private fun enterEditMode() {
        // 检查Fragment是否还存在
        if (_binding == null || !isAdded) return
        isEditMode = true
        binding.viewModeLayout.visibility = View.GONE
        binding.editModeLayout.visibility = View.VISIBLE
        binding.btnEdit.text = "保存"
        
        // 填充表单
        fillForm(profile ?: return)
    }
    
    /**
     * 退出编辑模式
     */
    private fun exitEditMode() {
        // 检查Fragment是否还存在
        if (_binding == null || !isAdded) return
        isEditMode = false
        binding.viewModeLayout.visibility = View.VISIBLE
        binding.editModeLayout.visibility = View.GONE
        binding.btnEdit.text = "编辑"
        
        // 刷新显示（包括背景）
        displayProfile()
    }
    
    /**
     * 填充表单（编辑模式）
     */
    private fun fillForm(profile: UserProfile) {
        binding.etName.setText(profile.name ?: "")
        
        when (profile.gender) {
            "男" -> binding.rgGender.check(R.id.rbMale)
            "女" -> binding.rgGender.check(R.id.rbFemale)
            "其他" -> binding.rgGender.check(R.id.rbOther)
            else -> binding.rgGender.clearCheck()
        }
        
        // 设置IMEI（不可编辑）
        binding.etImei.setText(profile.imei ?: "")
        
        binding.etAddress.setText(profile.address ?: "")
        binding.etPhone.setText(profile.phone ?: "")
        binding.etBirthday.setText(profile.birthday ?: "")
        binding.etPreferences.setText(profile.preferences ?: "")
        
        // 加载头像和背景
        if (!profile.avatar.isNullOrEmpty()) {
            val bitmap = base64ToBitmap(profile.avatar)
            if (bitmap != null) {
                binding.ivAvatarEdit.setImageBitmap(bitmap)
                // 设置模糊背景
                setBlurredBackground(bitmap, binding.ivAvatarBackgroundEdit)
            } else {
                binding.ivAvatarEdit.setImageResource(R.drawable.ic_person)
                binding.ivAvatarBackgroundEdit.setImageResource(R.drawable.ic_person)
            }
        } else {
            binding.ivAvatarEdit.setImageResource(R.drawable.ic_person)
            binding.ivAvatarBackgroundEdit.setImageResource(R.drawable.ic_person)
        }
    }
    
    /**
     * 保存个性化档案
     */
    private fun saveProfile() {
        val p = profile ?: return
        val imei = p.imei ?: ProfileManager.getOrGenerateImei(requireContext())
        
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
        
        // 验证生日格式（如果填写了）
        if (birthday.isNotEmpty()) {
            try {
                DATE_FORMAT.parse(birthday)
            } catch (e: Exception) {
                val ctx = context ?: return
                Toast.makeText(ctx, "生日格式不正确，请使用 YYYY-MM-DD 格式", Toast.LENGTH_SHORT).show()
                return
            }
        }
        
        // 构建UserProfile对象
        val newProfile = UserProfile(
            imei = imei,
            name = if (name.isEmpty()) null else name,
            gender = gender,
            address = if (address.isEmpty()) null else address,
            phone = if (phone.isEmpty()) null else phone,
            birthday = if (birthday.isEmpty()) null else birthday,
            preferences = if (preferences.isEmpty()) null else preferences,
            avatar = currentAvatarBase64
        )
        
        // 更新profile
        profile = newProfile
        
        // 保存到本地
        ProfileManager.saveProfileLocally(requireContext(), newProfile)
        
        // 同步到云侧
        mainScope.launch {
            // 检查Fragment是否还存在
            if (_binding == null || !isAdded) return@launch
            binding.btnSave.isEnabled = false
            binding.btnSave.text = "保存中..."
            
            try {
                val success = saveProfileToCloud(newProfile)
                // 再次检查Fragment是否还存在
                if (_binding == null || !isAdded) return@launch
                if (success) {
                    Toast.makeText(requireContext(), "保存成功", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "本地保存成功，云侧同步失败", Toast.LENGTH_SHORT).show()
                }
                // 退出编辑模式
                exitEditMode()
            } catch (e: Exception) {
                Log.e(TAG, "保存失败: ${e.message}", e)
                if (_binding != null && isAdded) {
                    Toast.makeText(requireContext(), "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (_binding != null && isAdded) {
                    binding.btnSave.isEnabled = true
                    binding.btnSave.text = "保存"
                }
            }
        }
    }
    
    /**
     * 取消编辑
     */
    private fun cancelEdit() {
        // 恢复原始数据
        originalProfile?.let {
            profile = it
            currentAvatarBase64 = it.avatar
        }
        exitEditMode()
    }
    
    /**
     * 显示日期选择器
     */
    private fun showDatePicker() {
        val calendar = Calendar.getInstance()
        var year = calendar.get(Calendar.YEAR)
        var month = calendar.get(Calendar.MONTH)
        var day = calendar.get(Calendar.DAY_OF_MONTH)
        
        // 如果已有生日，解析并设置
        val birthday = binding.etBirthday.text.toString()
        if (birthday.isNotEmpty()) {
            try {
                val date = DATE_FORMAT.parse(birthday)
                if (date != null) {
                    calendar.time = date
                    year = calendar.get(Calendar.YEAR)
                    month = calendar.get(Calendar.MONTH)
                    day = calendar.get(Calendar.DAY_OF_MONTH)
                }
            } catch (e: Exception) {
                Log.w(TAG, "解析生日失败: ${e.message}")
            }
        }
        
        DatePickerDialog(
            requireContext(),
            { _, selectedYear, selectedMonth, selectedDay ->
                // 检查Fragment是否还存在
                if (_binding == null || !isAdded) return@DatePickerDialog
                val selectedDate = Calendar.getInstance()
                selectedDate.set(selectedYear, selectedMonth, selectedDay)
                binding.etBirthday.setText(DATE_FORMAT.format(selectedDate.time))
            },
            year,
            month,
            day
        ).show()
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
                    
                    // 如果当前在编辑模式，更新编辑模式的头像和背景
                    if (isEditMode) {
                        binding.ivAvatarEdit.setImageBitmap(compressedBitmap)
                        setBlurredBackground(compressedBitmap, binding.ivAvatarBackgroundEdit)
                    } else {
                        // 如果当前在查看模式，只更新查看模式的头像和背景并保存
                        binding.ivAvatar.setImageBitmap(compressedBitmap)
                        setBlurredBackground(compressedBitmap, binding.ivAvatarBackground)
                        // 立即保存头像（不进入编辑模式）
                        saveAvatarOnly()
                    }
                    
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
     * 仅保存头像（不进入编辑模式）
     */
    private fun saveAvatarOnly() {
        val p = profile ?: return
        val imei = p.imei ?: ProfileManager.getOrGenerateImei(requireContext())
        
        // 构建UserProfile对象（只更新头像）
        val newProfile = UserProfile(
            imei = imei,
            name = p.name,
            gender = p.gender,
            address = p.address,
            phone = p.phone,
            birthday = p.birthday,
            preferences = p.preferences,
            avatar = currentAvatarBase64
        )
        
        // 更新profile
        profile = newProfile
        originalProfile = newProfile
        
        // 保存到本地
        ProfileManager.saveProfileLocally(requireContext(), newProfile)
        
        // 同步到云侧
        mainScope.launch {
            // 检查Fragment是否还存在
            if (_binding == null || !isAdded) return@launch
            try {
                val success = saveProfileToCloud(newProfile)
                if (success) {
                    Log.d(TAG, "头像已同步到云侧")
                } else {
                    Log.w(TAG, "头像本地保存成功，云侧同步失败")
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存头像到云侧失败: ${e.message}", e)
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
                Log.d(TAG, "已同步到云侧")
                true
            } else {
                Log.w(TAG, "同步到云侧失败")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "保存到云侧失败: ${e.message}", e)
            false
        }
    }
    
    /**
     * Base64转Bitmap
     */
    private fun base64ToBitmap(base64String: String): Bitmap? {
        return try {
            val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Base64转Bitmap失败: ${e.message}", e)
            null
        }
    }
    
    /**
     * 设置模糊背景图片
     * 将头像图片缩放并模糊处理后设置为背景
     */
    private fun setBlurredBackground(avatarBitmap: Bitmap, backgroundImageView: android.widget.ImageView) {
        mainScope.launch {
            try {
                val blurredBitmap = withContext(Dispatchers.Default) {
                    blurBitmap(avatarBitmap, 25f)
                }
                // 检查Fragment是否还存在
                if (_binding != null && isAdded) {
                    backgroundImageView.setImageBitmap(blurredBitmap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "设置模糊背景失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 模糊Bitmap（使用快速缩放模糊算法）
     */
    private fun blurBitmap(bitmap: Bitmap, blurRadius: Float): Bitmap {
        // 先缩小图片以提高性能（背景不需要太高分辨率）
        val scale = 0.2f
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        var scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
        
        // 使用多次缩放来模拟模糊效果（快速且有效）
        val iterations = 3
        for (i in 0 until iterations) {
            val tempWidth = (scaledBitmap.width * 0.7f).roundToInt().coerceAtLeast(1)
            val tempHeight = (scaledBitmap.height * 0.7f).roundToInt().coerceAtLeast(1)
            val temp = Bitmap.createScaledBitmap(scaledBitmap, tempWidth, tempHeight, true)
            if (i > 0) {
                scaledBitmap.recycle()
            }
            scaledBitmap = Bitmap.createScaledBitmap(temp, width, height, true)
            temp.recycle()
        }
        
        return scaledBitmap
    }
    
    /**
     * 显示清空确认对话框
     */
    private fun showClearConfirmDialog() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("清空所有个人资料")
            .setMessage("确定要清空所有个人资料吗？此操作不可恢复，将清除所有个人信息（包括头像、昵称、性别、地址、电话、生日、喜好等）。")
            .setPositiveButton("确定") { _, _ ->
                clearProfile()
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 清空所有个人资料
     */
    private fun clearProfile() {
        mainScope.launch {
            // 检查Fragment是否还存在
            if (_binding == null || !isAdded) return@launch
            
            binding.btnClear.isEnabled = false
            binding.btnClear.text = "清空中..."
            
            try {
                val imei = profile?.imei ?: ProfileManager.getImei(requireContext())
                
                // 删除云侧档案
                if (imei != null) {
                    deleteProfileFromCloud(imei)
                }
                
                // 清除本地数据
                ProfileManager.clearProfile(requireContext())
                
                // 重置初始设置完成标记，以便下次启动时重新显示初始设置界面
                val appPrefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                appPrefs.edit().putBoolean("initial_setup_completed", false).apply()
                
                // 清除群头像缓存（因为用户头像已清除）
                GroupAvatarHelper.clearGroupAvatarCache()
                
                // 生成新的IMEI
                val newImei = ProfileManager.getOrGenerateImei(requireContext())
                
                // 创建空的profile
                val emptyProfile = UserProfile(
                    imei = newImei,
                    name = null,
                    gender = null,
                    address = null,
                    phone = null,
                    birthday = null,
                    preferences = null,
                    avatar = null
                )
                
                // 更新profile
                profile = emptyProfile
                originalProfile = emptyProfile
                currentAvatarBase64 = null
                
                // 重置表单
                fillForm(emptyProfile)
                
                // 更新显示
                displayProfile()
                
                // 退出编辑模式
                exitEditMode()
                
                // 再次检查Fragment是否还存在
                if (_binding != null && isAdded) {
                    Toast.makeText(requireContext(), "个人资料已清空", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "清空个人资料失败: ${e.message}", e)
                if (_binding != null && isAdded) {
                    Toast.makeText(requireContext(), "清空失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (_binding != null && isAdded) {
                    binding.btnClear.isEnabled = true
                    binding.btnClear.text = "清空所有个人资料"
                }
            }
        }
    }
    
    /**
     * 删除云侧个性化档案
     */
    private suspend fun deleteProfileFromCloud(imei: String) = withContext(Dispatchers.IO) {
        try {
            // 初始化 CustomerServiceNetwork
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
}
