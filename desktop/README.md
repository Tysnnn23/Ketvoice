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
    ui/                                   the Ket 27 shell (2.0): floating glass sidebar, player island, pages, right panel, full player
    ui/glass/                             liquid glass: GlassShader (SkSL), GlassPolicy (fallbacks), glassSource/glass modifiers
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
needs no native graph. Since Windows 1.2 the bar loop is played too (the shared `LoopFx` in `Lane`, on the title's
own frames before the stretcher, as on the phone, where it comes before the change of speed), so the 6.9 « boucle »
and the 8.4 « Pont en boucle » (`MixStyle.Bridge`, `LoopBridge`) are planned and rendered: `DesktopAutomix` tracks
`loopedLast` as the phone does, reads where each title sings from its lyrics (`LyricsRepo` for the outgoing one, the
first lyrics source for the next) and its separation (`voiceLines`), and during a bridge `DesktopMixer.steer` gives both
decks the shared `GlideDriver`'s speeds on the mixer's clock (both decks are read in the same block, so no drift
correction); a bridge given up half-way glides back through `TempoReturn`. The desktop `BeatRunner` reads the network's
frame count from the ONNX input (`BeatPost.Window`), and `BeatPost.peaks` places beats between frames.

Tests: `KetmixRenderTest` renders real mixes of two FLAC tones through the engine into a `CaptureSink`
and checks the gain curves (Goertzel), the crossing, the length, the pitch at 1.04× and the glide back,
and that the worst second difference stays at a clean tone's level (no click). `DesktopAutomixTest` runs
the Player, the engine (paced 10×) and the controller over three generated grooves: the end of one mixes
into the next, and « Suivant » blends. `BeatModelOnnxTest` checks the pinned download; with
`KETVOICE_BEAT_ONNX=/path/small0.onnx` it runs the real network on a 120 BPM click track. `KetmixRenderTest`'s bridge
case checks that the looped bar hides what follows it in the title, the glide's steps and the pitch;
`DesktopAutomixTest` plans the bridge from lyrics (chosen style and automatic mode) and never twice running. The
phone's `KetmixLoopBridgeTest` is not reused (it reads the phone's Ketmix panel); `BeatPrecisionTest` is.

## Audio output and headphone EQ (Windows 1.2)

- **Sortie audio** (`audio/OutputDevices.kt`): `OutputDevices` lists the Java Sound mixers that can open a 48 kHz stereo
  line (without « Primary Sound Driver » and other aliases of the default); `DesktopOutput` keeps the listener's pick by
  device name in the `desktop` preferences (`audioOutput`), polls the devices every 2.5 s and points the engine at the
  chosen device while it is connected, else at the system's default (the choice is kept and comes back with its device).
  `FfmpegAudioEngine.setOutput` switches live: a 5 ms fade, the old line drained, the new one opened and faded in; a
  write that fails on a chosen device falls back to the default without stopping. `engine.output` is the device playing.
  The bar's output button and the equalizer page open the same menu.
