# Release build

This repository's current release target is the native-shell analysis app
(version 0.8.14 / code 42). This update adds engine-informed destination input,
independent temporary games, explorer games, bottom sections and experimental PGN/FEN import.

## v0.8.14 navigation and import

- Destination input uses matching current or cached engine visits, policy and line
  evidence before the unchanged offline trained ranker. The last 24 matching engine
  positions are retained. Previous move, as well as drag-back, corrects a guessed
  move and arms the next candidate. Other navigation clears that correction state.
- Analysis keeps its durable SQLite board. Archive, explorer and imported games
  have independent in-memory tabs, histories and orientations; they never write
  the saved board. Eight temporary tabs may be open; close one to open another.
  Closing the app discards temporary tabs and their unsaved edits. Export PGN
  explicitly before closing to keep them. Existing saved boards, including legacy
  game-context boards, remain readable. No migration, clearing or re-pairing.
- Screen/system Back from an archive game returns to Games. Explorer games return
  to their source board's Book tab. Imported games return to their file selector.
  Move-history Previous is a distinct action. Bottom sections are Analysis, Games,
  Books and Account; Broadcasts is not implemented.
- Open PGN / FEN uses Android's document picker or explicit clipboard paste, with
  a manual text field fallback. Multi-game files are indexed once and games parsed
  on demand in short slices. UTF-8/UTF-16 files up to 8 MiB, 20,000 moves per preview,
  standard chess only. Limits are reported, never silently truncated. FEN opens a
  temporary position only after confirmation; the separate saved Board editor
  remains available. Temporary original input stays in memory for original export.
- English, German, French, Spanish/Italian and Russian piece notation, figurines,
  coordinate moves, glued move numbers and zero-castling are accepted when legality
  disambiguates. The language selector resolves ambiguous symbols. Repairs are
  shown; unresolved branches stop and require explicit acceptance of a partial
  preview. Variations, comments and NAGs are retained. Edited exports normalize
  notation; original exports retain the imported text. Privacy aliases are display
  only, and export in privacy mode requires an explicit real-name warning.
- The PC adds only authenticated `POST /study/explorer/game`, within the existing
  gateway. It accepts a fixed source and eight-character ID, retrieves a bounded
  completed public PGN from a fixed HTTPS origin, using the existing PC-owned
  Lichess token just like book statistics. Tokens never enter the WebView;
  redirects are refused. It retains the fair-play gate and writes no archive data.
  No engine tuning, model, network configuration or repertoire changes.

Run `npm run check`, `navigation-import-ui.mjs`, and all existing portable phone
scenarios against the final APK's extracted assets. Native `GameDocumentsTest`,
`StudyDatabaseTest` (API 26/35), menu/portrait/privacy/lifecycle tests and release
lint cover decoding, bounds, rejected temporary saves and platform integration.
PC tests: `tests.test_mobile_api` and `tests.test_mobile_explorer_game`. Browser
previews use 360×640, 390×780 and 412×844 with touch and 4× CPU throttling; they are
not physical Android/WebView or storage benchmarks. Never use the USB tether phone.

## Earlier releases and common release checks

The offline click-chess input and drag-back correction
are described in [input behavior and validation](click-chess-preview.md).
Ship the minified, production-key-signed `release` APK, not the former development
preview (which installed separately and had unshrunk dependencies).
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

- versionCode/versionName are the intended 42 / 0.8.14 release values;
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
