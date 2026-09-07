# Repertoires

The PC source is the annotated repertoire package, not an indiscriminate PGN merge.
The installed source on Rafael's PC is `~/Repertoire` (not `~/projects/Repertoire`).
Only the three corrected PGNs named by `annotations/manifest.json` are used;
`archive/` is not imported. The source package's PGN and sidecar fingerprints must
match the derived SQLite index before it can be downloaded.

## Use on the phone

1. Open **Repertoires** on Home or in the menu. For first setup, open settings
   and **Download from PC** once, then select one or several repertoires.
2. Tap the bottom settings gear again to return to moves. There is no chooser
   on subsequent opens. Selections survive restarts; new games inherit your latest
   selection, while existing game-specific choices (including none) remain separate.
3. The gear holds all selection controls. **Combined** shows each move once;
   a name chip isolates a repertoire without altering its independent annotations.
4. Tap a move to play it. Its comment bubble opens all distinct source comments
   without playing the move. The current position's comment appears above the
   continuations; tap the preview to expand it. Forward/back navigation updates it.
5. The header reports coverage and the first deviation, not an engine verdict.
   A filled book on the destination square means the move is in a selected repertoire.
   An outlined book means a position match by a different history; it does not
   reactivate theory. Locally excluded/removed candidate lines do not qualify.
   **Book marker on board** in settings toggles both markers (default on).
   Markers check all selected repertoires, independent of the focused move-list view.
6. The sliders beside a move open local adjustments. Where repertoires overlap,
   choose which repertoire to adjust. **Add current line** is in settings.
7. **End of line** and a small finish flag on the book badge mark the last active
   theory move—even if informational continuations remain below it. In combined
   view, the panel names a repertoire if only some selected repertoires end there;
   the board flag appears only when none of the currently active selected
   repertoires has a theory continuation.
8. Play a new move (or several) on the board, then tap **Add move to repertoire**
   or **Add line to repertoire** directly below the continuations. Choose a target
   in combined view; in an individual view it saves directly to that repertoire.
   A confirmation appears after saving. You can navigate back to see/play the
   new continuation. There are no move-entry text fields. Comment writing is
   not included yet; existing comments retain the same read/expand behavior.

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
- Already known child eligibility/comments are projected immediately on any move
  or navigation action. Full visited-position results are cached up to 96 entries
  and 2 MiB of estimated text data; misses still use native SQLite. The cache is
  keyed by complete history, initial position and selected repertoires, and is
  invalidated on edits/download updates. A transposition never becomes theory
  merely because another history or position was cached.
- Downloads use the existing paired-device token and HTTPS gateway. Up to 80 MiB
  uncompressed; gzip in transit. Checksummed installation replaces the prior copy
  only after validation. A slow or interrupted download leaves that copy usable.
- Local overrides have a 4 MiB cap, are keyed by repertoire and stable history
  identity, and survive corpus updates. They are phone-local, not cloud-synced.
- Up to 16 repertoires can be selected; the existing analysis history limit is
  512 plies. All distinct comments at the current history and its continuations
  are returned in full, including comments from additional source occurrences.
  They are rendered as plain text, never executable HTML.
- No SRS trainer, arbitrary unannotated PGN importer or source-rule editor is
  introduced in this pass. This feature does not resurrect the reverted study UI.
- The private corpus is never included in the public source repository or APK.

The server accepts `INSTINCTAZERO_REPERTOIRE_DIR` to point at another complete
annotated package. The default is the server user's `~/Repertoire`. The routes
`GET /api/mobile/v1/repertoires` and `GET /api/mobile/v1/repertoires/index` require
authentication and are read-only. Changes to the package require an explicit
**Update from PC**; cached comparisons remain available offline.
