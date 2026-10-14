package cloud.music.service

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import cloud.music.playback.RetryingYouTubeDataSource
import cloud.music.playback.NewPipeDownloader
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import cloud.music.R
import cloud.music.net.datasource.MusicDataSource
import cloud.music.utils.MusicUtils
import top.wangchenyan.common.CommonApp

/**
 * Created by wangchenyan.top on 2024/3/26.
 */
class MusicService : MediaSessionService() {
    private lateinit var player: Player
    private lateinit var session: MediaSession

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        player = ExoPlayer.Builder(applicationContext)
            // 自动处理音频焦点
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            // 自动暂停播放
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(applicationContext)
                    .setDataSourceFactory(RetryingYouTubeDataSource.Factory(
                        MusicDataSource.Factory(applicationContext,
                            DefaultHttpDataSource.Factory().setUserAgent(NewPipeDownloader.USER_AGENT))
                    ))
            )
            .build()

        // Combined YouTube streams are an audio fallback; do not decode their video track.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()

        session = MediaSession.Builder(this, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    MusicUtils.getStartPlayingPageIntent(this),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(applicationContext).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            }
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return session
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        player.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
        session.release()
    }

    companion object {
        val EXTRA_NOTIFICATION = "${CommonApp.app.packageName}.notification"
    }
}
