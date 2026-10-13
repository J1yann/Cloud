package cloud.music.search.song

import androidx.core.view.isVisible
import top.wangchenyan.common.ext.context
import cloud.music.common.OnItemClickListener2
import cloud.music.common.bean.SongData
import cloud.music.databinding.ItemSearchSongBinding
import cloud.music.utils.MusicUtils
import cloud.music.utils.getSimpleArtist
import me.wcy.radapter3.RItemBinder

/**
 * Created by wangchenyan.top on 2023/9/20.
 */
class SearchSongItemBinder(private val listener: OnItemClickListener2<SongData>) :
    RItemBinder<ItemSearchSongBinding, SongData>() {
    var keywords = ""

    override fun onBind(viewBinding: ItemSearchSongBinding, item: SongData, position: Int) {
        viewBinding.root.setOnClickListener {
            listener.onItemClick(item, position)
        }
        viewBinding.ivMore.setOnClickListener {
            listener.onMoreClick(item, position)
        }
        viewBinding.tvTitle.text = MusicUtils.keywordsTint(viewBinding.context, item.name, keywords)
        viewBinding.tvTag.isVisible = item.recommendReason.isNotEmpty()
        viewBinding.tvTag.text = item.recommendReason
        viewBinding.tvSubTitle.text = buildString {
            append(item.getSimpleArtist())
            append(" - ")
            append(item.al.name)
            item.originSongSimpleData?.let { originSong ->
                append(" | Original artist: ")
                append(originSong.artists.joinToString("/") { it.name })
            }
        }
    }
}