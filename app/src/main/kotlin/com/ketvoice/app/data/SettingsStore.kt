package com.ketvoice.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistance légère des réglages (SharedPreferences).
 * Sauvegarde à la mise en arrière-plan ; rechargée avant l'init du cache
 * audio pour que la limite choisie soit appliquée dès le démarrage.
 */
object SettingsStore {

    private const val NAME = "ketvoice_settings"
    private var prefs: SharedPreferences? = null

    fun load(context: Context) {
        val p = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        prefs = p
        val s = AppSettings

        s.themeMode.value = p.getInt("themeMode", s.themeMode.value)
        s.accentArgb.value = p.getLong("accentArgb", s.accentArgb.value)
        s.syncYouTubeHistory.value = p.getBoolean("syncYouTubeHistory", true)
        s.personalizedYouTubeHome.value = p.getBoolean("personalizedYouTubeHome", true)
        s.reduceAnimations.value = p.getBoolean("reduceAnimations", s.reduceAnimations.value)
        s.reduceDynamicBlur.value = p.getBoolean("reduceDynamicBlur", s.reduceDynamicBlur.value)
        s.liquidGlass.value = p.getBoolean("liquidGlass", s.liquidGlass.value)
        s.fullArtwork.value = p.getBoolean("fullArtwork", s.fullArtwork.value)
        s.classicGradient.value = p.getBoolean("classicGradient", s.classicGradient.value)
        s.animatedArtwork.value = p.getBoolean("animatedArtwork", s.animatedArtwork.value)
        s.panoramicCanvas.value = p.getBoolean("panoramicCanvas", false)
        s.canvasWithMobileData.value = p.getBoolean("canvasWithMobileData", s.canvasWithMobileData.value)
        s.artistSignatures.value = p.getBoolean("artistSignatures", s.artistSignatures.value)
        s.syncedLyrics.value = p.getBoolean("syncedLyrics", s.syncedLyrics.value)
        s.blurInactiveLyrics.value = p.getBoolean("blurInactiveLyrics", s.blurInactiveLyrics.value)
        s.lyricsSource.value = p.getString("lyricsSource", s.lyricsSource.value) ?: "auto"
        if (s.lyricsSource.value !in MusicApi.lyricSources + listOf("auto", "off")) {
            s.lyricsSource.value = "auto"
        }
        s.favoriteIds.value = p.getStringSet("favoriteIds", emptySet())?.toSet() ?: emptySet()
        s.translateLanguage.value = p.getString("translateLanguage", s.translateLanguage.value) ?: "Français"
        s.betterLyricsKey.value = p.getString("betterLyricsKey", "").orEmpty()
        s.lyricaUrl.value = p.getString("lyricaUrl", "").orEmpty()
        s.translationSource.value = p.getString("translationSource", "en").orEmpty()

        s.wifiQuality.value = p.getString("wifiQuality", s.wifiQuality.value) ?: "Sans perte"
        s.mobileQuality.value = p.getString("mobileQuality", s.mobileQuality.value) ?: "Élevé"
        s.skipSilence.value = p.getBoolean("skipSilence", s.skipSilence.value)
        s.equalizerEnabled.value = p.getBoolean("equalizerEnabled", false)
        s.equalizerGains.value = com.ketvoice.app.player.EqualizerProfile.normalize(
            p.getString("equalizerGains", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() })
        s.wifiHandoffEnabled.value = p.getBoolean("wifiHandoffEnabled", false)
        s.lyricsMode.value = p.getInt("lyricsMode", s.lyricsMode.value)
        s.downloadQualityMode.value = p.getInt("downloadQualityMode", s.downloadQualityMode.value)

        s.highPerformanceMode.value = p.getBoolean("highPerformanceMode", s.highPerformanceMode.value)
        s.lyricsTextSize.value = p.getInt("lyricsTextSize", p.getInt("questLyricsSize", 0)).coerceIn(0, 2)
        s.questKeepAwake.value = p.getBoolean("questKeepAwake", true)
        s.localMusicFolder.value = p.getString("localMusicFolder", s.localMusicFolder.value)
        s.filterNonAudio.value = p.getBoolean("filterNonAudio", s.filterNonAudio.value)
        s.cacheBytes.value = p.getLong("cacheBytes", s.cacheBytes.value)
            .coerceIn(500_000_000L, 10_000_000_000L)
        // Migration 1.3 : le cache chansons passe à 10 Go par défaut.
        if (!p.getBoolean("cacheMigrated10g", false)) {
            s.cacheBytes.value = 10_000_000_000L
            p.edit().putBoolean("cacheMigrated10g", true).apply()
        }
        s.cacheBytes.value = (kotlin.math.round(s.cacheBytes.value / 500_000_000.0).toLong() * 500_000_000L)
            .coerceIn(500_000_000L, 10_000_000_000L)
        // Migration 2.4 : les anciens secrets locaux sont supprimés de l'appareil.
        // Ils vivent désormais uniquement côté Supabase.
        p.edit()
            .remove("spDc")
            .remove("canvasUrl")
            .remove("addonLastBabe")
            .remove("addonLosslessBridge")
            .remove("addonUltraMax")
            .apply()

        s.playNextOnSwipe.value = p.getBoolean("playNextOnSwipe", s.playNextOnSwipe.value)
        s.noRepeatSuggestions.value = p.getBoolean("noRepeatSuggestions", s.noRepeatSuggestions.value)
        s.stopOnSwipeFromRecents.value = p.getBoolean("stopOnSwipeFromRecents", s.stopOnSwipeFromRecents.value)
        s.hideVolumeBar.value = p.getBoolean("hideVolumeBar", s.hideVolumeBar.value)
        s.hideStatusBarInPlayer.value = p.getBoolean("hideStatusBarInPlayer", s.hideStatusBarInPlayer.value)
        s.showQueueHistory.value = p.getBoolean("showQueueHistory", s.showQueueHistory.value)
        s.hideTitleStatus.value = p.getBoolean("hideTitleStatus", s.hideTitleStatus.value)
        s.language.value = p.getString("language", s.language.value) ?: "Français"
        s.lastfmGenres.value = p.getBoolean("lastfmGenres", s.lastfmGenres.value)
        s.smartAudioAlignment.value = p.getBoolean("smartAudioAlignment", s.smartAudioAlignment.value)
        s.techStats.value = p.getBoolean("techStats", s.techStats.value)
        s.appIconVariant.value = p.getInt("appIconVariant", s.appIconVariant.value)
    }

