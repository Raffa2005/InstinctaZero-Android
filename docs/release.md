# Release build

This repository's current release target is the native-shell analysis app
(version 0.4.1). Before producing a release APK, rebuild and verify the two
checked-in browser assets from their retained sources:

```bash
cd web
npm ci
npm run check
cd ..
```

Then build the signed APK with JDK 17 and the Android SDK configured:

Create a local keystore outside version control, then supply its path and secrets as Gradle properties:

```bash
./gradlew \
  -PINSTINCTAZERO_STORE_FILE=/absolute/path/instinctazero-release.keystore \
  -PINSTINCTAZERO_STORE_PASSWORD='…' \
  -PINSTINCTAZERO_KEY_ALIAS=instinctazero \
  -PINSTINCTAZERO_KEY_PASSWORD='…' \
  assembleRelease
```

The app is compiled against the dedicated, path-limited InstinctaZero HTTPS
gateway at `https://rafael-ms-7e34.tail273ae6.ts.net:8443`. This is a public
HTTPS endpoint; the phone does not need the Tailscale app, Tailscale DNS, a VPN
connection, or any manual network setup. The native allow-list accepts only
that exact HTTPS host and port and the pairing/analysis/book routes. Port 8765
remains private and is never embedded in the APK.

The resulting APK is `app/build/outputs/apk/release/app-release.apk`. The
signing key is required for every future update; keep it backed up and never
commit it. Do not put a keystore, passwords, or Gradle signing properties in a
release artifact, issue tracker, or source repository.

Before publishing, at minimum verify:

- versionCode/versionName are the intended 8 / 0.4.1 release values;
- `npm run check`, `testDebugUnitTest`, release lint, and the signed release
  build all pass;
- the APK installs and opens on native Home, while Analysis opens at the
  standard starting position;
- system dark mode does not recolour the board or turn all pieces white;
- PC-side InstinctaZero is already connected/configured for the user's Lichess
  account before a pairing code is created, so active-game fair-play checks are
  available; an unconfigured analysis-only PC is not treated as pairable;
- a fresh PC pairing code entered through the Profile / PC touch keypad
  produces a paired state and no bearer appears in WebView storage, logs, URLs,
  or callbacks;
- Leela streams progressive lines/arrows and cancellation closes its request;
- a local study, selected variation, cursor, orientation, and game context are
  restored after Activity/process recreation;
- Home triggers a PC-owned completed-game sync, shows cached native thumbnails,
  paginates the archive, and opens a selected game by trusted stored ID;
- opening-book moves load and can be played, Masters/Lichess filters send the
  documented endpoint-specific body, and the chart remains blank;
- all tabs and footer/header controls perform their documented action;
- backgrounding the app cancels active native analysis;
- only the fixed HTTPS pairing/study endpoints are reachable, with the WebView
  still using `connect-src 'none'`; and
- the server accepts only the closed `cpu`/`sycl` selector, maps it to its
  code-owned CPU INT8 or exact-SYCL BT4 profile through InstinctaZero's guarded
  shared engine lifecycle, and enforces its fair-play gate. Mobile analysis
  shares the InstinctaZero session and may replace a currently active desktop
  search.

The release may request Android's `INTERNET` permission solely for the native
paired PC gateway. It must not claim phone-side Lichess sign-in or token access:
completed-game synchronization is performed by the paired PC. The evaluation
graph remains intentionally blank.
