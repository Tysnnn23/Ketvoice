# Ketvoice for Windows

A native Compose Desktop app (Kotlin/JVM) that shares the Android app's pure Kotlin code in place.
Version 1.0.0. **Phase 1 is in**: an Apple-Music-like Ket 27 window that plays real music through
FFmpeg, with the library on disk, downloads, the tray, media keys and the Windows media controls.
Phase 0 (the build, the shared-source closure, MSI packaging and CI) is below it.

## Layout

```
desktop/
  settings.gradle.kts, build.gradle.kts   standalone Gradle build (the root build does not include it)
  shared/*.txt                            include manifests: which app sources are compiled here
  src/compat/kotlin/                      same-name shims of the android.* / androidx.core APIs used
  src/main/kotlin/com/ketvoice/desktop/   the desktop app
    Main.kt                               single instance, engine + Player, tray, SMTC, window state, --smoke
    audio/                                AudioEngine contract, FfmpegAudioEngine (decks, mixer, gapless, EQ,
                                          ranged HTTP with a disk cache), PcmSink (sound card, CaptureSink), FakeAudioEngine
    player/DesktopStreams.kt              a Track to what the engine reads (download, then the app's sources)
    library/                              DesktopLibrary (favourites, playlists, history), DesktopDownloader
    platform/                             tray/SMTC/media keys, single instance, WindowStore, autostart, logs, About
    ui/                                   the Ket 27 shell: sidebar, bar, pages, queue/lyrics panels, full player
  src/main/kotlin/com/ketvoice/app/...    desktop counterparts of Android-bound app objects (Player facade…)
  src/test/kotlin/                        desktop tests (engine, player, library, platform, shell, renders)
  icons/ketvoice.ico, ketvoice.png        made from app/src/main/res/drawable-nodpi/vicecity_logo.webp
```

## Shared sources and manifests

`build.gradle.kts` adds `../app/src/main/kotlin` and `../app/src/test/kotlin` as source roots, then
filters them: a file under an app root is compiled only when a manifest lists it, while everything
under `desktop/src` is always in. Nothing is copied, so a fix in the app is a fix here.

Each manifest holds one path or Gradle glob per line, relative to the app's `com/ketvoice/app/`
package; `#` starts a comment. Main sources: `audio`, `automix`, `stems`, `data`, `lyrics`, `sync`,
`ui` (concatenated). Tests: `tests.txt` (relative to `app/src/test/kotlin/com/ketvoice/app/`).
`ui.txt` brings the Ket 27 pieces the shell reuses as they are: `CoverColors`, `DesignTokens`,
`KetIcons`, `Ket27Glyphs`, `LocalizedIcon`, `SymbolEffects`, and `AppText` + `UiTextDictionary`
(every visible string, the window's and the platform's alike, goes through `AppText.t`).

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
| `stems/StemModel`, `StemEngine`, `StemLibrary` | LiteRT | desktop counterparts (Phase 3, ONNX) |
| `stems/VoiceSeparation`, `StreamSeparation`, `StemWork*` | Android services | replaced by `DesktopVoice` (Phase 3) |
| `automix/BeatModel` | LiteRT, APK assets | ONNX counterpart (Phase 2) |
| `automix/DeckMixProcessor`, `Automix` | Media3 audio processors and player | replaced by `DesktopMixer` + `DesktopAutomix` |
| `data/CanvasRepo` | MediaMetadataRetriever, Bitmap, ConnectivityManager | desktop counterpart (Phase 2) |
| `data/Downloader` | Media3 DASH downloader, StatFs, Toast | 1 |
| `data/LocalMusic` | MediaStore, DocumentsContract | counterpart (Phase 2: `LocalFolders`) |
| `data/SettingsStore`, `data/PersonalBackup` | launcher icons, seasons, design choices, glass styles | counterparts (Phase 2) |
| `account/AccountSession`, `Profiles`, `sync/SharedPlaylists`, `NowPlayingSync` | Android intents, avatars, phone control | counterparts (Phase 2) |
| `glass/GlassClarity` (rendering half) | Android glass and contrast APIs | UI phase |
| `data/AppLocales` | Android per-app locales | counterpart kept |

