package com.ketvoice.app.ui.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsNavigationTest {
    @Test fun allExistingGroupsHaveExactlyOneDestination() {
        val groups = listOf("QUEST · CONFORT", "YOUTUBE MUSIC", "DISCORD", "PUBLICATIONS KETVOICE API",
            "LANGUE DE L'APPLICATION", "DESIGN KETVOICE", "APPARENCE", "LECTURE", "KETMIX (BÊTA)", "PAROLES",
            "KETVOICE API · CANVAS", "SOURCES", "TÉLÉCHARGEMENTS", "MUSIQUE LOCALE", "STOCKAGE", "À PROPOS")
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
    @Test fun ownerPublicationAndOrdinaryPlaybackRemainReachable() {
        assertEquals(SettingsCategory.Account, SettingsCategory.forGroup("PUBLICATIONS KETVOICE API"))
        assertEquals(SettingsCategory.Playback, SettingsCategory.forGroup("LECTURE"))
        // Ketmix (the Automix of 5.0, owner request): its switch sits with playback.
        assertEquals(SettingsCategory.Playback, SettingsCategory.forGroup("KETMIX (BÊTA)"))
    }
    @Test fun theFormerInterfaceSwitchHasNoPageAnyMore() {
        // 6.0.0: Ket 27 is the only interface; its Alpha switch and section are gone.
        assertNull(SettingsCategory.forGroup("KET 27 (ALPHA)"))
    }
    @Test fun designFlagsAreNotMixedIntoTheEverydayAppearancePage() {
        assertEquals(SettingsCategory.Design, SettingsCategory.forGroup("DESIGN KETVOICE"))
        assertEquals(SettingsCategory.Appearance, SettingsCategory.forGroup("LANGUE DE L'APPLICATION"))
    }
}
