# v0.8.12 — Cloudflare connection

Install over the existing app. **Do not uninstall or clear storage.** No new
pairing or repertoire download is needed. The package ID, signing certificate,
encrypted pairing store, saved boards, repertoire edits and settings are unchanged.

The fixed PC gateway is now `https://instinctazero.rs-industries.org`. The previous
Tailscale hostname repeatedly returned NXDOMAIN from public DNS even when the
local engine and private Tailscale connection were healthy. The new release
removes that public-DNS dependency; it still needs the PC and its internet
connection to remain available.

The separate Cloudflare Tunnel exposes only `/api/mobile/v1` and its descendants.
All other paths return 404. Device authentication and the existing active-game
fair-play checks are retained. There is no automatic cross-origin token fallback.
The old server route remains intact for rollback, and the existing Double Strike
tunnel and mail DNS records were not changed.

The native origin parser now accepts the standard implicit HTTPS port 443 while
retaining HTTPS, host, userinfo, path, query and fragment validation. The allow-list
tests cover the new host and reject the old host, HTTP, wrong port, host-suffix
spoofing and userinfo.

## Verification

- `npm run check`: all 65 tests pass under Node 24.
- `testDebugUnitTest`: 127 tests pass, 18 optional fixture tests skipped; release
  lint and signed release build pass.
- Version 0.8.12 / code 40, original application ID and signing certificate.
- Packaged app assets match v0.8.11; only the generated dexopt baseline differs.
- Google and Cloudflare public DNS both resolve the new hostname. HTTPS succeeds
  with normal certificate verification. Unauthenticated session requests return
  401; the root and desktop engine APIs return 404.
- With the native client's user agent and a temporary paired device, the session
  returned 200 and a start-position exact-SYCL analysis streamed progressive
  results, finishing with a `done` event, 32 nodes and seven lines. Responses are
  `no-store`. The fair-play gate was checked normally; no bypass was used.
- Temporary diagnostic devices were revoked and existing device credentials were
  compared unchanged. No saved game, board, repertoire or source PGN was edited.

These checks verify the public gateway and signed artifact, not a physical-phone
installation. The USB tethering phone was not used for testing. A short successful
check cannot establish long-term provider availability.
