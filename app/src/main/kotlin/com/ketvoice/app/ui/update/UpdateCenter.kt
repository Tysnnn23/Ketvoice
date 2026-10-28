package com.ketvoice.app.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.ketvoice.app.BuildConfig
import com.ketvoice.app.data.AppText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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
    val sizeBytes: Long
) {
    fun versionLabel(): String = tag.removePrefix("v").removePrefix("V")
}

object UpdateChecker {

    private const val API = "https://api.github.com/repos/Tysnnn23/Ketvoice/releases?per_page=10"
    private const val PREFS = "ketvoice_updates"
    private const val KEY_DISMISSED = "dismissed_tag"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_SKIP = "skipped_tag"
    private const val KEY_POSTPONED = "postponed_tag"
    internal const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

    /** Dernier auto-check persisté (0 = jamais). */
    fun lastCheckMs(): Long = com.ketvoice.app.KetvoiceApp.instance
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getLong(KEY_LAST_CHECK, 0L)

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

    private fun http(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Ketvoice/${BuildConfig.VERSION_NAME}")
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
        val lines = toLines(body)
        val sections = mutableListOf<Pair<String?, List<String>>>()
        var currentTitle: String? = null
        var currentItems = mutableListOf<String>()
        for (line in lines) {
            val lower = line.lowercase()
            val isHeader = lower.startsWith("## ") || lower.startsWith("### ") ||
                lower.startsWith("correctif") || lower.startsWith("fix") ||
                lower.startsWith("new") || lower.startsWith("nouveaut") ||
                lower.startsWith("liquid") || lower.startsWith("glass") ||
                lower.startsWith("traduction") || lower.startsWith("translation") ||
                lower.startsWith("update") || lower.startsWith("mise à jour")
            if (isHeader && currentItems.isNotEmpty()) {
                sections.add(currentTitle to currentItems.toList())
                currentItems.clear()
                currentTitle = line
            } else {
                currentItems.add(line)
            }
        }
        if (currentItems.isNotEmpty()) sections.add(currentTitle to currentItems)
        if (sections.isEmpty()) sections.add(null to lines)
        return sections
    }

    suspend fun check(force: Boolean = false): UpdateInfo? {
        val app = com.ketvoice.app.KetvoiceApp.instance
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force) {
            val last = prefs.getLong(KEY_LAST_CHECK, 0L)
            if (System.currentTimeMillis() - last < 6L * 60L * 60L * 1000L) return null
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val conn = http(API)
                if (conn.responseCode != 200) return@runCatching null
                val raw = conn.inputStream.bufferedReader().use { it.readText() }
                prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
                // /releases/latest exclut les prereleases : pour les voir (versions
                // BETA), on parcourt les 10 dernières releases et on prend la plus
                // récente candidate (stable OU prerelease).
                val arr = JSONArray(raw)
                if (arr.length() == 0) return@runCatching null
                val json = (0 until arr.length())
                    .mapNotNull { arr.optJSONObject(it) }
                    .firstOrNull { obj ->
                        val t = obj.optString("tag_name")
                        t.isNotBlank() && isNewer(t, BuildConfig.VERSION_NAME) &&
                            t != prefs.getString(KEY_DISMISSED, null) &&
                            t != prefs.getString(KEY_SKIP, null)
                    } ?: return@runCatching null
                val tag = json.optString("tag_name")

                var apk: String? = null
                var size = 0L
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        val n = a.optString("name")
                        if (!n.endsWith(".apk", true)) continue
                        val isQuestBuild = n.contains("quest", true)
                        if (BuildConfig.IS_QUEST != isQuestBuild) continue
                        apk = a.optString("browser_download_url")
                        size = a.optLong("size")
                        break
                    }
                }
                val apkUrl = apk ?: return@runCatching null

                UpdateInfo(
                    tag = tag,
                    name = json.optString("name").ifBlank { "Ketvoice $tag" },
                    notes = toLines(json.optString("body")),
                    apkUrl = apkUrl,
                    pageUrl = json.optString("html_url"),
                    publishedAt = json.optString("published_at").take(10),
                    sizeBytes = size
                )
            }.getOrNull()
        }
    }

    fun dismiss(tag: String) {
        com.ketvoice.app.KetvoiceApp.instance
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DISMISSED, tag).apply()
    }
    /** "Passer cette version" : plus de prompt auto pour ce tag (installable manuellement). */
    fun skip(tag: String) {
        com.ketvoice.app.KetvoiceApp.instance
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SKIP, tag).apply()
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
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "ketvoice-${info.versionLabel()}.apk")
            val conn = http(info.apkUrl).apply { instanceFollowRedirects = true }
            conn.connect()
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            var done = 0L
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            conn.disconnect()
            if (total > 0 && target.length() < total * 0.98f) {
                throw IllegalStateException("Téléchargement incomplet")
            }
            onProgress(1f)
            target
        }

    /** Lance l'installeur système. Renvoie false si l'utilisateur doit d'abord autoriser l'installation. */
    fun install(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
        return true
    }
}

