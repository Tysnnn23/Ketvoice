package com.ketvoice.app

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import com.ketvoice.app.ui.common.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ketvoice.app.data.YouTubeMusicSession
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.theme.LocalTheme
import kotlinx.coroutines.launch

/** First-party web session. No password fields, injected login scripts, or JS bridge. */
class YouTubeLoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KetvoiceTheme {
                val theme = LocalTheme.current
                val scope = rememberCoroutineScope()
                var webView by remember { mutableStateOf<WebView?>(null) }
                var checking by remember { mutableStateOf(false) }
                BackHandler { if (webView?.canGoBack() == true) webView?.goBack() else finish() }
                Column(Modifier.fillMaxSize().background(theme.background).systemBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Fermer", color = theme.accent, modifier = Modifier.clickable { finish() })
                        Spacer(Modifier.weight(1f))
                        Text("Session YouTube Music", color = theme.onBackground, fontWeight = FontWeight.Bold)
                    }
                    Text("Connexion sur le site Google uniquement. Après connexion, ouvrez YouTube Music puis confirmez ci-dessous. Google peut refuser les navigateurs intégrés ; aucun compte n'est alors déclaré connecté.",
                        color = theme.secondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    AndroidView(factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.safeBrowsingEnabled = true
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val host = request.url.host.orEmpty()
                                    val allowed = request.url.scheme == "https" && (host == "music.youtube.com" || host == "www.youtube.com" ||
                                        host == "accounts.youtube.com" || host == "google.com" || host.endsWith(".google.com"))
                                    return !allowed
                                }
                            }
                            webView = this
                            loadUrl("https://music.youtube.com/")
                        }
                    }, modifier = Modifier.weight(1f).fillMaxWidth(), onRelease = { it.destroy() })
                    Text(if (checking) "Vérification de la session…" else "Utiliser cette session et synchroniser mes écoutes",
                        color = theme.accent, fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = !checking) {
                            val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
                            checking = true
                            scope.launch {
                                val ok = runCatching { YouTubeMusicSession.validate(cookie) }.getOrDefault(false)
                                checking = false
                                if (ok) { CookieManager.getInstance().flush(); finish() }
                        else Toast.makeText(this@YouTubeLoginActivity, com.ketvoice.app.data.AppText.t("Google n'a pas confirmé de session connectée."), Toast.LENGTH_LONG).show()
                            }
                        }.padding(18.dp))
                }
            }
        }
    }
}
