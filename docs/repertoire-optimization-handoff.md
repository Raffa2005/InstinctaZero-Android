# Repertoire lookup optimization: GitHub-only workspace

This repository contains the Android implementation, the source-index builder,
its policy tests, and a synthetic package generator. A worker can develop and
test the optimization from this checkout without the production PC or private
repertoire books. This setup does not itself optimize production lookup.

## Requested work

Make first visits, new-game loads and multi-repertoire navigation faster without
changing book behavior or losing personal edits. The current SQLite source index
retains every PGN occurrence. Android merges repeated occurrences at lookup time.
An indexed query is not necessarily cheap when one move has many source copies.

Rafael suggested building the optimized index on Android. Evaluate deriving a
compact position-to-moves lookup from the existing downloaded database once per
library version. This does not require parsing PGNs on the phone or changing the
PC service. Compare against the current implementation before committing to a
format. PC-side preprocessing is another option, not a required architecture.

If building on Android, keep work off the UI thread, bound memory, account for
disk use and cancellation, and publish a validated result atomically. A failed or
interrupted build must leave the previous usable source/index intact. Key derived
data to the source version and its own schema/policy version. Invalidate or update
edit-dependent results correctly. Never rewrite the user's source or edit files
to create a performance cache. Do not do the entire build on every lookup/startup.

## Code map

- `app/src/main/java/com/instinctazero/android/RepertoireStore.kt`: SQLite install,
  lookup orchestration, personal overlays, Undo, backups and source refresh.
- `RepertoirePositionBook.kt` in that directory: position union, source occurrence
  inheritance, recommendations, legal re-entry, comments and history intersections.
- `RepertoireLegalMoves.kt`, `RepertoireLookupCache.kt`, `RepertoireActivityCache.kt`:
  legal geometry and bounded shared caches.
- `app/src/main/assets/analysis/repertoire.js`: WebView presentation, cached child
  facts and asynchronous native requests. MainActivity runs repertoire work on a
  dedicated single-thread executor and cancels obsolete reads.
- `tools/repertoire/repertoire_index.py`: exact portable copy of the PC builder
  at workspace creation. Schema version 1, resolver policy 4. It is development
  code here, not a symlink to or automatic replacement of the installed PC copy.
- `tools/repertoire/annotation.schema.json` and `tests/`: source policy contract
  and temporary artificial-fixture regressions.

The Python `Book.lookup()` is intentionally history-based source/SRS lookup.
It is NOT the specification for Android position-based book coverage. Optimize
the latter without changing the former into a different API.

## Behavior that must survive

Position identity includes board, side to move, castling rights and legal en
passant, but not move counters. Within one repertoire, eligible occurrences union
by position and move. A separately active occurrence can supply coverage even if
another occurrence is informational. Keep distinct source notes, variation
introductions, drawings and provenance. Do not flatten away the occurrence context
needed for exclusions, optional branches or training.

The book includes legal one-move re-entry into covered positions. Explicit
informational/refutation edges are not promoted merely because their destination
also occurs in theory. End-of-line markers and return-to-repertoire intersections
use the same eligibility as move lists. No arbitrary multi-ply closure or engine
search is intended. Playing a displayed move must load its destination material.

Personal added/deleted moves, main/alternative choices, comments, selections,
local books and Undo survive source refresh, restart and backup restore. Use
stable repertoire/position/move identities, not rebuilt SQLite node IDs. A personal
note must not erase or hide updated source explanations. Deliberate deletions stay
deleted through inferred re-entry. Informational and regular line additions use
the same ordinary workflow. Opponent moves keep regular labels even in an optional
branch. Preserve actual game history for deviation and return-point calculations.

Keep the current simple design, account privacy and analysis behavior. This is a
performance task, not a UI redesign, repertoire rewrite or engine project.

## Reproducible checkout-only tests

