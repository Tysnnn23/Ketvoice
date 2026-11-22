package com.ketvoice.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.LayoutSettings
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.data.Track
import com.ketvoice.app.player.Player
import com.ketvoice.app.player.SleepTimer
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Ket27Glyphs
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.KetMenu
import com.ketvoice.app.ui.common.KetMenuItem
import com.ketvoice.app.ui.motion.Haptics
import com.ketvoice.app.ui.motion.rememberHaptics
import com.ketvoice.app.ui.motion.symbolSlide

/** A button of the full player's bottom row (9.0 « Lecteur modulable »). */
internal enum class PlayerAction(val id: String, val label: String) {
    Lyrics("lyrics", "Paroles"),
    Output("output", "Sortie audio"),
    Account("account", "Compte"),
    Queue("queue", "File d'attente"),
    Like("like", "J'aime"),
    Ketmix("ketmix", "Ketmix"),
    Sleep("sleep", "Minuterie"),
    Share("share", "Partager"),
    Playlist("playlist", "Ajouter à une playlist"),
    Download("download", "Télécharger");

    companion object {
        fun of(id: String): PlayerAction? = entries.firstOrNull { it.id == id }
    }
}

/** A button of the mini-player beside play/pause. */
internal enum class MiniAction(val id: String, val label: String) {
    Next("next", "Suivant"),
    Previous("previous", "Précédent"),
    Like("like", "J'aime"),
    Ketmix("ketmix", "Ketmix"),
    Lyrics("lyrics", "Paroles"),
    Queue("queue", "File d'attente");

