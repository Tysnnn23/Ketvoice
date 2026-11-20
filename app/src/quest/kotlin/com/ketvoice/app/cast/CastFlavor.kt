package com.ketvoice.app.cast

import android.content.Context

/** Quest has no Play services, so no Google Cast: the same calls as the phones' CastFlavor, doing nothing. */
object CastFlavor {
    fun available(appContext: Context): Boolean = false
    fun scan(appContext: Context, on: Boolean) {}
    fun select(id: String) {}
    fun disconnect() {}
    fun attach(service: Context, playbackHost: CastHost) {}
    fun detach() {}
}
