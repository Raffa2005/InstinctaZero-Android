# Unified repertoire store — implementation acceptance plan

Implementation privately accepted for v0.8.10. Baseline: v0.8.9. PR2 reviewed at
1bcf38c7f84988c12954cadbf1047a2656457428: useful bounded batching/cancellation and
complete-response regression coverage, but its persisted source/JSON split is not
the requested final architecture. No live library or phone changes are authorized
until preservation and recovery acceptance passes.

## Storage contract

One authoritative writable SQLite database contains the source provenance,
normalized personal operations, repertoire metadata/settings, transactional change
history, and effective indexed positions/moves. Imported and authored moves use
the same read tables and policy on navigation; there is no JSON-overlay evaluator
in the read path. Contextual source classifications remain provenance. Personal
notes never erase independently readable source explanations.

Compile eligibility on import/edit transactions, not on navigation. Indexed
membership, recorded choices and one-ply legal re-entry share the same effective
facts. Actual played history remains necessary for departure/return points.
Initial implementation may rebuild the affected repertoire projection on edits;
measure that write cost rather than concealing it in the next lookup.

## Recovery contract

Initial migration builds and validates a new sibling file, then atomically publishes
it. The old downloaded SQLite and AtomicFile JSON remain untouched/recoverable.
Failure/interruption cannot publish partial state; restart retries the build.
Once committed, SQLite is authoritative, never silently replaced by stale legacy
files. Source refresh is a transaction in this database and reapplies personal
operations using stable position/move/path identities, never source node IDs.

Backup API v1 remains a logical compatibility export from normalized tables;
existing PC backups/Undo import without rewriting games, PGNs or credentials.
An old APK cannot read the new store directly: rollback requires the current v1
backup (download source and restore it), not blindly using frozen migration files.
Test a current legacy export/restore before shipping. History and undo updates share
the transaction with the effective graph. The database retains 100 recent audit
revisions, independently of current entries and the single-step Undo. Existing PC
backup history is unchanged. Neither journal is a backup of games or accounts.

## Required acceptance

- Compare complete native marker/detail responses with v0.8.9 and PR2 on artificial
  source duplicates, authored chains, notes, labels, exclusions and transpositions.
- Source-refresh with renumbered nodes/new notes; personal note at a changed source
  note; local book, selections, multi-entry Undo, restart and PC-backup restore.
- Inject interruption before publication and during edit/import transactions;
  recovery must expose the complete old or complete new revision only.
- Verify old-reader v1 export rollback and reimport, keeping original PGNs untouched.
- Measure migration time, size/working set, first/new positions, after edits,
  long history and multiple selections; record SQL/decoded work plus timings.
- Run existing native, source-policy and browser suites. No USB phone usage.
- Separate private acceptance remains scoped and local; do not publish private
  inputs. Release/sign only after these checks and routine release verification.

## Progress (17 September, not release acceptance)

- Isolated branch `feature/unified-repertoire-store`, worktree
  `/home/rafael/projects/InstinctaZero-Android-unified-store`; main remains v0.8.9.
- PR2's seven focused tests independently passed in
  `/tmp/instinctazero-pr2-review-20260917`; measurements at
  `/tmp/instinctazero-pr2-results-20260917.json`. No PR2 merge or release.
- Unified reader/projection implemented. The old source/JSON evaluator is retained
  only under test sources as a frozen v0.8.9 oracle, not in the APK read path.
- Full marker/detail JSON equivalence passed in 70 artificial cases: zero/100/1000
  additions, main override, comment-only edits, nine selected books, long game.
  Results: `/tmp/instinctazero-unified-equivalence-20260917.json`.
- 35 store regressions and the complete native suite passed. Eight new recovery
  cases pass across API 26 and 35, including interruption/refresh/restart/Undo/v1
  restore/old-reader rollback, stale writers and bounded journal retention.
  Legacy-file assertions now verify preserved frozen inputs plus the authoritative
  source fingerprint. JSON object comparisons preserve all fields without treating
  key order as data.
- Source explanations are deduplicated into a note pool with indexed associations,
  avoiding another full copy per resolved occurrence. Eligibility work is done on
  writes; read-only reopening does not deserialize all personal entries.
- First migration retains both original files. Invalid legacy JSON fails explicitly
  rather than migrating empty data. Transaction fault checkpoints are test-only;
  stale writers are rejected instead of overwriting a newer revision.
- Telegram milestone sent once, confirmed `ok:true`, message 704. No completion
  claim/APK. Previous investigation message 702 must not be repeated.
