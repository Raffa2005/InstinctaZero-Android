# Board editor

Open **Board editor** from Home/the sidebar to resume the last setup draft, or
**More → Board editor** on an analysis board to start from its current position.

- Choose a palette piece and tap squares to place it. Placing a king relocates
  that colour's existing king. **Move** supports drag or tap/tap; **Erase** removes
  pieces. **Undo** reverses up to 30 setup actions in the current editor session.
- **Clear** empties the board; **Reset** restores the standard starting position.
  Both can be undone. Flip changes only the viewing orientation.
- **State** selects White/Black to move, the four castling rights, an en-passant
  square and the halfmove/fullmove counters. Castling rights disappear when their
  home king/rook is removed; placing a rook does not silently grant castling.
  A castling right does not require the path to be clear yet.
- En-passant choices require the opposing pawn to have just advanced two squares,
  with its origin and passed-over square empty. A capture need not be available:
  an authentic FEN en-passant field is retained even without an adjacent capturer.
  Moving/placing/removing a piece clears stale en-passant state and resets the
  halfmove clock. Choosing en-passant also sets that clock to zero.
- **FEN** offers Paste, Use FEN and Copy FEN. Four-field input gets counters `0 1`;
  six-field input preserves them. Counters are bounded to 9999. Clipboard reading
  happens only when Paste is tapped. Copy exports the current setup, not a PGN.

Incomplete setups can be edited and saved as drafts. Analysis requires a valid
standard-chess position: exactly one king per colour, sensible piece/pawn counts,
no back-rank pawns, consistent castling/en-passant state, and no illegal opposite
check. The PC independently validates the root and replays every subsequent move.
This checks position validity, not a proof that a complete historical game could
have reached the position. Chess960/variant setup is not supported in this editor.

## Saved boards and analysis

**Analyse position** opens a separate position workspace with its own saved tree,
cursor and orientation. It does not change the original board, archived game or
PGN. Resetting this scratch board also leaves the original alone. Use
**More → Return to saved board** to return to that original. The most recently
analysed edited position remains stored separately, and the editor draft survives
restart. This is one scratch workspace, not a new multi-study library; analysing
another setup replaces that scratch workspace, not the original study/game.

Leela and the opening explorer require the paired PC's custom-position API update.
They retain the existing authentication/fair-play checks, backend choice and limits.
Local repertoire lookup continues to use board positions and normal transpositions;
opening/editing a setup does not alter repertoire data or selections.

Validation references: [chess.js](https://github.com/jhlywa/chess.js/) and
[python-chess position status](https://python-chess.readthedocs.io/en/latest/core.html#chess.Board.status).
