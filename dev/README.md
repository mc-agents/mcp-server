# Driving the server without a bot

Three scripts, no dependencies beyond Python 3. They exist because the alternative to having them
is verifying a change by reading it.

## `bot.py` — a bot that is not a Minecraft client

Speaks the whole of `docs/bot-protocol.md`: dials in, reports every tool the catalogue offers its
kind, answers `connect`/`disconnect`/`call`, folds nothing, and pushes a chat line when it joins.
For a structured tool it answers with the DTO from that tool's golden case in
`src/test/resources/render/`, so a sweep exercises every renderer over the wire and not only in a
unit test.

```
BOT_LINK_PORT=18765 MCP_PORT=13000 ./gradlew bootRun &
python3 dev/bot.py 18765 alice mineflayer
```

Pass `fabric` as the third argument to see the kind checks from the other side: `screenshot` and
`press-dialog-button` start working and nothing else changes.

## `sweep.py` — call all 64 tools once

```
python3 dev/sweep.py
```

Prints one line per tool and a count. A tool that answers "is not wired up yet" is the thing this
is looking for. Five `wait-for-*` tools fail on purpose, because the sweep asks them for a pattern
that never arrives, and the tools that need a `fabric` bot fail against a `mineflayer` one.

## `call.sh` — one tool, by hand

```
./dev/call.sh join-server '{"name":"alice","host":"127.0.0.1","port":25577}'
./dev/call.sh read-chat
```

Holds the MCP session id in `dev/sid`; delete that file to start a new session.

## A real server to point at

`ping-server` and `wait-for-server` talk to a Minecraft server directly, so they need one. Any
server will do, including one already running for something else.
