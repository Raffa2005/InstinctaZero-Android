# InstinctaZero Android

A pure native Android companion for reviewing completed Lichess games with an
InstinctaZero/Leela backend running on your PC. It intentionally has no game
play, move input, browser view, Stockfish, or live-game analysis path.

## Requirements and pairing

- An InstinctaZero PC server reachable from the phone through Tailscale or
  another private, trusted HTTPS connection. The app rejects plain HTTP.
- Completed games synchronized on the PC. The phone cannot submit arbitrary
  positions or import ongoing games.

On the PC, use the same state database as the running server:

```bash
instinctazero mobile --state-db instinctazero_state.db sync
instinctazero mobile --state-db instinctazero_state.db pair-code
```

The pairing code is single-use and expires after ten minutes. In the Android
app, open **Pair device**, enter the private HTTPS PC URL, pairing code, and a
device name, then tap **Pair securely**. The phone receives a revocable device
credential and encrypts it with Android Keystore. It never receives, stores, or
asks for your Lichess login or token.

Paired devices can be reviewed or revoked from the PC:

```bash
instinctazero mobile --state-db instinctazero_state.db devices
instinctazero mobile --state-db instinctazero_state.db revoke DEVICE_ID
```

## Using the app

- **Games** synchronizes and lists the completed-game archive, newest first.
- **Analysis** provides a native board, move navigation, Leela principal
  variations, evaluation graph, and Masters/Lichess opening-book views.
- **Settings** controls automatic sync/open behavior, arrows, opening-book
  visibility, analysis limits/profile, and pairing state.

An unpaired demo mode lets you evaluate the native board and analysis UI
without connecting to a backend.

## Build

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug
```

Release builds are minified with R8. Signing credentials are intentionally kept
outside this repository; see [`docs/release.md`](docs/release.md).

## License and source

InstinctaZero Android is free software distributed under GPL-3.0-or-later. The
complete preferred source for the APK is this repository, including its Gradle
wrapper and build instructions. See [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)
for the Cburnett/Chessground attribution.
