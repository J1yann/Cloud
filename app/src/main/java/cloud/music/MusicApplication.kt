package cloud.music

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.blankj.utilcode.util.ActivityUtils
import androidx.core.content.ContextCompat
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import cloud.music.account.service.UserService
import cloud.music.appwidget.WidgetRepository
import cloud.music.common.DarkModeService
import cloud.music.common.MusicFragmentContainerActivity
import cloud.music.service.MusicService
import cloud.music.service.PlayServiceModule
import cloud.music.service.likesong.LikeSongProcessor
import me.wcy.router.CRouter
import me.wcy.router.RouterClient
import top.wangchenyan.common.CommonApp
import top.wangchenyan.common.ext.findActivity
import javax.inject.Inject

/**
 * 自定义Application
 * Created by wcy on 2015/11/27.
 */
@HiltAndroidApp
class MusicApplication : Application() {
    @Inject
    lateinit var userService: UserService

    @Inject
    lateinit var darkModeService: DarkModeService

    @Inject
    lateinit var likeSongProcessor: LikeSongProcessor

    override fun onCreate() {
        super.onCreate()

        CommonApp.init {
            test = BuildConfig.DEBUG
            isDarkMode = { darkModeService.isDarkMode() }
            titleLayoutConfig {
                isStatusBarDarkFontWhenAuto = { darkModeService.isDarkMode().not() }
                textColorAuto = { R.color.common_text_h1_color }
                textColorBlack = { R.color.common_text_h1_color }
                isTitleCenter = false
            }
            imageLoaderConfig {
                placeholderAvatar = R.drawable.ic_launcher_round
            }
            apiConfig({}) {
                codeJsonNames = listOf("code")
                msgJsonNames = listOf("message", "msg")
                dataJsonNames = listOf("data", "result")
                successCode = 200
            }
        }
        initCRouter()
        darkModeService.init()
        likeSongProcessor.init()

        val sessionToken =
            SessionToken(this, ComponentName(this, MusicService::class.java))
        val mediaControllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        mediaControllerFuture.addListener({
            try {
                val player = mediaControllerFuture.get()
                PlayServiceModule.setPlayer(player)
                WidgetRepository.init(this)
            } catch (error: Exception) {
                // Audio initialization must not prevent Home or appearance settings from opening.
                Log.e("Cloud", "Could not initialize playback", error)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun initCRouter() {
        CRouter.setRouterClient(
            RouterClient.Builder()
                .baseUrl("app://music")
                .loginProvider { context, callback ->
                    var activity = context.findActivity()
                    if (activity == null) {
                        activity = ActivityUtils.getTopActivity()
                    }
                    if (activity != null) {
                        userService.checkLogin(activity) {
                            callback()
                        }
                    }
                }
                .fragmentContainerIntentProvider {
                    Intent(it, MusicFragmentContainerActivity::class.java)
                }
                .build()
        )
    }
}