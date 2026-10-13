package cloud.music.widget

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import androidx.core.text.buildSpannedString
import androidx.core.view.isVisible
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.LifecycleOwner
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
import top.wangchenyan.common.ext.getColor
import top.wangchenyan.common.ext.loadAvatar
import top.wangchenyan.common.widget.CustomSpan.appendStyle

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
    private val rotateAnimator: ObjectAnimator

    init {
        id = R.id.play_bar
        viewBinding = LayoutPlayBarBinding.inflate(LayoutInflater.from(context), this, true)

        rotateAnimator = ObjectAnimator.ofFloat(viewBinding.ivCover, "rotation", 0f, 360f).apply {
            duration = 20000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ObjectAnimator.RESTART
            interpolator = LinearInterpolator()
        }

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
                    viewBinding.ivCover.loadAvatar(currentSong.getSmallCover())
                    viewBinding.tvTitle.text = buildSpannedString {
                        append(currentSong.mediaMetadata.title)
                        appendStyle(
                            " - ${currentSong.mediaMetadata.artist}",
                            color = getColor(R.color.common_text_h2_color)
                        )
                    }
                    viewBinding.progressBar.max = currentSong.mediaMetadata.getDuration().toInt()
                    viewBinding.progressBar.progress = playerController.playProgress.value.toInt()
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

                if (playState.isPlaying) {
                    if (rotateAnimator.isPaused) {
                        rotateAnimator.resume()
                    } else if (rotateAnimator.isStarted.not()) {
                        rotateAnimator.start()
                    }
                } else {
                    if (rotateAnimator.isRunning) {
                        rotateAnimator.pause()
                    }
                }
            }
        }

        lifecycleOwner.lifecycleScope.launch {
            playerController.playProgress.collectLatest {
                viewBinding.progressBar.progress = it.toInt()
            }
        }
    }
}