package cloud.music.common

import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import top.wangchenyan.common.ui.activity.FragmentContainerActivity

/**
 * Created by wangchenyan.top on 2023/8/7.
 */
@AndroidEntryPoint
class MusicFragmentContainerActivity : FragmentContainerActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        applyCloudTheme()
        super.onCreate(savedInstanceState)
    }
}