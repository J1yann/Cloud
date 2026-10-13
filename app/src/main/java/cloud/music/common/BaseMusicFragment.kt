package cloud.music.common

import com.kingja.loadsir.callback.Callback
import top.wangchenyan.common.ui.fragment.BaseFragment
import cloud.music.widget.loadsir.SoundWaveLoadingCallback

/**
 * Created by wangchenyan.top on 2023/9/6.
 */
abstract class BaseMusicFragment : BaseFragment() {

    override fun getLoadingCallback(): Callback {
        return SoundWaveLoadingCallback()
    }

    override fun showLoadSirLoading() {
        loadService?.showCallback(SoundWaveLoadingCallback::class.java)
    }
}