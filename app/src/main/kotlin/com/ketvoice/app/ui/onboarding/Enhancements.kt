package com.ketvoice.app.ui.onboarding

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppText
import com.ketvoice.app.data.LocalMusic
import com.ketvoice.app.stems.ModelPack
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.ui.common.AppAlertDialog
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.KetProgressBar
import com.ketvoice.app.ui.common.KetTextButton
import com.ketvoice.app.ui.common.Text

/**
 * « Améliorez votre expérience d'écoute » (7.6, owner 2026-11-20): the optional downloads and the local scan, offered
 * at the end of the welcome and again from Settings. Each row says what it does and what it weighs, and its button
 * shows the real progress; downloads run in [ModelPack]'s own scope, so they go on when the listener moves on.
 *
 *  - The quick separation model (Spleeter, `ketmix-stems-v1`) and the high-quality one (UVR MDX-Net,
 *    `ketmix-stems-v2`), with the sizes pinned in `stems/StemModel.kt`; Lite and modest phones see the quick one only.
 *  - Ketmix's rhythm model, only where the APK does not carry it (Lite).
 *  - The local music scan, with Android's audio permission (a chosen folder works too), where the app declares it.
 *
 * On mobile data each download asks first, with its size.
 */
internal enum class EnhanceKind { VoiceQuick, VoiceHq, Rhythm, LocalMusic }

internal sealed interface EnhanceState {
    data object Available : EnhanceState
    /** Downloading (a fraction) or scanning (null). */
    data class Working(val fraction: Float?) : EnhanceState
    /** Installed, or switched on; [count] is how many titles the scan found. */
    data class Done(val count: Int? = null) : EnhanceState
    /** A download failed, or the audio permission was refused. */
    data object Failed : EnhanceState
}

internal data class Enhancement(val kind: EnhanceKind, val state: EnhanceState, val bytes: Long = 0L)

internal object Enhancements {
    /** The rows this phone is offered. */
    fun kinds(lite: Boolean, rhythmBundled: Boolean, localSupported: Boolean): List<EnhanceKind> = buildList {
        add(EnhanceKind.VoiceQuick)
        if (!lite) add(EnhanceKind.VoiceHq)
        if (!rhythmBundled) add(EnhanceKind.Rhythm)
        if (localSupported) add(EnhanceKind.LocalMusic)
    }

    /** Whether the listener has something on its way or already there: then nothing is to be warned about. */
    fun chosen(items: List<Enhancement>): Boolean =
        items.any { it.state is EnhanceState.Working || it.state is EnhanceState.Done }

    fun of(state: ModelPack.State): EnhanceState = when (state) {
        ModelPack.State.Missing -> EnhanceState.Available
        is ModelPack.State.Downloading -> EnhanceState.Working(state.fraction)
        ModelPack.State.Ready -> EnhanceState.Done()
        is ModelPack.State.Failed -> EnhanceState.Failed
    }

    /** The size as Settings writes it, rounded to the megabyte. */
    fun megabytes(bytes: Long): String = AppText.t("{0} Mo").replace("{0}", ((bytes + 500_000L) / 1_000_000L).toString())

    fun title(kind: EnhanceKind): String = when (kind) {
        EnhanceKind.VoiceQuick -> "Séparation des voix"
        EnhanceKind.VoiceHq -> "Modèle haute qualité"
        EnhanceKind.Rhythm -> "Calage précis du rythme"
        EnhanceKind.LocalMusic -> "Scanner la musique de l'appareil"
    }

    fun what(kind: EnhanceKind): String = when (kind) {
        EnhanceKind.VoiceQuick -> "Baissez la voix d'un titre depuis les paroles, et laissez Ketmix mêler les voix d'un titre à l'autre."
        EnhanceKind.VoiceHq -> "Voix et instruments bien plus nets. Plus lent, il travaille quand le téléphone charge."
        EnhanceKind.Rhythm -> "Ketmix trouve les temps et les mesures pour des enchaînements mieux calés."
        EnhanceKind.LocalMusic -> "Vos fichiers audio rejoignent la bibliothèque et la recherche. Rien ne quitte le téléphone."
    }

    /** The pack behind a download row. */
    fun pack(kind: EnhanceKind): ModelPack? = when (kind) {
        EnhanceKind.VoiceQuick -> com.ketvoice.app.stems.StemModel.fast
        EnhanceKind.VoiceHq -> com.ketvoice.app.stems.StemModel.hq
        EnhanceKind.Rhythm -> com.ketvoice.app.automix.BeatModel.pack
        EnhanceKind.LocalMusic -> null
    }

    /** Whether this build asks for the audio permission at all (every flavour does today, Quest included). */
    fun localSupported(context: Context): Boolean = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.contains(LocalMusic.permission) == true
    }.getOrDefault(false)

    fun metered(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching false
        !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)
}

