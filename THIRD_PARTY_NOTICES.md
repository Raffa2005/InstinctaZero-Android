# Third-party notices

## Legacy Lichess mobile Chessground

`web-src/legacy-chessground/` is a source-preserving snapshot of the
Chessground implementation from
[`lichess-org/lichobile`](https://github.com/lichess-org/lichobile), commit
`063167dda7119386cf36708d004d4565203d449a` (2018-04-09). It is bundled into
`app/src/main/assets/analysis/legacy-chessground.js` by
`web/build-legacy-chessground.mjs`.

- Copyright: Lichess contributors and the lichobile contributors
- License: GPL-3.0-or-later
- Source, upstream notice, and license: `web-src/legacy-chessground/`

## Cburnett pieces and legacy board

The included Cburnett SVG piece set and brown SVG board are byte-for-byte
legacy lichobile assets from the same revision. Cburnett artwork is attributed
to Colin M. L. Burnett and is GPL-2.0-or-later as recorded by the upstream
`COPYING.md`. The board is retained with the legacy source notice.

## chess.js

The local rules controller uses
[`chess.js`](https://github.com/jhlywa/chess.js) 1.4.0 by Jeff Hlywa.

- License: BSD-2-Clause
- Pinned source and integrity: `web/package-lock.json`
- Build/provenance: `web-src/chess-rules-PROVENANCE.md`
- Full text: `app/src/main/assets/licenses/Chess.js-BSD-2-Clause.txt`

## AndroidSVG

The native completed-game list renders the bundled Cburnett SVG artwork with
[`AndroidSVG`](https://github.com/BigBadaboom/androidsvg) 1.4 by Paul LeBeau,
Cave Rock Software Ltd.

- License: Apache-2.0
- Full text: `app/src/main/assets/licenses/Apache-2.0.txt`

## Fonts

- Noto Sans and Cousine: Apache-2.0
- Font Awesome webfont: SIL OFL-1.1

Their complete license texts are shipped in `app/src/main/assets/licenses/`.
The non-free legacy Lichess icon font (`lichess.ttf` and WOFF/WOFF2 variants)
is deliberately not included or redistributed.

InstinctaZero Android itself is distributed under GPL-3.0-or-later; the full
GPL version 3 text is in [`LICENSE`](LICENSE).
