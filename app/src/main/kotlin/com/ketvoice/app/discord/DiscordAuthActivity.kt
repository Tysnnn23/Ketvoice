package com.ketvoice.app.discord

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Discord OAuth2 inside a WebView owned by Ketvoice. Navigating to the custom
 * `discord-ID:/authorize/callback` redirect is intercepted before Android has to
 * resolve it, which is exactly where the browser and the Discord app give up.
 */
class DiscordAuthActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var status: TextView
    private var authorizeUrl = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(13, 13, 18)
        window.navigationBarColor = Color.rgb(13, 13, 18)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(13, 13, 18))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(10), dp(10))
        }
        val title = TextView(this).apply {
            text = "Connexion à Discord"
            setTextColor(Color.WHITE)
            textSize = 16f
        }
        val browser = TextView(this).apply {
            text = "Navigateur"
            setTextColor(Color.rgb(168, 171, 184))
            textSize = 13f
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener {
                if (authorizeUrl.isNotBlank()) {
                    runCatching {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(authorizeUrl))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        }
        val close = Button(this).apply {
            text = "✕"
            setBackgroundColor(Color.TRANSPARENT)
            setTextColor(Color.WHITE)
            textSize = 18f
            setOnClickListener { finish() }
        }
        bar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(browser, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        bar.addView(close, LinearLayout.LayoutParams(dp(48), dp(44)))

        status = TextView(this).apply {
            setTextColor(Color.rgb(168, 171, 184))
            textSize = 12f
            setPadding(dp(20), 0, dp(20), dp(10))
            text = "Chargement de Discord…"
        }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.userAgentString =
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
            setBackgroundColor(Color.rgb(13, 13, 18))
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean = handle(request.url)

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    if (request.isForMainFrame) fail(error.description?.toString().orEmpty())
                }
            }
        }

        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }
        authorizeUrl = url
        web.loadUrl(url)
    }

    private fun handle(uri: Uri): Boolean {
        val url = uri.toString()
        if (url.startsWith("discord-${DiscordOAuth.APPLICATION_ID}")) {
            Discord.completeAuthorization(uri)
            finish()
            return true
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            // External Discord links are opened outside the WebView.
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            return true
        }
        return false
    }

    private fun fail(message: String) {
        status.text = "Impossible de charger Discord. ${message.ifBlank { "Vérifie la connexion." }}"
        web.visibility = android.view.View.GONE
    }

    override fun onBackPressed() {
        if (::web.isInitialized && web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            runCatching { web.stopLoading(); web.destroy() }
        }
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_URL = "authorize_url"
    }
}
