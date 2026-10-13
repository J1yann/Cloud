package cloud.music.common

import android.content.res.Configuration
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import com.blankj.utilcode.util.ActivityUtils
import cloud.music.storage.preference.ConfigPreferences
import cloud.music.R
import top.wangchenyan.common.CommonApp
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DarkModeService @Inject constructor() {

    fun init() {
        setDarkModeInternal(DarkMode.fromValue(ConfigPreferences.darkMode))
    }

    fun setDarkMode(mode: DarkMode) {
        if (mode.value != ConfigPreferences.darkMode) {
            val previous = DarkMode.fromValue(ConfigPreferences.darkMode)
            val top = ActivityUtils.getTopActivity()
            val currentNight = top?.resources?.configuration?.uiMode?.and(Configuration.UI_MODE_NIGHT_MASK)
            val targetNight = when (mode) {
                DarkMode.Light -> Configuration.UI_MODE_NIGHT_NO
                DarkMode.Dark, DarkMode.Amoled -> Configuration.UI_MODE_NIGHT_YES
                DarkMode.Auto -> Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
            }
            ConfigPreferences.darkMode = mode.value
            setDarkModeInternal(mode)
            // Dark and AMOLED share night resources; switch the overlay even without a uiMode change.
            if ((previous == DarkMode.Amoled || mode == DarkMode.Amoled) && currentNight == targetNight) {
                ActivityUtils.getActivityList().toList().forEach { activity ->
                    if (!activity.isFinishing && !activity.isDestroyed) activity.recreate()
                }
            }
        }
    }

    private fun setDarkModeInternal(mode: DarkMode) {
        AppCompatDelegate.setDefaultNightMode(mode.systemValue)
        CommonApp.app.setTheme(if (mode == DarkMode.Amoled) R.style.AppTheme_Amoled else R.style.AppTheme)
    }

    fun isDarkMode(): Boolean {
        val context = ActivityUtils.getTopActivity() ?: return false
        val nightModeFlags =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        when (nightModeFlags) {
            Configuration.UI_MODE_NIGHT_NO -> {
                return false
            }

            Configuration.UI_MODE_NIGHT_YES -> {
                return true
            }
        }
        return false
    }

    sealed class DarkMode(val value: String, val systemValue: Int) {
        object Auto : DarkMode("0", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        object Light : DarkMode("1", AppCompatDelegate.MODE_NIGHT_NO)
        object Dark : DarkMode("2", AppCompatDelegate.MODE_NIGHT_YES)
        object Amoled : DarkMode("3", AppCompatDelegate.MODE_NIGHT_YES)

        override fun hashCode(): Int {
            return value.hashCode()
        }

        override fun equals(other: Any?): Boolean {
            return other is DarkMode && other.value == this.value
        }

        companion object {
            fun fromValue(value: String): DarkMode {
                return when (value) {
                    "1" -> Light
                    "2" -> Dark
                    "3" -> Amoled
                    else -> Auto
                }
            }
        }
    }
}