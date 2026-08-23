# Mobile API v1

The phone talks only to a paired InstinctaZero PC. It never receives a Lichess credential and cannot submit a position for analysis. Every analysis request names a completed game already stored on the PC and a ply reconstructed by the server.

All authenticated paths use `Authorization: Bearer <device token>`.

## Pairing and session

- `POST /api/mobile/v1/pair/claim` with `{ "code": "…", "device_name": "…" }`
- `GET /api/mobile/v1/session`
- `DELETE /api/mobile/v1/session`
- `GET /api/mobile/v1/devices`
- `POST /api/mobile/v1/devices/{id}/revoke`

The claim response returns the bearer exactly once. The Android client immediately wraps it with a non-exportable Android Keystore key.

## Completed-game synchronization

- `POST /api/mobile/v1/sync`
- `GET /api/mobile/v1/sync`
- `GET /api/mobile/v1/games?cursor=<opaque>&limit=50`
- `GET /api/mobile/v1/games/{id}`

Games are immutable and ordered newest first. The opaque cursor represents the server ordering; clients must not construct it.

## Analysis

- `GET /api/mobile/v1/games/{id}/explorer?ply=N&source=masters|lichess`
- `GET /api/mobile/v1/games/{id}/analysis/stream?ply=N&nodes=1000&multipv=5&profile=exact-sycl|experimental-hetero-int8`
- `POST /api/mobile/v1/games/{id}/values`
- `GET /api/mobile/v1/games/{id}/values`

The analysis stream uses SSE events `status`, `lc0`, `engine-error`, and `done`. A complete `lc0` event is an atomic snapshot. The client accepts a snapshot only when its request ID matches the current game/ply stream and its snapshot ID is newer than the displayed snapshot.

The default profile is exactly `exact-sycl`. `experimental-hetero-int8` is opt-in.

## Safety boundary

There is deliberately no endpoint accepting arbitrary FEN, PGN, move history, engine arguments, live games, or a Lichess game ID supplied by the phone. A mobile request cannot reach lc0 unless the PC has already imported the completed game.
