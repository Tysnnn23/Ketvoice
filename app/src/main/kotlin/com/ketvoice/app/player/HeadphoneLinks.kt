package com.ketvoice.app.player

import com.ketvoice.app.data.AppSettings
import org.json.JSONObject
import java.security.MessageDigest

/** A link as stored (9.0): the profile and the name the output had, to show it while it is not connected. */
data class HeadphoneLink(val profileId: String, val name: String)

/**
 * The stored `headphoneLinks` setting: output key → [HeadphoneLink]. Keys are digests (`bt:`, `btn:`, `usb:` and 16 hex
 * digits) or `wired`, so a backup or the account's sync never carries an address or a device name. Pure Kotlin, shared
 * with the Windows build, which keys its outputs in the same forms so that each side keeps the other's links.
 */
object HeadphoneLinks {
    private val keyRe = Regex("(?:bt|btn|usb):[0-9a-f]{16}|wired")
    private val idRe = Regex("[a-z0-9]{1,24}")

    /** The first 8 bytes of the SHA-256 of [text], in hex. */
    fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .take(8).joinToString("") { "%02x".format(it) }

    fun decode(text: String): Map<String, HeadphoneLink> {
        if (text.isBlank() || text.length > 16_000) return emptyMap()
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, HeadphoneLink>()
        for (key in o.keys()) {
            val v = o.optJSONObject(key) ?: continue
            val id = v.optString("p").takeIf { it.matches(idRe) } ?: continue
            val name = v.optString("n").take(HeadphoneProfiles.MAX_NAME).takeIf { it.none(Char::isISOControl) } ?: continue
            if (key.matches(keyRe) && out.size < 64) out[key] = HeadphoneLink(id, name)
        }
        return out
    }

    fun encode(links: Map<String, HeadphoneLink>): String = JSONObject().apply {
        links.forEach { (k, v) -> put(k, JSONObject().put("p", v.profileId).put("n", v.name)) }
    }.toString()

    /** True when [text] is a stored set of links read back whole (for backups and sync). */
    fun valid(text: String): Boolean = text.isEmpty() ||
        runCatching { JSONObject(text).length() == decode(text).size }.getOrDefault(false)

    /** The profile [key] is linked to, among [profiles]; null when the key is unknown or unlinked. */
    fun profileFor(key: String?, profiles: List<HeadphoneProfile>, links: Map<String, HeadphoneLink>): HeadphoneProfile? =
        key?.let { links[it] }?.let { link -> profiles.firstOrNull { it.id == link.profileId } }
}

/**
 * The listener's corrections and links as the settings keep them (`headphoneProfiles`, `headphoneLinks`), decoded once
 * per change of the stored text. Shared by Android's [HeadphoneEq] and the Windows equalizer page.
 */
object HeadphoneStore {
    private var profilesText: String? = null
    private var profilesValue: List<HeadphoneProfile> = emptyList()
    private var linksText: String? = null
    private var linksValue: Map<String, HeadphoneLink> = emptyMap()

    @Synchronized fun profiles(): List<HeadphoneProfile> {
        val text = AppSettings.headphoneProfiles.value
        if (text != profilesText) { profilesValue = HeadphoneProfiles.decode(text); profilesText = text }
        return profilesValue
    }

    @Synchronized fun links(): Map<String, HeadphoneLink> {
        val text = AppSettings.headphoneLinks.value
        if (text != linksText) { linksValue = HeadphoneLinks.decode(text); linksText = text }
        return linksValue
    }

    private fun saveProfiles(list: List<HeadphoneProfile>) { AppSettings.headphoneProfiles.value = HeadphoneProfiles.encode(list) }
    private fun saveLinks(map: Map<String, HeadphoneLink>) { AppSettings.headphoneLinks.value = HeadphoneLinks.encode(map) }

    /** Adds [profile] (a profile of the same name is replaced) and returns it. */
    fun add(profile: HeadphoneProfile): HeadphoneProfile {
        val others = profiles().filterNot { it.name.equals(profile.name, ignoreCase = true) }
        require(others.size < HeadphoneProfiles.MAX_PROFILES) { "Trop de profils enregistrés (40 au maximum)" }
        val kept = profiles().firstOrNull { it.name.equals(profile.name, ignoreCase = true) }?.let { profile.copy(id = it.id) } ?: profile
        saveProfiles(others + kept)
        return kept
    }

    /** Removes a profile and every link to it. */
    fun remove(profileId: String) {
        saveProfiles(profiles().filterNot { it.id == profileId })
        saveLinks(links().filterValues { it.profileId != profileId })
    }

    /** Links [profile] to the output known by [key], shown as [name] (null profile: the output is unlinked). */
    fun link(key: String, name: String, profile: HeadphoneProfile?) {
        val shown = name.take(HeadphoneProfiles.MAX_NAME)
        saveLinks(if (profile == null) links() - key else links() + (key to HeadphoneLink(profile.id, shown)))
    }

    fun unlink(key: String) = saveLinks(links() - key)
}
