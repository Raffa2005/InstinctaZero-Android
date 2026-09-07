# v0.8.0 — Repertoire library and comment editing

## Changes

- Grouped native Home and sidebar menus with icons and touch feedback.
- Repertoire library: create a named White or Black repertoire, open it on the
  analysis board, and rename phone-created repertoires. No PC download is needed
  to start a new empty repertoire.
- Write or edit position comments from the move adjustments, expanded comments,
  position settings or immediately after adding a line. Save, restore source text
  and Undo work through the existing atomic local-edit store. Comments follow
  transpositions, while original PGNs remain untouched.
- Add move/line automatically targets the only eligible repertoire or the unique
  closest covered prefix. The destination is shown; tied candidates still require
  a choice. Explicit focused repertoire views retain their scope.

New repertoires and edits are phone-local, not synced to the PC or exported as
PGNs. Existing downloaded repertoires do not need to be downloaded again. See
[repertoire documentation](repertoires.md) for limits and storage details.

## Verification

- 72 Android/Robolectric tests passed in both debug and release, with no skips.
  These include real native SQLite, atomic edits/Undo, offline creation, rename,
  reinstall, transposition comments, navigation and portrait contracts.
- 648 independent position-union reference cases passed against a read-only
  snapshot of the current nine-repertoire corpus. Full native responses for the
  prior six-repertoire performance workloads remained equivalent. Existing
  indexed lookup and bounded-cache regressions passed.
- 43 JavaScript/controller tests passed, including canonical forward navigation,
  Return to mainline, inheritance, cache invalidation and extension targeting.
- Native View/Skia menu previews at 360, 390 and 412 dp widths; touch-browser
  checks at 360×640, 390×780 and 412×844 against assets extracted from the signed
  APK. Comment checks include escaped text, keyboard-sized viewport, retained
  focus during analysis updates, restart, restore and Undo. Existing board,
  engine, book and navigation preview checks retained.
- Release lint, signature verification and zip alignment passed. Same signing
  certificate, package name and portrait lock; version code 28 / version 0.8.0.

These are host-native Android and browser checks, not physical-phone benchmarks.
The USB tethering phone and live services were not used for testing. No source
PGNs, annotation packages, board assets or branding were changed.
