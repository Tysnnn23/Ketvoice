package com.ketvoice.app.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** What kind of place the music comes out of, in the order the picker lists them. */
enum class OutputKind { Phone, Wired, Usb, Bluetooth, HearingAid, Hdmi }

/** One output of this phone as « Choix de la sortie audio » (8.4) lists it. [id] lasts as long as the device stays connected. */
data class AudioOutput(val id: Int, val type: Int, val kind: OutputKind, val name: String, val address: String = "")

/**
 * « Choix de la sortie audio » (8.4): the real outputs of the phone (speaker, wired or USB headphones, Bluetooth,
 * hearing aids, HDMI), and the one Ketvoice's own playback goes to. An app cannot move Android's media route, so
 * the choice re-points only Ketvoice's audio tracks ([androidx.media3.exoplayer.ExoPlayer.setPreferredAudioDevice],
 * applied by [PlaybackService] to every deck); other apps keep the system's route.
 *
 * The choice is not saved: a device's id is valid only while it stays connected, so a choice lasts as long as its
 * device does, and a device that goes away gives the music back to Android's own route.
 */
object AudioOutputs {
    /** A device as Android lists it, reduced to what the picker reads (kept apart for host tests). */
    data class Raw(val id: Int, val type: Int, val name: String, val address: String = "")

    private val _chosen = MutableStateFlow<Int?>(null)
    /** The output the listener picked for Ketvoice, or null for Android's own route. */
    val chosen: StateFlow<Int?> = _chosen

    /**
     * Null for what is not somewhere music is listened to: the earpiece, telephony Bluetooth (SCO, which plays a
     * call, never music: routed there the sound comes out of the speaker), remote submixes, buses.
     */
    fun kindOf(type: Int): OutputKind? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> OutputKind.Phone
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_AUX_LINE -> OutputKind.Wired
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputKind.Usb
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST -> OutputKind.Bluetooth
        AudioDeviceInfo.TYPE_HEARING_AID -> OutputKind.HearingAid
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> OutputKind.Hdmi
        else -> null
    }

    /** Among the entries one device publishes, the one that plays music best: A2DP, then LE Audio, then the rest. */
    private fun rank(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 0
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER -> 1
        else -> 2
    }

    /** The outputs to offer, one per device, phone first: what is not music is left out. */
    fun list(raw: List<Raw>): List<AudioOutput> = raw
        .mapNotNull { r -> kindOf(r.type)?.let { r to it } }
        .groupBy { (r, kind) -> if (kind == OutputKind.Phone) "phone" else "$kind|${r.address}|${r.name}" }
        .values.map { same -> same.minWith(compareBy({ rank(it.first.type) }, { it.first.id })) }
        .map { (r, kind) -> AudioOutput(r.id, r.type, kind, r.name.trim(), r.address) }
        .sortedWith(compareBy({ it.kind.ordinal }, { it.name.lowercase() }, { it.id }))

    /**
     * Where Ketvoice's music goes: the chosen output while it is connected, otherwise the one Android picks for
     * media (the last connected headphones win over the speaker).
     */
    fun active(outputs: List<AudioOutput>, chosenId: Int?): AudioOutput? =
        chosenId?.let { id -> outputs.firstOrNull { it.id == id } }
            ?: outputs.firstOrNull { it.kind == OutputKind.Bluetooth || it.kind == OutputKind.HearingAid }
            ?: outputs.firstOrNull { it.kind == OutputKind.Wired }
            ?: outputs.firstOrNull { it.kind == OutputKind.Usb }
            ?: outputs.firstOrNull { it.kind == OutputKind.Hdmi }
            ?: outputs.firstOrNull { it.kind == OutputKind.Phone }

    /** The choice once the outputs changed: a choice whose device went away is forgotten. */
    fun reconcile(outputs: List<AudioOutput>, chosenId: Int?): Int? = chosenId?.takeIf { id -> outputs.any { it.id == id } }

    /** The listener picks [output] (null: Android's own route again). */
    fun choose(output: AudioOutput?) { _chosen.value = output?.id }

    /** Forgets a choice whose device is gone; called on every device change. */
    fun refresh(outputs: List<AudioOutput>) { _chosen.value = reconcile(outputs, _chosen.value) }

    // ------------------------------------------------------------------ Android

    private fun manager(context: Context): AudioManager? = context.applicationContext.getSystemService(AudioManager::class.java)

    private fun raw(info: AudioDeviceInfo) = Raw(info.id, info.type, info.productName?.toString().orEmpty(),
        runCatching { info.address }.getOrNull().orEmpty())

    /** The outputs connected now. */
    fun read(context: Context): List<AudioOutput> = runCatching {
        list(manager(context)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty().map(::raw))
    }.getOrDefault(emptyList())

    /** The device behind a chosen id, while it is connected. */
    fun device(context: Context, id: Int?): AudioDeviceInfo? {
        if (id == null) return null
        return runCatching { manager(context)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.firstOrNull { it.id == id } }.getOrNull()
    }

    /**
     * The outputs, kept current: read at once, again on every connection or disconnection and a moment after it,
     * because a Bluetooth headset is announced before its media profile can play and stays listed a moment after.
     */
    fun watch(context: Context): Flow<List<AudioOutput>> = callbackFlow {
        val handler = Handler(Looper.getMainLooper())
        fun emit() { val now = read(context); refresh(now); trySend(now) }
        fun soon() { emit(); SETTLE_MS.forEach { handler.postDelayed(::emit, it) } }
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = soon()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = soon()
        }
        val audio = manager(context)
        emit()
        audio?.registerAudioDeviceCallback(callback, handler)
        awaitClose { audio?.unregisterAudioDeviceCallback(callback); handler.removeCallbacksAndMessages(null) }
    }.distinctUntilChanged()

    private val SETTLE_MS = longArrayOf(400L, 1_500L)
}
