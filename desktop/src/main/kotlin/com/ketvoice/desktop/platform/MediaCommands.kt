package com.ketvoice.desktop.platform

import com.ketvoice.app.player.PlayerUiState

/**
 * What the platform needs from the player: the transport commands. Main wires it to the
 * `Player` facade; tests use a recording fake.
 */
interface PlatformBridge {
    fun toggle()
    fun play()
    fun pause()
    fun next()
    fun previous()
    fun seekTo(ms: Long)

    companion object {
        val None: PlatformBridge = object : PlatformBridge {
            override fun toggle() {}
            override fun play() {}
            override fun pause() {}
            override fun next() {}
            override fun previous() {}
            override fun seekTo(ms: Long) {}
        }
    }
}

/** A transport request from the media keys, SMTC or the tray. */
sealed interface MediaCommand {
    data object Play : MediaCommand
    data object Pause : MediaCommand
    data object Toggle : MediaCommand
    data object Next : MediaCommand
    data object Previous : MediaCommand
    data object Stop : MediaCommand
    data class Seek(val positionMs: Long) : MediaCommand
}

object MediaCommands {
    // Virtual-key codes (WinUser.h).
    const val VK_MEDIA_NEXT_TRACK = 0xB0
    const val VK_MEDIA_PREV_TRACK = 0xB1
    const val VK_MEDIA_STOP = 0xB2
    const val VK_MEDIA_PLAY_PAUSE = 0xB3

    /** The keys the RegisterHotKey fallback claims, in hotkey-id order (id = [HOTKEY_BASE] + index). */
    val hotkeys = listOf(VK_MEDIA_PLAY_PAUSE, VK_MEDIA_NEXT_TRACK, VK_MEDIA_PREV_TRACK, VK_MEDIA_STOP)
    const val HOTKEY_BASE = 0x4B56 // "KV"

    fun fromVirtualKey(vk: Int): MediaCommand? = when (vk) {
        VK_MEDIA_PLAY_PAUSE -> MediaCommand.Toggle
        VK_MEDIA_NEXT_TRACK -> MediaCommand.Next
        VK_MEDIA_PREV_TRACK -> MediaCommand.Previous
        VK_MEDIA_STOP -> MediaCommand.Stop
        else -> null
    }

    fun fromHotkeyId(id: Int): MediaCommand? = hotkeys.getOrNull(id - HOTKEY_BASE)?.let(::fromVirtualKey)

    /** The DLL's callback codes (native/smtc/ketvoice_smtc.cpp). */
    fun fromNative(code: Int, value: Long): MediaCommand? = when (code) {
        1 -> MediaCommand.Play
        2 -> MediaCommand.Pause
        3 -> MediaCommand.Next
        4 -> MediaCommand.Previous
        5 -> MediaCommand.Seek(value.coerceAtLeast(0))
        6 -> MediaCommand.Stop
        7 -> MediaCommand.Toggle // the taskbar thumbnail's play/pause button
        else -> null
    }

    fun dispatch(command: MediaCommand, bridge: PlatformBridge) {
        when (command) {
            MediaCommand.Play -> bridge.play()
            MediaCommand.Pause, MediaCommand.Stop -> bridge.pause()
            MediaCommand.Toggle -> bridge.toggle()
            MediaCommand.Next -> bridge.next()
            MediaCommand.Previous -> bridge.previous()
            is MediaCommand.Seek -> bridge.seekTo(command.positionMs)
        }
    }
}

/** What the system's now-playing card shows. */
data class NowPlaying(
    val title: String,
    val artist: String,
    val album: String,
    /** A local file path or an http(s) URL, or null. */
    val art: String?,
    val playing: Boolean,
    val positionMs: Long,
    val durationMs: Long,
) {
    /** Worth sending again: anything but the position changed, or the position moved by a second or more. */
    fun differsFrom(previous: NowPlaying?): Boolean =
        previous == null || copy(positionMs = 0) != previous.copy(positionMs = 0) ||
            kotlin.math.abs(positionMs - previous.positionMs) >= 1000

    companion object {
        fun of(state: PlayerUiState): NowPlaying? {
            val track = state.current ?: return null
            val art = track.coverUrl?.takeIf { it.isNotBlank() }?.let { url ->
                if (url.startsWith("file:")) runCatching { java.io.File(java.net.URI(url)).path }.getOrNull() else url
            }
            val duration = if (state.durationMs > 0) state.durationMs else track.durationMs
            return NowPlaying(track.title, track.artist, track.album, art, state.isPlaying,
                state.positionMs.coerceAtLeast(0), duration.coerceAtLeast(0))
        }
    }
}
