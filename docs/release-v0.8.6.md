# v0.8.6 — One repertoire-add workflow

Version 0.8.5 added a generic single-response operation, not a hard-coded …b4
exception. It was nevertheless incomplete: ordinary Add line still rejected
informational source routes, and its test first saved the response separately.

This release removes that separate action and fixes ordinary **Add move / Add
line**. Play one move or several moves onward and save once. Informational and
regular source branches use the same controls and the same normal saved move
rows, recommendations and book markers. No preparatory save or PC annotation
change is needed. Earlier imported source labels remain intact; deliberate local
deletions and exclusions still require an explicit restore.

Original PGNs, games, existing edits, comments, selections, private PC backups and
Undo are retained. No repertoire redownload, re-pairing or server update is needed.
The simple design, privacy, portrait lock, canonical navigation and return symbol
are unchanged. Install this signed update over the existing app.

## Verification

- Native SQLite regression starts with the untouched installed Taimanov corpus,
  plays four further plies after the pictured …b4, and saves all five new moves in
  one ordinary Add line operation. No intermediate write is used.
- Compare the same extension from an informational and a regular parent; test
  restart, whole-operation Undo, backup restore, a different legal move order,
  position-rooted boards, and deliberate deletion/exclusion protection.
- Additional synthetic refutation/informational branches, legacy exclusions,
  independent transpositions, local authoring and existing full-corpus checks.
- `repertoire-unified-add-ui.mjs` uses actual native response fixtures at 360×640,
  390×780 and 412×844. It touch-plays five plies before the only save, verifies the
  single-move and multi-move controls and compares rendered rows and markers in
  both cases. `repertoire-editing-ui.mjs` also checks deletion/restore, navigation,
  privacy and stream recovery. Run both against the extracted signed APK assets.
- Existing JavaScript, native Android debug/release, privacy, game loading,
  repertoire authoring/transposition and board-editor regressions; release lint,
  APK signature, version, portrait manifest and public-download verification.

Tests use native Android host simulation and touch Chromium previews, not the
USB-tethering phone. Private corpus fixtures and screenshots are not distributed.
