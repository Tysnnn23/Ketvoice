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
   - Download the run's `ketvoice-release-keystore` artifact (kept for one day)
     and open `ketvoice-release.jks.b64`. The keystore is no longer printed in
     the job log, which anyone with read access can see.
2. Add it as a repository secret:
   - **Settings → Secrets and variables → Actions → New repository secret**
   - Name: `KETVOICE_KEYSTORE_B64` — Value: the content of `ketvoice-release.jks.b64`.
3. Add the matching passwords/alias secrets (only if you changed the defaults):
   - `KETVOICE_STORE_PASSWORD` (default `ketvoice2026`)
   - `KETVOICE_KEY_ALIAS` (default `ketvoice`)
   - `KETVOICE_KEY_PASSWORD` (default `ketvoice2026`)
4. Delete the keystore artifact from the one-shot run afterwards.

From now on, `android.yml` decodes `KETVOICE_KEYSTORE_B64` and passes it to the
`sideload` signing config, so **all APKs share the same signature**. New
releases will install cleanly over older ones.

The secret may be pasted with line wrapping: the workflow strips whitespace
before `base64 -d` and `app/build.gradle.kts` strips it too, because the strict
Base64 decoders abort on interior newlines.

## Current identity (pinned)

Every APK published up to 4.3.0 is signed with the **well-known Android debug
key** shipped with the SDK (`CN=Android Debug`, public password `android`).
Its SHA-256 certificate digest, pinned in `tools/verify_apk.py`, in the
`android.yml` APK verification step and in the `KETVOICE_CERT_SHA256` repository
variable, is:

```
ae25a19cee8ac781f30de4c933541257fdd46a6ea7fca6f7ab021f68221f37a9
```

Consequences, stated plainly:

- The key is **not secret**. Anybody can sign an APK that Android accepts as an
  update for Ketvoice. There is no protection against a malicious update and no
  Play Store upload possible (Play rejects debug-signed bundles).
- Keeping it is what allows in-place updates for every installed user today.

### CI preflight (added after the 4.3.0 red build)

The tag build `42709180867` failed inside `:app:packageQuestRelease` with
`DerInputStream.getLength(): lengthTag=109, too big`, because
`KETVOICE_KEYSTORE_B64` held a blob that was not a keystore at all. Two steps now
catch this before Gradle runs:

1. **Validate signing keystore** — `keytool -list` on the decoded file, then
   compares `sha256sum` of `keytool -exportcert` against the pin above. The
   digest is identical to what `apksigner` prints, but locale independent.
2. **Verify APK signatures** — runs `apksigner verify --print-certs` on every
   built APK and fails the job when a digest differs, so a wrong-identity APK can
   never reach the artifact upload or a release.

To re-upload the correct material (this is what repaired the 4.3.0 failure):

```bash
python tools/push_secrets.py Tysnnn23/Ketvoice   # GITHUB_TOKEN with `repo` + `workflow`
```

Both `tools/push_secrets.py` and the one-off repair script seal the value with a
libsodium sealed box; on a host without `libcrypto.so.3` use PyNaCl
(`pip install pynacl`) instead of `tools/sodium_seal.py`.

## Moving to a real release key (needs explicit approval)

Rotating to a private key is the correct long-term fix, but it changes the
signature identity, so **every installed user must uninstall once** and loses
local data. The migration, when approved:

1. Generate a private keystore (never commit it), upload it as the secrets above,
   set `KETVOICE_KEY_ALIAS`/passwords to match.
2. Update the pin in `tools/verify_apk.py`, the `KETVOICE_CERT_SHA256` variable
   and the comment above in the same commit.
3. Ship it as an announced major version with the uninstall step documented,
   and keep the previous APK available for users who refuse to lose their data.

## First install after switching signatures

Applies only **after** an approved key rotation: the phone still holds a build
signed with the *previous* key, so it must be removed **once**:

```
Paramètres → Apps → Ketvoice → Désinstaller
```

Then install the newly-signed APK. Every release after this one updates in place.

> ⚠️ Uninstalling clears local data (listening history, settings).

Today the CI key and the published key are the same (`ae25a19c…`), so no
uninstall is needed: 4.3.0 and later CI builds install in place.

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
