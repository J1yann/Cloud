package cloud.music.lyrics

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

data class LyricWord(val from: Int, val to: Int, val startMs: Long, val endMs: Long)
data class LyricLine(val text: String, val startMs: Long, val endMs: Long, val words: List<LyricWord> = emptyList())

/** Original TTML/LRC readers. Timing is taken from providers, never estimated per word. */
object LyricsTiming {
    private data class Span(val depth: Int, val start: Long?, val end: Long?, val excluded: Boolean)

    fun ttml(data: String): List<LyricLine> {
        if (data.isBlank() || data.length > 1_000_000) return emptyList()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(StringReader(data))
        }
        val lines = mutableListOf<LyricLine>()
        var text: StringBuilder? = null
        var start = 0L
        var end = 0L
        val words = mutableListOf<LyricWord>()
        val spans = mutableListOf<Span>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "p" -> {
                        text = StringBuilder()
                        words.clear()
                        spans.clear()
                        start = time(parser.getAttributeValue(null, "begin")) ?: 0L
                        end = time(parser.getAttributeValue(null, "end")) ?: start
                    }
                    "span" -> if (text != null) {
                        val role = (0 until parser.attributeCount)
                            .firstOrNull { parser.getAttributeName(it) == "role" }
                            ?.let { parser.getAttributeValue(it) }.orEmpty()
                        spans.add(Span(parser.depth, time(parser.getAttributeValue(null, "begin")),
                            time(parser.getAttributeValue(null, "end")),
                            role.contains("translation") || role.contains("x-bg") || role.contains("roman")))
                    }
                    "br" -> text?.append('\n')
                }
                XmlPullParser.TEXT -> text?.let { builder ->
                    if (spans.none { it.excluded }) {
                        val fragment = parser.text
                        val offset = builder.length
                        builder.append(fragment)
                        val timing = spans.lastOrNull { it.start != null && it.end != null }
                        if (timing != null && fragment.isNotBlank()) {
                            words.add(LyricWord(offset, builder.length, timing.start!!, timing.end!!))
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "span" -> spans.removeAll { it.depth == parser.depth }
                    "p" -> {
                        val value = text?.toString().orEmpty()
                        if (value.isNotBlank()) lines.add(LyricLine(value, start, end.coerceAtLeast(start), words.toList()))
                        text = null
                        if (lines.size >= 500) break
                    }
                }
            }
            parser.next()
        }
        return lines.sortedBy { it.startMs }
    }

    fun lrc(data: String): List<LyricLine> {
        val stamp = Regex("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]")
        val offset = Regex("\\[offset:([+-]?\\d+)\\]", RegexOption.IGNORE_CASE)
            .find(data)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val pending = data.lineSequence().flatMap { line ->
            val matches = stamp.findAll(line).toList()
            val content = line.replace(stamp, "").trim()
            matches.asSequence().filter { content.isNotBlank() }.map { match ->
                val ms = match.groupValues[1].toLong() * 60_000 +
                    (match.groupValues[2].toDouble() * 1000).toLong() + offset
                LyricLine(content, ms.coerceAtLeast(0), 0)
            }
        }.take(500).toList().sortedBy { it.startMs }
        return pending.mapIndexed { index, line ->
            line.copy(endMs = pending.getOrNull(index + 1)?.startMs ?: line.startMs + 10_000)
        }
    }

    private fun time(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val raw = value.trim()
        if (raw.endsWith("ms")) return raw.removeSuffix("ms").toDoubleOrNull()?.toLong()
        if (raw.endsWith("s")) return raw.removeSuffix("s").toDoubleOrNull()?.let { (it * 1000).toLong() }
        val parts = raw.split(':').map { it.toDoubleOrNull() ?: return null }
        if (parts.size !in 1..3) return null
        return (parts.fold(0.0) { total, part -> total * 60 + part } * 1000).toLong()
    }
}
