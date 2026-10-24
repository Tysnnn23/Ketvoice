package com.ketvoice.app.ui.settings

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Wifi
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ketvoice.app.data.AppLanguage
import com.ketvoice.app.data.AppSettings
import com.ketvoice.app.data.CustomSources
import com.ketvoice.app.data.HomeFeed
import com.ketvoice.app.data.LyricsRepo
import com.ketvoice.app.data.MusicApi
import com.ketvoice.app.data.SettingsStore
import com.ketvoice.app.data.TranslationLanguages
import com.ketvoice.app.data.YouTubeMusicSession
import com.ketvoice.app.player.AudioCache
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.Palette
import com.ketvoice.app.theme.T
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.util.LauncherIcons
import kotlinx.coroutines.launch

private val noRip: MutableInteractionSource = MutableInteractionSource()

/**
 * RÉGLAGES — uniquement des réglages RELIÉS à l'app :
 *  - thème / verre / animations → Glass + transitions,
 *  - qualité Wi-Fi / données mobiles → choix réel du flux (FLAC ↔ MP3),
 *  - ignorer les silences → moteur Media3,
 *  - mode des paroles → LyricsRepo réel,
 *  - canvas → Ketvoice API + test pipeline réel,
 *  - téléchargements → FLAC/MP3 du downloader,
 *  - stockage → taille réelle du cache + purge.
 */
