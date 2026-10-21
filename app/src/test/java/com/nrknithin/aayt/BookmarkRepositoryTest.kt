package com.nrknithin.aayt

import com.nrknithin.aayt.data.BookmarkCodec
import com.nrknithin.aayt.data.BookmarkRepository
import com.nrknithin.aayt.data.VideoItem
import org.junit.Assert.*
import org.junit.Test

class BookmarkRepositoryTest {
    private class MemoryStorage : BookmarkRepository.Storage {
        var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }

    private fun video(id: String = "dQw4w9WgXcQ") =
        VideoItem(id, "A title — 日本語 🎥", "Channel", "https://i.ytimg.com/vi/$id/hqdefault.jpg", "3:33", 42)

    @Test fun survivesRepositoryRecreationWithAllMetadataAndTimestamp() {
        val storage = MemoryStorage()
        assertTrue(BookmarkRepository(storage).save(video()))
        val reopened = BookmarkRepository(storage)
        assertEquals(listOf(video()), reopened.all())
        assertTrue(reopened.contains(video().id))
    }

    @Test fun deduplicatesAndKeepsExistingRichMetadataWhenUpdating() {
        val repository = BookmarkRepository(MemoryStorage())
        repository.save(video())
        repository.save(video().copy(title = "", channel = "", thumbnailUrl = null, durationText = null, startSeconds = 90))
        assertEquals(listOf(video().copy(startSeconds = 90)), repository.all())
        repository.save(video().copy(title = "Renamed"))
        assertEquals("Renamed", repository.all().single().title)
    }

    @Test fun newestFirstAndRemovalDoesNotAffectOtherVideos() {
        val repository = BookmarkRepository(MemoryStorage())
        val first = video()
        val second = video("aqz-KE-bpKQ")
        repository.save(first)
        repository.save(second)
        assertEquals(listOf(second, first), repository.all())
        repository.remove(second.id)
        assertEquals(listOf(first), repository.all())
        repository.save(second)
        assertEquals(listOf(second, first), repository.all())
        repository.remove("not-present")
        assertEquals(2, repository.all().size)
    }

    @Test fun undoRestoresRemovedVideoAtItsOriginalPosition() {
        val repository = BookmarkRepository(MemoryStorage())
        val videos = listOf(video(), video("aqz-KE-bpKQ"), video("abcdefghijk"))
        videos.reversed().forEach { repository.save(it) }
        repository.remove(videos[1].id)
        assertEquals(listOf(videos[0], videos[2]), repository.all())
        assertTrue(repository.save(videos[1], position = 1))
        assertEquals(videos, repository.all())
    }

    @Test fun rejectsInvalidIdsWithoutChangingExistingData() {
        val storage = MemoryStorage()
        val repository = BookmarkRepository(storage)
        repository.save(video())
        val encoded = storage.value
        assertFalse(repository.save(video("not a video")))
        assertEquals(encoded, storage.value)
    }

    @Test fun handlesMissingCorruptAndUnknownVersionStorage() {
        assertEquals(emptyList<VideoItem>(), BookmarkCodec.decode(null))
        assertEquals(emptyList<VideoItem>(), BookmarkCodec.decode("not-base64"))
        assertEquals(emptyList<VideoItem>(), BookmarkCodec.decode("AAAAAgAAAAA=")) // Version 2.
        val encoded = BookmarkCodec.encode(listOf(video()))
        assertEquals(emptyList<VideoItem>(), BookmarkCodec.decode(encoded.dropLast(12)))
    }

    @Test fun enforcesCapacityWhileAllowingUpdatesAndBoundsTextFields() {
        val storage = MemoryStorage()
        val videos = (0 until BookmarkRepository.MAX_BOOKMARKS).map { video(it.toString().padStart(11, '0')) }
        storage.value = BookmarkCodec.encode(videos)
        val repository = BookmarkRepository(storage)
        assertFalse(repository.save(video()))
        assertTrue(repository.save(videos.first().copy(title = "x".repeat(1_000))))
        assertEquals(BookmarkRepository.MAX_BOOKMARKS, repository.all().size)
        assertEquals(500, repository.all().first().title.length)
    }
}
