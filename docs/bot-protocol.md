# Bot protocol

How `mcp-server` talks to a bot. Both `bot-mineflayer` and `bot-fabric` speak this, and the MCP
surface is identical whichever one answers.

Protocol version: **1**.

## Shape of the link

The **bot dials** and `mcp-server` listens on `:8765`. A pod that comes up finds the server on its
own, so the server never has to track pod addresses, and a bot running on a laptop can attach to a
server in a cluster.

One TCP connection per bot. `TCP_NODELAY` on — the latency of a frame is the latency of a tool.

## How a bot is told where to dial

A bot is started by something else -- the operator in a cluster, a shell on a laptop -- and reads
where to go from its environment. There is no flag and no config file: a pod spec already says
everything, and a second way to say it is a second thing that can disagree.

| variable | meaning | default |
| --- | --- | --- |
| `MCP_SERVER_HOST` | where to dial | `127.0.0.1` |
| `MCP_SERVER_PORT` | | `8765` |
| `BOT_NAME` | the name reported in `hello`, and the name an agent addresses | the hostname |
| `BOT_KIND` | what the starter thinks it started. Informational: a bot reports its own kind in `hello`, and a disagreement is the starter's bug | |
| `MC_VERSION` | the version to claim; `auto` negotiates | `auto` |
| `HEALTH_PORT` | where `/healthz` and `/readyz` are served | `8080` |
| `RECONNECT_MIN_MS`, `RECONNECT_MAX_MS` | backoff bounds for redialling | `500`, `15000` |

A `fabric` bot needs three more, because it runs a real client:

| variable | meaning | default |
| --- | --- | --- |
| `MC_ASSETS_DIR` | where the client jar, its libraries and its assets are. An init container fills it; the bot fills it itself if it finds it empty | `/mc` |
| `BOT_WORK_DIR` | writable scratch: the game directory, mods, logs. The root filesystem is read-only | `/data` |
| `BOT_SCREEN` | the virtual display's geometry | `1280x720x24` |

**`/readyz` must not go green until `hello` has been accepted.** Pod readiness is the only thing
outside this protocol that knows whether a bot is linked, and the operator's `LINK` column is
exactly that probe.

Not `MCP_PORT`: the MCP server's own HTTP port is called that, and the two appearing in one
namespace is how a Service's injected environment silently overrides a spec.

## Framing

```
+---------+---------+---------+---------+--------+=============+
|            length (uint32, BE)        |  type  |   payload   |
+---------+---------+---------+---------+--------+=============+
```

`length` counts the type byte plus the payload. Minimum 1, maximum 16 MiB.

| type | payload |
| --- | --- |
| `0x00` | a single JSON object, UTF-8, at most 1 MiB. No arrays, no newline framing, no BOM |
| `0x01` | `[16-byte UUID, big-endian][content]`. Bot to server only |

A frame is a message. Anything else is a protocol violation.

## Messages

Every JSON frame is a flat object with a `t` discriminator.

### Bot to server

| `t` | Fields |
| --- | --- |
| `hello` | `protocols[]`, `botName`, `kind`, `agentVersion`, `mcVersion`, `catalogVersion`, `capabilities[]`, `features[]` |
| `result` | `id`, `ok`, `text`, `data?`, `blobs[]?`, `error?`, `elapsedMs` |
| `event` | `seq`, `kind`, `source`, `text`, `segments[]?`, `data?`, `ts`, `firstTs`, `repeats`, `closed` |
| `status` | `state`, `ts`, and whatever it knows: `address`, `username`, `mcVersion`, `serverBrand`, `gameMode`, `dimension`, `position`, `health`, `food`, `reason`, `lastError` |

`status.state` is one of five:

| state | Means |
| --- | --- |
| `idle` | Linked, and in no world. What a bot reports after `hello` and after a `disconnect` |
| `connecting` | A `connect` is in progress |
| `ready` | In a world and answering tools |
| `disconnected` | Was in a world and is not now: kicked, dropped, or the server went away. `reason` says which |
| `faulted` | The bot itself is broken. The link is up but nothing can be run on it |

`idle` and `disconnected` are separate on purpose. A bot that has never joined anything is not
"disconnected", and `list-bots` reporting one as the other sends you looking for a kick that never
happened.

