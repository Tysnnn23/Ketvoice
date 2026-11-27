package com.ketvoice.app.tv

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.ketvoice.app.player.Player
import com.ketvoice.app.util.Crash

/**
 * Ketvoice TV's only activity: the 10-foot interface over the phone's shared code — the same account and encrypted
 * session, library sync, playback service and media session (the remote's play/pause, the TV's Now Playing card),
 * downloads and Ketmix. What the TV can afford is read once ([TvCapability]) and sets the first defaults.
 */
class TvActivity : ComponentActivity() {
    private val actions = TvActions(TvNavigator())

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.ketvoice.app.data.AppLocales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Crash.install(this)
        // Two launches in a row closed by a crash: a plain screen with the report instead (as on the phone, 6.1.1).
        if (savedInstanceState == null && runCatching { com.ketvoice.app.util.CrashLoop.beginLaunch(this) }.getOrDefault(false)) {
            super.onCreate(savedInstanceState)
            startActivity(Intent(this, com.ketvoice.app.SafeModeActivity::class.java))
            finish()
            return
        }
        android.os.Handler(mainLooper).postDelayed({ runCatching { com.ketvoice.app.util.CrashLoop.steady(this) } },
            com.ketvoice.app.util.CrashLoop.STEADY_MS)
        super.onCreate(savedInstanceState)
        TvCapability.init(this)
        TvDefaults.apply(getSharedPreferences(TvDefaults.PREFS, MODE_PRIVATE), TvCapability.current)
        startShared()
        // 1.1 profiles: the book follows the account in use; « Qui écoute ? » at launch when the TV is shared.
        runCatching { TvProfiles.watch(this) }
        if (savedInstanceState == null) runCatching { TvProfiles.launch() }
        setContent { TvApp(actions, TvCapability.current, onExit = ::finish) }
        handle(intent)
    }

    /** The shared code the phone's MainActivity starts, minus what a TV has no use for (Discord, launcher icons, battery screens). */
    private fun startShared() {
        runCatching { com.ketvoice.app.data.AppLocales.sync(this) }
        runCatching { com.ketvoice.app.data.SettingsStore.load(this) }
        runCatching { com.ketvoice.app.data.CustomSources.init(this) }
        runCatching { com.ketvoice.app.data.ForYouRotation.init(this) }
        runCatching { com.ketvoice.app.data.ArtistPrefs.init(this) }
        runCatching { com.ketvoice.app.data.CustomLyrics.init(this) }
        // The account: a remembered session is renewed and the library synced; nothing is sent otherwise.
        runCatching { com.ketvoice.app.account.AccountSession.init(this) }
        val app = applicationContext
        Thread({ runCatching { com.ketvoice.app.data.LyricsSearch.init(app) } }, "ketvoice-tv-startup")
            .apply { priority = Thread.NORM_PRIORITY - 1 }.start()
        runCatching { com.ketvoice.app.data.FavoritesOffline.start(app) }
        runCatching { Player.connect(app) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** « Joue X sur Ketvoice » from the assistant, a search asked from the TV, or the media session's card. */
    private fun handle(intent: Intent?) {
        intent ?: return
        if (com.ketvoice.app.player.AutoVoice.handle(this, intent, lifecycleScope)) return
        if (intent.action == Intent.ACTION_SEARCH) {
            intent.getStringExtra(android.app.SearchManager.QUERY)?.takeIf { it.isNotBlank() }?.let(actions::search)
            intent.action = null
            return
        }
        // Opened from the media session or a notification while something plays: Now Playing.
        if (intent.action == null && intent.categories.isNullOrEmpty() && Player.state.value.current != null) actions.nav.open(TvRoute.NowPlaying)
    }

    override fun onStart() {
        super.onStart()
        com.ketvoice.app.automix.Automix.appInFront = true
        com.ketvoice.app.sync.LibrarySync.onForeground()
        com.ketvoice.app.sync.NowPlayingSync.refresh()
        // Phone control (8.1): the listener's phone can play, pause and skip on this TV through the account.
        com.ketvoice.app.remote.PhoneRemote.watch(this)
        com.ketvoice.app.remote.PhoneRemote.inFront(true)
    }

    override fun onStop() {
        super.onStop()
        com.ketvoice.app.automix.Automix.appInFront = false
        runCatching { com.ketvoice.app.data.SettingsStore.save() }
        com.ketvoice.app.sync.LibrarySync.onBackground()
        com.ketvoice.app.remote.PhoneRemote.inFront(false)
    }
}

/**
 * The TV's first defaults, written once before the settings are read: Ketmix's analysis and the performance mode
 * follow the TV's tier. Both are device settings (never synced), and a value already saved is never touched.
 */
object TvDefaults {
    const val PREFS = "ketvoice_settings"

    fun apply(prefs: android.content.SharedPreferences, tier: TvTier) {
        val edit = prefs.edit()
        var changed = false
        if (!prefs.contains("ketmixAnalysis")) { edit.putInt("ketmixAnalysis", tier.ketmixAnalysis); changed = true }
        if (!prefs.contains("highPerformanceMode")) { edit.putBoolean("highPerformanceMode", tier.performanceMode); changed = true }
        if (changed) edit.apply()
    }
}
