# v0.8.11 — durable lines and faster editing

Install this APK over the existing app. Do not uninstall or clear storage.
No repertoire redownload, pairing change or PC restart is needed. The simple
design, privacy, mainline navigation, repertoire intersections and portrait
orientation are unchanged. Source PGNs and archived games are not rewritten.

## Confirmed faults and fixes

- Board restoration silently stopped after 512 **total tree nodes**, not 512
  moves per line. Later saves could overwrite the complete tree with that prefix.
  A separate 256 KiB save rejection was ignored by the UI. A legal 3,279-node
  fixture reproduced the truncation in v0.8.10. Neither limit is in the new board
  persistence path. Invalid restoration fails visibly without overwriting storage.
- Board/cursor changes now commit transactionally to `analysis_boards.sqlite`,
  using flat move records and small branch updates. A navigation step writes
  metadata only. Success is acknowledged only after SQLite's FULL-synchronous
  transaction completes. Failed edits stay pending in the open board with a
  visible retry notice; switching/resetting boards is blocked while saving fails.
  Legacy preferences remain untouched and an atomic pre-migration recovery copy
  is made before import. Original and custom-position workspaces remain separate.
- Navigation rebuilt, rebound and serialized the whole notation tree repeatedly.
  It now changes the selected move in place; extending a leaf appends one move
  control. Tree reconstruction/serialization/render traversal are iterative.
  Canonical forward navigation and the return-to-intersection action are retained.
- A repertoire addition rebuilt imported occurrences and all source comments.
  Ordinary additions/Undo now retain that unchanged material and recompute only
  affected position/edge facts. Authored graph reachability still accounts for
  transpositions, optional ancestry and deliberately excluded routes. Queries use
  the existing indexed authored-ID range instead of scanning the entire book.
  No-op authored rows are not rewritten; structural JSON comparisons avoid
  repeated serialization. The same authoritative transaction includes the edit,
  effective facts, Undo and recovery journal. Labels/deletions/source updates
  retain the complete rebuild where source ancestry can change.
- Activity teardown drains accepted repertoire work instead of dropping the queue.
  A cheap process-generation check invalidates another Activity's stale lookup
  cache after a commit; revision checks reject stale writers rather than losing
  newer edits. Existing private PC backup/restore remains compatible.

The exact physical-reality performance principle requested by Rafael is now a
standing instruction in `AGENTS.md`.

## Measurements and regression checks

Same host, eight trials, first excluded as warmup. Native timings include the
completed local transaction, not optimistic UI feedback. The real-library test
uses writable isolated copies of the nine-book corpus and current personal edits.

| Measured operation | v0.8.10 | v0.8.11 |
| --- | ---: | ---: |
| Durable repertoire extension, real corpus | 799 ms | 20 ms |
| Undo that extension | 800 ms | 17 ms |
| Marker + complete lookup after the edit | 2.9 ms | 2.8 ms |
| Navigation in a 3,279-node notation tree | 55 ms | 4 ms |
| Navigation bridge payload | 84,413 characters | 281 characters |

The last two rows are touch-Chromium previews with 4× renderer CPU slowdown and
a mock native bridge, at 360/390/412 px widths. The old restore cap is bypassed
only for the equal-size live-tree performance comparison; the independent restart
check reproduces its unmodified 512-node loss. These are not physical-phone,
battery or end-to-end network latency claims. The USB tethering phone was not used.

Coverage includes complete incremental-versus-rebuild facts; informational and
ordinary additions; source/personal comments; labels, deletions and Undo; source
refresh, restart and replacement-phone restore. Private full-response comparisons
cover 1,768 source/personal positions. Board tests cover 15,001 nodes, migration
backup, failure rollback, stale writers, deletion/promotion, separate workspaces
and a 1,600-ply board read in a second JVM process. Phone-sized tests cover actual
large-tree reopen, failed-save/retry, game loading, repertoire authoring/Undo,
privacy, the board editor and maneuver arrows.

Full source refresh and imported-label/exclusion edits can still require ancestry
rebuilds. Opening a very large tree still takes work proportional to its size;
disk sync, available memory and device speed remain real limits. The repertoire
backup protocol's existing bounded edit payload is unchanged; the captured
personal backup was about 292 KB, well below its limit. It did not explain the
confirmed board truncation.

## Recovery limits

The 512-node failure matches partial next-day loss, but without the affected
phone state or exact missing lines it cannot be proven to explain both reports.
Seventy-five PC repertoire snapshots were backed up consistently without changing
live history. They contain repertoire edits, not analysis-board trees. Already
overwritten board branches cannot be promised recoverable from those snapshots.
No older snapshot was restored over newer work. Remaining legacy board data is
preserved during the update; nothing attempts to invent missing moves.
