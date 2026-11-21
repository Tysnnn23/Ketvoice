package com.ketvoice.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.ketvoice.app.data.AppText
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.desktop.ui.theme.DeskGlyphs
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.KetDesktopTheme
import com.ketvoice.desktop.ui.theme.LocalKet

/** The optional mini-player: a small window kept above the others (Ctrl + M, Settings › Windows, the full player). */
object MiniPlayer {
    val open = mutableStateOf(false)
    fun toggle() { open.value = !open.value }
}

/** The mini-player's window, while [MiniPlayer.open]; « Agrandir » brings the main window back. */
@Composable
fun MiniPlayerWindow(icon: Painter?, dark: Boolean, onShowMain: () -> Unit) {
    if (!MiniPlayer.open.value) return
    val state = rememberWindowState(size = DpSize(380.dp, 132.dp), position = WindowPosition(Alignment.BottomEnd))
    Window(onCloseRequest = { MiniPlayer.open.value = false }, state = state, title = AppText.t("Mini-lecteur"), icon = icon,
        resizable = false, alwaysOnTop = true) {
        KetDesktopTheme(dark) {
            val player = LocalPlayer.current
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            val actions = androidx.compose.runtime.remember(player) { TrackActions(player, LibraryBridge, Navigator(), scope) }
            Box(Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                // The same keys as the main window, without the ones that open a page there.
                val t = object : Shortcuts.Target {
                    override val typing = false; override val fullPlayer = false
                    override fun focusSearch() { onShowMain() }
                    override fun leaveField() {}
                    override fun setFullPlayer(open: Boolean) { onShowMain() }
                    override fun settings() { onShowMain() }
                    override fun back() = false
                }
                Shortcuts.handle(e, player, actions, t)
            }) {
                MiniPlayerContent(onExpand = { MiniPlayer.open.value = false; onShowMain() })
            }
        }
    }
}

/** Cover, title and artist, the transport and the seek bar, on the window's ground. */
@Composable
fun MiniPlayerContent(onExpand: () -> Unit) {
    val ket = LocalKet.current
    val player = LocalPlayer.current
    val state by player.state.collectAsState()
    val track = state.current
    Row(Modifier.fillMaxSize().background(ket.ground).padding(12.dp).testTag("mini-player"), verticalAlignment = Alignment.CenterVertically) {
        Artwork(track?.coverUrl, 108.dp, radius = Ket27Desk.Radius.card)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f).padding(top = 2.dp)) {
                    if (track == null) KText("Aucune lecture en cours", Ket27Desk.Type.subheadline, color = ket.secondary)
                    else {
                        KText(track.title, Ket27Desk.Type.subheadline, weight = FontWeight.SemiBold, translate = false)
                        KText(track.artist, Ket27Desk.Type.footnote, color = ket.secondary, translate = false)
                    }
                }
                KetIconButton(DeskGlyphs.ChevronUp, "Agrandir", onExpand, Modifier.testTag("mini-expand"), size = 26.dp, glyph = 13.dp, tint = ket.secondary)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                KetIconButton(DeskGlyphs.Previous, "Précédent", { player.previous() }, size = 32.dp, glyph = 18.dp, enabled = track != null)
                Spacer(Modifier.width(10.dp))
                PlayPause(state.isPlaying, { player.toggle() }, 38.dp, 24.dp)
                Spacer(Modifier.width(10.dp))
                KetIconButton(DeskGlyphs.Next, "Suivant", { player.next() }, size = 32.dp, glyph = 18.dp, enabled = track != null)
            }
            Box(Modifier.height(18.dp)) {
                if (track != null) Seek(state.positionMs, state.durationMs.takeIf { it > 0 } ?: track.durationMs, compact = true)
            }
        }
    }
}

/** The mini-player as an icon button (the full player's top bar). */
@Composable
internal fun MiniPlayerButton(tint: Color, modifier: Modifier = Modifier) =
    KetIconButton(KetIcons.Album, "Mini-lecteur", { MiniPlayer.toggle() }, modifier.testTag("mini-toggle"), size = 36.dp, glyph = 17.dp, tint = tint)