@Composable
fun UpdateHost() {
    var info by remember { mutableStateOf<UpdateInfo?>(null) }

    // Au lancement : recharge l'éventuel report "Plus tard" persisté, puis
    // auto-check au plus toutes les 6 h. "Passer" est mémorisé.
    LaunchedEffect(Unit) {
        UpdateBadge.postponedTag = UpdateChecker.postponedTag()
        if (System.currentTimeMillis() - UpdateChecker.lastCheckMs() >= UpdateChecker.CHECK_INTERVAL_MS) {
            info = UpdateChecker.check()
        }
    }

    // Clic sur le badge "mise à jour" de la page d'accueil : rouvre la popup.
    LaunchedEffect(UpdateBadge.pendingRequest) {
        if (UpdateBadge.pendingRequest) {
            UpdateBadge.pendingRequest = false
            if (info == null) info = UpdateBadge.lastInfo ?: UpdateChecker.check(force = true)
        }
    }

    val update = info
    if (update == null) {
        UpdateBadge.available = null
        return
    }
    UpdateBadge.available = update
    UpdateBadge.lastInfo = update
    UpdateDialog(update, onDismiss = {
        // "Plus tard" : la popup se ferme, un badge apparaît à côté de la photo
        // de profil sur la page d'accueil pour rouvrir la popup à la demande.
        UpdateChecker.postpone(update.tag)
        UpdateBadge.postponedTag = update.tag
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
    var pendingRequest by mutableStateOf(false)
    var lastInfo: UpdateInfo? = null

    /** Appelé par le badge : rouvre la popup (UpdateHost la ré-affiche). */
    fun requestShow() {
        pendingRequest = true
    }
}

@Composable
private fun UpdateDialog(info: UpdateInfo, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val downloading = progress != null

    Dialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF14151A),
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))) {
                    AsyncImage(
                        model = "file:///android_asset/release_banner.png",
                        contentDescription = null,
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
                            onClick = {
                                UpdateChecker.skip(info.tag)
                                onDismiss()
                            },
                            enabled = !downloading
                        ) {
                            Text(
                                AppText.t("Passer cette version"),
                                color = Color.White.copy(alpha = if (downloading) .3f else .62f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.size(6.dp))
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color.White,
                            modifier = Modifier.clip(RoundedCornerShape(50))
                        ) {
                            TextButton(
                                enabled = !downloading,
                                onClick = {
                                    error = null
                                    progress = 0f
                                    scope.launch {
                                        runCatching {
                                            val file = UpdateChecker.download(context, info) { progress = it }
                                            val started = UpdateChecker.install(context, file)
                                            if (!started) {
                                                progress = null
                                                error = AppText.t(
                                                    "Autorisez Ketvoice à installer des applications, puis relancez"
                                                )
                                            }
                                        }.onFailure {
                                            progress = null
                                            error = it.message ?: AppText.t("Téléchargement impossible")
                                        }
                                    }
                                }
                            ) {
                                if (downloading && progress!! <= 0f) {
                                    CircularProgressIndicator(Modifier.size(15.dp), color = Color.Black, strokeWidth = 1.8.dp)
                                } else {
                                    Text(
                                        AppText.t("Télécharger"),
                                        color = Color.Black,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}
