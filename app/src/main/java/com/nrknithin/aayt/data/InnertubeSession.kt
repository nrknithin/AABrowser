package com.nrknithin.aayt.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds the live Innertube client version and a visitor-data token, scraped once
 * from the YouTube homepage. Without a current client version + visitor token,
 * the API returns empty results — this keeps them fresh instead of hardcoding a
 * version that goes stale.
 */
object InnertubeSession {

    // Fallbacks used until the homepage fetch succeeds.
    @Volatile
    private var clientVersion: String = "2.20240814.00.00"

    @Volatile
    private var visitorData: String? = null

    private val loaded = AtomicBoolean(false)
    private val http = OkHttpClient()

    private const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    fun clientVersion(): String = clientVersion
    fun visitorData(): String? = visitorData

    /** Blocking; call from a background dispatcher. Fetches once, retries on failure. */
    fun ensureLoaded() {
        if (!loaded.compareAndSet(false, true)) return
        val ok = runCatching {
            val req = Request.Builder()
                .url("https://www.youtube.com/")
                .header("User-Agent", WEB_UA)
                .header("Cookie", "SOCS=CAISAiAD; CONSENT=YES+1")
                .build()
            http.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                Regex("\"INNERTUBE_CONTEXT_CLIENT_VERSION\":\"([^\"]+)\"").find(html)
                    ?.groupValues?.get(1)?.let { clientVersion = it }
                Regex("\"visitorData\":\"([^\"]+)\"").find(html)
                    ?.groupValues?.get(1)?.let { visitorData = it }
            }
            visitorData != null
        }.getOrDefault(false)
        if (!ok) loaded.set(false) // allow another attempt next call
    }
}
