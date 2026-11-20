package com.ketvoice.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.ArtworkRepo
import com.ketvoice.app.data.MediaOverrides
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.player.AppAutoSource
import com.ketvoice.app.player.AutoLibrary
import com.ketvoice.app.player.AutoRequest
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.PlayerAutoPlayback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * The two home-screen widgets of 7.4 (owner: « je veux les mêmes widgets qu'Apple Music et qui fonctionnent
 * réellement »): « En cours » 2×2 and « En cours + contrôles » 4×2, plain RemoteViews (no Glance).
 *
 * Live and frugal: one collector follows the player, the covers and the favourites, and redraws every placed widget
 * when the title, play/pause, the cover or J'aime change, or the listener seeks. Nothing is drawn while no widget is
 * placed. While a title plays the times tick by themselves (chronometers, no update); the progress bar moves every
 * [PROGRESS_STEP_MS] with a partial update, only while the screen is on and a 4×2 widget is placed.
 */
internal object NowPlayingWidgets {
    const val PROGRESS_STEP_MS = 3_000L
    /** Covers are drawn at this size (px), rounded, for both widgets. */
    const val COVER_PX = 300

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(NowPlaying())
    val state: StateFlow<NowPlaying> = _state
    @Volatile private var started = false
    @Volatile private var hadTitle = false
    private var coverUrl: String? = null
    private var ground: Pair<WidgetPalette, Bitmap>? = null

    private data class Key(val id: String?, val title: String, val artist: String, val playing: Boolean, val cover: String?,
                           val durationMs: Long, val liked: Boolean, val placed: Int)

    /** Bumped when a widget is placed or removed: the collector looks again (covers load only while one is placed). */
    private val placedEpoch = MutableStateFlow(0)
    fun placedChanged() { placedEpoch.value++ }

    @Synchronized
    fun start(context: Context) {
        if (started || com.ketvoice.app.BuildConfig.IS_QUEST) return
        started = true
        val app = context.applicationContext
        ScreenState.watch(app)
        scope.launch { follow(app) }
        scope.launch { followSeeks(app) }
        scope.launch { tick(app) }
    }

    private suspend fun follow(app: Context) {
        val favourites = androidx.compose.runtime.snapshotFlow { AppSettings.favoriteIds.value }
        combine(Player.state, ArtworkRepo.covers, favourites, placedEpoch) { player, covers, liked, placed ->
            val track = player.current
            Key(track?.id, track?.title.orEmpty(), track?.let { MediaOverrides.mainArtist(it.artist) }.orEmpty(),
                player.playWhenReady && track != null,
                track?.let { MediaOverrides.cover(it, covers["t:${it.id}"] ?: it.coverUrl) }, player.durationMs.takeIf { it > 0 } ?: track?.durationMs ?: 0L,
                track != null && track.id in liked, placed)
        }.distinctUntilChanged().collectLatest { key ->
            // A process started for another reason (a headset, the service) has no queue yet: it does not blank what
            // the launcher shows. Once a title was shown, an empty queue is « Rien en lecture ».
            if (key.title.isBlank() && !hadTitle) return@collectLatest
            hadTitle = hadTitle || key.title.isNotBlank()
            val now = SystemClock.elapsedRealtime()
            if (!anyPlaced(app)) return@collectLatest
            val sameCover = key.cover == coverUrl
            _state.value = _state.value.copy(trackId = key.id, title = key.title, artist = key.artist, playing = key.playing,
                liked = key.liked, durationMs = key.durationMs, positionMs = Player.positionMs.value, atMs = now)
            if (sameCover || key.cover == null) {
                if (!sameCover) { coverUrl = null; _state.value = _state.value.copy(cover = null, palette = WidgetPalette.FALLBACK) }
                push(app)
                return@collectLatest
            }
            // A new cover: drawn with its title when it comes quickly (the memory cache), else the title first.
            val quick = withTimeoutOrNull(600) { loadCover(app, key.cover) }
            if (quick == null) { _state.value = _state.value.copy(cover = null, palette = WidgetPalette.FALLBACK); push(app) }
            val bitmap = quick ?: loadCover(app, key.cover) ?: return@collectLatest
            coverUrl = key.cover
            _state.value = _state.value.copy(cover = bitmap, palette = WidgetPalette.of(bitmap))
            push(app)
        }
    }

