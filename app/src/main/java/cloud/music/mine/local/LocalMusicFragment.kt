package cloud.music.mine.local

import android.view.View
import androidx.lifecycle.lifecycleScope
import com.blankj.utilcode.util.ConvertUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import cloud.music.R
import cloud.music.common.BaseMusicFragment
import cloud.music.common.OnItemClickListener2
import cloud.music.common.dialog.songmenu.SimpleMenuItem
import cloud.music.common.dialog.songmenu.SongMoreMenuDialog
import cloud.music.consts.RoutePath
import cloud.music.databinding.FragmentLocalMusicBinding
import cloud.music.service.PlayerController
import cloud.music.storage.db.entity.SongEntity
import cloud.music.utils.TimeUtils
import cloud.music.utils.toMediaItem
import me.wcy.radapter3.RAdapter
import me.wcy.router.CRouter
import me.wcy.router.annotation.Route
import top.wangchenyan.common.ext.getColor
import top.wangchenyan.common.ext.viewBindings
import top.wangchenyan.common.permission.Permissioner
import javax.inject.Inject

/**
 * Created by wangchenyan.top on 2023/8/30.
 */
@Route(RoutePath.LOCAL_SONG)
@AndroidEntryPoint
class LocalMusicFragment : BaseMusicFragment() {
    private val viewBinding by viewBindings<FragmentLocalMusicBinding>()
    private val localMusicLoader by lazy {
        LocalMusicLoader()
    }
    private val adapter by lazy {
        RAdapter<SongEntity>()
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

        adapter.register(LocalSongItemBinder(object : OnItemClickListener2<SongEntity> {
            override fun onItemClick(item: SongEntity, position: Int) {
                val mediaList = adapter.getDataList().map { it.toMediaItem() }
                playerController.replaceAll(mediaList, mediaList[position])
                CRouter.with(requireContext()).url(RoutePath.PLAYING).start()
            }

            override fun onMoreClick(item: SongEntity, position: Int) {
                SongMoreMenuDialog(requireActivity(), item)
                    .setItems(
                        listOf(
                            SimpleMenuItem("File name: ${item.fileName}"),
                            SimpleMenuItem("Duration: ${TimeUtils.formatMs(item.duration)}"),
                            SimpleMenuItem(
                                "File size: ${ConvertUtils.byte2FitMemorySize(item.fileSize)}"
                            ),
                            SimpleMenuItem("File path: ${item.path}")
                        )
                    )
                    .show()
            }
        }))
        viewBinding.recyclerView.adapter = adapter

        viewBinding.tvPlayAll.setOnClickListener {
            val mediaList = adapter.getDataList().map { it.toMediaItem() }
            if (mediaList.isEmpty()) return@setOnClickListener
            if (mediaList.isEmpty()) return@setOnClickListener
            playerController.replaceAll(mediaList, mediaList.first())
            CRouter.with(requireContext()).url(RoutePath.PLAYING).start()
        }

        loadData()
    }

    private fun loadData() {
        showLoadSirLoading()
        Permissioner.requestStoragePermission(requireContext()) { granted, shouldRationale ->
            if (granted) {
                lifecycleScope.launch {
                    val songList = withContext(Dispatchers.Default) {
                        localMusicLoader.load(requireContext())
                    }
                    if (songList.isNotEmpty()) {
                        showLoadSirSuccess()
                        viewBinding.tvPlayAll.text = "Play all (${songList.size})"
                        adapter.refresh(songList)
                    } else {
                        showLoadSirEmpty(getString(R.string.no_local_music))
                    }
                }
            } else {
                showLoadSirError(getString(R.string.no_permission_storage))
            }
        }
    }
}