package com.ketvoice.app.ui.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsNavigationTest {
    @Test fun allExistingGroupsHaveExactlyOneDestination() {
        val groups = listOf("QUEST · CONFORT", "YOUTUBE MUSIC", "PUBLICATIONS KETVOICE API",
            "LANGUE DE L'APPLICATION", "DESIGN KETVOICE", "APPARENCE", "LECTURE", "PAROLES",
            "KETVOICE API · CANVAS", "SOURCES", "TÉLÉCHARGEMENTS", "MUSIQUE LOCALE", "STOCKAGE", "À PROPOS", "AUTOMIX")
        assertEquals(groups.toSet(), SettingsCategory.entries.flatMap { it.groups }.toSet())
        groups.forEach { assertEquals(1, SettingsCategory.entries.count { category -> it in category.groups }) }
    }
    @Test fun savedCategoryKeysRoundTripWithoutDependingOnTranslatedTitles() {
        SettingsCategory.entries.forEach { assertEquals(it, SettingsCategory.fromKey(it.name)) }
    }
    @Test fun unknownSavedKeysAndGroupsDoNotOpenAnArbitraryPage() {
        assertNull(SettingsCategory.fromKey(null))
        assertNull(SettingsCategory.fromKey("old-tab"))
        assertNull(SettingsCategory.forGroup("unknown"))
    }
    @Test fun ownerPublicationAndAutoMixControlsRemainReachable() {
        assertEquals(SettingsCategory.Account, SettingsCategory.forGroup("PUBLICATIONS KETVOICE API"))
        assertEquals(SettingsCategory.Playback, SettingsCategory.forGroup("AUTOMIX"))
    }
    @Test fun designFlagsAreNotMixedIntoTheEverydayAppearancePage() {
        assertEquals(SettingsCategory.Design, SettingsCategory.forGroup("DESIGN KETVOICE"))
        assertEquals(SettingsCategory.Appearance, SettingsCategory.forGroup("LANGUE DE L'APPLICATION"))
    }
}
