# Retrospective (draft: edit in your own words)

## Built
Authenticated REST API with RBAC; per-lounge authoritative real-time room (queue, voting, presence, moderation, host-disconnect policy); acoustic-aware AI DJ (hard BPM/Camelot rules, grounded script and Q&A, approval flow); sentiment engine that influences the AI pick; Soundclash battles; hybrid recommender; analytics and personal dashboard; login throttling; health checks; Docker and CI files; docs and a scripted demo.

## Deliberate deviations
- Spring MVC WebSocket instead of WebFlux: simpler single-writer model for this scale.
- No LLM: template script and extractive retrieval keep output grounded and testable.
- Seed catalogue is 7 authored tracks, not real licensed audio.

## What went well
Keeping the room a pure state machine made rules testable without infrastructure and made the concurrency test meaningful. Mutation-testing key rules caught a weak recommender test.

## Honest limitations
- Spring, SQL and WebSocket layers were only syntax-checked in the build sandbox; integration tests and the load tool have not been run.
- Sentiment accuracy uses self-labelled data; recommender precision uses synthetic data.
- Sync drift and end-to-end latency are unmeasured.
- Acting-host (co-DJ) rights are enforced over WebSocket; some REST paths still require the real host.
- No recorded demo video; transcript only. Per-request recommender rebuild will not scale to large catalogues.

## Next
Run `mvn verify` and the load tool; label sentiment with outside annotators; precompute similarities; add client playback-sync measurement; commit incrementally with git.
