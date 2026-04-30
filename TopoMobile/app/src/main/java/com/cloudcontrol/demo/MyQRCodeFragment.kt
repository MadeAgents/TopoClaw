package com.cloudcontrol.demo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.cloudcontrol.demo.databinding.FragmentMyQrcodeBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.Hashtable

/**
 * 我的二维码Fragment
 * 显示用户的二维码，其他用户可以通过扫描此二维码添加好友
 */
class MyQRCodeFragment : Fragment() {
    
    companion object {
        private const val TAG = "MyQRCodeFragment"
        private const val REQUEST_WRITE_STORAGE_PERMISSION = 1001
    }
    
    private var _binding: FragmentMyQrcodeBinding? = null
    private val binding get() = _binding!!
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var qrCodeBitmap: Bitmap? = null
    private var avatarBitmap: Bitmap? = null
    private var userName: String = ""
    private var userImei: String = ""
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMyQrcodeBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // 隐藏ActionBar（顶部导航栏）
        (activity as? MainActivity)?.hideActionBarWithoutAnimation()
        
        // 隐藏底部导航栏
        (activity as? MainActivity)?.setBottomNavigationVisibility(false)
        
        setupUI()
        loadProfileAndGenerateQRCode()
    }
    
    override fun onResume() {
        super.onResume()
        // 检查Fragment是否还存在
        if (!isAdded || activity == null) return
        // 确保隐藏ActionBar和底部导航栏
        try {
            (activity as? MainActivity)?.hideActionBarWithoutAnimation()
            (activity as? MainActivity)?.setBottomNavigationVisibility(false)
        } catch (e: Exception) {
            Log.w(TAG, "隐藏导航栏失败: ${e.message}", e)
        }
    }
    
    override fun onPause() {
        super.onPause()
        // 检查Fragment是否还存在
        if (!isAdded || activity == null) return
        // 恢复底部导航栏（但不在这个Fragment中恢复，由其他Fragment管理）
        // 这里不做任何操作，避免影响其他Fragment
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
            try {
                parentFragmentManager.popBackStack()
            } catch (e: Exception) {
                Log.e(TAG, "返回失败: ${e.message}", e)
            }
        }
        
        // 设置头像的圆形裁剪
        binding.ivAvatar.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        binding.ivAvatar.clipToOutline = true
        
        // 下载按钮
        binding.llDownload.setOnClickListener {
            downloadQRCode()
        }
        
        // 分享按钮
        binding.llShare.setOnClickListener {
            shareQRCode()
        }
    }
    
    /**
     * 加载用户资料并生成二维码
     */
    private fun loadProfileAndGenerateQRCode() {
        mainScope.launch {
            try {
                // 获取当前用户的IMEI
                val imei = ProfileManager.getOrGenerateImei(requireContext())
                
                // 加载用户资料
                val profile = ProfileManager.loadProfileLocally(requireContext())
                
                // 显示用户信息
                if (profile != null) {
                    // 显示头像
                    if (!profile.avatar.isNullOrEmpty()) {
                        val bitmap = base64ToBitmap(profile.avatar)
                        if (bitmap != null) {
                            binding.ivAvatar.setImageBitmap(bitmap)
                            this@MyQRCodeFragment.avatarBitmap = bitmap
                        } else {
                            binding.ivAvatar.setImageResource(R.drawable.ic_person)
                            this@MyQRCodeFragment.avatarBitmap = getBitmapFromDrawable(
                                ContextCompat.getDrawable(requireContext(), R.drawable.ic_person)
                            )
                        }
                    } else {
                        binding.ivAvatar.setImageResource(R.drawable.ic_person)
                        this@MyQRCodeFragment.avatarBitmap = getBitmapFromDrawable(
                            ContextCompat.getDrawable(requireContext(), R.drawable.ic_person)
                        )
                    }
                    
                    // 显示姓名
                    val name = profile.name ?: getString(R.string.not_filled)
                    binding.tvName.text = name
                    this@MyQRCodeFragment.userName = name
                } else {
                    binding.ivAvatar.setImageResource(R.drawable.ic_person)
                    binding.tvName.text = getString(R.string.not_filled)
                    this@MyQRCodeFragment.avatarBitmap = getBitmapFromDrawable(
                        ContextCompat.getDrawable(requireContext(), R.drawable.ic_person)
                    )
                    this@MyQRCodeFragment.userName = getString(R.string.not_filled)
                }
                
                // 显示IMEI
                binding.tvImei.text = "IMEI: $imei"
                this@MyQRCodeFragment.userImei = imei
                
                // 生成二维码（二维码内容为用户的IMEI）
                val qrCodeBitmap = generateQRCode(imei, 800)
                if (qrCodeBitmap != null) {
                    this@MyQRCodeFragment.qrCodeBitmap = qrCodeBitmap
                    binding.ivQRCode.setImageBitmap(qrCodeBitmap)
                } else {
                    Toast.makeText(requireContext(), "生成二维码失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载资料或生成二维码失败: ${e.message}", e)
                if (_binding != null && isAdded) {
                    Toast.makeText(requireContext(), "加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * 生成二维码
     * @param content 二维码内容
     * @param size 二维码尺寸（像素）
     */
    private fun generateQRCode(content: String, size: Int): Bitmap? {
        return try {
            val hints = Hashtable<EncodeHintType, Any>()
            hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
            hints[EncodeHintType.ERROR_CORRECTION] = ErrorCorrectionLevel.H
            hints[EncodeHintType.MARGIN] = 1
            
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)
            
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                }
            }
            
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "生成二维码失败: ${e.message}", e)
            null
        }
    }
    
    /**
     * Base64转Bitmap
     */
    private fun base64ToBitmap(base64String: String): Bitmap? {
        return try {
            val decodedBytes = android.util.Base64.decode(base64String, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Base64转Bitmap失败: ${e.message}", e)
            null
        }
    }
    
    /**
     * 下载二维码到本地
     */
    private fun downloadQRCode() {
        val cardBitmap = generateCardBitmap() ?: run {
            Toast.makeText(requireContext(), "生成卡片失败", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Android 10及以上版本不需要存储权限，使用MediaStore API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveQRCodeToMediaStore(cardBitmap)
        } else {
            // Android 9及以下版本需要存储权限
            if (hasStoragePermission()) {
                saveQRCodeToFile(cardBitmap)
            } else {
                requestStoragePermission()
            }
        }
    }
    
    /**
     * 检查是否有存储权限（Android 9及以下）
     */
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }
    
    /**
     * 请求存储权限（Android 9及以下）
     */
    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_WRITE_STORAGE_PERMISSION
            )
        }
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
        
        if (requestCode == REQUEST_WRITE_STORAGE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                generateCardBitmap()?.let { saveQRCodeToFile(it) }
            } else {
                Toast.makeText(requireContext(), "需要存储权限才能保存二维码", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 保存二维码到MediaStore（Android 10+）
     */
    private fun saveQRCodeToMediaStore(bitmap: Bitmap) {
        mainScope.launch {
            try {
                val contentValues = android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "我的二维码_${getCurrentTimeString()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/我的二维码")
                    }
                }
                
                val uri = withContext(Dispatchers.IO) {
                    requireContext().contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        contentValues
                    )
                }
                
                if (uri != null) {
                    withContext(Dispatchers.IO) {
                        requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                        }
                    }
                    
                    Toast.makeText(requireContext(), "二维码已保存到相册", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "二维码已保存: $uri")
                } else {
                    Toast.makeText(requireContext(), "保存失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "保存二维码失败: ${e.message}", e)
                Toast.makeText(requireContext(), "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 保存二维码到文件（Android 9及以下）
     */
    private fun saveQRCodeToFile(bitmap: Bitmap) {
        mainScope.launch {
            try {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val qrCodeDir = File(picturesDir, "我的二维码")
                if (!qrCodeDir.exists()) {
                    qrCodeDir.mkdirs()
                }
                
                val fileName = "我的二维码_${getCurrentTimeString()}.jpg"
                val file = File(qrCodeDir, fileName)
                
                withContext(Dispatchers.IO) {
                    FileOutputStream(file).use { outputStream ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                    }
                    
                    // 通知媒体库更新
                    val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                    intent.data = Uri.fromFile(file)
                    requireContext().sendBroadcast(intent)
                }
                
                Toast.makeText(requireContext(), "二维码已保存到相册", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "二维码已保存: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "保存二维码失败: ${e.message}", e)
                Toast.makeText(requireContext(), "保存失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 分享二维码
     */
    private fun shareQRCode() {
        val cardBitmap = generateCardBitmap() ?: run {
            Toast.makeText(requireContext(), "生成卡片失败", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 显示分享选项对话框
        showShareOptionsDialog(cardBitmap)
    }
    
    /**
     * 显示分享选项对话框
     */
    private fun showShareOptionsDialog(cardBitmap: Bitmap) {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_share_options, null)
        
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
        
        // 设置窗口样式
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        // 关闭按钮
        dialogView.findViewById<View>(R.id.btnClose)?.setOnClickListener {
            dialog.dismiss()
        }
        
        // 分享给应用内好友
        dialogView.findViewById<View>(R.id.llShareToFriend)?.setOnClickListener {
            dialog.dismiss()
            shareQRCodeToFriend(cardBitmap)
        }
        
        // 分享到其他应用
        dialogView.findViewById<View>(R.id.llShareToSystem)?.setOnClickListener {
            dialog.dismiss()
            shareQRCodeToSystem(cardBitmap)
        }
        
        dialog.show()
    }
    
    /**
     * 分享二维码到应用内好友
     */
    private fun shareQRCodeToFriend(cardBitmap: Bitmap) {
        // 获取好友列表
        val friends = FriendManager.getFriends(requireContext())
            .filter { it.status == "accepted" }
        
        if (friends.isEmpty()) {
            Toast.makeText(requireContext(), "暂无好友可分享", Toast.LENGTH_SHORT).show()
            return
        }
        
        // 构建选项列表
        val options = friends.map { friend ->
            friend.nickname ?: friend.imei.take(8) + "..."
        }
        
        // 显示选择对话框
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("选择分享对象")
            .setItems(options.toTypedArray()) { _, which ->
                val selectedFriend = friends[which]
                sendQRCodeToFriend(selectedFriend.imei, cardBitmap)
            }
            .setNegativeButton("取消", null)
            .show()
    }
    
    /**
     * 发送二维码给好友
     */
    private fun sendQRCodeToFriend(targetImei: String, cardBitmap: Bitmap) {
        mainScope.launch {
            try {
                val mainActivity = activity as? MainActivity
                val webSocket = mainActivity?.getCustomerServiceWebSocket()
                
                if (webSocket == null || !webSocket.isConnected()) {
                    Toast.makeText(requireContext(), "无法连接到服务器，请稍后重试", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                // 将二维码图片转换为Base64
                val imageBase64 = withContext(Dispatchers.IO) {
                    ScreenshotHelper.bitmapToBase64(cardBitmap, requireContext())
                }
                
                // 发送消息
                webSocket.sendFriendMessage(targetImei, "我的二维码", imageBase64)
                
                // 切换到好友对话页面
                val friendConversationId = "friend_$targetImei"
                val friend = FriendManager.getFriends(requireContext())
                    .firstOrNull { it.imei == targetImei }
                val friendName = friend?.nickname ?: "好友"
                
                val friendConversation = Conversation(
                    id = friendConversationId,
                    name = friendName,
                    avatar = friend?.avatar,
                    lastMessage = null,
                    lastMessageTime = System.currentTimeMillis()
                )
                
                mainActivity?.switchToChatFragment(friendConversation)
                
                Toast.makeText(requireContext(), "二维码已分享给好友", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "二维码已分享给好友: $targetImei")
            } catch (e: Exception) {
                Log.e(TAG, "分享二维码给好友失败: ${e.message}", e)
                Toast.makeText(requireContext(), "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 分享二维码到系统（其他应用）
     */
    private fun shareQRCodeToSystem(cardBitmap: Bitmap) {
        mainScope.launch {
            try {
                // 创建临时文件保存卡片
                val cacheDir = requireContext().cacheDir
                val imageFile = File(cacheDir, "qrcode_share_${getCurrentTimeString()}.jpg")
                
                withContext(Dispatchers.IO) {
                    FileOutputStream(imageFile).use { outputStream ->
                        cardBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                    }
                }
                
                // 使用FileProvider获取URI
                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    imageFile
                )
                
                // 创建分享Intent
                val shareIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, "我的二维码")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                
                // 启动分享选择器
                val chooserIntent = Intent.createChooser(shareIntent, "分享二维码")
                chooserIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                
                startActivity(chooserIntent)
                Log.d(TAG, "分享二维码")
            } catch (e: Exception) {
                Log.e(TAG, "分享二维码失败: ${e.message}", e)
                Toast.makeText(requireContext(), "分享失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    /**
     * 生成完整卡片的Bitmap
     */
    private fun generateCardBitmap(): Bitmap? {
        return try {
            val qrCode = qrCodeBitmap ?: return null
            val avatar = avatarBitmap ?: return null
            
            // 卡片尺寸（基于实际显示尺寸，转换为像素）
            val density = resources.displayMetrics.density
            val screenWidth = resources.displayMetrics.widthPixels
            val cardWidth = ((screenWidth - 48 * density).coerceAtMost(360 * density)).toInt() // 卡片宽度，最大360dp
            val padding = (24 * density).toInt() // 内边距24dp
            val cardPadding = padding
            
            // 计算各部分尺寸
            val avatarSize = (80 * density).toInt() // 头像80dp
            val avatarMarginBottom = (16 * density).toInt()
            val nameMarginBottom = (8 * density).toInt()
            val imeiMarginBottom = (24 * density).toInt()
            // 二维码尺寸：卡片宽度减去左右内边距，但不超过280dp
            val qrCodeMaxSize = (280 * density).toInt()
            val qrCodeSize = (cardWidth - cardPadding * 2 - 32 * density).toInt().coerceAtMost(qrCodeMaxSize)
            val qrCodePadding = (16 * density).toInt()
            val tipMarginTop = (16 * density).toInt()
            
            // 文本尺寸
            val nameTextSize = 20 * density
            val imeiTextSize = 14 * density
            val tipTextSize = 14 * density
            
            // 计算总高度
            val totalHeight = cardPadding * 2 +
                    avatarSize + avatarMarginBottom +
                    nameTextSize.toInt() + nameMarginBottom +
                    imeiTextSize.toInt() + imeiMarginBottom +
                    qrCodeSize + qrCodePadding * 2 +
                    tipTextSize.toInt() + tipMarginTop
            
            // 创建Bitmap
            val bitmap = Bitmap.createBitmap(cardWidth, totalHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            // 绘制白色背景
            canvas.drawColor(0xFFFFFFFF.toInt())
            
            // 绘制圆角矩形背景（模拟CardView）
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = 0xFFFFFFFF.toInt()
            val cornerRadius = 16 * density
            
            var currentY = cardPadding.toFloat()
            
            // 绘制头像（圆形）
            val avatarScaled = Bitmap.createScaledBitmap(avatar, avatarSize, avatarSize, true)
            // 创建圆形头像
            val circularAvatar = Bitmap.createBitmap(avatarSize, avatarSize, Bitmap.Config.ARGB_8888)
            val avatarCanvas = Canvas(circularAvatar)
            val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            
            // 使用Shader绘制圆形头像
            val shader = android.graphics.BitmapShader(avatarScaled, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
            avatarPaint.shader = shader
            avatarCanvas.drawCircle(avatarSize / 2f, avatarSize / 2f, avatarSize / 2f, avatarPaint)
            
            // 绘制圆形边框
            avatarPaint.shader = null
            avatarPaint.style = Paint.Style.STROKE
            avatarPaint.strokeWidth = 2f * density
            avatarPaint.color = 0xFFE0E0E0.toInt()
            avatarCanvas.drawCircle(avatarSize / 2f, avatarSize / 2f, avatarSize / 2f - 1f, avatarPaint)
            
            canvas.drawBitmap(circularAvatar, (cardWidth - avatarSize) / 2f, currentY, null)
            currentY += avatarSize + avatarMarginBottom
            
            // 绘制昵称
            val namePaint = Paint(Paint.ANTI_ALIAS_FLAG)
            namePaint.color = 0xFF212121.toInt()
            namePaint.textSize = nameTextSize
            namePaint.typeface = Typeface.DEFAULT_BOLD
            namePaint.textAlign = Paint.Align.CENTER
            val nameY = currentY + nameTextSize
            canvas.drawText(userName, cardWidth / 2f, nameY, namePaint)
            currentY = nameY + nameMarginBottom
            
            // 绘制IMEI
            val imeiPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            imeiPaint.color = 0xFF757575.toInt()
            imeiPaint.textSize = imeiTextSize
            imeiPaint.textAlign = Paint.Align.CENTER
            val imeiText = "IMEI: $userImei"
            val imeiY = currentY + imeiTextSize
            canvas.drawText(imeiText, cardWidth / 2f, imeiY, imeiPaint)
            currentY = imeiY + imeiMarginBottom
            
            // 绘制二维码（带白色背景和内边距）
            val qrCodeScaled = Bitmap.createScaledBitmap(qrCode, qrCodeSize, qrCodeSize, true)
            val qrCodeX = (cardWidth - qrCodeSize - qrCodePadding * 2) / 2f
            val qrCodeY = currentY
            // 绘制二维码的白色背景
            val qrBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            qrBgPaint.color = 0xFFFFFFFF.toInt()
            canvas.drawRect(
                qrCodeX,
                qrCodeY,
                qrCodeX + qrCodeSize + qrCodePadding * 2,
                qrCodeY + qrCodeSize + qrCodePadding * 2,
                qrBgPaint
            )
            canvas.drawBitmap(qrCodeScaled, qrCodeX + qrCodePadding, qrCodeY + qrCodePadding, null)
            currentY += qrCodeSize + qrCodePadding * 2 + tipMarginTop
            
            // 绘制提示文字
            val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            tipPaint.color = 0xFF757575.toInt()
            tipPaint.textSize = tipTextSize
            tipPaint.textAlign = Paint.Align.CENTER
            val tipText = getString(R.string.scan_qr_tip_my)
            canvas.drawText(tipText, cardWidth / 2f, currentY + tipTextSize, tipPaint)
            
            bitmap
        } catch (e: Exception) {
            Log.e(TAG, "生成卡片Bitmap失败: ${e.message}", e)
            null
        }
    }
    
    /**
     * 从Drawable获取Bitmap
     */
    private fun getBitmapFromDrawable(drawable: Drawable?): Bitmap? {
        if (drawable == null) return null
        
        return if (drawable is BitmapDrawable && drawable.bitmap != null) {
            drawable.bitmap
        } else {
            try {
                val bitmap = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap
            } catch (e: Exception) {
                Log.e(TAG, "从Drawable获取Bitmap失败: ${e.message}", e)
                null
            }
        }
    }
    
    /**
     * 获取当前时间字符串（用于文件名）
     */
    private fun getCurrentTimeString(): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        return sdf.format(Date())
    }
}

