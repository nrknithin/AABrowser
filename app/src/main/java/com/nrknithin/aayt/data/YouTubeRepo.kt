package com.nrknithin.aayt.data

import com.nrknithin.aayt.data.InnertubeClient.FeedPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single source of video data for the native UI. Everything goes through the
 * Innertube JSON API ([InnertubeClient]) — home and subscriptions are
 * authenticated with the harvested web session; search and anonymous home use a
 * consent-accepted anonymous session. Feeds paginate via continuation tokens.
 */
object YouTubeRepo {

    suspend fun home(): FeedPage = withContext(Dispatchers.IO) {
        val personalized = runCatching { InnertubeClient.homeFeed() }.getOrNull()
        if (personalized != null && personalized.items.isNotEmpty()) personalized
        else if (YouTubeAuth.isSignedIn) InnertubeClient.subscriptionsFeed()
        else FeedPage(emptyList(), null)
    }

    suspend fun search(query: String): FeedPage = withContext(Dispatchers.IO) {
        if (query.isBlank()) FeedPage(emptyList(), null) else InnertubeClient.search(query)
    }

    suspend fun subscriptions(): FeedPage = withContext(Dispatchers.IO) {
        if (YouTubeAuth.isSignedIn) InnertubeClient.subscriptionsFeed() else FeedPage(emptyList(), null)
    }

    suspend fun more(token: String): FeedPage = withContext(Dispatchers.IO) {
        InnertubeClient.continuation(token)
    }

    suspend fun accountName(): String? = withContext(Dispatchers.IO) {
        if (YouTubeAuth.isSignedIn) InnertubeClient.accountName() else null
    }
}
