package com.kododake.aabrowser.startpage

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.core.view.isVisible
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.kododake.aabrowser.R
import com.kododake.aabrowser.bookmarks.BookmarkManager
import com.kododake.aabrowser.data.BrowserPreferences
import com.kododake.aabrowser.databinding.ActivityMainBinding
import com.kododake.aabrowser.ui.adapters.StartPageAdapter
import com.kododake.aabrowser.ui.adapters.SlotItem
import com.kododake.aabrowser.data.SiteIconCache
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class StartPageManager(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val bookmarkManager: BookmarkManager,
    private val callbacks: StartPageCallbacks
) {

    interface StartPageCallbacks {
        fun onNavigateToUrl(url: String)
        fun onShowMenuOverlay()
        fun onHideMenuOverlay()
        fun onEnterFullscreen()
        fun onExitFullscreen()
        fun isInFullscreen(): Boolean
        fun getCurrentUrl(): String
        fun resolveThemeColor(attrRes: Int): Int
        fun updateNavigationButtons()
        fun updateConnectionSecurityIcon(url: String?)
        fun showMenuButtonTemporarily()
        fun loadUrlFromIntent(url: String)
        fun resolveReadableTextColor(bg: Int, pr: Int, fb: Int): Int
    }

    var isShowingStartPage: Boolean = false
    
    private var loadedStartPageBackgroundUri: String? = null
    private var loadedStartPageBackgroundBitmap: Bitmap? = null
    private var cachedStartPageGradientSignature: Int = 0

    private val startPageAdapter: StartPageAdapter by lazy {
        StartPageAdapter(
            bookmarkManager = bookmarkManager,
            onSlotClick = { url ->
                if (url.isEmpty()) {
                    callbacks.onShowMenuOverlay()
                    bookmarkManager.showBookmarkManager()
                } else {
                    callbacks.loadUrlFromIntent(url)
                }
            },
            onReordered = { newList ->
                syncBookmarksFromSlots(newList.map { it.url })
            },
            resolveThemeColor = callbacks::resolveThemeColor
        )
    }

    private var backgroundLoadJob: Job? = null

    fun onDestroy() {
        backgroundLoadJob?.cancel()
        backgroundLoadJob = null
        loadedStartPageBackgroundBitmap?.recycle()
        loadedStartPageBackgroundBitmap = null
        loadedStartPageBackgroundUri = null
    }

    private fun setupRecyclerView() {
        if (binding.startPageQuickLinksContainer.adapter != null) {
            return
        }
        
        binding.startPageQuickLinksContainer.apply {
            layoutManager = GridLayoutManager(activity, 2)
            adapter = startPageAdapter
        }

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
                startPageAdapter.onItemMove(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
        })
        touchHelper.attachToRecyclerView(binding.startPageQuickLinksContainer)
    }

    private fun syncBookmarksFromSlots(slotUrls: List<String>) {
        slotUrls.forEachIndexed { index, url ->
            BrowserPreferences.setStartPageSlot(activity, index, url.takeIf { it.isNotEmpty() })
        }
        
        val allBookmarks = BrowserPreferences.getBookmarks(activity).toMutableList()
        val nonSlotBookmarks = allBookmarks.filter { it !in slotUrls.filter { s -> s.isNotEmpty() } }
        
        val newOrder = mutableListOf<String>()
        slotUrls.filter { it.isNotEmpty() }.forEach { newOrder.add(it) }
        newOrder.addAll(nonSlotBookmarks)
        
        BrowserPreferences.setBookmarks(activity, newOrder)
        bookmarkManager.refreshBookmarks()
    }

    fun showStartPage() {
        val homePageUrl = BrowserPreferences.getHomePageUrl(activity)
        if (!homePageUrl.isNullOrBlank()) {
            val message = activity.getString(R.string.start_page_disabled_by_home_page)
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            callbacks.loadUrlFromIntent(homePageUrl)
            return
        }
        
        if (callbacks.isInFullscreen()) {
            callbacks.onExitFullscreen()
        }
        
        isShowingStartPage = true
        binding.startPageRoot.visibility = View.VISIBLE
        binding.pageTitle.text = activity.getString(R.string.start_page_title)
        binding.addressEdit.setText("")
        
        callbacks.updateConnectionSecurityIcon(null)
        refreshStartPage()
        callbacks.updateNavigationButtons()
        callbacks.showMenuButtonTemporarily()
    }

    fun hideStartPage(currentPageTitle: String, currentUrl: String) {
        if (!isShowingStartPage && binding.startPageRoot.visibility != View.VISIBLE) {
            return
        }
        
        isShowingStartPage = false
        binding.startPageRoot.visibility = View.GONE
        
        binding.pageTitle.text = currentPageTitle.ifBlank {
            currentUrl.takeIf { it.isNotBlank() }?.let { bookmarkManager.displayLabelForUrl(it) }.orEmpty()
        }
        
        if (currentUrl.isNotBlank()) {
            if (binding.addressEdit.text?.toString() != currentUrl) {
                binding.addressEdit.setText(currentUrl)
                binding.addressEdit.setSelection(currentUrl.length)
            }
        } else {
            binding.addressEdit.setText("")
        }
        
        val iconUrl = currentUrl.takeIf { bookmarkManager.isActiveWebsiteUrl(it) }
        callbacks.updateConnectionSecurityIcon(iconUrl)
        callbacks.updateNavigationButtons()
        bookmarkManager.refreshBookmarks()
    }

    fun refreshStartPage() {
        setupRecyclerView()
        refreshStartPageQuickLinks()
        refreshStartPageBackground()
        setupStartPageCardGlassBackground()
    }

    private fun setupStartPageCardGlassBackground() {
        binding.startPageCard.setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
        binding.startPageCard.strokeWidth = 0
        binding.startPageCard.strokeColor = android.graphics.Color.TRANSPARENT
    }

    fun refreshStartPageBackground() {
        applyDynamicStartPageGradientBackground()
        val backgroundUri = BrowserPreferences.getStartPageBackgroundUri(activity)
        
        if (backgroundUri.isNullOrBlank()) {
            clearBackground()
            return
        }

        if (backgroundUri == loadedStartPageBackgroundUri && loadedStartPageBackgroundBitmap != null) {
            binding.startPageBackgroundImage.setImageBitmap(loadedStartPageBackgroundBitmap)
            binding.startPageBackgroundImage.visibility = View.VISIBLE
            return
        }

        backgroundLoadJob?.cancel()
        val uriToLoad = Uri.parse(backgroundUri)
        val metrics = activity.resources.displayMetrics
        val reqWidth = metrics.widthPixels.coerceAtLeast(1)
        val reqHeight = metrics.heightPixels.coerceAtLeast(1)

        backgroundLoadJob = activity.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                decodeSampledBitmapFromUri(uriToLoad, reqWidth, reqHeight)
            }
            
            loadedStartPageBackgroundBitmap?.recycle()
            loadedStartPageBackgroundBitmap = bitmap
            loadedStartPageBackgroundUri = if (bitmap != null) backgroundUri else null

            if (bitmap != null) {
                binding.startPageBackgroundImage.setImageBitmap(bitmap)
                binding.startPageBackgroundImage.visibility = View.VISIBLE
            } else {
                binding.startPageBackgroundImage.setImageBitmap(null)
                binding.startPageBackgroundImage.visibility = View.GONE
            }
        }
    }

    private fun clearBackground() {
        loadedStartPageBackgroundBitmap?.recycle()
        loadedStartPageBackgroundBitmap = null
        loadedStartPageBackgroundUri = null
        binding.startPageBackgroundImage.setImageBitmap(null)
        binding.startPageBackgroundImage.visibility = View.GONE
    }

    private fun applyDynamicStartPageGradientBackground() {
        val baseSurface = callbacks.resolveThemeColor(com.google.android.material.R.attr.colorSurface)
        val primaryContainer = callbacks.resolveThemeColor(com.google.android.material.R.attr.colorPrimaryContainer)
        val secondaryContainer = callbacks.resolveThemeColor(com.google.android.material.R.attr.colorSecondaryContainer)
        val tertiaryContainer = callbacks.resolveThemeColor(com.google.android.material.R.attr.colorTertiaryContainer)

        val signature = baseSurface xor primaryContainer xor secondaryContainer xor tertiaryContainer
        if (cachedStartPageGradientSignature == signature) {
            return
        }

        val linearStart = ColorUtils.blendARGB(baseSurface, secondaryContainer, 0.30f)
        val linearMid = ColorUtils.blendARGB(baseSurface, tertiaryContainer, 0.28f)
        val linearEnd = ColorUtils.blendARGB(baseSurface, primaryContainer, 0.30f)

        val baseLayer = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(linearStart, linearMid, linearEnd)).apply {
            gradientType = GradientDrawable.LINEAR_GRADIENT
        }

        val density = activity.resources.displayMetrics.density
        val blobs = arrayOf(
            createBlob(primaryContainer, tertiaryContainer, 0.45f, 460f * density, 0.18f, 0.22f, 170),
            createBlob(secondaryContainer, primaryContainer, 0.50f, 520f * density, 0.78f, 0.30f, 160),
            createBlob(tertiaryContainer, secondaryContainer, 0.42f, 540f * density, 0.55f, 0.82f, 150)
        )

        binding.startPageRoot.background = LayerDrawable(arrayOf(baseLayer) + blobs)
        cachedStartPageGradientSignature = signature
    }

    private fun createBlob(c1: Int, c2: Int, blend: Float, radius: Float, x: Float, y: Float, alpha: Int): GradientDrawable {
        val color = ColorUtils.blendARGB(c1, c2, blend)
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = radius
            setGradientCenter(x, y)
            colors = intArrayOf(ColorUtils.setAlphaComponent(color, alpha), Color.TRANSPARENT)
        }
    }

    private fun decodeSampledBitmapFromUri(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return null
        }

        options.inSampleSize = calculateInSampleSize(options.outWidth, options.outHeight, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.RGB_565

        return runCatching {
            activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }

    private fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (srcHeight > reqHeight || srcWidth > reqWidth) {
            val halfHeight = srcHeight / 2
            val halfWidth = srcWidth / 2
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    fun refreshStartPageQuickLinks() {
        val slots = BrowserPreferences.getStartPageSlots(activity).map { url ->
            val cleanUrl = url ?: ""
            val hasIcon = if (cleanUrl.isNotEmpty()) SiteIconCache.getCachedIcon(activity, cleanUrl) != null else false
            SlotItem(cleanUrl, hasIcon)
        }
        startPageAdapter.submitList(slots)
    }

    fun handleStartPageBackgroundPicked(uri: Uri?) {
        if (uri == null) {
            return
        }
        if (activity.contentResolver.openInputStream(uri)?.use { true } != true) {
            Toast.makeText(activity, R.string.start_page_background_error, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val prev = BrowserPreferences.getStartPageBackgroundUri(activity)
        BrowserPreferences.setStartPageBackgroundUri(activity, uri.toString())
        if (!prev.isNullOrBlank() && prev != uri.toString()) {
            runCatching { activity.contentResolver.releasePersistableUriPermission(Uri.parse(prev), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        refreshStartPage()
        Toast.makeText(activity, R.string.start_page_background_set, Toast.LENGTH_SHORT).show()
    }

    fun clearStartPageBackground() {
        val prev = BrowserPreferences.getStartPageBackgroundUri(activity)
        if (prev == null) {
            return
        }
        runCatching { activity.contentResolver.releasePersistableUriPermission(Uri.parse(prev), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        BrowserPreferences.clearStartPageBackgroundUri(activity)
        refreshStartPage()
        Toast.makeText(activity, R.string.start_page_background_cleared, Toast.LENGTH_SHORT).show()
    }
}
