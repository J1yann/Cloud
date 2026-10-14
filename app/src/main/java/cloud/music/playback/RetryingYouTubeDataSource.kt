package cloud.music.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import java.io.IOException

/** Retries only an initial CDN open, never a partially consumed stream or a different recording. */
@OptIn(UnstableApi::class)
class RetryingYouTubeDataSource private constructor(
    private val upstream: DataSource,
) : DataSource by upstream {
    override fun open(dataSpec: DataSpec): Long {
        // Keep the stable original spec, including its exact seek position and requested length.
        val resolved = YouTubePlayback.resolve(dataSpec)
        return try {
            upstream.open(resolved)
        } catch (failure: IOException) {
            if (dataSpec.uri.scheme != YouTubePlayback.SCHEME || Thread.currentThread().isInterrupted ||
                !isExpiredOrDeniedOpen(failure)) throw failure
            closeFailedOpen()
            // resolveFresh ignores the cache and allows one extraction, not another retry loop.
            val fresh = YouTubePlayback.resolveFresh(dataSpec)
            try {
                upstream.open(fresh)
            } catch (retryFailure: IOException) {
                closeFailedOpen()
                throw retryFailure
            }
        }
    }

    private fun closeFailedOpen() {
        try {
            upstream.close()
        } catch (_: IOException) {
            // MusicDataSource resets its selected source in a finally block even if close fails.
        }
    }

    private fun isExpiredOrDeniedOpen(failure: IOException): Boolean {
        val response = failure as? HttpDataSource.InvalidResponseCodeException ?: return false
        return response.type == HttpDataSource.HttpDataSourceException.TYPE_OPEN &&
            (response.responseCode == 403 || response.responseCode == 410)
    }

    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = RetryingYouTubeDataSource(upstreamFactory.createDataSource())
    }
}