    /** A seek (in the app, the notification or a headset) moves the times and the bar at once. */
    private suspend fun followSeeks(app: Context) {
        Player.positionMs.collect { position ->
            val s = _state.value
            if (s.empty) return@collect
            val now = SystemClock.elapsedRealtime()
            if (abs(position - s.positionAt(now)) < 2_500 || !anyPlaced(app)) return@collect
            _state.value = s.copy(positionMs = position, atMs = now)
            push(app, smallToo = false)
        }
    }

    private suspend fun tick(app: Context) {
        combine(_state.map { it.playing && !it.empty && it.durationMs > 0 }.distinctUntilChanged(), ScreenState.on) { playing, on -> playing && on }
            .distinctUntilChanged().collectLatest { active ->
                while (active) {
                    val ids = ids(app, NowPlayingControlsWidgetReceiver::class.java)
                    if (ids.isNotEmpty()) runCatching {
                        AppWidgetManager.getInstance(app).partiallyUpdateAppWidget(ids,
                            WidgetViews.progressOnly(app, _state.value, SystemClock.elapsedRealtime()))
                    }
                    delay(PROGRESS_STEP_MS)
                }
            }
    }

    private fun ids(context: Context, type: Class<*>): IntArray =
        runCatching { AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, type)) }.getOrNull() ?: IntArray(0)

    private fun anyPlaced(context: Context) =
        ids(context, NowPlayingWidgetReceiver::class.java).isNotEmpty() || ids(context, NowPlayingControlsWidgetReceiver::class.java).isNotEmpty()

    /** Redraws every placed widget from the current state. */
    fun push(context: Context, smallToo: Boolean = true) {
        val app = context.applicationContext
        if (smallToo) render(app, NowPlayingWidgetReceiver::class.java, ids(app, NowPlayingWidgetReceiver::class.java))
        render(app, NowPlayingControlsWidgetReceiver::class.java, ids(app, NowPlayingControlsWidgetReceiver::class.java))
    }

    /** Draws the widgets [ids] of [type] (also called by the launcher's update, after a reboot or when placed). */
    fun render(context: Context, type: Class<*>, ids: IntArray) {
        if (ids.isEmpty()) return
        val manager = AppWidgetManager.getInstance(context)
        val s = _state.value
        val now = SystemClock.elapsedRealtime()
        for (id in ids) runCatching {
            val options = manager.getAppWidgetOptions(id)
            val views = if (type == NowPlayingControlsWidgetReceiver::class.java)
                WidgetViews.controls(context, s, now, groundFor(context, s.palette, options), compact = heightDp(options) in 1 until WidgetViews.FULL_HEIGHT_DP)
            else WidgetViews.small(context, s, now, groundFor(context, s.palette, options))
            manager.updateAppWidget(id, views)
        }
    }

    /** The widget's height in portrait (the launcher's largest), in dp; 0 when unknown. */
    private fun heightDp(options: Bundle?): Int = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) ?: 0

    /**
     * The card's gradient. Android 12+ clips it to the launcher's outline, so one small bitmap serves every size;
     * Android 11 (Lite) gets it at the widget's size with the corners cut in.
     */
    private fun groundFor(context: Context, palette: WidgetPalette, options: Bundle?): Bitmap {
        if (Build.VERSION.SDK_INT < 31) {
            val w = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH)?.takeIf { it > 0 } ?: 160
            val h = heightDp(options).takeIf { it > 0 } ?: 160
            return palette.ground(w, h, context.resources.getDimension(com.ketvoice.app.R.dimen.widget_radius) / context.resources.displayMetrics.density)
        }
        ground?.let { (p, b) -> if (p == palette) return b }
        return palette.ground().also { ground = palette to it }
    }

    private suspend fun loadCover(context: Context, url: String): Bitmap? = runCatching {
        val request = ImageRequest.Builder(context).data(url).size(COVER_PX).allowHardware(false).build()
        val bitmap = context.imageLoader.execute(request).drawable?.toBitmap(COVER_PX, COVER_PX, Bitmap.Config.ARGB_8888) ?: return null
        roundedCover(bitmap, COVER_PX)
    }.getOrNull()
}

