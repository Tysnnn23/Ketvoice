package com.ketvoice.app.tv

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.MonthStat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The TV's own choices for its system features (Ketvoice TV 1.1.0): artists' photos in the art frame, Ketvoice on
 * the TV's home screen. Device settings: kept on this TV, never synced.
 */
object TvPrefs {
    const val FILE = "ketvoice_tv_system"
    private var prefs: SharedPreferences? = null
    private val artists = MutableStateFlow(true)
    private val channels = MutableStateFlow(true)
    val artFrameArtists: StateFlow<Boolean> = artists
    val homeChannels: StateFlow<Boolean> = channels

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs = p
        artists.value = p.getBoolean("artFrameArtists", true)
        channels.value = p.getBoolean("homeChannels", true)
    }

    fun setArtFrameArtists(on: Boolean) { artists.value = on; prefs?.edit()?.putBoolean("artFrameArtists", on)?.apply() }
    fun setHomeChannels(on: Boolean) { channels.value = on; prefs?.edit()?.putBoolean("homeChannels", on)?.apply() }
}

/**
 * The cards that lead to the TV's own features, on Home and in Library: the month's Replay, the year's Wrapped and
 * the art frame. Pure: host-tested.
 */
internal object TvExtras {
    const val SHELF = "tv-extras"

    /**
     * [months] are the months with plays, newest first ([com.ketvoice.app.data.ListeningMonths.ordered]); [monthName]
     * names one in the interface's language; [cover] is a month's most played cover; [frames] are the art frame's
     * pictures. No two cards show the same picture: a repeated one gives way to the next picture of the frame.
     */
    fun shelf(months: List<String>, stats: Map<String, MonthStat>, year: String, monthName: (String) -> String,
              cover: (String) -> String?, frames: List<String>): TvShelf? {
        val used = HashSet<String>()
        fun fresh(preferred: String?): String? =
            (preferred?.takeIf { it !in used } ?: frames.firstOrNull { it !in used })?.also { used += it }
        val items = buildList {
            months.firstOrNull()?.let { m ->
                add(TvItem("replay:$m", TvItemKind.Collection, "Replay", monthName(m), fresh(cover(m)), TvRoute.Replay(m), appText = true))
            }
            val yearPlays = stats.filterKeys { it.startsWith("$year-") }.values.sumOf { s -> s.plays.values.sum() }
            if (yearPlays > 0) add(TvItem("wrapped:$year", TvItemKind.Collection, "Wrapped $year", "Votre année en musique",
                fresh(months.firstOrNull { it.startsWith("$year-") }?.let(cover)), TvRoute.Replay(null), appText = true))
            if (frames.size >= FRAMES_READY) add(TvItem("art-frame", TvItemKind.Collection, "Cadre d'art", "Vos pochettes en grand",
                fresh(frames.first()), TvRoute.ArtFrame, appText = true))
        }
        return items.takeIf { it.isNotEmpty() }?.let { TvShelf(SHELF, "Replay et cadre d'art", it) }
    }

    /** The art frame is offered once it has this many pictures. */
    const val FRAMES_READY = 3

    /** Home shows the shelf third (after what was played and the library); [at] is where it goes. */
    fun placeIn(shelves: List<TvShelf>, extra: TvShelf?, at: Int = 2): List<TvShelf> {
        if (extra == null) return shelves
        val i = at.coerceIn(0, shelves.size)
        return shelves.take(i) + extra + shelves.drop(i)
    }
}

/** The extras shelf as Home and Library show it, from the listener's months and library. */
@Composable
fun rememberTvExtras(): TvShelf? {
    val stats by com.ketvoice.app.data.ListeningHistory.months.collectAsState()
    val frames by rememberArtFrames()
    val library = rememberTvLibrary()
    return androidx.compose.runtime.remember(stats, frames, library) {
        val months = com.ketvoice.app.data.ListeningMonths.ordered(stats).filter { m -> stats[m]?.plays?.isNotEmpty() == true }
        TvExtras.shelf(months, stats, java.time.LocalDate.now().year.toString(), TvReplayPlan::monthName,
            { m -> TvReplayPlan.monthCover(m, stats, library) }, frames.map { it.image })
    }
}

/**
 * Settings → « Écran de veille et accueil »: the art frame (open it, artists' photos), the screensaver (chosen in the
 * TV's own settings) and Ketvoice on the TV's home screen.
 */
@Composable
fun TvSystemSection() {
    val actions = LocalTvActions.current
    val context = LocalContext.current
    val artists by TvPrefs.artFrameArtists.collectAsState()
    val channels by TvPrefs.homeChannels.collectAsState()
    Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TvButton("Ouvrir le cadre d'art", null, { actions.nav.open(TvRoute.ArtFrame) }, Modifier.pageItem("settings", "art-frame-open"),
            tag = "setting-art-frame-open")
        TvButton("Choisir l'écran de veille", null, { openDreamSettings(context) }, Modifier.pageItem("settings", "dream-open"),
            tag = "setting-dream-open")
    }
    TvToggle("Photos des artistes dans le cadre d'art", "Entre les pochettes, les photos de vos artistes les plus écoutés.",
        artists, "art-frame-artists", TvPrefs::setArtFrameArtists)
    TvToggle("Ketvoice sur l'accueil du téléviseur", "« Continuer l'écoute » et une chaîne de vos playlists, mixes et albums, tant que vous êtes connecté.",
        channels, "home-channels") { on -> TvPrefs.setHomeChannels(on); TvHomeChannels.refresh(context) }
}

/** The TV's screensaver settings, where « Ketvoice » is chosen; some TVs hide them, a hint says where to look then. */
private fun openDreamSettings(context: Context) {
    val opened = runCatching {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_DREAM_SETTINGS)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!opened) android.widget.Toast.makeText(context,
        AppText.t("Dans les réglages du téléviseur : Système → Économiseur d'écran, puis « Ketvoice »."), android.widget.Toast.LENGTH_LONG).show()
}
