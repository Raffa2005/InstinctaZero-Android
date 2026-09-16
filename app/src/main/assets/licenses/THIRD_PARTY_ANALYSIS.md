# Offline analysis assets

* Native repertoire one-ply move generation uses Chesslib 1.3.7
  (https://github.com/bhlangonijr/chesslib) and Apache Commons Lang 3.18.0,
  both Apache-2.0. The complete license is in `Apache-2.0.txt`.

* `analysis/legacy-chessground.js` is derived from the Chessground implementation in
  lichobile commit `063167dda7119386cf36708d004d4565203d449a`. Per its upstream
  `COPYING.md`, they are GPL-3.0-or-later; the source and license are retained in
  `web-src/legacy-chessground/` and `GPL-3.0.txt`.
* The Cburnett piece SVGs are GPL-2.0-or-later, per the same upstream notice. The brown
  board SVG is preserved with the legacy source notice.
* Noto Sans and Cousine are Apache-2.0 (`Apache-2.0.txt`). Font Awesome is SIL OFL-1.1
  (`OFL-1.1.txt`). They are bundled only for local rendering. `lichess.ttf` and every
  lichess WOFF/WOFF2 font are deliberately excluded.
* `analysis/chess-rules.js` is generated from the pinned `chess.js` 1.4.0 package
  (https://github.com/jhlywa/chess.js), recorded with its tarball integrity hash in
  `web/package-lock.json`. It is BSD-2-Clause; its full notice is in
  `Chess.js-BSD-2-Clause.txt`. The offline study controller uses it for legal move
  generation, FEN, SAN, castling, en-passant, and promotions.
