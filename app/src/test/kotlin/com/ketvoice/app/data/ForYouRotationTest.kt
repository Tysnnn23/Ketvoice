package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

class ForYouRotationTest {
    @Test fun thePosterTakesTurnsInsteadOfShowingTheSameReleaseEveryTime() {
        val candidates = listOf("release:a", "release:b", "mix:m1")
        var seen = emptyMap<String, Int>()
        var last: String? = null
        val shown = ArrayList<String>()
        repeat(9) { launch ->
            val chosen = ForYouRotation.pick(candidates, seen, last, launch.toLong())!!
            assertNotEquals("never twice in a row", last, chosen)
            shown += chosen; seen = ForYouRotation.shown(seen, chosen, candidates); last = chosen
        }
        // Over nine launches each of the three is shown three times.
        assertEquals(listOf(3, 3, 3), candidates.map { c -> shown.count { it == c } })
        // A single candidate is still shown; a release that is no longer new is forgotten.
        assertEquals("mix:m1", ForYouRotation.pick(listOf("mix:m1"), mapOf("mix:m1" to 5), "mix:m1", 0))
        assertFalse("release:a" in ForYouRotation.shown(mapOf("release:a" to 4), "mix:m1", listOf("mix:m1")))
        assertNull(ForYouRotation.pick(emptyList(), emptyMap(), null, 0))
        // Kept on the device as text.
        val counts = mapOf("release:77" to 2, "mix:artist:x" to 1)
        assertEquals(counts, ForYouRotation.decode(ForYouRotation.encode(counts)))
        // Within a launch the card keeps what it shows while it is still a candidate (releases arriving late do not flip it).
        ForYouRotation.newLaunchForTest()
        val first = ForYouRotation.choose(listOf("mix:m1"))
        assertEquals(first, ForYouRotation.choose(listOf("release:new", "mix:m1")))
        assertEquals("release:new", ForYouRotation.choose(listOf("release:new")))
        ForYouRotation.newLaunchForTest()
    }
}
