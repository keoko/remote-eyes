## Backlog

Known, not-yet-scheduled work items live in `BACKLOG.md`. Check it for
candidate tasks before assuming something needs inventing from scratch.

## Commands

### Android app (`android/`)

- Build debug APK: `./gradlew assembleDebug` (run from `android/`)
- Android lint: `./gradlew lintDebug`
- Unit tests: `./gradlew testDebugUnitTest` (no unit tests exist yet)
- No separate Kotlin Gradle plugin is applied — `app/build.gradle.kts` only
  applies `com.android.application`. AGP's built-in Kotlin compiler compiles
  `.kt` files from **both** `app/src/main/java` and `app/src/main/kotlin` as
  source roots. Don't assume from the Gradle files alone whether Kotlin
  compiles or which source dir is "live" — confirm against a real
  `./gradlew assembleDebug` and the resulting APK, not static config.

### Server (`server/`)

- Install deps: `npm install` (run from `server/`)
- Run the signaling server locally: `node server.js` (listens on port 8080,
  hardcoded in `server.js`)
- Manually test the join flow from a terminal: `node cli-test-client.js`
- `npm test` runs `test/helper-ice-race.test.js` — a regression test for a
  real race condition (extracts `helper.html`'s actual `<script>` and runs
  it in a sandboxed context with mocked browser APIs). No lint configured;
  no coverage of `server.js`'s own session/signaling logic yet.
- **Production deploy**: `flyctl deploy` from `server/` (app:
  `remote-eyes-server`, config in `server/fly.toml` + `server/Dockerfile`).
  Always-on (`min_machines_running = 1`) — see Architecture below for why.
  TURN credentials are Fly secrets (`flyctl secrets set METERED_DOMAIN=...
  METERED_SECRET_KEY=...`), never committed — `flyctl secrets list` to
  check what's set without exposing values.

## Architecture

Two independent, unrelated-stack sub-projects share this repo: `android/`
(Kotlin/Android) and `server/` (Node.js). They have no shared code or build
tooling — the only thing connecting them is a JSON-over-WebSocket signaling
protocol.

- **Signaling protocol** is the one contract spanning three files that must
  stay in lockstep: `server/server.js` (routes messages between two `ws`
  connections, understands none of the WebRTC content itself),
  `android/app/src/main/java/com/example/remoteeyes/SignalingClient.kt` +
  `ScreenCaptureService.kt` (the phone side — always the WebRTC offerer), and
  `server/helper.html` (the browser side — always the answerer). Message
  types: `create` → `help-code`, `join` → `joined`/`error`,
  `helper-connected`, `signal` (wrapping `signalType: offer|answer|ice-candidate`),
  `peer-disconnected`, `expired`. Changing a field name or message type on
  one side without updating the other two breaks the app silently — there's
  no schema/type checking across the boundary.

- **Session/role model** (`server/server.js`): the phone sends `create`
  (with `token` matching the `APP_TOKEN` Fly secret — see ICE/TURN below,
  same mechanism) and gets back a random 6-digit code with a 5-minute
  TTL; a helper sends `join` with that code. The server only relays SDP
  offers/answers and ICE candidates between the connection tagged
  `role: "phone"` and the one tagged `role: "helper"` — it has no
  understanding of WebRTC itself. Both `create` and `join` are
  rate-limited per source IP (`isRateLimited(attempts, ip)`, generic over
  two separate `Map`s, 10 attempts per 5-minute window matching
  `SESSION_TTL`) — proportionate to this app's actual threat model (a
  casual stranger, not a distributed attacker with many IPs), not
  hardened against a sophisticated attack. Sessions live in an in-memory
  `Map`, **not shared across machines** — this is why the Fly app runs
  exactly one machine (see Deployment below).

