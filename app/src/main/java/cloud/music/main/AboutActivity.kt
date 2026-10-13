package cloud.music.main

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.blankj.utilcode.util.AppUtils
import cloud.music.R
import cloud.music.common.configureBrowserInsets
import cloud.music.common.applyCloudTheme
import top.wangchenyan.common.ui.activity.BaseActivity

class AboutActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        applyCloudTheme()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        configureBrowserInsets()
        findViewById<View>(R.id.about_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.about_version).text =
            getString(R.string.about_version_label, AppUtils.getAppVersionName())
        findViewById<View>(R.id.about_project).setOnClickListener {
            openUrl(getString(R.string.about_project_url))
        }
        findViewById<View>(R.id.about_share).setOnClickListener { share() }
        findViewById<View>(R.id.about_creator).setOnClickListener {
            openUrl("https://github.com/J1yann")
        }
        findViewById<View>(R.id.about_ponymusic).setOnClickListener {
            openUrl("https://github.com/wangchenyan/ponymusic")
        }
        findViewById<View>(R.id.about_newpipe).setOnClickListener {
            openUrl("https://github.com/TeamNewPipe/NewPipeExtractor")
        }
        findViewById<View>(R.id.about_simpmusic).setOnClickListener {
            openUrl("https://github.com/maxrave-dev/SimpMusic")
        }
        findViewById<View>(R.id.about_lyrics).setOnClickListener {
            openUrl("https://lrclib.net")
        }
        findViewById<View>(R.id.about_youtube).setOnClickListener {
            openUrl("https://music.youtube.com")
        }
    }

    private fun share() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(
                Intent.EXTRA_TEXT,
                getString(R.string.share_app, getString(R.string.app_name), getString(R.string.about_project_url))
            )
        }
        launch(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun openUrl(url: String) {
        launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun launch(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.about_no_handler, Toast.LENGTH_SHORT).show()
        }
    }
}
