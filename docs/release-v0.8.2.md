# v0.8.2 — Board editor

- Board editor on Home, in the sidebar and under More on the analysis board.
- Same Chessground and Cburnett pieces. Tap to place pieces, drag/tap to move,
  erase, clear/reset and undo setup changes. Choose the turn, castling rights,
  en-passant and counters without numeric text fields.
- FEN paste/import and copy/export, with clear validation messages. Setup drafts
  survive restart; clipboard reading is explicit, not automatic.
- Analyse a setup in a separate saved position workspace. Leela, the opening
  explorer and local repertoire lookups use the correct custom root. The original
  study/game is preserved; More → Return to saved board brings it back. Resetting
  edited analysis does not reset that original board.

This is standard-chess setup, not a multi-study redesign. Existing mainline/return
navigation, comments, repertoire edits/Undo, game-load cancellation, saved choices
and portrait orientation remain. No PGNs or repertoire data are rewritten and no
repertoire redownload is required. The paired PC needs the matching custom-root
mobile API support; existing device credentials, URL and Funnel routes are unchanged.

## Verification

- 82 Android/Robolectric tests, including separate source/position persistence,
  restart, rejected writes, bounded FEN transport and native menu touch targets.
- 48 JavaScript/controller tests, including FEN round trips, en-passant,
  unavailable castling rights, invalid placements, terminal positions and the
  existing navigation/repertoire regressions.
- 33 PC mobile API tests, including custom-root replay, en-passant, invalid
  setups, unchanged archived data and the existing authentication/fair-play gate.
- Touch previews at 360×640, 390×780 and 412×844: palette placement, real touch
  drag, erase, Undo/reset, state controls, clipboard, keyboard layout, custom-root
  analysis/book requests, independent source board and restart. Checks are repeated
  against assets extracted from the signed APK. Existing game-loading, mainline,
  Return to mainline and repertoire previews are retained.
- Existing full-response comparisons and 648 independent real-corpus reference
  cases retain repertoire semantics. The SQLite/PGN corpus is not bundled in the APK.

Verification uses host-native Android/SQLite and browser environments, not the
USB-connected phone. That phone remains solely the internet tether. Standard
release signing, ZIP alignment, portrait manifest and public-download checks apply.
See [Board editor](board-editor.md) for controls and storage limits.
