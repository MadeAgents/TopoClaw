package com.cloudcontrol.demo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.cloudcontrol.demo.databinding.FragmentClipboardImageTestBinding
import java.io.File
import java.io.FileOutputStream

/**
 * 剪切板图片测试页：选择图片并注入系统剪切板。
 */
class ClipboardImageTestFragment : Fragment() {

    companion object {
        private const val TAG = "ClipboardImageTest"
    }

    private var _binding: FragmentClipboardImageTestBinding? = null
    private val binding get() = _binding!!

    private var selectedImageUri: Uri? = null

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                selectedImageUri = uri
                binding.ivPreview.setImageURI(uri)
                binding.tvSelectedImageStatus.text = getString(
                    R.string.clipboard_image_test_selected_uri,
                    uri.toString()
                )
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentClipboardImageTestBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? MainActivity)?.hideActionBarInstantly()
        setupUI()
    }

    override fun onResume() {
        super.onResume()
        if (!isAdded || context == null) return
        (activity as? MainActivity)?.let { mainActivity ->
            mainActivity.hideActionBarInstantly()
            mainActivity.setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
            mainActivity.setBottomNavigationVisibility(false)
        }
    }

    override fun onPause() {
        super.onPause()
        (activity as? MainActivity)?.setBottomNavigationVisibility(true)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnInsertImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        binding.btnCopyToClipboard.setOnClickListener {
            copyImageToClipboard()
        }
    }

    private fun copyImageToClipboard() {
        val sourceUri = selectedImageUri
        if (sourceUri == null) {
            Toast.makeText(requireContext(), R.string.clipboard_image_test_no_image, Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val clipboardUri = persistToCacheAndGetUri(sourceUri)
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newUri(
                requireContext().contentResolver,
                getString(R.string.clipboard_image_test),
                clipboardUri
            )
            clipboard.setPrimaryClip(clipData)

            binding.tvCopiedImageStatus.text = getString(
                R.string.clipboard_image_test_copied_uri,
                clipboardUri.toString()
            )
            (activity as? MainActivity)?.addLog("剪切板图片测试：已注入图片URI -> $clipboardUri")
            Toast.makeText(requireContext(), R.string.clipboard_image_test_copy_success, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "复制图片到剪切板失败: ${e.message}", e)
            Toast.makeText(
                requireContext(),
                getString(R.string.clipboard_image_test_copy_failed, e.message ?: ""),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun persistToCacheAndGetUri(sourceUri: Uri): Uri {
        val context = requireContext()
        val contentResolver = context.contentResolver
        val mimeType = contentResolver.getType(sourceUri)?.takeIf { it.startsWith("image/") } ?: "image/png"
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType)
            ?: if (mimeType == "image/jpeg") "jpg" else "png"

        val dir = File(context.cacheDir, "clipboard_test").apply { mkdirs() }
        val outputFile = File(dir, "clipboard_${System.currentTimeMillis()}.$extension")

        contentResolver.openInputStream(sourceUri)?.use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("无法读取所选图片")

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outputFile
        )
    }
}
