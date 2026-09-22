# mcp-server

The MCP endpoint for driving Minecraft bots. Speaks Streamable HTTP to agents on one side and the
[bot protocol](docs/bot-protocol.md) to bots on the other.

It does not know how to play Minecraft. A bot does that, and there are two kinds:
[`bot-fabric`](https://github.com/mc-agents/bot-fabric), a real Minecraft client that renders, so
it can take a screenshot and press a dialog button, and follows the game rather than
reimplementing it; and [`bot-azalea`](https://github.com/mc-agents/bot-azalea), a headless client
in a few megabytes that joins in under a second, for many bots at once or a fast loop. A bot
announces its kind when it links, `list-bots` shows it, and a tool no bot of that kind can run is
refused by name before anything is sent. The [tool reference](docs/tools.md) says which tools the
headless kind does not run; the arrangement is documented in [architecture](docs/architecture.md).

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
| [`docs/tools.md`](docs/tools.md) | The catalogue as a page: every tool by group, its arguments, what it answers with. Rendered by `./gradlew renderToolReference`, and the build fails when it is stale |

The catalogue is the single source. `tools/list` is built from it, argument validation runs
against it, a bot's `argsHash` is checked against it at handshake, and the tool reference is
rendered from it.

## Running it

```
MCP_AUTH_TOKEN=... BOT_LINK_PORT=8765 MCP_PORT=3000 ./gradlew bootRun
```

Leaving `MCP_AUTH_TOKEN` unset leaves `/mcp` open and says so in the log. That is the right
default for a laptop and the wrong one anywhere a pod can reach it, so the chart sets one.
`/actuator` is never behind the token: a probe cannot carry one.

Where it listens follows from the token. `MCP_BIND_HOST` overrides; without it the server binds
`127.0.0.1` when `MCP_AUTH_TOKEN` is blank and `0.0.0.0` otherwise, because a server with no
token is a laptop's and one with a token is a pod's. The chart and the operator set `0.0.0.0`
explicitly all the same. On `/mcp` a request with no `Origin` header passes, which is what Claude
Code and most clients outside a browser send; an `Origin` of `localhost` or `127.0.0.1` on any
port passes; `MCP_ALLOWED_ORIGINS` (a comma-separated list) extends that. The `Host` header is
not checked.

Everything else the server reads from its environment, with what it means and what it does when
unset:

| variable | meaning | default |
| --- | --- | --- |
| `MCP_AUTH_TOKEN` | The bearer token `/mcp` and `/regions` require. Blank leaves them open | |
| `MCP_PORT` | The HTTP port: `/mcp`, `/regions` and `/actuator` | `3000` |
| `MCP_BIND_HOST` | The address that port binds | `127.0.0.1` without a token, `0.0.0.0` with one |
| `MCP_ALLOWED_ORIGINS` | Browser origins `/mcp` accepts beside localhost, comma separated | none |
| `BOT_LINK_PORT` | Where bots dial in | `8765` |
| `BOT_LINK_REPEAT_FLUSH_MS` | How often a bot re-sends an action bar, title or dialog that is still showing; a run nothing has repeated for three of these is closed | `1000` |
| `BOT_LINK_MUTED_FEEDS` | Feeds bots are told not to push, comma separated: `chat`, `actionBar`, `title`, `dialog`, `effect`, `toast`. `effect` is the one a busy server floods | none |
| `BOT_LINK_TOKEN` | What a bot's `hello` has to carry; one without an equal `linkToken` is refused. Blank takes any bot that dials in | blank |
| `MCP_MAX_BOTS` | How many bots may be linked at once. A bot turned away is told this number | `16` |
| `MCP_BOTS_PROVISION` | Whether `join-server` creates a `MinecraftBot` for a name nothing runs under: `auto` looks for a cluster only from inside one, `always` uses whatever kubeconfig is around, `never` does not look | `auto` |
| `MCP_BOTS_NAMESPACE` | Where those bots are created | the pod's own namespace |
| `MCP_BOTS_MCP_HOST` | What a created bot is told to dial | the chart's Service in that namespace |
| `MCP_BOTS_PROFILE_KIND`, `MCP_BOTS_PROFILE_NAME` | The `MinecraftBotProfile` (or `ClusterMinecraftBotProfile`, by kind) every bot started here is built from | the operator's: the namespace's `default`, then the cluster's |
| `MCP_BOTS_LINK_SECRET` | The Secret (key `token`) in that namespace a bot started here reads its `BOT_LINK_TOKEN` from, written as `spec.linkTokenSecretRef`. Blank declares bots without one | blank |
| `MCP_LOG_LEVEL` | Log level for `kr.junhyung.mcagents` | `INFO` |

The image comes from Paketo buildpacks, not a Dockerfile:

```
./gradlew bootBuildImage
```

The JVM's heap is then sized from the container's real limit rather than a percentage someone
guessed, and an SBOM comes with it. One invocation builds one architecture; CI runs it on a
native runner per architecture and joins the two into a manifest list.

Agents connect to `/mcp`; bots dial in on `:8765`. Every tool in the catalogue is in `tools/list` before any
bot has linked, because an MCP client reads that list once when its session opens.

`/regions` is the one thing beside `/mcp` behind the token. A box `read-region` reads is kept in
memory under an id, and `GET /regions/<id>.schem` downloads it as a Sponge schematic while
`POST /regions` (the file as the body, `?name=` optional) keeps one and answers with its id, which
`write-region` then puts down. The store holds sixteen million blocks and lets the oldest go; a
restart empties it. A file is the shape a region takes between servers, or between a person's
WorldEdit and an agent, and a tool call is the wrong size for it either way.

[`dev/`](dev/README.md) has a fake bot that speaks the whole protocol, a sweep that calls every
tool, and a one-liner for calling one by hand. None of them needs a Minecraft client.

### Connecting an agent

```bash
claude mcp add --transport http mc-agents http://127.0.0.1:3000/mcp -H "Authorization: Bearer $MCP_AUTH_TOKEN"
```

The same thing in a project's `.mcp.json`:

```json
{
  "mcpServers": {
    "mc-agents": {
      "type": "http",
      "url": "http://127.0.0.1:3000/mcp",
      "headers": { "Authorization": "Bearer ${MCP_AUTH_TOKEN}" }
    }
  }
}
```

A wrong or missing token is answered `401` with `WWW-Authenticate: Bearer`, so a client that
cannot connect has that to look for. Against the development cluster, `make -C dev/cluster names`
prints this line with the cluster's own token in it.

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

CI shares a fabric bot's cases out between four runners, each with a server and a bot of its own,
and a failure names the shard it happened in. The same share runs locally with `-Pe2e.shard=2/4`.

The Paper server, and Testcontainers' own ryuk and sshd, are Docker Hub images. Locally they are
pulled from there with no login. In CI they come through the registry's private proxy cache, which
is what `-Pe2e.hub.prefix=junhyung.cloud/docker-hub/` (or `E2E_HUB_PREFIX`) puts in front of the
Paper image's name and `TESTCONTAINERS_HUB_IMAGE_NAME_PREFIX` puts in front of the other two;
both are empty by default. The bot images are on the public `mc-agents` project either way.

### Releases

A push to `main` publishes the image and the chart, once every job before it is green: the unit
tests and the tool sweep, the two architectures' images, and every end-to-end shard. A red shard
blocks the tags, the chart and the release rather than shipping beside them; there is no branch
ruleset, and this gate is what stands between a red suite and a release. A push that changes
nothing the image or the chart is built from publishes nothing.

The stamped tag, `<version>-<utc stamp>.g<sha>`, is immutable; `:<version>` and `:latest` move to
the newest build of that version, so a deployment that must not change pins the stamped tag or the
digest. The same push tags the commit `v<version>` and makes a GitHub Release whose notes are the
commits since the previous tag, followed by the catalogue version and the bot tags the suite ran
against (the ones [`dev/cluster/mcpserver.yaml`](dev/cluster/mcpserver.yaml) pins);
[`hack/release-notes.sh`](hack/release-notes.sh) writes them, and runs locally.

Which release of each repository goes with which is the operator's to say, since the operator is
what installs the set: the table is
[operator/docs/compatibility.md](https://github.com/mc-agents/operator/blob/main/docs/compatibility.md),
one row per operator release, with the order the pieces are released in.

## Status

The server answers the whole catalogue. `join-server` sends a bot that has linked into a world, and
in a cluster it starts one first when none is running under that name, by creating a
`MinecraftBot` for the [operator](https://github.com/mc-agents/operator) to run.

In a cluster, run this server through the operator: an `MCPServer` in the namespace that uses the
bots gets a server, its token and the RBAC to create bots there, and nowhere else. The chart in
[`charts/`](charts/mc-agents-mcp-server) is for running the server without the operator managing
it; creating bots still needs the operator's CRDs.

### Known limits

**The bot port has two gates, and both are off by default.** The first is a NetworkPolicy:
`networkPolicy.enabled` in the chart, or one the operator's user adds. The chart's opens `:8765`
to the bot pods alone, `/mcp` to the agent pods, and its third rule opens the MCP port to the
namespaces in `networkPolicy.metricsFrom` for scraping, and to nothing else; kubelet probes need
no rule. The second is the link token, `botLink.token` or `botLink.existingSecret` in the chart
(`BOT_LINK_TOKEN` on the server): a bot's `hello` has to carry it or the bot is refused, so a pod
that gets past the policy still cannot introduce itself as a bot. Bots the server starts are
pointed at the same Secret; one declared by hand needs `spec.linkTokenSecretRef`. The operator
sets both up for an `MCPServer`. With neither, any pod that can reach the port is a bot.

**The token carries no identity.** Every agent presents the same bearer token, so the server cannot
say who asked for a bot; `join-server`'s `owner` is the only audit trail, and it is whatever the
caller wrote.

**Cancelling a call does not stop the bot.** The MCP SDK gives a tool handler no cancellation hook,
though the bot protocol has `cancel`. An abandoned `move-to-position` keeps walking to its deadline,
and the bot refuses another exclusive tool until then (`RemoteTools.claim`). When the SDK exposes
one: complete the pending `BotLink` call with `Messages.Cancel` and release the claim.

**One replica.** Bots are a shared resource, and sharing them across replicas needs leases and a
roster. The requirement that replicas scale is about bot pods, which do scale, so that complexity
is not paid for here.

Licensed under the Apache License 2.0; see [LICENSE](LICENSE).
