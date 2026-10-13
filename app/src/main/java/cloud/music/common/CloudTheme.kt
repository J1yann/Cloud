package cloud.music.common

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import cloud.music.R
import cloud.music.storage.preference.ConfigPreferences

fun isAmoledTheme(): Boolean = ConfigPreferences.darkMode == DarkModeService.DarkMode.Amoled.value

fun Activity.applyCloudTheme(popup: Boolean = false) {
    setTheme(when {
        isAmoledTheme() && popup -> R.style.AppTheme_Amoled_Popup
        isAmoledTheme() -> R.style.AppTheme_Amoled
        popup -> R.style.AppTheme_Popup
        else -> R.style.AppTheme
    })
}

/** XML and programmatic views resolve the same surface palette. */
fun Context.getCloudColor(@ColorRes resource: Int): Int {
    val attribute = when (resource) {
        R.color.common_background_color -> R.attr.cloudBackgroundColor
        R.color.card_bg -> R.attr.cloudSurfaceColor
        R.color.common_dialog_background_color -> R.attr.cloudDialogColor
        R.color.tab_bg -> R.attr.cloudNavColor
        R.color.play_bar_bg -> R.attr.cloudPlayerBarColor
        R.color.common_divider -> R.attr.cloudDividerColor
        R.color.common_text_h1_color -> R.attr.cloudTextPrimary
        R.color.common_text_h2_color -> R.attr.cloudTextSecondary
        R.color.cloud_accent -> R.attr.cloudAccentColor
        R.color.cloud_accent_soft -> R.attr.cloudAccentSoft
        R.color.cloud_hero_start -> R.attr.cloudHeroStart
        R.color.cloud_hero_end -> R.attr.cloudHeroEnd
        else -> return ContextCompat.getColor(this, resource)
    }
    val value = TypedValue()
    return if (theme.resolveAttribute(attribute, value, true)) {
        if (value.resourceId != 0) ContextCompat.getColor(this, value.resourceId) else value.data
    } else ContextCompat.getColor(this, resource)
}
