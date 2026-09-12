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

## `fixture.sh` — put something in the world to read

```
./dev/fixture.sh <paper container>          install and load
./dev/fixture.sh <paper container> hud      send an action bar, a title, a sound, a particle
./dev/fixture.sh <paper container> dialog   open the dialog
```

An empty flat world makes every reading tool answer "nothing there", and two bots agree perfectly
about a world neither can see. `dev/fixture` is a datapack that puts a scoreboard, a boss bar, a
two-sided sign, a hologram, a named cow, a chest with custom names and lore, a furnace and a patch
of diamond blocks at fixed coordinates. `compare.py` is aimed at those coordinates.

The action bar it sends is in `minecraft:illageralt` with the label and the numbers as separate
pieces, which is the case a plain-text reader gets wrong.

## `compare.py` — ask both kinds of bot the same questions

```
MCP_AUTH_TOKEN=... python3 dev/compare.py alice bravo
```

Both must already be in the same world. A distance and a world tick are measurements, so they come
out of both answers before the comparison; three tools whose whole answer is about the bot that
answered are excused by name. Anything else is a finding, and so is a rendered `null`: that means a
bot did not send a field under the name the catalogue uses.

Excusing a sentence rather than normalising a measurement is how the suite goes blind. A blanket
"blocks away" allowance covered every line that mentioned a distance, and hid the two kinds
disagreeing about what `find-entity` calls an entity's type for as long as it existed.

Run it against two bots of the same kind first. That should produce no findings at all, and it is
what says the suite itself is right before it is pointed at two kinds.

Run `fixture.sh` again right before comparing. The fixture fixes both inventories and sweeps the
floor, and without that a run that tested `drop-held-item` or `fish` leaves the next one reporting
what the last one left lying about.

## `conform.py` — hold a bot to its half of the protocol

```
python3 dev/conform.py 18777
MCP_SERVER_HOST=127.0.0.1 MCP_SERVER_PORT=18777 BOT_NAME=conform <start the bot>
```

Plays the server badly on purpose and checks the bot keeps its end: hello first and complete, every
capability carrying the hash the catalogue gives, a pong that echoes the nonce, one result per id
whatever happens -- cancelled, cancelled late, two in flight -- a refusal for a tool it never
offered, blobs before the result that names them, and a link that survives a frame the bot cannot
read.

The server's half is covered by the unit tests in `src/test`; this is the other side, and a third
kind of bot written against `docs/bot-protocol.md` is what it exists for. Both of the bots that do
exist have to pass it too, because a contract nothing checks is a description of whatever one
implementation happened to do.

It never sends `connect`, so the bot answers every tool with "not in a world". That is a result,
and a result is what is being checked.

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
