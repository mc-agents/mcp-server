# mcp-server

The MCP endpoint for driving Minecraft bots. Speaks Streamable HTTP to agents on one side and the
[bot protocol](docs/bot-protocol.md) to bots on the other.

It does not know how to play Minecraft. Bots do that, and there are two of them:
[`bot-mineflayer`](https://github.com/mc-agents/bot-mineflayer) is cheap, and
[`bot-fabric`](https://github.com/mc-agents/bot-fabric) is a real client — it renders, so it can
take a screenshot and press a dialog button. `join-server` picks the cheap one unless the work
needs the other.

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

## Status

Bootstrapping. The contract is settled; the server is not written yet.
