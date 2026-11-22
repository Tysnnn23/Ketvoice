package com.ketvoice.app.ui.home

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.LayoutSettings

/** A section of Home that the listener may move or hide (9.0 « Accueil modulable »); the greeting always leads. */
internal enum class HomeSection(val id: String, val label: String) {
    Featured("featured", "À la une"),
    ForYou("forYou", "Pour toi"),
    Moods("moods", "Ambiances"),
    Releases("releases", "Nouvelles sorties"),
    Recent("recent", "Récents"),
    Library("library", "Favoris et playlists épinglées"),
    Friends("friends", "Activité des amis"),
    Mixes("mixes", "Vos mixes"),
    Week("week", "Votre semaine"),
    Replay("replay", "Replay"),
    Catalog("catalog", "Albums et sélections du catalogue");

    companion object {
        fun of(id: String): HomeSection? = entries.firstOrNull { it.id == id }
    }
}

internal object HomeSections {
    /** Today's Home, from the top. */
    val DEFAULT: List<HomeSection> = HomeSection.entries

    /** The sections shown, in order, for the stored [text] (empty: [DEFAULT]). */
    fun shown(text: String?): List<HomeSection> = LayoutSettings.homeList.shown(text).mapNotNull(HomeSection::of)

    /** « Modifier l'accueil » is open over Home. */
    val editing = mutableStateOf(false)
}

/** A long press on the greeting (and a screen reader's action) opens the Home editor. */
@Composable
internal fun Modifier.homeEditGesture(): Modifier {
    val label = AppText.t("Modifier l'accueil")
    return this.semantics { customActions = listOf(CustomAccessibilityAction(label) { HomeSections.editing.value = true; true }) }
        .pointerInput(Unit) { detectTapGestures(onLongPress = { HomeSections.editing.value = true }) }
}
