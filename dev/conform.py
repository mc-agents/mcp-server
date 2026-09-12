"""Hold a bot to the half of the protocol that is the bot's.

    python3 dev/conform.py 18777
    # then start the bot pointed at that port

The server's half is covered by unit tests in src/test: a bot that lies about a hash, sends a frame
before hello, or never answers is what BotLinkServerTest and BotLinkTest are about. This is the
other side. It plays the server badly on purpose and checks the bot keeps its end: one result per
id whatever happens, a pong that echoes the nonce, a refusal for a tool it never offered, blobs
before the result that names them, and a link that survives all of it.

A third kind of bot would be written against docs/bot-protocol.md and this is what would say it had
been written correctly. The two that exist have to pass it as well, because a contract nothing
checks is a description of what one implementation happened to do.

Nothing here needs the bot to be in a world. It never sends connect, so a bot answers every tool
with "not in a world" -- which is a result, and a result is what is being checked.
"""

import json
import socket
import struct
import sys
import threading
import time
import uuid
from pathlib import Path

JSON_FRAME = 0
BLOB_FRAME = 1

REPO = Path(__file__).resolve().parent.parent
CATALOG = json.loads((REPO / "catalog/catalog.json").read_text())
BY_NAME = {tool["name"]: tool for tool in CATALOG["tools"]}

# A tool every kind of bot has, whose arguments are trivial and whose answer is immediate.
PROBE = "get-position"

# A tool no catalogue will ever have, for the case where the server asks for something the bot
# never offered. The bot must answer it rather than ignore it.
UNKNOWN = "no-such-tool-at-all"


class Link:
    """The server end of one bot's link, with everything it has said kept for the checks."""

    def __init__(self, conn):
        self.conn = conn
        self.frames = []
        self.blobs = []
        self.violations = []
        self.lock = threading.Condition()
        self.alive = True

    def send(self, message):
        payload = json.dumps(message).encode()
        self.conn.sendall(struct.pack(">IB", len(payload) + 1, JSON_FRAME) + payload)

    def send_raw(self, body, kind=JSON_FRAME):
        self.conn.sendall(struct.pack(">IB", len(body) + 1, kind) + body)

    def pump(self):
        buffer = b""
        while self.alive:
            try:
                chunk = self.conn.recv(65536)
            except OSError:
                break
            if not chunk:
                break
            buffer += chunk
            while len(buffer) >= 4:
                (length,) = struct.unpack(">I", buffer[:4])
                if len(buffer) < 4 + length:
                    break
                frame, buffer = buffer[4:4 + length], buffer[4 + length:]
                self.on_frame(frame[0], frame[1:])
        with self.lock:
            self.alive = False
            self.lock.notify_all()

    def on_frame(self, kind, body):
        with self.lock:
            if kind == BLOB_FRAME:
                self.blobs.append(str(uuid.UUID(bytes=body[:16])))
            elif kind == JSON_FRAME:
                try:
                    self.frames.append(json.loads(body))
                except ValueError:
                    self.violations.append("a frame the server could not parse as JSON")
            else:
                self.violations.append("frame type %d, which the protocol does not have" % kind)
            self.lock.notify_all()

    def until(self, predicate, seconds):
        """Wait for something the bot said. Returns it, or None when the time ran out."""
        deadline = time.monotonic() + seconds
        with self.lock:
            while True:
                for frame in self.frames:
                    if predicate(frame):
                        return frame
                left = deadline - time.monotonic()
                if left <= 0 or not self.alive:
                    return None
                self.lock.wait(left)

    def results(self, call_id):
        with self.lock:
            return [f for f in self.frames if f.get("t") == "result" and f.get("id") == call_id]


CHECKS = []


def check(title):
    def wrap(body):
        CHECKS.append((title, body))
        return body
    return wrap


