const WebSocket = require("ws");
const readline = require("readline");

const rl = readline.createInterface({
    input: process.stdin,
    output: process.stdout
});

const ws = new WebSocket("ws://localhost:8080");

ws.on("open", () => {
    rl.question("Enter help code: ", (code) => {
        code = code.replace(/\s/g, "");

        ws.send(JSON.stringify({
            type: "join",
            code
        }));
    });
});

ws.on("message", (data) => {
    const message = JSON.parse(data.toString());

    console.log("Server:", message);

    if (message.type === "joined") {
        console.log("Successfully joined help session.");
    }

    if (message.type === "error") {
        console.log("Error:", message.message);
    }
});

ws.on("close", () => {
    console.log("Disconnected.");
});
