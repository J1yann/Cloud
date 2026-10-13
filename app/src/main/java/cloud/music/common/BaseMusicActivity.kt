package cloud.music.common

import android.os.Bundle
import com.kingja.loadsir.callback.Callback
import cloud.music.widget.loadsir.SoundWaveLoadingCallback
import top.wangchenyan.common.ui.activity.BaseActivity

/**
 * Created by wangchenyan.top on 2023/9/4.
 */
abstract class BaseMusicActivity : BaseActivity() {

    protected open val popupWindow = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applyCloudTheme(popupWindow)
        super.onCreate(savedInstanceState)
        applyCloudPalette()
    }

    override fun onResume() {
        super.onResume()
        refreshCloudThemeIfNeeded()
    }

    override fun getLoadingCallback(): Callback {
        return SoundWaveLoadingCallback()
    }

    override fun showLoadSirLoading() {
        loadService?.showCallback(SoundWaveLoadingCallback::class.java)
    }

    companion object {
        private const val TAG = "BaseMusicActivity"
    }
}