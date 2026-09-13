# Remote Eyes

Remote screen sharing for quick, ad-hoc help sessions — built to help
elderly, non-technical family members fix phone problems remotely. An
Android app shares the phone's screen over WebRTC, and whoever is helping
can watch it from a browser using a 6-digit code — no accounts, nothing to
install on the helper's side, as little friction as possible on the phone.

## How it works

1. The Android app connects to the signaling server and requests a session.
2. The server hands back a 6-digit code and shows it on the phone.
3. A helper opens the server's `/helper` page, enters the code, and joins.
4. The phone and the helper's browser exchange a WebRTC offer/answer and ICE
   candidates through the server, then connect directly, via STUN, or via a
   TURN relay (for cases like cellular carrier-grade NAT, where STUN alone
   usually can't establish a connection) and stream the phone's screen.
5. The session is torn down when either side disconnects or after a 5-minute
   TTL.

## Project layout

- `android/` — the Android app. Captures the screen via `MediaProjection` and
  streams it with WebRTC (`ScreenCaptureService.kt`, `SignalingClient.kt`).
- `server/` — a Node.js signaling server (`server.js`) plus the browser page
  a helper uses to view the stream (`helper.html`), served at `/helper`.

## Running the server

**Production** is deployed to Fly.io at `wss://remote-eyes-server.fly.dev`
(and `https://remote-eyes-server.fly.dev/helper` for the browser page), a
single machine (never more, since sessions live in an in-memory `Map` not
shared across machines) that scales to zero when idle. Deploy changes with
`flyctl deploy` from `server/`. TURN credentials (Metered.ca) are supplied
via `flyctl secrets set METERED_DOMAIN=... METERED_SECRET_KEY=...` — never
committed to the repo.

**Locally**, for development:
```
cd server
npm install
node server.js
```
Listens on port `8080` by default (see `PORT` in `server.js`). The helper
page is then available at `http://localhost:8080/helper`. Without
`METERED_DOMAIN`/`METERED_SECRET_KEY` set as environment variables, TURN is
simply omitted and ICE falls back to STUN-only.

`server/cli-test-client.js` is a small terminal script for manually testing
the join flow against a running server, independent of the browser page.
`npm test` runs a regression test for a real race-condition bug found by
actual use (`server/test/helper-ice-race.test.js`).

## Running the Android app

1. Create `android/local.properties` (gitignored) with
   `SIGNALING_URL=wss://remote-eyes-server.fly.dev` to point at production,
   or `ws://10.0.2.2:8080` for an emulator talking to a local server, or
   your machine's LAN IP for a real device on a local server. No source
   edit needed — this is read into a `BuildConfig` field at build time.
2. Build and install with `./gradlew assembleDebug`, or open `android/` in
   Android Studio.
3. Tap "Share my screen", grant the screen-capture permission (and the
   notification permission, on Android 13+), and the app will display a
   large, easy-to-read help code once connected to the signaling server.

The app is localized in English, Catalan, and Spanish (`res/values-ca`,
`res/values-es`), and its UI is deliberately dead-simple — one toggle
button and a big code display — since the actual users are the
maintainer's elderly, non-technical parents, not a general audience.
