<h1 align="center">🎧 KETVOICE — RELEASE HISTORY</h1>

<p align="center">
  <strong>Every public version, explained as a before → after story.</strong><br>
  Eleven interface languages, following the device language on first launch. These release notes are in English.
</p>

---

# Ketvoice 9.1.2

![Ketvoice](ketvoice-banner.png)

**versionCode 156** — Play on the TV: Ketvoice TV (device kind `tv`) shows in the phone's devices list with a TV icon and a "Play on TV" action; Ketvoice for Windows 2.0.2 shows the same action in its Devices panel. Ketvoice TV 1.1.0 ships alongside.

- Handoff (`remote/RemoteWire.kt`, `remote/PhoneRemote.kt`, `remote/RemoteSession.kt`, `ui/player/RemoteDevices.kt`, `desktop/ui/Devices.kt`): the TV announces kind `tv` (older apps read it as a phone); `cmd`/`take`/`give` older than 30 s are dropped (`COMMAND_MAX_AGE_MS`). `SendToTvTest`.
- Shared for the TV: `automix/PlaylistMix.kt` (gentle tempo/key reordering, four titles ahead, pinned titles), `Automix.mixList`, `BeatModel.usable()` treats a TV as charging, `AutoLibrary(artistRadio)` (off on phone and car), `LyricsBlur.deviceSupported` off on TV.

---

# Ketvoice 9.1.1

![Ketvoice](ketvoice-banner.png)

**versionCode 155** — Ketmix echoes become rare (≤ 10 % of automatic transitions, measured 3.8 %), titles that stop at full level are mixed out before their last hit, fixes to fades, bass swap and echo clicks; recently played, statistics and the Discord choice sync with the account. Ketvoice for Windows 2.0.1 ships the same Ketmix and sync, a Canvas that fills the player, sharp cached covers and Studio artwork on Windows, and drops the live mix view.

- Ketmix planner (`automix/TransitionPlanner.kt`, shared): echo only when the outgoing title stops at full level, its voice ends within 4 s of the last note, the next tempo is ≥ 12 % away or unsteady, and no echo in the last 3 (`echoSuits`); builds at most once in four; `earlyExit` mixes out at the start of the last 8/16-bar phrase (16–4 bars before the end) with a beat-matched blend, filter crossing, dissolve or fade that ends by the last hit. `KetmixMixShareTest` over 2,400 pairs: echo 27.8 → 3.8 %, trims 3.5 %, blends 68.4 → 89.3 % (balanced). Hand-picked Echo/Rise play at close tempos; reason labels tell tempo distance, unsteady beat and crossing apart.
- Render (`automix/DeckDsp.kt`): plain fade into a fading title rises to −6 dB then settles over 8 s (bulge 4.19 → 1.84 LU); bass swap ⅛ beat each side of the downbeat (same-note dip 5.5 → 4.4 dB); echo send and dry close over an eased 60 ms (−43.6 → −57.5 dBFS above 4 kHz).
- Sync (`sync/ListeningKind.kt`, `sync/ConnectionsKind.kt`, `data/ListeningShare.kt`, `data/ListeningHistory.kt`, `discord/DiscordAccountSync.kt`): kinds `listening` (per-device `dev:<id>:` counters, summed by the apps; recents interleaved by time) and `connections` (Discord share choice, latest wins; per-device link state and name, never a token) in `ketvoice_library` (migration `20261126120000_library_listening_connections`). `ListeningSyncTest`.

---

# Ketvoice 9.1.0

![Ketvoice](ketvoice-banner.png)

**versionCode 154** — Ketmix on the phone: song sections in planning, a fuller meeting level at the bass swap and exact deck speed for blends in time, and optional learning from 👍/👎.

- Song sections (`automix/SongStructure.kt`, now shared with Windows, `automix/SongSections.kt`, `OnsetTracker.kt`, `AnalysisStore.kt`, `MixPoints.kt`, `StyleCuts.kt`): 16 band energies (~21 frames a second) kept from the spectra the analysis already computes, or the beat model's log-mel frames when they cover the stretch, run through the self-similarity structure finder on the bar grid; Balanced and Maximum only, never Light or Lite, never an extra read; `SegmentAnalysis.sections` stored with a version (old records read as none). Exits use section starts in reach but never a chorus's own start, phrase starts on a chorus's first bar are dropped, drops land at the end of the next intro or on its first chorus; editor points override. `SongSectionsTest`.
- Render quality (`automix/DeckDsp.kt`): meeting level at the bass swap 0.72 → 0.86 for both decks, equal-power fade on the outgoing side of a filter crossing, incoming deck speed no longer rounded to a 0.002 step. Measured on MixBench: dip before the swap 3.44 → 2.28 LU (64-beat blend), 2.11 → 0.84 LU (quieter incoming); beat drift over a 64-beat blend 29.1 → 0.0 ms; worst true peak -0.75 dBTP. `KetmixRenderQualityTest` limits tightened to 2.8 LU, new `twoDecksInTimeDoNotDriftApartOverALongBlend`.
- Ketmix learns from ratings (`automix/KetmixTaste.kt`, now shared with Windows, `automix/KetmixLearning.kt`, `ui/player/KetmixRate.kt`, Ketmix settings): optional, off by default; keys `ketmixLearns`, `ketmixTaste` (on the phone only, not synced, not in the personal backup); `MixContext.avoid` filled from the weights while on, automatic style only; 👍/👎 on the player's mix line for 9 s after a transition, last transition rateable in Settings, reset row. `KetmixLearningTest`, `KetmixRateHostTest`.

---

# Ketvoice 9.0.0

![Ketvoice](ketvoice-banner.png)

**versionCode 153** — a Personalisation page (theme, font, size, corners, density, lyrics look, tabs, Home, player, library, menus and swipes), a headphone EQ linked to each output, and a lyrics timing editor. Defaults reproduce 8.4.0.

- Appearance (`theme/ThemeColors.kt`, `theme/Appearance.kt`, `theme/Personalization.kt`, `ui/settings/PersonalizeSettings.kt`, `ColorPickerSheet.kt`): custom background/surface/text/accent per side with a WCAG AA guard, seven presets, `KT1.` share codes (base64url, version byte, CRC-8); fonts Inter (default), Manrope, Atkinson Hyperlegible Next, Nunito, Lora (SIL OFL); text scale 85–130 %, corner scale 0–200 % over `Ket27.Radius`, list density 44/52/60 dp rows; lyrics font, scale, alignment, sung colour, blur level and motion level. Keys `themeColors`, `appFont`, `textScale`, `cornerScale`, `listDensity`, `lyricsFont`, `lyricsScale`, `lyricsAlign`, `lyricsSung`, `lyricsSungArgb`, `lyricsBlurLevel`, `lyricsMotion`. `ThemeCodecTest`, `PersonalizationSettingsHostTest`, `PersonalizationRenderHostTest`, `DefaultLookRenderHostTest` (default renders compared with 8.4.0).
- Layout (`data/LayoutSettings.kt`, `ui/chrome/TabLayout.kt`, `ui/home/HomeSections.kt`, `ui/player/PlayerActions.kt`, `ui/library/LibraryLayout.kt`, `ui/common/RowLayouts.kt`, `ui/settings/LayoutCustomization.kt`): 2–5 tabs (Home or Library required) in both glass looks and the tablet sidebar; 11 Home sections ordered/hidden; player row 2–5 buttons (queue kept), up to two mini-player extras, artwork ×0.85/×1.15, Canvas off in the player; list/grid and default order per library section; title menu order and hiding (Ketogether first, More last); swipe actions per direction. Keys `layoutTabs`, `layoutHome`, `layoutPlayer`, `layoutMini`, `layoutArtwork`, `layoutCanvas`, `layoutLibraryViews`, `layoutLibrarySorts`, `layoutMenu`, `layoutSwipeEnd`, `layoutSwipeStart`. `LayoutSettingsTest`, `LayoutCustomizationHostTest`.
- Headphone EQ (`player/Biquad.kt`, `HeadphoneProfile.kt`, `HeadphoneEq.kt`, `HeadphoneEqProcessor.kt`, `ui/player/HeadphoneEqSection.kt`): imported Equalizer APO parametric profiles (preamp + up to 20 PK/LSC/HSC filters, 40 profiles), linked per output (Bluetooth address digest, wired, USB name digest), Audio EQ Cookbook biquads per sample rate, automatic safe preamp, 60 ms crossfade, between `DeckMixProcessor` and `LevelingAudioProcessor`. No built-in headphone catalogue: the measurement data has no redistribution licence (`docs/licenses/AutoEq-review.md`). Keys `headphoneEq`, `headphoneProfiles`, `headphoneLinks`. `HeadphoneEqTest`, `AudioChainPlaybackTest`.
- Lyrics editor (`data/LyricsDraft.kt`, `ui/player/LyricsEditor.kt`): line or word tapping on the heard position, ±50 ms nudge, 200-step undo, Cascade preview, saved as (enhanced) LRC in the custom lyrics store, LRC export. `LyricsDraftTest`, `LyricsEditorSaveTest`, `LyricsEditorHostTest`.

Full notes: [9.0.0](docs/releases/v9.0.0.md).

---

# Ketvoice 8.4.0

![Ketvoice](ketvoice-banner.png)

**versionCode 152** — Crystal glass look, a full Android Auto experience, Ketmix's loop bridge and sub-frame beats, a real audio output picker, settings search, playlist updates, a recap soundtrack, Ketogether host controls and a lyrics source order.

- Crystal (`glass/CristalGlass.kt`, `ui/chrome/CristalChrome.kt`, `CristalPill.kt`, `ui/common/CristalMenus.kt`): a second glass look, key `glassLook` (0 Liquid 27, default and unchanged; 1 Crystal; synced and backed up). Control glass: saturation 1.5, blur 8 dp, lens 24/24 dp with depth and fringing on API 33+, rim 0.5 dp at 45°, shadow 24 dp, tint 40 %, sampled at 0.33 (0.25 in performance mode, no lens); menu frost saturation 1.8, blur 24 dp; selection pill springs travel 280/31.4, lift 250/19–22.1, material 1000/63.3, squash ≤ 12 %. Built on backdrop 1.0.0-rc02's public API. `CristalGlassTest`, `CristalGlassMenuTest`, `CristalChromePillTest`, `CristalGlassSettingsHostTest`, `CristalChromeRenderHostTest`.
- Android Auto (`player/AutoLibrary.kt`, `AutoSession.kt`, `AutoCollections.kt`, `AutoVoiceMatch.kt`, `CarControls.kt`, `QueueWindows.kt`): play-from-search with artist/album/title/genre/playlist extras, likes, downloads, mixes, radio and shuffle phrases in the ten languages, resume on an empty request; four tabs (Home, Library, Radio, Downloads) with content-style hints, “Play all”/“Shuffle” rows, explicit and downloaded badges, paging at 100; the car's queue shows up to 150 titles around the current one; custom commands Like, Shuffle, Repeat, Radio and Ketmix on the shared session; covers through a disk cache. Mobile flavour only. `AutoTreeTest`, `CarControlsTest`, `QueueTimelineTest`, `AutoCarSessionTest`.
- Ketmix (`automix/LoopBridge.kt`, `BeatPost.kt`, `TransitionPlanner.kt`, `Automix.kt`): `MixStyle.Bridge` (ordinal 9) — four instrumental bars chosen from lyrics and the voice mask, one tempo glide of at most 2.5 BPM per bar with half/double folding, bass swap half-way, high-pass exit, then `TempoReturn`; chosen automatically only when the gap exceeds `tempoRange`. Beats and downbeats refined between frames (log-activation parabola); the window length is read from the model (the published model stays at 500 frames). `BeatPrecisionTest`, `KetmixLoopBridgeTest`; `tools/beat/phase.py`.
- Everyday (`player/AudioOutputs.kt`, `ui/settings/SettingsSearch.kt`, `data/PlaylistUpdates.kt`, `ui/sub/WrappedSoundtrack.kt`, `together/`, `data/LyricsOrder.kt`): per-device output routing through `setPreferredAudioDevice` on every deck (not saved); an accent-insensitive index of about 110 settings rows; a WorkManager check every 6 hours (key `playlistUpdates`, on); recap clips on separate players with a 1.2 s equal-power crossfade (key `wrappedSound`); Ketogether party limit 2–10, signed removal, a 2-minute away state, an 8-entry activity log and drift in pings (no SQL change); a draggable lyrics provider order (key `lyricsOrder`). `AudioOutputsTest`, `SettingsSearchTest`, `PlaylistUpdatesTest`, `RecapSoundtrackTest`, `TogetherHostRulesTest`, `LyricsOrderTest` and their host tests.

Full notes: [8.4.0](docs/releases/v8.4.0.md).

---

# Ketvoice 8.3.0

![Ketvoice](ketvoice-banner.png)

**versionCode 151** — smoother lyrics with lively held notes and a distance blur, player artwork sized by its shape, and lighter, cleaner, more physical glass.

- Lyrics (`KaraokeDraw.kt`, `LyricsBlur.kt`, `LyricsView`/`LyricsMotion`): the fill moves at one constant pace inside each word with a 30 dp feathered edge; the lift follows the word's length (0.25 to 1) and settles back; words of 1–7 Latin, Greek or Cyrillic letters held ≥ 900 ms swell letter by letter with a 9 dp blurred glow on Android 12+; backing vocals open with a rise; break dots before every instrumental gap of 4 s or more. Inactive lines blur 0/1/1/1.7/2.4 dp by distance (key `lyricsBlur`, on), never on the sung line, while scrolling, under reduced motion, performance mode or economy, below Android 12, on Quest or on low-RAM Lite phones; lines outside their animation window no longer redraw every frame. `LivelyLyricsTest`, `LivelyLyricsHostTest`.
- Player artwork (`PlayerArtworkSize.kt`, `FullPlayerScreen`, `PlayerArtworkFrame`): the phone player's artwork is full width, top aligned, with height = width ÷ media aspect between 220 dp and the top of the controls; the aspect comes from the Studio framing, Media3's video size, the Canvas still, its metadata or the cover; when capped the bottom is trimmed; a no-overshoot spring between shapes. Tablet, Quest and landscape layouts unchanged. `Player83HostTest`.
- Glass (`GlassResolution.kt`, `FrozenBackdrop.kt`, `PillPhysics.kt`, `Chrome.kt`): the backdrop blur runs on a 1/3 copy (1/4 in performance mode or economy; about 0.73 for the clearest navigation glass) with a matched radius, the shader and rim stay full size; the captured backdrop always has an opaque floor; menus, sheets and alerts blur a still copy of the page (live over a playing Canvas and on Android 11); the tab selection pill uses integrated springs (travel 380/0.86, lift 520/0.5), squash capped at 12 %, a lens over the bar's own glass while lifted, and a plain move under reduced motion. `GlassResolutionHostTest`, `ChromePillPhysicsHostTest`.

Full notes: [8.3.0](docs/releases/v8.3.0.md).

---

# Ketvoice 8.2.0

![Ketvoice](ketvoice-banner.png)

**versionCode 150** — living cover-colour player background, livelier Cascade lyrics, featured Home cards, startup profile rules, smart prefetch on Wi-Fi, automatic power saving, an opt-in diagnostic, and Ketmix Studio presets in the account.

- Look (`ArtworkFlow.colourField`, `PlayerSceneBackground`, `LyricsView`/`LyricsMotion`, `HomeFeatured.kt`): the three turning layers are a 3×3 cover-colour field built once per title off the main thread and animated in draw; stopped in performance mode, under reduced motion, on Quest and behind a Canvas. Lyrics settle on `WAVE_SPRING`/`SCROLL_SPRING`, the sung word grows ≤ 3.5 % (`LyricsWords.grow`), past lines fade to .66–.36 (`pastAlpha`), read in the layer; no blur. Five featured cards; only the centred one plays its Canvas. `Look82HostTest`.
- Performance (`baseline-prof.txt`, `util/EconomyMode.kt`, `data/SmartPrefetch.kt`, `util/Diagnostics.kt`): named hot-path rules (handwritten); economy mode at battery ≤ 20 % or thermal ≥ SEVERE, never saved, key `autoEconomy`; prefetch on unmetered networks only, bounded (3 queue covers, 18 Home covers, album and artist of the playing title); diagnostic (key `diagnostics`, off) as capped JSON lines with hashed track ids, shared through the share sheet. `Perf82Test`.
- Ketmix Studio presets (`MixPresetStore.syncEntries`/`adoptSynced`): carried in the settings document as `ketmixPreset.<hash>` items; adoption only adds, so a device on an older version never deletes presets. `MixPresetSyncTest`.

Full notes: [8.2.0](docs/releases/v8.2.0.md).

---

# Ketvoice 8.1.0

![Ketvoice](ketvoice-banner.png)

**versionCode 149** — performance and latency pass, Canvas edge to edge again, a tap on the header cover returns to the player, and phone control with Ketvoice for Windows.

- Smoothness (`ui/chrome/Chrome.kt`, `ui/player/PlayerPresentation.kt`, `glass/Liquid27Fusion.kt`, `ui/AppRoot.kt`): the tab bar fold and the player presentation read their springs in layout, offset and draw instead of composition; `AppRoot` no longer recomposes on mini-player bounds. Home scroll with the fold: 15,934 → 1,079 recompositions (19 → 3 composed frames); player opening 28 → 5–6 composed frames of 60. `Perf81HostTest`.
- Loading (`data/RecentAnswers.kt`, `HomeFeed`, `CatalogRepo`, `ArtistPages`, `MusicApi`, `YouTubeCatalog`, `CanvasRepo`, `Downloader`): in-flight requests are shared and recent answers kept a few minutes; album and artist pages load from the tap; the personal YouTube Music Home is kept on disk; YouTube's player asks the last working client first; the Canvas on screen has its own lane; the downloads folder is read off the main thread. Measured on a fake 4G network (`LoadLatencyHostTest`): artist releases 3.0 → 1.0 s, replayed title 1.16 → 0.35 s, repeated search 0.7 s → immediate, Canvas on screen 3.6 → 1.8 s.
- Player: the Canvas is edge to edge again in the player and album header (`CanvasFit` no longer used there); a tap on the header cover over the lyrics or the queue returns to the player (`Player80HostTest`).
- Your devices (`remote/RemoteWire`, `RemoteSession`, `RemoteControl`, `PhoneRemote`, `ui/player/RemoteDevices.kt`): control between the listener's devices over the Ketogether Realtime transport; the channel comes from a per-account key in the owner-only settings document; HMAC-signed messages with a timestamp and nonce; no SQL change. `RemoteControlTest`, `RemoteDevicesHostTest`.

Full notes: [8.1.0](docs/releases/v8.1.0.md).

---

# Ketvoice 8.0.0

![Ketvoice](ketvoice-banner.png)

**versionCode 148** — refractive Liquid Glass paths and a melting tab bar, menus that grow from their button, springs and animated icons, the morphing player, duet lyrics, Ketmix Studio and live statistics.

- Glass (`glass/GlassRenderMode.glassMaterialPath`, `Liquid27Shader`, `Liquid27Fusion`, `ui/chrome/Chrome.kt`, `ChromeCollapse`): material paths Refractive, RefractiveLite (performance mode on API 33+: no dispersion, one luminance sample, lens 0.7), Opaque, Blur, SoftBlur, Static; dispersion kept to the outer rim; tabs fold into the current-tab capsule on a velocity-seeded spring. `GlassMaterialPathTest`, `ChromeMorphTest`, `ChromeMorphHostTest`.
- Motion (`ui/motion/GrowFromSource.kt`, `Motion.Springs` SNAPPY/SMOOTH/BOUNCY/GROW, `SymbolEffects`): context menu, `KetMenu` (own popup on `GlassPanel`), `SpringSheet` and alerts grow from the pressed control; 39 tweens replaced by springs; slide/ring/breathe/replace icon effects. `Motion80HostTest`.
- Player (`ui/player/PlayerMorph.kt`, `FullPlayerScreen`): one spring value morphs the artwork into a 56 dp header for lyrics and queue, drag-driven; reduced motion fades. Duets (`data/LyricsDuet.kt`, `LyricLine.singer`): TTML `ttm:agent`, enhanced LRC `v1:`/`v2:`/`F:`/`M:`/`D:`, custom JSON, or section headers naming a featured artist; never guessed. `LyricsDuetTest`, `PlayerMorphTest`, `Player80HostTest`.
- Ketmix Studio (`automix/MixStudio.kt`, `ui/player/KetmixStudio.kt`): fixed toolbar with undo/redo (60 states, 600 ms grouping), beat magnet and “+ Point”; pinch zoom 0.5–6×; presets in `ketmix-presets-v1`; `KM1.` share codes. Pair storage formats unchanged. `MixStudioTest`, `KetmixStudioHostTest`.
- Statistics (`data/LiveStats.kt`, `ui/stats/StatsScreen.kt`, `util/StatsShareCard.kt`): week/month/year/all with like-for-like deltas, tops, hour histogram, streak, new artists, Ketmix mixes; days keep hours and first listens, months keep per-day minutes (old data loads). `LiveStatsTest`, `StatsHostTest`.

