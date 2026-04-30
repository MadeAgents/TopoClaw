package com.cloudcontrol.demo

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import androidx.core.view.ViewCompat
import com.google.android.material.button.MaterialButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cloudcontrol.demo.databinding.FragmentAssistantPlazaBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 助手广场 Fragment
 * Tab（我创建的小助手 | 小助手广场）、支持左右滑动切换、Toolbar、内容区
 */
class AssistantPlazaFragment : Fragment() {

    companion object {
        private const val TAG = "AssistantPlazaFragment"
        /** 广场卡片头像 base64 解码并发上限，避免一次性 N 路解码占满 IO 与内存 */
        private val plazaAvatarDecodeDispatcher = Dispatchers.IO.limitedParallelism(4)
        /** onResume 等触发的重复拉取：有缓存数据时最短间隔（毫秒），排序/首次仍强制请求 */
        private const val PLAZA_LIST_REFRESH_MIN_INTERVAL_MS = 12_000L
    }

    private var _binding: FragmentAssistantPlazaBinding? = null
    private val binding get() = _binding!!

    private var activeTab: Tab = Tab.MY
    private var refreshKey = 0
    private var currentPage = 0 // 0: 我创建的小助手, 1: 小助手广场
    private var plazaList = emptyList<PlazaAssistantItem>()
    private var plazaLoading = false
    private var plazaAddingId: String? = null
    /** 广场列表排序：latest=最新，hot=最热（点赞数） */
    private var plazaSort: String = "latest"
    private var lastPlazaFetchSuccessElapsed: Long = 0L

    private enum class Tab { PLAZA, MY }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAssistantPlazaBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (activity as? MainActivity)?.let { mainActivity ->
            mainActivity.setupFragmentBottomNavigation(binding.bottomNavigation, R.id.nav_assistant_plaza)
            mainActivity.setFragmentBottomNavigationBackgroundColor(binding.bottomNavigation, 0xFFF5F5F5.toInt())
            mainActivity.initAndUpdateFragmentChatBadge(binding.bottomNavigation)
        }

