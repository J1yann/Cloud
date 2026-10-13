package cloud.music.account

import top.wangchenyan.common.CommonApp
import top.wangchenyan.common.storage.IPreferencesFile
import top.wangchenyan.common.storage.PreferencesFile
import cloud.music.account.bean.ProfileData
import cloud.music.consts.PreferenceName

/**
 * Created by wangchenyan.top on 2023/8/28.
 */
object AccountPreference :
    IPreferencesFile by PreferencesFile(CommonApp.app, PreferenceName.ACCOUNT, false) {
    var cookie by IPreferencesFile.StringProperty("cookie", "")
    var profile by IPreferencesFile.ObjectProperty("profile", ProfileData::class.java)
}