def call(link, tool, args=None, deadline=5000):
    call_id = str(uuid.uuid4())
    link.send({"t": "call", "id": call_id, "tool": tool, "args": args or {}, "deadlineMs": deadline})
    return call_id


@check("hello is the first thing it says, and it says what the catalogue needs")
def hello_first(link, hello):
    for field in ("protocols", "botName", "kind", "mcVersion", "catalogVersion", "capabilities"):
        if field not in hello:
            return "hello has no %s" % field
    if not isinstance(hello["protocols"], list) or not hello["protocols"]:
        return "protocols is not a list of at least one version"
    if hello["kind"] not in ("mineflayer", "fabric"):
        return "kind is %r, which no catalogue tool lists" % hello["kind"]
    return None


@check("every tool it offers is in the catalogue, with the hash the catalogue gives")
def capabilities_match(link, hello):
    wrong = []
    for capability in hello["capabilities"]:
        tool = BY_NAME.get(capability["tool"])
        if tool is None:
            wrong.append("%s is not in the catalogue" % capability["tool"])
        elif capability["argsHash"] != tool["wireSchemaHash"]:
            wrong.append("%s carries a hash the catalogue does not" % capability["tool"])
        elif hello["kind"] not in tool["kinds"]:
            wrong.append("%s is not offered to a %s bot" % (capability["tool"], hello["kind"]))
    return "; ".join(wrong[:4]) if wrong else None


@check("a ping comes back as a pong carrying the same nonce")
def ping_pong(link, hello):
    nonce = 4242
    link.send({"t": "ping", "nonce": nonce, "ackEventSeq": 0})
    pong = link.until(lambda f: f.get("t") == "pong", 5)

    if pong is None:
        return "no pong within 5s"
    if pong.get("nonce") != nonce:
        return "pong carried nonce %r, not the one the ping had" % pong.get("nonce")
    if not isinstance(pong.get("busy"), int) or isinstance(pong.get("busy"), bool):
        return "busy is %r; the protocol says how many calls are in flight" % pong.get("busy")
    return None


@check("a call gets exactly one result")
def one_result(link, hello):
    call_id = call(link, PROBE)

    if link.until(lambda f: f.get("t") == "result" and f.get("id") == call_id, 10) is None:
        return "no result within 10s"

    time.sleep(1)
    answers = link.results(call_id)

    return None if len(answers) == 1 else "%d results for one call" % len(answers)


@check("a tool it never offered is refused rather than ignored")
def unknown_tool(link, hello):
    call_id = call(link, UNKNOWN)
    answer = link.until(lambda f: f.get("t") == "result" and f.get("id") == call_id, 10)

    if answer is None:
        return "no result within 10s; an unknown tool has to be answered like any other"
    if answer.get("ok"):
        return "it claimed to have run a tool that does not exist"
    if not answer.get("error", {}).get("code"):
        return "the refusal carries no error code"
    return None


@check("a cancel still leaves exactly one result for the id")
def cancel_once(link, hello):
    call_id = call(link, "wait-ticks", {"ticks": 200}, deadline=60000)
    time.sleep(0.5)
    link.send({"t": "cancel", "id": call_id, "reason": "conformance"})

    if link.until(lambda f: f.get("t") == "result" and f.get("id") == call_id, 10) is None:
        return "no result after cancelling; the id was left unanswered"

    time.sleep(1)
    answers = link.results(call_id)

    return None if len(answers) == 1 else "%d results for one cancelled call" % len(answers)


@check("a cancel for an id it has already answered changes nothing")
def cancel_after_result(link, hello):
    call_id = call(link, PROBE)

    if link.until(lambda f: f.get("t") == "result" and f.get("id") == call_id, 10) is None:
        return "no result within 10s"

    link.send({"t": "cancel", "id": call_id, "reason": "late"})
    time.sleep(1)
    answers = link.results(call_id)

    return None if len(answers) == 1 else "a late cancel produced a second result"


