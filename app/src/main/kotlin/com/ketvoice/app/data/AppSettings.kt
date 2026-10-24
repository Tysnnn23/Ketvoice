package com.ketvoice.app.data

import androidx.compose.runtime.mutableStateOf

/**
 * Préférences v1 (mémoire) — branchées sur DataStore pour la persistance.
 */
object AppSettings {
    // APPARENCE
    val themeMode = mutableStateOf(0)     // 0 Système | 1 Clair | 2 Sombre
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
    val systemReduceMotion = mutableStateOf(false) // runtime only; never persisted
    val canvasWithMobileData = mutableStateOf(false)
    val artistSignatures = mutableStateOf(true)
    val syncedLyrics = mutableStateOf(true)
    val blurInactiveLyrics = mutableStateOf(true)
    val lyricsSource = mutableStateOf("auto")
    val betterLyricsKey = mutableStateOf("")
    val lyricaUrl = mutableStateOf("")
    val translationSource = mutableStateOf("en")
    val translateLanguage = mutableStateOf("Français")

    /** Titres mis en favori (étoile du lecteur) — persistés. */
    val favoriteIds = mutableStateOf<Set<String>>(emptySet())

    /** Mode des paroles : 0 synchronisées (LRCLIB) | 1 texte simple | 2 désactivées. */
    val lyricsMode = mutableStateOf(0)

    /** Qualité de téléchargement : 0 FLAC sans perte | 1 MP3 rapide. */
    val downloadQualityMode = mutableStateOf(0)

    // QUALITÉ
    val wifiQuality = mutableStateOf("Sans perte")
    val mobileQuality = mutableStateOf("Élevé")
    val dolbyAtmos = mutableStateOf(false)
    val outputPrecision = mutableStateOf(0)   // 0 16-bit PCM | 1 32-bit float
    val preferUsbDac = mutableStateOf(false)
    val volumeNormalization = mutableStateOf(true)
    val automix = mutableStateOf(false)
    val skipSilence = mutableStateOf(false)
    val equalizerEnabled = mutableStateOf(false)
    val equalizerGains = mutableStateOf(listOf(0, 0, 0, 0, 0))
    val wifiHandoffEnabled = mutableStateOf(false)
    val spatialAudio = mutableStateOf(false)
    val downloadQuality = mutableStateOf("~35MB par piste")

    // PERFORMANCE
    val highPerformanceMode = mutableStateOf(false)

    // Quest panel controls. These do not disable existing motion or glass.
    val lyricsTextSize = mutableStateOf(0)
    val questLyricsSize = lyricsTextSize // preserve existing callers/preferences
    val questKeepAwake = mutableStateOf(true)

    // MUSIQUE LOCALE
    val localMusicFolder = mutableStateOf<String?>(null)
    val filterNonAudio = mutableStateOf(true)

    // STOCKAGE
    /** Taille maximale du cache audio en octets (0,5 Go → 10 Go, pas de 0,5 Go). */
    val cacheBytes = mutableStateOf(10_000_000_000L)

    // SOURCES
    // Aucun cookie, jeton ou endpoint privé n'est livré avec l'application.
    // Les sources et les canvas passent par Ketvoice API (Supabase).

    // DIVERS
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
    val appIconVariant = mutableStateOf(1) // 0 défaut | 1 Liquid Glass | 2 Dark | 3 Classic
}
