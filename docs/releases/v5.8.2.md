# Ketvoice 5.8.2

![Ketvoice](https://raw.githubusercontent.com/Tysnnn23/Ketvoice/main/ketvoice-banner.png)

The fix for titles that would not play in 5.8.0 and 5.8.1, and a smoother app.

## Fixed

- **Every title plays again**, downloaded or streamed. Since 5.8.0, a shortcut in the reverb stage made the sound stop with *Reading error · try again* before the first second of each title; 5.8.1 did not remove it. A new test now plays a file through Ketvoice's whole audio chain, so that this cannot come back unnoticed.
- With *Stats info* on (Settings), the player now names the error behind a title that fails.

## Smoother

- While music plays, the app no longer redraws every screen every few seconds to follow the position of the title.
- The large card at the top of Home no longer redraws five times a second while a title plays.
- All of Ketvoice's code — playback, Ketmix, every page — is now compiled when the app is installed, instead of only its start-up path: it runs at full speed from the first second. Every effect is kept.
