package com.ketvoice.app.data

import androidx.compose.runtime.mutableStateOf

object AppSettings {

    val themeMode = mutableStateOf(0)
    val accentArgb = mutableStateOf(0xFFFF2D55L)
    val syncYouTubeHistory = mutableStateOf(true)
    val personalizedYouTubeHome = mutableStateOf(true)
    val reduceAnimations = mutableStateOf(false)
    val reduceDynamicBlur = mutableStateOf(false)
    val liquidGlass = mutableStateOf(true)
    val fullArtwork = mutableStateOf(false)
    val classicGradient = mutableStateOf(false)
    val animatedArtwork = mutableStateOf(true)
    /** Album/collection pages may show the album Canvas instead of the cover. */
    val albumHeroCanvas = mutableStateOf(true)
    /** 7.3: album and playlist headers — 0 centred cover (default), 1 full-screen Canvas, 2 cover over the Canvas (ui/sub/AlbumHeader). */
    val albumHeader = mutableStateOf(0)
    /** 0 clear optical glass, 1 frosted, 2 opaque accessibility material. */
    val glassStyle = mutableStateOf(0)
    /**
     * The look of the glass (8.4): 0 Liquid 27 (the default, unchanged), 1 « Cristal » — a lighter optical glass with its
     * own motion ([com.ketvoice.app.glass.GlassLook]). A new key: `glassStyle` holds values of older versions.
     */
    val glassLook = mutableStateOf(0)
    /**
     * The intensity of the glass (7.5), 0 very clear .. 1 opaque, continuous ([com.ketvoice.app.glass.GlassClarity]).
     * Replaces `glassTintLevel` (6.0, 0..6) and `glassTinted` (7.1), converted once by [SettingsStore].
     */
    val glassIntensity = mutableStateOf(com.ketvoice.app.glass.GlassClarity.DEFAULT)
    /** « Fond noir » (7.1, OLED): in dark mode, the pages stay pure black even under a cover's colours. */
    val trueBlack = mutableStateOf(false)
    /** The reflections of the glass follow the phone's tilt (never on Quest, with reduced motion or in the performance mode). */
    val glassTilt = mutableStateOf(true)
    val downloadsWifiOnly = mutableStateOf(false)
    /** « Playlists téléchargées à jour » (8.4): new titles of a downloaded playlist are announced, to download in a touch. */
    val playlistUpdates = mutableStateOf(true)
    /** A title made a favourite is downloaded for listening offline. */
    val autoOfflineFavorites = mutableStateOf(true)
    /** « Hors ligne intelligent » (7.0): what the listener plays most and their new favourites, downloaded on their own; off by default. */
    val smartOffline = mutableStateOf(false)
    /** Its room: 1, 2, 5 or 10 GB, or 0 for a share of the free storage ([smartOfflinePercent]). */
    val smartOfflineGb = mutableStateOf(SmartOfflinePlan.DEFAULT_GB)
    val smartOfflinePercent = mutableStateOf(SmartOfflinePlan.DEFAULT_PERCENT)
    /** Its passes wait for the phone to charge (on by default). */
    val smartOfflineCharging = mutableStateOf(true)
    val panoramicCanvas = mutableStateOf(false)
    val sonicCanvas = mutableStateOf(false)
    val albumColors = mutableStateOf(true)
    val artworkDepth = mutableStateOf(true)
    /** The player's artwork pulses very slightly with the bass of the music. */
    val livingArtwork = mutableStateOf(true)
    /** The accent of the whole interface follows the cover playing (5.9.0); off by default: the chosen accent rules. */
    val coverAccent = mutableStateOf(false)
    /** Every title at about the same loudness (5.9.0, a slow leveler in the audio chain); off by default. */
    val volumeLeveling = mutableStateOf(false)
    /** Every page of the app takes the colour of the cover playing, like an album page (5.9.1); off by default. */
    val coverBackground = mutableStateOf(false)
    /** Ketmix separates voices from instruments in real time for its transitions (5.9.1, experimental); off by default. */
    val ketmixStems = mutableStateOf(false)
    /** New separations only while the phone charges (those already made are always used). */
    val ketmixStemsCharging = mutableStateOf(false)
    /** Which separation model: 0 automatic (the better one while charging), 1 always the better one, 2 always the quick one (6.0.0; the default in Lite). */
    val ketmixStemsQuality = mutableStateOf(com.ketvoice.app.LiteTuning.defaultStemsQuality())
    /** Level of the voice in titles separated whole, 0..100 (6.0.0, the slider of the lyrics screen). */
    val voiceLevel = mutableStateOf(100)
    /** In shuffle, the next title is the one that mixes best with the current one (5.11.0). */
    val ketmixSmartQueue = mutableStateOf(true)
    /** « Mixer quand je passe au suivant » (7.5): off, « Suivant » cuts at once (a short fade) even with Ketmix on. */
    val ketmixOnSkip = mutableStateOf(true)
    /** « Calage précis du rythme » (7.0.0): the rhythm model carried by the mobile and Quest APKs runs; Lite downloads it instead. */
    val ketmixBeatModel = mutableStateOf(true)
    /** Downloaded titles are measured and separated ahead while the phone charges (5.11.0). */
    val ketmixLibrary = mutableStateOf(true)
    /** Playback that Android or a battery manager stopped while it played comes back at the same title and place (6.1). */
    val autoResume = mutableStateOf(true)
    /** The sung line in a notification of its own, on the lock screen and in the shade (6.1). */
    val lockscreenLyrics = mutableStateOf(true)
    /** « Flou des paroles » (8.3, owner lifts the 6.1 rule): lines away from the sung one are softly blurred. */
    val lyricsBlur = mutableStateOf(true)
    /** « Mode veille au chargement » (7.5): an ambient Now Playing while charging in landscape; off by default. */
    val standbyMode = mutableStateOf(false)
    /** Lyrics videos carry the song's sound (5.10.0). */
    val lyricVideoSound = mutableStateOf(true)
    /** « Bande-son du récap annuel » (8.4): Wrapped's pages play the best part of their titles; the story's mute button turns it off. */
    val wrappedSound = mutableStateOf(true)
    val systemReduceMotion = mutableStateOf(false)
    val canvasWithMobileData = mutableStateOf(false)
    /** A notification when an artist the listener follows puts out a release (6.3, see ReleaseWatch). */
    val releaseAlerts = mutableStateOf(true)
    /** The most the Canvas files may use on the device, in MB: 256, 512 (default), 1024 or 2048 (6.2.3). */
    val canvasCacheMb = mutableStateOf(512)
    /** The Canvas of favourites and playlists are fetched ahead on Wi-Fi (6.2.3). */
    val canvasPreloadWifi = mutableStateOf(true)
    val artistSignatures = mutableStateOf(false)
    val artistPhotos = mutableStateOf(true)
    val highRefreshRate = mutableStateOf(false)
    val syncedLyrics = mutableStateOf(true)
    /** Titles are mixed into each other at the end, like a DJ; off by default. */
    val automix = mutableStateOf(false)
    /** 0 = discreet, 1 = balanced, 2 = DJ. */
    val automixIntensity = mutableStateOf(1)
    /** Titles of an album played in order are mixed too (unless the album has no gap to mix over). */
    val automixAlbums = mutableStateOf(true)
    /** How much of the processor Ketmix's analysis may take: 0 = light, 1 = balanced, 2 = maximum. */
    val ketmixAnalysis = mutableStateOf(1)
    /** A title on repeat is mixed into itself at each loop instead of starting again after a silence. */
    val ketmixLoops = mutableStateOf(true)
    /** Shuffle keeps titles heard recently for later and does not put two titles of an artist in a row. */
    val smartShuffle = mutableStateOf(true)
    /** Ketmix transition style: 0 = automatic, 1 = matched to the tempo, 2 = echo, 3 = filters, 4 = fade, 5 = build (rise). */
    val ketmixStyle = mutableStateOf(0)
    /** A light vibration when the bass passes from one title to the next (app in front only). */
    val ketmixHaptics = mutableStateOf(false)
    val lyricsSource = mutableStateOf("auto")
    /** « Ordre des sources de paroles » (8.4): the automatic lookup's order, ids joined by commas; empty is the default ([LyricsOrder]). */
    val lyricsOrder = mutableStateOf("")
    val betterLyricsKey = mutableStateOf("")
    val lyricaUrl = mutableStateOf("")
    val translationSource = mutableStateOf("en")
    val translateLanguage = mutableStateOf("Français")
    /** 7.5: pronunciation (romanization) under each lyric line written in another script. */
    val lyricsRomanization = mutableStateOf(false)

