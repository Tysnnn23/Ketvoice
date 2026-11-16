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
    /** 0 clear optical glass, 1 frosted, 2 opaque accessibility material. */
    val glassStyle = mutableStateOf(0)
    /**
     * Transparency of the glass, 0 (very clear) .. 6 (tinted, most legible); 2 is the look of earlier
     * versions. Replaces the continuous `glassTint` of 5.x, converted once by [SettingsStore].
     */
    val glassTintLevel = mutableStateOf(com.ketvoice.app.glass.GlassClarity.DEFAULT)
    /** The reflections of the glass follow the phone's tilt (never on Quest, with reduced motion or in the performance mode). */
    val glassTilt = mutableStateOf(true)
    val downloadsWifiOnly = mutableStateOf(false)
    /** A title made a favourite is downloaded for listening offline. */
    val autoOfflineFavorites = mutableStateOf(true)
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
    /** Which separation model: 0 automatic (the better one while charging), 1 always the better one, 2 always the quick one (6.0.0). */
    val ketmixStemsQuality = mutableStateOf(0)
    /** Level of the voice in titles separated whole, 0..100 (6.0.0, the slider of the lyrics screen). */
    val voiceLevel = mutableStateOf(100)
    /** In shuffle, the next title is the one that mixes best with the current one (5.11.0). */
    val ketmixSmartQueue = mutableStateOf(true)
    /** Downloaded titles are measured and separated ahead while the phone charges (5.11.0). */
    val ketmixLibrary = mutableStateOf(true)
    /** Lyrics videos carry the song's sound (5.10.0). */
    val lyricVideoSound = mutableStateOf(true)
    val systemReduceMotion = mutableStateOf(false)
    val canvasWithMobileData = mutableStateOf(false)
    val artistSignatures = mutableStateOf(false)
    val artistPhotos = mutableStateOf(true)
    val highRefreshRate = mutableStateOf(false)
    val syncedLyrics = mutableStateOf(true)
    val blurInactiveLyrics = mutableStateOf(true)
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
    val betterLyricsKey = mutableStateOf("")
    val lyricaUrl = mutableStateOf("")
    val translationSource = mutableStateOf("en")
    val translateLanguage = mutableStateOf("Français")

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
    /** The dominant cover colour lightly tints the optical glass. */
    val coverTintedGlass = mutableStateOf(true)
    /** Opt-in: the website's "Listening now" shows the title, artist and cover. The count itself is anonymous. */
    val shareNowPlaying = mutableStateOf(false)
    val progressiveQuality = mutableStateOf(false)
    val equalizerGains = mutableStateOf(List(10) { 0 })
    val wifiHandoffEnabled = mutableStateOf(false)
    val spatialAudio = mutableStateOf(false)
    val downloadQuality = mutableStateOf("~35MB par piste")

    val highPerformanceMode = mutableStateOf(false)

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
    val appIconVariant = mutableStateOf(1)
}
