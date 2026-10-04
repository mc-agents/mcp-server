# mc-agents in docker compose

The server and some bots on one machine, for a Minecraft server you already run. No cluster, no
operator, no CRDs.

From the repository root, or `make up` from this directory, which is the same thing:

```bash
make -C deploy/compose up      # two tokens into .env on the first run, then the lot
```

It ends by printing the address and the line that registers it with Claude Code. `make names` prints
those again whenever you want them back; you do not need it now.

The first `up` is the slow one, and almost all of it is the real client. The three images are about
400MB to pull, half of that `bot-fabric`'s, and that bot then fetches 200-600MB of libraries and
assets from Mojang before it links -- call it a minute and a half of linking after the download, and
longer on a slow connection. The server and the headless bot are callable long before any of that
finishes, so `list-bots` showing one bot and not two is what a half-done first run looks like rather
than a failure. Neither cost comes back: the images are cached and the assets live in a volume that
`down` keeps.

Then it is ordinary compose:

```bash
docker compose up -d --scale bot=4     # four headless bots instead of one
docker compose logs -f mcp-server
docker compose down                    # keeps the Minecraft assets; `down -v` does not
```

## What is in it

| | |
| --- | --- |
| `mcp-server` | `/mcp` on `127.0.0.1:3000`, and the bot link on `8765`, which is not published |
| `bot` | A headless azalea bot. The scalable one: `--scale bot=N` |
| `bot-fabric` | One real Minecraft client, for screenshots, dialogs and the rest of the fabric-only tools |

The Minecraft server is not here, and that is the point of this file rather than an omission from
it: the server you want a bot in is the one you already have. `join-server` is what tells a bot
where it is.

Both tokens live in `.env`, written once. `MCP_AUTH_TOKEN` is the bearer token on `/mcp`;
`BOT_LINK_TOKEN` is what a bot's `hello` has to carry. Neither may be blank, and blank is the case
worth spelling out: an empty token does not mean "nothing to check against", it means the gate is
not installed -- the auth filter comes off `/mcp` and the link stops asking. So nothing starts
without both. Compose rejects a variable that is unset or empty on its own, and `make up` rejects
one edited down to a space, which compose would have passed and the server would have read as
nothing at all.

Rotating one is `rm .env`, `make up`, and re-registering every agent. `MCP_HOST_PORT` can go in the
same file, to publish `/mcp` somewhere other than 3000.

## Pointing a bot at your Minecraft server

The host and port in `join-server` are resolved inside the bot's container, not on your machine.

| the server is | what `join-server` is given |
| --- | --- |
| on this machine, port 25565 | `host.docker.internal`, `25565` |
| on this machine, a port you forwarded from somewhere else | `host.docker.internal`, that local port |
| another machine on the LAN, or a public address | the address itself |

`host.docker.internal` is the host as seen from a container. Docker Desktop resolves it on its own;
on Linux it works because both bot services carry
`extra_hosts: ["host.docker.internal:host-gateway"]`. A tunnel is the way in to anything else: an
SSH or `kubectl port-forward` on this machine listening on `0.0.0.0` or on the Docker bridge, and
the bot dials `host.docker.internal` at that port. One that listens on `127.0.0.1` only is not
reachable from a container, and neither is a Minecraft server bound to loopback -- both answer a
bot's dial with connection refused, which `join-server` reports as a server that is not there.

With the default offline authentication the target server has to be in offline-mode; it is what
makes `--scale` mean anything, since every bot names itself. For a server whose online-mode you
will not turn off, the commented block at the end of `compose.yml` runs bots on real accounts: one
account each, written out as services by hand, and `docker compose run --rm bot-login` does the
login once at your terminal and leaves the credentials in a volume.

## Scaling bots

`--scale bot=N` is the headless kind, and it is cheap: no client jar, no assets, no X server, and a
link in about a second. The names are not yours to choose. Replicas share every environment
variable, so `BOT_NAME` is deliberately not set on that service and each bot names itself after its
container id; an agent finds them with `list-bots`. Past `MCP_MAX_BOTS` (16) the extra bots are
refused and told the number.

**fabric is one service, and scaling it costs 200-600MB a bot.** The client jar, its libraries and
its assets are downloaded from Mojang when `/mc` is empty, which is why `bot-fabric` has a named
volume: the download happens once, not on every recreate. More fabric bots means more services
written out by hand, each with `BOT_NAME` and its own volume -- or the same volume, if the first
bot has already filled it and they only read. Two bots filling one empty volume at the same time
are two clients writing the same files. The operator gives a fabric bot 4Gi, which is what a client
with a resource pack turned out to need; nothing here sets a limit, so on Docker Desktop how many of
them fit is a question about the VM's memory before it is a question about this file.

## What this does not give you

The cluster deployment is the operator, and these are the things it does that nothing here does.

- **No autoscaling.** A `MinecraftBotPool` grows and shrinks with demand. `--scale` is a number you
  type.
- **No restart on anything but a crash.** `restart: unless-stopped` brings back a process that
  exited; nothing probes `/actuator/health/liveness`, so a server that is up and wedged stays up.
  There is no readiness gate either: `up` returning means the containers started, not that an agent
  can call. A bot that dials too early redials on its own.
- **No profile pinning.** In a cluster a `MinecraftBotProfile` says which image tags bots run with,
  per namespace, and the operator's chart carries the verified set. Here the tags are literals in
  `compose.yml` and moving them is editing it. (Not to be confused with compose's own `profiles`,
  which is only what keeps `bot-login` out of `up`.)
- **No second server.** The MCP server cannot be scaled: bots are linked to the process they dialled
  and a second one would answer about bots it cannot reach. The chart `fail`s on `replicaCount > 1`
  and the operator hardcodes 1; here the fixed published port is the whole guard, so
  `--scale mcp-server=2` fails on the port rather than being refused on principle -- whichever
  replica bound 3000 first keeps running, and the other sits in `created`.
- **No NetworkPolicy, and no second namespace.** The compose network is the only thing in front of
  the bot link, and everything in this project is on it. A container you add to this project can
  dial `8765`; the link token is what stops it registering as a bot. On Linux the reach is wider
  than that sentence sounds: an unpublished port is still open on the container's bridge address,
  so anything on the host, and anything that can route to the docker bridge, can reach it too.
  Docker Desktop keeps it inside its VM. Either way the token is the gate, not the network.
- **No secret store.** `.env` is a file in this directory, readable by your user, and it is handed
  to containers as environment variables.
- **No rolling update.** Changing an image tag and running `up` recreates the containers, bots
  included.

## The pins

The three image tags in `compose.yml` belong together, and they are newer than any set the cluster
side names. What makes this file work arrived after operator 0.24.0's defaults: a bot that takes its
name from its own container, a bot that can hold a Microsoft account, a server that says why an
online-mode login was refused. `operator/docs/compatibility.md` tabulates sets per operator release
and so has no row for this one, because a compose deployment installs no operator. What stands
behind these three is each repository's own release, and the handshake is the slack: a bot older
than the server reports fewer tools rather than failing. Move one and move the others.

`:latest` is not an option here: it moves with every push to `main`, so an `up` weeks later would be
a different deployment, and a server whose catalogue does not match the one its bots were built from
does not fail loudly -- it disables the mismatched tools at the handshake and leaves an agent
wondering where they went.
