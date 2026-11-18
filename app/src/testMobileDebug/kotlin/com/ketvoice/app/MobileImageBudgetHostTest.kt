package com.ketvoice.app

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 6.6: the mobile edition takes up Ketvoice Lite's rules that change nothing one sees. Every picture is decoded no
 * larger than the screen; a modest phone is found here too, for its decoder and Canvas budgets.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h900dp-mdpi")
class MobileImageBudgetHostTest {
    @Test fun mobileBoundsEveryDecodeToTheScreen() {
        assertFalse(BuildConfig.IS_LITE)
        val app = ApplicationProvider.getApplicationContext<KetvoiceApp>()
        val loader = app.newImageLoader()
        val bound = loader.components.interceptors.filterIsInstance<DecodeBound>()
        assertEquals("one decode bound in the mobile image loader", 1, bound.size)
        // The screen's longer side (900 dp at mdpi): a full-size 3000 px cover is decoded at 900 px.
        assertEquals(900 to 900, LiteTuning.boundedDecode(3000, 3000, LiteTuning.decodeBoundPx(900)!!))
    }
}
