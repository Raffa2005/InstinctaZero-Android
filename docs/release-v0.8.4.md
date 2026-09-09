# v0.8.4 — Privacy mode

Turn on **Privacy mode** in **Account / PC** (also available on Home and in the
sidebar). Your account becomes **Player**; other player names, ratings and
account-bearing screen text are concealed. The setting stays on after restarting
or reconnecting and applies while games are loading, with either player colour.

To show real identities again, turn the same switch off. Editing concealed original
comments or repertoire names asks you explicitly before revealing the text.

This is display privacy, not anonymity for public Lichess games. Original accounts,
PGNs, saved games, repertoires and pairing are unchanged. No PC restart, re-pairing
or repertoire redownload is needed. The simple design, return-to-mainline control,
board editor and portrait-only activity remain intact.

Verified with native Android view/lifecycle regressions, JavaScript tests, release
lint, signing checks and touch previews at 360×640, 390×780 and 412×844, including
the signed APK's packaged assets. Tests check transient text during loading,
reconnect/reopen, both colours, account switching, comment reveal, and unchanged
saved metadata, navigation and repertoire behavior. No USB-phone testing.
