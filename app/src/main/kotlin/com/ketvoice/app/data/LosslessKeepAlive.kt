package com.ketvoice.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/** Best effort only while the app process is alive; Doze and force-stop can defer or stop it. */
internal object LosslessKeepAlive {
    private val endpoints = listOf(CustomSources.LOSSLESS_MANIFEST, "https://ketvoice-tidal.onrender.com/")

    fun start(scope: CoroutineScope) = scope.launch(Dispatchers.IO) {
        while (isActive) {
            if (CustomSources.enabledSources().any { it.manifestUrl == CustomSources.LOSSLESS_MANIFEST }) {
                for (endpoint in endpoints) {
                    try {
                        CancellableConnection.use({ URL(endpoint).openConnection() as HttpURLConnection }) { connection ->
                            connection.connectTimeout = 8_000
                            connection.readTimeout = 8_000
                            connection.setRequestProperty("User-Agent", "Ketvoice/2.3 (Android)")
                            connection.responseCode
                        }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { /* A sleeping or unavailable service must not affect playback. */ }
                }
            }
            delay(10 * 60 * 1000L)
        }
    }
}