@Composable
fun SettingsScreen() {
    val th = LocalTheme.current
    val ctx = LocalContext.current
    var editSetting by remember { mutableStateOf("") }
    var accentOpen by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf<ChoiceSpec?>(null) }
    val settingsScope = rememberCoroutineScope()
    val youtube by YouTubeMusicSession.profile.collectAsState()
    val publisher by com.ketvoice.app.data.PublisherSession.allowed.collectAsState()
    val customSources by CustomSources.sources.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .background(th.background)
    ) {
        // ---------- En-tête ----------
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = 14.dp, end = 16.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            com.ketvoice.app.ui.common.GlassBackButton { AppUi.overlay.value = AppUi.NONE }
            Spacer(Modifier.width(12.dp))
            Text("Réglages", style = T.hugeTitle, color = th.onBackground, fontSize = 30.sp)
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 140.dp)
        ) {
            val testing = remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val cacheUsedMb = remember { mutableIntStateOf(0) }
            LaunchedEffect(Unit) {
                cacheUsedMb.intValue = (AudioCache.sizeBytes(ctx) / 1_048_576L).toInt()
            }

            // ==================================================== YOUTUBE MUSIC
            if (com.ketvoice.app.BuildConfig.IS_QUEST) {
                Section("QUEST · CONFORT") {
                    SToggle(Icons.Filled.Brightness6, "Garder la fenêtre éveillée",
                        "Pendant la lecture au premier plan uniquement",
                        AppSettings.questKeepAwake.value) { AppSettings.questKeepAwake.value = it; SettingsStore.save() }
                }
            }
            Section("YOUTUBE MUSIC") {
                SRow(
                    Icons.Filled.PlayCircle, "Compte YouTube Music",
                    sub = when {
                        youtube.connected -> "${youtube.name} · session validée"
                        else -> "Connexion web pour l'historique et l'accueil personnel"
                    },
                    value = if (youtube.connected) "Connecté" else "Se connecter"
                ) { AppUi.showAccount.value = true }
                SToggle(
                    Icons.Filled.Sync, "Synchroniser les écoutes",
                    "Une écoute n'est validée que si YouTube l'accepte",
                    AppSettings.syncYouTubeHistory.value
                ) { AppSettings.syncYouTubeHistory.value = it; SettingsStore.save() }
                SToggle(
                    Icons.Filled.PlayCircle, "Accueil personnalisé",
                    "Remplace les sélections locales par votre accueil YouTube Music",
                    AppSettings.personalizedYouTubeHome.value
                ) {
                    AppSettings.personalizedYouTubeHome.value = it
                    SettingsStore.save()
                    if (it && youtube.connected) HomeFeed.refreshYouTube() else if (!it) HomeFeed.clearPersonalized()
                }
            }

            if (publisher || (youtube.connected && com.ketvoice.app.data.PublisherAccess.matches(youtube.email))) {
                Section("PUBLICATIONS KETVOICE API") {
                    if (!publisher) {
                        SRow(Icons.Filled.CloudDownload, "Vérifier mon accès",
                            sub = "Confirmez votre adresse par un lien de connexion à usage unique", value = "") {
                            AppUi.openPublisher()
                        }
                    } else {
                        SRow(Icons.Filled.CloudDownload, "Importer un son",
                            sub = "Publier un fichier audio, sa pochette et ses paroles pour tous", value = "") {
                            AppUi.openPublisher()
                        }
                        SRow(Icons.Filled.Mic, "Importer des lyrics",
                            sub = "Recherchez un titre puis ouvrez son menu ⋯ pour publier un TTML ou LRC", value = "") {
                            AppUi.searchSeed.value = ""
                            AppUi.overlay.value = AppUi.SEARCH
                        }
                        SRow(Icons.Filled.Person, "Déconnecter la publication", sub = "", value = "") {
                            com.ketvoice.app.data.PublisherSession.signOut()
                        }
                    }
                }
            }

            // ==================================================== LANGUE
            Section("LANGUE DE L'APPLICATION") {
                val currentLanguage = AppLanguage.fromLabel(AppSettings.language.value)
                SRow(
                    Icons.Filled.Language, "Langue de l'application",
                    sub = "Traduit toute l'interface, pas seulement les réglages",
                    value = currentLanguage.nativeName
                ) {
                    choice = ChoiceSpec(
                        "Langue de l'application",
                        "Choisissez la langue de tous les écrans de Ketvoice.",
                        currentLanguage.label,
                        AppLanguage.all.map { language ->
                            ChoiceOption(
                                language.label,
                                language.nativeName,
                                if (language == AppLanguage.FRENCH) "Langue d'origine"
                                else "${language.englishName} · interface complète"
                            )
                        }
                    ) { id ->
                        AppSettings.language.value = id
                        SettingsStore.save()
                        com.ketvoice.app.data.AppLocales.apply(ctx, AppLanguage.fromLabel(id))
                    }
                }
            }

            // ==================================================== APPARENCE
            Section("DESIGN KETVOICE") {
                Text("Chaque finition peut être retirée séparément, sans modifier vos playlists ni votre musique.", color = th.secondary)
                com.ketvoice.app.data.DesignChoices.all.forEach { option ->
                    SToggle(Icons.Filled.Tune, option.title, "Revenir à la présentation précédente en désactivant cette option",
                        com.ketvoice.app.data.DesignChoices.enabled(option.code)) {
                        com.ketvoice.app.data.DesignChoices.set(option.code, it); SettingsStore.save()
                    }
                }
            }
            Section("APPARENCE") {
                SToggle(Icons.Filled.ColorLens, "Couleurs de la pochette", "Accents du lecteur adaptés à l'album, avec contraste renforcé",
                    AppSettings.albumColors.value) { AppSettings.albumColors.value = it; SettingsStore.save() }
                SToggle(Icons.Filled.Videocam, "Profondeur de pochette", "Mouvement léger au toucher ou au pointeur, sans modifier les gestes",
                    AppSettings.artworkDepth.value) { AppSettings.artworkDepth.value = it; SettingsStore.save() }
                SToggle(Icons.Filled.Videocam, "Canvas panoramique",
                    "Optionnel : Canvas en fond assombri, pochette fixe pour éviter un double décodage",
                    AppSettings.panoramicCanvas.value) { AppSettings.panoramicCanvas.value=it;SettingsStore.save() }
                SToggle(Icons.Filled.Bolt, "Animations réduites", "Atténue les mouvements décoratifs, sans désactiver le karaoké",
                    AppSettings.reduceAnimations.value) { AppSettings.reduceAnimations.value=it;SettingsStore.save() }
                SRow(Icons.Filled.Subtitles, "Taille des paroles", sub="Lisibilité sans agrandir les boutons",
                    value=listOf("Standard","Grand","Très grand")[AppSettings.lyricsTextSize.value]) {
                    AppSettings.lyricsTextSize.value=(AppSettings.lyricsTextSize.value+1)%3;SettingsStore.save()
                }
                SRow(
                    Icons.Filled.DarkMode, "Thème",
                    sub = "S'applique immédiatement à toute l'app",
                    value = themeLabel(AppSettings.themeMode.value)
                ) {
                    choice = ChoiceSpec(
                        "Thème", "Choisissez l'apparence de Ketvoice.",
                        AppSettings.themeMode.value.toString(),
                        listOf(
                            ChoiceOption("0", "Système", "Suit le réglage Android"),
                            ChoiceOption("1", "Clair", "Fond blanc, verre lumineux"),
                            ChoiceOption("2", "Sombre", "Fond noir, verre fumé")
                        )
                    ) { id ->
                        AppSettings.themeMode.value = id.toIntOrNull() ?: 0
                        SettingsStore.save()
                    }
                }
                SRow(
                    Icons.Filled.ColorLens, "Couleur d'accent",
                    sub = "Barre de lecture, scrub, boutons et progression",
                    value = accentLabel(AppSettings.accentArgb.value)
                ) { accentOpen = true }
                SToggle(
                    Icons.Filled.Brightness6, "Verre liquide",
                    "Flou, réfraction et reflets sur l'interface",
                    AppSettings.liquidGlass.value
                ) { AppSettings.liquidGlass.value = it; SettingsStore.save() }
                SToggle(
                    Icons.Filled.Bolt, "Mode performance",
                    "Allège les effets de verre sur les appareils modestes",
                    AppSettings.highPerformanceMode.value
                ) { AppSettings.highPerformanceMode.value = it; SettingsStore.save() }
                SToggle(
                    Icons.Filled.Brightness6, "Flou dynamique",
                    "Réfraction en temps réel derrière les panneaux",
                    !AppSettings.reduceDynamicBlur.value
                ) { AppSettings.reduceDynamicBlur.value = !it; SettingsStore.save() }
                SRow(
                    Icons.Filled.Image, "Icône de l'app",
                    sub = "Change réellement l'icône du lanceur",
                    value = appIconLabel(AppSettings.appIconVariant.value)
                ) {
                    choice = ChoiceSpec(
                        "Icône de l'app", "L'icône du lanceur change immédiatement.",
                        AppSettings.appIconVariant.value.toString(),
                        listOf(
                            ChoiceOption("0", "Défaut"),
                            ChoiceOption("1", "Liquid Glass"),
                            ChoiceOption("2", "Dark"),
                            ChoiceOption("3", "Classic")
                        )
                    ) { id ->
                        val next = id.toIntOrNull() ?: 0
                        AppSettings.appIconVariant.value = next
                        LauncherIcons.apply(ctx, next, notify = true)
                        SettingsStore.save()
                    }
                }
            }

            // ==================================================== LECTURE
            Section("LECTURE") {
                SRow(Icons.Filled.Tune, "Égaliseur", sub="Traitement réel de la session audio", value="") { AppUi.showEqualizer.value=true }
                SRow(Icons.Filled.Wifi, "Transfert sur le même Wi-Fi", sub="Découverte locale et confirmation sur le destinataire", value="") { AppUi.showHandoff.value=true }
                SRow(
                    Icons.Filled.Analytics, "Qualité en Wi-Fi",
                    sub = "Sans perte = FLAC, sinon MP3 rapide",
                    value = AppSettings.wifiQuality.value
                ) {
                    choice = qualityChoice("Qualité en Wi-Fi", AppSettings.wifiQuality.value) { value ->
                        AppSettings.wifiQuality.value = value
                        SettingsStore.save()
                    }
                }
                SRow(
                    Icons.Filled.Wifi, "Qualité en données mobiles",
                    sub = "Appliquée quand le réseau est facturé",
                    value = AppSettings.mobileQuality.value
                ) {
                    choice = qualityChoice("Qualité en données mobiles", AppSettings.mobileQuality.value) { value ->
                        AppSettings.mobileQuality.value = value
                        SettingsStore.save()
                    }
                }
                SToggle(
                    Icons.Filled.Bolt, "Ignorer les silences",
                    "Saute les passages silencieux (moteur audio)",
                    AppSettings.skipSilence.value
                ) { Player.setSkipSilence(it); SettingsStore.save() }
            }

            // ==================================================== PAROLES
            Section("PAROLES") {
                SRow(
                    Icons.Filled.Subtitles, "Source des paroles",
                    sub = "Priorité : syllabes natives, mots, lignes, texte",
                    value = MusicApi.sourceName(AppSettings.lyricsSource.value)
                ) {
                    val order = (listOf("auto") + MusicApi.lyricSources + "off").distinct()
                    choice = ChoiceSpec(
                        "Source des paroles",
                        "Aucun timing n'est inventé : la source doit fournir les syllabes ou les lignes.",
                        AppSettings.lyricsSource.value,
                        order.map { id -> ChoiceOption(id, MusicApi.sourceName(id)) }
                    ) { id ->
                        AppSettings.lyricsSource.value = id
                        if (id == "off") AppSettings.lyricsMode.value = 2
                        else if (AppSettings.lyricsMode.value == 2) AppSettings.lyricsMode.value = 0
                        SettingsStore.save()
                        LyricsRepo.clear()
                        Player.state.value.current?.let { LyricsRepo.load(it, force = true) }
                    }
                }
                SRow(
                    Icons.Filled.Subtitles, "Mode des paroles",
                    sub = "Syllabes ou mots seulement si la source les fournit",
                    value = lyricsModeLabel(AppSettings.lyricsMode.value)
                ) {
                    choice = ChoiceSpec(
                        "Mode des paroles", "Choisissez le rendu des paroles.",
                        AppSettings.lyricsMode.value.toString(),
                        listOf(
                            ChoiceOption("0", "Synchronisées", "Syllabes réelles si disponibles"),
                            ChoiceOption("1", "Texte simple", "Sans suivi temporel"),
                            ChoiceOption("2", "Désactivées", "Aucune parole affichée")
                        )
                    ) { id ->
                        AppSettings.lyricsMode.value = id.toIntOrNull() ?: 0
                        SettingsStore.save()
                        Player.state.value.current?.let { LyricsRepo.load(it, force = true) }
                    }
                }
                SToggle(
                    Icons.Filled.Mic, "Flouter les paroles inactives",
                    "Les lignes suivantes restent estompées",
                    AppSettings.blurInactiveLyrics.value
                ) { AppSettings.blurInactiveLyrics.value = it; SettingsStore.save() }
                SRow(Icons.Filled.Subtitles, "Clé BetterLyrics (facultative)",
                    sub = "Cache public ; certaines recherches exigent une clé",
                    value = if (AppSettings.betterLyricsKey.value.isBlank()) "Non définie" else "Définie"
                ) { editSetting = "better" }
                SRow(Icons.Filled.Subtitles, "Instance Lyrica", sub = "URL HTTPS de votre serveur Lyrica",
                    value = if (AppSettings.lyricaUrl.value.isBlank()) "À configurer" else "Configurée"
                ) { editSetting = "lyrica" }
                SRow(Icons.Filled.Subtitles, "lrc.red", sub = "Source intégrée · recherche de paroles par ISRC") {
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://lrc.red"))) }
                }
                SRow(Icons.Filled.Subtitles, "Langue d'origine (traduction)",
                    sub = "Utilisée par le service de secours MyMemory",
                    value = TranslationLanguages.name(AppSettings.translationSource.value)
                ) {
                    choice = ChoiceSpec(
                        "Langue d'origine", "Langue des paroles avant traduction.",
                        AppSettings.translationSource.value,
                        TranslationLanguages.codes.map { ChoiceOption(it, TranslationLanguages.name(it)) }
                    ) { id ->
                        AppSettings.translationSource.value = id
                        SettingsStore.save()
                    }
                }
                SRow(Icons.Filled.Subtitles, "Langue de traduction", sub = "Traduction réelle, sans timing par syllabe",
                    value = TranslationLanguages.name(AppSettings.translateLanguage.value)
                ) {
                    val current = TranslationLanguages.code(AppSettings.translateLanguage.value)
                    choice = ChoiceSpec(
                        "Langue de traduction", "La traduction s'affiche sous la ligne originale.",
                        current,
                        TranslationLanguages.codes.map { ChoiceOption(it, TranslationLanguages.name(it)) }
                    ) { id ->
                        AppSettings.translateLanguage.value = id
                        SettingsStore.save()
                    }
                }
            }

            // ==================================================== CANVAS
            Section("KETVOICE API · CANVAS") {
                SToggle(Icons.Filled.Bolt, "Sonic Canvas", "Fond réactif au signal audio réel, sans microphone. Désactivé avec les animations réduites.",
                    AppSettings.sonicCanvas.value) { AppSettings.sonicCanvas.value = it; SettingsStore.save() }
                SRow(
                    Icons.Filled.Videocam, "Ketvoice API",
                    sub = "Canvas Spotify résolu et authentifié côté Supabase",
                    value = "Intégré"
                ) {}
                SToggle(
                    Icons.Filled.Videocam, "Activer les Canvas vidéo",
                    "Coupe les vidéos et leurs téléchargements. Sonic Canvas possède son propre réglage.",
                    AppSettings.animatedArtwork.value
                ) {
                    AppSettings.animatedArtwork.value = it
                    SettingsStore.save()
                    if (it) Player.state.value.current?.let { track ->
                        settingsScope.launch { com.ketvoice.app.data.CanvasRepo.resolve(track) }
                    }
                }
                SToggle(
                    Icons.Filled.Wifi, "Canvas en données mobiles",
                    "Autorise le téléchargement des canvas hors Wi-Fi",
                    AppSettings.canvasWithMobileData.value
                ) {
                    AppSettings.canvasWithMobileData.value = it
                    SettingsStore.save()
                    if (it) Player.state.value.current?.let { track ->
                        settingsScope.launch { com.ketvoice.app.data.CanvasRepo.resolve(track) }
                    }
                }
                SRow(
                    Icons.Filled.Bolt, "Tester le canvas",
                    sub = if (testing.value) "Test en cours…" else "Vérifie le pipeline Ketvoice API",
                    value = ""
                ) {
                    if (!testing.value) {
                        testing.value = true
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            val ok = com.ketvoice.app.data.CanvasRepo.testPipeline()
                            launch(kotlinx.coroutines.Dispatchers.Main) {
                                testing.value = false
                                android.widget.Toast.makeText(
                                    ctx,
                                    com.ketvoice.app.data.AppText.t(if (ok) "Canvas prêt : Ketvoice API fonctionne ✓"
                                    else "Échec : vérifie la connexion à Ketvoice API"),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            }

            Section("SOURCES") {
                SRow(
                    Icons.Filled.Tune, "Ketvoice API",
                    sub = "Recherche, streaming et téléchargements · Supabase",
                    value = "Intégré"
                ) { AppUi.overlay.value = AppUi.SOURCES }
                SRow(
                    Icons.Filled.Tune, "Sources personnalisées",
                    sub = "URLs HTTPS terminées par .json · streaming et téléchargements",
                    value = if (customSources.isEmpty()) "Aucune" else "${customSources.size} source(s)"
                ) { AppUi.overlay.value = AppUi.SOURCES }
                Text(
                    "Ketvoice API protège les identifiants côté Supabase. Aucun cookie Spotify " +
                        "ni jeton d'addon n'est stocké dans l'application.",
                    color = th.secondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp)
                )
            }

            // ==================================================== TÉLÉCHARGEMENTS
            Section("TÉLÉCHARGEMENTS") {
                SRow(
                    Icons.Filled.CloudDownload, "Qualité de téléchargement",
                    sub = "Les fichiers restent hors ligne dans l'app",
                    value = if (AppSettings.downloadQualityMode.value == 0)
                        "FLAC · sans perte" else "MP3 · rapide"
                ) {
                    choice = ChoiceSpec(
                        "Qualité de téléchargement",
                        "Le repli sur l'autre qualité reste automatique si le flux échoue.",
                        AppSettings.downloadQualityMode.value.toString(),
                        listOf(
                            ChoiceOption("0", "FLAC · sans perte", "Fichier plus lourd (~35 Mo)"),
                            ChoiceOption("1", "MP3 · rapide", "Téléchargement plus léger (~2 Mo)")
                        )
                    ) { id ->
                        AppSettings.downloadQualityMode.value = id.toIntOrNull() ?: 0
                        SettingsStore.save()
                    }
                }
                SRow(
                    Icons.Filled.Image, "Voir la liste",
                    sub = "Pistes enregistrées sur l'appareil",
                    value = ""
                ) { AppUi.overlay.value = AppUi.DOWNLOADS }
            }

            // ==================================================== STOCKAGE
            Section("STOCKAGE") {
                SRow(
                    Icons.Filled.Storage, "Cache des chansons utilisé",
                    sub = "Le cache accélère les relectures",
                    value = "${cacheUsedMb.intValue} Mo"
                ) {}
                CacheLimitRow()
                SRow(
                    Icons.Filled.Delete, "Vider le cache des chansons",
                    sub = "Les paroles et canvas téléchargés restent"
                ) {
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AudioCache.clear(ctx) }
                        cacheUsedMb.intValue = (AudioCache.sizeBytes(ctx) / 1_000_000L).toInt()
                    android.widget.Toast.makeText(ctx, com.ketvoice.app.data.AppText.t("Cache des chansons vidé"), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                SRow(
                    Icons.Filled.Image, "Vider le cache des images",
                    sub = "Pochettes et photos d'artistes re-téléchargées à la demande"
                ) {
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching {
                                coil.Coil.imageLoader(ctx).let { it.diskCache?.clear(); it.memoryCache?.clear() }
                            }
                        }
                        android.widget.Toast.makeText(ctx, com.ketvoice.app.data.AppText.t("Cache des images vidé"), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // ==================================================== À PROPOS
            Section("À PROPOS") {
                SRow(
                    Icons.Filled.Image, "Version",
                    sub = "YouTube Music · Ketvoice API · canvas · paroles",
                    value = com.ketvoice.app.BuildConfig.VERSION_NAME + if (com.ketvoice.app.BuildConfig.IS_QUEST) " · Quest" else ""
                ) {}
            }

            Text(
                "Ketvoice API (Supabase) gère les sources et les canvas. " +
                    "Aucun cookie Spotify ni jeton d'addon n'est livré dans l'APK.",
                color = th.secondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp)
            )
        }
    }

    if (editSetting.isNotBlank()) {
        val state = if (editSetting == "better") AppSettings.betterLyricsKey else AppSettings.lyricaUrl
        val title = if (editSetting == "better") "Clé BetterLyrics" else "URL de l'instance Lyrica"
        ConfigValueDialog(title, state.value, editSetting == "better", onClose = { editSetting = "" }) { value ->
            state.value = if (editSetting == "better") value.trim()
                else value.trim().removeSuffix("/manifest.json").trimEnd('/')
            SettingsStore.save()
            MusicApi.resetLyricsLookups()
            LyricsRepo.clear()
            Player.state.value.current?.let { LyricsRepo.load(it, force = true) }
        }
    }
    choice?.let { spec ->
        ChoiceDialog(spec) { choice = null }
    }
    if (accentOpen) {
        AccentDialog(onClose = { accentOpen = false })
    }
}

// ------------------------------------------------------------- choix réels
private data class ChoiceOption(val id: String, val label: String, val sub: String = "")
private data class ChoiceSpec(
    val title: String,
    val subtitle: String,
    val selectedId: String,
    val options: List<ChoiceOption>,
    val onSelect: (String) -> Unit
)

private fun themeLabel(value: Int) = when (value) {
    1 -> "Clair"
    2 -> "Sombre"
    else -> "Système"
}

private fun lyricsModeLabel(value: Int) = when (value) {
    1 -> "Texte simple"
    2 -> "Désactivées"
    else -> "Synchronisées"
}

private fun appIconLabel(value: Int) = when (value) {
    1 -> "Liquid Glass"
    2 -> "Dark"
    3 -> "Classic"
    else -> "Défaut"
}

private val accentPalette = listOf(
    "Rose" to 0xFFFF2D55L,
    "Orange" to 0xFFFF9500L,
    "Jaune" to 0xFFFFCC00L,
    "Vert" to 0xFF34C759L,
    "Menthe" to 0xFF00C7BEL,
    "Turquoise" to 0xFF30B0C7L,
    "Bleu" to 0xFF0A84FFL,
    "Indigo" to 0xFF5E5CE6L,
    "Violet" to 0xFFBF5AF2L,
    "Graphite" to 0xFF8E8E93L
)

private fun accentLabel(argb: Long): String =
    accentPalette.firstOrNull { it.second == argb }?.first ?: "#%06X".format(argb and 0xFFFFFF)

private fun qualityChoice(title: String, current: String, onSelect: (String) -> Unit) = ChoiceSpec(
    title,
    "Le choix décide du flux réellement demandé lors de la lecture.",
    current,
    listOf(
        ChoiceOption("Sans perte", "Sans perte", "FLAC quand la source le propose"),
        ChoiceOption("Élevé", "Élevé", "MP3 haute qualité, démarrage rapide"),
        ChoiceOption("Normal", "Normal", "Économise la bande passante")
    ),
    onSelect
)

@Composable
private fun ChoiceDialog(spec: ChoiceSpec, onDismiss: () -> Unit) {
    val th = LocalTheme.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(th.background)
            .padding(vertical = 18.dp)) {
            Text(spec.title, color = th.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 20.dp))
            Text(spec.subtitle, color = th.secondary, fontSize = 12.5.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp))
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                spec.options.forEach { option ->
                    Row(Modifier.fillMaxWidth().clickable(noRip, null) {
                        spec.onSelect(option.id)
                        onDismiss()
                    }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(option.label, localize = spec.title != "Langue de l'application", color = th.onBackground, fontSize = 16.sp)
                            if (option.sub.isNotBlank()) Text(option.sub, color = th.secondary,
                                fontSize = 12.sp, lineHeight = 16.sp)
                        }
                        if (option.id == spec.selectedId) {
                            Text("✓", color = th.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccentDialog(onClose: () -> Unit) {
    val th = LocalTheme.current
    var hex by remember { mutableStateOf("#%06X".format(AppSettings.accentArgb.value and 0xFFFFFF)) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(th.background)
            .padding(20.dp)) {
            Text("Couleur d'accent", color = th.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Appliquée à la timeline, au scrub, aux contrôles et à la progression.",
                color = th.secondary, fontSize = 12.5.sp, lineHeight = 17.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            accentPalette.chunked(5).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    row.forEach { (_, argb) ->
                        Box(Modifier.weight(1f).aspectRatio(1f).clip(CircleShape)
                            .background(Color(argb.toInt()))
                            .clickable(noRip, null) {
                                AppSettings.accentArgb.value = argb
                                SettingsStore.save()
                                onClose()
                            }, contentAlignment = Alignment.Center) {
                            if (argb == AppSettings.accentArgb.value) {
                                Text("✓", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Code hexadécimal", color = th.secondary, fontSize = 12.sp)
            TextField(hex, { hex = it; error = null }, singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = th.card, unfocusedContainerColor = th.card,
                    focusedTextColor = th.onBackground, unfocusedTextColor = th.onBackground,
                    cursorColor = th.accent
                ), modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            if (error != null) Text(error.orEmpty(), color = Color(0xFFFF3B30), fontSize = 12.sp,
                modifier = Modifier.padding(top = 5.dp))
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                Text("Fermer", color = th.secondary, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(noRip, null) { onClose() }.padding(10.dp))
                Text("Appliquer", color = th.accent, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(noRip, null) {
                        val clean = hex.trim().removePrefix("#")
                        if (!clean.matches(Regex("[0-9A-Fa-f]{6}"))) {
                            error = "Entrez 6 caractères hexadécimaux, par exemple #0A84FF"
                        } else {
                            val value = (0xFF000000L or clean.toLong(16))
                            AppSettings.accentArgb.value = value
                            SettingsStore.save()
                            onClose()
                        }
                    }.padding(10.dp))
            }
        }
    }
}

@Composable
private fun ConfigValueDialog(title: String, initial: String, secret: Boolean, onClose: () -> Unit, onSave: (String) -> Unit) {
    val th = LocalTheme.current
    var value by remember(title) { mutableStateOf(initial) }
    val valid = secret || value.isBlank() || value.startsWith("https://")
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(Modifier.clip(RoundedCornerShape(24.dp)).background(th.card).padding(20.dp)) {
            Text(title, color = th.onBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            TextField(value, { value = it }, singleLine = true,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier.fillMaxWidth())
            if (!valid) Text("Une URL HTTPS est requise", color = th.accent, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                Text("Annuler", color = th.secondary, modifier = Modifier.clickable { onClose() }.padding(12.dp))
                Text("Enregistrer", color = th.accent, modifier = Modifier.clickable(enabled = valid) { onSave(value); onClose() }.padding(12.dp))
            }
        }
    }
}

private fun cycleQuality(current: String): String = when (current) {
    "Sans perte" -> "Élevé"
    "Élevé" -> "Normal"
    else -> "Sans perte"
}

// ---------------------------------------------------------------- UI units

@Composable
private fun Section(header: String, content: @Composable ColumnScope.() -> Unit) {
    val th = LocalTheme.current
    Text(
        header,
        color = th.secondary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.7.sp,
        modifier = Modifier.padding(start = 22.dp, top = 22.dp, bottom = 7.dp)
    )
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(th.card)
            .padding(vertical = 2.dp),
        content = content
    )
}

@Composable
private fun ColumnScope.SRow(
    icon: ImageVector,
    title: String,
    sub: String = "",
    value: String = "",
    onClick: (() -> Unit)? = null
) {
    val th = LocalTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(interactionSource = noRip, indication = null) { onClick?.invoke() }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = th.onBackground, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = th.onBackground, fontSize = 16.sp)
            if (sub.isNotEmpty()) {
                Text(sub, color = th.secondary, fontSize = 12.5.sp, lineHeight = 16.sp)
            }
        }
        if (value.isNotEmpty()) {
            Text(value, color = th.secondary, fontSize = 14.sp)
            if (onClick != null) {
                Spacer(Modifier.width(6.dp))
                Text("›", color = th.secondary, fontSize = 18.sp)
            }
        } else if (onClick != null) {
            Spacer(Modifier.width(6.dp))
            Text("›", color = th.secondary, fontSize = 18.sp)
        }
    }
}

/** Toggle iOS maison (pas de composant natif Material). */
@Composable
private fun IosSwitch(checked: Boolean, onChecked: (Boolean) -> Unit) {
    val th = LocalTheme.current
    val bg = if (checked) th.accent else th.hairline
    val knobAnim by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (checked) 24.dp else 4.dp,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.65f, stiffness = 500f
        ),
        label = "knob"
    )
    Box(
        Modifier
            .width(50.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(noRip, null) { onChecked(!checked) }
            .padding(2.dp)
    ) {
        Box(
            Modifier
                .offset(x = knobAnim)
                .size(26.dp)
                .clip(CircleShape)
                .background(if (checked) Palette.White else Palette.White)
                .background(if (!checked) Color.White.copy(alpha = 0.95f) else Color.Transparent)
        )
    }
}

@Composable
private fun ColumnScope.SToggle(
    icon: ImageVector,
    title: String,
    sub: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    val th = LocalTheme.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = th.onBackground, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = th.onBackground, fontSize = 16.sp)
            Text(sub, color = th.secondary, fontSize = 12.5.sp, lineHeight = 16.sp)
        }
        IosSwitch(checked = checked, onChecked = onChecked)
    }
}