- **Égaliseur par casque** (the phone's 9.0): shared `player/Biquad.kt`, `HeadphoneProfile.kt`, `HeadphoneLinks.kt`
  (`HeadphoneLink`, `HeadphoneLinks`, `HeadphoneStore`) and `EqCascade.kt` (`EqCascade` and the 60 ms `EqCrossfade`, also
  used by the phone's `HeadphoneEqProcessor`). `DesktopMixer.headphone` (`HeadphoneStage`) runs after the decks and the
  voice level, before the ten-band equalizer and the volume — the phone's place, after `DeckMixProcessor` and before
  the leveler. `DesktopHeadphoneEq` links a profile to the device playing by `btn:` + a digest of its name (the form the
  phone uses for an output known by name, so the phone keeps the PC's links when it edits its own and the sync of
  `headphoneLinks` holds); the system's default cannot be linked. The equalizer page lists, searches, imports
  (`JFileChooser`, Equalizer APO parametric text, 64 KB at most), links, compares and unlinks; no measurements ship
  (`docs/licenses/AutoEq-review.md`). Keys `headphoneEq`, `headphoneProfiles`, `headphoneLinks` (backed by
  `PersonalBackup.setting`, synced by the shared `SettingsKind`).
- Tests: `HeadphoneEqDesktopTest` (Cookbook responses, an APO file, Windows keys the phone's check accepts, the
  correction following the device and its fallback, the output list, the remembered choice moving the engine and back,
  the stage's pass-through, safe preamp and crossfade, the engine playing the preamp), `HeadphoneEqPageTest` (the page).
## Lyrics and Canvas (Phase 2, winlyrics)

- Shared code added to the build: `data/LyricsTranslation`, `data/TranslationLanguages` (lyrics.txt),
  `ui/player/LyricsMotion` (`LyricsMotion`, `LyricsCascade`) and `ui/player/LyricsPresentation` (ui.txt).
- `ui/Lyrics.kt` — Cascade (`CascadeLyrics`) in the right panel and in the full player (its lyrics button):
  the sung line lit word by word, a glow on held notes only (no blur), other lines stepped back with the
  wave, duet sides from `LyricsDuet`, romanization (`Romanizer`, switch at the top of the source menu),
  translation (`LyricsTranslation`), a click on a line seeks, scrolling pauses the following (« Suivre la
  lecture », or by itself after 4 s). Lines come from `LyricsRepo` through `LocalLyricsFeed` (tests use a
  fixture). The sung line is drawn by the shared `KaraokeDraw` and `LyricsWords` since 9.0 (see below).
- `canvas/VideoFrames.kt` — FFmpeg (avformat, avcodec, swscale; LGPL build) decodes a Canvas to BGRA at the
  size it is shown, never above the source, looping. `canvas/CanvasPlayback.kt` plays it on its own thread
  with three buffers; `ui/CanvasViews.kt` draws the frames (`CanvasVideo`), places them with the app's
  `CanvasFit` rule (`DeskCanvasFit`, `FittedCanvas`: fill when close in shape, else whole with an extension
  of its colours) in the album page's motion header (`MotionHeader`); the players size it by format (9.0, below).
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
- **Mini-player** (`ui/MiniPlayer.kt`): an optional always-on-top window (Ctrl+M, Settings › Windows, the full player's top
  bar); since 2.0 the frameless glass compact player described in « Windows 2.0 interface ».
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

## Windows 11 look (Windows 1.1.0, `winlook11`)

- **« Verre Windows 11 »** (`platform/WindowGlass.kt`): DWM `DWMWA_SYSTEMBACKDROP_TYPE` = `DWMSBT_MAINWINDOW` (Mica) on
  build 22621 and later, the older `DWMWA_MICA_EFFECT` on 22000–22620, through JNA; `DwmExtendFrameIntoClientArea(-1)` and
  a transparent skiko `SkiaLayer` let it through (the window keeps its system frame, so `ComposeWindow.isTransparent`,
  which needs an undecorated window, is not used). The shell then reads `KetColors.glass`: the window transparent, the
  pages on WinUI's layer fill; cards, menus and the players stay solid. Since 2.0 the sidebar, island and panel are the
  liquid glass of « Windows 2.0 interface », drawn above the Mica (1.1's Acrylic material, `ui/Acrylic.kt`, is kept for
  the flat fallback's grain). Solid colours as before on Windows 10, with « Effets de
  transparence » off, in high contrast, when Settings › Windows › « Verre Windows 11 » is off, or when a DWM call fails.
  Checked again when the theme or the setting changes and when the window comes to the front.
- **« Lecteur plein écran »** (`ui/FullScreenPlayer.kt`): F11 or the full player's button; the window goes
  `WindowPlacement.Fullscreen` and comes back to its placement on Esc, F11 or the exit button (never saved as the window
  state). The cover or Canvas edge to edge on the left (the screen's height, at most 54 % of its width, cropped, feathered
  into the cover colours), Cascade lyrics (`LyricsLook.Full`) and the voice level on the right, `CoverFlowBackground` behind,
  title and transport over the foot of the cover. A still pointer for 3 s while it plays fades the controls and hides the
  cursor.
- **« Boutons dans la miniature »** (`platform/ThumbBar.kt`, `native/smtc`): `kv_thumb_init/update/cover/shutdown` in
  `ketvoice_smtc.dll`. The DLL subclasses the window procedure and makes every `ITaskbarList3` and DWM call on the
  window's own thread: previous, play/pause and next (`ThumbBarAddButtons`, again on `TaskbarButtonCreated`), glyphs drawn
  by `ThumbIcons` at the window's DPI (dark on a light taskbar), localized tooltips, disabled with nothing to play. While
  the window is minimised the cover (at most 320 px) is its iconic thumbnail and peek (`DwmSetIconicThumbnail`,
  `DwmSetIconicLivePreviewBitmap`). `--smoke` prints `thumbbar ok` when the exports are in the packaged DLL; CI requires it.
- Tests: `WinLookTest` (backdrop choice, glass colours, F11/Esc and the buttons, glyphs, cover reduction; renders
  `w11-*.png` in `build/desktop-ui-preview/`: the shell over a Mica stand-in, the full-screen player at 1920 × 1080 and
  1366 × 768 with cover and Canvas, the glyphs). Only Windows shows the real Mica, taskbar and DWM thumbnails.

## Start-up, rendering and memory (Windows 1.1, `winperf`)

- **Class data sharing.** `createRuntimeImage` runs jlink with `--generate-cds-archive` (the JDK's base archive; Compose's
  `--strip-native-commands` is turned off for it, see `build.gradle.kts`). The packaged launcher adds
  `-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=$APPDIR/../Data/ketvoice.jsa`: with the per-user MSI that is
  `%LOCALAPPDATA%\Ketvoice\Data (it must exist when the JVM starts, which it does from the second launch)`. The JVM writes the archive (about 50 MB) when the app quits and maps it on later
  starts; after an update it notices the changed jars and writes a new one. `run` and the tests do not use it.
- **`--startup-probe`.** A normal start that quits after the first frame and writes `Data\startup.log` (time since the
  process started, render API, heap, CDS state). `windows.yml` times it after the install: twice with `-Xshare:off`
  (as 1.0.0), once to write the archive, twice with it. The same line is logged at every start (`start:` in the log).
  On the Linux build here, under Xvfb: 2.0–2.7 s to the first frame without CDS, 1.0–1.2 s with it.
- **Rendering** (`platform/Startup.kt`). Direct3D with vsync on Windows; if it cannot start, skiko falls back to OpenGL
  and then software, and the first-frame line says which one draws (a warning when it is not Direct3D).
  `SKIKO_RENDER_API` still overrides it.
- **Memory.** `Covers` is bounded by decoded bytes (160 MB, least recently used first out) instead of 400 entries, and
  decodes at most 1280 px on the long side. Decoded covers are Skia memory outside the Java heap and are freed only when
  the GC collects their wrappers, so the cache asks for a concurrent GC after every 64 MB it evicts. 15 s in the tray or
  minimised, the cache is emptied and the heap shrinks. The launcher runs G1 with `-Xms64m -Xmx1536m`, tight free ratios
  and a periodic GC every 2 min, so an idle app gives memory back to Windows. Pages are disposed when left
  (`AnimatedContent`), which `MemoryBudgetTest` checks with the heap and resident memory after browsing.

## Layout, settings search and downloaded playlists (the 8.4–9.0 port)

- **Disposition** (Settings › Disposition, `ui/DeskLayout.kt`, `ui/LayoutPage.kt`). The phone's 9.0 layout keys, read
  for the window, so the account's settings sync carries them both ways (`PersonalBackup.setting` checks them with the
  shared `data/LayoutSettings.kt`): `layoutTabs` is the sidebar (Home, Explore, the Library group; Search, Downloads
  and Favourites can be entries of their own, Downloads then leaving the group; Home or the Library always shown; the
  window opens on the first entry when Home is hidden), `layoutHome` Home's sections (For you, Recently played, the
  catalogue's shelves), `layoutPlayer` the bar's right-hand buttons (lyrics, queue, phone control as the phone's
  `account`, output; like, add to a playlist, download), `layoutMenu` a title's menu (« Aller à l'album/l'artiste » stay
  with Télécharger; `lyricsEditor` is the lyrics editor, through `TrackMenuSlots.lyricsEditor`), `layoutLibraryViews` grid
  or list for Albums and Artists, `layoutLibrarySorts` the order each library page opens with. Every list keeps the
  phone's ids and limits; ids the window has no button for keep their slots. Empty values are the window as before;
  the bar and the menu have the window's own default order, so their stored text is read as it is (the phone's reading
  would empty an arrangement equal to the phone's default). The phone's swipes, mini-player, artwork and Canvas keys are
  kept and synced untouched; the window has no swipes, and hover buttons in their place would change its default rows.
- **Settings search** (`ui/SettingsFind.kt`). A field at the top of Settings matches the rows' names and sections with
  the phone's `ui/settings/SettingsMatch.kt`; a result scrolls the lazy page to its section (item keys `h:…`) and lights
  its row (`Modifier.settingTarget`). New rows join the index through `SettingsFind.more`.
- **Downloaded playlists kept up to date** (`library/PlaylistWatch.kt`). The phone's rules (`data/PlaylistFollow.kt`)
  every six hours and three minutes after the playlists change from elsewhere; new titles are offered in a card at the
  bottom right of the window (Télécharger / Plus tard). Key `playlistUpdates`. The lyrics source order (`lyricsOrder`,
  shared `LyricsOrder`) is in Settings › Paroles.
- Not ported: the recap's soundtrack (the window has no recap) and Ketogether's host controls (no Ketogether here).

## Personnalisation, lyrics 8.3/9.0, artwork by format and the lyrics editor (Windows, 9.0 port)

- **Shared code moved out of Android files** (the phone's behaviour unchanged): `theme/CoverLegibility.kt` (from
  `CoverLook.kt`), `ThemeGuard.guarded` (the colours `ThemeGuard.theme` builds its `KetTheme` from, now an extension in
  `Theme.kt`), `theme/AppFontFiles.kt` (the font resources; `AppFonts.Choice.file` names the file), `ui/player/LyricsWords.kt`
  (`LyricsWords` and `segmentProgress`, from `LyricsView.kt`) and `ui/player/PlayerArtworkMath.kt` (`PlayerArtworkSize`).
  The desktop compiles `ThemeColors`, `Appearance`, `Personalization`, `KaraokeDraw`, `LyricsBlur` and `LyricsDraft` as
  they are; `app/theme/AppFontFiles.kt` is the desktop counterpart (the same five `.ttf` from `app/src/main/res/font`,
  copied to `font/` by `processResources`; their SIL OFL texts are in « À propos »). `android.os.Build.VERSION.SDK_INT`
  is 35 in the shim (the blur effects exist).
- **Theme** (`ui/theme/DesktopKet27.kt`): `DesktopTheme.colors` gives `KetColors.of`/`glass` exactly while
  `themeColors` is empty and `accentArgb` is the stored default (the desktop keeps its own red then); a custom theme
  goes through `ThemeGuard.guarded` (page = ground, surfaces = cards and menus, text = labels, AA for text, secondary
  and accent), translucent over the Mica with « Verre Windows 11 ». « Taille du texte » scales `LocalDensity.fontScale`,
  `Ket27Desk.Radius` follows « Arrondis », `Ket27Desk.Metrics` the « Densité des listes » (title rows 40/48/56 dp,
  compact rows 48/56/64), `Ket27Desk.font` the listener's font (Inter keeps its 300–800 weights).
- **Settings › Personnalisation** (`ui/PersonalizePage.kt`, `ui/ColorPicker.kt`; one line in `SettingsPage`): live
  preview, presets, light/dark side, the four colours with the picker (field, hue, swatches, hex, contrast on each
  ground), the guard's note, « Partager le thème » (copies the `KT1.` code with the phone's message), « Importer un
  thème », fonts, text size (applied on release), corners, density; lyrics font, size, alignment, sung colour (custom
  colour picker), blur level, word motion; « Tout réinitialiser ». The store saves every key under its Android name;
  `sanitizeAppearance` refuses values out of range at load and `PersonalBackup` accepts the phone's synced looks.
- **Lyrics** (`ui/Lyrics.kt`): the sung line is the phone's 8.3 drawing (feathered fill at one pace, the word lifting
  and growing ≤ 3.5 %, held notes swelling letter by letter with a blurred glow), far lines blur by distance
  (`LyricsBlur`, off while scrolling, under reduced motion or the performance mode), and « Paroles à ton goût »
  applies: font, size, centred or left, sung colour (`deskSungInk`: over the full player as on the phone; in the panel
  « Blanc » is the theme's ink and the other colours are made AA on the panel), blur level and motion.
- **Artwork by format** (`ui/PlayerArtwork.kt`): the windowed player's Canvas is the left column's full width from its
  top at its own height (capped by the window, then only its foot is cut, the foot fading into the moving colours); the
  full-screen column is as wide as the media's shape at the screen's height, at most 54 % of the width (a cover keeps
  the square column). Never bars, never stretched; the shape glides.
- **Lyrics editor** (`ui/LyricsEditor.kt`, `ui/LyricsEditorModel.kt`): opened from a title's menu (« Éditer les
  paroles… ») and the lyrics' source menu. Text step (paste, current lyrics, open a file, type), then timing on the
  heard position (the lyrics clock) by line or by word: Space or the round button stamps, ←/→ ±50 ms (Shift ±10 ms),
  ↑/↓ move the cursor, Ctrl+Z undo, Ctrl+S save, Esc close (asks when times would be lost); preview through Cascade;
  « Enregistrer » keeps the title's own lyrics (`CustomLyrics`, enhanced LRC when words are timed, offset reset);
  « Exporter » writes LRC or enhanced LRC through a save dialog (`LyricsEditorIo`, faked in tests).
