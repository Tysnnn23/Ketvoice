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
    val downloadsWifiOnly = mutableStateOf(false)
    val panoramicCanvas = mutableStateOf(false)
    val sonicCanvas = mutableStateOf(false)
    val albumColors = mutableStateOf(true)
    val artworkDepth = mutableStateOf(true)
    val systemReduceMotion = mutableStateOf(false)
    val canvasWithMobileData = mutableStateOf(false)
    val artistSignatures = mutableStateOf(false)
    val artistPhotos = mutableStateOf(true)
    val highRefreshRate = mutableStateOf(false)
    val syncedLyrics = mutableStateOf(true)
    val blurInactiveLyrics = mutableStateOf(true)
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
    val progressiveQuality = mutableStateOf(false)
    val equalizerGains = mutableStateOf(listOf(0, 0, 0, 0, 0))
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
    val language = mutableStateOf(AppLanguage.systemDefault().label)
    /** False until the user explicitly picks a language: follow the system one. */
    val languageChosen = mutableStateOf(false)
    val lastfmGenres = mutableStateOf(true)
    val smartAudioAlignment = mutableStateOf(true)
    val techStats = mutableStateOf(false)
    val appIconVariant = mutableStateOf(1)
}
