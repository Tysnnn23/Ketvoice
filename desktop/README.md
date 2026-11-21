# Ketvoice for Windows

A native Compose Desktop app (Kotlin/JVM) that shares the Android app's pure Kotlin code in place.
This is **Phase 0**: the build, the shared-source closure with its tests, a placeholder Ket 27
window, the MSI packaging and its CI. Version 1.0.0.

## Layout

```
desktop/
  settings.gradle.kts, build.gradle.kts   standalone Gradle build (the root build does not include it)
  shared/*.txt                            include manifests: which app sources are compiled here
  src/compat/kotlin/                      same-name shims of the android.* / androidx.core APIs used
  src/main/kotlin/com/ketvoice/desktop/   the desktop app (Main.kt: window and --smoke)
  src/main/kotlin/com/ketvoice/app/...    desktop counterparts of Android-bound app objects
  src/test/kotlin/                        desktop-only tests (CompatTest)
  icons/ketvoice.ico, ketvoice.png        made from app/src/main/res/drawable-nodpi/vicecity_logo.webp
```

## Shared sources and manifests

`build.gradle.kts` adds `../app/src/main/kotlin` and `../app/src/test/kotlin` as source roots, then
filters them: a file under an app root is compiled only when a manifest lists it, while everything
under `desktop/src` is always in. Nothing is copied, so a fix in the app is a fix here.

Each manifest holds one path or Gradle glob per line, relative to the app's `com/ketvoice/app/`
package; `#` starts a comment. Main sources: `audio`, `automix`, `stems`, `data`, `lyrics`, `sync`,
`ui` (concatenated). Tests: `tests.txt` (relative to `app/src/test/kotlin/com/ketvoice/app/`).

Rules for adding a file: add it to a manifest, run `./gradlew -p desktop compileKotlin`, and follow
the unresolved names. When a file needs Android-only code (Media3, LiteRT, MediaStore, Bitmap,
WebView…), do not pull that code in: either leave the file out, or add a desktop counterpart under
`src/main/kotlin/com/ketvoice/app/` holding only the members the shared code calls, with behaviour
that is true on the desktop. The Android app is never changed for the desktop build.

### Compiled in Phase 0 (shared, unchanged)

- automix: `AnalysisStore`, `BeatAnalysis`, `BeatMapping`, `BeatPost`, `BeatSpectrogram`, `BlendFit`,
  `CutBudget`, `DeckDsp`, `LoopFx`, `LyricTimes`, `MixBus`, `MixEditor`, `MixKeys`, `MixPoints`, `MixShape`,
  `MixVariety`, `MusicalKey`, `OnsetTracker`, `StyleCuts`, `TransitionPlanner`, `Waveform`
- stems: `Separator`, `StemDsp`, `StemStore`
- player: `QueueOrder`, `QueueEntryIds`
- data: `Data`, `AppSettings`, `AppLanguage`, `SmartOfflinePlan`, `MusicApi`, `KetvoiceApi`,
  `PublicationApi`, `PublisherAccess`, `PublisherSession`, `ArtistCache`, `ArtistPages`, `ArtistPrefs`,
  `ArtistSignatures`, `SearchHistory`, `ProviderConfig`, `CancellableConnection`, `DownloadValidation`,
  `DiskBudget`, `StreamResolver`, `StreamQuality`, `SourcePriority`, `CustomSources`, `YouTubeCatalog`,
  `YouTubeMusicSession`, `OfficialMusicParser`, `HomeFeed`, `CatalogRepo`, `ArtworkRepo`, `LiveStats`,
  `ListeningStats`, `ListeningMonths`, `ListeningHistory`, `ListeningWeek`, `PersonalMixes`, `Wrapped`,
  `ImportedSong`, `Recordings`, `Recommender`, `CanvasCachePlan`, `MediaOverrides`, `RangedDownload`,
  `SearchRank`, `Lite` (`LiteTuning`), `util/Romanizer`
