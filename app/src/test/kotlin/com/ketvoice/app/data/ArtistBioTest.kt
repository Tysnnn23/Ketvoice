package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

class ArtistBioTest {
    @Test fun theBiographyIsAskedInTheLanguageOfTheApp() {
        assertEquals("en", ArtistBio.target(AppLanguage.ENGLISH))
        assertEquals("fr", ArtistBio.target(AppLanguage.FRENCH))
        assertEquals("the service knows Chinese by its script", "zh-CN", ArtistBio.target(AppLanguage.CHINESE))
        assertEquals("ko", ArtistBio.target(AppLanguage.KOREAN))
        // A text sent back unchanged (already in that language) is not offered as a translation.
        assertFalse(ArtistBio.differs("Werenoi est un rappeur français.", "Werenoi est un rappeur français"))
        assertFalse(ArtistBio.differs("Text", null))
        assertTrue(ArtistBio.differs("Werenoi is a French rapper.", "Werenoi est un rappeur français."))
    }
}
