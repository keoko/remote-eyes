const test = require("node:test");
const assert = require("node:assert/strict");
const WebSocket = require("ws");

const {
    sessions,
    joinAttempts,
    createAttempts,
    generateCode,
    createSession,
    joinSession,
    relaySignal,
    removeClient,
    isRateLimited
} = require("../server.js");

function fakeWs() {
    return {
        readyState: WebSocket.OPEN,
        sent: [],
        send(data) {
            this.sent.push(JSON.parse(data));
        }
    };
}

test.beforeEach(() => {
    sessions.clear();
    joinAttempts.clear();
    createAttempts.clear();
});

test("generateCode returns a 6-digit numeric string", () => {
    const code = generateCode();

    assert.match(code, /^\d{6}$/);
});

test("createSession stores the session and sends help-code", () => {
    const phone = fakeWs();

    createSession(phone);

    assert.equal(phone.role, "phone");
    assert.ok(phone.sessionCode);

    const session = sessions.get(phone.sessionCode);
    assert.equal(session.phone, phone);
    assert.equal(session.helper, null);
    assert.ok(session.expires > Date.now());

    assert.equal(phone.sent.length, 1);
    assert.deepEqual(phone.sent[0], { type: "help-code", code: phone.sessionCode });
});

test("joinSession with an invalid code sends an error and creates nothing", () => {
    const helper = fakeWs();

    joinSession(helper, "000000");

    assert.deepEqual(helper.sent, [
        { type: "error", message: "Invalid or expired help code" }
    ]);
    assert.equal(helper.sessionCode, undefined);
});

test("joinSession with a valid code connects the helper and notifies both sides", () => {
    const phone = fakeWs();
    createSession(phone);
    const code = phone.sessionCode;

    const helper = fakeWs();
    joinSession(helper, code);

    assert.equal(helper.role, "helper");
    assert.equal(helper.sessionCode, code);
    assert.equal(sessions.get(code).helper, helper);

    assert.deepEqual(helper.sent, [{ type: "joined" }]);
    assert.deepEqual(phone.sent[1], { type: "helper-connected" });
});

test("joinSession rejects a second helper without displacing the first", () => {
    const phone = fakeWs();
    createSession(phone);
    const code = phone.sessionCode;

    const firstHelper = fakeWs();
    joinSession(firstHelper, code);

    const secondHelper = fakeWs();
    joinSession(secondHelper, code);

    assert.deepEqual(secondHelper.sent, [
        { type: "error", message: "A helper is already connected" }
    ]);
    assert.equal(sessions.get(code).helper, firstHelper);
});

test("relaySignal forwards from phone to helper and vice versa", () => {
    const phone = fakeWs();
    createSession(phone);
    const code = phone.sessionCode;

    const helper = fakeWs();
    joinSession(helper, code);

    relaySignal(phone, { type: "signal", signalType: "offer", sdp: "x" });
    assert.deepEqual(helper.sent.at(-1), { type: "signal", signalType: "offer", sdp: "x" });

    relaySignal(helper, { type: "signal", signalType: "answer", sdp: "y" });
    assert.deepEqual(phone.sent.at(-1), { type: "signal", signalType: "answer", sdp: "y" });
});

test("relaySignal on an unknown session is a silent no-op", () => {
    const ws = fakeWs();
    ws.sessionCode = "999999";
    ws.role = "phone";

    assert.doesNotThrow(() => relaySignal(ws, { type: "signal" }));
});

test("removeClient: phone disconnecting notifies the helper and deletes the session", () => {
    const phone = fakeWs();
    createSession(phone);
    const code = phone.sessionCode;

    const helper = fakeWs();
    joinSession(helper, code);

    removeClient(phone);

    assert.deepEqual(helper.sent.at(-1), { type: "peer-disconnected" });
    assert.equal(sessions.has(code), false);
});

test("removeClient: helper disconnecting notifies the phone but keeps the session", () => {
    const phone = fakeWs();
    createSession(phone);
    const code = phone.sessionCode;

    const helper = fakeWs();
    joinSession(helper, code);

    removeClient(helper);

    assert.deepEqual(phone.sent.at(-1), { type: "peer-disconnected" });
    assert.equal(sessions.has(code), true);
    assert.equal(sessions.get(code).helper, null);
});

test("removeClient on a ws with no sessionCode is a silent no-op", () => {
    assert.doesNotThrow(() => removeClient(fakeWs()));
});

test("isRateLimited allows up to the limit, then blocks", () => {
    const attempts = new Map();
    const ip = "1.2.3.4";

    for (let i = 0; i < 10; i++) {
        assert.equal(isRateLimited(attempts, ip), false, `attempt ${i + 1} should be allowed`);
    }

    assert.equal(isRateLimited(attempts, ip), true);
});

test("isRateLimited resets after the window elapses", () => {
    const attempts = new Map();
    const ip = "1.2.3.4";

    for (let i = 0; i < 11; i++) {
        isRateLimited(attempts, ip);
    }
    assert.equal(isRateLimited(attempts, ip), true);

    // Simulate the window having elapsed, without actually waiting.
    attempts.get(ip).windowStart = Date.now() - (6 * 60 * 1000);

    assert.equal(isRateLimited(attempts, ip), false);
});
