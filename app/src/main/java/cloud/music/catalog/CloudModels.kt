package cloud.music.catalog

data class CloudPage<T>(val items: List<T>, val next: String?)

data class CloudArtist(
    val id: String,
    val name: String,
    val artwork: String = "",
    val url: String = "",
    val description: String = "",
)

data class CloudTrack(
    val id: String,
    val title: String,
    val artist: String,
    val uploader: CloudArtist,
    val artwork: String,
    val durationMs: Long,
    val album: String = "",
    val genre: String = "",
    val url: String = "https://music.youtube.com/watch?v=$id",
    val available: Boolean = true,
    val explicit: Boolean = false,
    val plays: Long = 0,
    val likes: Long = 0,
) {
    val allowsPlayback: Boolean get() = available && Regex("[A-Za-z0-9_-]{11}").matches(id)
}

data class CloudPlaylist(
    val id: String,
    val title: String,
    val creator: String,
    val artwork: String,
    val url: String,
    val trackCount: Int = 0,
    val isAlbum: Boolean = false,
)

data class CloudSection(
    val title: String,
    val playlists: CloudPage<CloudPlaylist> = CloudPage(emptyList(), null),
    val tracks: CloudPage<CloudTrack> = CloudPage(emptyList(), null),
    val artists: CloudPage<CloudArtist> = CloudPage(emptyList(), null),
)