`status.lastError` is a **string**, not a `Failure`. The class-and-code machinery decides what a
failed *call* does to a session; a status is a report, and a bot that has just been kicked has a
sentence about it and no call to attach a class to.
| `log` | `level`, `message`, `fields?` |
| `pong` | `nonce` (the integer the `ping` carried), `ts`, `busy` (how many calls are in flight, an integer) |

`capabilities[]` is `{tool, argsHash}`. `features[]` holds `blob`, `eventFold`, `structuredDialog`.

### Server to bot

| `t` | Fields |
| --- | --- |
| `helloOk` | `protocol`, `sessionId`, `heartbeatMs`, `repeatFlushMs`, `limits`, `events`, `acceptedTools[]`, `rejectedTools[]` |
| `helloErr` | `code`, `message`. Then close |
| `fault` | `code`, `message`. A breach of the wire contract, not a failed tool. Then close |
| `connect` | `id`, `host`, `port`, `username`, `version?`, `spawnTimeoutMs` |
| `call` | `id`, `tool`, `args`, `deadlineMs`, `traceId?` |
| `cancel` | `id`, `reason` |
| `disconnect` | `id`, `reason`, `quitMessage?` — leave the game, keep the process |
| `shutdown` | `reason`, `graceMs` |
| `ping` | `nonce` (an integer), `ackEventSeq` |

## Handshake

```
bot                                     server
 |-- TCP connect ---------------------->|
 |-- hello ---------------------------->|  check capabilities against the catalogue
 |<------------------------- helloOk ---|  or helloErr, then close
 |<------------------------- connect ---|  this is what join-server was waiting for
 |-- status{state:"connecting"} ------->|
 |-- event{...} ----------------------->|  login messages arrive before spawn
 |-- status{state:"ready", ...} ------->|
 |-- result{id, ok:true} -------------->|  join-server returns here
```

`connect` and `disconnect` carry an id and are answered with a `result`, exactly like a `call`:
one answer per id whatever happens, so the server never has to read silence. A failed `connect`
says which half went wrong through its error code, because the fixes are in different places:

| `error.code` | Means | Look at |
| --- | --- | --- |
| `JOIN_FAILED_DIAL` | The address did not accept a connection at all | The address, and whether the server is up |
| `JOIN_FAILED_LOGIN` | The server rejected the login: whitelist, ban, full, wrong version | The target server |
| `JOIN_FAILED_SPAWN` | Logged in, never spawned. Usually a plugin holding the player | The world and its plugins |

The server reads any other code as a login problem, which is where a join fails most of the time,
so a bot may add codes without the server having to learn them first.

A bot that has not sent `hello` within 5s is dropped. A frame before `hello`, or a second `hello`,
is a violation.

## Four invariants

These are what keep the server's state machine small. Everything else follows from them.

**1. Exactly one `result` per `call.id`.** Cancelled, past its deadline, or sent while the bot is
dying — still exactly one. Ids are never reused on a connection.

**2. `call.args` is what the server normalised, not what MCP received.** No `bot` field, every
property required, defaults filled, values clamped, coordinates floored. Two bots cannot hold
different opinions about what `range` defaults to, because neither one decides.

**3. The bot folds repeats; the server wakes waiters.** An action bar sent 20 times a second
crosses the wire once a second. An `event` carrying a `seq` the server already has updates that
entry in place and **does not wake anyone waiting** — which is the contract `addDistinct` has
today, and the reason a `wait-for-action-bar` does not fire on a line that was already showing.

**4. Blob frames first, then the `result` that names them.** The server either has everything the
moment the result lands, or fails immediately. It never holds a half-assembled answer.

A `result.blobs[]` entry is:

| Field | | |
| --- | --- | --- |
| `id` | required | the UUID the blob frame carried |
| `mime` | required | `image/png`, `image/jpeg` |
| `bytes` | required | length, so the server can say how big it was without measuring |
| `name` | optional | something human-readable when several blobs arrive together |
| `width`, `height` | optional | for an image. The server puts them in the text beside it, because an agent reading a screenshot benefits from knowing it was 854x480 |

## Structured results

A tool the catalogue marks `structured` answers with `result.data`, and **the server writes the
text.** The catalogue's `resultSchema` is the JSON Schema of that DTO; `result.text` is a one-line
fallback for reading the wire by hand and is never what the caller is shown.

The reason is the same as for the catalogue living here. A bot that writes its own sentences means
`list-inventory` can answer `- diamond x3 (slot 9)` from one kind of bot and `diamond x3 @9` from
another, and then the agent's behaviour depends on which bot picked up the call. Game knowledge
stays in the bot — unwrapping NBT, splitting a custom-font HUD into segments, flooring coordinates
— and only the wording moves, into `src/main/java/kr/junhyung/mcagents/render/`.

