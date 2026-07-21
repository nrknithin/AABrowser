package com.nrknithin.aayt.data

import java.security.MessageDigest

/**
 * Holds the YouTube web-session credentials harvested from a WebView login and
 * derives the headers that make Innertube (NewPipe's API) calls authenticated.
 *
 * The amalgam: the user signs in through the WebView (the only place Google
 * allows it), we read the resulting cookies from Android's CookieManager, and
 * [NewPipeDownloader]/[InnertubeClient] attach them — plus per-request SAPISID
 * hashes — to every request so responses reflect the signed-in account.
 *
 * Google's web client authorizes with up to three hashes derived from whichever
 * of SAPISID / __Secure-1PAPISID / __Secure-3PAPISID are present:
 *   hash = SHA1("<unixSeconds> <cookieValue> <origin>")
 *   Authorization: SAPISIDHASH <s>_<h> SAPISID1PHASH <s>_<h1> SAPISID3PHASH <s>_<h3>
 * Sending all available variants is what makes the pull authenticate reliably —
 * hashing only SAPISID fails when the login set only the __Secure-*PAPISID form.
 */
object YouTubeAuth {

    private const val ORIGIN = "https://www.youtube.com"

    @Volatile
    private var cookieHeader: String? = null

    // (authScheme, cookieValue) pairs for each *APISID variant that is present.
    @Volatile
    private var apiSids: List<Pair<String, String>> = emptyList()

    val isSignedIn: Boolean
        get() = !cookieHeader.isNullOrBlank() && apiSids.isNotEmpty()

    fun debugStatus(): String =
        "signedIn=$isSignedIn · sids=${apiSids.map { it.first }} · cookieChars=${cookieHeader?.length ?: 0}"

    /** Cookie names only (no values) for diagnostics. */
    fun cookieNames(): List<String> =
        cookieHeader?.split(';')?.mapNotNull { it.trim().substringBefore('=').takeIf(String::isNotBlank) }
            ?: emptyList()

    /** Called after WebView login with CookieManager.getCookie("https://www.youtube.com"). */
    fun updateFromCookieString(rawCookies: String?) {
        if (rawCookies.isNullOrBlank()) {
            clear()
            return
        }
        cookieHeader = rawCookies
        apiSids = buildList {
            parseCookie(rawCookies, "SAPISID")?.let { add("SAPISIDHASH" to it) }
            parseCookie(rawCookies, "__Secure-1PAPISID")?.let { add("SAPISID1PHASH" to it) }
            parseCookie(rawCookies, "__Secure-3PAPISID")?.let { add("SAPISID3PHASH" to it) }
        }
    }

    fun clear() {
        cookieHeader = null
        apiSids = emptyList()
    }

    /** Auth headers for an Innertube request, or empty if not signed in. */
    fun authHeaders(nowSeconds: Long): Map<String, List<String>> {
        val cookies = cookieHeader
        val sids = apiSids
        if (cookies.isNullOrBlank() || sids.isEmpty()) return emptyMap()

        val authorization = sids.joinToString(" ") { (scheme, value) ->
            "$scheme ${nowSeconds}_${sha1("$nowSeconds $value $ORIGIN")}"
        }
        return mapOf(
            "Cookie" to listOf(cookies),
            "Authorization" to listOf(authorization),
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
