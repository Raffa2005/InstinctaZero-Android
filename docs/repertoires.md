# Repertoires

The PC source is the annotated repertoire package, not an indiscriminate PGN merge.
The installed source on Rafael's PC is `~/Repertoire` (not `~/projects/Repertoire`).
Only the corrected PGNs named by `annotations/manifest.json` are used (including
Tame the Sicilian, Queen’s Gambit Accepted, Taimanov Sicilian, Ruy Lopez, Jobava London
and Symmetrical English for Black);
`archive/` is not imported. The source package's PGN and sidecar fingerprints must
match the derived SQLite index before it can be downloaded.

## Use on the phone

1. Open **Repertoire board** on Home or in the menu. For first setup, open settings
   and **Download from PC** once, then select one or several repertoires.
   For the expanded library, install v0.7.4 or newer, tap **Update from PC**, then
   select Ruy Lopez and/or Jobava London. New repertoires are not auto-selected.
   Symmetrical English for Black is available through the same **Update from PC**
   action on v0.7.5; no new APK is needed. Choose it separately or combine it with
   other repertoires. The existing selections, edits and Undo survive the update.
2. Tap the bottom settings gear again to return to moves. There is no chooser
   on subsequent opens. Selections survive restarts; new games inherit your latest
   selection, while existing game-specific choices (including none) remain separate.
3. The gear holds all selection controls. **Combined** shows each move once;
   a name chip isolates a repertoire without altering its independent annotations.
4. Tap a move to play it. Its comment bubble opens all distinct source comments
   without playing the move. The current position's comment appears above the
   continuations; tap the preview to expand it. Forward/back navigation updates it.
   Introductions to a variation appear with a **Before move** label, separately
   from the after-move comments. Older downloaded packages remain readable.
5. The header reports position coverage and, when outside the book, the latest
   departure from it—not an engine verdict. A book on the destination square means
   the reached position is covered by a selected repertoire. Transpositions rejoin
   automatically, with the same continuations and comments as other move orders.
   **Book marker on board** in settings toggles the marker (default on).
   Markers check all selected repertoires, independent of the focused move-list view.
6. The sliders beside a move open local adjustments. Where repertoires overlap,
   choose which repertoire to adjust. **Add current line** is in settings.
7. **End of line** and a small finish flag on the book badge mark the last active
   theory move—even if informational continuations remain below it. In combined
   view, the panel names a repertoire if only some selected repertoires end there;
   the board flag appears only when none of the currently active selected
   repertoires has a theory continuation.
8. Play a new move (or several) on the board, then tap **Add move to repertoire**
   or **Add line to repertoire** directly below the continuations. The repertoire
   with the nearest covered prefix is chosen automatically, with its name shown
   before you tap. A focused view uses that repertoire. Only genuine ties ask.
   A confirmation appears after saving. You can navigate back to see/play the
   new continuation. There are no move-entry text fields. The **Comment** action
   after adding opens the position editor. You can also **Edit comment** below an
   expanded note, use **Write / edit comment** in move adjustments, or use position
   comments in settings. Save follows that position through transpositions.
   **Restore source text** removes your override; the original PGN is never changed.
9. After a change, tap **Undo** beside the confirmation, or find **Undo last
   repertoire change** in settings later. The action and affected repertoire are
   named there. One undo reverses the entire most recent local edit across the
   library: a whole added line, exclusion, optional-alternative label or removal/
   label restoration. It works after a restart and does not move your analysis
   cursor, delete the analyzed variation or alter repertoire selections. Earlier
   edits and other repertoires stay intact. This is one step, with no redo; it
   starts recording new changes in v0.7.3, not edits from older versions.

## Alternatives and information

- Main repertoire moves count as theory.
- Valid alternatives remain theory. Only the repertoire colour introduces an
  alternative; opponent replies have regular labels, even inside an optional line.
- Refutations, informational analysis and marked model-game continuations remain
  viewable, with their reasons and comments, but are not active theory.
- Book lookup uses the board position: pieces, side to move, castling rights and
  legally available en passant, without move counters or incoming move history.
  All same-position occurrences within each repertoire contribute continuations
  and deduplicated comments. An active occurrence qualifies a move even when an
  informational duplicate exists elsewhere. Purely informational moves remain so.
- A covered position rejoins the book even after an unknown or excluded route.
  This changes book coverage, not the actual game/analysis move history. The PC's
  source-path annotation and training semantics remain unchanged.
- End of line means no active continuation exists across any occurrence of that
  position in the repertoire, not merely that one source line stops there.

## Adjustments

