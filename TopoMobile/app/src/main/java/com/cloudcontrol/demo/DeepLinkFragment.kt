package com.cloudcontrol.demo

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.cloudcontrol.demo.databinding.FragmentDeepLinkBinding

/**
 * Deep Link Fragment
 * 支持通过 URL Deeplink 或 Activity 组件名跳转页面。
 */
class DeepLinkFragment : Fragment() {

    companion object {
        private const val TAG = "DeepLinkFragment"
        private const val REQUEST_CODE_DANGEROUS_PERMISSIONS = 2001
    }

    private var _binding: FragmentDeepLinkBinding? = null
    private val binding get() = _binding!!
    private var pendingPermissionLabel: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDeepLinkBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? MainActivity)?.let { mainActivity ->
            if (mainActivity.supportActionBar?.isShowing == true) {
                mainActivity.hideActionBarInstantly()
            }
        }

        setupUI()
    }

    override fun onResume() {
        super.onResume()
        if (!isAdded || context == null) return

        (activity as? MainActivity)?.let { mainActivity ->
            if (mainActivity.supportActionBar?.isShowing == true) {
                mainActivity.hideActionBarInstantly()
            }
            mainActivity.setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
            mainActivity.setBottomNavigationVisibility(false)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        binding.btnJump.setOnClickListener {
            jumpToActivity()
        }

        setupAppEntries()
        setupPermissionShortcuts()
    }

    private fun setupAppEntries() {
        val appNames = DeepLinkPresetRepository.getAppNames()
        binding.llPresetCases.removeAllViews()
        appNames.forEachIndexed { index, appName ->
            val button = Button(requireContext()).apply {
                text = "${index + 1}. $appName"
                isAllCaps = false
                setOnClickListener {
                    parentFragmentManager.beginTransaction()
                        .setCustomAnimations(
                            R.anim.slide_in_from_right,
                            R.anim.slide_out_to_left,
                            R.anim.slide_in_from_left,
                            R.anim.slide_out_to_right
                        )
                        .replace(
                            R.id.fragmentContainer,
                            DeepLinkAppCasesFragment.newInstance(appName)
                        )
                        .addToBackStack(null)
                        .commitAllowingStateLoss()
                }
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            }
            binding.llPresetCases.addView(button, params)
        }
    }

    private fun setupPermissionShortcuts() {
        val shortcuts = listOf(
            Pair(
                getString(R.string.deep_link_permission_call_phone),
                View.OnClickListener {
                    requestDangerousPermissions(
                        arrayOf(Manifest.permission.CALL_PHONE),
                        getString(R.string.deep_link_permission_call_phone)
                    )
                }
            ),
            Pair(
                getString(R.string.deep_link_permission_camera),
                View.OnClickListener {
                    requestDangerousPermissions(
                        arrayOf(Manifest.permission.CAMERA),
                        getString(R.string.deep_link_permission_camera)
                    )
                }
            ),
            Pair(
                getString(R.string.deep_link_permission_set_alarm_tip_button),
                View.OnClickListener {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.deep_link_permission_set_alarm_tip),
                        Toast.LENGTH_LONG
                    ).show()
                }
            ),
            Pair(
                getString(R.string.deep_link_permission_open_settings),
                View.OnClickListener {
                    openAppPermissionSettings()
                }
            )
        )

        binding.llPermissionShortcuts.removeAllViews()
        shortcuts.forEach { shortcut ->
            val button = Button(requireContext()).apply {
                text = shortcut.first
                isAllCaps = false
                setOnClickListener(shortcut.second)
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            }
            binding.llPermissionShortcuts.addView(button, params)
        }
    }

    private fun requestDangerousPermissions(permissions: Array<String>, label: String) {
        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.deep_link_permission_already_granted, label),
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        pendingPermissionLabel = label
        requestPermissions(notGranted.toTypedArray(), REQUEST_CODE_DANGEROUS_PERMISSIONS)
    }

    private fun openAppPermissionSettings() {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${requireContext().packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app permission settings", e)
            Toast.makeText(
                requireContext(),
                getString(R.string.deep_link_failed) + ": " + (e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun jumpToActivity() {
        val input = binding.etActivityComponent.text?.toString()?.trim()
        if (input.isNullOrBlank()) {
            Toast.makeText(requireContext(), getString(R.string.deep_link_empty_hint), Toast.LENGTH_SHORT).show()
            return
        }

        // Intent URI 模式（例如：intent:#Intent;action=...;end）
        if (isIntentUri(input)) {
            launchIntentUri(input)
            return
        }

        // URL Deeplink 模式（例如：alipays://platformapi/startapp?appId=20000067）
        if (isLikelyUrl(input)) {
            launchDeepLinkUrl(input)
            return
        }

        // 兼容 Activity 组件名模式（例如：com.android.settings/.Settings）
        launchByComponentName(input)
    }

    private fun isLikelyUrl(input: String): Boolean {
        return input.contains("://")
    }

    private fun isIntentUri(input: String): Boolean {
        return input.startsWith("intent:", ignoreCase = true)
    }

    private fun launchIntentUri(intentUri: String) {
        try {
            val intent = Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val pm = requireContext().packageManager
            if (intent.resolveActivity(pm) == null) {
                Toast.makeText(requireContext(), getString(R.string.deep_link_failed) + ": " + getString(R.string.deep_link_no_handler), Toast.LENGTH_LONG).show()
                return
            }

            startActivity(intent)
            Toast.makeText(requireContext(), getString(R.string.deep_link_success), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open intent uri: $intentUri", e)
            val message = e.message ?: getString(R.string.deep_link_failed)
            Toast.makeText(requireContext(), getString(R.string.deep_link_failed) + ": " + message, Toast.LENGTH_LONG).show()
        }
    }

    private fun launchDeepLinkUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val pm = requireContext().packageManager
            if (intent.resolveActivity(pm) == null) {
                Toast.makeText(requireContext(), getString(R.string.deep_link_failed) + ": " + getString(R.string.deep_link_no_handler), Toast.LENGTH_LONG).show()
                return
            }

            startActivity(intent)
            Toast.makeText(requireContext(), getString(R.string.deep_link_success), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open deep link url: $url", e)
            val message = e.message ?: getString(R.string.deep_link_failed)
            Toast.makeText(requireContext(), getString(R.string.deep_link_failed) + ": " + message, Toast.LENGTH_LONG).show()
        }
    }

    private fun launchByComponentName(input: String) {
        val parts = input.split("/")
        if (parts.size != 2) {
            Toast.makeText(requireContext(), getString(R.string.deep_link_format_hint), Toast.LENGTH_SHORT).show()
            return
        }

        val packageName = parts[0].trim()
        val activityPart = parts[1].trim()
        if (packageName.isEmpty() || activityPart.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.deep_link_format_hint), Toast.LENGTH_SHORT).show()
            return
        }

        val activityClassName = if (activityPart.startsWith(".")) {
            packageName + activityPart
        } else {
            activityPart
        }

        try {
            val intent = Intent().apply {
                component = ComponentName(packageName, activityClassName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(requireContext(), getString(R.string.deep_link_success), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch activity: $packageName/$activityClassName", e)
            val message = e.message ?: getString(R.string.deep_link_failed)
            Toast.makeText(requireContext(), getString(R.string.deep_link_failed) + ": " + message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_DANGEROUS_PERMISSIONS) return

        val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        val label = pendingPermissionLabel ?: getString(R.string.deep_link_permission_generic_label)
        pendingPermissionLabel = null

        if (allGranted) {
            Toast.makeText(
                requireContext(),
                getString(R.string.deep_link_permission_granted, label),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(
                requireContext(),
                getString(R.string.deep_link_permission_denied, label),
                Toast.LENGTH_LONG
            ).show()
        }
    }
}
