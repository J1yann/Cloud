package cloud.music.utils

import top.wangchenyan.common.ext.divide
import top.wangchenyan.common.ext.format
import java.math.RoundingMode

/**
 * Created by wangchenyan.top on 2023/9/21.
 */
object ConvertUtils {

    fun formatPlayCount(num: Long, dot: Int = 0): String {
        return when {
            num < 1000 -> num.toString()
            num < 1000000 -> num.toDouble().divide(1000.0).format(dot, RoundingMode.HALF_DOWN) + "K"
            num < 1000000000 -> num.toDouble().divide(1000000.0).format(dot, RoundingMode.HALF_DOWN) + "M"
            else -> num.toDouble().divide(1000000000.0).format(dot, RoundingMode.HALF_DOWN) + "B"
        }
    }
}