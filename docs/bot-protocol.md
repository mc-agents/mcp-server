# Bot protocol

How `mcp-server` talks to a bot. `bot-fabric` speaks it today; the document is the contract rather
than a description of that one bot, which is what a second kind of bot would be written against.
One was, and much of what is explained here is why -- the two disagreeing is how a good deal of it
was found.

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
| `BOT_LINK_TOKEN` | what to send as `hello.linkToken`. The operator sets it from the server's link Secret; a bot must never log it | unset: no `linkToken` in `hello` |

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
| `hello` | `protocols[]`, `botName`, `kind`, `agentVersion`, `mcVersion`, `catalogVersion`, `capabilities[]`, `features[]`, `linkToken?` |
| `result` | `id`, `ok`, `text`, `data?`, `blobs[]?`, `error?`, `elapsedMs` |
| `event` | `seq`, `kind`, `source`, `text`, `segments[]?`, `component?`, `data?`, `ts`, `firstTs`, `repeats`, `closed` |
| `status` | `state`, `ts`, and whatever it knows: `address`, `username`, `mcVersion`, `serverBrand`, `gameMode`, `dimension`, `position`, `health`, `food`, `dead`, `causeOfDeath`, `reason`, `lastError` |
| `log` | `level`, `message`, `fields?` |
| `pong` | `nonce` (the integer the `ping` carried), `ts`, `busy` (how many calls are in flight, an integer) |

`capabilities[]` is `{tool, argsHash}`. `features[]` holds `blob`, `eventFold`, `structuredDialog`.

`linkToken` is the bot's proof that it belongs to this server. A NetworkPolicy is the first gate
on the port; this is the second, so a pod that gets past the policy still cannot introduce itself
as a bot. When the server has a token configured (`mcagents.bot-link.token`, env `BOT_LINK_TOKEN`),
a `hello` without an equal one is answered with a `fault` `UNAUTHORIZED` and the connection closes,
before the name is taken -- an impostor must not hold a name the real bot is about to dial in
under. The comparison is constant-time. When the server has none, the field is ignored, which is
what lets the server, the bots and the operator each ship this on their own. The server logs a
refusal at WARN with the bot's name and never the token; a bot never logs it either.

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

**A bot sends `ready` again when it dies and when it comes back.** Death does not end the
connection, so nothing about `state` changes, and a status that only moved with the state told a
caller a dead bot was ready: it was sent walking, sent nothing to the server, and the walk timed out
without a word about why. `dead` is the flag, and `causeOfDeath` is what the death screen said while
it was up. A bot never respawns by itself -- a server under test may be checking what happens on
death, and a bot that got up again at once would hide exactly that -- so the flag stays until
`respawn` is called.

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
is a violation. A `hello` the server refuses -- the wrong protocol, a name it cannot use or one
already taken, a missing or unequal `linkToken` -- gets a `fault` naming which, then the close.

## Four invariants

These are what keep the server's state machine small. Everything else follows from them.

**1. Exactly one `result` per `call.id`.** Cancelled, past its deadline, or sent while the bot is
dying — still exactly one. Ids are never reused on a connection.

**2. `call.args` is what the server normalised, not what MCP received.** No `bot` field, every
property required, defaults filled, values clamped, coordinates floored, and inside an array of
objects, every element is normalised the same way. Two bots cannot hold different opinions about
what `range` defaults to, because neither one decides.

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

**A structured tool may also carry blobs.** The rendered text comes first and names them: `hover-slot`
sends the tooltip's lines in `result.data` and the frame they are drawn in as a blob, and `frame` in
the DTO is what tells the renderer an image is attached, because the renderer never sees the image.

**A tool needs a world unless the catalogue says otherwise.** `needsWorld` is false for exactly one
tool, `screenshot`, and the exception is the point: the screen a bot is stuck on is the answer to
why it cannot reach a world. A resource pack prompt held a join for ninety seconds and the only
thing that could have said so was a picture, which the server was refusing to ask for because the
bot was not in a world.

## Errors

A `result` with `ok:false` carries `error: {class, code, message, retryable, detail?}`. The class
decides what the server does with the session, and telling them apart is the point. `retryable` is
a hint the caller sees as `(retryable)` on the end of the failure; the server never retries on its
own, because a tool that half ran is not the same call twice.

