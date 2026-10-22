package com.ketvoice.app.ui.quest

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class QuestLayoutTest {
    @Test fun defaultQuestPanelUsesTheFullLandscapeWindow() {
        assertEquals(1280f, QuestLayout(1280f, 800f).contentWidthDp)
        assertEquals(1280f, QuestLayout(1280f, 800f).playerWidthDp)
    }
    @Test fun wideQuestPlayerIsNotAPortraitLetterboxOrTabletRail() {
        assertEquals(1440f, QuestLayout(1920f, 1200f).contentWidthDp)
        assertEquals(1920f, QuestLayout(1920f, 1200f).playerWidthDp)
        val mobile = com.ketvoice.app.ui.adaptive.AdaptiveLayout(1024f, 720f, preferPhone = true)
        assertFalse(mobile.hasRail)
        assertFalse(mobile.playerPanes)
        assertFalse(mobile.adaptivePlayer)
        assertEquals(1, mobile.trackColumns)
    }
    @Test fun artAndControlsHaveLessSpaceThanTheLyricsPane() {
        assertTrue(QuestLayout.CONTROL_PANE_FRACTION in 0.35f..0.45f)
        assertTrue(1f - QuestLayout.CONTROL_PANE_FRACTION > QuestLayout.CONTROL_PANE_FRACTION)
    }
    @Test fun lyricSizeIsBoundedAndIncreasing() {
        assertEquals(1f, QuestLayout.lyricsScale(-1))
        assertTrue(QuestLayout.lyricsScale(1) > QuestLayout.lyricsScale(0))
        assertTrue(QuestLayout.lyricsScale(2) > QuestLayout.lyricsScale(1))
        assertEquals(QuestLayout.lyricsScale(2), QuestLayout.lyricsScale(50))
    }
    @Test fun questManifestLocksBothActivitiesAndTheWindowToLandscape() {
        val manifest = File("src/quest/AndroidManifest.xml")
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(manifest)
        val ns = "http://schemas.android.com/apk/res/android"
        val activities = document.getElementsByTagName("activity")
        assertEquals(2, activities.length)
        for (i in 0 until activities.length) {
            val activity = activities.item(i) as org.w3c.dom.Element
            assertEquals("landscape", activity.getAttributeNS(ns, "screenOrientation"))
        }
        val layout = document.getElementsByTagName("layout").item(0) as org.w3c.dom.Element
        assertEquals("1280dp", layout.getAttributeNS(ns, "defaultWidth"))
        assertEquals("800dp", layout.getAttributeNS(ns, "defaultHeight"))
        val entries = document.getElementsByTagName("meta-data")
        assertTrue((0 until entries.length).any {
            val element = entries.item(it) as org.w3c.dom.Element
            element.getAttributeNS(ns, "name") == "com.oculus.vrshell.free_resizing_lock_aspect_ratio" &&
                element.getAttributeNS(ns, "value") == "true"
        })
    }
}
