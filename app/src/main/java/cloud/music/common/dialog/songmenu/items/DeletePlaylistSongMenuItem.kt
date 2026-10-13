package cloud.music.common.dialog.songmenu.items

import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import cloud.music.account.service.UserServiceModule.Companion.userService
import cloud.music.common.bean.PlaylistData
import cloud.music.common.bean.SongData
import cloud.music.common.dialog.songmenu.MenuItem
import cloud.music.mine.MineApi
import top.wangchenyan.common.ext.findActivity
import top.wangchenyan.common.ext.showConfirmDialog
import top.wangchenyan.common.ext.toast
import top.wangchenyan.common.ui.activity.BaseActivity

/**
 * Created by wangchenyan.top on 2023/10/11.
 */
class DeletePlaylistSongMenuItem(
    private val playlistData: PlaylistData,
    private val songData: SongData,
    private val onDelete: (songData: SongData) -> Unit,
) : MenuItem {
    override val name: String
        get() = "Remove"

    override fun onClick(view: View) {
        val activity = view.context.findActivity() as? BaseActivity
        activity ?: return
        if (activity.application.userService().isLogin().not()) return
        activity.showConfirmDialog(message = "Remove the selected track from this playlist?") {
            activity.lifecycleScope.launch {
                val result = runCatching {
                    MineApi.get().collectSong(playlistData.id, songData.id.toString(), "del")
                }
                if (result.isSuccess) {
                    val body = result.getOrThrow().body
                    if (body.code == 200) {
                        onDelete.invoke(songData)
                    } else {
                        toast(body.message)
                    }
                } else {
                    toast(result.exceptionOrNull()?.message)
                }
            }
        }
    }
}