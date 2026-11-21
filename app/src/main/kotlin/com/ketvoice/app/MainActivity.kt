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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.ketvoice.app.ui.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.KetvoiceTheme
import com.ketvoice.app.ui.AppRoot
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.SplashBrand
import com.ketvoice.app.util.Crash
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private companion object {
        const val STATE_NAVIGATION = "ketvoice.navigation"
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.ketvoice.app.data.AppLocales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        Crash.install(this)
        // 6.1.1: two launches in a row closed by a crash → a plain screen with the report instead of the app.
        if (savedInstanceState == null && runCatching { com.ketvoice.app.util.CrashLoop.beginLaunch(this) }.getOrDefault(false)) {
            super.onCreate(savedInstanceState)
            startActivity(Intent(this, SafeModeActivity::class.java))
            finish()
            return
        }
        android.os.Handler(mainLooper).postDelayed({ runCatching { com.ketvoice.app.util.CrashLoop.steady(this) } }, com.ketvoice.app.util.CrashLoop.STEADY_MS)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // After Android reclaimed the process, come back to the page the user was on.
        if (savedInstanceState != null) com.ketvoice.app.ui.AppUi.restoreNavigationOnce(savedInstanceState.getString(STATE_NAVIGATION))
        else com.ketvoice.app.ui.AppUi.markNavigationFresh()

        runCatching { com.ketvoice.app.data.AppLocales.sync(this) }
        runCatching { com.ketvoice.app.data.SettingsStore.load(this) }
            .onFailure { android.util.Log.e("KetvoiceSettings", "Could not restore settings", it) }
        runCatching { com.ketvoice.app.data.YouTubeMusicSession.init(this) }
        runCatching { com.ketvoice.app.data.PublisherSession.init(this) }
        runCatching { com.ketvoice.app.data.CustomSources.init(this) }
        runCatching { com.ketvoice.app.data.ForYouRotation.init(this) }
        runCatching { com.ketvoice.app.data.ArtistPrefs.init(this) }
        runCatching { com.ketvoice.app.data.CustomLyrics.init(this) }
        // The Ketvoice account: a remembered session is renewed and the library synced; nothing is sent otherwise.
        runCatching { com.ketvoice.app.account.AccountSession.init(this) }
        // Faster start (5.9.0): what the first screen does not need is read beside it, off the main thread —
        // the lyrics index (up to a few megabytes) and the launcher icon check (calls to the package manager).
        val app = applicationContext
        val iconVariant = com.ketvoice.app.data.AppSettings.appIconVariant.value
        Thread({
            runCatching { com.ketvoice.app.data.LyricsSearch.init(app) }
            runCatching { com.ketvoice.app.util.LauncherIcons.apply(app, iconVariant) }
        }, "ketvoice-startup").apply { priority = Thread.NORM_PRIORITY - 1 }.start()
        runCatching { com.ketvoice.app.data.SmartOffline.init(this) }
        com.ketvoice.app.data.FavoritesOffline.start(applicationContext)

        try {
            Player.connect(applicationContext)
        } catch (_: Exception) {
        }
        // 6.1: music that Android ended a moment ago comes back when the app is opened (when nothing brought it back before).
        if (savedInstanceState == null) lifecycleScope.launch {
            runCatching { com.ketvoice.app.player.PlaybackRevival.check(applicationContext, com.ketvoice.app.player.PlaybackRevival.WAY_APP) }
        }

        runCatching { com.ketvoice.app.discord.Discord.attach(this) }
            .onFailure { android.util.Log.w("KetvoiceDiscord", "Discord init failed", it) }
        // The Discord SDK starts once the first frame is drawn: the app shows itself first.
        window.decorView.post {
            runCatching { com.ketvoice.app.discord.Discord.start() }
                .onFailure { android.util.Log.w("KetvoiceDiscord", "Discord init failed", it) }
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
                var splash by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(950)
                    splash = false
                }
                Box(Modifier.fillMaxSize()) {
                    val crashReport = CrashReportsDialog()
                    // While a welcome is up (first launch, a season), TalkBack reads it alone.
                    val onboarding = com.ketvoice.app.data.FirstRun.pending.value
                    val welcome = onboarding ||
                        com.ketvoice.app.ui.seasons.SeasonWelcomeGate.visible.value != null
                    Box(Modifier.fillMaxSize().then(if (welcome) Modifier.clearAndSetSemantics { } else Modifier)) {
                        AppRoot()
                    }
                    com.ketvoice.app.ui.update.UpdateHost()
                    // 7.5: the ambient Now Playing while charging in landscape (off unless the listener turned it on).
                    com.ketvoice.app.ui.standby.StandbyHost()
                    // The first-launch welcome of a new install (6.2) comes first, then a season's offer (6.2):
                    // after the splash, never over a crash report.
                    com.ketvoice.app.ui.onboarding.OnboardingHost(ready = !splash && !crashReport)
                    com.ketvoice.app.ui.seasons.SeasonWelcomeHost(ready = !splash && !crashReport && !onboarding)
                    AnimatedVisibility(
                        visible = splash,
                        exit = fadeOut(tween(360))
                    ) {
                        SplashBrand(Modifier.fillMaxSize())
                    }
                }
            }
        }
        handleAuthIntent(intent)
        handleWidgetIntent(intent)
        com.ketvoice.app.player.AutoVoice.handle(this, intent, lifecycleScope)
        handleResumeIntent(intent)
        handleReleaseIntent(intent)
    }

    /** The notification of a new release was touched (6.3): the artist's page opens. */
    private fun handleReleaseIntent(intent: Intent?) {
        if (intent?.action != "ketvoice.release.open") return
        val id = intent.getStringExtra(com.ketvoice.app.data.ReleaseWatch.EXTRA_ARTIST_ID).orEmpty()
        val name = intent.getStringExtra(com.ketvoice.app.data.ReleaseWatch.EXTRA_ARTIST_NAME).orEmpty()
        intent.action = null
        com.ketvoice.app.ui.AppUi.openArtist(id, name)
    }

    /** The notification of a playback Android stopped was touched (6.1): it resumes, once. */
    private fun handleResumeIntent(intent: Intent?) {
        if (intent?.action != com.ketvoice.app.player.PlaybackRevival.ACTION_TAP) return
        intent.action = null
        lifecycleScope.launch { runCatching { com.ketvoice.app.player.PlaybackRevival.resumeAsked(applicationContext) } }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_NAVIGATION, com.ketvoice.app.ui.AppUi.saveNavigation())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAuthIntent(intent)
        handleWidgetIntent(intent)
        com.ketvoice.app.player.AutoVoice.handle(this, intent, lifecycleScope)
        handleResumeIntent(intent)
        handleReleaseIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        runCatching { com.ketvoice.app.discord.Discord.onForeground() }
    }

    /** A home-screen widget, a tile or the lyrics notification opened the app: the player, or its lyrics. */
    private fun handleWidgetIntent(intent: Intent?) {
        intent ?: return
        val open = intent.getStringExtra(com.ketvoice.app.widget.WidgetIntents.OPEN)
        intent.removeExtra(com.ketvoice.app.widget.WidgetIntents.OPEN)
        // The lock screen lyrics notification (6.1): the player, on its lyrics.
        val playing = com.ketvoice.app.player.Player.state.value
        if (open == com.ketvoice.app.widget.WidgetIntents.OPEN_LYRICS && playing.current != null) {
            AppUi.showPlayer.value = true
            AppUi.showLyrics.value = true
        }
        // A widget (7.4): the player; the play tile without a queue resumes what was loaded.
        if (open == com.ketvoice.app.widget.WidgetIntents.OPEN_PLAYER && playing.current != null) AppUi.showPlayer.value = true
        if (open == com.ketvoice.app.widget.WidgetIntents.OPEN_PLAY && playing.current != null) {
            if (!playing.playWhenReady) com.ketvoice.app.player.Player.toggle()
            AppUi.showPlayer.value = true
        }
    }

    private fun handleAuthIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        // 6.3: an invitation to a shared playlist (ketvoice://join/<code>) and a profile (ketvoice://u/<handle>).
        com.ketvoice.app.sync.SharedLinks.joinCode(uri.toString())?.let { code ->
            intent.data = null
            com.ketvoice.app.ui.library.SharedUi.openJoin(code)
            return
        }
        com.ketvoice.app.sync.SharedLinks.profileHandle(uri.toString())?.let { handle ->
            intent.data = null
            com.ketvoice.app.ui.account.ProfileUi.open(handle)
            return
        }
        val supabaseLink = uri.scheme == "https" &&
            uri.host == com.ketvoice.app.data.PublisherAccess.HOST &&
            uri.path == "/auth/v1/verify"
        val appLink = uri.scheme == "ketvoice" && uri.host == "auth"
        when {
            com.ketvoice.app.account.AccountSession.isOAuthRedirect(uri) -> {
                intent.data = null
                com.ketvoice.app.ui.account.AccountUi.completeOAuth(uri)
            }
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
        if (kind == "airket") {
            // Air Ket (7.0): the QR code of the old phone, opened with the camera; the receive screen asks before it connects.
            com.ketvoice.app.airket.AirKet.openReceive(id, uri.getQueryParameter("h"), uri.getQueryParameter("p")?.toIntOrNull() ?: 0)
            return
        }
        if (kind == com.ketvoice.app.together.TogetherLink.KIND) {
            // Ketogether (6.1): a session's link or QR code; the sheet asks before joining.
            com.ketvoice.app.together.TogetherLink.parse(id)?.let(com.ketvoice.app.together.Ketogether::openLink)
            return
        }
        lifecycleScope.launch {
            AppUi.closeOverlays()
            if (kind == "album") {
                AppUi.openCatalog(com.ketvoice.app.data.CatalogRequest("album", id, title))
                return@launch
            }
            if (kind == "artist") {
                // The website's public artist page (7.0): « Ouvrir dans Ketvoice » sends the artist's name in `artist`.
                AppUi.openArtist(id, artist.ifBlank { title })
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

    override fun onStart() {
        super.onStart()
        com.ketvoice.app.automix.Automix.appInFront = true
        com.ketvoice.app.sync.LibrarySync.onForeground()
        com.ketvoice.app.sync.NowPlayingSync.refresh()
        com.ketvoice.app.remote.PhoneRemote.watch(this)
        com.ketvoice.app.remote.PhoneRemote.inFront(true)
        // A season may end while the app lives: its theme then goes back, silently.
        if (com.ketvoice.app.data.Seasons.expire()) runCatching { com.ketvoice.app.data.SettingsStore.save() }
    }

    override fun onStop() {
        super.onStop()
        com.ketvoice.app.automix.Automix.appInFront = false
        runCatching { com.ketvoice.app.data.SettingsStore.save() }
        com.ketvoice.app.sync.LibrarySync.onBackground()
        com.ketvoice.app.remote.PhoneRemote.inFront(false)
    }
}

/** The crash reports left by an earlier run; returns whether the dialog is on screen. */
@Composable
private fun CrashReportsDialog(): Boolean {
    val activity = androidx.activity.compose.LocalActivity.current ?: return false
    val pending = remember { Crash.pending(activity) }
    var index by remember { mutableIntStateOf(0) }
    var visible by remember { mutableStateOf(pending.isNotEmpty()) }
    if (!visible || pending.isEmpty()) return false
    val report = remember(index) {
        runCatching { pending[index].readText() }.getOrDefault("(rapport illisible)")
    }
    com.ketvoice.app.ui.common.AppAlertDialog(
        onDismissRequest = { visible = false },
        title = { Text("Rapport de crash") },
        text = {
            Text(
                report,
                localize = false,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                modifier = androidx.compose.ui.Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())
            )
        },
        confirmButton = {
            com.ketvoice.app.ui.common.KetTextButton("Copier le rapport", primary = true, onClick = {
                runCatching {
                    val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("ketvoice_crash", report))
                        Toast.makeText(activity, com.ketvoice.app.data.AppText.t("Copié — colle-le dans la conversation"), Toast.LENGTH_SHORT).show()
                }
                Crash.clearAll(activity)
                visible = false
            })
        },
        dismissButton = {
            com.ketvoice.app.ui.common.KetTextButton("Effacer", { Crash.clearAll(activity); visible = false }, destructive = true)
        }
    )
    return true
}