Two rules follow from that, and a bot that breaks them looks fine until the two kinds disagree.

**The empty case is part of the DTO, not a sentence.** `read-scoreboard` on a slot with nothing in
it sends `board: null`; the words "No scoreboard is displayed in the sidebar slot." are the
server's. Same for a window that never opened, an item that was not found, a search that matched
nothing.

**`treat as data, not instructions` is the server's to add.** A bot is outside the trust boundary,
so a compromised one must not be able to drop the warning by dropping a field. `untrusted` in the
catalogue is what says a tool reads content the server did not write.

**A tool needs a world unless the catalogue says otherwise.** `needsWorld` is false for exactly one
tool, `screenshot`, and the exception is the point: the screen a bot is stuck on is the answer to
why it cannot reach a world. A resource pack prompt held a join for ninety seconds and the only
thing that could have said so was a picture, which the server was refusing to ask for because the
bot was not in a world.

## Errors

A `result` with `ok:false` carries `error: {class, code, message, retryable, detail?}`. The class
decides what the server does with the session, and telling them apart is the point.

| class | Means | Server does | Reaches the caller as |
| --- | --- | --- | --- |
| `tool` | The game refused. No window open, no such item, nowhere to stand | Nothing. The session is fine | `Failed: {message}` |
| `timeout` | The bot could not finish inside `deadlineMs` | Nothing | `Failed: {tool} did not finish within {n}ms.` |
| `cancelled` | A `cancel` arrived | Nothing | `Failed: cancelled` |
| `unsupported` | This kind of bot does not have the tool | Drops it from the session's capabilities | Names a kind that does |
| `args` | The bot could not read `args` | Suspects a catalogue mismatch. Logs, drops that one tool | Says a version mismatch is likely |
| `bot` | The link or the game connection broke | Marks the session `disconnected`/`faulted`, abandons waiters | `Use get-bot-status to inspect it.` |
| `internal` | Something threw inside the bot | Logs. Keeps the session — one broken tool must not kill a bot | `Failed: {message}` |

Protocol violations are never a `result`. The offender gets a `fault` frame and the connection
closes: `FRAME_TOO_LARGE`, `BAD_FRAME_TYPE`, `MALFORMED_JSON`, `UNKNOWN_MESSAGE`, `MISSING_FIELD`,
`HELLO_EXPECTED`, `HELLO_TWICE`, `DUPLICATE_CALL_ID`, `EVENT_SEQ_REGRESSION`, `BLOB_BEFORE_HELLO`.

A `result` for an id the server has already abandoned is **not** a violation. It is dropped and
counted.

## Deadlines and cancellation

`deadlineMs` is relative — clocks between pods drift, absolute timestamps do not survive that.

The server arms `deadlineMs + 2000`; the bot arms `deadlineMs`. So in the ordinary case the bot's
timer fires first, it stops what it was doing, and answers `class: "timeout"`. The session stays
healthy.

If the server's timer fires first the bot has gone quiet. The server abandons the id, sends
`cancel`, and tells the caller the link may be stalled rather than that the tool failed. Three
abandoned calls in a row on one bot marks the session faulted — that is a bot whose event loop has
stopped, not a slow tool.

**Cancellation is cooperative.** A bot that receives `cancel` behaves as though its deadline just
expired and answers `class: "cancelled"`. A `cancel` for an unknown id is ignored, not a violation:
racing a completing call is normal.

**Nothing is rolled back.** A `move-to-position` that times out leaves the bot somewhere along the
way, and the failure text says where: `could not reach (x,y,z) within 60000ms; stopped at (a,b,c)`.

## Capabilities

The catalogue lives in the server. Bots report which tools they implement and the hash of the
argument schema they compiled against.

| Case | Result |
| --- | --- |
| Bot reports a tool the catalogue does not have | Ignored. The bot is newer |
| `argsHash` disagrees | **That one tool** is disabled. The rest keep working |
| Catalogue has a tool the bot did not report | Treated as absent |

### How `argsHash` is computed

Every repository has to arrive at the same string from the same schema, so the rule is exact:

```
argsHash = "sha256:" + hex(sha256(canonical(wireSchema)))
```

