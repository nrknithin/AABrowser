package com.nrknithin.aayt.data

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Extracts video IDs, never an arbitrary destination to hand to the WebView. */
object YouTubeLinks {
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val urls = Regex("https?://[^\\s<>\"']+", RegexOption.IGNORE_CASE)
    private val youtubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")

    data class VideoLink(val id: String, val startSeconds: Int = 0) {
        val url: String
            get() = "https://www.youtube.com/watch?v=$id" +
                if (startSeconds > 0) "&t=${startSeconds}s" else ""
    }

    fun isVideoId(value: String): Boolean = videoId.matches(value)

    fun fromSharedText(text: String?): VideoLink? {
        if (text == null || text.length > 16_384) return null
        return urls.findAll(text).mapNotNull { parse(it.value.trimEnd('.', ',', ')', ']', '!', ';')) }.firstOrNull()
    }

    fun parse(value: String): VideoLink? = runCatching {
        if (value.length > 4_096) return null
        val uri = URI(value.trim())
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "https" && scheme != "http") return null
        if (uri.rawUserInfo != null || (uri.port != -1 && uri.port != if (scheme == "https") 443 else 80)) return null
        val host = uri.host?.lowercase() ?: return null
        val query = parameters(uri.rawQuery)
        val path = uri.path.orEmpty().trimEnd('/')
        val id = when {
            host == "youtu.be" -> path.removePrefix("/")
            host in youtubeHosts && path == "/watch" -> query["v"]
            host in youtubeHosts && (path.startsWith("/shorts/") || path.startsWith("/live/") || path.startsWith("/embed/")) ->
                path.substringAfter('/', "").substringAfter('/', "")
            else -> null
        } ?: return null
        if (!isVideoId(id)) return null
        val fragment = parameters(uri.rawFragment)
        VideoLink(id, parseTime(query["t"] ?: query["start"] ?: fragment["t"]))
    }.getOrNull()

    private fun parameters(raw: String?): Map<String, String> = raw.orEmpty().split('&')
        .filter { it.contains('=') }
        .associate { part ->
            URLDecoder.decode(part.substringBefore('='), StandardCharsets.UTF_8.name()) to
                URLDecoder.decode(part.substringAfter('='), StandardCharsets.UTF_8.name())
        }

    private fun parseTime(raw: String?): Int {
        if (raw == null || raw.length > 20) return 0
        raw.toLongOrNull()?.let { return it.coerceIn(0, 604_800).toInt() }
        val match = Regex("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?").matchEntire(raw) ?: return 0
        val values = match.groupValues.drop(1).map { it.toLongOrNull() ?: 0 }
        return (values[0].coerceAtMost(168) * 3600 + values[1].coerceAtMost(10080) * 60 +
            values[2].coerceAtMost(604800)).coerceAtMost(604_800).toInt()
    }
}
