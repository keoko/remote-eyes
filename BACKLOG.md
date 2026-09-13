# Backlog

Known work items, not yet scheduled. Pick one and run it through a
Research → Plan → Implement pass; check it off (or delete it) once done.

## Android (`android/`)

- [x] Wire up `WebRtcRuntime.kt` (or delete it) — `ScreenCaptureService`
  currently duplicates its WebRTC init logic inline instead of using it.
- [ ] Request `POST_NOTIFICATIONS` permission at runtime on Android 13+, so
  the "Stop sharing" foreground notification actually shows up.
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

- [ ] Add basic rate limiting to the 6-digit join code — currently
  unlimited join attempts within the 5-minute session TTL.
- [ ] Improve `helper.html`'s UI/UX — it's currently a bare-bones page
  (plain input + button + status line). Used by whoever is helping (not the
  parents), so lower priority than the Android redesign, but clearer
  connection-state feedback and layout polish would help.

## Cross-cutting

- [ ] Add a TURN server to the ICE config (both `ScreenCaptureService.kt`
  and `helper.html` are STUN-only today) — connections across some NAT
  configurations may otherwise fail to establish.
- [ ] Add automated tests — neither sub-project has any today. `server/`'s
  `npm test` is just a placeholder that errors; `android/`'s
  `testDebugUnitTest` Gradle task exists but has no test sources. Best
  starting point is probably `server/server.js`'s session/signaling logic
  (`createSession`, `joinSession`, `relaySignal`, `removeClient`) — it's
  plain functions operating on the `sessions` map, testable without a real
  WebSocket server.