- Tests: `PersonalizeTest` (defaults identical, guarded custom themes, accent, sizes, fonts and licences, settings kept
  and sanitized, sync checks, share code, the page in the shell, sung ink, full-screen sizes), `LyricsEditorDeskTest`
  (model, file seam, keys through the window, preview, close dialog, menus), shared `LivelyLyricsTest`, `LyricsDraftTest`.

## Sources and playback (Windows 2.0)

- **Sources** (`ui/SourcesPage.kt`, one line in Settings): every source the player tries, in `SourcePriority`'s playback
  order (drag, or the arrows), each with its switch and status; custom manifests are added by address
  (`CustomSources.add`: fetch, test search) or from the **catalogue** (`SourceCatalogue`, `sources/catalogue.json` on
  ketvoice-web, schema in that repository's `sources/README.md`), tested by `player/SourceTester.kt` (a real search, the
  first results opened and decoded for their real codec, rate and bits) and removed. The desktop never adds the
  Ketvoice Lossless manifest by itself (`CustomSources.seedLossless = false`): the catalogue offers it. Custom sources'
  search is merged into the search (`Catalog.search`, 6 s at most).
- **Sources follow the account**: `customSource.<id>` items of the settings document (`CustomSourceSync`; address,
  on/off and rank only), adopted by the phone too; removals travel as `{"removed": true}` for 90 days.
- **Extended manifest** (`docs/manifest.md`): lyrics are the lyrics engine's extra provider `MusicApi.SRC_SOURCES`
  (after the built-in ones), Canvas a fallback in `CanvasRepo.fromSources`, covers a fallback in `ArtworkRepo.enrich`.
- **Smart cache** (`audio/StreamCache.kt`): `HttpRangeSource` files are named by title and quality tier
  (`AudioSource.Url.cacheKey`, `StreamCache.key`) so a freshly signed address finds what is on disk; least recently
  played out first past « Taille du cache » (1–50 GB, default 2, `audioCacheGb` in the `desktop` preferences); a title
  all on disk plays as a file, one whose start is on disk starts from it with the address kept in its `.idx`
  (`AudioSource.Url.refresh` asks the sources again when that address is refused or answers other bytes).
  `player/CacheWarmer.kt` keeps the first 2 MB of favourites and playlist titles (150 per round, a quarter of the cache
  at most, never while loading; `audioCacheWarm`, on). Statistics and « Vider le cache » in Settings › Lecture.