/** The rows as they are now on this phone, following each download and the scan live. */
@Composable
internal fun rememberEnhancements(refused: Boolean = false): List<Enhancement> {
    val context = LocalContext.current
    LaunchedEffect(Unit) { runCatching { com.ketvoice.app.stems.StemModel.init(context) } }
    val fast by com.ketvoice.app.stems.StemModel.fast.state.collectAsState()
    val hq by com.ketvoice.app.stems.StemModel.hq.state.collectAsState()
    val beat by com.ketvoice.app.automix.BeatModel.pack.state.collectAsState()
    val scanning by LocalMusic.scanning.collectAsState()
    val tracks by LocalMusic.tracks.collectAsState()
    val needsPermission by LocalMusic.needsPermission.collectAsState()
    val kinds = remember {
        Enhancements.kinds(lite = com.ketvoice.app.LiteTuning.lite || com.ketvoice.app.LiteTuning.modest,
            rhythmBundled = com.ketvoice.app.automix.BeatModel.bundled, localSupported = Enhancements.localSupported(context))
    }
    val localOn = !needsPermission && (com.ketvoice.app.data.AppSettings.localMusicFolder.value != null || LocalMusic.hasPermission(context))
    return kinds.map { kind ->
        when (kind) {
            EnhanceKind.VoiceQuick -> Enhancement(kind, Enhancements.of(fast), com.ketvoice.app.stems.StemModel.fast.totalBytes)
            EnhanceKind.VoiceHq -> Enhancement(kind, Enhancements.of(hq), com.ketvoice.app.stems.StemModel.hq.totalBytes)
            EnhanceKind.Rhythm -> Enhancement(kind, Enhancements.of(beat), com.ketvoice.app.automix.BeatModel.pack.totalBytes)
            EnhanceKind.LocalMusic -> Enhancement(kind, when {
                scanning -> EnhanceState.Working(null)
                localOn -> EnhanceState.Done(tracks.size)
                refused -> EnhanceState.Failed
                else -> EnhanceState.Available
            })
        }
    }
}

/**
 * The list with its actions: a download (confirmed first on mobile data) or the permission and the scan.
 * [items] come from [rememberEnhancements]; [onRefused] is told when Android's permission is refused.
 */
@Composable
internal fun EnhanceList(items: List<Enhancement>, onRefused: (Boolean) -> Unit, modifier: Modifier = Modifier,
                         metered: Boolean = Enhancements.metered(LocalContext.current)) {
    val th = LocalTheme.current
    val context = LocalContext.current
    var confirm by remember { mutableStateOf<Enhancement?>(null) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        onRefused(!granted)
        LocalMusic.scan(context)
    }
    val folder = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { LocalMusic.acceptFolder(context, uri); onRefused(false); LocalMusic.scan(context) }
    }
    fun act(item: Enhancement) {
        when (item.kind) {
            EnhanceKind.LocalMusic -> when {
                item.state == EnhanceState.Failed -> runCatching { folder.launch(null) }
                LocalMusic.hasPermission(context) -> LocalMusic.scan(context)
                else -> runCatching { permission.launch(LocalMusic.permission) }
            }
            else -> if (metered) confirm = item else Enhancements.pack(item.kind)?.download()
        }
    }
    Column(modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(th.card).testTag("enhance-list")) {
            items.forEachIndexed { i, item ->
                EnhanceRow(item) { act(item) }
                if (i != items.lastIndex) Box(Modifier.fillMaxWidth().padding(start = 58.dp).height(.5.dp).background(th.hairline))
            }
        }
        Text(if (metered) "Données mobiles : chaque téléchargement vous est confirmé avec sa taille. Il continue ensuite en arrière-plan."
            else "Les téléchargements continuent en arrière-plan. Tout se gère aussi dans les réglages.",
            color = th.secondary, fontSize = 13.sp, lineHeight = 18.sp,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).testTag("enhance-footnote"))
    }
    confirm?.let { item ->
        AppAlertDialog(onDismissRequest = { confirm = null },
            title = { Text("Télécharger sur les données mobiles ?") },
            text = { Text(AppText.t("« {0} » pèse {1}. Le Wi-Fi évite d'entamer votre forfait.")
                .replace("{0}", AppText.t(Enhancements.title(item.kind))).replace("{1}", Enhancements.megabytes(item.bytes)), localize = false) },
            confirmButton = { KetTextButton("Télécharger", { confirm = null; Enhancements.pack(item.kind)?.download() }, primary = true) },
            dismissButton = { KetTextButton("Annuler", { confirm = null }) })
    }
}

