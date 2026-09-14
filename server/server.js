const http = require("http");
const crypto = require("crypto");
const WebSocket = require("ws");
const fs = require("fs");
const path = require("path");


const PORT = 8080;
const SESSION_TTL = 5 * 60 * 1000;
const RATE_LIMIT = 10;
const RATE_WINDOW = SESSION_TTL;

const sessions = new Map();
const joinAttempts = new Map();
const createAttempts = new Map();

function isRateLimited(attempts, ip) {
    const now = Date.now();
    const entry = attempts.get(ip);

    if (!entry || now - entry.windowStart > RATE_WINDOW) {
        attempts.set(ip, { count: 1, windowStart: now });
        return false;
    }

    entry.count += 1;

    return entry.count > RATE_LIMIT;
}

let turnCache = { iceServers: [], expiresAt: 0 };

async function getTurnIceServers() {
    if (turnCache.expiresAt > Date.now()) {
        return turnCache.iceServers;
    }

    const domain = process.env.METERED_DOMAIN;
    const secretKey = process.env.METERED_SECRET_KEY;

    if (!domain || !secretKey) {
        return [];
    }

    const expiryInSeconds = 24 * 60 * 60;

    try {
        const credResponse = await fetch(
            `https://${domain}/api/v1/turn/credential?secretKey=${secretKey}`,
            {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ expiryInSeconds, label: "remote-eyes" })
            }
        );
        const cred = await credResponse.json();

        const iceResponse = await fetch(
            `https://${domain}/api/v1/turn/credentials?apiKey=${cred.apiKey}`
        );
        const iceServers = await iceResponse.json();

        turnCache = {
            iceServers,
            expiresAt: Date.now() + (expiryInSeconds - 300) * 1000
        };

        return iceServers;
    } catch (err) {
        console.error("Failed to fetch TURN credentials:", err);
        return turnCache.iceServers;
    }
}

function generateCode() {
    let code;

    do {
        code = crypto.randomInt(100000, 1000000).toString();
    } while (sessions.has(code));

    return code;
}

function send(ws, message) {
    if (ws?.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify(message));
    }
}

function createSession(ws) {
    const code = generateCode();

    sessions.set(code, {
        phone: ws,
        helper: null,
        expires: Date.now() + SESSION_TTL
    });

    ws.sessionCode = code;
    ws.role = "phone";

    console.log(`Session created: ${code}`);

    send(ws, {
        type: "help-code",
        code
    });
}

function joinSession(ws, code) {
    const session = sessions.get(code);

    if (!session) {
        send(ws, {
            type: "error",
            message: "Invalid or expired help code"
        });
        return;
    }

    if (session.helper) {
        send(ws, {
            type: "error",
            message: "A helper is already connected"
        });
        return;
    }

    session.helper = ws;

    ws.sessionCode = code;
    ws.role = "helper";

    console.log(`Helper joined: ${code}`);

    send(session.phone, {
        type: "helper-connected"
    });

    send(ws, {
        type: "joined"
    });
}

function relaySignal(ws, message) {
    const session = sessions.get(ws.sessionCode);

    if (!session) {
        return;
    }

    const target =
        ws.role === "phone"
            ? session.helper
            : session.phone;

    send(target, message);
}

function removeClient(ws) {
    const code = ws.sessionCode;

    if (!code) {
        return;
    }

    const session = sessions.get(code);

    if (!session) {
        return;
    }

    const other =
        ws.role === "phone"
            ? session.helper
            : session.phone;

    send(other, {
        type: "peer-disconnected"
    });

    if (ws.role === "phone") {
        sessions.delete(code);
        console.log(`Session removed: ${code}`);
    } else {
        session.helper = null;
        console.log(`Helper disconnected: ${code}`);
    }
}

const server = http.createServer((req, res) => {
    if (req.url.split("?")[0] === "/ice-config") {
        const query = new URLSearchParams(req.url.split("?")[1] || "");
        const code = query.get("code");
        const session = code != null ? sessions.get(code) : null;
        const hasActiveSession = session != null && session.helper != null;

        const respond = (turnServers) => {
            const iceServers = [
                { urls: "stun:stun.l.google.com:19302" },
                ...turnServers
            ];

            res.writeHead(200, { "Content-Type": "application/json" });
            res.end(JSON.stringify({ iceServers }));
        };

        if (!hasActiveSession) {
            respond([]);
            return;
        }

        getTurnIceServers().then(respond);
        return;
    }

    if (req.url === "/helper") {
        const file = fs.readFileSync(
            path.join(__dirname, "helper.html")
        );

        res.writeHead(200, {
            "Content-Type": "text/html; charset=utf-8"
        });

        res.end(file);
        return;
    }

    res.writeHead(404);
    res.end("Not found");
});


const wss = new WebSocket.Server({ server });

wss.on("connection", (ws, req) => {
    console.log("Client connected");

    ws.ip = req.socket.remoteAddress;

    ws.on("message", (data) => {
        let message;

        try {
            message = JSON.parse(data.toString());
        } catch {
            send(ws, {
                type: "error",
                message: "Invalid JSON"
            });
            return;
        }

        switch (message.type) {
            case "create":
                if (message.token !== process.env.APP_TOKEN) {
                    send(ws, {
                        type: "error",
                        message: "Unable to create session. Please try again."
                    });
                    break;
                }

                if (isRateLimited(createAttempts, ws.ip)) {
                    send(ws, {
                        type: "error",
                        message: "Too many attempts. Please wait a few minutes and try again."
                    });
                    break;
                }

                createSession(ws);
                break;

            case "join":
                if (isRateLimited(joinAttempts, ws.ip)) {
                    send(ws, {
                        type: "error",
                        message: "Too many attempts. Please wait a few minutes and try again."
                    });
                    break;
                }

                joinSession(ws, message.code);
                break;

            case "signal":
                relaySignal(ws, message);
                break;

            default:
                send(ws, {
                    type: "error",
                    message: "Unknown message type"
                });
        }
    });

    ws.on("close", () => {
        removeClient(ws);
    });
});

setInterval(() => {
    const now = Date.now();

    for (const [code, session] of sessions) {
        if (session.expires <= now) {
            send(session.phone, {
                type: "expired"
            });

            send(session.helper, {
                type: "expired"
            });

            session.phone?.close();
            session.helper?.close();

            sessions.delete(code);

            console.log(`Session expired: ${code}`);
        }
    }
}, 30_000);

server.listen(PORT, () => {
    console.log(`Signaling server listening on port ${PORT}`);
});
