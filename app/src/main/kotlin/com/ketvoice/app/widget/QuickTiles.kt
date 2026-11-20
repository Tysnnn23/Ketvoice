package com.ketvoice.app.widget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ketvoice.app.MainActivity
import com.ketvoice.app.R
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.player.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Quick Settings tiles (6.4, phones only; Quest has no shade): play/pause with the title as subtitle, and Ketmix on
 * or off with what it mixes next. A tile only listens while the shade shows it ([TileService.onStartListening]):
 * nothing runs for it otherwise.
 */
internal object QuickTiles {
    /** What the play tile shows (host-tested): Ketvoice, the title as subtitle; [subtitleIsText] when it is a French message. */
    data class PlayLook(val state: Int, val subtitle: String, val playing: Boolean, val subtitleIsText: Boolean)

    fun playLook(title: String?, playing: Boolean): PlayLook =
        if (title.isNullOrBlank()) PlayLook(Tile.STATE_INACTIVE, "Rien en lecture", false, subtitleIsText = true)
        else PlayLook(if (playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE, title, playing, subtitleIsText = false)

    data class KetmixLook(val state: Int, val subtitle: String)

    fun ketmixLook(on: Boolean, nextTitle: String?): KetmixLook =
        if (!on) KetmixLook(Tile.STATE_INACTIVE, "Désactivé")
        else KetmixLook(Tile.STATE_ACTIVE, nextTitle?.takeIf { it.isNotBlank() } ?: "Activé")

    /** Asks Android to redraw both tiles (a change made in a widget or the app while the shade is open). */
    fun refresh(context: Context) {
        if (com.ketvoice.app.BuildConfig.IS_QUEST) return
        runCatching { TileService.requestListeningState(context, ComponentName(context, PlayPauseTileService::class.java)) }
        runCatching { TileService.requestListeningState(context, ComponentName(context, KetmixTileService::class.java)) }
    }

    /** Opens Ketvoice from a tile and folds the shade (with an Intent before Android 14, the only way there). */
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    fun openApp(service: TileService, where: String? = null) {
        val intent = Intent(service, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        where?.let { intent.putExtra(WidgetIntents.OPEN, it) }
        if (Build.VERSION.SDK_INT >= 34) {
            val pending = PendingIntent.getActivity(service, 9, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            runCatching { service.startActivityAndCollapse(pending) }
        } else {
            @Suppress("DEPRECATION")
            runCatching { service.startActivityAndCollapse(intent) }
        }
    }
}

/** Ketmix on or off, from its tile (the tile and the app follow). */
internal object KetmixSwitch {
    fun flip(context: Context): Boolean {
        val app = context.applicationContext
        if (!SettingsStore.loaded) runCatching { SettingsStore.load(app) }
        val next = !AppSettings.automix.value
        AppSettings.automix.value = next
        SettingsStore.save()
        // Outside a screen of the app, nothing else sends the state's change notifications.
        runCatching { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        QuickTiles.refresh(app)
        return next
    }
}

/** The base of both tiles: a scope that lives while the shade shows the tile. */
abstract class KetvoiceTileService : TileService() {
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    protected fun listen(block: suspend CoroutineScope.() -> Unit) {
        val s = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        job?.cancel()
        job = s.launch(block = block)
    }

    protected fun launch(block: suspend CoroutineScope.() -> Unit) {
        val s = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
        s.launch(block = block)
    }

    override fun onStopListening() {
        super.onStopListening()
        job?.cancel(); job = null
    }

    override fun onDestroy() {
        scope?.cancel(); scope = null
        super.onDestroy()
    }

    protected fun paint(state: Int, label: String, subtitle: String, icon: Int, description: String) {
        val tile = qsTile ?: return
        tile.state = state
        tile.label = label
        tile.subtitle = subtitle
        tile.icon = Icon.createWithResource(this, icon)
        tile.contentDescription = description
        tile.updateTile()
    }
}

/** « Lecture / pause »: the title as subtitle; a tap plays or pauses, and without a queue opens Ketvoice. */
class PlayPauseTileService : KetvoiceTileService() {
    override fun onStartListening() {
        super.onStartListening()
        listen {
            Player.state.map { it.current?.title to it.playWhenReady }.distinctUntilChanged().collect { (title, playing) ->
                val look = QuickTiles.playLook(title, playing)
                val subtitle = if (look.subtitleIsText) AppText.t(look.subtitle) else look.subtitle
                val action = AppText.t(if (look.playing) "Pause" else "Lecture")
                paint(look.state, "Ketvoice", subtitle, if (look.playing) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
                    "$action · $subtitle")
            }
        }
    }

    override fun onClick() {
        super.onClick()
        launch {
            runCatching { Player.connect(applicationContext) }
            // The saved session is read from the disk when the queue first connects.
            val current = Player.state.value.current ?: withTimeoutOrNull(2_500) { Player.state.first { it.current != null } }?.current
            if (current == null) QuickTiles.openApp(this@PlayPauseTileService, WidgetIntents.OPEN_PLAY) else Player.toggle()
        }
    }
}

/** « Ketmix »: on or off, with the title it mixes in next as subtitle. */
class KetmixTileService : KetvoiceTileService() {
    override fun onStartListening() {
        super.onStartListening()
        if (!SettingsStore.loaded) runCatching { SettingsStore.load(applicationContext) }
        listen {
            val on = androidx.compose.runtime.snapshotFlow { AppSettings.automix.value }
            combine(on, Player.state) { switched, _ -> switched to runCatching { Player.automixNext()?.track?.title }.getOrNull() }
                .distinctUntilChanged().collect { (switched, next) -> show(switched, next) }
        }
    }

    private fun show(on: Boolean, next: String?) {
        val look = QuickTiles.ketmixLook(on, next)
        val subtitle = if (on && !next.isNullOrBlank()) look.subtitle else AppText.t(look.subtitle)
        paint(look.state, "Ketmix", subtitle, R.drawable.ic_widget_ketmix, "Ketmix")
    }

    override fun onClick() {
        super.onClick()
        val on = KetmixSwitch.flip(this)
        show(on, runCatching { Player.automixNext()?.track?.title }.getOrNull())
    }
}