- lyrics: `LyricsRepo`, `LyricsDuet`, `LyricsFormats`, `LyricsOffsets`, `ImportedLyrics`, `LyricsDiskCache`,
  `LyricsSearch`, `ExtraLyricsProviders`, `CustomLyrics`, `LocalLyrics`

### Deferred (and why)

| File | Blocker | Phase |
|---|---|---|
| `stems/VoiceLevel` | `StemModel` (LiteRT interpreter) | 3 (ONNX) |
| `stems/StemModel`, `Uvr`, `StemEngine`, `StemLibrary`, `VoiceSeparation`, `StreamSeparation` | LiteRT, Android services | 3 |
| `automix/BeatModel` | LiteRT, APK assets | 3 (desktop counterpart for now) |
| `automix/DeckMixProcessor`, `Automix` | Media3 audio processors and player | 1–2 |
| `data/CanvasRepo` | MediaMetadataRetriever, Bitmap, ConnectivityManager | 1 |
| `data/Downloader` | Media3 DASH downloader, StatFs, Toast | 1 |
| `data/LocalMusic` | MediaStore, DocumentsContract | 1 |
| `glass/GlassClarity` (rendering half) | Android glass and contrast APIs | UI phase |
| `data/AppLocales` | Android per-app locales | counterpart kept |

Not tried yet: `TransitionPreview`, `MixStudio`, `KetmixJournal`, and the UI packages. Reused tests
left out because they need deferred code: `TransitionPlannerTest` (`TempoReturn` in `Automix`),
`MixKeysTest` and `StemsTest` (`VoiceStage`, `StemEngine`, `StemModel`), `MediaOverridesTest`
(`PersonalBackup`), `SearchRankTest` (`SearchGroups`), `CanvasCachePlanTest` (`CanvasRepo`),
`LiteTuningTest` (`MemoryRelief`, glass render modes).

### Desktop counterparts (`src/main/kotlin/com/ketvoice/app/`)

- `player/Player.kt` — the queue facade Ketmix reads (`state`, `automixNext`, `automixUpcoming`,
  `pinnedNext`, `moveQueue`, `playNext`, `refreshPublication`), same rules as the Android `Player`.
- `BuildConfig.kt` — `FLAVOR = "desktop"`, not Quest, not Lite, version 1.0.0.
- `automix/DeckTap.kt` — the `PcmTap` contract (lives in the Android `DeckMixProcessor.kt`).
- `automix/BeatModel.kt` — no rhythm model yet: `usable()` is false, Ketmix keeps its own analysis
  (as a phone without the optional model).
- `data/SecureSessionStore.kt` — sessions sealed with Windows DPAPI (AES-GCM with a user-only key
  file on other systems, for tests).
- `data/AppLocales.kt`, `util/LauncherIcons.kt`, `glass/GlassClarity.kt` — the values shared settings need.
- `data/DesktopSeams.kt` — `CanvasRepo`, `LocalMusic`, `Downloader`: the members the shared code calls,
  saying truthfully that the desktop has no Canvas, local files or downloads yet. Phase 1 replaces them.

## Compat shims (`src/compat/kotlin/`)

Only what the compiled files use: `android.content.Context` (filesDir, cacheDir, assets,
contentResolver, getSharedPreferences; data in `%LOCALAPPDATA%\Ketvoice\Data` on Windows — the
per-user MSI installs the program in `%LOCALAPPDATA%\Ketvoice` — and `$XDG_DATA_HOME/ketvoice` or
`~/.local/share/ketvoice` elsewhere; `-Dketvoice.dataDir=` overrides), `SharedPreferences` + `Editor`
(one typed JSON file per name, atomic temp-file + move writes, ordered background `apply`),
`androidx.core.content.edit`, `ContentResolver` + `Cursor` + `OpenableColumns` (picked files are
`file:` URIs), `res.AssetManager` (the app's `provider_registry.json` and `translations.json` on the
classpath), `ComponentCallbacks2` constants, `android.util.AtomicFile`, `android.os.SystemClock`,
`android.net.Uri`, `android.icu.text.Transliterator` (real ICU4J), `android.webkit.CookieManager`
(no browser cookie store on the desktop). `android.util.Base64` and `Log` were not needed.

