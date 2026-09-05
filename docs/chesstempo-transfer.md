# ChessTempo repertoire transfer

Checked against ChessTempo's public official documentation on 2026-09-05. No
account was accessed or changed.

## Official export

In Opening Training, open the actions menu under the board and choose **Export**.
The [official manual, section 17.24.2](https://chesstempo.com/manual/en/manual.html)
documents exports of the current repertoire, all White repertoires, all Black
repertoires, or both colors. A combined export writes each repertoire as a separate
PGN game. Your own/imported comments are included by default, with an option to
omit them. Use this repertoire export, not a move-list/current-line PGN command.

[Official support](https://vps.chesstempo.com/forum/topic/no-and-quotshow-pgn-and-quot-in-opening-trainer/12519/)
explains that purchased-book content may be restricted by its publisher; repertoire
Export respects those restrictions. This is not a promise that every purchased
course can be exported. The supported transfer found is PGN export; no documented
official repertoire-sync API was found in the sources checked.

## Import into InstinctaZero

1. Keep your original exports as backups. Export White and Black separately, or
   individual repertoires when large. Give files useful names, e.g. `White - Catalan.pgn`.
2. In InstinctaZero, choose **Studies / PGN → Import PGN** (one file at a time).
   Each file creates a separate named study; each PGN game becomes a chapter,
   retaining its branching lines. A combined ChessTempo export can therefore map
   its repertoires to chapters, subject to the limits below.
3. Open a chapter and choose **More → Repertoire → White/Black**. This marks all
   chapters in that study. Avoid mixed-color exports unless separated first:
   ChessTempo-specific color metadata is not automatically interpreted.
4. Repeat for the remaining files. All studies marked for the same color contribute
   to the local matching graph, so the repertoire can span multiple files/studies.
   Position matching recognizes transpositions. Edit lines using normal board moves
   and variation deletion. This is repertoire matching, not a spaced-repetition trainer.

## Current limits and caveats

- Import: 1 MiB file bytes, at most 100 PGN games, 4096 parsed moves across the entire
  import (including repeated prefixes), 512 plies per line, 64 nested variations.
  Per-chapter saved JSON is also bounded. Oversized imports are rejected, not silently
  truncated. A single enormous tree may need to be split by branches on the PC while
  preserving each branch's full starting-position move history; splitting only by
  games is insufficient when one repertoire exceeds the node limit.
- There is no multi-file batch picker, folder hierarchy, automatic merging of studies,
  automatic splitting, or cloud/account synchronization. Files import separately;
  same-color matching combines their positions. Data is local to this phone.
- Inactive chapter trees are not retained, but the enabled repertoire position graph
  is in memory. Very large repertoires are not yet a validated large-scale workload;
  exporting everything into one huge PGN will not bypass the import limits.
- Standard PGN branches, comments, headers and numeric annotations round-trip.
  ChessTempo-specific training history, review schedules, enabled/disabled training
  flags and book structure are not interpreted. Comment markup is preserved as text,
  not necessarily rendered as ChessTempo diagrams/arrows. Stored comments can be
  exported, but the current move list is not a full comment editor/viewer.
  A move comment longer than 16,000 characters is currently shortened on chapter
  load, another reason to retain the original export and audit a large transfer.
- Imported custom-FEN roots are editable/exportable locally; engine/book requests
  require a standard-start study or an existing archived game with this PC gateway.
- Whole-study export currently has a 2 MiB text handoff ceiling. Export chapters
  individually for large studies. No source PGN or archived Lichess PGN is overwritten.
- New imports use the filename without `.pgn`; shared text without a filename falls
  back to Event or “Imported study.” Existing studies are not retroactively renamed:
  their original filenames were not captured by earlier versions.