@Composable
private fun EnhanceRow(item: Enhancement, onAction: () -> Unit) {
    val th = LocalTheme.current
    val icon = when (item.kind) {
        EnhanceKind.VoiceQuick -> KetIcons.Audio
        EnhanceKind.VoiceHq -> KetIcons.Sliders
        EnhanceKind.Rhythm -> KetIcons.Clock
        EnhanceKind.LocalMusic -> KetIcons.Folder
    }
    // The text has the row's whole width; its size and its button share the line under it, as in the App Store.
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 12.dp, top = 14.dp, bottom = 8.dp).testTag("enhance-${item.kind.name}")) {
        Box(Modifier.padding(top = 2.dp)) { com.ketvoice.app.ui.settings.Ket27Tile(icon, item.kind.ordinal * 3 + 1) }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(Enhancements.title(item.kind), color = th.onBackground, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
            Text(Enhancements.what(item.kind), color = th.secondary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 2.dp))
            val meta = when {
                item.kind == EnhanceKind.LocalMusic && item.state is EnhanceState.Done && item.state.count != null && item.state.count > 0 ->
                    AppText.t("{0} titres").replace("{0}", item.state.count.toString())
                item.kind == EnhanceKind.LocalMusic && item.state == EnhanceState.Failed -> AppText.t("Autorisation refusée : choisissez plutôt un dossier.")
                item.kind == EnhanceKind.LocalMusic -> null
                item.state is EnhanceState.Failed -> AppText.t("Échec du téléchargement") + " · " + Enhancements.megabytes(item.bytes)
                else -> Enhancements.megabytes(item.bytes)
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(meta.orEmpty(), localize = false, color = th.secondary, fontSize = 13.sp, lineHeight = 18.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(end = 8.dp))
                EnhanceAction(item, onAction)
            }
            val working = item.state as? EnhanceState.Working
            if (working != null) KetProgressBar(working.fraction?.let { f -> { f } },
                Modifier.padding(bottom = 8.dp).fillMaxWidth().testTag("enhance-progress-${item.kind.name}"))
        }
    }
}

/** The App Store's small capsule: « Télécharger », the percentage while it comes, a tick once it is there. */
@Composable
private fun EnhanceAction(item: Enhancement, onAction: () -> Unit) {
    val th = LocalTheme.current
    val tag = "enhance-action-${item.kind.name}"
    when (val state = item.state) {
        is EnhanceState.Done -> Row(Modifier.heightIn(min = 44.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
            Icon(KetIcons.Check, null, tint = th.accent, modifier = Modifier.size(16.dp))
            Text(if (item.kind == EnhanceKind.LocalMusic) "Activé" else "Prêt", color = th.secondary, fontSize = 15.sp,
                modifier = Modifier.padding(start = 4.dp))
        }
        is EnhanceState.Working -> Box(Modifier.heightIn(min = 44.dp).widthIn(min = 52.dp).testTag(tag), contentAlignment = Alignment.CenterEnd) {
            Text(state.fraction?.let { "${(it * 100).toInt()} %" } ?: AppText.t("Analyse…"), localize = false,
                color = th.secondary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        else -> {
            val label = when {
                item.kind == EnhanceKind.LocalMusic && state == EnhanceState.Failed -> "Choisir un dossier"
                item.kind == EnhanceKind.LocalMusic -> "Autoriser"
                state == EnhanceState.Failed -> "Réessayer"
                else -> "Télécharger"
            }
            Box(Modifier.heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onAction).testTag(tag), contentAlignment = Alignment.Center) {
                Box(Modifier.clip(RoundedCornerShape(50)).background(th.accent.copy(alpha = if (th.isDark) .22f else .12f))
                    .padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(label, color = th.accent, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

/** « Continuer sans les améliorations ? »: what is missed, and that Settings has it all later. */
@Composable
internal fun SkipEnhancementsAlert(onContinue: () -> Unit, onBack: () -> Unit) {
    AppAlertDialog(onDismissRequest = onBack,
        modifier = Modifier.testTag("enhance-skip-alert"),
        title = { Text("Continuer sans les améliorations ?") },
        text = { Text("Sans modèle de séparation, pas de niveau de la voix ni de mélanges de voix dans Ketmix, et la musique de l'appareil reste hors de votre bibliothèque. Vous pourrez tout activer plus tard dans Réglages.") },
        confirmButton = { KetTextButton("Retour", onBack, primary = true, modifier = Modifier.testTag("enhance-skip-back")) },
        dismissButton = { KetTextButton("Continuer sans", onContinue, modifier = Modifier.testTag("enhance-skip-continue")) })
}

/** The same rows from Settings (7.6): « Améliorez votre expérience d'écoute » in a sheet. */
@Composable
internal fun EnhanceSheet(onClose: () -> Unit) {
    var refused by remember { mutableStateOf(false) }
    val items = rememberEnhancements(refused)
    com.ketvoice.app.ui.settings.SettingsSheet(onDismiss = onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max = com.ketvoice.app.ui.common.sheetMaxHeight())
            .verticalScroll(rememberScrollState()).padding(horizontal = com.ketvoice.app.theme.Ket27.Metrics.margin, vertical = 16.dp)
            .testTag("enhance-sheet")) {
            com.ketvoice.app.ui.settings.SettingsSheetHeader("Améliorez votre expérience d'écoute",
                "Des modèles d'IA qui travaillent sur le téléphone, et votre propre musique. Tout est facultatif.", onClose)
            Box(Modifier.height(18.dp))
            EnhanceList(items, onRefused = { refused = it })
        }
    }
}