| class | Means | Server does | Reaches the caller as |
| --- | --- | --- | --- |
| `tool` | The game refused. No window open, no such item, nowhere to stand | Nothing. The session is fine | `Failed: {message}` |
| `timeout` | The bot could not finish inside `deadlineMs` | Nothing | `Failed: {tool} did not finish within {n}ms.` |
| `cancelled` | A `cancel` arrived | Nothing | `Failed: cancelled` |
| `unsupported` | This kind of bot does not have the tool | Drops it from the session's capabilities | Names a kind that does |
| `args` | The bot could not use `args` as given: a slot outside the window it has open, a hand it does not know -- what the schema cannot judge | Nothing; a build that disagrees with the catalogue never had the tool offered, since the hashes are compared at the handshake | `Failed: {message}` |
| `bot` | The link or the game connection broke | Reports the failure. The session itself is reaped by the heartbeat and by the next `status`, not by the call | `Failed: {message} Use get-bot-status to inspect it.` |
| `internal` | Something threw inside the bot | Logs. Keeps the session — one broken tool must not kill a bot | `Failed: {message}` |

Protocol violations are never a `result`. The offender gets a `fault` frame and the connection
closes: `FRAME_TOO_LARGE`, `BAD_FRAME_TYPE`, `MALFORMED_JSON`, `UNKNOWN_MESSAGE`, `MISSING_FIELD`,
`HELLO_EXPECTED`, `HELLO_TWICE`, `BLOB_BEFORE_HELLO`. An `event` carrying a `seq` the server has
already seen is not one: a folded run is re-sent under its own number, which is the third invariant.

A `result` for an id the server has already abandoned is **not** a violation. It is dropped.

## Deadlines and cancellation

`deadlineMs` is relative — clocks between pods drift, absolute timestamps do not survive that.

The server arms `deadlineMs + 2000`; the bot arms `deadlineMs`. So in the ordinary case the bot's
timer fires first, it stops what it was doing, and answers `class: "timeout"`. The session stays
healthy.

If the server's timer fires first the bot has gone quiet. The server abandons the id, sends
`cancel`, and tells the caller the link may be stalled rather than that the tool failed. Whether
the bot is gone is the heartbeat's to say: a link that misses three beats is closed, and nothing
is counted per call.

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

A disabled tool is visible on both sides, because the two catalogues disagreeing is a fact that
explains a refusal an agent meets an hour later. The server logs every tool it refuses at WARN with
the reason, counts them in the gauge `mcagents.bots.rejected_tools{bot}`, and `list-bots` and
`get-bot-status` name them as "disabled: schema mismatch". A bot logs the `rejectedTools` list
from `helloOk` at WARN.

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

Six feeds: `chat`, `actionBar`, `title`, `dialog`, `effect`, `toast`. Bots push; they do not wait
to be asked. `helloOk.events` carries one boolean per feed as a valve, because `effect` on a busy server
is a firehose. A bot sends nothing on a feed that is false or missing. The server sets them from
`mcagents.bot-link.muted-feeds`.

`chat`, `effect` and `toast` are never folded — the same line twice, a sound played twice, a toast
put up twice are each two things that happened. `actionBar`, `title` and `dialog` are folded by the
bot into runs, one open run per feed. A repeat keeps the run's `seq` and raises `repeats`; a line
that differs closes the run and opens the next. A run is re-sent every `helloOk.repeatFlushMs` while
it stays open, so the server's view of "how long has this been showing" stays current, and a run
nothing has repeated for three of those is sent once more with `closed: true` and dropped -- a
server that stops sending an action bar says nothing about it having gone, and three flushes is
where the bot decides it has. Leaving the world closes every run. `repeatFlushMs` is the server's,
from `mcagents.bot-link.repeat-flush-ms`, so there is no second number for the two kinds of bot to
disagree about.

`event.source` is **who produced it**: a player's name for a message a player sent, and
`system` for anything else. Not where the client drew it -- that is what `kind` already says, and
one bot reporting `chat`/`system`/`game_info` while the other reported player names made the two
describe the same chat log differently.

Segments travel structured: `{text, font?, color?}`. The bot strips glyphs and colour codes,
because that is game knowledge; the server joins them for display, because that is presentation.
Neither bot gets to have an opinion about what the separator looks like.

