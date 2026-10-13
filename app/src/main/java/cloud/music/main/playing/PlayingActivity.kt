package cloud.music.main.playing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import cloud.music.R
import cloud.music.common.BaseMusicActivity
import cloud.music.common.DarkModeService
import cloud.music.storage.preference.ConfigPreferences
import android.graphics.Color
import cloud.music.consts.RoutePath
import cloud.music.databinding.ActivityPlayingBinding
import cloud.music.main.playlist.CurrentPlaylistFragment
import cloud.music.service.PlayMode
import cloud.music.service.PlayState
import cloud.music.service.PlayerController
import cloud.music.catalog.CloudLibrary
import cloud.music.lyrics.CloudLyrics
import cloud.music.lyrics.LyricsTiming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.net.Uri
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
    override val popupWindow = true
    private val viewBinding by viewBindings<ActivityPlayingBinding>()

    @Inject
    lateinit var playerController: PlayerController


    private val isAmoled: Boolean
        get() = ConfigPreferences.darkMode == DarkModeService.DarkMode.Amoled.value

    private val defaultCoverBitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.bg_playing_default_cover)
    }
    private val defaultBgBitmapDelegate = lazy {
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

    private val defaultBgBitmap by defaultBgBitmapDelegate

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

        if (isAmoled) {
            viewBinding.flBackground.setBackgroundColor(Color.BLACK)
            viewBinding.ivPlayingBg.isVisible = false
            viewBinding.backgroundShade.isVisible = false
            viewBinding.backgroundFade.isVisible = false
        }
        initWindowInsets()
        initTitle()
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

    private fun initCover() {
        viewBinding.albumCoverView.setOnClickListener {
            switchCoverLrc(false)
        }
        setDefaultCover()
    }

    private fun initLrc() {
        viewBinding.lrcView.positionProvider = { playerController.mediaController.currentPosition }
        viewBinding.lrcView.onLineClick = { time ->
            // Seeking a lyric line keeps a paused track paused.
            playerController.seekTo(time.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            viewBinding.controlLayout.sbProgress.progress = time.toInt()
            viewBinding.controlLayout.tvCurrentTime.text = TimeUtils.formatMs(time)
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
                viewBinding.controlLayout.ivMode.contentDescription = getString(playMode.nameRes)
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
                    viewBinding.lrcView.updatePosition(progress.toLong())
                } else {
                    seekBar.progress = 0
                }
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
                viewBinding.lrcView.updatePosition(playerController.mediaController.currentPosition)
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
        val original = song.getLargeCover()
        val host = runCatching { Uri.parse(original).host }.getOrNull().orEmpty()
        val large = when {
            host.endsWith("googleusercontent.com") || host.endsWith("ggpht.com") -> original
                .replace(Regex("=w\\d+-h\\d+"), "=w1200-h1200")
                .replace(Regex("=s\\d+"), "=s1200")
            host.endsWith("ytimg.com") -> original.replace(
                Regex("/(?:mqdefault|sddefault|hqdefault|hq720)\\.jpg"), "/maxresdefault.jpg"
            )
            else -> original
        }
        fun load(url: String, fallback: Boolean) {
            ImageUtils.loadBitmap(url) { result ->
                if (isDestroyed || isFinishing || playerController.currentSong.value?.mediaId != song.mediaId) return@loadBitmap
                if (result.isSuccessWithData()) {
                    val bitmap = result.getDataOrThrow()
                    // Missing YouTube maxres artwork can return a tiny placeholder with HTTP 200.
                    if (fallback && url != original && (bitmap.width < 320 || bitmap.height < 180)) {
                        load(original, false)
                        return@loadBitmap
                    }
                    viewBinding.albumCoverView.setImageBitmap(bitmap)
                    if (!isAmoled) Blurry.with(this).sampling(8).radius(22).from(bitmap).into(viewBinding.ivPlayingBg)
                } else if (fallback && url != original) load(original, false)
            }
        }
        if (original.isNotBlank()) load(large, true)
    }

    private fun setDefaultCover() {
        viewBinding.albumCoverView.setImageBitmap(defaultCoverBitmap)
        if (!isAmoled) viewBinding.ivPlayingBg.setImageBitmap(defaultBgBitmap)
    }

    private fun updateLrc(song: MediaItem) {
        loadLrcJob?.cancel()
        lyricsDurationMs = knownDuration(song)
        val duration = lyricsDurationMs
        setLrcLabel(getString(R.string.player_lyrics_loading))
        viewBinding.lrcView.setCredit("")
        loadLrcJob = lifecycleScope.launch {
            try {
                val lrcPath = LrcCache.getLrcFilePath(song)
                if (!lrcPath.isNullOrEmpty() && File(lrcPath).exists()) {
                    val lines = withContext(Dispatchers.IO) {
                        val file = File(lrcPath)
                        if (file.length() <= 1_000_000) LyricsTiming.lrc(file.readText()) else emptyList()
                    }
                    if (playerController.currentSong.value?.mediaId != song.mediaId) return@launch
                    if (lines.isNotEmpty()) {
                        viewBinding.lrcView.showLines(lines)
                        viewBinding.lrcView.setCredit(getString(R.string.player_local_lyrics))
                        return@launch
                    }
                }
                val track = CloudLibrary.track(song.mediaId.substringAfter('#'))
                var displayedFallback: CloudLyrics.Result? = null
                val lyrics = CloudLyrics.get(
                    song.mediaMetadata.title?.toString().orEmpty(),
                    song.mediaMetadata.artist?.toString().orEmpty(),
                    track?.album ?: song.mediaMetadata.albumTitle?.toString().orEmpty(),
                    duration,
                    onFallback = { fallback ->
                        withContext(Dispatchers.Main.immediate) {
                            if (playerController.currentSong.value?.mediaId == song.mediaId) {
                                renderLyrics(fallback)
                                displayedFallback = fallback
                            }
                        }
                    },
                )
                if (playerController.currentSong.value?.mediaId != song.mediaId) return@launch
                if (lyrics != displayedFallback) renderLyrics(lyrics)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                setLrcLabel(getString(R.string.player_lyrics_error))
                viewBinding.lrcView.setCredit("")
            }
        }
    }

    private fun renderLyrics(lyrics: CloudLyrics.Result) {
        val timing = if (lyrics.lines.any { it.words.isNotEmpty() }) getString(R.string.player_word_synced)
            else if (lyrics.lines.isNotEmpty()) getString(R.string.player_line_synced)
            else getString(R.string.player_plain_lyrics)
        when {
            lyrics.instrumental -> setLrcLabel(getString(R.string.player_instrumental))
            lyrics.lines.isNotEmpty() -> {
                viewBinding.lrcView.showLines(lyrics.lines)
                viewBinding.lrcView.updatePosition(playerController.mediaController.currentPosition)
            }
            lyrics.plain.isNotBlank() -> viewBinding.lrcView.showPlain(lyrics.plain)
            else -> {
                setLrcLabel(getString(R.string.player_no_lyrics))
                return
            }
        }
        viewBinding.lrcView.setCredit(getString(R.string.player_lyrics_credit, lyrics.source, timing))
    }

    private fun setLrcLabel(label: String) {
        viewBinding.lrcView.showMessage(label)
    }

    private fun switchCoverLrc(showCover: Boolean) {
        viewBinding.albumCoverView.isVisible = showCover
        viewBinding.lrcLayout.isVisible = !showCover
        viewBinding.lrcView.isVisible = !showCover
        viewBinding.titleLayout.lyricsToggle.isSelected = !showCover
        viewBinding.titleLayout.lyricsToggle.text = getString(
            if (showCover) R.string.player_lyrics else R.string.player_artwork
        )
        viewBinding.lrcView.setPlaying(playerController.playState.value == PlayState.Playing)
    }

    private fun switchPlayMode() {
        val mode = when (playerController.playMode.value) {
            PlayMode.Sequential -> PlayMode.Loop
            PlayMode.Loop -> PlayMode.Shuffle
            PlayMode.Shuffle -> PlayMode.Single
            PlayMode.Single -> PlayMode.Sequential
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
        viewBinding.lrcView.setPlaying(playState == PlayState.Playing)
        viewBinding.controlLayout.flPlay.contentDescription = getString(
            if (playState == PlayState.Playing) R.string.player_pause else R.string.player_play
        )
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
        defaultCoverBitmap.recycle()
        if (defaultBgBitmapDelegate.isInitialized()) defaultBgBitmap.recycle()
    }

}
