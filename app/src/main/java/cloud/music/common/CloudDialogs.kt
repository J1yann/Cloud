package cloud.music.common

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.widget.LinearLayout
import android.widget.RadioButton
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import cloud.music.R
import cloud.music.databinding.DialogCloudTimerBinding

internal fun AlertDialog.applyCloudStyle(context: Context) {
    window?.setBackgroundDrawable(ContextCompat.getDrawable(context, R.drawable.bg_cloud_dialog))
    val accent = ContextCompat.getColor(context, R.color.cloud_accent)
    getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(accent)
    getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(accent)
}

class CloudSleepTimerDialog(
    private val context: Context,
    private val currentMinutes: Int,
    private val onSet: (Int) -> Unit,
) {
    fun show() {
        val binding = DialogCloudTimerBinding.inflate(LayoutInflater.from(context))
        var selected = currentMinutes
        val choices = mutableListOf<Pair<Int, RadioButton>>()
        val dialog = AlertDialog.Builder(context).setView(binding.root)
            .setPositiveButton(R.string.cloud_timer_apply) { _, _ -> onSet(selected) }
            .setNegativeButton(android.R.string.cancel, null).create()
        fun updateSelection() {
            choices.forEach { (minutes, button) ->
                button.isChecked = selected == minutes
                button.isSelected = button.isChecked
                button.setTextColor(ContextCompat.getColor(context,
                    if (button.isChecked) R.color.cloud_accent else R.color.common_text_h1_color))
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setText(
                if (selected == 0) R.string.cloud_timer_off else R.string.cloud_timer_apply)
        }
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val values = context.resources.getIntArray(R.array.timer_int).toList()
        (listOf(listOf(0)) + values.filter { it > 0 }.chunked(2)).forEach { rowValues ->
            val row = LinearLayout(context)
            rowValues.forEachIndexed { index, minutes ->
                val button = RadioButton(context).apply {
                    text = if (minutes == 0) context.getString(R.string.cloud_timer_disabled)
                        else context.getString(R.string.cloud_timer_minutes, minutes)
                    textSize = 14f
                    buttonDrawable = null
                    gravity = Gravity.CENTER
                    minHeight = dp(52)
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                    setBackgroundResource(R.drawable.bg_cloud_choice)
                    setOnClickListener { selected = minutes; updateSelection() }
                }
                choices.add(minutes to button)
                row.addView(button, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (index > 0) marginStart = dp(10)
                })
            }
            binding.timerOptions.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            })
        }
        dialog.setOnShowListener { dialog.applyCloudStyle(context); updateSelection() }
        dialog.show()
    }
}
