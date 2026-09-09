# v0.8.3 — Easier position editing

- Drag any piece to move it, even with a palette piece selected or Erase active.
- Tap an identical piece to remove it. Deselecting a palette piece selects Erase.
- Compact icon-led tools with short labels, and readable coordinates in both views.
- **Reverse** rotates the actual position 180° when it was entered from the wrong
  side. It keeps piece colours and side to move unchanged and supports Undo. The
  header **Flip** still changes only the view. After Reverse, check castling and
  en-passant in State; incompatible rights are cleared, and Undo restores them.

Open **Board editor** from Home or **More → Board editor** in analysis. Your saved
board/game remains separate. Existing PGNs, repertoires, pairing, navigation and
portrait behavior are unchanged. No PC restart or repertoire redownload is needed.

Verification: JavaScript model/controller tests, Android debug/release regressions,
release lint and signed APK checks. Touch previews at 360×640, 390×780 and 412×844
cover tool-independent drag, fast/occupied drops, cancelled and multitouch gestures,
tap toggles, coordinates, reverse/Undo, FEN/state, independent source-board storage
and restart. Checks are repeated against the signed APK's assets alongside existing
game, mainline/return and repertoire/Undo/transposition previews. The USB phone is
not used for testing.