/** « En cours » (2×2). The name of the 6.4 « Lecture en cours » receiver is kept, so widgets already placed stay. */
class NowPlayingWidgetReceiver : NowPlayingProvider()

/** « En cours + contrôles » (4×2). */
class NowPlayingControlsWidgetReceiver : NowPlayingProvider()

abstract class NowPlayingProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        NowPlayingWidgets.start(context)
        NowPlayingWidgets.render(context.applicationContext, javaClass, ids)
        NowPlayingWidgets.placedChanged()
    }

    override fun onDeleted(context: Context, ids: IntArray) = NowPlayingWidgets.placedChanged()

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        NowPlayingWidgets.render(context.applicationContext, javaClass, intArrayOf(id))
    }
}

/**
 * The widgets' buttons (not exported; only Ketvoice's own PendingIntents reach it). It works with Ketvoice closed:
 * the queue is connected (the saved session is read from the disk on first connect, as for the Quick Settings tile),
 * then the button acts on it. « Lecture » without anything to resume plays the favourites, else the recent titles,
 * else the downloads (the same choice as « Joue de la musique » in Android Auto).
 */
class WidgetActionReceiver : BroadcastReceiver() {
    companion object {
        const val TOGGLE = "com.ketvoice.app.widget.TOGGLE"
        const val NEXT = "com.ketvoice.app.widget.NEXT"
        const val PREVIOUS = "com.ketvoice.app.widget.PREVIOUS"
        const val LIKE = "com.ketvoice.app.widget.LIKE"
        /** « Rien en lecture »: resume the last queue, or start one. */
        const val PLAY = "com.ketvoice.app.widget.PLAY"
        val all = listOf(TOGGLE, NEXT, PREVIOUS, LIKE, PLAY)
        fun requestCode(action: String) = 741 + all.indexOf(action).coerceAtLeast(0)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action?.takeIf { it in all } ?: return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                withTimeoutOrNull(8_000) { perform(app, action) }
            } finally {
                runCatching { NowPlayingWidgets.push(app) }
                pending.finish()
            }
        }
    }

    private suspend fun perform(app: Context, action: String) {
        if (!SettingsStore.loaded) runCatching { SettingsStore.load(app) }
        NowPlayingWidgets.start(app)
        runCatching { Player.connect(app) }
        val current = Player.state.value.current ?: withTimeoutOrNull(2_500) { Player.state.first { it.current != null } }?.current
        when (action) {
            TOGGLE, PLAY -> when {
                current == null -> playSomething(app)
                action == PLAY && Player.state.value.playWhenReady -> Unit
                else -> Player.toggle()
            }
            NEXT -> if (current != null) Player.next()
            PREVIOUS -> if (current != null) Player.previous()
            LIKE -> if (current != null) toggleLike(current.id)
        }
        QuickTiles.refresh(app)
    }

    private suspend fun playSomething(app: Context) {
        val library = AutoLibrary(AppAutoSource(app), PlayerAutoPlayback(app))
        withContext(Dispatchers.Default) { runCatching { library.prepare() } }
        runCatching { library.perform(AutoRequest.Resume) }
    }

    private fun toggleLike(id: String) {
        val liked = AppSettings.favoriteIds.value
        AppSettings.favoriteIds.value = if (id in liked) liked - id else liked + id
        SettingsStore.save()
        // Outside a screen of the app nothing else sends the state's change notifications.
        runCatching { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
    }
}

/**
 * Whether the screen is on: nobody sees a home screen with the screen off, so the progress bar waits for it (the
 * times keep ticking on their own).
 */
internal object ScreenState {
    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on
    private var receiver: BroadcastReceiver? = null

    @Synchronized
    fun watch(context: Context) {
        if (receiver != null) return
        val app = context.applicationContext
        _on.value = com.ketvoice.app.player.LockscreenLyrics.defaultScreenOn(app)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> _on.value = true
                    Intent.ACTION_SCREEN_OFF -> _on.value = false
                }
            }
        }.also { r ->
            runCatching {
                androidx.core.content.ContextCompat.registerReceiver(app, r,
                    IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },
                    androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
            }
        }
    }
}
