#!/bin/bash
# Drive the MCP endpoint over Streamable HTTP: ./call.sh <tool> <json args>
# Exits 1 on a tool error, so a script that joins and then sweeps stops at the join.
set -euo pipefail
BASE=${MCP_BASE:-http://127.0.0.1:13000/mcp}
DIR=$(dirname "$0")
ARGS=${2:-"{}"}

if [ ! -f "$DIR/sid" ]; then
  INIT=$(curl -s -i -X POST "$BASE" -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' \
    -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"probe","version":"0"}}}')
  printf '%s' "$INIT" | grep -i '^mcp-session-id:' | tr -d '\r' | awk '{print $2}' > "$DIR/sid"
  curl -s -X POST "$BASE" -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' -H "mcp-session-id: $(cat "$DIR/sid")" \
    -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' > /dev/null
fi

curl -s -X POST "$BASE" -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' -H "mcp-session-id: $(cat "$DIR/sid")" \
  -d "{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":${ARGS}}}" \
  | sed -n 's/^data://p' | python3 -c "
import sys, json
d = json.loads(sys.stdin.read())
r = d.get('result', d)
if 'content' not in r:
    print('RPC', json.dumps(r)[:300]); raise SystemExit(1)
print(('ERR  ' if r.get('isError') else 'OK   ') + r['content'][0].get('text', '')[:400])
for c in r['content'][1:]:
    print('     +', c['type'], len(c.get('data', '')), 'base64 chars')
if r.get('isError'):
    raise SystemExit(1)
"
