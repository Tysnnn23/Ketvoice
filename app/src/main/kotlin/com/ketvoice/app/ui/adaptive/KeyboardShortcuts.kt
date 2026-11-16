package com.ketvoice.app.ui.adaptive

import android.view.KeyEvent as AndroidKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import com.ketvoice.app.player.Player
import com.ketvoice.app.ui.AppUi

/** What a key does in Ketvoice on a large screen. */
enum class Shortcut {
    None, PlayPause, SeekBack, SeekForward, SeekBackLong, SeekForwardLong, VolumeUp, VolumeDown,
    PreviousTrack, NextTrack, Search, Back, Lyrics, Queue, ToggleSidebar
}

/**
 * The keyboard of a tablet, a Chromebook or a desktop window, as on Apple Music for Mac and iPad:
 * Space plays and pauses, the arrows seek and change the volume (while no control holds the focus,
 * so that the arrows still move it from one control to the next once Tab has entered them), Ctrl or
 * ⌘ with the arrows changes title or volume from anywhere, Ctrl/⌘ F searches, Esc goes back (closes a
 * sheet, the player, a page), Ctrl/⌘ L opens the lyrics, Ctrl/⌘ U the queue, Ctrl/⌘ ⇧ S the sidebar.
 */
object KeyboardShortcuts {
    /** [freeFocus]: no control of the page holds the focus, the app itself does. */
    fun of(keyCode: Int, ctrl: Boolean, meta: Boolean, shift: Boolean, alt: Boolean, freeFocus: Boolean): Shortcut {
        val command = ctrl || meta
        if (alt) return Shortcut.None
        return when (keyCode) {
            AndroidKey.KEYCODE_SPACE -> if (!command && !shift) Shortcut.PlayPause else Shortcut.None
            AndroidKey.KEYCODE_DPAD_LEFT -> when {
                command -> Shortcut.PreviousTrack
                !freeFocus -> Shortcut.None
                shift -> Shortcut.SeekBackLong
                else -> Shortcut.SeekBack
            }
            AndroidKey.KEYCODE_DPAD_RIGHT -> when {
                command -> Shortcut.NextTrack
                !freeFocus -> Shortcut.None
                shift -> Shortcut.SeekForwardLong
                else -> Shortcut.SeekForward
            }
            AndroidKey.KEYCODE_DPAD_UP -> if (command || freeFocus) Shortcut.VolumeUp else Shortcut.None
            AndroidKey.KEYCODE_DPAD_DOWN -> if (command || freeFocus) Shortcut.VolumeDown else Shortcut.None
            AndroidKey.KEYCODE_F -> if (command && !shift) Shortcut.Search else Shortcut.None
            AndroidKey.KEYCODE_L -> if (command && !shift) Shortcut.Lyrics else Shortcut.None
            AndroidKey.KEYCODE_U -> if (command && !shift) Shortcut.Queue else Shortcut.None
            AndroidKey.KEYCODE_S -> if (command && shift) Shortcut.ToggleSidebar else Shortcut.None
            AndroidKey.KEYCODE_ESCAPE -> if (!command && !shift) Shortcut.Back else Shortcut.None
            else -> Shortcut.None
        }
    }

    const val SEEK_MS = 10_000L
    const val SEEK_LONG_MS = 30_000L

    /** How far a seek shortcut moves, in ms (0 for any other shortcut). */
    fun seekDelta(shortcut: Shortcut): Long = when (shortcut) {
        Shortcut.SeekBack -> -SEEK_MS
        Shortcut.SeekForward -> SEEK_MS
        Shortcut.SeekBackLong -> -SEEK_LONG_MS
        Shortcut.SeekForwardLong -> SEEK_LONG_MS
        else -> 0L
    }
}

/**
 * Gives the app the keyboard on a large screen: the root holds the focus whenever no control does
 * (so keys arrive even before anything was touched), and the shortcuts above run. A key a focused
 * control uses itself (a text field typing a space, a button pressed with Space) never reaches them.
 * Does nothing when [enabled] is false: the phone and Quest keep their behaviour.
 */
