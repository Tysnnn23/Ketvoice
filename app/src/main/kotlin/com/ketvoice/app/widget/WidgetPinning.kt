package com.ketvoice.app.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build

/**
 * « Ajouter le widget » in Settings (6.4): every widget, offered to the launcher with
 * [AppWidgetManager.requestPinAppWidget] (the launcher asks the listener where to put it), and both tiles offered
 * to the shade with StatusBarManager.requestAddTileService (Android 13+).
 */
internal object WidgetPinning {
    /** A widget as Settings lists it: its name and what it shows (French, translated where shown). */
    data class Entry(val kind: WidgetKind, val name: String, val description: String, val receiver: Class<*>)

    val widgets: List<Entry> = listOf(
        Entry(WidgetKind.NowPlaying, "Lecture en cours", "Pochette, titre et commandes, à toutes les tailles", NowPlayingWidgetReceiver::class.java),
        Entry(WidgetKind.Player, "Lecteur", "Grand lecteur avec la ligne chantée et la progression", GlassPlayerWidgetReceiver::class.java),
        Entry(WidgetKind.Lyrics, "Paroles en direct", "La ligne chantée et la suivante", LyricsWidgetReceiver::class.java),
        Entry(WidgetKind.Ketmix, "Ketmix", "L'interrupteur et le prochain titre mixé", KetmixWidgetReceiver::class.java),
        Entry(WidgetKind.Replay, "Replay", "Vos minutes du mois, votre artiste et votre titre", ReplayWidgetReceiver::class.java),
        Entry(WidgetKind.Week, "Votre semaine", "Vos minutes d'écoute et votre artiste de la semaine", WeekWidgetReceiver::class.java),
        Entry(WidgetKind.Mix, "Pour vous", "Le mix du moment : un tap le lance", MixWidgetReceiver::class.java),
        Entry(WidgetKind.Mini, "Mini", "Pochette, lecture/pause et suivant", MiniControlsWidgetReceiver::class.java),
    )

    /** Whether the launcher lets an app propose a widget (most do; some makers' launchers do not). */
    fun supported(context: Context): Boolean =
        !com.ketvoice.app.BuildConfig.IS_QUEST &&
            runCatching { AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported }.getOrDefault(false)

    /** Asks the launcher to place [entry]; false when it cannot be asked. */
    fun pin(context: Context, entry: Entry): Boolean {
        if (!supported(context)) return false
        return runCatching {
            AppWidgetManager.getInstance(context).requestPinAppWidget(ComponentName(context, entry.receiver), null, null)
        }.getOrDefault(false)
    }

    /** The two tiles as Settings lists them. */
    data class TileEntry(val name: String, val service: Class<*>, val icon: Int)

    val tiles: List<TileEntry> = listOf(
        TileEntry("Lecture / pause", PlayPauseTileService::class.java, com.ketvoice.app.R.drawable.ic_widget_play),
        TileEntry("Ketmix", KetmixTileService::class.java, com.ketvoice.app.R.drawable.ic_widget_ketmix),
    )

    fun tilesSupported(): Boolean = !com.ketvoice.app.BuildConfig.IS_QUEST && Build.VERSION.SDK_INT >= 33

    /** Asks the system to add a tile to the shade (Android 13+); [done] hears whether it was added. */
    fun addTile(context: Context, entry: TileEntry, label: String, done: (Boolean) -> Unit) {
        if (com.ketvoice.app.BuildConfig.IS_QUEST || Build.VERSION.SDK_INT < 33) { done(false); return }
        requestTile(context, entry, label, done)
    }

    @androidx.annotation.RequiresApi(33)
    private fun requestTile(context: Context, entry: TileEntry, label: String, done: (Boolean) -> Unit) {
        val manager = context.getSystemService(android.app.StatusBarManager::class.java) ?: run { done(false); return }
        runCatching {
            manager.requestAddTileService(ComponentName(context, entry.service), label,
                android.graphics.drawable.Icon.createWithResource(context, entry.icon), context.mainExecutor) { result ->
                done(result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                    result == android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
            }
        }.onFailure { done(false) }
    }
}
