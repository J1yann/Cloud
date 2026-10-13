package cloud.music.search.bean

import com.google.gson.annotations.SerializedName
import cloud.music.common.bean.PlaylistData
import cloud.music.common.bean.SongData

/**
 * Created by wangchenyan.top on 2023/9/20.
 */
data class SearchResultData(
    @SerializedName("songs")
    val songs: List<SongData> = emptyList(),
    @SerializedName("songCount")
    val songCount: Int = 0,
    @SerializedName("playlists")
    val playlists: List<PlaylistData> = emptyList(),
    @SerializedName("playlistCount")
    val playlistCount: Int = 0,
)
