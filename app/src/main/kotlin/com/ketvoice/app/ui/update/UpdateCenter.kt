package com.ketvoice.app.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.ketvoice.app.BuildConfig
import com.ketvoice.app.data.AppText
import kotlinx.coroutines.launch
import java.io.File

internal val ReleaseBannerLoaded = SemanticsPropertyKey<Boolean>("ReleaseBannerLoaded")

/**
 * Mise à jour depuis les releases GitHub.
 *
 * Volontairement minimale : une requête, un APK, une intention d'installation.
 * Aucune dépendance à un serveur maison, aucun service en arrière-plan.
 */
data class UpdateInfo(
    val tag: String,
    val name: String,
    val notes: List<String>,
    val apkUrl: String,
    val pageUrl: String,
    val publishedAt: String,
    val sizeBytes: Long,
    val sha256: String? = null
) {
    fun versionLabel(): String = tag.removePrefix("v").removePrefix("V")
    fun bannerUrl(): String? = UpdateChecker.releaseBanner(notes.joinToString("\n"))
}

object UpdateChecker {
    internal const val CANONICAL_BANNER = "https://raw.githubusercontent.com/Tysnnn23/Ketvoice/main/ketvoice-banner.png"

    /** Only trusted repository images; never load arbitrary release-body URLs. */
    internal fun releaseBanner(body: String): String? = Regex("!\\[[^\\]]*]\\((https://[^\\s)]+)\\)")
        .findAll(body).mapNotNull { match ->
            val value = match.groupValues[1].replace("https://github.com/Tysnnn23/Ketvoice/blob/",
                "https://raw.githubusercontent.com/Tysnnn23/Ketvoice/")
            val uri = runCatching { java.net.URI(value) }.getOrNull() ?: return@mapNotNull null
            val trusted = (uri.host == "raw.githubusercontent.com" && uri.path.startsWith("/Tysnnn23/Ketvoice/")) ||
                (uri.host == "github.com" && uri.path.startsWith("/Tysnnn23/Ketvoice/releases/download/"))
            value.takeIf { trusted && uri.scheme == "https" && uri.rawUserInfo == null && uri.port in setOf(-1, 443) &&
                uri.path.lowercase().let { path -> listOf(".png", ".jpg", ".jpeg", ".webp").any { path.endsWith(it) } } }
        }.firstOrNull()

    private const val PREFS = "ketvoice_updates"
    private const val KEY_DISMISSED = "dismissed_tag"
    private const val KEY_SKIP = "skipped_tag"
    private const val KEY_POSTPONED = "postponed_tag"
    internal const val CHECK_INTERVAL_MS = 15 * 60 * 1000L
    private val repository by lazy { UpdateRepository(com.ketvoice.app.KetvoiceApp.instance) }

    /** Dernier auto-check persisté (0 = jamais). */
    fun lastCheckMs(): Long = repository.lastCheckMs()

