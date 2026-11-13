package com.ketvoice.app.widget

import org.junit.Assert.*
import org.junit.Test

class WidgetLayoutTest {
    @Test fun sizeSelectsTheLayout() {
        assertEquals(WidgetLayout.Compact, widgetLayoutFor(110f, 50f))
        assertEquals(WidgetLayout.Compact, widgetLayoutFor(200f, 200f))
        assertEquals(WidgetLayout.Row, widgetLayoutFor(250f, 50f))
        assertEquals(WidgetLayout.Row, widgetLayoutFor(400f, 100f))
        assertEquals(WidgetLayout.Card, widgetLayoutFor(250f, 120f))
    }

    @Test fun anEmptyStateHasNoTrack() {
        assertFalse(WidgetNowPlaying().hasTrack)
        assertTrue(WidgetNowPlaying(title = "Song").hasTrack)
    }
}
