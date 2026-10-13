package cloud.music.service

import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
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
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                if (isPlaying) {
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
                mediaItem ?: return
                val playlist = _playlist.value
                _currentSong.value = playlist.find { it.mediaId == mediaItem.mediaId }
                if (player.isPlaying && mediaItem.mediaId.startsWith("3#")) {
                    cloud.music.catalog.CloudLibrary.played(mediaItem.mediaId.substringAfter("#"))
                }
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
                if (player.isPlaying) {
                    _playProgress.value = player.currentPosition
                }
                delay(1000)
            }
        }
    }

    @MainThread
    override fun addAndPlay(song: MediaItem) {
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
        launch(Dispatchers.Main.immediate) {
            queueMutex.withLock {
                if (_playlist.value.any { it.mediaId == song.mediaId }) return@launch
                val queue = _playlist.value + song
                withContext(Dispatchers.IO) { db.playlistDao().insertAll(listOf(song.toSongEntity())) }
                _playlist.value = queue
                player.addMediaItem(song)
            }
        }
    }

    @MainThread
    override fun replaceAll(songList: List<MediaItem>, song: MediaItem) {
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
    }

    @MainThread
    override fun delete(song: MediaItem) {
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
                }
            }
        }
    }

    @MainThread
    override fun clearPlaylist() {
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
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }
    }

    @MainThread
    override fun next() {
        if (player.mediaItemCount == 0) return
        player.seekToNextMediaItem()
        player.prepare()
        player.play()
        _playProgress.value = 0
        _bufferingPercent.value = 0
    }

    @MainThread
    override fun prev() {
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
        when (mode) {
            PlayMode.Loop -> {
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.shuffleModeEnabled = false
            }

            PlayMode.Shuffle -> {
                player.repeatMode = Player.REPEAT_MODE_ALL
                player.shuffleModeEnabled = true
            }

            PlayMode.Single -> {
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.shuffleModeEnabled = false
            }
        }
    }

    @MainThread
    override fun stop() {
        player.stop()
        _playState.value = PlayState.Idle
    }
}
