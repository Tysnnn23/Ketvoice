package com.ketvoice.app.player

import android.content.Context
import com.ketvoice.app.data.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import org.json.JSONObject
import java.security.MessageDigest

/**
 * « Égaliseur par casque » (9.0): a correction linked to an output (a Bluetooth headset by its address, or its name
 * when Android gives none; wired headphones; a USB device by its name) is played by [HeadphoneEqProcessor] whenever
 * that output is the one Ketvoice plays to. Settings: `headphoneEq` (on), `headphoneProfiles`, `headphoneLinks`.
 */
object HeadphoneEq {
    /** A link as stored: the profile and the name the output had, to show it while it is not connected. */
    data class Link(val profileId: String, val name: String)

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

    private fun hash(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }

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

    fun profiles(): List<HeadphoneProfile> = cachedProfiles(AppSettings.headphoneProfiles.value)

    fun links(): Map<String, Link> = cachedLinks(AppSettings.headphoneLinks.value)

    private var profilesText: String? = null
    private var profilesValue: List<HeadphoneProfile> = emptyList()
    @Synchronized private fun cachedProfiles(text: String): List<HeadphoneProfile> {
        if (text != profilesText) { profilesValue = HeadphoneProfiles.decode(text); profilesText = text }
        return profilesValue
    }

    private var linksText: String? = null
    private var linksValue: Map<String, Link> = emptyMap()
    @Synchronized private fun cachedLinks(text: String): Map<String, Link> {
        if (text != linksText) { linksValue = decodeLinks(text); linksText = text }
        return linksValue
    }

    private val keyRe = Regex("(?:bt|btn|usb):[0-9a-f]{16}|wired")

    fun decodeLinks(text: String): Map<String, Link> {
        if (text.isBlank() || text.length > 16_000) return emptyMap()
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, Link>()
        for (key in o.keys()) {
            val v = o.optJSONObject(key) ?: continue
            val id = v.optString("p").takeIf { it.matches(Regex("[a-z0-9]{1,24}")) } ?: continue
            val name = v.optString("n").take(HeadphoneProfiles.MAX_NAME).takeIf { it.none(Char::isISOControl) } ?: continue
            if (key.matches(keyRe) && out.size < 64) out[key] = Link(id, name)
        }
        return out
    }

    fun encodeLinks(links: Map<String, Link>): String = JSONObject().apply {
        links.forEach { (k, v) -> put(k, JSONObject().put("p", v.profileId).put("n", v.name)) }
    }.toString()

    /** True when [text] is a stored set of links read back whole (for backups and sync). */
    fun validLinks(text: String): Boolean = text.isEmpty() ||
        runCatching { JSONObject(text).length() == decodeLinks(text).size }.getOrDefault(false)

    /** The profile heard on [output]: its linked one, while the switch is on and the listener is not comparing. */
    fun resolve(output: AudioOutput?, enabled: Boolean, comparing: Boolean, profiles: List<HeadphoneProfile>,
                links: Map<String, Link>): Status {
        val key = output?.let(::keyOf)
        val profile = key?.let { links[it] }?.let { link -> profiles.firstOrNull { it.id == link.profileId } }
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

    private fun saveProfiles(list: List<HeadphoneProfile>) { AppSettings.headphoneProfiles.value = HeadphoneProfiles.encode(list) }
    private fun saveLinks(map: Map<String, Link>) { AppSettings.headphoneLinks.value = encodeLinks(map) }

    /** Adds [profile] (a profile of the same name is replaced) and returns it. */
    fun add(profile: HeadphoneProfile): HeadphoneProfile {
        val others = profiles().filterNot { it.name.equals(profile.name, ignoreCase = true) }
        require(others.size < HeadphoneProfiles.MAX_PROFILES) { "Trop de profils enregistrés (40 au maximum)" }
        val kept = profiles().firstOrNull { it.name.equals(profile.name, ignoreCase = true) }?.let { profile.copy(id = it.id) } ?: profile
        saveProfiles(others + kept)
        changed()
        return kept
    }

    fun remove(profileId: String) {
        saveProfiles(profiles().filterNot { it.id == profileId })
        saveLinks(links().filterValues { it.profileId != profileId })
        changed()
    }

    /** Links [profile] to [output] (null profile: the output is unlinked). */
    fun link(output: AudioOutput, profile: HeadphoneProfile?) {
        val key = keyOf(output) ?: return
        val name = output.name.ifBlank { if (output.kind == OutputKind.Wired) "Écouteurs filaires" else "Casque" }.take(HeadphoneProfiles.MAX_NAME)
        saveLinks(if (profile == null) links() - key else links() + (key to Link(profile.id, name)))
        changed()
    }

    fun unlink(key: String) { saveLinks(links() - key); changed() }
}
