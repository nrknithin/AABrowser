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
                    // Once the flow lands back on YouTube with a session, we're done.
                    val host = runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty()
                    if (host.endsWith("youtube.com") && hasSession()) finishWithHarvest()
                }
            }
            // Google blocks sign-in on accounts.google.com inside a WebView
            // ("browser not secure"), so we go to YouTube's own profile/account
            // page which offers sign-in directly within the allowed flow.
            loadUrl("https://m.youtube.com/account")
        }

        binding.loginHint.text = getString(R.string.login_hint)

        // Fallback cancel, kept clear of the system nav bar.
        binding.loginCancel.setOnClickListener { finish() }
    }

    private fun hasSession(): Boolean {
        val cm = CookieManager.getInstance()
        val cookies = cm.getCookie("https://www.youtube.com").orEmpty() +
            cm.getCookie("https://www.google.com").orEmpty()
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
        val cm = CookieManager.getInstance()
        val yt = cm.getCookie("https://www.youtube.com").orEmpty()
        val google = cm.getCookie("https://www.google.com").orEmpty()
        val merged = listOf(yt, google).filter { it.isNotBlank() }.joinToString("; ")
        YouTubeAuth.updateFromCookieString(merged)
    }
}
