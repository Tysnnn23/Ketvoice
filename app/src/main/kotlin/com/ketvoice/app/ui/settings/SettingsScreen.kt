package com.ketvoice.app.ui.settings

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import com.ketvoice.app.ui.common.KetIcons
import com.ketvoice.app.ui.common.Icon
import com.ketvoice.app.ui.common.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
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
import com.ketvoice.app.player.BitPerfectState
import com.ketvoice.app.player.ExactAudioPhase
import com.ketvoice.app.player.Player
import com.ketvoice.app.theme.LocalTheme
import com.ketvoice.app.theme.Palette
import com.ketvoice.app.glass.GlassPanel
import com.ketvoice.app.glass.GlassParams
import com.ketvoice.app.theme.T
import com.ketvoice.app.ui.AppUi
import com.ketvoice.app.util.LauncherIcons
import kotlinx.coroutines.delay
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
    var glassPreview by remember { mutableStateOf(false) }
    if (glassPreview) GlassAppearancePreview { glassPreview = false }
    val th = LocalTheme.current
    val ctx = LocalContext.current
    var editSetting by remember { mutableStateOf("") }
    var accentOpen by remember { mutableStateOf(false) }
    var lyricsDiag by remember { mutableStateOf(false) }
    var backupOpen by rememberSaveable { mutableStateOf(false) }
    var categoryKey by rememberSaveable { mutableStateOf<String?>(null) }
    val category = SettingsCategory.fromKey(categoryKey)
    var choice by remember { mutableStateOf<ChoiceSpec?>(null) }
    val settingsScope = rememberCoroutineScope()
    val youtube by YouTubeMusicSession.profile.collectAsState()
    val publisher by com.ketvoice.app.data.PublisherSession.allowed.collectAsState()
    val customSources by CustomSources.sources.collectAsState()
    val playerState by Player.state.collectAsState()

    fun back() { when (category) {
        SettingsCategory.Design -> categoryKey = SettingsCategory.Appearance.name
        null -> AppUi.closeOverlay()
        else -> categoryKey = null
    } }
    androidx.activity.compose.BackHandler(enabled = category != null) { back() }
    SettingsLayout(category, onBack = ::back) {
        if (category == null) SettingsHome(youtube.connected, youtube.name) { categoryKey = it.name }
        else key(category) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(top = LocalSettingsTopInset.current, bottom = 32.dp)
        ) {
            val testing = remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            val cacheUsedMb = remember { mutableIntStateOf(0) }
            if (category == SettingsCategory.Storage) LaunchedEffect(Unit) {
                cacheUsedMb.intValue = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    (AudioCache.sizeBytes(ctx) / 1_048_576L).toInt()
                }
            }

            // ==================================================== YOUTUBE MUSIC
            if (com.ketvoice.app.BuildConfig.IS_QUEST) {
                Section("QUEST · CONFORT") {
                    SToggle(KetIcons.Appearance, "Garder la fenêtre éveillée",
                        "Pendant la lecture au premier plan uniquement",
                        AppSettings.questKeepAwake.value) { AppSettings.questKeepAwake.value = it; SettingsStore.save() }
                }
            }
            Section("YOUTUBE MUSIC") {
                SRow(
                    KetIcons.Play, "Compte YouTube Music",
                    sub = when {
                        youtube.connected -> "${youtube.name} · session validée"
                        else -> "Connexion web pour l'historique et l'accueil personnel"
                    },
                    value = if (youtube.connected) "Connecté" else "Se connecter"
                ) { AppUi.showAccount.value = true }
                SToggle(
                    KetIcons.Refresh, "Synchroniser les écoutes",
                    "Une écoute n'est validée que si YouTube l'accepte",
                    AppSettings.syncYouTubeHistory.value
                ) { AppSettings.syncYouTubeHistory.value = it; SettingsStore.save() }
                SToggle(
                    KetIcons.Play, "Accueil personnalisé",
                    "Remplace les sélections locales par votre accueil YouTube Music",
                    AppSettings.personalizedYouTubeHome.value
                ) {
                    AppSettings.personalizedYouTubeHome.value = it
                    SettingsStore.save()
                    if (it && youtube.connected) HomeFeed.refreshYouTube() else if (!it) HomeFeed.clearPersonalized()
                }
            }

            Section("DISCORD") {
                com.ketvoice.app.ui.account.DiscordSettings()
            }

            if (publisher || (youtube.connected && com.ketvoice.app.data.PublisherAccess.matches(youtube.email))) {
                Section("PUBLICATIONS KETVOICE API") {
                    if (!publisher) {
                        SRow(KetIcons.Download, "Vérifier mon accès",
                            sub = "Confirmez votre adresse par un lien de connexion à usage unique", value = "") {
                            AppUi.openPublisher()
                        }
                    } else {
                        SRow(KetIcons.Download, "Importer un son",
                            sub = "Publier un fichier audio, sa pochette et ses paroles pour tous", value = "") {
                            AppUi.openPublisher()
                        }
                        SRow(KetIcons.Lyrics, "Importer des lyrics",
                            sub = "Recherchez un titre puis ouvrez son menu ⋯ pour publier un TTML ou LRC", value = "") {
                            AppUi.searchSeed.value = ""
                            AppUi.openSearch()
                        }
                        SRow(KetIcons.Account, "Déconnecter la publication", sub = "", value = "") {
                            com.ketvoice.app.data.PublisherSession.signOut()
                        }
                    }
                }
            }

            // ==================================================== LANGUE
            Section("LANGUE DE L'APPLICATION") {
                val systemId = "system"
                val currentLanguage = AppLanguage.fromLabel(AppSettings.language.value)
                val followingSystem = !AppSettings.languageChosen.value
                SRow(
                    KetIcons.Globe, "Langue de l'application",
                    sub = "Suit la langue du téléphone tant que tu n'en choisis pas une",
                    value = if (followingSystem) "Système · ${currentLanguage.nativeName}"
                    else currentLanguage.nativeName
                ) {
                    choice = ChoiceSpec(
                        "Langue de l'application",
                        "Choisissez la langue de tous les écrans de Ketvoice.",
                        if (followingSystem) systemId else currentLanguage.label,
                        listOf(
                            ChoiceOption(
                                systemId,
                                "Langue du système",
                                "Suit le téléphone · ${com.ketvoice.app.data.AppLocales.systemLanguage().nativeName}"
                            )
                        ) + AppLanguage.all.map { language ->
                            ChoiceOption(
                                language.label,
                                language.nativeName,
                                if (language == AppLanguage.FRENCH) "Langue d'origine"
                                else "${language.englishName} · interface complète"
                            )
                        }
                    ) { id ->
                        if (id == systemId) {
                            AppSettings.languageChosen.value = false
                            AppSettings.language.value =
                                com.ketvoice.app.data.AppLocales.systemLanguage().label
                        } else {
                            AppSettings.languageChosen.value = true
                            AppSettings.language.value = id
                        }
                        SettingsStore.save()
                        com.ketvoice.app.data.AppLocales.apply(
                            ctx,
                            if (AppSettings.languageChosen.value) AppLanguage.fromLabel(id) else null
                        )
                    }
                }
            }

            // ==================================================== APPARENCE
            Section("DESIGN KETVOICE") {
                Text("Chaque finition peut être retirée séparément, sans modifier vos playlists ni votre musique.", color = th.secondary)
                com.ketvoice.app.data.DesignChoices.all.forEach { option ->
                    SToggle(KetIcons.Sliders, option.title, "Revenir à la présentation précédente en désactivant cette option",
                        com.ketvoice.app.data.DesignChoices.enabled(option.code)) {
                        com.ketvoice.app.data.DesignChoices.set(option.code, it); SettingsStore.save()
                    }
                }
            }
            Section("APPARENCE") {
                SRow(KetIcons.Appearance, "Aperçu du verre", sub = "Comparer Clair, Dépoli, Opaque et Liquid 27 avant d'appliquer") {
                    glassPreview = true
                }
                SToggle(KetIcons.Info, "Stats info", "Informations audio réelles, uniquement dans le lecteur", AppSettings.techStats.value) {
                    AppSettings.techStats.value = it; SettingsStore.save()
                }
                SToggle(KetIcons.Album, "Photos d'artiste HD",
                    "Portrait ou fond haute résolution quand il existe. Aucune photo de remplacement inventée.",
                    AppSettings.artistPhotos.value) { AppSettings.artistPhotos.value = it; SettingsStore.save() }
                SToggle(KetIcons.Album, "Signatures et logos d'artistes",
                    "Signature authentique (Wikidata) sinon logo transparent. Le nom est envoyé au service uniquement si activé.",
                    AppSettings.artistSignatures.value) { AppSettings.artistSignatures.value = it; SettingsStore.save() }
                val refresh = com.ketvoice.app.ui.motion.DisplayRefresh.status
                SToggle(KetIcons.Sliders, "Fluidité 120 Hz",
                    when {
                        com.ketvoice.app.BuildConfig.IS_QUEST -> "Le casque conserve la fréquence gérée par Quest."
                        refresh.powerSaving -> "Demande suspendue en économie d'énergie."
                        !refresh.supported -> "Aucun mode 120 Hz compatible annoncé par cet écran."
                        refresh.requested -> "120 Hz demandé à Android ; la fréquence reste gérée par le système."
                        else -> "Demande le mode 120 Hz natif, sans changer la résolution. Consomme davantage de batterie."
                    }, AppSettings.highRefreshRate.value) { AppSettings.highRefreshRate.value = it; SettingsStore.save() }
                if (refresh.actualHz > 0f) Text(
                    com.ketvoice.app.data.AppText.t("Fréquence de l'écran") + " : " + kotlin.math.round(refresh.actualHz).toInt() + " Hz",
                    localize = false, color = th.secondary, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                SRow(KetIcons.Sliders, "DESIGN KETVOICE", sub = "Chaque finition peut être retirée séparément, sans modifier vos playlists ni votre musique.") {
                    categoryKey = SettingsCategory.Design.name
                }
                SToggle(KetIcons.Canvas, "Canvas sur les pages d'album",
                    "Affiche la vidéo de l'album à la place de la pochette, en plein cadre. Indépendant du Canvas du lecteur.",
                    AppSettings.albumHeroCanvas.value) { AppSettings.albumHeroCanvas.value = it; SettingsStore.save() }
                SToggle(KetIcons.Appearance, "Couleurs de la pochette", "Accents du lecteur adaptés à l'album, avec contraste renforcé",
                    AppSettings.albumColors.value) { AppSettings.albumColors.value = it; SettingsStore.save() }
                SToggle(KetIcons.Appearance, "Verre teinté par la pochette",
                    "La couleur dominante de la pochette colore légèrement le verre, sans réduire la lisibilité",
                    AppSettings.coverTintedGlass.value) { AppSettings.coverTintedGlass.value = it; SettingsStore.save() }
                SToggle(KetIcons.Canvas, "Fond animé du lecteur",
                    "Dégradé lent aux couleurs de la pochette derrière le lecteur, qui suit doucement la musique",
                    AppSettings.meshBackground.value) { AppSettings.meshBackground.value = it; SettingsStore.save() }
                if (!com.ketvoice.app.BuildConfig.IS_QUEST) SToggle(KetIcons.Sliders, "Retours haptiques",
                    "Légère vibration sur le verre, les onglets et la barre de lecture",
                    AppSettings.haptics.value) { AppSettings.haptics.value = it; SettingsStore.save() }
                SToggle(KetIcons.Canvas, "Profondeur de pochette", "Mouvement léger au toucher ou au pointeur, sans modifier les gestes",
                    AppSettings.artworkDepth.value) { AppSettings.artworkDepth.value = it; SettingsStore.save() }
                SToggle(KetIcons.Canvas, "Canvas panoramique",
                    "Optionnel : Canvas en fond assombri, pochette fixe pour éviter un double décodage",
                    AppSettings.panoramicCanvas.value) { AppSettings.panoramicCanvas.value=it;SettingsStore.save() }
                SToggle(KetIcons.Sliders, "Animations réduites", "Atténue les mouvements décoratifs, sans désactiver le karaoké",
                    AppSettings.reduceAnimations.value) { AppSettings.reduceAnimations.value=it;SettingsStore.save() }
                SRow(KetIcons.Lyrics, "Taille des paroles", sub="Lisibilité sans agrandir les boutons",
                    value=listOf("Standard","Grand","Très grand")[AppSettings.lyricsTextSize.value.coerceIn(0, 2)]) {
                    AppSettings.lyricsTextSize.value=(AppSettings.lyricsTextSize.value+1)%3;SettingsStore.save()
                }
                SRow(
                    KetIcons.Moon, "Thème",
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
                    KetIcons.Appearance, "Couleur d'accent",
                    sub = "Barre de lecture, scrub, boutons et progression",
                    value = accentLabel(AppSettings.accentArgb.value)
                ) { accentOpen = true }
                SToggle(
                    KetIcons.Appearance, "Verre liquide",
                    "Flou, réfraction et reflets sur l'interface",
                    AppSettings.liquidGlass.value
                ) { AppSettings.liquidGlass.value = it; SettingsStore.save() }
                SToggle(
                    KetIcons.Sliders, "Mode performance",
                    "Allège les effets de verre sur les appareils modestes",
                    AppSettings.highPerformanceMode.value
                ) { AppSettings.highPerformanceMode.value = it; SettingsStore.save() }
                SToggle(
                    KetIcons.Appearance, "Flou dynamique",
                    "Réfraction en temps réel derrière les panneaux",
                    !AppSettings.reduceDynamicBlur.value
                ) { AppSettings.reduceDynamicBlur.value = !it; SettingsStore.save() }
                SRow(
                    KetIcons.Album, "Icône de l'app",
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
                SToggle(KetIcons.Globe, "Partager le titre en cours sur le site",
                    "Le compteur d'auditeurs reste anonyme ; le titre, l'artiste et la pochette ne sont envoyés qu'avec ton accord",
                    AppSettings.shareNowPlaying.value) { AppSettings.shareNowPlaying.value = it; SettingsStore.save() }
                SToggle(KetIcons.Audio, "Qualité progressive",
                    "Activé : les sources sont interrogées en parallèle, le premier flux audio réellement valide démarre, puis une meilleure qualité prend le relais sans couper la lecture. Désactivé : la première source disponible dans l'ordre des Sources est utilisée.",
                    AppSettings.progressiveQuality.value) { AppSettings.progressiveQuality.value = it; SettingsStore.save() }
                SToggle(
                    KetIcons.Shield, "Bit-Perfect",
                    "Envoie le PCM décodé au mixeur sans rééchantillonnage ni traitement de l'app, lorsque Android et la sortie le permettent. Égaliseur, réverbération et ignorance des silences sont alors désactivés.",
                    AppSettings.bitPerfect.value
                ) { Player.setBitPerfect(it); SettingsStore.save() }
                BitPerfectPanel()
                SRow(KetIcons.Sliders, "Égaliseur", sub="Traitement réel de la session audio", value="") { AppUi.showEqualizer.value=true }
                SRow(KetIcons.Wifi, "Transfert sur le même Wi-Fi", sub="Découverte locale et confirmation sur le destinataire", value="") { AppUi.showHandoff.value=true }
                SRow(
                    KetIcons.Audio, "Qualité en Wi-Fi",
                    sub = "Sans perte = FLAC, sinon MP3 rapide",
                    value = AppSettings.wifiQuality.value
                ) {
                    choice = qualityChoice("Qualité en Wi-Fi", AppSettings.wifiQuality.value) { value ->
                        AppSettings.wifiQuality.value = value
                        SettingsStore.save()
                    }
                }
                SRow(
                    KetIcons.Wifi, "Qualité en données mobiles",
                    sub = "Appliquée quand le réseau est facturé",
                    value = AppSettings.mobileQuality.value
                ) {
                    choice = qualityChoice("Qualité en données mobiles", AppSettings.mobileQuality.value) { value ->
                        AppSettings.mobileQuality.value = value
                        SettingsStore.save()
                    }
                }
                Text("Si la source sans perte est indisponible, la lecture et le téléchargement basculent automatiquement sur le MP3 le plus rapide.",
                    color = th.secondary, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
                SToggle(
                    KetIcons.Sliders, "Ignorer les silences",
                    "Saute les passages silencieux (moteur audio)",
                    AppSettings.skipSilence.value
                ) { Player.setSkipSilence(it); SettingsStore.save() }
            }

            // ==================================================== PAROLES
            Section("PAROLES") {
                SRow(
                    KetIcons.Audio, "Diagnostic des sources",
                    sub = "Teste chaque source sur le titre en cours",
                    value = if (playerState.current == null) "Aucun titre en lecture" else "Tester"
                ) { lyricsDiag = true }
                SRow(
                    KetIcons.Lyrics, "Source des paroles",
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
                    KetIcons.Lyrics, "Mode des paroles",
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
                    KetIcons.Lyrics, "Flouter les paroles inactives",
                    "Les lignes suivantes restent estompées",
                    AppSettings.blurInactiveLyrics.value
                ) { AppSettings.blurInactiveLyrics.value = it; SettingsStore.save() }
                SRow(KetIcons.Lyrics, "Clé BetterLyrics (facultative)",
                    sub = "Cache public ; certaines recherches exigent une clé",
                    value = if (AppSettings.betterLyricsKey.value.isBlank()) "Non définie" else "Définie"
                ) { editSetting = "better" }
                SRow(KetIcons.Lyrics, "Instance Lyrica", sub = "URL HTTPS de votre serveur Lyrica",
                    value = if (AppSettings.lyricaUrl.value.isBlank()) "À configurer" else "Configurée"
                ) { editSetting = "lyrica" }
                SRow(KetIcons.Lyrics, "lrc.red", sub = "Source intégrée · recherche de paroles par ISRC") {
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://lrc.red"))) }
                }
                SRow(KetIcons.Lyrics, "Langue d'origine (traduction)",
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
                SRow(KetIcons.Lyrics, "Langue de traduction", sub = "Traduction réelle, sans timing par syllabe",
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
                SToggle(KetIcons.Sliders, "Sonic Canvas", "Fond réactif au signal audio réel, sans microphone. Désactivé avec les animations réduites.",
                    AppSettings.sonicCanvas.value) { AppSettings.sonicCanvas.value = it; SettingsStore.save() }
                SRow(
                    KetIcons.Canvas, "Ketvoice API",
                    sub = "Canvas Spotify résolu et authentifié côté Supabase",
                    value = "Intégré"
                ) {}
                SToggle(
                    KetIcons.Canvas, "Activer les Canvas vidéo",
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
                    KetIcons.Wifi, "Canvas en données mobiles",
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
                    KetIcons.Sliders, "Tester le canvas",
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
                    KetIcons.Sliders, "Ketvoice API",
                    sub = "Recherche, streaming et téléchargements · Supabase",
                    value = "Intégré"
                ) { AppUi.pushOverlay(AppUi.SOURCES) }
                SRow(
                    KetIcons.Sliders, "Sources personnalisées",
                    sub = "URLs HTTPS terminées par .json · streaming et téléchargements",
                    value = if (customSources.isEmpty()) "Aucune" else "${customSources.size} source(s)"
                ) { AppUi.pushOverlay(AppUi.SOURCES) }
                Text(
                    "Ketvoice API protège les identifiants côté Supabase. Aucun cookie Spotify " +
                        "ni jeton d'addon n'est stocké dans l'application.",
                    color = th.secondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp)
                )
            }

            // ==================================================== TÉLÉCHARGEMENTS
            Section("TÉLÉCHARGEMENTS") {
                SToggle(KetIcons.Wifi, "Téléchargements en Wi-Fi uniquement", "Les transferts attendent une connexion Wi-Fi", AppSettings.downloadsWifiOnly.value) {
                    AppSettings.downloadsWifiOnly.value = it; SettingsStore.save()
                }
                SRow(
                    KetIcons.Download, "Qualité de téléchargement",
                    sub = "Le fichier réellement obtenu, pas seulement le réglage",
                    value = when (AppSettings.downloadQualityMode.value) {
                        0 -> "FLAC · sans perte"
                        1 -> "Haute qualité"
                        else -> "Rapide"
                    }
                ) {
                    choice = ChoiceSpec(
                        "Qualité de téléchargement",
                        "FLAC attend la meilleure source sans perte. Haute qualité garde le meilleur flux compressé réel (AAC/Opus/MP3) au lieu du premier MP3 128 kb/s. Rapide prend la première source lisible. Le repli reste automatique.",
                        AppSettings.downloadQualityMode.value.toString(),
                        listOf(
                            ChoiceOption("0", "FLAC · sans perte", "Fichier plus lourd (~35 Mo)"),
                            ChoiceOption("1", "Haute qualité", "Meilleur débit compressé disponible"),
                            ChoiceOption("2", "Rapide", "Première source lisible")
                        )
                    ) { id ->
                        AppSettings.downloadQualityMode.value = id.toIntOrNull() ?: 1
                        SettingsStore.save()
                    }
                }
                SRow(
                    KetIcons.Album, "Voir la liste",
                    sub = "Pistes enregistrées sur l'appareil",
                    value = ""
                ) { AppUi.pushOverlay(AppUi.DOWNLOADS) }
            }

            // ==================================================== MUSIQUE LOCALE
            val localFolderPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocumentTree()
            ) { uri ->
                if (uri != null) {
                    com.ketvoice.app.data.LocalMusic.acceptFolder(ctx, uri)
                    com.ketvoice.app.data.LocalMusic.scan(ctx)
                }
            }
            val localFolderName = com.ketvoice.app.data.LocalMusic.folderName()
            Section("MUSIQUE LOCALE") {
                SRow(
                    KetIcons.Folder, "Dossier de musique locale",
                    sub = if (localFolderName == null)
                        "Tous les fichiers audio de l'appareil (MediaStore)"
                    else "Dossier analysé : $localFolderName",
                    value = if (localFolderName == null) "Appareil" else ""
                ) { localFolderPicker.launch(null) }
                if (localFolderName != null) {
                    SRow(
                        KetIcons.Folder, "Revenir à tout l'appareil",
                        sub = "Le scan MediaStore remplace le dossier choisi"
                    ) {
                        com.ketvoice.app.data.LocalMusic.clearFolder()
                        com.ketvoice.app.data.LocalMusic.scan(ctx)
                    }
                }
                SToggle(KetIcons.Sliders, "Correspondances musicales",
                    "Complète artiste, album et pochette des fichiers locaux sans métadonnées. Les métadonnées existantes ne sont jamais remplacées.",
                    AppSettings.localMusicEnrich.value) { AppSettings.localMusicEnrich.value = it; SettingsStore.save() }
            }

            // ==================================================== STOCKAGE
            Section("STOCKAGE") {
                SRow(
                    KetIcons.Storage, "Cache des chansons utilisé",
                    sub = "Le cache accélère les relectures",
                    value = "${cacheUsedMb.intValue} Mo"
                ) {}
                CacheLimitRow()
                SRow(KetIcons.Storage, "Sauvegarde personnelle", sub = "Playlists, favoris et réglages") { backupOpen = true }
                SRow(
                    KetIcons.Trash, "Vider le cache des chansons",
                    sub = "Les paroles et canvas téléchargés restent"
                ) {
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { AudioCache.clear(ctx) }
                        cacheUsedMb.intValue = (AudioCache.sizeBytes(ctx) / 1_000_000L).toInt()
                    android.widget.Toast.makeText(ctx, com.ketvoice.app.data.AppText.t("Cache des chansons vidé"), android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                SRow(
                    KetIcons.Album, "Vider le cache des images",
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
                SRow(KetIcons.Refresh, "Vérifier les mises à jour",
                    sub = if (com.ketvoice.app.ui.update.UpdateBadge.checking) "Vérification des mises à jour…" else "GitHub Releases") {
                    if (!com.ketvoice.app.ui.update.UpdateBadge.checking) com.ketvoice.app.ui.update.UpdateBadge.requestCheck()
                }
                SRow(
                    KetIcons.Album, "Version",
                    sub = "YouTube Music · Ketvoice API · canvas · paroles",
                    value = com.ketvoice.app.BuildConfig.VERSION_NAME + if (com.ketvoice.app.BuildConfig.IS_QUEST) " · Quest" else ""
                ) {}
            }

            if (category == SettingsCategory.Sources) Text(
                "Ketvoice API (Supabase) gère les sources et les canvas. " +
                    "Aucun cookie Spotify ni jeton d'addon n'est livré dans l'APK.",
                color = th.secondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp)
            )
        }
        }
    }

    if (lyricsDiag) LyricsDiagnosticsDialog(onClose = { lyricsDiag = false })
    if (backupOpen) PersonalBackupSheet(onClose = { backupOpen = false })

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
    SettingsSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)) {
            SettingsSheetHeader(spec.title, spec.subtitle, onDismiss)
            Spacer(Modifier.height(18.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(th.onBackground.copy(alpha = .035f)).selectableGroup()) {
                spec.options.forEachIndexed { index, option ->
                    Row(Modifier.fillMaxWidth().selectable(option.id == spec.selectedId,
                        role = Role.RadioButton, onClick = { spec.onSelect(option.id); onDismiss() })
                        .heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 14.dp)) {
                            Text(option.label, localize = spec.title != "Langue de l'application", color = th.onBackground,
                                fontSize = 15.5.sp, fontWeight = if (option.id == spec.selectedId) FontWeight.SemiBold else FontWeight.Normal,
                                lineHeight = 22.sp)
                            if (option.sub.isNotBlank()) Text(option.sub, color = th.secondary,
                                fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
                        }
                        if (option.id == spec.selectedId) Icon(KetIcons.Check, null, tint = th.onBackground, modifier = Modifier.size(20.dp))
                        else Spacer(Modifier.size(20.dp))
                    }
                    if (index != spec.options.lastIndex) Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(.5.dp)
                        .background(th.onBackground.copy(alpha = .065f)))
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AccentDialog(onClose: () -> Unit) {
    val th = LocalTheme.current
    var hex by remember { mutableStateOf("#%06X".format(AppSettings.accentArgb.value and 0xFFFFFF)) }
    var error by remember { mutableStateOf<String?>(null) }
    val hexLabel = com.ketvoice.app.data.AppText.t("Code hexadécimal")
    SettingsSheet(onDismiss = onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(20.dp)) {
            SettingsSheetHeader("Couleur d'accent",
                "Appliquée à la timeline, au scrub, aux contrôles et à la progression.", onClose)
            Spacer(Modifier.height(18.dp))
            Column(Modifier.selectableGroup()) {
                accentPalette.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { (label, argb) ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                                .selectable(argb == AppSettings.accentArgb.value, role = Role.RadioButton,
                                    onClick = { AppSettings.accentArgb.value = argb; SettingsStore.save(); onClose() })
                                .heightIn(min = 72.dp).padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(32.dp).clip(CircleShape).background(Color(argb.toInt())), contentAlignment = Alignment.Center) {
                                    if (argb == AppSettings.accentArgb.value) Icon(KetIcons.Check, null,
                                        tint = if (argb == 0xFFFFCC00L || argb == 0xFF34C759L || argb == 0xFF00C7BEL) Color.Black else Color.White,
                                        modifier = Modifier.size(18.dp))
                                }
                                Text(label, color = th.secondary, fontSize = 12.sp, lineHeight = 16.sp,
                                    modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text("Code hexadécimal", color = th.secondary, fontSize = 12.sp)
            BasicTextField(hex, { hex = it; error = null }, singleLine = true,
                textStyle = TextStyle(color = th.onBackground, fontSize = 16.sp), cursorBrush = SolidColor(th.accent),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).semantics { contentDescription = hexLabel }.clip(RoundedCornerShape(12.dp))
                    .background(th.onBackground.copy(alpha = .07f)).heightIn(min = 48.dp).padding(12.dp))
            if (error != null) Text(error.orEmpty(), color = Color(0xFFFF3B30), fontSize = 12.sp,
                modifier = Modifier.padding(top = 5.dp))
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End) {
                SettingsAction("Fermer", onClose)
                SettingsAction("Appliquer", {
                        val clean = hex.trim().removePrefix("#")
                        if (!clean.matches(Regex("[0-9A-Fa-f]{6}"))) {
                            error = "Entrez 6 caractères hexadécimaux, par exemple #0A84FF"
                        } else {
                            val value = (0xFF000000L or clean.toLong(16))
                            AppSettings.accentArgb.value = value
                            SettingsStore.save()
                            onClose()
                        }
                    }, primary = true)
            }
        }
    }
}

@Composable
private fun ConfigValueDialog(title: String, initial: String, secret: Boolean, onClose: () -> Unit, onSave: (String) -> Unit) {
    val th = LocalTheme.current
    var value by remember(title) { mutableStateOf(initial) }
    val valid = secret || value.isBlank() || value.startsWith("https://")
    val inputLabel = com.ketvoice.app.data.AppText.t(title)
    SettingsSheet(onDismiss = onClose) {
        Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(20.dp)) {
            SettingsSheetHeader(title, onClose = onClose)
            Spacer(Modifier.height(18.dp))
            BasicTextField(value, { value = it }, singleLine = true,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                textStyle = TextStyle(color = th.onBackground, fontSize = 16.sp), cursorBrush = SolidColor(th.accent),
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = inputLabel }.clip(RoundedCornerShape(12.dp)).background(th.onBackground.copy(alpha = .07f))
                    .heightIn(min = 48.dp).padding(12.dp))
            if (!valid) Text("Une URL HTTPS est requise", color = th.accent, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                SettingsAction("Annuler", onClose)
                SettingsAction("Enregistrer", { onSave(value); onClose() }, enabled = valid, primary = true)
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
    val destination = SettingsCategory.forGroup(header) ?: return
    if (destination != LocalSettingsCategory.current) return
    val th = LocalTheme.current
    SettingsGroupHeading(header, Modifier.padding(horizontal = 20.dp))
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(shape)
        .background(th.card).border(.5.dp, th.onBackground.copy(alpha = .055f), shape)
        .padding(vertical = 3.dp), content = content)
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
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 72.dp).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = th.onBackground.copy(alpha = .74f), modifier = Modifier.size(21.dp))
        Column(Modifier.weight(1f).padding(start = 18.dp, end = 12.dp)) {
            Text(title, color = th.onBackground, fontSize = 15.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
            if (sub.isNotEmpty()) Text(sub, color = th.secondary, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(top = 3.dp))
            if (value.isNotEmpty()) Text(value, color = th.onBackground.copy(alpha = .76f), fontSize = 13.sp,
                lineHeight = 18.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 5.dp))
        }
        if (onClick != null) Icon(KetIcons.ChevronRight, null, tint = th.secondary.copy(alpha = .8f),
            modifier = Modifier.size(17.dp))
    }
    SettingsCellDivider()
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
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChecked)
            .heightIn(min = 76.dp).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = th.onBackground.copy(alpha = .74f), modifier = Modifier.size(21.dp))
        Column(Modifier.weight(1f).padding(start = 18.dp, end = 14.dp)) {
            Text(title, color = th.onBackground, fontSize = 15.5.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
            Text(sub, color = th.secondary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
        }
        com.ketvoice.app.ui.common.LiquidGlassSwitch(checked, onCheckedChange = null)
    }
    SettingsCellDivider()
}