    fun save() {
        val p = prefs ?: return
        val s = AppSettings
        p.edit()
            .putInt("themeMode", s.themeMode.value)
            .putLong("accentArgb", s.accentArgb.value)
            .putBoolean("syncYouTubeHistory", s.syncYouTubeHistory.value)
            .putBoolean("personalizedYouTubeHome", s.personalizedYouTubeHome.value)
            .putBoolean("reduceAnimations", s.reduceAnimations.value)
            .putBoolean("reduceDynamicBlur", s.reduceDynamicBlur.value)
            .putBoolean("liquidGlass", s.liquidGlass.value)
            .putBoolean("fullArtwork", s.fullArtwork.value)
            .putBoolean("classicGradient", s.classicGradient.value)
            .putBoolean("animatedArtwork", s.animatedArtwork.value)
            .putBoolean("panoramicCanvas", s.panoramicCanvas.value)
            .putInt("lyricsTextSize", s.lyricsTextSize.value)
            .putBoolean("canvasWithMobileData", s.canvasWithMobileData.value)
            .putBoolean("artistSignatures", s.artistSignatures.value)
            .putBoolean("syncedLyrics", s.syncedLyrics.value)
            .putBoolean("blurInactiveLyrics", s.blurInactiveLyrics.value)
            .putString("lyricsSource", s.lyricsSource.value)
            .putStringSet("favoriteIds", s.favoriteIds.value)
            .putString("translateLanguage", s.translateLanguage.value)
            .putString("betterLyricsKey", s.betterLyricsKey.value)
            .putString("lyricaUrl", s.lyricaUrl.value)
            .putString("translationSource", s.translationSource.value)
            .putString("wifiQuality", s.wifiQuality.value)
            .putString("mobileQuality", s.mobileQuality.value)
            .putBoolean("skipSilence", s.skipSilence.value)
            .putBoolean("equalizerEnabled", s.equalizerEnabled.value)
            .putString("equalizerGains", s.equalizerGains.value.joinToString(","))
            .putBoolean("wifiHandoffEnabled", s.wifiHandoffEnabled.value)
            .putInt("lyricsMode", s.lyricsMode.value)
            .putInt("downloadQualityMode", s.downloadQualityMode.value)
            .putBoolean("highPerformanceMode", s.highPerformanceMode.value)
            .putInt("questLyricsSize", s.questLyricsSize.value)
            .putBoolean("questKeepAwake", s.questKeepAwake.value)
            .putString("localMusicFolder", s.localMusicFolder.value)
            .putBoolean("filterNonAudio", s.filterNonAudio.value)
            .putLong("cacheBytes", s.cacheBytes.value)
            .putBoolean("playNextOnSwipe", s.playNextOnSwipe.value)
            .putBoolean("noRepeatSuggestions", s.noRepeatSuggestions.value)
            .putBoolean("stopOnSwipeFromRecents", s.stopOnSwipeFromRecents.value)
            .putBoolean("hideVolumeBar", s.hideVolumeBar.value)
            .putBoolean("hideStatusBarInPlayer", s.hideStatusBarInPlayer.value)
            .putBoolean("showQueueHistory", s.showQueueHistory.value)
            .putBoolean("hideTitleStatus", s.hideTitleStatus.value)
            .putString("language", s.language.value)
            .putBoolean("lastfmGenres", s.lastfmGenres.value)
            .putBoolean("smartAudioAlignment", s.smartAudioAlignment.value)
            .putBoolean("techStats", s.techStats.value)
            .putInt("appIconVariant", s.appIconVariant.value)
            .apply()
    }
}