**A separator goes between labels, not between words.** The pieces are joined with ` | ` only when
one of them names a font, because a font is what says the pieces are separate labels stacked on a
HUD. Without one there is nothing to take apart: the pieces are one run of text the server
happened to colour, and the screen shows them touching. Separating those reported a chat line
reading "Hello world and welcome" as `Hello  | world |  and welcome`, and both kinds of bot said
it, so the comparison suite called them identical and was right about the wrong thing.

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

The stacks in a window carry it as well: `labelComponent` beside `label`, and `loreComponents`
beside `lore`, paired by position. A plugin draws a screen out of custom-named items and writes
those names in the resource pack's own glyphs, so an item label is a HUD in the same way an action
bar is. `read-window`, `open-container`, `wait-for-window`, `click-slot`, `drag-slots` and
`drop-held-item` all send them, from the one place in each bot that describes a stack.

The same goes for everything else a server writes and a bot used to flatten on its own: a window
sends `titleComponent` for its header and `close-window` for the one it closed, a sign face sends
`lineComponents`, an entity sends `labelComponent` for its nameplate, a scoreboard sends
`titleComponent` and a `nameComponent` per entry, the inventory's stacks send `label` and
`labelComponent` with `lore` and `loreComponents` beside them, and the tab list sends `displayName` with its component beside the username. The username stays because it is the identity every other tool
takes; the drawn name is where a server puts a rank.

**A scoreboard entry is the line as the client draws it, not the score as the objective keeps it.**
A plugin writes a quest log on the sidebar one team per line: the owner of each score is a colour
code nobody sees, the team it is on carries the line as its prefix and suffix, and the objective
hides the numbers. Read as owners and scores that was three colour codes counting down. So
`read-scoreboard` sends what the sidebar shows: `name` and `nameComponent` are the drawn name -- on
the sidebar the entry's team prefix, its display name or else its owner, and its team suffix, in the
team's colour -- and `score` is the number whether or not the line shows it. `scoreText` and
`scoreComponent` are the score column: the number in the slot's default style, the fixed text the
objective or the entry puts in its place, or empty text when the format is blank. Both null is a bot
built before the column was sent, and the server prints the number then. The entries come in the
client's order, highest score first and owners case-insensitively after that; the sidebar leaves out
every owner starting with `#` and stops at fifteen, and the objective it shows is the one for the
local player's team colour when that slot has one. The list and below-name slots are sent whole,
unfiltered and without team formatting, because that is not how the client draws them.

**A pattern matches what the tools show.** `wait-for-window`'s `titlePattern` and the `after` and
`until` patterns of `press-input` are matched by the bot, against three candidates, and any one
matching is a match: the title or line as `read-window`, `read-action-bar` and `read-title` show it,
font labels and ` | ` separators included, which is the server's `Flatten` and `Piece` rule mirrored
in each bot; the plain text with the glyphs and colour codes taken out, which is what the patterns
used to be matched against; and the raw string as the game holds it, so a pattern written against a
glyph goes on working. An agent copies what one tool showed into the next, and matched against the
plain text alone a wait for `[ui/page_6] 2/2` never came. A sound or a particle is still its id.

**An NPC is picked by the label over it, and the rule for "over" is the catalogue's.** On a server
that draws with a resource pack an NPC has no name of its own: it is a mannequin, or a model clicked
through an invisible base or an `interaction` hitbox, and the name a player reads is a separate
`text_display` floating above it. Nothing in the game links the two, so every kind of bot links
them by the same rule about where they stand -- feet no higher than the label and at most 3 blocks
below it, at most 1 block to the side, the nearest such entity a player could click -- and
`find-entity` reports the label it arrives at as `nameplate`, so what an agent reads is what
`label` then finds. Two bots with their own idea of "over" would click different NPCs under the
same sign.

`crosshair` is the client's own hit test where there is one. azalea keeps a hit result that gives
an `interaction` entity no size, so it never lands on the hitbox a model is clicked through; that
bot walks the same ray itself with the entity's width and height from its metadata.

