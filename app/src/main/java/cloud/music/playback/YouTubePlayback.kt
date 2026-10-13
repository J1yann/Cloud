package cloud.music.playback

import androidx.media3.datasource.DataSpec
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException

/** Resolves a stable video ID on the device; signed CDN URLs are never persisted or logged. */
object YouTubePlayback {
    const val SCHEME = "cloud-youtube"
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")
    private data class CachedStream(val url: String, val validUntilMillis: Long)
    private val streams = object : LinkedHashMap<String, CachedStream>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedStream>?): Boolean = size > 24
    }

    /** Called by ResolvingDataSource on Media3's loader thread, including restored queues. */
    fun resolve(spec: DataSpec): DataSpec {
        if (spec.uri.scheme != SCHEME) return spec
        val id = spec.uri.lastPathSegment.orEmpty()
        if (!videoIdPattern.matches(id)) throw IOException("Invalid YouTube track ID")
        val url = resolveVideo(id)
        return spec.buildUpon()
            .setUri(url)
            .setHttpRequestHeaders(spec.httpRequestHeaders + mapOf("User-Agent" to NewPipeDownloader.USER_AGENT))
            .build()
    }

    @Synchronized
    private fun resolveVideo(id: String): String = NewPipeDownloader.bounded {
        val cached = streams[id]
        if (cached != null && cached.validUntilMillis > System.currentTimeMillis()) {
            if (probe(cached.url) == ProbeResult.PLAYABLE) return@bounded cached.url
        }
        streams.remove(id)
        var wasDenied = false
        // A stale extraction can return expired URLs: re-extract once after HTTP 403/410.
        repeat(2) { attempt ->
            val info = try {
                StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$id")
            } catch (failure: InterruptedIOException) {
                throw failure
            } catch (failure: Exception) {
                // Upstream exception messages can contain signed URLs; keep the surfaced error safe.
                throw IOException("YouTube could not load this track. It may be restricted or temporarily unavailable.")
            }
            if (info.id != id) throw IOException("YouTube returned a different track")
            val candidates = info.audioStreams.filter {
                it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                    (it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL) &&
                    it.format in setOf(MediaFormat.M4A, MediaFormat.WEBMA, MediaFormat.WEBMA_OPUS, MediaFormat.OPUS)
            }.sortedWith(compareBy({ if (it.format == MediaFormat.M4A) 0 else 1 }, { -it.averageBitrate }))
                .distinctBy { it.content }.take(8)
            for (candidate in candidates) {
                val address = candidate.content.toHttpUrlOrNull() ?: continue
                if (address.scheme != "https" || !isMediaHost(address.host)) continue
                if ((address.queryParameter("expire")?.toLongOrNull()?.times(1000) ?: Long.MAX_VALUE) <= System.currentTimeMillis() + 30_000) {
                    wasDenied = true
                    continue
                }
                when (probe(address.toString())) {
                    ProbeResult.PLAYABLE -> {
                        val validUntil = minOf(
                            System.currentTimeMillis() + 5 * 60_000,
                            (address.queryParameter("expire")?.toLongOrNull()?.times(1000) ?: Long.MAX_VALUE) - 30_000
                        )
                        streams[id] = CachedStream(address.toString(), validUntil)
                        return@bounded address.toString()
                    }
                    ProbeResult.DENIED -> wasDenied = true
                    ProbeResult.UNAVAILABLE -> Unit
                }
            }
            if (attempt == 0 && !wasDenied) {
                throw IOException("No playable audio stream is available for this track")
            }
        }
        throw IOException("YouTube denied the audio stream. Try again later or choose another track.")
    }

    private enum class ProbeResult { PLAYABLE, DENIED, UNAVAILABLE }

    private fun probe(url: String): ProbeResult = try {
        val request = okhttp3.Request.Builder().url(url)
            .header("User-Agent", NewPipeDownloader.USER_AGENT)
            .header("Range", "bytes=0-1023")
            .build()
        NewPipeDownloader.executeCall(request) { response ->
            when {
                !isMediaHost(response.request.url.host) -> ProbeResult.UNAVAILABLE
                response.code == 403 || response.code == 410 -> ProbeResult.DENIED
                response.code != 200 && response.code != 206 -> ProbeResult.UNAVAILABLE
                response.header("Content-Type").orEmpty().startsWith("text/") -> ProbeResult.UNAVAILABLE
                response.body?.source()?.readByteArray(1)?.isNotEmpty() == true -> ProbeResult.PLAYABLE
                else -> ProbeResult.UNAVAILABLE
            }
        }
    } catch (failure: SocketTimeoutException) {
        ProbeResult.UNAVAILABLE
    } catch (failure: InterruptedIOException) {
        throw failure
    } catch (failure: IOException) {
        ProbeResult.UNAVAILABLE
    }

    private fun isMediaHost(host: String): Boolean = host == "googlevideo.com" || host.endsWith(".googlevideo.com")
}
