# API reference
Base `/api/v1`. JSON. Errors: `{"error":{"code","message","fields":[...]}}`. Auth: `Authorization: Bearer <access JWT>` (15 min); refresh tokens rotate.

## REST
| Method | Path | Role | Notes |
|---|---|---|---|
| POST | /auth/register | public | `{email,password,displayName}` |
| POST | /auth/login | public | throttled: 5 fails/email+IP and 60/IP per 15 min -> 429 `too_many_attempts` |
| POST | /auth/refresh, /auth/logout | public | rotating refresh token |
| GET | /auth/me | any | |
| PATCH | /admin/users/{id}/role | admin | |
| GET | /tracks | any | search/filter |
| GET | /tracks/{id}/acoustic-profile | any | |
| GET | /tracks/{id}/compatible | any | ranked, with `matchReason` |
| POST / PUT | /tracks, /tracks/{id}/acoustic-profile | admin | |
| POST | /lounges | host | create |
| POST | /lounges/join | any | by invite code |
| GET | /lounges/{id} | member | |
| POST | /lounges/{id}/ai/segue | host/co-DJ | `{"loungeId"...}` returns suggestion with script + sourceEvents |
| POST | /lounges/{id}/ai/ask | member | `{"question"}` -> `{answer, grounded, citations}` |
| GET | /lounges/{id}/analytics | member | peak concurrent, AI questions, sentiment timeline, top tracks, soundclash |
| GET | /analytics/overview | admin | |
| GET | /recommendations | any | hybrid, with reasons |
| POST | /interactions | any | |
| GET | /me/dashboard | any | taste profile, history |

Example `GET /lounges/{id}/analytics` (abridged): `{"loungeId":"lng_..","status":"live","peakConcurrent":4,"aiQuestions":3,"sentiment":0.64,"topVotedTracks":[{"trackId":"t_2006","votes":2}]}`

## WebSocket `/ws/lounges/{id}?token=<JWT>`
Handshake validates JWT and lounge membership. Envelope: `{"type","requestId","payload"}`; server replies `lounge.ack` or `lounge.error` (codes e.g. `forbidden`, `room_frozen`, `rate_limited`, `incompatible_segue`, `not_found`). Every `lounge.queue.update` carries a full snapshot and a monotonic `seq`.

Client -> server: `queue.add|remove|vote|unvote|promote|advance`, `lounge.start|end`, `ai.segue.request|approve|reject`, `ai.ask`, `chat.send|react|typing`, `mod.kick|mute|lock|set_role|transfer_host`, `soundclash.open|submit|vote|resolve`, `lounge.sync.ping`, `auth.refresh`.

Server -> client: `lounge.state.snapshot`, `lounge.queue.update`, `lounge.presence.update`, `lounge.status`, `lounge.host.changed`, `lounge.member.role|muted`, `lounge.chat.message|reaction|typing`, `lounge.sentiment.score`, `lounge.ai.segue.suggested|rejected`, `lounge.ai.dj_announcement`, `lounge.ai.answer`, `lounge.soundclash.open|update|resolved`, `lounge.sync.pong`, `lounge.error`.

Example: `{"type":"queue.vote","requestId":"r1","payload":{"trackId":"t_2006"}}`
