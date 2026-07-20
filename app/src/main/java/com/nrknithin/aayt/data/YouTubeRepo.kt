package com.nrknithin.aayt.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single source of video data for the native UI. Everything goes through the
 * Innertube JSON API ([InnertubeClient]) — home and subscriptions are
 * authenticated with the harvested web session, search and (anonymous) home use
 * a consent-accepted anonymous session. No HTML scraping, so no ytInitialData
 * fragility.
 */
object YouTubeRepo {

    suspend fun home(): List<VideoItem> = withContext(Dispatchers.IO) {
        InnertubeClient.homeFeed()
    }

    suspend fun search(query: String): List<VideoItem> = withContext(Dispatchers.IO) {
        if (query.isBlank()) emptyList() else InnertubeClient.search(query)
    }

    suspend fun subscriptions(): List<VideoItem> = withContext(Dispatchers.IO) {
        if (YouTubeAuth.isSignedIn) InnertubeClient.subscriptionsFeed() else emptyList()
    }

    suspend fun accountName(): String? = withContext(Dispatchers.IO) {
        if (YouTubeAuth.isSignedIn) InnertubeClient.accountName() else null
    }
}
