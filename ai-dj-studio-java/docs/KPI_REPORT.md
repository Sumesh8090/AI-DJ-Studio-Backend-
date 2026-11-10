# KPI report (PRD targets vs. what was actually measured)

Status key: **Measured** = run here, number reproduced by a test; **Written, not run** = code/tool exists but could not be executed in the build sandbox (no Maven Central, no MySQL); **Open** = needs a real deployment.

| KPI | Target | How measured | Result | Status |
|---|---|---|---|---|
| Queue update fan-out logic (vote/unvote) | fast, ordered | `PerformanceTest`, 200 members, 50 queued, in-process | p50 0.007 ms, p95 0.019 ms, p99 0.037 ms | Measured (excludes network, JSON, DB) |
| Queue consistency under concurrency | no lost/duplicated updates | `RoomConcurrencyTest` (many threads, one room; `seq` strictly monotonic, final snapshot equals replay) | pass | Measured |
| AI segue compute | low latency | `PerformanceTest`, 20 candidates, match + script | p50 0.016 ms, p95 0.026 ms | Measured (compute only; excludes request/response and DB read) |
| AI segue validity | 100% satisfy BPM/key rules | `SegueMatcherTest` (22) incl. property-style sweep + demo | no violation | Measured |
| Grounded Q&A | cites sources, declines otherwise | `AiTest` (14), demo transcript | pass | Measured on 7 authored tracks |
| Sentiment accuracy | >=85% | `SentimentTest` on two label sets | dev 98.3%, held-out 96.7% | Measured, **labels are self-authored**, so this is optimistic |
| Sentiment throughput | - | `PerformanceTest` | ~108k msgs/s single thread | Measured |
| Recommendation quality | beats baselines | `RecommenderTest`, P@5 on synthetic 36-user world | 0.92 vs popularity 0.38 vs random 0.19 | Measured on synthetic data only |
| Memory per live room | small | `PerformanceTest` | ~22 KB (40 members, 50 tracks); 500 rooms | Measured |
| Playback sync drift | <= 250 ms | `lounge.sync.ping/pong` implemented | - | Open: needs real clients |
| End-to-end WS latency / API latency (p95) | per PRD | `tools/LoadTest.java` (30 clients, rounds, AI calls, REST timing) | - | Written, not run |
| Login brute-force protection | throttle | `FailureLimiterTest` (5), `ApiIntegrationTest.repeatedFailedLoginsAreThrottledPerAccount` | unit pass; integration not run | Partly measured |
| Peak concurrency, AI question count, session duration | reported | `GET /lounges/{id}/analytics` | implemented | Written, not run against MySQL |

Totals: 140 pure-Java tests pass here (core, room, AI, sentiment, recommender, analytics, security limiter, performance, concurrency, scripted demo). Spring/MySQL/WebSocket integration tests (`ApiIntegrationTest`, `WebSocketAiIntegrationTest`, etc.) are written but were **not executed**; run `mvn verify` once to confirm.

To close the open rows: `docker compose up`, then `java tools/LoadTest.java --clients 30 --rounds 100 --ai 100` and paste its output below.

## Load test output
_(not yet run)_
