package cloud.music.common

import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import cloud.music.R

import top.wangchenyan.common.ui.activity.BaseActivity

/** The activity owns browser insets so child screens never add them twice. */
fun BaseActivity.configureBrowserInsets() {
    val background = getCloudColor(R.color.common_background_color)
    val navigationBackground = getCloudColor(R.color.tab_bg)
    configWindowInsets {
        fillStatusBar = true
        fillNavBar = true
        fillDisplayCutout = true
        fillIme = true
        statusBarColor = background
        navBarColor = navigationBackground
        displayCutoutColor = background
        statusBarTextDarkStyle = ColorUtils.calculateLuminance(background) > 0.5
        navBarButtonDarkStyle = ColorUtils.calculateLuminance(navigationBackground) > 0.5
    }
    ViewCompat.requestApplyInsets(findViewById(android.R.id.content))
}
