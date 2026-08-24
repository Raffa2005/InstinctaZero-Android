# InstinctaZero Android

InstinctaZero Android 0.3 is a compact, legacy-Lichess-inspired analysis board.
It opens at the standard starting position and provides a legal interactive
board, local variation tree, live Leela lines and arrows, opening-book results,
and move navigation. The evaluation-chart tab is intentionally blank in this
release.

The app pairs with one InstinctaZero PC using a short-lived code. It does not
sign into Lichess, fetch games, synchronize an account, or contain a Lichess
token. Before creating that pairing code, PC-side InstinctaZero must already be
connected and configured for the user's Lichess account. An unconfigured,
analysis-only PC cannot create a phone pairing code; the PC account configuration
is what enables the active-game fair-play check. The app reaches the PC through
a dedicated, path-limited InstinctaZero HTTPS gateway at
`https://rafael-ms-7e34.tail273ae6.ts.net:8443`. It is a public HTTPS endpoint
despite the hostname: the phone does not need the Tailscale app, Tailscale DNS,
a VPN connection, or any manual network setup.

## Current scope

The board uses the actual Chessground implementation from the 2018 legacy
Lichess Android client, rather than a replacement board implementation. The
local controller uses `chess.js` for legal moves, FEN, SAN, castling,
en-passant, and promotion.

The WebView still loads only APK-packaged files and is forbidden from making
network requests. A narrow native bridge performs pairing, Leela, and book
requests. The revocable bearer token is held in Android Keystore-backed
encrypted storage and is never exposed to JavaScript. The native client accepts
only the fixed HTTPS host and the pairing/study API routes; cookies, downloads,
pop-ups, external navigation, cleartext traffic, and arbitrary WebView network
access remain blocked.

Leela uses the server-selected `mobile-cpu-int8` BT4 profile. It is an
approximate CPU-only profile and never starts or uses a SYCL engine. Mobile and
desktop analysis share the PC's InstinctaZero analysis session, however, so a
phone request can replace a currently active desktop search. The phone cannot
select an executable, backend, engine profile, arbitrary FEN, or server route.
The service's fair-play gate refuses analysis while the configured Lichess
account has an ongoing game.

## Controls

- Tap or drag a piece to make a legal move.
- Tap a move in the notation, a Leela line, or a book move; use previous/next
  (including press-and-hold) to navigate the local tree.
- The back button first closes an open panel view or promotion picker; otherwise
  it exits the Android activity. The checkerboard icon opens Appearance inside
  the full-width analysis panel.
- Menu, settings, board flip, board size, previous, and next are functional.
  Settings remember nodes, independent arrow count (1–8), Leela, and arrow
  visibility. The engine panel scrolls through every returned line with visits.
- The tabs show study information, notation, live Leela analysis, the
  intentionally blank evaluation chart, and the live opening book. Book rows
  compactly show move share, game count, and White/draw/Black percentages.
- Pairing code entry and connection/error status are shown inside Settings.

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

./gradlew testDebugUnitTest lintDebug assembleDebug
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
