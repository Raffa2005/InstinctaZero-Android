# InstinctaZero Android

InstinctaZero Android 0.4.1 is a compact, legacy-Lichess-inspired analysis app.
It opens on a native Home screen; Analysis opens at the standard starting
position and provides a legal interactive board, local variation tree, live
Leela lines and arrows, opening-book results, and move navigation. The
evaluation-chart tab is intentionally blank in this release.

The app pairs with one InstinctaZero PC using a short-lived code. It does not
sign into Lichess or contain a Lichess token. Instead, the paired PC synchronizes
the user's completed-game archive and the native Home screen caches and displays
that archive. Before creating the pairing code, PC-side InstinctaZero must already be
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

Leela offers two closed, server-owned choices: `CPU · safe` uses the approximate
oneDNN INT8 CPU profile, while `iGPU · exact` uses the normal `exact-sycl` BT4
profile. CPU is the mobile default so another iGPU workload cannot collide with
it. Mobile and desktop analysis still share the PC's guarded InstinctaZero
analysis session, so a phone request can replace a currently active desktop
search. The phone cannot select an executable, weights, arbitrary backend
options, FEN, or server route.
The service's fair-play gate refuses analysis while the configured Lichess
account has an ongoing game.

## Controls

- Tap or drag a piece to make a legal move.
- The complete local study tree, selected continuation, current position, tab,
  orientation, board size, and completed-game context survive app restarts.
- Tap a move in the notation, a Leela line, or a book move; use previous/next
  (including press-and-hold) to navigate the local tree.
- Long-press a move where sibling variations exist to promote it to the main
  line or delete that variation without invoking text selection.
- Android Back closes the pairing keypad, drawer, or analysis subpanel first;
  then Profile and Analysis return to Home, and Back from Home exits.
- Menu, settings, board flip, board size, previous, and next are functional.
  Compact touch-only settings remember CPU/iGPU mode, a discrete node target,
  independent arrow count (1–8), Leela, and arrow visibility. The engine panel scrolls through
  every returned line with visits.
- The tabs show study information, notation, live Leela analysis, the
  intentionally blank evaluation chart, and the live opening book. Book rows
  show move share, game count, and White/draw/Black percentages. Its contextual
  gear selects Masters/Lichess plus optional Lichess speed and rating filters.
- Pairing is kept out of Analysis. Profile / PC uses non-editable code slots and
  a custom touch keypad, so it never opens the Android soft keyboard. Disconnect
  forgets the local token immediately and then attempts remote self-revocation.
- When paired, Home asks the PC to sync completed Lichess games, renders the
  locally cached list immediately on later launches, and opens analyzable games
  on the trusted stored starting position. No ongoing game is exposed.

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
