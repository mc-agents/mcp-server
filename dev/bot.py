import json, os, socket, struct, sys, threading, time
from pathlib import Path

HOST = os.environ.get("BOT_LINK_HOST", "127.0.0.1")
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
NAME = sys.argv[2] if len(sys.argv) > 2 else "alice"
KIND = sys.argv[3] if len(sys.argv) > 3 else "fabric"
REPO = str(Path(__file__).resolve().parent.parent)
CATALOG = json.load(open(REPO + "/catalog/catalog.json"))

# A structured tool has to answer with a DTO. The golden cases are real ones, so serving them back
# exercises every renderer over the wire instead of only in a unit test.
GOLDEN = {}
for path in sorted(Path(REPO + "/src/test/resources/render").glob("*.json")):
    case = json.loads(path.read_text())
    GOLDEN.setdefault(case["tool"], case["data"])

JSON, BLOB = 0, 1
seq = 0
state = {"address": None, "username": None}
lock = threading.Lock()


def frame(sock, payload, kind=JSON):
    with lock:
        sock.sendall(struct.pack(">I", len(payload) + 1) + bytes([kind]) + payload)


def send(sock, obj):
    frame(sock, json.dumps(obj).encode())


def readexact(sock, n):
    buf = b""
    while len(buf) < n:
        step = sock.recv(n - len(buf))
        if not step:
            raise EOFError
        buf += step
    return buf


def readframe(sock):
    (length,) = struct.unpack(">I", readexact(sock, 4))
    body = readexact(sock, length)
    return body[0], body[1:]


def now():
    return int(time.time() * 1000)


def status(sock, st, **extra):
    payload = {"t": "status", "state": st, "ts": now(),
               "address": state["address"], "username": state["username"]}
    payload.update(extra)
    send(sock, payload)


def chat(sock, text):
    global seq
    seq += 1
    send(sock, {"t": "event", "seq": seq, "kind": "chat", "source": "system", "text": text,
                "segments": [], "ts": now(), "firstTs": now(), "repeats": 1, "closed": False})


sock = socket.create_connection((HOST, PORT))
# A compose tool that watches another has no wire schema of its own: the server builds it from the
# tool it watches, and a bot that announced it would be reporting a hash it cannot have.
caps = [
    {"tool": t["name"], "argsHash": t["wireSchemaHash"]}
    for t in CATALOG["tools"]
    if t.get("wireSchemaHash") and KIND in t["kinds"]
]
send(sock, {
    "t": "hello", "protocols": [CATALOG["protocol"]], "botName": NAME, "kind": KIND,
    "agentVersion": "0.0.1", "mcVersion": "26.1.2", "catalogVersion": CATALOG["catalogVersion"],
    "capabilities": caps, "features": ["blob", "eventFold"],
})

kind, payload = readframe(sock)
reply = json.loads(payload)
print("handshake:", reply["t"], "accepted", len(reply.get("acceptedTools", [])),
      "rejected", len(reply.get("rejectedTools", [])), flush=True)

while True:
    kind, payload = readframe(sock)
    if kind != JSON:
        continue
    msg = json.loads(payload)
    t = msg["t"]

    if t == "ping":
        send(sock, {"t": "pong", "nonce": msg["nonce"], "ts": now(), "busy": 0})

    elif t == "connect":
        print("connect:", msg["host"], msg["port"], msg["username"], flush=True)
        state["address"] = "%s:%d" % (msg["host"], msg["port"])
        state["username"] = msg["username"]
        status(sock, "connecting")
        chat(sock, "%s joined the game" % msg["username"])
        status(sock, "ready", mcVersion="26.1.2", serverBrand="Paper", gameMode="survival",
               dimension="minecraft:overworld",
               position={"x": 8.5, "y": 65.0, "z": -12.5}, health=20.0, food=20.0)
        send(sock, {"t": "result", "id": msg["id"], "ok": True, "text": "joined", "elapsedMs": 900})

    elif t == "disconnect":
        print("disconnect:", msg["reason"], flush=True)
        status(sock, "disconnected", reason=msg["reason"])
        state["address"] = None
        send(sock, {"t": "result", "id": msg["id"], "ok": True, "text": "left", "elapsedMs": 40})

    elif t == "call":
        print("call:", msg["tool"], msg["args"], flush=True)
        if msg["tool"] == "run-command":
            send(sock, {"t": "result", "id": msg["id"], "ok": True,
                        "text": "Ran /%s." % msg["args"]["command"].lstrip("/"), "elapsedMs": 12})
            chat(sock, "Unknown command. Type /help for a list.")
        elif msg["tool"] == "switch-server":
            state["address"] = "%s:25565" % msg["args"]["target"]
            status(sock, "ready", mcVersion="26.1.2", serverBrand="Paper", gameMode="survival",
                   dimension="minecraft:overworld",
                   position={"x": 0.5, "y": 70.0, "z": 0.5}, health=20.0, food=20.0)
            send(sock, {"t": "result", "id": msg["id"], "ok": True,
                        "text": "Moved to %s." % msg["args"]["target"], "elapsedMs": 700})
        elif msg["tool"] == "get-position":
            send(sock, {"t": "result", "id": msg["id"], "ok": True, "text": "x=8 y=65 z=-12",
                        "data": {"position": {"x": 8, "y": 65, "z": -13},
                                 "dimension": "minecraft:overworld", "onGround": True,
                                 "yaw": 90.0, "pitch": 0.0}, "elapsedMs": 3})
        elif msg["tool"] in GOLDEN:
            send(sock, {"t": "result", "id": msg["id"], "ok": True,
                        "text": "%s (text fallback)" % msg["tool"],
                        "data": GOLDEN[msg["tool"]], "elapsedMs": 4})
        else:
            send(sock, {"t": "result", "id": msg["id"], "ok": True,
                        "text": "%s ran" % msg["tool"], "elapsedMs": 1})
