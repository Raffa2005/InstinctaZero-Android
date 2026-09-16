# v0.8.8 — local repertoire transposition re-entry

The book now unions recorded continuations with legal one-ply moves into eligible
positions in the same repertoire, at terminal **and nonterminal** positions. A
move appears once and playing it loads the destination material. End-of-line
markers and repertoire intersections use the same eligibility. No network query,
engine search, arbitrary multi-ply closure or special add workflow is involved.

Existing informational/refutation/model-game edges are not promoted. Destination
eligibility respects source occurrence flags, personal exclusions and independent
transposition routes. Deleted inferred associations remain deleted after restart,
source refresh and backup restore. Recommendations can still be chosen manually;
a position match alone does not invent a ranking between incoming moves.

Personal comments remain in their existing overlay. When present, original/source
notes are separately readable under “Source note” in the existing comment panel.
The editor only edits the personal text. No source PGNs or game files are rewritten.

## Implementation and checks

- Pinned Chesslib 1.3.7 supplies native standard-chess legal moves/SAN. Position
  keys retain turn, castling and **legal** en passant, excluding move counters.
  Existing browser chess rules and board behavior are unchanged.
- One-ply geometry cache: at most 256 positions. Choice facts: separate 512 KiB /
  2,048-entry budget, cleared alongside other facts after edit, Undo, install and
  restore. Departure and extension context still use the actual game history.
- Independent python-chess reference: 648 positions across all nine installed
  repertoires, eight additional re-entry associations, exact existing comments and
  labels, and 23,192 legal move/destination/SAN comparisons across those positions.
  This is sampled corpus coverage, not an exhaustive source audit.
- Focused native tests: terminal/nonterminal union, destination content, explicit
  refutation versus active destination, inactive targets, exclusion/deletion,
  restoration/Undo, cache revision, turn/rights/EP/promotion, and renumbered-source
  refresh with personal additions, main choice, deletion, notes and backup restore.
- Touch UI checks at 360×640, 390×780 and 412×844, both orientations: actual native
  response fixtures, ordinary move buttons, correct markers and source/personal
  comment coexistence. Existing privacy, navigation, normal informational add,
  editing/Undo, game loading, maneuver arrows and portrait contracts remain covered.

Host native-SQLite measurements (not phone latency): with repertoire-intersection
checks, warm one/nine-selection opening and transposition lookups were about
0.3–1.8 ms in the final run. A 160-ply, nine-selection game took about 84 ms on first
lookup and 10 ms warm in the deliberately long-history benchmark. Choice caching removed
repeated one-ply work (that warm long-history case was about 90 ms without it).
These are complete native requests on this host, excluding WebView/transport.

## Dataset coordination

The APK works with the currently installed library: **no redownload is required
for this app fix**. Source-reference clarification is a separate coordinated
update. At preparation time its immutable snapshot was not ready; no snapshot has
been accepted or installed. Actual old-to-new snapshot preservation, deployment
and source-audit conclusions remain pending and must not be represented as done.

The app does not clear studies, pairing, games, repertoire selections, personal
edits or backups. No backend restart or tethering-phone test is needed for this APK.
