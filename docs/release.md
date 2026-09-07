# Release build

This repository's current release target is the native-shell analysis app
(version 0.7.6). This update accelerates lookups against the existing six-repertoire
library. It requires no repertoire redownload or data migration.
Before producing a release APK, rebuild and verify the two
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

- versionCode/versionName are the intended 27 / 0.7.6 release values;
- verify the optional real-corpus performance/regression procedure in
  [`repertoire-performance.md`](repertoire-performance.md), including index plans,
  complete response equivalence, independent deviation context, and warmed-cache
  invalidation after edits, Undo and library installation;
- QGA reproduction and the alternate source move order both show Rd1/e4, their
  continuations and deduplicated position comments. Terminal transposition references
  must not hide coverage, and inactive duplicates must not veto active moves;
- the expanded catalog supports independent and combined selection of all six
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
  the chosen repertoire, cannot cross excluded/informational source lines, and
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
- DNS-address and TLS-handshake failures advance to another Funnel ingress;
  read-only requests may retry, while pairing, account selection, archive sync,
  and analysis cannot replay after request transmission begins;
- a local study, selected variation, cursor, orientation, and game context are
  restored after Activity/process recreation;
- advancing through the canonical continuation inherits the cached response arrow
  and White-perspective value before the replacement search arrives;
- forward navigation always chooses the first child at an intersection,
  regardless of which variation was created, visited, or restored;
- Return to mainline replaces the size/minimize control, is disabled on the
  mainline, and jumps from a variation to its nearest divergence intersection;
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
