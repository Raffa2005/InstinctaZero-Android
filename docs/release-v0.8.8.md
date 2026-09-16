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

The APK works with the previous library: **no redownload is required for the
app's one-ply re-entry fix**. A separate source-reference clarification update,
`2026-09-16.v1`, was subsequently accepted, tested and installed on the PC.
To receive its new comments in v0.8.8, open **Repertoire → Settings → Update from
PC**. There is no additional APK for this source-only integration.

That update appends 2,360 clarifications across eight of nine PGNs; Smith-Morra
is unchanged. Move trees and classifications are unchanged. There are still 27
explicitly unresolved source references, including the missing Taimanov destination.
Clarifying missing coverage does not supply the missing theory.

Integration checks used writable isolated copies of the frozen previous index
and the actual accepted replacement. Personal additions, deletions, recommendations,
comments, selection settings, restart, Undo and backup restore passed, including
renumbered node IDs. A personal note at an actually clarified source occurrence
remains intact, with the complete updated source comment separately readable.
The real private backup overlay was also checked at every affected position; no
private corpus, notes or fixtures are committed here. The release-asset touch test
`web/test/repertoire-source-refresh-ui.mjs` verifies notes, editing and reopening at
360×640, 390×780 and 412×844 using an external native-response fixture.

Opt-in native test inputs: `REPERTOIRE_TEST_INDEX` (writable old copy),
`REPERTOIRE_REFRESH_INDEX` (writable new copy), `REPERTOIRE_CLARIFIED_CASE` (a real
old/new comment patch plus legal request), and `REPERTOIRE_PERSONAL_TEST_BACKUP`
(private backup snapshot). Tests install/restore only into Robolectric's isolated
application directory. `REPERTOIRE_CLARIFIED_PREVIEW` optionally exports the actual
post-refresh response for the touch test; `PHONE_PREVIEW_ASSETS` should point to
assets extracted from the signed release, not a different working-tree build.

The PC corpus and state were freshly backed up before the source-only replacement.
Snapshot and live-baseline hashes were rechecked before installation. The installed
package was verified byte-for-byte through the existing authenticated gateway;
existing saved records, pairings and backup history remain intact. The simple UI
and production app/backend code did not change during integration.

The app does not clear studies, pairing, games, repertoire selections, personal
edits or backups. The later source installation used one brief scoped PC service
restart, with health checked afterward. The tethering phone was not used for tests.
