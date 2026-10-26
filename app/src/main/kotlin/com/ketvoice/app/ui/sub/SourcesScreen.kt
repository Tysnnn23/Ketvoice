package com.ketvoice.app.ui.sub

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.T
import com.ketvoice.app.ui.AppUi
import kotlinx.coroutines.launch

@Composable
fun SourcesScreen() {
    val th = LocalTheme.current
    val sources by CustomSources.sources.collectAsState()
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var qualityFor by remember { mutableStateOf<CustomSource?>(null) }
    var deleteFor by remember { mutableStateOf<CustomSource?>(null) }
    var refreshing by remember { mutableStateOf<String?>(null) }
    var flash by remember { mutableStateOf<String?>(null) }
    var apiTesting by remember { mutableStateOf(false) }
    var apiOk by remember { mutableStateOf<Boolean?>(null) }

    Column(Modifier.fillMaxSize().background(th.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = th.onBackground,
                modifier = Modifier.size(30.dp).clickable { AppUi.back() })
            Spacer(Modifier.width(12.dp))
            Text("Sources", color = th.onBackground, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            Box(Modifier.size(38.dp).clip(CircleShape).background(th.accent)
                .clickable { showAdd = true }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, "Ajouter", tint = Color.White, modifier = Modifier.size(22.dp))
            }
        }
        LazyColumn(contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 160.dp)) {
            item {
                Text(
                    "Ketvoice API est la source intégrée : recherche, streaming et canvas passent par Supabase, " +
                        "sans cookie ni jeton dans l'application. Vous pouvez aussi ajouter des manifestes .json.",
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
            items(sources, key = { it.id }) { source ->
                SourceCard(
                    source = source,
                    refreshing = refreshing == source.id,
                    onToggle = { CustomSources.setEnabled(source.id, !source.enabled) },
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
                Spacer(Modifier.height(12.dp))
            }
            item {
                Spacer(Modifier.height(12.dp))
                Text("Format d'un manifeste", style = T.section, color = th.onBackground)
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
        Dialog(onDismissRequest = { qualityFor = null }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(th.background).padding(20.dp)) {
                Text("Qualité demandée", color = th.onBackground, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text("La source reste maîtresse du flux renvoyé.",
                    color = th.secondary, style = T.caption, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                listOf("high" to "Haute qualité", "low" to "Qualité économique").forEach { (value, label) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable {
                            CustomSources.setQuality(source.id, value)
                            qualityFor = null
                        }.padding(horizontal = 12.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(label, color = th.onBackground, fontSize = 16.sp, modifier = Modifier.weight(1f))
                        if (source.quality == value) Text("✓", color = th.accent, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
    deleteFor?.let { source ->
        Dialog(onDismissRequest = { deleteFor = null }) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp))
                .background(th.background).padding(22.dp)) {
                Text("Supprimer la source ?", color = th.onBackground, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                Text(source.name, color = th.secondary, style = T.sub, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.End) {
                    Text("Annuler", color = th.secondary, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { deleteFor = null }.padding(10.dp))
                    Text("Supprimer", color = Color(0xFFFF3B30), fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            CustomSources.remove(source.id)
                            deleteFor = null
                            flash = "Source supprimée."
                        }.padding(10.dp))
                }
            }
        }
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
                CircularProgressIndicator(color = th.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
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
    refreshing: Boolean,
    onToggle: () -> Unit,
    onQuality: () -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit
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
                Text(source.name, color = th.onBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(runCatching { java.net.URL(source.manifestUrl).host }.getOrDefault(source.manifestUrl),
                    color = th.secondary, style = T.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CustomSwitch(checked = source.enabled, onToggle = onToggle)
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
            if (refreshing) CircularProgressIndicator(color = th.accent, strokeWidth = 2.dp,
                modifier = Modifier.size(18.dp))
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
    val th = LocalTheme.current
    val offset by animateDpAsState(if (checked) 19.dp else 1.dp, spring(dampingRatio = .82f, stiffness = 500f))
    val color by animateColorAsState(if (checked) th.accent else th.hairline)
    Box(Modifier.width(43.dp).height(25.dp).clip(RoundedCornerShape(999.dp))
        .background(color).clickable(onClick = onToggle).padding(2.dp)) {
        Box(Modifier.offset(x = offset).size(21.dp).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun AddSourceDialog(onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val th = LocalTheme.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(th.background).padding(20.dp)
            .verticalScroll(rememberScrollState())) {
            Text("Ajouter une source", color = th.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("URL HTTPS du manifeste, terminée par .json",
                color = th.secondary, style = T.caption, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            BasicTextField(
                value = url,
                onValueChange = { url = it.trim(); error = null },
                singleLine = false,
                textStyle = TextStyle(color = th.onBackground, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(13.dp))
                    .background(th.card).padding(13.dp),
                decorationBox = { inner ->
                    if (url.isEmpty()) Text("https://exemple.com/source.json",
                        color = th.secondary.copy(alpha = .7f), fontSize = 14.sp)
                    inner()
                }
            )
            if (error != null) {
                Text(error.orEmpty(), color = Color(0xFFFF3B30), style = T.caption,
                    modifier = Modifier.padding(top = 8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically) {
                Text("Annuler", color = th.secondary, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(enabled = !busy) { onDismiss() }.padding(10.dp))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.clip(RoundedCornerShape(999.dp)).background(
                    if (busy) th.hairline else th.accent).clickable(enabled = !busy) {
                    if (url.isBlank() || busy) return@clickable
                    busy = true
                    scope.launch {
                        val result = CustomSources.add(url)
                        busy = false
                        val source = result.getOrNull()
                        if (source != null) onAdded(source.name)
                        else error = result.exceptionOrNull()?.message ?: "Source refusée"
                    }
                }.padding(horizontal = 18.dp, vertical = 10.dp)) {
                    if (busy) CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp,
                        modifier = Modifier.size(17.dp))
                    else Text("Valider", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}
