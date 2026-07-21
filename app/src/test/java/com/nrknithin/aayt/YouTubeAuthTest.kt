package com.nrknithin.aayt

import com.nrknithin.aayt.data.YouTubeAuth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class YouTubeAuthTest {

    @After
    fun tearDown() = YouTubeAuth.clear()

    @Test
    fun signedOutByDefault() {
        YouTubeAuth.clear()
        assertFalse(YouTubeAuth.isSignedIn)
        assertTrue(YouTubeAuth.authHeaders(1000L).isEmpty())
    }

    @Test
    fun parsesSapisidAndFallsBackToSecureVariant() {
        assertEquals("abc123", YouTubeAuth.parseCookie("HSID=x; SAPISID=abc123; SSID=y", "SAPISID"))
        assertNull(YouTubeAuth.parseCookie("HSID=x; SSID=y", "SAPISID"))
    }

    @Test
    fun buildsSapisidHashHeaderWhenSignedIn() {
        YouTubeAuth.updateFromCookieString("HSID=x; SAPISID=MYSAPISID; SSID=y")
        assertTrue(YouTubeAuth.isSignedIn)

        val ts = 1_700_000_000L
        val headers = YouTubeAuth.authHeaders(ts)
        assertEquals(listOf("HSID=x; SAPISID=MYSAPISID; SSID=y"), headers["Cookie"])
        assertEquals(listOf("https://www.youtube.com"), headers["X-Origin"])

        val expectedHash = sha1("$ts MYSAPISID https://www.youtube.com")
        assertEquals(listOf("SAPISIDHASH ${ts}_$expectedHash"), headers["Authorization"])
    }

    @Test
    fun usesSecure3pApisidHashWhenOnlyThatIsPresent() {
        YouTubeAuth.updateFromCookieString("HSID=x; __Secure-3PAPISID=SECURESID")
        assertTrue(YouTubeAuth.isSignedIn)
        val headers = YouTubeAuth.authHeaders(42L)
        assertEquals(listOf("SAPISID3PHASH 42_${sha1("42 SECURESID https://www.youtube.com")}"), headers["Authorization"])
    }

    @Test
    fun sendsAllApiSidHashVariantsWhenPresent() {
        YouTubeAuth.updateFromCookieString("SAPISID=A; __Secure-1PAPISID=B; __Secure-3PAPISID=C")
        val auth = YouTubeAuth.authHeaders(10L).getValue("Authorization").first()
        assertTrue(auth.contains("SAPISIDHASH 10_${sha1("10 A https://www.youtube.com")}"))
        assertTrue(auth.contains("SAPISID1PHASH 10_${sha1("10 B https://www.youtube.com")}"))
        assertTrue(auth.contains("SAPISID3PHASH 10_${sha1("10 C https://www.youtube.com")}"))
    }

    @Test
    fun clearOnBlankCookies() {
        YouTubeAuth.updateFromCookieString("SAPISID=x")
        YouTubeAuth.updateFromCookieString("")
        assertFalse(YouTubeAuth.isSignedIn)
    }

    private fun sha1(input: String): String =
        MessageDigest.getInstance("SHA-1").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}
