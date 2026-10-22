package com.ketvoice.app.ui.quest

import org.junit.Assert.*
import org.junit.Test

class QuestLayoutTest {
    @Test fun defaultQuestPanelKeepsPhoneProportions() {
        assertEquals(540f, QuestLayout(540f, 900f).contentWidthDp)
        assertEquals(540f, QuestLayout(540f, 900f).playerWidthDp)
    }
    @Test fun wideQuestWindowsDoNotStretchThePhonePlayer() {
        assertEquals(600f, QuestLayout(1440f, 900f).contentWidthDp)
        assertEquals(468f, QuestLayout(1024f, 720f).playerWidthDp, .01f)
        val mobile = com.ketvoice.app.ui.adaptive.AdaptiveLayout(1024f, 720f, preferPhone = true)
        assertFalse(mobile.hasRail)
        assertFalse(mobile.playerPanes)
        assertFalse(mobile.adaptivePlayer)
        assertEquals(1, mobile.trackColumns)
    }
    @Test fun lyricSizeIsBoundedAndIncreasing() {
        assertEquals(1f, QuestLayout.lyricsScale(-1))
        assertTrue(QuestLayout.lyricsScale(1) > QuestLayout.lyricsScale(0))
        assertTrue(QuestLayout.lyricsScale(2) > QuestLayout.lyricsScale(1))
        assertEquals(QuestLayout.lyricsScale(2), QuestLayout.lyricsScale(50))
    }
}
