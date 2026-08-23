# chess.js rules-engine provenance

The offline analysis board uses [chess.js](https://github.com/jhlywa/chess.js)
version **1.4.0**, by Jeff Hlywa, distributed under BSD-2-Clause.

`web/package.json` pins the exact package version and `web/package-lock.json`
pins both the npm tarball URL and SHA-512 integrity value. The generated browser
asset is `app/src/main/assets/analysis/chess-rules.js`; rebuild it with:

```sh
cd web
npm ci
npm run check:chess-rules
```

The build script bundles the package's ESM distribution as the global
`InstinctaZeroChessRules`. Its `Chess` export is the only API intended for the
local study controller. The check runs against the generated asset and verifies
ordinary legal moves, castling, en-passant, and promotion/SAN behavior.

The binary distribution notice is retained at
`app/src/main/assets/licenses/Chess.js-BSD-2-Clause.txt`.