- **Android service architecture**: `MainActivity` only handles the UI and
  the `MediaProjection` permission flow; it hands off to
  `ScreenCaptureService` (a foreground service) via `ACTION_START`/
  `ACTION_STOP` intents. `ScreenCaptureService` owns the entire WebRTC
  lifecycle — the `PeerConnection`, `ScreenCapturerAndroid`, and the
  `SignalingClient` — and reports status back to `MainActivity` through the
  static `ScreenCaptureService.UiListener` callback (set/cleared in
  `MainActivity.onStart`/`onStop`). `PeerConnectionFactory`/`EglBase` are
  **not** owned per-session — `ScreenCaptureService` borrows the
  process-lifetime singleton from `WebRtcRuntime` (`WebRtcRuntime.factory()`/
  `.eglBase()`), since the WebRTC native library's global init must run
  exactly once per process; `stopScreenCapture()` correspondingly must never
  dispose them, only null out the local references.

- **Signaling server address**: configurable via `android/local.properties`
  (gitignored) → `BuildConfig.SIGNALING_URL`, set in
  `android/app/build.gradle.kts`. Not a source edit.

- **ICE/TURN**: both clients use Google's public STUN server plus a TURN
  relay (Metered.ca) for cases STUN alone can't traverse (confirmed
  necessary: cellular carrier-grade NAT). Neither client holds the Metered
  secret key directly — per Metered's own guidance, credential generation
  must stay server-side, and an APK is trivially reverse-engineered, so
  baking in even a gitignored secret would still ship it to every device.
  Instead, `server/server.js` caches a 24h TURN credential
  (`getTurnIceServers()`) and serves it, plus the STUN entry, from
  `GET /ice-config?code=<active session code>` — gated behind the session
  having **both** a phone and a helper (`session.helper != null`, not just
  `sessions.has(code)`), since a bare `create` shouldn't be enough on its
  own to harvest credentials. An invalid/missing/create-only code gets
  STUN-only back, not an error. `helper.html` fetches it when a code is
  submitted; `ScreenCaptureService.kt` fetches it in
  `fetchIceServersAndOffer()` using the code captured in `onHelpCode()`.

- **`APP_TOKEN`**: since `join`/`ice-config` both require an existing
  valid session code, the one action that lets an abuser bootstrap a code
  for free is `create` — so that's the one gated behind a shared token
  (`android/local.properties` → `BuildConfig.APP_TOKEN`, checked against
  the same value as a Fly secret), rather than trying to protect every
  endpoint. Same reasoning as the Metered key about not committing it,
  but a materially weaker guarantee: this token *does* end up compiled
  into the distributed APK (unlike Metered's key, which never leaves this
  server), recoverable by decompiling it. That's a deliberate, accepted
  trade-off — raises the bar from "read the public repo" to "decompile an
  APK," proportionate to deterring casual abuse, not a claim of real
  cryptographic protection. `helper.html` never calls `create` and so
  never needs the token.

## Deployment

The Fly.io app (`remote-eyes-server`, Paris/`cdg`) runs **exactly one
machine** (`server/fly.toml`, right-sized to 256MB) — never more, and never
autoscaled to multiple machines:
- More than one machine would split the in-memory `sessions` map across
  machines, causing intermittent "Invalid or expired help code" errors
  depending on which machine a request landed on.

`min_machines_running = 0` — scale-to-zero, a deliberate cost/reliability
trade-off, not a default left in place. It was briefly set to `1`
(always-on, ~$2/month) specifically because Metered's expiring TURN
credentials can take up to ~2 minutes to propagate, and the in-memory
`turnCache` in `server.js` is wiped on every cold start — with this app's
sporadic usage, that meant nearly every real session risked a cold start
minting a fresh, not-yet-usable credential right when it's needed.
Reverted back to `0` (cents/month instead of ~$2/month) with that risk
knowingly accepted: worst case, the first connection attempt of a session
fails and a retry a minute or two later succeeds. If this trade-off ever
stops feeling worth it, the fix is to persist the cached credential to a
Fly volume so a cold-started machine reads the still-valid cached one
instead of re-minting — not to just flip `min_machines_running` back to
`1` without addressing why it was needed.
