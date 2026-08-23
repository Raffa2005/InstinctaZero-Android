# Legacy Chessground provenance

This directory is a source-preserving vendor snapshot of `src/chessground` from
[`lichess-org/lichobile`](https://github.com/lichess-org/lichobile), commit
`063167dda7119386cf36708d004d4565203d449a` (2018-04-09).

The source files in `src/` and the ambient declarations in `types/lichess.d.ts`
are copied verbatim from that revision. `COPYING.md`, `UPSTREAM-README.md`, and
`LICENSE-GPL-3.0.txt` are the corresponding upstream documents. The legacy
Chessground code is GPL-3.0-or-later under that upstream notice.

`assets/` contains byte-for-byte copies from the same revision:

- `board/brown.svg` is the legacy brown SVG board.
- `pieces/` is the Cburnett SVG piece set (GPL-2.0-or-later, per
  `COPYING.md`).
- Noto Sans and Cousine are Apache-2.0; Font Awesome is SIL OFL-1.1. Their
  license texts are vendored in `assets/licenses/`.

Do not add `lichess.ttf` or any `.woff2` font to this distribution.
