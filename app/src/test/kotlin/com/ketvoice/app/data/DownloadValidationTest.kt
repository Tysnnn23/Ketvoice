package com.ketvoice.app.data

import org.junit.Assert.*
import org.junit.Test

class DownloadValidationTest {
    @Test fun acceptsRealContainerHeaders() {
        listOf("fLaC", "ID3\u0004", "OggS", "RIFF").forEach {
            assertTrue(it, DownloadValidation.audio(it.toByteArray()))
        }
        assertTrue(DownloadValidation.audio(byteArrayOf(-1, -5, 0, 0)))
        assertTrue(DownloadValidation.audio(byteArrayOf(0, 0, 0, 24) + "ftypM4A ".toByteArray()))
    }

    @Test fun rejectsErrorPagesAndShortResponses() {
        listOf("<html>unauthorized</html>", "{\"error\":403}", "", "ID").forEach {
            assertFalse(it, DownloadValidation.audio(it.toByteArray()))
        }
    }

    @Test fun rejectsTruncatedResponsesButAllowsUnknownLength() {
        assertFalse(DownloadValidation.complete(9999, -1))
        assertFalse(DownloadValidation.complete(20_000, 50_000))
        assertFalse(DownloadValidation.complete(60_000, 50_000))
        assertTrue(DownloadValidation.complete(50_000, 50_000))
        assertTrue(DownloadValidation.complete(50_000, -1))
    }
}
