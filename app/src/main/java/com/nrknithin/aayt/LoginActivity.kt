package com.nrknithin.aayt

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.nrknithin.aayt.data.YouTubeAuth
import com.nrknithin.aayt.databinding.ActivityLoginBinding

/**
 * The app's only login surface: opens Google sign-in directly. When the flow
 * redirects back to YouTube with a session cookie, we harvest it into
 * [YouTubeAuth] and finish automatically — no confirmation button. Cookies
 * persist, so this is a one-time step.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private var finished = false
    private val profileMode by lazy { intent.getBooleanExtra(EXTRA_PROFILE, false) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        CookieManager.getInstance().setAcceptCookie(true)
        with(binding.loginWeb) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/149.0.0.0 Mobile Safari/537.36"
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                    val scheme = request.url.scheme?.lowercase()
                    return scheme != null && scheme != "http" && scheme != "https"
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    super.onPageFinished(view, url)
                    // In profile mode we stay open so the user can view/manage
                    // their account; otherwise auto-close once a session appears.
                    if (profileMode) return
                    val host = runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty()
                    if (host.endsWith("youtube.com") && hasSession()) finishWithHarvest()
                }
            }
            // The Library hub for both: signed in it shows the profile/library;
            // signed out YouTube redirects it to its own sign-in flow (which works
            // in a WebView, unlike accounts.google.com directly).
            loadUrl("https://m.youtube.com/feed/library")
        }

        binding.loginHint.text =
            getString(if (profileMode) R.string.profile_hint else R.string.login_hint)

        // Fallback cancel, kept clear of the system nav bar.
        binding.loginCancel.setOnClickListener { finish() }
    }

    private fun hasSession(): Boolean {
        val cookies = CookieManager.getInstance().getCookie("https://www.youtube.com").orEmpty()
        return cookies.contains("SAPISID") || cookies.contains("__Secure-3PAPISID")
    }

    private fun finishWithHarvest() {
        if (finished) return
        finished = true
        harvestCookies()
        finish()
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        harvestCookies()
        super.onDestroy()
    }

    private fun harvestCookies() {
        val yt = CookieManager.getInstance().getCookie("https://www.youtube.com").orEmpty()
        YouTubeAuth.updateFromCookieString(yt)
    }

    companion object {
        const val EXTRA_PROFILE = "profile"
    }
}
