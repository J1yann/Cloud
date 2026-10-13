package cloud.music.playback

import cloud.music.catalog.CloudTrack
import cloud.music.storage.db.entity.SongEntity
import cloud.music.utils.toMediaItem

/** Radio uses the same stable IDs and persisted metadata as explicit catalogue selections. */
internal fun CloudTrack.radioMediaItem() = SongEntity(
    type = SongEntity.YOUTUBE,
    title = title,
    artist = artist,
    album = album,
    albumCover = artwork,
    duration = durationMs,
    uri = "${YouTubePlayback.SCHEME}://track/$id"
).apply { uniqueId = "3#$id" }.toMediaItem()
