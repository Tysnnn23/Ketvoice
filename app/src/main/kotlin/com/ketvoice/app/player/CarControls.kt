package com.ketvoice.app.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.ListeningExecutorService
import com.google.common.util.concurrent.MoreExecutors
import com.ketvoice.app.R
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.data.Track
import java.util.concurrent.Executors

/**
 * The buttons of the car's Now Playing screen (8.4), beside previous / play / next: like, shuffle, repeat,
 * radio from the title playing and Ketmix. They also reach the phone's media controls (lock screen,
 * notification), which share the same session. Every one goes through [Player], like the phone's own buttons;
 * their look follows the app's state live ([look]).
 */
internal object CarControls {
    const val LIKE = "ketvoice.car.like"
    const val SHUFFLE = "ketvoice.car.shuffle"
    const val REPEAT = "ketvoice.car.repeat"
    const val RADIO = "ketvoice.car.radio"
    const val KETMIX = "ketvoice.car.ketmix"
    val ACTIONS = listOf(LIKE, SHUFFLE, REPEAT, RADIO, KETMIX)

    /** What the buttons show: one value per state the listener can see on the car. */
    data class Look(val hasTitle: Boolean, val liked: Boolean, val shuffle: Boolean, val repeat: Int, val ketmix: Boolean)

    @androidx.annotation.OptIn(UnstableApi::class)
    fun look(state: PlayerUiState, liked: Set<String>, ketmix: Boolean): Look {
        val current = state.current
        return Look(current != null, current != null && current.id in liked, state.shuffle, QueueBridgePlayer.repeatModeOf(state), ketmix)
    }

    /** [commands] plus the car's own. */
    fun withCommands(commands: SessionCommands): SessionCommands =
        commands.buildUpon().apply { ACTIONS.forEach { add(SessionCommand(it, Bundle.EMPTY)) } }.build()

    /** In the car's order of importance: the first ones sit beside the transport, the others in its overflow. */
    @UnstableApi
    fun buttons(look: Look, text: (String) -> String): List<CommandButton> = listOf(
        button(LIKE, if (look.liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED,
            text(if (look.liked) "Retirer des favoris" else "Ajouter aux favoris"), look.hasTitle),
        button(SHUFFLE, if (look.shuffle) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF,
            text(if (look.shuffle) "Aléatoire activé" else "Aléatoire désactivé"), true),
        button(REPEAT, repeatIcon(look.repeat), text(repeatName(look.repeat)), true),
        button(RADIO, CommandButton.ICON_RADIO, text("Lancer la radio"), look.hasTitle),
        CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(if (look.ketmix) R.drawable.ic_widget_ketmix else R.drawable.ic_car_ketmix_off)
            .setDisplayName(text(if (look.ketmix) "Ketmix actif" else "Ketmix désactivé"))
            .setSessionCommand(SessionCommand(KETMIX, Bundle.EMPTY))
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build())

    @UnstableApi
    private fun button(action: String, icon: Int, name: String, enabled: Boolean): CommandButton = CommandButton.Builder(icon)
        .setDisplayName(name)
        .setSessionCommand(SessionCommand(action, Bundle.EMPTY))
        .setEnabled(enabled)
        .setSlots(CommandButton.SLOT_OVERFLOW)
        .build()

    @UnstableApi
    private fun repeatIcon(mode: Int) = when (mode) {
        androidx.media3.common.Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
        androidx.media3.common.Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
        else -> CommandButton.ICON_REPEAT_OFF
    }

    private fun repeatName(mode: Int) = when (mode) {
        androidx.media3.common.Player.REPEAT_MODE_ONE -> "Répéter le titre"
        androidx.media3.common.Player.REPEAT_MODE_ALL -> "Répéter la file"
        else -> "Répétition désactivée"
    }

    /** Carries out a button press; a message for the car when it could not be done. Main thread. */
    fun perform(context: Context, action: String, connect: () -> Unit = { Player.connect(context) }): String? {
        connect()
        val state = Player.state.value
        when (action) {
            LIKE -> state.current?.let { toggleLike(it) } ?: return AppText.t("Aucun titre en lecture")
            SHUFFLE -> Player.setShuffle(!state.shuffle)
            REPEAT -> Player.cycleRepeat()
            RADIO -> {
                val current = state.current ?: return AppText.t("Aucun titre en lecture")
                if (!hasInternet(context)) return AppText.t("Hors ligne : la radio a besoin d'une connexion")
                Player.startRadio(current)
            }
            KETMIX -> com.ketvoice.app.widget.KetmixSwitch.flip(context)
        }
        return null
    }

    private fun toggleLike(track: Track) {
        val liked = AppSettings.favoriteIds.value
        AppSettings.favoriteIds.value = if (track.id in liked) liked - track.id else liked + track.id
        SettingsStore.save()
        // Outside a screen of the app nothing else sends the state's change notifications.
        runCatching { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
    }
}

/** [Player]'s queue as the session shows it to cars ([QueueBridgePlayer]). */
internal class PlayerQueueHost(private val context: Context, private val outside: () -> Boolean) : QueueHost {
    override fun state(): PlayerUiState = Player.state.value
    override fun artwork(track: Track): Uri? = AutoArtwork.uri(context, playbackCover(track))
    override fun queueTitle(): String = AppText.t("File d'attente")
    override fun jumpTo(index: Int) { Player.connect(context); Player.jumpTo(index) }
    override fun setShuffle(on: Boolean) { Player.connect(context); Player.setShuffle(on) }
    override fun setRepeat(mode: Int) { Player.connect(context); Player.setRepeatMode(mode) }
    override fun fromOutside(): Boolean = outside()

    companion object {
        /** A request from outside the app: any package but Ketvoice's, or the system's controls (the notification's controller). */
        @androidx.annotation.OptIn(UnstableApi::class)
        fun outside(session: MediaSession?, packageName: String): Boolean {
            val s = session ?: return false
            val controller = s.controllerForCurrentRequest ?: return false
            return controller.packageName != packageName || s.isMediaNotificationController(controller)
        }
    }
}

/**
 * Covers of the session (car, lock screen, notification) read through [AutoArtwork]'s disk cache: a cover the car
 * was shown once is there again offline, and each is scaled for a car screen. Anything else goes to [fallback].
 */
@UnstableApi
internal class CarBitmapLoader(private val context: Context, private val fallback: BitmapLoader) : BitmapLoader {
    private val executor: ListeningExecutorService by lazy {
        MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ketvoice-car-covers").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        })
    }

    override fun supportsMimeType(mimeType: String): Boolean = fallback.supportsMimeType(mimeType)
    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = fallback.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val source = uri.toString()
        if (!AutoArtwork.allowed(source) || uri.scheme == "content") return fallback.loadBitmap(uri)
        val cached = executor.submit<Bitmap?> {
            runCatching { BitmapFactory.decodeFile(AutoArtwork.file(context, source).path) }.getOrNull()
        }
        return com.google.common.util.concurrent.Futures.transformAsync(cached, { bitmap ->
            if (bitmap != null) com.google.common.util.concurrent.Futures.immediateFuture(bitmap) else fallback.loadBitmap(uri)
        }, MoreExecutors.directExecutor())
    }
}