    companion object {
        fun of(id: String): MiniAction? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The player's buttons and artwork, as the listener set them (Settings › Personnalisation › Disposition). The
 * defaults are the player of 8.4: Lyrics, then Output and Account in one glass capsule, then the queue; Next in the
 * mini-player; the 8.3 artwork height; the Canvas on.
 */
internal object PlayerLayout {
    val DEFAULT_BAR = listOf(PlayerAction.Lyrics, PlayerAction.Output, PlayerAction.Account, PlayerAction.Queue)
    val DEFAULT_MINI = listOf(MiniAction.Next)

    fun bar(text: String?): List<PlayerAction> = LayoutSettings.playerList.shown(text).mapNotNull(PlayerAction::of)
    fun mini(text: String?): List<MiniAction> = LayoutSettings.miniList.shown(text).mapNotNull(MiniAction::of)

    /** The bottom row in three places: a round button at each end, the ones between in a glass capsule. */
    data class Row(val start: PlayerAction?, val middle: List<PlayerAction>, val end: PlayerAction?)

    fun row(actions: List<PlayerAction>): Row = when (actions.size) {
        0 -> Row(null, emptyList(), null)
        1 -> Row(actions[0], emptyList(), null)
        else -> Row(actions.first(), actions.subList(1, actions.size - 1), actions.last())
    }

    /** Width of the capsule holding [count] buttons (140 dp for the two of 8.4). */
    fun capsuleWidthDp(count: Int): Float = 70f * count

    /** How much taller or shorter the artwork is: 1 as in 8.3, smaller .85, larger 1.15. */
    fun artworkScale(size: Int): Float = when (size) { 1 -> .85f; 2 -> 1.15f; else -> 1f }

    /** The mini-player's buttons before play/pause (Previous) and after it (the others, in their order). */
    fun miniBefore(actions: List<MiniAction>): List<MiniAction> = actions.filter { it == MiniAction.Previous }
    fun miniAfter(actions: List<MiniAction>): List<MiniAction> = actions.filter { it != MiniAction.Previous }
}

/** Whether the title is a favourite, and a way to flip it; shared by the player row and the mini-player. */
internal fun toggleFavorite(track: Track) {
    val favorite = track.id in AppSettings.favoriteIds.value
    AppSettings.favoriteIds.value = if (favorite) AppSettings.favoriteIds.value - track.id else AppSettings.favoriteIds.value + track.id
    SettingsStore.save()
}

/** The symbol of [action] (the glyph-drawn ones excepted: lyrics, queue and Ketmix draw their own). */
internal fun playerActionIcon(action: PlayerAction, favorite: Boolean, downloaded: Boolean): ImageVector = when (action) {
    PlayerAction.Output -> KetIcons.Audio
    PlayerAction.Account -> Icons.Filled.Person
    PlayerAction.Like -> if (favorite) Ket27Glyphs.HeartFilled else Ket27Glyphs.Heart
    PlayerAction.Sleep -> Icons.Filled.Bedtime
    PlayerAction.Share -> KetIcons.Share
    PlayerAction.Playlist -> KetIcons.Plus
    PlayerAction.Download -> if (downloaded) KetIcons.Check else KetIcons.Download
    else -> KetIcons.More
}

/** What the full player's buttons do that the player itself does not (Lyrics, Output, Account and Queue are its own). */
internal class PlayerActionHandlers(val onLyrics: () -> Unit, val onOutput: () -> Unit, val onProfile: () -> Unit, val onQueue: () -> Unit)

/** The menu of the sleep timer, from its button. */
@Composable
internal fun SleepTimerMenu(open: Boolean, onDismiss: () -> Unit) {
    val remaining by SleepTimer.remainingSeconds.collectAsState()
    val afterTrack by SleepTimer.endOfTrack.collectAsState()
    KetMenu(open, onDismiss, listOf(
        listOf(KetMenuItem("À la fin du titre", Icons.Filled.Bedtime, checked = afterTrack) { SleepTimer.stopAfterTrack() }),
        SleepTimer.CHOICES.map { minutes -> KetMenuItem("Pause dans $minutes min") { SleepTimer.set(minutes) } },
        listOfNotNull(if (afterTrack || remaining > 0) KetMenuItem("Annuler la minuterie", destructive = true) { SleepTimer.cancel() } else null)
    ).filter { it.isNotEmpty() })
}

/** Runs [action] for [track]; the sleep timer opens its menu instead ([onSleep]). */
internal fun runPlayerAction(action: PlayerAction, track: Track?, handlers: PlayerActionHandlers, onSleep: () -> Unit,
                             onKetmix: () -> Unit) {
    when (action) {
        PlayerAction.Lyrics -> handlers.onLyrics()
        PlayerAction.Output -> handlers.onOutput()
        PlayerAction.Account -> handlers.onProfile()
        PlayerAction.Queue -> handlers.onQueue()
        PlayerAction.Ketmix -> onKetmix()
        PlayerAction.Sleep -> onSleep()
        PlayerAction.Like -> track?.let(::toggleFavorite)
        PlayerAction.Share -> track?.let { AppUi.shareCard.value = it }
        PlayerAction.Playlist -> track?.let { AppUi.addToPlaylist.value = it }
        PlayerAction.Download -> track?.takeIf { Downloader.itemFor(it.id) == null }?.let { Downloader.download(it) }
    }
}

/** The symbol of a player button, [tint]ed; lyrics, queue and Ketmix are drawn by their own glyphs. */
@Composable
internal fun PlayerActionGlyph(action: PlayerAction, track: Track?, tint: Color, size: Dp) {
    val favorite = track != null && track.id in AppSettings.favoriteIds.value
    val downloaded = track != null && Downloader.itemFor(track.id) != null
    val label = AppText.t(when {
        action == PlayerAction.Like && favorite -> "Retirer des favoris"
        action == PlayerAction.Download && downloaded -> "Téléchargé"
        else -> action.label
    })
    val described = Modifier.size(size).semantics { contentDescription = label }
    when (action) {
        PlayerAction.Lyrics -> LyricsGlyph(described, tint)
        PlayerAction.Queue -> QueueGlyph(described, tint)
        PlayerAction.Ketmix -> AutomixGlyph(if (AppSettings.automix.value) com.ketvoice.app.theme.LocalTheme.current.accent else tint, described)
        PlayerAction.Output -> Icon(castOutputIcon(), null, tint = tint, modifier = described)
        PlayerAction.Like -> Icon(playerActionIcon(action, favorite, downloaded), null,
            tint = if (favorite) com.ketvoice.app.theme.LocalTheme.current.accent else tint, modifier = described)
        else -> Icon(playerActionIcon(action, favorite, downloaded), null, tint = tint, modifier = described)
    }
}

/** One of the glass capsule's buttons in the player's bottom row. */
@Composable
internal fun RowScope.PlayerActionSegment(action: PlayerAction, track: Track?, handlers: PlayerActionHandlers) {
    var sleepOpen by remember { mutableStateOf(false) }
    val ketmix = rememberKetmixSwitch()
    Box(Modifier.weight(1f).fillMaxHeight().clickable(remember { MutableInteractionSource() }, null) {
            runPlayerAction(action, track, handlers, onSleep = { sleepOpen = true }, onKetmix = ketmix)
        }.testTag("player-action-" + action.id), contentAlignment = Alignment.Center) {
        PlayerActionGlyph(action, track, Color.White, 22.dp)
        if (action == PlayerAction.Sleep) SleepTimerMenu(sleepOpen) { sleepOpen = false }
    }
}

/** One of the round buttons at the ends of the player's bottom row. */
@Composable
internal fun PlayerActionCircle(action: PlayerAction, track: Track?, lyricsActive: Boolean, handlers: PlayerActionHandlers) {
    var sleepOpen by remember { mutableStateOf(false) }
    val ketmix = rememberKetmixSwitch()
    val active = action == PlayerAction.Lyrics && lyricsActive
    Box(Modifier.testTag("player-action-" + action.id)) {
        SmallCircleButton(onClick = { runPlayerAction(action, track, handlers, onSleep = { sleepOpen = true }, onKetmix = ketmix) },
            active = active, transparent = !active) { tint ->
            PlayerActionGlyph(action, track, tint, if (action == PlayerAction.Lyrics || action == PlayerAction.Queue) 25.dp else 23.dp)
        }
        if (action == PlayerAction.Sleep) SleepTimerMenu(sleepOpen) { sleepOpen = false }
    }
}

/** A button of the mini-player other than Next (which keeps its own anchor for the player's opening). */
@Composable
internal fun MiniActionButton(action: MiniAction, tint: Color, size: Dp, glyph: Dp) {
    val haptic = rememberHaptics()
    val ketmix = rememberKetmixSwitch()
    var taps by remember { mutableIntStateOf(0) }
    val track = Player.state.collectAsState().value.current
    val favorite = track != null && track.id in AppSettings.favoriteIds.value
    val accent = com.ketvoice.app.theme.LocalTheme.current.accent
    val label = AppText.t(if (action == MiniAction.Like && favorite) "Retirer des favoris" else action.label)
    Box(Modifier.size(size).clip(CircleShape).clickable(remember { MutableInteractionSource() }, null) {
            taps++
            haptic(Haptics.Kind.Press)
            when (action) {
                MiniAction.Next -> Player.next()
                MiniAction.Previous -> Player.previous()
                MiniAction.Like -> track?.let(::toggleFavorite)
                MiniAction.Ketmix -> ketmix()
                MiniAction.Lyrics -> { AppUi.showLyrics.value = true; AppUi.showPlayer.value = true }
                MiniAction.Queue -> AppUi.openQueue()
            }
        }.semantics { contentDescription = label }.testTag("mini-" + action.id), contentAlignment = Alignment.Center) {
        val m = Modifier.size(glyph)
        when (action) {
            MiniAction.Next -> Icon(Icons.Filled.SkipNext, null, tint = tint, modifier = m.symbolSlide(taps, 1))
            MiniAction.Previous -> Icon(Icons.Filled.SkipPrevious, null, tint = tint, modifier = m.symbolSlide(taps, -1))
            MiniAction.Like -> Icon(if (favorite) Ket27Glyphs.HeartFilled else Ket27Glyphs.Heart, null,
                tint = if (favorite) accent else tint, modifier = m)
            MiniAction.Ketmix -> AutomixGlyph(if (AppSettings.automix.value) accent else tint, m)
            MiniAction.Lyrics -> LyricsGlyph(m, tint)
            MiniAction.Queue -> QueueGlyph(m, tint)
        }
    }
}

/** The capsule's width for [count] buttons. */
internal fun Modifier.playerCapsuleWidth(count: Int): Modifier = width(PlayerLayout.capsuleWidthDp(count).dp)

/** The hairline between two buttons of the capsule. */
@Composable
internal fun CapsuleDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.16f)))
}
