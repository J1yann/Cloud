package cloud.music.discover.playlist.square

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import cloud.music.R
import cloud.music.common.BaseMusicFragment
import cloud.music.consts.RoutePath
import cloud.music.databinding.FragmentPlaylistSpuareBinding
import cloud.music.discover.playlist.square.viewmodel.PlaylistSquareViewModel
import me.wcy.router.annotation.Route
import top.wangchenyan.common.ext.getColor
import top.wangchenyan.common.ext.viewBindings
import top.wangchenyan.common.widget.pager.TabLayoutPager

/**
 * Created by wangchenyan.top on 2023/9/26.
 */
@Route(RoutePath.PLAYLIST_SQUARE)
@AndroidEntryPoint
class PlaylistSquareFragment : BaseMusicFragment() {
    private val viewBinding by viewBindings<FragmentPlaylistSpuareBinding>()
    private val viewModel by viewModels<PlaylistSquareViewModel>()
    private var pager: TabLayoutPager? = null

    override fun getRootView(): View {
        return viewBinding.root
    }

    override fun isUseLoadSir(): Boolean {
        return true
    }

    override fun getLoadSirTarget(): View {
        return viewBinding.content
    }

    override fun onReload() {
        super.onReload()
        loadTagList()
    }

    override fun onLazyCreate() {
        super.onLazyCreate()

        configWindowInsets {
            navBarColor = getColor(R.color.play_bar_bg)
        }

        initTab()
        loadTagList()
    }

    private fun initTab() {
        lifecycleScope.launch {
            viewModel.tagList.collectLatest { tagList ->
                if (tagList.isNotEmpty() && pager == null) {
                    pager = TabLayoutPager(
                        lifecycle,
                        childFragmentManager,
                        viewBinding.viewPage2,
                        viewBinding.tabLayout
                    ).apply {
                        tagList.forEach { tag ->
                            addFragment(PlaylistTabFragment().apply {
                                arguments = Bundle().apply {
                                    putString("tag", tag)
                                }
                            }, tag)
                        }
                        setup()
                    }
                }
            }
        }
    }

    private fun loadTagList() {
        lifecycleScope.launch {
            showLoadSirLoading()
            val res = viewModel.loadTagList()
            if (res.isSuccess()) {
                showLoadSirSuccess()
            } else {
                showLoadSirError(res.msg)
            }
        }
    }
}