        setupTabBar()
        setupPlazaSortBar()
        setupToolbar()
        setupSwipeGesture()
        switchTab(Tab.MY)
    }

    override fun onResume() {
        super.onResume()
        if (!isAdded || context == null || isHidden) return

        (activity as? MainActivity)?.let { mainActivity ->
            // 始终隐藏 Activity 的 ActionBar，避免从新建小助手返回时被误显示
            mainActivity.hideActionBarInstantly()
            // 布局完成后再次隐藏，兜底应对 OnBackStackChanged 与 Fragment 生命周期的时序竞争
            binding.root.post { mainActivity.hideActionBarInstantly() }
            mainActivity.setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
            mainActivity.setBottomNavigationVisibility(false)
            mainActivity.setFragmentBottomNavigationBackgroundColor(binding.bottomNavigation, 0xFFF5F5F5.toInt())
            binding.bottomNavigation.selectedItemId = R.id.nav_assistant_plaza
            mainActivity.initAndUpdateFragmentChatBadge(binding.bottomNavigation)
        }
        refreshContent(fromVisibilityResume = true)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && isAdded && _binding != null) {
            (activity as? MainActivity)?.let { mainActivity ->
                mainActivity.hideActionBarInstantly()
                mainActivity.setStatusBarColor(0xFFF5F5F5.toInt(), lightStatusBar = true)
                binding.bottomNavigation.selectedItemId = R.id.nav_assistant_plaza
                mainActivity.initAndUpdateFragmentChatBadge(binding.bottomNavigation)
            }
            refreshContent(fromVisibilityResume = true)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun setupTabBar() {
        binding.btnTabMy.setOnClickListener { switchTab(Tab.MY) }
        binding.btnTabPlaza.setOnClickListener { switchTab(Tab.PLAZA) }
    }

    private fun setupPlazaSortBar() {
        binding.btnPlazaSortLatest.setOnClickListener {
            if (plazaSort != "latest") {
                plazaSort = "latest"
                updatePlazaSortBarStyle()
                refreshPlazaList(forceRefresh = true)
            }
        }
        binding.btnPlazaSortHot.setOnClickListener {
            if (plazaSort != "hot") {
                plazaSort = "hot"
                updatePlazaSortBarStyle()
                refreshPlazaList(forceRefresh = true)
            }
        }
    }

    private fun updatePlazaSortBarStyle() {
        val active = 0xFF000000.toInt()
        val idle = 0xFF999999.toInt()
        binding.btnPlazaSortLatest.setTextColor(if (plazaSort == "latest") active else idle)
        binding.btnPlazaSortHot.setTextColor(if (plazaSort == "hot") active else idle)
    }

    /**
     * 设置滑动手势：左右滑动切换「我创建的小助手」与「小助手广场」
     */
    private fun setupSwipeGesture() {
        var startX = 0f
        var startY = 0f
        var isSwipeDetected = false
        currentPage = if (binding.llMyPage.visibility == View.VISIBLE) 0 else 1
        val touchListener = View.OnTouchListener { view, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    isSwipeDetected = false
                    false
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - startX
                    val deltaY = event.rawY - startY
                    val absDeltaX = kotlin.math.abs(deltaX)
                    val absDeltaY = kotlin.math.abs(deltaY)
                    val swipeThreshold = 30f * resources.displayMetrics.density
                    if (absDeltaX > absDeltaY * 2f && absDeltaX > swipeThreshold) {
                        isSwipeDetected = true
                        view.parent?.requestDisallowInterceptTouchEvent(true)
                        true
                    } else {
                        view.parent?.requestDisallowInterceptTouchEvent(false)
                        false
                    }
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    if (isSwipeDetected) {
                        val deltaX = event.rawX - startX
                        val swipeThreshold = 80f * resources.displayMetrics.density
                        if (deltaX > swipeThreshold && currentPage == 1) {
                            switchTab(Tab.MY)
                            currentPage = 0
                            true
                        } else if (deltaX < -swipeThreshold && currentPage == 0) {
                            switchTab(Tab.PLAZA)
                            currentPage = 1
                            true
                        } else false
                    } else false
                }
                else -> false
            }
        }
        binding.scrollViewMy.setOnTouchListener(touchListener)
        binding.scrollViewPlaza.setOnTouchListener(touchListener)
    }

    private fun switchTab(tab: Tab) {
        activeTab = tab
        binding.btnTabPlaza.setTextColor(if (tab == Tab.PLAZA) 0xFF000000.toInt() else 0xFF999999.toInt())
        binding.btnTabMy.setTextColor(if (tab == Tab.MY) 0xFF000000.toInt() else 0xFF999999.toInt())

        val targetButton = if (tab == Tab.PLAZA) binding.btnTabPlaza else binding.btnTabMy
        moveIndicator(targetButton)

        binding.llToolbar.visibility = if (tab == Tab.MY) View.VISIBLE else View.GONE
        binding.llPlazaSort.visibility = if (tab == Tab.PLAZA) View.VISIBLE else View.GONE
        if (tab == Tab.PLAZA) updatePlazaSortBarStyle()
        refreshContent()
    }

    private fun moveIndicator(targetButton: android.widget.Button) {
        val indicator = binding.viewIndicator
        targetButton.post {
            val screenWidth = resources.displayMetrics.widthPixels.toFloat()
            val buttonWidth = (screenWidth / 2f).toInt()
            val layoutParams = indicator.layoutParams as? LinearLayout.LayoutParams ?: return@post
            layoutParams.width = buttonWidth
            // 我创建的小助手在左(marginStart=0)，小助手广场在右(marginStart=buttonWidth)
            layoutParams.marginStart = if (targetButton.id == R.id.btnTabMy) 0 else buttonWidth
            indicator.layoutParams = layoutParams
        }
    }

    private fun setupToolbar() {
        binding.btnAddAssistant.setOnClickListener { showAddAssistantDialog() }
        binding.btnAddAssistantInline.setOnClickListener { showAddAssistantDialog() }
        binding.btnNewAssistant.setOnClickListener { navigateToNewAssistant() }
        try { binding.root.findViewById<android.widget.Button>(R.id.btnNewAssistantInline)?.setOnClickListener { navigateToNewAssistant() } } catch (_: Exception) {}
    }

    /**
     * @param fromVisibilityResume 为 true 时表示自 [onResume] 触发：广场列表允许短时节流，避免反复进出页面重复拉取大 JSON。
     */
    private fun refreshContent(fromVisibilityResume: Boolean = false) {
        when (activeTab) {
            Tab.PLAZA -> {
                binding.llMyPage.visibility = View.GONE
                binding.llPlazaPage.visibility = View.VISIBLE
                refreshPlazaList(forceRefresh = !fromVisibilityResume)
            }
            Tab.MY -> {
                binding.llPlazaPage.visibility = View.GONE
                binding.llMyPage.visibility = View.VISIBLE
                refreshMyList()
            }
        }
    }

    private fun refreshPlazaList(forceRefresh: Boolean = false) {
        if (plazaLoading) return
        if (!forceRefresh && plazaList.isNotEmpty() && lastPlazaFetchSuccessElapsed > 0L) {
            val delta = SystemClock.elapsedRealtime() - lastPlazaFetchSuccessElapsed
            if (delta in 0 until PLAZA_LIST_REFRESH_MIN_INTERVAL_MS) return
        }
        plazaLoading = true
        binding.llPlazaEmpty.visibility = View.VISIBLE
        binding.llPlazaList.visibility = View.GONE
        binding.tvPlazaEmptyTitle.text = getString(R.string.plaza_loading)
        binding.tvPlazaEmptyDesc.text = ""

        lifecycleScope.launch {
            try {
                val api = CustomerServiceNetwork.getApiService()
                if (api == null) {
                    plazaLoading = false
                    binding.tvPlazaEmptyTitle.text = getString(R.string.assistant_plaza_empty_title)
                    binding.tvPlazaEmptyDesc.text = getString(R.string.plaza_add_failed)
                    return@launch
                }
                val viewerImei = ProfileManager.getOrGenerateImei(requireContext()).takeIf { it.isNotBlank() }
                val res = withContext(Dispatchers.IO) {
                    api.getPlazaAssistants(page = 1, limit = 50, imei = viewerImei, sort = plazaSort)
                }
                val list = if (res.isSuccessful) (res.body()?.assistants ?: emptyList()) else emptyList()
                if (res.isSuccessful) {
                    lastPlazaFetchSuccessElapsed = SystemClock.elapsedRealtime()
                }
                plazaList = list
                withContext(Dispatchers.Main) {
                    if (list.isEmpty()) {
                        binding.llPlazaEmpty.visibility = View.VISIBLE
                        binding.llPlazaList.visibility = View.GONE
                        binding.tvPlazaEmptyTitle.text = getString(R.string.assistant_plaza_empty_title)
                        binding.tvPlazaEmptyDesc.text = getString(R.string.assistant_plaza_empty_desc)
                    } else {
                        binding.llPlazaEmpty.visibility = View.GONE
                        binding.llPlazaList.visibility = View.VISIBLE
                        renderPlazaList(list)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "加载广场列表失败", e)
                withContext(Dispatchers.Main) {
                    binding.llPlazaEmpty.visibility = View.VISIBLE
                    binding.llPlazaList.visibility = View.GONE
                    binding.tvPlazaEmptyTitle.text = getString(R.string.assistant_plaza_empty_title)
                    binding.tvPlazaEmptyDesc.text = getString(R.string.plaza_add_failed)
                }
            } finally {
                plazaLoading = false
            }
        }
    }

    private fun decodeBase64AvatarBitmap(raw: String?): Bitmap? {
        if (raw.isNullOrBlank()) return null
        return try {
            val decodedBytes = Base64.decode(raw, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (_: Exception) {
            null
        }
    }

    private fun applyCircleAvatarOutline(iv: ImageView) {
        iv.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        iv.clipToOutline = true
    }

    /**
     * 广场头像：与好友列表一致，IO 线程解码 + [AvatarCacheManager] 内存缓存，避免主线程卡顿。
     */
    private fun bindPlazaAvatarAsync(
        iv: ImageView,
        tvLetter: TextView,
        avatarB64: String?,
        letter: String,
        cacheKey: String,
        validationTag: String,
    ) {
        tvLetter.text = letter
        applyCircleAvatarOutline(iv)
        if (avatarB64.isNullOrBlank()) {
            iv.setImageDrawable(null)
            iv.visibility = View.GONE
            tvLetter.visibility = View.VISIBLE
            return
        }
        iv.visibility = View.VISIBLE
        AvatarCacheManager.getCachedAvatar(cacheKey)?.let { cached ->
            iv.setImageBitmap(cached)
            tvLetter.visibility = View.GONE
            return
        }
        tvLetter.visibility = View.VISIBLE
        iv.setImageDrawable(null)
        iv.tag = validationTag
        lifecycleScope.launch {
            val bitmap = withContext(plazaAvatarDecodeDispatcher) { decodeBase64AvatarBitmap(avatarB64) }
            if (!isAdded || iv.tag != validationTag) return@launch
            if (bitmap != null) {
                AvatarCacheManager.putCachedAvatar(cacheKey, bitmap)
                iv.setImageBitmap(bitmap)
                tvLetter.visibility = View.GONE
            } else {
                iv.visibility = View.GONE
                tvLetter.visibility = View.VISIBLE
            }
        }
    }

    private fun creatorDisplayLetter(creatorLine: String): String {
        val sep = " · "
        return if (creatorLine.contains(sep)) {
            creatorLine.substringAfterLast(sep).trim().take(1).ifEmpty { "创" }
        } else {
            creatorLine.take(1).ifEmpty { "创" }
        }
    }

    private fun runPlazaListAction(
        item: PlazaAssistantItem,
        block: suspend (imei: String) -> Boolean,
        onDone: ((Boolean) -> Unit)? = null
    ) {
        val imei = ProfileManager.getOrGenerateImei(requireContext())
        if (imei.isBlank()) {
            Toast.makeText(requireContext(), R.string.plaza_need_bind, Toast.LENGTH_SHORT).show()
            onDone?.invoke(false)
            return
        }
        plazaAddingId = item.id
        if (plazaList.isNotEmpty()) {
            binding.llPlazaList.visibility = View.VISIBLE
            binding.llPlazaEmpty.visibility = View.GONE
            renderPlazaList(plazaList)
        }
        lifecycleScope.launch {
            try {
                val ok = block(imei)
                onDone?.invoke(ok)
            } finally {
                plazaAddingId = null
                refreshContent()
            }
        }
    }

    private fun applyIntroLikeButtonStyle(btnLike: ImageButton, liked: Boolean) {
        btnLike.imageTintList = null
        btnLike.imageAlpha = if (liked) 255 else 100
    }

    private fun patchPlazaItemLikes(plazaId: String, count: Int, liked: Boolean) {
        plazaList = plazaList.map {
            if (it.id == plazaId) it.copy(likes_count = count, liked_by_me = liked) else it
        }
        if (activeTab == Tab.PLAZA && binding.llPlazaList.visibility == View.VISIBLE) {
            renderPlazaList(plazaList)
        }
    }

    private fun showPlazaAssistantIntroDialog(item: PlazaAssistantItem) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_plaza_assistant_intro, null)
        dialogView.findViewById<TextView>(R.id.tvIntroAssistantName).text = item.name
        val introText = item.intro?.trim().orEmpty()
        dialogView.findViewById<TextView>(R.id.tvIntroIntro).text =
            if (introText.isNotEmpty()) introText else getString(R.string.plaza_intro_no_intro)
        dialogView.findViewById<TextView>(R.id.tvIntroBaseUrl).text = item.baseUrl.ifEmpty { "—" }
        val creatorLine = item.creator_imei?.trim().orEmpty().ifEmpty { "—" }
        dialogView.findViewById<TextView>(R.id.tvIntroCreatorLine).text = creatorLine
        val ivAsst = dialogView.findViewById<ImageView>(R.id.ivIntroAssistantAvatar)
        val tvAsstLetter = dialogView.findViewById<TextView>(R.id.tvIntroAssistantLetter)
        bindPlazaAvatarAsync(
            ivAsst,
            tvAsstLetter,
            item.avatar,
            item.name.take(1).ifEmpty { "助" },
            cacheKey = "plaza_asst_${item.id}",
            validationTag = "plaza_intro_asst_${item.id}",
        )
        val ivCreator = dialogView.findViewById<ImageView>(R.id.ivIntroCreatorAvatar)
        val tvCreatorLetter = dialogView.findViewById<TextView>(R.id.tvIntroCreatorLetter)
        val cLetter = if (creatorLine == "—") "创" else creatorDisplayLetter(creatorLine)
        bindPlazaAvatarAsync(
            ivCreator,
            tvCreatorLetter,
            item.creator_avatar,
            cLetter,
            cacheKey = "plaza_intro_creator_${item.id}",
            validationTag = "plaza_intro_creator_${item.id}",
        )

        val isCreator = item.is_creator == true
        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val btnClose = dialogView.findViewById<MaterialButton>(R.id.btnIntroClose)
        val btnPrimary = dialogView.findViewById<MaterialButton>(R.id.btnIntroPrimary)
        btnPrimary.text = getString(
            if (isCreator) R.string.plaza_intro_remove else R.string.plaza_intro_add_mine,
        )
        val density = resources.displayMetrics.density
        val padH = (12 * density).toInt()
        val padV = (8 * density).toInt()
        btnClose.setTextColor(0xFF000000.toInt())
        if (isCreator) {
            btnPrimary.setTextColor(0xFF000000.toInt())
            btnPrimary.setBackgroundResource(R.drawable.bg_plaza_intro_remove_btn)
            ViewCompat.setBackgroundTintList(btnPrimary, null)
            btnPrimary.setPadding(padH, padV, padH, padV)
        } else {
            btnPrimary.setTextColor(0xFFFFFFFF.toInt())
            btnPrimary.setBackgroundResource(R.drawable.bg_plaza_intro_add_btn)
            ViewCompat.setBackgroundTintList(btnPrimary, null)
            btnPrimary.setPadding(padH, padV, padH, padV)
        }

        val btnLike = dialogView.findViewById<ImageButton>(R.id.btnIntroLike)
        val tvLikeCount = dialogView.findViewById<TextView>(R.id.tvIntroLikeCount)
        fun refreshLikeUi(liked: Boolean, count: Int) {
            tvLikeCount.text = count.toString()
            applyIntroLikeButtonStyle(btnLike, liked)
        }
        refreshLikeUi(item.liked_by_me == true, item.likes_count ?: 0)
        var likeBusy = false
        btnLike.setOnClickListener {
            if (likeBusy) return@setOnClickListener
            val imeiLike = ProfileManager.getOrGenerateImei(requireContext())
            if (imeiLike.isBlank()) {
                Toast.makeText(requireContext(), R.string.plaza_need_bind, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            likeBusy = true
            lifecycleScope.launch {
                try {
                    val api = CustomerServiceNetwork.getApiService()
                    if (api == null) {
                        Toast.makeText(requireContext(), R.string.plaza_like_failed, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    val res = withContext(Dispatchers.IO) {
                        api.togglePlazaLike(item.id, PlazaAddRequest(imei = imeiLike))
                    }
                    if (res.isSuccessful && res.body()?.success == true) {
                        val b = res.body()!!
                        refreshLikeUi(b.liked_by_me, b.likes_count)
                        patchPlazaItemLikes(item.id, b.likes_count, b.liked_by_me)
                    } else {
                        Toast.makeText(requireContext(), R.string.plaza_like_failed, Toast.LENGTH_SHORT).show()
                    }
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), R.string.plaza_like_failed, Toast.LENGTH_SHORT).show()
                } finally {
                    likeBusy = false
                }
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        btnPrimary.setOnClickListener {
            if (isCreator) {
                android.app.AlertDialog.Builder(requireContext())
                    .setMessage(R.string.plaza_remove_confirm)
                    .setPositiveButton(R.string.plaza_intro_remove) { _, _ ->
                        runPlazaListAction(item, { imei -> removePlazaAssistantFromPlaza(item, imei) }) { ok ->
                            if (ok) dialog.dismiss()
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            } else {
                runPlazaListAction(item, { imei -> addPlazaAssistantFromPlaza(item, imei) }) { ok ->
                    if (ok) dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    /** @return 是否添加成功 */
    private suspend fun addPlazaAssistantFromPlaza(item: PlazaAssistantItem, imei: String): Boolean {
        return try {
            val api = CustomerServiceNetwork.getApiService()
            if (api == null) {
                Toast.makeText(requireContext(), R.string.plaza_add_failed, Toast.LENGTH_SHORT).show()
                false
            } else {
                val res = withContext(Dispatchers.IO) {
                    api.addPlazaAssistantToMine(item.id, PlazaAddRequest(imei = imei))
                }
                if (res.isSuccessful && res.body()?.success == true) {
                    val assistant = res.body()?.assistant
                    if (assistant != null) {
                        CustomAssistantManager.add(requireContext(), CustomAssistantManager.fromApiItem(assistant))
                        syncAssistantsToCloud()
                        Toast.makeText(requireContext(), R.string.plaza_add_success, Toast.LENGTH_SHORT).show()
                        refreshKey++
                        true
                    } else {
                        Toast.makeText(requireContext(), R.string.plaza_add_failed, Toast.LENGTH_SHORT).show()
                        false
                    }
                } else {
                    Toast.makeText(requireContext(), R.string.plaza_add_failed, Toast.LENGTH_SHORT).show()
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "添加广场小助手失败", e)
            Toast.makeText(requireContext(), R.string.plaza_add_failed, Toast.LENGTH_SHORT).show()
            false
        }
    }

    private suspend fun removePlazaAssistantFromPlaza(item: PlazaAssistantItem, imei: String): Boolean {
        return try {
            val api = CustomerServiceNetwork.getApiService()
            if (api == null) {
                Toast.makeText(requireContext(), R.string.plaza_remove_failed, Toast.LENGTH_SHORT).show()
                false
            } else {
                val res = withContext(Dispatchers.IO) {
                    api.removePlazaAssistantFromPlaza(item.id, PlazaAddRequest(imei = imei))
                }
                if (res.isSuccessful && res.body()?.success == true) {
                    plazaList = plazaList.filter { it.id != item.id }
                    Toast.makeText(requireContext(), R.string.plaza_remove_success, Toast.LENGTH_SHORT).show()
                    refreshKey++
                    true
                } else {
                    Toast.makeText(requireContext(), R.string.plaza_remove_failed, Toast.LENGTH_SHORT).show()
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "广场下架失败", e)
            Toast.makeText(requireContext(), R.string.plaza_remove_failed, Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun renderPlazaList(list: List<PlazaAssistantItem>) {
        binding.llPlazaList.removeAllViews()
        list.forEach { item ->
            val card = layoutInflater.inflate(R.layout.item_assistant_plaza_plaza_card, binding.llPlazaList, false)
            card.setOnClickListener { showPlazaAssistantIntroDialog(item) }
            card.findViewById<TextView>(R.id.tvCardName).text = item.name
            card.findViewById<TextView>(R.id.tvCardIntro).apply {
                text = item.intro?.takeIf { it.isNotBlank() }
                visibility = if (!item.intro.isNullOrBlank()) View.VISIBLE else View.GONE
            }
            val ivAvatar = card.findViewById<ImageView>(R.id.ivCardAvatar)
            val tvLetter = card.findViewById<TextView>(R.id.tvCardAvatarLetter)
            bindPlazaAvatarAsync(
                ivAvatar,
                tvLetter,
                item.avatar,
                item.name.take(1).ifEmpty { "助" },
                cacheKey = "plaza_asst_${item.id}",
                validationTag = item.id,
            )
            val btnAdd = card.findViewById<MaterialButton>(R.id.btnCardAdd)
            val strokePx = (resources.displayMetrics.density * 1f).toInt().coerceAtLeast(1)
            val isCreator = item.is_creator == true
            if (isCreator) {
                btnAdd.text = if (plazaAddingId == item.id) getString(R.string.plaza_remove_ing) else getString(R.string.plaza_remove)
                btnAdd.setTextColor(0xFF000000.toInt())
                btnAdd.backgroundTintList = ColorStateList.valueOf(0xFFFFFFFF.toInt())
                btnAdd.strokeWidth = strokePx
                btnAdd.strokeColor = ColorStateList.valueOf(0xFFE0E0E0.toInt())
                btnAdd.setOnClickListener {
                    android.app.AlertDialog.Builder(requireContext())
                        .setMessage(R.string.plaza_remove_confirm)
                        .setPositiveButton(R.string.plaza_remove) { _, _ -> handleRemoveFromPlaza(item) }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            } else {
                btnAdd.text = if (plazaAddingId == item.id) getString(R.string.plaza_add_ing) else getString(R.string.plaza_add)
                btnAdd.setTextColor(0xFFFFFFFF.toInt())
                btnAdd.backgroundTintList = ColorStateList.valueOf(0xFF10AEFF.toInt())
                btnAdd.strokeWidth = 0
                btnAdd.setOnClickListener { handleAddFromPlaza(item) }
            }
            btnAdd.isEnabled = plazaAddingId == null
            binding.llPlazaList.addView(card)
        }
    }

    private fun handleAddFromPlaza(item: PlazaAssistantItem) {
        runPlazaListAction(item, { imei -> addPlazaAssistantFromPlaza(item, imei) })
    }

    private fun handleRemoveFromPlaza(item: PlazaAssistantItem) {
        runPlazaListAction(item, { imei -> removePlazaAssistantFromPlaza(item, imei) })
    }

    private fun handlePublishToPlaza(assistant: CustomAssistantManager.CustomAssistant) {
        val imei = ProfileManager.getOrGenerateImei(requireContext())
        if (imei.isBlank()) {
            Toast.makeText(requireContext(), R.string.plaza_need_bind, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val api = CustomerServiceNetwork.getApiService()
                if (api == null) {
                    Toast.makeText(requireContext(), R.string.plaza_publish_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val apiItem = CustomAssistantManager.toApiItem(assistant)
                val res = withContext(Dispatchers.IO) {
                    api.submitToPlaza(PlazaSubmitRequest(imei = imei, assistant = apiItem))
                }
                withContext(Dispatchers.Main) {
                    if (res.isSuccessful && res.body()?.success == true) {
                        Toast.makeText(requireContext(), R.string.plaza_publish_success, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), R.string.plaza_publish_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "发布到广场失败", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), R.string.plaza_publish_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun syncAssistantsToCloud() {
        Log.d(TAG, "APK 侧已禁用自定义小助手云端写入（POST /api/custom-assistants）")
    }

    private fun refreshMyList() {
        val list = CustomAssistantManager.getVisibleAll(requireContext())
        if (list.isEmpty()) {
            binding.llMyEmpty.visibility = View.VISIBLE
            binding.llMyList.visibility = View.GONE
            binding.tvMyEmptyDesc.text = getString(R.string.no_assistant)
            binding.tvMyEmptyHint.visibility = View.VISIBLE
            binding.root.findViewById<View>(R.id.btnAddAssistantInline).visibility = View.VISIBLE
            try { binding.root.findViewById<View>(R.id.btnNewAssistantInline)?.visibility = View.VISIBLE } catch (_: Exception) {}
        } else {
            binding.llMyEmpty.visibility = View.GONE
            binding.llMyList.visibility = View.VISIBLE
            renderMyList(list)
        }
    }

    private fun renderMyList(list: List<CustomAssistantManager.CustomAssistant>) {
        binding.llMyList.removeAllViews()
        list.forEach { assistant ->
            val card = layoutInflater.inflate(R.layout.item_assistant_plaza_card, binding.llMyList, false)
            card.findViewById<TextView>(R.id.tvCardName).text = assistant.name
            card.findViewById<TextView>(R.id.tvCardIntro).apply {
                text = assistant.intro.ifEmpty { null }
                visibility = if (assistant.intro.isNotEmpty()) View.VISIBLE else View.GONE
            }
            val ivAvatar = card.findViewById<ImageView>(R.id.ivCardAvatar)
            val tvLetter = card.findViewById<TextView>(R.id.tvCardAvatarLetter)
            tvLetter.text = assistant.name.take(1).ifEmpty { "助" }
            if (!assistant.avatar.isNullOrBlank()) {
                try {
                    val decodedBytes = Base64.decode(assistant.avatar, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                    if (bitmap != null) {
                        ivAvatar.setImageBitmap(bitmap)
                        ivAvatar.outlineProvider = object : android.view.ViewOutlineProvider() {
                            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                                outline.setOval(0, 0, view.width, view.height)
                            }
                        }
                        ivAvatar.clipToOutline = true
                        ivAvatar.visibility = View.VISIBLE
                        tvLetter.visibility = View.GONE
                    } else {
                        ivAvatar.visibility = View.GONE
                        tvLetter.visibility = View.VISIBLE
                    }
                } catch (_: Exception) {
                    ivAvatar.visibility = View.GONE
                    tvLetter.visibility = View.VISIBLE
                }
            } else {
                ivAvatar.visibility = View.GONE
                tvLetter.visibility = View.VISIBLE
            }
            card.findViewById<View>(R.id.btnCardPublish).setOnClickListener {
                handlePublishToPlaza(assistant)
            }
            card.findViewById<View>(R.id.btnCardDelete).setOnClickListener {
                showDeleteConfirm(assistant)
            }
            binding.llMyList.addView(card)
        }
    }

    private fun showDeleteConfirm(assistant: CustomAssistantManager.CustomAssistant) {
        android.app.AlertDialog.Builder(requireContext())
            .setMessage(getString(R.string.delete_assistant_confirm, assistant.name))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                CustomAssistantManager.remove(requireContext(), assistant.id)
                refreshKey++
                refreshContent()
                Toast.makeText(requireContext(), R.string.delete_assistant_success, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun showAddAssistantDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_assistant, null)
        val etLink = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etAssistantLink)
        val btnClose = dialogView.findViewById<android.widget.ImageButton>(R.id.btnClose)
        val btnCancel = dialogView.findViewById<android.widget.Button>(R.id.btnCancel)
        val btnAdd = dialogView.findViewById<android.widget.Button>(R.id.btnAdd)

        val dialog = android.app.AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val cornerRadiusPx = (20 * resources.displayMetrics.density).toInt()
        dialogView.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: android.view.View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerRadiusPx.toFloat())
            }
        }
        dialogView.clipToOutline = true

        fun dismiss() { if (dialog.isShowing) dialog.dismiss() }
        btnClose.setOnClickListener { dismiss() }
        btnCancel.setOnClickListener { dismiss() }
        btnAdd.setOnClickListener {
            val link = etLink.text?.toString()?.trim() ?: ""
            if (link.isBlank()) {
                Toast.makeText(requireContext(), R.string.add_assistant_paste_hint, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val parsed = CustomAssistantManager.parseAssistantUrl(link, requireContext())
            if (parsed != null) {
                CustomAssistantManager.add(requireContext(), parsed)
                dismiss()
                refreshKey++
                refreshContent()
                Toast.makeText(requireContext(), R.string.add_assistant_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.add_assistant_invalid_link, Toast.LENGTH_SHORT).show()
            }
        }
        dialog.show()
    }

    private fun navigateToNewAssistant() {
        val newAssistantFragment = NewAssistantFragment()
        parentFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_from_right,
                R.anim.slide_out_to_left,
                R.anim.slide_in_from_left,
                R.anim.slide_out_to_right
            )
            .replace(R.id.fragmentContainer, newAssistantFragment)
            .addToBackStack(null)
            .commitAllowingStateLoss()
    }
}
