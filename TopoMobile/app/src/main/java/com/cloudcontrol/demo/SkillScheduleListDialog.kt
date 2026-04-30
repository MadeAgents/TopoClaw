package com.cloudcontrol.demo

import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 定时任务列表对话框（与电脑端统一走 TopoClaw cron 任务池）
 */
object SkillScheduleListDialog {

    private data class CronJobItem(
        val id: String,
        val name: String,
        val message: String,
        val kind: String,
        val everySeconds: Int?,
        val cronExpr: String?,
        val atMs: Long?,
        val tz: String?,
        val nextRunAtMs: Long?,
        val enabled: Boolean
    )

    private data class CronJobForm(
        val name: String,
        val message: String,
        val everySeconds: Int? = null,
        val cronExpr: String? = null,
        val at: String? = null,
        val tz: String? = null
    )

    fun show(context: Context, ws: CustomerServiceWebSocket? = null) {
        val socket = ws ?: resolveWebSocket(context)
        val loadingDialog = AlertDialog.Builder(context)
            .setMessage("正在加载定时任务...")
            .setCancelable(true)
            .create()
        loadingDialog.show()
        if (socket == null || !socket.isConnected()) {
            loadingDialog.dismiss()
            AlertDialog.Builder(context)
                .setTitle("连接未就绪")
                .setMessage("暂时无法加载定时任务，请确认手机与中转服务已连接，且电脑端在线。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val response = withContext(Dispatchers.IO) { socket.listTopoclawCronJobs() }
                loadingDialog.dismiss()
                if (!response.optBoolean("ok", false)) {
                    val err = response.optString("error", "获取定时任务失败")
                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val jobs = parseJobs(response.optJSONArray("jobs"))
                showJobsDialog(context, socket, jobs)
            } catch (e: Exception) {
                loadingDialog.dismiss()
                Toast.makeText(context, "获取定时任务失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun resolveWebSocket(context: Context): CustomerServiceWebSocket? {
        var current: Context? = context
        while (current != null) {
            if (current is MainActivity) return current.getCustomerServiceWebSocket()
            current = if (current is ContextWrapper) current.baseContext else null
        }
        return null
    }

    private fun parseJobs(array: JSONArray?): List<CronJobItem> {
        if (array == null) return emptyList()
        val jobs = mutableListOf<CronJobItem>()
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val schedule = row.optJSONObject("schedule")
            val payload = row.optJSONObject("payload")
            val state = row.optJSONObject("state")
            val kind = schedule?.optString("kind", "") ?: ""
            val everyMs = schedule?.optLong("every_ms", 0L) ?: 0L
            val atMs = schedule?.optLong("at_ms", 0L) ?: 0L
            val nextRunAtMs = state?.optLong("next_run_at_ms", 0L) ?: 0L
            jobs.add(
                CronJobItem(
                    id = row.optString("id", ""),
                    name = row.optString("name", ""),
                    message = payload?.optString("message", "") ?: "",
                    kind = kind,
                    everySeconds = if (everyMs > 0) (everyMs / 1000L).toInt() else null,
                    cronExpr = schedule?.optString("expr", null),
                    atMs = if (atMs > 0) atMs else null,
                    tz = schedule?.optString("tz", null),
                    nextRunAtMs = if (nextRunAtMs > 0) nextRunAtMs else null,
                    enabled = row.optBoolean("enabled", true)
                )
            )
        }
        return jobs
    }

    private fun showJobsDialog(
        context: Context,
        ws: CustomerServiceWebSocket,
        jobs: List<CronJobItem>
    ) {
        val density = context.resources.displayMetrics.density
        fun dpToPx(dp: Int) = (dp * density).toInt()
        var dialog: AlertDialog? = null

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(20), dpToPx(24), dpToPx(20))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = dpToPx(20).toFloat()
            }
        }

        val titleView = TextView(context).apply {
            text = "⏰ 定时任务"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF333333.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dpToPx(12))
        }
        container.addView(titleView)

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val addButton = Button(context).apply {
            text = "新增"
            background = null
            setTextColor(0xFF2196F3.toInt())
            setOnClickListener {
                showCronEditor(context, null) { form ->
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            val created = withContext(Dispatchers.IO) {
                                ws.createTopoclawCronJob(
                                    name = form.name,
                                    message = form.message,
                                    everySeconds = form.everySeconds,
                                    cronExpr = form.cronExpr,
                                    at = form.at,
                                    tz = form.tz
                                )
                            }
                            if (created.optBoolean("ok", false)) {
                                Toast.makeText(context, "定时任务已新增", Toast.LENGTH_SHORT).show()
                                dialog?.dismiss()
                                show(context)
                            } else {
                                Toast.makeText(context, created.optString("error", "新增失败"), Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "新增失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        actionRow.addView(addButton)
        container.addView(actionRow)

        if (jobs.isEmpty()) {
            container.addView(TextView(context).apply {
                text = "暂无定时任务"
                textSize = 14f
                setTextColor(0xFF999999.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(30), 0, dpToPx(30))
            })
        } else {
            val listScroll = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(420)
                )
            }
            val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            jobs.forEach { job ->
                val card = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dpToPx(14), dpToPx(12), dpToPx(14), dpToPx(12))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFFF5F5F5.toInt())
                        cornerRadius = dpToPx(12).toFloat()
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dpToPx(10) }
                }

                val row = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(TextView(context).apply {
                    text = if (job.name.isBlank()) job.message else job.name
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(0xFF333333.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply { weight = 1f }
                })
                row.addView(Button(context).apply {
                    text = "编辑"
                    textSize = 12f
                    background = null
                    setTextColor(0xFF2196F3.toInt())
                    setOnClickListener {
                        showCronEditor(context, job) { form ->
                            CoroutineScope(Dispatchers.Main).launch {
                                try {
                                    val deleted = withContext(Dispatchers.IO) { ws.deleteTopoclawCronJob(job.id) }
                                    if (!deleted.optBoolean("ok", false)) {
                                        Toast.makeText(context, deleted.optString("error", "编辑失败"), Toast.LENGTH_SHORT).show()
                                        return@launch
                                    }
                                    val created = withContext(Dispatchers.IO) {
                                        ws.createTopoclawCronJob(
                                            name = form.name,
                                            message = form.message,
                                            everySeconds = form.everySeconds,
                                            cronExpr = form.cronExpr,
                                            at = form.at,
                                            tz = form.tz
                                        )
                                    }
                                    if (created.optBoolean("ok", false)) {
                                        Toast.makeText(context, "定时任务已更新", Toast.LENGTH_SHORT).show()
                                        dialog?.dismiss()
                                        show(context)
                                    } else {
                                        Toast.makeText(context, created.optString("error", "更新失败"), Toast.LENGTH_SHORT).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(context, "更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                })
                row.addView(Button(context).apply {
                    text = "删除"
                    textSize = 12f
                    background = null
                    setTextColor(0xFFE53935.toInt())
                    setOnClickListener {
                        AlertDialog.Builder(context)
                            .setTitle("删除定时任务")
                            .setMessage("确定删除该任务吗？")
                            .setPositiveButton("删除") { _, _ ->
                                CoroutineScope(Dispatchers.Main).launch {
                                    try {
                                        val resp = withContext(Dispatchers.IO) { ws.deleteTopoclawCronJob(job.id) }
                                        if (resp.optBoolean("ok", false)) {
                                            Toast.makeText(context, "定时任务已删除", Toast.LENGTH_SHORT).show()
                                            dialog?.dismiss()
                                            show(context)
                                        } else {
                                            Toast.makeText(context, resp.optString("error", "删除失败"), Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "删除失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                })
                card.addView(row)

                card.addView(TextView(context).apply {
                    text = "消息: ${job.message.ifBlank { "-" }}"
                    textSize = 13f
                    setTextColor(0xFF666666.toInt())
                    setPadding(0, dpToPx(4), 0, 0)
                })
                card.addView(TextView(context).apply {
                    text = "调度: ${formatSchedule(job)}"
                    textSize = 13f
                    setTextColor(0xFF666666.toInt())
                })
                card.addView(TextView(context).apply {
                    text = "下次执行: ${formatTs(job.nextRunAtMs)}"
                    textSize = 12f
                    setTextColor(0xFF999999.toInt())
                })
                if (!job.enabled) {
                    card.addView(TextView(context).apply {
                        text = "状态: 已禁用"
                        textSize = 12f
                        setTextColor(0xFFE53935.toInt())
                    })
                }
                list.addView(card)
            }
            listScroll.addView(list)
            container.addView(listScroll)
        }

        val closeButton = Button(context).apply {
            text = "关闭"
            background = null
            setTextColor(0xFF2196F3.toInt())
            setOnClickListener { dialog?.dismiss() }
        }
        container.addView(closeButton)

        dialog = AlertDialog.Builder(context).setView(container).setCancelable(true).create()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = dpToPx(20).toFloat()
        })
        dialog.show()
    }

    private fun showCronEditor(
        context: Context,
        initial: CronJobItem?,
        onSubmit: (CronJobForm) -> Unit
    ) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val p = (16 * context.resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p / 2)
        }

        val nameInput = EditText(context).apply {
            hint = "任务名称"
            setText(initial?.name ?: "")
        }
        val messageInput = EditText(context).apply {
            hint = "触发消息（必填）"
            setText(initial?.message ?: "")
        }
        val modeSpinner = Spinner(context)
        val modes = arrayOf("按秒间隔", "Cron 表达式", "指定时间(ISO)")
        modeSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, modes).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val everyInput = EditText(context).apply {
            hint = "every_seconds，例如 300"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(initial?.everySeconds?.toString() ?: "")
        }
        val cronInput = EditText(context).apply {
            hint = "cron_expr，例如 */5 * * * *"
            setText(initial?.cronExpr ?: "")
        }
        val atInput = EditText(context).apply {
            hint = "at，例如 2026-04-12T10:30:00"
            setText(initial?.atMs?.let { toIsoLocal(it) } ?: "")
        }
        val tzInput = EditText(context).apply {
            hint = "时区（仅 cron，可选），例如 Asia/Shanghai"
            setText(initial?.tz ?: "")
        }

        val initialMode = when {
            initial?.kind == "cron" -> 1
            initial?.kind == "at" -> 2
            else -> 0
        }
        modeSpinner.setSelection(initialMode)

        fun updateMode() {
            val mode = modeSpinner.selectedItemPosition
            everyInput.visibility = if (mode == 0) View.VISIBLE else View.GONE
            cronInput.visibility = if (mode == 1) View.VISIBLE else View.GONE
            tzInput.visibility = if (mode == 1) View.VISIBLE else View.GONE
            atInput.visibility = if (mode == 2) View.VISIBLE else View.GONE
        }
        modeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) = updateMode()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
        }
        updateMode()

        container.addView(nameInput)
        container.addView(messageInput)
        container.addView(modeSpinner)
        container.addView(everyInput)
        container.addView(cronInput)
        container.addView(tzInput)
        container.addView(atInput)

        AlertDialog.Builder(context)
            .setTitle(if (initial == null) "新增定时任务" else "编辑定时任务")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val name = nameInput.text?.toString()?.trim().orEmpty()
                val message = messageInput.text?.toString()?.trim().orEmpty()
                if (message.isBlank()) {
                    Toast.makeText(context, "触发消息不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val mode = modeSpinner.selectedItemPosition
                val form = when (mode) {
                    0 -> {
                        val sec = everyInput.text?.toString()?.trim()?.toIntOrNull()
                        if (sec == null || sec <= 0) {
                            Toast.makeText(context, "请输入有效 every_seconds", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        CronJobForm(name = name, message = message, everySeconds = sec)
                    }
                    1 -> {
                        val expr = cronInput.text?.toString()?.trim().orEmpty()
                        if (expr.isBlank()) {
                            Toast.makeText(context, "cron_expr 不能为空", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        CronJobForm(
                            name = name,
                            message = message,
                            cronExpr = expr,
                            tz = tzInput.text?.toString()?.trim().orEmpty().ifBlank { null }
                        )
                    }
                    else -> {
                        val at = atInput.text?.toString()?.trim().orEmpty()
                        if (at.isBlank()) {
                            Toast.makeText(context, "at 不能为空", Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        CronJobForm(name = name, message = message, at = at)
                    }
                }
                onSubmit(form)
            }
            .show()
    }

    private fun formatSchedule(job: CronJobItem): String {
        return when (job.kind) {
            "every" -> "每 ${job.everySeconds ?: 0} 秒"
            "cron" -> {
                val tz = job.tz?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                "CRON ${job.cronExpr ?: "-"}$tz"
            }
            "at" -> "一次性 ${formatTs(job.atMs)}"
            else -> "未知"
        }
    }

    private fun formatTs(ts: Long?): String {
        if (ts == null || ts <= 0) return "-"
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return format.format(Date(ts))
    }

    private fun toIsoLocal(ts: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        return format.format(Date(ts))
    }
}