**`join-server` waits three minutes for a spawn.** A bot on a machine with a graphics card is in
the world a few seconds after it dials. Without one it is another matter: the client builds every texture atlas in software before it can act, and on a
four-core CI runner that took eighty-nine seconds -- so the bot arrived in the world one second
after its own ninety-second deadline had fired, and disconnected itself on the way in. The number
is what a client without a card costs, not slack.

**An item's model is part of what it is.** A server that draws its own item gives paper a model of its
own, and the id alone reads a quest note, a menu icon and a sheet of paper as one thing. So the
inventory's stacks and the item an `item_display` holds send `itemModel`, the `item_model` component
as the game resolves it -- which for a plain item is its own id, and the server leaves that unsaid.

**A blank lore line is a line.** It is where a menu puts its spacing, and the line below it sits
where the server put it -- the same reason a blank sign face line is reported rather than dropped.
One kind of bot used to drop them and the other kept them, so an item with a spacer read as two
different items; dropping them also left nothing for the components below to pair with.

The feeds carry it too, on every kind and not only the ones with segments. A HUD component on a
server that draws with glyphs is around a kilobyte, and folding means one of those crosses the wire
about once a second per feed: a kilobyte a second per bot, next to the gigabyte of memory a real
client costs. The measurement is the reason it is on the feeds rather than only on the tools.

A bot still sends `segments` and `text`, and they are what the server uses when there is no
component -- or when the component holds a translate key, which the bot resolved with the game's
language table and the server cannot.

**A DTO carries segments where a server stacks labels.** `read-boss-bars` and `read-displays` send
the pieces their text is drawn from, for the same reason the feeds do: a real server's boss bar
named a place, a date and a channel side by side, and joined into one string it read as one word.
`read-displays` also sends `glyphPieces`, because a display drawn only from glyphs is an icon --
something is there and there is nothing to read -- which is not the same as an empty display.

**A wait on state nothing pushes is the server polling a tool.** Four feeds are pushed by the bot
and waited on where they are kept; a sidebar counting a quest up, a boss bar, a hologram, the tab
list and what the bot is carrying are state a tool reads when asked, with nothing to wake a waiter.
Those waits carry `watches` in the catalogue, naming the reading tool they poll until its answer
matches, and nothing of them reaches a bot -- so they have no wire schema and a bot does not report
them as capabilities. The alternative was every bot pushing every piece of state it holds on the
chance somebody waits for it.

**The dialog feed carries the dialog, not a description of it.** `event.data` on a `dialog` line is
the dialog as the game serialises it -- `title`, `body`, `actions`/`yes`/`no`/`action`/`exit_action`,
`inputs` -- and the server reads the parts worth reading and writes the line. A dialog is not one
piece of text, so it cannot travel as a component, and which of its parts to say in what order is
presentation. One kind of bot used to build that sentence itself while the other had no dialog feed
at all, so the same dialog read one way or not at all depending on which bot was asked.

A bot that is sent a dialog by registry reference has to resolve it: `/dialog show` names one the
datapack declared and the packet then carries nothing but an index into the `minecraft:dialog`
registry. `source` is `dialog` for one being shown and `closed` for it going away, and the sentence
for the second is the server's.

What an input holds is not in the dialog: its definition carries a starting value, and after that
the value lives in the client's control. A bot that changes one -- `set-dialog-input` -- sends the
dialog again with `values`, an object from each input's `key` to what it holds now: `true` or
`false` for a checkbox, the option's id for a cycle, the number for a slider, the text for a text
field. Without `values` the server reads each input as its starting value, which is what a player
who touched nothing would send.

**A toast is a feed because it does not stay.** An advancement made or a recipe unlocked is up in
the corner for five seconds; a tool that read the screen would be asking whether the call happened
to land inside those five seconds, and an agent's round trip is often longer. `source` is
`advancement` or `recipe`, the component is the title, and `data` carries what the title does not
show: `id`, `frame`, `description` and `descriptionComponent` for an advancement, `item` for a
recipe. The id is what a caller can name: a server that grants one when a quest is done draws its
title in the pack's own font, and a wait written against the id does not care.

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

## A sequence runs in the bot, in ticks

`run-inputs` runs several steps -- a key press, a window click, an item used from the hand, a chat
command, a wait, a wait for a line -- inside the bot, one after another, and reports the client
tick each step started and ended on. Two kinds of bot have to produce the same numbers for the same steps, so the rule is
written here rather than in either of them.

