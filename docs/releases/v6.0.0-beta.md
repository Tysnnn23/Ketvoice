# Ketvoice 6.0.0 Beta

![Ketvoice](https://raw.githubusercontent.com/Tysnnn23/Ketvoice/main/ketvoice-banner.png)

The first beta of Ketvoice 6: a far better voice separation for Ketmix. The 6.0 redesign of the interface comes in the next beta.

## Ketmix

- **A high-quality voice separation model** (Settings → Ketmix → *High-quality model*, an optional 33 MB download, UVR MDX-Net). Measured on MUSDB18-HQ, the standard test set with isolated stems, the instrumentals Ketmix keeps come out about 3 dB cleaner and the voices about 3.5 dB cleaner than with the quick model: less voice left in the instrumental when a song continues without its singer, cleaner voices over the other song's music.
- **Separation quality** setting:
  - *Automatic* (default): the quick model on battery, the high-quality one while the phone charges; songs already separated are then separated again, better, once.
  - *Always the best*: the high-quality model all the time; it is about ten times slower, so on battery Ketmix prepares one song ahead instead of three.
  - *Always quick*: the least battery.
- The quick model stays available. Each song remembers which model separated it, and is never separated twice by the same one.

## Install

Download `Ketvoice-6.0.0-beta.apk` (phone) or `Ketvoice-6.0.0-beta-Quest.apk` (Meta Quest) below and install it over your current version: your library, settings and downloads stay.
