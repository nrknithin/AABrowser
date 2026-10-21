package com.nrknithin.aayt

import com.nrknithin.aayt.data.YouTubeLinks
import org.junit.Assert.*
import org.junit.Test

class YouTubeLinksTest {
    private val id = "dQw4w9WgXcQ"

    @Test fun acceptsWatchShortsLiveEmbedAndShortLinks() {
        listOf(
            "https://www.youtube.com/watch?v=$id&si=tracking",
            "https://m.youtube.com/watch?v=$id",
            "https://music.youtube.com/watch?v=$id&list=test",
            "https://youtube.com/shorts/$id?feature=share",
            "https://youtube.com/live/$id",
            "https://youtube.com/embed/$id",
            "https://youtu.be/$id?si=tracking"
        ).forEach { url -> assertEquals(url, id, YouTubeLinks.parse(url)?.id) }
    }

    @Test fun extractsLinksFromShareTextAndDiscardsUnrelatedDestinations() {
        val result = YouTubeLinks.fromSharedText("See https://example.com and this video: https://youtu.be/$id.")
        assertEquals(id, result?.id)
        assertEquals("https://www.youtube.com/watch?v=$id", result?.url)
        assertNull(YouTubeLinks.fromSharedText("No video here"))
        assertNull(YouTubeLinks.fromSharedText(null))
        assertNull(YouTubeLinks.fromSharedText("x".repeat(16_385)))
    }

    @Test fun rejectsLookalikesCredentialsForeignSchemesAndRedirectPages() {
        listOf(
            "https://evilyoutube.com/watch?v=$id",
            "https://youtube.com.evil.example/watch?v=$id",
            "https://youtube.com@evil.example/watch?v=$id",
            "https://user@youtube.com/watch?v=$id",
            "https://www.youtube.com:8443/watch?v=$id",
            "javascript:https://youtube.com/watch?v=$id",
            "file:///youtube.com/watch?v=$id",
            "https://youtube.com/redirect?q=https://youtu.be/$id",
            "https://youtube.com/shorts/extra/$id",
            "https://youtu.be/$id/extra",
            "https://youtube.com/playlist?list=abc",
            "https://youtube.com/watch?v=bad%27%3Balert(1)",
            "https://youtube.com/watch?v=$id&bad=%ZZ"
        ).forEach { url -> assertNull(url, YouTubeLinks.parse(url)) }
    }

    @Test fun preservesSharedTimestampsButNeverCarriesTrackingParameters() {
        assertEquals(90, YouTubeLinks.parse("https://youtu.be/$id?t=90")?.startSeconds)
        assertEquals(3723, YouTubeLinks.parse("https://youtube.com/watch?v=$id&t=1h2m3s&si=tracking")?.startSeconds)
        assertEquals(12, YouTubeLinks.parse("https://youtube.com/embed/$id?start=12")?.startSeconds)
        assertEquals(60, YouTubeLinks.parse("https://youtu.be/$id#t=1m")?.startSeconds)
        assertEquals("https://www.youtube.com/watch?v=$id&t=90s",
            YouTubeLinks.parse("http://youtu.be/$id?t=90&si=tracking")?.url)
    }

    @Test fun malformedAndExtremeTimestampsStayBounded() {
        assertEquals(0, YouTubeLinks.parse("https://youtu.be/$id?t=-12")?.startSeconds)
        assertEquals(0, YouTubeLinks.parse("https://youtu.be/$id?t=nonsense")?.startSeconds)
        assertEquals(604_800, YouTubeLinks.parse("https://youtu.be/$id?t=9999999999")?.startSeconds)
        assertEquals(604_800, YouTubeLinks.parse("https://youtu.be/$id?t=999999h")?.startSeconds)
    }
}
