# Backlog

Known work items, not yet scheduled. Pick one and run it through a
Research → Plan → Implement pass; check it off (or delete it) once done.

## Android (`android/`)

- [x] Wire up `WebRtcRuntime.kt` (or delete it) — `ScreenCaptureService`
  currently duplicates its WebRTC init logic inline instead of using it.
- [x] Request `POST_NOTIFICATIONS` permission at runtime on Android 13+, so
  the "Stop sharing" foreground notification actually shows up. Requested
  in context, when "Share screen" is tapped (before the `MediaProjection`
  request), not on app launch. Denial doesn't block sharing itself — only
  the visible notification.
- [x] Make `SIGNALING_URL` configurable instead of hardcoded in
  `ScreenCaptureService.kt` — currently needs a source edit per network
  (emulator vs. LAN IP vs. parent's real network).
- [ ] Localize the app to Catalan — the target users (the maintainer's
  parents) are Catalan speakers. UI strings are currently hardcoded English
  literals in `MainActivity.kt` (e.g. "Ready", "Share screen", "Help code: …
  Waiting for helper...") rather than Android string resources, so this
  needs extracting them into `res/values/strings.xml` first, then adding a
  `res/values-ca/strings.xml` translation.
- [ ] Redesign the Android UI to be dead simple for elderly users —
  `MainActivity.kt` currently builds a plain `LinearLayout` with default-size
  buttons/text and multi-step status copy. Needs large touch targets and
  text, minimal steps (ideally one obvious action), plain non-technical
  language for status messages, and high contrast. Should be designed with
  the actual end users (the maintainer's parents) in mind, not a general
  audience.

## Server (`server/`)

- [x] Make the signaling server reachable from outside the LAN — deployed
  to Fly.io (`remote-eyes-server.fly.dev`, single machine — the in-memory
  `sessions` map isn't shared, so more than one machine would cause
  intermittent "Invalid or expired help code" errors depending on which
  machine handled which request). Fly terminates TLS at the edge, so the
  app now uses `wss://` end to end; `helper.html`'s WebSocket URL had to
  become scheme-aware to avoid a mixed-content error, and Android's
  `usesCleartextTraffic` was removed since it's no longer needed.
- [ ] Add basic rate limiting to the 6-digit join code — now more
  important than before: the join code is reachable from the whole
  internet, not just the home LAN, so it's genuinely brute-forceable by
  anyone, not just someone already on the network.
- [ ] Improve `helper.html`'s UI/UX — it's currently a bare-bones page
  (plain input + button + status line). Used by whoever is helping (not the
  parents), so lower priority than the Android redesign, but clearer
  connection-state feedback and layout polish would help.

## Cross-cutting

- [x] Add a TURN server to the ICE config — used Metered.ca's free tier
  (20GB/month, expiring credentials generated server-side only, never
  shipped to either client since an APK is trivially reverse-engineered).
  `server.js` caches a 24h credential and exposes it at `/ice-config`;
  both `helper.html` and `ScreenCaptureService.kt` fetch from there instead
  of hardcoding STUN-only. Fly app is right-sized to 256MB and scales to
  zero when idle — a freshly minted credential can take up to ~2 minutes
  to propagate, so a cold start could occasionally cause a failed first
  connection attempt (accepted trade-off for cost; see AGENTS.md's
  Deployment section). **Confirmed working on a real device**: phone on
  cellular data, Wi-Fi off, video now appears in the helper's browser.
- [x] Gate `/ice-config` behind an active session code — it was fully
  public and unauthenticated, so anyone (this repo is public on GitHub)
  could fetch a working Metered TURN credential and burn the 20GB/month
  quota with unrelated traffic. Now requires a `?code=` matching a
  currently-active session in `sessions`; an invalid/missing code gets
  STUN-only back instead of an error, matching the existing fetch-failure
  fallback shape in both clients.
- [ ] Add automated tests — neither sub-project has any today. `server/`'s
  `npm test` is just a placeholder that errors; `android/`'s
  `testDebugUnitTest` Gradle task exists but has no test sources. Best
  starting point is probably `server/server.js`'s session/signaling logic
  (`createSession`, `joinSession`, `relaySignal`, `removeClient`) — it's
  plain functions operating on the `sessions` map, testable without a real
  WebSocket server.
