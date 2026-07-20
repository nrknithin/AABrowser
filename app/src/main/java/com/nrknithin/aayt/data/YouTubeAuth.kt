package com.nrknithin.aayt.data

import java.security.MessageDigest

/**
 * Holds the YouTube web-session credentials harvested from a WebView login and
 * derives the headers that make Innertube (NewPipe's API) calls authenticated.
 *
 * The amalgam: the user signs in through the WebView (the only place Google
 * allows it), we read the resulting cookies from Android's CookieManager, and
 * [NewPipeDownloader] attaches them — plus a per-request SAPISIDHASH — to every
 * extractor request so the responses reflect the signed-in account.
 *
 * SAPISIDHASH scheme (as used by the YouTube/Google web client):
 *   hash = SHA1("<unixSeconds> <SAPISID> <origin>")
 *   Authorization: SAPISIDHASH <unixSeconds>_<hash>
 */
object YouTubeAuth {

    private const val ORIGIN = "https://www.youtube.com"

    @Volatile
    private var cookieHeader: String? = null

    @Volatile
    private var sapisid: String? = null

    val isSignedIn: Boolean
        get() = !cookieHeader.isNullOrBlank() && !sapisid.isNullOrBlank()

    /** Called after WebView login with CookieManager.getCookie("https://www.youtube.com"). */
    fun updateFromCookieString(rawCookies: String?) {
        if (rawCookies.isNullOrBlank()) {
            clear()
            return
        }
        cookieHeader = rawCookies
        sapisid = parseCookie(rawCookies, "SAPISID")
            ?: parseCookie(rawCookies, "__Secure-3PAPISID")
    }

    fun clear() {
        cookieHeader = null
        sapisid = null
    }

    /** Auth headers for an Innertube request, or empty if not signed in. */
    fun authHeaders(nowSeconds: Long): Map<String, List<String>> {
        val cookies = cookieHeader
        val sid = sapisid
        if (cookies.isNullOrBlank() || sid.isNullOrBlank()) return emptyMap()

        val hash = sha1("$nowSeconds $sid $ORIGIN")
        return mapOf(
            "Cookie" to listOf(cookies),
            "Authorization" to listOf("SAPISIDHASH ${nowSeconds}_$hash"),
            "X-Origin" to listOf(ORIGIN),
            "Origin" to listOf(ORIGIN),
            "X-Goog-AuthUser" to listOf("0"),
        )
    }

    internal fun parseCookie(cookieString: String, name: String): String? {
        cookieString.split(';').forEach { part ->
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0 && trimmed.substring(0, eq) == name) {
                return trimmed.substring(eq + 1)
            }
        }
        return null
    }

    private fun sha1(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