- **Lecture sans blanc** (`trueGapless`, off): `FfmpegDecoder(exactEnd)` drains the resampler to its last sample and
  ends an MP4/M4A on its container's stated length (the AAC encoder's padding stays unheard: two 1000 ms AAC titles
  measure 1999.85 ms instead of 2004.52 ms); the next title is decoded 15 s ahead. The handover is `DesktopMixer`'s,
  sample for sample; Ketmix's path is unchanged (`KetmixRenderTest`, `DesktopAutomixTest`).
- **Mode exclusif (bit-perfect)** (`exclusiveAudio`, off): `native/wasapi` (`ketvoice_wasapi.dll`, MSVC, built and
  export-checked by `windows.yml`, packaged like the SMTC DLL; `-PrequireWasapi=true` in CI) opens the device in
  `AUDCLNT_SHAREMODE_EXCLUSIVE` at the title's own rate in a PCM format that holds its bits. `ExclusiveRules` allow it
  only with Ketmix, the equalizer, the headphone EQ and the voice level neutral and the volume at 100 %; otherwise the
  shared output plays and Settings says why. In it, `FfmpegAudioEngine.setNativeRate` decodes each title at its own
  rate (`Deck(outRate = 0)`), passes the voice stage by, writes 32-bit samples (`PcmSink.bytesPerSample`) and reopens
  the output when the rate changes at a handover (`Block.cutAt`). `SwitchingSink` falls back to Java Sound when the
  device refuses (in use, not allowed, unsupported format). Off Windows the DLL is never looked for.
- **Near-instant start** (`player/Session.kt`, `ui/LastPage.kt`): the window opens on the last page, the queue paused
  where it stood and its cover (`cache/session-cover`), while the catalogue, the account, Ketmix, sound settings and
  updates start on a background thread (`startBehind` in `Main.kt`). `startup.log` adds the milestones (`state`,
  `behind`) to the first-frame line.
- Tests: `CustomSourceExtendedTest`, `CustomSourceSyncTest`, `SourceCatalogueTest` (shared, also run here),
  `StreamCacheTest`, `GaplessExclusiveTest` (a signal cut in two FLAC files comes back sample for sample; AAC ends on
  its length; at a title's own rate the output is the file's samples bit for bit, across a rate change),
  `ExclusiveRulesTest`, `SessionTest`, `DesktopStreamsTest`.

## Puissance de Ketmix (Windows 2.0, AI side)

- **Tiers** (`ketmix/KetmixPower.kt`, settings in their own `ketvoice_ketmix_pc` preferences, never synced): Auto
  (default), Équilibré (today's models), Maximum (large models, also on a laptop). Auto gives « Maximum » to a PC
  without a system battery (`GetSystemPowerStatus`, `BatteryFlag` 128) with ≥ 12 GB of memory and ≥ 4 cores; the
  studio voice model also needs ≥ 8 GB. The graphics adapters come from `Win32_VideoController` (basic, remote and
  virtual adapters ignored). Settings: `ui/KetmixPowerSection.kt`.
- **Large models** (`ketmix/PcModels.kt`, prerelease `ketmix-pc-v1`, `publish-ketmix-pc-models.yml`, pins in
  `tools/models/expected-pc.sha256`): Beat This! final0, Kim's Mel-Band RoFormer (studio voice, `StemMask.STUDIO`),
  S-KEY (neural key), CLAP music int8 (sound embeddings). Downloaded, smallest first, only when the tier is
  « Maximum » and Ketmix, the voices or the library preparation are in use; licences and conversion checks in
  `docs/licenses/ketmix-pc-models.md`.
- **GPU** (`app/stems/OrtAccel.kt`). What works from Java with ONNX Runtime 1.32: CUDA needs the `onnxruntime_gpu`
  JNI plus CUDA 12 and cuDNN 9 installed by the listener (not used); DirectML is compiled out of the Maven JNI
  (`addDirectML` throws « not compiled with DirectML support ») and its last ONNX Runtime build is 1.24.4, older than
  the C API the 1.32 JNI asks for (not used). Used: the **WebGPU plugin execution provider** (NuGet
  `Microsoft.ML.OnnxRuntime.EP.WebGpu` 0.6.0, MIT; Direct3D 12 through Dawn, any DX12 card), registered at run time
  on the stock runtime (`OrtEnvironment.registerExecutionProviderLibrary`, `getEpDevices`,
  `SessionOptions.addExecutionProvider`). Its package (55.6 MB, two DLLs kept) is fetched from nuget.org with a
  pinned digest only for « Maximum » with a real card: the MSI does not grow. A tiny probe network
  (`resources/ketmix/gpu-probe.onnx`, `tools/models/gpu_probe_onnx.py`) runs on the card and on the processor and
  must agree before the card is used; a check that never finished (a crash) is remembered as failed. Not run on a
  real Windows GPU here (this machine has none, and loading the downloaded plugin locally was not allowed): the probe
  and the fallback are what protect listeners. The processor path uses every core while a mix waits and half of them
  in the background.
- **Library prepared at idle** (`ketmix/KetmixPc.kt` `LibraryPrep`, `ketmix/IdleWatch.kt`): on mains power, no
  full-screen app (`SHQueryUserNotificationState`), other programs under 25 % of the processor and no input for
  3 minutes (`GetLastInputInfo`), downloads get their whole-title facts (`TrackFacts`: Beat This! bars, sections,
  S-KEY key, energy, CLAP embedding; `ketmix/facts/*.kvf`) and their studio separation. Any input, a full-screen
  app or leaving mains power stops the title at once (ONNX Runtime's terminate flag) and it is taken up later.
  `StemLibrary` keeps its mains-power passes (quick, then UVR when the studio model is absent).
- **Planner inputs** (pure, shared, null/empty on the phone): `SegmentAnalysis.sectionStartsMs` (exits and entries
  land on section starts through `phraseStarts`), S-KEY's key over the chroma estimate when its probability ≥ 0.3,
  `MixContext.energyStep` (a clearly more intense next title drops in; a clear drop in energy skips the drop) and
  `MixContext.avoid` (« Ketmix apprend »). `ketmix/PcPlanning.kt`: `PcInputs`, `SoundQueue` (shuffle order by sound,
  `suggest` for the interface).
- **For the interface**: `KetmixFeedback.transition` (StateFlow of the current/last transition) and
  `KetmixFeedback.rate(up)` (counted only with « Ketmix apprend de vos avis », off by default; weights local).
- **« Ketmix apprend »** (`ui/KetmixRating.kt`, through the registries only): with « Ketmix apprend de vos avis » on, the
  « Ketmix » panel tab opens with the last transition's 👍/👎 (« Dernière transition »; the tab of `KetmixRating` replaces
  the built-in one) and a glass capsule above the island asks after each transition (9 s, not over the full player). The
  live mix view of 2.0 (« MIX EN DIRECT » in the tab and the full player, `LiveMixView`, `LiveMix`) was removed after 2.0.0
  (owner: « retire le truc mix en direct »); do not bring it back. The planner leaves a kind the listener keeps turning down out of its automatic choices: blends in time,
  echoes and builds as before, and since 2.0 filter crossings and dissolves become a plain fade (`MixContext.avoid`, only
  in « Automatique »; empty on the phone).

## Connexions and account (Windows 2.0)

Everything lives in `desktop/connect/` (logic) and `ui/Connect*.kt`, `ui/MyDataPage.kt` (pages), registered through the
shell's registries only (`ConnectUi.register`: page `connections` in the sidebar, page `my-data`, Settings sections
« Connexions » after « Compte Ketvoice » and « Profils sur ce PC » after « Windows »). `Connections.start` runs behind the
window. Its switches are in their own `ketvoice_connections` preferences (never synced, never exported with the settings).

