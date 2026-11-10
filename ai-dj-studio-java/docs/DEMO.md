# Demo guide
1. `cp .env.example .env`, set `JWT_SECRET` (32+ chars) and DB passwords; `docker compose up --build`.
2. Open `http://localhost:8080/dashboard.html`; log in as `host@example.com` with `SEED_DEMO_PASSWORD` (demo credential only).
3. Open a second browser profile as a listener, join the lounge by invite code.
4. Script: add tracks -> vote (queue reorders on both tabs) -> request AI segue (see matchReason + script) -> ask "was anything sampled?" -> chat a burst of excited messages and watch the mood and AI pick change -> open a Soundclash -> kill the host tab to see acting host / frozen.
5. No-infrastructure version: `docs/evidence/scripted-demo-output.txt` is the transcript of `DemoScenarioTest`, which runs the same scenario over the real engine. No screen recording is included.
