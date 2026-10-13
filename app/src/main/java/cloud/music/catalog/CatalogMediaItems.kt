package cloud.music.catalog

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import cloud.music.utils.setBaseCover
import cloud.music.utils.setDuration

fun CloudTrack.toCloudMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId("3#$id")
    .setUri(Uri.Builder().scheme("cloud-youtube").authority("track").appendPath(id).build())
    .setMimeType(MimeTypes.AUDIO_MP4)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setAlbumTitle(album)
        .setArtworkUri(Uri.parse(artwork)).setBaseCover(artwork).setDuration(durationMs).build())
    .build()