    val favoriteIds = mutableStateOf<Set<String>>(emptySet())

    val lyricsMode = mutableStateOf(0)

    val downloadQualityMode = mutableStateOf(0)

    val wifiQuality = mutableStateOf("Sans perte")
    val mobileQuality = mutableStateOf("Élevé")
    val dolbyAtmos = mutableStateOf(false)
    val outputPrecision = mutableStateOf(0)
    val preferUsbDac = mutableStateOf(false)
    val volumeNormalization = mutableStateOf(true)
    val firstLaunchAt = mutableStateOf(0L)
    val skipSilence = mutableStateOf(false)
    val equalizerEnabled = mutableStateOf(false)
    val reverbAmount = mutableStateOf(0)
    val reverbSize = mutableStateOf(50)
    val reverbDamping = mutableStateOf(35)
    /** Opt-in live Discord listening presence. Linking alone never publishes. */
    val discordPresence = mutableStateOf(false)
    /** Light touch feedback on glass controls, tab changes and released sliders. */
    val haptics = mutableStateOf(true)
    /** Animated colour mesh behind the full player. */
    val meshBackground = mutableStateOf(true)
    /**
     * When a page scrolls down, the tab bar shrinks into a pill and the mini-player docks beside it
     * (Apple Music on iOS 26). Optional in 6.0.0; since 6.1 (owner, 2026-11-17: « le nav bar dock il doit etre
     * par defaut non desactivable ») always on for phones and tablets: the `collapsingTabBar` key is retired.
     */
    val dockingTabBar: Boolean get() = !com.ketvoice.app.BuildConfig.IS_QUEST
    /** The dominant cover colour lightly tints the optical glass. */
    val coverTintedGlass = mutableStateOf(true)
    /** Opt-in: the website's "Listening now" shows the title, artist and cover. The count itself is anonymous. */
    val shareNowPlaying = mutableStateOf(false)
    /** Start on the first stream found, then switch to the best one without a gap (on by default since 6.0.0 Beta 4). */
    val progressiveQuality = mutableStateOf(true)
    val equalizerGains = mutableStateOf(List(10) { 0 })
    val wifiHandoffEnabled = mutableStateOf(false)
    val spatialAudio = mutableStateOf(false)
    val downloadQuality = mutableStateOf("~35MB par piste")

