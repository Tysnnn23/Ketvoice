package com.ketvoice.app.ui.update

import org.junit.Assert.*
import org.junit.Test

class UpdateBannerTest {
    @Test fun canonicalGitHubBlobBannerIsNormalized() {
        assertEquals(UpdateChecker.CANONICAL_BANNER, UpdateChecker.releaseBanner(
            "![Ketvoice](https://github.com/Tysnnn23/Ketvoice/blob/main/ketvoice-banner.png)\n## Changes"))
    }
    @Test fun absentOrUntrustedImagesAreNotLoaded() {
        listOf("## Changes", "![x](https://evil.example/banner.png)",
            "![x](https://raw.githubusercontent.com/other/repository/main/banner.png)",
            "![x](https://github.com/Tysnnn23/Ketvoice/blob/main/test.svg)").forEach {
            assertNull(UpdateChecker.releaseBanner(it))
        }
    }
}
