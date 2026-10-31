package com.ketvoice.app.ui.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Real private-file writes with a mocked HTTPS connection and archive verifier, not a device install. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UpdateDownloadHostTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun prepare() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private val bytes = "mock APK transfer fixture".toByteArray()
    private fun info(size: Long = bytes.size.toLong(), hash: String? = null) = UpdateInfo("v99.0.0", "QA", emptyList(),
        UpdateFeed.DOWNLOAD_PREFIX + "v99.0.0/Ketvoice-99.0.0.apk", "", "", size, hash)
    private class Connection(private val bytes: ByteArray) : HttpURLConnection(URL("https://github.com/fixture")) {
        var closed = false
        override fun connect() {}
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getContentLengthLong() = -1L
        override fun getInputStream() = ByteArrayInputStream(bytes)
    }

    @Test fun exactVerifiedPayloadBecomesAnInstallableTargetOnlyAfterValidation() = runTest {
        val connection = Connection(bytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        var validated = false
        val progress = mutableListOf<Float>()
        val result = UpdateInstaller.download(context, info(hash = hash), { progress += it }, { connection }, { _, file, _ ->
            assertTrue(file.name.endsWith(".part.apk"))
            assertArrayEquals(bytes, file.readBytes())
            validated = true
        })
        assertTrue(validated); assertTrue(connection.closed)
        assertArrayEquals(bytes, result.readBytes())
        assertEquals(1f, progress.last())
        assertFalse(File(result.parentFile, "ketvoice-99.0.0.part.apk").exists())
    }

    @Test fun truncatedOrWrongDigestPayloadNeverLeavesAPartialInstallFile() = runTest {
        for (candidate in listOf(info(size = bytes.size + 1L), info(hash = "0".repeat(64)))) {
            val connection = Connection(bytes)
            var failed = false
            try {
                UpdateInstaller.download(context, candidate, {}, { connection }, { _, _, _ -> fail("must not validate bad bytes") })
            } catch (_: java.io.IOException) { failed = true }
            assertTrue(failed); assertTrue(connection.closed)
            assertFalse(File(context.cacheDir, "updates/ketvoice-99.0.0.part.apk").exists())
            assertFalse(File(context.cacheDir, "updates/ketvoice-99.0.0.apk").exists())
        }
    }

    @Test fun failedArchiveValidationCannotPublishADownloadedApk() = runTest {
        val connection = Connection(bytes)
        var rejected = false
        try {
            UpdateInstaller.download(context, info(), {}, { connection }, { _, _, _ -> throw IllegalArgumentException("wrong signature") })
        } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected); assertTrue(connection.closed)
        assertFalse(File(context.cacheDir, "updates/ketvoice-99.0.0.apk").exists())
        assertFalse(File(context.cacheDir, "updates/ketvoice-99.0.0.part.apk").exists())
    }
}
