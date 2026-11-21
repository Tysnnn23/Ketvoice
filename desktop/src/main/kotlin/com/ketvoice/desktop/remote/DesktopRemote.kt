package com.ketvoice.desktop.remote

import android.content.Context
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.player.Player
import com.ketvoice.app.remote.LocalPlayback
import com.ketvoice.app.remote.RemoteControl
import com.ketvoice.app.remote.RemoteHandoff
import com.ketvoice.app.remote.RemoteKeyStore
import com.ketvoice.app.remote.RemoteOp
import com.ketvoice.app.remote.RemotePort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.swing.SwingUtilities

/**
 * Phone control on Windows: while the listener is signed in, this PC is in the account's room for as long as
 * Ketvoice runs (it lives in the tray), so the phone can see and drive it, and the bar's devices button drives
 * the phone. Everything runs on the window's thread, as the UI's own calls do.
 */
object DesktopRemote {
    /** The PC's name as Windows shows it (« DESKTOP-4F2K… »), else the host name. */
    fun computerName(): String = (System.getenv("COMPUTERNAME") ?: runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull())
        ?.trim()?.take(24)?.takeIf { it.isNotBlank() } ?: "PC"

    val port = object : RemotePort {
        override fun playback(): LocalPlayback {
            val s = Player.state.value
            return LocalPlayback(s.queue, s.index, s.isPlaying || s.playWhenReady, s.positionMs, s.durationMs, Player.volume.value)
        }

        override fun run(op: RemoteOp) {
            val s = Player.state.value
            when (op) {
                RemoteOp.Toggle -> Player.toggle()
                RemoteOp.Next -> Player.next()
                RemoteOp.Previous -> Player.previous()
                is RemoteOp.Seek -> Player.seekTo(op.ms)
                is RemoteOp.Volume -> Player.setVolume(op.level)
                is RemoteOp.Jump -> Player.jumpTo(op.index)
                is RemoteOp.Remove -> if (op.index in s.queue.indices && op.index != s.index) Player.removeAt(op.index)
            }
        }

        override fun play(handoff: RemoteHandoff) = Player.resumeFrom(handoff.queue, handoff.index, handoff.positionMs, handoff.play)
        override fun pause() = Player.pause()
    }

    fun start(context: Context, scope: CoroutineScope) {
        val me = com.ketvoice.app.sync.DeviceIdentity.id(context)
        val name = computerName()
        val ui: (Runnable) -> Unit = { r -> SwingUtilities.invokeLater(r) }
        scope.launch {
            AccountSession.state.map { it.userId }.distinctUntilChanged().collect { uid -> RemoteKeyStore.load(context, uid) }
        }
        scope.launch {
            combine(AccountSession.state.map { it.userId }, RemoteKeyStore.secret) { uid, secret -> secret?.takeIf { uid != null } }
                .distinctUntilChanged().collect { secret ->
                    ui(Runnable { if (secret == null) RemoteControl.stop() else RemoteControl.start(secret, me, name, "pc", port, ui) })
                }
        }
        scope.launch { Player.state.collect { ui(Runnable { RemoteControl.changed() }) } }
        scope.launch { Player.volume.collect { ui(Runnable { RemoteControl.changed() }) } }
    }
}
