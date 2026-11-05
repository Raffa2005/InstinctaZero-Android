# Offline destination input — v0.8.13

Tap a destination without selecting a piece. The bundled trained model chooses
the highest-ranked legal origin. If it guessed incorrectly, **drag the moved piece
from its destination back to its original square**. This immediately undoes the
move. Tap the same destination from that restored position to try the next origin.
Repeat undo-and-tap to cycle; tapping by itself never cycles. A same-square tap can
play a legal recapture for the other side.

Normal piece-then-destination input and dragging still select an explicit origin.
Promotions always show the piece picker, including underpromotions. A promoted
piece can be dragged back too; the next origin still requires a promotion choice.
Settings → One tap disables destination input and remembers that choice.

Undo restores the parent FEN and study path, including captures, en passant,
castling rights and the rook, counters, promotions and the previous-two-ply model
context. A corrected leaf is removed and the undo is saved immediately, even
before the next tap. A nonleaf and all its descendants stay intact. Forward
navigation still follows the canonical first child. No source game or PGN changes.

The cycling state belongs to the controller, not the model. It is cleared on
ordinary move entry, navigation, game replacement, board editing, leaving analysis,
backgrounding, touch cancellation and outside controls. It is deliberately not
restored after reopening. Invalid drags do not play moves. Piece/square graphics
are not touch targets: the stable board receives gestures even while pieces animate.

The model and dependency-free runtime are unchanged upstream files; 305 reference
fixtures check exact ranking/probability parity after packaging. The complete
model is 18,619 bytes; no inference framework or training corpus is bundled.
Legal moves are cached for the current FEN; each session retains one ranking.
All input works offline under the unchanged WebView network policy.

## Release and validation

This is a signed update to **InstinctaZero** (`com.instinctazero.android`), not the
former separate **InstinctaZero Click** preview. Install over the normal app.
No pairing reset, repertoire download, PC restart or network change is needed.
The isolated preview's separate data is not automatically imported into production.

The former e32283b preview APK was 5,877,955 bytes. Main v0.8.12 was 1,591,667 bytes.
Building e32283b with the same optimized, production-signed release configuration
produced 1,619,256 bytes. The large preview principally contained unshrunk DEX
code, not a large model. The final production artifact is measured at release
acceptance; it keeps release code/resource shrinking and minifies the new JS.

Run `npm run check --prefix web` and `node web/test/click-chess-ui.mjs` with
`PHONE_PREVIEW_CHROMIUM`, `PHONE_PREVIEW_ASSETS` (extracted signed APK assets) and
`PHONE_PREVIEW_OUTPUT` configured. The touch suite checks 360×640, 390×780 and
412×844, flipped orientation, all candidate cycles, offline input, same-square
recaptures, stale state, explicit input, special moves, leaf/nonleaf behavior,
reopening, and failed-save retry. It emits synthetic `study-traces.json`.

`ClickInputPersistenceTest` replays those actual controller deltas against native
Android SQLite at API 26 and 35. Set `CLICK_CHESS_TRACES` to that trace file and
`CLICK_CHESS_PROCESS_FOLDER` to a fresh directory whose name starts
`iz-click-process-`. Run once with `CLICK_CHESS_PROCESS_PHASE=write`, then in a
separate Gradle/JVM invocation with `read` (rerun the test task). The read phase
never initializes or overwrites test data. Both phases compare the complete
persisted tree and cursor; legacy recovery copies remain available.

Native tests/lint and existing large-study, navigation, privacy, repertoire,
editor, game-load and maneuver-arrow regressions complement these checks.
Phone-sized touch Chromium uses 4× CPU throttling and a synthetic save bridge;
its timings are not physical phone or Android WebView/storage benchmarks.
The USB tethering phone must not be used for testing.
