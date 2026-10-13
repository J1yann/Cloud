package cloud.music.lyrics

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ReplacementSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.NestedScrollView

/** Lyrics follow the actual player position, including pause and backward seeking. */
class KaraokeLyricsView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : NestedScrollView(context, attrs) {
    private data class Row(val line: LyricLine, val view: TextView, val highlights: List<WordFill>)
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val rows = mutableListOf<Row>()
    private var activeRow = -1
    private var playing = false
    private var lastManualScroll = 0L
    var positionProvider: (() -> Long)? = null
    var onLineClick: ((Long) -> Unit)? = null
    private val tick = object : Runnable {
        override fun run() {
            if (!playing || !isShown || !isAttachedToWindow || windowVisibility != View.VISIBLE) return
            positionProvider?.let { updatePosition(it()) }
            postDelayed(this, 40)
        }
    }

    init {
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        isFillViewport = true
        clipToPadding = false
        overScrollMode = View.OVER_SCROLL_NEVER
        content.setPadding(dp(4), dp(56), dp(4), dp(120))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    fun showMessage(message: String) {
        reset()
        content.addView(textView().apply {
            text = message
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
            setTextColor(0xCCFFFFFF.toInt())
        })
    }

    fun showPlain(text: String) {
        reset()
        text.lineSequence().filter { it.isNotBlank() }.take(500).forEach {
            content.addView(textView().apply { this.text = it; setTextColor(0xE6FFFFFF.toInt()) })
        }
    }

    fun showLines(lines: List<LyricLine>) {
        reset()
        lines.take(500).forEach { line ->
            val text = SpannableString(line.text)
            val spans = line.words.mapNotNull { word ->
                if (word.from < 0 || word.to > text.length || word.from >= word.to || word.endMs <= word.startMs) return@mapNotNull null
                WordFill(word).also { text.setSpan(it, word.from, word.to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            }
            val view = textView().apply {
                this.text = text
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    lastManualScroll = 0L
                    onLineClick?.invoke(line.startMs)
                    updatePosition(line.startMs)
                }
            }
            rows.add(Row(line, view, spans))
            content.addView(view)
        }
        post { positionProvider?.let { updatePosition(it()) } }
    }

    private fun reset() {
        rows.clear()
        activeRow = -1
        content.removeAllViews()
        scrollTo(0, 0)
    }

    private fun textView() = TextView(context).apply {
        layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        setTextColor(0x73FFFFFF)
        setLineSpacing(dp(4).toFloat(), 1.08f)
        setPadding(dp(8), dp(12), dp(8), dp(12))
        minHeight = dp(48)
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }

    fun updatePosition(positionMs: Long) {
        if (rows.isEmpty()) return
        val index = rows.indexOfLast { it.line.startMs <= positionMs }
        if (index != activeRow) {
            rows.getOrNull(activeRow)?.let { old ->
                old.highlights.forEach { it.active = false; it.fill = 0f }
                old.view.invalidate()
            }
            rows.getOrNull(activeRow)?.view?.apply {
                animate().cancel()
                setTextColor(0x73FFFFFF)
                animate().alpha(0.72f).scaleX(1f).scaleY(1f).setDuration(250).start()
            }
            activeRow = index
            rows.getOrNull(index)?.view?.apply {
                setTextColor(Color.WHITE)
                animate().alpha(1f).scaleX(1.015f).scaleY(1.015f).setDuration(250).start()
                if (SystemClock.uptimeMillis() - lastManualScroll > 3_000) {
                    post { smoothScrollTo(0, (top - height / 4 - this@KaraokeLyricsView.height / 3).coerceAtLeast(0)) }
                }
            }
        }
        // Only the current row needs animation work; previous spans reset at a line change.
        rows.getOrNull(index)?.let { row ->
            var changed = false
            row.highlights.forEach { span ->
                val fill = ((positionMs - span.word.startMs).toFloat() /
                    (span.word.endMs - span.word.startMs)).coerceIn(0f, 1f)
                if (span.fill != fill || !span.active) {
                    span.fill = fill
                    span.active = true
                    changed = true
                }
            }
            if (changed) row.view.invalidate()
        }
    }

    fun setPlaying(value: Boolean) {
        playing = value
        removeCallbacks(tick)
        if (value && isShown && windowVisibility == View.VISIBLE) post(tick)
        else positionProvider?.let { updatePosition(it()) }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_MOVE) {
            lastManualScroll = SystemClock.uptimeMillis()
        }
        return super.onTouchEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) lastManualScroll = SystemClock.uptimeMillis()
        return super.onInterceptTouchEvent(event)
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // View's constructor can call this before Kotlin fields have been initialized.
        if (visibility == View.VISIBLE) post { if (playing && isShown) { removeCallbacks(tick); post(tick) } }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // Defer initialization-sensitive callbacks until after construction.
        post {
            removeCallbacks(tick)
            if (visibility == View.VISIBLE && playing && isShown) post(tick)
        }
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private class WordFill(val word: LyricWord) : ReplacementSpan() {
        var fill = 0f
        var active = false
        override fun getSize(paint: Paint, text: CharSequence, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            fm?.let { paint.getFontMetricsInt(it) }
            return kotlin.math.ceil(paint.measureText(text, start, end).toDouble()).toInt()
        }
        override fun draw(canvas: Canvas, text: CharSequence, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val savedColor = paint.color
            val width = paint.measureText(text, start, end)
            paint.color = if (active) 0x66FFFFFF else 0x73FFFFFF
            canvas.drawText(text, start, end, x, y.toFloat(), paint)
            if (active && fill > 0f) {
                val saved = canvas.save()
                canvas.clipRect(x, top.toFloat(), x + width * fill, bottom.toFloat())
                paint.color = Color.WHITE
                canvas.drawText(text, start, end, x, y.toFloat(), paint)
                canvas.restoreToCount(saved)
            }
            paint.color = savedColor
        }
    }
}
