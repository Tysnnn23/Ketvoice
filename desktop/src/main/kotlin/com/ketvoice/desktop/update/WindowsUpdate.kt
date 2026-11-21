package com.ketvoice.desktop.update

import com.ketvoice.desktop.platform.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A Windows release version (`windows-v1.2.3`); prerelease suffixes are never offered. */
data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int = compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)
    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""(\d{1,5})\.(\d{1,5})\.(\d{1,5})""")
        fun parse(text: String): SemVer? = PATTERN.matchEntire(text.trim())?.groupValues?.let { SemVer(it[1].toInt(), it[2].toInt(), it[3].toInt()) }
    }
}

data class WindowsRelease(
    val tag: String,
    val version: SemVer,
    val name: String,
    /** The release body: English Markdown, shown as it is written. */
    val notes: String,
    val msiName: String,
    val msiUrl: String,
    val msiSize: Long,
    /** GitHub's own `sha256:` asset digest when the API gives one; SHA256SUMS.txt is still required. */
    val msiDigest: String?,
    val sumsUrl: String,
    val pageUrl: String,
)

/**
 * The public GitHub releases of the repository, read without a token. Only `windows-v<major.minor.patch>` tags count:
 * the Android `v*` releases and the model tags (`ketmix-*`) are ignored, and so are drafts and prereleases. A release
 * is offered only with both `Ketvoice-<version>.msi` and `SHA256SUMS.txt` served from the repository's download path.
 */
object ReleaseFeed {
    const val REPO = "Tysnnn23/Ketvoice"
    const val API = "https://api.github.com/repos/$REPO/releases?per_page=30"
    const val DOWNLOAD_PREFIX = "https://github.com/$REPO/releases/download/"
    private val TAG = Regex("""windows-v(\d{1,5}\.\d{1,5}\.\d{1,5})""")
    private val DIGEST = Regex("""sha256:([0-9a-fA-F]{64})""")
    private val SUM_LINE = Regex("""([0-9a-fA-F]{64})\s+\*?(.+)""")

    fun releases(json: String): List<WindowsRelease> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::release) }
    }

    /** The newest release above [current], or null (never an older or equal version: no downgrade). */
    fun newest(json: String, current: SemVer): WindowsRelease? =
        releases(json).filter { it.version > current }.maxByOrNull { it.version }

    private fun release(o: JSONObject): WindowsRelease? {
        val tag = o.optString("tag_name")
        val version = TAG.matchEntire(tag)?.groupValues?.get(1)?.let(SemVer::parse) ?: return null
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val assets = o.optJSONArray("assets") ?: return null
        val byName = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }.associateBy { it.optString("name") }
        val msiName = "Ketvoice-$version.msi"
        val msi = byName[msiName] ?: return null
        val sums = byName["SHA256SUMS.txt"] ?: return null
        val msiUrl = msi.optString("browser_download_url")
        val sumsUrl = sums.optString("browser_download_url")
        if (!msiUrl.startsWith("$DOWNLOAD_PREFIX$tag/") || !sumsUrl.startsWith("$DOWNLOAD_PREFIX$tag/")) return null
        return WindowsRelease(tag, version, o.optString("name").ifBlank { "Ketvoice $version" }, o.optString("body"),
            msiName, msiUrl, msi.optLong("size"), DIGEST.matchEntire(msi.optString("digest"))?.groupValues?.get(1)?.lowercase(),
            sumsUrl, o.optString("html_url").ifBlank { "https://github.com/$REPO/releases/tag/$tag" })
    }

    /** The SHA-256 listed for [file] in a `sha256sum` file (`<hash>  <name>` or `<hash> *<name>`), lower case. */
    fun sumFor(sums: String, file: String): String? = sums.lineSequence().mapNotNull { SUM_LINE.matchEntire(it.trim()) }
        .firstOrNull { it.groupValues[2].trim() == file }?.groupValues?.get(1)?.lowercase()

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/** How the updater reaches GitHub; tests give a fake feed. */
interface UpdateTransport {
    fun text(url: String, limit: Int): String
    /** Writes [url] to [to]; [onProgress] gets the bytes written so far. */
    fun download(url: String, to: File, onProgress: (Long) -> Unit)
}

class OkHttpUpdateTransport(private val userAgent: String) : UpdateTransport {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", userAgent)
        .header("Accept", if (url.startsWith("https://api.github.com/")) "application/vnd.github+json" else "*/*").build()

    override fun text(url: String, limit: Int): String = client.newCall(request(url)).execute().use { r ->
        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
        val body = r.body ?: throw IOException("empty answer")
        if (body.contentLength() > limit) throw IOException("answer too large")
        body.source().readUtf8().also { if (it.length > limit) throw IOException("answer too large") }
    }

    override fun download(url: String, to: File, onProgress: (Long) -> Unit) = client.newCall(request(url)).execute().use { r ->
        if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
        val body = r.body ?: throw IOException("empty answer")
        to.outputStream().use { out ->
            body.byteStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    out.write(buffer, 0, n); total += n; onProgress(total)
                }
            }
        }
    }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: WindowsRelease) : UpdateState
    data class Downloading(val release: WindowsRelease, val fraction: Float?) : UpdateState
    data class Installing(val release: WindowsRelease) : UpdateState
    /** [message] is shown as it is (already translated); [release] is set when the failure came while installing it. */
    data class Failed(val message: String, val release: WindowsRelease? = null) : UpdateState
}

/**
 * In-app updates from the `windows-v*` releases. [check] reads the feed at most once per [interval] (the last feed is
 * kept in [dir], so a restart shows what it found); [install] downloads the MSI, checks its SHA-256 against the
 * release's SHA256SUMS.txt (and GitHub's digest when there is one), then hands it to [launcher] — `msiexec /i … /passive`,
 * after which the app quits and the MSI (same UpgradeCode) upgrades it in place.
 */
class WindowsUpdater(
    private val transport: UpdateTransport,
    private val dir: File,
    private val current: SemVer,
    private val launcher: (File) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val interval: Long = SIX_HOURS,
    private val text: (String) -> String = { it },
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private val mutex = Mutex()
    private val feedFile get() = File(dir, "feed.json")
    private val stateFile get() = File(dir, "state.json")

    private fun saved(): JSONObject = runCatching { JSONObject(stateFile.readText()) }.getOrDefault(JSONObject())
    private fun save(o: JSONObject) = runCatching { dir.mkdirs(); stateFile.writeText(o.toString()) }

    /** The tag the listener put off with « Plus tard »; its banner stays hidden until a newer release. */
    val dismissedTag: String? get() = saved().optString("dismissed").ifEmpty { null }

    fun dismiss(release: WindowsRelease) = save(saved().put("dismissed", release.tag))

    /** Reads the feed (cached for [interval] unless [force]) and sets [state]. */
    suspend fun check(force: Boolean = false): UpdateState = mutex.withLock {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return _state.value
        val last = saved().optLong("checkedAt", 0)
        val fresh = !force && clock() - last in 0 until interval && feedFile.isFile
        if (!fresh) _state.value = UpdateState.Checking
        val next = runCatching {
            val json = if (fresh) feedFile.readText() else withContext(Dispatchers.IO) { transport.text(ReleaseFeed.API, 2_000_000) }.also {
                ReleaseFeed.releases(it) // malformed feeds fail here, before they replace the cached one
                dir.mkdirs(); feedFile.writeText(it); save(saved().put("checkedAt", clock()))
            }
            ReleaseFeed.newest(json, current)?.let { UpdateState.Available(it) } ?: UpdateState.UpToDate
        }.getOrElse {
            AppLog.w("update", "check: $it")
            UpdateState.Failed(text("Impossible de vérifier les mises à jour.") + " (${it.message ?: it.javaClass.simpleName})")
        }
        _state.value = next
        next
    }

    /** Downloads, verifies and launches [release]'s MSI. Returns true once the installer has started. */
    suspend fun install(release: WindowsRelease): Boolean {
        if (release.version <= current) { _state.value = UpdateState.Failed(text("Cette version n'est pas plus récente."), release); return false }
        _state.value = UpdateState.Downloading(release, null)
        val result = runCatching {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                val sums = transport.text(release.sumsUrl, 64_000)
                val expected = ReleaseFeed.sumFor(sums, release.msiName) ?: throw UpdateError(text("Le fichier SHA256SUMS.txt ne contient pas l'installateur."))
                if (release.msiDigest != null && release.msiDigest != expected) throw UpdateError(text("Les empreintes de l'installateur ne concordent pas."))
                val part = File(dir, release.msiName + ".part")
                part.delete()
                transport.download(release.msiUrl, part) { done ->
                    _state.value = UpdateState.Downloading(release, if (release.msiSize > 0) (done.toFloat() / release.msiSize).coerceIn(0f, 1f) else null)
                }
                val actual = ReleaseFeed.sha256(part)
                if (actual != expected) { part.delete(); throw UpdateError(text("L'installateur téléchargé est altéré (SHA-256 différent).")) }
                val msi = File(dir, release.msiName)
                msi.delete()
                if (!part.renameTo(msi)) throw IOException("rename ${part.name}")
                _state.value = UpdateState.Installing(release)
                launcher(msi)
            }
        }
        result.exceptionOrNull()?.let {
            AppLog.w("update", "install ${release.tag}: $it")
            _state.value = UpdateState.Failed((it as? UpdateError)?.message
                ?: (text("La mise à jour a échoué.") + " (${it.message ?: it.javaClass.simpleName})"), release)
            return false
        }
        return true
    }

    private class UpdateError(message: String) : Exception(message)

    companion object {
        const val SIX_HOURS = 6 * 60 * 60 * 1000L

        /** `msiexec /i <msi> /passive` (a progress bar, no questions; per-user MSI, so no elevation), with a log beside it. */
        fun msiexec(msi: File): Process = ProcessBuilder("msiexec", "/i", msi.absolutePath, "/passive", "/norestart",
            "/l*v", File(msi.parentFile, "install.log").absolutePath).start()
    }
}
