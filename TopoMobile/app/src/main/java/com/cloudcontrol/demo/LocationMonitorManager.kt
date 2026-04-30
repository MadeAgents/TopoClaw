package com.cloudcontrol.demo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * 独立的位置变化监视器：
 * - 定时获取当前位置
 * - 检测“显著位移 + 稳定停留”
 * - 完成逆地理编码及附近POI提取
 * - 主动发送到 custom_topoclaw 会话
 */
class LocationMonitorManager(
    private val context: Context,
    private val webSocketProvider: () -> CustomerServiceWebSocket?,
) {
    data class CheckResult(
        val sent: Boolean,
        val reason: String,
    )
    companion object {
        private const val TAG = "LocationMonitorManager"
        private const val PREFS_NAME = "app_prefs"

        private const val KEY_ENABLED = "location_change_monitor_enabled"
        private const val KEY_LAST_NOTIFY_AT = "location_change_last_notify_at"
        private const val KEY_ANCHOR_LAT = "location_change_anchor_lat"
        private const val KEY_ANCHOR_LNG = "location_change_anchor_lng"
        private const val KEY_LAST_STABLE_LAT = "location_change_last_stable_lat"
        private const val KEY_LAST_STABLE_LNG = "location_change_last_stable_lng"

        private const val KEY_API_KEY = "location_change_api_key"
        private const val KEY_REGEO_URL = "location_change_regeo_url"
        private const val KEY_TEST_MODE_ENABLED = "location_change_test_mode_enabled"
        private const val KEY_TEST_START_ADDRESS = "location_change_test_start_address"
        private const val KEY_DEBUG_LOGS = "location_change_debug_logs"
        private const val MAX_DEBUG_LOGS = 200

        private const val DEFAULT_REGEO_URL = "https://restapi.amap.com/v3/geocode/regeo"

        private const val MONITOR_INTERVAL_MS = 5 * 60 * 1000L
        private const val SAMPLE_TIMEOUT_MS = 10_000L

        private const val ENTER_DISTANCE_METERS = 1000f
        private const val STAY_RADIUS_METERS = 250f
        private const val CANDIDATE_MIN_STAY_MS = 2 * 60 * 1000L
        private const val COOLDOWN_MS = 45 * 60 * 1000L

        private const val MAX_POI_COUNT = 3
        private const val MAX_POI_NAME_LEN = 16

        fun getDebugLogs(context: Context): List<String> {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(KEY_DEBUG_LOGS, null) ?: return emptyList()
            return try {
                val arr = JSONArray(raw)
                buildList {
                    for (i in 0 until arr.length()) {
                        val line = arr.optString(i, "").trim()
                        if (line.isNotEmpty()) add(line)
                    }
                }
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun clearDebugLogs(context: Context) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(KEY_DEBUG_LOGS).apply()
        }
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private var monitorJob: Job? = null
    private var candidate: CandidatePlace? = null
    private var cachedTestAddress: String = ""
    private var cachedTestAnchor: Anchor? = null

    private data class Anchor(val lat: Double, val lng: Double)
    private data class CandidatePlace(val center: Location, val firstSeenAt: Long)

    fun start() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            Log.d(TAG, "位置变化监视已启动")
            while (isActive) {
                try {
                    runOnce()
                } catch (e: Exception) {
                    Log.w(TAG, "位置监视tick异常: ${e.message}")
                }
                delay(MONITOR_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        candidate = null
        Log.d(TAG, "位置变化监视已停止")
    }

    fun triggerCheckNow(onResult: ((CheckResult) -> Unit)? = null) {
        scope.launch {
            Log.d(TAG, "手动触发一次位置变化检测")
            val result = runCatching { runOnce() }
                .getOrElse { e ->
                    Log.w(TAG, "手动触发位置检测异常: ${e.message}")
                    CheckResult(sent = false, reason = "检测异常: ${e.message ?: "unknown"}")
                }
            onResult?.invoke(result)
        }
    }

    private suspend fun runOnce(): CheckResult {
        appendDebugLog("开始位置检测请求")
        if (!prefs.getBoolean(KEY_ENABLED, true)) {
            appendDebugLog("检测终止：位置监视开关已关闭")
            return CheckResult(sent = false, reason = "位置监视开关已关闭")
        }
        if (!hasLocationPermission()) {
            appendDebugLog("检测终止：缺少定位权限")
            return CheckResult(sent = false, reason = "缺少定位权限")
        }

        val current = withContext(Dispatchers.Main) {
            getCurrentLocationCompat(
                timeoutMs = SAMPLE_TIMEOUT_MS,
                requiresFine = false,
            )
        } ?: run {
            Log.d(TAG, "本轮未获取到有效位置，跳过")
            appendDebugLog("定位失败：未获取到有效位置（NETWORK不可用或超时）")
            return CheckResult(sent = false, reason = "未获取到定位（NETWORK不可用或超时）")
        }
        appendDebugLog(
            "定位返回：lat=${"%.6f".format(Locale.US, current.latitude)}, " +
                "lng=${"%.6f".format(Locale.US, current.longitude)}, " +
                "acc=${current.accuracy.toInt()}m, provider=${current.provider ?: "unknown"}"
        )

        if (!isAccurateEnough(current)) {
            Log.d(TAG, "本轮位置精度不足，acc=${current.accuracy}")
            appendDebugLog("检测终止：定位精度不足（${current.accuracy.toInt()}m）")
            return CheckResult(sent = false, reason = "定位精度不足（${current.accuracy.toInt()}m）")
        }

        val testAnchor = resolveTestAnchorIfEnabled()
        val anchor = testAnchor ?: readAnchor()
        if (anchor == null) {
            if (testAnchor == null) {
                writeAnchor(current.latitude, current.longitude)
            }
            Log.d(TAG, "初始化位置锚点: ${current.latitude}, ${current.longitude}")
            return CheckResult(sent = false, reason = "已初始化锚点，等待下一次检测")
        }

        val distance = FloatArray(1)
        Location.distanceBetween(
            anchor.lat, anchor.lng,
            current.latitude, current.longitude,
            distance
        )
        val movedMeters = distance[0]
        val now = System.currentTimeMillis()
        val lastNotifyAt = prefs.getLong(KEY_LAST_NOTIFY_AT, 0L)
        if (movedMeters < ENTER_DISTANCE_METERS) {
            candidate = null
            appendDebugLog("大变化判定：否（位移 ${movedMeters.toInt()}m < ${ENTER_DISTANCE_METERS.toInt()}m）")
            return CheckResult(sent = false, reason = "距离变化不足：${movedMeters.toInt()}m < ${ENTER_DISTANCE_METERS.toInt()}m")
        }
        appendDebugLog("大变化判定：是（位移 ${movedMeters.toInt()}m >= ${ENTER_DISTANCE_METERS.toInt()}m）")
        if (now - lastNotifyAt < COOLDOWN_MS) {
            Log.d(TAG, "位移达标但仍在冷却期，moved=$movedMeters")
            val leftMin = ((COOLDOWN_MS - (now - lastNotifyAt)).coerceAtLeast(0L) / 60000L)
            appendDebugLog("检测终止：位移达标但处于冷却期（剩余约${leftMin}分钟）")
            return CheckResult(sent = false, reason = "冷却中，剩余约${leftMin}分钟")
        }

        val c = candidate
        if (c == null) {
            candidate = CandidatePlace(center = current, firstSeenAt = now)
            Log.d(TAG, "进入候选新地点，moved=$movedMeters")
            return CheckResult(sent = false, reason = "已进入候选新地点，等待稳定停留")
        }

        val drift = c.center.distanceTo(current)
        if (drift > STAY_RADIUS_METERS) {
            candidate = CandidatePlace(center = current, firstSeenAt = now)
            Log.d(TAG, "候选地点漂移过大，重置候选: drift=$drift")
            return CheckResult(sent = false, reason = "候选点漂移较大，已重置候选")
        }
        if (now - c.firstSeenAt < CANDIDATE_MIN_STAY_MS) {
            Log.d(TAG, "候选地点观察中，elapsed=${now - c.firstSeenAt}")
            val waitSec = ((CANDIDATE_MIN_STAY_MS - (now - c.firstSeenAt)).coerceAtLeast(0L) / 1000L)
            return CheckResult(sent = false, reason = "候选地点观察中，还需约${waitSec}秒")
        }

        val lastStable = readLastStableLocation()
        if (lastStable != null) {
            val oldStableText = "lat=${"%.6f".format(Locale.US, lastStable.lat)}, lng=${"%.6f".format(Locale.US, lastStable.lng)}"
            Log.d(TAG, "上一个稳定位置: $oldStableText")
            appendDebugLog("上一个稳定位置：$oldStableText")
        } else {
            Log.d(TAG, "上一个稳定位置: 无")
            appendDebugLog("上一个稳定位置：无")
        }
        writeLastStableLocation(current.latitude, current.longitude)
        val newStableText = "lat=${"%.6f".format(Locale.US, current.latitude)}, lng=${"%.6f".format(Locale.US, current.longitude)}"
        Log.d(TAG, "检测到新的稳定位置，已替换: $newStableText")
        appendDebugLog("新的稳定位置已保存并替换旧值：$newStableText")

        val geocode = withContext(Dispatchers.IO) {
            reverseGeocodeAndPois(current.longitude, current.latitude)
        }
        if (geocode == null) {
            Log.w(TAG, "逆地理失败，跳过主动消息")
            appendDebugLog("检测终止：逆地理失败")
            return CheckResult(sent = false, reason = "逆地理失败")
        }
        val proactiveText = buildProactiveMessage(geocode)
        if (proactiveText.isBlank()) return CheckResult(sent = false, reason = "消息内容为空")

        val ws = webSocketProvider()
        if (ws == null || !ws.isConnected()) {
            Log.w(TAG, "WebSocket不可用，无法主动发送位置提醒")
            appendDebugLog("检测终止：WebSocket未连接，无法发送提醒")
            return CheckResult(sent = false, reason = "WebSocket未连接")
        }

        ws.sendAssistantSyncMessage(
            sender = "TopoClaw",
            content = proactiveText,
            conversationId = "custom_topoclaw",
        )
        persistLocalProactiveMessage(
            conversationId = "custom_topoclaw",
            sender = "TopoClaw",
            content = proactiveText,
            timestamp = now,
        )
        prefs.edit()
            .putLong(KEY_LAST_NOTIFY_AT, now)
            .apply()
        if (testAnchor == null) {
            writeAnchor(current.latitude, current.longitude)
        }
        candidate = null
        Log.d(TAG, "位置变化主动提醒已发送")
        appendDebugLog("提醒已发送到 custom_topoclaw")
        return CheckResult(sent = true, reason = "已发送位置变化提醒到 custom_topoclaw")
    }

    private suspend fun resolveTestAnchorIfEnabled(): Anchor? {
        if (!prefs.getBoolean(KEY_TEST_MODE_ENABLED, false)) return null
        val testAddress = prefs.getString(KEY_TEST_START_ADDRESS, "")?.trim().orEmpty()
        if (testAddress.isBlank()) return null
        if (cachedTestAddress == testAddress && cachedTestAnchor != null) {
            return cachedTestAnchor
        }
        val resolved = withContext(Dispatchers.IO) { geocodeAddressToAnchor(testAddress) }
        if (resolved != null) {
            cachedTestAddress = testAddress
            cachedTestAnchor = resolved
        }
        return resolved
    }

    private fun hasLocationPermission(): Boolean {
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return coarse || fine
    }

    private fun isAccurateEnough(location: Location): Boolean {
        val acc = location.accuracy
        return acc <= 100f
    }

    private fun readAnchor(): Anchor? {
        if (!prefs.contains(KEY_ANCHOR_LAT) || !prefs.contains(KEY_ANCHOR_LNG)) return null
        return Anchor(
            lat = prefs.getString(KEY_ANCHOR_LAT, null)?.toDoubleOrNull() ?: return null,
            lng = prefs.getString(KEY_ANCHOR_LNG, null)?.toDoubleOrNull() ?: return null,
        )
    }

    private fun writeAnchor(lat: Double, lng: Double) {
        prefs.edit()
            .putString(KEY_ANCHOR_LAT, lat.toString())
            .putString(KEY_ANCHOR_LNG, lng.toString())
            .apply()
    }

    private fun readLastStableLocation(): Anchor? {
        if (!prefs.contains(KEY_LAST_STABLE_LAT) || !prefs.contains(KEY_LAST_STABLE_LNG)) return null
        return Anchor(
            lat = prefs.getString(KEY_LAST_STABLE_LAT, null)?.toDoubleOrNull() ?: return null,
            lng = prefs.getString(KEY_LAST_STABLE_LNG, null)?.toDoubleOrNull() ?: return null,
        )
    }

    private fun writeLastStableLocation(lat: Double, lng: Double) {
        prefs.edit()
            .putString(KEY_LAST_STABLE_LAT, lat.toString())
            .putString(KEY_LAST_STABLE_LNG, lng.toString())
            .apply()
    }

    private data class GeocodeResult(
        val address: String,
        val pois: List<String>,
    )

    private fun reverseGeocodeAndPois(lng: Double, lat: Double): GeocodeResult? {
        val key = prefs.getString(KEY_API_KEY, null)
            ?.trim()
            .orEmpty()
            .ifBlank { BuildConfig.LOCATION_CHANGE_API_KEY.trim() }
        val baseUrl = prefs.getString(KEY_REGEO_URL, null)?.trim().orEmpty().ifBlank { DEFAULT_REGEO_URL }
        if (key.isBlank() || baseUrl.isBlank()) return null

        val httpUrl = baseUrl.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("key", key)
            ?.addQueryParameter("location", "$lng,$lat")
            ?.addQueryParameter("extensions", "all")
            ?.addQueryParameter("output", "json")
            ?.build() ?: return null

        return try {
            val req = Request.Builder().url(httpUrl).get().build()
            val rsp = httpClient.newCall(req).execute()
            rsp.use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return null
                val json = JSONObject(body)
                if (json.optString("status", "0") != "1") return null
                val regeo = json.optJSONObject("regeocode") ?: return null
                val address = regeo.optString("formatted_address", "").trim()
                val poiArray = regeo.optJSONArray("pois")
                val pois = mutableListOf<String>()
                if (poiArray != null) {
                    for (i in 0 until minOf(poiArray.length(), MAX_POI_COUNT)) {
                        val poi = poiArray.optJSONObject(i) ?: continue
                        val name = poi.optString("name", "").trim()
                        if (name.isNotBlank()) {
                            pois.add(name.take(MAX_POI_NAME_LEN))
                        }
                    }
                }
                if (address.isBlank()) return null
                GeocodeResult(address = address, pois = pois)
            }
        } catch (e: Exception) {
            Log.w(TAG, "逆地理请求异常: ${e.message}")
            null
        }
    }

    private fun buildProactiveMessage(geo: GeocodeResult): String {
        val poiText = if (geo.pois.isNotEmpty()) geo.pois.joinToString("、") else "一些值得逛的地点"
        return "我发现你到了新的地方：${geo.address}。附近有${poiText}，需要我帮你查路线、停车、餐饮或天气吗？"
    }

    private fun persistLocalProactiveMessage(
        conversationId: String,
        sender: String,
        content: String,
        timestamp: Long,
    ) {
        runCatching {
            val appPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val msgKey = AssistantSyncMessageHelper.resolveAssistantMsgKey(context, conversationId, null)
            val existingMessagesJson = appPrefs.getString(msgKey, null)
            val messagesArray = if (existingMessagesJson != null) {
                org.json.JSONArray(existingMessagesJson)
            } else {
                org.json.JSONArray()
            }
            messagesArray.put(
                JSONObject().apply {
                    put("sender", sender)
                    put("message", content)
                    put("type", "answer")
                    put("timestamp", timestamp)
                    put("uuid", java.util.UUID.randomUUID().toString())
                }
            )
            appPrefs.edit().putString(msgKey, messagesArray.toString()).apply()

            val conversationPrefs = context.getSharedPreferences("conversations", Context.MODE_PRIVATE)
            conversationPrefs.edit()
                .putString("${conversationId}_last_message", content)
                .putLong("${conversationId}_last_time", timestamp)
                .apply()

            ConversationSessionNotifier.incrementUnread(context, conversationId, 1)
            Log.d(TAG, "位置变化提醒已写入本地会话: conv=$conversationId, msgKey=$msgKey")
        }.onFailure { e ->
            Log.w(TAG, "位置变化提醒写入本地会话失败: ${e.message}")
        }
    }

    private fun appendDebugLog(message: String) {
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(java.util.Date())
        val line = "[$now] $message"
        runCatching {
            val raw = prefs.getString(KEY_DEBUG_LOGS, null)
            val arr = if (raw.isNullOrBlank()) JSONArray() else JSONArray(raw)
            arr.put(line)
            while (arr.length() > MAX_DEBUG_LOGS) {
                arr.remove(0)
            }
            prefs.edit().putString(KEY_DEBUG_LOGS, arr.toString()).apply()
        }.onFailure { e ->
            Log.w(TAG, "记录位置监视日志失败: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun geocodeAddressToAnchor(address: String): Anchor? {
        return try {
            val geocoder = Geocoder(context, Locale.CHINA)
            val results = geocoder.getFromLocationName(address, 1)
            val loc = results?.firstOrNull() ?: return null
            Anchor(loc.latitude, loc.longitude)
        } catch (e: Exception) {
            Log.w(TAG, "测试起始地址解析失败: ${e.message}")
            null
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun getCurrentLocationCompat(timeoutMs: Long, requiresFine: Boolean): Location? {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val networkEnabled = runCatching { locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        if (!networkEnabled) return null
        val provider = LocationManager.NETWORK_PROVIDER
        return suspendCancellableCoroutine { cont ->
            val handler = Handler(Looper.getMainLooper())
            var completed = false
            var listenerRef: LocationListener? = null

            fun complete(location: Location?) {
                if (completed) return
                completed = true
                listenerRef?.let { runCatching { locationManager.removeUpdates(it) } }
                handler.removeCallbacksAndMessages(null)
                if (cont.isActive) cont.resume(location)
            }

            handler.postDelayed({ complete(null) }, timeoutMs)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val executor = ContextCompat.getMainExecutor(context)
                runCatching {
                    locationManager.getCurrentLocation(provider, null, executor) { loc -> complete(loc) }
                }.onFailure {
                    complete(null)
                }
            } else {
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        complete(location)
                    }
                }
                listenerRef = listener
                runCatching {
                    @Suppress("DEPRECATION")
                    locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }.onFailure {
                    complete(null)
                }
            }

            cont.invokeOnCancellation {
                listenerRef?.let { runCatching { locationManager.removeUpdates(it) } }
                handler.removeCallbacksAndMessages(null)
            }
        }
    }
}

