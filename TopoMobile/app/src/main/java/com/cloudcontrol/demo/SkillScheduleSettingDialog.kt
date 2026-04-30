package com.cloudcontrol.demo

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 技能定时设置对话框
 */
object SkillScheduleSettingDialog {
    
    fun show(context: Context, skill: Skill, onSave: (SkillScheduleConfig?) -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dpToPx(dp: Int) = (dp * density).toInt()
        
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(20), dpToPx(24), dpToPx(20))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = dpToPx(20).toFloat()
            }
        }
        
        // 标题（居中）
        val titleView = TextView(context).apply {
            text = "⏰ 定时设置"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF333333.toInt())
            setPadding(0, 0, 0, dpToPx(20))
            gravity = android.view.Gravity.CENTER
        }
        container.addView(titleView)
        
        // 定时类型选择
        container.addView(TextView(context).apply {
            text = "重复类型"
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, dpToPx(8))
        })
        // 使用自定义布局包装Spinner，添加下拉箭头
        val spinnerContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFF5F5F5.toInt())
                cornerRadius = dpToPx(12).toFloat()
            }
            setPadding(dpToPx(12), dpToPx(12), dpToPx(12), dpToPx(12))
        }
        
        val typeSpinner = Spinner(context).apply {
            adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_item,
                arrayOf("单次", "每天", "每周", "每月")
            ).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }
            val currentType = skill.scheduleConfig?.scheduleType ?: ScheduleType.ONCE
            setSelection(when (currentType) {
                ScheduleType.ONCE -> 0
                ScheduleType.DAILY -> 1
                ScheduleType.WEEKLY -> 2
                ScheduleType.MONTHLY -> 3
            })
            background = null
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
        }
        
        // 添加下拉箭头图标
        val arrowIcon = TextView(context).apply {
            text = "▼"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(24),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        spinnerContainer.addView(typeSpinner)
        spinnerContainer.addView(arrowIcon)
        container.addView(spinnerContainer)
        
        // 间距
        container.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(16)
            )
        })
        
        // 时间选择按钮
        container.addView(TextView(context).apply {
            text = "执行时间"
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, dpToPx(8))
        })
        val timeButton = Button(context).apply {
            val currentTime = skill.scheduleConfig?.targetTime ?: System.currentTimeMillis()
            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
            text = "🕐 ${timeFormat.format(Date(currentTime))}"
            textSize = 16f
            setTextColor(0xFF2196F3.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFE3F2FD.toInt())
                cornerRadius = dpToPx(12).toFloat()
            }
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
        }
        container.addView(timeButton)
        
        // 日期选择按钮（仅单次模式显示）
        val dateButton = Button(context).apply {
            val currentTime = skill.scheduleConfig?.targetTime ?: System.currentTimeMillis()
            val dateFormat = SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault())
            text = "📅 ${dateFormat.format(Date(currentTime))}"
            textSize = 16f
            setTextColor(0xFF2196F3.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFFE3F2FD.toInt())
                cornerRadius = dpToPx(12).toFloat()
            }
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
            visibility = if (typeSpinner.selectedItemPosition == 0) View.VISIBLE else View.GONE
        }
        container.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(8)
            )
            visibility = if (typeSpinner.selectedItemPosition == 0) View.VISIBLE else View.GONE
        })
        container.addView(dateButton)
        
        // 星期选择（仅每周模式显示，使用横向布局）
        val weekLabel = TextView(context).apply {
            text = "重复星期"
            textSize = 14f
            setTextColor(0xFF666666.toInt())
            setPadding(0, dpToPx(8), 0, dpToPx(8))
            visibility = if (typeSpinner.selectedItemPosition == 2) View.VISIBLE else View.GONE
        }
        container.addView(weekLabel)
        
        val weekContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            visibility = if (typeSpinner.selectedItemPosition == 2) View.VISIBLE else View.GONE
            setPadding(0, 0, 0, 0)
        }
        val weekCheckBoxes = mutableListOf<CheckBox>()
        val weekDays = arrayOf("日", "一", "二", "三", "四", "五", "六")
        val currentRepeatDays = skill.scheduleConfig?.repeatDays ?: listOf(Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1)
        weekDays.forEachIndexed { index, day ->
            val checkBox = CheckBox(context).apply {
                text = day
                isChecked = currentRepeatDays.contains(index)
                textSize = 14f
                setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8))
            }
            weekCheckBoxes.add(checkBox)
            val layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                weight = 1f
            }
            weekContainer.addView(checkBox, layoutParams)
        }
        container.addView(weekContainer)
        
        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                dateButton.visibility = if (position == 0) View.VISIBLE else View.GONE
                weekContainer.visibility = if (position == 2) View.VISIBLE else View.GONE
                weekLabel.visibility = if (position == 2) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        var selectedTime = skill.scheduleConfig?.targetTime ?: System.currentTimeMillis()
        // 确保初始时间的秒和毫秒为0
        var selectedDate = Calendar.getInstance().apply { 
            timeInMillis = selectedTime
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            selectedTime = timeInMillis
        }
        
        // 时间选择 - 使用自定义滑动选择器
        timeButton.setOnClickListener {
            showCustomTimePicker(context, selectedTime) { hour, minute ->
                selectedDate.set(Calendar.HOUR_OF_DAY, hour)
                selectedDate.set(Calendar.MINUTE, minute)
                selectedDate.set(Calendar.SECOND, 0)  // 确保秒为0
                selectedDate.set(Calendar.MILLISECOND, 0)  // 确保毫秒为0
                selectedTime = selectedDate.timeInMillis
                val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                timeButton.text = "🕐 ${timeFormat.format(Date(selectedTime))}"
            }
        }
        
        // 日期选择
        dateButton.setOnClickListener {
            val calendar = Calendar.getInstance().apply { timeInMillis = selectedTime }
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    selectedDate.set(year, month, day)
                    selectedDate.set(Calendar.SECOND, 0)  // 确保秒为0
                    selectedDate.set(Calendar.MILLISECOND, 0)  // 确保毫秒为0
                    selectedTime = selectedDate.timeInMillis
                    val dateFormat = SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault())
                    dateButton.text = "📅 ${dateFormat.format(Date(selectedTime))}"
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
        
        // 创建自定义按钮容器
        val buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(dpToPx(24), dpToPx(16), dpToPx(24), dpToPx(16))
        }
        
        // 先创建dialog
        val dialog = AlertDialog.Builder(context)
            .setView(container)
            .setCancelable(true)
            .create()
        
        // 取消按钮
        val cancelButton = Button(context).apply {
            text = "取消"
            textSize = 16f
            setTextColor(0xFF666666.toInt())
            background = null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener {
                dialog.dismiss()
            }
        }
        
        // 保存按钮
        val saveButton = Button(context).apply {
            text = "保存"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF2196F3.toInt())
                cornerRadius = dpToPx(12).toFloat()
            }
            setPadding(dpToPx(24), dpToPx(12), dpToPx(24), dpToPx(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dpToPx(12)
            }
            setOnClickListener {
                // 如果用户点击了保存按钮，说明想要保存定时设置，自动启用定时
                val isEnabled = true
                Log.d("SkillScheduleSettingDialog", "保存定时设置: isEnabled=$isEnabled")
                
                val scheduleType = when (typeSpinner.selectedItemPosition) {
                    0 -> ScheduleType.ONCE
                    1 -> ScheduleType.DAILY
                    2 -> ScheduleType.WEEKLY
                    3 -> ScheduleType.MONTHLY
                    else -> ScheduleType.ONCE
                }
                val repeatDays = if (scheduleType == ScheduleType.WEEKLY) {
                    weekCheckBoxes.mapIndexedNotNull { index, cb -> if (cb.isChecked) index else null }
                } else null
                
                // 确保保存时秒和毫秒为0
                val finalCalendar = Calendar.getInstance().apply {
                    timeInMillis = selectedTime
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                
                val config = SkillScheduleConfig(
                    isEnabled = isEnabled,
                    scheduleType = scheduleType,
                    targetTime = finalCalendar.timeInMillis,
                    repeatDays = repeatDays
                )
                
                Log.d("SkillScheduleSettingDialog", "保存配置: isEnabled=${config.isEnabled}, scheduleType=${config.scheduleType}, targetTime=${finalCalendar.timeInMillis}")
                onSave(config)
                dialog.dismiss()
            }
        }
        
        buttonContainer.addView(cancelButton)
        buttonContainer.addView(saveButton)
        container.addView(buttonContainer)
        
        // 设置圆角背景
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = dpToPx(20).toFloat()
        })
        
        dialog.show()
    }
    
    /**
     * 显示自定义时间选择器（使用NumberPicker实现上下滑动）
     */
    private fun showCustomTimePicker(
        context: Context,
        currentTime: Long,
        onTimeSelected: (hour: Int, minute: Int) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        fun dpToPx(dp: Int) = (dp * density).toInt()
        
        val calendar = Calendar.getInstance().apply { timeInMillis = currentTime }
        var selectedHour = calendar.get(Calendar.HOUR_OF_DAY)
        var selectedMinute = calendar.get(Calendar.MINUTE)
        
        // 创建容器
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24), dpToPx(20), dpToPx(24), dpToPx(20))
            gravity = android.view.Gravity.CENTER
        }
        
        // 标题
        val titleView = TextView(context).apply {
            text = "选择时间"
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF333333.toInt())
            setPadding(0, 0, 0, dpToPx(20))
            gravity = android.view.Gravity.CENTER
        }
        container.addView(titleView)
        
        // 时间选择器容器（横向布局：小时 : 分钟）
        val pickerContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
        }
        
        // 小时选择器
        val hourPicker = NumberPicker(context).apply {
            minValue = 0
            maxValue = 23
            value = selectedHour
            wrapSelectorWheel = true
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(100),
                dpToPx(200)
            )
            setOnValueChangedListener { _, _, newVal ->
                selectedHour = newVal
            }
        }
        
        // 格式化小时显示（00, 01, 02...）
        val hourDisplayValues = Array(24) { String.format("%02d", it) }
        hourPicker.displayedValues = hourDisplayValues
        
        // 分隔符
        val separator = TextView(context).apply {
            text = ":"
            textSize = 32f
            setTextColor(0xFF333333.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(dpToPx(16), 0, dpToPx(16), 0)
        }
        
        // 分钟选择器
        val minutePicker = NumberPicker(context).apply {
            minValue = 0
            maxValue = 59
            value = selectedMinute
            wrapSelectorWheel = true
            layoutParams = LinearLayout.LayoutParams(
                dpToPx(100),
                dpToPx(200)
            )
            setOnValueChangedListener { _, _, newVal ->
                selectedMinute = newVal
            }
        }
        
        // 格式化分钟显示（00, 01, 02...）
        val minuteDisplayValues = Array(60) { String.format("%02d", it) }
        minutePicker.displayedValues = minuteDisplayValues
        
        pickerContainer.addView(hourPicker)
        pickerContainer.addView(separator)
        pickerContainer.addView(minutePicker)
        container.addView(pickerContainer)
        
        // 按钮容器
        val buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(dpToPx(24), dpToPx(20), dpToPx(24), 0)
        }
        
        // 取消按钮
        val cancelButton = Button(context).apply {
            text = "取消"
            textSize = 16f
            setTextColor(0xFF666666.toInt())
            background = null
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        // 创建对话框
        val dialog = AlertDialog.Builder(context)
            .setView(container)
            .setCancelable(true)
            .create()
        
        // 确定按钮
        val confirmButton = Button(context).apply {
            text = "确定"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF2196F3.toInt())
                cornerRadius = dpToPx(12).toFloat()
            }
            setPadding(dpToPx(24), dpToPx(12), dpToPx(24), dpToPx(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dpToPx(12)
            }
            setOnClickListener {
                onTimeSelected(selectedHour, selectedMinute)
                dialog.dismiss()
            }
        }
        
        cancelButton.setOnClickListener {
            dialog.dismiss()
        }
        
        buttonContainer.addView(cancelButton)
        buttonContainer.addView(confirmButton)
        container.addView(buttonContainer)
        
        // 设置圆角背景
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFFFFFFFF.toInt())
            cornerRadius = dpToPx(20).toFloat()
        })
        
        dialog.show()
    }
}

