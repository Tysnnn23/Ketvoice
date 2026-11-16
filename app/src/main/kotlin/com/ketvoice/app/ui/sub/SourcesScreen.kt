package com.ketvoice.app.ui.sub

import com.ketvoice.app.theme.pageBackground
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ketvoice.app.data.CustomSource
import com.ketvoice.app.data.CustomSources
import com.ketvoice.app.data.KetvoiceApi
import com.ketvoice.app.data.SourcePriority
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.T
import com.ketvoice.app.theme.Ket27
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import com.ketvoice.app.ui.AppUi
import kotlinx.coroutines.launch

/**
 * Écran de gestion des sources personnalisées `.json`.
 * Chaque source est réellement téléchargée, parsée puis testée via son
 * endpoint de recherche avant d'être marquée active.
 */
@Composable
fun SourcesScreen() {
    val th = LocalTheme.current
    val sources by CustomSources.sources.collectAsState()
    var mode by remember { mutableStateOf(SourcePriority.Mode.PLAYBACK) }
    val order by SourcePriority.orderFlow(mode).collectAsState()
    val disabled by SourcePriority.disabledFlow(mode).collectAsState()
    val entries = remember(order, disabled, sources, mode) { SourcePriority.entries(mode) }
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var qualityFor by remember { mutableStateOf<CustomSource?>(null) }
    var deleteFor by remember { mutableStateOf<CustomSource?>(null) }
    var refreshing by remember { mutableStateOf<String?>(null) }
    var flash by remember { mutableStateOf<String?>(null) }
    var apiTesting by remember { mutableStateOf(false) }
    var apiOk by remember { mutableStateOf<Boolean?>(null) }

    Column(Modifier.fillMaxSize().pageBackground(th).statusBarsPadding()) {
        // Ket 27: the bar of a pushed page, with the page's one action at its end.
        com.ketvoice.app.ui.common.Ket27PageBar("Sources", { AppUi.closeOverlay() }) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable { showAdd = true }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(th.accent), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, "Ajouter", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
        LazyColumn(contentPadding = PaddingValues(start = com.ketvoice.app.ui.common.pageMargin, end = com.ketvoice.app.ui.common.pageMargin, bottom = 160.dp)) {
            item {
                Text(
                    "Glissez les poignées pour définir la priorité de lecture et de téléchargement. " +
                        "Sans qualité progressive : la première source disponible dans cet ordre. " +
                        "En progressif : recherches parallèles, premier flux audio validé, puis amélioration de la lecture si une meilleure qualité arrive. " +
                        "Pour du sans perte, placez « sans perte » avant le MP3 : l'ordre l'emporte sur le réglage de qualité.",
                    color = th.secondary, style = T.sub, lineHeight = 18.sp,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )
                if (flash != null) {
                    Text(flash.orEmpty(), color = th.accent, fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 12.dp))
                }
                KetvoiceApiCard(
                    testing = apiTesting,
                    lastTestOk = apiOk,
                    onTest = {
                        if (!apiTesting) {
                            apiTesting = true
                            scope.launch {
                                val ok = KetvoiceApi.health()
                                apiOk = ok
                                apiTesting = false
                                flash = if (ok) "Ketvoice API répond correctement ✓"
                                else "Ketvoice API est indisponible pour le moment."
                            }
                        }
                    }
                )
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(SourcePriority.Mode.PLAYBACK to "Lecture", SourcePriority.Mode.DOWNLOAD to "Téléchargement").forEach { (value, label) ->
                        val selected = mode == value
                        Text(label, color = if (selected) Color.White else th.secondary, fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp))
                                .background(if (selected) th.accent else th.card)
                                .clickable { mode = value }.padding(horizontal = 14.dp, vertical = 9.dp))
                    }
                }
                Text("Ordre ${if (mode == SourcePriority.Mode.PLAYBACK) "de lecture" else "de téléchargement"} : glissez les poignées, activez ou désactivez chaque source. Chaque mode garde son propre ordre.",
                    color = th.secondary, style = T.caption, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 12.dp))
            }
            if (sources.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                        .background(th.card).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(54.dp).clip(CircleShape).background(th.accent.copy(alpha = .12f)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Tune, null, tint = th.accent, modifier = Modifier.size(26.dp))
                        }
                        Text("Aucune source personnalisée", color = th.onBackground, fontWeight = FontWeight.Bold,
                            fontSize = 17.sp, modifier = Modifier.padding(top = 12.dp))
                        Text("Ketvoice API et YouTube Music restent disponibles.",
                            color = th.secondary, style = T.sub, modifier = Modifier.padding(top = 5.dp))
                        Text("Ajouter une source .json", color = Color.White, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(th.accent)
                                .clickable { showAdd = true }.padding(horizontal = 18.dp, vertical = 10.dp)
                                .padding(top = 0.dp))
                    }
                }
            }
            itemsIndexed(entries, key = { _, it -> it.id }) { position, entry ->
                val source = sources.firstOrNull { "cs:${it.id}" == entry.id }
                if (source == null) {
                    IntegratedSourceCard(
                        entry,
                        position,
                        onMove = { SourcePriority.move(entry.id, it, mode) },
                        onToggle = { SourcePriority.setEnabled(entry.id, !entry.enabled, mode) }
                    )
                } else {
                    SourceCard(
                        source = source,
                        position = position,
                        checked = entry.enabled,
                        onMove = { delta -> SourcePriority.move(entry.id, delta, mode) },
                        refreshing = refreshing == source.id,
                        onToggle = {
                            val target = !entry.enabled
                            if (target && !source.enabled) CustomSources.setEnabled(source.id, true)
                            SourcePriority.setEnabled("cs:${source.id}", target, mode)
                        },
                        onQuality = { qualityFor = source },
                        onRefresh = {
                            refreshing = source.id
                            scope.launch {
                                val result = CustomSources.refresh(source.id)
                                refreshing = null
                                flash = if (result.isSuccess) "« ${result.getOrNull()?.name} » est à jour."
                                else "Échec du test : ${result.exceptionOrNull()?.message ?: "manifeste invalide"}"
                            }
                        },
                        onDelete = { deleteFor = source }
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            item {
                Spacer(Modifier.height(12.dp))
                com.ketvoice.app.ui.common.SectionHeader("Format d'un manifeste")
                Spacer(Modifier.height(8.dp))
                Text(
                    "{\n" +
                        "  \"name\": \"Ma source\",\n" +
                        "  \"baseUrl\": \"https://api.exemple.com\",\n" +
                        "  \"search\": \"/search?q={query}\",\n" +
                        "  \"stream\": \"/stream/{id}?quality={quality}\"\n" +
                        "}",
                    color = th.onBackground, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(th.card).padding(14.dp)
                )
                Text(
                    "Recherche attendue : {\"tracks\":[{\"id\",\"title\",\"artist\",\"album\",\"duration\",\"artworkURL\",\"streamURL\"}]}\n" +
                        "Stream attendu : {\"url\":\"https://…\",\"format\":\"flac\"} ou une URL directe.\n" +
                        "Les manifestes d'addon BitChord (manifest.json, resources, qualities) sont reconnus. " +
                        "« allowDownloads » à 0 interdit le téléchargement.",
                    color = th.secondary, style = T.caption, lineHeight = 16.sp,
                    modifier = Modifier.padding(top = 9.dp)
                )
            }
        }
    }

    if (showAdd) {
        AddSourceDialog(onDismiss = { showAdd = false }, onAdded = { name ->
            showAdd = false
            flash = "Source « $name » ajoutée et validée."
        })
    }
    qualityFor?.let { source ->
        // Ket 27: a short choice on the dialog card — an inset group of rows, the choice ticked in the app's colour.
        com.ketvoice.app.ui.common.Ket27DialogCard({ qualityFor = null }, Modifier.testTag("source-quality")) {
            Text("Qualité demandée", color = th.onBackground, style = Ket27.Type.headline,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text("La source reste maîtresse du flux renvoyé.", color = th.secondary, style = Ket27.Type.footnote,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(th.onBackground.copy(alpha = .06f))) {
                listOf("high" to "Haute qualité", "low" to "Qualité économique").forEachIndexed { index, (value, label) ->
                    if (index > 0) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(.5.dp).background(th.hairline))
                    Row(Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.RadioButton) {
                            CustomSources.setQuality(source.id, value)
                            qualityFor = null
                        }.heightIn(min = Ket27.Metrics.row).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = th.onBackground, style = Ket27.Type.body, modifier = Modifier.weight(1f))
                        if (source.quality == value) Icon(com.ketvoice.app.ui.common.KetIcons.Check, null, tint = th.accent,
                            modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
    deleteFor?.let { source ->
        com.ketvoice.app.ui.common.AppAlertDialog(onDismissRequest = { deleteFor = null },
            title = { Text("Supprimer la source ?") },
            text = { Text(source.name, localize = false) },
            confirmButton = { com.ketvoice.app.ui.common.KetTextButton("Supprimer", {
                CustomSources.remove(source.id)
                deleteFor = null
                flash = "Source supprimée."
            }, primary = true, destructive = true) },
            dismissButton = { com.ketvoice.app.ui.common.KetTextButton("Annuler", { deleteFor = null }) })
    }
}

@Composable
private fun IntegratedSourceCard(entry: SourcePriority.Entry, position: Int, onMove: (Int) -> Unit, onToggle: () -> Unit) {
    val th = LocalTheme.current
    var dragged by remember(entry.id) { mutableFloatStateOf(0f) }
    val step = with(LocalDensity.current) { 70.dp.toPx() }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(th.card).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).pointerInput(entry.id) {
            detectDragGestures(onDragEnd = { dragged = 0f }, onDragCancel = { dragged = 0f }) { change, amount ->
                change.consume(); dragged += amount.y
                while (dragged > step) { onMove(1); dragged -= step }
                while (dragged < -step) { onMove(-1); dragged += step }
            }
        }, contentAlignment = Alignment.Center) { Icon(Icons.Filled.DragHandle, "Réordonner la source", tint = th.secondary) }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(entry.name, localize = false, color = th.onBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text("Priorité ${position + 1}", color = th.secondary, style = T.caption)
        }
        CustomSwitch(checked = entry.enabled, onToggle = onToggle)
    }
}

@Composable
private fun KetvoiceApiCard(
    testing: Boolean,
    lastTestOk: Boolean?,
    onTest: () -> Unit
) {
    val th = LocalTheme.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(th.card).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(th.accent.copy(alpha = .12f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Tune, null, tint = th.accent, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(KetvoiceApi.DISPLAY_NAME, color = th.onBackground, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Recherche, streaming et canvas · Supabase",
                    color = th.secondary, style = T.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("ACTIF", color = th.accent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(5.dp))
                    .background(th.accent.copy(alpha = .12f))
                    .padding(horizontal = 7.dp, vertical = 4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (lastTestOk) {
                    true -> "Serveur et secrets validés"
                    false -> "Test impossible pour le moment"
                    null -> "Aucun réglage de cookie ou de jeton requis"
                },
                color = th.secondary, fontSize = 12.sp
            )
            Spacer(Modifier.weight(1f))
            if (testing) {
                com.ketvoice.app.ui.common.ActivitySpinner(size = 18.dp)
            } else {
                Text("Tester", color = th.accent, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                    modifier = Modifier.clickable(onClick = onTest).padding(horizontal = 6.dp, vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun SourceCard(
    source: CustomSource,
    position: Int,
    checked: Boolean = true,
    onMove: (Int) -> Unit,
    refreshing: Boolean,
    onToggle: () -> Unit,
    onQuality: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit
) {
    val th = LocalTheme.current
    var dragged by remember(source.id) { mutableFloatStateOf(0f) }
    val step = with(LocalDensity.current) { 58.dp.toPx() }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(th.card).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.DragHandle, "Réordonner la source", tint = th.secondary,
                modifier = Modifier.size(24.dp).padding(end = 6.dp).pointerInput(source.id) {
                    detectDragGestures(
                        onDragEnd = { dragged = 0f },
                        onDragCancel = { dragged = 0f },
                        onDrag = { change, amount ->
                            change.consume()
                            dragged += amount.y
                            while (dragged > step) { onMove(1); dragged -= step }
                            while (dragged < -step) { onMove(-1); dragged += step }
                        }
                    )
                })
            Box(Modifier.size(42.dp).clip(CircleShape).background(th.accent.copy(alpha = .12f)),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Tune, null, tint = th.accent, modifier = Modifier.size(21.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(source.name, color = th.onBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Priorité ${position + 1} · " + runCatching { java.net.URL(source.manifestUrl).host }
                    .getOrDefault(source.manifestUrl),
                    color = th.secondary, style = T.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CustomSwitch(checked = checked, onToggle = onToggle)
        }
        if (source.error != null) {
            Text(source.error, color = Color(0xFFFF3B30), style = T.caption,
                modifier = Modifier.padding(top = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (source.quality == "high") "Haute qualité" else "Économique",
                color = th.secondary, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(th.background.copy(alpha = .55f))
                    .clickable(onClick = onQuality).padding(horizontal = 9.dp, vertical = 6.dp))
            Spacer(Modifier.weight(1f))
            if (refreshing) com.ketvoice.app.ui.common.ActivitySpinner(size = 18.dp)
            else Icon(Icons.Filled.Refresh, "Tester", tint = th.secondary,
                modifier = Modifier.size(20.dp).clickable(onClick = onRefresh).padding(1.dp))
            Spacer(Modifier.width(14.dp))
            Icon(Icons.Filled.DeleteOutline, "Supprimer", tint = th.secondary,
                modifier = Modifier.size(21.dp).clickable(onClick = onDelete))
        }
    }
}

@Composable
private fun CustomSwitch(checked: Boolean, onToggle: () -> Unit) {
    // The same switch as everywhere else in the app.
    com.ketvoice.app.ui.common.LiquidGlassSwitch(checked, { onToggle() })
}

@Composable
private fun AddSourceDialog(onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val th = LocalTheme.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Ket 27: the dialog card of an iOS alert with a field — title and message centred, the field in a
    // rounded well, Annuler and Valider as the alert's two capsules.
    com.ketvoice.app.ui.common.Ket27DialogCard({ if (!busy) onDismiss() }, Modifier.testTag("add-source")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("Ajouter une source", color = th.onBackground, style = Ket27.Type.headline,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            Text("URL HTTPS du manifeste, terminée par .json", color = th.secondary, style = Ket27.Type.footnote,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp))
            BasicTextField(
                value = url,
                onValueChange = { url = it.trim(); error = null },
                singleLine = false,
                textStyle = TextStyle(color = th.onBackground, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(th.accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                    .background(th.onBackground.copy(alpha = .07f)).padding(12.dp),
                decorationBox = { inner ->
                    if (url.isEmpty()) Text("https://exemple.com/source.json",
                        color = th.secondary.copy(alpha = .7f), fontSize = 14.sp)
                    inner()
                }
            )
            if (error != null) {
                Text(error.orEmpty(), color = com.ketvoice.app.ui.common.DESTRUCTIVE, style = Ket27.Type.footnote,
                    modifier = Modifier.padding(top = 8.dp))
            }
            com.ketvoice.app.ui.common.Ket27AlertButtons(confirmButton = {
                if (busy) Box(Modifier.heightIn(min = 44.dp), contentAlignment = Alignment.Center) { com.ketvoice.app.ui.common.ActivitySpinner(size = 17.dp) }
                else com.ketvoice.app.ui.common.KetTextButton("Valider", {
                    busy = true
                    scope.launch {
                        val result = CustomSources.add(url)
                        busy = false
                        val source = result.getOrNull()
                        if (source != null) onAdded(source.name)
                        else error = result.exceptionOrNull()?.message ?: "Source refusée"
                    }
                }, primary = true, enabled = url.isNotBlank())
            }, dismissButton = { com.ketvoice.app.ui.common.KetTextButton("Annuler", { onDismiss() }, enabled = !busy) },
                modifier = Modifier.padding(top = 18.dp))
        }
    }
}
