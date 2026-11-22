package com.ketvoice.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Desktop counterpart of the Android `SettingsStore`: every [AppSettings] value lives in the
 * `ketvoice_settings` preferences under its Android key, is read at start and written a moment after
 * it changes, so Ketvoice comes back as the listener left it. The Android store lists its keys one by
 * one and reaches phone-only objects (launcher icons, seasons, design choices); here the keys are the
 * fields of AppSettings, by name, with the two Android names that differ.
 */
object SettingsStore {
    private const val NAME = "ketvoice_settings"
    /** Android names that differ from the field (the retired `automix` key is never reused). */
    private val renamed = mapOf("automix" to "automixOn", "artistSignatures" to "artistSignaturesOptIn")
    /** Windows decides reduced motion; `questLyricsSize` is the same state as `lyricsTextSize`. */
    private val notStored = setOf("systemReduceMotion", "questLyricsSize")
    private var prefs: SharedPreferences? = null

    /** Whether this process read the settings already. */
    val loaded: Boolean get() = prefs != null

    /** Each stored setting: its key and its state. */
    internal val entries: List<Pair<String, MutableState<Any?>>> by lazy {
        AppSettings::class.java.declaredFields
            .filter { MutableState::class.java.isAssignableFrom(it.type) && it.name !in notStored }
            .map { field ->
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (renamed[field.name] ?: field.name) to (field.get(AppSettings) as MutableState<Any?>)
            }
    }

    fun load(context: Context) {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        prefs = p
        // A value of the wrong type (written by an older or newer version) keeps the default instead of failing.
        for ((key, state) in entries) if (key in p) runCatching { read(p, key, state.value) }.getOrNull()?.let { state.value = it }
        AppSettings.glassIntensity.value = com.ketvoice.app.glass.GlassClarity.sanitize(AppSettings.glassIntensity.value)
        sanitizeAppearance()
    }

    /** « Personnalisation » values read back as the Android store reads them: out of range or unknown is the default. */
    internal fun sanitizeAppearance() {
        val s = AppSettings
        if (!com.ketvoice.app.theme.ThemeCodec.valid(s.themeColors.value)) s.themeColors.value = ""
        if (!com.ketvoice.app.theme.AppFonts.valid(s.appFont.value)) s.appFont.value = com.ketvoice.app.theme.AppFonts.INTER
        if (s.lyricsFont.value.isNotEmpty() && !com.ketvoice.app.theme.AppFonts.valid(s.lyricsFont.value)) s.lyricsFont.value = ""
        fun clamp(state: MutableState<Int>, range: IntRange, default: Int) { if (state.value !in range) state.value = default }
        clamp(s.textScale, com.ketvoice.app.theme.AppShape.TEXT_MIN..com.ketvoice.app.theme.AppShape.TEXT_MAX, 100)
        clamp(s.cornerScale, com.ketvoice.app.theme.AppShape.CORNER_MIN..com.ketvoice.app.theme.AppShape.CORNER_MAX, 100)
        clamp(s.listDensity, 0..2, 1)
        clamp(s.lyricsScale, com.ketvoice.app.theme.LyricsLook.SIZE_MIN..com.ketvoice.app.theme.LyricsLook.SIZE_MAX, 100)
        clamp(s.lyricsAlign, 0..1, 0); clamp(s.lyricsSung, 0..3, 0)
        clamp(s.lyricsBlurLevel, 0..100, 100); clamp(s.lyricsMotion, 0..100, 100)
        if (s.lyricsSungArgb.value !in 0L..0xFFFFFFFFL) s.lyricsSungArgb.value = 0xFFFFFFFFL
    }

    private fun read(p: SharedPreferences, key: String, current: Any?): Any? = when (current) {
        is Boolean -> p.getBoolean(key, current)
        is Int -> p.getInt(key, current)
        is Long -> p.getLong(key, current)
        is Float -> p.getFloat(key, current).takeIf { it.isFinite() }
        is Set<*> -> p.getStringSet(key, null)
        // The ten equalizer gains, kept as on Android: « 0,2,-3,… ».
        is List<*> -> p.getString(key, null)?.split(',')?.map { it.trim().toInt() }?.takeIf { it.size == current.size }
        else -> p.getString(key, null)
    }

    fun save() {
        val p = prefs ?: return
        val editor = p.edit()
        for ((key, state) in entries) when (val v = state.value) {
            is Boolean -> editor.putBoolean(key, v)
            is Int -> editor.putInt(key, v)
            is Long -> editor.putLong(key, v)
            is Float -> editor.putFloat(key, if (key == "glassIntensity") com.ketvoice.app.glass.GlassClarity.sanitize(v) else v)
            is String -> editor.putString(key, v)
            is Set<*> -> editor.putStringSet(key, v.mapTo(LinkedHashSet()) { it.toString() })
            is List<*> -> editor.putString(key, v.joinToString(","))
            null -> editor.remove(key)
        }
        editor.apply()
    }

    /** Saves a moment after any setting changes; the first look is the value just loaded. */
    @OptIn(FlowPreview::class)
    fun watch(scope: CoroutineScope): Job = scope.launch {
        snapshotFlow { entries.map { it.second.value } }.drop(1).debounce(400).collect { save() }
    }

    /** Tests: forget the store (the file stays). */
    internal fun resetForTest() { prefs = null }
}

