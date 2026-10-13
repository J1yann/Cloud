package cloud.music.service

import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import cloud.music.catalog.CloudLibrary
import cloud.music.catalog.CloudPage
import cloud.music.catalog.CloudTrack
import cloud.music.catalog.YouTubeMusicMetadata
import cloud.music.playback.radioMediaItem
import cloud.music.storage.db.MusicDatabase
import cloud.music.storage.preference.ConfigPreferences
import cloud.music.utils.toMediaItem
import cloud.music.utils.toSongEntity
import top.wangchenyan.common.ext.toUnMutable
import top.wangchenyan.common.ext.toast

/**
 * Created by wangchenyan.top on 2024/3/27.
 */
class PlayerControllerImpl(
    private val player: MediaController,
    private val db: MusicDatabase,
) : PlayerController, CoroutineScope by MainScope() {

    override val mediaController: MediaController
        get() = player

    private val _playlist = MutableStateFlow(emptyList<MediaItem>())
    override val playlist = _playlist.toUnMutable()

    private val _currentSong = MutableStateFlow<MediaItem?>(null)
    override val currentSong = _currentSong.toUnMutable()

    private val _playState = MutableStateFlow<PlayState>(PlayState.Idle)
    override val playState = _playState.toUnMutable()

    private val _playProgress = MutableStateFlow<Long>(0)
    override val playProgress = _playProgress.toUnMutable()

    private val _bufferingPercent = MutableStateFlow(0)
    override val bufferingPercent = _bufferingPercent.toUnMutable()

    private val _playMode = MutableStateFlow(PlayMode.valueOf(ConfigPreferences.playMode))
    override val playMode: StateFlow<PlayMode> = _playMode

    private val queueMutex = Mutex()

    private var audioSessionId = 0
    private var radioJob: Job? = null
    private var radioGeneration = 0L
    private var radioSeed: String? = null
    private var radioContinuation: String? = null
    private var radioPending = emptyList<CloudTrack>()
    private var radioStarted = false
    private var radioExhausted = false
    private var radioRetryAfterMillis = 0L
    private var autoplayEnabled = ConfigPreferences.autoplay

    init {
        player.playWhenReady = false
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                super.onPlaybackStateChanged(playbackState)
                when (playbackState) {
                    Player.STATE_IDLE -> {
                        _playState.value = PlayState.Idle
                        _playProgress.value = 0
                        _bufferingPercent.value = 0
                    }

                    Player.STATE_BUFFERING -> {
                        _playState.value = PlayState.Preparing
                    }

                    Player.STATE_READY -> {
                        _playState.value = if (player.isPlaying) PlayState.Playing else PlayState.Pause
                    }

                    Player.STATE_ENDED -> {
                        _playState.value = PlayState.Pause
                        requestRadioIfNeeded()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                if (isPlaying) {
                    requestRadioIfNeeded()
                    player.currentMediaItem?.mediaId?.takeIf { it.startsWith("3#") }?.let {
                        cloud.music.catalog.CloudLibrary.played(it.substringAfter("#"))
                    }
                }
                if (player.playbackState == Player.STATE_READY) {
                    _playState.value = if (isPlaying) PlayState.Playing else PlayState.Pause
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                invalidateRadio(resetSession = false)
                mediaItem ?: return
                val playlist = _playlist.value
                _currentSong.value = playlist.find { it.mediaId == mediaItem.mediaId } ?: mediaItem
                requestRadioIfNeeded()
                if (player.isPlaying && mediaItem.mediaId.startsWith("3#")) {
                    cloud.music.catalog.CloudLibrary.played(mediaItem.mediaId.substringAfter("#"))
                }
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                requestRadioIfNeeded()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) requestRadioIfNeeded() else invalidateRadio(resetSession = false)
            }

            @OptIn(UnstableApi::class)
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                super.onAudioSessionIdChanged(audioSessionId)
                this@PlayerControllerImpl.audioSessionId = audioSessionId
            }

            override fun onPlayerError(error: PlaybackException) {
                super.onPlayerError(error)
                stop()
                val message = error.cause?.message?.takeIf { it.isNotBlank() }
                    ?: error.localizedMessage ?: "Please try again"
                val safeMessage = Regex("https?://\\S+").replace(message, "[stream]").take(240)
                toast("Could not play this song: $safeMessage")
            }
        })
        setPlayMode(PlayMode.valueOf(ConfigPreferences.playMode))

        launch(Dispatchers.Main.immediate) {
            queueMutex.withLock {
                val playlist = withContext(Dispatchers.IO) {
                    db.playlistDao()
                        .queryAll()
                        .filter { entity ->
                            val supported = entity.type == 0 || entity.type == 3
                            if (!supported) db.playlistDao().delete(entity)
                            supported
                        }
                        .onEach {
                            // 兼容老版本数据库
                            if (it.uri.isEmpty()) {
                                it.uri = it.path
                            }
                        }
                        .map { it.toMediaItem() }
                }
                if (playlist.isNotEmpty()) {
                    _playlist.value = playlist
                    player.setMediaItems(playlist)
                    val currentSongId = ConfigPreferences.currentSongId
                    if (currentSongId.isNotEmpty()) {
                        val currentSongIndex = playlist.indexOfFirst {
                            it.mediaId == currentSongId
                        }.coerceAtLeast(0)
                        _currentSong.value = playlist[currentSongIndex]
                        player.seekTo(currentSongIndex, 0)
                    }
                }
            }

            _currentSong.collectLatest {
                ConfigPreferences.currentSongId = it?.mediaId ?: ""
            }
        }

        launch {
            while (isActive) {
                if (autoplayEnabled != ConfigPreferences.autoplay) {
                    autoplayEnabled = ConfigPreferences.autoplay
                    invalidateRadio(resetSession = true)
                    applyPlayMode(_playMode.value)
                }
                requestRadioIfNeeded()
                if (player.isPlaying) {
                    _playProgress.value = player.currentPosition
                }
                delay(1000)
            }
        }
    }

    @MainThread
    override fun addAndPlay(song: MediaItem) {
        invalidateRadio(resetSession = true)
        launch(Dispatchers.Main.immediate) {
            queueMutex.withLock {
                val newPlaylist = _playlist.value.toMutableList()
                val index = newPlaylist.indexOfFirst { it.mediaId == song.mediaId }
                if (index >= 0) newPlaylist[index] = song else newPlaylist.add(song)
                withContext(Dispatchers.IO) {
                    db.playlistDao().clear()
                    db.playlistDao().insertAll(newPlaylist.map { it.toSongEntity() })
                }
                _playlist.value = newPlaylist
                if (index >= 0) player.replaceMediaItem(index, song) else player.addMediaItem(song)
                play(song.mediaId)
            }
        }
    }

    @MainThread
    override fun enqueue(song: MediaItem) {
        invalidateRadio(resetSession = true)
        launch(Dispatchers.Main.immediate) {
            queueMutex.withLock {
                if (_playlist.value.any { it.mediaId == song.mediaId }) return@launch
                val queue = _playlist.value + song
                withContext(Dispatchers.IO) { db.playlistDao().insertAll(listOf(song.toSongEntity())) }
                _playlist.value = queue
                player.addMediaItem(song)
                requestRadioIfNeeded()
            }
        }
    }

    @MainThread
    override fun replaceAll(songList: List<MediaItem>, song: MediaItem) {
        invalidateRadio(resetSession = true)
        launch(Dispatchers.Main.immediate) {
            queueMutex.withLock {
                withContext(Dispatchers.IO) {
                    db.playlistDao().clear()
                    db.playlistDao().insertAll(songList.map { it.toSongEntity() })
                }
                stop()
                _playlist.value = songList
                player.setMediaItems(songList)
                _currentSong.value = song
                play(song.mediaId)
            }
        }
    }

    @MainThread
    override fun play(mediaId: String) {
        invalidateRadio(resetSession = true)
        val playlist = _playlist.value
        if (playlist.isEmpty()) {
            return
        }
        val index = playlist.indexOfFirst { it.mediaId == mediaId }
        if (index < 0) {
            return
        }

        stop()
        player.seekTo(index, 0)
        player.prepare()
        player.play()

        _currentSong.value = playlist[index]
        _playProgress.value = 0
        _bufferingPercent.value = 0
        requestRadioIfNeeded()
    }

    @MainThread
    override fun delete(song: MediaItem) {
        invalidateRadio(resetSession = true)
        launch(Dispatchers.Main.immediate) {
            queueMutex.withLock {
                val playlist = _playlist.value.toMutableList()
                val index = playlist.indexOfFirst { it.mediaId == song.mediaId }
                if (index < 0) return@launch
                if (playlist.size == 1) {
                    clearQueueLocked()
                } else {
                    playlist.removeAt(index)
                    _playlist.value = playlist
                    withContext(Dispatchers.IO) {
                        db.playlistDao().delete(song.toSongEntity())
                    }
                    player.removeMediaItem(index)
                    requestRadioIfNeeded()
                }
            }
        }
    }

    @MainThread
    override fun clearPlaylist() {
        invalidateRadio(resetSession = true)
        launch(Dispatchers.Main.immediate) {
            queueMutex.withLock {
                clearQueueLocked()
            }
        }
    }

    private suspend fun clearQueueLocked() {
        withContext(Dispatchers.IO) {
            db.playlistDao().clear()
        }
        stop()
        _playlist.value = emptyList()
        _currentSong.value = null
        player.clearMediaItems()
    }

    @MainThread
    override fun playPause() {
        if (player.mediaItemCount == 0) return
        when (player.playbackState) {
            Player.STATE_IDLE -> {
                player.prepare()
                player.play()
            }

            Player.STATE_BUFFERING -> {
                stop()
            }

            Player.STATE_READY -> {
                if (player.isPlaying) {
                    player.pause()
                    _playState.value = PlayState.Pause
                } else {
                    player.play()
                    _playState.value = PlayState.Playing
                }
            }

            Player.STATE_ENDED -> {
                invalidateRadio(resetSession = false)
                // An explicit Play tap restarts a finished final item when there is no next item.
                if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.seekTo(0L)
                player.prepare()
                player.play()
                _playProgress.value = 0
                _bufferingPercent.value = 0
            }
        }
    }

    @MainThread
    override fun next() {
        if (player.mediaItemCount == 0) return
        if (!player.hasNextMediaItem()) {
            if (ConfigPreferences.autoplay &&
                (_playMode.value == PlayMode.Sequential || _playMode.value == PlayMode.Shuffle) &&
                player.currentMediaItem?.mediaId?.startsWith("3#") == true) {
                // Keep a pending radio fetch; seeking to a nonexistent next item leaves STATE_ENDED stuck.
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
                requestRadioIfNeeded()
            }
            return
        }
        invalidateRadio(resetSession = true)
        player.seekToNextMediaItem()
        player.prepare()
        player.play()
        _playProgress.value = 0
        _bufferingPercent.value = 0
    }

    @MainThread
    override fun prev() {
        invalidateRadio(resetSession = true)
        if (player.mediaItemCount == 0) return
        player.seekToPreviousMediaItem()
        player.prepare()
        player.play()
        _playProgress.value = 0
        _bufferingPercent.value = 0
    }

    @MainThread
    override fun seekTo(msec: Int) {
        if (player.playbackState == Player.STATE_READY) {
            player.seekTo(msec.toLong())
        }
    }

    @MainThread
    override fun getAudioSessionId(): Int {
        return audioSessionId
    }

    @MainThread
    override fun setPlayMode(mode: PlayMode) {
        ConfigPreferences.playMode = mode.value
        _playMode.value = mode
        invalidateRadio(resetSession = true)
        applyPlayMode(mode)
        requestRadioIfNeeded()
    }

    private fun applyPlayMode(mode: PlayMode) {
        when (mode) {
            PlayMode.Sequential -> {
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.shuffleModeEnabled = false
            }
            PlayMode.Loop -> {
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.shuffleModeEnabled = false
            }

            PlayMode.Shuffle -> {
                player.repeatMode = if (ConfigPreferences.autoplay) Player.REPEAT_MODE_OFF else Player.REPEAT_MODE_ALL
                player.shuffleModeEnabled = true
            }

            PlayMode.Single -> {
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.shuffleModeEnabled = false
            }
        }
    }

    private fun invalidateRadio(resetSession: Boolean) {
        radioGeneration += 1
        radioJob?.cancel()
        radioJob = null
        if (resetSession) {
            radioSeed = null
            radioContinuation = null
            radioStarted = false
            radioPending = emptyList()
            radioExhausted = false
            radioRetryAfterMillis = 0
        }
    }

    /** Count the real upcoming items, respecting Media3's shuffle order but ignoring repetition. */
    private fun nearQueueEnd(): Boolean {
        val timeline = player.currentTimeline
        var index = player.currentMediaItemIndex
        if (timeline.isEmpty || index !in 0 until timeline.windowCount) return false
        var remaining = 0
        repeat(3) {
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (index == C.INDEX_UNSET) return remaining <= 2
            remaining += 1
        }
        return false
    }

    private fun eligibleForRadio(): Boolean =
        ConfigPreferences.autoplay && player.playWhenReady &&
            (_playMode.value == PlayMode.Sequential || _playMode.value == PlayMode.Shuffle) &&
            player.currentMediaItem?.mediaId?.startsWith("3#") == true && nearQueueEnd()

    /** Append to the user's queue; recommendations never replace album order or seed from history. */
    private fun requestRadioIfNeeded() {
        if (!eligibleForRadio() || radioJob?.isActive == true ||
            System.currentTimeMillis() < radioRetryAfterMillis) return
        val currentId = player.currentMediaItem?.mediaId ?: return
        val videoId = currentId.substringAfter("#")
        if (radioExhausted) {
            if (radioSeed == videoId) return
            // A finished radio without continuation can be renewed from the song now playing.
            invalidateRadio(resetSession = true)
        }
        if (!Regex("[A-Za-z0-9_-]{11}").matches(videoId)) return
        if (radioSeed == null) radioSeed = videoId
        val seed = radioSeed ?: return
        val generation = radioGeneration
        val startingContinuation = if (radioStarted) radioContinuation else null
        radioJob = launch(start = CoroutineStart.LAZY) {
            try {
                var token = startingContinuation
                // A radio page may include an entire existing album; walk up to three pages for fresh songs.
                repeat(3) {
                    val page = if (radioPending.isNotEmpty()) CloudPage(radioPending, radioContinuation)
                        else YouTubeMusicMetadata.instance.radio(seed, token)
                    queueMutex.withLock {
                        if (generation != radioGeneration || player.currentMediaItem?.mediaId != currentId ||
                            !eligibleForRadio()) return@launch
                        val queuedIds = _playlist.value.map { it.mediaId.substringAfter("#") }.toHashSet()
                        val fresh = page.items.filter { it.allowsPlayback && it.id !in queuedIds && it.id != videoId }
                            .distinctBy { it.id }
                        val additions = fresh.take(10)
                        if (additions.isNotEmpty()) {
                            val items = additions.map { it.radioMediaItem() }
                            val firstAddedIndex = _playlist.value.size
                            // Queue persistence and its UI update form one commit even if this job is cancelled.
                            withContext(NonCancellable) {
                                withContext(Dispatchers.IO) {
                                    db.playlistDao().insertAll(items.map { it.toSongEntity() })
                                }
                                if (generation != radioGeneration || player.currentMediaItem?.mediaId != currentId ||
                                    !eligibleForRadio()) {
                                    withContext(Dispatchers.IO) { items.forEach { db.playlistDao().delete(it.toSongEntity()) } }
                                    return@withContext
                                }
                                additions.forEach(CloudLibrary::remember)
                                radioStarted = true
                                radioContinuation = page.next
                                radioPending = fresh.drop(additions.size)
                                val resumeAfterEnd = player.playbackState == Player.STATE_ENDED && player.playWhenReady
                                _playlist.value = _playlist.value + items
                                player.addMediaItems(items)
                                radioRetryAfterMillis = 0
                                radioExhausted = page.next == null && radioPending.isEmpty()
                                if (resumeAfterEnd) {
                                    player.seekTo(firstAddedIndex, 0)
                                    player.prepare()
                                    player.play()
                                }
                            }
                            return@launch
                        }
                        radioStarted = true
                        radioContinuation = page.next
                        radioPending = emptyList()
                        if (page.next == null || page.next == token) {
                            radioExhausted = true
                            return@launch
                        }
                    }
                    token = page.next
                }
                radioRetryAfterMillis = System.currentTimeMillis() + 60_000
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Offline recommendations leave the explicit queue playable and retry on a later tick.
                if (generation == radioGeneration) radioRetryAfterMillis = System.currentTimeMillis() + 60_000
            } finally {
                if (generation == radioGeneration) radioJob = null
            }
        }
        radioJob?.start()
    }

    @MainThread
    override fun stop() {
        invalidateRadio(resetSession = true)
        player.stop()
        _playState.value = PlayState.Idle
    }
}
