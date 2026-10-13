package cloud.music.lyrics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** BetterLyrics supplies genuine word timing; LRCLIB supplies line/plain fallback. */
object CloudLyrics {
    data class Result(
        val lines: List<LyricLine> = emptyList(), val plain: String = "",
        val instrumental: Boolean = false, val source: String = "LRCLIB",
    )
    private data class Cached(val result: Result, val savedAt: Long)
    private val client = OkHttpClient.Builder().callTimeout(18, TimeUnit.SECONDS).build()
    private val cache = object : LinkedHashMap<String, Cached>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 40
    }

    suspend fun get(
        title: String, artist: String, album: String, durationMs: Long,
        onFallback: (suspend (Result) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {
        if (title.isBlank() || artist.isBlank()) return@withContext Result()
        val key = "$title\u0000$artist\u0000$album\u0000${durationMs / 1000}"
        synchronized(cache) { cache[key] }?.let {
            val lifetime = if (it.result.lines.isNotEmpty() || it.result.plain.isNotBlank()) 6 * 60 * 60_000L else 5 * 60_000L
            if (System.currentTimeMillis() - it.savedAt < lifetime) return@withContext it.result
        }
        val result = coroutineScope {
            val fallback = async {
                safely { lrclib(title, artist, album, durationMs) }?.also { result ->
                    if (result.lines.isNotEmpty() || result.plain.isNotBlank() || result.instrumental) {
                        onFallback?.invoke(result)
                    }
                }
            }
            val rich = safely { betterLyrics(title, artist, durationMs) }
            if (rich != null && rich.lines.isNotEmpty()) {
                fallback.cancel()
                rich
            } else fallback.await() ?: throw IOException("Lyrics providers unavailable")
        }
        synchronized(cache) { cache[key] = Cached(result, System.currentTimeMillis()) }
        result
    }

    private suspend fun safely(block: suspend () -> Result): Result? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) { null }

    private suspend fun betterLyrics(title: String, artist: String, durationMs: Long): Result {
        val url = "https://lyrics-api.boidu.dev/getLyrics".toHttpUrl().newBuilder()
            .addQueryParameter("s", title).addQueryParameter("a", artist)
            .apply { if (durationMs > 0) addQueryParameter("d", (durationMs / 1000).toString()) }.build()
        val data = json(url)
        val lines = LyricsTiming.ttml(data.optString("ttml").takeUnless { it == "null" }.orEmpty())
        return Result(lines = lines, source = "BetterLyrics")
    }

    private suspend fun lrclib(title: String, artist: String, album: String, durationMs: Long): Result {
        val url = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title).addQueryParameter("artist_name", artist)
            .apply {
                if (album.isNotBlank()) addQueryParameter("album_name", album)
                if (durationMs > 0) addQueryParameter("duration", (durationMs / 1000).toString())
            }.build()
        val data = json(url)
        return Result(
            lines = LyricsTiming.lrc(data.optString("syncedLyrics").takeUnless { it == "null" }.orEmpty()),
            plain = data.optString("plainLyrics").takeUnless { it == "null" }.orEmpty(),
            instrumental = data.optBoolean("instrumental"),
        )
    }

    private suspend fun json(url: HttpUrl): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url)
            .header("User-Agent", "Cloud Android (https://github.com/J1yann/Cloud)").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        val body = if (it.code == 404) JSONObject() else {
                            if (!it.isSuccessful) throw IOException("Lyrics unavailable (${it.code})")
                            val source = it.body?.source() ?: throw IOException("Empty lyrics response")
                            if (source.request(1_000_001)) throw IOException("Lyrics response too large")
                            JSONObject(source.readUtf8())
                        }
                        if (continuation.isActive) continuation.resume(body)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }
}