@Composable
private fun CacheLimitRow() {
    val th = LocalTheme.current
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Storage, null, tint = th.onBackground, modifier = Modifier.size(23.dp))
            Spacer(Modifier.width(14.dp))
            Text("Limite du cache des chansons", color = th.onBackground, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text(
                if (AppSettings.cacheBytes.value < 1_000_000_000L) "500 Mo"
                else String.format(java.util.Locale.FRANCE, "%.1f Go", AppSettings.cacheBytes.value / 1_000_000_000.0),
                color = th.secondary,
                fontSize = 14.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.material3.Slider(
            value = (AppSettings.cacheBytes.value / AudioCache.STEP.toFloat()).coerceIn(1f, 20f),
            onValueChange = { gb ->
                AppSettings.cacheBytes.value = kotlin.math.round(gb).toLong().coerceIn(1, 20) * AudioCache.STEP
            },
            onValueChangeFinished = { SettingsStore.save() },
            valueRange = 1f..20f,
            steps = 18,
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = th.accent,
                activeTrackColor = th.accent
            )
        )
        Text(
            "Appliqué immédiatement : les plus anciens fichiers sont retirés au-delà de la limite.",
            color = th.secondary,
            fontSize = 12.sp,
            lineHeight = 16.sp
        )
        LaunchedEffect(AppSettings.cacheBytes.value) {
            kotlinx.coroutines.delay(200)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AudioCache.trimTo(ctx, AppSettings.cacheBytes.value) }
        }
    }
}
