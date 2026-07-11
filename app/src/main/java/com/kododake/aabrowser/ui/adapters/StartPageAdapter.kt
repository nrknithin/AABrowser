package com.kododake.aabrowser.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.kododake.aabrowser.R
import com.kododake.aabrowser.bookmarks.BookmarkManager
import com.kododake.aabrowser.databinding.ItemStartPageSlotBinding

data class SlotItem(
    val url: String,
    val hasCachedIcon: Boolean
)

class StartPageAdapter(
    private val bookmarkManager: BookmarkManager,
    private val onSlotClick: (String) -> Unit,
    private val onReordered: (List<SlotItem>) -> Unit,
    private val resolveThemeColor: (Int) -> Int
) : ListAdapter<SlotItem, StartPageAdapter.SlotViewHolder>(SlotDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlotViewHolder {
        val binding = ItemStartPageSlotBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        
        parent.post {
            val layoutManager = (parent as RecyclerView).layoutManager as? androidx.recyclerview.widget.GridLayoutManager
            val spanCount = layoutManager?.spanCount ?: 2
            val rows = com.kododake.aabrowser.data.BrowserPreferences.MAX_START_PAGE_SITES / spanCount
            val targetHeight = parent.measuredHeight / rows
            
            if (binding.root.layoutParams.height != targetHeight) {
                binding.root.layoutParams.height = targetHeight
                binding.root.requestLayout()
            }
        }
        
        return SlotViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SlotViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun onItemMove(fromPosition: Int, toPosition: Int) {
        val list = currentList.toMutableList()
        val item = list.removeAt(fromPosition)
        list.add(toPosition, item)
        submitList(list)
        onReordered(list)
    }

    inner class SlotViewHolder(private val binding: ItemStartPageSlotBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: SlotItem) {
            val url = item.url
            val context = itemView.context
            val density = context.resources.displayMetrics.density
            val isEmpty = url.isEmpty()

            binding.root.elevation = 6 * density
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                binding.root.outlineAmbientShadowColor = android.graphics.Color.WHITE
                binding.root.outlineSpotShadowColor = android.graphics.Color.WHITE
            }
            val transparentBg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 18 * density
                setColor(android.graphics.Color.TRANSPARENT)
            }
            binding.root.background = transparentBg
            binding.root.clipToOutline = true
            
            val cardBg = resolveThemeColor(com.google.android.material.R.attr.colorSurfaceContainerLowest)
            val glassBgColor = androidx.core.graphics.ColorUtils.setAlphaComponent(cardBg, 180)
            
            val contentDrawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 18 * density
                setColor(glassBgColor)
            }
            
            val maskDrawable = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 18 * density
                setColor(android.graphics.Color.WHITE)
            }
            
            val rippleColor = android.content.res.ColorStateList.valueOf(
                androidx.core.graphics.ColorUtils.setAlphaComponent(
                    resolveThemeColor(com.google.android.material.R.attr.colorOnSurface), 30
                )
            )
            
            val rippleDrawable = android.graphics.drawable.RippleDrawable(
                rippleColor,
                contentDrawable,
                maskDrawable
            )
            
            binding.slotContentLayout.background = rippleDrawable
            binding.slotContentLayout.setOnClickListener { onSlotClick(url) }

            binding.iconContainer.removeAllViews()
            binding.iconContainer.addView(
                bookmarkManager.createSiteIconBadge(
                    url = if (isEmpty) null else url,
                    sizeDp = 48f,
                    cornerRadiusDp = 14f,
                    paddingDp = 8f,
                    backgroundColor = resolveThemeColor(
                        if (isEmpty) com.google.android.material.R.attr.colorSecondaryContainer
                        else com.google.android.material.R.attr.colorPrimaryContainer
                    ),
                    showAddOnEmptyUrl = true
                )
            )

            binding.labelView.text = if (isEmpty) {
                context.getString(R.string.start_page_slot_empty_title)
            } else {
                bookmarkManager.displayLabelForUrl(url)
            }
            binding.labelView.setTextColor(resolveThemeColor(androidx.appcompat.R.attr.colorPrimary))

            binding.titleText.text = if (isEmpty) {
                context.getString(R.string.start_page_slot_empty_title)
            } else {
                bookmarkManager.displayTitleForUrl(url)
            }

            binding.urlText.text = if (isEmpty) {
                context.getString(R.string.start_page_slot_empty_subtitle)
            } else {
                url
            }
            binding.urlText.setTextColor(resolveThemeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        }
    }

    class SlotDiffCallback : DiffUtil.ItemCallback<SlotItem>() {
        override fun areItemsTheSame(oldItem: SlotItem, newItem: SlotItem): Boolean = oldItem.url == newItem.url
        override fun areContentsTheSame(oldItem: SlotItem, newItem: SlotItem): Boolean = oldItem == newItem
    }
}
