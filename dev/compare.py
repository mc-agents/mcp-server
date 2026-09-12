"""Run the same tool calls against two bots and diff what came back.

The MCP surface is the contract. An agent is supposed to be unable to tell which kind of bot
answered, and the only way to know that holds is to ask both and compare the strings.

    python3 dev/compare.py alice bravo

Both names must already be joined to the same server, standing in the same place. What this finds
is a renderer that only one kind reaches, a DTO field one kind leaves out, and a tool that says
"not supported" on a bot the catalogue says supports it. What it cannot find is two bots that are
wrong in the same way; for that there is a real server on the other end.

A measurement -- a distance, a timestamp, the world tick -- is taken out of both answers before
they are compared, because it is a fact about when the call happened and not about the world. Only
three tools are excused wholesale, and they are the ones whose whole answer is about the bot that
answered. Anything else is a finding.
"""

import json
import os
import re
import sys
import urllib.request
from pathlib import Path

BASE = os.environ.get("MCP_BASE", "http://127.0.0.1:13000/mcp")
TOKEN = os.environ.get("MCP_AUTH_TOKEN")
REPO = Path(__file__).resolve().parent.parent
CATALOG = json.loads((REPO / "catalog/catalog.json").read_text())

# Enough to satisfy required arguments. The same values go to both bots, which is the point.
# Aimed at what dev/fixture.sh puts in the world: the sign at (3, -60, 0), the chest at
# (1, -60, 3), the named cow, the diamond blocks. Against an empty world every one of these
# answers "nothing there" and the two kinds of bot agree about a world neither can see.
SAMPLES = {
    "blockType": "minecraft:diamond_block", "itemName": "minecraft:stick",
    "outputItem": "minecraft:torch", "nameOrType": "diamond", "message": "hello",
    "command": "/help", "text": "/he", "prefix": "/he",
    "pattern": "never-matches-this", "titlePattern": "chest", "slot": 0,
    "x": 3, "y": -60, "z": 0, "direction": "forward", "ticks": 2, "label": "Confirm",
    "type": "minecraft:cow", "entity": "Probe Cow",
    "inputItem": "minecraft:iron_ore", "fuelItem": "minecraft:coal", "timeoutMs": 1500,
    "collectMs": 300, "durationMs": 200, "count": 3, "host": "127.0.0.1",
    # Nullable and defaulted to null, so this asks set-stance to report the stance without
    # changing it, which both kinds have to word the same way.
    "sneak": None, "sprint": None,
}

# Tools that leave the world different for the bot asked second, so the two answers are about two
# worlds. Turning, jumping and crouching are not among them: they change nothing a compared tool
# reports, and their sentences are the bot's own, which is exactly what has to be checked. A tool
# excluded here is a tool whose wording nothing holds to the other kind's.
NOT_COMPARABLE = {
    "get-bot-status", "detect-gamemode", "list-bots",  # name the bot, so they cannot match
    "join-server", "leave-server", "restart-bot", "switch-server",  # move the bot out from under
    "move-to-position", "fly-to", "move-in-direction",  # leave the bot somewhere else
    "dig-block", "place-block", "activate-block", "give-item", "equip-item", "drop-held-item",
    "click-slot", "craft-item", "smelt-item", "attack-entity", "interact-entity", "use-held-item",
    "send-chat", "run-command", "fish",  # change the world, so the second bot sees a different one
}

# A wait that is meant to expire tells nothing when it expires twice, and costs its timeout on
# each bot. What they wait on is covered by the feed reads, which are compared.
NOT_COMPARABLE |= {name for name in (tool["name"] for tool in CATALOG["tools"])
                   if name.startswith("wait-")}

# A measurement is a fact about when or where the call happened rather than about the world, and
# two bots never take the same one. It comes out before the comparison rather than being excused
# after: an excuse covers the whole answer, and an answer says more than the measurement in it. A
# blanket "blocks away" excuse hid the two kinds disagreeing about what find-entity calls an
# entity's type, on the same line, for as long as it existed.
NOISE = [
    (re.compile(r"\[\d{4}-\d{2}-\d{2}T[\d:.]+Z\]"), "[when]"),
    (re.compile(r"-?\d+(?:\.\d+)? blocks away"), "<distance> blocks away"),
    (re.compile(r"tick \d+ of the day"), "tick <n> of the day"),
]


def settled(text):
    for pattern, instead in NOISE:
        text = pattern.sub(instead, text)
    return text