Not tried yet: `TransitionPreview`, `MixStudio`, `KetmixJournal`, and the UI packages. Reused tests
left out because they need deferred code: `MixKeysTest` and `StemsTest` (`VoiceStage`, `StemEngine`, `StemModel`), `MediaOverridesTest`
(`PersonalBackup`), `CanvasCachePlanTest` (`CanvasRepo`),
`LiteTuningTest` (`MemoryRelief`, glass render modes).

### Desktop counterparts (`src/main/kotlin/com/ketvoice/app/`)

- `player/Player.kt` — the facade the UI, tray and media controls drive: queue (`playQueue`, `setQueue`,
  `enqueue`, `playNext`, `removeAt`, `moveQueue`, shuffle/repeat, autoplay radio), transport
  (`toggle`, `play`, `pause`, `next`, `previous`, `seekTo`, `setVolume`), `state` with the position
  4 times a second, `loading`, `error`, and `format` (the engine's `AudioEvent.Format`, reset on each
  load, for the Lossless badge). `attach(engine, resolve, scope)` plugs the engine in; a gapless
  takeover (`AudioEvent.Advanced`) moves the queue on. Ketmix reads `automixNext`/`automixUpcoming`.
- `BuildConfig.kt` — `FLAVOR = "desktop"`, not Quest, not Lite, version 1.0.0.
- `automix/DeckTap.kt` — the `PcmTap` contract (lives in the Android `DeckMixProcessor.kt`).
- `automix/BeatModel.kt` — Beat This! small through ONNX Runtime (`OnnxBeatNet`, CPU, 2 threads), with the
  phone's `BeatPcm`/`refine` and the shared `BeatSpectrogram`/`BeatPost`/`BeatMapping`. Downloaded on request
  (Settings → Ketmix → « Calage précis du rythme ») to `files/beat/ketmix-beat-small0.onnx` and kept only when
  its SHA-256 is `c462b064…aea` (the `small0.onnx` that `publish-beat-model.yml` pins). It is fetched from the
  `ketmix-beat-v1` prerelease first, then from that file's source on Hugging Face (same bytes, same hash).
- `automix/TempoReturn.kt` — the Android object restated (it lives in `Automix.kt`, which is Media3-bound).
- `data/SecureSessionStore.kt` — sessions sealed with Windows DPAPI (AES-GCM with a user-only key
  file on other systems, for tests).
- `data/AppLocales.kt`, `util/LauncherIcons.kt`, `glass/GlassClarity.kt` — the values shared settings need.
- `ui/motion/Motion.kt` — the app's motion values (springs, reduced motion) without Android's.
- `data/DesktopSeams.kt` — `LocalMusic`, `Downloader`: the members the shared code calls.
  Downloads are `DesktopDownloader`'s; local files are `LocalFolders`'.
- `data/DesktopSettings.kt` — `SettingsStore` (every `AppSettings` field under its Android key in `ketvoice_settings`,
  read at start, saved 400 ms after a change and at quit) and `PersonalBackup.setting` (the check the settings sync calls).
- `account/AccountSession.kt` — the account (shared `AuthApi`, `AccountTokens`, `SupabaseHttp`), Discord through
  `LoopbackRedirect`, `Profiles` (name only); `sync/DesktopSyncSeams.kt` — `SharedPlaylists`/`NowPlayingSync` stubs.
- `data/CanvasRepo.kt` — Canvas on disk with the phone's rules (see « Lyrics and Canvas » below).

## Compat shims (`src/compat/kotlin/`)