- The source-refresh stress test exposed a bad recursive join order. A composite
  repertoire/parent index and explicitly ancestor-first join fix it. The actual
  recursive query plan is regression-tested on API 26 and 35.
- Python policy tests: 25 passed. Browser/controller checks: 65 passed. Touch
  repertoire/authoring/game-load/re-entry scenarios pass at 360×640, 390×780 and
  412×844, with actual native synthetic re-entry results. Current UI is unchanged.
- Private acceptance is complete. Final signed APK/public-download verification
  remains required before announcing release readiness.
- No live corpus/state, services, source snapshot or USB phone touched.

### Private acceptance correction

The first private acceptance of `10ae064` caught one extra `0000` null-move
analysis record in a full response. The projection had checked UCI length only,
whereas the released reader checks the full move format. Effective edges and their
note links now use the exact equivalent format filter. Raw source occurrences and
their position notes remain intact. Synthetic coverage includes invalid/null
records beside ordinary moves and missing-edge re-entry, plus terminal markers.
Another 324 artificial source-mask/recommendation/repeated-position comparisons
pass. Fresh private acceptance passed at `a04b5d1`: 600 independent source cases
plus 1,116 personal positions, using the current 1,079-entry backup. All 1,716
complete-response requests match the released reader through migration, refresh,
restart, actual Undo, replacement-phone restore and old-reader v1 rollback.
The four private refresh tests pass, including a personal note at an actually
clarified source comment. The full current-index suite discovered 134 tests:
123 passed, 11 optional-input skips, zero failures. All seven private input hashes
were unchanged. Private fixtures/results remain outside this repository. This is
host/native acceptance, not physical-phone validation.

## Same-host measurements

`RepertoireReadPathBenchmarkTest` ran unchanged on clean v0.8.9, PR2 and this
branch. Artificial corpus: 19,440 source occurrences, nine books, up to 1,000
authored edges. Each case has seven randomized trials, a fresh store's cold
position caches, then a warm repeat. Menu preparation/migration is separate.
Markers, full lookup, JSON serialization and callback quoting are timed together.
All 100 complete marker/detail result pairs match both baselines. SQLite query
counts below instrument the read path, not every framework/metadata call.

| Cold request | v0.8.9 ms | PR2 ms | Unified ms | Read queries, old → PR2 → unified |
| --- | ---: | ---: | ---: | --- |
| 1 book, 1,000 additions, authored position | 19.48 | 3.14 | 1.37 | 1946 → 29 → 24 |
| 9 selected, same authored position | 39.81 | 12.32 | 6.12 | 2226 → 309 → 128 |
| 9 selected, 140-ply game, 1,000 additions | 75.62 | 54.23 | 36.81 | 3714 → 2541 → 189 |
| 9 selected, 100 additions in every book | 28.66 | 6.16 | 7.22 | 1990 → 235 → 216 |
| 9 selected, source only, new game | 64.30 | 57.29 | 35.26 | 2548 → 2548 → 189 |

Not every case beats PR2, and these are host/Robolectric measurements, not phone
latency or battery claims. A comment edit with 1,000 existing additions took about
45 ms including persistence, versus 28 ms in v0.8.9. Its next lookup was 3.4 ms
versus 25.6 ms. Graph projection work happens on writes, not the next navigation.
New requests still pay for legal one-ply geometry and history intersections.

Stress fixture: 121,500 occurrences, nine books, 1,000 authored edges, 76.84 MiB
source. One-time migration 1.05 s, graph deletion 0.23 s, source refresh 1.63 s,
Undo 0.19 s on the host. Authoritative database 145.24 MiB plus 77.11 MiB retained
legacy recovery inputs. Peak working-directory footprint during refresh was
299.19 MiB, including download/journal. Host-process RSS rose about 121 MiB to
1.08 GiB in the full Robolectric run. This includes Android/JVM test infrastructure,
so it is not an Android app RAM measurement. Operations remain off the UI thread.

Reproduce with `tools/repertoire/generate_edit_fixture.py` in a new temporary
directory, then the opt-in environment variables in the benchmark and scale tests.
Only synthetic input is needed. The optional `UnifiedRepertoireAcceptanceTest`
uses the existing locally provided source/refresh/reference/backup variables and
writable isolated copies. It compares every full response against the frozen
released reader, including after refresh, restart, Undo and replacement-phone
restore. Do not commit its private inputs or test outputs.