Each repertoire has independent local overrides, automatically saved on the phone:
make an active own-side move optional; exclude a branch; restore the original
label; add the current board line; remove a local addition. New adjustments identify
the position and outgoing move, so they apply across move orders in that repertoire.
Existing path-scoped edits are still respected within their source subtrees; another
independently active occurrence can supply book coverage. Extensions anchor at the
most recent covered position and add only the missing moves, not the incoming order.
These changes do not
rewrite source PGNs, PC annotations or archived Lichess games. Excluded branches
remain viewable and can be restored. Adding a line cannot silently cross an
informational source move. To activate source information, review its PC annotation
rules and rebuild their index, then update the phone copy.

## Storage and limits

**Repertoire library → New repertoire** creates an empty, named White or Black book
without requiring pairing or a download. Open the book, play a line and add it.
Opening a particular library book selects it for the current board; **Repertoire
board** keeps the existing combined selection. Phone-created books can be renamed.
Creation and renaming preserve the last line/comment Undo step. They are kept apart
from downloaded books and survive PC library updates. There is no automatic PC sync
or export for phone-created books in this version.

Up to 64 phone-created repertoires and 16 simultaneous selections are supported.
Names have an 80-character limit; position comments have a 64 Ki-character limit.
Metadata, added moves and comments share the existing 4 MiB atomic local-edit budget.
Variation introductions remain separate read-only **Before move** source notes;
the editor changes the position's after-move comment, not source classifications.
Uninstalling/clearing app data removes this phone-only work, just as with prior local edits.

- One private SQLite download holds the corpus; only indexed current-position
  queries and small result sets enter the WebView. No per-move network request.
- Already known child-position coverage/comments are projected immediately on any move
  or navigation action. Full visited-position results are cached up to 96 entries
  and 2 MiB of estimated text data; misses still use native SQLite. The cache is
  keyed by complete history, initial position and selected repertoires, and is
  invalidated on edits/download updates. History remains in the cache key only
  because extension/deviation context can differ; it does not restrict book coverage.
- Downloads use the existing paired-device token and HTTPS gateway. Up to 128 MiB
  uncompressed; gzip in transit. Checksummed installation replaces the prior copy
  only after validation. A slow or interrupted download leaves that copy usable.
- The six-repertoire PC index is about 100.2 MiB on disk / 19.3 MiB over gzip.
  It includes repeated source occurrences,
  positions, annotation reasons and lookup indexes. It is streamed to disk in
  64 KiB chunks, not retained in a giant JavaScript or native byte array. These
  sizes are for the September 7 package; the 128 MiB cap is not an allocation.
- Local overrides have a 4 MiB cap, are keyed by repertoire and stable position/move
  identity, and survive corpus updates. Legacy history keys remain readable without
  migrating or dropping saved edits/Undo. Edits are phone-local, not cloud-synced.
- One inverse edit journal is stored alongside the overrides in the same atomic
  file. It adds only the prior values of the paths touched by that action; the
  existing 4 MiB override limit excludes this journal. No-op or failed edits keep
  the previous undo. A completed undo consumes it. Token and state checks prevent
  an obsolete button or repeated tap from undoing a newer action. Legacy override
  files remain readable, and invalid/stale journals are ignored without dropping
  overrides. Corpus downloads preserve the local undo step; downloads themselves
  and UI-setting changes are not repertoire edits and are not undone.
- Up to 16 repertoires can be selected; the existing analysis history limit is
  512 plies. All distinct comments at the current position and its continuations
  are returned in full, including comments from additional source occurrences.
  They are rendered as plain text, never executable HTML.
- No SRS trainer, arbitrary unannotated PGN importer or source-rule editor is
  introduced in this pass. This feature does not resurrect the reverted study UI.
- The English source retains 333 records (29 main chapters plus orientation and
  quickstarter sections) and their headers/comments/variations in the index.
  The current phone UI is a position-based book, not a chapter-by-chapter viewer.
  Optional …Bf5 starts at Black's sixth move in records 331–333; the shared prefix
  is regular repertoire. White replies keep regular labels inside that optional
  context. Rejected examples and post-cutoff continuations remain viewable but
  are excluded from normal source training. Policy 4 preserves explicit author
  recommendations in resolved source annotations; it changes neither the SQLite
  layout nor v0.7.5's book lookup. The PC must support policy 4 before downloading.
- The private corpus is never included in the public source repository or APK.

The server accepts `INSTINCTAZERO_REPERTOIRE_DIR` to point at another complete
annotated package. The default is the server user's `~/Repertoire`. The routes
`GET /api/mobile/v1/repertoires` and `GET /api/mobile/v1/repertoires/index` require
authentication and are read-only. Changes to the package require an explicit
**Update from PC**; cached comparisons remain available offline.
