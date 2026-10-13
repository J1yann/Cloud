package cloud.music.common.dialog.songmenu.items

import android.view.View
import cloud.music.common.bean.SongData
import cloud.music.common.dialog.songmenu.MenuItem
import top.wangchenyan.common.ext.toast

/**
 * Created by wangchenyan.top on 2023/10/11.
 */
class CommentMenuItem(private val songData: SongData) : MenuItem {
    override val name: String
        get() = "Comments"

    override fun onClick(view: View) {
        toast("Coming soon")
    }
}