- **Discord's status** (`DiscordIpc.kt`, `DiscordPresence.kt`): pure Kotlin over Discord's local IPC, no native library —
  the named pipe `\\.\pipe\discord-ipc-0..9` opened as a file on Windows (reads start only once `length()` says the bytes
  are there, since a blocked read would block writes on the same handle), a Unix socket elsewhere. Frames are
  `[op int32 LE][length int32 LE][UTF-8 JSON]` (`DiscordFrame`); handshake `{v: 1, client_id}` with the public id of
  the Ketvoice Discord application (`1566454532890820668`, docs/discord-integration.md). « Listening to »: title,
  artist, the cover by its https address, start/end for elapsed/remaining, and « Écouter sur Ketvoice » to the title's
  share page when `ShareLinks.provider` (the share-links feature plugs it in) gives one. On by default, shown only while
  a title plays, cleared on pause; « Masquer le titre » shows only that Ketvoice plays. Discord started later is found
  again within 15 s; updates go only when what shows changes (Discord allows 5 per 20 s).
- **Scrobbling** (shared `scrobble/Scrobbling.kt`, desktop `Scrobbler.kt`): « en cours » at the start, the listen once half
  the title or 4 minutes was really heard (seeking forward does not count), never under 30 s; queues on disk per service
  (`files/scrobbles/*.json`, oldest first, 2 000 at most, Last.fm's 14-day limit), flushed every minute and after each
  listen, backing off after failures. Ketmix Studio's previews play on their own engine and never reach the player; a
  play heard while the lyrics editor is open on that title is left out (`Scrobbler.exclusions`). ListenBrainz uses the
  listener's own token, sealed by DPAPI in `SecureSessionStore` (`listenbrainz-token`). Last.fm goes through the
  `lastfm` edge function: the API key and shared secret are server secrets, the Last.fm session key is kept server-side
  (`ketvoice_lastfm_links`, service role only); linking is Last.fm's desktop flow (allow in the browser, then « J'ai
  autorisé »).
- **Live sync** (shared `sync/PlaybackSync.kt` + `sync/NowPlayingDoc.kt`, moved unchanged out of the phone's
  `NowPlayingSync.kt`; desktop `LiveSync.kt`): while signed in and « Synchronisation en direct » is on, the PC upserts its
  row of `ketvoice_devices` (name, kind, its session id, the now-playing document: title, position, the 50 titles around
  it, playing) on each change of title, play/pause or seek, every 15 s while playing and every 5 min otherwise
  (`PublishPolicy`), and writes the account's `now_playing` document while playing and when it pauses, so the phone of
  today offers to resume what the PC played. It reads the devices every 30 s, on focus and when Home shows;
  `PlaybackMerge` merges rows and the single document (newest per device) and picks « Reprendre là où tu en étais » (Home,
  `ResumeElsewhereCard`). Live control between open devices stays phone control's private room (`RemoteControl`).
- **Connected devices**: name, kind, « En ligne » (in the room) or last seen, what it plays; « Transférer la lecture ici »
  (`takeFrom` through the room, else resume from its row), « Lire sur cet appareil » (`sendTo`, open devices only),
  « Déconnecter »: the `devices` edge function calls `ketvoice_revoke_device` (service role only), which deletes that
  device's `auth.sessions` row (its refresh tokens go by cascade) for the caller's own account only and never the
  caller's own session; the device's access token lives up to an hour, but its row is marked and the PC signs itself out
  at its next read.
- **Sign-in by QR code** (shared `account/DeviceCode.kt`, `DeviceLink.kt`, `ConnectQr.kt` with the shared
  `together/QrCode.kt` encoder): the PC asks `device-link/start` for an 8-letter code (no 0/O/1/I, 5 minutes, used once,
  10 per address per 10 min; only the SHA-256 of the poll secret is stored), shows the QR code of
  `https://ketvoice-web.vercel.app/link?code=…` and the code in letters, and polls every 3 s (`LinkPoll`). The website's
  `/link` page approves it with the web player's session (or the code typed by hand); the function then mints a one-time
  magic-link token (Auth admin `generate_link`, nothing e-mailed) that the PC exchanges at `/auth/v1/verify`
  (`AccountSession.signInWithLink`). Never for `konnektsupport@gmail.com`.
- **Zero telemetry and « Mes données »**: the desktop sends no usage statistics or crash reports (logs and crash files stay
  in the data folder; the 8.2 `diagnostics` key stays off and is not used here); `PrivacyTest` keeps an allowlist of the
  hosts the sources write out and bans analytics libraries (wave 3 added the hosts of word sync's vocabulary and of naming a
  dropped Deezer, Spotify or YouTube link, and « Liens déposés » to the page). The page lists what leaves the PC, exports the account as
  JSON (`my-data/export`, the caller's rows with their own session, plus this PC's connection switches) to
  `Documents\ketvoice-mes-donnees-<date>.json`, and deletes the account after typing SUPPRIMER (`my-data/delete`, service
  role: photos, then the auth user; refused for the publishing account).