# Tools whose whole answer is about the bot that answered, and so cannot match. Naming the tool
# rather than matching a sentence keeps the allowance from spreading to answers that merely mention
# a coordinate: the ones the caller asked about are the same on both bots and have to match.
ABOUT_THE_BOT = {
    "get-position": "where that bot is standing",
    "get-player-state": "where that bot is standing",
    "read-player-list": "the list marks the caller",
}

# Refusals that can land on any tool, and are the two kinds of bot agreeing about a gap.
EXPECTED = [
    (re.compile(r"is not supported by bot"), "both refused it, naming the bot that was asked"),
    (re.compile(r"does not implement"), "one kind has not written it yet, which is allowed"),
]


def session():
    headers = {"Content-Type": "application/json", "Accept": "application/json, text/event-stream"}
    if TOKEN:
        headers["Authorization"] = "Bearer " + TOKEN

    opening = json.dumps({
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                   "clientInfo": {"name": "compare", "version": "0"}},
    }).encode()

    with urllib.request.urlopen(urllib.request.Request(BASE, opening, headers)) as answer:
        sid = answer.headers["mcp-session-id"]
        answer.read()

    headers["mcp-session-id"] = sid
    urllib.request.urlopen(urllib.request.Request(
        BASE, json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}).encode(), headers)).read()

    return headers


def call(headers, tool, args):
    body = json.dumps({"jsonrpc": "2.0", "id": 9, "method": "tools/call",
                       "params": {"name": tool, "arguments": args}}).encode()

    with urllib.request.urlopen(urllib.request.Request(BASE, body, headers)) as answer:
        for line in answer.read().decode().splitlines():
            if line.startswith("data:"):
                result = json.loads(line[5:]).get("result", {})
                content = result.get("content", [{}])
                kinds = [c.get("type") for c in content]
                text = content[0].get("text", "") if content else ""
                return bool(result.get("isError")), settled(text), kinds
    return True, "no answer", []


# A rendered null is a field the bot did not send under the name the catalogue uses, and the
# renderer had nothing to put there. "Position: null" was excused by the rule about positions
# differing, which is how a bot standing somewhere definite reported standing nowhere.
BROKEN = re.compile(r"\bnull\b|\bundefined\b|\bNaN\b")


def explained(tool, left, right):
    if BROKEN.search(left) or BROKEN.search(right):
        return None
    if tool in ABOUT_THE_BOT:
        return ABOUT_THE_BOT[tool]
    for pattern, why in EXPECTED:
        if pattern.search(left) or pattern.search(right):
            return why
    return None


def main(first, second):
    headers = session()
    same, expected, findings = 0, [], []

    for tool in CATALOG["tools"]:
        name = tool["name"]

        if name in NOT_COMPARABLE or tool["route"] == "orchestrate":
            continue

        required = tool["inputSchema"].get("required") or []
        if any(field not in SAMPLES for field in required):
            print("skip %-22s no sample for %s" % (name, [f for f in required if f not in SAMPLES]))
            continue

        args = {field: SAMPLES[field] for field in required}
        # One line of a feed, not the history: the two bots joined at different times, so the
        # depth of what they have seen is a fact about when they arrived and not about the world.
        if name.startswith("read-") and "count" in (tool["inputSchema"].get("properties") or {}):
            args["count"] = 1
        print("  %s" % name, end="\r", flush=True)
        left = call(headers, name, dict(args, bot=first))
        right = call(headers, name, dict(args, bot=second))

        if left == right:
            same += 1
            continue

        why = explained(name, left[1], right[1])
        if why is not None:
            expected.append((name, why))
            continue

        findings.append((name, left, right))

    print(" " * 40, end="\r")

    for name, why in expected:
        print("differs %-20s %s" % (name, why))

    for name, left, right in findings:
        print("\nFINDING %s" % name)
        print("  %-8s %s%s" % (first, "ERR " if left[0] else "", left[1].replace("\n", " ⏎ ")[:220]))
        print("  %-8s %s%s" % (second, "ERR " if right[0] else "", right[1].replace("\n", " ⏎ ")[:220]))
        if left[2] != right[2]:
            print("  content: %s vs %s" % (left[2], right[2]))

    print("\nidentical %d | differ for a reason %d | findings %d" % (same, len(expected), len(findings)))

    return 1 if findings else 0


if __name__ == "__main__":
    if len(sys.argv) != 3:
        print(__doc__)
        raise SystemExit(2)
    raise SystemExit(main(sys.argv[1], sys.argv[2]))