@Composable
fun Modifier.keyboardShortcuts(enabled: Boolean, onToggleSidebar: () -> Unit): Modifier {
    if (!enabled) return this
    val requester = remember { FocusRequester() }
    var ownFocus by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    return this
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val shortcut = KeyboardShortcuts.of(event.key.nativeKeyCode, event.isCtrlPressed, event.isMetaPressed,
                event.isShiftPressed, event.isAltPressed, ownFocus)
            when (shortcut) {
                Shortcut.None -> false
                Shortcut.PlayPause -> { Player.toggle(); true }
                Shortcut.SeekBack, Shortcut.SeekForward, Shortcut.SeekBackLong, Shortcut.SeekForwardLong -> {
                    Player.seekBy(KeyboardShortcuts.seekDelta(shortcut)); true
                }
                Shortcut.VolumeUp, Shortcut.VolumeDown -> {
                    // The system's own volume, with its panel: the listener sees where it stands.
                    val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
                    audio?.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC,
                        if (shortcut == Shortcut.VolumeUp) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
                        android.media.AudioManager.FLAG_SHOW_UI)
                    true
                }
                Shortcut.PreviousTrack -> { Player.previous(); true }
                Shortcut.NextTrack -> { Player.next(); true }
                Shortcut.Search -> {
                    AppUi.showPlayer.value = false
                    AppUi.openSearch()
                    LargeScreenNav.focusSearch.value = true
                    true
                }
                Shortcut.Lyrics -> {
                    if (Player.state.value.current == null) false
                    else { AppUi.showLyrics.value = true; AppUi.showPlayer.value = true; true }
                }
                Shortcut.Queue -> {
                    if (Player.state.value.current == null) false
                    else { LargeScreenNav.openQueue.value = true; AppUi.showPlayer.value = true; true }
                }
                Shortcut.ToggleSidebar -> { onToggleSidebar(); true }
                Shortcut.Back -> {
                    // A control holding the focus (the search field) lets it go first; then Esc is Back:
                    // the same handlers close a sheet, the player, a Canvas, a page. Never the app itself.
                    if (!ownFocus) { focus.clearFocus(); runCatching { requester.requestFocus() }; true }
                    else {
                        val dispatcher = (context.findComponentActivity())?.onBackPressedDispatcher
                        if (dispatcher != null && dispatcher.hasEnabledCallbacks()) { dispatcher.onBackPressed(); true } else false
                    }
                }
            }
        }
        .focusRequester(requester)
        .onFocusChanged { state ->
            ownFocus = state.isFocused
            // The focused control went away (a page closed under it): the app takes the keyboard back.
            if (!state.hasFocus) runCatching { requester.requestFocus() }
        }
        // A focus target without semantics: a screen reader never stops on the app itself.
        .focusTarget()
}

private tailrec fun android.content.Context.findComponentActivity(): androidx.activity.ComponentActivity? = when (this) {
    is androidx.activity.ComponentActivity -> this
    is android.content.ContextWrapper -> baseContext.findComponentActivity()
    else -> null
}

/** Requests between the root (keyboard, sidebar) and the pages that act on them. Not saved. */
object LargeScreenNav {
    /** Raised by Ctrl/⌘ F, lowered by the search field once it has taken the focus. */
    val focusSearch = mutableStateOf(false)
    /** Raised by Ctrl/⌘ U, lowered by the player once it shows its queue. */
    val openQueue = mutableStateOf(false)
    /** A section of the library chosen in the sidebar (Playlists 1, Albums 2, Titres 3, Artistes 4), consumed by the library. */
    val librarySection = mutableStateOf<Int?>(null)
    /** The section the library shows, for the sidebar to light it. */
    val shownLibrarySection = androidx.compose.runtime.mutableIntStateOf(0)
}
