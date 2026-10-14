package cloud.music.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.C
import cloud.music.utils.ImageUtils.loadCover
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import cloud.music.R
import cloud.music.consts.RoutePath
import cloud.music.databinding.LayoutPlayBarBinding
import cloud.music.main.playlist.CurrentPlaylistFragment
import cloud.music.service.PlayServiceModule
import cloud.music.service.PlayServiceModule.playerController
import cloud.music.service.PlayState
import cloud.music.utils.getDuration
import cloud.music.utils.getSmallCover
import me.wcy.router.CRouter
import top.wangchenyan.common.CommonApp
import top.wangchenyan.common.ext.findActivity
import top.wangchenyan.common.ext.findLifecycleOwner

/**
 * Created by wangchenyan.top on 2023/9/4.
 */
class PlayBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    private val viewBinding: LayoutPlayBarBinding
    private val playerController by lazy {
        CommonApp.app.playerController()
    }
    private var playerDataBound = false

    init {
        id = R.id.play_bar
        viewBinding = LayoutPlayBarBinding.inflate(LayoutInflater.from(context), this, true)

        isVisible = false
        initView()
        context.findLifecycleOwner()?.let { owner ->
            PlayServiceModule.isPlayerReady.observe(owner) { ready ->
                if (ready && !playerDataBound) {
                    playerDataBound = true
                    initData(owner)
                }
            }
        }
    }

    private fun initView() {
        viewBinding.root.setOnClickListener {
            if (!isPlayerReady()) return@setOnClickListener
            CRouter.with(context).url(RoutePath.PLAYING).start()
        }
        viewBinding.flPlay.setOnClickListener {
            if (!isPlayerReady()) return@setOnClickListener
            playerController.playPause()
        }
        viewBinding.ivNext.setOnClickListener {
            if (!isPlayerReady()) return@setOnClickListener
            playerController.next()
        }
        viewBinding.ivPlaylist.setOnClickListener {
            if (!isPlayerReady()) return@setOnClickListener
            val activity = context.findActivity()
            if (activity is FragmentActivity) {
                CurrentPlaylistFragment.newInstance()
                    .show(activity.supportFragmentManager, CurrentPlaylistFragment.TAG)
            }
        }
    }

    private fun isPlayerReady(): Boolean = PlayServiceModule.isPlayerReady.value == true

    private fun initData(lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.lifecycleScope.launch {
            playerController.currentSong.collectLatest { currentSong ->
                if (currentSong != null) {
                    isVisible = true
                    viewBinding.ivCover.loadCover(currentSong.getSmallCover(), (9 * resources.displayMetrics.density).toInt())
                    viewBinding.tvTitle.text = currentSong.mediaMetadata.title
                    viewBinding.tvArtist.text = currentSong.mediaMetadata.artist
                    viewBinding.root.contentDescription = context.getString(
                        R.string.mini_player_track,
                        currentSong.mediaMetadata.title?.toString().orEmpty(),
                        currentSong.mediaMetadata.artist?.toString().orEmpty(),
                    )
                    updateProgress(playerController.playProgress.value)
                } else {
                    isVisible = false
                }
            }
        }

        lifecycleOwner.lifecycleScope.launch {
            playerController.playState.collectLatest { playState ->
                when (playState) {
                    PlayState.Preparing -> {
                        viewBinding.flPlay.isEnabled = false
                        viewBinding.ivPlay.isSelected = false
                        viewBinding.loadingProgress.isVisible = true
                    }

                    PlayState.Playing -> {
                        viewBinding.flPlay.isEnabled = true
                        viewBinding.ivPlay.isSelected = true
                        viewBinding.loadingProgress.isVisible = false
                    }

                    else -> {
                        viewBinding.flPlay.isEnabled = true
                        viewBinding.ivPlay.isSelected = false
                        viewBinding.loadingProgress.isVisible = false
                    }
                }

                viewBinding.ivPlay.isVisible = playState != PlayState.Preparing
                viewBinding.flPlay.contentDescription = context.getString(
                    when (playState) {
                        PlayState.Preparing -> R.string.mini_player_buffering
                        PlayState.Playing -> R.string.player_pause
                        else -> R.string.player_play
                    }
                )
                updateProgress(playerController.playProgress.value)
            }
        }

        lifecycleOwner.lifecycleScope.launch {
            playerController.playProgress.collectLatest {
                updateProgress(it)
            }
        }
    }
    private fun updateProgress(positionMs: Long) {
        val song = playerController.currentSong.value ?: return
        val metadataDuration = song.mediaMetadata.getDuration()
        val duration = if (metadataDuration > 0 && metadataDuration != C.TIME_UNSET) metadataDuration else {
            val controller = playerController.mediaController
            if (controller.currentMediaItem?.mediaId == song.mediaId) {
                controller.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
            } else 0L
        }
        val max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        viewBinding.progressBar.max = max.coerceAtLeast(1)
        viewBinding.progressBar.progress = if (max > 0) positionMs.coerceIn(0L, max.toLong()).toInt() else 0
    }

}