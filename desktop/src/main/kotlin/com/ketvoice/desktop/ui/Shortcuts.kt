package com.ketvoice.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ketvoice.desktop.ui.theme.Ket27Desk
import com.ketvoice.desktop.ui.theme.LocalKet

/** The window's keyboard shortcuts: what they do, and the list Settings shows. */
object Shortcuts {
    data class Entry(val keys: String, val label: String)

    val list = listOf(
        Entry("Espace", "Lecture ou pause"),
        Entry("← →", "Reculer ou avancer de 10 secondes"),
        Entry("Ctrl + ← →", "Titre précédent ou suivant"),
        Entry("↑ ↓", "Volume"),
        Entry("Ctrl + F", "Rechercher"),
        Entry("Ctrl + L", "Ajouter le titre écouté aux favoris"),
        Entry("Ctrl + P", "Ouvrir ou fermer le lecteur"),
        Entry("Ctrl + M", "Mini-lecteur"),
        Entry("Ctrl + ,", "Réglages"),
        Entry("Alt + ←", "Retour"),
        Entry("Échap", "Fermer le lecteur"),
    )

    const val SEEK_MS = 10_000L
    const val VOLUME_STEP = .05f

    /** What the window does with a key; [typing] is true while a text field has the keyboard. */
    interface Target {
        val typing: Boolean
        val fullPlayer: Boolean
        fun focusSearch()
        fun leaveField()
        fun setFullPlayer(open: Boolean)
        fun settings()
        fun back(): Boolean
    }

    fun handle(e: KeyEvent, player: PlayerControls, actions: TrackActions, t: Target): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        val ctrl = e.isCtrlPressed || e.isMetaPressed
        val state = player.state.value
        return when {
            ctrl && e.key == Key.F -> { t.focusSearch(); true }
            ctrl && e.key == Key.M -> { MiniPlayer.toggle(); true }
            ctrl && e.key == Key.Comma -> { t.settings(); true }
            ctrl && e.key == Key.P -> { if (state.current != null || t.fullPlayer) t.setFullPlayer(!t.fullPlayer); true }
            e.key == Key.Escape && t.fullPlayer -> { t.setFullPlayer(false); true }
            e.key == Key.Escape && t.typing -> { t.leaveField(); true }
            t.typing -> false
            e.key == Key.Spacebar && !ctrl && !e.isAltPressed -> { player.toggle(); true }
            e.isAltPressed && e.key == Key.DirectionLeft -> t.back()
            ctrl && e.key == Key.DirectionLeft -> { player.previous(); true }
            ctrl && e.key == Key.DirectionRight -> { player.next(); true }
            ctrl && e.key == Key.L -> { state.current?.let(actions::like); true }
            e.key == Key.DirectionLeft || e.key == Key.DirectionRight -> {
                if (state.current == null) return false
                val step = if (e.key == Key.DirectionLeft) -SEEK_MS else SEEK_MS
                val end = state.durationMs.takeIf { it > 0 } ?: state.current?.durationMs ?: 0L
                player.seekTo((state.positionMs + step).coerceIn(0L, maxOf(0L, end - 1_000L)))
                true
            }
            e.key == Key.DirectionUp || e.key == Key.DirectionDown -> {
                val step = if (e.key == Key.DirectionUp) VOLUME_STEP else -VOLUME_STEP
                player.setVolume((player.volume.value + step * (if (e.isShiftPressed) 2 else 1)).coerceIn(0f, 1f))
                true
            }
            else -> false
        }
    }
}

/** Settings › « Raccourcis clavier »: the keys on the right, as Windows writes them. */
@Composable
internal fun ShortcutsCard() {
    val ket = LocalKet.current
    SettingsCard {
        Shortcuts.list.forEachIndexed { i, s ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp).testTag("shortcut:$i"), verticalAlignment = Alignment.CenterVertically) {
                KText(s.label, Ket27Desk.Type.subheadline, Modifier.weight(1f))
                Box(Modifier.clip(RoundedCornerShape(6.dp)).background(ket.fill).padding(horizontal = 8.dp, vertical = 3.dp)) {
                    KText(s.keys, Ket27Desk.Type.footnote, color = ket.secondary, weight = FontWeight.Medium)
                }
            }
        }
    }
}
