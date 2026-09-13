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
   candidates through the server, then connect directly (or via STUN) and
   stream the phone's screen.
5. The session is torn down when either side disconnects or after a 5-minute
   TTL.

## Project layout

- `android/` — the Android app. Captures the screen via `MediaProjection` and
  streams it with WebRTC (`ScreenCaptureService.kt`, `SignalingClient.kt`).
- `server/` — a Node.js signaling server (`server.js`) plus the browser page
  a helper uses to view the stream (`helper.html`), served at `/helper`.

## Running the server

```
cd server
npm install
node server.js
```

Listens on port `8080` by default (see `PORT` in `server.js`). The helper
page is then available at `http://<server-host>:8080/helper`.

`server/cli-test-client.js` is a small terminal script for manually testing
the join flow against a running server, independent of the browser page.

## Running the Android app

1. Point `SIGNALING_URL` in `ScreenCaptureService.kt` at your signaling
   server (`ws://10.0.2.2:8080` for an emulator talking to a server on the
   host machine, or your machine's LAN IP for a real device).
2. Build and install with `./gradlew assembleDebug`, or open `android/` in
   Android Studio.
3. Tap "Share screen", grant the screen-capture permission, and the app will
   display a help code once connected to the signaling server.

## Known limitations

- ICE uses a public STUN server only (no TURN), so connections across some
  NATs — e.g. phone on cellular, helper elsewhere — may fail to establish.
- The 6-digit join code has no rate limiting on the server, so it's not
  resistant to brute-forcing within its 5-minute lifetime.
- The signaling connection is plain `ws://`/cleartext, meant for trusted
  local networks rather than the open internet.
