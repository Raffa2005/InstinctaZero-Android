# Privacy mode

Enable **Privacy mode** in **Account / PC**, on Home below Connection, or in the
sidebar. Disable the same switch to show real identities again. The control
explicitly labels the on/off behavior. The choice persists on this phone and is
available before connecting to the PC.

The account is shown as **Player**; the other player as **Opponent**, regardless
of colour. Player ratings and the paired device's name are hidden. Multiple
connected accounts appear as **Account 1**, **Account 2**, etc.; the checkmark
still identifies the active account. Switching them sends the original username,
not the display alias. Game result colours, ownership, board orientation and
analysis requests continue using original data.

The saved analysis heading and loading heading use aliases. Arbitrary custom
headings/subtitles become neutral labels. On-screen PGN header text in comments,
known account handles in repertoire names/comments and web links are concealed.
Names remembered from previously connected accounts remain covered offline.
Unknown server error bodies are replaced with useful generic messages rather than
showing possible usernames, profile information or URLs.

Editing a name/comment that contains concealed text first offers **Show original
… (reveals identity)**. This deliberately exposes the original for editing;
the masking is never written into the saved text. Toggling privacy back on covers
an already-open original-text editor. Text you deliberately type yourself can
also be visible while editing.

## Limits and data preservation

This is screen privacy, **not anonymization of public Lichess games**. Moves,
positions, dates, results and time controls remain visible and may identify a
public game. Arbitrary personal information in user-written prose is not a
general-purpose personally identifying information detector. The mode does not
protect the phone's files from someone with device access or erase screenshots
already taken with privacy off.

Real IDs, authentication, synchronization, original PGNs, saved boards, repertoire
sources, edits and exports are not changed. Original/shared PGNs remain
identifiable. No PC restart, re-pairing, data migration or repertoire download is
required. Settings and up to 128 remembered account handles are stored separately
from credentials/game data; no bearer token enters the display policy.

## Surface audit and verification

- Native Home, sidebar, account selection/profile text, pairing errors, Games
  heading, game matchups/ratings/results and unavailable-game messages use the
  presentation policy before views are attached.
- The current app has only the static InstinctaZero logo, not account avatars.
  It has no real-profile-opening action, public account link action, PGN-sharing
  action, app-generated Android notification or Toast. Future revealing actions
  must explain that they reveal the real identity before doing so. FEN clipboard
  actions contain only board state and remain unchanged.
- The packaged page reads privacy synchronously before restoring its saved study.
  Detached HTML text is masked before DOM insertion. Native toggles cover the warm
  WebView until its policy refresh completes; stale refresh callbacks cannot expose
  an older policy. Unchanged reconnects do not reset focused comment editors.
- Robolectric exercises the actual MainActivity/native views with a fake PC bridge,
  persistent preference restart, both player colours, row recycling, raw-ID actions,
  errors, reversible toggles and activity recreation. Native Skia snapshots and
  touch Chromium previews use 360, 390 and 412-pixel portrait widths.
- Browser tests observe every inserted text/label during loading/reopen/reconnect,
  cover error text, PGN metadata, policy failure and explicit original-comment
  reveal, and assert that saved metadata and deliberately edited originals retain
  their actual values. Existing navigation/mainline, editor, repertoire, Undo and
  transposition tests remain part of release checks.

The USB-tethering phone is not used for testing. These are native JVM/Skia and
packaged-asset browser checks, not a physical-device installation claim.
