package com.ketvoice.app.sync

import org.junit.Assert.*
import org.junit.Test

/** The two links of 6.3: an invitation to a shared playlist and a profile page. */
class SharedLinksTest {
    @Test fun anInvitationLinkGivesItsCodeUpperCased() {
        assertEquals("ABCDEFGH23", SharedLinks.joinCode("ketvoice://join/abcdefgh23"))
        assertEquals("ABCDEFGH23", SharedLinks.joinCode(" ketvoice://join/ABCDEFGH23/ "))
        assertEquals("ABCDEFGH23", SharedLinks.joinCode("ketvoice://join/ABCDEFGH23?utm=x"))
    }

    @Test fun otherLinksAreNotInvitations() {
        assertNull(SharedLinks.joinCode("ketvoice://listen?track=1"))
        assertNull(SharedLinks.joinCode("https://example.com/join/ABCDEFGH23"))
        assertNull(SharedLinks.joinCode("ketvoice://join/"))
        assertNull(SharedLinks.joinCode("ketvoice://join/AB CD"))
        assertNull(SharedLinks.joinCode("ketvoice://join/" + "A".repeat(40)))
    }

    @Test fun aTypedCodeIsCleanedAndAnImpossibleOneRefused() {
        assertEquals("ABCDEFGH23", SharedLinks.cleanCode("abcde fgh23"))
        assertEquals("ABCDEFGH23", SharedLinks.cleanCode("ABCDE-FGH23 "))
        assertNull(SharedLinks.cleanCode("ABCDEFGH2"))
        assertNull(SharedLinks.cleanCode("ABCDEFGHI0"))
        assertNull(SharedLinks.cleanCode("ABCDEFGHO1"))
        assertNull(SharedLinks.cleanCode(""))
    }

    @Test fun aProfileLinkGivesALowercaseHandle() {
        assertEquals("alice", SharedLinks.profileHandle("ketvoice://u/Alice"))
        assertEquals("alice.b_2", SharedLinks.profileHandle("ketvoice://u/@alice.B_2/"))
        assertNull(SharedLinks.profileHandle("ketvoice://u/ab"))
        assertNull(SharedLinks.profileHandle("ketvoice://u/a b c"))
        assertNull(SharedLinks.profileHandle("ketvoice://join/ABCDEFGH23"))
        assertEquals("ketvoice://u/alice", SharedLinks.profileLink("@Alice"))
    }
}
