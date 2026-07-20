package com.nrknithin.aayt.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal direct client for YouTube's internal Innertube API. Used for the
 * account-specific data NewPipe deliberately omits (subscriptions feed, account
 * name), authenticated via [YouTubeAuth]. Blocking — call from a background
 * dispatcher.
 */
object InnertubeClient {

    private const val INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
    private const val CLIENT_VERSION = "2.20240814.00.00"
    private const val WEB_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"

    private val http = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun context(): JSONObject = JSONObject().apply {
        put("client", JSONObject().apply {
            put("clientName", "WEB")
            put("clientVersion", CLIENT_VERSION)
            put("hl", "en")
            put("gl", "US")
        })
    }

    private fun post(endpoint: String, extra: JSONObject.() -> Unit = {}): JSONObject {
        val body = JSONObject().apply {
            put("context", JSONObject().put("client", context().getJSONObject("client")))
            extra()
        }.toString()

        val nowSeconds = System.currentTimeMillis() / 1000
        val builder = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/$endpoint?key=$INNERTUBE_KEY&prettyPrint=false")
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("User-Agent", WEB_UA)
            .header("Content-Type", "application/json")
            .header("X-Youtube-Client-Name", "1")
            .header("X-Youtube-Client-Version", CLIENT_VERSION)
        val auth = YouTubeAuth.authHeaders(nowSeconds)
        if (auth.isEmpty()) {
            // Anonymous: pre-accept the consent gate so the API returns data
            // instead of a consent interstitial.
            builder.header("Cookie", "SOCS=CAISAiAD; CONSENT=YES+1")
        } else {
            auth.forEach { (name, values) -> values.forEach { builder.addHeader(name, it) } }
        }

        http.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("Innertube HTTP ${resp.code}: ${text.take(160)}")
            return JSONObject(text)
        }
    }

    /** The home feed — personalized when authenticated, popular otherwise. */
    fun homeFeed(): List<VideoItem> = post("browse") { put("browseId", "FEwhat_to_watch") }
        .let { collectVideos(it) }

    /** Search results (videos only). */
    fun search(query: String): List<VideoItem> = post("search") { put("query", query) }
        .let { collectVideos(it) }

    /** Recent videos from the account's subscriptions (requires sign-in). */
    fun subscriptionsFeed(): List<VideoItem> = post("browse") { put("browseId", "FEsubscriptions") }
        .let { collectVideos(it) }

    /** The signed-in account's display name, or null if signed out. */
    fun accountName(): String? {
        val json = runCatching { post("account/account_menu") }.getOrNull() ?: return null
        return findAccountName(json)
    }

    // --- parsing ---------------------------------------------------------

    private fun collectVideos(root: JSONObject): List<VideoItem> {
        val out = LinkedHashMap<String, VideoItem>()
        walk(root) { obj ->
            obj.optJSONObject("videoRenderer")?.let { vr -> parseVideoRenderer(vr)?.let { out[it.id] = it } }
            obj.optJSONObject("gridVideoRenderer")?.let { vr -> parseVideoRenderer(vr)?.let { out[it.id] = it } }
            obj.optJSONObject("compactVideoRenderer")?.let { vr -> parseVideoRenderer(vr)?.let { out[it.id] = it } }
            // YouTube's newer feed format.
            obj.optJSONObject("lockupViewModel")?.let { vm -> parseLockup(vm)?.let { out[it.id] = it } }
        }
        return out.values.toList()
    }

    private fun parseLockup(vm: JSONObject): VideoItem? {
        // Skip Shorts and non-video lockups.
        val contentType = vm.optString("contentType")
        if (contentType.contains("SHORTS", ignoreCase = true)) return null
        val id = vm.optString("contentId").takeIf { it.isNotBlank() && it.length == 11 } ?: return null

        val meta = vm.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val title = meta?.optJSONObject("title")?.optString("content")?.takeIf { it.isNotBlank() } ?: return null

        val channel = meta.optJSONObject("metadata")
            ?.optJSONObject("contentMetadataViewModel")
            ?.optJSONArray("metadataRows")?.let { rows ->
                for (i in 0 until rows.length()) {
                    val parts = rows.optJSONObject(i)?.optJSONArray("metadataParts")
                    if (parts != null) for (j in 0 until parts.length()) {
                        val t = parts.optJSONObject(j)?.optJSONObject("text")?.optString("content")
                        if (!t.isNullOrBlank()) return@let t
                    }
                }
                null
            }.orEmpty()

        val thumbVm = vm.optJSONObject("contentImage")?.optJSONObject("thumbnailViewModel")
        val thumb = thumbVm?.optJSONObject("image")?.optJSONArray("sources")
            ?.let { s -> if (s.length() > 0) s.getJSONObject(s.length() - 1).optString("url") else null }

        val duration = thumbVm?.optJSONArray("overlays")?.let { overlays ->
            var found: String? = null
            walk(overlays) { o ->
                if (found == null) {
                    o.optJSONObject("thumbnailBadgeViewModel")?.optString("text")
                        ?.takeIf { it.matches(Regex("\\d+(:\\d{2})+")) }?.let { found = it }
                }
            }
            found
        }

        return VideoItem(id, title, channel, thumb, duration)
    }

    private fun parseVideoRenderer(vr: JSONObject): VideoItem? {
        val id = vr.optString("videoId").takeIf { it.isNotBlank() } ?: return null
        val title = text(vr.opt("title")) ?: return null
        val channel = text(vr.opt("longBylineText"))
            ?: text(vr.opt("ownerText"))
            ?: text(vr.opt("shortBylineText"))
            ?: ""
        val thumb = vr.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            ?.let { arr -> if (arr.length() > 0) arr.getJSONObject(arr.length() - 1).optString("url") else null }
        val duration = text(vr.opt("lengthText"))
        return VideoItem(id, title, channel, thumb, duration)
    }

    private fun text(node: Any?): String? {
        if (node !is JSONObject) return null
        node.optString("simpleText").takeIf { it.isNotBlank() }?.let { return it }
        node.optJSONArray("runs")?.let { runs ->
            val sb = StringBuilder()
            for (i in 0 until runs.length()) sb.append(runs.getJSONObject(i).optString("text"))
            return sb.toString().takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun findAccountName(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                if (node.has("accountName")) text(node.opt("accountName"))?.let { return it }
                // account_menu nests the name in activeAccountHeaderRenderer.accountName
                node.keys().forEach { k -> findAccountName(node.get(k))?.let { return it } }
            }
            is JSONArray -> for (i in 0 until node.length()) findAccountName(node.get(i))?.let { return it }
        }
        return null
    }

    private fun walk(node: Any?, onObject: (JSONObject) -> Unit) {
        when (node) {
            is JSONObject -> {
                onObject(node)
                node.keys().forEach { k -> walk(node.get(k), onObject) }
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.get(i), onObject)
        }
    }
}
