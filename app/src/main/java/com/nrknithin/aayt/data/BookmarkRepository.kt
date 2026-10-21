package com.nrknithin.aayt.data

import android.content.Context
import androidx.core.content.edit
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.Base64

/** Local bookmarks only: storing/removing a video makes no network request. */
class BookmarkRepository(private val storage: Storage) {
    interface Storage {
        fun read(): String?
        fun write(value: String)
    }

    fun all(): List<VideoItem> = BookmarkCodec.decode(storage.read())

    fun contains(id: String): Boolean = all().any { it.id == id }

    fun save(item: VideoItem, position: Int = 0): Boolean {
        if (!YouTubeLinks.isVideoId(item.id)) return false
        val videos = all().toMutableList()
        val index = videos.indexOfFirst { it.id == item.id }
        if (index < 0 && videos.size >= MAX_BOOKMARKS) return false
        if (index >= 0) {
            // Sharing an already-saved link must not discard its richer feed metadata.
            val previous = videos[index]
            videos[index] = item.copy(
                title = item.title.ifBlank { previous.title },
                channel = item.channel.ifBlank { previous.channel },
                thumbnailUrl = item.thumbnailUrl ?: previous.thumbnailUrl,
                durationText = item.durationText ?: previous.durationText
            )
        } else {
            videos.add(position.coerceIn(0, videos.size), item)
        }
        storage.write(BookmarkCodec.encode(videos))
        return true
    }

    fun remove(id: String) {
        storage.write(BookmarkCodec.encode(all().filterNot { it.id == id }))
    }

    companion object {
        const val MAX_BOOKMARKS = 1_000

        fun create(context: Context): BookmarkRepository {
            val prefs = context.applicationContext.getSharedPreferences("aayt_bookmarks", Context.MODE_PRIVATE)
            return BookmarkRepository(object : Storage {
                override fun read(): String? = prefs.getString("videos_v1", null)
                override fun write(value: String) { prefs.edit { putString("videos_v1", value) } }
            })
        }
    }
}

/** Versioned, bounded JVM codec so persistence is testable without device data. */
internal object BookmarkCodec {
    fun encode(videos: List<VideoItem>): String {
        require(videos.size <= BookmarkRepository.MAX_BOOKMARKS)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            out.writeInt(videos.size)
            videos.forEach { video ->
                out.writeUTF(video.id)
                out.writeUTF(video.title.take(500))
                out.writeUTF(video.channel.take(300))
                out.writeUTF(video.thumbnailUrl.orEmpty().take(2_048))
                out.writeUTF(video.durationText.orEmpty().take(32))
                out.writeInt(video.startSeconds.coerceIn(0, 604_800))
            }
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    fun decode(raw: String?): List<VideoItem> = runCatching {
        if (raw == null || raw.length > 12_000_000) return emptyList()
        DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(raw))).use { input ->
            if (input.readInt() != 1) return emptyList()
            val size = input.readInt()
            if (size !in 0..BookmarkRepository.MAX_BOOKMARKS) return emptyList()
            List(size) {
                VideoItem(input.readUTF(), input.readUTF(), input.readUTF(),
                    input.readUTF().ifEmpty { null }, input.readUTF().ifEmpty { null },
                    input.readInt().coerceIn(0, 604_800))
            }.filter { YouTubeLinks.isVideoId(it.id) }.distinctBy { it.id }
        }
    }.getOrDefault(emptyList())
}
