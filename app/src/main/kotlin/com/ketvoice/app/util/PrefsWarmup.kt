package com.ketvoice.app.util

import android.content.Context

/**
 * Faster start (6.2.2): Android reads each settings file the first time it is asked for and the main thread waits
 * for it. The files the first screen reads are loaded here, on a thread of their own, while the main thread builds the
 * screen; when it asks, they are already in memory. Nothing is written and no file is created.
 */
object PrefsWarmup {
    /** The files the first screen and the playback service read, most needed first. */
    val NAMES = listOf(
        "ketvoice_settings", "ketvoice-account", "ketvoice-account-sync", "home-feed-v1", "home-learning",
        "listening-history", "weekly-discoveries", "for-you", "artist-prefs", "ketvoice_custom_sources",
        "custom-lyrics", "ketvoice_overrides", "ketvoice_canvas", "ketvoice_art", "ketvoice-editions",
        "playback-revival", "background-playback", "ketmix-points-v1", "ketvoice-smartlib", "ketvoice_updates",
    )

    fun start(context: Context) {
        val app = context.applicationContext
        Thread({ load(app, NAMES) }, "ketvoice-prefs-warmup").apply {
            isDaemon = true; priority = Thread.NORM_PRIORITY - 1
        }.start()
    }

    /** Loads each file in turn; one that fails to load never stops the others. Returns how many were loaded. */
    internal fun load(context: Context, names: List<String>): Int =
        names.count { runCatching { context.getSharedPreferences(it, Context.MODE_PRIVATE).all; true }.getOrDefault(false) }
}