/**
 * Desktop counterpart of `PersonalBackup`, reduced to the check the account's settings sync calls:
 * the value another device sent for a setting, or null when it is not one Ketvoice accepts. Same rules
 * as on Android; backup files themselves are not on the desktop.
 */
object PersonalBackup {
    private val booleans = setOf("reduceAnimations", "reduceDynamicBlur", "liquidGlass", "fullArtwork",
        "classicGradient", "animatedArtwork", "albumHeroCanvas", "downloadsWifiOnly", "panoramicCanvas",
        "sonicCanvas", "albumColors", "artworkDepth", "livingArtwork", "coverAccent", "volumeLeveling", "coverBackground", "ketmixStems",
        "ketmixStemsCharging", "ketmixSmartQueue", "ketmixOnSkip", "ketmixLibrary", "autoResume", "lyricVideoSound", "lockscreenLyrics", "lyricsBlur",
        "standbyMode", "autoOfflineFavorites", "smartOffline", "smartOfflineCharging", "canvasWithMobileData", "canvasPreloadWifi",
        "artistPhotos", "highRefreshRate", "syncedLyrics", "lyricsRomanization", "skipSilence", "equalizerEnabled", "progressiveQuality",
        "highPerformanceMode", "questKeepAwake", "filterNonAudio", "localMusicEnrich", "playNextOnSwipe",
        "noRepeatSuggestions", "stopOnSwipeFromRecents", "hideVolumeBar", "hideStatusBarInPlayer",
        "showQueueHistory", "hideTitleStatus", "languageChosen", "lastfmGenres", "smartAudioAlignment", "techStats",
        "haptics", "coverTintedGlass", "glassTilt", "glassTinted", "trueBlack", "meshBackground", "automixOn", "automixAlbums",
        "ketmixLoops", "smartShuffle", "ketmixHaptics")
    // glassStyle: up to GlassStyles.LIQUID_27 (3) on Android.
    private val integers = mapOf("themeMode" to 0..2, "glassStyle" to 0..3, "glassLook" to 0..1, "glassTintLevel" to 0..6, "lyricsTextSize" to 0..2,
        "albumHeader" to 0..2, "reverbAmount" to 0..100, "reverbSize" to 0..100, "reverbDamping" to 0..100,
        "lyricsMode" to 0..2, "automixIntensity" to 0..2, "ketmixAnalysis" to 0..2, "ketmixStyle" to 0..9, "ketmixStemsQuality" to 0..2,
        "voiceLevel" to 0..100, "canvasCacheMb" to 256..2048, "smartOfflineGb" to 0..10, "smartOfflinePercent" to 10..50,
        "downloadQualityMode" to 0..2,
        // « Personnalisation » (9.0), as on Android.
        "textScale" to com.ketvoice.app.theme.AppShape.TEXT_MIN..com.ketvoice.app.theme.AppShape.TEXT_MAX,
        "cornerScale" to com.ketvoice.app.theme.AppShape.CORNER_MIN..com.ketvoice.app.theme.AppShape.CORNER_MAX, "listDensity" to 0..2,
        "lyricsScale" to com.ketvoice.app.theme.LyricsLook.SIZE_MIN..com.ketvoice.app.theme.LyricsLook.SIZE_MAX, "lyricsAlign" to 0..1,
        "lyricsSung" to 0..3, "lyricsBlurLevel" to 0..100, "lyricsMotion" to 0..100)
    private val strings = setOf("lyricsSource", "translateLanguage", "translationSource", "wifiQuality", "mobileQuality", "equalizerGains", "language",
        "themeColors", "appFont", "lyricsFont")

    internal fun setting(key: String, raw: Any?): Any? = when {
        key in booleans -> raw as? Boolean
        // « Pochette et Canvas » (2) became the animated Canvas (1), as AlbumHeader.normalize does.
        key == "albumHeader" -> (raw as? Number)?.toInt()?.takeIf { it in 0..2 }?.let { if (it == 2) 1 else it }
        key in integers -> (raw as? Number)?.toInt()?.takeIf { it in integers.getValue(key) }
        key == "accentArgb" || key == "lyricsSungArgb" -> (raw as? Number)?.toLong()?.takeIf { it in 0L..0xffffffffL }
        key == "cacheBytes" -> (raw as? Number)?.toLong()?.takeIf { it in 500_000_000L..10_000_000_000L }
        key == "glassTint" || key == "glassIntensity" -> (raw as? Number)?.toFloat()?.takeIf { it.isFinite() && it in 0f..1f }
        key in strings -> (raw as? String)?.takeIf { value ->
            when (key) {
                "wifiQuality", "mobileQuality" -> value in setOf("Normal", "Élevé", "Sans perte")
                "lyricsSource" -> value in MusicApi.lyricSources + listOf("auto", "off")
                "language" -> AppLanguage.all.any { it.label == value }
                "equalizerGains" -> value.matches(Regex("-?\\d{1,2}(,-?\\d{1,2}){0,9}"))
                "themeColors" -> com.ketvoice.app.theme.ThemeCodec.valid(value)
                "appFont" -> com.ketvoice.app.theme.AppFonts.valid(value)
                "lyricsFont" -> value.isEmpty() || com.ketvoice.app.theme.AppFonts.valid(value)
                "translateLanguage", "translationSource" -> value in TranslationLanguages.codes ||
                    TranslationLanguages.codes.any { TranslationLanguages.name(it) == value }
                else -> false
            }
        }
        else -> null
    }
}