Full notes: [8.0.0](docs/releases/v8.0.0.md).

---

# Ketvoice 7.6.0

![Ketvoice](ketvoice-banner.png)

**versionCode 147** — whole-Canvas fit, per-title Canvas cache in the player, performance pass, streaming voice separation, volume keyframes in the mix editor, three new Ketmix styles and a render guard, the "Improve your listening" welcome step, the GTA VI event removed with its icon as the default.

- Canvas (`ui/player/CanvasFit.kt`, `CanvasRepo.aheadOf`, `CanvasRepo.forgetStandIns`): a Canvas within 12 % of its space's shape fills it, any other is shown whole with its edge colours run to the border (owner framing wins); album headers keep the words under the video. The playing title's Canvas is kept screen off on unmetered Wi-Fi and the next two are prefetched; the album Canvas only stands in while a title's own is looked up; a one-time migration forgets 7.5 stand-in mappings. `CanvasPlayerReplayHostTest`, `Canvas76HostTest`.
- Performance (`PlayerSliders`, `Chrome`, `LyricsView`, `Player` position loop, `AppText.warm`, `Downloader.newestFirst`, `CoverPalette`): scrub bar and mini-player progress read in layout/draw (full player 15 759 → 70 composable runs in 5 s on the host), paused position ticks 300 → 6 per minute, translations warmed off the main thread, start-up file operations 758 → 445 with 40 downloads, cover accent cache 32 → 256 with misses remembered. `PerfRecompositionHostTest`, `PerfBackgroundHostTest`, `StartupIoHostTest` (unit-test JVMs get `-Djava.security.manager=allow`).
- Voice (`stems/StreamSeparation.kt`, `StemStore.savePartial`, `automix/MixKeys.kt`): streaming separation from the playhead with partial masks taken up live, DASH audio renditions (HLS not supported); keyframes (voice, instruments, music for both titles) stored in `PairChoice` as `base|v2|…` (old data readable), music level in `DeckDsp`, voice/instruments in `VoiceStage`. `MixKeysTest`, `StreamSeparationTest`, `KetmixKeysHostTest`.
- Ketmix (`MixStyle` 6 Cut, 7 On the drop, 8 Vocal handoff; `StyleCuts`, `MixBus`): filters cross-faded over 12 ms (clicks above 4 kHz −30…−38 → −73…−97 dBFS on synthetic fixtures), earlier incoming level after the bass swap, a linked bus keeps both decks under −1 dBFS. Automatic mode may pick On the drop only; `ketmixStyle` range 0..8. `KetmixRenderQualityTest`, `KetmixStylePickerHostTest`.
- Welcome (`ui/onboarding`): an "Improve your listening" step and Settings sheet for the separation models, the rhythm model where not bundled and the local scan, with a warning on leaving without any. Vice City edition removed (`ViceCityEdition`, Vice FM, theme; `viceCityTheme` retired); `.LauncherViceCity` is the default icon (`LauncherIcons.DEFAULT` 4, `appIconDefault76` marker).

Full notes: [7.6.0](docs/releases/v7.6.0.md).

---

# Ketvoice 7.5.0

![Ketvoice](ketvoice-banner.png)

**versionCode 146** — Google Cast, lyric romanization, standby mode, new cover-colour picker, bigger album cover without the scroll fade, Canvas/cover cache fixes, ranged downloads, faster analysis, one-song quality upgrade, pause/resume fade, Ketmix-on-skip switch, one glass-intensity slider, glass lens on held sliders, search and sort inside lists.

- Cast (`cast/`, mobile and Lite; Quest gets a stand-in): `CastPlayer` behind the media session with the Default Media Receiver, one title at a time, downloads and local covers from a token-path LAN server that lives only while casting; Ketmix and quality upgrades pause. `CastPlanAndServerTest`, `CastItemsTest`, `Cast75HostTest`. Lint `UseAppTint` off (RemoteViews need `android:tint`).
- Romanization (key `lyricsRomanization`, switch at the top of the lyrics source menu): ICU transliterators plus an own Revised Romanization for Hangul; kanji lines left without a reading. `RomanizerTest`.
- Colours (`CoverColors.analyze`): OKLab clustering weighted by area and saturation, frames and fine detail discounted, near-black/near-white only when dominant; 13 fixtures with a contact sheet. `CoverPickerFixturesTest`, `Album75HostTest`. Header cover 75 % width (≤ 380 dp, ≤ 46 % height), `heroShrinkScale` removed.
- Speed (`CanvasRepo`, `RangedDownload.kt`, `StreamResolver`, `PauseFade.kt`, `stems/StemDsp.kt`, `automix/BeatSpectrogram.kt`): local Canvas without lock or request, album Canvas stands in, “none” kept 3 days, use refreshes eviction age; 4-part ranged downloads from 6 MB; lossless offer ends the probe after 1.5 s; two upgrades at once; polyphase resampler weights per phase and sparse mel sums (JVM: 1000 → 265 ms on 75 s). Keys `ketmixOnSkip` (on). `CanvasReopenHostTest`, `CoverCacheHostTest`, `DownloadSpeedHostTest`, `PauseFadeHostTest`, `AnalysisSpeedBenchmarkTest`.
- Motion header (`ui/sub/AlbumHeader.kt`, `CanvasRepo`, `PlayerControls.CanvasVideo`, `DiskBudget`): `albumHeader` 1 “Animated Canvas” is a full-width Apple-Music-style motion header (≈ 72 % height, fade into the page colour, title/artist/meta over it, parallax and pull zoom); value 2 maps to 1 (load, backups, sync). First-frame poster `<name>.poster.jpg` shown until the video draws; album Canvas remembered as `page:<id>`; album pages cached 30 min; a closed page keeps its player 20 s (not Lite); album tiles prefetch on Wi-Fi (12 per session). `Motion75HostTest`.
- Glass and lists (`GlassClarity`, `ui/common/PressLens.kt`, `data/ListOrder.kt`, `ui/common/ListTools.kt`, `ui/standby/Standby.kt`, `StandbyDream`): key `glassIntensity` (0..1) replaces `glassTintLevel` + `glassTinted` (migrated from prefs, backups and sync); `standbyMode` (off). `DownloadSort` removed. `Glass75HostTest`.

Full notes: [7.5.0](docs/releases/v7.5.0.md).

---

# Ketvoice 7.4.0

![Ketvoice](ketvoice-banner.png)

**versionCode 145** — two real home-screen widgets with picker previews, immersive artist page, player colours and blur without flashes, bigger Downloads/Favourites covers without the scroll fade, the Downloads ⋯ menus fixed, airier song rows, weekly discoveries removed.

- Widgets (`widget/NowPlayingWidgets.kt`, `WidgetViews.kt`, `WidgetPalette.kt`, `WidgetState.kt`, `WidgetActionReceiver`): the nine Glance widgets are replaced by RemoteViews “Now Playing” 2×2 (receiver name `NowPlayingWidgetReceiver` kept) and “Now Playing + Controls” 4×2; buttons reach `Player` through a non-exported receiver (session read from disk; empty state resumes through the Android Auto path); chronometers while playing, bar refresh every 3 s only with the screen on; cover colours under `CoverLegibility`; `previewLayout` + host-rendered `previewImage`. Glance removed. `NowPlayingWidgetsHostTest`.
- Pages (`common/CollectionHero.libraryArtworkSide`, `common/TrackRow.kt`, `data/Recordings.kt`): weekly discoveries, their worker and `MixKind.Discover` removed (old work cancelled, data deleted); Downloads/Favourites cover about 66 % of the width with no fade; row ⋯ opens the glass menu, top-bar ⋯ the page menu; 72 dp rows with inset separators. `Pages74HostTest`.
- Feel (`ui/common/SystemBarTint.kt`, artist hero, player colours): artist photo hero (58 % height, parallax 45 %, cover fallback); player colours cached, held until the next are ready and read ahead, fixed dim band per hue, veil from cover lightness, pre-blurred still background with crossfade, feathered layers and grain, Canvas fades in, 700 ms glides. `PlayerColorsTest`, `ArtistHeroTest`, `Feel74HostTest`.

Full notes: [7.4.0](docs/releases/v7.4.0.md).

---

# Ketvoice 7.3.0

![Ketvoice](ketvoice-banner.png)

**versionCode 144** — Downloads selection and better-quality re-download, live download rings, playlist import from Apple Music/Tidal/SoundCloud, Apple-style album header with Canvas options, search start page, breathing artwork, song credits, double mini-player during Ketmix mixes, share card, per-album accent.

- Downloads (`data/DownloadUpgrade.kt`, `ui/sub/DownloadSelection.kt`): selection starts from the long-press menu; the selection menu plays, adds, deletes or upgrades. An upgrade is offered only for lossy → lossless, a known higher lossless sample rate, or +64 kb/s lossy; the new file must pass a header check and the old one is removed only after the metadata points to the new one (rollback and crash cleanup). DASH and owner uploads are excluded. `DownloadUpgradeTest`, `DownloadsSelectionHostTest`.
- Import (`smartlib/PublicPage.kt`, `AppleMusicImport.kt`, `TidalImport.kt`, `SoundCloudImport.kt`): public pages only (page data, JSON-LD, embed player), no keys or tokens; Tidal embed lists 50 titles, SoundCloud's page about 20, reported as partial with counts. ISRC lookup through Deezer's public API; a length gap over 45 s scores below the threshold.
- Look (`ui/sub/AlbumHeader.kt`, `ui/overlay/SearchStart.kt`, `ui/common/TrackCredits.kt`): setting `albumHeader` (0 cover, 1 Canvas, 2 cover + Canvas, cover fallback); search start without recent searches; `rememberArtworkBreath`; credits from catalogue data and real codec data. `Look73HostTest`.
- Extras (`ui/player/MiniMixCovers.kt`, `util/ShareCard.kt`, `CoverLegibility.pageAccent`): mini player follows `Automix.ui`; 1080×1920 share card with Instagram Stories intent and fallbacks; cover accent on album and artist pages under the WCAG rules. `MixShareAccentHostTest`.

Full notes: [7.3.0](docs/releases/v7.3.0.md).

---

# Ketvoice 7.2.0

![Ketvoice](ketvoice-banner.png)

**versionCode 143** — Apple-Music-style Home and Library, Favourites and Downloads pages, Lossless badges, “Recommended songs”, progressive quality that really switches, faster start, compact Canvas, the Sync button fixed, phone↔web change checks, search ranking, recommendations.

- UI (`ui/library/LikedSongsScreen.kt`, `CollectionCovers.kt`, `ui/home/HomeLibraryShelf.kt`, `ui/common/LosslessBadge.kt`, `ui/sub/MoreLikeThis.kt`): heart cover drawn in code; Downloads mosaic of up to four distinct covers (same picture or album skipped); Library root with Favourites/Downloads first and the playlist list; Lossless/Hi-Res (> 48 kHz) only from the decoder's codec or a saved FLAC header; “Recommended songs” from `Player.autoplayCandidates` + `AutoplayRules.pick`, lazy and cached per session.
- Playback (`QualityHandoff`, `PlaybackService.prepareUpgrade`, `BetterStream`, `StartupOrder`): the lag is measured once and the deck gap closed by seek (> 400 ms) or a muted speed nudge, then a 96/240 ms crossfade; Ketmix-adopted and cache-started titles look for better; first sound gets the network for up to 4 s. `ProgressiveHandoffTest`.
- Canvas (`data/CanvasCompact.kt`, `util/CanvasTranscoder.kt`): large files re-encoded at screen resolution (HEVC 0.07 / H.264 0.10 bpp·frame, 1.5–8 Mb/s) by a charging WorkManager job, replaced under the same name only after a check. `CanvasCompactTest`.
- Sync: the profile button ran `LibrarySync.syncNow()` on the main thread (`NetworkOnMainThreadException` reported as offline); now on IO with offline / session expired / server error states. Revision checks on resume and every 3 min (app) or every minute and on tab focus (web). `SyncButtonTest`.
- Search (`data/SearchRank.kt`) and recommendations (`Recommender`, early-skip memory on the device). `SearchRankTest`, `RecommenderTest`.

Full notes: [7.2.0](docs/releases/v7.2.0.md).

---

# Ketvoice 7.1.0

![Ketvoice](ketvoice-banner.png)

**versionCode 142** — Ketmix quality pass from listener complaints about other automixes, the editor preview fixed, glass on navigation only, Clear/Tinted glass, true black, mini-player swipe, A–Z index, a 4×1 widget, a radio without repeats; the DJ set mode removed.

