package com.cloudcontrol.demo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.cloudcontrol.demo.databinding.FragmentScanQrcodeBinding
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import kotlinx.coroutines.*
import java.io.InputStream

/**
 * 扫码Fragment
 * 用于扫描二维码，主要用于添加好友
 */
class ScanQRCodeFragment : Fragment() {
    
    companion object {
        private const val TAG = "ScanQRCodeFragment"
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1001
        private const val REQUEST_CODE_PICK_IMAGE = 1002
    }
    
    private var _binding: FragmentScanQrcodeBinding? = null
    private val binding get() = _binding!!
    private var isScanning = false
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentScanQrcodeBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 使用post延迟执行，确保在MainActivity的updateUIForCurrentFragment之后执行
        view.post {
            val mainActivity = activity as? MainActivity
            
            // 隐藏ActionBar（顶部导航栏）
            mainActivity?.hideActionBarWithoutAnimation()
            
            // 隐藏底部导航栏
            mainActivity?.setBottomNavigationVisibility(false)
            
            // 隐藏状态栏（全屏模式）
            mainActivity?.hideStatusBar()
        }
        
        setupUI()
        checkCameraPermission()
    }
    
    override fun onResume() {
        super.onResume()
        // 确保状态栏、ActionBar和底部导航栏都被隐藏
        // 使用post延迟执行，确保在MainActivity的updateUIForCurrentFragment之后执行
        binding.root.post {
            val mainActivity = activity as? MainActivity
            mainActivity?.hideActionBarWithoutAnimation()
            mainActivity?.setBottomNavigationVisibility(false)
            mainActivity?.hideStatusBar()
        }
        
        if (hasCameraPermission()) {
            startScanning()
        }
    }
    
    override fun onPause() {
        super.onPause()
        stopScanning()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        stopScanning()
        mainScope.cancel()
        
        // 恢复状态栏显示
        (activity as? MainActivity)?.showStatusBar()
        
        _binding = null
    }
    
    /**
     * 设置UI
     */
    private fun setupUI() {
        // 返回按钮
        binding.btnBack.setOnClickListener {
            // 恢复状态栏显示
            (activity as? MainActivity)?.showStatusBar()
            parentFragmentManager.popBackStack()
        }
        
        // 我的二维码按钮
        binding.llMyQRCode.setOnClickListener {
            navigateToMyQRCode()
        }
        
        // 我的相册按钮
        binding.llMyAlbum.setOnClickListener {
            pickImageFromAlbum()
        }
        
        // 设置扫码回调
        binding.barcodeScanner.decodeContinuous(callback)
        
        // English模式下隐藏“将二维码放入框内，即可自动扫描”提示（英文版该字符串为空）
        if (getString(R.string.scan_qr_tip).isEmpty()) {
            binding.tvScanTip.visibility = View.GONE
        }
    }
    
    /**
     * 导航到我的二维码页面
     */
    private fun navigateToMyQRCode() {
        try {
            val qrCodeFragment = MyQRCodeFragment()
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.slide_in_from_right,
                    R.anim.slide_out_to_left,
                    R.anim.slide_in_from_left,
                    R.anim.slide_out_to_right
                )
                .replace(R.id.fragmentContainer, qrCodeFragment)
                .addToBackStack(null)
                .commit()
        } catch (e: Exception) {
            Log.e(TAG, "打开我的二维码页面失败: ${e.message}", e)
            Toast.makeText(requireContext(), "打开失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 从相册选择图片
     */
    private fun pickImageFromAlbum() {
        try {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE)
        } catch (e: Exception) {
            Log.e(TAG, "选择图片失败: ${e.message}", e)
            Toast.makeText(requireContext(), "选择图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    /**
     * 处理图片选择结果
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            REQUEST_CODE_PICK_IMAGE -> {
                if (resultCode == android.app.Activity.RESULT_OK && data != null) {
                    val imageUri: Uri? = data.data
                    if (imageUri != null) {
                        // 识别图片中的二维码
                        recognizeQRCodeFromImage(imageUri)
                    }
                }
            }
        }
    }
    
    /**
     * 从图片中识别二维码
     */
    private fun recognizeQRCodeFromImage(imageUri: Uri) {
        // 停止相机扫码，避免干扰
        stopScanning()
        
        mainScope.launch {
            try {
                // 显示加载对话框
                val progressDialog = android.app.ProgressDialog(requireContext()).apply {
                    setMessage("正在识别二维码...")
                    setCancelable(false)
                    show()
                }
                
                try {
                    // 在后台线程加载图片
                    val bitmap = withContext(Dispatchers.IO) {
                        loadBitmapFromUri(imageUri)
                    }
                    
                    if (bitmap == null) {
                        progressDialog.dismiss()
                        Toast.makeText(requireContext(), "加载图片失败", Toast.LENGTH_SHORT).show()
                        // 重新开始相机扫码
                        startScanning()
                        return@launch
                    }
                    
                    // 识别二维码
                    val qrCodeText = withContext(Dispatchers.IO) {
                        decodeQRCode(bitmap)
                    }
                    
                    progressDialog.dismiss()
                    
                    if (qrCodeText != null && qrCodeText.isNotEmpty()) {
                        // 识别成功，立即处理结果（就像相机扫码一样）
                        Log.d(TAG, "从图片识别到二维码: $qrCodeText")
                        handleScanResult(qrCodeText)
                    } else {
                        Toast.makeText(requireContext(), "未识别到二维码，请确保图片中包含清晰的二维码", Toast.LENGTH_LONG).show()
                        // 重新开始相机扫码
                        startScanning()
                    }
                } catch (e: Exception) {
                    progressDialog.dismiss()
                    Log.e(TAG, "识别二维码失败: ${e.message}", e)
                    Toast.makeText(requireContext(), "识别失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    // 重新开始相机扫码
                    startScanning()
                }
            } catch (e: Exception) {
                Log.e(TAG, "显示进度对话框失败: ${e.message}", e)
                Toast.makeText(requireContext(), "识别失败: ${e.message}", Toast.LENGTH_SHORT).show()
                // 重新开始相机扫码
                startScanning()
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
     * 识别Bitmap中的二维码
     */
    private fun decodeQRCode(bitmap: Bitmap): String? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            
            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            
            val reader = MultiFormatReader()
            val result = reader.decode(binaryBitmap)
            
            result.text
        } catch (e: Exception) {
            Log.e(TAG, "识别二维码失败: ${e.message}", e)
            null
        }
    }
    
    /**
     * 检查相机权限
     */
    private fun checkCameraPermission() {
        if (hasCameraPermission()) {
            startScanning()
        } else {
            requestCameraPermission()
        }
    }
    
    /**
     * 检查是否有相机权限
     */
    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * 请求相机权限
     */
    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            requireActivity(),
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_REQUEST_CODE
        )
    }
    
    /**
     * 处理权限请求结果
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startScanning()
            } else {
                Toast.makeText(requireContext(), "需要相机权限才能扫码", Toast.LENGTH_SHORT).show()
                // 延迟返回，让用户看到提示
                binding.root.postDelayed({
                    parentFragmentManager.popBackStack()
                }, 2000)
            }
        }
    }
    
    /**
     * 开始扫码
     */
    private fun startScanning() {
        if (!isScanning && hasCameraPermission()) {
            try {
                binding.barcodeScanner.resume()
                isScanning = true
                Log.d(TAG, "开始扫码")
            } catch (e: Exception) {
                Log.e(TAG, "启动扫码失败: ${e.message}", e)
                Toast.makeText(requireContext(), "启动相机失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 停止扫码
     */
    private fun stopScanning() {
        if (isScanning) {
            try {
                binding.barcodeScanner.pause()
                isScanning = false
                Log.d(TAG, "停止扫码")
            } catch (e: Exception) {
                Log.e(TAG, "停止扫码失败: ${e.message}", e)
            }
        }
    }
    
    /**
     * 扫码回调
     */
    private val callback = object : BarcodeCallback {
        override fun barcodeResult(result: BarcodeResult?) {
            if (result == null || result.text.isNullOrEmpty()) {
                return
            }
            
            val scannedText = result.text.trim()
            Log.d(TAG, "扫描到内容: $scannedText")
            
            // 停止扫码，避免重复扫描
            stopScanning()
            
            // 处理扫描结果
            handleScanResult(scannedText)
        }
        
        override fun possibleResultPoints(resultPoints: MutableList<com.google.zxing.ResultPoint>?) {
            // 可以在这里处理可能的扫描点，用于UI反馈
        }
    }
    
    /** PC 端扫码绑定的二维码前缀，格式 cma-bind:{bindingToken} */
    private val CMA_BIND_PREFIX = "cma-bind:"
    
    private data class PcBindingPayload(
        val token: String,
        val serverUrl: String? = null,
        val customerServiceUrl: String? = null,
        val chatAssistantUrl: String? = null,
        val skillCommunityUrl: String? = null
    )

    private data class ServiceConfigApplyResult(
        val hasServiceDomainChanged: Boolean,
        val customerServiceChanged: Boolean,
        val customerServiceUrl: String
    )

    private data class ResolvedServiceUrls(
        val customerServiceUrl: String?,
        val chatAssistantUrl: String?,
        val skillCommunityUrl: String?
    )
    
    /** 添加小助手链接前缀 assistant://add?type=...&url=... */
    private val ASSISTANT_ADD_PREFIX = "assistant://"
    
    /**
     * 处理扫描结果
     */
    private fun handleScanResult(scannedText: String) {
        if (scannedText.isEmpty()) {
            Toast.makeText(requireContext(), "扫描结果为空", Toast.LENGTH_SHORT).show()
            startScanning()
            return
        }
        
        // 检查是否是 PC 端扫码绑定
        if (scannedText.startsWith(CMA_BIND_PREFIX)) {
            val payload = parsePcBindingPayload(scannedText)
            if (payload != null) {
                submitBindingToken(payload)
                return
            }
        }
        
        // 检查是否是添加小助手链接
        if (scannedText.startsWith(ASSISTANT_ADD_PREFIX)) {
            val assistant = CustomAssistantManager.parseAssistantUrl(scannedText.trim(), requireContext())
            if (assistant != null) {
                CustomAssistantManager.add(requireContext(), assistant)
                Toast.makeText(requireContext(), getString(R.string.add_assistant_success), Toast.LENGTH_SHORT).show()
                LocalBroadcastManager.getInstance(requireContext())
                    .sendBroadcast(Intent(ConversationListFragment.ACTION_CUSTOM_ASSISTANT_ADDED))
                parentFragmentManager.popBackStack()
                return
            }
        }
        
        // 检查是否是URL
        if (isValidUrl(scannedText)) {
            // 如果是URL，直接用浏览器打开
            openUrlInBrowser(scannedText)
        } else {
            // 如果不是URL，假设是IMEI，显示添加好友确认对话框
            showAddFriendConfirmDialog(scannedText)
        }
    }
    
    /**
     * 判断字符串是否是有效的URL
     */
    private fun isValidUrl(text: String): Boolean {
        // 使用Android的Patterns.WEB_URL进行匹配
        // 同时也检查是否以http://或https://开头（更严格的判断）
        val trimmedText = text.trim()
        return Patterns.WEB_URL.matcher(trimmedText).matches() ||
               trimmedText.startsWith("http://", ignoreCase = true) ||
               trimmedText.startsWith("https://", ignoreCase = true) ||
               trimmedText.startsWith("www.", ignoreCase = true)
    }
    
    /**
     * 在浏览器中打开URL
     */
    private fun openUrlInBrowser(url: String) {
        try {
            var urlToOpen = url.trim()
            
            // 如果URL以www.开头但没有协议，添加https://
            if (urlToOpen.startsWith("www.", ignoreCase = true) && 
                !urlToOpen.startsWith("http://", ignoreCase = true) &&
                !urlToOpen.startsWith("https://", ignoreCase = true)) {
                urlToOpen = "https://$urlToOpen"
            }
            
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlToOpen))
            // 检查是否有应用可以处理这个Intent
            if (intent.resolveActivity(requireContext().packageManager) != null) {
                startActivity(intent)
                Log.d(TAG, "在浏览器中打开URL: $urlToOpen")
                // 打开浏览器后，延迟一下再重新开始扫码，让用户有时间看到浏览器打开
                binding.root.postDelayed({
                    if (isAdded) {
                        startScanning()
                    }
                }, 500)
            } else {
                Toast.makeText(requireContext(), "无法打开浏览器", Toast.LENGTH_SHORT).show()
                // 重新开始扫码
                startScanning()
            }
        } catch (e: Exception) {
            Log.e(TAG, "打开浏览器失败: ${e.message}", e)
            Toast.makeText(requireContext(), "打开浏览器失败: ${e.message}", Toast.LENGTH_SHORT).show()
            // 重新开始扫码
            startScanning()
        }
    }
    
    /**
     * 显示添加好友确认对话框
     */
    private fun showAddFriendConfirmDialog(friendImei: String) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("扫描结果")
            .setMessage("扫描到IMEI: $friendImei\n\n是否添加为好友？")
            .setPositiveButton("添加") { _, _ ->
                addFriend(friendImei)
            }
            .setNegativeButton("取消") { _, _ ->
                // 重新开始扫码
                startScanning()
            }
            .setOnDismissListener {
                // 对话框关闭时，如果还在当前页面，重新开始扫码
                if (isAdded && isScanning.not()) {
                    startScanning()
                }
            }
            .show()
    }
    
    /**
     * 添加好友
     */
    private fun addFriend(friendImei: String) {
        // 检查是否是自己
        val currentImei = ProfileManager.getOrGenerateImei(requireContext())
        if (friendImei == currentImei) {
            Toast.makeText(requireContext(), "不能添加自己为好友", Toast.LENGTH_SHORT).show()
            startScanning()
            return
        }
        
        // 检查是否已经是好友
        val existingFriend = FriendManager.getFriends(requireContext())
            .firstOrNull { it.imei == friendImei }
        if (existingFriend != null) {
            Toast.makeText(requireContext(), "该用户已经是您的好友", Toast.LENGTH_SHORT).show()
            startScanning()
            return
        }
        
        // 调用添加好友逻辑（复用FriendFragment的逻辑）
        addFriendToServer(friendImei)
    }
    
    /**
     * 扫码绑定：上报手机 IMEI 到服务端，供 PC 轮询获取
     */
    private fun submitBindingToken(payload: PcBindingPayload) {
        val loadingToast = Toast.makeText(requireContext(), "正在绑定...", Toast.LENGTH_SHORT)
        loadingToast.show()
        
        mainScope.launch {
            try {
                val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                val localCustomerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                    ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                val resolvedServiceUrls = resolveServiceUrlsFromPayload(payload)
                // 绑定 token 应优先提交到二维码声明的 customer_service，
                // 否则在跨域名切换场景下会出现手机“绑定成功”但 PC 轮询不到的问题。
                val customerServiceUrl = resolvedServiceUrls.customerServiceUrl
                    ?: normalizeBaseUrl(localCustomerServiceUrl)
                
                CustomerServiceNetwork.initialize(customerServiceUrl)
                val apiService = CustomerServiceNetwork.getApiService()
                
                if (apiService == null) {
                    loadingToast.cancel()
                    Toast.makeText(requireContext(), "无法连接服务器", Toast.LENGTH_SHORT).show()
                    startScanning()
                    return@launch
                }
                
                val imei = ProfileManager.getOrGenerateImei(requireContext())
                val response = apiService.submitBinding(payload.token, BindingSubmitRequest(imei))
                
                if (response.isSuccessful && response.body()?.success == true) {
                    val applyResult = applyPcServiceConfig(payload)
                    if (applyResult.hasServiceDomainChanged) {
                        refreshDataAfterServiceDomainChanged(applyResult)
                    }
                    loadingToast.cancel()
                    Toast.makeText(requireContext(), "绑定成功，已与 PC 端打通", Toast.LENGTH_LONG).show()
                    binding.root.postDelayed({
                        if (isAdded) {
                            (activity as? MainActivity)?.showStatusBar()
                            parentFragmentManager.popBackStack()
                        }
                    }, 1500)
                } else {
                    loadingToast.cancel()
                    val errorMsg = response.body()?.message ?: "绑定失败"
                    Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_SHORT).show()
                    startScanning()
                }
            } catch (e: Exception) {
                loadingToast.cancel()
                Log.e(TAG, "绑定失败: ${e.message}", e)
                Toast.makeText(requireContext(), "绑定失败: ${e.message ?: ""}", Toast.LENGTH_SHORT).show()
                startScanning()
            }
        }
    }

    private fun parsePcBindingPayload(scannedText: String): PcBindingPayload? {
        val raw = scannedText.removePrefix(CMA_BIND_PREFIX).trim()
        if (raw.isEmpty()) return null
        val token = raw.substringBefore('?').trim()
        if (token.isEmpty()) return null
        val query = raw.substringAfter('?', "")
        if (query.isEmpty()) return PcBindingPayload(token = token)
        val uri = Uri.parse("https://bind.local/?$query")
        return PcBindingPayload(
            token = token,
            serverUrl = uri.getQueryParameter("server_url"),
            customerServiceUrl = uri.getQueryParameter("customer_service_url"),
            chatAssistantUrl = uri.getQueryParameter("chat_assistant_url"),
            skillCommunityUrl = uri.getQueryParameter("skill_community_url")
        )
    }

    private fun applyPcServiceConfig(payload: PcBindingPayload): ServiceConfigApplyResult {
        val ctx = requireContext()
        val prefs = ctx.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
        val oldServerUrl = prefs.getString("chat_server_url", ChatConstants.DEFAULT_CHAT_SERVER_URL)
            ?: ChatConstants.DEFAULT_CHAT_SERVER_URL
        val oldCustomerServiceUrl = ServiceUrlConfig.getCustomerServiceUrl(ctx)
        val oldChatAssistantUrl = ServiceUrlConfig.getChatAssistantUrl(ctx)
        val oldSkillCommunityUrl = ServiceUrlConfig.getSkillCommunityUrl(ctx)
        val editor = prefs.edit()
        payload.serverUrl?.trim()?.takeIf { it.isNotEmpty() }?.let {
            val normalized = if (it.endsWith("/")) it else "$it/"
            editor.putString("chat_server_url", normalized)
        }
        editor.apply()
        val resolvedServiceUrls = resolveServiceUrlsFromPayload(payload)
        resolvedServiceUrls.customerServiceUrl?.let {
            ServiceUrlConfig.setCustomerServiceUrl(ctx, it)
        }
        resolvedServiceUrls.chatAssistantUrl?.let {
            ServiceUrlConfig.setChatAssistantUrl(ctx, it)
        }
        resolvedServiceUrls.skillCommunityUrl?.let {
            ServiceUrlConfig.setSkillCommunityUrl(ctx, it)
        }
        val newServerUrl = prefs.getString("chat_server_url", ChatConstants.DEFAULT_CHAT_SERVER_URL)
            ?: ChatConstants.DEFAULT_CHAT_SERVER_URL
        val newCustomerServiceUrl = ServiceUrlConfig.getCustomerServiceUrl(ctx)
        val newChatAssistantUrl = ServiceUrlConfig.getChatAssistantUrl(ctx)
        val newSkillCommunityUrl = ServiceUrlConfig.getSkillCommunityUrl(ctx)
        return ServiceConfigApplyResult(
            hasServiceDomainChanged =
                oldServerUrl != newServerUrl ||
                    oldCustomerServiceUrl != newCustomerServiceUrl ||
                    oldChatAssistantUrl != newChatAssistantUrl ||
                    oldSkillCommunityUrl != newSkillCommunityUrl,
            customerServiceChanged = oldCustomerServiceUrl != newCustomerServiceUrl,
            customerServiceUrl = newCustomerServiceUrl
        )
    }

    private suspend fun refreshDataAfterServiceDomainChanged(result: ServiceConfigApplyResult) {
        val ctx = requireContext()
        CustomerServiceNetwork.initialize(result.customerServiceUrl)
        val imei = ProfileManager.getOrGenerateImei(ctx)

        // 关键：切域名后立刻重连 customer_service websocket，避免继续消费旧域名连接。
        if (result.customerServiceChanged) {
            withContext(Dispatchers.Main) {
                (activity as? MainActivity)?.getCustomerServiceWebSocket()?.connect(imei, result.customerServiceUrl)
            }
        }

        withContext(Dispatchers.IO) {
            try {
                val api = CustomerServiceNetwork.getApiService()
                val resp = api?.getProfile(imei)
                val profileToCache = if (resp?.isSuccessful == true && resp.body()?.success == true) {
                    resp.body()?.profile?.copy(name = resp.body()?.profile?.name ?: imei)
                        ?: UserProfile(imei = imei, name = imei)
                } else {
                    UserProfile(imei = imei, name = imei)
                }
                ProfileManager.saveProfileLocally(ctx, profileToCache)
            } catch (e: Exception) {
                Log.w(TAG, "域名切换后同步个人资料失败: ${e.message}")
                ProfileManager.saveProfileLocally(ctx, UserProfile(imei = imei, name = imei))
            }
            try {
                FriendManager.syncFriendsFromServer(ctx)
            } catch (e: Exception) {
                Log.w(TAG, "域名切换后同步好友失败: ${e.message}")
            }
            try {
                GroupManager.syncGroupsFromServer(ctx)
            } catch (e: Exception) {
                Log.w(TAG, "域名切换后同步群组失败: ${e.message}")
            }
            try {
                val api = CustomerServiceNetwork.getApiService()
                val resp = api?.getCustomAssistants(imei)
                if (resp?.isSuccessful == true && resp.body()?.success == true) {
                    val cloudItems = (resp.body()?.assistants ?: emptyList()).map { item ->
                        CustomAssistantManager.CustomAssistant(
                            id = item.id,
                            name = item.name,
                            intro = item.intro ?: "",
                            baseUrl = item.baseUrl,
                            capabilities = item.capabilities ?: emptyList(),
                            avatar = item.avatar,
                            multiSession = item.multiSessionEnabled ?: true
                        )
                    }
                    CustomAssistantManager.replaceAll(ctx, cloudItems)
                } else {
                    Unit
                }
            } catch (e: Exception) {
                Log.w(TAG, "域名切换后同步自定义助手失败: ${e.message}")
            }
            Unit
        }

        LocalBroadcastManager.getInstance(ctx)
            .sendBroadcast(Intent(ConversationListFragment.ACTION_SERVICE_DOMAIN_CHANGED))
    }

    private fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    private fun joinPath(baseUrl: String, path: String): String {
        val normalizedBase = normalizeBaseUrl(baseUrl)
        return normalizedBase + path.trim().trimStart('/')
    }

    private fun resolveServiceUrlsFromPayload(payload: PcBindingPayload): ResolvedServiceUrls {
        val normalizedServerUrl = payload.serverUrl
            ?.let(::normalizeBaseUrl)
            ?.takeIf { it.isNotEmpty() }
        val customerServiceUrl = payload.customerServiceUrl
            ?.let(::normalizeBaseUrl)
            ?.takeIf { it.isNotEmpty() }
            ?: normalizedServerUrl?.let { joinPath(it, "v4/") }
        val chatAssistantUrl = payload.chatAssistantUrl
            ?.let(::normalizeBaseUrl)
            ?.takeIf { it.isNotEmpty() }
            ?: normalizedServerUrl?.let { joinPath(it, "v10/") }
        val skillCommunityUrl = payload.skillCommunityUrl
            ?.let(::normalizeBaseUrl)
            ?.takeIf { it.isNotEmpty() }
            ?: normalizedServerUrl?.let { joinPath(it, "v9/") }
        return ResolvedServiceUrls(
            customerServiceUrl = customerServiceUrl,
            chatAssistantUrl = chatAssistantUrl,
            skillCommunityUrl = skillCommunityUrl
        )
    }
    
    /**
     * 添加好友到服务器
     */
    private fun addFriendToServer(friendImei: String) {
        // 显示加载提示
        val loadingToast = Toast.makeText(requireContext(), getString(R.string.adding_friend), Toast.LENGTH_SHORT)
        loadingToast.show()
        
        mainScope.launch {
            try {
                val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                val customerServiceUrl = prefs.getString("customer_service_url", ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL)
                    ?: ServiceUrlConfig.DEFAULT_CUSTOMER_SERVICE_URL
                
                CustomerServiceNetwork.initialize(customerServiceUrl)
                val apiService = CustomerServiceNetwork.getApiService()
                
                if (apiService == null) {
                    loadingToast.cancel()
                    Toast.makeText(requireContext(), getString(R.string.cannot_connect_server), Toast.LENGTH_SHORT).show()
                    startScanning()
                    return@launch
                }
                
                val currentImei = ProfileManager.getOrGenerateImei(requireContext())
                val response = apiService.addFriend(AddFriendRequest(friendImei, currentImei))
                
                if (response.isSuccessful && response.body()?.success == true) {
                    // 添加好友到本地
                    val friend = Friend(
                        imei = friendImei,
                        nickname = null,
                        avatar = null,
                        status = "accepted",
                        addedAt = System.currentTimeMillis()
                    )
                    FriendManager.addFriend(requireContext(), friend)
                    
                    // 记录好友关系的发起方（我发起的）
                    val friendsPrefs = requireContext().getSharedPreferences("friends_prefs", android.content.Context.MODE_PRIVATE)
                    val initiatorJson = friendsPrefs.getString("friend_initiator", null)
                    val initiatorMap = if (initiatorJson != null) {
                        try {
                            val type = com.google.gson.reflect.TypeToken.getParameterized(MutableMap::class.java, String::class.java, String::class.java).type
                            com.google.gson.Gson().fromJson<MutableMap<String, String>>(initiatorJson, type) ?: mutableMapOf()
                        } catch (e: Exception) {
                            mutableMapOf()
                        }
                    } else {
                        mutableMapOf()
                    }
                    initiatorMap[friendImei] = currentImei
                    val gson = com.google.gson.Gson()
                    friendsPrefs.edit().putString("friend_initiator", gson.toJson(initiatorMap)).apply()
                    
                    // 同步服务器好友列表
                    try {
                        FriendManager.syncFriendsFromServer(requireContext())
                    } catch (e: Exception) {
                        Log.w(TAG, "同步好友列表失败: ${e.message}")
                    }
                    
                    loadingToast.cancel()
                    Toast.makeText(requireContext(), getString(R.string.add_friend_success), Toast.LENGTH_SHORT).show()
                    
                    // 延迟返回，让用户看到成功提示
                    binding.root.postDelayed({
                        // 恢复状态栏显示
                        (activity as? MainActivity)?.showStatusBar()
                        parentFragmentManager.popBackStack()
                    }, 1500)
                } else {
                    loadingToast.cancel()
                    val errorMsg = response.body()?.message ?: getString(R.string.add_friend_failed)
                    Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_SHORT).show()
                    startScanning()
                }
            } catch (e: Exception) {
                loadingToast.cancel()
                Log.e(TAG, "添加好友失败: ${e.message}", e)
                Toast.makeText(requireContext(), getString(R.string.add_friend_failed_format, e.message ?: ""), Toast.LENGTH_SHORT).show()
                startScanning()
            }
        }
    }
}

