package com.nrknithin.aayt.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.nrknithin.aayt.R
import com.nrknithin.aayt.data.VideoItem

class VideoAdapter(
    private val onClick: (VideoItem) -> Unit
) : ListAdapter<VideoItem, VideoAdapter.VH>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(getItem(position))

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val thumbnail: ImageView = itemView.findViewById(R.id.thumbnail)
        private val duration: TextView = itemView.findViewById(R.id.duration)
        private val title: TextView = itemView.findViewById(R.id.title)
        private val channel: TextView = itemView.findViewById(R.id.channel)

        fun bind(item: VideoItem) {
            title.text = item.title
            channel.text = item.channel
            duration.text = item.durationText
            duration.visibility = if (item.durationText.isNullOrBlank()) View.GONE else View.VISIBLE
            if (item.thumbnailUrl.isNullOrBlank()) {
                thumbnail.setImageDrawable(null)
            } else {
                thumbnail.load(item.thumbnailUrl)
            }
            itemView.setOnClickListener { onClick(item) }
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<VideoItem>() {
            override fun areItemsTheSame(a: VideoItem, b: VideoItem) = a.id == b.id
            override fun areContentsTheSame(a: VideoItem, b: VideoItem) = a == b
        }
    }
}
