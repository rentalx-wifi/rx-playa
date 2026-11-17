# RX Playa

**Music without subscriptions and without ads.** A fork of
[BitChord](https://github.com/kushagrasinghx/BitChord).

[Русская версия](README.md)

YouTube Music · SoundCloud · Spotify · Yandex Music · VK · local files
One list, one search. Which source actually plays a track is decided by the app.

---

## What it does

▸ **RX Wave** - a station built from your likes. Your own tracks seed it, each
seed's radio supplies what is similar, and a strict filter follows: artists you
already know come first, never two rows by one artist in a row, no stranger's
slowed or cover re-uploads, and nothing from the global chart.

▸ **Source rescue** - YouTube will not serve a track: region, age gate, pulled
upload. It is then looked for on every other enabled source - the same artist, or
a runtime matching to the second. Nothing is swapped silently: the app tells you
where it plays from.

▸ **Appearance** - Material 3 ported from Pixel Music: palette, Montserrat,
8/16/24 corners, a floating icon dock, settings as tonal cards. Wallpaper colours
(Material You) or your own accent.

▸ **Sound** - Automix and crossfade, bit-perfect, USB DAC, PCM selection,
equalizer, Dolby Atmos, spatial audio, loudness normalisation, skip silence,
speed without pitch.

▸ **The rest** - lyrics with translation and line highlighting, downloads and
offline playback, Discord RPC, Listen Together (a shared queue), canvas
artwork, a player with queue and lyrics on one screen.

▸ **Account and import** - likes, playlists and history come from the signed-in
service; other services' playlists import by link; search can run across every
source at once or across one.

---

## Beta

Stated plainly, so nothing is a surprise:

1. **The YouTube throttling bypass (zapret).** Root is required. The script was
   rewritten from scratch - `su -c` used to swallow its arguments, so `nfqws`
   never started once - but it still refuses to run on some ROMs. That is
   expected, not a bug unique to you.
2. **Widgets.** Being ported from Pixel Music, still in progress. The old ones
   keep working: square, wide and pill.
3. **RX Wave sometimes misses.** The selection is still being tuned: the filter
   removes clear junk but cannot protect against an odd radio answer. Will be
   fixed in the next patch.

---

## Install

1. Open [Releases](../../releases) and download the latest APK.
2. Allow installing from this source, install.

`arm64-v8a` · Android 8.0+ (`minSdk 26`) · package `com.dev.bitchord`

The signing key has not changed since the first builds, so a new version installs
over the previous one: settings, likes and downloads stay.

> APKs are uploaded to Releases by hand, so if the latest release looks older,
> the build simply is not published yet. Fresh builds also appear in CI
> (Actions → build).

---

## Build

JDK 17 and an Android SDK with `compileSdk 37` and build-tools 37 are required.

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk

# debug
./gradlew :app:assembleDevDebug

# release, arm64
./gradlew :app:assembleDevRelease \
  -PbitchordAbi=arm64-v8a \
  -Pbitchord.bundleWear=false
```

For a signed build, put `keystore.properties` next to `app/` following
[`keystore.properties.example`](keystore.properties.example) and pass the signing
init script:

```bash
./gradlew :app:assembleDevRelease -PbitchordAbi=arm64-v8a \
  --init-script build/rx-local-signing.init.gradle
```

`keystore.properties`, `*.jks`, `local.properties` and `build/` never enter the
repository - they are in `.gitignore`.

---

## Documentation

- [`docs/ACCOUNT_INTEGRATIONS.md`](docs/ACCOUNT_INTEGRATIONS.md) - Spotify, SoundCloud, VK
- [`docs/YANDEX_MUSIC.md`](docs/YANDEX_MUSIC.md) - Yandex Music
- [`docs/LYRICS_TRANSLATION.md`](docs/LYRICS_TRANSLATION.md) - lyrics translation

---

## Bugs and requests

Use [Issues](../../issues), or the comments under the channel post. A useful bug
report has the phone model, the ROM, the build version (Settings → About) and
what you did just before.

---

## Credits

- [BitChord](https://github.com/kushagrasinghx/BitChord) - the base this fork
  grew from.
- [Pixel Music](https://github.com/ianshulyadav/PixelMusic) - the appearance
  ported here: palette, typography, shapes.
- Montserrat - the font, under the OFL; its licence sits next to the font file.

## Licence

GPL-3.0 - see [`LICENSE`](LICENSE).

## Disclaimer

An unofficial client. Not affiliated with YouTube, Spotify, SoundCloud, VK or
Yandex. No subscriptions are needed; you use your own account. The project does
not store or distribute music.
