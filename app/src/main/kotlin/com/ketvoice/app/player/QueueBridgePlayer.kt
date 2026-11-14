package com.ketvoice.app.player

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * The audio engine holds a single item; the queue lives in the app. This wrapper is
 * what the media session sees: it announces "next" and "previous" and hands them to
 * the app queue, so the notification, the lock screen, headsets, watches and cars
 * can change track.
 */
@UnstableApi
internal class QueueBridgePlayer(
    player: Player,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit
) : ForwardingPlayer(player) {
    // Keyed by equality: the session removes a listener through a new wrapper that is
    // only equal to the one it added, never the same instance.
    private val listeners = HashMap<Player.Listener, Player.Listener>()

    override fun getAvailableCommands(): Player.Commands = withQueueCommands(super.getAvailableCommands())

    override fun isCommandAvailable(command: Int): Boolean = command in QUEUE_COMMANDS || super.isCommandAvailable(command)

    override fun hasNextMediaItem(): Boolean = true
    override fun hasPreviousMediaItem(): Boolean = true

    override fun seekToNext() = onNext()
    override fun seekToNextMediaItem() = onNext()
    override fun seekToPrevious() = onPrevious()
    override fun seekToPreviousMediaItem() = onPrevious()

    /**
     * The engine reports its own commands whenever they change; without this the
     * session would forward them and the queue buttons would disappear again.
     */
    override fun addListener(listener: Player.Listener) {
        val bridged = listeners.getOrPut(listener) { bridge(listener) }
        super.addListener(bridged)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)?.let { super.removeListener(it) }
    }

    private fun bridge(listener: Player.Listener): Player.Listener {
        val handler = InvocationHandler { proxy, method, args ->
            val values = args ?: emptyArray()
            when {
                method.declaringClass == Any::class.java -> when (method.name) {
                    "equals" -> proxy === values.getOrNull(0)
                    "hashCode" -> System.identityHashCode(proxy)
                    else -> "QueueBridgeListener($listener)"
                }
                // onAvailableCommandsChanged is the only callback that carries the command set.
                values.size == 1 && values[0] is Player.Commands -> {
                    listener.onAvailableCommandsChanged(withQueueCommands(values[0] as Player.Commands))
                    null
                }
                else -> try {
                    method.invoke(listener, *values)
                } catch (error: InvocationTargetException) {
                    throw error.targetException ?: error
                }
            }
        }
        return Proxy.newProxyInstance(Player.Listener::class.java.classLoader, arrayOf(Player.Listener::class.java), handler)
            as Player.Listener
    }

    companion object {
        val QUEUE_COMMANDS = intArrayOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)

        fun withQueueCommands(commands: Player.Commands): Player.Commands =
            commands.buildUpon().addAll(*QUEUE_COMMANDS).build()
    }
}