@check("two calls in flight are told apart")
def two_in_flight(link, hello):
    first = call(link, "wait-ticks", {"ticks": 20}, deadline=30000)
    second = call(link, "wait-ticks", {"ticks": 5}, deadline=30000)

    for call_id in (second, first):
        if link.until(lambda f: f.get("t") == "result" and f.get("id") == call_id, 15) is None:
            return "one of two calls in flight was never answered"

    return None


@check("every blob it names arrives before the result that names it")
def blobs_first(link, hello):
    if hello["kind"] != "fabric":
        return None

    with link.lock:
        seen = len(link.blobs)

    call_id = call(link, "screenshot", {"width": 320, "quality": 40}, deadline=30000)
    answer = link.until(lambda f: f.get("t") == "result" and f.get("id") == call_id, 30)

    if answer is None:
        return "no result within 30s"
    if not answer.get("ok"):
        return None

    named = [blob["id"] for blob in answer.get("blobs") or []]

    if not named:
        return "a screenshot came back with no blob named in the result"

    with link.lock:
        arrived = link.blobs[seen:]

    missing = [blob for blob in named if blob not in arrived]

    return "the result named %d blob(s) that had not arrived" % len(missing) if missing else None


@check("it keeps answering after the server sends something it cannot read")
def survives_rubbish(link, hello):
    link.send_raw(b"{not json at all")

    call_id = call(link, PROBE)
    answer = link.until(lambda f: f.get("t") == "result" and f.get("id") == call_id, 10)

    if answer is not None:
        return None
    # Closing the link is the other allowed answer: the contract calls unreadable JSON a fault.
    fault = link.until(lambda f: f.get("t") == "log" and "MALFORMED" in json.dumps(f), 1)

    return None if fault is not None or not link.alive else "it went quiet instead of faulting"


@check("everything it sent was a message the protocol has")
def frames_are_legal(link, hello):
    allowed = {"hello", "result", "event", "status", "log", "pong"}

    with link.lock:
        unknown = {f.get("t") for f in link.frames} - allowed

    if unknown:
        return "it sent " + ", ".join(sorted(str(t) for t in unknown))
    return "; ".join(link.violations) if link.violations else None


def main(port):
    listener = socket.socket()
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("127.0.0.1", port))
    listener.listen(1)

    print("waiting for a bot on 127.0.0.1:%d" % port)
    conn, _ = listener.accept()

    link = Link(conn)
    threading.Thread(target=link.pump, daemon=True).start()

    hello = link.until(lambda f: f.get("t") == "hello", 30)

    if hello is None:
        print("FAIL  the bot connected and never said hello")
        return 1

    print("%s (%s), catalogue %s, %d tools\n"
          % (hello.get("botName"), hello.get("kind"), hello.get("catalogVersion"),
             len(hello.get("capabilities") or [])))

    link.send({
        "t": "helloOk", "protocol": CATALOG["protocol"], "sessionId": str(uuid.uuid4()),
        "heartbeatMs": 5000, "repeatFlushMs": 1000,
        "limits": {"maxFrameBytes": 2 * 1024 * 1024, "maxInFlight": 8},
        "events": {"chat": True, "actionBar": True, "title": True, "dialog": True, "effect": True},
        "acceptedTools": [c["tool"] for c in hello["capabilities"]], "rejectedTools": [],
    })

    failed = 0

    for title, body in CHECKS:
        try:
            complaint = body(link, hello)
        except Exception as error:                                  # noqa: BLE001
            complaint = "the check itself broke: %r" % error

        print("%-4s %s%s" % ("FAIL" if complaint else "ok", title,
                             "\n       " + complaint if complaint else ""))
        failed += 1 if complaint else 0

    link.send({"t": "shutdown", "reason": "conformance run finished", "graceMs": 1000})
    print("\n%d check(s) failed" % failed)

    return 1 if failed else 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        raise SystemExit(2)
    raise SystemExit(main(int(sys.argv[1])))
