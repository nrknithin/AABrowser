package com.nrknithin.aayt.data

/** A single video card shown in the native grid. */
data class VideoItem(
    val id: String,
    val title: String,
    val channel: String,
    val thumbnailUrl: String?,
    val durationText: String?
)
