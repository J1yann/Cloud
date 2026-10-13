package cloud.music.main.playing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.MediaItem
import dagger.hilt.android.AndroidEntryPoint
import jp.wasabeef.blurry.Blurry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.wcy.lrcview.LrcView
import cloud.music.R
import cloud.music.common.BaseMusicActivity
import cloud.music.consts.RoutePath
import cloud.music.databinding.ActivityPlayingBinding
import cloud.music.ext.registerReceiverCompat
import cloud.music.main.playlist.CurrentPlaylistFragment
import cloud.music.service.PlayMode
import cloud.music.service.PlayState
import cloud.music.service.PlayerController
import cloud.music.catalog.CloudLibrary
import cloud.music.lyrics.CloudLyrics
import kotlinx.coroutines.CancellationException
import cloud.music.storage.LrcCache
import cloud.music.utils.TimeUtils
import cloud.music.utils.getDuration
import cloud.music.utils.getLargeCover
import cloud.music.utils.isLocal
import me.wcy.router.annotation.Route
import top.wangchenyan.common.ext.toast
import top.wangchenyan.common.ext.viewBindings
import top.wangchenyan.common.utils.image.ImageUtils
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

/**
 * Created by wangchenyan.top on 2023/9/4.
 */
@Route(RoutePath.PLAYING)
@AndroidEntryPoint
class PlayingActivity : BaseMusicActivity() {
    private val viewBinding by viewBindings<ActivityPlayingBinding>()

