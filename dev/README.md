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
python3 dev/bot.py 18765 alice fabric
```

The third argument is the kind it reports, `fabric` or `azalea`.

## `fixture.sh` — put something in the world to read

```
./dev/fixture.sh <paper container>          install and load
./dev/fixture.sh <paper container> hud      send an action bar, a title, a sound, a particle
./dev/fixture.sh <paper container> dialog   open the dialog
```

An empty flat world makes every reading tool answer "nothing there", which is a world nothing can
be checked against. `dev/fixture` is a datapack that puts a scoreboard, a boss bar, a two-sided
sign, a hologram, a named cow, a chest with custom names and lore, a furnace and a patch of
diamond blocks at fixed coordinates, each of them drawn the way a server with a resource pack
draws it. Two quests are advancements under `mcagents:quest/`, granted by nothing but a command. The [end-to-end suite](../e2e) is aimed at those coordinates.

The action bar it sends is in `minecraft:illageralt` with the label and the numbers as separate
pieces, which is the case a plain-text reader gets wrong.

## `fixture-plugin` — what the server received from the keys

```
./gradlew :fixture-plugin:jar      # dev/fixture-plugin/build/libs/mcagents-fixture.jar
```

A datapack can put things in the world but cannot see a player's input, and a client shows a jump
or a hotbar slot it never sent as readily as one it did. This Paper plugin counts, per player, in
scoreboard objectives rcon reads back:

| objective | counts |
| --- | --- |
| `fx_jump`, `fx_sneak`, `fx_sprint` | rising edges of the input packet's flags |
| `fx_slot`, `fx_slots` | the hotbar slot last selected, and how many selections |
| `fx_left`, `fx_right` | clicks into the air with the main hand |
| `fx_swing` | every arm swing |
| `fx_catch`, `fx_miss` | reels inside and outside a bite's window |
| `fx_gather_hit`, `fx_gather_miss`, `fx_gather_early` | gathering rounds clicked inside the 2-tick window, outside it, and before the cue |
| `fx_gather_ticks`, `fx_gather_ms` | how long the last gathering click took from the cue |

It also plays two pieces of hyperfarm just far enough to test against. `/fixture talk <player>`
opens a conversation on the action bar: jump turns the page, hotbar slot 0 or 1 answers and tags
the player `fixture_choice_<n>`, and sneaking leaves and tags `fixture_talk_left`. A cast rod gets a
bite sixty ticks later -- `entity.fishing_bobber.splash` played to the angler -- and a reel within
forty ticks of it counts as a catch. `/fixture gather <player>` puts `Gather: JUST!` on the action bar 30 to 70 ticks later, and a left-click
within two ticks of it is a hit.

The end-to-end suite builds it and loads it into its server.

## What replaced `compare.py`

There used to be a script here that asked two kinds of bot the same questions and diffed the
answers. It is gone with the second kind, and what took its place is stronger anyway: the
[end-to-end suite](../e2e) asserts the sentence each tool is supposed to produce, against a real
server and a real bot, per Minecraft version, in CI.

Two implementations agreeing was always the weaker thing to know. They agreed for a while that a
chat line reading "Hello world" was "Hello  | world", and the suite called that a match.

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

## `sweep.py` — call every tool once

```
python3 dev/sweep.py
```

Prints one line per tool and a count. A tool that answers "is not wired up yet" is the thing this
is looking for. Five `wait-for-*` tools fail on purpose, because the sweep asks them for a pattern
that never arrives.

## `call.sh` — one tool, by hand

```
./dev/call.sh join-server '{"name":"alice","host":"127.0.0.1","port":25577}'
./dev/call.sh read-chat
```

Holds the MCP session id in `dev/sid`; delete that file to start a new session.

## `compose.yml` — a proxy with two backends

```
docker compose -f dev/compose.yml up -d
./dev/call.sh join-server '{"name":"alice","host":"127.0.0.1","port":25579}'
./dev/call.sh switch-server '{"bot":"alice","target":"arena"}'
```

`switch-server` is the one tool that cannot be written without a proxy: it is a proxy command and a
re-login on the same connection, and both of its other outcomes -- "already connected" and "that
server does not exist" -- only ever arrive as chat. Guessing at what a proxy says back is how the
other bot ended up sending a dialog packet no server could decode.

Velocity with two Paper backends, `lobby` and `arena`, on different worlds so a bot that thinks it
switched and did not is caught by where it is standing. Forwarding is off and everything is offline
mode; a real deployment uses modern forwarding and a shared secret, and none of that changes what
the tool does. Nothing else in `dev/` needs this running.

## A real server to point at

`ping-server` and `wait-for-server` talk to a Minecraft server directly, so they need one. Any
server will do, including one already running for something else.

## `cluster/` — the development cluster

```
k3d cluster create mc-agents --agents 1     once
make -C dev/cluster up                      operator, this server, Paper with the fixture
make -C dev/cluster names                   the MCP address and the `claude mcp add` line
```

The server answers on `127.0.0.1:13000/mcp` with the token from the `mcp-auth` Secret, and bots are
started by calling `join-server` with host `paper.mc-agents.svc`; the operator makes the pod, of
either kind. `make fixture` copies an edited datapack in and restarts the server, since dialogs are read only at
start, and `make plugin` builds the
fixture plugin, copies it into the server's plugins and restarts it -- Paper loads plugins only at
start. A bot the cluster starts is named with at most 16 letters, digits and `_`, or lowercase letters,
digits and `-`; a name Kubernetes would refuse gets a MinecraftBot of a derived name, with the bot's
own in `spec.botName`. Anyone who connects is made
op, because a scenario names its bots and no ops list could name them in advance.
