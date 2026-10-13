package cloud.music.main

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import androidx.annotation.DrawableRes
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import cloud.music.R
import cloud.music.BuildConfig
import cloud.music.catalog.CloudLibrary
import cloud.music.account.service.UserService
import cloud.music.common.CloudSleepTimerDialog
import cloud.music.common.BaseMusicActivity
import cloud.music.common.configureBrowserInsets
import cloud.music.consts.RoutePath
import cloud.music.databinding.ActivityMainBinding
import cloud.music.databinding.NavigationHeaderBinding
import cloud.music.databinding.TabItemBinding
import cloud.music.service.MusicService
import cloud.music.service.PlayServiceModule
import cloud.music.service.PlayServiceModule.playerController
import cloud.music.utils.QuitTimer
import cloud.music.utils.TimeUtils
import me.wcy.router.CRouter
import top.wangchenyan.common.ext.getColorEx
import top.wangchenyan.common.ext.showConfirmDialog
import top.wangchenyan.common.ext.toast
import top.wangchenyan.common.ext.viewBindings
import top.wangchenyan.common.widget.pager.CustomTabPager
import javax.inject.Inject

/**
 * Created by wangchenyan.top on 2023/8/21.
 */
@AndroidEntryPoint
class MainActivity : BaseMusicActivity() {
    private val viewBinding by viewBindings<ActivityMainBinding>()
    private val quitTimer by lazy {
        QuitTimer(onTimerListener)
    }
    private var timerItem: MenuItem? = null
    private var timerMinutes = 0


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Browsing and settings must remain available even if playback cannot connect.
        setContentView(viewBinding.root)
        configureBrowserInsets()
        CustomTabPager(lifecycle, supportFragmentManager, viewBinding.viewPager).apply {
            NaviTab.ALL.onEach {
                val tabItem = getTabItem(it.icon, it.name)
                addFragment(it.newFragment(), tabItem.root)
            }
            setScrollable(false)
            setup()
        }
        initDrawer()
        parseIntent()
        PlayServiceModule.isPlayerReady.observe(this) { isReady ->
            if (isReady) parseIntent()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseIntent()
    }

    private fun initDrawer() {
        val navigationHeaderBinding = NavigationHeaderBinding.inflate(
            LayoutInflater.from(this),
            viewBinding.navigationView,
            false
        )
        viewBinding.navigationView.addHeaderView(navigationHeaderBinding.root)
        viewBinding.navigationView.setNavigationItemSelectedListener(onMenuSelectListener)
        navigationHeaderBinding.btnCloseDrawer.setOnClickListener {
            viewBinding.drawerLayout.closeDrawers()
        }
        viewBinding.tvDrawerVersion.text = getString(R.string.cloud_drawer_version, BuildConfig.VERSION_NAME)
        lifecycleScope.launch {
            CloudLibrary.revision.collectLatest {
                navigationHeaderBinding.tvLibrarySummary.text = getString(
                    R.string.cloud_drawer_summary,
                    CloudLibrary.likes().size,
                    CloudLibrary.playlists().size,
                )
            }
        }
    }

    fun openDrawer() {
        if (viewBinding.drawerLayout.isDrawerOpen(GravityCompat.START).not()) {
            viewBinding.drawerLayout.openDrawer(GravityCompat.START)
        }
    }

    fun openSearch() {
        viewBinding.viewPager.setCurrentItem(NaviTab.Search.getPosition(), false)
    }

    private fun parseIntent() {
        val intent = intent
        if (intent.hasExtra(MusicService.EXTRA_NOTIFICATION)) {
            // Keep the notification intent pending until a controller is ready.
            if (PlayServiceModule.isPlayerReady.value != true) return
            if (application.playerController().currentSong.value != null) {
                CRouter.with(this).url(RoutePath.PLAYING).start()
            }
            setIntent(Intent())
        }
    }

    private val onMenuSelectListener = object : NavigationView.OnNavigationItemSelectedListener {
        override fun onNavigationItemSelected(item: MenuItem): Boolean {
            viewBinding.drawerLayout.closeDrawers()
            lifecycleScope.launch {
                delay(1000)
                item.isChecked = false
            }
            when (item.itemId) {
                R.id.action_setting -> {
                    CRouter.with(this@MainActivity).url("/settings").start()
                    return true
                }

                R.id.action_timer -> {
                    timerDialog()
                    return true
                }

                R.id.action_exit -> {
                    exitApp()
                    return true
                }

                R.id.action_about -> {
                    startActivity(Intent(this@MainActivity, AboutActivity::class.java))
                    return true
                }
            }
            return false
        }
    }

    private val onTimerListener = object : QuitTimer.OnTimerListener {
        override fun onTick(remain: Long) {
            if (timerItem == null) {
                timerItem = viewBinding.navigationView.menu.findItem(R.id.action_timer)
            }
            val title = getString(R.string.menu_timer)
            timerItem?.title = if (remain == 0L) {
                title
            } else {
                TimeUtils.formatTime("$title(mm:ss)", remain)
            }
        }

        override fun onTimeEnd() {
            exitApp()
        }
    }

    private fun timerDialog() {
        CloudSleepTimerDialog(this, timerMinutes, ::startTimer).show()
    }

    private fun startTimer(minute: Int) {
        timerMinutes = minute
        quitTimer.start((minute * 60 * 1000).toLong())
        if (minute > 0) {
            toast(getString(R.string.timer_set, minute.toString()))
        } else {
            toast(R.string.timer_cancel)
        }
    }


    private fun exitApp() {
        if (PlayServiceModule.isPlayerReady.value == true) application.playerController().stop()
        finish()
    }

    private fun getTabItem(@DrawableRes icon: Int, text: CharSequence): TabItemBinding {
        val binding = TabItemBinding.inflate(layoutInflater, viewBinding.tabBar, true)
        binding.ivIcon.setImageResource(icon)
        binding.tvTitle.text = text
        return binding
    }

    override fun onDestroy() {
        super.onDestroy()
        quitTimer.stop()
    }
}