/**
 * Status card for the exact path. The dot never turns green from a preference:
 * the sink promotes it to Active only after Android confirms the mixer and route.
 */
@Composable
private fun ColumnScope.BitPerfectPanel() {
    if (!AppSettings.bitPerfect.value) return
    val th = LocalTheme.current
    val status by BitPerfectState.status.collectAsState()
    val trackId = Player.state.collectAsState().value.current?.id
    val decoded by produceState("", trackId) {
        while (true) {
            value = Player.decodedAudioInfo()
            delay(1000)
        }
    }
    val dot = when (status.phase) {
        ExactAudioPhase.Active -> Color(0xFF34C759)
        ExactAudioPhase.Pending -> th.accent
        ExactAudioPhase.Unavailable -> Color(0xFFFF9F0A)
        ExactAudioPhase.Standard -> th.secondary
    }
    val headline = when (status.phase) {
        ExactAudioPhase.Active -> "Bit-Perfect actif · confirmé par Android"
        ExactAudioPhase.Pending -> status.reason
        ExactAudioPhase.Unavailable -> "Chemin exact indisponible"
        ExactAudioPhase.Standard -> "Sortie standard"
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            Text(headline, color = th.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
        if (status.phase == ExactAudioPhase.Unavailable && status.reason.isNotBlank()) {
            Text("Sortie standard", color = th.secondary, fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp))
            Text(status.reason, localize = false, color = th.secondary, fontSize = 12.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp))
        }
        status.source?.let {
            Text("${com.ketvoice.app.data.AppText.t("PCM décodé")} · ${it.describe()}", localize = false,
                color = th.secondary, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
        }
        status.output?.let {
            Text("${com.ketvoice.app.data.AppText.t("Sortie négociée")} · ${it.describe()}", localize = false,
                color = th.secondary, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 2.dp))
        }
        if (status.device.isNotBlank()) {
            Text("${com.ketvoice.app.data.AppText.t("Périphérique")} · ${status.device}", localize = false,
                color = th.secondary, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 2.dp))
        }
        if (decoded.isNotBlank()) {
            Text("${com.ketvoice.app.data.AppText.t("Source")} · $decoded", localize = false,
                color = th.secondary, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 2.dp))
        }
        Text(
            "Android 14+ requis. Le volume système reste géré par Android ; l'app ne revendique le mode exact qu'après confirmation du mixeur et de la sortie.",
            color = th.secondary, fontSize = 11.sp, lineHeight = 15.sp,
            modifier = Modifier.padding(start = 16.dp, top = 7.dp)
        )
    }
}

