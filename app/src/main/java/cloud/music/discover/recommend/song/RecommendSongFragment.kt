package cloud.music.discover.recommend.song

import android.view.View
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import cloud.music.R
import cloud.music.common.BaseMusicFragment
import cloud.music.common.OnItemClickListener2
import cloud.music.common.bean.SongData
import cloud.music.common.dialog.songmenu.SongMoreMenuDialog
import cloud.music.common.dialog.songmenu.items.AlbumMenuItem
import cloud.music.common.dialog.songmenu.items.ArtistMenuItem
import cloud.music.common.dialog.songmenu.items.CollectMenuItem
import cloud.music.common.dialog.songmenu.items.CommentMenuItem
import cloud.music.consts.RoutePath
import cloud.music.databinding.FragmentRecommendSongBinding
import cloud.music.discover.DiscoverApi
import cloud.music.discover.recommend.song.item.RecommendSongItemBinder
import cloud.music.service.PlayerController
import cloud.music.utils.toMediaItem
import me.wcy.radapter3.RAdapter
import me.wcy.router.CRouter
import me.wcy.router.annotation.Route
import top.wangchenyan.common.ext.getColor
import top.wangchenyan.common.ext.viewBindings
import top.wangchenyan.common.net.apiCall
import javax.inject.Inject

/**
 * Created by wangchenyan.top on 2023/9/15.
 */
@Route(RoutePath.RECOMMEND_SONG, needLogin = true)
@AndroidEntryPoint
class RecommendSongFragment : BaseMusicFragment() {
    private val viewBinding by viewBindings<FragmentRecommendSongBinding>()
    private val adapter by lazy {
        RAdapter<SongData>()
    }

    @Inject
    lateinit var playerController: PlayerController

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
        loadData()
    }

    override fun onLazyCreate() {
        super.onLazyCreate()

        configWindowInsets {
            navBarColor = getColor(R.color.play_bar_bg)
        }

        adapter.register(RecommendSongItemBinder(object : OnItemClickListener2<SongData> {
            override fun onItemClick(item: SongData, position: Int) {
                val entityList = adapter.getDataList().map { it.toMediaItem() }
                playerController.replaceAll(entityList, entityList[position])
                CRouter.with(requireContext()).url(RoutePath.PLAYING).start()
            }

            override fun onMoreClick(item: SongData, position: Int) {
                SongMoreMenuDialog(requireActivity(), item)
                    .setItems(
                        listOf(
                            CollectMenuItem(lifecycleScope, item),
                            CommentMenuItem(item),
                            ArtistMenuItem(item),
                            AlbumMenuItem(item)
                        )
                    )
                    .show()
            }
        }))
        viewBinding.recyclerView.adapter = adapter
        viewBinding.tvPlayAll.setOnClickListener {
            val entityList = adapter.getDataList().map { it.toMediaItem() }
            playerController.replaceAll(entityList, entityList.first())
            CRouter.with(requireContext()).url(RoutePath.PLAYING).start()
        }

        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            showLoadSirLoading()
            val res = apiCall { DiscoverApi.get().getRecommendSongs() }
            if (res.isSuccessWithData()) {
                showLoadSirSuccess()
                adapter.refresh(res.getDataOrThrow().dailySongs)
            } else {
                showLoadSirError(res.msg)
            }
        }
    }
}