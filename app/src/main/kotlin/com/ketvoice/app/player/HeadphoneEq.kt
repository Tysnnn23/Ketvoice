package com.ketvoice.app.player

import android.content.Context
import com.ketvoice.app.data.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine

/**
 * « Égaliseur par casque » (9.0): a correction linked to an output (a Bluetooth headset by its address, or its name
 * when Android gives none; wired headphones; a USB device by its name) is played by [HeadphoneEqProcessor] whenever
 * that output is the one Ketvoice plays to. Settings: `headphoneEq` (on), `headphoneProfiles`, `headphoneLinks`.
 */
object HeadphoneEq {
    /** What the equalizer screen shows: the output playing, the profile linked to it, and whether it is heard. */
    data class Status(val output: AudioOutput? = null, val key: String? = null, val profile: HeadphoneProfile? = null,
                      val applied: Boolean = false)

    /** « Écouter sans correction »: the correction is held off while the listener compares (never saved). */
    val compare = MutableStateFlow(false)
    private val revision = MutableStateFlow(0)
    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status

    /** What the audio chain plays now (null: nothing), read by every deck's processor. */
    @Volatile var playing: HeadphoneProfile? = null
        private set

    /** The listener changed a profile, a link or the switch: the chain follows. */
    fun changed() { revision.value++ }

    private fun hash(text: String): String = HeadphoneLinks.digest(text)

    /**
     * The key an output is linked by, or null for what is not headphones (the phone's speaker, HDMI, hearing aids).
     * Addresses and names are kept only as digests, so a backup or the account's sync never carries them.
     */
    fun keyOf(output: AudioOutput): String? = when (output.kind) {
        OutputKind.Bluetooth -> if (output.address.isNotBlank()) "bt:" + hash(output.address.uppercase())
            else output.name.takeIf { it.isNotBlank() }?.let { "btn:" + hash(it) }
        OutputKind.Wired -> "wired"
        OutputKind.Usb -> "usb:" + hash(output.name.ifBlank { "usb" })
        else -> null
    }

    fun profiles(): List<HeadphoneProfile> = HeadphoneStore.profiles()

    fun links(): Map<String, HeadphoneLink> = HeadphoneStore.links()

    fun decodeLinks(text: String): Map<String, HeadphoneLink> = HeadphoneLinks.decode(text)

    fun encodeLinks(links: Map<String, HeadphoneLink>): String = HeadphoneLinks.encode(links)

    /** True when [text] is a stored set of links read back whole (for backups and sync). */
    fun validLinks(text: String): Boolean = HeadphoneLinks.valid(text)

    /** The profile heard on [output]: its linked one, while the switch is on and the listener is not comparing. */
    fun resolve(output: AudioOutput?, enabled: Boolean, comparing: Boolean, profiles: List<HeadphoneProfile>,
                links: Map<String, HeadphoneLink>): Status {
        val key = output?.let(::keyOf)
        val profile = HeadphoneLinks.profileFor(key, profiles, links)
        return Status(output, key, profile, applied = profile != null && enabled && !comparing && !AppSettings.bitPerfect.value)
    }

    /** Takes up the outputs connected now and the output Ketvoice plays to. */
    fun follow(outputs: List<AudioOutput>, chosenId: Int?) {
        val status = resolve(AudioOutputs.active(outputs, chosenId), AppSettings.headphoneEq.value, compare.value, profiles(), links())
        _status.value = status
        playing = status.profile?.takeIf { status.applied }
    }

    /** Keeps the chain on the right correction while [context]'s outputs, the choice and the settings change. */
    fun watch(context: Context): Flow<Status> =
        combine(AudioOutputs.watch(context), AudioOutputs.chosen, revision, compare) { outputs, chosen, _, _ ->
            follow(outputs, chosen); _status.value
        }

    // ------------------------------------------------------------------ the listener's changes

    /** Adds [profile] (a profile of the same name is replaced) and returns it. */
    fun add(profile: HeadphoneProfile): HeadphoneProfile = HeadphoneStore.add(profile).also { changed() }

    fun remove(profileId: String) { HeadphoneStore.remove(profileId); changed() }

    /** Links [profile] to [output] (null profile: the output is unlinked). */
    fun link(output: AudioOutput, profile: HeadphoneProfile?) {
        val key = keyOf(output) ?: return
        val name = output.name.ifBlank { if (output.kind == OutputKind.Wired) "Écouteurs filaires" else "Casque" }
        HeadphoneStore.link(key, name, profile)
        changed()
    }

    fun unlink(key: String) { HeadphoneStore.unlink(key); changed() }
}
