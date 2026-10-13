package cloud.music.main

import androidx.annotation.DrawableRes
import androidx.fragment.app.Fragment
import cloud.music.R
import cloud.music.discover.home.DiscoverFragment
import cloud.music.mine.home.MineFragment
import cloud.music.search.SearchFragment

sealed class NaviTab private constructor(
    val id: String,
    @DrawableRes
    val icon: Int,
    val name: String,
    val newFragment: () -> Fragment
) {
    object Discover : NaviTab(
        "discover",
        R.drawable.ic_tab_discover,
        "Home",
        { DiscoverFragment() }
    )

    object Search : NaviTab(
        "search",
        R.drawable.ic_menu_search,
        "Search",
        { SearchFragment() }
    )

    object Mine : NaviTab(
        "mine",
        R.drawable.ic_tab_mine,
        "Library",
        { MineFragment() }
    )

    fun getPosition(): Int {
        return ALL.indexOf(this)
    }

    companion object {
        val ALL: List<NaviTab> = listOf(
            Discover, Search, Mine
        )

        fun findByPosition(position: Int): NaviTab? {
            return ALL.getOrNull(position)
        }

        fun findByName(name: String): NaviTab? {
            return ALL.find { it.id == name }
        }
    }
}
