package com.ketvoice.app.discord

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Receives `discord-1566454532890820668:/authorize/callback?...` from the browser.
 *
 * The SDK context is initialised here too: a cold start from this activity used to
 * create a client without an engine activity, so its callbacks never fired.
 */
class DiscordCallbackActivity : Activity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.ketvoice.app.data.AppLocales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { Discord.attach(this) }
        Discord.completeAuthorization(intent?.data)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        runCatching { Discord.attach(this) }
        Discord.completeAuthorization(intent.data)
    }
}
