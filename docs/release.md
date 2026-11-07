# Release build

This repository's current release target is the native-shell analysis app
(version 0.8.16 / code 44), adding only engine-informed destination input and
opening-explorer castling fixes to the restored v0.8.15 app. The offline click-chess input and drag-back correction
are described in [input behavior and validation](click-chess-preview.md).
Ship the minified, production-key-signed `release` APK, not the former development
preview (which installed separately and had unshrunk dependencies).

## v0.8.16 small input fixes

- Destination clicks prefer available visits, then policy, for the exact current
  FEN and selected engine backend. A bounded 24-position session cache stores only
  legal-move statistics, including zero-visit policy rows not eligible for display.
  Provisional/inherited data and stale request IDs cannot populate it. Different
  turns, castling rights, en-passant squares and move counters do not share entries.
  Without usable evidence for the tapped destination, the unchanged trained model
  runs locally. No extra requests, waits or persisted data are introduced.
- A correction cycle freezes its candidate order until normal input/navigation
  clears it. Drag-back still restores the complete pre-move state, removes only a
  leaf and preserves existing continuations. Previous remains ordinary navigation;
  it is not the withdrawn v0.8.14 correction shortcut.
- Explorer castling uses king-to-rook UCI, which previously reached the board's
  standard-UCI move handler unchanged and was rejected. Convert it to the king's
  destination only when the current legal move list contains that castle. Cover
  kingside/queenside, both colours, blocked/through-check cases, canonical history
  and ordinary moves. No new Chess960 support is implied.
- UI, navigation, import, privacy, saved-state formats, pairing, engine tuning,
  gateway/security settings and backend are unchanged. Install over the existing
  app without uninstalling, clearing data, re-pairing or redownloading books.

Run `move-input.test.mjs`, `click-chess.test.mjs`, `input-book-ui.mjs` and all eight
baseline packaged phone suites. The focused touch test covers cached/current engine
visits, policy-only and final snapshots, stale callbacks, fallback, drag-back cycling
and unchanged Previous navigation. Run native tests/lint, verify production signing
and code44 > 43, then verify the public download. No USB phone testing.

## v0.8.15 restoration (historical)

All production app sources and browser assets match `012c253` (v0.8.13).
Only the version identifiers, release notes and rollback verification differ.
The rejected v0.8.14 work remains recoverable in its Git commits and release tag.
Install this higher-code update over v0.8.14; do not uninstall or clear storage.
It retains the production application ID and signing key. The saved-board schema,
pairing preferences, repertoire stores and game data formats are unchanged; no
migration or reset runs. Offline ranking and drag-back cycling, the previous menu,
mainline navigation and portrait lock are restored. Previous move again performs
ordinary navigation, not the rejected guess-correction shortcut.

Before installing, export any wanted edits in **v0.8.14 temporary game tabs**.
Those tabs were session-only and an app update closes their process. They cannot
be recovered by the restored UI unless exported first. Saved Analysis, existing
PGN files, games, books and pairing are not cleared.

Verify the restored code can read databases actually written by v0.8.14, then save
and reopen an isolated copy without altering its tree, cursor, unrelated files or
preferences. `ClickInputPersistenceTest` supports this with the previous release's
synthetic traces/database folder and `CLICK_CHESS_PROCESS_PHASE=read`; never point
it at live user data. Run all eight baseline phone-sized suites against extracted
signed APK assets, plus native tests/lint and unchanged-model parity checks.
Confirm versionCode43 > 42, identical certificate/package and public APK bytes.
These checks are not a physical Android package-install test; do not use the USB
tethering phone. The extra authenticated explorer-game server endpoint is inert
for this restored client, so no backend restart or configuration change is needed.

## Earlier releases and common release checks
The Cloudflare gateway migration and its checks are described in
[v0.8.12](release-v0.8.12.md). Install over the existing app: pairing and all local
data remain intact. Durable board saves and indexed repertoire additions are described
in [v0.8.11](release-v0.8.11.md). No server change or repertoire download is needed.
The unified indexed library and its migration/recovery contract
are described in [v0.8.10](release-v0.8.10.md). It needs no server change,
repertoire redownload or re-pairing. Private acceptance and signed-artifact checks
must pass before publishing. The reviewed PR #1 performance update is described in
[v0.8.9](release-v0.8.9.md). It needs no server change, repertoire redownload or
re-pairing. The local one-ply repertoire re-entry update is described in
[v0.8.8](release-v0.8.8.md). It works with the existing downloaded library.
The separate source-comment update `2026-09-16.v1` has been accepted and deployed;
its remaining unresolved references are documented in the v0.8.8 notes. The web maneuver-arrow port is described in
[v0.8.7](release-v0.8.7.md). No server or repertoire update is needed.
The unified repertoire-add correction is described in
[v0.8.6](release-v0.8.6.md). It requires no server update or repertoire redownload.
The repertoire editing, private PC backups, marker staging and
analysis recovery update is described in [v0.8.5](release-v0.8.5.md). It requires
the authenticated PC backup routes but no pairing reset or corpus redownload.
The display-only privacy mode uses persistent neutral aliases,
conceals mobile identity-bearing metadata and errors, and retains original account
and game data. It needs no PC update. The focused editor update adds tool-independent dragging, tap
toggles, readable coordinates and undoable Reverse coordinates. It needs no server
change. Version 0.8.2 adds a separate board editor/position workspace and the narrow
custom-root PC API handoff. The v0.8.1 update cancels obsolete game/repertoire reads, avoids old-board
activation during game loading, batches history/edited ancestry checks, and imports
games in short UI slices. The grouped menus, comment editing and automatic extension
targeting remain unchanged. No repertoire redownload or data migration is needed.
Before producing a release APK, rebuild and verify the
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
gateway at `https://instinctazero.rs-industries.org` (standard HTTPS port 443). This is a public
HTTPS endpoint; the phone does not need the Tailscale app, Tailscale DNS, a VPN
connection, or any manual network setup. The native allow-list accepts only
that exact HTTPS host and port and the pairing/analysis/book routes. Port 8765
remains private and is never embedded in the APK.

