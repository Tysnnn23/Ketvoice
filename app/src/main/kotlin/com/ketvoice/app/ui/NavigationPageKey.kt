package com.ketvoice.app.ui

/** Stable content identity: unrelated stale requests must not replace a tab. */
internal fun navigationPageKey(tab: Int, entry: AppUi.OverlayEntry): String = when (entry.overlay) {
    AppUi.NONE -> "tab:$tab"
    AppUi.CATALOG -> "catalog:${entry.catalogRequest?.kind}:${entry.catalogRequest?.id}"
    AppUi.ARTIST -> "artist:${entry.artistRequest?.id}:${entry.artistRequest?.name}"
    AppUi.PLAYLIST -> "playlist:${entry.playlistId}"
    else -> "overlay:${entry.overlay}"
}

internal fun AppUi.OverlayEntry.isArtworkDetail(): Boolean =
    overlay == AppUi.ARTIST || (overlay == AppUi.CATALOG && catalogRequest?.kind == "album")
