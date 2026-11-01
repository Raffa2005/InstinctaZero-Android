# Click Chess

`ranker.mjs` and `origin-ranker.bin` are unmodified MIT-licensed files from
[Raffa2005/click-chess](https://github.com/Raffa2005/click-chess/tree/73453406b550a1c1026401d1c2db9a3c01e65397),
commit `73453406b550a1c1026401d1c2db9a3c01e65397`.
The complete license ships in `assets/analysis/click-chess-LICENSE.txt`.

The selected default model has 17,345 parameters (18,619 binary bytes), uses
feature version 2 and the previous two plies, and scores legal **origins** with a
96/32 hidden-layer MLP. It is the retained default, not the experimental round-2
candidate. Training used public CC0 Lichess games. No training corpus is included.

Pinned SHA-256 checks in `web/build-click-chess.mjs` cover the binary and upstream
runtime. `entry.mjs` is the GPL-3.0-or-later app adapter. Esbuild embeds the weights
in the offline script; the existing `connect-src 'none'` policy is unchanged.
No model download, engine call, network request, or WebView permission is added.

`web/test/click-chess-fixtures.json` derives from the same revision's 305 public
cross-language fixtures. It retains FEN/history/legal origins and expected results
from the upstream JSON INT8 model, to test the packaged binary against that model.

Rebuild with `npm run build:click-chess` from `web`; verify with `npm run check`.
