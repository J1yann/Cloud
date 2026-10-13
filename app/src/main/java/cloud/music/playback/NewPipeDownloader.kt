package cloud.music.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** HTTP adapter for TeamNewPipe/NewPipeExtractor, using its public Downloader API. */
object NewPipeDownloader : Downloader() {
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val MAX_RESPONSE_BYTES = 16L * 1024 * 1024
    private val initialized = AtomicBoolean(false)
    private val currentScope = ThreadLocal<ExtractionScope?>()
    private val cancellationWatcher = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "cloud-extraction-cancellation").apply { isDaemon = true }
    }
    internal val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            if (chain.request().url.scheme != "https") {
                throw IOException("Only secure media requests are allowed")
            }
            chain.proceed(chain.request())
        }
        .build()

    @Synchronized
    fun ensureInitialized() {
        if (!initialized.get()) {
            NewPipe.init(this, Localization("en", "US"))
            initialized.set(true)
        }
    }

    /** Runs extraction off the UI thread and cancels its own HTTP calls when its caller leaves. */
    suspend fun <T> extract(block: () -> T): T = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val scope = ExtractionScope()
            continuation.invokeOnCancellation { scope.cancel() }
            try {
                val result = scoped(scope, block)
                continuation.resume(result)
            } catch (failure: Exception) {
                continuation.resumeWithException(failure)
            }
        }
    }

    internal fun <T> bounded(block: () -> T): T = scoped(ExtractionScope(), block)

    private fun <T> scoped(scope: ExtractionScope, block: () -> T): T {
        ensureInitialized()
        val previous = currentScope.get()
        currentScope.set(scope)
        return try {
            scope.checkActive()
            val result = block()
            scope.checkActive()
            result
        } finally {
            scope.cancel()
            currentScope.set(previous)
        }
    }

    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val body = request.dataToSend()?.toRequestBody()
            ?: if (method == "POST" || method == "PUT" || method == "PATCH") {
                ByteArray(0).toRequestBody()
            } else null
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .header("User-Agent", USER_AGENT)
            .method(method, body)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        return executeCall(builder.build()) { response ->
            if (response.code == 429) {
                throw ReCaptchaException("YouTube is limiting requests. Try again later.", "https://www.youtube.com/")
            }
            val responseBody = response.body
            if ((responseBody?.contentLength() ?: 0) > MAX_RESPONSE_BYTES) {
                throw IOException("The metadata response is too large")
            }
            val buffer = okio.Buffer()
            val source = responseBody?.source()
            if (source != null) {
                while (buffer.size <= MAX_RESPONSE_BYTES) {
                    val read = source.read(buffer, minOf(8192L, MAX_RESPONSE_BYTES + 1 - buffer.size))
                    if (read == -1L) break
                }
            }
            if (buffer.size > MAX_RESPONSE_BYTES) throw IOException("The metadata response is too large")
            val bytes = buffer.readByteArray()
            Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                bytes.toString(responseBody?.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8),
                response.request.url.toString()
            )
        }
    }

    internal fun <T> executeCall(request: okhttp3.Request, block: (okhttp3.Response) -> T): T {
        if (request.url.scheme != "https" || request.url.username.isNotEmpty() || request.url.password.isNotEmpty()) {
            throw IOException("Invalid secure media address")
        }
        val scope = currentScope.get()
        scope?.checkActive()
        val call = client.newCall(request)
        scope?.register(call)
        if (scope != null) call.timeout().timeout(scope.remainingMillis(), TimeUnit.MILLISECONDS)
        return try {
            call.execute().use(block)
        } finally {
            scope?.calls?.remove(call)
        }
    }

    private class ExtractionScope {
        val calls = ConcurrentHashMap.newKeySet<Call>()
        private val cancelled = AtomicBoolean(false)
        private val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
        private val ownerThread = Thread.currentThread()
        // Media3 cancels its loader by interrupting its thread; cancel the blocking HTTP call too.
        private val watch = cancellationWatcher.scheduleAtFixedRate({
            if (ownerThread.isInterrupted || System.nanoTime() >= deadlineNanos) cancel()
        }, 100, 100, TimeUnit.MILLISECONDS)
        fun checkActive() {
            if (cancelled.get() || Thread.currentThread().isInterrupted || System.nanoTime() >= deadlineNanos) {
                throw InterruptedIOException("Media request cancelled or timed out")
            }
        }
        fun remainingMillis(): Long {
            checkActive()
            return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()).coerceIn(1, 25_000)
        }
        fun register(call: Call) {
            calls.add(call)
            if (cancelled.get()) call.cancel()
            checkActive()
        }
        fun cancel() {
            watch.cancel(false)
            cancelled.set(true)
            calls.forEach { it.cancel() }
        }
    }
}
