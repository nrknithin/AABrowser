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
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.nrknithin.aayt.data.BookmarkRepository
import com.nrknithin.aayt.data.Preferences
import com.nrknithin.aayt.data.VideoItem
import com.nrknithin.aayt.data.YouTubeAuth
import com.nrknithin.aayt.data.YouTubeRepo
import com.nrknithin.aayt.data.YouTubeLinks
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

    private enum class Section { HOME, SEARCH, SUBSCRIPTIONS, SAVED }

    private lateinit var binding: ActivityMainBinding
    private val adapter = VideoAdapter(::openVideo, ::toggleBookmark)
    private val bookmarks by lazy { BookmarkRepository.create(this) }
    private var section = Section.HOME

    // Pagination state for the current feed.
    private val items = mutableListOf<VideoItem>()
    private var continuation: String? = null
    private var loadingMore = false
    private var loadGeneration = 0

    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    private var playingVideoId: String? = null
    private var playingVideo: VideoItem? = null
    private var allowWebTouch = false
    private var userSeeking = false
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingVideo(intent)
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

        showSection(if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_VIEW) Section.SAVED else Section.HOME)
        if (savedInstanceState == null) handleIncomingVideo(intent)
    }

    override fun onResume() {
        super.onResume()
        // Pick up a login that just completed.
        harvestSessionCookies()
        refreshBookmarks()
    }

    private fun handleIncomingVideo(incoming: Intent) {
        if (incoming.action != Intent.ACTION_SEND && incoming.action != Intent.ACTION_VIEW) return
        val link = runCatching {
            if (incoming.action == Intent.ACTION_VIEW) YouTubeLinks.parse(incoming.dataString.orEmpty())
            else YouTubeLinks.fromSharedText(incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
        }.getOrNull()
        val subject = runCatching { incoming.getStringExtra(Intent.EXTRA_SUBJECT)?.take(120) }.getOrNull()
        // Consume once: a later theme/configuration recreation must not replay a share.
        setIntent(Intent(this, MainActivity::class.java))
        if (link == null) {
            Toast.makeText(this, R.string.invalid_youtube_link, Toast.LENGTH_LONG).show()
            return
        }
        val previous = bookmarks.all().firstOrNull { it.id == link.id }
        val view = layoutInflater.inflate(R.layout.dialog_shared_video, null)
        val name = view.findViewById<TextInputEditText>(R.id.sharedVideoName)
        name.setText(previous?.title ?: subject?.takeIf { YouTubeLinks.fromSharedText(it) == null })
        view.findViewById<android.widget.TextView>(R.id.sharedVideoUrl).text = link.url
        fun video() = VideoItem(
            link.id,
            name.text?.toString()?.trim().orEmpty().ifBlank { getString(R.string.shared_video_fallback, link.id) },
            previous?.channel.orEmpty(),
            previous?.thumbnailUrl ?: "https://i.ytimg.com/vi/${link.id}/hqdefault.jpg",
            previous?.durationText,
            link.startSeconds
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.shared_video_title)
            .setView(view)
            .setPositiveButton(R.string.play_now) { _, _ ->
                hideKeyboard()
                closePlayer()
                openVideo(video())
            }
            .setNegativeButton(R.string.save_for_later) { _, _ ->
                hideKeyboard()
                if (bookmarks.save(video())) {
                    closePlayer()
                    showSection(Section.SAVED)
                    Toast.makeText(this, R.string.video_saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.bookmark_limit, Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun renderSaved() {
        loadGeneration++ // Invalidate an in-flight feed before showing local bookmarks.
        continuation = null
        loadingMore = false
        val saved = bookmarks.all()
        items.clear()
        items.addAll(saved)
        adapter.updateSavedIds(saved.map { it.id }.toSet())
        adapter.submitList(items.toList())
        binding.loading.isVisible = false
        if (items.isEmpty()) showEmpty(getString(R.string.empty_saved)) else {
            binding.emptyState.isVisible = false
            binding.videoGrid.isVisible = true
        }
    }

    private fun refreshBookmarks() {
        val savedIds = bookmarks.all().map { it.id }.toSet()
        adapter.updateSavedIds(savedIds)
        val saved = playingVideoId in savedIds
        binding.playerSave.setIconResource(if (saved) R.drawable.bookmark_filled_24px else R.drawable.bookmark_24px)
        binding.playerSave.contentDescription = playingVideo?.let {
            getString(if (saved) R.string.remove_saved_named else R.string.save_video_named, it.title)
        } ?: getString(R.string.save_video)
        if (section == Section.SAVED) renderSaved()
    }

    private fun toggleBookmark(item: VideoItem) {
        val saved = bookmarks.all()
        val previousIndex = saved.indexOfFirst { it.id == item.id }
        val previous = saved.getOrNull(previousIndex)
        if (previous != null) {
            bookmarks.remove(item.id)
            refreshBookmarks()
            Snackbar.make(binding.root, R.string.video_removed, Snackbar.LENGTH_LONG)
                .setAction(R.string.undo) {
                    if (!bookmarks.save(previous, previousIndex)) {
                        Toast.makeText(this, R.string.bookmark_limit, Toast.LENGTH_LONG).show()
                    }
                    refreshBookmarks()
                }.show()
        } else if (bookmarks.save(item)) {
            refreshBookmarks()
            Snackbar.make(binding.root, R.string.video_saved, Snackbar.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, R.string.bookmark_limit, Toast.LENGTH_LONG).show()
        }
        if (binding.playerContainer.isVisible) nudgeControls()
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
        // Rail always navigates, dismissing the player first if it's open.
        binding.railMenu.setOnClickListener { closePlayer(); showMenu() }
        binding.railHome.setOnClickListener { closePlayer(); showSection(Section.HOME) }
        binding.railSearch.setOnClickListener { closePlayer(); showSection(Section.SEARCH) }
        binding.railSubscriptions.setOnClickListener { closePlayer(); showSection(Section.SUBSCRIPTIONS) }
        binding.railSaved.setOnClickListener { closePlayer(); showSection(Section.SAVED) }
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
        hideKeyboard()
        binding.searchBarContainer.isVisible = target == Section.SEARCH
        binding.savedHeader.isVisible = target == Section.SAVED
        if (target != Section.HOME) binding.homeChipsScroll.isVisible = false
        highlightRail(target)

        when (target) {
            Section.HOME ->
                if (YouTubeAuth.isSignedIn) {
                    loadHome()
                } else {
                    binding.homeChipsScroll.isVisible = false
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
            Section.SAVED -> renderSaved()
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

    private fun loadHome() {
        val gen = ++loadGeneration
        items.clear()
        continuation = null
        loadingMore = false
        adapter.submitList(emptyList())
        binding.loading.isVisible = true
        binding.emptyState.isVisible = false
        binding.videoGrid.isVisible = false
        lifecycleScope.launch {
            val result = runCatching { YouTubeRepo.homeWithChips() }
            if (gen != loadGeneration) return@launch
            binding.loading.isVisible = false
            result.onSuccess { home ->
                renderChips(home.chips)
                items.addAll(home.page.items)
                continuation = home.page.continuation
                adapter.submitList(items.toList())
                if (items.isEmpty()) showEmpty(getString(R.string.empty_generic)) else {
                    binding.videoGrid.isVisible = true
                    binding.videoGrid.scrollToPosition(0)
                }
            }.onFailure {
                showEmpty(getString(R.string.load_error, it.message ?: "unknown"))
            }
        }
    }

    private fun renderChips(chips: List<com.nrknithin.aayt.data.InnertubeClient.Chip>) {
        val group = binding.homeChipsRow
        group.removeAllViews()
        if (chips.isEmpty() || section != Section.HOME) {
            binding.homeChipsScroll.isVisible = false
            return
        }
        chips.take(8).forEach { chip ->
            val view = com.google.android.material.chip.Chip(this).apply {
                text = chip.title
                isCheckable = true
                isChecked = chip.token == null
                setOnClickListener {
                    isChecked = true
                    if (chip.token == null) loadHome()
                    else load(getString(R.string.empty_generic)) { YouTubeRepo.more(chip.token) }
                }
            }
            group.addView(view)
        }
        binding.homeChipsScroll.isVisible = true
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
            Section.SUBSCRIPTIONS to binding.railSubscriptions,
            Section.SAVED to binding.railSaved
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

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun setupPlayer() {
        CookieManager.getInstance().setAcceptCookie(true)
        // Consume every touch on the video so YouTube's player never sees a tap
        // (no accidental play/pause). Each tap toggles our own controls instead.
        // Only a synthesized fullscreen tap is allowed through (allowWebTouch).
        binding.playerWeb.setOnTouchListener { _, ev ->
            if (allowWebTouch) return@setOnTouchListener false
            if (ev.action == android.view.MotionEvent.ACTION_UP) toggleControls()
            true
        }
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
                    view.evaluateJavascript(PLAYER_SKIN_JS, null)
                    view.postDelayed({ view.evaluateJavascript(PLAYER_SKIN_JS, null) }, 1400)
                    view.postDelayed({ view.evaluateJavascript(PLAYER_SKIN_JS, null) }, 3000)
                    // Set max quality early (before fullscreen) so the quality
                    // switch doesn't restart the video mid-fullscreen.
                    view.postDelayed({ setMaxQuality() }, 1000)
                    // Then auto-fullscreen via a synthesized real touch (fullscreen
                    // requires a user gesture).
                    view.postDelayed({ synthTapForFullscreen() }, 1600)
                    view.postDelayed({ synthTapForFullscreen() }, 2900)
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
        binding.playerSave.setOnClickListener { playingVideo?.let(::toggleBookmark) }
        binding.playerInfo.setOnClickListener {
            binding.infoPanel.isVisible = !binding.infoPanel.isVisible
        }
        binding.playerTapCatcher.setOnClickListener { toggleControls() }
        binding.playerRewind.setOnClickListener { seek(-10); nudgeControls() }
        binding.playerForward.setOnClickListener { seek(10); nudgeControls() }
        binding.playerNext.setOnClickListener { playNext() }
        binding.playerPlayPause.setOnClickListener { togglePlayPause(); nudgeControls() }
        binding.playerQuality.setOnClickListener { showQualityPicker(); nudgeControls() }
        binding.playerSeek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.playerTime.text = formatTime(progress)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar) {
                userSeeking = true
                controlsHandler.removeCallbacks(hideControlsRunnable)
            }
            override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                userSeeking = false
                binding.playerWeb.evaluateJavascript(
                    "(function(){var v=document.querySelector('video');if(v)v.currentTime=${sb.progress};})();",
                    null
                )
                nudgeControls()
            }
        })
    }

    private fun formatTime(totalSeconds: Int): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    private fun setMaxQuality() {
        binding.playerWeb.evaluateJavascript(
            "(function(){var p=document.getElementById('movie_player');" +
                "if(p&&p.getAvailableQualityLevels){var q=p.getAvailableQualityLevels();" +
                "if(q&&q.length){var b=q[0];try{if(p.setPlaybackQualityRange)p.setPlaybackQualityRange(b,b);" +
                "if(p.setPlaybackQuality)p.setPlaybackQuality(b);}catch(e){}}}})();",
            null
        )
    }

    private fun showQualityPicker() {
        binding.playerWeb.evaluateJavascript(
            "(function(){var p=document.getElementById('movie_player');" +
                "return (p&&p.getAvailableQualityLevels)?JSON.stringify(p.getAvailableQualityLevels()):'[]';})();"
        ) { json ->
            val levels = parseQualityLevels(json)
            if (levels.isEmpty()) {
                Toast.makeText(this, R.string.player_quality_unavailable, Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }
            val labels = levels.map { qualityLabel(it) }.toTypedArray()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.player_quality)
                .setItems(labels) { _, which ->
                    val q = levels[which]
                    binding.playerWeb.evaluateJavascript(
                        "(function(){var p=document.getElementById('movie_player');if(p){try{" +
                            "if(p.setPlaybackQualityRange)p.setPlaybackQualityRange('$q','$q');" +
                            "if(p.setPlaybackQuality)p.setPlaybackQuality('$q');}catch(e){}}})();",
                        null
                    )
                }
                .show()
        }
    }

    private fun parseQualityLevels(json: String?): List<String> {
        val raw = json ?: return emptyList()
        return Regex("\"([a-z0-9]+)\"").findAll(raw).map { it.groupValues[1] }.toList()
    }

    private fun qualityLabel(code: String): String = when (code) {
        "highres", "hd2880" -> "2880p"
        "hd2160" -> "2160p (4K)"
        "hd1440" -> "1440p"
        "hd1080" -> "1080p"
        "hd720" -> "720p"
        "large" -> "480p"
        "medium" -> "360p"
        "small" -> "240p"
        "tiny" -> "144p"
        "auto" -> getString(R.string.player_quality_auto)
        else -> code
    }

    private fun openVideo(item: VideoItem) {
        if (!YouTubeLinks.isVideoId(item.id)) {
            Toast.makeText(this, R.string.video_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        playingVideoId = item.id
        playingVideo = item
        refreshBookmarks()
        binding.infoTitle.text = item.title
        binding.infoChannel.text = item.channel
        binding.infoPanel.isVisible = false
        binding.playerContainer.isVisible = true
        binding.playerPlayPause.setIconResource(R.drawable.pause_24px)
        // Controls hidden by default — they appear only when you tap the video.
        setControlsVisible(false)
        // Cover the load/fullscreen transition with a black loading screen.
        binding.playerLoadingCover.isVisible = true
        controlsHandler.removeCallbacks(revealPlayerRunnable)
        controlsHandler.postDelayed(revealPlayerRunnable, 6000) // fallback reveal
        controlsHandler.removeCallbacks(playerStateRunnable)
        controlsHandler.postDelayed(playerStateRunnable, 800)
        val start = if (item.startSeconds > 0) "&t=${item.startSeconds.coerceAtMost(604_800)}s" else ""
        binding.playerWeb.loadUrl("https://m.youtube.com/watch?v=${item.id}$start")
    }

    private fun closePlayer() {
        if (!binding.playerContainer.isVisible) return
        exitWebFullscreen()
        controlsHandler.removeCallbacks(hideControlsRunnable)
        controlsHandler.removeCallbacks(playerStateRunnable)
        controlsHandler.removeCallbacks(revealPlayerRunnable)
        binding.playerLoadingCover.isVisible = false
        binding.playerWeb.loadUrl("about:blank")
        binding.playerContainer.isVisible = false
        binding.infoPanel.isVisible = false
        playingVideoId = null
        playingVideo = null
    }

    // Keeps the play/pause icon and the seek bar in sync with the actual video
    // (taps never reach YouTube's player, so only our controls change playback).
    private val playerStateRunnable = object : Runnable {
        override fun run() {
            binding.playerWeb.evaluateJavascript(
                "(function(){var v=document.querySelector('video');if(!v)return '';" +
                    "return (Math.floor(v.currentTime||0))+'|'+(Math.floor(v.duration||0))+'|'+(v.paused?1:0);})();"
            ) { r ->
                val s = r?.trim()?.removeSurrounding("\"").orEmpty()
                val parts = s.split("|")
                if (parts.size == 3) {
                    val t = parts[0].toIntOrNull() ?: 0
                    val d = parts[1].toIntOrNull() ?: 0
                    val paused = parts[2] == "1"
                    binding.playerPlayPause.setIconResource(
                        if (paused) R.drawable.play_arrow_24px else R.drawable.pause_24px
                    )
                    if (!userSeeking && d > 0) {
                        binding.playerSeek.max = d
                        binding.playerSeek.progress = t
                        binding.playerTime.text = formatTime(t)
                        binding.playerDuration.text = formatTime(d)
                    }
                }
            }
            if (binding.playerContainer.isVisible) controlsHandler.postDelayed(this, 500)
        }
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
        val next = if (idx >= 0) items.getOrNull(idx + 1) else null
        if (next != null) {
            openVideo(next)
        } else {
            Toast.makeText(this, R.string.player_no_next, Toast.LENGTH_SHORT).show()
        }
    }

    private fun forceUnmute() {
        binding.playerWeb.evaluateJavascript("window.__aaytUnmute&&window.__aaytUnmute();", null)
    }

    /** Feed a real touch into the WebView so the page's fullscreen listener (which
     *  requires a user gesture) can enter fullscreen automatically. */
    private fun synthTapForFullscreen() {
        if (fullscreenView != null) return
        val w = binding.playerWeb.width
        val h = binding.playerWeb.height
        if (w == 0 || h == 0) return
        val now = android.os.SystemClock.uptimeMillis()
        val x = w / 2f
        val y = h * 0.35f
        val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val up = android.view.MotionEvent.obtain(now, now + 60, android.view.MotionEvent.ACTION_UP, x, y, 0)
        allowWebTouch = true
        binding.playerWeb.dispatchTouchEvent(down)
        binding.playerWeb.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        binding.playerWeb.postDelayed({ allowWebTouch = false }, 150)
    }

    private fun toggleControls() {
        forceUnmute()
        setControlsVisible(!controlsVisible)
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        binding.playerControls.isVisible = visible
        binding.playerSeekRow.isVisible = visible
        binding.playerClose.isVisible = visible
        binding.playerInfo.isVisible = visible
        binding.playerSave.isVisible = visible
        if (!visible) binding.infoPanel.isVisible = false
        controlsHandler.removeCallbacks(hideControlsRunnable)
        if (visible) controlsHandler.postDelayed(hideControlsRunnable, 4000)
    }

    private fun nudgeControls() {
        controlsHandler.removeCallbacks(hideControlsRunnable)
        controlsHandler.postDelayed(hideControlsRunnable, 4000)
    }

    private fun enterWebFullscreen(view: View?, callback: WebChromeClient.CustomViewCallback?) {
        if (view == null) {
            callback?.onCustomViewHidden()
            return
        }
        fullscreenView = view
        fullscreenCallback = callback
        // Add into the VIDEO layer — always below the overlay, so the overlay keeps
        // owning taps (video never receives them → never auto-plays/pauses).
        binding.playerVideoHost.addView(view, FrameLayout.LayoutParams(-1, -1))
        // Now that it's truly fullscreen, reveal (drop the black loading cover)
        // after a short beat so the first frame is ready.
        controlsHandler.removeCallbacks(revealPlayerRunnable)
        controlsHandler.postDelayed(revealPlayerRunnable, 350)
    }

    private val revealPlayerRunnable = Runnable { binding.playerLoadingCover.isVisible = false }

    private fun exitWebFullscreen() {
        val view = fullscreenView ?: return
        binding.playerVideoHost.removeView(view)
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
        btn(R.id.menuBtnSaved).setOnClickListener { dialog.dismiss(); showSection(Section.SAVED) }
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
        controlsHandler.removeCallbacksAndMessages(null)
        binding.playerWeb.destroy()
        super.onDestroy()
    }

    companion object {
        // Tidy the watch page (hide nav chrome) and start playback. We do NOT
        // force a fullscreen overlay here — that caused a black screen. The real
        // player's own fullscreen button drives onShowCustomView for true
        // fullscreen, and being signed in it plays Premium (no ads).
        // style.textContent is not a Trusted-Types sink, so this is CSP-safe.
        // Only hides page chrome and starts playback — never repositions the
        // <video> element (doing so breaks its hardware surface → black screen).
        // Fullscreen is achieved via the browser's real fullscreen (below).
        private const val PLAYER_SKIN_JS = """
(function(){
  try{
    var id='aayt-player-skin';
    var s=document.getElementById(id)||document.createElement('style'); s.id=id;
    // Hide page chrome + YouTube's own controls. Never resize/reposition the
    // <video> or its player (that blacks out the hardware surface on some devices).
    s.textContent='html,body{background:#000!important}'+
      'ytm-pivot-bar-renderer,.pivot-bar,ytm-mobile-topbar-renderer,.mobile-topbar-renderer,'+
      'ytm-item-section-renderer,ytm-comment-section-renderer,#comments,'+
      'ytm-single-column-watch-next-results-renderer,.related-chips-slot-wrapper,'+
      '.slim-video-metadata-section{display:none!important}'+
      '.ytp-chrome-bottom,.ytp-chrome-top,.ytp-gradient-bottom,.ytp-gradient-top,'+
      '.player-controls-content,.ytm-player-control-overlay{display:none!important}';
    (document.head||document.documentElement).appendChild(s);
  }catch(e){}
  // One real tap on the video enters true fullscreen (onShowCustomView), which
  // fills the pane with a correctly-rendered surface (no black, no CSS resize).
  try{
    if(!window.__aaytFsInit){
      window.__aaytFsInit=true;
      document.addEventListener('click',function(){
        if(!document.fullscreenElement){
          var p=document.getElementById('movie_player'), v=document.querySelector('video');
          var el=(p&&p.requestFullscreen)?p:v;
          if(el&&el.requestFullscreen){ el.requestFullscreen().catch(function(){}); }
        }
      },true);
    }
  }catch(e){}
  // Persistent unmute: YouTube autostarts muted and re-mutes on JS unmute, so we
  // force it back on every volumechange, call the player's unMute(), and click
  // the 'tap to unmute' pill — repeatedly for the first several seconds.
  try{
    if(!window.__aaytUnmuteInit){
      window.__aaytUnmuteInit=true;
      document.addEventListener('volumechange',function(e){
        var t=e.target; if(t&&t.tagName==='VIDEO'&&t.muted){ t.muted=false; }
      },true);
    }
    window.__aaytUnmute=function(){
      // Unmute only — never resume playback (that fought the user's pause).
      var v=document.querySelector('video');
      if(v){ if(v.muted) v.muted=false; try{v.volume=1;}catch(e){} }
      var p=document.getElementById('movie_player');
      if(p&&p.unMute){ try{p.unMute();p.setVolume(100);}catch(e){} }
      var pill=document.querySelector('.ytp-unmute,button[aria-label*="unmute" i],button[aria-label*="Unmute" i],.ytm-unmute-banner button');
      if(pill){ try{pill.click();}catch(e){} }
    };
    window.__aaytUnmute();
    if(!window.__aaytUnmuteTimer){
      var n=0; window.__aaytUnmuteTimer=setInterval(function(){
        window.__aaytUnmute(); if(++n>15){ clearInterval(window.__aaytUnmuteTimer); window.__aaytUnmuteTimer=null; }
      },600);
    }
  }catch(e){}
})();
"""
    }
}
