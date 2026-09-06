# Repertoires

The PC source is the annotated repertoire package, not an indiscriminate PGN merge.
The installed source on Rafael's PC is `~/Repertoire` (not `~/projects/Repertoire`).
Only the three corrected PGNs named by `annotations/manifest.json` are used;
`archive/` is not imported. The source package's PGN and sidecar fingerprints must
match the derived SQLite index before it can be downloaded.

## Use on the phone

1. Open **Repertoires** on Home or in the menu; tap **Download from PC** once.
2. Select one or several repertoires. Tap **Compare selected** to use the board.
3. During game analysis, open the new repertoire tab to change this game's selection.
   **Combined** shows selected repertoires together, labelled separately. A name
   chip isolates one repertoire without changing the selected set or another repertoire's edits.
4. The header reports repertoire status. The repertoire panel identifies whose
   move first left the recorded history. This is a coverage label, not an engine verdict.
5. Tap a move to play it. Tap its sliders icon for annotation details and local adjustments.

## Alternatives and information

- Main repertoire moves count as theory.
- Valid alternatives remain theory. Only the repertoire colour introduces an
  alternative; opponent replies have regular labels, even inside an optional line.
- Refutations, informational analysis and marked model-game continuations remain
  viewable, with their reasons and comments, but are not active theory.
- Full UCI history is checked. Recorded transpositions remain supported; a FEN
  match alone cannot reactivate an excluded or unknown history. Such a position is
  reported as a match in another context, not as being back in repertoire.
- A terminal theory position still counts as theory with no further coverage.

## Adjustments

Each repertoire has independent local overrides, automatically saved on the phone:
make an active own-side move optional; exclude a branch; restore the original
label; add the current board line; remove a local addition. These changes do not
rewrite source PGNs, PC annotations or archived Lichess games. Excluded branches
remain viewable and can be restored. Adding a line cannot silently cross an
informational source move. To activate source information, review its PC annotation
rules and rebuild their index, then update the phone copy.

## Storage and limits

- One private SQLite download holds the corpus; only indexed current-history
  queries and small result sets enter the WebView. No per-move network request.
- Downloads use the existing paired-device token and HTTPS gateway. Up to 80 MiB
  uncompressed; gzip in transit. Checksummed installation replaces the prior copy
  only after validation. A slow or interrupted download leaves that copy usable.
- Local overrides have a 4 MiB cap, are keyed by repertoire and stable history
  identity, and survive corpus updates. They are phone-local, not cloud-synced.
- Up to 16 repertoires can be selected; the existing analysis history limit is
  512 plies. Source comments shown in a move detail are capped at 4,000 characters.
- No SRS trainer, arbitrary unannotated PGN importer or source-rule editor is
  introduced in this pass. This feature does not resurrect the reverted study UI.
- The private corpus is never included in the public source repository or APK.

The server accepts `INSTINCTAZERO_REPERTOIRE_DIR` to point at another complete
annotated package. The default is the server user's `~/Repertoire`. The routes
`GET /api/mobile/v1/repertoires` and `GET /api/mobile/v1/repertoires/index` require
authentication and are read-only. Changes to the package require an explicit
**Update from PC**; cached comparisons remain available offline.
