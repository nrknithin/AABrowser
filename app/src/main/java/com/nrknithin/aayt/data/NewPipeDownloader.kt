package com.nrknithin.aayt.data

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/**
 * OkHttp-backed [Downloader] for NewPipeExtractor. Pure-JVM (no Android deps) so
 * it works both at runtime and in unit tests. Handles the ReCaptcha (429) signal
 * the extractor expects.
 */
class NewPipeDownloader private constructor(
    private val client: OkHttpClient
) : Downloader() {

    override fun execute(request: Request): Response {
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBody = dataToSend?.toRequestBody()

        val builder = okhttp3.Request.Builder()
            .method(httpMethod, requestBody)
            .url(url)
            .addHeader("User-Agent", USER_AGENT)

        headers.forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { value -> builder.addHeader(name, value) }
        }

        // Attach the signed-in web session (cookies + SAPISIDHASH) so Innertube
        // responses reflect the user's account. No-op when signed out. Only sent
        // to Google/YouTube hosts so credentials never leak elsewhere.
        if (isGoogleHost(url)) {
            val nowSeconds = System.currentTimeMillis() / 1000
            YouTubeAuth.authHeaders(nowSeconds).forEach { (name, values) ->
                builder.removeHeader(name)
                values.forEach { value -> builder.addHeader(name, value) }
            }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", url)
            }
            val body = response.body?.string()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                body,
                response.request.url.toString()
            )
        }
    }

    private fun isGoogleHost(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
        return host.endsWith("youtube.com") || host.endsWith("google.com") ||
            host.endsWith("googleapis.com") || host.endsWith("googlevideo.com")
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

        @Volatile
        private var instance: NewPipeDownloader? = null

        fun getInstance(): NewPipeDownloader = instance ?: synchronized(this) {
            instance ?: NewPipeDownloader(
                OkHttpClient.Builder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
            ).also { instance = it }
        }
    }
}