    @Inject
    lateinit var playerController: PlayerController


    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val defaultCoverBitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.bg_playing_default_cover)
    }
    private val defaultBgBitmap by lazy {
        BitmapFactory.decodeResource(
            resources,
            R.drawable.bg_playing_default,
            BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    outConfig = Bitmap.Config.RGB_565
                }
            }
        )
    }

    private var loadLrcJob: Job? = null
    private var lyricsDurationMs = 0L
    private val durationListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)
                || events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)
                || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                updateDuration()
            }
        }
    }

    private var lastProgress = 0
    private var isDraggingProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        initWindowInsets()
        initTitle()
        initVolume()
        initCover()
        initLrc()
        initActions()
        initPlayControl()
        initData()
        playerController.mediaController.addListener(durationListener)
        switchCoverLrc(true)
    }

    private fun initWindowInsets() {
        configWindowInsets {
            fillStatusBar = false
            fillNavBar = false
            fillDisplayCutout = false
            statusBarTextDarkStyle = false
            navBarButtonDarkStyle = false
        }

        val updateInsets = { insets: WindowInsetsCompat ->
            val result = insets.getInsets(
                WindowInsetsCompat.Type.statusBars()
                        or WindowInsetsCompat.Type.navigationBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            viewBinding.llContent.updatePadding(
                left = result.left,
                top = result.top,
                right = result.right,
                bottom = result.bottom,
            )
        }
        val insets = ViewCompat.getRootWindowInsets(viewBinding.llContent)
        if (insets != null) {
            updateInsets(insets)
        }
        ViewCompat.setOnApplyWindowInsetsListener(viewBinding.llContent) { v, insets ->
            updateInsets(insets)
            insets
        }
    }

    private fun initTitle() {
        viewBinding.titleLayout.ivClose.setOnClickListener {
            onBackPressed()
        }
    }

    private fun initVolume() {
        viewBinding.volumeLayout.sbVolume.max =
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        viewBinding.volumeLayout.sbVolume.progress =
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val filter = IntentFilter(VOLUME_CHANGED_ACTION)
        registerReceiverCompat(volumeReceiver, filter)
    }

    private fun initCover() {
        viewBinding.albumCoverView.setOnClickListener {
            switchCoverLrc(false)
        }
        setDefaultCover()
    }

    private fun initLrc() {
        viewBinding.lrcView.setDraggable(true) { view, time ->
            val playState = playerController.playState.value
            if (playState.isPlaying || playState.isPausing) {
                playerController.seekTo(time.toInt())
                if (playState.isPausing) {
                    playerController.playPause()
                }
                return@setDraggable true
            }
            return@setDraggable false
        }
        viewBinding.lrcView.setOnTapListener { view: LrcView?, x: Float, y: Float ->
            switchCoverLrc(true)
        }
    }

    private fun initActions() {
        viewBinding.controlLayout.ivLike.setOnClickListener {
            val song = playerController.currentSong.value ?: return@setOnClickListener
            CloudLibrary.toggleLike(song.mediaId.substringAfter('#'))
            updateOnlineActionsState(song)
        }
        viewBinding.titleLayout.lyricsToggle.setOnClickListener {
            switchCoverLrc(viewBinding.albumCoverView.isVisible.not())
        }
        viewBinding.controlLayout.ivDownload.isVisible = false
    }

    private fun initPlayControl() {
        lifecycleScope.launch {
            playerController.playMode.collectLatest { playMode ->
                viewBinding.controlLayout.ivMode.setImageLevel(playMode.value)
            }
        }

        viewBinding.controlLayout.ivMode.setOnClickListener {
            switchPlayMode()
        }
        viewBinding.controlLayout.flPlay.setOnClickListener {
            playerController.playPause()
        }
        viewBinding.controlLayout.ivPrev.setOnClickListener {
            playerController.prev()
        }
        viewBinding.controlLayout.ivNext.setOnClickListener {
            playerController.next()
        }
        viewBinding.controlLayout.ivPlaylist.setOnClickListener {
            CurrentPlaylistFragment.newInstance()
                .show(supportFragmentManager, CurrentPlaylistFragment.TAG)
        }
        viewBinding.controlLayout.sbProgress.setOnSeekBarChangeListener(object :
            OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (abs(progress - lastProgress) >= DateUtils.SECOND_IN_MILLIS) {
                    viewBinding.controlLayout.tvCurrentTime.text =
                        TimeUtils.formatMs(progress.toLong())
                    lastProgress = progress
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isDraggingProgress = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar ?: return
                isDraggingProgress = false
                val playState = playerController.playState.value
                if (playState.isPlaying || playState.isPausing) {
                    val progress = seekBar.progress
                    playerController.seekTo(progress)
                    if (viewBinding.lrcView.hasLrc()) {
                        viewBinding.lrcView.updateTime(progress.toLong())
                    }
                } else {
                    seekBar.progress = 0
                }
            }
        })
        viewBinding.volumeLayout.sbVolume.setOnSeekBarChangeListener(object :
            OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar ?: return
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    seekBar.progress,
                    AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE
                )
            }
        })
    }

    private fun initData() {
        val onSongUpdate: (MediaItem) -> Unit = { song ->
            viewBinding.controlLayout.tvTitle.text = song.mediaMetadata.title
            viewBinding.controlLayout.tvArtist.text = song.mediaMetadata.artist
            viewBinding.controlLayout.sbProgress.max = knownDuration(song).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            viewBinding.controlLayout.sbProgress.progress =
                playerController.playProgress.value.toInt()
            viewBinding.controlLayout.sbProgress.secondaryProgress = 0
            viewBinding.controlLayout.tvCurrentTime.text =
                TimeUtils.formatMs(playerController.playProgress.value)
            viewBinding.controlLayout.tvTotalTime.text =
                TimeUtils.formatMs(knownDuration(song))
            updateCover(song)
            updateLrc(song)
            updatePlayState(playerController.playState.value)
            updateOnlineActionsState(song)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerController.currentSong.collectLatest { song ->
                    if (song != null) {
                        onSongUpdate(song)
                    } else {
                        finish()
                    }
                }
            }
        }

        lifecycleScope.launch {
            playerController.playState.collectLatest { playState ->
                updatePlayState(playState)
            }
        }

        lifecycleScope.launch {
            playerController.playProgress.collectLatest { progress ->
                if (isDraggingProgress.not()) {
                    viewBinding.controlLayout.sbProgress.progress = progress.toInt()
                }
                if (viewBinding.lrcView.hasLrc()) {
                    viewBinding.lrcView.updateTime(progress)
                }
            }
        }

        lifecycleScope.launch {
            playerController.bufferingPercent.collectLatest { percent ->
                viewBinding.controlLayout.sbProgress.secondaryProgress =
                    viewBinding.controlLayout.sbProgress.max * percent / 100
            }
        }
    }

    private fun updateCover(song: MediaItem) {
        setDefaultCover()
        ImageUtils.loadBitmap(song.getLargeCover()) {
            if (it.isSuccessWithData() && !isDestroyed && playerController.currentSong.value?.mediaId == song.mediaId) {
                val bitmap = it.getDataOrThrow()
                viewBinding.albumCoverView.setImageBitmap(bitmap)
                Blurry.with(this).sampling(10).from(bitmap).into(viewBinding.ivPlayingBg)
            }
        }
    }

    private fun setDefaultCover() {
        viewBinding.albumCoverView.setImageBitmap(defaultCoverBitmap)
        viewBinding.ivPlayingBg.setImageBitmap(defaultBgBitmap)
    }

    private fun updateLrc(song: MediaItem) {
        loadLrcJob?.cancel()
        lyricsDurationMs = knownDuration(song)
        viewBinding.lrcView.loadLrc("")
        viewBinding.lrcView.isVisible = !viewBinding.albumCoverView.isVisible
        viewBinding.plainLyrics.text = ""
        viewBinding.plainLyricsScroll.isVisible = false
        viewBinding.lyricsCredit.text = getString(R.string.player_lyrics_source)
        val lrcPath = LrcCache.getLrcFilePath(song)
        if (!lrcPath.isNullOrEmpty() && File(lrcPath).exists()) {
            loadLrc(lrcPath)
            viewBinding.lyricsCredit.text = getString(R.string.player_local_lyrics)
            return
        }
        setLrcLabel(getString(R.string.player_lyrics_loading))
        loadLrcJob = lifecycleScope.launch {
            try {
                val track = CloudLibrary.track(song.mediaId.substringAfter('#'))
                val lyrics = CloudLyrics.get(
                    song.mediaMetadata.title?.toString().orEmpty(),
                    song.mediaMetadata.artist?.toString().orEmpty(),
                    track?.album ?: song.mediaMetadata.albumTitle?.toString().orEmpty(),
                    lyricsDurationMs,
                )
                if (playerController.currentSong.value?.mediaId != song.mediaId) return@launch
                when {
                    lyrics.instrumental -> setLrcLabel(getString(R.string.player_instrumental))
                    lyrics.synced.isNotBlank() -> {
                        viewBinding.lrcView.loadLrc(lyrics.synced)
                        viewBinding.lrcView.updateTime(playerController.playProgress.value)
                    }
                    lyrics.plain.isNotBlank() -> {
                        viewBinding.plainLyrics.text = lyrics.plain
                        viewBinding.plainLyricsScroll.isVisible = viewBinding.albumCoverView.isVisible.not()
                        viewBinding.lrcView.isVisible = false
                    }
                    else -> setLrcLabel(getString(R.string.player_no_lyrics))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                setLrcLabel(getString(R.string.player_lyrics_error))
            }
        }
    }

    private fun loadLrc(path: String) {
        val file = File(path)
        viewBinding.lrcView.loadLrc(file)
    }

    private fun setLrcLabel(label: String) {
        viewBinding.lrcView.setLabel(label)
    }

    private fun switchCoverLrc(showCover: Boolean) {
        viewBinding.albumCoverView.isVisible = showCover
        viewBinding.lrcLayout.isVisible = !showCover
        val hasPlain = viewBinding.plainLyrics.text.isNotBlank()
        viewBinding.plainLyricsScroll.isVisible = !showCover && hasPlain
        viewBinding.lrcView.isVisible = !showCover && !hasPlain
        viewBinding.titleLayout.lyricsToggle.text = getString(
            if (showCover) R.string.player_lyrics else R.string.player_artwork
        )
    }

    private fun switchPlayMode() {
        val mode = when (playerController.playMode.value) {
            PlayMode.Loop -> PlayMode.Shuffle
            PlayMode.Shuffle -> PlayMode.Single
            PlayMode.Single -> PlayMode.Loop
        }
        toast(mode.nameRes)
        playerController.setPlayMode(mode)
    }

    private fun knownDuration(song: MediaItem): Long {
        val metadataDuration = song.mediaMetadata.getDuration()
        if (metadataDuration > 0 && metadataDuration != C.TIME_UNSET) return metadataDuration
        val controller = playerController.mediaController
        return if (controller.currentMediaItem?.mediaId == song.mediaId) {
            controller.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
        } else 0L
    }

    private fun updateDuration() {
        val song = playerController.currentSong.value ?: return
        val duration = knownDuration(song)
        viewBinding.controlLayout.sbProgress.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        viewBinding.controlLayout.sbProgress.isEnabled = duration > 0
        viewBinding.controlLayout.tvTotalTime.text = TimeUtils.formatMs(duration)
        // Home shelves can omit duration; retry exact lyric matching once the stream supplies it.
        if (duration > 0 && lyricsDurationMs == 0L) updateLrc(song)
    }

    private fun updatePlayState(playState: PlayState) {
        updateDuration()
        when (playState) {
            PlayState.Preparing -> {
                viewBinding.controlLayout.flPlay.isEnabled = false
                viewBinding.controlLayout.ivPlay.isSelected = false
                viewBinding.controlLayout.loadingProgress.isVisible = true
            }

            PlayState.Playing -> {
                viewBinding.controlLayout.flPlay.isEnabled = true
                viewBinding.controlLayout.ivPlay.isSelected = true
                viewBinding.controlLayout.loadingProgress.isVisible = false
            }

            else -> {
                viewBinding.controlLayout.flPlay.isEnabled = true
                viewBinding.controlLayout.ivPlay.isSelected = false
                viewBinding.controlLayout.loadingProgress.isVisible = false
            }
        }
    }

    private fun updateOnlineActionsState(song: MediaItem) {
        viewBinding.controlLayout.llActions.isVisible = true
        viewBinding.controlLayout.ivLike.isVisible = song.isLocal().not()
        val liked = CloudLibrary.liked(song.mediaId.substringAfter('#'))
        viewBinding.controlLayout.ivLike.isSelected = liked
        viewBinding.controlLayout.ivLike.contentDescription = getString(
            if (liked) R.string.player_unlike else R.string.player_like
        )
    }

    override fun onDestroy() {
        loadLrcJob?.cancel()
        playerController.mediaController.removeListener(durationListener)
        super.onDestroy()
        unregisterReceiver(volumeReceiver)
        defaultCoverBitmap.recycle()
        defaultBgBitmap.recycle()
    }

    private val volumeReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            viewBinding.volumeLayout.sbVolume.progress =
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
    }

    companion object {
        private const val TAG = "PlayingActivity"
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    }
}
