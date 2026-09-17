# v0.8.9 — repertoire and analysis work reduction

Integrates [PR #1](https://github.com/Raffa2005/InstinctaZero-Android/pull/1), reviewed
and locally validated before merging. This is the same simple app, not a redesign.

- Cached repertoire/marker requests can avoid opening SQLite. Cold reads share
  one request-scoped connection, closed on success, failure or cancellation.
  Source columns are cached until installation, and local additions/edge edits
  are indexed within each lookup instead of repeatedly scanned or hashed.
- Engine messages still update analysis state immediately. Panel/arrow rendering
  is combined into one paint per animation frame without rebuilding unrelated
  tabs. Navigation/backgrounding cancel pending paints; inherited arrows and
  final snapshots retain their existing behavior.
- Gateway responses and analysis frames have byte limits before large decoded
  strings accumulate. Game thumbnails reuse drawing bounds and avoid reparsing
  unchanged placements.
- The portable Python indexer writes completed repertoire batches into the same
  temporary transaction and joins duplicate-occurrence child queries. This does
  not replace the installed PC indexer or rebuild any private repertoire.

No source/library migration, repertoire redownload, re-pairing or backend restart
is required. Personal additions, deleted moves, recommendations, comments, Undo,
backups, games and local PGNs are retained. The prior source update's 27 unresolved
references remain unresolved; this performance release does not add opening theory.

## Local acceptance

Before merging, the current nine-repertoire library was checked on writable,
isolated copies. An independent python-chess/SQL reference covered 600 positions,
including 14 missing legal re-entry associations and complete comments/labels.
The indexed-child invariant and query plans passed across the corpus. Synthetic
and actual-source refresh/restart/Undo/restore tests passed, including a personal
note at a source comment that was genuinely clarified. The latest private backup
overlay was restored only in isolated test storage and checked at every affected
position. No private inputs, notes, screenshots or signing material are committed.

Complete native responses match the pre-PR code on 20 workloads spanning early
and later positions, one/nine selections, transpositions, outside-book positions
and repertoire intersections. Timings are host/Robolectric checks, not controlled
phone measurements; no device-level latency, FPS or battery improvement is claimed.
Deterministic tests establish zero database opens on fully cached theory/marker
reads and one scheduled paint for a burst of 100 analysis updates.

Pre-merge debug suite: 119 discovered, 112 passed, seven opt-in tests skipped.
The three real-refresh/private-backup checks and complete-response workload check
ran separately and passed. Python policy/index tests: 25 passed. Browser/controller
checks: 65 passed, including current web maneuver parity. Release acceptance also
requires release tests/lint, phone-sized touch scenarios against extracted signed
APK assets, same signer/app ID, version 37 / 0.8.9, portrait orientation and a
verified public download of the exact tested artifact. No USB-phone testing.