- Ketmix (`automix/CutBudget.kt`): `CutBudget` checks every automatic plan (listener editor points exempt) and falls back to a shorter transition; `Endings.afterGap` plans false endings and hidden tracks on the music after the silence; `PairFit` turns distant pairs with really different tempos into a 3 s fade. `BeatAnalysis.structure` finds the last ≥ 2 s silence and spectral flux (stored by `AnalysisStore`; older readings lack them until re-read). `BlendFit.steady`: both grids at confidence ≥ .55; no beatmatch start under a voice beyond 3.5 %. `MixPlan.voicesMeet`: outgoing mids −7.5 dB when sung lines meet in a filter/wash crossing. `VolumeLeveler` holds during a transition (`LevelHold`). `KetmixBudgetTest`.
- Editor preview (`automix/TransitionPreview.kt`): `TransitionPreview.volume` gives a deck full volume once its processors carry the levels (the incoming deck stayed at 0 before); a start generation and one `resumeMain` flag end the A/B race. `TransitionPreviewPlaybackTest` plays two generated WAVs through the service's audio chain.
- Design: content buttons use solid fills (`LiquidGlassButton(plain = true)`, `LocalOnGlass` inside glass panels); `glassTinted` (Clear/Tinted, minimum opacity 62–72 %, on with Android's high-contrast text); menus and sheets at least 84 % (93 % tinted); `trueBlack`; mini-player keeps Next and swipes titles (phone and tablet); A–Z index on name-sorted Library lists of 30+; Glance widget “Controls” (4×1, down to 110 dp, not on Quest); radio and autoplay skip titles heard in the last 4 hours (`heardAt`, kept one day). `Design71Test`, `Design71HostTest`.
- Removed: “DJ set mode” (`DjSet`, `SetRender`, `SetExport`, `DjSetPanel` and their strings).

Full notes: [7.1.0](docs/releases/v7.1.0.md).

---

# Ketvoice 7.0.0

![Ketvoice](ketvoice-banner.png)

**versionCode 141** — Home “For You”, Ket 27 identity pass, autoplay of the same style, Apple-Music-style Downloads and downloaded albums, Ketmix DJ set and the built-in rhythm model.

- Identity (`theme/Ket27.kt`, `ui/motion/Motion.kt`, `KetIcons.kt`, `SpringSheet.kt`): secondary text 60 % → 73 % in light, `Ket27.Space`/`Ket27.Radius` scales, `Motion.Springs` (push, zoom, sheet, dismiss, appear), stroke 1.8 with solid Play/Pause, one sheet grabber. Home “For You”: Resume, Mix of the day, moods by the hour, new releases of followed artists. `ui/common/FlowPick.kt` reads `StateFlow` parts in composition without `.value`.
- “Autoplay” (`player/Player.kt`, `ui/player/QueuePane.kt`): InnerTube `next` with `RDAMVM<videoId>` (audio only), a YouTube Music search gives catalogue/local titles a video id; fallbacks: `CatalogRepo.artistRadio`, then offline same artist / same style (`SmartFacts`) / same feel. Batches of 10 when ≤ 3 remain; seed, queued and session titles, blocked artists and > 2 per artist left out; tempo/energy filter when Ketmix analysed both. `autoEntryIds` are not kept across a session restore. `AutoplayRulesTest`, `DownloadsAlbumsAutoplayHostTest`.
- Downloads (`ui/sub/DownloadsScreen.kt`, `ui/library/LibraryScreen.kt`): Songs/Albums/Artists/Playlists with counts; downloaded albums open on an album page with per-title state, “Download missing songs”, “Remove download”; “X of Y songs downloaded” once the catalogue tracklist loaded.
- Ketmix: the build fetches the pinned `ketmix-beat-small0.tflite` (size and SHA-256 checked) into generated assets, stored uncompressed, mobile and Quest only (+18.6 MB); `-PketvoiceWithoutBeatModel=true` builds offline; CI checks the APKs. `automix/DjSet.kt` (tempo, Camelot neighbours, energy peak at 70 %), `SetRender.kt`/`SetExport.kt` (offline render through `LoopFx`/`DeckDsp` to AAC/M4A 256 kb/s, WAV fallback; downloads and local files only; no tempo matching in the export).
- Fixes: `TransportGlyphAt` draws both halves in one path (no seam down the play triangle); `MainActivity.openSharedTrack` opens `kind=artist` links from the website's public artist pages.

Full notes: [7.0.0](docs/releases/v7.0.0.md).

---

# Ketvoice 6.9.0

![Ketvoice](ketvoice-banner.png)

**versionCode 140** — Ketmix editor redesign and three Ketmix exclusives.

- Editor (`ui/player/KetmixEditor.kt`, `KetmixPanel.kt`): one timeline on the outgoing clock, a draggable/resizable transition box (beat/bar/phrase snapping kept), the decks' real automation drawn (volume, bass, filter), bar numbers, `VoiceActivity` passages and lyric line starts, zoom; presets from `MixStyle.values()` through `presetOf()`; detail rows stored as `MixShape` in the pair choice (old choices still read, default identical: `MixShapeTest`), applied by `MixShaping.apply` and honoured by `DeckDsp`; staged choices written on « Enregistrer »; `automix/TransitionPreview.kt` plays the transition alone on two borrowed decks with an A/B plain cut and restores the main player (the old `Automix.preview` is gone).
- Exclusives: `LyricTimes.kt` (sung lines from synced lyrics or separation; `MixPlan.voiceDownMs`; entries moved before a line; `Automix` reads lyrics only for the playing title), `LoopFx.kt` (`LoopBlend`: a clean 4/8-beat bar after the last sung line and before a fade, beat confidence ≥ .7, looped sample-accurately with 4 ms seams), `MixVariety.kt` (Auto only). `KetmixExclusivesTest`, `KetmixEditorHostTest`.

Full notes: [6.9.0](docs/releases/v6.9.0.md).

---

# Ketvoice 6.8.0

![Ketvoice](ketvoice-banner.png)

**versionCode 139** — Beat This! small as an optional Ketmix rhythm model.

- `automix/BeatModel.kt`, `BeatSpectrogram.kt`, `BeatPost.kt`, `BeatMapping.kt`: pure-Kotlin log-mel front end (22.05 kHz, 1024/441, 128 slaney bands, `log1p(1000x)`), 500-frame (10 s) LiteRT windows with overlap, Beat This!'s minimal post-processing, a tempo/bar fit merged with the DSP analysis (model used when confidence ≥ .6 and it does not contradict a sure DSP result). `SegmentAnalysis.analyser` is stored as `"an"`. Always on mobile, charging only on Lite and modest devices. Settings → Ketmix: « Calage précis du rythme ».
- Model: `.github/workflows/publish-beat-model.yml` converts Beat This! small (ONNX export, MIT) with a fixed input [1, 500, 128] and float16 weights, checks it against the original, and publishes `ketmix-beat-small0.tflite` (18,575,568 bytes, SHA-256 `74cc5ee9…`) as the `ketmix-beat-v1` prerelease. 30 s windows were not used: Beat This!'s attention tensors grow with the square of the length.
- `tools/beat/` (prepare, check, reference), `BeatModelTest`, `BeatSettingsRowTest`.

Full notes: [6.8.0](docs/releases/v6.8.0.md).

---

# Ketvoice 6.7.0

![Ketvoice](ketvoice-banner.png)

**versionCode 138** — Ketmix fit rules, Lite pass 2, the GTA VI welcome restored, host tests off the network.

- Ketmix: `automix/BlendFit.kt` (blend length from tempo stretch and level gap; vocal-over-vocal guards for beat-matched blends and phrase snapping; pickup-aware bar alignment, bass swap on a downbeat); `MixPlan.outGain` leads the outgoing title down to a quieter one (max 4.4 dB), no Rise into a title over 4 dB quieter; albums in order with running sound or no beat are not mixed. `KetmixFitTest`.
- Lite: `AppText` reads no dictionary in French and streams one language otherwise; `SoftBlur` keeps its pyramid layers while size, blur, backdrop and place are unchanged (`BackdropPlaces`); `TabletMiniPlayer` scrubber and line drawn in the draw phase.
- Vice City: `ViceCityWelcome.kt` and `vicecity_welcome.webp` restored from 6.5 with their own night and sunset colours; the theme stays one accent and one illustration.
- Host tests run with a dead local proxy (`app/build.gradle.kts`): a real lyrics answer kept `TabletLayoutHostTest` from getting idle.

Full notes: [6.7.0](docs/releases/v6.7.0.md).

---

# Ketvoice 6.6.0

![Ketvoice](ketvoice-banner.png)

**versionCode 137** — smoothness pass, Apple Music style Playlists/Downloads, large-window content layouts, calm Vice City.

- Smoothness: `ListeningHistory` publishes its maps every 5 s of listening (was every 200 ms tick); Wrapped story progress, mini-player line, now-playing bars, indeterminate bar, artist skeleton, Ketogether dot and Ketmix sheen read their animations in the draw phase; Library Albums/Artists grids are lazy rows (`coverGridRows`) with keys and content types; `DownloadCover` and `rememberTrackCover` follow one title; Home reads only the playing title; tilt-light redraw threshold ~0.65°; friends feed refresh on IO; `UiTextDictionary` caches template results; cover-colour cache 512. Image decodes bounded to the screen on every edition; mobile detects modest phones like Lite and applies the invisible Lite rules there. `SmoothnessHostTest`, `MobileImageBudgetHostTest`.
- Library: `PlaylistsView.kt` (list/grid, sort, filter, join, file import), `KetMenu.kt`; playlist page (centred artwork, Play/Shuffle, footer), reorder from the playlist menu; share/members/public actions wired from `AppRoot`. Downloads page restyled; remove from the row menu.
- Large windows: `LargeScreen.gridColumns`/`listColumns`; one-column collection pages with the artwork beside the title; Library root hides categories beside the sidebar. `AppleLibraryHostTest`.
- Vice City: `ViceCityNeon.kt`, `ViceCityDecor.kt`, neon hooks and the welcome/palm images removed; `ViceCityScene.kt` (flat sunset, day/dusk), accent `#AD2756` light / `#FF7AA8` dark; listener's light/dark kept.

Full notes: [6.6.0](docs/releases/v6.6.0.md).

---

# Ketvoice 6.5.0

![Ketvoice](ketvoice-banner.png)

**versionCode 136** — Ketvoice Lite for Android 11 and low-memory devices.

- `lite` flavour: `minSdk = 30` (mobile stays 31, Quest 32). `glass/SoftBlur.kt` blurs the backdrop without RenderEffect below Android 12 (a downscale pyramid of offscreen layers on the GPU, a three-box Gaussian in software); `glass/SoftImage.kt` blurs covers once from a 64 px copy; `widget/WidgetCorners.kt` rounds widget parts inside bitmaps on Android 11; `PlaybackRevival.exactAllowed` is true below 31.
- `LiteTuning`: modest devices (low-RAM flag, < 3.3 GB, or < 4.4 GB without GLES 3.2) get RGB_565 opaque decodes bounded to the screen (`DecodeBound`), one Canvas video at a time (720×1280 cap), quick-model separation away from the charger, image memory freed at the first warning; Settings → À propos « Ketvoice Lite » row.
- Glass: `colorControls` called with named arguments (Blur mode had saturation applied as brightness).
- Host tests: `-ProbolectricSdks=31` forwards `robolectric.enabledSdks`; new Lite host tests declare `sdk = [30, 31]`.

Full notes: [6.5.0](docs/releases/v6.5.0.md).

---

# Ketvoice 6.4.0

![Ketvoice](ketvoice-banner.png)

**versionCode 135** — friends feed, widget redesign, smart offline, local music pro, Vice City neon night.

- Friends feed (`sync/FriendsClient.kt`, `sync/FriendsFeed.kt`, `ui/friends/*`): migration `20261119120000_friends_feed.sql` (follows, blocks, trigger-written activity, reactions; tables granted to no client role, only security definer RPCs; `ketvoice_public_profile` now honours blocks), tested with `supabase/tests/friends_feed_rls.sql` on a local PostgreSQL 16 (`supabase/tests/supabase_stubs.sql`).
- Widgets (`widget/*`): Glance responsive layouts, cover-colour gradient, launcher radius, coalesced updates (`WidgetCoalescer`), new Live lyrics / Ketmix / Replay widgets, `QuickTiles`, pinning from Settings; `PlaybackRevival.playing` clears the resume notification.
- Smart offline (`data/SmartOffline*.kt`): planner, WorkManager on unmetered networks (charging optional), auto marks, manual deletions remembered; keys `smartOffline`, `smartOfflineGb`, `smartOfflinePercent`, `smartOfflineCharging`.
- Local music (`data/Local*.kt`): tags, grouping, catalogue matching as proposals, MP3/FLAC tag writing on request, covers, lyrics, four views.
- Vice City: `SeasonLook.BottomDecor` synthwave floor, chrome titles (`PageTitle(neon)`), `ViceCityTheme.LocalNeon` player tints, `LyricsWords.glowColor`, `sheetEdge()`, `data/ViceFm.kt`.

Full notes: [6.4.0](docs/releases/v6.4.0.md).

---

# Ketvoice 6.3.0

![Ketvoice](ketvoice-banner.png)

**versionCode 134** — shared playlists, public profile, Air Ket, Canvas cache, release alerts, faster start.

- Shared playlists (`sync/Shared*`, `ui/library/SharedPlaylist*`): Supabase tables `ketvoice_shared_playlists/_members/_tracks/_invites` with member-only RLS, invitation codes through `ketvoice_create_shared_invite` / `ketvoice_join_shared_playlist` (8 wrong codes per 10 minutes), titles ordered by fractional `pos`, offline writes queued; deep links `ketvoice://join/<code>`. Public profile: `ketvoice_profiles.is_public/show_listening/avatar_ref`, `ketvoice_public_playlists`, read-only `ketvoice_public_profile(handle)`, public photo copy in `ketvoice-public-avatars`; deep link `ketvoice://u/<handle>`. Migration `20261118120000_shared_playlists_and_public_profiles.sql`, checked with `supabase/tests/shared_playlists_rls.sql` on a local Postgres 16.
- Air Ket (`airket/*`): SPAKE2 pairing with a six-digit code, AES-256-GCM frames, LAN-only, NSD `_airket._tcp`, resumable per-file SHA-256 transfer of the personal backup and, optionally, downloads, Canvas and separations; foreground service.
- Canvas: a Canvas from a link is kept as `kvlink-<sha>.mp4` (cap 40 MB); next titles' Canvas fetched ahead; a downloaded title takes its album's (`CanvasRepo.albumFile`); Settings: size, limit `canvasCacheMb`, clear; Wi-Fi preload `canvasPreloadWifi`; `CanvasPill`.
- `ReleaseWatch`: twice a day, per followed artist, new discography ids are announced (key `releaseAlerts`).
- Start: `PrefsWarmup` loads the settings files on a thread of their own; late work starts 1.5 s after the first screen on every edition.
- Vice City: `ViceCitySun`, sunset fill of `ScrubBar` and `GlassSlider` (`drawPressTrack(fillBrush)`).

Full notes: [6.3.0](docs/releases/v6.3.0.md).

---

# Ketvoice 6.2.1

![Ketvoice](ketvoice-banner.png)

**versionCode 133** — the Vice City theme gets its sunset.

- `ViceCitySun` (in Home's palms decor): a faint striped sun (orange to pink, slats widening towards the horizon) that breathes with `SeasonPulse` and holds still under reduced motion.
- `drawPressTrack(fillBrush)`: with a seasonal theme the player's `ScrubBar` (unless the caller chose its own colour) and `GlassSlider` fill with the season's gradient and a soft halo under the fill.

Full notes: [6.2.1](docs/releases/v6.2.1.md).

---

# Ketvoice 6.2.0

![Ketvoice](ketvoice-banner.png)

**versionCode 132** — Ketvoice accounts and library sync, the smart library, a livelier player, first-launch welcome and seasonal themes.

- `account/*`: Supabase Auth over REST (email and password without confirmation code, or Discord), session in the Android Keystore with refresh; `ketvoice_profiles` (name, handle, photo in the private `ketvoice-avatars` bucket) and `ketvoice_library` (favourites, playlists, artists, settings, now playing; one JSON document per kind, server-counted revisions, tombstones for deletions), all under owner-only row level security. `ui/account/*`: account sheet, photo crop, avatar on Home, Library, Search and Settings, « Reprendre ici » on Home.
- `smartlib/*`: `NaturalQuery` reads free-text searches in the 11 interface languages into removable chips; `MoodScore` builds mood playlists from Ketmix measurements, styles and words; `Duplicates` merges copies in playlists (downloads deleted only after confirmation); `PlaylistImport` reads public Spotify and Deezer playlists.
- Player: `CoverDrift`, bass-breathing `ArtworkFlow`, Apple Music-style title changes (reduced motion: still; performance mode and Lite: gentler).
- `data/FirstRun` + `ui/onboarding`: welcome for new installs only (language, playlist import, Ketmix, voice).
- `data/Seasons`: Christmas, New Year, Valentine's Day, Spring, Summer, Halloween, generalised from the Vice City edition; image slots `season_<id>_welcome/_header/_emblem` for the owner's pictures.

Full notes: [6.2.0](docs/releases/v6.2.0.md).

---

# Ketvoice 6.1.2

![Ketvoice](ketvoice-banner.png)

**versionCode 131** — the crash at launch of 6.1.0 and 6.1.1 fixed.

- Cause (from the owner's safe-mode report, Honor BRP-NX1M, API 36): `java.lang.VerifyError: Verifier rejected class com.ketvoice.app.automix.Automix` — `Automix.transition` had grown to 287 registers with the Ketmix editor's points, and ART refused the class at `MainActivity.onStart`. JVM/Robolectric tests never run the ART verifier.
- `transition` is split (`analyse`, `startStems`, `joinStems`): 144 registers. `tools/check_dex_registers.py` fails CI when any Ketvoice method in a release APK needs 256 registers or more.

Full notes: [6.1.2](docs/releases/v6.1.2.md).

---

# Ketvoice 6.1.1

![Ketvoice](ketvoice-banner.png)

**versionCode 130** — safe mode after crashes at launch.

- `util/CrashLoop` counts launches that did not last 10 s; after two in a row with a newer crash report, `SafeModeActivity` (plain Android views) opens with the report to share, copy, or retry. `Crash.install` now runs first in `KetvoiceApp.onCreate`, in every process, and reports carry the version and edition.
- `AppLaunchHostTest`, `AppLaunchWithServiceHostTest` (SDK 34–36, real `PlaybackService` bound) and `SafeModeHostTest` start the whole app on the host.

Full notes: [6.1.1](docs/releases/v6.1.1.md).

---

# Ketvoice 6.1.0

![Ketvoice](ketvoice-banner.png)

**versionCode 129** — Ketogether, the Ketmix editor, lock-screen lyrics, weekly discoveries and Replay, redone artist/album pages, playback that survives, Ketvoice Lite and the Vice City edition.

- Ketogether: `together/` (Supabase Realtime public broadcast + presence channel per room, host-relative clock, signed host messages with ECDSA P-256, drift corrected by seeking, rate limits, reconnect), `ui/together/KetogetherUi.kt`, `Player.transportGate`; Ketmix held for guests.
- Ketmix editor: `automix/Waveform.kt` (50 ms columns stored in `filesDir/ketmix-waves`), `MixPoints.kt`/`MixPointStore` (per-title exit/entry, per-pair style/overlap, backed up), `TransitionPlanner.honour()`, `Automix.preview`, `ui/player/KetmixEditor.kt`.
- Lyrics: `player/LiveLyrics.kt` and `LockscreenLyrics.kt` (channel `ketvoice.lyrics`, promoted ongoing on Android 16, key `lockscreenLyrics`); no halo and no line blur (`blurInactiveLyrics` retired), held-note glow only; widgets in the glass look with the sung line.
- Discover: `data/WeeklyDiscoveries.kt` (+ worker), `player/Radio.kt` (no repeats), `data/ListeningMonths.kt` (monthly Replay), richer `WrappedStory` with 1080×1920 share pictures.
- Pages: `ui/sub/ArtistSections.kt`, `ArtistDiscography.kt`, `data/ArtistPages.kt`, album foot and « More by » shelf.
- Background: `stems/StemWork.kt` + `StemWorkService` in process `:stems`; `player/PlaybackRevival.kt` (`ResumeDecision`, `RevivalStore`, dead-man alarm, key `autoResume`); `MemoryRelief` also clears image memory.
- Ketvoice Lite: `lite` flavour (same applicationId, trimmed icon set, WebP resources, compressed dex, `LiteTuning` defaults), `Ketvoice-<v>-Lite.apk`, edition-aware updater.
- Vice City edition: `data/ViceCityEdition.kt` (welcome until 2026-11-20 once per versionCode, theme and icon until 2026-12-10), `theme/ViceCityTheme.kt` (key `viceCityTheme`), `ui/vicecity/ViceCityWelcome.kt`, `.LauncherViceCity` alias; new banner.
- Tab bar dock always on (`collapsingTabBar` retired).

Full notes: [6.1.0](docs/releases/v6.1.0.md).

---

# Ketvoice 6.0.0 Beta 5

![Ketvoice](ketvoice-banner.png)

**versionCode 128** — Android Auto, background playback that holds, iOS 27 sliders, Ketmix's sign under the seek bar.

- Android Auto: `PlaybackService` is a `MediaLibraryService`; `player/AutoLibrary.kt` builds the browse tree (Home mixes and history, Favourites, Playlists, Downloads; offline root), search (playlists, `LibrarySearch`, catalogue sources with a 9 s timeout) and voice requests; car requests are resolved to tracks and handed to `Player` (same sources and fallbacks as the phone). `AutoArtworkProvider` serves signed, scaled covers. Mobile flavour only (`app/src/mobile/AndroidManifest.xml`).
- Background playback: `PlaybackBuffer` (30–180 s ahead, 32 MB cap); `BackgroundThrift` (off screen and on battery: slow Ketmix analysis, no automatic separation); `MemoryRelief` releases the separation models on `onTrimMemory`; journal lines for data waits, data saver and memory warnings; Honor/Huawei *App launch* intents first, with guidance in Settings.
- Ket 27: `ui/common/SliderPress.kt` (press-and-hold swell, reach and rubber band on the seek bar, volume, glass and voice sliders; sliders moved to `ui/player/PlayerSliders.kt`); `RowHighlight`; `EmptyState` with symbols; `CatalogSkeletonList`; `KetmixMixingBadge` between the times; the full-bleed `PlayerHero` removed; word halo at alpha 0.11 and none after a word ends.

Full notes: [6.0.0 Beta 5](docs/releases/v6.0.0-beta.5.md).

---

# Ketvoice 6.0.0 Beta 4

![Ketvoice](ketvoice-banner.png)

**versionCode 127** — Ketmix at AutoMix's level, an instant voice slider, the new glass icon, a finer Ket 27.

- Ketmix: `tempoRange` 4/6/8 % with `TempoReturn` (0.25 % every 500 ms after the blend, pitch kept; the owner lifted the no-speed-change rule); `DeckAutomation.midDb` peaking band at 1 kHz held back on one deck at a time in blends in time (−4 dB, −8 dB on key clashes); crossfades start on phrases and are halved on key clashes.
- Voice level: `VoiceStage` runs from the stream start whenever a model is installed and takes masks up live (no re-seek), survives speed-change flushes, interpolates between mask frames and extends the mask above 11 kHz (also in `Separator.stem`); `VoiceSeparation` starts on the lyrics screen and upgrades to the HQ model; lyrics compensate `VoiceLevel.delayMs`.
- Progressive quality: upgrades wait for the first stream to play and retry; `QualityHandoff.offset` aligns renditions on their loudness envelopes; equal-power crossfade; `progressiveQuality` on by default.
- Icons: owner's four iOS 27 images matted (`tools/icons/`) and laid on the ground each was drawn on (Android fills an adaptive icon's background in black, so a transparent one is not possible), themed layer, picker with previews.
- Ket 27: `glassTintLevel` (0..6, replaces `glassTint`), `glassTilt`, `CoverLook` with WCAG-checked accents; full-bleed phone player (`PlayerHero`); Home mix cards; Library/Downloads glass pills and grids; lyrics mesh and word halo; floating search bubble; cover zoom (`CoverZoom`); `collapsingTabBar` (off by default).

Full notes: [6.0.0 Beta 4](docs/releases/v6.0.0-beta.4.md).

---

# Ketvoice 6.0.0 Beta 3

![Ketvoice](ketvoice-banner.png)

**versionCode 126** — The voice level on every song.

- `VoiceSlider(trackId)` is shown on every title (lyrics screen, controls shown). A touch on a title not separated whole calls `VoiceSeparation.request`: the title is read with `MediaExtractor` from its download or from the address the playback service loaded (`VoiceSeparation.heard`; DASH/HLS excluded) and separated whole with the quick model when present (`StemLibrary.separateWhole`, shared with the library, which waits while it runs). Without a model, a dialog offers the quick model's download and the separation follows it.
- `PlaybackService.takeUpVoice` gives the playing deck its fresh separation and re-seeks to the same position so that `DeckMixProcessor` starts its voice stage; with the voice turned down (`voiceLevel` < 100) each new title is separated as it starts.

Full notes: [6.0.0 Beta 3](docs/releases/v6.0.0-beta.3.md).

---

# Ketvoice 6.0.0 Beta 2

![Ketvoice](ketvoice-banner.png)

**versionCode 125** — The Ketvoice 6 redesign: Ket 27 as the only interface, tablet and large-window layouts, and the voice level of a song.

- Interface: the `ket27` and `lyricsStyle` settings are retired (stored values ignored, never backed up); every classic branch is removed; Material alerts, text buttons, spinners, progress bars and the snackbar are replaced by `AppAlertDialog`, `KetTextButton`, `ActivitySpinner`, `KetProgressBar`, `KetSnackbar`. Cascade is the only lyrics style.
- Menus: `GlassContextMenu` is the menu of a title from a long press and from every "…" button (anchored on the button, centred when opened from nowhere); the full sheet opens from its *More* item.
- Large screens (`ui/adaptive`, `ui/tablet`): navigation follows the window (tab bar, rail, glass sidebar from 1000 dp), docked mini-player, two-pane collection and search pages, `TabletPlayer` (lyrics beside or below the artwork, queue panel, fold-aware), floating sheets, keyboard shortcuts, secondary click, hover, drag to a sidebar playlist. Phones and Quest keep their layouts.
- Voice level: `VoiceStage` (real-time STFT, 4096/1024, constant 4096-frame delay) applies the whole-title separation (`StemStore` `-w.kvs`, made by `StemLibrary` for downloads while charging) with `kept + level × (1 − kept)` per bin; `VoiceSlider` on the lyrics screen (controls shown, not full-screen lyrics); new key `voiceLevel` (0–100). Downloads mark titles separated whole with a music symbol.

Full notes: [6.0.0 Beta 2](docs/releases/v6.0.0-beta.2.md).

---

# Ketvoice 6.0.0 Beta

![Ketvoice](ketvoice-banner.png)

**versionCode 124** — First beta of Ketvoice 6: high-quality voice separation for Ketmix.

- Stems: optional UVR-MDX-NET Voc FT model (LiteRT, float16 weights, 33 MB, `ketmix-stems-v2` prerelease, pinned), run as sherpa-onnx does (`Uvr.kt`, mixed-radix 6144-point FFT); its voice is stored as the existing 4-bit mask (format KVS3, with the model that made it). MUSDB18-HQ: instrumental 15.0 dB / voice 10.2 dB SDR against Spleeter's 12.4 / 6.7.
- `ketmixStemsQuality` (automatic: high quality while charging, upgrading quick separations; always the best; always quick); with the slow model on battery Ketmix prepares one title ahead.

Full notes: [6.0.0 Beta](docs/releases/v6.0.0-beta.md).

---

# Ketvoice 5.11.0

![Ketvoice](ketvoice-banner.png)

**versionCode 123** — Ketmix prepared from the first seconds and smarter; Ket 27 animations, menus, sheets and controls.

- Ketmix: `Automix.prepare` (ending of the current title, intro and ending of the next three, from 4 s in), `StemLibrary` (downloads while charging), `skipMix` (two-deck mix on a manual skip), `VoiceActivity` (sung passages from the separation, format KVS2), `Mashup` (incoming voice over the outgoing instrumental), `SmartQueue` (shuffle picks the best tempo/key match).
- Ket 27: unfolding mini-player with travelling title and transport, symbol effects (morphing play/pause, filling heart and download, pulsing shuffle/repeat), `SpringSheet` detents, `GlassContextMenu` on track rows, stepped glass sliders with haptics, inset pickers, `Ket27PageBar` and large titles on more pages.

Full notes: [5.11.0](docs/releases/v5.11.0.md).

---

# Ketvoice 5.10.0

![Ketvoice](ketvoice-banner.png)

**versionCode 122** — Real voice separation in Ketmix, music that keeps playing behind other apps, lyrics videos with sound.

- Stems: optional Spleeter 2-stems model (LiteRT, float16 weights, 39 MB, `ketmix-stems-v1` prerelease, SHA-256 pinned); each title's intro and ending separated once, stored as 4-bit masks (~1 MB per title), applied to the decks' own sound; decks fade from the title to its instrumental (`DeckControl.stem`).
- Playback: network losses wait for the network and resume the same stream at the same position (`NetworkRecovery`); playback log with Android's exit reasons; manufacturer battery-manager shortcut.
- LyricVideo: Canvas frames decoded in one pass, per-frame allocations removed; optional AAC sound of the chosen lines. Canvas players skip their audio track.

Full notes: [5.10.0](docs/releases/v5.10.0.md).

---

# Ketvoice 5.9.2

![Ketvoice](ketvoice-banner.png)

**versionCode 121** — Lyrics video sharing, a performance mode that keeps the effects, a like that no longer shifts the layout.

- LyricVideo: on-device H.264 MP4 (720×1280, 30 fps) of up to four lines lit in time over the blurred Canvas or the flowing cover; Moments hold four lines.
- Performance mode now keeps glass as blur (no refraction), drops per-line lyric blur, runs the artwork flow at a quarter rate, turns image crossfades off; album hero Canvas stays.
- HeartBurst lays out as a point: liking no longer grows the title row (LikeLayoutHostTest).

Full notes: [5.9.2](docs/releases/v5.9.2.md).

---

# Ketvoice 5.9.1

![Ketvoice](ketvoice-banner.png)

**versionCode 120** — Custom lyrics files, app-wide cover background, truer cover colours, Discord session restore, vocal separation in Ketmix.

- CustomLyrics: file picker in the lyrics sources; LRC (multi-time, enhanced), TTML, SRT, VTT, ASS/SSA (\k), YRC, JSON, TXT; saved per title.
- `coverBackground` setting (off): every page tinted like an album page. CoverColors: hue-clustered cover colour for pages, accents and the player.
- Discord: stored access/refresh tokens restored on start (they were saved but never read back).
- `ketmixStems` (off): real-time vocal cut — incoming instrumental first, outgoing vocals down before the next voice.
- Add an album or a selection (library, queue) to a playlist, without duplicates.

Full notes: [5.9.1](docs/releases/v5.9.1.md).

---

# Ketvoice 5.9.0

![Ketvoice](ketvoice-banner.png)

**versionCode 119** — Wrapped, four widgets, ten-band equalizer, Apple-style lyrics, flowing player colours.

- Ketvoice Wrapped (yearly listening recorded from 5.9.0; all-time fallback), shareable story picture.
- Widgets: large glass player, your week, mix of the moment, mini controls.
- Ten-band EQ via DynamicsProcessing with limiter (Equalizer fallback), draggable curve, new presets.
- Volume normalisation (slow leveler, off by default), Ketmix fade on manual skip, cover-coloured accent option.
- Artwork flow background, word lift and glow in lyrics, bass breathing; Home without the For you card and tiles.
- Startup: lyrics index, launcher icon check and Discord start moved off the first frame.

Full notes: [5.9.0](docs/releases/v5.9.0.md).

---

# Ketvoice 5.8.3

![Ketvoice](ketvoice-banner.png)

**versionCode 118** — Artist radio under the buttons, *Play* on albums, translated For you card.

Full notes: [5.8.3](docs/releases/v5.8.3.md).

---

# Ketvoice 5.8.2

![Ketvoice](ketvoice-banner.png)

**versionCode 117** — Every title plays again; a smoother app.

- Fixed: since 5.8.0 the reverb stage's bulk copy put Media3's shared empty buffer into itself, which threw on
  every title ("Reading error · try again"). New Robolectric test plays a file through the whole audio chain.
- The player state is no longer copied every 3.5 s for the position (it recomposed the app root while playing);
  the session position is saved every 10 s instead. Home's For you card reads the position only when paused.
- Baseline profile covers all of Ketvoice's code. Stats info shows the last playback error code.

Full notes: [5.8.2](docs/releases/v5.8.2.md).

---

# Ketvoice 5.8.1

![Ketvoice](ketvoice-banner.png)

**versionCode 116** — Playback and smoothness restored, new Liquid Glass app icon.

- The R8-optimised release build of 5.8.0 is withdrawn (titles could stop starting and the app could stutter).
- Page card transition reads its animation in the layer only; the lyrics index is not rewritten for lyrics already indexed.
- New Liquid Glass app icon: an iridescent glass "K" on black.

Full notes: [5.8.1](docs/releases/v5.8.1.md).

---

# Ketvoice 5.8.0

![Ketvoice](ketvoice-banner.png)

**versionCode 115** — Faster, music without cuts in the background, search by lyrics, radios, Apple-style library and animations.

- Release built with R8; less work behind other apps (reverb idle copy, slower position polling, Canvas
  downloads and Ketmix body readings only in front); one-time offer to lift Android's battery restrictions.
- Search by lyrics (on the device, and Genius for sentences); radio from a title or an artist;
  favourites downloaded automatically.
- Ket 27 library in the manner of Apple Music; pages grow out of their cover as cards; iOS rubber-band
  lists; heart burst; living artwork; Liquid Glass app icon; Ket 27 symbols.

Full notes: [5.8.0](docs/releases/v5.8.0.md).

---

# Ketvoice 5.7.0

![Ketvoice](ketvoice-banner.png)

**versionCode 114** — Ketmix without speed effects and with a build-up transition; block or keep artists; translated biographies; the tab bar lens.

- Ketmix never changes a title's speed while it is heard; titles are played in time within 2/3/4 % only,
  others leave on the new build-up transition, an echo or filters.
- Search: a long press on an artist's circle blocks, unblocks or keeps the artist as a favourite; blocked
  artists are left out of what Ketvoice proposes.
- Artist biographies are translated into the app's language; the For you card rotates between releases and mixes.
- Holding or sliding the tab bar's selection turns it into a clear, magnifying lens.

Full notes: [5.7.0](docs/releases/v5.7.0.md).

---

# Ketvoice 5.6.0

![Ketvoice](ketvoice-banner.png)

**versionCode 113** — Ket 27 by default with the glass of iOS 27; steadier playback behind other apps; cleaner Ketmix blends.

- Ket 27 (Alpha) is on by default and uses the regular glass of iOS 27 (70 % surface, soft shadow,
  Clear ↔ Tinted from 55 % to 85 %); settings rows darken under a touch.
- Half a second of audio is buffered ahead and Ketmix reads titles more slowly while Ketvoice is
  not on screen, against gaps in the music.
- Ketmix: filters, echo and reverb carry on through tempo nudges; fewer and calmer tempo changes;
  the journal shows beat sync and speed changes.

Full notes: [5.6.0](docs/releases/v5.6.0.md).

---

# Ketvoice 5.5.0

![Ketvoice](ketvoice-banner.png)

**versionCode 112** — Ketmix in time far more often; Ket 27 (Alpha), the interface in the manner of iOS 27.

- Ketmix reads the tempo of each title where its drums play and reads intros and endings against it;
  titles are played in time within 6/14/18 % of each other instead of 5/8/12 %.
- Ket 27 (Alpha), in Settings → Appearance: iOS system colours, Inter with the iOS text styles, settings
  as inset grouped lists with coloured tiles, the iOS 27 switch, a 62 pt tab bar tinted by the accent,
  Liquid 27 glass everywhere.

Full notes: [5.5.0](docs/releases/v5.5.0.md).

---

# Ketvoice 5.4.1

![Ketvoice](ketvoice-banner.png)

**versionCode 111** — Ketmix transitions that keep their effects; last notes that ring on; intros under the last words.

- Ketmix waits for both players' audio processing before choosing a transition, so filtered and echoed
  transitions are played instead of a plain fade.
- A last note at full level rings on in an echo past the end of the title while the next one starts.
- A next title that does not sing yet rises under the last sung words (up to 6 s balanced, 10 s DJ),
  the outgoing voice stepping back; the mix journal shows more of each transition.

Full notes: [5.4.1](docs/releases/v5.4.1.md).

---

# Ketvoice 5.4.0

![Ketvoice](ketvoice-banner.png)

**versionCode 110** — Longer Ketmix blends under the last sung bars; accent colour everywhere; recent titles as covers.

- Ketmix: blends up to 16 bars, tempo matching up to 5/8/12 %, blends starting under the last sung bars
  with the outgoing voice stepping back, and a copyable mix journal.
- The chosen accent colour applies everywhere except album pages, artist pages and the player;
  album pages are tinted by the colour their artwork is mostly made of.
- Home: recent titles as a shelf (or grid) of large covers.

Full notes: [5.4.0](docs/releases/v5.4.0.md).

---

# Ketvoice 5.3.0

![Ketvoice](ketvoice-banner.png)

**versionCode 109** — Ketmix on the phrase and on repeat; Liquid Glass that forms and settles; queue selection.

- Ketmix: mixes start on eight-bar phrases, better bar detection, highs-mids-bass blends, titles on
  repeat mixed into themselves, a transition style setting, an animated mix strip, an optional
  vibration on the bass swap.
- Liquid Glass: squircle bezel (Liquid 27), Schlick Fresnel, materialising glass, calmer springs, lens switches,
  readable scroll edges in every style.
- Queue multi-select (play next, remove), shuffle with fewer repeats, night-dimmed coloured pages.

Full notes: [5.3.0](docs/releases/v5.3.0.md).

---

# Ketvoice 5.2.0

![Ketvoice](ketvoice-banner.png)

**versionCode 108** — Ketmix (beta): more than a fade; a new dark theme; a new Home.

- Ketmix, the new name of Automix: six kinds of transition chosen from the two titles (matched to
  the tempo with tempos that meet, echo out, filter crossing, dissolve, cut on the beat, plain
  crossfade as a last resort), key detection, tighter tempo limits and less of an ending left
  out, three analysis settings, the switch in the bar under the transport.
- A new dark theme (neutral near-black, lit pages) and a rebuilt Home (lead card, tiles, week bars).
- Smoother intro dots in lyrics; album pages take their colour from the Canvas they play.

Full notes: [5.2.0](docs/releases/v5.2.0.md).

---

# Ketvoice 5.1.0

![Ketvoice](ketvoice-banner.png)

**versionCode 107** — Automix that explains itself; signatures that show up.

- Automix mixes albums too (setting, on by default; gapless albums stay untouched), shows a status
  line in the queue and in Settings, prepares the next title 150 s ahead and retries, mixes longer.
- Artist signatures no longer depend on the busy artwork service.

Full notes: [5.1.0](docs/releases/v5.1.0.md).

---

# Ketvoice 5.0.0

![Ketvoice](ketvoice-banner.png)

**versionCode 106** — Automix: titles flow into each other like a mix.

- Automix (off by default; queue button or Settings): tempo-matched, beat-aligned mixes
  with a bass hand-over, a crossfade when tempos differ, silence removal after a hard
  ending; lyrics-aware; albums in order, repeat, sleep timer and bit-perfect never mixed.
- Three intensities, a "Mixing" badge, covers dissolving into each other, a second
  progress line, the same in the mini-player.
- Album and artist pages: actions in one glass capsule.

Full notes: [5.0.0](docs/releases/v5.0.0.md).

---

# Ketvoice 4.17.0

![Ketvoice](ketvoice-banner.png)

**versionCode 105** — smoother lyrics and glass that catches the light.

- Lyrics: Share sits beside Cancel again; sung lines stay white; Cascade glides smoothly.
- Navigation: the floating bar returns on a small scroll up; pages push like on iOS.
- Look: a bevel highlight on Liquid Glass, a tighter album page, more artist signatures.

Full notes: [4.17.0](docs/releases/v4.17.0.md).

---

# Ketvoice 4.16.1

![Ketvoice](ketvoice-banner.png)

**versionCode 104** — curated artwork everywhere.

- Refreshed covers, Canvas and artist photos chosen by Ketvoice appear across the app:
  player, mini-player, lists, search, Home, album and artist pages, lock screen, widget.
- An album's artwork applies to its titles; artist pages fit a wide photo to its shape.

Full notes: [4.16.1](docs/releases/v4.16.1.md).

---

# Ketvoice 4.16.0

![Ketvoice](ketvoice-banner.png)

**versionCode 103** — a more personal Home, a more polished player, moments to share.

- Home: a For you card (resume, new release, mix of the hour), new releases from your
  artists, your week, and shelves that move with what you open.
- Player: the artwork steps back when paused, the progress bar swells under the finger,
  a calmer background; the mini-player plays the Canvas and shows a fine progress line.
- Lyrics: a Cascade style in Appearance, and Share a moment as a story card.
- Library: playlists and albums as a grid of large covers.

Full notes: [4.16.0](docs/releases/v4.16.0.md).

---

# Ketvoice 4.15.0

![Ketvoice](ketvoice-banner.png)

**versionCode 102** — the album page takes the effect of the artist page.

- The cover or its Canvas fills the top of the album page and dissolves into a colour
  taken from the cover, which tints the whole page.
- The artwork is sized to the window (at most 46 % of its height and 420 dp) and is
  shown whole, over a soft copy of itself, where filling the width would cut it.

Full notes: [4.15.0](docs/releases/v4.15.0.md).

---

# Ketvoice 4.14.2

![Ketvoice](ketvoice-banner.png)

**versionCode 101** — the droplet morph of the navigation bar is 17 % slower than its
original speed, instead of 3 %.

Full notes: [4.14.2](docs/releases/v4.14.2.md).

---

# Ketvoice 4.14.1

![Ketvoice](ketvoice-banner.png)

**versionCode 100** — the artist page takes its colour from the portrait.

- The portrait fills the top of the artist page again and dissolves, without a line,
  into a colour taken from the photo.
- That colour tints the whole page: a pastel on a light theme, a deep shade on a dark one.

Full notes: [4.14.1](docs/releases/v4.14.1.md).

---

# Ketvoice 4.14.0

![Ketvoice](ketvoice-banner.png)

**versionCode 99** — one layout for album, playlist, artist and Downloads.

- A shared header: the cover's colours blurred edge to edge, the artwork floating
  over them, the name centred, Play and Shuffle as a pair.
- A glass title bar with a Play button once the header has scrolled away.
- Liquid 27: Play and Shuffle are one body of glass that bridges when pressed; menus
  grow out of the place that opened them; selectors use a sliding lens of glass.
- The menu of a title starts with five round glass buttons.
- One row for a title in catalogue, artist, Local music and Replay lists.

Full notes: [4.14.0](docs/releases/v4.14.0.md).

---

# Ketvoice 4.13.0

![Ketvoice](ketvoice-banner.png)

**versionCode 98** — one set of glass buttons, cover colours behind collection headers.

- A prominent, accent-tinted glass button for the main action and plain glass
  capsules for the others, shared by album, playlist, artist, Downloads, Library,
  Local music and Replay.
- In Liquid 27, glass buttons swell under the finger and settle with a small bounce.
- Playlist, artist and Downloads headers glow with the colours of their cover;
  Downloads shows the latest covers in its summary card.
- The droplet morph of the navigation bar is 3 % slower.

Full notes: [4.13.0](docs/releases/v4.13.0.md).

---

# Ketvoice 4.12.0

![Ketvoice](ketvoice-banner.png)

**versionCode 97** — skip from anywhere, a visible and editable queue, four screens brought up to date.

- Next and Previous now work from the notification, the lock screen and headset or
  Bluetooth buttons. Shuffle reorders the visible queue and is undone when turned
  off; Previous returns to the title you came from; repeat cycles off, queue, title.
- The next title is resolved before the current one ends. Sleep timer on phones,
  with a fade-out or "at the end of the song". Swipe the mini-player or the artwork
  to change title.
- Queue: live reordering, swipe to remove with Undo, Clear and Save as playlist.
- The same swipe and long-press gestures in every list of titles.
- Replay shows real listening time; Local music asks for its permission and gains
  search, sorting and Albums/Artists views; Search gains an Albums shelf and a real
  Library scope; Explorer shows the catalogue chart.

Full notes: [4.12.0](docs/releases/v4.12.0.md).

---

# Ketvoice 4.11.0

![Ketvoice](ketvoice-banner.png)

**versionCode 96** — a more personal Home, mixes from your listening, redesigned Downloads.

- Home greets by time of day, offers shortcuts to what exists on the device, and
  varies the order of its secondary shelves at each launch.
- After ten plays, up to four personal mixes: artist mix, "in the style of", on
  repeat and rediscover. A mix can be saved as a playlist.
- Downloads: summary card with play time, lossless share and free space; Titles,
  Albums and Artists views; sorting; format badges.

Full notes: [4.11.0](docs/releases/v4.11.0.md).

---

# Ketvoice 4.10.0

![Ketvoice](ketvoice-banner.png)

**versionCode 95** — a design release: motion, Liquid Glass, formats and accessibility.

- The player artwork grows out of the mini-player cover; predictive back on Android
  14+; a compact glass title on scrolled tabs; light haptics; a bundled startup profile.
- Liquid 27 droplet fusion: Home, the mini-player and Search merge and split like
  droplets while the chrome morphs. Droplet tab lens, cover-tinted glass and an
  animated mesh behind the player.
- A home-screen widget, a tabletop layout for flip-style phones, and the enriched
  cover on the notification and lock screen.
- Headings, play state and track-change announcements for screen readers; reduced
  motion follows the system transition scale; navigation survives process death.

Full notes: [4.10.0](docs/releases/v4.10.0.md).

---

# Ketvoice 4.9.0

![Ketvoice](ketvoice-banner.png)

**versionCode 94** — a fourth glass style, Liquid 27, plus Discord, privacy and
reliability fixes.

- Add **Liquid 27**, a fourth glass style next to Clair, Dépoli and Opaque. It is
  modelled on the publicly described 2026 Liquid Glass refinements: continuous
  corners, uniform edge refraction, defined borders instead of drop shadows, a
  lighter dark material, a Clear ↔ Tinted slider that starts at its midpoint,
  highlights that follow device tilt, a touch glow and a scroll-edge fade under
  the bars. The other three styles are unchanged.
- Fix Discord callbacks: the Social SDK only delivers results from
  `RunCallbacks()`, which was never called. Presence results and the linked
  account's Ready state can now arrive.
- Fix **Ignorer les silences**, which could never be turned on.
- Make track details on the website's "Listening now" opt-in; the listener count
  stays anonymous.
- Bound the lyrics, artwork and Canvas caches without touching files that
  downloads use.
- Rate-limit and sanitize the listener heartbeat on the server.
- Translate the 4.8.1–4.8.2 Discord and language strings, so the UI text audit
  passes again.

Full notes: [4.9.0](docs/releases/v4.9.0.md).

---

# Ketvoice 4.8.4

![Ketvoice](ketvoice-banner.png)

**versionCode 93** — the collapsed Home / mini-player / Search row now triggers on
320–379 dp phones too.

- Lower the collapse threshold from 380 dp to 320 dp. Many phones report a 360 dp
  logical width, so the previous threshold silently disabled the whole animation.
- Keep the 48 dp transport targets, the 52 dp Home/Search circles and the shrinking
  mini-player at 320 dp.
- Update the host regression test to assert the new row at 320 dp instead of the
  removed three tabs.

Full notes and validation limits: [4.8.4](docs/releases/v4.8.4.md).

---

# Ketvoice 4.8.3

![Ketvoice](ketvoice-banner.png)

**versionCode 92** — while you scroll, the floating chrome becomes one glass row:
Home on the left, a smaller mini-player in the middle and Search on the right.

- Collapse the three-tab capsule into a single liquid-glass Home button when a page is scrolled.
- Shrink the mini-player continuously between Home and Search: smaller cover, lower pill, faded text, 48 dp transport targets.
- Keep the same spring for the capsule, the mini-player and the Search button so the row morphs as one piece.
- Scroll back up to restore the full mini-player above the navigation and the three tabs.
- Keep the Discord RPC presence and the 4.8.1 device-language behaviour.

Full notes and validation limits: [4.8.3](docs/releases/v4.8.3.md).

---

# Ketvoice 4.8.2

![Ketvoice](ketvoice-banner.png)

**versionCode 91** — Discord presence now goes through the installed Discord app
over RPC, without account linking or a gateway connection.

- Publish Discord presence without OAuth: the SDK talks to the installed, signed-in Discord app (Android RPC) and no longer waits for a `Ready` gateway state.
- Add **Afficher via l'app Discord** as the primary Discord action, with a visible success/error result per presence update.
- Keep account linking as an advanced option; the gateway loop is bounded and never restarts on foreground.
- Show the track title in the Discord status line and keep the **Listening** activity type.
- Report the presence mode, last result and SDK log in the diagnostic panel.

Full notes and validation limits: [4.8.2](docs/releases/v4.8.2.md).

---

# Ketvoice 4.8.1

![Ketvoice](ketvoice-banner.png)

**versionCode 90** — the device language is now the default, and Discord linking
no longer stays stuck on "waiting for the Discord app".

- Start in the phone's language (Spanish, German, Portuguese, …) instead of French, with an explicit **System language** entry in Settings.
- Clear a previously pinned per-app locale so a later system-language change is picked up.
- End the endless Discord "waiting" state with a bounded connection deadline and visible account-vs-presence status.
- Retry the Discord presence connection with backoff and re-send the current track once the SDK is ready.
- Recover the OAuth code from the WebView page itself and publish presence as **Listening** rather than Playing.
- Initialise the Discord SDK context when the callback activity cold-starts the app.

Full notes and validation limits: [4.8.1](docs/releases/v4.8.1.md).

---

# Ketvoice 4.8.0

![Ketvoice](ketvoice-banner.png)

**versionCode 89** — playback recovery, progressive search and personal-library improvements.

- Respect ordered or progressive source selection, with safer lossless cache fallback.
- Restore the queue and last saved position without automatically starting playback.
- Show search results as providers answer, group confident matches and offer source choices.
- Add portable personal backups with a preview and non-destructive playlist merging.
- Add per-track lyric timing adjustments and a more locally personalized Home.
- Support restricted, explicitly authorized unprotected FLAC DASH downloads in a separate cache.
- Keep Discord support in the signed release APKs; device and live-session validation remain open.

Full notes and validation limits: [4.8.0](docs/releases/v4.8.0.md).

---

# Ketvoice 4.7.0

![Ketvoice](ketvoice-banner.png)

**versionCode 88** — optical glass with pressure-responsive refraction and a
visible redesign of Library, Downloads, Album and Settings.

- Replace symmetric bright rims with directional reflection and quieter depth shading.
- Animate glass pressure without shrinking glyphs or accessible touch targets.
- Refract independent slider tracks instead of rendering static painted thumbs.
- Use cohesive outline icons, editorial titles, clean filters and matte grouped content.
- Preserve offline progress, confirmations, playlist actions and restored navigation/player.
- Integrate album artwork with an image-derived surface and genuinely captured floating controls.

Full notes and validation boundaries: [4.7.0](docs/releases/v4.7.0.md).

---

# Ketvoice 4.6.1

![Ketvoice](ketvoice-banner.png)

**versionCode 87** — lossless/Tidal playback repair, with the addon as the primary
high-quality source and a periodic keep-alive for the free hosted services.

- Parse `dash`, `flac`, `aac` and direct stream answers, including MIME type and codec.
- Build a DASH `MediaSource` for extensionless manifests instead of a progressive one.
- Keep the requested play state during resolution; no full re-resolution on each Play tap.
- Surface real errors instead of an endless spinner, and recover an expired signed URL once.
- Fall back across providers, then across the addon's own Qobuz and Tidal results.
- Add the Lossless addon once, first in the playback order, without overriding your choices.
- Ping the addon while the process is alive, about every ten minutes; Android can defer this.

Full notes and validation limits: [4.6.1](docs/releases/v4.6.1.md).

---

# Ketvoice 4.6.0

![Ketvoice](ketvoice-banner.png)

**versionCode 86** — native glass refinements, adaptive settings and a
continuous saved glass-tint control.

- Centralize visual tokens and explicitly select AGSL, blur or static fallbacks.
- Keep the separate floating navigation and cover-first player, with improved
  compact sizing, touch targets and reduced-motion behaviour.
- Add live, persistent tint adjustment and adaptive settings/playlist sheets.
- Refine album/playlist header collapse, artist controls and Explorer contrast.
- Preserve clean synchronized lyrics, Canvas, audio functionality and Quest layout.

Full notes and validation limits: [4.6.0](docs/releases/v4.6.0.md).

---

# Ketvoice 4.5.1

![Ketvoice](ketvoice-banner.png)

**versionCode 85** — restore the previous navigation and music-player layout,
without reverting the clean lyrics or the personal-library improvements.

- Bring back separate mini-player, tab capsule and search surfaces.
- Return the phone title/actions below the artwork and remove the added top page
  selector on phone, tablet and Quest.
- Keep clean synchronized lyrics, pins, local albums, offline selection/filters
  and confirmed deletion from 4.5.0.

Full notes and validation limits: [4.5.1](docs/releases/v4.5.1.md).

---

# Ketvoice 4.5.0

![Ketvoice](ketvoice-banner.png)

**versionCode 84** — unified glass navigation, clean karaoke and a library built
around local listening, pinned playlists and offline albums.

- Remove scaled and diffused word copies without removing karaoke progression.
- Combine floating navigation/mini-player/search into one material enclosure;
  pressure changes the reflection and surface, not the glyphs.
- Anchor the phone player's identity and transport across explicit page choices.
- Add compact scrolled playback actions and a shorter, clearer artist identity.
- Add persistent playlist pins, local album grouping, library views and selection.
- Add offline state filters, album presentation and confirmed batch deletion.
- Lead Home with local Resume listening and pins, without extra feed requests.

Full notes and honest host/device validation limits: [4.5.0](docs/releases/v4.5.0.md).

---

# Ketvoice 4.4.0

![Ketvoice](ketvoice-banner.png)

**versionCode 83** — refined optical controls, a rebuilt offline library, and a
more compact, adaptive album page.

- Glass has less blur and edge distortion, one shared navigation-lens material,
  and a quieter centre without painted diagonal streaks. Static clipping and
  reduced-motion colour transitions are corrected.
- Downloads separate transfers from saved files, add local title/artist/album
  search and honest byte progress, and confirm deletion instead of presenting a
  false Undo that re-downloads the track.
- Album artwork stays full-bleed on phones. The identity is left-aligned, actions
  adapt to large fonts, landscape places identity beside artwork, and numbered
  rows no longer repeat the cover. Canvas and queue gestures remain.
- Software-host validation is explicitly distinguished from real GPU/device,
  Quest, DAC, FPS and live-provider checks.

Full notes and validation limits: [4.4.0](docs/releases/v4.4.0.md).

---

# Ketvoice 4.3.1

![Ketvoice](ketvoice-banner.png)

**versionCode 82** — the 116 user-facing strings that only existed in French are
now translated into all ten other languages, CI refuses to let that gap reopen,
and paused downloads survive an app restart.

- Every non-French table now covers playback and source ordering, the Bit-Perfect
  status panel, download queue and Wi-Fi states, updater errors, Discord
  diagnostics, the listening recap and share actions. Placeholders, brand names
  and units are preserved, so composed labels still reorder arguments per language.
- `tools/audit_ui_texts.py --check` fails when a user-facing string has no bundled
  translation and when an allowlist entry goes stale; CI runs it on every push.
- The machine-translation helper now drafts into a proposal file instead of
  overwriting the catalog, because its output is not shippable.
- Download queue entries are persisted: paused stays paused after a process kill,
  pending restarts by itself, failed stays visible without holding a busy slot.
- Every APK is signed with the same pinned certificate, so updates install over
  the previous version. The keystore and each built APK are verified in CI.

Full notes and explicit host/hardware validation limits: [4.3.1](docs/releases/v4.3.1.md).

---

# Ketvoice 4.3.0

![Ketvoice](ketvoice-banner.png)

**versionCode 81** — coordinated navigation, artist/album reveals and a guarded
native Bit-Perfect PCM path on compatible Android 14+ USB outputs.

- Sliding glass navigation lens, elastic icon/label feedback and directional tab
  motion, with saved tab/scroll state.
- Detail pages open from the selected card and close toward its saved origin.
  Outgoing pages keep their own immutable request during rapid navigation.
- Artist header parallax, continuous top-bar collapse, staggered identity/actions
  and a loading skeleton. Album artwork reveals progressively and dissolves into
  its local colour without painting a broad white veil over the image.
- Optional Bit-Perfect setting uses Android's advertised BIT_PERFECT mixer contract
  and a native PCM AudioTrack. Active requires live route/format/ownership checks;
  refusals, unsupported outputs and incompatible formats fall back transparently.
- App DSP, EQ, skip-silence and progressive handoff are bypassed while requested;
  preferences are retained for standard mode. Optional player Stats info stays off
  by default. No proprietary USB driver or unverifiable hardware guarantee.

Full notes and explicit host/hardware validation limits: [4.3.0](docs/releases/v4.3.0.md).

---

# Ketvoice 4.2.0

![Ketvoice](ketvoice-banner.png)

**versionCode 80** — album artwork, a single reversible player reveal, glass
appearance preview, optional Stats info, safe queue shortcuts and download controls.

- Album images start at the top edge with no large white overlay; colour is local
  to the album being viewed.
- Replaced competing player/cover animations with a single rounded-surface reveal.
- Clear/Frosted/Opaque preview, restrained optical controls, matte track menus,
  animated play/pause glyph and a resizing mini-player.
- Optional decoder-derived Stats info in the player only, off by default.
- Album/search swipe queue actions with occurrence-specific Undo.
- In-process pause/resume/cancel/retry, validated HTTP Range recovery, optional
  Wi-Fi gate and real remaining-byte information for downloads.

Full notes and explicit validation limits: [4.2.0](docs/releases/v4.2.0.md).

---

# Ketvoice 4.1.0

![Ketvoice](ketvoice-banner.png)

**versionCode 79** — instant playback: every source races for the first stream, better quality lands in the background.

- Playback no longer waits for the slowest provider: all enabled sources are queried in parallel and the first usable stream starts the music.
- The lossless/high-bitrate stream keeps loading behind the current one and swaps seamlessly at the same position, without an audible gap.
- The next two queue tracks are resolved ahead of time, so skipping and auto-advance feel immediate.
- The "qualité progressive" switch now only decides whether a better stream is fetched — it no longer blocks fast starts or the seamless upgrade.
- Fixed the album page not compiling: the full-bleed hero used `clipToBounds()` without importing it.

Full notes and limits: [4.1.0](docs/releases/v4.1.0.md).

---

# Ketvoice 4.0.1

![Ketvoice](ketvoice-banner.png)

**versionCode 78** — full-bleed album page with Canvas/cover, build and signing fixes.

- Album artwork now runs edge to edge with the album colour over the top of the page.
- New Apparence switch: album Canvas on pages, independent from the player Canvas.
- Android 13 themed (monochrome) launcher icon and an animated launch splash.
- Fixed duplicated Gradle imports that broke `main`, and the CI cannot publish debug-key APKs any more.
- Discord panel gains a copyable diagnostic journal.

Full notes and limits: [4.0.1](docs/releases/v4.0.1.md).

---

# Ketvoice 3.19.0

![Ketvoice](ketvoice-banner.png)

**versionCode 77** — dynamic colour from cover art, a Wrapped-style recap screen, and CI that builds the APKs.

- **Dynamic colour:** the theme accent now follows the now-playing artwork (cached per URL, opt-in via Album colours, clamped for legibility).
- **Ton récap:** new Wrapped-style stats screen — total time, plays, artists, most-replayed and top tracks, all offline from existing history, with a shareable recap line.
- **CI:** GitHub Actions workflow compiles, tests, lints, uploads APKs as artifacts and attaches them to the GitHub Release on `v*` tags.
- Additive changes; Gradle build and device checks are delegated to CI.

Full notes: [3.19.0](docs/releases/v3.19.0.md).

---

# Ketvoice 3.18.0

![Ketvoice](ketvoice-banner.png)

**versionCode 76** — a design-system pass that unifies shadows, motion, contrast and responsive layout into shared primitives.

- **Shadows & reflections:** one elevation ladder (card 1 dp → button 4 dp → pill 8 dp → sheet 16 dp) drives every glass shadow; static glass now casts a real drop shadow instead of reading as a flat rectangle; the shader gains a diagonal specular band and a bottom-right bounce halo (Android 13+).
- **Coherent animations:** a single `Motion` object now holds the duration scale, easing curves, springs and elevation steps; the legacy API is preserved so nothing breaks; reduce-motion still collapses everything to instant.
- **Automatic contrast:** new helpers pick ink and tint from the real backdrop luminance (with a WCAG 4.5:1 check), keeping labels readable over album art in light and dark.
- **Screen adaptation:** Material3 window tiers, up to three track columns, 720 dp reading cap and 16→32 dp gutters; short landscape windows get a tighter bottom pad.
- **Dark & light:** per-theme elevation alpha and role-aware static glass.
- No functional change to playback, sources, Discord or publications.

Committed as a design-system change without an APK; compilation and on-device checks remain required before publication.

Full notes: [3.18.0](docs/releases/v3.18.0.md).

---

# Ketvoice 3.17.1

![Ketvoice](ketvoice-banner.png)

**versionCode 75** — collection header scrolls like the playlist and album pages.

- Liked songs, recently played and all tracks now scroll their header away instead of pinning it.
- No functional change from 3.17.0 otherwise: artist hero, refresh/share, Discord linking, progressive playback.

Full notes: [3.17.1](docs/releases/v3.17.1.md).

---

# Ketvoice 3.17.0

![Ketvoice](ketvoice-banner.png)

**versionCode 74** — artist hero without edges, real refresh/share, unified collections and working Discord link.

- Artist hero: full-bleed smart scale, no side seams; refresh clears the cached page/artwork; new share button with a real channel link.
- Playlists, albums and collections share real links.
- Liked songs, recent, all tracks and downloads use the playlist/album presentation.
- Discord: persisted PKCE state + direct token exchange, so returning from authorization actually links the account.
- Progressive playback keeps its position-preserving quality fallback and two-track prefetch.

Full notes and limits: [3.17.0](docs/releases/v3.17.0.md).

---

# Ketvoice 3.16.1

![Ketvoice](ketvoice-banner.png)

**versionCode 73** — streaming and downloads restored.

- No pre-request consumes one-shot stream URLs any more; the provider URL goes straight to the player.
- Downloads try every enabled source in cascade, best quality first, then the legacy custom-source path.
- Resolution has a 25 s budget with explicit failure labels instead of infinite loading.
- All-disabled sources now say so; custom sources are initialised with the player.
- Verification is advisory: only 401/404/410/451 or an HTML/JSON body count as unavailable.

Full notes: [3.16.1](docs/releases/v3.16.1.md).

---

# Ketvoice 3.16.0

![Ketvoice](ketvoice-banner.png)

**versionCode 72** — scrolling playlist header, WebView Discord, artist disk cache, real download quality.

- Playlist cover/title scroll away like the album page, with no pinned header and no blank block.
- Discord connection is the in-app WebView only; the broken native path is removed.
- Artist pages, photos, backgrounds and signatures are cached on disk (24 h) and load instantly.
- Downloads keep the best real quality: FLAC, best compressed stream, or fastest, chosen by detected audio.
- Progressive switch has a restart fallback; the next two tracks are prefetched after playback starts.
- Playback and download now have separate source order and enable/disable lists.

Full notes and limits: [3.16.0](docs/releases/v3.16.0.md).

---

# Ketvoice 3.15.0

![Ketvoice](ketvoice-banner.png)

**versionCode 71** — Apple Music sustained glow and a real source-ordered stream engine.

- Glow threshold 820 ms of sung audio, two-scale glyph blur, 300 ms in, breathing, 375 ms out.
- One persisted source priority for streaming and downloads; first verified audio wins.
- Progressive mode starts the first stream immediately and upgrades without interruption.
- Every stream and download URL is probed for real audio bytes; cancelled sources stop.
- Lossless provider still fails upstream and is skipped; French default and 11 languages kept.

Full notes and limits: [3.15.0](docs/releases/v3.15.0.md).

---

# Ketvoice 3.14.1

![Ketvoice](ketvoice-banner.png)

**versionCode 70** — fixed playlist opacity and a connected Studio control dashboard.

- Keep the playlist header opaque, adapt it to short landscape windows and fix drag indices.
- Add real track/album/artist catalogue search, browsable album/artist tracks and clear owner-editing permissions.
- Categorize 30 integrations; wire ten public adapter bases and two app streaming switches to validated, cached remote configuration.
- Add overview metrics, draft/conflict handling and reliable remembered-session refresh.
- Preserve the 870 ms glow, French default, 11 interface languages and landscape 2D Quest presentation.

Full notes and validation limits: [3.14.1](docs/releases/v3.14.1.md).

---

# Ketvoice 3.14.0

![Ketvoice](ketvoice-banner.png)

**versionCode 69** — dashboard Studio, source priority, fixed playlist header.

- Glow threshold 870 ms; soft halo and 1.07× growth unchanged.
- Playlist artwork stays fixed; the bottom bar condenses after 24 px of scroll.
- Drag-handle priority for custom sources (app) and Studio sources (saved order).
- Ketvoice Studio dashboard: sidebar, account, pink/black theme, remembered PC
  session.
- Discord account link is directly visible and opens the WebView OAuth.

Full notes: [3.14.0](docs/releases/v3.14.0.md).

---

# Ketvoice 3.13.9

![Ketvoice](ketvoice-banner.png)

**versionCode 68** — HD artist artwork, authentic signatures and a softer glow.

- Artist pages resolve HD Fanart portraits/backgrounds by MusicBrainz identity and
  use Wikidata signatures when they exist; long gradient, crossfade, no invention.
- Lyric glow only above 1.05 s, with a diffuse halo, inner light, 1.07× growth and
  a luminous sweep; no contour.
- Discord OAuth now uses the HTTPS bridge URL
  `https://ketvoice.netlify.app/discord.html`, which must be registered in the portal.

Full notes: [3.13.9](docs/releases/v3.13.9.md).

---

# Ketvoice 3.13.8

![Ketvoice](ketvoice-banner.png)

**versionCode 67** — album management in the Studio and artist-page fixes.

- Studio → Albums: search albums, set a Canvas for the whole album or one track,
  and add/remove tracks. The app reads the same `canvasURL`.
- Artist pages: Listen/Écouter, Show less/Afficher moins, translated EP and
  Compilation; artist-logo rate limiting fixed.
- Discord opens through several routes and only reports failure if all fail.
- Removed the oversized first-letter artwork fallback.

Full notes: [3.13.8](docs/releases/v3.13.8.md).

---

# Ketvoice 3.13.7

![Ketvoice](ketvoice-banner.png)

**versionCode 66** — held-note-only glow and a Discord button that opens Discord.

- Remove the permanent lyric halo: only notes held 650 ms or longer bloom, in
  line, word and syllable modes alike, with intensity following the note length.
- **Activer via l'app Discord** opens the Discord app, waits, then connects; a
  missing app is reported explicitly and the manifest queries `com.discord`.
- Show the OAuth2 redirect to register in the advanced Discord section (it was
  missing from the Developer Portal, which blocked the account-link flow).

Full notes: [3.13.7](docs/releases/v3.13.7.md).

---

# Ketvoice 3.13.6

![Ketvoice](ketvoice-banner.png)

**versionCode 65** — Discord in Settings, aligned lyric glow and album Canvas.

- Move Discord to Settings → Compte, add an optional OAuth2 token connection and
  surface the SDK log line when a connect attempt fails.
- Restore the soft active-line halo and keep the bloom + contour for held notes
  only; align word masks against the rendered line for foreign scripts.
- Blurred ambience for artist heroes, no letterbox bands, ringed avatars.
- Pace MusicBrainz at 1 request/second so artist logos stop returning 429.
- Studio: album Canvas (MP4) upload applied to every track of the album, played
  by the app through `canvas_path` / `canvasURL`.

Full notes: [3.13.6](docs/releases/v3.13.6.md).

---

# Ketvoice 3.13.5

![Ketvoice](ketvoice-banner.png)

**versionCode 64** — word-level glow, stable backs and honest audio quality.

- Restrict the lyric glow to sustained notes with a thin sung-edge contour; no
  permanent halo on ordinary lines.
- Keep a backing vocal on screen for its real duration (parent-line window when the
  provider has no word timings) and only reserve the row 200 ms before it.
- Ease the translated-line reveal with a per-glyph opacity ramp.
- Keep title, artist and duration visible in fullscreen; tapping the artwork
  returns to the normal player.
- Prefer YouTube's adaptive audio over the broker's 128 kb/s MP3 for High/Lossless
  and display the real codec/bitrate; downloads do the same.
- Discord presence keeps the "Open the Discord app" shortcut; still unverified.

Full notes: [3.13.5](docs/releases/v3.13.5.md).

---

# Ketvoice 3.13.4

![Ketvoice](ketvoice-banner.png)

**versionCode 63** — Discord presence without OAuth, fluid lyric overlaps and a glowing equalizer.

- Publish the listening activity through the installed Discord app, without a
  consent page or redirect, so the connection loop cannot recur. Optional account
  linking stays available.
- Show a backing-vocal row only 200 ms before it starts; let unfinished lines and
  long backs finish while the next line continues.
- Rework the karaoke glow and add a glowing response curve to the equalizer.
- Device validation of the Discord presence and lyric motion is still not claimed.

Full notes: [3.13.4](docs/releases/v3.13.4.md).

---

# Ketvoice 3.13.3

![Ketvoice](ketvoice-banner.png)

**versionCode 62** — Discord consent inside Ketvoice and truthful lossless fallback.

- Run the Discord consent in an in-app WebView and intercept the custom-scheme
  redirect locally, instead of relying on a browser or the Discord app to hand it
  back. The 8-second return cancel stays.
- Probe lossless streams, hide an expired provider for 15 minutes and start the
  working MP3 without waiting; downloads retry the fast MP3 before YouTube.
- Explain the automatic MP3 fallback in Settings.
- The third-party lossless provider remains expired; device validation of the
  Discord consent is still pending.

Full notes: [3.13.3](docs/releases/v3.13.3.md).

---

# Ketvoice 3.13.2

![Ketvoice](ketvoice-banner.png)

**versionCode 61** — Discord return handling, restored equalizer and a rebuilt reverb.

- Cancel a pending Discord link 8 seconds after returning without a code; try the
  Discord app before the browser and keep the explicit timeout/state errors.
- Put the equalizer back in the track three-dot menu, show the device's native
  bands and add a Reset action.
- Rebuild Reverb as a Freeverb-style room with pre-delay, eight combs, four
  all-pass diffusers and adjustable room size/damping.
- Device validation of the Discord hand-off and the audio effects is still not
  claimed.

Full notes: [3.13.2](docs/releases/v3.13.2.md).

---

# Ketvoice 3.13.1

![Ketvoice](ketvoice-banner.png)

**versionCode 60** — Discord redirect fix and system-language default.

- Receive the Discord OAuth redirect in Ketvoice's own activity, run the PKCE
  browser flow locally, check `state` and hand the code to the SDK for exchange.
  This fixes the panel hanging on "Connecting to Discord…".
- Add a three-minute timeout and clear errors for cancellation, invalid responses,
  timeouts and `invalid_client` (public-client setting).
- Start in the system language when supported, English otherwise; keep an explicit
  Settings choice. French is no longer forced for new installs.
- Device validation of the Discord flow is still not claimed.

Full notes: [3.13.1](docs/releases/v3.13.1.md).

---

# Ketvoice 3.13.0

![Ketvoice](ketvoice-banner.png)

**versionCode 59** — real Discord account linking and live listening presence.

- Link a Discord account through the official Social SDK with PKCE, no password
  collection and no simulated linked state. The refresh token is encrypted with an
  Android Keystore key and can be removed with one tap.
- Show the playing track, artist, cover and elapsed timer on the linked Discord
  profile, plus **Download ketvoice** and **Listen now** buttons pointing at the
  public site and the track's listen page.
- Presence is opt-in, only while playing, and cleared on pause, stop, unlink or
  opt-out. Expiring media URLs are never published.
- The SDK is owner-supplied and stays out of Git; builds without it hide the
  Discord panel. Voice permissions and the SDK foreground service are excluded.
- Canvas and reverb from 3.12.1 are unchanged. Hardware OAuth/presence checks are
  not claimed: no phone, Quest or accelerated emulator was available.

Full notes: [3.13.0](docs/releases/v3.13.0.md).

---

# Ketvoice 3.12.1

![Ketvoice](ketvoice-banner.png)

**versionCode 58** — banner-era Canvas framing, audible reverb and optical
navigation glass.

- Fill the normal artwork region edge to edge again: no more wide side bands
  around the Canvas, with the blurred scene ambience kept behind it.
- Force decoded PCM playback so device passthrough/offload cannot bypass Reverb,
  deepen the room and show whether audio is actually being processed.
- Give the navigation bar and player pill a dedicated optical glass material and
  sample the player footer from its own backdrop layer.
- Register the Ketvoice Discord application and document its redirect. Account
  linking and live presence remain blocked on the owner-only Social SDK download;
  no fake linked state or non-functional button is shipped.
- Physical audio/video, accelerated-emulator, Discord linking and APK upgrade
  validation are not claimed.

Full notes: [3.12.1](docs/releases/v3.12.1.md).

---

# Ketvoice 3.12.0

![Ketvoice](ketvoice-banner.png)

**versionCode 57** — legacy Canvas framing, sampled-glass menus, real reverb,
optional progressive quality and server-protected artist logos.

- Restore the normal artwork-region Canvas and shared dissolve; allow fullscreen
  only for verified portrait 9:16 clips.
- Render action sheets outside their sampled backdrop; retain readable fallbacks.
- Add adjustable PCM room reverb and independently prepared same-track quality
  upgrades. Keep higher-quality failures from interrupting current playback.
- Fix JSON-returning custom download endpoints, distinct download links and
  initial byte-zero HTTP 206 handling; preserve alternate source candidates.
- Add Home/artist/playlist artwork progress rings and refine collection pages.
- Broker opt-in Fanart.tv logos using a server-only credential, automatic
  MusicBrainz resolution, original colors and attributed fallback behavior.
- External limits: lossless provider access is expired (live HTTP 410); Discord
  requires a registered application and is not included. Physical audio/video,
  owner-session and APK upgrade validation are not claimed.

Full notes: [3.12.0](docs/releases/v3.12.0.md).

---

# Ketvoice 3.11.0

![Ketvoice](ketvoice-banner.png)

**versionCode 56** — player motion, dark-theme readability and portrait-led artist profiles.

- Fix white-on-white player action sheets above light pages; refine charcoal
  surfaces, secondary-text contrast, system-bar spacing and fitted Canvas edges.
- Animate fullscreen Canvas chrome, lyrics transport/viewport changes and menus;
  retain reduced-motion support and independent seeking/exit gestures.
- Add optional public-domain artist signatures in Appearance, disabled by
  default with attribution and a normal-name fallback. No silent network opt-in.
- Request a real native 120 Hz display mode on compatible phones at the current
  resolution, respecting lifecycle, battery saver and Android policy. Report
  the current rate separately; this is not a guaranteed 120 FPS benchmark.
- Show backing vocals only in their timed window, fading out at the next line
  without moving the measured layout. AutoMix stays removed.
- Keep automatic update prompts with release notes and trusted release banners.
  See release metadata for host/mock, external image-rate-limit and device limits.

---

# Ketvoice 3.10.1

![Ketvoice](ketvoice-banner.png)

**versionCode 55** — in-app update checks, download verification and installer retry fixed.

- Foreground/periodic checks, persisted available releases and immediate checks
  after app-version changes; failed requests do not throttle future retries.
- Manual Check for updates in Settings → About, including skipped versions.
  Update badge requests no longer cancel themselves while awaiting GitHub.
- Exact-size/SHA-256 download verification, package/version/signing-certificate
  checks, partial-file cleanup, permission-return continuation and reusable
  installer actions. Android installation confirmation remains mandatory.
- English release notes and website feed stay synchronized with the canonical
  banner. Host/mock tests are distinguished from unperformed real installations.

---

# Ketvoice 3.10.0

![Ketvoice](ketvoice-banner.png)

**versionCode 54** — normal Canvas framing fixed; AutoMix removed completely.

- Canvas no longer inherits the cover's lower fade mask or obscuring overlay.
  Its regular-player viewport ends before the track title and preserves FIT framing.
- Removed the second audio deck, analysis/planning engine, transition commands,
  preparation jobs, overlay, settings and feature labels. Existing analysis cache
  and preferences are retired without touching music, artwork or playlists.
- The website follows stable release notes/downloads, removes outdated feature
  claims and uses the canonical repository banner. New release notes are English.
- Host tests and browser checks remain distinct from real-device playback.
  See [release notes and limits](docs/releases/v3.10.0.md).

---

# Ketvoice 3.9.0

**versionCode 53** — refined control glass, artist/album headers, and Canvas framing.

- Canvas now fits the regular player instead of zoom-cropping. Fullscreen focus
  shows only title, artist and time rail, and is available only when at least
  85% of the source image survives the fill. Missing metadata and decoder errors
  disable it; portrait clips are not magnified into a landscape Quest window.
- Density-independent glass rims, opposite-edge light and thickness shading;
  matte content panels and reduced-effects fallbacks remain unchanged.
- Artist photo/name hierarchy and pill actions; album cover halo and localized
  known duration. Lyrics enter with shorter stagger and longer ease-out fades,
  without changing line size/weight or separate backing vocals.
- Ten translated dictionaries completed; French remains the default.
- Host Android/Skia previews and tests are distinct from physical validation.
  No final device install, real Canvas playback or physical Quest check claimed.
  See [release notes](docs/releases/v3.9.0.md) and the release validation metadata.

---

# Ketvoice 3.7.0

**versionCode 50** — installs over 3.6.3 and every earlier build (same signing certificate).

### Liquid Glass, rebuilt — and the disabled mode too
Before → After: the lens refraction had a visible step where it started, the tint leaned milky and large panels banded. The AGSL shader now raises the lens on a smooth smoothstep profile with softer dispersion, adds a permanent hairline contour and sub-pixel dithering, and every preset is rebalanced so the glass reads as optical material. With glass disabled, panels are no longer flat paint: a frosted translucent body, diagonal light gradient, hairline rim and specular veil.

### The whole chrome collapses together
Before → After: scrolling down only shrank the tab buttons. The bar is now bigger (60 dp tabs, 54 dp mini-player), and when you scroll the mini player condenses to artwork + transport and joins the navigation row with Home and Search interposed beside it — one slim floating row. Scroll back up and everything returns to the full layout. The collapse now also works on Explorer and Library, not just Home.

### The player closes progressively
Before → After: closing the full player was instant. Drag the artwork, the title bar or the handle and the whole screen follows your finger, fading and growing a sheet corner; release low or flick and it slides out, release high and it springs back. Back button and handle tap animate the same way. Quest and tablets keep the previous behavior.

### Held-word highlight in karaoke lyrics
Before → After: nothing marked the word the song was lingering on. On word-by-word and syllable lines, a fully-sung word the singer holds now grows by 9 % with a soft white glow, easing in and out. Reduced motion keeps it off.

### Lyrics tracking is smooth again
Before → After: every visible lyric line recomposed on each 200 ms position tick because the ad-lib window read the position stream, and every arriving translation invalidated the whole list. Position is now read only by the active line and translations land in a per-line granular map.

### Artist page — the photo is back, and it never leaves
Before → After: 3.6.3 replaced the artist photo with an animated canvas that faded away on scroll. The photo is restored as the permanent hero — displayed whole over a blurred extension of itself, with the extended progressive blur band under the status bar, exactly like the player. Artist pages without an immersive header no longer fail entirely, and related-artist avatars fall back to a person icon instead of an empty circle.

### Album page — redesigned
Before → After: the cover sat on the side at 128 dp. It is now the page: 216 dp, centered, with a canvas video when one exists for the album's first track, over a full-bleed blurred backdrop, with centered title, artist, year, track count and **Listen** / **Share** actions.

### Share links that open the app
Before → After: share sent plain text. The player (lyrics header and ⋯ menu) and the album page now build a real link served by the project's GitHub Pages: a friend with Ketvoice is switched automatically to the app and the shared track starts playing; without the app, the page shows the track and a Download button. The app registers `ketvoice://listen` and the matching https path.

### Native volume control
Before → After: the slider drove an in-app multiplier. It now drives the real system media volume (`AudioManager` STREAM_MUSIC) — same steps as the hardware buttons, live sync when hardware volume changes, and an icon that follows the level.

### Settings no longer crash on open
Before → After: a corrupted or locked audio cache could throw inside the Settings screen composition and kill the app. Cache init, size queries and trimming are now failure-proof and degrade to "no cache".

### Fully translated
Before → After: the new strings (share, shared playback, album actions) ship in the bundled catalogs — 827 strings across the eleven interface languages.

---

# Ketvoice 3.6.3

**versionCode 49** — installs over 3.6.2 and every earlier build (same signing certificate).

### Artist page — the hero now owns the whole top of the screen
Before → After: the banner followed the aspect ratio of the artist photo and topped out around 320–560 dp, so it left dead space at the sides and never felt like a cover. It now takes 58% of the screen height (clamped between 400 and 720 dp) and runs edge to edge, so the animated canvas truly fills the top of the page.

### Artist page — title, artist name and actions live inside the cover
Before → After: the name and monthly listeners sat under the banner, and playback was only reachable from the separate button above the track list. The cover now carries the artist name, the "Artist · YouTube Music" label, the listener count, plus two actions directly on top of it: **Listen** (plays the artist's tracks) and **Queue** (opens the player queue). Both labels already exist in `translations.json`, so all eleven languages are covered.

---

# Ketvoice 3.6.2

**versionCode 48** — installs over 3.6.1 and every earlier build (same signing certificate).

### Artist page — songs were missing
Before → After: on many artists the "Popular tracks" list stayed empty, because the parser only kept YouTube Music rows typed `ATV`/`OMV` and the popular section usually exposes another type. The type filter is no longer applied to that list, so every track of the page is returned.

### Artist page — animated canvas instead of the blurred cover
Before → After: the top of the artist page was a full-bleed blurred copy of the artist photo, which the user found wrong. The hero is now filled by `SonicCanvas`, the same animated canvas used by the player, layered under a soft vertical gradient that keeps the artist name readable.

### Artist page — the hero no longer stays on screen
Before → After: the canvas is driven by the scroll state of the list. It fades out over the first 520 px of scrolling and is fully gone as soon as the first item leaves the top, so it introduces the page and then disappears instead of remaining permanent.

### Playlist page — the header scrolled away
Before → After: the playlist cover, title, description and "Listen" button were siblings placed above the track list, so they stayed pinned forever while scrolling. They are now the first item of the list itself and fade out as they scroll off, while the floating back button stays reachable at all times.

---

# Ketvoice 3.6.1

**versionCode 47** — installs over 3.6.0 and every earlier build (same signing certificate).

## Glass outside the player

- **The rest of the app is glass now.** Settings groups were opaque cards sitting next to a glass chrome; they now use the same material (`GlassParams.card`: large radius, 26 px blur, 14 px refraction) so the whole interface reads as one surface. The selected sidebar item is no longer a flat tint but a lit glass layer (vertical highlight plus a 0.6 dp rim), and the settings icon is a circular glass pill instead of a bare icon.
- **New glass slider.** The Material slider was the last solid control in the app: its opaque track and thumb broke the material. It is replaced by `GlassSlider` — a recessed groove with a light top edge, an accent-filled progress segment and a glass thumb. It now drives the equalizer gain, the automix fade and the cache limit, with the same commit-on-release behaviour.

## Lyrics

- **Ad-libs are no longer duplicated.** Parenthesised segments were rendered twice: once inside the line text and once in the animated block below it. The base line is now stripped of those segments before display, so each back exists exactly once.
- **Backs appear only on their own syllables.** They used to stay visible for the whole line because the animated block was bound to the line's active state. Their real window is now derived from bracket depth across the synced words, so they appear at their passage and disappear as soon as the next syllable starts, with a fallback to the previous behaviour when no timing is available.

# Ketvoice 3.6.0

**versionCode 46** — installs over 3.5.3 and every earlier build (same signing certificate).

## Liquid Glass

- **Real optical glass again.** The glass tint was dropping to near zero, which made panels read as empty; the white offsets inside the surface gradient were simultaneously pushing every panel back towards opaque plastic. Both are now balanced: the material keeps a visible milky body while the content behind it stays readable through the refraction.
- **Thin contours.** The extra border drawn on top of the mini-player is gone — the shader already draws its own rim, so the doubled outline had become a thick white frame. The moving tab lens no longer stacks a second edge either.
- **No artificial reflections.** The manual specular strip inside the mini-player was removed; the shader's own sheen and rim now carry the highlights alone.
- **No grey sheet.** Bottom sheets use a lighter tint, so the playlist menu shows depth instead of a flat opaque grey slab.

## Chrome layout

- **Slimmer chrome.** The mini-player is 54 dp tall (was 58 dp) and the navigation capsule is 52/58 dp collapsed/expanded (was 58/64 dp), giving roughly 14 dp back to the content.
- **Sheets are never covered.** The floating mini-player and navigation now hide whenever a bottom sheet or overlay is open — track menu, add-to-playlist, create-playlist, playlist picker, queue and settings. Their options can no longer be masked by the chrome.

---


# Ketvoice 3.5.3

**versionCode 45** — installs over 3.5.2 and every earlier build (same signing certificate).

## Live listeners

- **Real-time listening activity on the website.** The Ketvoice site now shows how many people are listening to music in the app at this moment. The figure refreshes every 30 seconds while the page stays visible.
- **Completely anonymous.** The app sends a heartbeat every 25 seconds while music plays, using a random session ID generated on the device. No account, no identifiers, no track history leaves the phone — only an optional "now playing" title/artist snippet keeps the counter meaningful.
- **Stops with playback.** Pausing or stopping music removes the listener within a minute. Closing the app also lets the entry expire, so the counter only reflects people actually listening.

## Backend

- **New API routes.** `POST /listeners/heartbeat` records anonymous playback pings; `GET /listeners` returns the live count for the website. Both are served by the existing Ketvoice API Edge Function.
- **New migration.** `supabase/migrations/20261029100000_active_listeners.sql` adds the listener table, the heartbeat routine, and the counter routine, locked down to the service role.

---

# Ketvoice 3.5.2

![Ketvoice 3.5.2](https://github.com/Tysnnn23/Ketvoice/releases/download/v3.5.2/ketvoice-banner.png)

**versionCode 44** — installs over 3.5.1 and every earlier build (same signing certificate).

## Lyrics

- **Plain intro dots.** The three-dot intro is now three solid white dots. No halo, no ripple, no drift, no bounce. Each dot simply goes from dim to fully opaque as the intro runs out.
- **Blurred cover behind the lyrics.** The lyrics page no longer uses a flat colour. It shows the same blurred album cover as the player.
- **Translation keeps following.** The translated line used to track the song for one line and then stop. Its clock now restarts every time a line becomes the active one.
- **Translation follows the current line.** The translation slides down from the lyric line when it becomes active and slides back up and fades out when the song moves on. Unsynced lyrics keep showing every translation.

---

# Ketvoice 3.5.1

![Ketvoice 3.5.1](https://github.com/Tysnnn23/Ketvoice/releases/download/v3.5.1/ketvoice-banner.png)

**versionCode 43** — installs over 3.5.0 and every earlier build (same signing certificate).

## Lyrics

- Reworked the three-dot intro: each dot pops in with a springy bounce and a single ripple when it lights, lit dots get a soft halo, and the group drifts gently. Drift, ripple and halo are turned off with "reduce motion".
- Dots light up progressively during the wait (one at 25%, two at 50%, all three at 75%), so all three are lit before the first line starts.

## Liquid Glass

- Glass surfaces now have a thin dark edge under the light rim, for more definition and depth.
- Brighter specular highlights and a stronger inner sheen.
- Dark-mode glass is lighter: the tint is a dark grey instead of pure black.
- These changes follow the published descriptions of the iOS 27 look (defined edges, lighter dark glass, brighter highlights). It is an approximation built with Compose, not Apple's renderer.

---

# Ketvoice 3.5.0

![Ketvoice 3.5.0](https://github.com/Tysnnn23/Ketvoice/releases/download/v3.5.0/ketvoice-banner.png)

**versionCode 42** — installs over 3.4.3 and every earlier build (same signing certificate).

## Lyrics

- **Before:** a fully blurred copy of the active line appeared above the lyrics. **After:** one sharp layer per line, with the usual progressive fade on neighbouring lines.
- **Before:** unselected lines looked "basic" and the text jumped when the active line changed. **After:** every line shares the same size and weight; only the colour marks the active line.
- **Before:** French credits ("Paroles : …", "Compositeur : …") and duplicated lines showed up as ghost lyrics. **After:** they are filtered out.

## Artist page

- **Before:** portrait photos were squeezed into a fixed banner and faces were cropped. **After:** the header follows the real aspect ratio (320–460 dp), anchored to the top, and photos fade in.
- Similar artists without a photo show the artist's initial.

## Interface

- Player controls use a firm press spring with a small bounce on release (instant with "reduce motion").
- Official Spotify icon in the Spotify import.

## In-app updates

- **Before:** `3.5.0` and `3.5.0-BETA` compared as equal, so beta testers never received the final release. **After:** a stable build ranks above its own pre-release (covered by 7 unit tests).
- The check looks at the 10 latest releases (stable and beta); "Later" keeps a green badge next to the profile avatar.
- Only the APK matching your build (phone or Quest) is offered.

> Versions 3.3.1 to 3.4.3 are documented in their GitHub release notes.

---

# Ketvoice 3.3.0

**versionCode 35** — installs over v3.2.0 and every previous release (same signing certificate).

## 🎨 Reference design pass (Liquid Glass + Apple Music-style pages)

This release completes the interface alignment with the provided design references:

- **Navigation dock**: the liquid-glass bottom bar keeps its sliding selection capsule and long-press lens drag, with the specular mini-player above it. The selected capsule now reads as a lighter glass pill, closer to the reference.
- **Library, immersive top**: "VOTRE EXPÉRIENCE D'ÉCOUTE" dark stat card and "VOTRE ARTISTE DU MOMENT" card, system shortcut rows (Téléchargements, Musique locale) and the playlist card row (+ / Spotify import / playlists) — all matching the reference layout.
  - **All statistics are now real**: listening minutes come from actual play counts weighted by track duration, play totals from the listening history, the artist card only appears when a top artist exists, and "MEMBRE DEPUIS" reflects the app's first launch. The placeholder values (forced minimums, hardcoded artist and date) are gone.
- **Explorer, category cards**: rebuilt in the reference style — a solid tinted band on the left carrying the title, and a desaturated artwork fused into the card color on the right. Each genre keeps a stable vivid tint across sessions.

## 🧭 Fixes
- Old **"KONNEKT" branding replaced by "KETVOICE"** wherever it referred to the app.
- The Téléchargements and Musique locale shortcuts in the Library opened a broken empty catalog view; they now open the real Downloads and Local music screens.
- New interface labels translated across the 10 non-French catalogs (809 messages per language).

## 📦 Install
- `Ketvoice-3.3.0.apk` — phones and tablets
- `Ketvoice-3.3.0-Quest.apk` — Meta Quest (landscape 2D window, mobile style)

## ✅ Validation
- Unit tests pass on both flavors; lint 0 errors on both flavors.
- Release APKs verified: `apksigner` v2 (certificate SHA-256 `ae25a19c…`), 16 KB page alignment, `aapt` badging (versionCode 35).
- Visual comparisons with the reference video were done on the rendered frames; no physical-device or emulator run is claimed for this build.


# Ketvoice 3.2.0

**versionCode 34** — installs over v3.1.0 and every previous release (same signing certificate).

## 🎤 Lyrics, cinema mode
- **Progressive depth blur**: upcoming lines sharpen toward the active line, then soften again the further down they go (subtle at 1–2 lines, deep at 5+) — the sung lines keep their light haze.
- Smoother follow-scroll (0.56 s eased glide instead of a short snap).
- The three intro dots before the first line are now animated: each dot breathes on its own phase, lights up with a soft bounce and a faint halo as the music approaches.
- Tapping empty space in the lyrics view brings the transport controls back instantly.

## 🎛️ AutoMix, fully integrated
- The crossfade engine returns as a complete feature: a second audio deck in the playback service, real equal-power/linear crossfades, and optional rhythm analysis (BPM, beat alignment, vocal-risk and quiet-tail detection) to place the transition where it sounds natural.
- Arms the next track in the background, starts the blend a beat before the end, and falls back to a clean fade when analysis is unavailable. Manual skip triggers a short tasteful crossfade.
- New toggle in Réglages → Lecture, with an **AutoMix tab**: maximum fade duration (automatic or 1–30 s custom) and analysis performance level (Basse/Moyenne/Standard/Élevée).
- It is disabled by default and never changes the regular player when off. A discreet overlay shows the blend progress during a transition.

## 🎵 Playlists, rebuilt again — this time premium
- Centered hero: large artwork with a soft glow halo, bold name, availability line.
- Track rows now show artwork, bolder titles, and the same typography as the player.
- The ⋯ button opens a **glass action sheet in the player-menu style**: Tout lire ensuite, **Télécharger la playlist** (with pending count), Renommer, Exporter (.json), Exporter (.m3u), Supprimer la playlist.

## ✨ Interface polish
- **The Android ripple feedback is gone app-wide**: interactions now live in the glass, the content and the accent colors only — consistent with the player and home style.
- All new interface strings are translated across the 11 interface languages (804 messages per language).

## 📦 Install
- `Ketvoice-3.2.0.apk` — phones and tablets
- `Ketvoice-3.2.0-Quest.apk` — Meta Quest (landscape 2D window, mobile style)

## ✅ Validation
- Unit tests pass on both flavors, including the AutoMix planner/DSP test suite; lint 0 errors on both flavors.
- Release APKs verified: `apksigner` v2 (certificate SHA-256 `ae25a19c…`), 16 KB page alignment, `aapt` badging (versionCode 34).
- Crossfade audio behavior is exercised by mocked unit tests only; no physical-device listening validation is claimed. Backend unchanged in this release.


# Ketvoice 3.1.0

**versionCode 33** — installs over both the 3.0.x line (v3.0.5–v3.0.7) and the 3.0.0-Beta line. Same signing certificate as every previous release; no uninstall needed.

This release reunifies the two parallel lines: the playlist/navigation/Replay work from 3.0.0-Beta4 and the later 3.0.x maintenance are combined in a single Android codebase. The iOS port directory and its build workflow are removed from the repository.

## 🎵 Playlists, redesigned
- Complete playlist screen redesign: large artwork hero header, bold title, filled play button, overflow menu (rename, export, delete).
- **Drag & drop reordering** with a drag handle on every row and animated live reordering.
- **Export** as `.json` (re-importable) or `.m3u` through the Android share sheet; **import** a `.json` or `.m3u` file from the Library.
- Playlists open on top of the library with a proper back stack.

## 🧭 Navigation
- **Overlay back stack**: artist → similar artist → back, album → artist → back; Settings → Downloads/Sources → back returns to Settings.
- Differentiated transitions: horizontal push forward, reverse on back, vertical fade for tab changes.

## 🔁 Replay
- Real Replay screen: total listens, most-played titles with per-track play counts, top artists — counted from actual playback.

## 🎤 Lyrics
- **Source diagnostics** panel (Réglages → Paroles): tests every lyrics provider live on the current track.

## 🐛 Fixes
- Tablet player: interior horizontal drags (scrub, volume) no longer move the whole screen; swipe-to-close only responds from the screen edges.
- `previous` wraps to the last title at the top of the queue; shuffle avoids repeats.
- Playback retry after stream errors is state-based; possible double download during quality upgrade fixed.
- Download deletion offers **Annuler**; YouTube Music sign-out asks for confirmation.

## 🔒 Backend (Supabase)
- `ketvoice-api` now enforces **per-IP rate limits** (search 60/min, stream 120/min, canvas 30/min) and caches search results in Postgres for 6 hours. Both verified live: the 61st request returns HTTP 429; repeat searches are served from the cache.

## ℹ️ Note for users coming from 3.0.6/3.0.7
The experimental **AutoMix** crossfade feature is not carried into this unified build — its player hooks were incompatible with the unified playback engine and shipping it half-wired was not acceptable. It remains available in the archived v3.0.6/v3.0.7 releases, which stay published. All other 3.0.x fixes are included.

## 📦 Install
- `Ketvoice-3.1.0.apk` — phones and tablets
- `Ketvoice-3.1.0-Quest.apk` — Meta Quest (landscape 2D window, mobile style)

## ✅ Validation
- Unit tests pass on both flavors (mobile + Quest); lint 0 errors on both flavors.
- Release APKs verified: `apksigner` (v2 scheme, certificate SHA-256 `ae25a19c…`), `zipalign -c -P 16` (4 KB and 16 KB page alignment), `aapt` badging (versionCode 33).
- Emulator install checks were not run for this build (no AVD available in the build environment). The install failure reported on 3.0.0-Beta4 was a versionCode downgrade (26 over an installed 32), not a package defect.


# Ketvoice 3.0.0-Beta4

## 🎵 Playlists, redesigned
- Complete playlist screen redesign: large artwork hero header, bold title, filled play button, and a proper overflow menu (rename, export, delete).
- **Drag & drop reordering**: edit mode now shows a drag handle on every row — grab ⠿ and slide a title to its new place, with animated live reordering. No more up/down arrow buttons.
- **Export a playlist** as `.json` (full fidelity, re-importable) or `.m3u` (universal), through the Android share sheet.
- **Import a playlist file** from the Library: pick a Ketvoice `.json` or any `.m3u` file; titles are added and can be resolved to streams afterwards, exactly like Spotify import previews.
- Playlists now open on top of the library with a proper back stack — the back button and system back both return where you came from.

## 🧭 Navigation
- **Overlay back stack**: opening an artist from a similar artist, or an album from an artist page, now keeps the previous screen — back returns through the chain instead of dumping you at the root.
- Differentiated transitions: horizontal push forward, reverse on back, vertical fade for tab changes.

## 🔁 Replay
- Replay is now a real screen: total listens, most-played titles (with per-track play counts) and your top artists, all counted from actual playback.
- New Replay card in the Library collections.

## 🎤 Lyrics
- New **source diagnostics** panel (Réglages → Paroles): tests every lyrics provider live on the current track and reports what each one can offer (native syllables, word timing, line timing or plain text).

## 🐛 Fixes
- Fixed tablet player: horizontal swipes inside the player (scrub bar, volume) no longer drag the whole screen; swipe-to-close now only responds from the screen edges.
- Fixed `previous` button: at the top of the queue it now wraps to the last title instead of restarting the first one.
- Shuffle no longer repeats the same title back-to-back and avoids recently played tracks.
- Playback retry after a stream error is now state-based, not string-based (the retry button could silently stop working when the quality label changed).
- Fixed a possible double download when quality upgrade warm-up and background caching raced on the same stream.
- Deleting a download now shows an **Annuler** action for a few seconds (relaunches the download).
- Sign-out from YouTube Music now asks for confirmation.

## 🔒 Backend (Supabase)
- The public `ketvoice-api` proxy now enforces **per-IP rate limits** on search/stream/canvas (memory + database-backed rolling window), protecting egress and the server-side provider cookies.
- Search results are now **cached server-side** (6 h) in Postgres: faster repeat searches, less load on upstream providers.
- Requires applying the two new migrations (`20261026120000_api_rate_limit.sql`, `20261026120100_search_cache.sql`) and redeploying the function.

## 📦 Install
- `Ketvoice-3.0.0-Beta4.apk` — phones
- `Ketvoice-3.0.0-Beta4-Quest.apk` — Meta Quest (landscape 2D window, same interface)

## ✅ Validation
- 127 JVM unit tests pass on the mobile flavor (122 existing + 5 new playlist export/import tests); the Quest flavor unit tests pass as well.
- `compileMobileDebugKotlin` and `compileQuestDebugKotlin` succeed.
- Lint: 0 errors on both flavors (121 warnings, 8 hints — unchanged baseline level).
- Emulator-based Compose UI tests were not run for this build. No live provider download, no physical Quest run and no real-owner publication edit is claimed.

---

## 3.0.7 — NAVIGATION BACK HISTORY (versionCode 32)

- Hardware Back and each in-app back button now restore the previous screen,
  including artist, album, playlist, settings, downloads and search routes.
- Opening an artist from the full player restores the player when returning.
- Switching main tabs intentionally clears navigation history.
- See [3.0.7 release notes](docs/releases/v3.0.7.md).

---

## 3.0.6 — AUTOMIX (BETA) (versionCode 31)

- Optional AutoMix blends the current and upcoming songs using two independent
  Media3 audio decks and a real equal-power crossfade. It is off by default;
  turning it off cancels any ongoing blend immediately.
- Off-UI-thread PCM analysis estimates BPM, energy and likely vocal density near
  transition points. Compatible tempos align beat phases without time-stretching;
  failed or unavailable analyses fall back to a simple fade.
- Settings add an AutoMix tab with automatic/custom maximum fade (1–30 seconds)
  and four performance levels, reduced on modest devices. The player shows a
  small two-cover progress indicator while tracks overlap.
- See [3.0.6 release notes](docs/releases/v3.0.6.md) for validation limits.

---

## 3.0.5 — STUDIO EMAIL RETURN (versionCode 30)

- A sign-in link requested from the desktop Studio now returns to the hosted
  Studio page and signs in there automatically.
- The mobile app continues to request its own `ketvoice://auth` redirect;
  its e-mail link flow is unchanged.
- The Studio still opens from its local HTML shortcut. No changes to music
  playback, Quest layout or the authorization of publisher actions.
- See [3.0.5 release notes](docs/releases/v3.0.5.md).

---

## 3.0.4 — STUDIO, MANAGED SOURCES AND ARTIST BACKDROP (versionCode 29)

- Add a local desktop HTML studio for owner-authenticated publishing, imported
  song edits, cover replacement, lyrics and managed Ketvoice API sources.
- The API reloads active managed sources on searches and stream requests;
  deleting, disabling or changing a source needs no APK installation or alert.
- Artist pages extend the image into a blurred backdrop beneath the banner,
  avoiding a blank block when only a short landscape image is available.
- See [3.0.4 release notes](docs/releases/v3.0.4.md).

---

## 3.0.3 — ADAPTIVE ARTIST BANNERS (versionCode 28)

- Artist headers now adapt to the source image's measured aspect ratio. Short
  panoramic banners no longer leave an oversized empty color block beneath them.
- The image-to-color fade starts at the actual lower edge of the displayed
  artwork. When a portrait source exists, it takes priority over a wide banner.
- See [3.0.3 release notes](docs/releases/v3.0.3.md) for validation limits.

---

## 3.0.2 — ARTIST IMAGE FRAMING (versionCode 27)

- Artist profiles now prefer a wide header image when the catalog provides one.
  Square portraits keep their natural width instead of being enlarged to cover
  the entire page, eliminating the excessive crop.
- A taller, full-screen-inspired header fades into a sampled pastel tone, with
  the artist name and functional shuffle/play/share controls on that surface.
- See [3.0.2 release notes](docs/releases/v3.0.2.md) for validation limits.

---

## 3.0.1 — IMMERSIVE ARTIST PROFILES (versionCode 26)

- Artist pages now open with a large, unblurred portrait and a gradual blend into
  a color sampled from the artwork; playback and refresh actions sit in the header.
- Artist biographies are translated to the selected app language, with the
  original text retained if translation is unavailable.
- Tapping an artist in the full-screen player now reveals the profile and keeps
  audio playing in the mini-player.
- See [3.0.1 release notes](docs/releases/v3.0.1.md). Existing releases remain intact.

---

## 3.0.0-Beta3 — SIGNED APK REBUILD (versionCode 25)

- Rebuilt the latest Beta3 source, retaining the recent beta UI and crash fixes.
- The original mobile and Quest APKs had no valid Android signature. Release
  outputs are now automatically signed, and a blocking verification gate checks
  certificate, version, ZIP CRC, 16 KB alignment and downloaded release hashes.
- Protected captured screens from sampling their own glass backdrop; preserved
  Liquid Glass on sibling chrome. Hardened catalog/artist error handling.
- Fixed local-audio permission requests for Android 12/12L and 13+, denied/read
  error states, true 2×2 cover mosaics and bottom navigation clearance.
- 117 JVM and 19 Compose tests pass per flavor, plus 8 packaging-gate unit tests.
  Both signed release APKs install and launch on an emulator. No physical-device,
  real owner-session or full live provider playback validation is claimed.
- [Rebuild notes](docs/releases/v3.0.0-Beta3-rebuild.1.md). Separate prerelease tag;
  the original Beta3 and all previous releases remain intact.

---


## 2.12.0 — PLAYLISTS, SPOTIFY IMPORT & SELECTED DESIGN FINISHES

**Mobile, tablet and Quest · versionCode 21**

- Library gains **Créer une playlist** and **Importer depuis Spotify**. Playlists are local only: create, rename, delete, reorder, remove and play, with an atomic bounded store and no expiring stream URLs kept on disk.
- Spotify import reads a public playlist preview by link, warns that the preview may be incomplete, requires confirmation, and lists matched and unmatched titles separately. No Spotify login, cookie, audio or 30-second preview download is used. Unmatched titles stay visible and can be resolved manually.
- The import cover is an original Ketvoice artwork tinted by the playlist and may use the public artwork URL as a dimmed background.
- Selected designs **D1, D2, D3, D4, D5, D7 and D8** are implemented, each with its own switch in Réglages → Design Ketvoice. D6 was not implemented. Quest remains a 2D landscape window.
- The whole interface is audited and translated: 745 messages per language across the 10 non-French catalogs, placeholder parity checked, and the selected language now also applies to native Android dialogs.
- 117 JVM tests and 15 Android Compose tests pass per flavor; lint and release results are recorded in `validation.json`.
- No real Spotify account, Spotify audio download, authenticated owner edit or physical Quest run is claimed. See [2.12.0 release notes](docs/releases/v2.12.0.md).

---

## 2.11.0 — IMPORTED MEDIA EDITS, SONIC CANVAS & OFFLINE CAPSULES

**Mobile, tablet and Quest · versionCode 20**

- The verified publisher can replace audio and/or artwork only on its own imported publications. Ownership, file validation and concurrent-edit protection are enforced server-side; lyrics and track IDs are preserved.
- Imported stream cache keys include the immutable media revision URL. Existing offline downloads are never silently replaced.
- A global video Canvas switch disables video playback and future video downloads. Sonic Canvas is separate and off by default, reacting to local decoded PCM without using the microphone.
- Offline capsules provide a duration/budget selection preview, favorite-first planning and sequential downloads without evicting existing files. Readiness comes from saved files; shared caches are outside the capsule budget.
- Ketogether remains deferred; existing single-device Wi-Fi handoff is unchanged.
- 110 JVM tests and 12 Android Compose tests pass per flavor; lint 0 errors, 137 warnings, 8 hints. Broker checks: 21 existing and 23 edit cases mocked; 3 live read-only checks. Content broker v4 deployed with JWT verification on.
- No authenticated real-owner edit, full live capsule download or physical Quest test is claimed. See [2.11.0 release notes](docs/releases/v2.11.0.md).

---

## 2.10.0 — ALBUM COLOURS, ARTWORK DEPTH & LIBRARY MOTION

**Mobile, tablet and Quest · versionCode 19**

- Album-derived player accents with contrast adjustment and an opt-out setting.
- Subtle artwork depth that leaves gestures intact, one-time collection entrances, locale-aware animated album sorting and real per-track download progress/success feedback.
- Longer titles, actionable empty states and clearer Quest detail text; landscape split panes and the 2.9.0 artist pages remain intact.
- A5 (playlist-add animation) is explicitly deferred; no new playlist feature is introduced.
- 98 JVM tests and 7 Android Compose tests pass per flavor. Lint has 0 errors (129 warnings, 8 hints). Both APKs retain the existing development signing certificate.
- Device UI checks use owned-metadata fixtures on an Android 16 emulator, not physical Quest hardware or a live provider download. Previous releases and the backend are unchanged.

See [2.10.0 release notes](docs/releases/v2.10.0.md) and [implementation/validation details](docs/SELECTED-POLISH.md).

## 2.9.0 — ARTIST PAGES & PLAYER READABILITY

- Full-width blurred artist banners with gradient overlays and larger artist headings.
- Larger player titles and clickable artist names when a catalog artist ID is available.
- See the original [2.9.0 change notes](CHANGES-2.9.0.md).

---

## 2.8.0 — GLASS, MOTION, EQUALIZER & SAME-WIFI HANDOFF

**Mobile, tablet and Quest:** 23 October 2026 · **versionCode:** 17

- Liquid Glass surfaces get tighter bevel/refraction and a soft gradient rim; Quest hover light follows the pointer.
- One motion vocabulary with an **Animations réduites** toggle and Android's animator scale respected. The three intro dots are now fixed-size lights that switch on in sequence — no halo, circle, merge, or zoom — in a reserved slot so lyrics do not jump.
- Lyrics stay visible during the intro, follow correctly, pause following on manual scroll, and resume after a seek. Karaoke masks are built once per layout instead of every frame.
- Equalizer with five bands, presets, and ±12 dB controls, applied to the real playback audio session through the device's native effect and clamped to its supported range. Unsupported devices are told so.
- Same-Wi-Fi transfer with local mDNS discovery and **mandatory confirmation on the receiver**: current track, position, flags, and up to 50 catalog queue entries. No QR code, account, cookies, tokens, files, or private URLs.
- Lyrics size now applies on phones/tablets; optional panoramic Canvas is off by default; favorite and transport polish; stable queue row keys.
- 87 JVM tests pass per flavor; lint 0 errors. Signed APKs checked on an Android 16 emulator: phone player, Quest landscape player, native equalizer effect, mDNS discovery, and a real LAN offer with explicit refusal. No physical Quest or real owner upload test.
- The 2.7.0 server-side music-upload fix is unchanged; no backend change in this release.

See [2.8.0 notes](docs/releases/v2.8.0.md), [Quest guide](docs/QUEST.md), and [2.7.1 Quest notes](docs/releases/v2.7.1.md). Previous releases are preserved.

---

## 2.7.1 — QUEST LANDSCAPE: ARTWORK LEFT, LYRICS RIGHT

**Quest-only release:** 22 October 2026 · **versionCode:** 16

- Both Quest activities request landscape; default 1280×800 dp window with aspect-ratio locking. Removed the portrait-phone frame.
- Lyrics and queue use the right pane. Artwork/Canvas, title, artist, and original mobile playback/volume controls remain left and no longer disappear after the phone's two-second lyric delay.
- Normal artwork mode retains cover left and controls right. Mobile icons, animations, karaoke, and styling are reused without the oversized Quest toolbar.
- 68 tests pass per flavor; Quest build/lint/signature checks pass. Signed APK UI checks run on an Android emulator at 1280×800 and 960×600 dp. Physical Quest validation remains outstanding.
- The mobile 2.7.0 APK, backend, and previous release assets are unchanged. No new mobile APK is published.

See [Quest 2.7.1 notes](docs/releases/v2.7.1.md).

---

## 2.7.0 — MOBILE-STYLE QUEST WINDOW & MUSIC UPLOAD FIX

**Released:** 22 October 2026 · **versionCode:** 15

- Separate Quest 3/3S APK that reuses the mobile interface, player, gestures, floating navigation, Liquid Glass, and animations. No replacement Quest dashboard, oversized toolbar, tablet rail, or forced two-pane player.
- Portrait window defaults and bounded phone-style content on wide windows. Optional lyric sizing/keep-awake in Settings and a sleep timer in the existing track menu.
- Fixed music upload rollback caused by parsing an empty successful PostgREST 201 response after saving associated lyrics. Deployed server-side; existing clients benefit too. Server authorization and validation stay intact.
- 66 JVM tests pass per flavor; both release builds and lint pass with 0 errors; 21 mocked broker checks pass. Signed Quest APK installation/UI/timer checks use an Android emulator. No physical Quest compatibility/FPS guarantee or real owner upload is claimed.

See [2.7.0 notes](docs/releases/v2.7.0.md) and [Quest guide](docs/QUEST.md). Previous published releases are preserved.

---

## 🔗 2.6.1 — PUBLISHER LOGIN RETURNS TO KETVOICE

**Released:** 22 October 2026<br>
**Version:** `2.6.1` (`versionCode` 14)<br>
**Status:** Login hotfix; 2.6.0 and 2.5.0 remain unchanged

- **Before:** opening the email link ended at localhost; pasting a standard Supabase link also used the wrong verification payload.
- **After:** new email links return to `ketvoice://auth`, reopening the publisher screen in a cold or already-running app. The original email link is verified using `token_hash` when pasted manually.
- Callback sessions are checked against Supabase and the publication broker; only the confirmed publisher mailbox can publish. No client-side permission bypass, SMTP credential, or paid subscription is added.
- Numeric codes remain supported, but the selected delivery workflow is a direct email link, not a customized code-only email.
- 61 JVM tests pass; lint has 0 errors; 17 mocked publication API checks and 5 emulator callback checks pass. The mailbox owner must still complete the real email-login test.

See [2.6.1 notes](docs/releases/v2.6.1.md) for installation, checksums, and limitations. Request a **new** email link after installing.

---

## 📱 2.6.0 — TABLETS, RICHER ARTIST SEARCH & PUBLIC PUBLICATIONS

**Released:** 22 October 2026<br>
**Version:** `2.6.0` (`versionCode` 13)<br>
**Status:** Final release; 2.5.0 remains published and is not overwritten

### Before → after

| Area | Before | After |
|---|---|---|
| **Tablet navigation** | Phone navigation stretched across large windows. | A floating glass rail appears at tablet widths, with a compact labelled rail for portrait tablets and an expanded rail on wide landscapes. |
| **Tablet layouts** | Lists, grids, settings, and dialogs used phone widths. | Track grids gain columns, Explore tiles adapt, Settings/import forms keep readable widths, and mini-player/dialog bounds stop stretching. |
| **Tablet player** | One vertically stretched player column. | Artwork and controls sit beside lyrics or queue when the window has room. Short landscape windows such as phones keep the single-pane landscape player instead of squeezing two panes. |
| **Artist search** | Searching an artist name showed artist cards, but the strict song parser discarded valid rows. | Artist searches now show the artist’s songs in a separate section as well as the artist card. Requests use the same `WEB_REMIX` client as artist/album lookups, and rows still need their own official-audio watch endpoint. |
| **Search chrome** | On phones, the floating mini-player and navigation could cover results while the keyboard was open. | Floating chrome hides while the IME is visible and returns when the keyboard closes. |
| **Public songs** | Every playable track came from external sources. | Authorized publications appear in normal search and the Ketvoice API source, with public streaming, downloads, artwork, and lyrics. |
| **Public lyrics** | Lyrics came only from external providers. | A published TTML/LRC version adds a selectable **Ketvoice API** lyric provider. Auto mode may use it when it answers within a short window, then falls back to the existing providers. Original timing is preserved. |
| **Publishing access** | No in-app import workflow. | Only the verified **konnektsupport@gmail.com** mailbox can publish. A matching YouTube profile reveals the entry point, but the broker re-verifies the Supabase Auth user and confirmed email on every write. |
| **Import workflow** | Not available. | **Settings → Import a song** accepts MP3/M4A/AAC/FLAC/WAV/OGG/Opus up to 25 MiB, an optional cover, and optional TTML/LRC. **Track ⋯ → Import lyrics** previews native timing before publishing a supplementary public version. |
| **Write security** | Not applicable. | No client write grants on the public tables or media bucket. The broker validates files/signatures/sizes, rejects unauthenticated and forged requests before consuming bodies, and uses a server-side service role. The YouTube session is never sent to the content service. |

### Validation and limitations

- JVM tests, Android lint, debug/release builds, signing checks, and credential scans are part of the release checks.
- Android 16 emulator: phone portrait, phone landscape, tablet portrait, and 1280×800 dp landscape windows; artist searches returning real songs; keyboard-safe search; adaptive settings/import/player screens.
- A mocked HTTP/Storage harness exercises the publication broker and authorization paths. Live checks confirm public reads, rejected unauthorized/forged writes, database grants/RLS, and security advisories.
- A real authenticated upload requires the mailbox owner to open the Supabase email link. Development did not impersonate that account or send mail automatically.
- Published media is public and counts against Supabase storage/egress quotas. There is no separate moderation queue beyond the single authorized publisher.
- Tablet validation is emulator-only; no physical tablet or device farm was available.
- No historical 1.2.0–2.3.0 APKs are published, and the public 2.5.0 release remains unchanged.

---

## 🚀 2.5.0 — OFFLINE MUSIC, LANGUAGES & MOTION

**Released:** 21 October 2026<br>
**Version:** `2.5.0` (`versionCode` 12)<br>
**Status:** Final release; supersedes the unpublished 2.4.0 candidate

### Before → after

| Area | Before | After |
|---|---|---|
| **Languages** | French-only interface. | French, English, Hindi, Japanese, Chinese, Russian, Italian, Spanish, German, Portuguese, and Korean throughout the interface, persisted in Settings and available offline. Original songs, artists, and lyric text remain unchanged. |
| **Downloads** | A single failed stream could prevent a usable offline song. | Redirect handling, retry/resume, audio signature and response-length validation, selected-quality preference, and playable fallbacks. The saved format is shown honestly. |
| **Offline lyrics** | Lyrics were fetched independently of the audio download. | The best available native syllable, word, line, or plain lyrics are stored with the song as JSON and LRC sidecars, then loaded locally before network requests. No timings are fabricated. |
| **Canvas** | Playback cache only. | Explicit song downloads also retain available Canvas media. Persistent Spotify-ID and artist-qualified album mappings allow reuse. |
| **Library** | Simple collection rows. | Local-music card, horizontal collection cards, artist circles, albums, recent tracks, and downloads, backed by actual library data. |
| **Player motion** | Full-screen dismissal without a cover handoff. | Fading artwork flight between the full player and mini-player, in both directions. |
| **Lyric motion** | Abrupt line emphasis and small looping dots. | Animated line color/blur/scale, lyric-view opening and closing, and larger dots that illuminate sequentially, grow, merge, and fade before lyrics. |
| **Providers** | A third provider could stall or rate-limit requests. | Only Ultra MAX and Last Babe remain in built-in routing; the retired provider's server configuration and Vault secret are deleted. |
| **Credentials** | Server migration prepared as 2.4.0. | That isolation is included here: Spotify/add-on credentials stay in Supabase Vault, never in the APK. |

### Validation and limitations

- JVM tests, Android lint, debug/release builds, APK signing/integrity checks, and credential scans are part of the release checks.
- Android 16 emulator: real source search, playback, downloaded audio/lyric sidecars/Canvas, and language switching across screens.
- A provider may not offer lyrics, Canvas, or the selected audio quality. Optional asset failures do not discard saved audio. Lower-quality fallback is possible.
- Interface translations are machine-assisted with curated corrections; external provider content is left intact. Physical-device validation is not available.
- Explicit downloads may fetch Canvas over mobile data even when automatic artwork downloads are Wi-Fi-only.
- No 2.4.0 APK or credential-bearing historical APKs are published with this release.

---

## 🧪 2.4.0 — KETVOICE API (SUPERSEDED CANDIDATE)

**Released:** 21 October 2026<br>
**Version:** `2.4.0` (`versionCode` 11)<br>
**Status:** Unpublished candidate — included in and superseded by 2.5.0

Version 2.4.0 moves the remaining sensitive music-source and Spotify Canvas work to a Supabase Edge Function called **Ketvoice API**. The app keeps its French Liquid Glass interface and its real playback pipeline, but it no longer asks the user to paste a Spotify cookie or private add-on URLs.

### 🔐 Credentials — before → after

| Area | 😕 Before | ✅ After |
|---|---|---|
| **Spotify Canvas** | The user had to find and paste a personal `sp_dc` cookie into Settings. | Ketvoice API resolves Canvas server-side. The app has no Spotify cookie field and receives only a Canvas URL and Spotify ID. |
| **Source search and streams** | Add-on base URLs and tokens were user-supplied app settings. | Ketvoice API searches the configured providers and returns only track metadata and short-lived stream URLs. |
| **APK contents** | No credentials were bundled, but users still handled sensitive values. | The APK contains only the public Supabase `anon` key and the function URL. Spotify and add-on credentials live in Supabase Vault. |
| **Server permissions** | No Ketvoice backend existed. | The function uses the Supabase service role only to read named Vault secrets. The helper RPC is service-role-only. |

### 🎵 Sources and Canvas — before → after

| Area | 😕 Before | ✅ After |
|---|---|---|
| **Source UI** | Users added JSON manifests and configured private add-ons manually. | A pinned **Ketvoice API** card appears in **Search → Sources** and Settings, with a health test. |
| **Source results** | Results came from user-configured endpoints. | Server-side results are labelled `API` in the app. The final 2.5.0 provider set is Ultra MAX and Last Babe. |
| **Playback quality** | The app resolved add-on streams directly. | Ketvoice API resolves a fast MP3 start and a FLAC upgrade when a lossless provider has the track. |
| **Canvas setup** | Manual Spotify cookie entry. | No setup. Canvas works when Spotify has a Canvas for the track and the server session is valid. |
| **Provider failures** | A slow or failing add-on could delay the whole search. | Each source search has an eight-second cap, results are cached for 60 seconds, and HTTP `429` puts a provider on a five-minute cooldown. |

### ✅ Validation — before → after

| Check | 😕 Before | ✅ After |
|---|---|---|
| **Backend** | No server-side broker. | Live `/health`, `/search`, `/stream`, and `/canvas` checks pass. Ultra MAX returns MP3, Last Babe returns FLAC, and Canvas returns a real `canvaz.scdn.co` URL. |
| **Android build** | 2.3.1 only. | `compileDebugKotlin`, `assembleDebug`, `assembleRelease`, JVM tests, and `lintDebug` pass for 2.4.0. |
| **APK scan** | No credentials in 2.3.1. | No `sp_dc` value, long Spotify cookie pattern, or private add-on host in the signed 2.4.0 APK. |
| **Runtime** | No 2.4.0 run. | Android 16 emulator: search shows `API` results, playback reaches `PLAYING`, and Canvas downloads to the app cache. |
| **Settings** | Cookie and add-on fields were visible. | Canvas and Sources are read-only Ketvoice API sections; the app shows version 2.4.0 and a server health test. |

### ⚠️ Known limitations

- Providers can rate-limit requests. Ketvoice API temporarily skips unavailable providers and continues with available sources.
- Runtime validation used an Android 16 emulator, not a physical device.
- The public Supabase `anon` key can be extracted from any client. `verify_jwt` prevents unauthenticated calls, but it is not a full abuse barrier.
- Custom JSON sources remain user-managed and are separate from Ketvoice API.

---

## 🚀 2.3.1 — THE CLEAN PUBLIC BUILD

**Released:** 21 October 2026  
**Version:** `2.3.1` (`versionCode` 10)  
**Status:** ✅ Previous clean public release

This release is intentionally small in code and huge in safety. It removes the personal credentials that made the earlier APKs unsafe to share.

### 🔐 Security cleanup — before → after

| Area | 😕 Before | ✅ After |
|---|---|---|
| **Spotify Canvas** | A personal Spotify `sp_dc` session cookie was compiled into the default settings. Anyone with the APK could extract it. | The default is empty. Each user enters their own cookie in **Settings → Spotify Canvas**. |
| **Private add-ons** | Private add-on URLs and tokens were compiled into `AppSettings`. | All default add-on endpoints are empty. Users add their own trusted sources in Settings or **Search → Sources**. |
| **Blank add-ons** | A blank add-on base could still start a malformed request before failing. | Blank endpoints are filtered out. Search and stream resolution return safely with no request. |
| **Public distribution** | The app could not be published safely with its embedded credentials. | The source and the 2.3.1 APK contain no session cookies, API tokens, or private add-on credentials. |
| **Versioning** | The last personal build was 2.3.0 (`versionCode` 9). | 2.3.1 (`versionCode` 10) is the first clean public build. |

### 🧪 Validation — before → after

| Check | 😕 Before | ✅ After |
|---|---|---|
| **JVM tests** | No public clean-build verification. | 36 tests across 6 suites, 0 failures. |
| **Android lint** | No public clean-build verification. | 0 errors. |
| **APK integrity** | Personal build only. | Debug-signed, zipaligned, APK Signature Scheme v2 verified. |

> ⚠️ Historical APKs from **1.2.0 through 2.3.0** are withheld because they embed development-only credentials. Version **2.3.1** is the first APK intended for public use.

---

## 🧩 2.3.0 — CUSTOM SOURCES, REAL YOUTUBE MUSIC, REAL CANVAS

**Released:** 21 October 2026  
**Version:** `2.3.0` (`versionCode` 9)  
**Status:** 🚫 Binary withheld — contained development credentials

### 🔎 Search and catalog — before → after

| Area | 😕 Before | ✅ After |
|---|---|---|
| **Custom sources** | The app relied on built-in providers. There was no user-managed JSON source system. | Users can add HTTPS `.json` manifests, with direct endpoints or BitChord-compatible add-ons. |
| **Source validation** | A saved URL was not proven to work. | Ketvoice fetches the manifest and performs a real test search before saving it. |
| **Quality selection** | Quality was not negotiated from the source manifest. | The app reads declared quality options (`LOSSLESS`, `HIGH`, `LOW`, `high`, `normal`, `low`) and negotiates the best available tier. |
| **Downloads from sources** | There was no explicit source download endpoint. | An explicit `download` endpoint is preferred; otherwise the stream is used. `allowDownloads: 0` is respected. |
| **Artist search** | Artist results were only search rows. | Dedicated artist page with photo, description, monthly listeners, albums, and tracks. |
| **Album pages** | Albums were not resolved from real album IDs. | Album pages are built from real YouTube Music `MPRE...` identifiers. |
| **Home feed** | The home screen was generic. | A personalized YouTube Music home feed appears when a valid session is connected. |

### 🔐 YouTube Music session — before → after

| Area | 😕 Before | ✅ After |
|---|---|---|
| **Account state** | A mock account system and fake “connected” state existed. | The account area reflects the real YouTube Music web session. No fake account is created. |
| **Login** | There was no real first-party login flow. | A WebView opens Google sign-in, with a manual `SAPISID` cookie fallback. |
| **Session storage** | No real session storage. | The cookie is stored locally with Android Keystore encryption. |
| **Validation** | “Connected” was cosmetic. | The app reports connected only after `account/account_menu` validates the session. |
| **History sync** | No real listening history submission. | A play is counted only when YouTube accepts the real tracking request. No fake history is created. |
| **Search history** | Assumed available. | Search history is not read back; only accepted playback events can be submitted. |
| **Personalized home** | Generic content. | Personalized home is available when the real session supports it. |

### 🎨 Canvas, accent, and settings — before → after

| Area | 😕 Before | ✅ After |
|---|---|---|
| **Canvas** | No real Spotify Canvas pipeline. | Real Pathfinder lookup, Genius fallback, local video cache, and optional Wi-Fi-only downloads. |
| **Canvas credentials** | No user-controlled session. | The user provides a Spotify `sp_dc` cookie; no Spotify credential is bundled. |
| **Accent color** | Fixed accent color. | User-selectable accent applied to sliders, timeline, scrubber, controls, progress, downloads, switches, and selected states. |
| **Settings** | Some rows were visual or mock toggles. | Settings are persisted and functional. Choice rows open a sub-menu instead of changing on a single tap. |
| **Animated artwork** | The setting did not fully control Canvas downloads. | Turning off animated artwork also disables Canvas downloads. |
| **Lyrics settings** | Some lyric preferences were not persisted. | Inactive-lyric blur, animated artwork, and Canvas-on-mobile-data persist correctly. |
| **Cache** | Cache behavior was incomplete. | Real cache with a configurable ceiling up to 10 GB and real cache clearing. |

### 🖼️ Player, artwork, and lyrics — before → after

| Area | 😕 Before | ✅ After |
|---|---|---|
| **Home logo** | A square background was visible around the logo. | The home logo is transparent and background-free. |
| **Artwork edge** | A visible border or hard edge appeared at the bottom of the artwork. | The artwork fades into the ambient background with no visible border. |
| **Artwork from lyrics** | Tapping the compact artwork could open the action menu instead of returning to the artwork. | Tapping the artwork from lyrics returns directly to the artwork page without opening actions. |
| **Mini-player** | Navigation and compact behavior were less polished. | Improved mini-player, navigation lens, three-dot indicator, and haptics. |

---

## 🎨 2.2.0 — OFFICIAL SEARCH, REAL LYRICS, CLEANER NAVIGATION

**Released:** 20 October 2026  
**Version:** `2.2.0` (`versionCode` 8)  
**Status:** 🚫 Binary withheld — contained development credentials

### 🔎 Search — before → after

| Area | 😕 Before | ✅ After |
|---|---|---|
| **Official tracks** | Community uploads could be used as a fallback. | Search requires verified audio tracks with an artist and album. |
| **Old entries** | Unverified cached entries could play. | Entries are re-resolved before YouTube playback. |
| **Slowed/sped-up** | Variants could appear implicitly. | Explicit search is required. |

### ✍️ Lyrics — before → after

| Area | 😕 Before | ✅ After |
|---|---|---|
| **Typography** | Smaller, lighter lyrics. | 33 sp Black weight, wider layout. |
| **Highlight timing** | Highlight could ignore real segment duration. | The glow progresses during each source-provided segment. |
| **Syllable timing** | Could imply fabricated syllable timing. | Syllable-by-syllable highlight only when the provider supplies it. |
| **Controls** | Always visible. | Hidden after 2 seconds without contact; artwork tap restores them. |
| **Translations** | Less clearly separated from the original line. | Small subtitle under each original line. |
| **Translation loading** | No progressive priority. | Priorities TTML translations, cached results, then loads near the active line first. |
| **Source labels** | Granularity was unclear. | French labels distinguish syllable, word, line, and unsynchronized text. |

### 🧭 Navigation and artwork — before → after

| Area | 😕 Before | ✅ After |
|---|---|---|
| **Tabs** | Search could remain open behind a tab change. | Tabs close search and remain available in compact mode. |
| **Long press** | Navigation long-press behavior was basic. | Liquid Glass lens, drag, haptics, and a three-dot indicator. |
| **Zoom** | Artwork could zoom. | Zoom removed. |
| **Ambient background** | Flat or less adaptive. | Blurred, colored from the artwork, with brightness adaptation. |
| **Demo content** | Demo collections could appear in Explore and Library. | Fake collections removed. Real genres, albums, history, favorites, and downloads are used. |

---

## 🎉 1.0.0 — THE FIRST COMPLETE BUILD

**Released:** 20 October 2026  
**Version:** `1.0.0` (`versionCode` 1)  
**Status:** 🗄️ Legacy archive — unsupported

### Before → after

| Area | 😕 Before | ✅ After |
|---|---|---|
| **Project** | Design specification and reference screenshots only. | First complete Kotlin + Jetpack Compose Android app. |
| **Interface** | No runnable UI. | Liquid Glass foundation, Home, Explore, Library, search, and a custom full-screen player. |
| **Playback** | No playback stack. | Media3 playback, queue, lyrics, downloads, and settings. |
| **Builds** | No APK. | Four archived variants: standard, `compat`, `fix1`, and `fix2`. |
| **Security** | No public distribution decision. | The 1.0.0 builds contain no session cookie or add-on access token. Their help text contains one obsolete example add-on URL. |

> 🗄️ The original 1.0.0 source snapshot was not preserved. The legacy release contains the four APK variants and an archive marker.

---

## 🧱 INTERMEDIATE BUILDS — 1.2.0 → 2.1.0

These development milestones existed, but their detailed before/after history and source snapshots were not preserved:

- `1.2.0`
- `1.3.0`
- `1.3.1`
- `2.0.0`
- `2.1.0`

Their binaries embedded development-only credentials and are **not published**. They are superseded by **2.3.1**.

---

## 📦 RELEASE STATUS AT A GLANCE

| Version | APK published? | Why |
|---|---|---|
| **2.4.0** | ⏳ Pending | Credentials moved to Supabase Vault; sign-off required before publishing the APK. |
| **2.3.1** | ✅ Yes | Clean public build with no embedded credentials. Superseded by 2.4.0. |
| **2.3.0** | 🚫 No | Embedded Spotify cookie and private add-on credentials. |
| **2.2.0** | 🚫 No | Embedded development credentials. |
| **2.1.0** | 🚫 No | Embedded development credentials. |
| **2.0.0** | 🚫 No | Embedded development credentials. |
| **1.3.1** | 🚫 No | Embedded development credentials. |
| **1.3.0** | 🚫 No | Embedded development credentials. |
| **1.2.0** | 🚫 No | Embedded development credentials. |
| **1.0.0** | ✅ Yes | Legacy archive; no session cookie or access token. |

---

<p align="center">
  <strong>Latest published release:</strong>
  <a href="https://github.com/Tysnnn23/Ketvoice/releases/latest">Ketvoice 2.3.1</a><br>
  <strong>Prepared release:</strong> Ketvoice 2.4.0
</p>
