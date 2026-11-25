package com.ketvoice.desktop.connect

import com.ketvoice.app.data.Track

/**
 * The share page of a title (« Écouter sur Ketvoice » in Discord), or null when it has none. The share links feature
 * plugs its own function into [provider] at start; until it does, Discord shows no button.
 */
object ShareLinks {
    @Volatile var provider: (Track) -> String? = { null }

    /** An https address of the share page, or null. */
    fun trackUrl(track: Track): String? =
        runCatching { provider(track) }.getOrNull()?.takeIf { it.startsWith("https://") && it.length <= 512 }
}