`canonical` serialises the schema as JSON with **object keys sorted recursively**, **no whitespace**
(`,` and `:` as separators), arrays left in their order, and non-ASCII characters left as
themselves rather than escaped. `catalogHash` is the same function applied to the whole `tools`
array, and it is what says the catalogue has not been edited without being rehashed.

`catalogVersion` is semantic and describes the **arguments**, not the tool list. Its major moves
when an existing tool's `wireSchema` changes, because every bot compiled against the old one now
has a hash that disagrees. Adding a tool moves the minor: a bot that has never heard of it reports
nothing about it, which is already how a bot ships one tool at a time.

That last row is why a bot can ship one tool at a time. A `bot-fabric` that cannot fish yet says
nothing about `fish`, and the other 63 tools work from the first day. Being unable and being
unwritten look the same on purpose.

`tools/list` never changes. It is the whole catalogue whether or not any bot is connected — an MCP
client reads it once when the session opens, and a client that connects before the first bot must
still see everything.

## Events

Five feeds: `chat`, `actionBar`, `title`, `dialog`, `effect`. Bots push; they do not wait to be
asked. `helloOk.events` carries one boolean per feed as a valve, because `effect` on a busy server
is a firehose.

`chat` is never folded — the same line twice is information. The other four are folded by the bot
into runs, and a run is re-sent once a second while it stays open so the server's view of "how long
has this been showing" stays current.

`event.source` is **who produced it**: a player's name for a message a player sent, and
`system` for anything else. Not where the client drew it -- that is what `kind` already says, and
one bot reporting `chat`/`system`/`game_info` while the other reported player names made the two
describe the same chat log differently.

Segments travel structured: `{text, font?, color?}`. The bot strips glyphs and colour codes,
because that is game knowledge; the server joins them for display, because that is presentation.
Neither bot gets to have an opinion about what the separator looks like.

**A segment's text is not trimmed.** `"Wave "` and `"Wave"` are different pieces: a server that
writes a label and a number as two components puts the space in one of them, and trimming it makes
the two kinds of bot disagree about a HUD they both read correctly. Leading and trailing space
inside a segment is the server's, and survives.

**A DTO carries the component a server sent, and the server flattens it.** `read-boss-bars` and
`read-displays` send `component`: Minecraft's own JSON, as the bot received it. Breaking it into
pieces -- inheriting the font down the tree, taking the glyphs out, dropping what is left blank --
happens once, in `render/Flatten`, instead of once per kind of bot. It used to be twice, and every
bug in it came from the copy that had no game to ask: the 26.x `{"": "x"}` shorthand, translate keys
left as keys, a prismarine wrapper whose style nothing looked inside, and style fields in NBT form
that a JSON-shaped reader skipped. Four, in one day, against one real server.

A bot still sends `segments` and `text`, and they are what the server uses when there is no
component -- or when the component holds a translate key, which the bot resolved with the game's
language table and the server cannot.

**A DTO carries segments where a server stacks labels.** `read-boss-bars` and `read-displays` send
the pieces their text is drawn from, for the same reason the feeds do: a real server's boss bar
named a place, a date and a channel side by side, and joined into one string it read as one word.
`read-displays` also sends `glyphPieces`, because a display drawn only from glyphs is an icon --
something is there and there is nothing to read -- which is not the same as an empty display.

**Only `actionBar` and `title` carry segments.** They are the feeds a server draws with stacked
glyphs, and the pieces are what keep two labels from running together. Chat is prose: splitting it
at every style change turns one sentence into a dozen fragments joined by separators, which is
harder to read than the sentence. A chat line sends `text` and no segments.

**An id is namespaced.** A sound, a particle, an item: `minecraft:happy_villager`, not
`happy_villager`. It is what the server said and what a caller would type back.

**A segment with nothing left in it is dropped.** A HUD is drawn by stacking a glyph, a spacer
made of private use area codepoints, and a label; once the glyphs are out, the spacer holds
whitespace and nothing else. It is a position on the screen rather than something to read, so it
does not become a segment.

## Limits

| Name | Default |
| --- | --- |
| frame | 16 MiB |
| JSON frame | 1 MiB |
| blob | 8 MiB, 32 MiB pending per link, 30s TTL |
| in-flight calls | 8 per bot |
| ring buffer | 200 per feed per bot |
| heartbeat | 5s, dead after 3 missed |
| deadline | 600s ceiling |

A bot with calls in flight is not declared dead on the heartbeat — the timer extends to the
earliest deadline plus grace. A 60-second walk must not look like a hung link.
