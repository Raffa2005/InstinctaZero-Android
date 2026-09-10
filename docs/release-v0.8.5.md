# v0.8.5 — Repertoire editing, backups and reliable navigation

- At a known repertoire position, **Add just-played move** saves the response you
  just made. This fixes the reported …b4 case without changing the earlier Bd3
  informational annotation. Adding a continuation or a missing whole line remains
  a separate action.
- The sliders beside a move offer **Delete repertoire move** and, for your own
  colour, **Make main recommendation**. Deletion hides this repertoire association
  at every transposition. Dependent data stays saved, and independent routes stay
  available. Use **Undo**, or **Deleted moves here** in repertoire settings, to
  restore it. Original PGNs never change.
- Choosing a main recommendation labels the other active candidates as alternatives.
  A single candidate is not labelled an alternative. Ambiguous imported choices
  remain unranked until you choose, without an invented engine ranking.
- **Repertoire library → Backups and restore** keeps private, recoverable edit
  history on the paired PC. Edits save locally first and upload when connected.
  The screen shows pending/confirmed status and offers manual backup and restore.
- In the **Repertoires** tab, the existing return symbol jumps to the nearest
  earlier notation or relevant repertoire intersection, including transpositions.
  Other tabs keep notation-only Return to mainline. Forward navigation remains
  canonical and never follows a variation just because it was recently visited.
- Interrupted Leela streams recover on the current position with a visible,
  bounded retry. Stale responses cannot overwrite it. Book markers use lightweight
  local facts before full comments arrive, with position-based cache reuse.

Install this APK over the existing app. No re-pairing or repertoire redownload is
needed. The required PC backup endpoint has been deployed separately. The simple
design, privacy mode, portrait lock, board editor, saved games and local PGNs remain
intact. No USB-phone testing was used.

## Backup scope and recovery

Backups contain local additions, deletions, recommendation labels, comments,
phone-created repertoire names/colours, selection settings and the last Undo.
They do not contain authentication, saved games or source PGN files. The corpus
and original PGNs remain on the PC and are never rewritten by these operations.

Up to 100 snapshot versions are retained, bounded by 64 MiB compressed. They live
in the private `.mobile-repertoire-backups/history.sqlite3` folder beside the PC's
state database, not in Git or the public APK. This protects against losing the
phone, not against losing both phone and PC. Include that folder and the original
repertoire package in the PC's own private backups if you need that protection.

On a replacement phone, pair the same PC and download its repertoire library.
Open **Repertoire library → Backups and restore**, refresh history, select a dated
version and confirm. Current edits are backed up before replacement. Restore is
atomic, invalidates book caches and does not change games, pairing or privacy.
Offline edits remain local until a PC upload is confirmed. Simultaneous editing on
multiple phones does not merge automatically: history stores separate snapshots.

## Verification

Native regressions cover the exact pictured position, separate add operations,
deletion/Undo/restore, independent transposition routes, recommendation consistency,
legacy edits, replacement-phone recovery, failed/racing restore, private routes,
SSE EOF/final-only/cancellation framing, portrait recreation and privacy.

`repertoire-editing-ui.mjs` exercises native-corpus fixtures in touch Chromium at
360×640, 390×780 and 412×844, including the repertoire intersection action, staged
markers, rapid navigation, stale responses and recovery. Existing privacy, game
loading, authoring, transposition and board-editor checks remain part of release
verification. The private corpus is an optional test input, never a public asset.

## Measured marker path

On the nine-repertoire corpus in native Android/SQLite host tests, the marker
previously waited for the complete response. Representative cold timings in ms:

| Selection / position | Previous full response | New first-stage marker | New full response |
| --- | ---: | ---: | ---: |
| Taimanov / start | 62.61 | 1.05 | 24.68 |
| Nine / start | 49.91 | 2.32 | 58.83 |
| Nine / after e4 | 35.34 | 2.07 | 44.41 |
| Nine / pictured …b4 | 15.86 | 1.64 | 17.86 |

These are host measurements, not phone benchmarks or a claim that the entire UI
is faster by the same ratio. Full cold responses still load all comments and can
take tens of milliseconds. Warm markers are cached. A 4× CPU-throttled touch
renderer completed 16 rapid navigation actions in 128–139 ms total, and a 161-ply
game import in 46–47 ms. Deliberately delayed full replies did not delay first-stage
markers. Network delays can still affect Leela, and cold/local scheduling can
still cause small delays. No content was removed to obtain these results.