    /** Numeric core of a version: "v3.5.0-BETA+7" -> [3, 5, 0]. */
    private fun core(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V")
            .substringBefore('+').substringBefore('-')
            .split('.')
            .map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    /** Pre-release label ("BETA" in "3.5.0-BETA"), or null for a stable version. */
    private fun preRelease(version: String): String? =
        version.trim().substringBefore('+').substringAfter('-', "").ifBlank { null }

    /**
     * True when [remote] should replace [current]. The numeric core decides first;
     * with an identical core, a stable build is newer than its own pre-release
     * (3.5.0 > 3.5.0-BETA), so beta users are offered the final release.
     */
    fun isNewer(remote: String, current: String): Boolean {
        val a = core(remote)
        val b = core(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return preRelease(remote) == null && preRelease(current) != null
    }

    /** Nettoie le Markdown de la release pour un affichage en texte simple. */
    private fun toLines(body: String): List<String> {
        // If the body is mostly English, keep it; if mostly French, translate section titles.
        // We don't auto-translate full text (quality), just normalise headers.
        val looksEnglish = body.split('\n')
            .filter { it.trim().isNotEmpty() }
            .take(30)
            .count { it.matches(Regex("^[A-Za-z]")) } >
            body.split('\n').filter { it.trim().isNotEmpty() }.take(30).size / 2

        return body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.startsWith("![") || it.startsWith("<img") }
            .map { line ->
                when {
                    line.startsWith("### ") -> line.removePrefix("### ").trim()
                    line.startsWith("## ") -> line.removePrefix("## ").trim()
                    line.startsWith("# ") -> line.removePrefix("# ").trim()
                    line.startsWith("- ") || line.startsWith("* ") -> {
                        val txt = line.drop(2).replace("**", "").replace("`", "")
                        if (looksEnglish && txt.startsWith("·")) txt else "· $txt"
                    }
                    else -> line.replace("**", "").replace("`", "")
                }
            }
            .toList()
    }

    /** Parse release notes into structured sections for better UI. */
    internal fun parseSections(body: String): List<Pair<String?, List<String>>> {
        val sections = mutableListOf<Pair<String?, List<String>>>()
        var currentTitle: String? = null
        var currentItems = mutableListOf<String>()
        for (raw in body.lines()) {
            val line = raw.trim()
            if (line.startsWith("# ") || line.startsWith("![") || line.startsWith("<img")) continue
            if (line.startsWith("## ") || line.startsWith("### ")) {
                if (currentItems.isNotEmpty()) sections.add(currentTitle to currentItems.toList())
                currentItems.clear()
                currentTitle = line.trimStart('#').trim()
            } else {
                currentItems.addAll(toLines(line))
            }
        }
        if (currentItems.isNotEmpty()) sections.add(currentTitle to currentItems)
        if (sections.isEmpty()) sections.add(null to toLines(body))
        return sections
    }

    suspend fun check(force: Boolean = false): UpdateInfo? = repository.check(force)

    fun cached(): UpdateInfo? = repository.cached()

    fun dismiss(tag: String) {
        com.ketvoice.app.KetvoiceApp.instance
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DISMISSED, tag).apply()
    }
    /** "Passer cette version" : plus de prompt auto pour ce tag (installable manuellement). */
    fun skip(tag: String) {
        com.ketvoice.app.KetvoiceApp.instance
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SKIP, tag).remove(KEY_POSTPONED).apply()
    }

    fun isSkipped(tag: String): Boolean {
        return tag == com.ketvoice.app.KetvoiceApp.instance
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SKIP, null)
    }

    /** "Plus tard" : report mémorisé -> badge "mise à jour" sur la page d'accueil. */
    fun postpone(tag: String) {
        com.ketvoice.app.KetvoiceApp.instance
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_POSTPONED, tag).apply()
    }

    fun postponedTag(): String? =
        com.ketvoice.app.KetvoiceApp.instance
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_POSTPONED, null)
            ?.takeIf { it != BuildConfig.VERSION_NAME && isNewer(it, BuildConfig.VERSION_NAME) }

    /** Télécharge l'APK dans le cache privé et renvoie le fichier prêt à installer. */
    suspend fun download(context: Context, info: UpdateInfo, onProgress: (Float) -> Unit): File =
        UpdateInstaller.download(context, info, onProgress)

    /** Lance l'installeur système. Renvoie false si l'utilisateur doit d'abord autoriser l'installation. */
    suspend fun install(context: Context, file: File, info: UpdateInfo): Boolean = UpdateInstaller.install(context, file, info)
}

