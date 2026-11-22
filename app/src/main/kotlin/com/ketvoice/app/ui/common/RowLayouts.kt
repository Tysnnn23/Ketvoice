package com.ketvoice.app.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Radio
import androidx.compose.ui.graphics.vector.ImageVector
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.Downloader
import com.ketvoice.app.data.LayoutSettings
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.data.Track
import com.ketvoice.app.ui.AppUi

/** The title menu's own items (9.0 « Bibliothèque et menus »), in the listener's order; « Plus » always closes it. */
internal object TitleMenuLayout {
    /** The id, the label shown in Settings and the symbol of each item. */
    val items: List<Triple<String, String, ImageVector>> by lazy {
        listOf(
            Triple("playNext", "Lire ensuite", Icons.AutoMirrored.Filled.PlaylistPlay),
            Triple("enqueue", "Ajouter à la file", Icons.AutoMirrored.Filled.PlaylistAdd),
            Triple("favorite", "Ajouter aux favoris", Ket27Glyphs.Heart),
            Triple("download", "Télécharger", KetIcons.Download),
            Triple("playlist", "Ajouter à une playlist", KetIcons.Plus),
            Triple("radio", "Lancer la radio", Icons.Filled.Radio),
            Triple("share", "Partager", KetIcons.Share),
            Triple("mixPoint", "Point de mix…", KetIcons.Sliders),
            Triple("lyricsEditor", "Éditer les paroles…", KetIcons.Lyrics),
            Triple("about", "À propos de ce titre", KetIcons.Info))
    }

    fun label(id: String): String = items.firstOrNull { it.first == id }?.second ?: id

    fun shown(text: String?): List<String> = LayoutSettings.menuList.shown(text)
}

/**
 * What a sideways swipe on a title row does (9.0): towards the end « Lire ensuite » and towards the start « Ajouter à
 * la file » by default, as since 6.0; either way can do something else, or nothing.
 */
internal enum class SwipeAction(val id: String, val label: String, val done: String) {
    None("none", "Aucune action", ""),
    PlayNext("playNext", "Lire ensuite", "Lire ensuite"),
    Enqueue("enqueue", "Ajouter à la file", "Ajouté à la file"),
    Favorite("favorite", "Ajouter aux favoris ou les retirer", "Favoris mis à jour"),
    Download("download", "Télécharger", "Téléchargement lancé"),
    Playlist("playlist", "Ajouter à une playlist", ""),
    RemoveDownload("removeDownload", "Supprimer le téléchargement", "Téléchargement supprimé");

    val icon: ImageVector get() = when (this) {
        None -> KetIcons.Close
        PlayNext -> Icons.AutoMirrored.Filled.PlaylistPlay
        Enqueue -> Icons.AutoMirrored.Filled.PlaylistAdd
        Favorite -> Ket27Glyphs.Heart
        Download -> KetIcons.Download
        Playlist -> KetIcons.Plus
        RemoveDownload -> KetIcons.Close
    }

    companion object {
        fun of(id: String?): SwipeAction? = entries.firstOrNull { it.id == id }

        /** Towards the end of the row (to the right in a left-to-right language). */
        fun toEnd(): SwipeAction = of(LayoutSettings.swipeEnd.value) ?: PlayNext
        /** Towards the start of the row. */
        fun toStart(): SwipeAction = of(LayoutSettings.swipeStart.value) ?: Enqueue
    }
}

/** Whether [action] can act on [track] now (a title not saved cannot lose its download, a saved one is not saved again). */
internal fun swipeApplies(action: SwipeAction, track: Track): Boolean = when (action) {
    SwipeAction.None -> false
    SwipeAction.Download -> Downloader.itemFor(track.id) == null
    SwipeAction.RemoveDownload -> Downloader.itemFor(track.id) != null
    else -> true
}

/**
 * Runs a swipe that is not a queue action (those go through [Player.queueWithUndo] so they can be undone) nor the
 * removal of a download (which asks first, see `SwipeTrackActions`). Returns what the snackbar says, or null.
 */
internal fun runSwipe(action: SwipeAction, track: Track): String? {
    if (!swipeApplies(action, track)) return null
    when (action) {
        SwipeAction.Favorite -> {
            val favorite = track.id in AppSettings.favoriteIds.value
            AppSettings.favoriteIds.value = if (favorite) AppSettings.favoriteIds.value - track.id else AppSettings.favoriteIds.value + track.id
            SettingsStore.save()
            return if (favorite) "Retiré des favoris" else "Ajouté aux favoris"
        }
        SwipeAction.Download -> Downloader.download(track)
        SwipeAction.Playlist -> AppUi.addToPlaylist.value = track
        else -> return null
    }
    return action.done.ifEmpty { null }
}
