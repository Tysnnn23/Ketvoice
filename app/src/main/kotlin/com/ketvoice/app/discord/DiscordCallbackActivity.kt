package com.ketvoice.app.discord

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Receives `discord-1566454532890820668:/authorize/callback?...` from the browser. */
class DiscordCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Discord.completeAuthorization(intent?.data)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Discord.completeAuthorization(intent.data)
    }
}