## Build and test

Use the repository's wrapper (Gradle 8.13) with `-p desktop`; JDK 21.

```
./gradlew -p desktop test                    # shared + desktop tests, headless
./gradlew -p desktop run                     # the window
./gradlew -p desktop run --args=--smoke      # loads ONNX Runtime and FFmpeg natives, prints versions, exits 0
./gradlew -p desktop packageMsi              # Windows only (WiX); also packageExe
./gradlew -p desktop createDistributable     # app image for the current OS
```

Natives follow the host: `windows-x86_64` on Windows, `linux-x86_64` elsewhere
(`-PnativePlatform=` overrides). FFmpeg is `org.bytedeco:ffmpeg:8.0.1-1.5.13`, the LGPL build
(`--smoke` prints the license); 8.1.2-1.5.14 is GPL-only on Windows and must not be used.
Host tests get a dead local proxy, as in the app: they never reach the network.

### Versions

Kotlin 2.2.21 (same compiler as the app), Compose Multiplatform 1.12.2, coroutines 1.11.0,
navigation-compose-desktop 2.10.0, JNA 5.19.1, org.json 20240303, OkHttp 4.12.0, ONNX Runtime 1.32.0,
ICU4J 77.1, Gson 2.11.0 (as the app). **Coil is 3.4.0, not 3.6.3**: Coil 3.5 and later are built
with Kotlin 2.4 and their metadata cannot be read by the 2.2.21 compiler; 3.4.0 is the newest that fits.

### Packaging

`compose.desktop.application`: MSI and EXE, package `Ketvoice` 1.0.0, vendor Ketvoice, per-user
install, Start-menu group and desktop shortcut, icon `icons/ketvoice.ico`, runtime modules
`java.desktop`, `java.net.http`, `jdk.crypto.ec`, `jdk.unsupported`, `java.sql`.

**MSI upgrade code: `5319BA3F-92FE-494B-97B3-BDF702D3D01E`.** Never change it: Windows Installer finds
the installed Ketvoice by it to upgrade in place.

CI: `.github/workflows/windows.yml` (windows-latest, Temurin 21) runs the tests, `packageMsi`, a
silent `msiexec /qn` install, the installed `Ketvoice.exe --smoke` (its output is in
`%LOCALAPPDATA%\Ketvoice\Data\smoke.log`, since the windowed launcher has no console), the
uninstall, and uploads the MSI. It runs on pushes touching `desktop/**` or the workflow, and by hand.

## Plan

**Phase 1 — audio engine and library.** JavaCPP FFmpeg in-process: a custom `AVIOContext` reading
the resolved stream (`StreamResolver`, `RangedDownload`) or a file, decode, `swresample` to 48 kHz
stereo 16-bit, out through a `javax.sound.sampled.SourceDataLine`, with the `Player` facade driving
it. Desktop `CanvasRepo` (posters through FFmpeg), `Downloader`, `LocalMusic` (folder scan, tags
through FFmpeg) replace `DesktopSeams.kt`; then the Ket 27 UI (Apple-Music-like sidebar layout).

**Phase 2 — Ketmix.** A `DesktopMixer` with two decks feeding the shared `DeckDsp`/`MixBus` and the
`PcmTap` taps, a port of Sonic for tempo changes with pitch kept (`TempoReturn`), and a
`DesktopAutomix` playing what `TransitionPlanner` plans; bring in `TransitionPlannerTest`,
`MixKeysTest`, `StemsTest`.

**Phase 3 — models.** Beat This! and the separation models through ONNX Runtime (CPU, DirectML
later), replacing the desktop `BeatModel`; then `StemModel`, `VoiceLevel` and the voice slider.

**Phase 4 — Windows integration.** A small native DLL for System Media Transport Controls (media
keys, the volume flyout's now-playing card); sign-in through the system browser with a loopback
redirect (no embedded WebView); phone control over a private Supabase Realtime channel bound to the
listener's account; auto-update from GitHub releases tagged `windows-v*` (first check that the
Android in-app updater skips those tags, as it skips the model tags).
