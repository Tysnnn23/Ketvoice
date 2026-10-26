# iPhone parity and validation

- Keep this native SwiftUI/AVFoundation port aligned with Android fixes where the corresponding feature exists. Record missing counterparts in README.md instead of adding non-functional controls or claiming parity.
- Use native NavigationStack paths and dismiss only the top screen/sheet. Regression-check that Settings, playlists and the player return to their actual caller.
- Android is the localization/public catalog configuration source of truth: run `python IOS/tools/sync_android_resources.py` after relevant changes, then its `--check` mode. Do not hand-edit generated Resources.
- French default, eleven interface language catalogs, native Liquid Glass, Canvas controls, reduced-motion and accessibility support must be preserved.
- Store Apple credentials and signing profiles only outside Git. An unsigned IPA is a re-signing input, not an installable release.
- Read README.md and validation.json before reporting status. Host Python checks, Swift core tests, Xcode compilation, UI rendering and physical iPhone validation are different checks; never substitute one for another.
