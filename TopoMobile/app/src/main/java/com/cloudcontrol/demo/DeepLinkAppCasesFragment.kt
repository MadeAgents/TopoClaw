package com.cloudcontrol.demo

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.cloudcontrol.demo.databinding.FragmentDeepLinkAppCasesBinding

class DeepLinkAppCasesFragment : Fragment() {

    companion object {
        private const val TAG = "DeepLinkAppCasesFragment"
        private const val ARG_APP_NAME = "arg_app_name"

        fun newInstance(appName: String): DeepLinkAppCasesFragment {
            return DeepLinkAppCasesFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_APP_NAME, appName)
                }
            }
        }
    }

    private var _binding: FragmentDeepLinkAppCasesBinding? = null
    private val binding get() = _binding!!

    private val appName: String
        get() = arguments?.getString(ARG_APP_NAME).orEmpty()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDeepLinkAppCasesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
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
        binding.tvTitle.text = appName
        binding.btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.btnJump.setOnClickListener { jumpToActivity() }
        setupCases()
    }

    private fun setupCases() {
        val cases = DeepLinkPresetRepository.getCasesByApp(appName)
        binding.llAppCases.removeAllViews()
        cases.forEachIndexed { index, item ->
            val button = Button(requireContext()).apply {
                text = "${index + 1}. ${item.featureName}"
                isAllCaps = false
                setOnClickListener {
                    binding.etActivityComponent.setText(item.deeplink)
                    binding.etActivityComponent.setSelection(item.deeplink.length)
                    jumpToActivity()
                }
            }
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            }
            binding.llAppCases.addView(button, params)
        }
    }

    private fun jumpToActivity() {
        val input = binding.etActivityComponent.text?.toString()?.trim()
        if (input.isNullOrBlank()) {
            Toast.makeText(requireContext(), getString(R.string.deep_link_empty_hint), Toast.LENGTH_SHORT).show()
            return
        }
        if (showParameterDialogIfNeeded(input)) {
            return
        }
        jumpToActivityInternal(input)
    }

    private fun jumpToActivityInternal(input: String) {
        if (input.isBlank()) return

        if (input.startsWith("intent:", ignoreCase = true)) {
            launchIntentUri(input)
            return
        }

        if (input.contains("://")) {
            launchDeepLinkUrl(input)
            return
        }

        launchByComponentName(input)
    }

    private fun showParameterDialogIfNeeded(template: String): Boolean {
        val placeholders = extractPlaceholders(template)
        if (placeholders.isEmpty()) return false

        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p / 2)
        }

        val inputs = mutableMapOf<String, EditText>()
        placeholders.forEach { key ->
            val edit = EditText(requireContext()).apply {
                hint = key
                setSingleLine(true)
            }
            container.addView(
                edit,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (10 * resources.displayMetrics.density).toInt()
                }
            )
            inputs[key] = edit
        }

        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.deep_link_fill_params_title))
            .setMessage(getString(R.string.deep_link_fill_params_hint))
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                var resolved = template
                placeholders.forEach { key ->
                    val value = inputs[key]?.text?.toString().orEmpty()
                    resolved = resolved.replace("{$key}", value)
                    if (key == "关键词") {
                        resolved = resolved.replace("关键词", value)
                    }
                }
                binding.etActivityComponent.setText(resolved)
                binding.etActivityComponent.setSelection(resolved.length)
                jumpToActivityInternal(resolved)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return true
    }

    private fun extractPlaceholders(template: String): List<String> {
        val result = linkedSetOf<String>()
        var start = template.indexOf('{')
        while (start >= 0) {
            val end = template.indexOf('}', start + 1)
            if (end <= start + 1) {
                break
            }
            val key = template.substring(start + 1, end).trim()
            if (key.isNotEmpty()) {
                result.add(key)
            }
            start = template.indexOf('{', end + 1)
        }
        val hasKeywordPlaceholder = result.any { it.contains("关键词") }
        if (!hasKeywordPlaceholder && template.contains("关键词")) {
            result.add("关键词")
        }
        return result.toList()
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
            Toast.makeText(requireContext(), getString(R.string.deep_link_failed) + ": " + (e.message ?: ""), Toast.LENGTH_LONG).show()
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
            Toast.makeText(requireContext(), getString(R.string.deep_link_failed) + ": " + (e.message ?: ""), Toast.LENGTH_LONG).show()
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
        val activityClassName = if (activityPart.startsWith(".")) packageName + activityPart else activityPart
        try {
            val intent = Intent().apply {
                component = ComponentName(packageName, activityClassName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Toast.makeText(requireContext(), getString(R.string.deep_link_success), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch activity: $packageName/$activityClassName", e)
            Toast.makeText(requireContext(), getString(R.string.deep_link_failed) + ": " + (e.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }
}
