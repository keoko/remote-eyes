/*
 * Regression test for a real bug found by manual testing: helper.html's
 * createPeerConnection() became async (awaiting the /ice-config fetch)
 * without the message handler waiting for it, so a phone's SDP offer
 * arriving before that fetch resolved crashed with
 * "TypeError: can't access property 'setRemoteDescription',
 * peerConnection is undefined".
 *
 * This extracts the actual <script> content from helper.html (not a
 * hand-copied duplicate, so it can't silently drift out of sync) and
 * runs it in a sandboxed context with mocked browser APIs, deliberately
 * delaying the fake fetch so the offer/ICE-candidate messages arrive
 * first - reproducing the exact race.
 */

const vm = require("vm");
const fs = require("fs");
const path = require("path");

const html = fs.readFileSync(path.join(__dirname, "..", "helper.html"), "utf8");
const scriptMatch = html.match(/<script>([\s\S]*?)<\/script>/);

if (!scriptMatch) {
    console.log("FAIL - could not find a <script> block in helper.html");
    process.exit(1);
}

const script = scriptMatch[1];

function fakeElement() {
    const listeners = {};
    return {
        value: "",
        textContent: "",
        dataset: {},
        style: {},
        _clickHandler: null,
        set onclick(fn) { this._clickHandler = fn; },
        get onclick() { return this._clickHandler; },
        addEventListener(type, fn) {
            listeners[type] = listeners[type] || [];
            listeners[type].push(fn);
        },
        focus() {},
        click() { if (this._clickHandler) this._clickHandler(); }
    };
}

const elements = {
    code: fakeElement(),
    join: fakeElement(),
    status: fakeElement(),
    screen: fakeElement(),
    videoPlaceholder: fakeElement()
};

let fetchCalls = 0;
function fakeFetch() {
    fetchCalls++;
    return new Promise(resolve => {
        setTimeout(() => {
            resolve({
                json: () => Promise.resolve({
                    iceServers: [{ urls: "stun:stun.l.google.com:19302" }]
                })
            });
        }, 50); // slower than the synchronous offer/ICE dispatch below
    });
}

let peerConnectionInstances = [];
let setRemoteDescriptionCalled = false;

class FakeRTCPeerConnection {
    constructor(config) {
        this.config = config;
        peerConnectionInstances.push(this);
    }
    setRemoteDescription() {
        setRemoteDescriptionCalled = true;
        return Promise.resolve();
    }
    createAnswer() {
        return Promise.resolve({ sdp: "fake-answer-sdp" });
    }
    setLocalDescription() {
        return Promise.resolve();
    }
    addIceCandidate() {
        return Promise.resolve();
    }
}

let sentMessages = [];
let wsInstance;

class FakeWebSocket {
    constructor(url) {
        this.url = url;
        wsInstance = this;
    }
    send(data) {
        sentMessages.push(JSON.parse(data));
    }
}

const errors = [];

const sandbox = {
    document: { getElementById: id => elements[id] },
    window: { location: { protocol: "https:", host: "remote-eyes-server.fly.dev" } },
    console: {
        log: () => {},
        error: (...args) => { errors.push(args); }
    },
    fetch: fakeFetch,
    WebSocket: FakeWebSocket,
    RTCPeerConnection: FakeRTCPeerConnection,
    JSON,
    Promise,
    setTimeout
};

vm.createContext(sandbox);

process.on("unhandledRejection", reason => {
    errors.push(["unhandledRejection", reason]);
});

vm.runInContext(script, sandbox);

async function run() {
    elements.code.value = "482731";
    elements.join.click();
    wsInstance.onopen();

    if (JSON.stringify(sentMessages[0]) !== JSON.stringify({ type: "join", code: "482731" })) {
        throw new Error("Did not send expected join message: " + JSON.stringify(sentMessages));
    }

    if (fetchCalls !== 1) {
        throw new Error("Expected exactly 1 fetch call at this point, got " + fetchCalls);
    }

    // Fire "joined" immediately followed by the offer, same tick - no
    // await in between, matching how two separate WebSocket message
    // events actually arrive. Neither has waited for the slow fetch yet.
    const joinedPromise = wsInstance.onmessage({ data: JSON.stringify({ type: "joined" }) });
    const offerPromise = wsInstance.onmessage({
        data: JSON.stringify({ type: "signal", signalType: "offer", sdp: "fake-offer-sdp" })
    });

    await Promise.all([joinedPromise, offerPromise]);
    await new Promise(r => setTimeout(r, 100));

    if (errors.length > 0) {
        throw new Error("Errors occurred: " + JSON.stringify(errors));
    }

    if (peerConnectionInstances.length !== 1) {
        throw new Error("Expected exactly 1 RTCPeerConnection, got " + peerConnectionInstances.length);
    }

    if (!setRemoteDescriptionCalled) {
        throw new Error("setRemoteDescription was never called - handleOffer did not run against a real peerConnection");
    }

    let addIceCandidateCalls = 0;
    peerConnectionInstances[0].addIceCandidate = () => { addIceCandidateCalls++; return Promise.resolve(); };

    await wsInstance.onmessage({
        data: JSON.stringify({
            type: "signal",
            signalType: "ice-candidate",
            sdpMid: "0",
            sdpMLineIndex: 0,
            candidate: "candidate:fake"
        })
    });

    if (addIceCandidateCalls !== 1) {
        throw new Error("addIceCandidate was not called for the ICE candidate message");
    }

    console.log("PASS - offer/ICE candidates arriving before the /ice-config fetch resolves no longer crash");
}

run().catch(err => {
    console.log("FAIL -", err.message);
    process.exit(1);
});