Use Python 3.10+ (CI uses 3.12), Node 22, JDK 17 and Android SDK platform 35.
The Gradle wrapper is committed. Standard package downloads need network access,
but tests need no account, PC gateway, signing secret or physical phone.

```sh
python3 -m venv .venv
.venv/bin/pip install -r tools/repertoire/requirements.txt
.venv/bin/python -m unittest discover -s tools/repertoire/tests -v
.venv/bin/python tools/repertoire/generate_fixture.py .repertoire-work/fixture
.venv/bin/python tools/repertoire/repertoire_index.py \
  --folder .repertoire-work/fixture/annotations check
npm ci --prefix web
npm run check --prefix web
REPERTOIRE_PORTABLE_INDEX="$PWD/.repertoire-work/fixture/annotations/repertoire.sqlite" \
  ./gradlew --no-daemon --max-workers=1 \
  -Dorg.gradle.jvmargs='-Xmx1024m -Dfile.encoding=UTF-8' :app:testDebugUnitTest --rerun-tasks
```

Set `JAVA_HOME` and `ANDROID_HOME` to the environment's JDK/SDK if needed. The
public CI workflow performs this sequence on an ordinary hosted runner. An unsigned
debug build can use `:app:assembleDebug`. Production release signing remains local.

`RepertoirePortableCorpusTest` actually installs the Python-generated database
into isolated Robolectric storage and tests missing re-entry, deletion, refresh,
restart, Undo and restore. It skips unless `REPERTOIRE_PORTABLE_INDEX` is set.
The other native synthetic tests run without it. Older tests requiring private
`REPERTOIRE_TEST_INDEX` or real-backup inputs intentionally skip without those
inputs. Do not set private-corpus variables to the synthetic package and claim
the original private-corpus checks passed.

The generator only uses artificial constants. It refuses an existing output
directory. Generate another scale in a new destination, for example:

```sh
.venv/bin/python tools/repertoire/generate_fixture.py \
  .repertoire-work/stress --copies 250 --books 9
```

Repeated records reproduce duplicate-source pressure without publishing real
books. Include fresh and warm caches, one and multiple selections, long histories,
out-of-book positions, edits/exclusions and long comments in before/after testing.
Measure complete native requests as well as index build time, disk growth and peak
memory. Add synthetic cases where needed rather than tuning only for this generator.
Host/Robolectric timings are not physical-phone latency or battery measurements.

## PC delivery compatibility

The backend is not in the hot lookup path. It verifies hash-bound PGNs/sidecars and
serves the source SQLite file to an authenticated paired phone. Current metadata
has `schema`, `sha256`, `bytes` and `repertoires`. The index is streamed with gzip,
SQLite content type, `X-Repertoire-SHA256`, and `Cache-Control: private, no-store`.
The client verifies the decompressed content, schema and integrity before replacing
its source. Current package limit is 128 MiB, schema 1, accepted resolver policies
2/3/4. Do not bypass stale/hash/unknown-policy checks or alter authentication.

An Android-derived index can preserve this protocol unchanged. If another design
requires changing it, supply a compatibility/migration proposal with the code.
This repository is not a runnable copy of the full PC service. End-to-end private
package validation and production deployment remain a later local step for Patchy.

## Privacy, handback and acceptance

Do not upload real PGNs, annotation sidecars, generated real databases, personal
edit backups, private screenshots, account tokens or signing keys. Generated
fixtures belong under ignored `.repertoire-work/` or a temporary directory. The
private source corpus and running services must not be touched by this task.

Return the code/PR, the chosen design, before/after measurements, build/memory
costs, and behavior tests. Include migration/failure recovery and cache invalidation
coverage. Do not claim that performance or preservation on the real phone/private
corpus was verified remotely. Patchy will run those available local acceptance
checks before any live library replacement or signed release.

There is no Telegram bridge in an ordinary GitHub checkout. Report in the thread
or PR rather than blocking on notifications or trying to reach production systems.