@Composable
private fun CacheLimitRow() {
    val th = LocalTheme.current
    val ctx = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(KetIcons.Storage, null, tint = th.onBackground.copy(alpha = .74f), modifier = Modifier.size(21.dp))
            Column(Modifier.weight(1f).padding(start = 18.dp)) {
                Text("Limite du cache des chansons", color = th.onBackground, fontSize = 15.5.sp,
                    lineHeight = 22.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (AppSettings.cacheBytes.value < 1_000_000_000L) "500 Mo"
                    else String.format(java.util.Locale.FRANCE, "%.1f Go", AppSettings.cacheBytes.value / 1_000_000_000.0),
                    color = th.secondary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        com.ketvoice.app.ui.common.GlassSlider(
            value = (AppSettings.cacheBytes.value / AudioCache.STEP.toFloat()).coerceIn(1f, 20f),
            onValueChange = { gb ->
                AppSettings.cacheBytes.value = kotlin.math.round(gb).toLong().coerceIn(1, 20) * AudioCache.STEP
            },
            onValueChangeFinished = { SettingsStore.save() },
            valueRange = 1f..20f,
            modifier = Modifier.heightIn(min = 48.dp).semantics {
                contentDescription = com.ketvoice.app.data.AppText.t("Limite du cache des chansons")
            }
        )
        Text(
            "Appliqué immédiatement : les plus anciens fichiers sont retirés au-delà de la limite.",
            color = th.secondary,
            fontSize = 12.sp,
            lineHeight = 16.sp
        )
        LaunchedEffect(AppSettings.cacheBytes.value) {
            kotlinx.coroutines.delay(200)
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    AudioCache.trimTo(ctx, AppSettings.cacheBytes.value)
                }
            }
        }
    }
}
