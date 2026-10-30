package com.ketvoice.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import com.ketvoice.app.ui.common.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.AppRoot
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.util.Crash
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.ketvoice.app.data.AppLocales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        Crash.install(this)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        runCatching { com.ketvoice.app.data.SettingsStore.load(this) }
            .onFailure { android.util.Log.e("KetvoiceSettings", "Could not restore settings", it) }
        runCatching { com.ketvoice.app.data.YouTubeMusicSession.init(this) }
        runCatching { com.ketvoice.app.data.PublisherSession.init(this) }
        runCatching { com.ketvoice.app.data.CustomSources.init(this) }
        runCatching {
            com.ketvoice.app.util.LauncherIcons.apply(
                this,
                com.ketvoice.app.data.AppSettings.appIconVariant.value
            )
        }

        try {
            Player.connect(applicationContext)
        } catch (_: Exception) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4123
            )
        }

        setContent {
            val view = LocalView.current
            val playback by Player.state.collectAsState()
            val keepAwake = BuildConfig.IS_QUEST && com.ketvoice.app.data.AppSettings.questKeepAwake.value && playback.playWhenReady
            DisposableEffect(view, keepAwake) {
                view.keepScreenOn = keepAwake
                onDispose { view.keepScreenOn = false }
            }
            val dark = isSystemInDarkTheme()

            KetvoiceTheme(dark) {
                CrashReportsDialog()
                AppRoot()
                com.ketvoice.app.ui.update.UpdateHost()
            }
        }
        handleAuthIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthIntent(intent)
    }

    private fun handleAuthIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        val supabaseLink = uri.scheme == "https" &&
            uri.host == com.ketvoice.app.data.PublisherAccess.HOST &&
            uri.path == "/auth/v1/verify"
        val appLink = uri.scheme == "ketvoice" && uri.host == "auth"
        when {
            supabaseLink || appLink -> {
                AppUi.openPublisherLink(uri.toString())
                intent.data = null
            }
            isListenLink(uri) -> {
                openSharedTrack(uri)
                intent.data = null
            }
        }
    }

    private fun isListenLink(uri: android.net.Uri): Boolean =
        (uri.scheme == "ketvoice" && uri.host == "listen") ||
            (uri.scheme == "https" && uri.host == "tysnnn23.github.io" &&
                uri.path.orEmpty().startsWith("/Ketvoice/listen"))

    /**
     * Lien de partage ouvert (depuis ketvoice://listen ou listen.html) :
     * résout le morceau — connu localement, publication importée, sinon
     * recherche par titre/artiste — puis lance la lecture. `kind=album`
     * ouvre la page de l'album.
     */
    private fun openSharedTrack(uri: android.net.Uri) {
        val id = uri.getQueryParameter("track")?.trim().orEmpty()
        if (id.isEmpty()) return
        val title = uri.getQueryParameter("title")?.trim().orEmpty()
        val artist = uri.getQueryParameter("artist")?.trim().orEmpty()
        val kind = uri.getQueryParameter("kind")?.trim().orEmpty()
        lifecycleScope.launch {
            AppUi.closeOverlays()
            if (kind == "album") {
                AppUi.openCatalog(com.ketvoice.app.data.CatalogRequest("album", id, title))
                return@launch
            }
            val resolved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                resolveSharedTrack(id, title, artist)
            }
            if (resolved != null) {
                Player.playTrack(resolved)
                Toast.makeText(
                    this@MainActivity,
                    com.ketvoice.app.data.AppText.t("Lecture partagée : ${resolved.title}"),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this@MainActivity,
                    com.ketvoice.app.data.AppText.t("Morceau partagé introuvable"),
                    Toast.LENGTH_SHORT
                ).show()
                AppUi.openSearch(listOf(title, artist).filter { it.isNotBlank() }.joinToString(" "))
            }
        }
    }

    private suspend fun resolveSharedTrack(id: String, title: String, artist: String): com.ketvoice.app.data.Track? {
        val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
        // 1) Déjà connu localement (historique, favoris, téléchargements).
        com.ketvoice.app.data.ListeningHistory.knownTracks.value.firstOrNull { it.id == id }?.let { return it }
        com.ketvoice.app.data.Downloader.items.value.firstOrNull { it.track.id == id }?.let { return it.track }
        // 2) Recherche : match exact d'id d'abord, meilleur titre ensuite.
        val results = buildList {
            if (query.isNotBlank()) addAll(runCatching { com.ketvoice.app.data.KetvoiceApi.search(query) }.getOrDefault(emptyList()))
            if (query.isNotBlank()) addAll(runCatching { com.ketvoice.app.data.MusicApi.searchAll(query) }.getOrDefault(emptyList()))
        }
        results.firstOrNull { it.id == id }?.let { return it }
        results.firstOrNull {
            it.title.equals(title, ignoreCase = true) &&
                (artist.isBlank() || it.artist.contains(artist, ignoreCase = true))
        }?.let { return it }
        // 3) Dernier recours : reconstruire depuis le lien (videoIdYouTube),
        //    le pipeline de résolution de flux complète le reste.
        if (id.startsWith("yt:")) {
            val videoId = id.removePrefix("yt:")
            if (videoId.length == 11) return com.ketvoice.app.data.Track(
                id = id, title = title.ifBlank { "Lecture partagée" }, artist = artist,
                album = "", videoId = videoId, youtubeCatalogVerified = true
            )
        }
        return null
    }

    override fun onStop() {
        super.onStop()
        runCatching { com.ketvoice.app.data.SettingsStore.save() }
    }
}

@Composable
private fun CrashReportsDialog() {
    val activity = androidx.activity.compose.LocalActivity.current ?: return
    val pending = remember { Crash.pending(activity) }
    var index by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(pending.isNotEmpty()) }
    if (!visible || pending.isEmpty()) return
    val report = remember(index) {
        runCatching { pending[index].readText() }.getOrDefault("(rapport illisible)")
    }
    AlertDialog(
        onDismissRequest = { visible = false },
        title = { Text("Rapport de crash") },
        text = {
            Text(
                report,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching {
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("ketvoice_crash", report))
                        Toast.makeText(activity, com.ketvoice.app.data.AppText.t("Copié — colle-le dans la conversation"), Toast.LENGTH_SHORT).show()
                }
                Crash.clearAll(activity)
                visible = false
            }) { Text("Copier le rapport") }
        },
        dismissButton = {
            TextButton(onClick = { Crash.clearAll(activity); visible = false }) { Text("Effacer") }
        }
    )
}
