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
    val panoramicCanvas = mutableStateOf(false)
    val sonicCanvas = mutableStateOf(false)
    val albumColors = mutableStateOf(true)
    val artworkDepth = mutableStateOf(true)
    val systemReduceMotion = mutableStateOf(false)
    val canvasWithMobileData = mutableStateOf(false)
    val artistSignatures = mutableStateOf(true)
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
    val automix = mutableStateOf(false)
    val automixAutoFade = mutableStateOf(true)
    val automixFadeSeconds = mutableStateOf(8)
    val automixPerformance = mutableStateOf(2)
    val firstLaunchAt = mutableStateOf(0L)
    val skipSilence = mutableStateOf(false)
    val equalizerEnabled = mutableStateOf(false)
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

    val cacheBytes = mutableStateOf(10_000_000_000L)

    val playNextOnSwipe = mutableStateOf(false)
    val noRepeatSuggestions = mutableStateOf(false)
    val stopOnSwipeFromRecents = mutableStateOf(false)
    val hideVolumeBar = mutableStateOf(false)
    val hideStatusBarInPlayer = mutableStateOf(false)
    val showQueueHistory = mutableStateOf(false)
    val hideTitleStatus = mutableStateOf(false)
    val language = mutableStateOf("Français")
    val lastfmGenres = mutableStateOf(true)
    val smartAudioAlignment = mutableStateOf(true)
    val techStats = mutableStateOf(false)
    val appIconVariant = mutableStateOf(1)
}
