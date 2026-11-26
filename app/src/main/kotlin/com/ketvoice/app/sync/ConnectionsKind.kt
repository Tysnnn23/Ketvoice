package com.ketvoice.app.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Discord as one device of the account sees it: never a token, only whether it is linked and the name shown. */
data class DiscordLink(val device: String, val platform: String, val linked: Boolean, val user: String?, val sharing: Boolean)

/**
 * The connections that follow the account (9.2), in its `connections` document: the « show what I listen to on
 * Discord » choice (`discord.share`, the latest change on any device wins) and, per device (`discord:<id>`), whether
 * Discord is linked there and under which name. Discord's tokens stay where they are (the phone's Social SDK, the PC's
 * local Discord app): another device only shows « connected on … ». Each app plugs its own Discord in
 * ([localLink], [sharing], [setSharing], [sharingChosen]).
 */
internal object ConnectionsKind : SyncKind {
    override val name = "connections"
    const val SHARE = "discord.share"
    const val LINK_PREFIX = "discord:"

    /** This device's Discord link, or null when it has none to tell. */
    @Volatile var localLink: () -> DiscordLink? = { null }
    /** The « show what I listen to » choice on this device. */
    @Volatile var sharing: () -> Boolean = { com.ketvoice.app.data.AppSettings.discordPresence.value }
    @Volatile var setSharing: (Boolean) -> Unit = { on ->
        com.ketvoice.app.data.AppSettings.discordPresence.value = on
        com.ketvoice.app.data.SettingsStore.save()
    }
    /** Whether the listener ever made the choice here: a default never overrides another device's choice. */
    @Volatile var sharingChosen: () -> Boolean = { sharing() }

    private val _others = MutableStateFlow<List<DiscordLink>>(emptyList())
    /** Discord on the account's other devices, for « Connexions ». */
    val others: StateFlow<List<DiscordLink>> = _others

    override fun snapshot(context: Context, previous: SyncDoc): Map<String, String> =
        values(previous.live, DeviceIdentity.id(context), localLink(), sharing(), sharingChosen())

    /** What this device holds: the other devices' links as agreed, its own link, and the choice to send. */
    internal fun values(agreed: Map<String, String>, id: String, link: DiscordLink?, here: Boolean, chosen: Boolean): Map<String, String> {
        val out = agreed.filterKeys { it.startsWith(LINK_PREFIX) && it != LINK_PREFIX + id }.toMutableMap()
        shareValue(agreed[SHARE], here, chosen)?.let { out[SHARE] = it }
        link?.let { out[LINK_PREFIX + id] = Canonical.text(encode(it)) }
        return out
    }

    /** The choice to send: this device's when it changed since the last agreement (or was made here), else the account's. */
    internal fun shareValue(agreed: String?, here: Boolean, chosen: Boolean): String? = when {
        agreed == null -> here.toString().takeIf { chosen }
        agreed != here.toString() -> here.toString()
        else -> agreed
    }

    override suspend fun apply(context: Context, values: Map<String, String>) {
        val id = DeviceIdentity.id(context)
        values[SHARE]?.toBooleanStrictOrNull()?.let { if (it != sharing()) setSharing(it) }
        adopt(values, id)
    }

    fun restore(context: Context, doc: SyncDoc?) { doc?.live?.let { adopt(it, DeviceIdentity.id(context)) } }

    fun forget() { _others.value = emptyList() }

    internal fun adopt(values: Map<String, String>, id: String) {
        _others.value = values.filterKeys { it.startsWith(LINK_PREFIX) && it != LINK_PREFIX + id }.values.mapNotNull(::decode)
            .sortedBy { it.device.lowercase() }
    }

    fun encode(link: DiscordLink): JSONObject = JSONObject().put("device", link.device.take(40)).put("platform", link.platform)
        .put("linked", link.linked).put("user", link.user?.take(64) ?: JSONObject.NULL).put("sharing", link.sharing)

    fun decode(text: String): DiscordLink? = runCatching {
        val o = JSONObject(text)
        DiscordLink(o.getString("device").filterNot(Char::isISOControl).take(40).ifBlank { return null },
            o.optString("platform").takeIf { it == "android" || it == "windows" } ?: "android", o.optBoolean("linked"),
            o.optString("user").takeIf { !o.isNull("user") && it.isNotBlank() }?.filterNot(Char::isISOControl)?.take(64),
            o.optBoolean("sharing"))
    }.getOrNull()

    /** « Discord connected on Pixel 9 (name) », or null when no other device has it linked. */
    fun elsewhere(links: List<DiscordLink>): DiscordLink? = links.firstOrNull { it.linked }
}
