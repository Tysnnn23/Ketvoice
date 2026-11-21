package com.ketvoice.app.remote

import android.content.Context
import com.ketvoice.app.account.AccountSession
import com.ketvoice.app.player.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Phone control on Android: this phone joins the account's room while the listener is signed in and the app is
 * in front or plays, so the PC (or another phone) sees it and drives it; commands land on [Player] as the
 * listener's own touches would (Ketogether's gate included).
 */
object PhoneRemote {
    private val front = MutableStateFlow(false)
    private var started = false

    private object Port : RemotePort {
        override fun playback(): LocalPlayback {
            val s = Player.state.value
            return LocalPlayback(s.queue, s.index, s.playWhenReady, Player.currentPositionMs(), s.durationMs, Player.volumeValue())
        }

        override fun run(op: RemoteOp) {
            val s = Player.state.value
            when (op) {
                RemoteOp.Toggle -> Player.toggle()
                RemoteOp.Next -> Player.next()
                RemoteOp.Previous -> Player.previous()
                is RemoteOp.Seek -> Player.seekTo(op.ms)
                is RemoteOp.Volume -> Player.setVolume(op.level)
                is RemoteOp.Jump -> if (op.index in s.queue.indices) Player.jumpTo(op.index)
                is RemoteOp.Remove -> if (op.index in s.queue.indices && op.index != s.index) Player.removeAt(op.index)
            }
        }

        override fun play(handoff: RemoteHandoff) {
            Player.playTrack(handoff.queue[handoff.index], handoff.queue, handoff.index, handoff.positionMs, handoff.play, keepOrder = true)
        }

        override fun pause() = Player.pause()
    }

    fun inFront(on: Boolean) { front.value = on }

    /** Once, from the activity: follows the account, the secret, the app's place and the player. */
    fun watch(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        val kind = when {
            com.ketvoice.app.BuildConfig.IS_QUEST -> "quest"
            app.resources.configuration.smallestScreenWidthDp >= 600 -> "tablet"
            else -> "phone"
        }
        val me = com.ketvoice.app.sync.DeviceIdentity.id(app)
        val name = com.ketvoice.app.sync.DeviceIdentity.name()
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            AccountSession.state.map { it.userId }.distinctUntilChanged().collect { uid -> RemoteKeyStore.load(app, uid) }
        }
        scope.launch {
            combine(AccountSession.state.map { it.userId }, RemoteKeyStore.secret, front,
                Player.state.map { it.playWhenReady }.distinctUntilChanged()) { uid, secret, inFront, playing ->
                secret?.takeIf { uid != null && (inFront || playing) }
            }.distinctUntilChanged().collect { secret ->
                if (secret == null) RemoteControl.stop()
                else RemoteControl.start(secret, me, name, kind, Port, { r -> main.post(r) })
            }
        }
        scope.launch { Player.state.collect { RemoteControl.changed() } }
        scope.launch { Player.volume.collect { RemoteControl.changed() } }
    }
}
