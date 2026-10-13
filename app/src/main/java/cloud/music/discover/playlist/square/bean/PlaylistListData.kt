package cloud.music.discover.playlist.square.bean

import com.google.gson.annotations.SerializedName
import cloud.music.common.bean.PlaylistData

/**
 * Created by wangchenyan.top on 2023/9/25.
 */
data class PlaylistListData(
    @SerializedName("code")
    val code: Int = 0,
    @SerializedName("playlists", alternate = ["playlist", "recommend", "list"])
    val playlists: List<PlaylistData> = emptyList(),
)
