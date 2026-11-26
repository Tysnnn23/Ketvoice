# Ketvoice TV 1.0.0

![Ketvoice](https://raw.githubusercontent.com/Tysnnn23/Ketvoice/main/ketvoice-banner.png)

The first version of Ketvoice for Android TV and Google TV: your music on the big screen, made to be used from the sofa with the remote.

## Made for the remote

- Every screen is laid out for a TV and moves with the directional pad: rows of albums, playlists and artists, a clear focus on what is selected, and Back always takes you where you came from.
- A slim side menu for Search, Home, Library, Settings and the song playing, which opens with its names when you move onto it.
- Home brings back what you played recently, your library, mixes made for you, new releases from your artists, your artists and what is popular. A TV that is not signed in yet already shows the catalogue.
- Library, album, artist and playlist pages, with Play, Shuffle and Start radio. Press the remote's Menu button (or hold OK) on a song to play it next, add it to the queue, like it, start a radio, download it or open its artist.
- Play, pause, next and previous work from the remote's media buttons, and music keeps playing while you browse.

## Sign in with your phone

- Scan the QR code on the TV with your phone, or type the 8-character code on the website, and your Ketvoice account is signed in: no typing an email or a password with the remote.
- Your library follows you: favourites, playlists, artists, recently played and your listening statistics are the same on the TV, your phone and your computer, and changes made on one device show up on the others.

## Search

- Search songs, albums and artists with an on-screen keyboard made for the arrows, or with your voice when the TV has a microphone. Your recent searches are a click away.

## The player

- A full-screen player with the animated Canvas filling the screen, or the artwork's colours behind the cover.
- Synced lyrics that light up word by word, large enough to read from across the room, or the queue beside the cover.
- The controls fade away after a few seconds so the music takes the whole screen; any button brings them back.

## Ketmix

- Ketmix blends one song into the next like a DJ, with the same transitions as on the phone, and can learn from your ratings if you turn that on.
- Ketvoice TV checks how powerful your TV is. On a modest box it uses a lighter Ketmix analysis and a lighter look, so everything stays smooth.

## Install

Ketvoice TV is a separate app with its own updates. It installs beside the phone app on another device and works on Android TV 9 and later and on Google TV (TVs, the Chromecast with Google TV and other Android TV boxes).

1. **Allow apps from outside the Play Store.** On Google TV, open Settings > System > About and select "Android TV OS build" seven times to turn on developer mode, then open Settings > Apps > Security & restrictions > Unknown sources. On Android TV, open Settings > Device Preferences > Security & restrictions > Unknown sources. Allow the app you will install Ketvoice TV with (for example Downloader).
2. **With the Downloader app.** Install Downloader by AFTVnews from the Play Store on the TV, open it and enter `https://github.com/Tysnnn23/Ketvoice/releases/download/tv-v1.0.0/Ketvoice-TV-1.0.0.apk`, then choose Install.
3. **Or with adb from a computer.** Turn on developer mode as above, then Settings > System > Developer options > USB debugging (or Network debugging). On the computer run `adb connect <the TV's IP address>` and `adb install Ketvoice-TV-1.0.0.apk`.
4. Open Ketvoice from the apps row of the TV's home screen and scan the QR code with your phone to sign in.

`SHA256SUMS.txt` lists the SHA-256 of `Ketvoice-TV-1.0.0.apk`: compare it with `sha256sum Ketvoice-TV-1.0.0.apk` before installing with adb. Later versions install over this one and keep your settings, and Ketvoice TV offers them itself.
