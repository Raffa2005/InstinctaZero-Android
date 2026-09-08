# v0.8.1 — Responsive game opening

## Changes

- Opening a completed game no longer activates the previous analysis board first.
  Repertoire reads stop when hidden or superseded, including cancellation inside
  SQLite. Late repertoire and game-download callbacks cannot overwrite a newer
  game or reopen it after leaving the screen. Edits and Undo are never canceled.
- Long games build a temporary tree in short UI slices. Only the completed, valid,
  current import replaces the board. Back, invalid input and overlapping imports
  preserve the previous saved state. A short loading status replaces stale results.
- Batch indexed membership/extension checks for past game positions. Batch the
  same source ancestors required by local exclusions/optional labels; memoize
  duplicate edge-edit hashes. A bounded cache retains only position/edge facts,
  never a game's deviation or extension context. Edits, Undo and library changes
  invalidate both native caches.

The menus, board, comments, variations, repertoire choices, automatic transpositions,
Return to mainline and portrait lock are unchanged. No source PGNs or archived game
PGNs are modified. No repertoire redownload, re-pairing or migration is needed.

## Measurements and limits

Before/after use v0.8.0 and this version's full `RepertoireStore.lookup` on
Robolectric API 35/native SQLite, against a read-only copy of the installed
nine-repertoire corpus. These are **host lookup timings, not phone UI timings**.
Each row has nine consecutive requests in one fresh store; medians include its
first read. Deterministic legal 40/80/160-ply games exercise the final position and
its full history. The edited case is the measured Taimanov position after ...Qc7,
with a local exclusion, followed by Undo. Complete JSON responses match v0.8.0.

| Case | v0.8.0 median | v0.8.1 median | v0.8.1 first read |
| --- | ---: | ---: | ---: |
| 40-ply game, one repertoire | 9.45 ms | 0.42 ms | 2.90 ms |
| 40-ply game, nine repertoires | 20.57 ms | 1.41 ms | 8.30 ms |
| 80-ply game, one repertoire | 4.35 ms | 0.46 ms | 2.71 ms |
| 80-ply game, nine repertoires | 22.37 ms | 1.83 ms | 8.62 ms |
| 160-ply game, one repertoire | 4.91 ms | 0.64 ms | 3.26 ms |
| 160-ply game, nine repertoires | 36.28 ms | 10.37 ms | 11.36 ms |
| Local exclusion, one repertoire | 115.16 ms | 0.30 ms | 87.23 ms |
| Local exclusion, nine repertoires | 117.61 ms | 0.70 ms | 80.93 ms |

The edited first read still costs appreciable work; it is not claimed instant.
The bounded cache avoids repeating it, and cancellation stops an obsolete read
from delaying the newly selected game. The 160-ply/all-library case also shows the
intentional bounded-cache tradeoff. No comments are truncated to improve timings.

To repeat locally (private fixture paths are intentionally not committed), generate
workloads with `node web/test/game-load-workloads.mjs /absolute/output.json`
and set `REPERTOIRE_TEST_INDEX`, `REPERTOIRE_GAME_LOAD_CASES`, and
`REPERTOIRE_GAME_LOAD_OUTPUT` for `RepertoireGameLoadPerformanceTest`. Optionally
set `REPERTOIRE_GAME_LOAD_REFERENCE` to the earlier output for full-response
comparison. Use Gradle `--rerun` when changing fixture environment variables.
The existing corpus/performance procedures remain in
[repertoire-performance.md](repertoire-performance.md).

## Verification

All 79 Android/Robolectric tests passed in debug and release, with no skips. This
includes native SQLite cancellation and bounded cache facts, 648 independent
position-union corpus cases, full previous performance-response equivalence,
edits/comments/Undo/reinstall, transpositions, and navigation contracts. All 44
JavaScript/controller tests passed. Release lint, signature verification and ZIP
alignment passed with the existing signing certificate and version code 29.
Touch-browser game-load checks run at 360×640, 390×780 and 412×844 with 4× renderer
CPU throttling, alongside existing authoring/repertoire/analysis previews. They
exercise overlapping imports, delayed old responses, hidden warmup, invalid input,
Back, orientation, resume and saved-state restart against packaged assets.

The USB tethering phone and live services are not used for testing. These checks
do not reproduce or measure Rafael's physical handset stall end-to-end.
