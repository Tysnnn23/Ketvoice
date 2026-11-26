package com.ketvoice.app.sync

import android.content.Context
import com.ketvoice.app.data.DeviceListening
import com.ketvoice.app.data.Heard
import com.ketvoice.app.data.ListeningHistory
import com.ketvoice.app.data.ListeningMonths
import com.ketvoice.app.data.ListeningShare
import com.ketvoice.app.data.ListeningWeek
import com.ketvoice.app.data.Track
import com.ketvoice.app.data.Wrapped
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recently played and the listening statistics (9.2), in the account's `listening` document. Each device writes
 * only its own counters, under `dev:<its id>:<part>`, and keeps every other device's keys as they are: what a
 * page shows is this device's listening plus the sum of the others' ([ListeningHistory.adoptOthers]), so a play
 * is counted once, by the device that heard it. Listening changes all the time; this device's values are taken
 * again at most every [REFRESH_MS] (or when the app goes to the background, quits or syncs by hand).
 */
internal object ListeningKind : SyncKind {
    override val name = "listening"
    const val PREFIX = "dev:"
    const val REFRESH_MS = 5 * 60_000L
    /** Months older than this stay on the device that heard them. */
    const val MONTHS_KEPT = 24
    private const val MAX_COUNTS = 2_000
    private const val MAX_TRACKS = 400
    @Volatile private var cached: Map<String, String>? = null
    @Volatile private var cachedAt = 0L
    /** The clock the cache reads; tests move it. */
    internal var clock: () -> Long = System::currentTimeMillis

    /** The next snapshot takes this device's listening again. */
    fun invalidate() { cached = null }

    fun prefix(deviceId: String) = "$PREFIX$deviceId:"

    override fun snapshot(context: Context, previous: SyncDoc): Map<String, String> {
        val id = DeviceIdentity.id(context)
        return values(previous.live, id, ownValues(id))
    }

    /** The other devices' keys exactly as agreed (a key left out would travel as a removal), and this device's own. */
    internal fun values(agreed: Map<String, String>, id: String, own: Map<String, String>): Map<String, String> =
        agreed.filterKeys { it.startsWith(PREFIX) && !it.startsWith(prefix(id)) } + own

    private fun ownValues(id: String): Map<String, String> {
        val now = clock()
        cached?.takeIf { now - cachedAt in 0 until REFRESH_MS }?.let { return it }
        return encode(id, ListeningHistory.own(), java.time.LocalDate.now()).also { cached = it; cachedAt = now }
    }

    override suspend fun apply(context: Context, values: Map<String, String>) {
        val id = DeviceIdentity.id(context)
        ListeningHistory.adoptOthers(decode(values, except = id).values.toList())
        // The account held an older copy of this device's own counters (a first sync after signing in again):
        // the next round sends the ones this device has now.
        val mine = values.filterKeys { it.startsWith(prefix(id)) }
        if (mine.isNotEmpty() && mine != ownValues(id)) { invalidate(); resend = true }
    }

    @Volatile private var resend = false
    /** True once after a round that brought back an older copy of this device's counters. */
    fun takeResend(): Boolean = resend.also { resend = false }

    /** At sign-in: the other devices' listening as last agreed, before the first round answers. */
    fun restore(context: Context, doc: SyncDoc?) {
        val values = doc?.live ?: return
        ListeningHistory.adoptOthers(decode(values, except = DeviceIdentity.id(context)).values.toList())
    }

    fun forget() { invalidate(); ListeningHistory.adoptOthers(emptyList()) }

    // ---- Wire format ----------------------------------------------------------------------------------------

    fun encode(id: String, own: DeviceListening, today: java.time.LocalDate): Map<String, String> {
        val p = prefix(id)
        val oldest = ListeningMonths.key(today.minusMonths(MONTHS_KEPT.toLong() - 1))
        val out = linkedMapOf(
            p + "recent" to Canonical.text(JSONArray(own.recent.map { JSONObject().put("t", ListeningMonths.trackJson(it.track)).put("at", it.at) })),
            p + "known" to Canonical.text(JSONArray(own.known.take(MAX_TRACKS).map(ListeningMonths::trackJson))),
            p + "counts" to Canonical.text(JSONObject(own.counts.filterKeys(::validId))),
            p + "total" to own.listenedMs.coerceAtLeast(0L).toString(),
            p + "days" to Canonical.text(JSONObject(ListeningWeek.encode(own.days))),
            p + "years" to Canonical.text(JSONObject(ListeningWeek.encode(own.years))),
        )
        own.months.filterKeys { ListeningMonths.isKey(it) && it >= oldest }.forEach { (month, stat) ->
            val clean = stat.copy(tracks = stat.tracks.mapNotNull { (k, t) -> ListeningShare.shareable(t)?.let { k to it } }.toMap())
            out[p + "m:" + month] = Canonical.text(JSONObject(ListeningMonths.encode(clean)))
        }
        return out
    }

    /** Every device's listening found in [values], by device id, except [except] (this device). */
    fun decode(values: Map<String, String>, except: String): Map<String, DeviceListening> {
        val parts = LinkedHashMap<String, MutableMap<String, String>>()
        values.forEach { (key, text) ->
            if (!key.startsWith(PREFIX)) return@forEach
            val rest = key.removePrefix(PREFIX)
            val device = rest.substringBefore(':')
            if (device == except || !device.matches(Regex("[A-Za-z0-9-]{8,64}")) || ':' !in rest) return@forEach
            parts.getOrPut(device) { LinkedHashMap() }[rest.substringAfter(':')] = text
        }
        return parts.mapValues { (_, p) -> device(p) }
    }

    private fun device(p: Map<String, String>): DeviceListening = DeviceListening(
        recent = array(p["recent"]).mapNotNull { o -> track(o.optJSONObject("t"))?.let { Heard(it, o.optLong("at").coerceAtLeast(0L)) } }
            .take(ListeningShare.RECENT_MAX),
        known = array(p["known"]).mapNotNull(::track).take(MAX_TRACKS),
        counts = counts(p["counts"]),
        listenedMs = p["total"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
        days = ListeningWeek.decode(p["days"]),
        years = Wrapped.decode(p["years"]),
        months = p.filterKeys { it.startsWith("m:") && ListeningMonths.isKey(it.removePrefix("m:")) }
            .mapNotNull { (k, text) -> ListeningMonths.decode(text)?.let { k.removePrefix("m:") to it } }.toMap(),
    )

    private fun validId(id: String) = id.isNotBlank() && id.length <= 256

    private fun track(o: JSONObject?): Track? = ListeningMonths.track(o)?.let(ListeningShare::shareable)

    private fun array(text: String?): List<JSONObject> = runCatching {
        val a = JSONArray(text ?: return emptyList())
        (0 until minOf(a.length(), MAX_TRACKS)).mapNotNull { a.optJSONObject(it) }
    }.getOrDefault(emptyList())

    private fun counts(text: String?): Map<String, Int> = runCatching {
        val o = JSONObject(text ?: return emptyMap())
        o.keys().asSequence().filter(::validId).take(MAX_COUNTS).associateWith { o.optInt(it).coerceAtLeast(0) }.filterValues { it > 0 }
    }.getOrDefault(emptyMap())
}
