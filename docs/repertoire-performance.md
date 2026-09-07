# Repertoire lookup performance — v0.7.6

This is an app-only update. The existing downloaded six-repertoire index already
has the required `nodes_before(repertoire_id,fen_before)` index. No redownload,
PGN rewrite, pairing change or migration is required.

## Scoped changes

- Child queries use `n.fen_before=?`, including extension checks limited to the
  played UCI move. Tests explain the **production query**, not a simplified proxy.
- Matched source occurrences retain the old `srs,id` order, preserving move order,
  comments and fallback reasons. Sorting these matches in Kotlin is intentional:
  adding `ORDER BY srs` in SQL caused SQLite to choose the slow training index again.
- Unedited-book departure checks use indexed existence queries rather than loading
  full duplicate comments. Edited books retain their contextual ancestor checks.
  The departure anchor is calculated once per repertoire/request.
- A native LRU retains serialized position facts, including complete comments and
  child markers. It is limited to 384 entries and approximately 4 MiB (UTF-16 strings,
  keys and per-entry allowance). Oversized entries bypass it; content is never truncated.
  Readers get independent objects. Edits/Undo, including rollback after failed
  writes, clear the cache; a successful library installation also clears it.
- Deviation, extension eligibility and missing-prefix counts are **not** shared
  across histories. Two histories reaching the same outside position still report
  different departure points/add counts. The existing history-aware JS cache stays
  unchanged. Connections and raw source graphs remain request-scoped; their remaining
  overhead did not justify adding retained-connection lifecycle complexity.

No UI, engine, network, archive, chess navigation or portrait lifecycle code changed.
Source/SRS roles, optional systems, informational lines, transpositions, selections,
saved additions and Undo retain their meaning.

## Host measurements

Measured on 2026-09-07 against the installed 100.2 MiB, six-repertoire corpus.
Baseline was the actual pre-change app source (`e6554a8`, v0.7.5 production code)
in an isolated worktree. Robolectric API 35 used native SQLite. Timed work includes
request JSON decoding, the complete `RepertoireStore.lookup`, all child statuses and
comments, DB opening/closing, and response JSON serialization—not just one SQL query.

Milliseconds; baseline/repeat columns are medians of six samples after one initial
request. Fresh-cache values are medians of three new stores in a warmed JVM/OS;
they are **not** cold device-start timings. Rounding is deliberate.

| Position / selection | Before | After, fresh app cache | After, repeat |
| --- | ---: | ---: | ---: |
| Initial / English only | 23.8 | 4.4 | 0.07 |
| Initial / all six | 99.2 | 30.0 | 0.13 |
| 1.c4 / English only | 12.9 | 3.7 | 0.04 |
| 1.c4 / all six | 69.1 | 6.6 | 2.3 |
| English after 6…e6 / English only | 29.2 | 2.4 | 0.08 |
| English after 6…e6 / all six | 310.1 | 6.7 | 4.1 |
| English after 8…O-O / English only | 27.5 | 1.1 | 0.10 |
| English after 8…O-O / all six | 389.2 | 5.4 | 4.5 |
| QGA transposition / QGA only | 8.2 | 0.6 | 0.06 |
| QGA transposition / all six | 362.5 | 4.8 | 3.9 |
| QGA transposition + Rd1 / all six | 381.3 | 3.9 | 3.7 |
| Early outside-book / all six | 116.0 | 2.8 | 2.6 |
| Later outside-book / all six | 389.6 | 4.3 | 3.8 |
| Same outside FEN, other history / all six | 404.6 | 4.2 | 4.1 |

The 49 forward/back/forward navigation steps had a median first-request time of
227.8 → 3.2 ms. Across separate add/exclude/Undo/install navigation phases, medians
were approximately 391–434 ms before and 5–10 ms after. Timings vary with JVM warmup
and shared-host load; these are not physical-phone speedup claims.

The packaged WebView UI was separately profiled in touch-enabled Chromium previews
at 360×640, 390×780 and 412×844 using complete real native responses. Before the fix,
context creation was at most 0.1 ms, response handling/rendering at most 1.2 ms,
and synchronous forward/back work typically 3.5–3.8 ms (maximum 6 ms). Those stages
were already small; no UI/cache redesign was warranted. The preview verifies the
actual board/navigation/marker/comment path but does not measure a physical Android
WebView's thread scheduling, bridge overhead or paint latency. The USB tethering
phone was not accessed.

## Reproduce and verify

Generate non-private workloads with Node 20+ and the existing web dependencies:

```bash
node web/test/repertoire-workloads.mjs /tmp/repertoire-workloads.json
```

Set `REPERTOIRE_TEST_INDEX` to a private copy of the installed index,
`REPERTOIRE_BENCH_CASES` to that workload JSON, and `REPERTOIRE_BENCH_OUTPUT` to
a private output path. Run `:app:testDebugUnitTest --rerun --tests '*RepertoirePerformanceTest'`.
To compare complete responses with a prior build, set `REPERTOIRE_BENCH_REFERENCE`
to its output path. Its adjacent `.edits.json` contains the mutation-phase reference.
JSON object key order is ignored; all array order and content remain exact.

The opt-in checks cover:

- every source edge's stored-before-FEN/parent/repertoire invariant;
- identical complete parent-join/direct-index rows at 582 sampled positions across
  all six repertoires, plus the production full/single-move query plans;
- 67 one/six-selection, early/late, outside-book, QGA-transposition and navigation
  workloads, with complete responses equal to the pre-change implementation;
- two real histories reaching one outside FEN (departure 17/add 1 versus departure
  13/add 5), both rejoining after a local addition, and exact restoration after Undo;
- warmed navigation after add, exclusion, Undo and a full installation, using only
  a disposable test copy of the database and edits;
- the existing independent 528-position corpus reference suite (via
  `REPERTOIRE_TEST_CASES`), including comments, variation introductions, Black
  orientation metadata and the optional …Bf5 system.

Always run the ordinary unit/controller tests and phone-size repertoire previews
as described in `release.md`, including cache bounds, independent returned objects,
legacy schema fallback, failed writes, stale Undo and successful install invalidation.
Private input/output JSON, SQLite files and screenshots must never enter the APK,
repository or public release.
