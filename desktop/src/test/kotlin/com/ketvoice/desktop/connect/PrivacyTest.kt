package com.ketvoice.desktop.connect

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Zero telemetry (Windows 2.0 « Mes données »): no analytics or crash-reporting library or host anywhere in the desktop
 * sources, every address the desktop app writes out belongs to a feature the listener uses, and the 8.2 diagnostic
 * stays off by default. A new host here must also be listed in « Mes données » (MyDataPage.WHAT_LEAVES).
 */
class PrivacyTest {
    private val sources = File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Hosts the desktop app may call, and why. */
    private val allowed = mapOf(
        "github.com" to "updates and model downloads (releases)",
        "api.github.com" to "the update check (windows-v* releases)",
        "huggingface.co" to "a model's original source, on request",
        "api.nuget.org" to "the GPU plugin, on request (Maximum)",
        "music.youtube.com" to "the catalogue source the listener signs in to",
        "api.listenbrainz.org" to "scrobbling, once linked",
        "listenbrainz.readthedocs.io" to "a documentation link in a comment",
        "www.last.fm" to "Last.fm's allow page, once the listener links it",
    )

    @Test fun noAnalyticsOrCrashReporting() {
        assertTrue(sources.size > 50)
        // Libraries and services by name (whole words: « KetmixPanel » is not Mixpanel), as an import or an address.
        val banned = Regex("(?i)\\b(sentry|crashlytics|firebase|mixpanel|bugsnag|posthog|datadog|appcenter)\\b|segment\\.(io|com)|google-analytics|amplitude\\.com")
        val hits = sources.filter { banned.containsMatchIn(it.readText()) }.map { it.name }
        assertEquals(emptyList(), hits)
    }

    @Test fun everyHostIsAFeatureTheListenerUses() {
        val hosts = sources.flatMap { f -> Regex("https://([a-zA-Z0-9.-]+)").findAll(f.readText()).map { it.groupValues[1] }.toList() }.toSet()
        assertEquals(emptySet(), hosts - allowed.keys)
    }

    @Test fun theDiagnosticStaysOptIn() {
        assertFalse(com.ketvoice.app.data.AppSettings.diagnostics.value)
        assertEquals(5, com.ketvoice.desktop.ui.WHAT_LEAVES.size)
    }
}