The resulting APK is `app/build/outputs/apk/release/app-release.apk`. The
signing key is required for every future update; keep it backed up and never
commit it. Do not put a keystore, passwords, or Gradle signing properties in a
release artifact, issue tracker, or source repository.

Before publishing, at minimum verify:

- versionCode/versionName are the intended 44 / 0.8.16 release values;
- confirm the signed artifact descends from merged PR #1, retains the production
  application ID and signing certificate, and passes the bounded-body/stream,
  lazy-connection, corpus/transposition and private-edit preservation regressions;
- run `ManeuverSettingsTest`, `maneuver-arrows.test.mjs` and
  `maneuver-arrows-ui.mjs` against the signed APK assets. Check the web parity,
  both orientations, count/mode/off controls, settings persistence, inherited PVs,
  position changes and stale replies without additional analysis requests;
- run the editing, backup and stream regressions and `repertoire-editing-ui.mjs`
  against extracted APK assets, with `REPERTOIRE_EDIT_PREVIEW` pointing to the
  private response fixture produced by `RepertoireEditingRegressionTest`;
- run `repertoire-unified-add-ui.mjs` with that same fixture and APK assets; both
  informational and regular parents must have identical normal add controls and
  saved move presentation, with no preparatory save;
- run `AccountPrivacyTest` and `node web/test/privacy-ui.mjs` (also against extracted
  signed APK assets). Check Home, sidebar, Account / PC, both player colours,
  recycled game rows, raw account selection IDs, orientation/results, errors,
  aliases during chunked loading, saved-game reopen, reconnect, policy failure,
  on-screen PGN tags and comment/name reveal warnings. Turning privacy off restores
  original presentation without rewriting saved metadata; enabling it also covers
  an already-focused original-text editor. See [privacy audit](account-privacy.md);
- run `node web/test/position-editor-ui.mjs` against the signed APK's extracted
  assets. Check placement, actual touch drag in every tool mode (also with an
  existing board selection), quick/occupied drops, empty drags and touch cancel/
  multitouch. Check same-piece tap removal, palette deselection to Erase, coordinates
  in both orientations, Reverse versus view-only Flip, exact Undo, turn, castling,
  en-passant, clipboard import/export, keyboard layout, custom-root analysis and
  explorer payloads, source-board preservation, Return to saved board and restart;
- run `StudyWorkspaceStoreTest` and the position-editor controller tests, plus
  the PC mobile API tests. Verify the PC loads the matching `initial_fen` support
  without bypassing device authentication, fair-play gates or stored-game roots.
  Coordinate the PC service restart; do not interrupt active analysis unannounced;
- run `node web/test/game-load-ui.mjs` at the same phone-preview settings below,
  including assets extracted from the signed APK. Hidden warmup must not start a
  study lookup; switching games must cancel old reads and ignore delayed callbacks.
  Invalid, superseded and Back-canceled imports must not replace the saved board.
  Check new-game comments, Black orientation, resume and restart;
- run `LatestRepertoireReadTest` with native SQLite cancellation, plus bounded
  activity/transition-cache and existing edit/Undo/reinstall regression coverage.
  Optional end-of-game corpus benchmarks are documented in
  [`release-v0.8.1.md`](release-v0.8.1.md);
- run `WorkspaceMenuTest` with `NATIVE_MENU_PREVIEW` set to a private output folder
  for actual native View/Skia snapshots at phone sizes. Check groups/icons, naming
  and colour selection without starting a real account or using the USB phone;
- run `node web/test/repertoire-authoring-ui.mjs` with the same preview environment
  as the existing repertoire UI test. Check named-library selection, typed text,
  focus across updates, a keyboard-sized viewport, Save/restore/Undo/restart, and
  nearest-prefix versus tied extension choices;
- verify new books work without a source database, and that creating/renaming,
  PC installation, transpositions and Undo preserve local books and comments;
- verify the optional real-corpus performance/regression procedure in
  [`repertoire-performance.md`](repertoire-performance.md), including index plans,
  complete response equivalence, independent deviation context, and warmed-cache
  invalidation after edits, Undo and library installation;