- **Profiles on one PC** (`LocalProfiles.kt`, `ConnectProfiles.kt`): `profiles.json` in the data folder lists them (name,
  colour); « main » is the data folder of before, the others `profiles/<id>`. `applyAtStart` (first line of `main`) points
  `ketvoice.dataDir` at the profile in use and keeps one audio cache for all (`StreamCache.dir`). Switching relaunches
  Ketvoice with `--profile=<id>` (the stores are single objects started once); each profile has its own single-instance
  lock. There is no avatar menu in the 2.0 shell yet: profiles are switched from « Connexions » and Settings.

Tests: `PlaybackSyncTest`, `ScrobblingTest`, `DeviceCodeTest`, `QrCodeTest` (shared), `ConnectTest` (IPC frames byte for
byte, the pipe session with a scripted Discord, the activity, profile folders and relaunch), `PrivacyTest`,
`ConnectPagesTest` (pages in the shell, QR code, devices, the resume card, renders `win2-connections-*.png`). Server:
`supabase/tests/connections_rls.sql` and the functions' `handler_test.ts`.

## Windows 2.0 interface (`win2ui`)

The window was redrawn for 2.0; everything of 1.2 (Personnalisation, Disposition, settings search, the lyrics editor,
the headphone EQ page, Ketmix Studio, F11, the thumbnail buttons, the tray) works in it unchanged.

- **Layout** (`ui/Shell.kt`, state in `ui/ShellState.kt`). The pages run edge to edge under floating glass: the sidebar
  (`ui/Sidebar.kt`) 12 dp from the edges, keeping only its icons below 1100 dp (`ShellMetrics.COLLAPSE_BELOW`); the player
  as an island at the bottom centre of the room between the sidebar and the panel (`ui/Island.kt`), growing under the
  pointer (seek bar, shuffle, repeat, volume, Ketmix) and unfolding into the full player; the contextual panel on the right
  (`ui/SidePanel.kt`, 300–560 dp, resized from its left edge, slid in and out on a spring). Pages keep clear of the glass
  through `LocalShellInsets` (`PageEdges` in `ui/Pages.kt`; `Page()` applies it). `ShellState` holds the open tab, the panel
  and sidebar widths, the island's state and place, and whether the full player is open; `KetvoiceShell(shell = …)` takes
  one (tests drive it). `Panel` is kept for 1.2 callers (`Panel.LYRICS.tab == "lyrics"`).
