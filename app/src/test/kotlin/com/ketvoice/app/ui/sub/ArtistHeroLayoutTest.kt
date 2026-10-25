package com.ketvoice.app.ui.sub

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistHeroLayoutTest {
    @Test fun panoramicBannerGetsShorterHeroWithFadeNearTheImage() {
        val hero = artistHeroLayout(393.dp, 820.dp, 1000f / 416f)
        assertTrue(hero.height < 500.dp)
        assertTrue(hero.photoHeight < 180.dp)
        assertTrue(hero.fadeEnd > hero.photoHeight.value / hero.height.value)
        assertTrue(hero.fadeEnd < .82f)
    }

    @Test fun portraitArtworkKeepsImmersiveHeight() {
        val hero = artistHeroLayout(393.dp, 820.dp, 1f)
        assertTrue(hero.height > 700.dp)
        assertTrue(hero.photoHeight > 380.dp)
        assertTrue(hero.fadeStart < hero.photoHeight.value / hero.height.value)
    }
}
