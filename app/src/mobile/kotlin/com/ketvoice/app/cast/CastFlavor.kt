package com.ketvoice.app.cast

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

/**
 * Google Cast on phones (mobile and Lite). Everything runs on the main thread, as the Cast framework asks.
 * Without Play services nothing here starts and the app plays on the phone as before.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object CastFlavor {
    private var context: CastContext? = null
    private var app: Context? = null
    private var host: CastHost? = null
    private var cast: CastPlayer? = null
    private var bridge: CastBridgePlayer? = null
    private var server: LocalCastServer? = null
    private var router: MediaRouter? = null
    private val main = Handler(Looper.getMainLooper())
    /** The listener chose to stop casting: the phone carries on playing. */
    private var endedByListener = false
    private var lastPositionMs = 0L
    private var lastPlaying = false

    fun available(appContext: Context): Boolean = castContext(appContext) != null

    private fun castContext(appContext: Context): CastContext? {
        context?.let { return it }
        val ctx = appContext.applicationContext
        val ok = runCatching { GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS }.getOrDefault(false)
        if (!ok) return null
        val cc = runCatching { CastContext.getSharedInstance(ctx) }.getOrNull() ?: return null
        app = ctx
        context = cc
        cc.sessionManager.addSessionManagerListener(sessions, CastSession::class.java)
        cc.sessionManager.currentCastSession?.let { CastState.connected.value = it.castDevice?.friendlyName }
        CastState.supported.value = true
        return cc
    }

    // ---------------------------------------------------------------- devices (the output sheet)

    private val routes = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
    }

    fun scan(appContext: Context, on: Boolean) {
        val cc = castContext(appContext) ?: return
        val selector = cc.mergedSelector ?: return
        val r = router ?: MediaRouter.getInstance(appContext.applicationContext).also { router = it }
        if (on) r.addCallback(selector, routes, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN) else r.removeCallback(routes)
        publishRoutes()
    }

    private fun publishRoutes() {
        val r = router ?: return
        val selector = context?.mergedSelector ?: return
        CastState.devices.value = r.routes.filter { !it.isDefault && !it.isBluetooth && it.isEnabled && it.matchesSelector(selector) }
            .map { CastState.Device(it.id, it.name, it.description) }
    }

    fun select(id: String) {
        val route = router?.routes?.firstOrNull { it.id == id } ?: return
        CastState.connecting.value = id
        route.select()
    }

    /** Stop casting; the title goes on on the phone where the device was. */
    fun disconnect() {
        endedByListener = true
        context?.sessionManager?.endCurrentSession(true)
    }

    private val sessions = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStarted(session: CastSession, sessionId: String) = connectedTo(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            CastState.connecting.value = null
            CastState.say("Connexion à l'appareil Cast impossible")
        }
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) {
            CastState.connecting.value = null
            CastState.connected.value = null
            if (error != 0 && !endedByListener) CastState.say("Connexion à l'appareil Cast perdue")
        }
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = connectedTo(session)
        override fun onSessionResumeFailed(session: CastSession, error: Int) { CastState.connected.value = null }
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    private fun connectedTo(session: CastSession) {
        CastState.connecting.value = null
        CastState.connected.value = session.castDevice?.friendlyName ?: "Cast"
    }

    // ---------------------------------------------------------------- playback (PlaybackService)

    fun attach(service: Context, playbackHost: CastHost) {
        val cc = castContext(service) ?: return
        host = playbackHost
        val player = CastPlayer(cc, CastItemConverter(::publishLocal))
        cast = player
        bridge = CastBridgePlayer(player, ::idleReason, ::accept)
        player.setSessionAvailabilityListener(object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() = start()
            override fun onCastSessionUnavailable() = end()
        })
        if (player.isCastSessionAvailable) start()
    }

    fun detach() {
        if (CastState.active) end()
        cast?.setSessionAvailabilityListener(null)
        runCatching { cast?.release() }
        cast = null; bridge = null; host = null
    }

    private fun start() {
        val h = host ?: return
        val b = bridge ?: return
        if (CastState.remote === b) return
        val local = h.local
        val item = local.currentMediaItem
        val position = local.currentPosition.coerceAtLeast(0L)
        val playing = local.playWhenReady
        local.playWhenReady = false
        endedByListener = false
        CastState.remote = b
        h.refreshSession()
        if (item != null) {
            b.setMediaItem(item, position)
            b.prepare()
            b.playWhenReady = playing
        }
        track()
    }

    /** Back to the phone: the same title at the receiver's last position, playing if the listener stopped casting. */
    private fun end() {
        val h = host
        val b = bridge
        if (h == null || b == null || CastState.remote == null) return
        main.removeCallbacks(tick)
        CastState.remote = null
        h.refreshSession()
        b.lastItem?.let { item ->
            val local = h.local
            local.setMediaItem(item, lastPositionMs)
            local.prepare()
            local.playWhenReady = endedByListener && lastPlaying
        }
        endedByListener = false
        server?.stop()
        server = null
    }

    private val tick = object : Runnable {
        override fun run() {
            val b = bridge ?: return
            if (CastState.remote !== b) return
            if (b.lastItem != null && b.currentMediaItem != null) {
                lastPositionMs = b.currentPosition.coerceAtLeast(0L)
                lastPlaying = b.playWhenReady
            }
            main.postDelayed(this, 1_000L)
        }
    }

    private fun track() { main.removeCallbacks(tick); main.post(tick) }

    private fun idleReason(): Int? {
        val status = context?.sessionManager?.currentCastSession?.remoteMediaClient?.mediaStatus ?: return null
        return status.idleReason.takeIf { status.playerState == MediaStatus.PLAYER_STATE_IDLE }
    }

    private fun accept(item: MediaItem): Boolean = when (CastItemConverter.plan(item)) {
        is CastSource.Unsupported -> { CastState.say("Ce titre ne peut pas être envoyé à l'appareil Cast"); false }
        is CastSource.Local -> (ensureServer() != null).also { if (!it) CastState.say("Pas de Wi-Fi : les titres téléchargés ne peuvent pas être envoyés") }
        is CastSource.Remote -> true
    }

    // ---------------------------------------------------------------- files from the phone

    private fun ensureServer(): LocalCastServer? {
        val address = LocalCastServer.lanAddress() ?: return null
        server?.let { if (it.boundAddress == address) return it; it.stop() }
        val ctx = app ?: return null
        return runCatching { LocalCastServer(address) { uri -> openLocal(ctx, uri) }.start() }.getOrNull().also { server = it }
    }

    private fun publishLocal(uri: String, contentType: String): String? = ensureServer()?.publish(uri, contentType)

    private fun openLocal(ctx: Context, uri: String): LocalCastServer.Opened? {
        val parsed = android.net.Uri.parse(uri)
        if (parsed.scheme == "file") {
            val file = java.io.File(parsed.path ?: return null)
            return if (file.isFile) LocalCastServer.Opened(file.length(), file.inputStream()) else null
        }
        val fd = ctx.contentResolver.openAssetFileDescriptor(parsed, "r") ?: return null
        return LocalCastServer.Opened(fd.length, fd.createInputStream())
    }
}
