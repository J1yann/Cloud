package cloud.music.lyrics

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** LRCLIB's exact metadata endpoint; lyrics never use a best-guess search result. */
object CloudLyrics {
    data class Result(val synced: String = "", val plain: String = "", val instrumental: Boolean = false)
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val cache = object : LinkedHashMap<String, Result>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Result>?) = size > 40
    }

    suspend fun get(title: String, artist: String, album: String, durationMs: Long): Result {
        if (title.isBlank() || artist.isBlank()) return Result()
        val key = "$title\u0000$artist\u0000$album\u0000${durationMs / 1000}"
        synchronized(cache) { cache[key] }?.let { return it }
        val url = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .apply {
                if (album.isNotBlank()) addQueryParameter("album_name", album)
                if (durationMs > 0) addQueryParameter("duration", (durationMs / 1000).toString())
            }.build()
        val result = suspendCancellableCoroutine<Result> { continuation ->
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
                            val lyrics = if (it.code == 404) Result() else {
                                if (!it.isSuccessful) throw IOException("Lyrics unavailable (${it.code})")
                                val body = JSONObject(it.body?.string().orEmpty())
                                Result(
                                    synced = body.optString("syncedLyrics").takeUnless { text -> text == "null" }.orEmpty(),
                                    plain = body.optString("plainLyrics").takeUnless { text -> text == "null" }.orEmpty(),
                                    instrumental = body.optBoolean("instrumental"),
                                )
                            }
                            if (continuation.isActive) continuation.resume(lyrics)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                }
            })
        }
        synchronized(cache) { cache[key] = result }
        return result
    }
}