@Composable
fun UpdateHost(
    check: suspend (Boolean) -> UpdateInfo? = { UpdateChecker.check(it) },
    cached: () -> UpdateInfo? = { UpdateChecker.cached() }
) {
    var info by remember { mutableStateOf<UpdateInfo?>(null) }
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lock = remember { Mutex() }
    fun offer(candidate: UpdateInfo?, manual: Boolean) {
        if (candidate == null) return
        UpdateBadge.lastInfo = candidate
        if (!manual && candidate.tag == UpdateChecker.postponedTag()) {
            UpdateBadge.postponedTag = candidate.tag
        } else {
            info = candidate
            UpdateBadge.available = candidate
        }
    }
    suspend fun refresh(manual: Boolean) = lock.withLock {
        UpdateBadge.checking = true
        try {
            val candidate = check(manual)
            offer(candidate, manual)
            if (manual && candidate == null) android.widget.Toast.makeText(context,
                AppText.t("Application à jour"), android.widget.Toast.LENGTH_SHORT).show()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (manual) android.widget.Toast.makeText(context,
                AppText.t("Impossible de vérifier les mises à jour"), android.widget.Toast.LENGTH_LONG).show()
        } finally {
            UpdateBadge.checking = false
        }
    }
    LaunchedEffect(lifecycle) {
        UpdateBadge.postponedTag = UpdateChecker.postponedTag()
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            offer(cached(), false)
            while (true) {
                refresh(false)
                delay(UpdateChecker.CHECK_INTERVAL_MS)
            }
        }
    }

    // Consume an ID, not the effect's key: resetting a Boolean here cancelled
    // the suspend check before its result could open the dialog.
    LaunchedEffect(UpdateBadge.pendingRequest) {
        val request = UpdateBadge.pendingRequest
        if (request != UpdateBadge.handledRequest) {
            UpdateBadge.handledRequest = request
            val previous = UpdateBadge.lastInfo
            if (!UpdateBadge.forceRefresh && previous != null && UpdateChecker.isNewer(previous.tag, BuildConfig.VERSION_NAME))
                offer(previous, true)
            else refresh(true)
        }
    }

    val update = info ?: return
    UpdateDialog(update, onDismiss = {
        // "Plus tard" : la popup se ferme, un badge apparaît à côté de la photo
        // de profil sur la page d'accueil pour rouvrir la popup à la demande.
        UpdateChecker.postpone(update.tag)
        UpdateBadge.postponedTag = update.tag
        UpdateBadge.available = null
        info = null
    }, onSkip = {
        UpdateChecker.skip(update.tag)
        UpdateBadge.postponedTag = null
        UpdateBadge.available = null
        info = null
    })
}

/**
 * État global minimal du flux de mise à jour, observé par le badge de la page
 * d'accueil : [available] tant que la popup est affichée, [postponedTag] dès
 * que l'utilisateur a cliqué "Plus tard" (le badge reste jusqu'à l'install).
 */
object UpdateBadge {
    var available by mutableStateOf<UpdateInfo?>(null)
    var postponedTag by mutableStateOf<String?>(null)
    var pendingRequest by mutableIntStateOf(0)
    var checking by mutableStateOf(false)
    internal var handledRequest = 0
    internal var forceRefresh = false
    var lastInfo: UpdateInfo? = null

    /** Appelé par le badge : rouvre la popup (UpdateHost la ré-affiche). */
    fun requestShow() {
        forceRefresh = false
        pendingRequest++
    }

    fun requestCheck() {
        forceRefresh = true
        pendingRequest++
    }
}

