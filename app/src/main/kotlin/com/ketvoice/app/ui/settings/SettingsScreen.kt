package com.ketvoice.app.ui.settings

import com.ketvoice.app.ui.motion.symbolBounce
import com.ketvoice.app.ui.motion.symbolWiggle
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
import androidx.compose.ui.platform.testTag
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
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
import com.ketvoice.app.theme.Ket27
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
    val th = LocalTheme.current
    val ctx = LocalContext.current
    var editSetting by remember { mutableStateOf("") }
    var accentOpen by remember { mutableStateOf(false) }
    var lyricsDiag by remember { mutableStateOf(false) }
    var lyricsOrderOpen by remember { mutableStateOf(false) }
    var backupOpen by rememberSaveable { mutableStateOf(false) }
    var categoryKey by rememberSaveable { mutableStateOf<String?>(null) }
    val category = SettingsCategory.fromKey(categoryKey)
    var choice by remember { mutableStateOf<ChoiceSpec?>(null) }
    var iconPicker by remember { mutableStateOf(false) }
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
    val focus = remember { mutableStateOf<String?>(null) }
    SettingsLayout(category, onBack = ::back, focus = focus) {
        if (category == null) SettingsHome(youtube.connected, youtube.name,
            onResult = { focus.value = it.title; categoryKey = it.category.name }) { categoryKey = it.name }
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
                Section("STUDIO") {
                    if (!publisher) {
                        SRow(KetIcons.Download, "Ouvrir le Studio",
                            sub = "Connexion par un lien à usage unique, mémorisée sur cet appareil", value = "") {
                            AppUi.openPublisher()
                        }
                    } else {
                        SRow(KetIcons.Download, "Ouvrir le Studio",
                            sub = "Vos titres, pochettes, Canvas, paroles et albums", value = "") {
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
            Section("VERRE") {
                // 8.4: the look of the glass — Liquid 27 (default) or Cristal, each with its own material and motion.
                SRow(KetIcons.Appearance, "Style du verre", sub = "La matière du verre et ses animations",
                    value = com.ketvoice.app.glass.GlassLook.labels[AppSettings.glassLook.value.coerceIn(0, com.ketvoice.app.glass.GlassLook.MAX)]) {
                    choice = ChoiceSpec(
                        "Style du verre", "Choisissez la matière et les animations du verre.",
                        AppSettings.glassLook.value.toString(),
                        listOf(
                            ChoiceOption("0", "Liquid 27", "Le verre habituel de Ketvoice, par défaut"),
                            ChoiceOption("1", "Cristal", "Verre limpide et léger, pastille élastique, menus qui jaillissent du bouton")
                        )
                    ) { id ->
                        AppSettings.glassLook.value = (id.toIntOrNull() ?: 0).coerceIn(0, com.ketvoice.app.glass.GlassLook.MAX)
                        SettingsStore.save()
                    }
                }
                GlassTransparencyCard()
                // A headset's window has no tilt to follow: the switch is not offered on Quest.
                if (!com.ketvoice.app.BuildConfig.IS_QUEST) SToggle(KetIcons.Appearance, "Reflets selon l'inclinaison",
                    when {
                        com.ketvoice.app.ui.motion.Motion.reduced -> "Reflet fixe tant que les animations sont réduites."
                        AppSettings.highPerformanceMode.value -> "Reflet fixe en mode performance."
                        else -> "Le reflet du verre suit doucement l'inclinaison du téléphone"
                    }, AppSettings.glassTilt.value) { AppSettings.glassTilt.value = it; SettingsStore.save() }
            }
            Section("COULEURS DE LA POCHETTE") {
                Text("Le texte reste toujours lisible : chaque couleur est ajustée au thème clair ou sombre.",
                    color = th.secondary, fontSize = 13.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                if (com.ketvoice.app.data.Seasons.inEffect() != null) Text(
                    "Avec un thème saisonnier, les pages gardent ses couleurs ; la pochette colore toujours le lecteur et le verre.",
                    color = th.secondary, fontSize = 13.sp, lineHeight = 18.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp).testTag("season-cover-note"))
                SToggle(KetIcons.Appearance, "Verre teinté par la pochette",
                    "La couleur dominante de la pochette colore légèrement le verre, sans réduire la lisibilité",
                    AppSettings.coverTintedGlass.value) { AppSettings.coverTintedGlass.value = it; SettingsStore.save() }
                SToggle(KetIcons.Sliders, "Couleur de la pochette", "La couleur d'accent de toute l'interface suit la pochette du titre en cours",
                    AppSettings.coverAccent.value) { AppSettings.coverAccent.value = it; SettingsStore.save() }
                SToggle(KetIcons.Sliders, "Fond selon la pochette", "Le fond de toute l'application prend la couleur de ce qui est en écoute, comme une page d'album",
                    AppSettings.coverBackground.value) { AppSettings.coverBackground.value = it; SettingsStore.save() }
                SToggle(KetIcons.Appearance, "Couleurs de la pochette", "Accents du lecteur adaptés à l'album, avec contraste renforcé",
                    AppSettings.albumColors.value) { AppSettings.albumColors.value = it; SettingsStore.save() }
            }
            Section("APPARENCE") {
                // The seasonal theme of the moment (6.2): here only while it lasts.
                com.ketvoice.app.data.Seasons.active()?.let { season -> SeasonThemeRow(season) }
                SToggle(KetIcons.Info, "Stats info", "Informations audio réelles, uniquement dans le lecteur", AppSettings.techStats.value) {
                    AppSettings.techStats.value = it; SettingsStore.save()
                }
                SToggle(KetIcons.Album, "Photos d'artiste HD",
                    "Portrait ou fond haute résolution quand il existe. Aucune photo de remplacement inventée.",
                    AppSettings.artistPhotos.value) { AppSettings.artistPhotos.value = it; SettingsStore.save() }
                SToggle(KetIcons.Album, "Signatures et logos d'artistes",
                    "Signature authentique (Wikidata), sinon archive publique de signatures, sinon logo transparent. Le nom est envoyé à ces services uniquement si activé.",
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
                // 7.3: how album and playlist pages open; a page without a Canvas always shows the centred cover.
                SRow(KetIcons.Album, "En-tête des albums et playlists", sub = "Sans Canvas, la pochette centrée s'affiche",
                    value = com.ketvoice.app.ui.sub.AlbumHeader.LABELS[com.ketvoice.app.ui.sub.AlbumHeader.normalize(AppSettings.albumHeader.value)]) {
                    // 7.5 motion75: two choices, the cover or the animated Canvas as Apple Music shows it.
                    AppSettings.albumHeader.value = 1 - com.ketvoice.app.ui.sub.AlbumHeader.normalize(AppSettings.albumHeader.value); SettingsStore.save()
                }
                SToggle(KetIcons.Canvas, "Fond animé du lecteur",
                    "Dégradé lent aux couleurs de la pochette derrière le lecteur, qui suit doucement la musique",
                    AppSettings.meshBackground.value) { AppSettings.meshBackground.value = it; SettingsStore.save() }
                if (!com.ketvoice.app.BuildConfig.IS_QUEST) SToggle(KetIcons.Sliders, "Retours haptiques",
                    "Légère vibration sur le verre, les onglets et la barre de lecture",
                    AppSettings.haptics.value) { AppSettings.haptics.value = it; SettingsStore.save() }
                SToggle(KetIcons.Canvas, "Pochette vivante", "Dans le lecteur, la pochette pulse très légèrement au rythme des basses",
                    AppSettings.livingArtwork.value) { AppSettings.livingArtwork.value = it; SettingsStore.save() }
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
                    sub = if (com.ketvoice.app.data.Seasons.inEffect() != null) "Le thème saisonnier est sombre ; votre choix revient quand il est désactivé"
                        else "S'applique immédiatement à toute l'app",
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
                // Noir (7.1, OLED): beside the appearance, pure black pages in dark mode.
                if (AppSettings.themeMode.value != 1) SToggle(KetIcons.Moon, "Fond noir",
                    "En mode sombre, les pages restent noir pur, même sous la couleur d'une pochette (écrans OLED)",
                    AppSettings.trueBlack.value) { AppSettings.trueBlack.value = it; SettingsStore.save() }
                SRow(
                    KetIcons.Appearance, "Couleur d'accent",
                    sub = if (com.ketvoice.app.data.Seasons.inEffect() != null) "La couleur du thème saisonnier la remplace tant qu'il est activé"
                        else "Barre de lecture, scrub, boutons et progression",
                    value = accentLabel(AppSettings.accentArgb.value)
                ) { accentOpen = true }
                SToggle(
                    KetIcons.Appearance, "Verre liquide",
                    "Flou, réfraction et reflets sur l'interface",
                    AppSettings.liquidGlass.value
                ) { AppSettings.liquidGlass.value = it; SettingsStore.save() }
                SToggle(
                    KetIcons.Sliders, "Mode performance",
                    "Garde les effets visibles et allège ceux qu'on remarque à peine : verre sans réfraction, paroles sans flou, fond animé à cadence réduite, images sans fondu",
                    AppSettings.highPerformanceMode.value
                ) { com.ketvoice.app.util.EconomyMode.manual(it); AppSettings.highPerformanceMode.value = it; SettingsStore.save() }
                // 8.2: low battery or a hot device holds performance mode on for as long as it lasts.
                SToggle(
                    KetIcons.Sliders, "Mode économie auto",
                    "Batterie à 20 % ou moins hors charge, ou appareil très chaud : le mode performance s'active le temps qu'il faut",
                    AppSettings.autoEconomy.value
                ) { AppSettings.autoEconomy.value = it; com.ketvoice.app.util.EconomyMode.autoChanged(); SettingsStore.save() }
                SToggle(
                    KetIcons.Appearance, "Flou dynamique",
                    "Réfraction en temps réel derrière les panneaux",
                    !AppSettings.reduceDynamicBlur.value
                ) { AppSettings.reduceDynamicBlur.value = !it; SettingsStore.save() }
                SRow(
                    KetIcons.Album, "Icône de l'app",
                    sub = "Change réellement l'icône du lanceur",
                    value = appIconLabel(AppSettings.appIconVariant.value)
                ) { iconPicker = true }
                if (iconPicker) AppIconPicker(AppSettings.appIconVariant.value, onPick = { next ->
                    AppSettings.appIconVariant.value = next
                    LauncherIcons.apply(ctx, next, notify = true)
                    SettingsStore.save()
                }, onClose = { iconPicker = false })
            }

            // ==================================================== LECTURE
            Section("LECTURE") {
                SToggle(KetIcons.Audio, "Normaliser le volume", "Tous les titres à un volume perçu proche, sans saut d'une chanson à l'autre",
                    AppSettings.volumeLeveling.value) { AppSettings.volumeLeveling.value = it; SettingsStore.save() }
                if (!com.ketvoice.app.BuildConfig.IS_QUEST) {
                    val ctx = LocalContext.current
                    var free by remember { mutableStateOf(com.ketvoice.app.data.BackgroundPlayback.unrestricted(ctx)) }
                    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { free = com.ketvoice.app.data.BackgroundPlayback.unrestricted(ctx); onPauseOrDispose { } }
                    SRow(KetIcons.Audio, "Lecture en arrière-plan",
                        sub = "Si la musique se coupe quand Ketvoice n'est pas à l'écran, laissez Android la jouer sans restriction de batterie.",
                        value = if (free) "Sans restriction" else "Limitée par l'économie de batterie") {
                        com.ketvoice.app.data.BackgroundPlayback.ask(ctx)
                    }
                    val vendor = remember { com.ketvoice.app.data.BackgroundPlayback.manufacturerScreen(ctx) != null }
                    if (vendor) SRow(KetIcons.Shield, "Gestionnaire de batterie du fabricant",
                        sub = if (com.ketvoice.app.data.BackgroundPlayback.honorLike()) "Lancement d'applis → Ketvoice : désactivez « Gérer automatiquement », puis activez « Exécution en arrière-plan ». Sans cela, ce téléphone coupe la musique."
                            else "Sur ce téléphone, autorisez aussi Ketvoice à démarrer et à rester actif en arrière-plan : c'est souvent lui qui coupe la musique.",
                        value = "Ouvrir") { com.ketvoice.app.data.BackgroundPlayback.openManufacturerScreen(ctx) }
                    SToggle(KetIcons.Bell, "Nouvelles sorties de vos artistes",
                        "Une notification quand un artiste que vous suivez sort un album, un single ou un EP. Vérifié deux fois par jour, au plus trois notifications à la fois.",
                        AppSettings.releaseAlerts.value) { AppSettings.releaseAlerts.value = it; SettingsStore.save() }
                    SToggle(KetIcons.Refresh, "Reprendre automatiquement la lecture après un arrêt par le système",
                        "Si Android ou le gestionnaire de batterie arrête Ketvoice pendant l'écoute, la musique reprend au même titre et au même endroit dans les minutes qui suivent. Jamais après une pause ou un arrêt de votre part.",
                        AppSettings.autoResume.value) { AppSettings.autoResume.value = it; SettingsStore.save() }
                    var exactAlarms by remember { mutableStateOf(com.ketvoice.app.player.PlaybackRevival.exactAllowed(ctx)) }
                    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { exactAlarms = com.ketvoice.app.player.PlaybackRevival.exactAllowed(ctx); onPauseOrDispose { } }
                    if (AppSettings.autoResume.value && !exactAlarms) SRow(KetIcons.Clock, "Reprise en moins d'une minute",
                        sub = "Autorisez Ketvoice dans « Alarmes et rappels » : après un arrêt par Android, la musique revient en moins d'une minute, même écran éteint.",
                        value = "Autoriser") {
                        runCatching {
                            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                android.net.Uri.parse("package:${ctx.packageName}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                    var journal by remember { mutableStateOf(false) }
                    SRow(KetIcons.Info, "Journal de lecture",
                        sub = "Ce qui a arrêté la musique : réseau perdu, autre app, fermeture par Android. Reste sur le téléphone.",
                        value = "Voir") { journal = true }
                    if (journal) PlaybackJournalDialog { journal = false }
                }
                SToggle(KetIcons.Shuffle, "Aléatoire avec moins de répétitions",
                    "Le mode aléatoire garde pour plus tard les titres écoutés récemment et n'enchaîne pas deux titres du même artiste quand il peut l'éviter.",
                    AppSettings.smartShuffle.value) { AppSettings.smartShuffle.value = it; SettingsStore.save() }
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
                SRow(com.ketvoice.app.ui.together.KetogetherIcon, "Ketogether", sub="Écouter ensemble, en même temps, avec une file partagée", value="") { com.ketvoice.app.together.Ketogether.sheet.value=true }
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

            // ==================================================== KETMIX
            Section("KETMIX (BÊTA)") {
                SToggle(KetIcons.Shuffle, "Ketmix",
                    "La fin d'un titre se mêle au début du suivant, comme le ferait un DJ : tempos accordés, basses échangées, écho, filtres ou réverbération selon les deux titres. Le chant n'est jamais coupé : une voix qui se termine passe en retrait sous le titre suivant.",
                    AppSettings.automix.value) { AppSettings.automix.value = it; SettingsStore.save() }
                SRow(KetIcons.Sliders, "Intensité du mix",
                    sub = "Longueur des transitions et écart de tempo accepté",
                    value = when (AppSettings.automixIntensity.value) { 0 -> "Discret"; 2 -> "DJ"; else -> "Équilibré" }) {
                    choice = ChoiceSpec("Intensité du mix", "S'applique dès la prochaine transition.",
                        AppSettings.automixIntensity.value.toString(), listOf(
                            ChoiceOption("0", "Discret", "Chaque titre joue jusqu'au bout, mix jusqu'à 8 mesures, tempos à 2 % près, pas d'écho"),
                            ChoiceOption("1", "Équilibré", "Mix jusqu'à 16 mesures, sous les 2 dernières mesures chantées, tempos à 3 % près"),
                            ChoiceOption("2", "DJ", "Mix jusqu'à 16 mesures, sous les 4 dernières mesures chantées, tempos à 4 % près"))
                    ) { id -> AppSettings.automixIntensity.value = id.toIntOrNull()?.coerceIn(0, 2) ?: 1; SettingsStore.save() }
                }
                SRow(KetIcons.Audio, "Analyse des titres",
                    sub = "Part du processeur du téléphone consacrée à l'écoute des titres avant un mix",
                    value = when (AppSettings.ketmixAnalysis.value) { 0 -> "Légère"; 2 -> "Maximale"; else -> "Équilibrée" }) {
                    choice = ChoiceSpec("Analyse des titres", "Les titres déjà analysés sont relus si le nouveau réglage en demande plus.",
                        AppSettings.ketmixAnalysis.value.toString(), listOf(
                            ChoiceOption("0", "Légère", "Tempo, temps et niveaux sur 30 secondes : le moins de batterie"),
                            ChoiceOption("1", "Équilibrée", "45 secondes par titre et sa tonalité : deux tonalités qui jurent ne se chevauchent pas longtemps"),
                            ChoiceOption("2", "Maximale", "75 secondes par titre, tonalité et tenue du tempo vérifiée : les mix les plus longs et les plus sûrs"))
                    ) { id -> AppSettings.ketmixAnalysis.value = id.toIntOrNull()?.coerceIn(0, 2) ?: 1; SettingsStore.save() }
                }
                SRow(KetIcons.Shuffle, "Style de transition",
                    sub = "Comment un titre passe au suivant",
                    value = when (AppSettings.ketmixStyle.value) { 1 -> "Calé sur le tempo"; 2 -> "Écho"; 3 -> "Filtres"; 4 -> "Fondu"; 5 -> "Montée"; 6 -> "Coupe"; 7 -> "Sur le drop"; 8 -> "Relais des voix"; 9 -> "Pont en boucle"; else -> "Automatique" }) {
                    choice = ChoiceSpec("Style de transition", "Ketmix garde toujours le chant intact et repasse à un autre style quand celui-ci ne convient pas aux deux titres.",
                        AppSettings.ketmixStyle.value.toString(), listOf(
                            ChoiceOption("0", "Automatique", "Ketmix choisit pour chaque paire de titres, d'après ce qu'il a mesuré"),
                            ChoiceOption("1", "Calé sur le tempo", "Les deux titres en rythme et les basses échangées sur un temps ; jamais d'écho"),
                            ChoiceOption("2", "Écho", "Le dernier temps résonne en écho, le titre suivant part sur ce temps"),
                            ChoiceOption("3", "Filtres", "Les deux titres se croisent par leurs fréquences : l'un s'amincit, l'autre s'ouvre"),
                            ChoiceOption("4", "Fondu", "Un fondu enchaîné, sans effet"),
                            ChoiceOption("5", "Montée", "Le titre monte en tension sur ses dernières mesures, le suivant tombe sur le premier temps"),
                            ChoiceOption("6", "Coupe", "Le titre s'arrête net sur le premier temps d'une phrase et le suivant part sur ce temps"),
                            ChoiceOption("7", "Sur le drop", "Le titre s'arrête sur une phrase et le suivant entre là où tout son rythme démarre"),
                            ChoiceOption("8", "Relais des voix", "Les deux titres se croisent et la voix qui part laisse la place à celle qui arrive"),
                            ChoiceOption("9", "Pont en boucle", "Le titre tourne sur quatre mesures sans chant pendant que le tempo glisse jusqu'à celui du suivant, même très éloigné"))
                    ) { id -> AppSettings.ketmixStyle.value = id.toIntOrNull()?.coerceIn(0, 9) ?: 0; SettingsStore.save() }
                }
                SToggle(KetIcons.Album, "Mixer aussi les albums",
                    "Les titres d'un album écouté dans l'ordre sont mixés aussi, sauf si l'album n'a aucun blanc entre ses titres.",
                    AppSettings.automixAlbums.value) { AppSettings.automixAlbums.value = it; SettingsStore.save() }
                SToggle(KetIcons.Audio, "Vibrer à l'échange des basses",
                    "Une petite vibration au moment où la basse passe d'un titre à l'autre, quand l'app est à l'écran.",
                    AppSettings.ketmixHaptics.value) { AppSettings.ketmixHaptics.value = it; SettingsStore.save() }
                SToggle(KetIcons.Audio, "Séparer les voix",
                    "Pendant un mix, la voix du titre en cours s'arrête et le suivant continue sur son instrumental, puis sa voix le rejoint ; quand tempo et tonalité s'accordent, la voix du suivant chante sur l'instrumental de celui en cours. Avec le modèle de séparation, la voix est vraiment retirée ; sans lui, elle est seulement atténuée.",
                    AppSettings.ketmixStems.value) { AppSettings.ketmixStems.value = it; SettingsStore.save() }
                // 7.0.0: the rhythm model is in the mobile and Quest APKs, a switch rather than a download.
                if (com.ketvoice.app.automix.BeatModel.bundled) SToggle(KetIcons.Sliders, "Calage précis du rythme",
                    "Un petit modèle d'IA intégré trouve les temps et les mesures pour des mélanges mieux calés.",
                    AppSettings.ketmixBeatModel.value) { AppSettings.ketmixBeatModel.value = it; SettingsStore.save() }
                if (AppSettings.ketmixStems.value) StemSettings()
                SToggle(KetIcons.Shuffle, "File intelligente",
                    "En lecture aléatoire, le titre suivant est choisi parmi les prochains pour s'accorder au tempo et à la tonalité de celui en cours. Une file que vous avez ordonnée et un titre demandé « à la suite » ne bougent jamais.",
                    AppSettings.ketmixSmartQueue.value) { AppSettings.ketmixSmartQueue.value = it; SettingsStore.save() }
                SToggle(KetIcons.Play, "Mixer quand je passe au suivant",
                    "Activé : « Suivant » enchaîne avec un vrai mix. Désactivé : le titre suivant démarre tout de suite, avec un fondu très court ; les fins de titres restent mixées.",
                    AppSettings.ketmixOnSkip.value) { AppSettings.ketmixOnSkip.value = it; SettingsStore.save() }
                KetmixLibraryRow()
                SToggle(KetIcons.Refresh, "Mixer aussi un titre en boucle",
                    "Quand un titre est répété, sa fin se mêle à son propre début, sur le temps, au lieu de repartir après un blanc.",
                    AppSettings.ketmixLoops.value) { AppSettings.ketmixLoops.value = it; SettingsStore.save() }
                com.ketvoice.app.ui.player.AutomixStatusLine(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), th.secondary, 12.sp)
                KetmixJournalPanel()
                Text("Ketmix est en bêta. La minuterie et la sortie bit-perfect ne sont jamais mixées. Une dernière note n'est jamais couverte : le titre suivant part juste après, sur le temps.",
                    color = th.secondary, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 8.dp))
            }

            // ==================================================== WIDGETS (6.4, phones only)
            if (!com.ketvoice.app.BuildConfig.IS_QUEST) Section("WIDGETS") { WidgetShortcutRows() }

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
                // 8.4: the order the automatic lookup tries the sources in.
                SRow(KetIcons.Lyrics, "Ordre des sources de paroles",
                    sub = "Les sources sont essayées dans cet ordre : la première qui a des paroles gagne",
                    value = if (com.ketvoice.app.data.LyricsOrder.custom(AppSettings.lyricsOrder.value)) "Personnalisé" else "Par défaut"
                ) { lyricsOrderOpen = true }
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
                if (!com.ketvoice.app.BuildConfig.IS_QUEST) LockscreenLyricsRows()
                // 8.3: offered where it is drawn (Android 12 and later, not on Quest, not on a modest phone in Lite).
                if (com.ketvoice.app.ui.player.LyricsBlur.deviceSupported()) SToggle(KetIcons.Lyrics, "Flou des paroles",
                    "Les lignes éloignées de celle chantée s'estompent dans un léger flou.",
                    AppSettings.lyricsBlur.value) { AppSettings.lyricsBlur.value = it; SettingsStore.save() }
                if (!com.ketvoice.app.BuildConfig.IS_QUEST) SToggle(KetIcons.Clock, "Mode veille au chargement",
                    "En charge et à l'horizontale pendant l'écoute : la pochette ou le Canvas, l'heure et les paroles en plein écran. Aussi proposé comme économiseur d'écran d'Android.",
                    AppSettings.standbyMode.value) { AppSettings.standbyMode.value = it; SettingsStore.save() }
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
                CanvasCacheRows { choice = it }
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
                SToggle(KetIcons.Download, "Favoris hors ligne automatiquement",
                    "Un titre ajouté aux favoris se télécharge tout seul, pour l'écouter sans connexion",
                    AppSettings.autoOfflineFavorites.value) { AppSettings.autoOfflineFavorites.value = it; SettingsStore.save() }
                SToggle(KetIcons.Wifi, "Téléchargements en Wi-Fi uniquement", "Les transferts attendent une connexion Wi-Fi", AppSettings.downloadsWifiOnly.value) {
                    AppSettings.downloadsWifiOnly.value = it; SettingsStore.save()
                    runCatching { com.ketvoice.app.data.PlaylistUpdates.schedule(ctx, replace = true) }
                }
                // 8.4: new titles of a downloaded playlist, announced in the background.
                SToggle(KetIcons.Bell, "Playlists téléchargées à jour",
                    "Une notification quand une playlist téléchargée reçoit de nouveaux titres : touchez-la pour télécharger seulement ceux-là",
                    AppSettings.playlistUpdates.value) {
                    AppSettings.playlistUpdates.value = it; SettingsStore.save()
                    runCatching { com.ketvoice.app.data.PlaylistUpdates.schedule(ctx, replace = true) }
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
            Section("HORS LIGNE INTELLIGENT") { SmartOfflineRows { choice = it } }

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
                    "Compare tes fichiers au catalogue et propose artiste, album et pochette. Rien ne change sans ton accord, et tes fichiers restent intacts.",
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
                SRow(KetIcons.Wifi, "Air Ket", sub = "Tout transférer vers un autre téléphone, en Wi-Fi") {
                    com.ketvoice.app.airket.AirKet.sheet.value = com.ketvoice.app.airket.AirKet.Mode.Menu
                }
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
                    value = com.ketvoice.app.BuildConfig.VERSION_NAME + com.ketvoice.app.Edition.current.label
                ) {}
                if (com.ketvoice.app.LiteTuning.lite) LiteAboutRow()
                DiagnosticRows()
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
    if (lyricsOrderOpen) LyricsOrderSheet(onClose = { lyricsOrderOpen = false })
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
internal data class ChoiceOption(val id: String, val label: String, val sub: String = "")
internal data class ChoiceSpec(
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

private fun appIconLabel(value: Int) = AppIcons.options.firstOrNull { it.id == value }?.label ?: "GTA VI"

/** The launcher icons: the GTA VI K (6.1, the default since 7.6) and the owner's glass K of 6.0.0 Beta 4. */
internal object AppIcons {
    /**
     * [ground] is the colour Android draws under the glass (an adaptive icon's background is always filled);
     * a [fullBleed] preview is the whole icon, background included (the GTA VI K). A [brand] label is not translated.
     */
    class Option(val id: Int, val label: String, val preview: Int, val ground: Long, val fullBleed: Boolean = false,
                 val note: String? = null, val brand: Boolean = false)
    val options: List<Option> = listOf(
        Option(com.ketvoice.app.util.LauncherIcons.DEFAULT, "GTA VI", com.ketvoice.app.R.drawable.vicecity_logo, 0xFF120A22,
            fullBleed = true, note = "Par défaut", brand = true),
        Option(1, "Liquid Glass", com.ketvoice.app.R.drawable.icon_preview_glass, 0xFF000000),
        Option(3, "Clair", com.ketvoice.app.R.drawable.icon_preview_light, 0xFFF4F6F8),
        Option(2, "Teinté", com.ketvoice.app.R.drawable.icon_preview_tinted, 0xFF000000),
        Option(0, "Transparent", com.ketvoice.app.R.drawable.icon_preview_clear, 0xFFF4F6F8))
}

/** The icon chooser: each icon shown as on a home screen, over a wallpaper, the chosen one ringed. */
@Composable
internal fun AppIconPicker(current: Int, onPick: (Int) -> Unit, onClose: () -> Unit) {
    val theme = LocalTheme.current
    var chosen by remember { mutableStateOf(current) }
    com.ketvoice.app.ui.common.AppAlertDialog(
        onDismissRequest = onClose,
        title = { Text("Icône de l'app") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("L'icône du lanceur change immédiatement.",
                    fontSize = 13.sp, lineHeight = 17.sp, color = theme.secondary)
                AppIcons.options.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        row.forEach { option ->
                            val selected = chosen == option.id
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).clickable {
                                chosen = option.id; onPick(option.id)
                            }.padding(4.dp).testTag("icon-option-${option.id}"), horizontalAlignment = Alignment.CenterHorizontally) {
                                // As the launcher shows it: the glass K on its own ground, in the launcher's rounded shape.
                                Box(Modifier.size(104.dp).clip(RoundedCornerShape(26.dp))
                                    .background(androidx.compose.ui.graphics.Color(option.ground))
                                    .border(if (selected) 3.dp else 0.dp, if (selected) theme.accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(26.dp)),
                                    contentAlignment = Alignment.Center) {
                                    if (option.fullBleed) androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(option.preview), null,
                                        Modifier.matchParentSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                    else androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(option.preview), null, Modifier.size(84.dp))
                                    if (option.fullBleed && selected) Box(Modifier.matchParentSize()
                                        .border(3.dp, theme.accent, RoundedCornerShape(26.dp)))
                                }
                                Text(option.label, fontSize = 13.sp, color = if (selected) theme.accent else theme.onBackground,
                                    modifier = Modifier.padding(top = 6.dp), maxLines = 1, localize = !option.brand)
                                option.note?.let { Text(it, fontSize = 11.sp, color = theme.secondary, maxLines = 1) }
                            }
                        }
                    }
                }
                // The GTA VI K is fan art: said under the icons whenever it is shown.
                Text("Ketvoice n'est pas affilié à Rockstar Games.", fontSize = 12.sp, lineHeight = 16.sp,
                    color = theme.secondary, modifier = Modifier.testTag("icon-rockstar-note"))
            }
        },
        confirmButton = { com.ketvoice.app.ui.common.KetTextButton("OK", onClose, primary = true) })
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
    // Ket 27: the picker of iOS 27 — an inset group of 52 dp rows on the glass sheet, the choice
    // marked in the app's colour, and a tick in the hand when it is made.
    val tick = com.ketvoice.app.ui.motion.rememberHaptics()
    SettingsSheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().heightIn(max = com.ketvoice.app.ui.common.sheetMaxHeight()).verticalScroll(rememberScrollState())
            .padding(horizontal = Ket27.Metrics.margin, vertical = 16.dp)) {
            SettingsSheetHeader(spec.title, spec.subtitle, onDismiss)
            Spacer(Modifier.height(18.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Ket27.Metrics.groupRadius))
                .background(th.card.copy(alpha = if (th.isDark) .62f else .78f))
                .selectableGroup().testTag("choice-options")) {
                spec.options.forEachIndexed { index, option ->
                    val chosen = option.id == spec.selectedId
                    Row(Modifier.fillMaxWidth().rowPress(Role.RadioButton) {
                            tick(com.ketvoice.app.ui.motion.Haptics.Kind.Tick); spec.onSelect(option.id); onDismiss()
                        }.semantics { selected = chosen }
                        .heightIn(min = Ket27.Metrics.row).padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(end = 14.dp)) {
                            Text(option.label, localize = spec.title != "Langue de l'application", color = th.onBackground,
                                fontSize = 17.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp)
                            if (option.sub.isNotBlank()) Text(option.sub, color = th.secondary,
                                fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 1.dp))
                        }
                        if (chosen) Icon(KetIcons.Check, null, tint = th.accent, modifier = Modifier.size(20.dp))
                        else Spacer(Modifier.size(20.dp))
                    }
                    if (index != spec.options.lastIndex) Box(Modifier.padding(start = 16.dp).fillMaxWidth().height(.5.dp)
                        .background(th.hairline))
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
        Column(Modifier.fillMaxWidth().heightIn(max = com.ketvoice.app.ui.common.sheetMaxHeight()).verticalScroll(rememberScrollState()).padding(20.dp)) {
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
        Column(Modifier.fillMaxWidth().heightIn(max = com.ketvoice.app.ui.common.sheetMaxHeight()).verticalScroll(rememberScrollState()).padding(20.dp)) {
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
    val margin = Ket27.Metrics.margin
    SettingsGroupHeading(header, Modifier.padding(horizontal = margin))
    Column(Modifier.padding(horizontal = margin).fillMaxWidth().settingsGroup(), content = content)
}

/**
 * Ketvoice Lite in À propos (owner, 2026-11-18): which mode is active and why — « Ce téléphone a 2 Go de mémoire :
 * effets allégés, identiques à l'œil » — and, on Android 11, that the glass is blurred by Ketvoice itself.
 */
@Composable
private fun ColumnScope.LiteAboutRow() {
    val tablet = androidx.compose.ui.platform.LocalConfiguration.current.smallestScreenWidthDp >= 600
    val tuning = com.ketvoice.app.LiteTuning
    val line = tuning.aboutLine(tablet = tablet)
    val sub = if (com.ketvoice.app.glass.SoftBlur.needed()) line + "\n" + tuning.ANDROID_11_LINE else line
    SRow(KetIcons.Sliders, "Ketvoice Lite", sub = sub, value = if (tuning.modest) "Mode allégé" else "Mode standard")
}

@Composable
internal fun ColumnScope.SRow(
    icon: ImageVector,
    title: String,
    sub: String = "",
    value: String = "",
    onClick: (() -> Unit)? = null
) {
    val th = LocalTheme.current
    Row(
        Modifier.fillMaxWidth().settingsTarget(title)
            .then(if (onClick != null) Modifier.rowPress(onClick = onClick) else Modifier)
            .heightIn(min = Ket27.Metrics.row).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Ket27Tile(icon, title.length + title.first().code)
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 12.dp)) {
            Text(title, color = th.onBackground, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
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

/**
 * « Paroles sur l'écran verrouillé » (6.1): the switch, then what Android needs for it — the notifications
 * allowed, or the notification's own settings (silent notifications hidden on the lock screen, Live Updates).
 */
@Composable
private fun ColumnScope.LockscreenLyricsRows() {
    val ctx = LocalContext.current
    var allowed by remember { mutableStateOf(com.ketvoice.app.player.LyricsNotification.allowed(ctx)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        val now = com.ketvoice.app.player.LyricsNotification.allowed(ctx)
        if (now != allowed) com.ketvoice.app.player.LiveLyrics.demandChanged()
        allowed = now
        onPauseOrDispose { }
    }
    SToggle(KetIcons.Lyrics, "Paroles sur l'écran verrouillé",
        "La ligne chantée dans une notification silencieuse, sur l'écran verrouillé et dans le volet ; la suivante en dessous.",
        AppSettings.lockscreenLyrics.value) {
        AppSettings.lockscreenLyrics.value = it
        SettingsStore.save()
        com.ketvoice.app.player.LiveLyrics.demandChanged()
    }
    if (!AppSettings.lockscreenLyrics.value) return
    if (!allowed) SRow(KetIcons.Info, "Notifications désactivées",
        sub = "Autorisez les notifications de Ketvoice pour voir les paroles sur l'écran verrouillé.",
        value = "Autoriser") {
        runCatching {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    } else SRow(KetIcons.Info, "Notification des paroles",
        sub = "Si l'écran verrouillé masque les notifications silencieuses, rendez celle-ci visible dans les réglages d'Android.",
        value = "Réglages d'Android") {
        com.ketvoice.app.player.LyricsNotification.ensureChannel(ctx)
        runCatching {
            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, com.ketvoice.app.player.LyricsNotification.CHANNEL)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/**
 * A seasonal theme (6.2) while its window lasts: the switch, its emblem and a small picture of it. Like Vice City,
 * it lays its own look over the listener's theme, accent and colours, which come back when it is turned off.
 */
@Composable
private fun ColumnScope.SeasonThemeRow(season: com.ketvoice.app.data.SeasonalTheme) {
    val th = LocalTheme.current
    val look = com.ketvoice.app.ui.seasons.SeasonLooks.of(season.id) ?: return
    val tick = com.ketvoice.app.ui.motion.rememberHaptics()
    val on = com.ketvoice.app.data.Seasons.chosen(season)
    Column(Modifier.fillMaxWidth().toggleable(on, role = Role.Switch, onValueChange = {
            tick(com.ketvoice.app.ui.motion.Haptics.Kind.Tick); com.ketvoice.app.data.Seasons.choose(season, it); SettingsStore.save()
        }).padding(horizontal = 16.dp, vertical = 12.dp).testTag("season-theme-row")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            look.Emblem(Ket27.Metrics.tile, Modifier)
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 14.dp)) {
                Text(season.title, color = th.onBackground, fontSize = 17.sp, lineHeight = 22.sp)
                Text(season.until, color = th.secondary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 3.dp))
            }
            com.ketvoice.app.ui.common.LiquidGlassSwitch(on, onCheckedChange = null)
        }
        look.Preview(Modifier.padding(top = 12.dp), 92.dp)
        Text(season.description, color = th.secondary, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp))
    }
    SettingsCellDivider()
}

@Composable
internal fun ColumnScope.SToggle(
    icon: ImageVector,
    title: String,
    sub: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit
) {
    val th = LocalTheme.current
    // Ket 27: flipping a switch is felt, as on iOS.
    val tick = com.ketvoice.app.ui.motion.rememberHaptics()
    Row(
        Modifier.fillMaxWidth().settingsTarget(title).toggleable(checked, role = Role.Switch,
            onValueChange = { tick(com.ketvoice.app.ui.motion.Haptics.Kind.Tick); onChecked(it) })
            .heightIn(min = Ket27.Metrics.row).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 8.0: switched on, the symbol reacts — the bell of new releases rings, any other bounces.
        Box(if (icon == KetIcons.Bell) Modifier.symbolWiggle(checked, enabled = checked)
            else Modifier.symbolBounce(checked, enabled = checked)) { Ket27Tile(icon, title.length + title.first().code) }
        Column(Modifier.weight(1f).padding(start = 12.dp, end = 14.dp)) {
            Text(title, color = th.onBackground, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Normal)
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
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Ket27Tile(KetIcons.Storage, "Limite du cache des chansons".let { it.length + it.first().code })
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("Limite du cache des chansons", color = th.onBackground, fontSize = 17.sp,
                    lineHeight = 22.sp)
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

/**
 * The last transitions Ketmix played or gave up, and a button to copy them: what a listener
 * sends when a mix sounded wrong, so that it can be understood and fixed.
 */
@Composable
private fun KetmixJournalPanel() {
    val th = LocalTheme.current
    val entries by com.ketvoice.app.automix.KetmixJournal.entries.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("ketmix-journal"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Journal des mix", color = th.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (entries.isEmpty()) Text("Aucune transition depuis le lancement de l'app.", color = th.secondary, fontSize = 12.sp)
        entries.take(5).forEach { e ->
            Text("${e.from} → ${e.to}", localize = false, color = th.onBackground, fontSize = 12.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(if (e.kind != com.ketvoice.app.automix.MixKind.None) "${e.kind.name} · ${e.reason}" + (if (e.precise == false) " · fade only" else "")
                else e.outcome, localize = false, color = th.secondary, fontSize = 11.sp, maxLines = 1)
        }
        if (entries.isNotEmpty()) com.ketvoice.app.ui.common.GlassCapsuleButton("Copier le journal", null, {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Ketmix", com.ketvoice.app.automix.KetmixJournal.text(entries)))
            android.widget.Toast.makeText(context, com.ketvoice.app.data.AppText.t("Journal copié"), android.widget.Toast.LENGTH_SHORT).show()
        }, Modifier.fillMaxWidth().padding(top = 4.dp), tag = "ketmix-journal-copy", minHeight = 44.dp)
    }
}


/** The optional separation model and the separations already made (5.10.0). */
@Composable
private fun ColumnScope.StemSettings() {
    val fast by com.ketvoice.app.stems.StemModel.fast.state.collectAsState()
    val hq by com.ketvoice.app.stems.StemModel.hq.state.collectAsState()
    val beat by com.ketvoice.app.automix.BeatModel.pack.state.collectAsState()
    var usage by remember { mutableStateOf(0 to 0L) }
    var confirm by remember { mutableStateOf<com.ketvoice.app.stems.ModelPack?>(null) }
    var qualityOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(fast, hq) {
        usage = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.ketvoice.app.stems.StemStore.usage() }
    }
    @Composable
    fun ModelRow(pack: com.ketvoice.app.stems.ModelPack, state: com.ketvoice.app.stems.ModelPack.State, title: String, sub: String) {
        val size = "${pack.totalBytes / 1_000_000L} Mo"
        SRow(KetIcons.Download, title,
            sub = com.ketvoice.app.data.AppText.t(sub).replace("{0}", size),
            value = when (state) {
                com.ketvoice.app.stems.ModelPack.State.Missing -> "Télécharger"
                is com.ketvoice.app.stems.ModelPack.State.Downloading -> "Téléchargement · ${(state.fraction * 100).toInt()} %"
                com.ketvoice.app.stems.ModelPack.State.Ready -> "Prêt"
                is com.ketvoice.app.stems.ModelPack.State.Failed -> "Échec · réessayer"
            }) {
            when (state) {
                com.ketvoice.app.stems.ModelPack.State.Ready -> confirm = pack
                is com.ketvoice.app.stems.ModelPack.State.Downloading -> pack.cancel()
                else -> pack.download()
            }
        }
    }
    ModelRow(com.ketvoice.app.stems.StemModel.fast, fast, "Modèle de séparation",
        "Séparation par IA sur le téléphone (Spleeter), téléchargée une fois : {0}")
    ModelRow(com.ketvoice.app.stems.StemModel.hq, hq, "Modèle haute qualité",
        "Voix et instrumentaux bien plus nets (UVR MDX-Net), environ dix fois plus lent : {0}. Il sert téléphone en charge et améliore alors les titres déjà séparés.")
    // 7.0.0: the mobile and Quest APKs carry the rhythm model (its switch is above); Lite still downloads it.
    if (!com.ketvoice.app.automix.BeatModel.bundled) ModelRow(com.ketvoice.app.automix.BeatModel.pack, beat, "Calage précis du rythme (Ketmix)",
        "Un petit modèle d'IA trouve les temps et les mesures pour des mélanges mieux calés : {0}")
    SRow(KetIcons.Sliders, "Qualité de séparation",
        sub = "Quel modèle sépare les titres",
        value = when (AppSettings.ketmixStemsQuality.value) { 1 -> "Toujours la meilleure"; 2 -> "Toujours rapide"; else -> "Automatique" }) { qualityOpen = true }
    SRow(KetIcons.Storage, "Titres séparés",
        sub = "Chaque titre n'est séparé qu'une fois ; ses mix suivants réutilisent la séparation. Toucher pour tout effacer.",
        value = "${usage.first} · ${"%.1f".format(usage.second / 1_000_000.0)} Mo") {
        scope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.ketvoice.app.stems.StemStore.clear() }
            usage = 0 to 0L
        }
    }
    SToggle(KetIcons.Audio, "Séparer seulement en charge",
        "Les nouvelles séparations n'ont lieu que téléphone branché ; celles déjà faites servent toujours.",
        AppSettings.ketmixStemsCharging.value) { AppSettings.ketmixStemsCharging.value = it; SettingsStore.save() }
    if (qualityOpen) {
        val options = listOf(
            "0" to ("Automatique" to "Le modèle rapide sur batterie, le meilleur téléphone en charge ; les titres déjà séparés sont alors améliorés"),
            "1" to ("Toujours la meilleure" to "Le modèle haute qualité tout le temps : plus de batterie, et un seul titre préparé à l'avance sur batterie"),
            "2" to ("Toujours rapide" to "Le modèle rapide seulement : le moins de batterie"))
        com.ketvoice.app.ui.common.AppAlertDialog(
            onDismissRequest = { qualityOpen = false },
            title = { Text(com.ketvoice.app.data.AppText.t("Qualité de séparation")) },
            text = {
                Column {
                    options.forEach { (id, label) ->
                        Column(Modifier.fillMaxWidth().clickable {
                            AppSettings.ketmixStemsQuality.value = id.toInt(); SettingsStore.save(); qualityOpen = false
                        }.padding(vertical = 10.dp)) {
                            Text((if (AppSettings.ketmixStemsQuality.value == id.toInt()) "✓ " else "") + com.ketvoice.app.data.AppText.t(label.first), fontSize = 16.sp)
                            Text(com.ketvoice.app.data.AppText.t(label.second), fontSize = 12.sp, lineHeight = 16.sp)
                        }
                    }
                }
            },
            confirmButton = { com.ketvoice.app.ui.common.KetTextButton("Fermer", { qualityOpen = false }, primary = true) })
    }
    confirm?.let { pack ->
        val rhythm = pack === com.ketvoice.app.automix.BeatModel.pack
        com.ketvoice.app.ui.common.AppAlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(com.ketvoice.app.data.AppText.t(if (rhythm) "Supprimer le modèle de rythme ?" else "Supprimer le modèle de séparation ?")) },
            text = { Text(com.ketvoice.app.data.AppText.t(if (rhythm) "Les mix reviennent au calage par analyse du son. Les titres déjà analysés restent." else "Les mix atténueront la voix au lieu de la retirer. Les titres déjà séparés restent.")) },
            confirmButton = { com.ketvoice.app.ui.common.KetTextButton("Supprimer", { confirm = null; pack.delete() },
                primary = true, destructive = true) },
            dismissButton = { com.ketvoice.app.ui.common.KetTextButton("Annuler", { confirm = null }) })
    }
}


/** The playback journal, to read or share (5.10.0). */
@Composable
private fun PlaybackJournalDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val lines = remember { com.ketvoice.app.data.PlaybackJournal.snapshot().takeLast(120).reversed() }
    com.ketvoice.app.ui.common.AppAlertDialog(
        onDismissRequest = onClose,
        title = { Text("Journal de lecture") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (lines.isEmpty()) item { Text("Rien à signaler pour l'instant.", fontSize = 13.sp) }
                items(lines.size) { i -> Text(lines[i], localize = false, fontSize = 11.sp, lineHeight = 15.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) }
            }
        },
        confirmButton = { com.ketvoice.app.ui.common.KetTextButton("Partager", {
            val text = "Ketvoice ${com.ketvoice.app.BuildConfig.VERSION_NAME}${com.ketvoice.app.Edition.current.label} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}\n\n" +
                com.ketvoice.app.data.PlaybackJournal.snapshot().joinToString("\n")
            runCatching { ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text), null).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, primary = true) },
        dismissButton = { com.ketvoice.app.ui.common.KetTextButton("Fermer", onClose) })
}

/** Downloaded titles prepared ahead for Ketmix while the phone charges (5.11.0). */
@Composable
private fun ColumnScope.KetmixLibraryRow() {
    val progress by com.ketvoice.app.stems.StemLibrary.progress.collectAsState()
    SToggle(KetIcons.Download, "Préparer les titres téléchargés",
        if (progress.running) com.ketvoice.app.data.AppText.t("Préparation en cours : {0} sur {1}").replace("{0}", progress.done.toString()).replace("{1}", progress.total.toString())
        else "Téléphone en charge, les titres téléchargés sont analysés (et leurs voix séparées si le modèle est là) avant même d'être joués : leurs mix sont prêts tout de suite.",
        AppSettings.ketmixLibrary.value) {
        AppSettings.ketmixLibrary.value = it; SettingsStore.save()
        if (it) com.ketvoice.app.stems.StemLibrary.wake()
    }
}
