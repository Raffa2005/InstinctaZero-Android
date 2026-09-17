# Edited-line navigation regression

The reported case is `1.e4 c5 2.g3 d5`, with the continuation saved as added
repertoire moves. `RepertoireEditedLinePerformanceTest` reproduces navigation
through `3.exd5 Qxd5 4.Nf3 Nc6 5.Bg2` using public synthetic data, not a private
book or backup. Its source has 400 duplicate occurrences; its edit library also
has 320 unrelated legal additions. It tests both ordinary additions and a chosen
main recommendation.

The measured operation is the first-stage marker followed by the full lookup,
including repertoire intersections, at each previously unvisited position.
Inputs and fixture creation are outside the measured interval. A fresh store
starts each of the two runs. Consequently the first position includes cold
read-model construction, while later positions test navigation reuse rather than
repeated exact-response cache hits.

## Read-path invariants

Source and added edges feed the same position, history and continuation queries.
An ordinary addition or main recommendation must not disable indexed aggregation.
Actual exclusions and optional ancestry labels still retain occurrence semantics.

The store retains a bounded derived added-edge index and publishes reachability
only after complete resolution. It retains no database, cancellation signal or
request object. The cache has a 2 MiB estimated byte budget and a 16-book entry
limit; larger values remain functional without shared caching. Edits, Undo,
backup restore and successful source replacement invalidate the derived state.
The persisted source and edit formats are unchanged.

Anchor queries are batched in groups of at most 400 positions. Ancestor-ID queries
must use SQLite's integer primary key instead of scanning a repertoire through
`nodes_training`. Complete continuation choices aggregate duplicate source
occurrences, but retain informational UCIs so they cannot be incorrectly promoted
to inferred re-entry edges.

## Deterministic acceptance

Normal CI enforces at most 40 SQL queries and 50 decoded rows for each measured
marker/full-lookup pair, including the cold first visit. It also checks theory,
end-of-line, marker consistency, exclusions, Undo, restore, restart, source
replacement, cancellation during resolution, cache limits and the ancestor query
plan. These are work-count/correctness checks, not device latency assertions.

To run the focused suite with Android SDK 35 and Java 17 configured:

```sh
./gradlew :app:testDebugUnitTest \
  --tests '*RepertoireEditedLinePerformanceTest' \
  --tests '*RepertoireLocalIndexTest'
```

For an old/new comparison on the same host, the following baseline commit contains
only the original reproduction tests and unchanged v0.8.9 production code. The
`EDITED_LINE_BASELINE` switch disables the new work budgets only for that old-code
measurement; it is not set in normal CI.

```sh
mkdir -p .repertoire-work
report_dir="$(pwd)/.repertoire-work"
git worktree add --detach ../edited-line-baseline 9a60e379c1af372a0a27e65b1fd0f8e4990ce871
(
  cd ../edited-line-baseline
  EDITED_LINE_BASELINE=1 \
  EDITED_LINE_OUTPUT="$report_dir/edited-line-baseline.json" \
  ./gradlew :app:testDebugUnitTest --rerun-tasks \
    --tests '*RepertoireEditedLinePerformanceTest.editedSicilianUsesBoundedWorkAcrossPreviouslyUnvisitedMoves'
)
EDITED_LINE_REFERENCE="$report_dir/edited-line-baseline.json" \
EDITED_LINE_OUTPUT="$report_dir/edited-line-optimized.json" \
./gradlew :app:testDebugUnitTest --rerun-tasks \
  --tests '*RepertoireEditedLinePerformanceTest.editedSicilianUsesBoundedWorkAcrossPreviouslyUnvisitedMoves'
```

The comparison checks complete canonical JSON responses and markers, not just
counts or a timing threshold. Reports contain only generated fixture data. Host
Robolectric timings include JIT and scheduling variability and are not physical
Android-device measurements. Private-corpus and handset validation remain separate.