    /** On by default in Ketvoice Lite (LiteTuning): visible effects stay, barely visible ones are lightened. */
    val highPerformanceMode = mutableStateOf(com.ketvoice.app.LiteTuning.defaultPerformanceMode())
    /** 8.2: low battery or a hot phone switches performance mode on for as long as it lasts (util/EconomyMode). */
    val autoEconomy = mutableStateOf(true)
    /** 8.2 « Diagnostic intégré »: opt-in, written to a local file only (util/Diagnostics). */
    val diagnostics = mutableStateOf(false)

    val lyricsTextSize = mutableStateOf(0)
    val questLyricsSize = lyricsTextSize
    val questKeepAwake = mutableStateOf(true)

    val localMusicFolder = mutableStateOf<String?>(null)
    val filterNonAudio = mutableStateOf(true)
    // Correspondances musicales en ligne pour les fichiers locaux sans métadonnées.
    val localMusicEnrich = mutableStateOf(true)

    val cacheBytes = mutableStateOf(10_000_000_000L)

    val playNextOnSwipe = mutableStateOf(false)
    val noRepeatSuggestions = mutableStateOf(false)
    val stopOnSwipeFromRecents = mutableStateOf(false)
    val hideVolumeBar = mutableStateOf(false)
    val hideStatusBarInPlayer = mutableStateOf(false)
    val showQueueHistory = mutableStateOf(false)
    val hideTitleStatus = mutableStateOf(false)
    val language = mutableStateOf(AppLocales.systemLanguage().label)
    /** False until the user explicitly picks a language: follow the system one. */
    val languageChosen = mutableStateOf(false)
    val lastfmGenres = mutableStateOf(true)
    val smartAudioAlignment = mutableStateOf(true)
    val techStats = mutableStateOf(false)
    val bitPerfect = mutableStateOf(false)
    /** The launcher icon (`util/LauncherIcons.kt`); since 7.6 the GTA VI one by default. */
    val appIconVariant = mutableStateOf(com.ketvoice.app.util.LauncherIcons.DEFAULT)
    /**
     * The seasonal theme chosen (6.2, [Seasons]): the id of an active season, or empty. Kept
     * only while that season lasts; read [Seasons.inEffect].
     */
    val seasonTheme = mutableStateOf("")
}