A tick is the bot's client tick: fabric's `END_CLIENT_TICK`, azalea's `Event::Tick`. Tick 0 is the
tick the first step started on. **A step starts on the very tick the step before it ended**:
`startedTick == previous.endedTick`, and the first step's is 0.

| Step | Starts | Ends |
| --- | --- | --- |
| `press` | the key goes down on `startedTick` and stays down for `holdTicks` | it comes up on `startedTick + holdTicks`, and the step ends **one tick later**, so the game reads the key up before the next step. That is press-input's `intervalTicks` of 1, and two presses in a row reach the server as two rising edges. fabric is exact; azalea can end up to three ticks later while its keys settle, and reports the tick it did |
| `click` | sent on `startedTick` | the tick the server sent the window back, or answered with another window or by closing it -- one to three ticks in a cluster. Another window or a closed one is reported as click-slot's `window` and **the sequence goes on**: the next click is made in whatever container screen is open at its turn, and a click with no window open is refused there |
| `useItem` | the item in the hand named is used on `startedTick` -- the item's own right-click, whatever the crosshair is on, which is what tells it from `press: use`, the player's click that goes to the entity or block there first -- and stays in use for `holdTicks` | released on `startedTick + holdTicks`, and the step ends **one tick later**, as a press does. `useItem` is what use-held-item answers: the hand, what it held when the use began, and `holdTicks` |
| `command` | sent on `startedTick` | the same tick |
| `wait N` | | `startedTick + N`, so `[click, wait 20, click]` sends the second click exactly twenty ticks after the first was answered (fabric exact, azalea a lower bound) |
| `waitFor` | | the end of the tick a matching line arrived on. Lines that arrived since the step before it started count, so a reply the server sent while that step was still settling is not missed; the first step counts lines since the sequence began. A press that must land on the very tick a cue arrives is press-input's `after`, which presses from the packet handler; a `waitFor` followed by a `press` is one tick later |

Steps that end on the tick they started -- a command, a `waitFor` whose line is already there --
let several steps run in one tick. A press, a click and a wait always take at least one.

**Refusals before anything runs are a failed call**, the way any tool refuses: `BAD_STEP` for a
step that names none or more than one of press, click, useItem, command, wait and waitFor,
`NO_SLOT` for a hotbar press without one, `BAD_PATTERN`, and `TOO_LONG` when the steps add up to
more than `timeoutMs` on their own -- `(holdTicks + 1)` ticks per press or useItem, `N` per wait,
one per click, none for a command or a `waitFor`, at 50ms a tick.

**A step the game refuses while the sequence runs stops it there, and the call still succeeds.**
`WINDOW_OPEN`, `NO_WINDOW`, `SLOT_OUT_OF_RANGE`, `CLICK_UNCONFIRMED`, `DEAD`: the step carries
`error: {code, message}`, `stopped` is `refused`, and the steps after it are not in `steps[]`. What
the steps before it confirmed is the reason the sequence was asked for, so it is not thrown away.
When `timeoutMs` runs out the step under way is cleaned up -- a held key let go -- and carries
`error: {code: "TIMEOUT"}` with `stopped: "timeout"`. The bot stops itself before the server's
deadline (`timeoutMs` plus its margin), so a sequence never ends as a `timeout`-class result with
no DTO. A `cancel` or a lost link cleans the running step up the same way. Nothing is rolled back.

Every step in `steps[]` carries `asked`: the step as the bot parsed it, in the bot's own words
(`press jump`, `press hotbar 1`, `press use for 40 ticks`, `click slot 13`, `use item in main hand`,
`use item in off-hand for 20 ticks`, `command /spawn`, `wait 20 ticks`, `wait for /Fine day/ on
actionBar`), set before it runs, so a step that was refused
or timed out is still named in the answer. A command is named, and `steps[].command` reported, as
it was sent: a slash is put in front of text that has none, and text that already starts with one
is kept as given, the way run-command takes it -- so `//set stone` stays WorldEdit's `//set`.

One known difference, which click-slot already has: azalea's click treats two seconds without an
answer as the same window and goes on, where fabric stops with `CLICK_UNCONFIRMED`.

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
