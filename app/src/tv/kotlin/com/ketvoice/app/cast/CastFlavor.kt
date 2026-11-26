package com.ketvoice.app.cast

import android.content.Context

/** A TV is a Cast receiver, not a sender: the same calls as the phones' CastFlavor, doing nothing. */
object CastFlavor {
    fun available(appContext: Context): Boolean = false
    fun scan(appContext: Context, on: Boolean) {}
    fun select(id: String) {}
    fun disconnect() {}
    fun attach(service: Context, playbackHost: CastHost) {}
    fun detach() {}
}
