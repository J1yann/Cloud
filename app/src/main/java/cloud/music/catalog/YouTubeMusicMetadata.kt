package cloud.music.catalog

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import top.wangchenyan.common.CommonApp
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device YouTube Music catalogue. Playback extraction is deliberately separate.
 * Uses Innertube WEB_REMIX requests and an original structured-renderer parser.
 */
class YouTubeMusicMetadata {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).build()
    private val configMutex = Mutex()
    private val cacheMutex = Mutex()
    private var configExpires = 0L
    private var clientVersion = "1.20261011.16.00"
    private var visitorData = ""
    private val cache = LinkedHashMap<String, Pair<Long, JsonObject>>()
    private val parser = YouTubeMusicParser()

    suspend fun searchTracks(query: String, next: String? = null): CloudPage<CloudTrack> =
        parser.tracks(search(query, SONGS, next))
    suspend fun searchArtists(query: String, next: String? = null): CloudPage<CloudArtist> =
        parser.artists(search(query, ARTISTS, next))
    suspend fun searchPlaylists(query: String, next: String? = null): CloudPage<CloudPlaylist> =
        parser.playlists(search(query, PLAYLISTS, next))
    suspend fun searchAlbums(query: String, next: String? = null): CloudPage<CloudPlaylist> =
        parser.playlists(search(query, ALBUMS, next))

    suspend fun home(): CloudPage<CloudSection> = parser.sections(browse("FEmusic_home"))
    suspend fun trending(next: String? = null): CloudPage<CloudTrack> {
        if (next != null) return parser.tracks(browse("FEmusic_charts", next))
        val charts = browse("FEmusic_charts")
        val direct = parser.tracks(charts)
        if (direct.items.isNotEmpty()) return direct
        // Anonymous charts currently expose their songs through playlist cards, not inline rows.
        val playlists = parser.playlists(charts).items
        val chart = playlists.firstOrNull { it.title.contains("Trending", ignoreCase = true) }
            ?: playlists.firstOrNull() ?: throw IOException("YouTube Music has no charts available right now.")
        return parser.tracks(browse(chart.id))
    }

    suspend fun artistTracks(id: String, next: String? = null): CloudPage<CloudTrack> {
        if (next != null) return parser.tracks(browse(id, next))
        val artist = browse(validBrowseId(id))
        val songs = parser.shelfEndpoint(artist, "Top songs") ?: parser.shelfEndpoint(artist, "Songs")
        return parser.tracks(if (songs == null) artist else request("browse", songs))
    }

    suspend fun artistAlbums(id: String, next: String? = null): CloudPage<CloudPlaylist> {
        val artist = browse(validBrowseId(id), next)
        if (next != null) return parser.playlists(artist)
        val albums = parser.shelfEndpoint(artist, "Albums")
        return parser.playlists(if (albums == null) parser.shelfData(artist, "Albums") ?: artist else request("browse", albums))
            .let { page -> CloudPage(page.items.filter { it.isAlbum }, page.next) }
    }

    suspend fun related(videoId: String, next: String? = null): CloudPage<CloudTrack> {
        requireVideoId(videoId)
        if (next != null) return parser.tracks(request("browse", obj("continuation" to next)))
        val queue = request("next", obj(
            "videoId" to videoId, "playlistId" to "RDAMVM$videoId", "params" to "wAEB",
            "isAudioOnly" to true, "enablePersistentPlaylistPanel" to true,
        ))
        val related = parser.relatedEndpoint(queue)
        val page = parser.tracks(if (related != null) request("browse", related) else queue)
        return CloudPage(page.items.filter { it.id != videoId }, if (related != null) page.next else null)
    }

    suspend fun playlistTracks(item: CloudPlaylist): List<CloudTrack> {
        val id = validBrowseId(item.id).let {
            if (it.startsWith("VL") || item.isAlbum || it.startsWith("MPRE")) it else "VL$it"
        }
        val tracks = mutableListOf<CloudTrack>()
        var response = browse(id)
        val seenTokens = mutableSetOf<String>()
        // Large playlists are fetched in order. Bound work to keep a malicious continuation finite.
        repeat(20) {
            val page = parser.tracks(response)
            tracks.addAll(page.items.map { track ->
                if (item.isAlbum && track.album.isBlank()) track.copy(album = item.title) else track
            })
            val next = page.next ?: return tracks
            if (!seenTokens.add(next)) throw IOException("YouTube Music repeated a playlist page. Try again.")
            response = browse(id, next)
        }
        throw IOException("This playlist is too large to load at once. Try a smaller playlist.")
    }

    suspend fun suggestions(query: String): List<String> =
        parser.suggestions(request("music/get_search_suggestions", obj("input" to query.trim())))

    private suspend fun search(query: String, filter: String, next: String?): JsonObject {
        require(query.isNotBlank()) { "Enter a search term" }
        return request("search", if (next == null) obj("query" to query.trim(), "params" to filter)
            else obj("continuation" to next))
    }

    private suspend fun browse(id: String, next: String? = null): JsonObject = request(
        "browse", if (next == null) obj("browseId" to id) else obj("continuation" to next),
    )

    private suspend fun request(endpoint: String, body: JsonObject): JsonObject {
        val key = "$endpoint:${body}"
        cacheMutex.withLock {
            cache[key]?.let { (expires, data) -> if (System.nanoTime() < expires) return data }
        }
        val disk = withContext(Dispatchers.IO) { readDisk(key) }
        if (disk != null && System.currentTimeMillis() - disk.first < CACHE_MILLIS) {
            remember(key, disk.second)
            return disk.second
        }
        try {
            refreshConfig()
            for (attempt in 0..1) {
                val context = obj("clientName" to "WEB_REMIX", "clientVersion" to clientVersion,
                    "hl" to "en", "gl" to "US", "userAgent" to USER_AGENT)
                if (visitorData.isNotBlank()) context.addProperty("visitorData", visitorData)
                val payload = body.deepCopy().apply { add("context", JsonObject().apply { add("client", context) }) }
                val builder = Request.Builder().url("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .header("X-Youtube-Client-Name", "67").header("X-Youtube-Client-Version", clientVersion)
                if (visitorData.isNotBlank()) builder.header("X-Goog-Visitor-Id", visitorData)
                val (status, text) = exchange(headers(builder).build())
                if ((status == 401 || status == 403) && attempt == 0) {
                    configMutex.withLock { configExpires = 0 }
                    refreshConfig()
                    continue
                }
                if (status !in 200..299) throw IOException(when (status) {
                    403 -> "YouTube Music access was refused. Check your connection or try again later."
                    429 -> "YouTube Music is receiving too many requests. Try again later."
                    else -> "YouTube Music could not load ($status). Check your connection and try again."
                })
                val data = try { JsonParser.parseString(text).takeIf { it.isJsonObject }?.asJsonObject }
                    catch (e: Exception) { throw IOException("YouTube Music returned an unreadable response. Try again.", e) }
                    ?: throw IOException("YouTube Music returned an empty response. Try again.")
                data.getAsJsonObject("error")?.let {
                    throw IOException("YouTube Music: ${it.get("message")?.asString ?: "request failed"}")
                }
                if (!parser.hasContent(data)) throw IOException("YouTube Music returned no catalogue data. Try again.")
                remember(key, data)
                withContext(Dispatchers.IO) { writeDisk(key, data) }
                return data
            }
            throw IOException("YouTube Music access expired. Try again.")
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Cached catalogue data remains useful offline; never cache or reuse playback URLs here.
            if (disk != null && System.currentTimeMillis() - disk.first < STALE_MILLIS) return disk.second
            throw IOException(e.message ?: "Cannot reach YouTube Music. Check your connection.", e)
        }
    }

    private suspend fun refreshConfig() = configMutex.withLock {
        if (System.nanoTime() < configExpires) return@withLock
        try {
            val (status, html) = exchange(headers(Request.Builder().url("https://music.youtube.com/")).build())
            if (status == 200) {
                Regex("\"INNERTUBE_CLIENT_VERSION\"\\s*:\\s*\"([^\"]+)\"").find(html)?.let {
                    clientVersion = it.groupValues[1]
                }
                Regex("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"").find(html)?.let {
                    visitorData = it.groupValues[1]
                }
                configExpires = System.nanoTime() + TimeUnit.HOURS.toNanos(6)
            } else configExpires = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
        } catch (e: IOException) {
            // The music API may be reachable even when the web homepage is unavailable.
            configExpires = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
        }
    }

    private fun headers(builder: Request.Builder): Request.Builder = builder
        .header("User-Agent", USER_AGENT).header("Origin", "https://music.youtube.com")
        .header("Referer", "https://music.youtube.com/").header("Accept-Language", "en-US,en;q=0.9")

    private suspend fun exchange(request: Request): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use { it.code to (it.body?.string() ?: "") }
                    if (!continuation.isCancelled) continuation.resume(result)
                } catch (e: Exception) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
            }
        })
    }

    private suspend fun remember(key: String, data: JsonObject) = cacheMutex.withLock {
        cache.remove(key)
        if (cache.size >= 48) cache.remove(cache.keys.first())
        cache[key] = System.nanoTime() + TimeUnit.MINUTES.toNanos(10) to data
    }

    private fun cacheFile(key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(CommonApp.app.cacheDir, "youtube-music-metadata/$hash.json")
    }

    private fun readDisk(key: String): Pair<Long, JsonObject>? = try {
        val file = cacheFile(key)
        if (file.isFile && System.currentTimeMillis() - file.lastModified() < STALE_MILLIS) {
            file.lastModified() to JsonParser.parseString(file.readText()).asJsonObject
        } else null
    } catch (_: Exception) { null }

    private fun writeDisk(key: String, data: JsonObject) {
        try {
            val file = cacheFile(key)
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.${Thread.currentThread().id}.tmp")
            temporary.writeText(data.toString())
            if (!temporary.renameTo(file)) temporary.delete()
            val files = file.parentFile?.listFiles()?.filter { it.extension == "json" }
                ?.sortedByDescending { it.lastModified() }.orEmpty()
            var bytes = 0L
            files.forEachIndexed { index, cached ->
                bytes += cached.length()
                if (index >= 48 || bytes > 12 * 1024 * 1024) cached.delete()
            }
        } catch (_: IOException) { /* Metadata browsing still works if the disk cache is full. */ }
    }

    private fun validBrowseId(id: String): String {
        require(id.isNotBlank() && id.length <= 512 && id.none { it.isWhitespace() }) { "Invalid YouTube Music ID" }
        return id
    }
    private fun requireVideoId(id: String) = require(Regex("[A-Za-z0-9_-]{11}").matches(id)) { "Invalid video ID" }

    private fun obj(vararg entries: Pair<String, Any>): JsonObject = JsonObject().apply {
        entries.forEach { (key, value) -> when (value) {
            is Boolean -> addProperty(key, value)
            is Number -> addProperty(key, value)
            else -> addProperty(key, value.toString())
        } }
    }

    companion object {
        val instance by lazy { YouTubeMusicMetadata() }
        private const val USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        private const val CACHE_MILLIS = 10 * 60 * 1000L
        private const val STALE_MILLIS = 24 * 60 * 60 * 1000L
        private const val SONGS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        private const val ARTISTS = "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"
        private const val ALBUMS = "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"
        private const val PLAYLISTS = "EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ%3D"
    }
}