- QGA reproduction and the alternate source move order both show Rd1/e4, their
  continuations and deduplicated position comments. Terminal transposition references
  must not hide coverage, and inactive duplicates must not veto active moves;
- the expanded catalog supports independent and combined selection of installed
  repertoires. Corpus updates retain edits, selections and Undo; old offline
  packages remain readable. Variation introductions retain their Before move role;
- streamed downloads accept the current >80 MiB library and reject >128 MiB
  without replacing the saved copy. Unknown resolver versions and stale hashes
  still fail closed on the PC;
- no local PGN files or source annotations are deleted or modified;
- board/piece assets, arrow metrics and portrait manifest retain the simple version. Do not use the USB
  tethering phone for testing or disconnect it;
- `npm run check`, `testDebugUnitTest`, release lint, and the signed release
  build all pass;
- run `npm run test:repertoire-ui` under Node 20+ with `PHONE_PREVIEW_CHROMIUM`
  pointing at a local headless Chromium. No real account or phone is used;
- verify compact repertoire rows, full escaped comments, restored selections,
  new-game defaults, explicit empty selections, and the persistent board-marker
  toggle. Markers must follow both orientations and navigation, reject stale
  callbacks, and mark covered transpositions as ordinary repertoire positions;
- known child markers appear before a deliberately delayed native reply;
  cached positions do not need another bridge request. Edits and corpus updates
  invalidate cached flags. End-of-line labels distinguish active terminal theory
  from positions with no active source occurrence; direct single/multi-move additions persist in
  the chosen repertoire, extend informational source lines normally but cannot
  override deliberate local exclusions/deletions, and
  leave source PGNs and archived games unchanged;
- undo is available beside edit confirmations and in repertoire settings after
  recreation. Verify whole-line rollback, previous-prefix/other-repertoire
  preservation, labels/removals, stale/repeated requests, failed atomic writes,
  no-ops, legacy state, and unchanged analysis tree/cursor/selected repertoires.
  Undo must invalidate cached markers/coverage without introducing stale callbacks;
- optionally set `REPERTOIRE_TEST_INDEX` and `REPERTOIRE_TEST_CASES` for native
  integration checks against independent position-union reference lookups. Setting
  `REPERTOIRE_PREVIEW_OUTPUT` saves the actual QGA native results locally; with the
  same environment variable, run `node web/test/repertoire-transposition-ui.mjs`
  to exercise those results at three phone sizes, including both orders, immediate
  markers/comments, restart and canonical forward navigation. Use
  `PHONE_PREVIEW_ASSETS` to repeat against assets extracted from the signed APK.
  Do not commit private reference results or screenshots;
- for a library-only compatibility check, supply `REPERTOIRE_TEST_CATALOG` with
  the server's catalogue response. Optional `move_details` reference fields verify
  own-side/alternative flags and move comments. `REPERTOIRE_PACKAGE_PREVIEW_OUTPUT`
  saves the actual native catalogue and position results for local phone previews.
  Use `:app:testDebugUnitTest --rerun` when changing these environment inputs;
  Gradle does not otherwise know that an optional private fixture has changed;
- verify the paired PC serves the read-only repertoire endpoints, rejects
  unauthenticated access and preserves the gateway/Funnel configuration;
- the APK installs and opens on native Home, while Analysis opens at the
  standard starting position;
- system dark mode does not recolour the board or turn all pieces white;
- PC-side InstinctaZero is already connected/configured for the user's Lichess
  account before a pairing code is created, so active-game fair-play checks are
  available; an unconfigured analysis-only PC is not treated as pairable;
- a fresh PC pairing code entered through the Account / PC touch keypad
  produces a paired state and no bearer appears in WebView storage, logs, URLs,
  or callbacks;
- another Lichess account already authorized in an InstinctaZero PC browser
  session can be selected without changing or exposing the paired-device token;
- switching accounts is accepted while another account is syncing, already
  imported games render immediately, and an archived analysis from the former
  account safely detaches instead of leaving the engine in a 404 error state;
- Leela streams progressive lines/arrows and cancellation closes its request;
- DNS-address and TLS-handshake failures advance to another resolved gateway address;
  read-only requests may retry, while pairing, account selection, archive sync,
  and analysis cannot replay after request transmission begins;
- a local study, selected variation, cursor, orientation, and game context are
  restored after Activity/process recreation;
- advancing through the canonical continuation inherits the cached response arrow
  and White-perspective value before the replacement search arrives;
- forward navigation always chooses the first child at an intersection,
  regardless of which variation was created, visited, or restored;
- Return to mainline replaces the size/minimize control. Outside the repertoire
  tab it is disabled on the mainline and jumps to the nearest notation divergence.
  In the repertoire tab it also considers the selected/focused repertoire's
  position-based intersections, without changing canonical forward navigation;
- MainActivity is locked to portrait and its existing pause/resume persistence
  remains intact;
- Home performs no archive sync or list construction. The separate Games screen
  triggers a PC-owned sync, uses recycled native rows with the bundled Cburnett
  pieces, requests 20-game pages while scrolling, and opens a selected game by
  trusted stored ID;
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
