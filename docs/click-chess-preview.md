# Destination input preview

Tap an empty or capturable destination without selecting a piece. The bundled
tiny neural network chooses among legal origins. Tap that destination again to
cycle alternatives from the **original position**; the header explains when this
is available. Choosing another square or using a control ends that correction.
For an immediate recapture on that same square, select the capturing piece first.
Normal piece-then-destination input and dragging retain explicit origin priority.
Settings → One tap switches the feature off and persists across restart.

Promotion piece choice still opens the existing picker, including all four
underpromotion choices. A promotion ends the origin-cycling session; select its
source explicitly when a different promoting pawn is intended. Board editing is
unchanged. Input cancels on navigation, game replacement, backgrounding, touch
cancellation or multiple fingers. Canceled destination drags do not enter moves.

The model sees only the pre-move FEN, chess.js legal moves and the previous two
study plies. Legality is checked by chess.js. Legal verbose moves are cached per
FEN and reused by the board and input; repeated taps reuse the ranked origins.
Corrections remove only the just-created leaf, preserving existing branches and
descendants, then use the existing durable delta save once for the replacement.
Save errors remain visible and retryable. No optimistic saved-state claim is added.

The new `clickPreview` build installs as **InstinctaZero Click**, package
`com.instinctazero.android.clickpreview`. It uses the Android development signing
configuration and separate storage; production app data and pairing are untouched.
Pair this separate preview if PC analysis is needed. It is not a production-signed
upgrade, and preview signing keys are not a promise of future update compatibility.
Production signing/private-PC acceptance remain with the existing release owner;
no private corpus or production keys are needed for this preview.

CI runs native unit tests, lint and the preview build, checks APK identity and
signature, verifies pinned-model parity, and runs the public phone-sized browser
regressions **against extracted APK assets**. Only a successful push build on the
feature branch publishes a prerelease APK and SHA-256 checksum. The production
latest release is not replaced. No physical Android device or private PC is used.

The touch UI test measures ranking and complete synchronous input-handler work
(legal move application, study-tree changes, mocked save bridge and synchronous
render work) at 360, 390 and 412 pixels with 4× CPU throttling. CI retains timing
JSON and screenshots; these are browser measurements, not Android storage or
device frame-time measurements. Real native durable writes remain a device-bound
latency constraint. The upstream holdout accuracy is not a guarantee of intent.
