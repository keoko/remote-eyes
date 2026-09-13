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
- Run the signaling server: `node server.js` (listens on port 8080, hardcoded
  in `server.js`)
- Manually test the join flow from a terminal: `node cli-test-client.js`
- No lint or real test script is configured yet (`npm test` is a placeholder
  that just exits with an error).

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

- **Session/role model** (`server/server.js`): the phone sends `create` and
  gets back a random 6-digit code with a 5-minute TTL; a helper sends `join`
  with that code. The server only relays SDP offers/answers and ICE
  candidates between the connection tagged `role: "phone"` and the one
  tagged `role: "helper"` — it has no understanding of WebRTC itself, and
  the join code has no rate limiting.

- **Android service architecture**: `MainActivity` only handles the UI and
  the `MediaProjection` permission flow; it hands off to
  `ScreenCaptureService` (a foreground service) via `ACTION_START`/
  `ACTION_STOP` intents. `ScreenCaptureService` owns the entire WebRTC
  lifecycle — `PeerConnectionFactory`, `ScreenCapturerAndroid`, the
  `PeerConnection`, and the `SignalingClient` — and reports status back to
  `MainActivity` through the static `ScreenCaptureService.UiListener`
  callback (set/cleared in `MainActivity.onStart`/`onStop`). `WebRtcRuntime.kt`
  is a leftover, currently-unused singleton for shared WebRTC init —
  `ScreenCaptureService.initializeWebRtc()` duplicates that setup inline
  instead of calling it.

- The signaling server address is hardcoded (`SIGNALING_URL` in
  `ScreenCaptureService.kt`) rather than configurable per build variant — it
  must be edited by hand for the emulator (`ws://10.0.2.2:8080`) vs. a real
  device on the LAN.

- ICE is STUN-only on both sides (Google's public STUN server) — no TURN, so
  connections across some NAT configurations may fail to establish.