@Composable
internal fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit, onSkip: () -> Unit,
    download: suspend (Context, UpdateInfo, (Float) -> Unit) -> File = UpdateChecker::download,
    install: suspend (Context, File, UpdateInfo) -> Boolean = UpdateChecker::install) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val maxDialogHeight = (LocalConfiguration.current.screenHeightDp.dp - 48.dp).coerceAtLeast(200.dp)
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloadedPath by rememberSaveable(info.tag) { mutableStateOf<String?>(null) }
    var awaitingPermission by rememberSaveable(info.tag) { mutableStateOf(false) }
    var permissionResult by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { permissionResult++ }
    val downloading = progress != null
    suspend fun installReady(file: File) {
        if (!install(context, file, info)) {
            awaitingPermission = true
            permissionLauncher.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")))
        }
    }
    LaunchedEffect(permissionResult) {
        if (permissionResult > 0 && awaitingPermission) {
            awaitingPermission = false
            if (context.packageManager.canRequestPackageInstalls()) {
                try {
                    downloadedPath?.let { installReady(File(it)) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = AppText.t("Installation impossible") }
            } else error = AppText.t("Autorisez Ketvoice à installer des applications, puis relancez")
        }
    }

    Dialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF14151A),
            tonalElevation = 0.dp,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .heightIn(max = maxDialogHeight)
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                val banner = info.bannerUrl()
                var bannerLoaded by remember(banner) { mutableStateOf(false) }
                if (banner != null) Box(Modifier.fillMaxWidth().height(190.dp).testTag("release-banner")
                    .semantics { this[ReleaseBannerLoaded] = bannerLoaded }
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))) {
                    AsyncImage(
                        model = if (banner == UpdateChecker.CANONICAL_BANNER) "file:///android_asset/release_banner.png" else banner,
                        contentDescription = null,
                        onSuccess = { bannerLoaded = true },
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(190.dp)
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0.55f to Color.Transparent,
                                1f to Color(0xFF14151A)
                            )
                        )
                    )
                }
                else Spacer(Modifier.height(24.dp))

                Column(Modifier.padding(horizontal = 22.dp)) {
                    Text(
                        info.name,
                        color = Color.White,
                        fontSize = 21.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        AppText.t("Version") + " " + info.versionLabel() + " · " + info.publishedAt,
                        color = Color.White.copy(alpha = .45f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(14.dp))
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.pageUrl))) }
                    }) { Text("GitHub", color = Color.White.copy(alpha = .74f)) }

                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 168.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        val sections = UpdateChecker.parseSections(info.notes.joinToString("\n"))
                        sections.forEachIndexed { idx, (title, items) ->
                            if (idx > 0) Spacer(Modifier.height(8.dp))
                            title?.let {
                                Text(
                                    it,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            items.forEach { line ->
                                Text(
                                    line,
                                    color = Color.White.copy(alpha = .74f),
                                    fontSize = 12.sp,
                                    lineHeight = 18.sp,
                                    modifier = Modifier.padding(vertical = 2.dp)
                                )
                            }
                        }
                    }

                    error?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = Color(0xFFFF8A80), fontSize = 12.sp)
                    }

                    progress?.let { value ->
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(
                            progress = { value },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = Color.White,
                            trackColor = Color.White.copy(alpha = .16f)
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            (value * 100).toInt().toString() + "% · " + AppText.t("Téléchargement en cours"),
                            color = Color.White.copy(alpha = .5f),
                            fontSize = 11.sp
                        )
                    }

                    Spacer(Modifier.height(18.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss, enabled = !downloading) {
                            Text(
                                AppText.t("Plus tard"),
                                color = Color.White.copy(alpha = if (downloading) .3f else .62f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        TextButton(
                            onClick = onSkip,
                            enabled = !downloading
                        ) {
                            Text(
                                AppText.t("Passer cette version"),
                                color = Color.White.copy(alpha = if (downloading) .3f else .62f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color.White,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
                        ) {
                            TextButton(
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !downloading,
                                onClick = {
                                    error = null
                                    progress = 0f
                                    scope.launch {
                                        try {
                                            val file = downloadedPath?.let(::File)?.takeIf { it.isFile }
                                                ?: download(context, info) { progress = it }.also { downloadedPath = it.absolutePath }
                                            installReady(file)
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) {
                                            error = AppText.t(if (downloadedPath == null) "Téléchargement impossible" else "Installation impossible")
                                        } finally {
                                            // The system installer may be cancelled; keep the verified file
                                            // and re-enable retry instead of leaving a permanent 100% state.
                                            progress = null
                                        }
                                    }
                                }
                            ) {
                                if (downloading && progress!! <= 0f) {
                                    CircularProgressIndicator(Modifier.size(15.dp), color = Color.Black, strokeWidth = 1.8.dp)
                                } else {
                                    Text(
                                        AppText.t(if (downloadedPath == null) "Télécharger" else "Installer"),
                                        color = Color.Black,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}
