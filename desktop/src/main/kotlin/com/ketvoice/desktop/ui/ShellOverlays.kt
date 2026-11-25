package com.ketvoice.desktop.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf

/**
 * Layers a feature draws over the whole window (Windows 2.0): the drop overlay of « Glisser-déposer partout », the
 * cards of followed outside playlists… Drawn by the shell above the pages, the island and the panel, under the
 * menus, in their [order]. Register from the feature's own file, as with [PageRegistry].
 */
object ShellOverlays {
    /** [content] gets the shell's glass backdrop (the pages), to refract with `Modifier.glass`. */
    data class Overlay(val id: String, val order: Int = 100, val content: @Composable BoxScope.(com.ketvoice.desktop.ui.glass.GlassBackdrop?) -> Unit)

    private val overlays = mutableStateListOf<Overlay>()

    fun register(overlay: Overlay) {
        overlays.removeAll { it.id == overlay.id }
        overlays += overlay
        overlays.sortBy { it.order }
    }

    fun unregister(id: String) { overlays.removeAll { it.id == id } }

    @Composable
    fun BoxScope.Draw(backdrop: com.ketvoice.desktop.ui.glass.GlassBackdrop?) { overlays.toList().forEach { o -> androidx.compose.runtime.key(o.id) { o.content(this, backdrop) } } }
}
