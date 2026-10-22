# Ketvoice on Quest 3 / Quest 3S

Install **Ketvoice-2.7.1-Quest.apk** from the [2.7.1 Quest release](https://github.com/Tysnnn23/Ketvoice/releases/tag/v2.7.1). The phone/tablet APK remains **2.7.0**, unchanged.

This is a **landscape-only 2D Horizon OS window with the mobile visual style**, not an immersive 3D app. Original artwork/Canvas, icon controls, floating navigation, and lyric animations are reused. Opening lyrics puts the artwork, title, artist, and playback controls on the **left**, with synchronized lyrics on the **right**. The queue uses the same right-hand pane. There is no oversized text-button dashboard or portrait phone frame.

## Installation

1. Enable developer mode for your headset through Meta's developer setup.
2. Connect the headset to your computer and approve USB debugging inside the headset.
3. Install the Quest APK with Meta Quest Developer Hub, SideQuest, or ADB:

   ```text
   adb install -r Ketvoice-2.7.1-Quest.apk
   ```

   If several devices are connected, specify the **headset's** serial with `adb -s <headset-serial> install -r ...`.
4. Find **Ketvoice Quest** in the headset's installed/Unknown Sources apps.

The mobile and Quest builds share the package name and signing certificate, rather than installing as two independent apps. Use the mobile APK on your phone and the Quest APK on your headset. Normal Android version-code/downgrade checks still apply. Do not uninstall to update unless you intend to remove local app data.

## Window and features

- Default window: **1280 × 800 dp**, in landscape. Both main and YouTube-login activities request landscape orientation.
- Resize support: minimum requested size **960 × 600 dp**, with Horizon OS aspect-ratio locking enabled. The portrait letterbox used by 2.7.0 has been removed; the player uses the full landscape window. Horizon OS may constrain the requested dimensions.
- In lyrics/queue mode, the left-hand artwork/title/controls remain visible. Quest does not auto-expand lyrics to hide them after two seconds. Artwork mode keeps a large cover on the left and normal mobile controls on the right.
- Liquid Glass and existing animations remain enabled by default. Android 12L uses the existing blur/tint fallback; newer Android versions can render the shader/refraction effects.
- Optional lyric sizing and foreground playback keep-awake are in **Settings → Quest comfort**. Defaults retain normal mobile lyric sizing.
- The normal track **⋯ menu → Timer** can pause playback after 15, 30, or 60 minutes, or cancel the timer. It continues when the panel closes while the playback process remains alive. It is not persisted across process death/restarts and does not acquire a wake lock.
- Playback continues through the existing Media3 media session when the panel is minimized, subject to Horizon OS audio focus and lifecycle rules. This app does not force other games to mix their audio.
- The system translates controller/hand selections into Android pointer events. No raw hand, gaze, or camera tracking data is requested. Small original controls are intentionally preserved, with subtle hover/focus feedback on supported controls.

## Compatibility and validation limits

- Intended targets: **Quest 3 / 3S**, updated Horizon OS. The APK declares `quest3|quest3s`, optional touchscreen/faketouch, Android API 32 minimum, target 34, and arm64 native libraries. x86_64 is included for emulator QA.
- No Google Play Services dependency is added. No immersive head-tracking feature is required.
- Builds, unit tests, APK inspection, and Android emulator installation/window/UI checks are completed. **No physical Quest 3/3S was available**: hand/controller behavior, headset audio mixing, panel resizing, refresh rate, and thermal performance must still be checked on hardware. This is not Meta Store certification or a guaranteed 90/120 FPS claim.
- A portrait-shaped Android emulator display request was checked separately: Android letterboxes the app into a landscape activity window rather than making the app portrait. This is not a claim that the headset's entire display or shell is rotated.
- Publication login must be completed from the headset's browser/mail session, or by pasting a fresh original email link. A link consumed on a phone does not transfer its session to the headset.

Implementation references: [porting an Android app](https://developers.meta.com/vr/documentation/android-apps/port-an-existing-app), [Horizon OS AOSP features](https://developers.meta.com/horizon/resources/2d-features).
