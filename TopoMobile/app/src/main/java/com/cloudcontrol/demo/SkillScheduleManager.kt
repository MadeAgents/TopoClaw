package com.cloudcontrol.demo

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * 技能定时任务管理器
 */
object SkillScheduleManager {
    private const val TAG = "SkillScheduleManager"
    private const val ACTION_SKILL_REMINDER = "com.cloudcontrol.demo.ACTION_SKILL_REMINDER"
    private const val EXTRA_SKILL_ID = "skill_id"
    
    // 性能优化：缓存计算结果，避免短时间内重复计算
    private data class CacheKey(
        val scheduleType: ScheduleType,
        val targetTime: Long,
        val repeatDays: List<Int>?
    )
    
    private data class CacheValue(
        val nextTime: Long?,
        val cacheTime: Long
    )
    
    private val calculationCache = mutableMapOf<CacheKey, CacheValue>()
    private const val CACHE_VALIDITY_MS = 1000L // 缓存有效期1秒
    
    /**
     * 设置技能定时任务
     */
    fun scheduleSkill(context: Context, skill: Skill) {
        val config = skill.scheduleConfig ?: return
        if (!config.isEnabled) {
            cancelSchedule(context, skill.id)
            return
        }
        
        val nextTime = calculateNextTriggerTime(config)
        if (nextTime == null || nextTime <= System.currentTimeMillis()) {
            Log.w(TAG, "计算的下次触发时间无效: $nextTime")
            return
        }
        
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ACTION_SKILL_REMINDER).apply {
            putExtra(EXTRA_SKILL_ID, skill.id)
            setPackage(context.packageName)
        }
        
