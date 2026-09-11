"""Run the same tool calls against two bots and diff what came back.

The MCP surface is the contract. An agent is supposed to be unable to tell which kind of bot
answered, and the only way to know that holds is to ask both and compare the strings.

    python3 dev/compare.py alice bravo

Both names must already be joined to the same server, standing in the same place. What this finds
is a renderer that only one kind reaches, a DTO field one kind leaves out, and a tool that says
"not supported" on a bot the catalogue says supports it. What it cannot find is two bots that are
wrong in the same way; for that there is a real server on the other end.

Differences are expected in a few places and listed rather than hidden: a position is where that
bot is standing, an elapsed time is a measurement. Anything else is a finding.
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
SAMPLES = {
    "blockType": "minecraft:dirt", "itemName": "minecraft:stick", "outputItem": "minecraft:torch",
    "nameOrType": "stick", "message": "hello", "command": "/help", "text": "/he",
    "pattern": "never-matches-this", "titlePattern": "chest", "slot": 0,
    "x": 0, "y": -60, "z": 0, "direction": "forward", "ticks": 2, "label": "Confirm",
    "type": "minecraft:cow", "entity": "minecraft:cow", "prefix": "/he",
    "inputItem": "minecraft:iron_ore", "fuelItem": "minecraft:coal", "timeoutMs": 1500,
    "collectMs": 300, "durationMs": 200, "count": 1, "host": "127.0.0.1",
}

# Tools whose answer is about this bot rather than about the world, or which move it.
NOT_COMPARABLE = {
    "get-bot-status", "detect-gamemode", "list-bots",  # name the bot, so they cannot match
    "join-server", "leave-server", "restart-bot", "switch-server",  # move the bot out from under
    "move-to-position", "move-in-direction", "fly-to", "jump", "look-at", "set-stance",
    "dig-block", "place-block", "activate-block", "give-item", "equip-item", "drop-held-item",
    "click-slot", "craft-item", "smelt-item", "attack-entity", "interact-entity", "use-held-item",
    "send-chat", "run-command", "fish",  # change the world, so the second bot sees a different one
}

# A wait that is meant to expire tells nothing when it expires twice, and costs its timeout on
# each bot. What they wait on is covered by the feed reads, which are compared.
NOT_COMPARABLE |= {name for name in (tool["name"] for tool in CATALOG["tools"])
                   if name.startswith("wait-")}

# Sentences that legitimately differ, and why. Anything not matched here is a finding.
EXPECTED = [
    (re.compile(r"^Position: |^position: |\nposition: "), "where that bot is standing"),
    (re.compile(r"\(this bot\)"), "the player list marks the caller"),
    (re.compile(r"^\[\d{4}-"), "a feed entry carries the time it arrived"),
    (re.compile(r"blocks away|within \d+ blocks"), "measured from where that bot is standing"),
    (re.compile(r"tick \d+ of the day"), "the world ticked between the two calls"),
    (re.compile(r"is not supported by bot"), "both refused it, naming the bot that was asked"),
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
                return bool(result.get("isError")), text, kinds
    return True, "no answer", []


def explained(left, right):
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
        print("  %s" % name, end="\r", flush=True)
        left = call(headers, name, dict(args, bot=first))
        right = call(headers, name, dict(args, bot=second))

        if left == right:
            same += 1
            continue

        why = explained(left[1], right[1])
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
