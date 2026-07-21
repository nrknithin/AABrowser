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
import androidx.activity.result.contract.ActivityResultContracts
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

    // Pagination state for the current feed.
    private val items = mutableListOf<VideoItem>()
    private var continuation: String? = null
    private var loadingMore = false
    private var loadGeneration = 0

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var playingVideoId: String? = null
    private var controlsVisible = true
    private val hideControlsRunnable = Runnable { setControlsVisible(false) }
    private val controlsHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        harvestSessionCookies()
        // Reload whatever section is showing now that we're (possibly) signed in.
        showSection(Section.HOME)
    }

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
        // Only youtube.com cookies. Merging google.com duplicates SID/SAPISID/
        // __Secure-3PSID with different values, which YouTube rejects as
        // logged-out. The youtube.com set already carries the full auth cookies.
        val yt = CookieManager.getInstance().getCookie("https://www.youtube.com").orEmpty()
        YouTubeAuth.updateFromCookieString(yt)
    }

    private fun setupGrid() {
        val widthDp = resources.configuration.screenWidthDp - 72
        val span = (widthDp / 210).coerceAtLeast(2)
        binding.videoGrid.layoutManager = GridLayoutManager(this, span)
        binding.videoGrid.adapter = adapter
        binding.videoGrid.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: androidx.recyclerview.widget.RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = rv.layoutManager as GridLayoutManager
                if (lm.findLastVisibleItemPosition() >= lm.itemCount - 6) loadMore()
            }
        })
    }

    private fun setupRail() {
        binding.railMenu.setOnClickListener { showMenu() }
        binding.railHome.setOnClickListener { showSection(Section.HOME) }
        binding.railSearch.setOnClickListener { showSection(Section.SEARCH) }
        binding.railSubscriptions.setOnClickListener { showSection(Section.SUBSCRIPTIONS) }
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
            Section.HOME ->
                if (YouTubeAuth.isSignedIn) {
                    load(getString(R.string.empty_generic)) { YouTubeRepo.home() }
                } else {
                    clearFeed()
                    showEmpty(getString(R.string.empty_home_signed_out))
                }
            Section.SUBSCRIPTIONS ->
                if (YouTubeAuth.isSignedIn) {
                    load(getString(R.string.empty_subscriptions)) { YouTubeRepo.subscriptions() }
                } else {
                    clearFeed()
                    showEmpty(getString(R.string.empty_signed_out))
                }
            Section.LIBRARY -> {
                clearFeed()
                showEmpty(getString(R.string.library_soon))
            }
            Section.SEARCH -> {
                clearFeed()
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

    private fun clearFeed() {
        loadGeneration++
        items.clear()
        continuation = null
        loadingMore = false
        adapter.submitList(emptyList())
    }

    private fun load(emptyMsg: String, fetch: suspend () -> com.nrknithin.aayt.data.InnertubeClient.FeedPage) {
        val gen = ++loadGeneration
        items.clear()
        continuation = null
        loadingMore = false
        adapter.submitList(emptyList())
        binding.loading.isVisible = true
        binding.emptyState.isVisible = false
        binding.videoGrid.isVisible = false
        lifecycleScope.launch {
            val result = runCatching { fetch() }
            if (gen != loadGeneration) return@launch
            binding.loading.isVisible = false
            result.onSuccess { page ->
                items.addAll(page.items)
                continuation = page.continuation
                adapter.submitList(items.toList())
                if (items.isEmpty()) showEmpty(emptyMsg) else {
                    binding.videoGrid.isVisible = true
                    binding.videoGrid.scrollToPosition(0)
                }
            }.onFailure {
                showEmpty(getString(R.string.load_error, it.message ?: "unknown"))
            }
        }
    }

    private fun loadMore() {
        val token = continuation ?: return
        if (loadingMore) return
        loadingMore = true
        val gen = loadGeneration
        lifecycleScope.launch {
            val result = runCatching { YouTubeRepo.more(token) }
            if (gen != loadGeneration) return@launch
            result.onSuccess { page ->
                items.addAll(page.items)
                continuation = page.continuation
                adapter.submitList(items.toList())
            }
            loadingMore = false
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
            Section.SUBSCRIPTIONS to binding.railSubscriptions
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
        CookieManager.getInstance().setAcceptCookie(true)
        with(binding.playerWeb) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            // Real mobile watch page → the actual YouTube player (no embed
            // restrictions, uses the signed-in session).
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
            webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    // Blow the player up to fill and hide the rest of the page.
                    view.evaluateJavascript(PLAYER_SKIN_JS, null)
                    view.postDelayed({ view.evaluateJavascript(PLAYER_SKIN_JS, null) }, 1500)
                }
            }
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
        binding.playerTapCatcher.setOnClickListener { toggleControls() }
        binding.playerRewind.setOnClickListener { seek(-10); nudgeControls() }
        binding.playerForward.setOnClickListener { seek(10); nudgeControls() }
        binding.playerNext.setOnClickListener { playNext() }
        binding.playerPlayPause.setOnClickListener { togglePlayPause(); nudgeControls() }
    }

    private fun openVideo(item: VideoItem) {
        if (item.id.isBlank()) {
            Toast.makeText(this, R.string.video_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        playingVideoId = item.id
        binding.infoTitle.text = item.title
        binding.infoChannel.text = item.channel
        binding.infoPanel.isVisible = false
        binding.playerContainer.isVisible = true
        binding.playerPlayPause.setIconResource(R.drawable.pause_24px)
        setControlsVisible(true)
        binding.playerWeb.loadUrl("https://m.youtube.com/watch?v=${item.id}")
    }

    private fun closePlayer() {
        exitWebFullscreen()
        controlsHandler.removeCallbacks(hideControlsRunnable)
        binding.playerWeb.loadUrl("about:blank")
        binding.playerContainer.isVisible = false
        binding.infoPanel.isVisible = false
        playingVideoId = null
    }

    private fun seek(deltaSeconds: Int) {
        binding.playerWeb.evaluateJavascript(
            "(function(){var v=document.querySelector('video');" +
                "if(v)v.currentTime=Math.max(0,v.currentTime+($deltaSeconds));})();",
            null
        )
    }

    private fun togglePlayPause() {
        binding.playerWeb.evaluateJavascript(
            "(function(){var v=document.querySelector('video');if(!v)return 'none';" +
                "if(v.paused){v.play();return 'playing';}else{v.pause();return 'paused';}})();"
        ) { result ->
            val playing = result?.contains("playing") == true
            binding.playerPlayPause.setIconResource(
                if (playing) R.drawable.pause_24px else R.drawable.play_arrow_24px
            )
        }
    }

    private fun playNext() {
        val current = playingVideoId ?: return
        val idx = items.indexOfFirst { it.id == current }
        val next = items.getOrNull(idx + 1)
        if (next != null) {
            openVideo(next)
        } else {
            Toast.makeText(this, R.string.player_no_next, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleControls() = setControlsVisible(!controlsVisible)

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        binding.playerControls.isVisible = visible
        binding.playerClose.isVisible = visible
        binding.playerInfo.isVisible = visible
        if (!visible) binding.infoPanel.isVisible = false
        controlsHandler.removeCallbacks(hideControlsRunnable)
        if (visible) controlsHandler.postDelayed(hideControlsRunnable, 4000)
    }

    private fun nudgeControls() {
        controlsHandler.removeCallbacks(hideControlsRunnable)
        controlsHandler.postDelayed(hideControlsRunnable, 4000)
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
        val view = layoutInflater.inflate(R.layout.dialog_menu, null)
        val dialog = MaterialAlertDialogBuilder(this).setView(view).create()

        fun btn(id: Int) = view.findViewById<com.google.android.material.button.MaterialButton>(id)

        // First tile: Profile (opens the WebView profile) when signed in, else Sign in.
        btn(R.id.menuBtnAuth).apply {
            text = getString(if (signedIn) R.string.menu_profile else R.string.menu_sign_in)
            setOnClickListener {
                dialog.dismiss()
                val intent = Intent(this@MainActivity, LoginActivity::class.java)
                if (signedIn) intent.putExtra(LoginActivity.EXTRA_PROFILE, true)
                loginLauncher.launch(intent)
            }
        }
        btn(R.id.menuBtnLibrary).setOnClickListener { dialog.dismiss(); showSection(Section.LIBRARY) }
        btn(R.id.menuBtnTheme).setOnClickListener { dialog.dismiss(); cycleTheme() }
        btn(R.id.menuBtnRefresh).setOnClickListener { dialog.dismiss(); showSection(section) }
        // Sign out: last tile, only when signed in.
        btn(R.id.menuBtnSignOut).apply {
            isVisible = signedIn
            setOnClickListener { dialog.dismiss(); signOut() }
        }
        dialog.show()
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

    companion object {
        // Tidy the watch page (hide nav chrome) and start playback. We do NOT
        // force a fullscreen overlay here — that caused a black screen. The real
        // player's own fullscreen button drives onShowCustomView for true
        // fullscreen, and being signed in it plays Premium (no ads).
        // style.textContent is not a Trusted-Types sink, so this is CSP-safe.
        private const val PLAYER_SKIN_JS = """
(function(){
  try{
    var id='aayt-player-skin';
    var s=document.getElementById(id)||document.createElement('style');
    s.id=id;
    s.textContent=
      'html,body{background:#000!important;margin:0!important;overflow:hidden!important;height:100%!important}'+
      /* pull the player element out to fill the screen */
      '#player,#movie_player,.html5-video-player,ytm-watch,#player-container-id{position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:0!important;width:100vw!important;height:100vh!important;z-index:2147483000!important;background:#000!important;margin:0!important}'+
      '.html5-main-video,video{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;object-fit:contain!important;background:#000!important;z-index:2147483000!important}'+
      /* hide YouTube's own controls/gradients — we use native controls */
      '.ytp-chrome-bottom,.ytp-chrome-top,.ytp-gradient-bottom,.ytp-gradient-top,.player-controls-background,.ytp-cued-thumbnail-overlay,.ytm-player-control-overlay,.player-controls,.ytp-spinner{display:none!important}'+
      /* hide everything below the player (details, comments, related) */
      'ytm-item-section-renderer,ytm-comment-section-renderer,#comments,.slim-video-metadata-section,ytm-single-column-watch-next-results-renderer,.related-chips-slot-wrapper{display:none!important}';
    (document.head||document.documentElement).appendChild(s);
    var v=document.querySelector('video');
    if(v){ v.muted=false; v.volume=1.0; if(v.paused){v.play().catch(function(){});} }
  }catch(e){}
})();
"""
    }
}
