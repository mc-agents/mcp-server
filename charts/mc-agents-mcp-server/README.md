# mc-agents-mcp-server

The MCP endpoint in a cluster: one Deployment, one Service with two ports, and a token on each.
Bots are not here -- something has to start them, and that something is the operator.

```bash
helm install mc-agents oci://junhyung.cloud/mc-agents/charts/mc-agents-mcp-server \
  --namespace game --create-namespace \
  --set auth.token="$(openssl rand -hex 32)" \
  --set botLink.token="$(openssl rand -hex 32)"
```

Keep both tokens. The chart will not generate them, for the reason under **Tokens** below, and
there is no way to read `auth.token` back out of a release you installed with `--set`.

That release is named `mc-agents-mc-agents-mcp-server`, because Helm's convention is
`<release>-<chart>` and both halves say it. `fullnameOverride: mc-agents` is the way out, and the
examples below use the stuttered name since that is what an install like the one above produces.

## Which of the three

| | |
| --- | --- |
| **This chart** | A cluster, and one game or one team. You write the `MinecraftBot`s, or the operator does when `join-server` asks. |
| **The [operator](https://github.com/mc-agents/operator)'s `MCPServer`** | A cluster with tenants. A namespace gets a server by creating one CR, and the operator builds everything this chart builds, plus the bot pods. Where you want more than one server, this is the one to use. |
| **[`deploy/compose/`](../../deploy/compose/README.md)** | No cluster. The server and some bots on one machine, scaled with `--scale bot=N`. |

The operator path supersedes this chart rather than needing it: an `MCPServer` CR does not install
this chart, it renders the same objects from Go. This chart stays because a single server does not
need an operator, three CRDs and a cluster-wide RBAC to run.

## What it installs

| | |
| --- | --- |
| Deployment | One replica. `replicaCount: 2` is refused, not ignored -- see **One replica** below |
| Service | `3000` for `/mcp` and `/regions`, `8765` for the bot link. Two ports so a NetworkPolicy can open one and not the other |
| Secrets | One per token, `-auth` and `-link`, each holding it under the key `token`. Neither is created where you named an existing one |
| ServiceAccount, Role, RoleBinding | Only with `bots.provision` on, and the Role lands in `bots.namespace` |
| NetworkPolicy | Off by default |
| ServiceMonitor | Off by default; pod `prometheus.io/scrape` annotations are on |

What a bot's `spec.server` should say, and what an agent connects to, is in the install notes the
chart prints. `make -C dev/cluster names` prints the same thing with the development cluster's own
token already in it.

## Tokens

Two, and they gate different things.

`auth.token` is the bearer token on `/mcp` and `/regions`. `/actuator` is never behind it, because
a kubelet probe cannot carry one.

`botLink.token` is what a bot's `hello` has to present. It is the second gate after the
NetworkPolicy and the one that matters more: a pod that can reach `8765` without it cannot
introduce itself as a bot, and a pod that could would be able to answer an agent's calls with
whatever it liked, under a name a real bot would have used.

**The chart generates neither.** A value that differs on every render leaves a GitOps application
permanently out of sync, so `auth.enabled: true` with no token is a refused render rather than a
convenient default. Use `--set` for a cluster you drive by hand, or `existingSecret` anywhere the
values file is not the secret store.

`existingSecret` also changes what a rotation costs. A Secret the chart owns is part of the
release, so changing it rolls the pod; one of yours is not, so it does not, and the server goes on
holding the old token until you follow the rotation with:

```bash
kubectl -n game rollout restart deployment/mc-agents-mc-agents-mcp-server
```

or run [Reloader](https://github.com/stakater/Reloader) and put `reloader.stakater.com/auto: "true"`
in `podAnnotations`.

`auth.enabled: false` leaves `/mcp` open to anything that can reach the port, which is every pod in
the namespace unless the NetworkPolicy says otherwise. The install notes say so out loud when it is
off.

## Bots

`join-server` can start a bot that is not running: it creates a `MinecraftBot` and the operator
makes the pod. That needs the operator installed and `bots.provision: true`, which is the default
and what the Role above is for. This chart never touches a pod itself.

With `bots.provision: false` the Role is not created either and `join-server` can only use bots
that dial in on their own -- which is the arrangement for bots you run by hand, outside the
cluster, or from `docker compose`.

`bots.namespace` is worth setting. A bot belongs with whatever uses it rather than with the server
that started it, so a game in namespace `game` puts its bots there, and the Role follows them. Two
things then have to line up, and the chart refuses the render if they do not: the link Secret lives
in the release's namespace, a bot in another namespace cannot read it, so copy it across and name
the copy in `bots.linkSecret`.

`bots.profile` picks the `MinecraftBotProfile` (or `ClusterMinecraftBotProfile`) those bots are
built from. Leaving the name empty leaves the choice to the operator, which looks for the
namespace's `default` and then the cluster's.

## One replica

`replicaCount: 2` fails the render with a sentence rather than installing something subtly broken.
A bot exists on the one pod it dialled and any agent may address any bot by name, so a second
replica answers about bots it cannot reach: `list-bots` shows half of them, and a call aimed at
the other half says the bot is not linked while it stands in the world. Sticky sessions are the
wrong half of the problem -- what has to stay put is the bot, not the caller.

`replicaCount: 0` is allowed, and is how you stop a server without deleting its tokens.

Scaling bots is what the operator does. Scaling the server means moving the bot link off the pod,
which nothing here does.

## Feeds, and a busy server

`feeds.muted` is the knob to reach for when a server floods a bot. A muted feed is never sent by
the bot at all, which costs less than dropping it here: `chat`, `actionBar`, `title`, `dialog`,
`effect`, `toast`. `effect` is the one a busy server floods.

`feeds.repeatFlushMs` is how often a bot re-sends an action bar, title or dialog that is still
showing, and a run that goes three of those without a repeat is treated as closed.

## Network

`networkPolicy.enabled` is off because a policy that names the wrong pods is a server nothing can
reach, and the token is the gate either way. Turning it on with both selectors empty allows every
pod in the namespace, which is the same reach as no policy at all and a place to narrow from:
`botSelector` for `8765`, `agentSelector` for `3000`.

`metricsFrom` is for the scraper, which is usually in another namespace. With
`metrics.serviceMonitor.enabled` on and `metrics.serviceMonitor.namespace` set, that namespace is
let in on its own, since that is where the scraper is.

## Values

`values.yaml` carries the full set with a comment on each, and
[`values.schema.json`](values.schema.json) is what `helm install` validates against. The rest of
what the server reads, and what each setting does to it, is in the
[repository README](../../README.md).
