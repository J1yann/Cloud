package cloud.music.search

import dagger.hilt.android.AndroidEntryPoint
import cloud.music.consts.RoutePath
import cloud.music.catalog.CloudBrowseFragment
import me.wcy.router.annotation.Route

@Route(RoutePath.SEARCH)
@AndroidEntryPoint
class SearchFragment : CloudBrowseFragment() {
    override val searchOnly = true
}
