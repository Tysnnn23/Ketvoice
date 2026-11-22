package com.ketvoice.app.player

import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
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
    private val engine: Player,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    /** The app's queue, shown to cars and other controllers as the session's timeline (8.4); null keeps a single item. */
    private val host: QueueHost? = null,
    /** Whether a pause or a resume may fade now (7.5, [PauseFade.applies]); the host tests say yes. */
    private val fades: () -> Boolean = {
        PauseFade.applies(audible = true, bitPerfect = com.ketvoice.app.data.AppSettings.bitPerfect.value,
            mixing = com.ketvoice.app.automix.Automix.mixing)
    }
) : ForwardingPlayer(engine) {
    // Keyed by equality: the session removes a listener through a new wrapper that is
    // only equal to the one it added, never the same instance.
    private val listeners = HashMap<Player.Listener, Player.Listener>()

    private val queueCommands = if (host == null) QUEUE_COMMANDS else QUEUE_COMMANDS + SHOWN_QUEUE_COMMANDS

    override fun getAvailableCommands(): Player.Commands = withCommands(super.getAvailableCommands())

    override fun isCommandAvailable(command: Int): Boolean = command in queueCommands || super.isCommandAvailable(command)

    private fun withCommands(commands: Player.Commands): Player.Commands = commands.buildUpon().addAll(*queueCommands).build()

    override fun hasNextMediaItem(): Boolean = true
    override fun hasPreviousMediaItem(): Boolean = true

    // ---- 7.5: fade on pause and resume ([PauseFade]).

    private val handler by lazy { android.os.Handler(engine.applicationLooper) }
    /** The fade under way, null when none. */
    private var fade: Fade? = null
    /** The volume asked for (by the app, a skip fade): what a fade starts from and comes back to. */
    private var asked = 1f

    private inner class Fade(val out: Boolean, val from: Float) : Runnable {
        private val steps = PauseFade.steps()
        private var step = 0
        var level = from
        override fun run() {
            if (fade !== this) return
            step++
            level = PauseFade.level(from, if (out) 0f else 1f, step / steps.toFloat())
            engine.volume = asked * level
            if (step < steps) handler.postDelayed(this, PauseFade.STEP_MS) else finish()
        }
        fun finish() {
            if (fade !== this) return
            fade = null
            handler.removeCallbacks(this)
            if (out) engine.playWhenReady = false
            // Paused (silent) or fully back: the volume is the one asked for again.
            engine.volume = asked
        }
    }

    private fun start(out: Boolean, from: Float) {
        fade?.let { handler.removeCallbacks(it) }
        val next = Fade(out, from)
        fade = next
        handler.postDelayed(next, PauseFade.STEP_MS)
    }

    private fun cancelFade() {
        val current = fade ?: return
        fade = null
        handler.removeCallbacks(current)
        engine.volume = asked
    }

    /** The pause is shown at once, while the sound still glides down: the session and the app see it now. */
    private fun announce(playWhenReady: Boolean, isPlaying: Boolean) {
        val events = Player.Events(androidx.media3.common.FlagSet.Builder()
            .addAll(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_IS_PLAYING_CHANGED).build())
        listeners.keys.toList().forEach { listener ->
            listener.onPlayWhenReadyChanged(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            listener.onIsPlayingChanged(isPlaying)
            listener.onEvents(this, events)
        }
    }

    override fun play() = setPlayWhenReady(true)
    override fun pause() = setPlayWhenReady(false)

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        val current = fade
        if (!playWhenReady) {
            if (current?.out == true) return
            if (!engine.isPlaying || !fades()) { cancelFade(); engine.playWhenReady = false; return }
            if (current == null) asked = engine.volume
            start(out = true, from = current?.level ?: 1f)
            announce(playWhenReady = false, isPlaying = false)
            return
        }
        if (current != null && !current.out) return
        if (current?.out == true) {
            // Resumed while it was still fading out: the sound never stopped, it rises again from where it is.
            start(out = false, from = current.level)
            announce(playWhenReady = true, isPlaying = engine.isPlaying)
            return
        }
        if (engine.playWhenReady || engine.playbackState != Player.STATE_READY || !fades()) { engine.playWhenReady = true; return }
        asked = engine.volume
        engine.volume = 0f
        engine.playWhenReady = true
        start(out = false, from = 0f)
    }

    override fun getPlayWhenReady(): Boolean = if (fade?.out == true) false else super.getPlayWhenReady()
    override fun isPlaying(): Boolean = if (fade?.out == true) false else super.isPlaying()
    override fun getVolume(): Float = if (fade != null) asked else super.getVolume()
    override fun setVolume(volume: Float) {
        val current = fade
        if (current == null) super.setVolume(volume) else { asked = volume; engine.volume = volume * current.level }
    }
    override fun stop() { cancelFade(); super.stop() }
    override fun release() { cancelFade(); super.release() }

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
                    listener.onAvailableCommandsChanged(withCommands(values[0] as Player.Commands))
                    null
                }
                host != null && rewrites(listener, method.name, values) -> null
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

    // ---- 8.4: the app's queue as the session's timeline (Android Auto's queue screen, Bluetooth browsing).

    private val shown = QueueWindows()
    /** What [queueChanged] last announced, so an unchanged queue is not sent again. */
    private var announced: Any? = null

    private fun windows(): QueueWindows.View? {
        val queue = host ?: return null
        return shown.view(queue.state(), engine.currentTimeline, engine.currentMediaItemIndex, engine.currentMediaItem, queue::artwork)
    }

    override fun getCurrentTimeline(): Timeline = windows()?.timeline ?: super.getCurrentTimeline()
    override fun getMediaItemCount(): Int = windows()?.timeline?.windowCount ?: super.getMediaItemCount()
    override fun getMediaItemAt(index: Int): MediaItem = windows()?.item(index) ?: super.getMediaItemAt(index)
    override fun getCurrentMediaItemIndex(): Int = windows()?.current ?: super.getCurrentMediaItemIndex()
    @Deprecated("Deprecated in Media3", ReplaceWith("currentMediaItemIndex"))
    override fun getCurrentWindowIndex(): Int = currentMediaItemIndex
    override fun getCurrentPeriodIndex(): Int = windows()?.current ?: super.getCurrentPeriodIndex()
    override fun getNextMediaItemIndex(): Int = windows()?.next() ?: super.getNextMediaItemIndex()
    override fun getPreviousMediaItemIndex(): Int = windows()?.previous() ?: super.getPreviousMediaItemIndex()

    override fun getShuffleModeEnabled(): Boolean = host?.state()?.shuffle ?: super.getShuffleModeEnabled()
    override fun getRepeatMode(): Int = host?.state()?.let(::repeatModeOf) ?: super.getRepeatMode()
    override fun getPlaylistMetadata(): MediaMetadata =
        host?.let { MediaMetadata.Builder().setTitle(it.queueTitle()).build() } ?: super.getPlaylistMetadata()

    /** A car or an assistant turns shuffle on or off: the app's visible shuffle, not the engine's flag. */
    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        val queue = host
        if (queue != null && queue.fromOutside()) queue.setShuffle(shuffleModeEnabled) else super.setShuffleModeEnabled(shuffleModeEnabled)
    }

    /** The same for repeat: the queue, the title or off, as the app's own repeat button does. */
    override fun setRepeatMode(repeatMode: Int) {
        val queue = host
        if (queue != null && queue.fromOutside()) queue.setRepeat(repeatMode) else super.setRepeatMode(repeatMode)
    }

    /** A title chosen in the car's queue: the app's queue jumps to it; the title playing is only sought. */
    override fun seekToDefaultPosition(mediaItemIndex: Int) = seekToItem(mediaItemIndex, C.TIME_UNSET)
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = seekToItem(mediaItemIndex, positionMs)

    private fun seekToItem(index: Int, positionMs: Long) {
        val view = windows()
        if (view == null) {
            if (positionMs == C.TIME_UNSET) super.seekToDefaultPosition(index) else super.seekTo(index, positionMs)
            return
        }
        if (index != view.current) { view.queueIndex(index)?.let { host?.jumpTo(it) }; return }
        if (positionMs == C.TIME_UNSET) engine.seekToDefaultPosition() else engine.seekTo(positionMs)
    }

    /** Called when the app's queue or its modes changed: the session (and the car) hear of it, once. */
    fun queueChanged() {
        val queue = host ?: return
        val state = queue.state()
        val signature = listOf(windows()?.signature, state.shuffle, repeatModeOf(state))
        if (signature == announced) return
        announced = signature
        val timeline = currentTimeline
        val events = Player.Events(androidx.media3.common.FlagSet.Builder().addAll(Player.EVENT_TIMELINE_CHANGED,
            Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED).build())
        listeners.keys.toList().forEach { listener ->
            listener.onTimelineChanged(timeline, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            listener.onShuffleModeEnabledChanged(shuffleModeEnabled)
            listener.onRepeatModeChanged(repeatMode)
            listener.onEvents(this, events)
        }
    }

    /** The engine's own news told in the queue's terms: its timeline, its positions and the app's modes. */
    private fun rewrites(listener: Player.Listener, name: String, values: Array<out Any?>): Boolean {
        when {
            name == "onTimelineChanged" && values.size == 2 -> listener.onTimelineChanged(currentTimeline, values[1] as Int)
            name == "onPositionDiscontinuity" && values.size == 3 -> listener.onPositionDiscontinuity(
                inQueue(values[0] as Player.PositionInfo), inQueue(values[1] as Player.PositionInfo), values[2] as Int)
            name == "onShuffleModeEnabledChanged" && values.size == 1 -> listener.onShuffleModeEnabledChanged(shuffleModeEnabled)
            name == "onRepeatModeChanged" && values.size == 1 -> listener.onRepeatModeChanged(repeatMode)
            else -> return false
        }
        return true
    }

    private fun inQueue(position: Player.PositionInfo): Player.PositionInfo {
        val view = windows() ?: return position
        if (position.mediaItemIndex != engine.currentMediaItemIndex) return position
        val uid = view.uid(view.current)
        return Player.PositionInfo(uid, view.current, position.mediaItem, uid, view.current, position.positionMs,
            position.contentPositionMs, position.adGroupIndex, position.adIndexInAdGroup)
    }

    companion object {
        /** Jumping to a title of the queue: offered once the queue is shown. */
        val SHOWN_QUEUE_COMMANDS = intArrayOf(Player.COMMAND_SEEK_TO_MEDIA_ITEM)

        fun repeatModeOf(state: PlayerUiState): Int = when {
            state.repeatOne -> Player.REPEAT_MODE_ONE
            state.repeatAll -> Player.REPEAT_MODE_ALL
            else -> Player.REPEAT_MODE_OFF
        }

        val QUEUE_COMMANDS = intArrayOf(Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)

        fun withQueueCommands(commands: Player.Commands): Player.Commands =
            commands.buildUpon().addAll(*QUEUE_COMMANDS).build()
    }
}
