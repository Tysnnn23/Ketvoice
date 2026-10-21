# Security Policy

## Supported version

| Version | Supported |
| --- | --- |
| 2.3.1 | Yes |
| 2.3.0 and earlier | No |

Version 2.3.1 is the first public release with no embedded credentials.

## Reporting a vulnerability

Please use GitHub’s private vulnerability reporting / Security Advisories for this repository. Do not open a public issue containing:

- Spotify `sp_dc` cookies
- YouTube `SAPISID` cookies
- Private add-on URLs or tokens
- Personal access tokens
- Keystore files or passwords

Include the affected version, Android version, reproduction steps, and the impact. Remove any personal data from logs before attaching them.

## Credentials in this project

No credentials, cookies, API tokens, or private add-on endpoints are committed to this repository or bundled in the 2.3.1 APK.

Runtime credentials entered by the user are kept on the device:

- The YouTube Music web session is stored with Android Keystore encryption.
- The Spotify Canvas `sp_dc` value and custom source URLs are app settings stored locally.
- The app disables Android backup (`android:allowBackup="false"`).

Clearing app data or uninstalling the app removes the local session and settings. Logging out of YouTube Music removes the app session but does not revoke the Google account session on the web.

## Historical binaries

Development builds from 1.2.0 through 2.3.0 contained a Spotify session cookie and private add-on endpoints in their defaults. They are intentionally not published. Do not redistribute copies of those APKs.

## APK integrity

Release APKs are debug-signed. The signing certificate is a development certificate, not a production identity. Always compare the downloaded APK’s SHA-256 checksum with the value in the release notes.

## Scope

This is a personal, unofficial client. Third-party services can change their APIs or terms at any time. Security reports about YouTube, Spotify, Google, or other providers should be sent to those providers, not to this project.
