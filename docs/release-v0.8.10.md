# v0.8.10 — unified indexed repertoire library

Imported and personally added moves now use the same indexed position/move read
path in one authoritative SQLite library. Navigation no longer reconstructs the
authored graph from a JSON overlay. The simple design, account privacy, analysis,
canonical mainline navigation, return-to-repertoire intersections and portrait
orientation are unchanged.

Install the update over the existing app. Repertoire preparation happens once,
locally and off the UI thread. No repertoire redownload, re-pairing, source update
or PC restart is required. Source refresh keeps using the existing gateway.

## Preservation and recovery

Additions, exclusions/deletions, main/alternative choices, comments, local books,
selections and Undo migrate together. Source explanations remain separately
readable behind personal notes. Position-based transpositions and legal one-ply
re-entry retain contextual source classifications. Null-move analysis records
remain source provenance but are not exposed as playable moves.

The original source download and edit JSON are left intact as frozen recovery
inputs. They stop being live stores after migration. Current edits and the last
Undo are transactional in SQLite, with 100 recent audit revisions. Existing PC
backups retain their compatible v1 format and history. A current backup can be
restored on a replacement phone or by the old reader. Downgrade recovery requires
that current backup and source download, not stale pre-migration JSON. Android
also requires a recovery APK with an installable version code, or a separate
installation. Do not uninstall the current app just to try an older APK.

This does not rewrite local PGNs, archived games, source annotations or accounts.
The source-comment update's 27 unresolved references remain unresolved. This
release does not add or rewrite opening theory.

## Verification and measured limits

Complete results match v0.8.9 and the reviewed PR2 in 100 same-host synthetic
benchmark cases, including source/edited positions, one/nine books and long
history. Another 70 complete-response cases, 324 source-mask/label combinations
and a 1,008-position migration/refresh/restore rehearsal pass. Recovery tests run
on API 26 and 35, with transaction faults, stale writers, source-node renumbering,
personal/source notes, Undo and old-reader backup compatibility.

In the synthetic host benchmark, one cold authored-position request with 1,000
added edges went from 19.48 ms in v0.8.9 to 1.37 ms. A nine-book 140-ply request
went from 75.62 to 36.81 ms. These are native host measurements, not phone latency,
FPS or battery claims. Not every case is faster than PR2.

There is a write/storage trade-off. A 121,500-occurrence artificial library took
about 1.05 s to migrate and 1.63 s to refresh on the host. Its 76.84 MiB source
became a 145.24 MiB authoritative database, plus the retained original recovery
files. Peak temporary footprint during refresh was about 299 MiB. Graph edits
rebuild the affected repertoire off the UI thread. Full measurements and the
acceptance procedure are in [the implementation report](unified-repertoire-store.md).

Native debug/release tests: 134 discovered, 119 passed, 15 optional-input skips.
Python policy tests: 25 passed. Controller/browser checks: 65 passed. Phone-sized
touch checks cover repertoire comments/edits/Undo, re-entry in both orientations,
game loading, privacy, maneuvers and the board editor. No USB-phone testing.

Scoped private acceptance of `a04b5d1` passed on the current 1,079-entry personal
backup and old/current source snapshots. All 1,716 full-response comparisons pass
through migration, refresh, restart, Undo, replacement-phone restore and old-reader
rollback. The actual clarified-source/personal-note checks pass. Full current-index
suite: 134 discovered, 123 passed, 11 optional-input skips, zero failures. Private
inputs and live data were unchanged. Release publication also requires matching
production signer/app ID, version 38 / 0.8.10, portrait orientation, extracted-asset
touch checks and a byte-identical publicly downloadable APK.