Only what the compiled files use: `android.content.Context` (filesDir, cacheDir, assets,
contentResolver, getSharedPreferences; data in `%LOCALAPPDATA%\Ketvoice\Data` on Windows — the
per-user MSI installs the program in `%LOCALAPPDATA%\Ketvoice` — and `$XDG_DATA_HOME/ketvoice` or
`~/.local/share/ketvoice` elsewhere; `-Dketvoice.dataDir=` overrides), `SharedPreferences` + `Editor`
(one typed JSON file per name, atomic temp-file + move writes, background `apply` that always writes the
latest values; as on Android only real changes are written and announced to listeners),
`androidx.core.content.edit`, `ContentResolver` + `Cursor` + `OpenableColumns` (picked files are
`file:` URIs), `res.AssetManager` (the app's `provider_registry.json` and `translations.json` on the
classpath), `ComponentCallbacks2` constants, `android.util.AtomicFile`, `android.os.SystemClock`,
`android.os.Build` (the system's name as the model), `android.net.Uri`, `android.icu.text.Transliterator` (real ICU4J), `android.webkit.CookieManager`
(no browser cookie store on the desktop), `android.util.Log` (stderr) and `android.util.JsonReader`.

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

## How the pieces connect (Phase 1)

```
main()  AppText.init · MusicApi.init · DesktopLibrary.init (+ DesktopDownloader)
        FfmpegAudioEngine()  ──  Player.attach(engine, DesktopStreams::source, scope)
        DesktopPlatform.bridge = Player  (tray menu, media keys, SMTC)
        Window(min 900×600) { KetvoiceShell() }        quit: Player.pause, engine.release

KetvoiceShell ── LocalPlayer  = PlayerBridge   → Player (contract names only; format → Lossless badge)
              ── LocalLibrary = LibraryBridge  → DesktopLibrary (favourites, PlaylistStore, history,
                                                 recently played) + DesktopDownloader (items + live tasks)
              ── LocalCatalog = LiveCatalog    → HomeFeed, Recommender, MusicApi, CatalogRepo, YouTubeCatalog
Player.playQueue → DesktopStreams.source(track): downloaded file, else StreamResolver's first source
                   (a DASH offer becomes its single-file audio rendition) → engine.load
engine.events: Format → Player.format · Advanced/Ended → next title · Buffering → loading · Error → skip
```

Tests use fixtures (`FixtureCatalog`, `MemoryLibrary`, `RecordingPlayer`, `FakeAudioEngine`) and
never reach the network. `ShellPlayerIntegrationTest` runs the shell over the real `Player` with
`FakeAudioEngine` (double-click loads the resolved source, the bar follows, next/previous, the
Lossless badge from a FLAC `Format`). `ShellScreenshots` renders the window to
`build/desktop-ui-preview/`. Opt-in live checks: `KETVOICE_LIVE=1 ./gradlew -p desktop test --tests
'*LiveStreamCheck*' --tests '*LivePlaybackCheck*'` (one known song resolved, 5 s decoded to a
`CaptureSink`).

## Ketmix (Phase 2)

```
DesktopAutomix (ketmix/)  every 250 ms while a title plays with Ketmix on:
   near the end (analysis window + 25 s): Segments.read decodes the ending and the next title's start
   straight from their sources (FfmpegDecoder, faster than real time) into the shared AnalysisTap
   (+ BeatModel when downloaded) → AnalysisStore → TransitionPlanner.plan + MixShaping (style, intensity,
   analysis from AppSettings) → engine.mixIntoNext(plan, preRoll)
   « Suivant » (Player.skipHook, ketmixOnSkip): the phone's 3.2 s filter blend into the held next title
FfmpegAudioEngine.mixIntoNext → DesktopMixer.Mix: the Player's preloaded deck is seeked to the plan's
   entry and started when the outgoing title reaches outMs − preRoll, in the same render block, so the
   two decks are sample-locked (no drift correction needed). Each deck reads through its Lane:
   TimeStretch (WSOLA, pitch kept) when off its own speed → the shared DeckDsp with
   DeckAutomation.outgoing/incoming(plan) (gain, bass swap high-pass, filters, echo, reverb, mids,
   MixBus guard on the sum). At the plan's promote point the engine emits Advanced (the Player moves its
   queue as for a gapless takeover); past the outgoing automation's end that deck is closed and the
   incoming one glides back to speed 1 in TempoReturn's steps. Positions carry the rate per segment.
UI: the « Ketmix » switch under the transport, « Mix en cours » beside the title during a mix, and
   Settings → Ketmix (on/off, style, « Intensité du mix », « Analyse des titres », mix on skip, beat model).
```

Tempo uses a WSOLA stretcher in Kotlin (`audio/TimeStretch.kt`, the method of FFmpeg's `atempo`) rather
than `atempo` through avfilter: it keeps the exact title position of every output frame (the automation
is keyed on it), is sample-exact at speed 1 (a deck keeps it after the glide back without a click), and
needs no native graph. Not on the desktop yet: the bar loop (« boucle »; the planner is told one was just
played so it picks another), voice separation (`stems`), sung-line facts from lyrics, the DJ editor.

Tests: `KetmixRenderTest` renders real mixes of two FLAC tones through the engine into a `CaptureSink`
and checks the gain curves (Goertzel), the crossing, the length, the pitch at 1.04× and the glide back,
and that the worst second difference stays at a clean tone's level (no click). `DesktopAutomixTest` runs
the Player, the engine (paced 10×) and the controller over three generated grooves: the end of one mixes
into the next, and « Suivant » blends. `BeatModelOnnxTest` checks the pinned download; with
`KETVOICE_BEAT_ONNX=/path/small0.onnx` it runs the real network on a 120 BPM click track.
## Lyrics and Canvas (Phase 2, winlyrics)

- Shared code added to the build: `data/LyricsTranslation`, `data/TranslationLanguages` (lyrics.txt),
  `ui/player/LyricsMotion` (`LyricsMotion`, `LyricsCascade`) and `ui/player/LyricsPresentation` (ui.txt).
- `ui/Lyrics.kt` — Cascade (`CascadeLyrics`) in the right panel and in the full player (its lyrics button):
  the sung line lit word by word, a glow on held notes only (no blur), other lines stepped back with the
  wave, duet sides from `LyricsDuet`, romanization (`Romanizer`, switch at the top of the source menu),
  translation (`LyricsTranslation`), a click on a line seeks, scrolling pauses the following (« Suivre la
  lecture », or by itself after 4 s). Lines come from `LyricsRepo` through `LocalLyricsFeed` (tests use a
  fixture). `DeskLyricsWords` repeats the app's `LyricsWords`, which lives inside the Android `LyricsView.kt`.
- `canvas/VideoFrames.kt` — FFmpeg (avformat, avcodec, swscale; LGPL build) decodes a Canvas to BGRA at the
  size it is shown, never above the source, looping. `canvas/CanvasPlayback.kt` plays it on its own thread
  with three buffers; `ui/CanvasViews.kt` draws the frames (`CanvasVideo`), places them with the app's
  `CanvasFit` rule (`DeskCanvasFit`, `FittedCanvas`: fill when close in shape, else whole with an extension
  of its colours) in the full player, and as the album page's motion header (`MotionHeader`).
- `data/CanvasRepo.kt` — the phone's cache: `<data>/files/canvas/<name>.mp4`, names in `ketvoice_canvas`
  (per title, `album:` stand-in during a lookup, `page:`, `none:`), owner's and published Canvas, a
  first-frame still `<name>.poster.jpg` (FFmpeg + Skia JPEG) shown until the video draws.
- Settings › Lecteur: « Activer les Canvas vidéo » (`animatedArtwork`) and « Canvas sur les pages d'album »
  (`albumHeroCanvas`); reduced motion keeps the still. The album header follows `albumHeroCanvas` only
  (the app's `albumHeader` choice has no desktop setting).
- Tests: `CanvasVideoTest` (a clip made by the build's own `ffmpeg` program), `CascadeLyricsTest`,
  `CascadeScreenshots` (lyrics panel, player with lyrics, player with Canvas, album motion header).

## Voice separation, voice level and equalizer (Phase 3, winstems)

- **Models**: the phone's two networks as the ONNX files sherpa-onnx publishes (the sources of the phone's LiteRT
  conversions), run by ONNX Runtime on the CPU behind the shared pipeline (`Separator`, `Uvr.kt`'s `UvrSeparator`,
  `StemStore`, `VoiceLevel`'s `VoiceStage`, all compiled in place). Desktop `stems/StemModel.kt` (`ModelPack`: download on
  request to `files/stems/model-onnx` and `model-hq-onnx`, kept only with the pinned size and SHA-256 of
  `tools/stems/expected-onnx.sha256`; first from the `ketmix-stems-onnx-v1` prerelease, then from the Hugging Face source,
  same bytes) and `stems/StemEngine.kt` (`OnnxStemNet`: Spleeter `x`/`y` `[2, 1, 512, 1024]`; `OnnxUvrNet`: `input`/`output`
  `[1, 4, 3072, 256]`; the phone's rules for which model separates, « en charge » being mains power through
  `GetSystemPowerStatus`). `src/compat/kotlin/org/tensorflow/lite/Interpreter.kt` only lets `Uvr.kt` compile; it throws.
- **Publishing**: `.github/workflows/publish-stem-models-onnx.yml` (manual) downloads the three files, checks the pins,
  runs each once in ONNX Runtime and creates the NEW prerelease `ketmix-stems-onnx-v1` (refuses if it exists; the phone's
  tags are never touched). Until it is run the app takes the same files from their source.
- **Voice level**: `DeskVoiceSlider` (`ui/VoiceSlider.kt`) at the right of the lyrics in the panel and the full player:
  drag, wheel, or a click on the microphone (all / none). The first touch on a title separates it whole
  (`stems/DesktopVoice.kt`: the source the Player resolved, decoded by FFmpeg, the quick model first; stored as `-w.kvs`),
  or offers the quick model's download. `DesktopMixer` runs the shared `VoiceStage` on the current deck from the moment a
  model is installed (4096 frames of delay, taken off the reported position), with the title's mask; it lets the sound
  through during a transition. Key `voiceLevel`.
- **Ketmix voices**: with « Séparer les voix » on and a model installed, `PartStems` separates the ending and the next
  title's start (at most 50 s each, once per title, 30 s at most before the transition gives up waiting) and the decks'
  `Lane.stem` carry the instrumentals into the shared `DeckDsp`: `vocalCut`, `vocalSolo`, and the mashup when
  `Mashup.fits` and both parts cover the blend. Sung moments come from `VoiceActivity`. Without a model the band cut stays.
- **Equalizer**: Settings › Lecteur › « Égaliseur » opens `EqualizerPage`: on/off, the presets of `EqualizerProfile`, ten
  vertical sliders (−12…+12 dB, double-click for 0), « Réinitialiser »; `DesktopEqualizer` plays `equalizerEnabled`/
  `equalizerGains` on the engine (also at start).
- **Settings › Séparation des voix**: the switch, both models (download, progress, delete) and « Qualité de séparation ».
- **Tests**: `StemsOnnxTest` (tiny ONNX stand-ins made by `tools/stems/tiny_onnx.py` against the same networks in Kotlin,
  through `Separator.mask` and `UvrSeparator`; pins against the workflow; download fallback and digest; the mixer's voice
  stage; a lane's stem), the shared `StemsTest`, `UvrTest`, `VoiceActivityTest`, `StemQualityTest`, `MixKeysTest`
  (`StemModelPinTest` pins the phone's LiteRT files and is excluded), `StemsScreenshots` (renders and drives the slider and
  the equalizer). Opt-in: `KETVOICE_STEMS_ONNX=<folder with the three files>` runs the real networks on a generated clip
  (here: Spleeter 12 s of sound in about 0.9 s, UVR in about 10 s, CPU).
- Not on the desktop yet: separating streams ahead of the playhead (a title is separated whole once its slider is
  touched; downloads are separated ahead, see « Voix prêtes à l'avance » below). The lyrics need no `VoiceLevel.delayMs`: the engine's position
  already leaves the stage's delay out.

## Ketmix Studio and « Voix prêtes à l'avance » (Windows 1.1, winstudio)

- **Studio** (`ketmix/StudioState.kt`, `ui/KetmixStudio.kt`): the phone's editor state restated over the shared code only
  (`MixEditor`, `MixStudio`'s `EditHistory`/`StudioSnap`/`MixPresetStore`/`MixShare`, `MixKeys`, `MixShape`, `PairChoice`).
  One timeline on the outgoing clock: both waves (`WaveBuilder` over `DesktopVoice.decode`, kept in `WaveStore`) with their
  bars and phrases, the transition box (drag = exit, right edge = length, lower wave = entry, magnet on the grid), the
  keyframe lane (double-click adds, drag moves, click opens voice/instruments/music of both titles), style and details,
  undo/redo (Ctrl+Z/Ctrl+Y), presets in `ketmix-presets-v1` (the phone's format), `KM1.` codes copied to the clipboard
  and imported. « Enregistrer » writes `MixPointStore` (`ketmix-points-v1`, now opened at start); `DesktopAutomix` applies
  the pair's keyframes and `pointsChanged()` re-plans the next transition. Opened from a right click on the bar's Ketmix
  switch and from a title's menu (« Ketmix Studio »: the title into the next one in the queue). Keys: Space listens,
  Delete removes the open keyframe, Escape closes (asks to save when changed).
- **Écouter** (`ketmix/StudioPreview.kt`): the plan played by a second `FfmpegAudioEngine` through `mixIntoNext` (the same
  decks and `DeckDsp` automation as a real mix), 5 s before the blend to 4 s after; the music pauses and resumes.
- **Voix prêtes à l'avance** (`app/stems/StemLibrary.kt`, desktop counterpart; Settings › Séparation des voix, key
  `ketmixLibrary`): on mains power, the 300 most recent downloads are separated whole, the quick model over all of them
  first, then the best model where `StemEngine.wanted()` asks for it; one title at a time on a low-priority thread, held
  while a voice is awaited or Ketmix prepares or mixes, stopped off mains. Unlike the phone it does not need Ketmix on
  (the voice level uses the separations too).
- **Presets shared with the phone** need an app-side change (`SettingsKind` carrying each preset as a
  `ketmixPreset.<hash>` item); the desktop already uses the same store and the shared sync code.
- Tests: `KetmixStudioTest` (mouse edits, undo, save, presets in the real prefs file, `KM1.` round trip, preview through
  the engine into a capture, library order and power rule), `MixStudioTest` (shared), `StudioScreenshots` (`studio-*.png`).

## Done in Phase 1

- Audio: FFmpeg engine (decks, mixer, gapless preload, 10-band EQ, ranged HTTP with a disk cache,
  device loss handled), real codec format for the Lossless/Hi-Res badge.
- Player facade with queue, shuffle/repeat, history, autoplay radio, position ticks; downloads first.
- Library on disk: favourites, playlists (create, rename, add, remove, reorder; shared
  `playlists-v1.json`), downloads with live progress, history and recently played.
- Window: Apple-Music-like Ket 27 shell (sidebar with library and playlists, Home, Explore, Search,
  Album, Artist, Titres/Albums/Artistes, Downloads, queue and lyrics panels, full player, context
  menus, drag onto playlists, keyboard shortcuts), Settings (language, appearance, « Fermer dans la
  zone de notification », « Démarrer avec Windows », « À propos » with the licences).
- Windows: single instance, tray, media keys and SMTC, window state, autostart, logs, `--smoke`.

## Done in Phase 2 (winaccount)

- **Account**: Settings › « Compte Ketvoice » signs in with e-mail and password (or creates the account) and with
  Discord: the browser opens Supabase's authorize page (PKCE) and the answer comes back to a one-shot server on
  `http://127.0.0.1:47821/callback` (then `47822`, `47823` when taken). **These three addresses must be added to the
  Supabase project's Auth › URL Configuration › Redirect URLs**, or Supabase sends Discord's answer to the site URL
  instead. The session is sealed with DPAPI. A listener account never gains a publishing right.
- **Library sync**: the shared `LibrarySync`/`SyncKinds`/`CloudLibrary` as on the phone (favourites, playlists,
  artists, settings): a round at sign-in and a few seconds after a change, a revision check every 3 minutes (a full
  round only when another device wrote), and when the window comes to the front (at most once a minute). Shared
  playlists and « now playing » stay on the phone and the web (Phase 4).
- **Statistiques**: `StatsPage` computes the shared `LiveStats` over the local history, live while music plays.
- **Settings persist**: `SettingsStore` (above); the shim's `apply` ordering and change notifications fixed.
- **Local music**: Settings › « Musique locale » keeps folders; `LocalFolders` scans them (FFmpeg tags, embedded
  covers, an index of path/size/date in `files/local-music-v1.json`); titles have `local:file:` ids (never synced)
  and `file:` addresses played as `AudioSource.LocalFile`; they show in Titres, Albums and Artistes.
- **Playlists** can be deleted from their page, after a confirmation.

## Phone control (Phase 3, winremote)

Both ways, like Spotify Connect, with no new SQL:
- **Secret**: `remote/RemoteKeyStore` keeps 32 random bytes per account (own preferences file, tied to the user id,
  wiped at sign-out). It travels to the account's other devices as the `remoteControlKey` item of the existing
  owner-only `settings` library document (`SettingsKind`); a new `SyncKinds` kind would have needed SQL, because
  `ketvoice_library.kind` has a check constraint. Two devices that each made one converge on the merged copy.
- **Room**: Ketogether's `RealtimeTransport` on `realtime:ketremote:<hex of HMAC(secret, "room")>` (public broadcast
  + presence, no table). Presence key = `DeviceIdentity.id`.
- **Messages** (`remote/RemoteWire`, `RemoteSession`): `{v, b, s}`, `s` = HMAC-SHA256 of the body with a key drawn
  from the secret; the body has sender, optional recipient, time and a 12-byte nonce. Dropped when the HMAC is
  wrong, more than 60 s off the server-corrected clock, the nonce was seen, it is this device's own or for another.
  Types: `hi`, `st` (title, play state, position, volume, the next 25 titles), `cmd` (toggle, next, previous,
  seek, volume, jump, remove), `take`/`give` (the queue around the current title, never local files). Nothing else.
- **Windows**: `desktop/remote/DesktopRemote` (in the room while signed in), the bar's devices button and popover
  (`ui/Devices.kt`), `Player.resumeFrom`/`jumpTo`. **Android**: `remote/PhoneRemote` (in the room while signed in
  and the app is in front or plays), « Vos appareils » in « Sortie audio » (`ui/player/RemoteDevices.kt`).
- Tests: `RemoteControlTest` (shared), `DesktopRemoteTest` (key store, the PC's player, popover renders),
  `RemoteDevicesHostTest` (Android render).

## Plan

**Phase 2 — Ketmix and the rest of the app.** Ketmix on the desktop mixer (done, see above); synced lyrics in
the lyrics panel (the shared `LyricsRepo` is compiled, the panel still says they arrive later);
statistics (`LiveStats` over the local history); settings persisted through the shared settings
store; Canvas (`CanvasRepo` with FFmpeg posters) and local music (`LocalMusic`, folder scan with
FFmpeg tags) in place of `DesktopSeams.kt`; account sign-in and sync of the library.

Ketmix left for later: `MixKeysTest` and `StemsTest` (they need the stems), the bar loop, lyrics facts.

**Phase 3 — models.** The separation models through ONNX Runtime (CPU, DirectML later; Beat This!
already runs there since Phase 2); then `StemModel`, `VoiceLevel` and the voice slider.

**Phase 4 — Windows integration.** A small native DLL for System Media Transport Controls (media
keys, the volume flyout's now-playing card); sign-in through the system browser with a loopback
redirect (no embedded WebView); phone control over a private Supabase Realtime channel bound to the
listener's account; auto-update from GitHub releases tagged `windows-v*` (first check that the
Android in-app updater skips those tags, as it skips the model tags).

## Polish (Phase 3, `winpolish`)

- **Search** (`LiveCatalog.search`): the phone's « Tout » — YouTube Music, published titles and add-ons together, ordered by
  `SearchRank` (with `liveTaste`), versions grouped by `SearchGroups`, blocked artists left out, artists named like the
  query first. A « Tout / Bibliothèque » switch (`SearchScope`; `LibraryViews.search` matches every word of the
  listener's titles and history). Recent searches (`SearchHistory`) on the start page; a search is kept when it found
  titles. Shared code added: `data/SearchGroups`, and the tests `SearchRankTest`, `SearchGroupsTest`.
- **Playlist import** (`ui/ImportPlaylist.kt`, the link button beside « Playlists »): the shared `smartlib/PlaylistImport`
  with `AppleMusicImport`, `TidalImport`, `SoundCloudImport`, `PublicPage`, `Duplicates` and `data/SpotifyPlaylistImport`
  (public pages only, no account); preview, matching (`MusicApi.searchAll`), then `LibraryStore.importPlaylist` keeps the
  titles not found as named entries (`PlaylistStore.create`). Tests: the three services' parser tests.
- **Album and artist pages**: « Plus de <artiste> », the release footer, a ⋯ menu; artist listeners, shuffle, ⋯,
  « Artistes similaires », « À propos » (`ArtistPage.description`, `related`, `listeners`).
- **Menus**: `TrackActions.collectionMenu` (play, shuffle, « Lire ensuite » in order, queue, playlist, download) on
  album cards (right click), album/playlist/artist headers, and the title menu on the bar's LCD and the full player's ⋯.
- **Shortcuts** (`ui/Shortcuts.kt`, listed in Settings): Space, ←/→ ±10 s, Ctrl+←/→, ↑/↓ volume, Ctrl+F, Ctrl+L,
  Ctrl+P, Ctrl+M, Ctrl+,, Alt+←, Escape. Text fields marked with `Modifier.textEntry()` keep their keys.
- **Mini-player** (`ui/MiniPlayer.kt`): an optional always-on-top 380×132 window (Ctrl+M, Settings › Windows, the
  full player's top bar).
- **Dates**: `LocalDates` takes short day and month names from ICU4J in the app language (the packaged runtime has
  no `jdk.localedata`, so the JDK gave « Mon Tue » everywhere).
- **Full player**: `CoverFlowBackground`, the phone's `ArtworkFlow` restated (three blurred cover fields turning and
  drifting, eased to a stop on pause, one still field under reduced motion, a glide between titles).
- **Language**: Settings › Langue lists the system language and the 11 languages by their own names; every text reads
  `AppSettings.language` through `AppText.t`, so the window switches at once.
- **States** (`ui/States.kt`): `rememberLoad` + `LoadState` give each catalogue page a spinner, an offline state
  (`CatalogSource.online()`: one HEAD to the catalogue host; Downloads stay one click away) or an error state with
  « Réessayer »; playback errors (`PlayerControls.errors` ← `Player.error`) show as a toast.
- Tests: `PolishTest` (shortcuts, album menu order, live language switch, ICU dates, import flow, offline pages,
  library search, colour fields); renders: `PolishScreenshots` (`p3-*.png`).

## Release tooling (Phase 4, `winrelease`)

- **Version**: `-PappVersion=1.2.3` (default 1.0.0) sets the MSI's ProductVersion and a generated `BuildConfig`
  (`build/generated/buildconfig`); MAJOR.MINOR.PATCH only, as Windows Installer reads it. The UpgradeCode never changes.
- **In-app updates** (`update/`): `WindowsUpdater` reads the public releases of Tysnnn23/Ketvoice without a token, at
  most once per 6 h (the last feed is kept in `Data\updates`), or at once from Settings › À propos › « Mises à jour ».
  Only `windows-v<semver>` tags count (Android `v*` and model tags, drafts and prereleases are ignored), never an older
  or equal version. `Ketvoice-<ver>.msi` is checked against the release's `SHA256SUMS.txt` (and GitHub's asset digest),
  then `msiexec /i … /passive /norestart` runs and the app quits; the same UpgradeCode upgrades in place. The dialog
  shows the English notes (`ReleaseNotes`: headings, bullets, bold, code). Tests: `WindowsUpdateTest` (fake feed).
- **Publishing**: `.github/workflows/publish-windows-release.yml` (dispatched with `version`) needs
  `docs/releases/windows-v<ver>.md` and `release-metadata/windows-v<ver>/validation.json` (`version`,
  `source_commit`, `credential_scan.findings == 0`, optional authorized `publication_override`); desktop, shared app
  sources, native and licences must be unchanged since `source_commit`. It refuses an existing tag or release, tests,
  packages, checks the MSI identity twice (`tools/verify_msi.py` and Windows Installer), installs it silently, checks
  the Start-menu and desktop shortcuts and the installed licences, runs `--smoke` (LGPL FFmpeg, app version),
  uninstalls, writes `SHA256SUMS.txt`, creates a draft with `make_latest: false`, re-downloads and compares, publishes
  (still not latest: the Android updater and the website read the latest release) and verifies the public download.
- **`tools/verify_msi.py`**: `release <ver>` downloads and checks a published MSI; `local`, `identity`, `sums-write`,
  `sums-check`, `validation`. ProductVersion/UpgradeCode come from `msiinfo` when installed, else from the MSI's OLE
  property table (stdlib only). Tests: `python3 -m unittest tools/test_verify_msi.py` (builds a real MSI with `wixl`).
- **Licences**: the JavaCPP FFmpeg build is configured with `--enable-version3`, so it ships under the LGPL v3
  (`docs/licenses/FFmpeg-LGPL-3.0.txt`, with the GPL v3 text it refers to). Notices for JavaCPP, ONNX Runtime, Compose,
  Coil, OkHttp, JNA, ICU4J, Gson, JSON-Java, Inter and the OpenJDK runtime, and one Apache 2.0 text. « À propos » lists
  them; the MSI installs them in `app\resources\licenses`. `checkNoGpl` fails the tests and every package task when a
  `-gpl` artifact is on the runtime classpath; `--smoke` fails unless `avutil_license()` is LGPL.
- **Shortcuts and files**: Start menu « Ketvoice » and a desktop shortcut; « Ouvrir avec » for mp3, flac, m4a, aac,
  ogg, opus, wav and aiff. An opened file reaches the running window through the single instance (`OpenFiles`) and plays.
