# Signing — stable APK signatures

## Why "L'application n'a pas été installée car elle est en conflit avec un package existant"

Android refuses to install an APK over an existing app when the **signing
certificate differs**. A CI run that generates a fresh random debug keystore on
every build produces a **different signature each time**, so no new APK can ever
update the previous one.

The fix is to sign **every** build with **one stable keystore**. That keystore is
stored as a base64 GitHub secret (`KETVOICE_KEYSTORE_B64`) and reused for all CI
runs and local release builds.

## One-time setup (owner)

1. Generate the stable keystore once:
   - Go to **Actions → Generate Signed Keystore → Run workflow**.
   - Open the finished run, copy the block between
     `-----BEGIN BASE64 KEYSTORE-----` and `-----END BASE64 KEYSTORE-----`.
2. Add it as a repository secret:
   - **Settings → Secrets and variables → Actions → New repository secret**
   - Name: `KETVOICE_KEYSTORE_B64` — Value: the base64 blob from step 1.
3. Add the matching passwords/alias secrets (only if you changed the defaults):
   - `KETVOICE_STORE_PASSWORD` (default `ketvoice2026`)
   - `KETVOICE_KEY_ALIAS` (default `ketvoice`)
   - `KETVOICE_KEY_PASSWORD` (default `ketvoice2026`)
4. Delete the keystore artifact from the one-shot run afterwards.

From now on, `android.yml` decodes `KETVOICE_KEYSTORE_B64` and passes it to the
`sideload` signing config, so **all APKs share the same signature**. New
releases will install cleanly over older ones.

## First install after switching signatures

Your phone currently holds a build signed with the *old* (different) key, so it
must be removed **once**:

```
Paramètres → Apps → Ketvoice → Désinstaller
```

Then install the newly-signed APK. Every release after this one updates in place.

> ⚠️ Uninstalling clears local data (listening history, settings).

## Local release builds

```bash
export KETVOICE_KEYSTORE=/abs/path/to/ketvoice-release.jks
export KETVOICE_STORE_PASSWORD=ketvoice2026
export KETVOICE_KEY_ALIAS=ketvoice
export KETVOICE_KEY_PASSWORD=ketvoice2026
./gradlew :app:assembleMobileRelease
```

Without those env vars the build falls back to `~/.android/debug.keystore`
(fine for local debugging, not for distribution).

## Security note

A keystore committed to the repository is a bad practice for public apps.
Prefer the **secret** (`KETVOICE_KEYSTORE_B64`). Keep the `.jks` out of git;
only the base64 secret is stored server-side.