        val requestCode = skill.id.hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        try {
            // 使用setAlarmClock可以获得更高的精确度和优先级（Android 5.0+）
            // 它会在系统UI中显示闹钟图标，并且系统会尽量精确触发
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                // 创建显示闹钟的Intent（点击通知栏的闹钟图标时打开应用）
                val showIntent = Intent(context, MainActivity::class.java).apply {
                    putExtra("skill_reminder_id", skill.id)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                val showPendingIntent = PendingIntent.getActivity(
                    context,
                    requestCode + 10000,
                    showIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                
                val alarmClockInfo = android.app.AlarmManager.AlarmClockInfo(nextTime, showPendingIntent)
                alarmManager.setAlarmClock(alarmClockInfo, pendingIntent)
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    nextTime,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, nextTime, pendingIntent)
            }
            val timeFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            Log.d(TAG, "定时任务已设置: skillId=${skill.id}, nextTime=${timeFormat.format(java.util.Date(nextTime))}, currentTime=${timeFormat.format(java.util.Date(System.currentTimeMillis()))}")
        } catch (e: Exception) {
            Log.e(TAG, "设置定时任务失败: ${e.message}", e)
        }
    }
    
    /**
     * 取消技能定时任务
     */
    fun cancelSchedule(context: Context, skillId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(ACTION_SKILL_REMINDER).apply {
            putExtra(EXTRA_SKILL_ID, skillId)
            setPackage(context.packageName)
        }
        val requestCode = skillId.hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        Log.d(TAG, "定时任务已取消: skillId=$skillId")
    }
    
    /**
     * 计算下次触发时间
     * 使用缓存机制优化性能，避免短时间内重复计算
     */
    fun calculateNextTriggerTime(config: SkillScheduleConfig): Long? {
        val currentTime = System.currentTimeMillis()
        
        // 检查缓存
        val cacheKey = CacheKey(
            scheduleType = config.scheduleType,
            targetTime = config.targetTime,
            repeatDays = config.repeatDays
        )
        
        val cachedValue = calculationCache[cacheKey]
        if (cachedValue != null && (currentTime - cachedValue.cacheTime) < CACHE_VALIDITY_MS) {
            // 缓存有效，直接返回
            return cachedValue.nextTime
        }
        
        // 计算下次触发时间
        val calendar = Calendar.getInstance()
        val targetCalendar = Calendar.getInstance().apply {
            timeInMillis = config.targetTime
        }
        
        val result = when (config.scheduleType) {
            ScheduleType.ONCE -> {
                // 确保秒和毫秒为0，保证精确触发
                val targetCalendar = Calendar.getInstance().apply {
                    timeInMillis = config.targetTime
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val targetTime = targetCalendar.timeInMillis
                if (targetTime > System.currentTimeMillis()) targetTime else null
            }
            ScheduleType.DAILY -> {
                calendar.set(Calendar.HOUR_OF_DAY, targetCalendar.get(Calendar.HOUR_OF_DAY))
                calendar.set(Calendar.MINUTE, targetCalendar.get(Calendar.MINUTE))
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                if (calendar.timeInMillis <= System.currentTimeMillis()) {
                    calendar.add(Calendar.DAY_OF_YEAR, 1)
                }
                calendar.timeInMillis
            }
            ScheduleType.WEEKLY -> {
                val repeatDays = config.repeatDays ?: return null
                val currentDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1 // 转换为0=周日
                val targetHour = targetCalendar.get(Calendar.HOUR_OF_DAY)
                val targetMinute = targetCalendar.get(Calendar.MINUTE)
                
                // 找到下一个符合条件的日期
                for (i in 0..7) {
                    val checkDay = (currentDayOfWeek + i) % 7
                    if (repeatDays.contains(checkDay)) {
                        calendar.set(Calendar.HOUR_OF_DAY, targetHour)
                        calendar.set(Calendar.MINUTE, targetMinute)
                        calendar.set(Calendar.SECOND, 0)
                        calendar.set(Calendar.MILLISECOND, 0)
                        calendar.add(Calendar.DAY_OF_YEAR, if (i == 0 && calendar.timeInMillis > System.currentTimeMillis()) 0 else if (i == 0) 7 else i)
                        if (calendar.timeInMillis > System.currentTimeMillis()) {
                            return calendar.timeInMillis
                        }
                    }
                }
                null
            }
            ScheduleType.MONTHLY -> {
                calendar.set(Calendar.DAY_OF_MONTH, targetCalendar.get(Calendar.DAY_OF_MONTH))
                calendar.set(Calendar.HOUR_OF_DAY, targetCalendar.get(Calendar.HOUR_OF_DAY))
                calendar.set(Calendar.MINUTE, targetCalendar.get(Calendar.MINUTE))
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                if (calendar.timeInMillis <= System.currentTimeMillis()) {
                    calendar.add(Calendar.MONTH, 1)
                }
                calendar.timeInMillis
            }
        }
        
        // 保存到缓存
        calculationCache[cacheKey] = CacheValue(result, currentTime)
        
        // 清理过期缓存（避免内存泄漏）
        if (calculationCache.size > 100) {
            val expiredKeys = calculationCache.filter { 
                (currentTime - it.value.cacheTime) >= CACHE_VALIDITY_MS 
            }.keys
            expiredKeys.forEach { calculationCache.remove(it) }
        }
        
        return result
    }
    
    /**
     * 重新注册所有定时任务（应用启动时调用）
     * 在后台线程执行，避免阻塞主线程
     */
    suspend fun rescheduleAll(context: Context) = withContext(Dispatchers.IO) {
        try {
            val skills = SkillManager.loadSkills(context)
            var scheduledCount = 0
            skills.forEach { skill ->
                if (skill.scheduleConfig?.isEnabled == true) {
                    scheduleSkill(context, skill)
                    scheduledCount++
                }
            }
            Log.d(TAG, "已重新注册 $scheduledCount 个定时任务")
        } catch (e: Exception) {
            Log.e(TAG, "重新注册定时任务失败: ${e.message}", e)
        }
    }
    
    /**
     * 重新注册所有定时任务（同步版本，用于非协程环境）
     * 注意：此方法会在后台线程执行，不会阻塞调用线程
     */
    fun rescheduleAllSync(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            rescheduleAll(context)
        }
    }
}

