# v0.8.7 — Web maneuver arrows on mobile

In **Analysis → Engine → Settings**, choose **Best + maneuver**. The setting is
remembered. **Best moves** retains the previous display, and the existing Arrows
switch still turns all engine arrows off. Existing installations keep their
current best-move display until the new mode is selected.

This ports the current web app's `Best + maneuver` behavior, not a new plan mode:

- Follow the best PV's same piece through at most three own moves (six plies).
  Opponent replies are skipped. A different moving piece ends the sequence.
- Use the web's exact overlap/reversal rules for knights and sliding pieces.
- Maneuver arrows use the existing blue brush; alternative root moves retain
  their grey visit/evaluation-weighted widths and suppression.
- The existing Arrow count caps the total, with the maneuver first and alternative
  moves filling any remaining places, exactly as on the web.
- Flipping, ordinary navigation and inherited PVs all redraw locally. Selecting
  the display mode does not start another analysis or add network requests.

No backend change, restart, re-pairing or repertoire redownload is needed. Install
over the existing app. Games, local PGNs, repertoire edits, Undo/backups, privacy,
portrait lock, canonical navigation and Return to mainline/intersection remain.

## Verification

- `maneuver-arrows.test.mjs`: same-piece horizon, both colours, promotions, invalid
  moves, sliding-path intersections, knight reversals, arrow caps and unchanged
  alternative weighting. When the web checkout is available, a parity test compares
  the four maneuver helpers verbatim with its current implementation.
- `ManeuverSettingsTest`: native defaults/upgrades, typed validation, persistence
  across bridge recreation, and preservation of existing controls.
- `maneuver-arrows-ui.mjs`: touch Chromium at 360×640, 390×780 and 412×844, with
  4× CPU throttling. Verify exact SVG endpoints in both orientations, selection,
  count/off controls, full settings visibility, actual board touch moves, immediate
  inherited maneuvers, stale-response rejection, clearing on uncached positions,
  rapid navigation and saved settings/board restoration. Also run against assets
  extracted from the signed APK. Timings are host-preview observations, not phone
  benchmarks.
- Existing JS/native debug and release tests, real-corpus repertoire checks, privacy,
  game-loading, repertoire editing/transposition/authoring, navigation and board-editor
  touch regressions; release lint and APK signer/version/portrait/public-download checks.

No USB-phone testing or changes to the web service are required.
