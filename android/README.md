# AniBox for Android (step 1: the phone at home)

The phone app of AniBox. There is no server: AniBox on the Windows PC writes an **AniBox Sync** folder onto
one of the Shield's drives (PC: Settings → Phone app). This app reads that folder over the Shield's SMB share,
plays the episodes straight from the Shield's shares with **mpv**, and writes its own watch progress next to it,
which AniBox on the PC imports on its next sync.

What it does now:

- **Setup**: scan the PC's QR code (CameraX + ZXing, no Google Play services; the camera is asked for only
  then), paste the code's text, or type host / share / folder (`\\MEDIABOX\Drive A\Anime` works too). Then the
  share's user name and password, tested before anything is saved.
- **Finding the Shield** by its Windows name: the address saved on the phone → the address the PC found
  (QR / library.json `address`) → DNS (also `<name>.local`) → a NetBIOS name query on the Wi-Fi network →
  ask for the address (kept per host, editable in Settings). Each candidate is tried on port 445.
- **Library**: Home (Continue watching, then the libraries), Library (Anime with Series / Movies / OVAs /
  Specials / ONAs / Music videos, Series, Movies; search; sort by title, year, recently watched), show page,
  Settings. A copy of `library.json` is kept, so the library opens at once and without the Shield. Posters
  are fetched from `posters/<name>` when shown and kept in a cache (names no longer listed are dropped).
- **Player** (libmpv): full screen landscape, resume 10 s before the saved place, watched at 95 %, next
  episode after a 10 s countdown, audio and subtitle pickers, double-tap to seek, the PC's languages (or your
  own) as `alang`/`slang` + subtitle mode like AniBox on the PC, subtitle files next to the episode
  (`.srt .ass .ssa .vtt .sup`), pause on headphones unplugged and on lost audio focus.
- **Progress**: kept on the phone; `progress/pc.jsonl` (profile "main") and other devices' journals are
  merged (newer wins, syncing never removes a watched mark: only your own "Mark as not watched" does). The
  phone's own journal `progress/android-<model>-<4 hex>.jsonl` is append-only, in exactly the PC's line format;
  lines wait in a queue while the Shield can't be reached, and the journal is rewritten with one line per
  episode once it passes ~512 KB (temp file + rename). Nothing else in the sync folder is ever written.

Not in this step (later steps): away from home (Tailscale needs nothing extra: same addresses), downloads,
TV / CastApp, MyAnimeList/AniList sync, profiles, the Shield app.

## Build

Needs JDK 17+ and the Android SDK (`platforms;android-37.1`, `build-tools;37.0.0`) with a `local.properties`
holding `sdk.dir=…` (not committed). From this folder:

```
./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx2g -Pkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest
./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx3g :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx3g :app:assembleRelease   # app/build/outputs/apk/release/app-release.apk
./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx3g :app:lintDebug         # must have no errors
```

Screenshots (Robolectric + Roborazzi, opt-in): add `-Pscreenshots -Dorg.gradle.jvmargs=-Xmx3g` to the test
command; the PNGs (made-up data) go to `app/build/outputs/roborazzi/`.

The APKs hold arm64-v8a and armeabi-v7a only (phones). Debug builds are `io.github.patschgoo.anibox.debug`,
named "AniBox (test)", so they sit beside the real app.

## Signing

- **Test key (public on purpose):** `app/anibox-test.keystore` (PKCS12, alias `anibox-test`, store and key
  password `anibox-test`, certificate SHA-256 `9e5a5991…1bcf8a`). It signs every debug build and is the
  release fallback, so builds can be installed over each other. Anyone can sign with it: don't publish with it.
- **Your own key:** set (environment variables, or gradle properties in `~/.gradle/gradle.properties`)
  `ANIBOX_KEYSTORE_FILE` or `ANIBOX_KEYSTORE_BASE64`, `ANIBOX_KEYSTORE_PASSWORD`, `ANIBOX_KEY_ALIAS` and
  optionally `ANIBOX_KEY_PASSWORD` (defaults to the store password). Release builds then use it. Switching
  keys later means uninstalling the app once (Android refuses an update signed by another key).

## Privacy

No account, no analytics, no crash reporter, no Google Play services. The app only talks to the Shield's
shares and to its own player server on `127.0.0.1` (random port, random token, loopback only). The shares'
passwords are encrypted with an Android Keystore key (`signin.json`), left out of Android's backups and
device transfers, never logged and never written to the sync folder.

## Licence

This app is **GPL-3.0** because it links libmpv (`dev.jdtech.mpv:libmpv` 1.1.0: mpv + FFmpeg,
GPL-3.0-or-later). Included: smbj (Apache-2.0), Bouncy Castle (MIT), ZXing core (Apache-2.0), AndroidX /
Compose / CameraX / Kotlin (Apache-2.0), Sora and Inter (SIL OFL 1.1; texts in `app/src/main/assets/licenses/`).
Settings → About shows them. Parts of the code come from CastApp (same owner, GPL-3.0): SMB access, the
Keystore encryption, natural sort order, the byte-range server.

## Not tested yet

Everything here is checked with JVM tests only (no emulator in the build environment). Not tried yet: a real
phone, the real Shield shares (smbj against the Shield's SMB server, appending and renaming over SMB), the
NetBIOS lookup on a real Wi-Fi network, mpv playback itself (surface, hardware decoding, Hi10P falling back to
software, ASS rendering with the bundled font, external subtitles over the local server), the QR scanner with a
real camera, audio focus / headphones, and the release build's R8 rules at run time.
