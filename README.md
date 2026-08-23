# InstinctaZero Android

InstinctaZero Android 0.2 is an **offline local analysis board**. It opens
directly to a compact, legacy-Lichess-inspired analysis surface: a legal,
interactive board, move navigation, orientation and board-size controls, and
small notation/book/lines/graph tabs populated by a static demonstration
position.

This release deliberately has no account connection, pairing, game archive,
synchronisation, PC service, remote engine, opening-book service, or Leela
request path. It declares no `INTERNET` permission. Everything rendered by the
app is packaged in the APK and loaded from an Android `WebViewAssetLoader`; the
WebView is configured to block network, file, content, mixed-content, cookies,
pop-ups, and external navigation.

## Current scope

The board uses the actual Chessground implementation from the 2018 legacy
Lichess Android client, rather than a replacement board implementation. The
local controller uses `chess.js` for legal moves, FEN, SAN, castling,
en-passant, and promotion.

The engine rows, opening-book panel, and graph are visual/static demo content
for this UI-rebuild milestone. They do **not** run Leela, query a server, or
represent live analysis. Account and completed-game integration are deferred
until the offline board experience is accepted.

## Controls

- Tap or drag a piece to make a legal move.
- Tap a move in the notation or use the previous/next footer controls to
  navigate the local line.
- Use the footer controls for menu, settings, orientation, board size,
  previous, and next. The row of small tabs switches the visible legacy-style
  analysis panel.

## Build from source

Requirements: JDK 17, Android SDK (compile SDK 35), Node.js with npm for the
checked-in browser bundles.

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk

cd web
npm ci
npm run check
cd ..

./gradlew test assembleDebug
```

`npm run check` type-checks and recreates/verifies both generated browser
bundles. `legacy-chessground.js` is generated from the retained source snapshot
in `web-src/legacy-chessground/`; `chess-rules.js` is generated from the pinned
`chess.js` dependency. The remaining HTML, CSS, SVGs, fonts, and piece assets
are source-controlled under `app/src/main/assets/analysis/`.

For a release build, use the external signing properties described in
[`docs/release.md`](docs/release.md). Signing credentials and keystores are
never stored in this repository.

## Licensing and provenance

InstinctaZero Android is GPL-3.0-or-later. The complete preferred source for
the APK includes the Android project, the vendor source snapshot, locked npm
dependencies, and browser build scripts.

The legacy Chessground source, board/piece artwork, typefaces, and `chess.js`
have their respective notices in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)
and `app/src/main/assets/licenses/`. See
[`web-src/legacy-chessground/PROVENANCE.md`](web-src/legacy-chessground/PROVENANCE.md)
and [`web-src/chess-rules-PROVENANCE.md`](web-src/chess-rules-PROVENANCE.md) for
the exact upstream revisions and rebuild procedure.
