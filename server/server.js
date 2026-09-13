const http = require("http");
const crypto = require("crypto");
const WebSocket = require("ws");
const fs = require("fs");
const path = require("path");


const PORT = 8080;
const SESSION_TTL = 5 * 60 * 1000;

const sessions = new Map();

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

wss.on("connection", (ws) => {
    console.log("Client connected");

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
                createSession(ws);
                break;

            case "join":
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
