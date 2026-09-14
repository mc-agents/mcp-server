# mcp-server

The MCP endpoint for driving Minecraft bots. Speaks Streamable HTTP to agents on one side and the
[bot protocol](docs/bot-protocol.md) to bots on the other.

It does not know how to play Minecraft. A bot does that, and there is one:
[`bot-fabric`](https://github.com/mc-agents/bot-fabric), a real Minecraft client. It renders, so
it can take a screenshot and press a dialog button, and it follows the game rather than
reimplementing it.

There was a second kind for a while -- a mineflayer bot, cheap and headless -- and the machinery
for telling kinds apart is still here: a bot announces its kind when it links, `list-bots` shows
it, and a tool no bot of that kind can run is refused by name before anything is sent. What the
second kind cost was every tool implemented twice, and what it could not do was press a dialog
button, take a screenshot, walk round a wall, or join a server newer than its protocol library.
The repository is archived and the arrangement is documented in
[architecture](docs/architecture.md).

> **Local development only.** Bots authenticate offline, so the target server has to run
> offline-mode.

## What this is for

Driving a Minecraft client from an agent, for two jobs. Running the QA a person used to do by
opening the game and looking, and closing the loop while developing — build, deploy, have the
agent press the thing, find the edge case.

That purpose settles the design arguments. When a choice comes up, it is decided by whether the
answer tells you what went wrong when something fails, and whether it keeps the build-and-check
loop short. Not by how many tools it adds.

## Layout

| | |
| --- | --- |
| [`docs/architecture.md`](docs/architecture.md) | Why this is four repositories and what each one owes the others |
| [`docs/bot-protocol.md`](docs/bot-protocol.md) | The wire. Both bots implement it |
| [`catalog/catalog.json`](catalog/catalog.json) | Every tool: its MCP schema, the normalised schema bots receive, and which kinds of bot can run it |

The catalogue is the single source. `tools/list` is built from it, argument validation runs
against it, and a bot's `argsHash` is checked against it at handshake.

## Running it

```
MCP_AUTH_TOKEN=... BOT_LINK_PORT=8765 MCP_PORT=3000 ./gradlew bootRun
```

Leaving `MCP_AUTH_TOKEN` unset leaves `/mcp` open and says so in the log. That is the right
default for a laptop and the wrong one anywhere a pod can reach it, so the chart sets one.
`/actuator` is never behind the token: a probe cannot carry one.

The image comes from Paketo buildpacks, not a Dockerfile:

```
./gradlew bootBuildImage
```

The JVM's heap is then sized from the container's real limit rather than a percentage someone
guessed, and an SBOM comes with it. One invocation builds one architecture; CI runs it on a
native runner per architecture and joins the two into a manifest list.

Agents connect to `/mcp`; bots dial in on `:8765`. Every tool in the catalogue is in `tools/list` before any
bot has linked, because an MCP client reads that list once when its session opens.

[`dev/`](dev/README.md) has a fake bot that speaks the whole protocol, a sweep that calls every
tool, and a one-liner for calling one by hand. None of them needs a Minecraft client.

### End to end

```bash
./gradlew :e2e:test
```

[`e2e/`](e2e) is a project of its own: it starts the jar a release ships, a Paper server with the
fixture datapack in it and a real bot in a container, and then asks the MCP endpoint for things and
asserts the sentence that comes back. It compiles against nothing in this project, so what it holds
is the contract rather than the code behind it.

Every case in it is a bug that was once shipped. What found those was a set of scripts somebody had
to run and read; none of them could fail a build, so a renderer that started saying something else
went unnoticed until the next time anyone looked. The assertions are against the expected sentence
rather than against a second bot, because two implementations agreeing is a weaker thing to know --
they agreed for a while that a chat line reading "Hello world" was "Hello  | world".

It needs Docker and a bot image, so it is not part of `build`. Which bot to drive is the build's to
say; the Minecraft version is the one the bots support, 26.1.2:

```bash
./gradlew :e2e:test -Pe2e.bot.image=<image>
```

## Status

The server answers the whole catalogue. `join-server` sends a bot that has linked into a world;
creating the bot process is the [operator](https://github.com/mc-agents/operator)'s job and is not
wired up yet, so for now a bot is started by hand and `join-server` finds it by name.

### Known limits

**The bot port has no authentication.** A NetworkPolicy opens `:8765` to this server alone. Giving
every bot a rotating token would put secret rotation in the operator, for a port that does not
leave the cluster.

**One replica.** Bots are a shared resource, and sharing them across replicas needs leases and a
roster. The requirement that replicas scale is about bot pods, which do scale, so that complexity
is not paid for here.
