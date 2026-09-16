"""Call every tool in the catalogue once and report what came back."""
import json, os, urllib.request
from pathlib import Path

BASE = os.environ.get("MCP_BASE", "http://127.0.0.1:13000/mcp")
REPO = Path(__file__).resolve().parent.parent
CATALOG = json.loads((REPO / "catalog/catalog.json").read_text())

# Enough to get past required arguments; the fake bot answers anything.
SAMPLES = {
    "blockType": "minecraft:stone", "itemName": "minecraft:diamond", "outputItem": "minecraft:torch",
    "nameOrType": "diamond", "message": "hello", "command": "/help", "target": "lobby",
    "host": "127.0.0.1", "port": 25577, "name": "bob", "type": "minecraft:cow", "entity": "minecraft:cow",
    "text": "hi", "pattern": "never-matches-this", "titlePattern": "chest", "slot": 0,
    "x": 0, "y": 64, "z": 0, "direction": "forward", "id": "test", "key": "jump",
    "buttonId": "ok", "windowTitle": "Chest", "recipeIndex": 0, "timeoutMs": 1500,
    "collectMs": 200, "durationMs": 200, "fuelItem": "minecraft:coal", "inputItem": "minecraft:iron_ore",
    "prefix": "/he", "sound": "minecraft:entity.pig.ambient", "particle": "minecraft:flame",
    "scoreboard": "sidebar", "username": "bob", "owner": "test", "bot": "alice", "label": "Confirm", "ticks": 2,
    "match": "[Accept]", "slots": [0, 1], "steps": [{"press": "jump"}], "item": "1", "trade": "1",
    "primary": "speed", "value": True,
}

# The errors the world is allowed to answer with. The wait-for tools are asked for a pattern that
# never arrives, and the two that talk to a Minecraft server directly have none to talk to in CI.
# Anything else that errors is a tool the server refused, and the sweep exists to notice that.
EXPECTED_ERRORS = {
    "wait-for-action-bar", "wait-for-boss-bars", "wait-for-chat", "wait-for-dialog", "wait-for-displays",
    "wait-for-effect", "wait-for-item", "wait-for-player-list", "wait-for-scoreboard", "wait-for-title",
    "wait-for-toast", "ping-server", "wait-for-server",
}


def session():
    request = urllib.request.Request(BASE, method="POST", data=json.dumps({
        "jsonrpc": "2.0", "id": 1, "method": "initialize",
        "params": {"protocolVersion": "2025-06-18", "capabilities": {},
                   "clientInfo": {"name": "sweep", "version": "0"}},
    }).encode(), headers={"Content-Type": "application/json",
                          "Accept": "application/json, text/event-stream"})
    with urllib.request.urlopen(request) as answer:
        sid = answer.headers["mcp-session-id"]
        answer.read()

    note = urllib.request.Request(BASE, method="POST", data=json.dumps(
        {"jsonrpc": "2.0", "method": "notifications/initialized"}).encode(),
        headers={"Content-Type": "application/json", "mcp-session-id": sid,
                 "Accept": "application/json, text/event-stream"})
    urllib.request.urlopen(note).read()
    return sid


def call(sid, tool, args):
    request = urllib.request.Request(BASE, method="POST", data=json.dumps({
        "jsonrpc": "2.0", "id": 9, "method": "tools/call",
        "params": {"name": tool, "arguments": args},
    }).encode(), headers={"Content-Type": "application/json", "mcp-session-id": sid,
                          "Accept": "application/json, text/event-stream"})
    with urllib.request.urlopen(request) as answer:
        for line in answer.read().decode().splitlines():
            if line.startswith("data:"):
                body = json.loads(line[5:])
                result = body.get("result", body)
                if "content" not in result:
                    return True, json.dumps(result)[:160]
                text = result["content"][0].get("text", "")
                return bool(result.get("isError")), text.replace("\n", " ⏎ ")[:150]
    return True, "no answer"


sid = session()
unwired, failed, skipped, ok = [], [], [], []

for tool in CATALOG["tools"]:
    if tool["name"] in ("join-server", "leave-server", "restart-bot", "switch-server"):
        continue  # driven separately; they move the bot out from under the rest
    properties = tool["inputSchema"].get("properties", {})
    args = {k: SAMPLES[k] for k in tool["inputSchema"].get("required", []) or [] if k in SAMPLES}
    missing = [k for k in tool["inputSchema"].get("required", []) or [] if k not in SAMPLES]
    if missing:
        skipped.append(tool["name"])
        print("SKIP %-22s no sample for %s" % (tool["name"], missing))
        continue
    # A wait that is going to fail should fail quickly; wait-for-server's default is five minutes.
    if "timeoutMs" in properties:
        args["timeoutMs"] = SAMPLES["timeoutMs"]

    error, text = call(sid, tool["name"], args)
    if "not wired up yet" in text:
        unwired.append(tool["name"])
        print("WIRE %-22s %s" % (tool["name"], text))
    elif error:
        failed.append(tool["name"])
        print("ERR  %-22s %s" % (tool["name"], text))
    else:
        ok.append(tool["name"])
        print("ok   %-22s %s" % (tool["name"], text))

unexpected = [name for name in failed if name not in EXPECTED_ERRORS]
print("\nok %d | error %d (%d unexpected) | unwired %d | skipped %d"
      % (len(ok), len(failed), len(unexpected), len(unwired), len(skipped)))

# A tool that is not wired up is the server saying nothing. A skipped tool was never asked, and an
# error outside the named set is the server refusing -- every rpc tool answering "not in a world"
# because the join failed looks exactly like that, and used to pass.
if unwired:
    print("unwired:", unwired)
if skipped:
    print("skipped:", skipped)
if unexpected:
    print("unexpected errors:", unexpected)
if unwired or skipped or unexpected:
    raise SystemExit(1)
