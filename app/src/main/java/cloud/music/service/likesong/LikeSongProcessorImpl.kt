package cloud.music.service.likesong

import android.app.Activity
import cloud.music.catalog.CloudLibrary
import top.wangchenyan.common.model.CommonResult
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LikeSongProcessorImpl @Inject constructor() : LikeSongProcessor {
    override fun init() = Unit
    override fun updateLikeSongList() = Unit
    override fun isLiked(id: Long): Boolean = CloudLibrary.liked(id.toString())
    override suspend fun like(activity: Activity, id: Long): CommonResult<Unit> {
        CloudLibrary.toggleLike(id.toString())
        return CommonResult.success(Unit)
    }
}
