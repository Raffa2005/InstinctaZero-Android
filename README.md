# InstinctaZero Android

InstinctaZero Android 0.4.7 is a compact, legacy-Lichess-inspired analysis app.
It opens on a native Home screen; Analysis opens at the standard starting
position and provides a legal interactive board, local variation tree, live
Leela lines and arrows, opening-book results, and move navigation. The
evaluation-chart tab is intentionally blank in this release.

The app pairs with one InstinctaZero PC using a short-lived code. It does not
sign into Lichess directly or contain a Lichess token. Instead, pairing signs the
phone into the analysis PC. The selected Lichess account is then independent of
that PC pairing: accounts already authorized in separate InstinctaZero browser
sessions can be switched from Account / PC without re-pairing the phone. The PC
synchronizes only the selected account's completed-game archive. Games have their own native screen and are
loaded in 20-game pages only as the user scrolls; Home never builds or refreshes the
archive. Before creating the pairing code, PC-side InstinctaZero must already be
connected and configured for the user's Lichess account. An unconfigured,
analysis-only PC cannot create a phone pairing code; the PC account configuration
is what enables the active-game fair-play check. The app reaches the PC through
a dedicated, path-limited InstinctaZero HTTPS gateway at
`https://rafael-ms-7e34.tail273ae6.ts.net:8443`. It is a public HTTPS endpoint
despite the hostname: the phone does not need the Tailscale app, Tailscale DNS,
a VPN connection, or any manual network setup.

The native HTTP client tries the other addresses returned for the Funnel host
when an ingress route fails during connection or TLS setup. Ordinary read-only
requests may be retried. Pairing, account selection, archive `/sync`, and live
analysis requests are never replayed after their request body starts; analysis
also remains a single long-lived stream rather than being silently restarted.

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
- The complete local study tree, current position, tab, orientation, and
  completed-game context survive app restarts.
- Playing a displayed Leela move immediately inherits its already-known best
  response arrow and White-perspective value until the child search replaces it.
- Moving forward through the canonical mainline inherits that same cached response
  arrow and value immediately; it does not wait for a new network snapshot.
- Tap a move in the notation, a Leela line, or a book move; use previous/next
  (including press-and-hold) to navigate the local tree.
- Long-press a move where sibling variations exist to promote it to the main
  line or delete that variation without invoking text selection.
- Forward navigation always follows the first, canonical child at an
  intersection. Creating, visiting, or restoring a variation never changes
  that default path.
- The former board-size control is now a distinct Return to mainline button.
  From a variation it jumps to the nearest intersection where that branch
  diverged; the ordinary Forward button then follows the canonical mainline.
- Android Back closes the pairing keypad, drawer, or analysis subpanel first;
  then Profile and Analysis return to Home, and Back from Home exits.
- Menu, settings, board flip, Return to mainline, previous, and next are functional.
  Compact touch-only settings remember CPU/iGPU mode, a discrete node target,
  independent arrow count (1–8), Leela, and arrow visibility. The engine panel scrolls through
  every returned line with visits.
- The tabs show study information, notation, live Leela analysis, the
  intentionally blank evaluation chart, and the live opening book. Book rows
  show move share, game count, and White/draw/Black percentages. Its contextual
  gear selects Masters/Lichess plus optional Lichess speed and rating filters.
- Pairing is kept out of Analysis. Account / PC uses non-editable code slots and
  a custom touch keypad, so it never opens the Android soft keyboard. Disconnect
  forgets the local token immediately and then attempts remote self-revocation.
- When paired, Games asks the PC to sync completed Lichess games, displays only
  one recycled 20-game page at a time, and fetches another page near the end of
  scrolling. Already imported pages appear immediately while a first full sync
  continues in the background. Switching accounts cooperatively hands the sync
  over to the selected account instead of blocking on a global archive job. Its
  legacy-style rows use the exact bundled Cburnett pieces, speed
  and variant symbols, and green/red/draw result text. No ongoing
  game is exposed, and Home performs no archive work.
- Changing accounts detaches any completed-game context belonging to the former
  account while preserving an ordinary local study. A stale archived position
  also falls back to a fresh local study if the server no longer exposes it.
- The packaged board is warmed after Home's first frame, and its piece sprites
  are revealed atomically, avoiding both launch contention and partial boards.
- MainActivity is portrait-only, preventing the unsupported landscape rotation
  path while retaining normal pause/resume and process-recreation persistence.

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
