package com.cloudcontrol.demo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.cloudcontrol.demo.R as AppR
import com.cloudcontrol.demo.databinding.FragmentNewAssistantBinding
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Hashtable

/**
 * 新建小助手 Fragment
 * 供开发者创建小助手，填写表单后生成链接和二维码供用户添加
 */
class NewAssistantFragment : Fragment() {

    companion object {
        private const val TAG = "NewAssistantFragment"
        private const val REQUEST_CODE_PICK_AVATAR = 2001
        private const val AVATAR_MAX_SIZE = 200
    }

    private var _binding: FragmentNewAssistantBinding? = null
    private val binding get() = _binding!!
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var avatarBase64: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNewAssistantBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? MainActivity)?.let { mainActivity ->
            mainActivity.hideActionBarWithoutAnimation()
            mainActivity.setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
            mainActivity.setBottomNavigationVisibility(false)
        }
        setupUI()
        setupAvatarOutline()
    }

    override fun onStart() {
        super.onStart()
        (activity as? MainActivity)?.hideActionBarWithoutAnimation()
    }

    override fun onResume() {
        super.onResume()
        if (!isAdded || context == null) return
        (activity as? MainActivity)?.let { mainActivity ->
            mainActivity.hideActionBarWithoutAnimation()
            mainActivity.setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
            mainActivity.setBottomNavigationVisibility(false)
        }
        // 再次延迟隐藏，防止 fragment 切换时 ActionBar 被重新显示
        view?.postDelayed({ (activity as? MainActivity)?.hideActionBarWithoutAnimation() }, 50)
    }

    private fun setupAvatarOutline() {
        binding.ivAvatar.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        binding.ivAvatar.clipToOutline = true
        binding.ivResultAvatar.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        binding.ivResultAvatar.clipToOutline = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        mainScope.cancel()
        _binding = null
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_AVATAR && resultCode == android.app.Activity.RESULT_OK && data != null) {
            val uri: Uri? = data.data
            if (uri != null) {
                loadAndSetAvatar(uri)
            }
        }
    }

    private fun setupUI() {
        requireView().findViewById<android.widget.ImageButton>(AppR.id.btnBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnSave.setOnClickListener {
            saveAssistant()
        }

        binding.btnCopyLink.setOnClickListener {
            copyLinkToClipboard()
        }

        binding.btnShowQR.setOnClickListener {
            toggleQRCode()
        }

        binding.btnPickAvatar.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, REQUEST_CODE_PICK_AVATAR)
        }
    }

    private fun loadAndSetAvatar(uri: Uri) {
        mainScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { loadBitmapFromUri(uri) }
                if (bitmap != null) {
                    val compressed = resizeBitmap(bitmap, AVATAR_MAX_SIZE)
                    avatarBase64 = bitmapToBase64(compressed)
                    binding.ivAvatar.setImageBitmap(compressed)
                    if (!compressed.equals(bitmap)) bitmap.recycle()
                    Toast.makeText(requireContext(), getString(AppR.string.new_assistant_avatar_updated), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "加载图片失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载头像失败: ${e.message}", e)
                Toast.makeText(requireContext(), "加载头像失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream? = requireContext().contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            Log.e(TAG, "从URI加载Bitmap失败: ${e.message}", e)
            null
        }
    }

    private fun resizeBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxSize && h <= maxSize) return bitmap
        val scale = minOf(maxSize.toFloat() / w, maxSize.toFloat() / h)
        return Bitmap.createScaledBitmap(bitmap, (w * scale).toInt(), (h * scale).toInt(), true)
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun base64ToBitmap(base64: String): Bitmap? {
        return try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "Base64转Bitmap失败: ${e.message}", e)
            null
        }
    }

    private fun saveAssistant() {
        val name = binding.etName.text?.toString()?.trim() ?: ""
        val intro = binding.etIntro.text?.toString()?.trim() ?: ""
        val baseUrlRaw = binding.etBaseUrl.text?.toString()?.trim() ?: ""
        val baseUrl = if (baseUrlRaw.endsWith("/")) baseUrlRaw else "$baseUrlRaw/"
        val capMobile = binding.cbCapMobile.isChecked
        val capPc = binding.cbCapPc.isChecked
        val capChat = binding.cbCapChat.isChecked

        when {
            name.isBlank() -> {
                Toast.makeText(requireContext(), getString(AppR.string.new_assistant_name_required), Toast.LENGTH_SHORT).show()
                return
            }
            baseUrlRaw.isBlank() -> {
                Toast.makeText(requireContext(), getString(AppR.string.new_assistant_url_required), Toast.LENGTH_SHORT).show()
                return
            }
            !capMobile && !capPc && !capChat -> {
                Toast.makeText(requireContext(), getString(AppR.string.new_assistant_cap_required), Toast.LENGTH_SHORT).show()
                return
            }
        }

        val capabilities = mutableListOf<String>()
        if (capMobile) capabilities.add(CustomAssistantManager.CAP_EXECUTION_MOBILE)
        if (capPc) capabilities.add(CustomAssistantManager.CAP_EXECUTION_PC)
        if (capChat) capabilities.add(CustomAssistantManager.CAP_CHAT)
        val multiSession = binding.cbMultiSession.isChecked

        // 保存到本地列表
        val assistant = CustomAssistantManager.CustomAssistant(
            id = CustomAssistantManager.buildCustomAssistantId(requireContext()),
            name = name,
            intro = intro,
            baseUrl = baseUrl,
            capabilities = capabilities,
            avatar = avatarBase64,
            multiSession = multiSession
        )
        CustomAssistantManager.add(requireContext(), assistant)

        // APK 侧不再写入 /api/custom-assistants，避免覆盖云端配置。

        Toast.makeText(requireContext(), getString(AppR.string.new_assistant_success), Toast.LENGTH_SHORT).show()

        // 显示结果区域（链接和二维码供分享）
        val link = CustomAssistantManager.buildAssistantUrl(name, baseUrl, capabilities, assistant.id)
        binding.tvLink.text = link
        binding.tvResultName.text = name
        binding.llResult.visibility = View.VISIBLE
        binding.ivQRCode.visibility = View.GONE

        if (avatarBase64 != null) {
            binding.ivResultAvatar.visibility = View.VISIBLE
            base64ToBitmap(avatarBase64!!)?.let { binding.ivResultAvatar.setImageBitmap(it) }
        } else {
            binding.ivResultAvatar.visibility = View.GONE
        }
    }

    private fun copyLinkToClipboard() {
        val link = binding.tvLink.text?.toString() ?: return
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("assistant_link", link))
        Toast.makeText(requireContext(), getString(AppR.string.new_assistant_link_copied), Toast.LENGTH_SHORT).show()
    }

    private fun toggleQRCode() {
        if (binding.ivQRCode.visibility == View.VISIBLE) {
            binding.ivQRCode.visibility = View.GONE
            return
        }
        val link = binding.tvLink.text?.toString() ?: return
        val bitmap = generateQRCode(link, 400)
        if (bitmap != null) {
            binding.ivQRCode.setImageBitmap(bitmap)
            binding.ivQRCode.visibility = View.VISIBLE
        } else {
            Toast.makeText(requireContext(), "生成二维码失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncCustomAssistantsToCloud() {
        Log.d(TAG, "APK 侧已禁用自定义小助手云端写入（POST /api/custom-assistants）")
    }

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
}
