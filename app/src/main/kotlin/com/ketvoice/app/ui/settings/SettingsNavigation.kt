package com.ketvoice.app.ui.settings

/** Stable saved keys; every existing settings group has exactly one destination. */
internal enum class SettingsCategory(val title: String, val groups: Set<String>) {
    Account("Compte", setOf("YOUTUBE MUSIC", "DISCORD", "PUBLICATIONS KETVOICE API")),
    Appearance("Apparence", setOf("APPARENCE", "VERRE", "COULEURS DE LA POCHETTE", "LANGUE DE L'APPLICATION", "QUEST · CONFORT", "WIDGETS")),
    Design("DESIGN KETVOICE", setOf("DESIGN KETVOICE")),
    Playback("Lecture", setOf("LECTURE", "KETMIX (BÊTA)")),
    Lyrics("Paroles", setOf("PAROLES")),
    Canvas("Canvas", setOf("KETVOICE API · CANVAS")),
    Sources("Sources", setOf("SOURCES")),
    Downloads("Téléchargements", setOf("TÉLÉCHARGEMENTS", "HORS LIGNE INTELLIGENT")),
    LocalMusic("Musique locale", setOf("MUSIQUE LOCALE")),
    Storage("Stockage", setOf("STOCKAGE")),
    About("À propos", setOf("À PROPOS")),
    /** « Personnalisation » (2026-11-22): its own page ([PersonalizePage]), last so the other pages keep their tiles' colours. */
    Personalize("Personnalisation", setOf("DISPOSITION"));

    companion object {
        fun fromKey(key: String?): SettingsCategory? = entries.firstOrNull { it.name == key }
        fun forGroup(group: String): SettingsCategory? = entries.singleOrNull { group in it.groups }
    }
}
