package cloud.music.mine.home

import cloud.music.catalog.CloudBrowseFragment
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MineFragment : CloudBrowseFragment() {
    override val libraryOnly = true
}
