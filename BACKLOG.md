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
- [x] Localize the app to Catalan and Spanish — added
  `res/values-ca/strings.xml` and `res/values-es/strings.xml` with copy
  for all keys (verified all three configs — default, `ca`, `es` — are
  packaged in the built APK with the correct translated values). Also
  fixed a leftover from the UI redesign while touching these strings:
  `code_label` ("Your code") existed but was never actually wired into
  `MainActivity.kt` — added a small label above the big code display.
  **Both translations reviewed and confirmed good by the (native-speaker)
  user.**
- [x] Redesign the Android UI to be dead simple for elderly users — one
  toggle button (green "Share my screen" ↔ red "Stop sharing") instead of
  two always-visible buttons; the help code gets its own huge (72sp), bold,
  paired-digit display ("48 27 31") separate from the status line, since
  that's the moment a parent reads digits aloud over the phone; all status
  copy rewritten to plain language in both `MainActivity.kt` and
  `ScreenCaptureService.kt` (no more "WebRTC connection failed" or raw
  exception text); `FLAG_KEEP_SCREEN_ON` added so the screen can't time out
  mid-session; all text extracted to `res/values/strings.xml` (English,
  Catalan translation deferred to the localization item below). **Not yet
  visually verified** — no way to render/screenshot the UI from the dev
  environment; needs your eyes on a real device, likely a follow-up polish
  round once you see it.

## Server (`server/`)

- [x] Make the signaling server reachable from outside the LAN — deployed
  to Fly.io (`remote-eyes-server.fly.dev`, single machine — the in-memory
  `sessions` map isn't shared, so more than one machine would cause
  intermittent "Invalid or expired help code" errors depending on which
  machine handled which request). Fly terminates TLS at the edge, so the
  app now uses `wss://` end to end; `helper.html`'s WebSocket URL had to
  become scheme-aware to avoid a mixed-content error, and Android's
  `usesCleartextTraffic` was removed since it's no longer needed.
- [x] Add basic rate limiting to the 6-digit join code — tracks `join`
  attempts per source IP in-memory (`joinAttempts` Map, same pattern as
  `sessions`), capped at 10 attempts per 5-minute window matching
  `SESSION_TTL`. Not hardened against a distributed attacker with many
  IPs — proportionate to this app's actual threat model (a casual
  stranger, not a sophisticated attack), not gold-plated. Verified both
  locally and against the live production server: attempts 1-10 get the
  normal "Invalid or expired help code," 11+ get rate-limited.
- [x] Improve `helper.html`'s UI/UX — card layout, a colored connection-
  state badge (gray/blue/green/red for idle/connecting/connected/error)
  instead of a plain overwritten text line, a placeholder overlay on the
  video area until a track actually arrives, Enter-to-join, autofocus, and
  live digit-only sanitization on the code input. Purely visual/JS — no
  changes to the signaling protocol or `server.js`. Verified: JS syntax
  checked, page serves correctly with all new elements in both local and
  production, protocol logic unchanged. **Not visually verified** — no
  browser automation available in this environment; needs your eyes on
  the actual rendered page.
- [x] Fix a real race condition in `helper.html` found via actual user
  testing (not caught by any prior verification): `createPeerConnection()`
  became `async` (awaiting the `/ice-config` fetch) when TURN support was
  added, but each WebSocket message is a separate `onmessage` invocation —
  awaiting inside one doesn't block the next. If the phone's SDP offer
  arrived before the browser's own `/ice-config` fetch resolved,
  `handleOffer` ran against a still-`undefined` `peerConnection`, crashing
  with `TypeError: can't access property "setRemoteDescription",
  peerConnection is undefined`, followed by cascading `ICE candidate
  error: No remoteDescription` for every candidate after it. Fixed by
  awaiting a shared `peerConnectionReady` promise before processing any
  `signal` message, regardless of arrival order. Added
  `server/test/helper-ice-race.test.js` as a real regression test — it
  extracts the actual `<script>` from `helper.html` (not a hand-copied
  duplicate) and runs it in a sandboxed context with a deliberately
  delayed fake fetch to reproduce the exact race; confirmed it fails
  against the pre-fix code and passes against the fix. `npm test` now
  runs it (was previously a placeholder that always failed).

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
- [x] Prevent abuse of the public server (`remote-eyes-server.fly.dev`) —
  `join` is rate-limited and `/ice-config` requires an active session
  code, but `create` itself is free and unauthenticated, which
  undermines both: anyone can call `create` to mint a code for free, then
  immediately harvest real Metered TURN credentials via
  `/ice-config?code=...` for entirely unrelated use, or just spam
  `create` to keep the scale-to-zero machine artificially warm (turning
  the ~cents/month bill into something closer to the always-on ~$2/month
  cost, as griefing rather than gain). Three-part fix, all reusing
  existing patterns rather than adding real authentication (which this
  app's threat model doesn't warrant — see `AGENTS.md`):
  1. **A shared app token on `create` only** — `join`/`ice-config` don't
     need one, since they already require an existing valid code, which
     (once `create` is gated) can now only come from a legitimate phone
     session. Generated once, stored in `android/local.properties`
     (gitignored) → `BuildConfig.APP_TOKEN`, same pattern as
     `SIGNALING_URL`; checked against a Fly secret in `server.js`. Not
     committed anywhere, so reading the public repo reveals nothing —
     though it does end up compiled into the distributed APK, recoverable
     by anyone who decompiles it. Raises the bar from "read GitHub" to
     "decompile an APK," proportionate to this app's actual threat model,
     not a claim of real cryptographic protection.
  2. **Rate-limit `create`** the same way `join` already is (reuse
     `isRateLimited`), bounding how many free sessions/credential-mints
     any one IP can rack up per window even before the token check.
  3. **Only serve real TURN credentials once a session has both sides** —
     `/ice-config` currently only checks `sessions.has(code)`; also
     require `session.helper` to be set, so a bare `create` (even with a
     valid token) isn't enough on its own to harvest credentials.

  **Verified against the live production server**: `create` with no/wrong
  token gets a deliberately generic rejection; 11+ `create` attempts with
  a valid token get rate-limited (10th succeeds, 11th doesn't); a code
  from `create` alone returns STUN-only from `/ice-config`, and only
  returns real TURN entries after a helper actually joins. `local.properties`
  → `BuildConfig.APP_TOKEN` confirmed correct in a real built APK, and
  **confirmed end-to-end on-device**: rebuilt, reinstalled, real session
  created and joined normally with the token wired through.
- [ ] Add automated tests — `server/` now has one real test
  (`test/helper-ice-race.test.js`, run via `npm test`), added as a
  regression test while fixing a real bug, not as a deliberate coverage
  push. `server.js`'s session/signaling logic (`createSession`,
  `joinSession`, `relaySignal`, `removeClient`) is still untested — plain
  functions operating on the `sessions` map, testable without a real
  WebSocket server. `android/`'s `testDebugUnitTest` Gradle task still has
  no test sources at all.
