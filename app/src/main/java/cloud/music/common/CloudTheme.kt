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
    // Keep the activity's window/delegate theme stable; AMOLED changes its palette only.
    setTheme(if (popup) R.style.AppTheme_Popup else R.style.AppTheme)
    applyCloudPalette()
}

fun Activity.applyCloudPalette() {
    if (isAmoledTheme()) theme.applyStyle(R.style.CloudAmoledPalette, true)
}

private val pendingThemeRefresh = java.util.WeakHashMap<Activity, Unit>()

/** Refresh a stopped screen when it returns, instead of recreating the entire task at once. */
fun Activity.refreshCloudThemeIfNeeded() {
    if (pendingThemeRefresh.containsKey(this)) return
    val value = TypedValue()
    val applied = theme.resolveAttribute(R.attr.cloudAmoledActive, value, true) && value.data != 0
    if (applied == isAmoledTheme()) return
    pendingThemeRefresh[this] = Unit
    window.decorView.post {
        pendingThemeRefresh.remove(this)
        val current = TypedValue()
        val active = theme.resolveAttribute(R.attr.cloudAmoledActive, current, true) && current.data != 0
        if (!isFinishing && !isDestroyed && !isChangingConfigurations && active != isAmoledTheme()) recreate()
    }
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
        when {
            value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT -> value.data
            value.resourceId != 0 -> ContextCompat.getColor(this, value.resourceId)
            else -> ContextCompat.getColor(this, resource)
        }
    } else ContextCompat.getColor(this, resource)
}
