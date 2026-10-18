package cloud.music.catalog

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import cloud.music.R
import top.wangchenyan.common.ext.load

/** A swipeable discovery feature backed by the same tracks shown in Trending. */
class CloudFeaturedHero(
    context: Context,
    private val onDiscover: () -> Unit,
    private val onPlay: (CloudTrack) -> Unit,
) : LinearLayout(context) {
    private val rail = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val pager = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isFillViewport = true
        addView(rail, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }
    private val dots = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private var pageWidth = 0
    private var currentPage = 0

    init {
        orientation = VERTICAL
        addView(pager, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(dots, LayoutParams(LayoutParams.MATCH_PARENT, dp(24)))
        pager.setOnScrollChangeListener { _, x, _, _, _ ->
            if (pageWidth > 0) selectPage(((x + pageWidth / 2) / pageWidth).coerceIn(0, (rail.childCount - 1).coerceAtLeast(0)))
        }
        submit(emptyList())
    }

    fun submit(tracks: List<CloudTrack>) {
        rail.removeAllViews()
        dots.removeAllViews()
        val featured = tracks.filter { it.allowsPlayback && it.artwork.isNotBlank() }.distinctBy { it.id }.take(4)
        if (featured.isEmpty()) addCard(null) else featured.forEach(::addCard)
        dots.visibility = if (rail.childCount > 1) View.VISIBLE else View.GONE
        repeat(rail.childCount) { index ->
            dots.addView(View(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background = rounded(if (index == 0) 0xff8ab9d0.toInt() else 0xff435764.toInt(), 4)
            }, LayoutParams(dp(6), dp(6)).apply { marginStart = dp(4); marginEnd = dp(4) })
        }
        currentPage = 0
        pager.scrollTo(0, 0)
        resizeCards(width)
    }

    private fun addCard(track: CloudTrack?) {
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            background = rounded(0xff10212e.toInt(), 24)
            clipToOutline = true
        }
        val visual = FrameLayout(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xff244e64.toInt(), 0xff132532.toInt(), 0xff10212e.toInt()))
        }
        if (track != null) {
            visual.addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                load(track.artwork)
            }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        }
        visual.addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x3308141e, 0x4008141e, 0xf010212e.toInt()))
        }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        visual.addView(text(context.getString(R.string.hero_eyebrow), 10f, 0xffd9edf7.toInt(), true).apply {
            letterSpacing = 0.12f
            setShadowLayer(dp(3).toFloat(), 0f, 0f, Color.BLACK)
        }, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(dp(24), dp(24), dp(24), 0)
        })
        val headline = LinearLayout(context).apply {
            orientation = VERTICAL
            addView(text(context.getString(R.string.hero_title), 32f, Color.WHITE, true).apply {
                maxLines = 3
                setLineSpacing(0f, 1.02f)
            })
            addView(text(context.getString(R.string.hero_subtitle), 13f, 0xffdce9f1.toInt()).apply {
                setPadding(0, dp(8), 0, 0)
            })
        }
        visual.addView(headline, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(24), dp(64), dp(24), dp(24))
        })
        card.addView(visual, LayoutParams(LayoutParams.MATCH_PARENT,
            dp(300 + ((resources.configuration.fontScale.coerceAtLeast(1f) - 1f) * 80).toInt())))
        val panel = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(0xff10212e.toInt())
        }
        panel.addView(ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(0xff213e50.toInt(), 12)
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setImageResource(R.drawable.ic_default_cover)
            if (track != null) load(track.artwork, dp(12))
        }, LayoutParams(dp(52), dp(52)))
        panel.addView(LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(12), 0, dp(10), 0)
            addView(text(track?.title ?: context.getString(R.string.hero_placeholder_title), 15f, Color.WHITE, true).apply {
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(text(track?.artist ?: context.getString(R.string.hero_placeholder_artist), 12f, 0xffbacbd7.toInt()).apply {
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(0, dp(4), 0, 0)
            })
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(text(context.getString(if (track == null) R.string.hero_discover else R.string.hero_play), 13f, 0xff10212e.toInt(), true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            minWidth = dp(68)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = RippleDrawable(ColorStateList.valueOf(0x28000000), rounded(0xffb8dcf2.toInt(), 24), null)
            isFocusable = true
            contentDescription = if (track == null) context.getString(R.string.hero_discover)
                else context.getString(R.string.hero_play_track, track.title, track.artist)
            setOnClickListener { if (track == null) onDiscover() else onPlay(track) }
        }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        card.addView(panel)
        rail.addView(card, LayoutParams(if (pageWidth > 0) pageWidth - dp(12) else dp(300), LayoutParams.WRAP_CONTENT).apply {
            marginEnd = dp(12)
        })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) resizeCards(w)
    }

    private fun resizeCards(viewport: Int) {
        if (viewport <= 0) return
        pageWidth = viewport
        for (index in 0 until rail.childCount) {
            val card = rail.getChildAt(index)
            card.layoutParams = (card.layoutParams as LayoutParams).apply {
                width = if (rail.childCount == 1) viewport else viewport - dp(12)
                marginEnd = if (rail.childCount == 1) 0 else dp(12)
            }
        }
        pager.post { pager.scrollTo(currentPage * pageWidth, 0) }
    }

    private fun selectPage(page: Int) {
        if (currentPage == page) return
        currentPage = page
        for (index in 0 until dots.childCount) {
            dots.getChildAt(index).background = rounded(if (index == page) 0xff8ab9d0.toInt() else 0xff435764.toInt(), 4)
        }
    }

    private fun text(value: String, size: Float, tint: Int, bold: Boolean = false) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(tint)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
    }

    private fun rounded(tint: Int, radius: Int) = GradientDrawable().apply {
        setColor(tint)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
