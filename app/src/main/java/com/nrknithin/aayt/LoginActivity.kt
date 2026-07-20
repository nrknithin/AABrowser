package com.nrknithin.aayt

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.nrknithin.aayt.data.YouTubeAuth
import com.nrknithin.aayt.databinding.ActivityLoginBinding

/**
 * The only WebView in the app: signs the user into YouTube so the session
 * cookies land in Android's CookieManager. [MainActivity] then harvests them
 * into [YouTubeAuth] and all further requests are native, authenticated
 * Innertube calls. Cookies persist across launches, so login is a one-time step.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

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
            // Keep the whole login flow inside this WebView — never hand a
            // Google/YouTube sign-in URL off to an external browser or app.
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme?.lowercase()
                    // Load http/https here; block app deep links (intent://, market://…).
                    return scheme != null && scheme != "http" && scheme != "https"
                }
            }
            loadUrl("https://m.youtube.com/")
        }

        binding.loginDone.setOnClickListener { finish() }
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        harvestCookies()
        super.onDestroy()
    }

    private fun harvestCookies() {
        val yt = CookieManager.getInstance().getCookie("https://www.youtube.com").orEmpty()
        val google = CookieManager.getInstance().getCookie("https://www.google.com").orEmpty()
        val merged = listOf(yt, google).filter { it.isNotBlank() }.joinToString("; ")
        YouTubeAuth.updateFromCookieString(merged)
    }
}
