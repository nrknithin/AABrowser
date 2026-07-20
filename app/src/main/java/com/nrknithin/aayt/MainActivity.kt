package com.nrknithin.aayt

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nrknithin.aayt.data.Preferences
import com.nrknithin.aayt.data.VideoItem
import com.nrknithin.aayt.data.YouTubeAuth
import com.nrknithin.aayt.data.YouTubeRepo
import com.nrknithin.aayt.databinding.ActivityMainBinding
import com.nrknithin.aayt.model.AppThemeMode
import com.nrknithin.aayt.ui.VideoAdapter
import kotlinx.coroutines.launch

/**
 * AAYT v2 — a native YouTube car UI. A persistent rail switches sections; the
 * content pane shows a native video grid fed by [YouTubeRepo]. Tapping a video
 * plays it fullscreen in the content pane (the rail stays). The only WebView is
 * the one-time [LoginActivity] plus the player surface.
 */
class MainActivity : AppCompatActivity() {

    private enum class Section { HOME, SEARCH, SUBSCRIPTIONS, LIBRARY }

    private lateinit var binding: ActivityMainBinding
    private val adapter = VideoAdapter(::openVideo)
    private var section = Section.HOME

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)
        AppCompatDelegate.setDefaultNightMode(Preferences.getThemeMode(this).nightMode)
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        harvestSessionCookies()
        setupGrid()
        setupRail()
        setupSearch()
        setupPlayer()
        setupBackHandling()

        showSection(Section.HOME)
    }

    override fun onResume() {
        super.onResume()
        // Pick up a login that just completed.
        harvestSessionCookies()
    }

    private fun harvestSessionCookies() {
        val cm = CookieManager.getInstance()
        val yt = cm.getCookie("https://www.youtube.com").orEmpty()
        val google = cm.getCookie("https://www.google.com").orEmpty()
        val merged = listOf(yt, google).filter { it.isNotBlank() }.joinToString("; ")
        YouTubeAuth.updateFromCookieString(merged)
    }

    private fun setupGrid() {
        val widthDp = resources.configuration.screenWidthDp - 72
        val span = (widthDp / 210).coerceAtLeast(2)
        binding.videoGrid.layoutManager = GridLayoutManager(this, span)
        binding.videoGrid.adapter = adapter
    }

    private fun setupRail() {
        binding.railMenu.setOnClickListener { showMenu() }
        binding.railHome.setOnClickListener { showSection(Section.HOME) }
        binding.railSearch.setOnClickListener { showSection(Section.SEARCH) }
        binding.railLibrary.setOnClickListener { showSection(Section.LIBRARY) }
        binding.railBack.setOnClickListener { handleBack() }
    }

    private fun setupSearch() {
        binding.searchInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val q = v.text?.toString().orEmpty()
                hideKeyboard()
                runSearch(q)
                true
            } else {
                false
            }
        }
    }

    private fun showSection(target: Section) {
        section = target
        binding.searchBarContainer.isVisible = target == Section.SEARCH
        highlightRail(target)

        when (target) {
            Section.HOME -> load(getString(R.string.empty_generic)) { YouTubeRepo.home() }
            Section.SUBSCRIPTIONS ->
                if (YouTubeAuth.isSignedIn) {
                    load(getString(R.string.empty_subscriptions)) { YouTubeRepo.subscriptions() }
                } else {
                    adapter.submitList(emptyList())
                    showEmpty(getString(R.string.empty_signed_out))
                }
            Section.LIBRARY -> {
                adapter.submitList(emptyList())
                showEmpty(getString(R.string.library_soon))
            }
            Section.SEARCH -> {
                adapter.submitList(emptyList())
                showEmpty(getString(R.string.search_prompt))
                binding.searchInput.requestFocus()
                showKeyboard()
            }
        }
    }

    private fun runSearch(query: String) {
        if (query.isBlank()) return
        load(getString(R.string.empty_search)) { YouTubeRepo.search(query) }
    }

    private fun load(emptyMsg: String, fetch: suspend () -> List<VideoItem>) {
        binding.loading.isVisible = true
        binding.emptyState.isVisible = false
        binding.videoGrid.isVisible = false
        lifecycleScope.launch {
            val result = runCatching { fetch() }
            binding.loading.isVisible = false
            result.onSuccess { items ->
                adapter.submitList(items)
                if (items.isEmpty()) showEmpty(emptyMsg) else {
                    binding.videoGrid.isVisible = true
                    binding.videoGrid.scrollToPosition(0)
                }
            }.onFailure {
                showEmpty(getString(R.string.load_error, it.message ?: "unknown"))
            }
        }
    }

    private fun showEmpty(msg: String) {
        binding.videoGrid.isVisible = false
        binding.emptyState.isVisible = true
        binding.emptyState.text = msg
    }

    private fun highlightRail(target: Section) {
        val map = mapOf(
            Section.HOME to binding.railHome,
            Section.SEARCH to binding.railSearch,
            Section.LIBRARY to binding.railLibrary
        )
        val active = resolveColor(androidx.appcompat.R.attr.colorPrimary)
        val inactive = resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        map.forEach { (sec, btn) ->
            btn.iconTint = android.content.res.ColorStateList.valueOf(if (sec == target) active else inactive)
        }
    }

    private fun resolveColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) getColor(tv.resourceId) else tv.data
    }

    // --- player ----------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupPlayer() {
        with(binding.playerWeb) {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                    enterWebFullscreen(view, callback)
                }

                override fun onHideCustomView() {
                    exitWebFullscreen()
                }
            }
        }
        binding.playerClose.setOnClickListener { closePlayer() }
        binding.playerInfo.setOnClickListener {
            binding.infoPanel.isVisible = !binding.infoPanel.isVisible
        }
    }

    private fun openVideo(item: VideoItem) {
        if (item.id.isBlank()) {
            Toast.makeText(this, R.string.video_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        binding.infoTitle.text = item.title
        binding.infoChannel.text = item.channel
        binding.infoPanel.isVisible = false
        binding.playerContainer.isVisible = true
        val embed = "https://www.youtube.com/embed/${item.id}" +
            "?autoplay=1&fs=1&rel=0&modestbranding=1&playsinline=1"
        binding.playerWeb.loadUrl(embed)
    }

    private fun closePlayer() {
        exitWebFullscreen()
        binding.playerWeb.loadUrl("about:blank")
        binding.playerContainer.isVisible = false
        binding.infoPanel.isVisible = false
    }

    private fun enterWebFullscreen(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        if (view == null) return
        fullscreenView = view
        fullscreenCallback = callback
        (window.decorView as ViewGroup).addView(
            view,
            FrameLayout.LayoutParams(-1, -1)
        )
    }

    private fun exitWebFullscreen() {
        val view = fullscreenView ?: return
        (window.decorView as ViewGroup).removeView(view)
        fullscreenView = null
        runCatching { fullscreenCallback?.onCustomViewHidden() }
        fullscreenCallback = null
    }

    // --- menu / navigation ----------------------------------------------

    private fun showMenu() {
        val signedIn = YouTubeAuth.isSignedIn
        val authLabel = if (signedIn) getString(R.string.menu_sign_out) else getString(R.string.menu_sign_in)
        val options = arrayOf(
            authLabel,
            getString(R.string.section_subscriptions),
            getString(R.string.menu_theme),
            getString(R.string.menu_refresh)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(if (signedIn) getString(R.string.menu_signed_in) else getString(R.string.app_name))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> if (signedIn) signOut() else startActivity(Intent(this, LoginActivity::class.java))
                    1 -> showSection(Section.SUBSCRIPTIONS)
                    2 -> cycleTheme()
                    3 -> showSection(section)
                }
            }
            .show()
    }

    private fun signOut() {
        YouTubeAuth.clear()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        Toast.makeText(this, R.string.signed_out, Toast.LENGTH_SHORT).show()
        showSection(section)
    }

    private fun cycleTheme() {
        val next = when (Preferences.getThemeMode(this)) {
            AppThemeMode.AUTO -> AppThemeMode.LIGHT
            AppThemeMode.LIGHT -> AppThemeMode.DARK
            AppThemeMode.DARK -> AppThemeMode.AUTO
        }
        Preferences.setThemeMode(this, next)
        AppCompatDelegate.setDefaultNightMode(next.nightMode)
        recreate()
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this) { handleBack() }
    }

    private fun handleBack() {
        when {
            binding.playerContainer.isVisible -> closePlayer()
            section != Section.HOME -> showSection(Section.HOME)
            else -> finish()
        }
    }

    private fun showKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        binding.searchInput.post { imm.showSoftInput(binding.searchInput, 0) }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    override fun onDestroy() {
        binding.playerWeb.destroy()
        super.onDestroy()
    }
}
