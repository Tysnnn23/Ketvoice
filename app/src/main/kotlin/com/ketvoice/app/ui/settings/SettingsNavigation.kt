package com.ketvoice.app.ui.settings

/** Stable saved keys; every existing settings group has exactly one destination. */
internal enum class SettingsCategory(val title: String, val groups: Set<String>) {
    Account("Compte YouTube Music", setOf("YOUTUBE MUSIC", "PUBLICATIONS KETVOICE API")),
    Appearance("Apparence", setOf("APPARENCE", "LANGUE DE L'APPLICATION", "QUEST · CONFORT")),
    Design("DESIGN KETVOICE", setOf("DESIGN KETVOICE")),
    Playback("Lecture", setOf("LECTURE")),
    Lyrics("Paroles", setOf("PAROLES")),
    Canvas("Canvas", setOf("KETVOICE API · CANVAS")),
    Sources("Sources", setOf("SOURCES")),
    Downloads("Téléchargements", setOf("TÉLÉCHARGEMENTS")),
    LocalMusic("Musique locale", setOf("MUSIQUE LOCALE")),
    Storage("Stockage", setOf("STOCKAGE")),
    About("À propos", setOf("À PROPOS"));

    companion object {
        fun fromKey(key: String?): SettingsCategory? = entries.firstOrNull { it.name == key }
        fun forGroup(group: String): SettingsCategory? = entries.singleOrNull { group in it.groups }
    }
}