- **Liquid glass** (`ui/glass/`). `glassSource(backdrop)` records the pages into a `GraphicsLayer` each frame;
  `Modifier.glass(backdrop, radius, tint, style)` draws that layer again under a surface, moved to its place, through
  `GlassShader` (SkSL run by `ImageFilter.makeRuntimeShader` as the layer's `RenderEffect`): a rounded rim on a circular
  profile bends the light inward, the three colours bend by slightly different amounts, a thin specular line follows the
  rim where it faces the light, and a blur fed as the shader's input frosts the middle. `GlassLens` magnifies a disc of a
  surface. `GlassStyle` holds Panel / Island / Drop / Clear. Glass never samples a source it is inside (no recursion): the
  sidebar, island, panel and back button sit outside the pages' source; Home's hero card and the full player's controls
  refract a local source (the hero picture, the cover colours). It sits above Windows 11's Mica: where Mica shows through the
  pages, the glass is clear over it.
- **Fallbacks** (`GlassPolicy.choose`, logged at start as `glass: …`): `LIQUID` on Windows 11 with « Verre Windows 11 » on and
  a GPU renderer; `FROSTED` (the backdrop blurred under the tint) when the shader does not compile, the setting is off,
  on Windows 10 or with transparency effects off; `TINTED` (a flat tint, 1.2's material) in high contrast or with a software
  renderer. The window's render API comes from the first frame (`GlassPolicy.renderApi`).
- **Lens and pills.** The sidebar's selection is a clear glass drop (`LensPill`) sprung from the selected entry to the
  entry under the pointer, leaning towards it and stretching with speed (`SideLens`, `LensMath`); the hovered entry grows
  4.5 %. Symbol buttons swell under the pointer (`KetIconButton`), the panel's tabs slide a drop under the open one.
- **Motion** (`ui/Fluid.kt`, `ui/Transitions.kt`). `SharedTransitionLayout` wraps the shell: the island and the full
  player are the two sides of one `AnimatedContent` whose cover, title, artist and transport are shared elements
  (`Modifier.shared`), while the full player's outline unfolds from the island's place (`Unfold`); album covers are shared
  between grids and the album header (`sharedCover`, key `SharedMotion.albumKey`). Pages change on springs
  (`SharedMotion.pages`). Micro-interactions: a press sinks and the release bounces (`Motion.pressSpring`), toggles pop
  (`rememberPop`), a like bursts (`likeBurst`), buttons and capsules lean to the pointer (`magnetic`), sliders stretch past
  their ends (`ElasticSlider`), menus grow out of the button that opened them (`MenuState.open(…, from)`, `MenuGrow`).
  Wheel scrolling glides (`fluidScroll`: an exponential glide per notch, notches adding up, rubber-band edges that settle
  back), the same at any refresh rate since every step is time-based. Reduced motion turns all of it off.
- **Refresh rate** (`platform/FrameRate.kt`). Compose draws on skiko's frame dispatcher; with `skiko.vsync.enabled`
  Direct3D presents on the display's vertical blank (144 frames on a 144 Hz screen) and skiko's fallback limiter follows the
  display's own rate, never 60. No animation in the window paces itself on a timer. Three seconds after start the window
  asks for 90 frames and logs their rate beside the display's (`render: frames 143.9/s on a 144 Hz display: …`).
- **Home** (`ui/HomeHero.kt`): the last title heard (« Reprendre l'écoute ») or the first one picked for the listener, its
  Canvas edge to edge (cropped, never letterboxed) or its cover blurred to fill with the sharp cover on the right, the words
  on a clear glass card; the shelves of Settings › Disposition below, scrolling edge to edge.
- **Panel tabs**: Paroles (Cascade and the voice level), File d'attente, Artiste (photo, a few words, top titles, its page),
  Ketmix (the last transition's 👍/👎 while learning, switch, what it is doing, the next transition, style — « Pont en
  boucle » included — intensity, « Ouvrir le Studio »). The open tab takes the room its name needs (`TabStrip.openWeight`).
- **Compact player** (`ui/MiniPlayer.kt`, `MiniPlayer.open` as before): a frameless transparent window above the others
  (444 × 172, the card inside a shadow margin), dragged anywhere and reopened there (`CompactPlayer.place`, keys
  `compactX`/`compactY` in the `desktop` preferences, back to the bottom right when that place left the screens): the cover
  blurred behind glass, cover, title, artist, the line being sung, the transport on a glass capsule, the progress. Toggled
  from the island, the full player, the tray (« Mini-lecteur », checked), Ctrl + M and Settings › Windows.
- **Typography** (`Ket27Desk.Type`): `hero` 60, `display` 46, `largeTitle` 40, `title2` 24 for section headers, `eyebrow`
  small capitals; more air (`Space.page` 36, sections 40 above), all on the listener's font and text size.

### Extension points (for the pages and panels to come)

All three live in `ui/Registry.kt`; register from the feature's own file at start (Main, or the feature's `start`).

- **Pages** — `PageRegistry.register(PageRegistry.Page(id, title, icon, sidebar = true, order = 100) { arg -> … })`.
  Reach it with `nav.root(Route.Extra(id))` or `nav.push(Route.Extra(id, arg))`; with `sidebar` it gets an entry after the
  library (ordered by `order`, the lens and the narrow icons included). Draw it with `Page(title, tag) { … }` so it keeps the
  shell's edges and the gliding wheel. A route that is no longer registered shows « Page indisponible ».
- **Right panel tabs** — `PanelTabs.register(PanelTabs.Tab(id, label, icon, order) { … })` (built-ins: lyrics 10, queue 20,
  artist 30, ketmix 40). Open one with `LocalShell.current.open(id)` (or `toggle`).
- **Settings sections** — `SettingsSections.register(SettingsSections.Section(key, title, after = "Windows", rows) { … })`:
  drawn after the built-in section named `after` (header item `h:<key>`), its `rows` indexed by the settings search.
- Glass for a new surface: put it outside the pages' source and use `Modifier.glass(backdrop, …)` with the shell's backdrop
  (passed to the sidebar, island and panel), or give it a local `glassSource` to refract.
- **Island marks and shell hooks** (`ui/Registry.kt`, Windows 2.0 social): `IslandMarks.register(Mark(id) { … })` draws a
  small mark beside the island's title (« Ensemble · 3 »); `ShellHooks.register(Hook(id) { … })` keeps an invisible piece
  composed with the shell's locals for the window's life (a `ketvoice://` link opened from the browser acts there).
  `TrackMenuSlots.share/shareAlbum/sharePlaylist` and `TrackMenuSlots.extra` add items to the title, album and playlist menus.
- **Full player blocks** (`FullPlayerSlots.register(Slot(id, order) { backdrop, compact -> … })`, Windows 2.0 wave 3): drawn under
  the full player's transport; each draws nothing when it has nothing to say.
- **Keyboard** (wave 3 polish): `KetIconButton`, `CapsuleButton`, `PlayPause`, the sidebar entries and the panel's tabs are
  reached with Tab and pressed with Enter (`Modifier.keyboardControl`), with an accent ring only while the keyboard has
  them (`focusRing`); Space stays the window's play/pause.
- **Shadow under glass** (`glassShadow`): a blurred copy of the outline drawn only outside it (Compose's elevation shadow
  left a bright band under the rounded bottom of the sidebar and the panel on light pages).

Tests: `KetmixRatingTest` (no live mix view in the tab or the full player, 👍/👎 in the tab and the island's toast only
with learning on, buttons under the keyboard), `PcPlanningTest` (what was learnt reaches the desktop's
plans only while learning is on; avoided filters and dissolves become fades), `Win2LogicTest` (glass policy and shader, layout and panel limits, insets, island sizes, unfold, hero pick, glide
at 60/144/240 Hz, rubber band, magnet, elastic slider, menu origin, lens, page transitions under reduced motion, frame
rate, compact player place and lyric line), `Win2ShellTest` (the island opening and unfolding, the panel's tabs, the narrow
sidebar, registered pages/tabs/settings sections, every glass mode, the compact player), renders `Win2Screenshots`
(`build/desktop-ui-preview/win2-*.png`, `win2-ketmix-last-dark` and `win2-ketmix-toast-*` included).

## Lyrics and library (Windows 2.0)

Started behind the window by `ui/PcLyricsLibrary.start` (one line in `Main.startBehind`); pages, overlays and settings go
through the registries (`PageRegistry`, `ShellOverlays`, `SettingsSections`).

- **Word sync by AI** (`lyrics/`): on « Puissance de Ketmix » « Maximum » (Auto on a desktop PC), lyrics timed by line
  only (or not at all) are timed word by word on the song's separated voice (`VoiceAudio`: the title's stored separation
  applied to its sound, made with the studio model when there is none; the whole mix without a model). The model is
  OpenAI Whisper small (MIT code and weights) as two ONNX graphs (`tools/align/export_whisper.py`, prerelease
  `ketvoice-align-v1` from `publish-ketvoice-align.yml`, pins in `tools/models/expected-align.sha256` and
  `AlignModels.kt`, review in `docs/licenses/ketvoice-align.md`), 970 MB downloaded when the listener asks (the lyrics'
  « Synchroniser les mots » capsule or Settings › Paroles sur PC). It never transcribes: `WordAligner` reads each window
  of at most 29 s with its known words (`WhisperTokens`, the BPE of `multilingual.tiktoken`) and `AlignMath` applies
  `whisper.timing.find_alignment`'s recipe (alignment heads, softmax, normalisation, median 7, DTW). Lines keep their
  time; a line whose words were not heard keeps line timing; a song whose text is not what is sung is dropped (and not
  tried again). `AiSync` runs one title at a time on a low-priority thread: 6 s into a title whose lyrics need it, and
  over downloads and local files at idle (`IdleWatch`). Results are enhanced LRC in `lyrics-v2/ai` (`AiLyrics`, keyed by
  a digest of the lines they came from) and shown with « · Synchronisé par IA »; the capsule or the lyrics editor
  (« Revenir à la synchro d'origine ») goes back, « Paroles actuelles » + « Enregistrer » corrects them.
- **Lyrics engine** (`lyrics/LyricsEngine.kt`, plugged into the shared `LyricsRepo.hooks`, null on the phone): every
  provider and every custom source with lyrics is asked at once and the richest timing shows (`data/LyricsRank.kt`:
  word > syllable > word by AI > line > plain; a fragment under 55 % loses; then the earlier source). The source menu's
  « Pour ce titre » lists what each source answered with its timing; a pick is remembered per title (« Meilleure
  synchronisation » forgets it). No new third-party provider was added: none of the free lyrics APIs found states terms
  that allow use in an app (LRCLIB, already asked, does).
- **Translation and romanisation**: capsules under the lyrics' source (Traduction, « Sous chaque ligne » for every line
  instead of the sung one, Romanisation when the lyrics are not all Latin script); translations are cached per line by
  `LyricsTranslation`, romanisations per song (`RomanCache`).
- **Floating lyrics** (`ui/FloatingLyrics.kt`, off by default): a transparent, always-on-top band on the chosen monitor
  with the sung line lit word by word (the lyrics view's `WordLine`); locked it lets clicks through
  (`platform/ClickThrough.kt`: `WS_EX_TRANSPARENT | WS_EX_LAYERED | WS_EX_NOACTIVATE`), unlocked it can be dragged and its
  place is kept as a share of the monitor. Toggled from the lyrics capsule, the tray (with « Verrouiller ») and Settings.
- **Import with a match report** (`ui/ImportPage.kt`, page `import`, argument = a link): one click reads the playlist
  (the shared importers, plus `smartlib/YouTubeImport.kt` for YouTube and YouTube Music links) and matches every title;
  the report (`smartlib/ImportReport.kt`) shows the share that will play, found / uncertain (score under 0.9) / missing,
  each a filter; « Corriger » searches the catalogue to fix one by hand or leaves it out.
- **Followed outside playlists** (`library/OutsidePlaylists.kt`): « Suivre la playlist d'origine » at import; every 12 h
  the songs it gained (`PlaylistFollow.outsideFresh`) are matched and added, or offered in a glass card (« Ajouter
  automatiquement les nouveaux titres », off). Nothing is ever removed.
- **Drop anywhere** (`ui/DropAnywhere.kt`, `library/DropKinds.kt`): a glass sheet while something is dragged over the
  window; playlist links open the import page, Ketvoice share links and music-service links play the title (Deezer's
  public API and YouTube/Spotify oEmbed name it), `manifest.json` is offered to `CustomSources.add`, audio files play or
  their folders join the library, a lyrics file is attached to the title playing after a question.
- **Watched library** (`library/LibraryWatch.kt`): the music folders are watched (every subfolder, new ones included);
  changes are gathered (1.2 s of quiet, 8 s at most) and read by `LocalFolders.update`; embedded lyrics (`lyrics-xxx`,
  `LYRICS`, `UNSYNCEDLYRICS`) and a `.lrc` beside the file become the title's lyrics.
- Tests: `AlignMathTest` (Whisper's recipe on a fixture computed by Whisper's code), `WhisperTokensTest` (tiktoken's tokens
  with the real vocabulary, when present), `WordAlignerTest` (fake model; the real model on jfk.flac when
  `KETVOICE_ALIGN_DIR`/`KETVOICE_ALIGN_CLIP` are set: the same 22 word starts as Whisper), `LyricsEngineTest` (ranking,
  choices, AI swap and revert, failures remembered, pins), `LyricsPcTest` (romanisation cases, the capsules, the band's
  place, click-through), `ImportLibraryPcTest` (report math, drop classification, watcher gathering and a real watch,
  followed playlists, YouTube parsing), `ImportPageTest` (the page end to end with a fake importer).

## Social (Windows 2.0, `social/`)

Small and private by default; nothing reaches the network before the listener signs in and opens a social piece, and
nothing is shared before they switch it on. Shared rules live in `app/.../social/` (pure, compiled here through `sync.txt`).

- **Listening rooms** (`social/DesktopTogether.kt`, tab « Ensemble », `TogetherPanel.kt`): the phone's Ketogether, same
  protocol and session (`together/TogetherSession`, `RealtimeTransport`, signed host state, drift correction), bound to
  the desktop `Player` through `DesktopPort` and `Player.transportGate` (the phone's contract: a guest's transport asks the
  host, or is refused while only the host drives). Host or join by code or link (`joinTyped`), host controls of 8.4 (who
  drives, who adds, how many, proposals, take someone out), six quick reactions, members with their sync, the activity
  lines and endings of the phone (`ui/together/KetogetherLook.kt`, moved out of the phone's sheet unchanged). A guest's
  shuffle/repeat/radio are set aside and given back; its Ketmix waits (`DesktopAutomix.held`). The island shows
  « Ensemble · N »; « Ajouter à Ensemble » joins a title's menu while in a room.
- **Profile** (page `profile`, `ProfilePage.kt`, `ProfileEditor.kt`): name, @handle, bio (160), accent, photo (square
  512 px) and banner (3:1, 1500 × 500) framed with the phone's `account/AvatarCrop` rules and sent as JPEG under 900 KB to
  the listener's folder of the private `ketvoice-avatars` bucket; modules « En cours d'écoute », « Artistes du mois » (from
  this computer's `LiveStats`, a snapshot) and « Playlists épinglées » (link snapshots), each Moi / Amis / Public
  (`social/ProfileRules.canSee`, the server's `ketvoice_profile_can_see`: friends see friends+public, others see public
  only while the profile is public). Other profiles open by handle (`profile/<handle>`), with the friendship buttons.
- **Friends and « Activité des amis »** (tab, `FriendsPanel.kt`): add by @handle or profile link, requests to accept or
  decline (`social/FriendStates`, the server's statuses), what friends play now and « Rejoindre » their room — only friends,
  only those who switched « Partager mon écoute » on (`Presence`: a write on change or once a minute while on, never off).
- **Smart share links** (`ShareLinks.kt`, shared `ShareUrls`): « Partager » in the title (`share` id of `layoutMenu`, the
  phone's own id), album and playlist menus copies a page of https://ketvoice-web.vercel.app (`/listen?track&title&artist&cover`,
  `/album/MPRE…`, `/listen?playlist=<link id>` for a playlist snapshot, `/u/<handle>`). `ShareLinks.trackUrl(track)` is
  null for a local file.
- **`ketvoice://`** (`DeepLinks.kt`): the installed launcher registers the scheme for the user (HKCU\Software\Classes\ketvoice,
  « "Ketvoice.exe" "%1" »; the MSI does not remove it at uninstall). Links reach the running window through the single
  instance (`DesktopPlatform.pendingLinks`, each feature `claim`s the links it knows) and open a title, album, artist,
  profile, shared playlist or a room's invitation.
- **Server** (`supabase/migrations/20261125120*_social_*.sql`, nine parts under 6 KB; `supabase/tests/social_rls.sql`;
  edge function `supabase/functions/ketvoice-profile` for pictures, `verify_jwt = false`): profile columns and modules,
  friendships (symmetric RLS, ids not readable), presence (RLS: friends + opt-in + fresh), the profile view, the anon public
  card for the website, playlist links. Listener accounts gain no publishing right; no catalogue table is touched.
- Tests: shared `SocialRulesTest`, `ShareUrlsTest` and the phone's Ketogether suites (run here too); `DesktopTogetherTest`
  (this PC's real Player in a room with a phone on one simulated time line: sync, drift correction, host control, PC as host,
  ending, modes given back), `SocialDesktopTest` (profile, modules, pictures, friends, presence, share menu, links),
  `SocialShellTest` (the tabs, pages and links in the shell; renders `build/desktop-ui-preview/social-*.png`).
