# Tools

<!-- Rendered from catalog/catalog.json by ./gradlew renderToolReference. Edit the catalogue, not this page. -->

Catalogue 5.11.0, 102 tools. This is what `tools/list` offers an MCP client, one section a tool, with the arguments as the client sends them. What a bot receives is the catalogue's `wireSchema`, which drops `bot` and fills every default in; the [bot protocol](bot-protocol.md) covers that side.

Every tool but [`list-bots`](#list-bots), [`ping-server`](#ping-server), [`wait-for-server`](#wait-for-server) takes `bot`, the name given to join-server, which may be left out while exactly one bot is connected; it is not repeated below. A tool marked **fabric only** is one the headless kind of bot does not run. **Exclusive** means one call at a time on a bot, since two walks or two clicks at once would fight over the same body. **Untrusted** means the answer carries content the server did not write -- chat, item names, signs -- and is marked as data rather than instructions. **Read-only** means the call changes nothing in the game or on the server, which is what an MCP client's `readOnlyHint` approves without asking; **destructive** is the `destructiveHint`, for a call that removes something or runs with the bot's permissions. A tool that **needs a world** is refused while the bot is on a title or disconnected screen. The deadline is how long the server waits for the bot before giving the call up; a tool with a `timeoutMs` argument sets its own inside that.

| Group | Tools |
| --- | --- |
| [world](#world) | [`activate-block`](#activate-block), [`attack-entity`](#attack-entity), [`fish`](#fish), [`interact-entity`](#interact-entity), [`use-held-item`](#use-held-item) |
| [region](#region) | [`build-region`](#build-region), [`import-region`](#import-region), [`photograph-region`](#photograph-region), [`learn-custom-blocks`](#learn-custom-blocks), [`list-regions`](#list-regions), [`read-region`](#read-region), [`read-selection`](#read-selection), [`show-region`](#show-region), [`measure-room`](#measure-room), [`verify-region`](#verify-region), [`write-region`](#write-region) |
| [crafting](#crafting) | [`can-craft`](#can-craft), [`craft-item`](#craft-item), [`get-recipe`](#get-recipe), [`list-recipes`](#list-recipes) |
| [screen](#screen) | [`click-chat`](#click-chat), [`press-dialog-button`](#press-dialog-button), [`read-book`](#read-book), [`screenshot`](#screenshot), [`set-dialog-input`](#set-dialog-input), [`type-text`](#type-text) |
| [slot](#slot) | [`click-slot`](#click-slot), [`drag-slots`](#drag-slots), [`drop-held-item`](#drop-held-item), [`hover-slot`](#hover-slot), [`select-bundle-item`](#select-bundle-item) |
| [window](#window) | [`close-window`](#close-window), [`open-container`](#open-container), [`open-inventory`](#open-inventory), [`press-container-button`](#press-container-button), [`read-container-options`](#read-container-options), [`read-trades`](#read-trades), [`read-window`](#read-window), [`select-trade`](#select-trade), [`set-beacon-effects`](#set-beacon-effects), [`wait-for-window`](#wait-for-window) |
| [server](#server) | [`complete-command`](#complete-command), [`get-world-state`](#get-world-state), [`run-command`](#run-command), [`switch-server`](#switch-server), [`wait-ticks`](#wait-ticks) |
| [sessions](#sessions) | [`detect-gamemode`](#detect-gamemode), [`get-bot-status`](#get-bot-status), [`join-server`](#join-server), [`leave-server`](#leave-server), [`list-bots`](#list-bots), [`restart-bot`](#restart-bot) |
| [block](#block) | [`dig-block`](#dig-block), [`find-blocks`](#find-blocks), [`get-block-info`](#get-block-info), [`get-target-block`](#get-target-block), [`pick-block`](#pick-block), [`place-block`](#place-block), [`read-block-entity`](#read-block-entity) |
| [inventory](#inventory) | [`equip-item`](#equip-item), [`find-item`](#find-item), [`give-item`](#give-item), [`list-inventory`](#list-inventory), [`wait-for-item`](#wait-for-item) |
| [entity](#entity) | [`find-entity`](#find-entity), [`read-displays`](#read-displays), [`wait-for-displays`](#wait-for-displays) |
| [movement](#movement) | [`fly-to`](#fly-to), [`get-position`](#get-position), [`jump`](#jump), [`look-at`](#look-at), [`move-in-direction`](#move-in-direction), [`move-to-position`](#move-to-position), [`press-input`](#press-input), [`respawn`](#respawn), [`run-inputs`](#run-inputs), [`set-stance`](#set-stance) |
| [hud](#hud) | [`get-player-state`](#get-player-state), [`read-action-bar`](#read-action-bar), [`read-advancements`](#read-advancements), [`read-boss-bars`](#read-boss-bars), [`read-dialog`](#read-dialog), [`read-player-list`](#read-player-list), [`read-scoreboard`](#read-scoreboard), [`read-stats`](#read-stats), [`read-title`](#read-title), [`read-toasts`](#read-toasts), [`wait-for-action-bar`](#wait-for-action-bar), [`wait-for-boss-bars`](#wait-for-boss-bars), [`wait-for-dialog`](#wait-for-dialog), [`wait-for-player-list`](#wait-for-player-list), [`wait-for-scoreboard`](#wait-for-scoreboard), [`wait-for-title`](#wait-for-title), [`wait-for-toast`](#wait-for-toast) |
| [probe](#probe) | [`ping-server`](#ping-server), [`wait-for-server`](#wait-for-server) |
| [chat](#chat) | [`read-chat`](#read-chat), [`send-chat`](#send-chat), [`wait-for-chat`](#wait-for-chat) |
| [effect](#effect) | [`read-effects`](#read-effects), [`wait-for-effect`](#wait-for-effect) |
| [smelting](#smelting) | [`smelt-item`](#smelt-item) |

## world

### activate-block

*fabric and azalea · deadline 5s · exclusive · needs a world · the bot answers*

Right-click the block at a position, walking to it first when out of reach. Presses buttons and levers, opens doors, and triggers custom blocks.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `x` | number | yes | X coordinate |  |
| `y` | number | yes | Y coordinate |  |
| `z` | number | yes | Z coordinate |  |

### attack-entity

*fabric and azalea · deadline 30s · exclusive · needs a world · the bot answers*

Attack an entity, walking to it first when out of reach. A hit is counted when the server acknowledges it -- the entity flinches, takes damage or dies -- and a swing the server ignored is made again; three in a row it ignores fail the call, which is what an entity out of reach or invulnerable looks like. Say which entity with exactly one of name, label, id or crosshair; none, or more than one, is refused. A label is text floating in the world: a text_display, or an invisible armor stand showing its name. The entity under a label is the one a player could click whose feet are no higher than the label and at most 3 blocks below it, and at most 1 block from it horizontally; of several, the one nearest the label.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `name` | string | no | Entity name or part of a player name, for example villager or Steve. The nearest match within maxDistance. |  |
| `label` | string | no | Text floating over the entity, or part of it, for example Fisher Kim: what a player reads over an NPC that has no name of its own. The nearest label containing it that has an entity under it within maxDistance is used. find-entity shows each entity's label. |  |
| `id` | integer | no | The id find-entity gave the entity. Not limited by maxDistance. | at least 0 |
| `crosshair` | boolean | no | Pass true to take whatever entity the crosshair is on, as the client's own hit test finds it, without turning or walking. It reaches what a player clicks and a name does not: an interaction hitbox, an invisible model base. Turn the bot first. |  |
| `maxDistance` | number | no | Search radius for name and label (default: 8) | at least 1 |
| `times` | integer | no | How many hits to land (default: 1, at most 20) | 1 to 20 |

### fish

*fabric only · deadline 60s · exclusive · needs a world · the bot answers*

Cast the rod and wait for a bite, then reel in. Equips a fishing rod from the inventory if one is not already in hand. The bot has to be standing within reach of water.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `timeoutMs` | integer | no | How long to wait for a bite (default: 60000) | 1000 to 300000 |

### interact-entity

*fabric and azalea · deadline 5s · exclusive · needs a world · the bot answers*

Right-click an entity, walking to it first when out of reach. This is what opens an NPC dialogue. Say which entity with exactly one of name, label, id or crosshair; none, or more than one, is refused. A label is text floating in the world: a text_display, or an invisible armor stand showing its name. The entity under a label is the one a player could click whose feet are no higher than the label and at most 3 blocks below it, and at most 1 block from it horizontally; of several, the one nearest the label.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `name` | string | no | Entity name or part of a player name, for example villager or Steve. The nearest match within maxDistance. |  |
| `label` | string | no | Text floating over the entity, or part of it, for example Fisher Kim: what a player reads over an NPC that has no name of its own. The nearest label containing it that has an entity under it within maxDistance is used. find-entity shows each entity's label. |  |
| `id` | integer | no | The id find-entity gave the entity. Not limited by maxDistance. | at least 0 |
| `crosshair` | boolean | no | Pass true to take whatever entity the crosshair is on, as the client's own hit test finds it, without turning or walking. It reaches what a player clicks and a name does not: an interaction hitbox, an invisible model base. Turn the bot first. |  |
| `maxDistance` | number | no | Search radius for name and label (default: 8) | at least 1 |

### use-held-item

*fabric and azalea · deadline 5s · needs a world · the bot answers*

Right-click with the item the bot is holding, optionally holding the button down for a while. Uses the item itself; press-input use is the player's click, which hits the entity or block first (see run-inputs useItem).

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `offhand` | boolean | no | Use the off-hand item instead of the main hand (default: false), the same hand run-inputs useItem calls off-hand |  |
| `holdMs` | integer | no | How long to keep the button down before releasing, in milliseconds, not ticks, for bows and the like (default: 0); press-input and run-inputs use holdTicks, 1 tick = 50ms | 0 to 60000 |

## region

### build-region

*fabric and azalea · deadline 60s · exclusive · untrusted · destructive · the server drives the bot's session*

Set a WorldEdit selection over a box and run one operation on it, as a single call: //pos1, //pos2 and the operation, sent as the bot's own commands. Needs WorldEdit or FastAsyncWorldEdit on the server and the permission to run it; without the plugin the answer says so and names read-region as what reads the same box anyway. Arguments that cannot make a valid command are refused before the bot is touched. A large edit carries on after the command has been answered, so an answer that never arrives means the edit may still be running rather than that it failed, and verify-region is how to find out.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `from` | object | yes | One corner of the box, inclusive |  |
| `from.x` | integer | yes |  |  |
| `from.y` | integer | yes |  |  |
| `from.z` | integer | yes |  |  |
| `to` | object | yes | The other corner, inclusive |  |
| `to.x` | integer | yes |  |  |
| `to.y` | integer | yes |  |  |
| `to.z` | integer | yes |  |  |
| `operation` | string: `set`, `replace`, `walls`, `faces`, `overlay`, `hollow`, `smooth`, `naturalize` | yes | What to run over the selection |  |
| `pattern` | string | no | WorldEdit pattern, for example stone or 50%stone,50%cobblestone. Required by set, replace, walls, faces and overlay; optional for hollow, which fills with air without one; refused by smooth and naturalize, which take no pattern | at most 128 characters |
| `mask` | string | no | Which blocks replace changes, as a WorldEdit mask. Only replace takes one; without it replace changes everything that is not air | at most 128 characters |

### import-region

*fabric and azalea · deadline 5s · read-only · the server answers from what it holds*

Keep a region you spell out yourself, in the encoding read-region answers with: a palette of block ids and runs of {block, count} that walk the box y ascending, then z ascending, then x ascending, and have to spell out size.x times size.y times size.z blocks exactly. This is how a shape you designed, rather than one WorldEdit has a command for, gets into the world: import it, then write-region puts it down. The box is placed with its lower corner at and may hold at most 32768 blocks; a larger design comes in as a schematic through POST /regions instead. The answer draws it when it is small enough and quotes the id. Needs no bot.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `at` | object | yes | Where the region's lower corner is; write-region puts it there unless told otherwise |  |
| `at.x` | integer | yes |  |  |
| `at.y` | integer | yes |  |  |
| `at.z` | integer | yes |  |  |
| `size` | object | yes | How many blocks the box spans on each axis, each at least 1 |  |
| `size.x` | integer | yes |  |  |
| `size.y` | integer | yes |  |  |
| `size.z` | integer | yes |  |  |
| `palette` | array of string | yes | Block states the runs index into, as /setblock takes them: stone, oak_stairs[facing=north,half=top] or, on a server whose custom blocks have been learned, 2025summer:bar_table[facing=east] | 1 to 4096 items |
| `runs` | array of object | yes | Run-length encoded blocks, y then z then x ascending; block indexes into palette | 1 to 32768 items |
| `runs[].block` | integer | yes |  | at least 0 |
| `runs[].count` | integer | yes |  | at least 1 |
| `name` | string | no | What to call it, for list-regions; optional | at most 64 characters |

### photograph-region

*fabric only · deadline 120s · exclusive · untrusted · the server drives the bot's session*

Photograph a room from several places at once, so what it looks like can be judged rather than inferred from a block list. Give it the box and it works the viewpoints out itself: "corners" puts the camera high in each of the four top corners aimed at the middle, which is the room as a composition; "centre" stands in the middle and turns to each wall in turn, which is the room as somebody standing in it sees it. The HUD is left off by default here, the opposite of screenshot, because a hotbar across every frame is not what is being looked at. The bot goes back where it was afterwards.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `from` | object | yes | One corner of the room, inclusive |  |
| `from.x` | integer | yes |  |  |
| `from.y` | integer | yes |  |  |
| `from.z` | integer | yes |  |  |
| `to` | object | yes | The other corner, inclusive |  |
| `to.x` | integer | yes |  |  |
| `to.y` | integer | yes |  |  |
| `to.z` | integer | yes |  |  |
| `framing` | string: `corners`, `centre`, `both` | no | Where to put the camera: "corners" (default) for four views from the top corners looking in, "centre" for four from the middle looking out, "both" for all eight |  |
| `width` | integer | no | Image width (default: 854). The frame is the client's window scaled, so past 1280x720 there is no more detail in it, only more bytes | 256 to 1920 |
| `height` | integer | no | Image height (default: 480) | 144 to 1080 |
| `hud` | boolean | no | Draw the HUD and any open screen over each frame (default: false) |  |

### learn-custom-blocks

*fabric and azalea · deadline 600s · exclusive · untrusted · destructive · the server drives the bot's session*

Learn what every CraftEngine custom block on this server looks like to a client, once, so read-region names them and write-region puts them down. CraftEngine gives a custom block the look of a vanilla state nothing uses -- a note block with some instrument and note -- and that look is all a client is sent, so without this a custom block reads as note_block[...] and cannot be told from another. The tool lists every custom block state through the server's own tab completion, places each with CraftEngine's debug setblock in a scratch row of 64 at the top of the world beside the bot (or at scratch), reads the row back, asks the id WorldEdit files each under, and clears the row. Needs the permission for /craftengine debug (op has it). A server with a thousand states takes a minute or two; the call reports progress. What is learned is kept for the life of this server process, per server address; learn again after the plugin is reloaded. On a server without CraftEngine the answer says so and nothing is placed.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `scratch` | object | no | Where the scratch row of 64 blocks along x starts; omitted, it is 32 blocks west of the bot at y=319. Whatever is there is overwritten and cleared to air |  |
| `scratch.x` | integer | yes |  |  |
| `scratch.y` | integer | yes |  |  |
| `scratch.z` | integer | yes |  |  |

### list-regions

*fabric and azalea · deadline 5s · read-only · the server answers from what it holds*

List the regions this server is keeping: id, name, box, how many kinds of block, where each came from and how old it is, oldest first. The store holds 16777216 blocks at once and lets the oldest go when a new read needs the room, and everything in it is lost when the server restarts. Needs no bot.

No arguments.

### read-region

*fabric and azalea · deadline 10s · read-only · needs a world · the bot answers*

Read every block in a box: a palette, run-length-encoded runs, and a map of each layer a character to a block while the box is small enough to read as one. One call where a get-block-info per block would be thousands. Every box read is also kept on this server under an id the answer quotes, so show-region can draw any window of it later, write-region can put it down again, and GET /regions/<id>.schem downloads it as a Sponge schematic; the id is lost when the server restarts or when newer reads push it out. A box of at most 64 blocks a side and 32768 blocks is read in one call from where the bot stands, and a part the client has no chunk for is counted in missing rather than failing the call. A box up to 512 blocks a side and 4194304 blocks is walked instead: cut into tiles, the bot teleported with /tp to each tile it does not already hold, and the tiles read and assembled -- which needs the permission to run /tp (op), takes a second or two a tile, and moves the bot, which is put back where it stood afterwards; a walked box is answered with its palette and counts and the id, not a map, since show-region draws windows of it. The runs are ordered y ascending, then z ascending, then x ascending, and that order is binding -- it is not the order Minecraft's own iterators walk, and it is chosen so a horizontal layer stays contiguous: a 64x64 floor is one run rather than sixty-four. A palette entry is the whole block state as /setblock and //set take one -- stone, oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false] -- with the minecraft namespace left off and any other kept; two stairs facing different ways are two entries. The blocks are the ones the client was sent, which a plugin can make different from the ones the server holds: a custom block (CraftEngine, ItemsAdder, Nexo) is sent as a vanilla state such as a note block with some instrument and note. On a CraftEngine server, run learn-custom-blocks once and those states are named as the custom blocks they stand for, as 2025summer:bar_table[facing=east]. A fabric bot holds only the chunks the server sent it, so it reports more missing than an azalea bot for the same one-call box; the invariants hold on both kinds but that number does not, so do not compare the two.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `from` | object | yes | One corner of the box, inclusive |  |
| `from.x` | integer | yes |  |  |
| `from.y` | integer | yes |  |  |
| `from.z` | integer | yes |  |  |
| `to` | object | yes | The other corner, inclusive |  |
| `to.x` | integer | yes |  |  |
| `to.y` | integer | yes |  |  |
| `to.z` | integer | yes |  |  |
| `includeAir` | boolean | no | Whether air counts (default: true). False leaves air out of the palette and out of the runs, for reading what a structure is made of rather than where it sits; the per-layer map is then not drawn, because the runs no longer line up with the box |  |
| `name` | string | no | What to call the kept region, for list-regions; optional | at most 64 characters |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `from` (object) -- The lower corner, whichever corner the caller gave first
  - `x` (integer)
  - `y` (integer)
  - `z` (integer)
- `to` (object) -- The upper corner
  - `x` (integer)
  - `y` (integer)
  - `z` (integer)
- `size` (object) -- How many blocks the box spans on each axis
  - `x` (integer)
  - `y` (integer)
  - `z` (integer)
- `blocks` (integer) -- How many blocks the box holds, which is size.x times size.y times size.z
- `palette[]` (string) -- Block states in order of first appearance, as /setblock takes them, with the minecraft namespace left off -- stone, oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false] -- and any other namespace kept
- `runs[]` (object) -- Run-length encoded in y ascending, then z ascending, then x ascending; block indexes into palette
  - `block` (integer)
  - `count` (integer)
- `missing` (integer) -- Blocks the client holds no chunk for, 0 when the whole box was loaded. Loadedness is decided before airness: an unloaded chunk reads as void_air on a client, so the other order would count these as air, and as nothing at all when includeAir is false
- `outside` (integer) -- Blocks whose y is past the world's build height. Kept apart from missing rather than added to it: one is answered by flying closer, the other by moving the box

### read-selection

*fabric and azalea · deadline 5s · read-only · needs a world · the bot answers*

Read the WorldEdit selection the server is holding for this bot, over the worldedit:cui plugin channel rather than out of chat. WorldEdit describes a selection to any client that draws one -- that channel is what the WorldEditCUI mod exists to receive -- so the bot announces itself on it and answers with the description that comes back: the selection as the plugin holds it, not a line the plugin printed. That makes it independent of the server's language and of how the plugin words its replies, and it works on a server that has turned the plugin's chat feedback off. points holds the corners the plugin named, index 0 for //pos1 and 1 for //pos2, and is empty when nothing is selected; volume is the number of blocks the plugin counts in the selection. supported is false when nothing came back within timeoutMs, which is a server without WorldEdit or FastAsyncWorldEdit, one whose WorldEdit does not send CUI, or a proxy that drops the channel; the selection is then unknown rather than empty, and reading //pos1's reply in chat is what is left. Setting a selection is still run-command with //pos1 and //pos2 -- this only reads one.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `timeoutMs` | integer | no | How long to wait for the server to describe the selection (default: 1000) | 100 to 30000 |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `supported` (boolean) -- The server described the selection. False means it said nothing on the channel within the wait, so the selection is unknown rather than empty
- `shape` (string or null) -- The selector WorldEdit is using: cuboid for the default one, and polygon2d, ellipsoid, sphere, cylinder or convex for the others, whose corners this tool does not report
- `points[]` (object) -- The corners the plugin named, in its own numbering; empty when nothing is selected
  - `index` (integer) -- 0 for the corner //pos1 sets, 1 for //pos2
  - `x` (integer)
  - `y` (integer)
  - `z` (integer)
- `volume` (integer or null) -- Blocks in the selection as the plugin counts them, absent while it is incomplete

### show-region

*fabric and azalea · deadline 5s · read-only · the server answers from what it holds*

Draw a window of a kept region: its palette with counts and a map of each layer a character to a block, the same picture read-region draws for a small box. A window holds at most 4096 blocks, which is one 64x64 layer or a few smaller ones, so a large region is looked at a layer or a room at a time. The corners are world coordinates inside the region; omitted, the whole region is the window, which works while it is small enough. Needs no bot.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `region` | string | yes | The id of a kept region | at most 64 characters |
| `from` | object | no | One corner of the window, inclusive |  |
| `from.x` | integer | yes |  |  |
| `from.y` | integer | yes |  |  |
| `from.z` | integer | yes |  |  |
| `to` | object | no | The other corner, inclusive |  |
| `to.x` | integer | yes |  |  |
| `to.y` | integer | yes |  |  |
| `to.z` | integer | yes |  |  |

### measure-room

*fabric and azalea · deadline 10s · read-only · the server answers from what it holds*

Measure the room around a point in a kept region: how big it is, what its walls and floor and ceiling are finished in, and where the ways in and out are. Give it a point you know is inside -- where the bot stands, or where the furniture is -- and it grows outwards until the walls stop it. What stops it is not a roof, which an open arcade has too, but enclosure: a block carries the room further only when most of its horizon runs into a wall and there is room around it, so a fill cannot escape down a colonnade or slip through a doorway into the next room. If the answer says the walls did not stop it, raise "enclosure" or aim further in. Furniture is entities and no snapshot holds it, so find-entity over the box it answers with is what says whether the room is furnished. Needs no bot.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `region` | string | yes | The id of a kept region | at most 64 characters |
| `at` | object | yes | A point inside the room, which is where the measuring starts |  |
| `at.x` | integer | yes |  |  |
| `at.y` | integer | yes |  |  |
| `at.z` | integer | yes |  |  |
| `enclosure` | number | no | How much of a block's horizon must run into a wall for it to carry the room further, 0 to 1 (default: 0.75). Raise it when a room leaks into an open space | 0 to 1 |
| `clearance` | integer | no | How far the nearest wall must be for a block to carry the room further (default: 2), which is what keeps a doorway from joining two rooms | 1 to 8 |
| `from` | object | no | One corner of the part of the region to measure in, when the region is larger than one measurement takes |  |
| `from.x` | integer | yes |  |  |
| `from.y` | integer | yes |  |  |
| `from.z` | integer | yes |  |  |
| `to` | object | no | The other corner |  |
| `to.x` | integer | yes |  |  |
| `to.y` | integer | yes |  |  |
| `to.z` | integer | yes |  |  |

### verify-region

*fabric and azalea · deadline 30s · untrusted · read-only · the server drives the bot's session*

Ask WorldEdit what is in a box: //size and //distr over the same selection build-region sets, as a table of block, count and share. Needs WorldEdit or FastAsyncWorldEdit on the server. Holds the bot for the whole run, so another build-region or verify-region on the same bot is refused while this one is going: a player has one WorldEdit selection, and two of these interleaving their //pos1 and //pos2 would each measure the box the other selected. The call is read-only all the same -- what it claims is the bot's selection, not the world -- which is why it is not marked exclusive. read-region reads the same box straight from the client with no plugin at all, and is the one to reach for when the plugin is absent or when where the blocks sit matters as much as how many there are.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `from` | object | yes | One corner of the box, inclusive |  |
| `from.x` | integer | yes |  |  |
| `from.y` | integer | yes |  |  |
| `from.z` | integer | yes |  |  |
| `to` | object | yes | The other corner, inclusive |  |
| `to.x` | integer | yes |  |  |
| `to.y` | integer | yes |  |  |
| `to.z` | integer | yes |  |  |

### write-region

*fabric and azalea · deadline 600s · exclusive · untrusted · destructive · the server drives the bot's session*

Put a kept region down in the world, block for block. The region is cut into the fewest boxes of one block state each, and each box is one edit: through WorldEdit (//pos1, //pos2, //set) when the server has WorldEdit or FastAsyncWorldEdit and answers the bot, which also places custom blocks by name and respects the plugin's own limits and region restrictions; otherwise with the game's own /fill, which needs the permission to run it (op), places vanilla blocks only, and is not confined by WorldEdit's restrictions. via picks one explicitly. The bot is teleported tile by tile so the server has the chunks loaded, then put back. By default the region goes back exactly where it was read; give at to put its lower corner somewhere else. Air in the region leaves what is there unless pasteAir is true, in which case it clears. Block entities (a chest's contents, a sign's text) are not carried, since the region does not hold them. Afterwards every tile is read back and compared with the region unless verify is false, and the answer counts the blocks that differ and names the first few. A region comes from read-region, from import-region, or from POST /regions with a schematic file. Thousands of edits take minutes; the call reports progress.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `region` | string | yes | The id of a kept region, as read-region, import-region, list-regions or POST /regions gave it | at most 64 characters |
| `at` | object | no | Where the region's lower corner goes; omitted, the region goes back where it came from |  |
| `at.x` | integer | yes |  |  |
| `at.y` | integer | yes |  |  |
| `at.z` | integer | yes |  |  |
| `pasteAir` | boolean | no | Whether air in the region is put down too, clearing what is there (default: false, so air leaves what is there) |  |
| `verify` | boolean | no | Whether to read every tile back afterwards and compare it with the region (default: true) |  |
| `via` | string: `auto`, `worldedit`, `fill` | no | How to put it down: auto (default) uses WorldEdit when the server answers //pos1 and /fill otherwise; worldedit insists on WorldEdit and is refused without it; fill uses /fill and leaves custom blocks out |  |

## crafting

### can-craft

*fabric and azalea · deadline 5s · read-only · needs a world · the bot answers*

Check whether the bot can craft an item right now.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `itemName` | string | yes | Item to check: exact registry id, with or without minecraft: |  |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `item` (string)
- `craftable` (boolean)
- `hasRecipe` (boolean) -- False when nothing in the game produces the item
- `missing[]` (object) -- For the recipe that needs the least
  - `name` (string)
  - `count` (integer)
- `needsTable` (boolean) -- That recipe needs a crafting table and none is in reach
- `onlyWhatTheBotKnows` (boolean) -- True when the answer covers only the recipes the server has taught this bot. A Minecraft client is sent a recipe as it unlocks it and is never told the whole set, so a fabric bot answers about what it can place right now rather than about what the game has recipes for.

### craft-item

*fabric and azalea · deadline 60s · exclusive · needs a world · the bot answers*

Craft an item, walking to a nearby crafting table when the recipe needs one.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `outputItem` | string | yes | Item to craft: exact registry id, with or without minecraft: |  |
| `amount` | integer | no | How many times to craft (default: 1) | 1 to 64 |

### get-recipe

*fabric and azalea · deadline 5s · untrusted · read-only · needs a world · the bot answers*

Show every recipe for an item together with what the bot still needs.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `itemName` | string | yes | Item to look up: exact registry id, with or without minecraft: |  |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `item` (string)
- `tableInReach` (boolean)
- `stoppedAt` (integer or null) -- Always null here; only the unrestricted scan can run out of room
- `recipes[]` (object) -- The recipe that needs the least first
  - `result` (object)
    - `name` (string)
    - `count` (integer)
  - `ingredients[]` (object)
    - `name` (string)
    - `count` (integer)
  - `missing[]` (object) -- What the inventory is short of; empty means craftable
    - `name` (string)
    - `count` (integer)
  - `requiresTable` (boolean)
- `onlyWhatTheBotKnows` (boolean) -- True when the answer covers only the recipes the server has taught this bot. A Minecraft client is sent a recipe as it unlocks it and is never told the whole set, so a fabric bot answers about what it can place right now rather than about what the game has recipes for.

### list-recipes

*fabric and azalea · deadline 5s · untrusted · read-only · needs a world · the bot answers*

List recipes the bot can craft right now with what it carries. Pass outputItem to inspect one item instead of scanning everything.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `outputItem` | string | no | Restrict the list to this item: exact registry id, with or without minecraft: |  |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `item` (string or null) -- The item the caller asked about, or null for everything craftable now
- `tableInReach` (boolean)
- `stoppedAt` (integer or null) -- The cap the scan stopped at, or null when it listed everything
- `recipes[]` (object)
  - `result` (object)
    - `name` (string)
    - `count` (integer)
  - `ingredients[]` (object)
    - `name` (string)
    - `count` (integer)
  - `missing[]` (object) -- What the inventory is short of; empty means craftable
    - `name` (string)
    - `count` (integer)
  - `requiresTable` (boolean)
- `onlyWhatTheBotKnows` (boolean) -- True when the answer covers only the recipes the server has taught this bot. A Minecraft client is sent a recipe as it unlocks it and is never told the whole set, so a fabric bot answers about what it can place right now rather than about what the game has recipes for.

## screen

### click-chat

*fabric and azalea · deadline 10s · exclusive · needs a world · the bot answers*

Press something a server wrote in chat: a quest's choices, a shop's items, a menu. Matches the text of the clickable part -- "[Accept]" -- exactly first, then by substring, newest line first. Only clicks that run a command or open a dialog can be pressed; one that would open a URL or a file is refused.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `match` | string | yes | Text of the clickable part to press, for example "[Accept]" |  |

### press-dialog-button

*fabric and azalea · deadline 10s · exclusive · needs a world · the bot answers*

Press a button on the dialog the server has opened. Identify it by its label. Reading a dialog is not enough to carry a conversation forward; this is what answers it.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `label` | string | yes | Button label, or part of it |  |

### read-book

*fabric and azalea · deadline 5s · untrusted · read-only · needs a world · the bot answers*

Read every page of the book that is open, whether it is held or on a lectern. A server with something long to say says it in a book -- a quest log, a rulebook, the guide handed out on join -- and the client draws one page at a time, so a screenshot shows one page and loses the components the rest were written with.

No arguments beyond `bot`.

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `source` (string) -- Where the book is being read from: "hand" or "lectern"
- `title` (string or null) -- Null until the book has been signed
- `author` (string or null)
- `generation` (integer or null) -- 0 original, 1 copy, 2 copy of a copy, 3 tattered
- `page` (integer) -- The page the client is showing, counting from 1
- `pages[]` (string) -- Every page, in order
- `pageComponents` (array or null) -- The components the pages were written as, in the same order. Null from a bot built before they were sent

### screenshot

*fabric only · deadline 30s · untrusted · read-only · the bot answers*

Capture what the bot actually sees, as the client renders it. This is the only way to check a HUD drawn in custom fonts, a model, or a layout that overlaps; everything else reports text and leaves the look to guesswork.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `width` | integer | no | Image width (default: 854) | 256 to 1920 |
| `height` | integer | no | Image height (default: 480) | 144 to 1080 |
| `hud` | boolean | no | Include the HUD and any open screen (default: true) |  |

### set-dialog-input

*fabric and azalea · deadline 5s · exclusive · untrusted · needs a world · the bot answers*

Set a checkbox, a cycle or a slider on the dialog the server has opened, before pressing the button that sends it. The value goes into the dialog's own control, so the button sends exactly what a player who had clicked it would. Text fields are type-text's. The reply says what the input holds now and what it held before.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `key` | string | yes | The input's key, as the dialog names it: what its action's $(key) template reads |  |
| `value` | boolean or number or string | yes | A checkbox takes true or false. A cycle takes an option's id, or the text it is shown as. A slider takes a number inside its range, which moves to the nearest step the slider has |  |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `key` (string) -- the input that was set
- `type` (string: `boolean`, `single_option`, `number_range`) -- the kind of input, as the dialog's definition names it without its namespace
- `label` (string or null) -- the label the dialog shows beside the input, flattened; null when it has none
- `labelComponent` (object or array or string or null) -- The component that label was written as, as Minecraft's own JSON. Null when there is none.
- `value` (boolean or number or string) -- what the input holds now: true or false, an option's id, or the slider's number
- `previous` (boolean or number or string) -- what it held before
- `display` (string or null) -- for a cycle, the text the chosen option is shown as; null otherwise
- `requested` (number or null) -- for a slider, the number asked for when it is not on one of the slider's steps and the value moved to the nearest; null otherwise

### type-text

*fabric and azalea · deadline 10s · exclusive · needs a world · the bot answers*

Type into a text field on the screen that is open: a dialog's input, an anvil's name box, a sign being edited, a page of a book. A dialog's inputs are not in the packet that opened it, so this is the only way to fill one in before pressing a button. Characters go through the client's own keyboard path, so a field's length limit and the characters it refuses apply as they would to a player. On a sign the editor is closed afterwards, because closing it is what sends the sign to the server; a book stays open, because writing one takes a call per page and then a name.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `text` | string | yes | Text to type. A newline starts the next line in a multi-line field and on a sign; a one-line field refuses it. |  |
| `field` | string | no | Which field to type into, by its label. A number, as a string, e.g. "2", picks by position; on a sign editor it picks the line, 1 to 4, and in a book it picks the page, which is created if the book does not reach it yet. Omit it when the screen has exactly one field. |  |
| `replace` | boolean | no | Clear the field first, rather than typing onto the end of what is already there (default: true) |  |

## slot

### click-slot

*fabric and azalea · deadline 5s · exclusive · untrusted · needs a world · the bot answers*

Click one slot of the window that is currently open, or outside it to drop what the cursor holds, or press a key over it the way a player does: a number key, the offhand key, drop, a double-click or a creative middle-click.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `slot` | integer | no | Slot number as listed by the window contents. Leave it out only for a click outside the window | at least 0 |
| `outside` | boolean | no | Click outside the window instead of on a slot, which drops what the cursor holds: the whole stack with the left button, one item with the right. Only a plain click takes it, and it takes no slot (default: false) |  |
| `button` | string: `left`, `right` | no | Which mouse button to press for a click (default: 'left') |  |
| `shift` | boolean | no | Shift-click, which moves the whole stack across the window. Only a click takes it (default: false) |  |
| `mode` | string: `click`, `swap-hotbar`, `swap-offhand`, `throw-one`, `throw-stack`, `pickup-all`, `clone` | no | What the player does to the slot (default: 'click'). 'click' is a mouse click, shaped by button and shift. 'swap-hotbar' is a number key, trading the slot with the hotbar slot named by hotbar; 'swap-offhand' is the offhand key. 'throw-one' is the drop key and 'throw-stack' the same with control held, both straight from the slot without the cursor. 'pickup-all' is the second half of a double-click: pick a stack up with a click first, and pickup-all on the slot it came from gathers every matching stack in the window onto the cursor. 'clone' is a creative middle-click, which puts a full stack of what the slot holds on an empty cursor. |  |
| `hotbar` | integer | no | The number key for swap-hotbar, 1 being the leftmost hotbar slot. Only swap-hotbar takes it. (1-based: the number key, not the slot index press-input's slot uses) | 1 to 9 |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `slot` (integer or null) -- The slot clicked, null for a click outside the window
- `outside` (boolean)
- `button` (string: `left`, `right`)
- `shift` (boolean)
- `before` (object or null) -- What the slot held before the click, null when it was empty. For a click outside the window, what the cursor held
  - `name` (string)
  - `count` (integer)
  - `label` (string or null) -- The custom name the server gave it, if any
  - `lore[]` (string)
  - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
  - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
- `after` (object or null) -- What the slot holds now, null when the click emptied it. For a click outside the window, what the cursor holds
  - `name` (string)
  - `count` (integer)
  - `label` (string or null) -- The custom name the server gave it, if any
  - `lore[]` (string)
  - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
  - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
- `cursor` (object or null) -- What the cursor holds after the click, null when it holds nothing
  - `name` (string)
  - `count` (integer)
  - `label` (string or null) -- The custom name the server gave it, if any
  - `lore[]` (string)
  - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
  - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
- `mode` (string: `click`, `swap-hotbar`, `swap-offhand`, `throw-one`, `throw-stack`, `pickup-all`, `clone`)
- `hotbar` (integer or null)
- `swapped` (object or null) -- The other side of a swap: the hotbar slot or the offhand, which need not be in the window at all. Null for every mode that is not a swap.
  - `before` (object or null) -- What it held before the swap, null when it was empty
    - `name` (string)
    - `count` (integer)
    - `label` (string or null) -- The custom name the server gave it, if any
    - `lore[]` (string)
    - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
    - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
  - `after` (object or null) -- What it holds now, null when the swap emptied it
    - `name` (string)
    - `count` (integer)
    - `label` (string or null) -- The custom name the server gave it, if any
    - `lore[]` (string)
    - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
    - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
- `window` (object or null) -- Null when the server answered in the window that was clicked. Otherwise the server answered with another window, as a plugin's menu does when a click opens the next screen or opens its own screen again, or it closed the window: then the slots of the window clicked are not described, because it is gone, and after is null.
  - `closed` (boolean) -- True when the server closed the window rather than opening another
  - `title` (string or null) -- The title of the window now open, null when the server closed it
  - `titleComponent` (object or array or string or null) -- The component the title was written as, as Minecraft's own JSON. Null when the server closed the window.

### drag-slots

*fabric and azalea · deadline 5s · exclusive · untrusted · needs a world · the bot answers*

Drag the stack on the cursor across several slots of the open window, the way a player spreads items by holding a mouse button down.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `slots` | array of integer | yes | The slots to drag across, in the order the mouse passes over them. A slot the cursor has too few items left for is passed over, the way the client does. | 1 to any number of items |
| `button` | string: `left`, `right`, `middle` | no | Which mouse button drags (default: 'left'). 'left' shares the stack evenly, 'right' puts one in each slot, 'middle' fills each slot with a full stack and needs creative. |  |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `button` (string: `left`, `right`, `middle`)
- `slots[]` (object) -- Every slot that was asked for, in the order given, including the ones the drag passed over
  - `slot` (integer)
  - `before` (object or null) -- What the slot held before the drag, null when it was empty
    - `name` (string)
    - `count` (integer)
    - `label` (string or null) -- The custom name the server gave it, if any
    - `lore[]` (string)
    - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
    - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
  - `after` (object or null) -- What the slot holds now, null when it is empty
    - `name` (string)
    - `count` (integer)
    - `label` (string or null) -- The custom name the server gave it, if any
    - `lore[]` (string)
    - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
    - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
- `carried` (object or null) -- What the cursor held before the drag, null when it held nothing
  - `name` (string)
  - `count` (integer)
  - `label` (string or null) -- The custom name the server gave it, if any
  - `lore[]` (string)
  - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
  - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
- `cursor` (object or null) -- What the cursor holds after the drag, null when it holds nothing
  - `name` (string)
  - `count` (integer)
  - `label` (string or null) -- The custom name the server gave it, if any
  - `lore[]` (string)
  - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
  - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
- `window` (object or null) -- Null when the server answered in the window that was clicked. Otherwise the server answered with another window, as a plugin's menu does when a click opens the next screen or opens its own screen again, or it closed the window: then the slots of the window clicked are not described, because it is gone, and after is null.
  - `closed` (boolean) -- True when the server closed the window rather than opening another
  - `title` (string or null) -- The title of the window now open, null when the server closed it
  - `titleComponent` (object or array or string or null) -- The component the title was written as, as Minecraft's own JSON. Null when the server closed the window.

### drop-held-item

*fabric and azalea · deadline 5s · exclusive · untrusted · needs a world · the bot answers*

In the open window: drops the cursor stack or a slot. The window stays open. To throw from the hand use press-input drop.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `slot` | integer | no | Slot to empty onto the ground. Omit it to drop what the cursor holds. | at least 0 |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `slot` (integer or null) -- The slot that was emptied, null when the cursor was
- `dropped` (object or null) -- What went on the ground, null when there was nothing to drop
  - `name` (string)
  - `count` (integer)
  - `label` (string or null) -- The custom name the server gave it, if any
  - `lore[]` (string)
  - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
  - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.

### hover-slot

*fabric only · deadline 30s · exclusive · untrusted · needs a world · the bot answers*

Hover the cursor over one slot of the open window and capture the frame with its tooltip drawn, the way a player sees it. The tooltip's lines come back as text beside the image, fonts and glyphs included, so a name or lore drawn in the resource pack's own font can be checked both ways. read-window shows a slot's label and lore; this shows the whole tooltip the client builds -- display name, enchantments, attributes, hidden lines -- and how it is drawn.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `slot` | integer | yes | Slot number as listed by the window contents | at least 0 |
| `width` | integer | no | Image width (default: 854) | 256 to 1920 |
| `height` | integer | no | Image height (default: 480) | 144 to 1080 |
| `image` | boolean | no | Also capture the frame (default: true). false answers with the tooltip's lines only, which is cheaper when only the words are in question |  |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `slot` (integer) -- The slot hovered
- `item` (object or null) -- What the slot holds, null when it is empty
  - `name` (string)
  - `count` (integer)
  - `label` (string or null) -- The custom name the server gave it, if any
  - `lore[]` (string)
  - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
  - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
- `cursor` (object or null) -- What the cursor holds, null when it holds nothing. A held stack is what hides the tooltip (hidden 'cursor'), so the renderer names it
  - `name` (string)
  - `count` (integer)
  - `label` (string or null) -- The custom name the server gave it, if any
  - `lore[]` (string)
  - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
  - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
- `tooltip` (object or null) -- The lines of the tooltip the client builds for what the slot holds, as the screen draws them: the display name first, then whatever the item shows. Filled whenever the slot holds something, even when hidden says the tooltip was not drawn, so the words can still be read; null for an empty slot
  - `lines[]` (string) -- Plain text of each line, in the order drawn
  - `lineComponents[]` (array) -- The components the lines were written as, as Minecraft's own JSON, in the same order as lines. Read by position, so a shorter list simply leaves the rest read the way lines already were.
- `hidden` (string or null: `null`, `empty`, `inactive`, `cursor`) -- Null when the tooltip is drawn in the frame. 'empty': the slot holds nothing, so there is no tooltip. 'inactive': the screen did not take the hover, because the slot is inactive or covered by another creative tab or the recipe book, so no tooltip is drawn. 'cursor': the cursor holds a stack and the client hides the tooltip while it does.
- `frame` (object or null) -- The size of the frame attached beside the text, null when image was false and none was captured. The renderer never sees the image itself, so this is what says one is attached
  - `width` (integer)
  - `height` (integer)

### select-bundle-item

*fabric only · deadline 5s · exclusive · untrusted · needs a world · the bot answers*

Choose which item a bundle in the open window gives up next, as scrolling over its tooltip does. Nothing comes out yet: a right click on the bundle with an empty cursor (click-slot with button 'right') takes the chosen item out, and without a choice it takes the first. A left click, shift-click or number key on the bundle clears the choice. The answer lists what the bundle holds, numbered from 1, and an item that is not in it is refused with that list, so read-window's slot and this are all it takes to see inside one.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `slot` | integer | yes | The window slot the bundle is in, as read-window numbers it | at least 0 |
| `item` | string | yes | The item to choose: its number in the bundle counting from 1, as a string, e.g. "2", or its name. A name that more than one stack in the bundle answers to is refused with their numbers. |  |

## window

### close-window

*fabric and azalea · deadline 5s · untrusted · needs a world · the bot answers*

Close whatever the bot has open -- a container, a book, a sign editor, a dialog -- the way Escape would. Answers that there was none rather than failing, because asking to close nothing is a no-op and not a mistake. A screen that does not close on Escape, such as the death screen, is refused.

No arguments beyond `bot`.

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `closed` (string or null) -- the title of the window that was closed, null when none was open
- `closedComponent` (object or array or string or null) -- The component that title was written as, as Minecraft's own JSON. The flattening is done from this when it is here, so the rule lives in one place instead of once per kind of bot. Null when there is none.
- `screen` (string or null) -- What was closed when it was not a container window: "book", "lectern", "book editor", "sign editor", "dialog", or the screen's class name. Null for a container.

### open-container

*fabric and azalea · deadline 30s · exclusive · untrusted · needs a world · the bot answers*

Open the chest-like block at a position, walking to it first when out of reach, and list what it holds.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `x` | number | yes | X coordinate |  |
| `y` | number | yes | Y coordinate |  |
| `z` | number | yes | Z coordinate |  |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `title` (string) -- Plain text, with colour codes and glyphs already taken out
- `type` (integer or string) -- Registry id on a modern server, a number on an older one
- `slotCount` (integer)
- `containerSlots[]` (integer) -- First and last slot of the container half
- `inventorySlots[]` (integer) -- First and last slot of the player's own inventory
- `filled[]` (object) -- Only the slots that hold something, in slot order
  - `slot` (integer)
  - `name` (string)
  - `count` (integer)
  - `label` (string or null) -- The custom name the server gave it, if any
  - `lore[]` (string)
  - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
  - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
- `titleComponent` (object or array or string or null) -- The component the window's title was written as, as Minecraft's own JSON. The flattening is done from this when it is here, so the rule lives in one place instead of once per kind of bot. Null when there is none.

### open-inventory

*fabric only · deadline 5s · exclusive · needs a world · the bot answers*

Open the bot's own inventory, as pressing E does, so click-slot and drag-slots reach the crafting grid, armour, offhand and the inventory itself. In creative this is the creative inventory, opened on the tab asked for; its clicks go through the creative screen the way a player's do, and clicking an item on a tab other than the inventory takes a stack of it. Riding something with an inventory of its own, the server is asked instead, and wait-for-window follows.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `tab` | string | no | Creative only: the tab to open, by its name, such as "Building Blocks" or "Search". Defaults to the inventory tab, whose slots are the bot's own inventory. |  |

### press-container-button

*fabric and azalea · deadline 5s · exclusive · needs a world · the bot answers*

Press a button that an open menu draws for itself rather than keeps in a slot: one of an enchanting table's three offers, a stonecutter result, a loom pattern, or a lectern's page turns. Choose it by name; read-container-options lists the names, and a name that matches nothing is refused with the list. click-slot cannot reach these, and neither can press-dialog-button.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `option` | string | no | What to press, by name: an enchantment as the table shows it ("Unbreaking III"), a stonecutter result ("stone_brick_slab" or "Stone Brick Slab"), a loom pattern, or on a lectern "previous page", "next page", "page 3" or "take book". Part of a name is enough when only one option contains it. Give this or button, not both. |  |
| `button` | integer | no | The raw button number, only for an option that has no name; give option otherwise. Its meaning is the menu's own and differs on every one. | at least 0 |

### read-container-options

*fabric and azalea · deadline 5s · untrusted · read-only · needs a world · the bot answers*

Read what the open menu offers to press rather than click: an enchanting table's three offers with their cost, a stonecutter's results, a loom's patterns, a lectern's page turns, a beacon's effects with the pyramid levels each needs. read-window shows slots and none of these are in one. Answers that nothing is open rather than failing.

No arguments beyond `bot`.

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `window` (object or null) -- null when nothing is open, which is a state and not a failure
  - `title` (string) -- Plain text, with colour codes and glyphs already taken out
  - `titleComponent` (object or array or string or null) -- The component the window's title was written as, as Minecraft's own JSON. The flattening is done from this when it is here, so the rule lives in one place instead of once per kind of bot. Null when there is none.
  - `type` (string) -- Registry id of the menu
  - `options[]` (object) -- Everything there is to press, in button order. Empty on a menu that has nothing but slots.
    - `button` (integer) -- The number the menu reads this as. press-container-button takes it when a name will not do.
    - `name` (string or null) -- Registry id without its namespace: the enchantment, the result item, the banner pattern. On a lectern, the words that press it. Null when the table hides which enchantment it is offering.
    - `label` (string or null) -- What the game calls it in the bot's language, when that is not the name: "Unbreaking III", "Stone Brick Slab"
    - `count` (integer or null) -- How many a stonecutter result makes; null on every other menu
    - `levels` (integer or null) -- Experience levels an enchantment offer asks for; null on every other menu
    - `lapis` (integer or null) -- Lapis lazuli an enchantment offer uses; null on every other menu
    - `available` (boolean) -- Whether pressing it now would do anything: an offer the bot cannot pay for, or a page turn past the end of the book, is false
    - `selected` (boolean) -- Whether this is the stonecutter result or loom pattern already chosen
  - `page` (integer or null) -- The page a lectern is open at, counting from 1; null on every other menu
  - `pageCount` (integer or null) -- How many pages the lectern's book has; null on every other menu
  - `beacon` (object or null) -- What a beacon offers; null on every other menu. Its effects are not buttons: set-beacon-effects sends a primary and a secondary together.
    - `levels` (integer) -- How many levels of pyramid the beacon stands on, 0 to 4, as the server last counted
    - `payment` (string or null) -- The item in the payment slot, slot 0, or null when it is empty and nothing can be set
    - `effects[]` (object) -- Every effect button in the screen's order: the primary rows, regeneration, then the primary again as the level II secondary once a primary is set

### read-trades

*fabric and azalea · deadline 5s · untrusted · read-only · needs a world · the bot answers*

Read every trade on the trading screen that is open: what each one costs, what it gives, how many times it can still be made, and the villager's level and experience. The trades are not in any slot, so read-window cannot show them, and they arrive a moment after the screen opens: a screen read in the same tick lists none yet.

No arguments beyond `bot`.

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `title` (string)
- `titleComponent` (object or array or string or null) -- The component the title was written as, as Minecraft's own JSON
- `level` (integer) -- 1 novice to 5 master. Only shown on the screen when showProgressBar is true: a wandering trader is sent a level it has no use for.
- `xp` (integer) -- The villager's experience
- `showProgressBar` (boolean) -- Whether this merchant levels up at all
- `canRestock` (boolean) -- Whether a trade that is out of stock comes back
- `trades[]` (object) -- Every trade, in the order the screen lists them
  - `number` (integer) -- The trade's place in the list, counting from 1. select-trade takes this.
  - `costA` (object or null) -- The first price, with demand and the player's reputation already applied: what the payment slot takes
    - `name` (string)
    - `count` (integer)
    - `label` (string or null) -- The custom name the server gave it, if any
    - `lore[]` (string)
    - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
    - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
  - `baseCountA` (integer) -- How many of costA the trade asks before demand and reputation. Fewer in costA is a discount, more is a price pushed up by demand.
  - `costB` (object or null) -- The second price, null when the trade asks for one thing
    - `name` (string)
    - `count` (integer)
    - `label` (string or null) -- The custom name the server gave it, if any
    - `lore[]` (string)
    - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
    - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
  - `result` (object or null) -- What the trade gives
    - `name` (string)
    - `count` (integer)
    - `label` (string or null) -- The custom name the server gave it, if any
    - `lore[]` (string)
    - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
    - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
  - `enchantments[]` (object) -- The enchantments the result carries or, on a book, stores. Two enchanted books are otherwise the same item.
    - `name` (string)
    - `level` (integer)
  - `uses` (integer)
  - `maxUses` (integer)
  - `outOfStock` (boolean) -- uses has reached maxUses. The trade stays in the list and gives nothing until the villager restocks.
  - `xp` (integer) -- Experience the villager gains each time the trade is made

### read-window

*fabric and azalea · deadline 5s · untrusted · read-only · needs a world · the bot answers*

Read the window that is currently open: its title, its type, and every slot that has something in it. Answers that nothing is open rather than failing, because that is a state worth reporting.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `part` | string: `container`, `inventory`, `all` | no | Which half to list (default: 'all'). 'container' is the menu's own slots, 'inventory' the player's below it; a plugin menu fills a page with panes, so the container half alone is the cheaper read after a click |  |
| `lore` | boolean | no | Include each stack's lore lines (default: true). false lists names and counts only |  |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `window` (object or null) -- null when nothing is open, which is a state and not a failure
  - `title` (string) -- Plain text, with colour codes and glyphs already taken out
  - `type` (integer or string) -- Registry id on a modern server, a number on an older one
  - `slotCount` (integer)
  - `containerSlots[]` (integer) -- First and last slot of the container half
  - `inventorySlots[]` (integer) -- First and last slot of the player's own inventory
  - `filled[]` (object) -- Only the slots that hold something, in slot order
    - `slot` (integer)
    - `name` (string)
    - `count` (integer)
    - `label` (string or null) -- The custom name the server gave it, if any
    - `lore[]` (string)
    - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
    - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
  - `titleComponent` (object or array or string or null) -- The component the window's title was written as, as Minecraft's own JSON. The flattening is done from this when it is here, so the rule lives in one place instead of once per kind of bot. Null when there is none.

### select-trade

*fabric and azalea · deadline 5s · exclusive · untrusted · needs a world · the bot answers*

Pick a trade on the trading screen that is open, as pressing it in the list does. The price is moved out of the inventory into the payment slots and the result slot fills if the trade can be made. Picking is not trading: take the result from slot 2 with click-slot afterwards -- a plain click trades once, a shift-click trades as many times as the payment slots cover.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `trade` | string | yes | The trade to pick: its number in read-trades, counting from 1, as a string, e.g. "2", or the name of what it gives. A name that more than one trade gives is refused with their numbers. |  |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `trade` (object)
  - `number` (integer) -- The trade's place in the list, counting from 1. select-trade takes this.
  - `costA` (object or null) -- The first price, with demand and the player's reputation already applied: what the payment slot takes
    - `name` (string)
    - `count` (integer)
    - `label` (string or null) -- The custom name the server gave it, if any
    - `lore[]` (string)
    - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
    - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
  - `baseCountA` (integer) -- How many of costA the trade asks before demand and reputation. Fewer in costA is a discount, more is a price pushed up by demand.
  - `costB` (object or null) -- The second price, null when the trade asks for one thing
    - `name` (string)
    - `count` (integer)
    - `label` (string or null) -- The custom name the server gave it, if any
    - `lore[]` (string)
    - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
    - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
  - `result` (object or null) -- What the trade gives
    - `name` (string)
    - `count` (integer)
    - `label` (string or null) -- The custom name the server gave it, if any
    - `lore[]` (string)
    - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
    - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
  - `enchantments[]` (object) -- The enchantments the result carries or, on a book, stores. Two enchanted books are otherwise the same item.
    - `name` (string)
    - `level` (integer)
  - `uses` (integer)
  - `maxUses` (integer)
  - `outOfStock` (boolean) -- uses has reached maxUses. The trade stays in the list and gives nothing until the villager restocks.
  - `xp` (integer) -- Experience the villager gains each time the trade is made
- `paymentA` (object or null) -- What payment slot 0 holds after picking, null when the inventory had none of the price to move
  - `name` (string)
  - `count` (integer)
  - `label` (string or null) -- The custom name the server gave it, if any
  - `lore[]` (string)
  - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
  - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
- `paymentB` (object or null) -- What payment slot 1 holds after picking, null when it is empty
  - `name` (string)
  - `count` (integer)
  - `label` (string or null) -- The custom name the server gave it, if any
  - `lore[]` (string)
  - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
  - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
- `result` (object or null) -- What result slot 2 holds after picking, null when the trade cannot be made: out of stock, or the payment slots do not cover the price
  - `name` (string)
  - `count` (integer)
  - `label` (string or null) -- The custom name the server gave it, if any
  - `lore[]` (string)
  - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
  - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.

### set-beacon-effects

*fabric and azalea · deadline 5s · exclusive · needs a world · the bot answers*

Choose and confirm the effects of the beacon whose window is open, as its confirm button does: a primary effect and, on a pyramid of four levels, a secondary one. The payment slot (slot 0) has to hold an iron ingot, gold ingot, emerald, diamond or netherite ingot, and one is used up. A choice the beacon would not take -- an effect its pyramid is too low for, a secondary that is neither regeneration nor the primary again, or nothing to pay with -- is refused with the reason before anything is sent. read-container-options lists the effects and what each needs. The window closes afterwards, as it does for a player.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `primary` | string | yes | The primary effect, by id or by the name the screen shows: speed or haste from a pyramid of one level, resistance or jump_boost from two, strength from three. |  |
| `secondary` | string | no | The secondary effect, which needs a pyramid of four levels: regeneration, or the primary effect again to make it level II. Leave it out for none. |  |

### wait-for-window

*fabric and azalea · deadline 30s · untrusted · read-only · needs a world · the bot answers*

Wait until a GUI window opens and return its contents. Returns straight away if a matching window is already open.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `titlePattern` | string | no | Regular expression in the subset both bots share: no lookaround or backreferences; (?i) for case-insensitive. It matches when it matches the title as read-window shows it, font labels such as [gui/header] included, or the title's plain text (default: any window) | at most 256 characters |
| `timeoutMs` | integer | no | How long to wait (default: 10000) | 100 to 120000 |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `titlePattern` (string or null) -- The pattern the caller waited on, or null for any window
- `timeoutMs` (integer)
- `window` (object or null) -- Null when nothing matching opened in time
  - `title` (string) -- Plain text, with colour codes and glyphs already taken out
  - `type` (integer or string) -- Registry id on a modern server, a number on an older one
  - `slotCount` (integer)
  - `containerSlots[]` (integer) -- First and last slot of the container half
  - `inventorySlots[]` (integer) -- First and last slot of the player's own inventory
  - `filled[]` (object) -- Only the slots that hold something, in slot order
    - `slot` (integer)
    - `name` (string)
    - `count` (integer)
    - `label` (string or null) -- The custom name the server gave it, if any
    - `lore[]` (string)
    - `labelComponent` (object or array or string or null) -- The component the custom name was written as, as Minecraft's own JSON. A plugin draws a screen out of custom-named items and writes those names in the resource pack's own glyphs, so the flattening is done from this when it is here and the rule lives in one place instead of once per kind of bot. Null when the item has no custom name.
    - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
  - `titleComponent` (object or array or string or null) -- The component the window's title was written as, as Minecraft's own JSON. The flattening is done from this when it is here, so the rule lives in one place instead of once per kind of bot. Null when there is none.

## server

### complete-command

*fabric and azalea · deadline 5s · untrusted · read-only · needs a world · the bot answers*

Ask the server what completes a partial command, which is how to find out what a plugin offers without being told. "/" lists every command the bot may run.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `text` | string | yes | The partial command, for example "/is " | at most 256 characters |
| `timeoutMs` | integer | no | How long to wait for the answer (default: 5000) | 100 to 30000 |
| `limit` | integer | no | How many completions to show (default: 60) | 1 to 500 |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `text` (string) -- The partial command that was completed
- `total` (integer) -- How many the server offered, before the limit was applied
- `completions[]` (object)
  - `name` (string)
  - `tooltip` (string or null)

### get-world-state

*fabric and azalea · deadline 5s · read-only · needs a world · the bot answers*

Report the in-game time and weather, for a feature that only happens at a certain time of day. This is the clock the world ticks on, not whatever a server may draw on its HUD.

No arguments beyond `bot`.

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `timeOfDay` (integer) -- Ticks into the day, 0 being sunrise
- `day` (integer)
- `moonPhase` (integer)
- `isDay` (boolean)
- `weather` (string: `clear`, `rain`, `thunder`)
- `doDaylightCycle` (boolean or null) -- null when the bot cannot tell. A Minecraft client is not told the game rule and only sees whether the clock moved.

### run-command

*fabric and azalea · deadline 5s · untrusted · destructive · needs a world · the bot answers and the server adds what its feeds caught meanwhile*

Runs with whatever permissions the bot has on the server; on the development cluster every player is op. Run a slash command as the bot and return what the server sent back: chat, or a dialog or title it opened when there was no chat.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `command` | string | yes | Command with or without the leading slash | at most 256 characters |
| `collectMs` | integer | no | How long to collect the reply before returning (default: 1000) | 0 to 10000 |

### switch-server

*fabric and azalea · deadline 30s · exclusive · needs a world · the bot answers and the server adds what its feeds caught meanwhile*

Send the bot to another backend server through the proxy and wait until it spawns there.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `target` | string | yes | Backend server name the proxy knows | at most 64 characters |
| `timeoutMs` | integer | no | Give up after this long (default: 30000) | 1000 to 120000 |

### wait-ticks

*fabric and azalea · deadline 60s · read-only · needs a world · the bot answers*

Wait a number of server ticks so the server has time to apply a change.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `ticks` | integer | yes | How many ticks to wait (20 ticks is one second) | 1 to 400 |

## sessions

### detect-gamemode

*fabric and azalea · deadline 5s · read-only · the server answers from what it holds*

Report the game mode the server assigned to the bot. The same value get-bot-status and get-player-state report.

No arguments beyond `bot`.

### get-bot-status

*fabric and azalea · deadline 5s · read-only · the server answers from what it holds*

Report connection state, position and health for one bot, and whether it is dead. Answers from what the server last heard without asking the bot, so it works when the bot is stuck, kicked or not answering; it is what every failure message points at.

No arguments beyond `bot`.

### join-server

*fabric and azalea · deadline 180s · the server drives the bot's session*

Send a bot into a Minecraft server and wait until it has spawned. A bot already running under this name is used as it is; otherwise one is started, which in a cluster means the operator creates it. A fabric client takes about a minute to link, longer than one call waits: a call that runs out of patience says the bot is still starting, and calling join-server again with the same arguments waits for that bot rather than starting another. A failure says how far it got, because a bot that never started and a server that refused the login have different fixes.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `host` | string | yes | Server host or Kubernetes service name to connect to |  |
| `port` | integer | no | Server port (default: 25565) | 1 to 65535 |
| `username` | string | no | In-game username (default: the bot name) |  |
| `owner` | string | no | Free-form label recording who asked for this bot. Omitted when not given. |  |
| `kind` | string: `fabric`, `azalea` | no | Which kind of bot to start when none by this name is running. "fabric" is a real Minecraft client: every tool, a screenshot, dialogs, books and the creative inventory, at about 1.7GiB and a minute and a half to link. "azalea" is a headless client in a few megabytes that joins in under a second, for many bots at once or a fast loop, and runs only the tools whose descriptions do not say otherwise. (default: fabric) |  |
| `minecraftVersion` | string | no | Minecraft version for a bot that has to be started. Both kinds are built for one version. (default: 26.1.2) | matching `^[0-9]+\.[0-9]+(\.[0-9]+)?$` |
| `timeoutMs` | integer | no | How long to wait for the spawn once the bot is linked (default: 180000). A join the server holds in configuration past it -- a plugin waiting on a resource pack, a transfer that never comes -- fails as a spawn that never came, and the bot leaves that connection | 1000 to 600000 |

### leave-server

*fabric and azalea · deadline 5s · destructive · the server drives the bot's session*

Take a bot out of the world it is in. The process stays up and linked, ready for the next join-server, so leaving and rejoining is cheap.

No arguments beyond `bot`.

### list-bots

*fabric and azalea · deadline 5s · read-only · the server answers from what it holds*

List every bot this server currently holds, including bots joined by other agents.

No arguments.

### restart-bot

*fabric and azalea · deadline 90s · destructive · the server drives the bot's session*

Put a bot back on the server it is on, under the same name. Use it after redeploying a plugin: the bot is still connected and still holding whatever the previous build gave it, and a stale inventory or scoreboard looks exactly like a bug in the new one.

No arguments beyond `bot`.

## block

### dig-block

*fabric and azalea · deadline 60s · exclusive · destructive · needs a world · the bot answers*

Break the block at a position, walking to it first when out of reach.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `x` | number | yes | X coordinate |  |
| `y` | number | yes | Y coordinate |  |
| `z` | number | yes | Z coordinate |  |

### find-blocks

*fabric and azalea · deadline 5s · read-only · needs a world · the bot answers*

Find nearby blocks of a given type. The block is the one the client was sent, which a plugin can make different from the one the server holds: a custom block (CraftEngine, ItemsAdder, Nexo) is usually sent as a vanilla block such as stone or a note block. When what the server holds matters -- a quest objective counting a block, say -- ask the server, for example run-command "/execute if block X Y Z minecraft:stone".

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `blockType` | string | yes | Block to find: exact registry id, with or without minecraft:, for example oak_log |  |
| `maxDistance` | number | no | Search radius (default: 16) | at least 1 |
| `count` | integer | no | How many to return (default: 1, clamped to 256) | 1 to 256 |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `blockType` (string)
- `maxDistance` (number)
- `positions[]` (object) -- Nearest first, as the search returned them
  - `x` (integer)
  - `y` (integer)
  - `z` (integer)

### get-block-info

*fabric and azalea · deadline 5s · read-only · needs a world · the bot answers*

Describe the block at a position. The block is the one the client was sent, which a plugin can make different from the one the server holds: a custom block (CraftEngine, ItemsAdder, Nexo) is usually sent as a vanilla block such as stone or a note block. When what the server holds matters -- a quest objective counting a block, say -- ask the server, for example run-command "/execute if block X Y Z minecraft:stone".

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `x` | number | yes | X coordinate |  |
| `y` | number | yes | Y coordinate |  |
| `z` | number | yes | Z coordinate |  |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `position` (object) -- Where the caller asked about
  - `x` (integer)
  - `y` (integer)
  - `z` (integer)
- `block` (object or null) -- Null when the position is outside the loaded chunks
  - `name` (string)
  - `type` (integer)
  - `position` (object)
    - `x` (integer)
    - `y` (integer)
    - `z` (integer)

### get-target-block

*fabric only · deadline 5s · untrusted · read-only · needs a world · the bot answers*

What the bot is looking at: the block under its crosshair, which face of it, or the entity in the way. A screenshot shows what a place looks like and carries no coordinates; this is how to find out where something is in order to build, dig or place there. Point with look-at first.

No arguments beyond `bot`.

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `hit` (string: `block`, `entity`, `nothing`) -- What the crosshair is on
- `position` (object or null) -- Where the caller asked about, null when the crosshair is on nothing
  - `x` (integer)
  - `y` (integer)
  - `z` (integer)
- `block` (string or null)
- `face` (string or null) -- The side of the block being looked at, where a placed block would go
- `label` (string or null)
- `type` (string or null) -- The entity's registry id without the namespace
- `distance` (number)
- `labelComponent` (object or array or string or null) -- The component the entity's name was written as, as Minecraft's own JSON. Null when there is none.

### pick-block

*fabric only · deadline 10s · exclusive · needs a world · the bot answers*

Middle-click the block at a position, walking into reach and facing it first. In creative the hand gets the block's item, made if the inventory has none. Outside creative an item already in the inventory is moved onto the hotbar and selected, and a block the inventory has none of is refused. Answers with what the bot holds once the server has updated the hand.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `x` | number | yes | X coordinate |  |
| `y` | number | yes | Y coordinate |  |
| `z` | number | yes | Z coordinate |  |
| `includeData` | boolean | no | Pick the block with its data, as Ctrl+middle-click does: a chest keeps its contents, a sign its text. The server gives it only to a creative player (default: false) |  |

### place-block

*fabric and azalea · deadline 30s · exclusive · needs a world · the bot answers*

Place the held block at a position, using an adjacent block as reference.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `x` | number | yes | X coordinate |  |
| `y` | number | yes | Y coordinate |  |
| `z` | number | yes | Z coordinate |  |
| `faceDirection` | string: `down`, `up`, `north`, `south`, `east`, `west` | no | Which neighbouring face to try first (default: 'down') |  |

### read-block-entity

*fabric and azalea · deadline 5s · untrusted · read-only · needs a world · the bot answers*

Read the data a block carries beyond its type: sign text, a container's custom name, a banner's pattern. Signs are the common case, since that is where servers write instructions into the world itself.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `x` | number | yes | X coordinate |  |
| `y` | number | yes | Y coordinate |  |
| `z` | number | yes | Z coordinate |  |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `block` (string)
- `position` (object)
  - `x` (integer)
  - `y` (integer)
  - `z` (integer)
- `present` (boolean) -- False when the block carries no block entity at all
- `signFaces[]` (object) -- Only faces with something written on them
  - `face` (string)
  - `lines[]` (string) -- Four lines, blanks included
  - `lineComponents[]` (array) -- The components the lines were written as, in the same order. Read by position, so a shorter list simply leaves the rest read the way they already were.
- `raw` (string or null) -- The block entity as JSON, for anything that is not a sign

## inventory

### equip-item

*fabric and azalea · deadline 5s · needs a world · the bot answers*

Equip an item from the inventory.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `itemName` | string | yes | Item to equip: registry id with or without minecraft:, or a fragment of it or of the item's display name |  |
| `destination` | string: `hand`, `head`, `torso`, `legs`, `feet`, `off-hand` | no | Where to equip it (default: 'hand') |  |

### find-item

*fabric and azalea · deadline 5s · untrusted · read-only · needs a world · the bot answers*

Look for an item in the bot's inventory by exact or partial name.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `nameOrType` | string | yes | Item to look for: registry id with or without minecraft:, or a fragment of it or of the item's display name |  |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `query` (string) -- What the caller asked for
- `item` (object or null) -- Null when nothing in the inventory matches
  - `name` (string)
  - `count` (integer)
  - `slot` (integer)
  - `label` (string or null) -- The custom name the server gave it, if any
  - `labelComponent` (object or array or string or null) -- The component that name was written as, as Minecraft's own JSON. The flattening is done from this when it is here, so the rule lives in one place instead of once per kind of bot. Null when there is none.
  - `lore[]` (string) -- The lines the server wrote under the name, in order. A blank line is a line: it is where a menu puts its spacing.
  - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
  - `itemModel` (string or null) -- The item_model component, namespaced: the model the client draws the item with. A server dresses paper as a quest item or a menu icon by setting it, and the item id alone cannot tell those apart. Every item has one, and a plain item's is its own id.
  - `cooldownTicks` (integer or null) -- Ticks left before the item can be used again, null when it is not cooling down. A cooldown belongs to the item's cooldown group, so every stack in that group reads the same.

### give-item

*fabric and azalea · deadline 5s · destructive · needs a world · the bot answers*

Put an item straight into the inventory. Creative mode only, which is what makes it useful: a test can start from the state it needs instead of gathering its way there.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `itemName` | string | yes | Item to give: exact registry id, with or without minecraft:, for example diamond_pickaxe |  |
| `count` | integer | no | How many (default: 1) | 1 to 64 |
| `slot` | integer | no | Inventory slot to fill (default: the first empty one) | 0 to 44 |

### list-inventory

*fabric and azalea · deadline 5s · untrusted · read-only · needs a world · the bot answers*

List every item in the bot's inventory with slot numbers. The wait for this is wait-for-item.

Waited on by [`wait-for-item`](#wait-for-item).

No arguments beyond `bot`.

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `items[]` (object)
  - `name` (string)
  - `count` (integer)
  - `slot` (integer)
  - `label` (string or null) -- The custom name the server gave it, if any
  - `labelComponent` (object or array or string or null) -- The component that name was written as, as Minecraft's own JSON. The flattening is done from this when it is here, so the rule lives in one place instead of once per kind of bot. Null when there is none.
  - `lore[]` (string) -- The lines the server wrote under the name, in order. A blank line is a line: it is where a menu puts its spacing.
  - `loreComponents[]` (array) -- The components the lore lines were written as, in the same order as lore. Read by position, so a shorter list simply leaves the rest read the way lore already was.
  - `itemModel` (string or null) -- The item_model component, namespaced: the model the client draws the item with. A server dresses paper as a quest item or a menu icon by setting it, and the item id alone cannot tell those apart. Every item has one, and a plain item's is its own id.
  - `cooldownTicks` (integer or null) -- Ticks left before the item can be used again, null when it is not cooling down. A cooldown belongs to the item's cooldown group, so every stack in that group reads the same.

### wait-for-item

*fabric and azalea · deadline 130s · untrusted · read-only · needs a world · the bot answers and the server adds what its feeds caught meanwhile*

Wait until the bot is carrying something. The condition half of most quests: collect ten of these, be given that. The read for this is list-inventory.

Polls [`list-inventory`](#list-inventory) until its answer matches, so the answer is that tool's.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `pattern` | string | yes | Java regular expression (java.util.regex), matched anywhere in what list-inventory shows | at most 256 characters |
| `timeoutMs` | integer | no | How long to wait (default: 10000) | 100 to 120000 |

## entity

### find-entity

*fabric and azalea · deadline 5s · untrusted · read-only · needs a world · the bot answers*

Find nearby entities, optionally filtered by type or name. Each comes with the id interact-entity and attack-entity take, and the label floating over it when there is one. Give from and to to search a box instead of a radius: a room's furniture sits in a box and not in a sphere, and the count a box needs is not the count a glance around needs.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `type` | string | no | "player", "mob", or part of an entity name. Omit to match anything. |  |
| `maxDistance` | number | no | Search radius (default: 16) | at least 1 |
| `count` | integer | no | How many to return (default: 1, at most 500) | 1 to 500 |
| `from` | object | no | One corner of a box to search instead of a radius, inclusive. Needs "to"; maxDistance is then ignored |  |
| `from.x` | integer | yes |  |  |
| `from.y` | integer | yes |  |  |
| `from.z` | integer | yes |  |  |
| `to` | object | no | The other corner, inclusive |  |
| `to.x` | integer | yes |  |  |
| `to.y` | integer | yes |  |  |
| `to.z` | integer | yes |  |  |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `query` (string or null) -- The filter the caller gave, or null for anything
- `maxDistance` (number)
- `entities[]` (object) -- Nearest first
  - `label` (string) -- What a reader sees: the username, else the name the server gave it, else its id
  - `type` (string) -- The entity's registry id without the namespace, for example cow or armor_stand. A custom name hides what a thing is, and this is what says it. Feeding it back as the filter finds the same kind again.
  - `position` (object)
    - `x` (integer)
    - `y` (integer)
    - `z` (integer)
  - `distance` (number)
  - `labelComponent` (object or array or string or null) -- The component the name the server gave it was written as, as Minecraft's own JSON. The flattening is done from this when it is here, so the rule lives in one place instead of once per kind of bot. Null when there is none.
  - `item` (object or null) -- What an item_display shows, null for every other entity and for one holding nothing. A display is how a server puts a model into the world, and its item_model is what says which one.
    - `name` (string)
    - `count` (integer)
    - `label` (string or null) -- The custom name the server gave it, if any
    - `labelComponent` (object or array or string or null) -- The component that name was written as, as Minecraft's own JSON. Null when there is none.
    - `itemModel` (string or null) -- The item_model component, namespaced. A plain item's is its own id.
  - `block` (object or null) -- What a block_display shows, null for every other entity
    - `name` (string) -- The block's registry id without the namespace
    - `properties` (object) -- The block state's properties, facing and the like, as the game names them
  - `id` (integer) -- The entity's id in this bot's world, which interact-entity and attack-entity take as id. It holds while the entity stays loaded.
  - `nameplate` (string or null) -- The label floating over it, which interact-entity and attack-entity take as label. A label is text floating in the world: a text_display, or an invisible armor stand showing its name. The entity under a label is the one a player could click whose feet are no higher than the label and at most 3 blocks below it, and at most 1 block from it horizontally; of several, the one nearest the label. A label belongs to that one entity only. Null when no label is over it.
  - `nameplateComponent` (object or array or string or null) -- The component that label was written as, as Minecraft's own JSON, flattened by the server the same way labelComponent is. Null when there is no label.

### read-displays

*fabric only · deadline 5s · untrusted · read-only · needs a world · the bot answers*

Read the text floating in the world: holograms, name tags and NPC labels. They are display entities, so find-entity only reports that they exist.

Waited on by [`wait-for-displays`](#wait-for-displays).

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `maxDistance` | number | no | Search radius (default: 24) | at least 1 |
| `count` | integer | no | How many to return (default: 20) | 1 to 50 |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `maxDistance` (number)
- `displays[]` (object) -- Nearest first
  - `text` (string) -- Font segments already joined by the bot
  - `entity` (string)
  - `position` (object)
    - `x` (integer)
    - `y` (integer)
    - `z` (integer)
  - `distance` (number)
  - `segments[]` (object) -- The pieces the text is drawn from, glyphs taken out
    - `text` (string)
    - `font` (string) -- The font the piece was drawn in, absent for the default one
    - `color` (string)
  - `glyphPieces` (integer) -- How many pieces held nothing but glyphs. A display made only of those is an icon: it says something is there, and there is nothing to read
  - `component` (object or array or string or null) -- The component the server sent, as Minecraft's own JSON. The flattening a renderer needs is done from this when it is here, so the rule lives in one place instead of once per kind of bot; segments are the fallback for a bot that cannot produce it. It also keeps what flattening drops -- click and hover actions, and the nesting.

### wait-for-displays

*fabric only · deadline 130s · untrusted · read-only · needs a world · the bot answers and the server adds what its feeds caught meanwhile*

Wait until the text floating in the world says something: a hologram, a nameplate, an NPC's label.

Polls [`read-displays`](#read-displays) until its answer matches, so the answer is that tool's.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `pattern` | string | yes | Java regular expression (java.util.regex), matched anywhere in what read-displays shows | at most 256 characters |
| `maxDistance` | number | no | Search radius (default: 24) | at least 1 |
| `timeoutMs` | integer | no | How long to wait (default: 10000) | 100 to 120000 |

## movement

### fly-to

*fabric only · deadline 60s · exclusive · needs a world · the bot answers*

Fly straight to a position. Requires creative mode. Use move-to-position when not in creative, on an azalea bot, or when the path must go around blocks.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `x` | number | yes | X coordinate |  |
| `y` | number | yes | Y coordinate |  |
| `z` | number | yes | Z coordinate |  |

### get-position

*fabric and azalea · deadline 5s · read-only · needs a world · the bot answers*

Report the block position the bot currently stands on.

No arguments beyond `bot`.

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `position` (object)
  - `x` (integer)
  - `y` (integer)
  - `z` (integer)

### jump

*fabric and azalea · deadline 5s · needs a world · the bot answers*

Make the bot jump once. Moves the bot without pressing the jump key; a game that listens for the key needs press-input jump.

No arguments beyond `bot`.

### look-at

*fabric and azalea · deadline 5s · needs a world · the bot answers*

Turn the bot to face a position.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `x` | number | yes | X coordinate |  |
| `y` | number | yes | Y coordinate |  |
| `z` | number | yes | Z coordinate |  |

### move-in-direction

*fabric and azalea · deadline 5s · exclusive · needs a world · the bot answers*

Hold a movement key for a while. Use this when pathfinding is not wanted.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `direction` | string: `forward`, `back`, `left`, `right` | yes | Direction to move |  |
| `durationMs` | integer | no | How long to hold the key (default: 1000) | 1 to 30000 |

### move-to-position

*fabric and azalea · deadline 60s · exclusive · needs a world · the bot answers*

Walk the bot to a position using pathfinding. In creative on a fabric bot, fly-to is a straight line.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `x` | number | yes | X coordinate |  |
| `y` | number | yes | Y coordinate |  |
| `z` | number | yes | Z coordinate |  |
| `range` | number | no | How close to get (default: 1) | at least 0 |
| `timeoutMs` | integer | no | Give up after this long (default: 60000) | 1000 to 600000 |

### press-input

*fabric and azalea · deadline 10s · untrusted · needs a world · the bot answers*

Press a key the way a player does, tick by tick, through the client's own input: jump, sneak, sprint, the mouse buttons, a hotbar slot, the mouse wheel, the off-hand swap or drop. The server receives the packets a keyboard and mouse would make, so a game driven by input -- a jump that turns a conversation's page, a hotbar slot that picks an answer, a click inside a timing window -- sees a real player. after waits for an action bar, title or effect line and presses on the client tick it arrives, reacting inside the bot, so how narrow a window it can catch is bounded by the link's latency and not by an MCP round trip; until stops pressing when a line shows. Keys go to the game only while no window is open.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `key` | string: `jump`, `sneak`, `sprint`, `use`, `attack`, `hotbar`, `scroll-up`, `scroll-down`, `swap-offhand`, `drop` | yes | The key to press. jump, sneak and sprint are the movement keys the server receives as input; use and attack are the right and left mouse buttons, clicking at whatever the bot is looking at or into the air; hotbar selects the hotbar slot given by slot, as its number key does; scroll-up and scroll-down move the selection one slot as the mouse wheel does; swap-offhand swaps the held item into the off-hand; drop throws one of the held item. |  |
| `slot` | integer | no | The hotbar slot for hotbar, 0 being the leftmost. Only hotbar takes it. (0-based: the slot index, not the number key click-slot's hotbar uses) | 0 to 8 |
| `holdTicks` | integer | no | How many ticks each press keeps the key down (default: 1). Held use repeats every four ticks, as holding the button does; a wheel notch has nothing to hold. | 1 to 1200 |
| `repeat` | integer | no | How many times to press (default: 1) | 1 to 200 |
| `intervalTicks` | integer | no | How many ticks the key stays up between presses, so each one reaches the server as a press of its own (default: 1) | 1 to 1200 |
| `after` | object | no | Press nothing until this shows, then press on the very tick it arrives, inside the bot rather than after a round trip: a bite's splash sound, or a timing window drawn on the action bar. Fails if it does not show within timeoutMs. |  |
| `after.feed` | string: `actionBar`, `title`, `effect` | yes | The feed to watch |  |
| `after.pattern` | string | yes | Regular expression in the subset both bots share: no lookaround or backreferences; (?i) for case-insensitive. It matches when it matches the line as read-action-bar and read-title show it, font labels included, or the line's plain text; a sound or particle is its id, such as minecraft:entity.fishing_bobber.splash | at most 256 characters |
| `until` | object | no | Stop as soon as this shows, letting go of a held key and skipping the presses left, on the tick it arrives. |  |
| `until.feed` | string: `actionBar`, `title`, `effect` | yes | The feed to watch |  |
| `until.pattern` | string | yes | Regular expression in the subset both bots share: no lookaround or backreferences; (?i) for case-insensitive. It matches when it matches the line as read-action-bar and read-title show it, font labels included, or the line's plain text; a sound or particle is its id, such as minecraft:entity.fishing_bobber.splash | at most 256 characters |
| `timeoutMs` | integer | no | How long the whole call may take, waiting for after included (default: 10000). Presses still to come when it runs out are not made. | 100 to 120000 |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `key` (string)
- `slot` (integer or null) -- The slot hotbar was given, null for every other key
- `presses` (integer) -- How many presses were made
- `repeat` (integer) -- How many were asked for
- `holdTicks` (integer)
- `intervalTicks` (integer)
- `after` (object or null) -- What the presses waited for, or null when nothing was
  - `feed` (string)
  - `pattern` (string)
  - `matched` (string) -- The text that matched
  - `waitedMs` (integer) -- How long it took to show
- `until` (object or null) -- What the presses stopped on, or null when nothing was watched for
  - `feed` (string)
  - `pattern` (string)
  - `matched` (string or null) -- The text that matched, or null when it never showed
- `stopped` (string: `done`, `until`, `timeout`) -- Why the presses ended: every one was made, until showed, or timeoutMs ran out
- `selectedSlot` (integer) -- The hotbar slot selected once the keys were let go

### respawn

*fabric and azalea · deadline 30s · needs a world · the bot answers*

Respawn from the death screen and wait until the server has put the bot back in the world, then say where. A bot that is not dead is told so rather than refused. The bot never respawns by itself: a server under test may be checking what happens on death.

No arguments beyond `bot`.

### run-inputs

*fabric and azalea · deadline 10s · exclusive · untrusted · needs a world · the bot answers*

Run several inputs in one call, tick by tick inside the bot: key presses, window clicks, an item used from the hand, chat commands, waits and waits for a line, in the order given. Use it when what matters is how far apart two inputs reach the server -- a cooldown, a double-click window, a combo -- because separate tool calls land a second or more apart. A step starts on the client tick the step before it ended; wait puts exactly that many ticks between them. The answer lists every step with the ticks it ran on and what it did, and stops at the first step the game refuses; the steps that ran are still reported. A key still goes to the game only while no window is open, and a click still needs one.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `steps` | array of object | yes | The inputs, in order. Each step is exactly one of press, click, useItem, command, wait or waitFor; the other fields shape it | 1 to 32 items |
| `steps[].press` | string: `jump`, `sneak`, `sprint`, `use`, `attack`, `hotbar`, `scroll-up`, `scroll-down`, `swap-offhand`, `drop` | no | A key to press, as press-input names it. Down for holdTicks, then up for one tick before the next step |  |
| `steps[].slot` | integer | no | The hotbar slot for press: hotbar, 0 being the leftmost (0-based: the slot index, not the number key click-slot's hotbar uses) | 0 to 8 |
| `steps[].holdTicks` | integer | no | How many ticks the key stays down, or the item stays in use (default: 1) | 1 to 1200 |
| `steps[].useItem` | string: `main-hand`, `off-hand` | no | A hand whose item to use, as use-held-item does: the item's own right-click, whatever the crosshair is on. press: use is the player's click, which goes to the entity or block there first and uses the item only when nothing is. In use for holdTicks, then released, and the step ends one tick later as a press does |  |
| `steps[].click` | integer | no | A window slot to click, as listed by read-window; the step ends when the server has sent the window back, as click-slot does | at least 0 |
| `steps[].button` | string: `left`, `right` | no | For click (default: 'left') |  |
| `steps[].shift` | boolean | no | For click (default: false) |  |
| `steps[].mode` | string: `click`, `swap-hotbar`, `swap-offhand`, `throw-one`, `throw-stack`, `pickup-all`, `clone` | no | For click (default: 'click'), as click-slot describes them |  |
| `steps[].hotbar` | integer | no | The number key for mode swap-hotbar (1-based: the number key, not the slot index press-input's slot uses) | 1 to 9 |
| `steps[].command` | string | no | A slash command sent on its tick, with or without the slash; what the server replies is on read-chat | at most 256 characters |
| `steps[].wait` | integer | no | Client ticks between the end of the step before and the start of the step after | 1 to 1200 |
| `steps[].waitFor` | string | no | Regular expression in the subset both bots share: no lookaround or backreferences; (?i) for case-insensitive. Run nothing until a line matching it shows on feed, then move on at the end of the tick it arrives. Lines that arrived since the step before started count, so a reply the server sends while that step is still settling is not missed. Bounded by timeoutMs. A press that must land on the very tick a cue arrives is press-input's after | at most 256 characters |
| `steps[].feed` | string: `actionBar`, `title`, `effect` | no | The feed waitFor watches (default: 'actionBar'), matched the way press-input's after is |  |
| `timeoutMs` | integer | no | How long the whole sequence may take, waits included (default: 10000). Steps still to come when it runs out are not made | 100 to 120000 |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `asked` (integer) -- How many steps were given
- `ran` (integer) -- How many steps ended without an error
- `ticks` (integer) -- The tick the last step listed ended on, or the tick timeoutMs ran out on, counted from the tick the first step started
- `stopped` (string: `done`, `refused`, `timeout`) -- Why the sequence ended: every step ran, the game refused one, or timeoutMs ran out
- `steps[]` (object) -- Only the steps that started, in order; each either ended or carries an error. Steps still to come when the sequence stopped are not listed
  - `kind` (string: `press`, `click`, `useItem`, `command`, `wait`, `waitFor`)
  - `asked` (string) -- The step as it was asked for, in the bot's words: press jump, click slot 13, use item in main hand, command /fixture pling e2e, wait 20 ticks, wait for /Fine day/ on actionBar. Set before the step runs, so a step that was refused or timed out is still named
  - `startedTick` (integer) -- The client tick the step started on, 0 being the tick the first step started. A step starts on the tick the step before it ended
  - `endedTick` (integer) -- The client tick the step ended on, or the tick it was stopped on when error says why
  - `press` (object or null) -- What press-input answers for the key: one press, intervalTicks 1, nothing waited for. Null for every other kind
    - `key` (string)
    - `slot` (integer or null) -- The slot hotbar was given, null for every other key
    - `presses` (integer) -- How many presses were made
    - `repeat` (integer) -- How many were asked for
    - `holdTicks` (integer)
    - `intervalTicks` (integer)
    - `after` (object or null) -- What the presses waited for, or null when nothing was
    - `until` (object or null) -- What the presses stopped on, or null when nothing was watched for
    - `stopped` (string: `done`, `until`, `timeout`) -- Why the presses ended: every one was made, until showed, or timeoutMs ran out
    - `selectedSlot` (integer) -- The hotbar slot selected once the keys were let go
  - `click` (object or null) -- What click-slot answers for the click. Null for every other kind
    - `slot` (integer or null) -- The slot clicked, null for a click outside the window
    - `outside` (boolean)
    - `button` (string: `left`, `right`)
    - `shift` (boolean)
    - `before` (object or null) -- What the slot held before the click, null when it was empty. For a click outside the window, what the cursor held
    - `after` (object or null) -- What the slot holds now, null when the click emptied it. For a click outside the window, what the cursor holds
    - `cursor` (object or null) -- What the cursor holds after the click, null when it holds nothing
    - `mode` (string: `click`, `swap-hotbar`, `swap-offhand`, `throw-one`, `throw-stack`, `pickup-all`, `clone`)
    - `hotbar` (integer or null)
    - `swapped` (object or null) -- The other side of a swap: the hotbar slot or the offhand, which need not be in the window at all. Null for every mode that is not a swap.
    - `window` (object or null) -- Null when the server answered in the window that was clicked. Otherwise the server answered with another window, as a plugin's menu does when a click opens the next screen or opens its own screen again, or it closed the window: then the slots of the window clicked are not described, because it is gone, and after is null.
  - `useItem` (object or null) -- What the use did: the hand, what it held when the use began, and how long it stayed in use. Null for every other kind
    - `hand` (string: `main-hand`, `off-hand`)
    - `item` (string) -- The stack the hand held, as use-held-item words it: the name the server gave it, else what it is, with its count; or an empty hand
    - `holdTicks` (integer)
  - `command` (string or null) -- The command sent, null for every other kind
  - `wait` (integer or null) -- The ticks waited, null for every other kind
  - `waitFor` (object or null) -- What the step waited for and the line that matched, null for every other kind
    - `feed` (string)
    - `pattern` (string)
    - `matched` (string) -- The text that matched
    - `waitedMs` (integer) -- How long it took to show, from the tick the step started
  - `error` (object or null) -- Why the game refused the step, or TIMEOUT when timeoutMs ran out during it. Null when the step ended as asked
    - `code` (string)
    - `message` (string)

### set-stance

*fabric and azalea · deadline 5s · needs a world · the bot answers*

Hold the bot crouching or sprinting. Both stay on until turned off, so a plugin that only reacts to a crouching player can be reached. press-input sneak/sprint holds the key for holdTicks instead.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `sneak` | boolean | no | Crouch, or stand up again |  |
| `sprint` | boolean | no | Sprint, or stop sprinting |  |

## hud

### get-player-state

*fabric and azalea · deadline 5s · read-only · needs a world · the bot answers*

Report the health, hunger, experience and position the bot sees for itself, what it is riding, and whether it is dead. Asks the bot; use get-bot-status when it may not answer.

No arguments beyond `bot`.

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `health` (number)
- `food` (number)
- `saturation` (number)
- `experience` (object)
  - `level` (integer)
  - `progress` (number)
  - `points` (integer)
- `gameMode` (string)
- `dimension` (string)
- `position` (object or null) -- Null while the bot has no entity yet
  - `x` (integer)
  - `y` (integer)
  - `z` (integer)
- `oxygen` (number or null) -- Null when the server has not said, which means full
- `dead` (boolean) -- True from the moment the server says the bot died until it respawns. Every tool that acts in the world refuses while this is true.
- `causeOfDeath` (string or null) -- What the death screen says killed the bot. Null while alive, or when the server respawned it without a screen.
- `causeOfDeathComponent` (object or array or string or null) -- The component that line was written as, as Minecraft's own JSON. The flattening is done from this when it is here, so the rule lives in one place instead of once per kind of bot. Null when there is none.
- `vehicle` (object or null) -- What the bot is riding, null when it stands on its own feet. type and id are the bottom of the stack, the thing that moves.
  - `type` (string) -- The entity's registry id without the namespace
  - `id` (integer) -- The entity's network id, which the server assigns and every client in that world shares
  - `seat` (object or null) -- The entity the bot sits on when that is not the vehicle itself: a plugin seats a player on an invisible entity riding the mount. Null when the bot sits on the vehicle directly.
    - `type` (string) -- The entity's registry id without the namespace
    - `id` (integer) -- The entity's network id, which the server assigns and every client in that world shares

### read-action-bar

*fabric and azalea · deadline 5s · untrusted · read-only · the server answers from what it holds*

Read the action bar text above the hotbar, which servers use for live status. Repeats are collapsed, so each line is a change. A HUD drawn in custom fonts arrives as several pieces separated by " | ", each tagged with the font that names it.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `count` | integer | no | How many recent lines to return (default: 5) | 1 to 200 |

### read-advancements

*fabric only · deadline 5s · untrusted · read-only · needs a world · the bot answers*

Read the advancements the server has sent: which are done and when, and how far the rest have got. Vanilla alone has over a hundred, so by default only a server's own are listed -- any namespace but minecraft -- most recently progressed first. Servers track quests as advancements. The server only sends what the player could see in the advancements screen: an unfinished one appears once its parent is done, and a root with nothing finished under it is not sent at all, so a quest missing here may simply not be visible yet.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `prefix` | string | no | Only advancements whose id starts with this, such as "myserver:quests/" (default: every namespace except minecraft) | at most 256 characters |
| `status` | string: `any`, `done`, `inProgress` | no | Which to list: done, inProgress (started and not done), or any (default: any) |  |
| `count` | integer | no | How many to return, most recently progressed first (default: 10) | 1 to 200 |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `prefix` (string or null) -- The prefix asked for, or null for every namespace except minecraft
- `status` (string)
- `matched` (integer) -- How many matched before count cut the list short
- `advancements[]` (object)
  - `id` (string)
  - `title` (string or null) -- Null for an advancement with no display, which a server uses as a hidden trigger
  - `titleComponent` (object or array or string or null) -- The component the title was written as, as Minecraft's own JSON. The flattening is done from this when it is here, so the rule lives in one place instead of once per kind of bot. Null when there is none.
  - `description` (string or null)
  - `descriptionComponent` (object or array or string or null) -- The component the description was written as. Null when there is none.
  - `frame` (string or null) -- task, goal or challenge. Null with no display
  - `done` (boolean)
  - `criteriaDone` (integer)
  - `criteriaTotal` (integer)
  - `lastProgressAt` (integer or null) -- When the most recent criterion was met, in milliseconds since the epoch. Null when none has been

### read-boss-bars

*fabric and azalea · deadline 5s · untrusted · read-only · needs a world · the bot answers*

Read every boss bar the server is showing above the hotbar.

Waited on by [`wait-for-boss-bars`](#wait-for-boss-bars).

No arguments beyond `bot`.

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `bars[]` (object)
  - `title` (string) -- Empty when the server set none
  - `progress` (number) -- 0 to 1
  - `color` (string)
  - `dividers` (integer)
  - `segments[]` (object) -- The pieces the title is drawn from, glyphs taken out, in order. A server builds a boss bar out of stacked labels the same way it builds an action bar, and joining them into one string runs them together
    - `text` (string)
    - `font` (string) -- The font the piece was drawn in, absent for the default one
    - `color` (string)
  - `component` (object or array or string or null) -- The component the server sent, as Minecraft's own JSON. The flattening a renderer needs is done from this when it is here, so the rule lives in one place instead of once per kind of bot; segments are the fallback for a bot that cannot produce it. It also keeps what flattening drops -- click and hover actions, and the nesting.

### read-dialog

*fabric and azalea · deadline 5s · untrusted · read-only · the server answers from what it holds*

Read the dialogs the server has opened on screen, with their title, body, the buttons they offer and what each input holds, and the point at which each was closed again. A dialog comes again with the new values when set-dialog-input changes one. press-dialog-button presses them. Repeats are collapsed, so each line is a change. A HUD drawn in custom fonts arrives as several pieces separated by " | ", each tagged with the font that names it.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `count` | integer | no | How many recent lines to return (default: 5) | 1 to 200 |

### read-player-list

*fabric and azalea · deadline 5s · untrusted · read-only · needs a world · the bot answers*

List the players on the tab list, with their game mode and ping.

Waited on by [`wait-for-player-list`](#wait-for-player-list).

No arguments beyond `bot`.

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `players[]` (object) -- Sorted by name
  - `name` (string)
  - `gameMode` (string)
  - `ping` (integer or null) -- Null when the server has not said
  - `self` (boolean)
  - `displayName` (string or null) -- What the tab list draws for this player, which is where a server puts a rank. Null when it set none and the username is what shows.
  - `displayNameComponent` (object or array or string or null) -- The component that name was written as, as Minecraft's own JSON. The flattening is done from this when it is here, so the rule lives in one place instead of once per kind of bot. Null when there is none.

### read-scoreboard

*fabric and azalea · deadline 5s · untrusted · read-only · needs a world · the bot answers*

Read the scoreboard the server draws on screen, which most servers use for stats and quest progress.

Waited on by [`wait-for-scoreboard`](#wait-for-scoreboard).

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `slot` | string: `sidebar`, `list`, `belowName` | no | Which display slot to read (default: 'sidebar') |  |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `slot` (string)
- `board` (object or null) -- Null when nothing is displayed in that slot
  - `title` (string)
  - `entries[]` (object) -- The lines as drawn, top first: highest score first, and on the sidebar without entries whose owner starts with '#' and at most 15
    - `name` (string) -- What the line draws, in plain text: on the sidebar the entry's team prefix, its display name or else its owner, and its team suffix
    - `score` (integer) -- The score itself, whether or not the line shows it
    - `nameComponent` (object or array or string or null) -- The component the line's name was drawn as, as Minecraft's own JSON: on the sidebar the team prefix, the name and the team suffix in the team's colour. The flattening is done from this when it is here, so the rule lives in one place instead of once per kind of bot. Null when there is none.
    - `scoreText` (string) -- What the score column shows, in plain text: the number, the fixed text the objective or the entry puts in its place, or empty when the number is hidden
    - `scoreComponent` (object or array or string or null) -- The component the score column was drawn as, as Minecraft's own JSON. Empty text when the number is hidden. Null when there is none.
  - `titleComponent` (object or array or string or null) -- The component the title was written as, as Minecraft's own JSON. The flattening is done from this when it is here, so the rule lives in one place instead of once per kind of bot. Null when there is none.

### read-stats

*fabric only · deadline 10s · read-only · needs a world · the bot answers*

Read the bot's statistics as the server counts them: kills per mob, blocks mined, items crafted and used, distance walked, time played, deaths. The client only holds the statistics it was last sent, so this asks the server and reads its answer, never an older copy. Without type or target the general statistics (minecraft:custom) are listed, with how many of each other type are nonzero; a kill or mining quest is checked with both, such as type minecraft:killed and target minecraft:zombie. Distances are kept in centimetres and times in ticks, and those are shown in their unit as well.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `type` | string | no | Only this statistic type, such as "minecraft:killed", "minecraft:mined", "minecraft:crafted" or "minecraft:custom"; the namespace may be left off (default: minecraft:custom with the other types summarised, or every type when a target is given) | at most 256 characters |
| `target` | string | no | Only this target, such as "minecraft:zombie" for kills or "minecraft:jump" for a custom statistic. Given with type, that one statistic is answered even when it is zero; without type, every type counting it is listed (default: any target) | at most 256 characters |
| `count` | integer | no | How many to return, highest value first (default: 30) | 1 to 500 |

The bot answers with a DTO the server renders into the text (see [bot-protocol.md](bot-protocol.md), Structured results), shaped:

- `type` (string or null) -- The type asked for, with its namespace, or null when none was
- `target` (string or null) -- The target asked for, with its namespace, or null when none was
- `matched` (integer) -- How many matched before count cut the list short
- `stats[]` (object) -- Nonzero only, highest value first, except that one statistic asked for by type and target is there at zero
  - `type` (string)
  - `target` (string)
  - `value` (integer) -- The number the server keeps, which is what a statistic scoreboard objective reads: centimetres for a distance, ticks for a time
  - `formatted` (string or null) -- The value in the unit the statistics screen shows, such as "1.52 km" or "3.5 h". Null for a plain count
- `otherTypes[]` (object) -- Only when neither type nor target was given: every other type with something nonzero in it, by id
  - `type` (string)
  - `nonZero` (integer) -- How many of its statistics are nonzero

### read-title

*fabric and azalea · deadline 5s · untrusted · read-only · the server answers from what it holds*

Read the titles and subtitles the server has thrown across the screen, which is where servers put things the player must not miss. Repeats are collapsed, so each line is a change. A HUD drawn in custom fonts arrives as several pieces separated by " | ", each tagged with the font that names it.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `count` | integer | no | How many recent lines to return (default: 5) | 1 to 200 |

### read-toasts

*fabric only · deadline 5s · untrusted · read-only · the server answers from what it holds*

Read the toasts that popped up in the corner of the screen: an advancement made, with its id, frame and description, and recipes unlocked. A toast is gone within seconds, so these are kept as they arrive rather than read off the screen. A title drawn in custom fonts arrives as several pieces separated by " | ", each tagged with the font that names it. The wait for this is wait-for-toast.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `count` | integer | no | How many recent lines to return (default: 5) | 1 to 200 |

### wait-for-action-bar

*fabric and azalea · deadline 130s · untrusted · read-only · the server answers from what it holds*

Wait until the action bar shows text matching a regular expression. Returns straight away if it already says so.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `pattern` | string | yes | Java regular expression (java.util.regex), matched anywhere in the line | at most 256 characters |
| `timeoutMs` | integer | no | How long to wait (default: 10000) | 100 to 120000 |

### wait-for-boss-bars

*fabric and azalea · deadline 130s · untrusted · read-only · needs a world · the bot answers and the server adds what its feeds caught meanwhile*

Wait until a boss bar says something. Servers put phase, progress and timers there.

Polls [`read-boss-bars`](#read-boss-bars) until its answer matches, so the answer is that tool's.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `pattern` | string | yes | Java regular expression (java.util.regex), matched anywhere in what read-boss-bars shows | at most 256 characters |
| `timeoutMs` | integer | no | How long to wait (default: 10000) | 100 to 120000 |

### wait-for-dialog

*fabric and azalea · deadline 130s · untrusted · read-only · the server answers from what it holds*

Wait until a dialog whose text matches a regular expression is opened. Returns straight away if it already says so.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `pattern` | string | yes | Java regular expression (java.util.regex), matched anywhere in the line | at most 256 characters |
| `timeoutMs` | integer | no | How long to wait (default: 10000) | 100 to 120000 |

### wait-for-player-list

*fabric and azalea · deadline 130s · untrusted · read-only · needs a world · the bot answers and the server adds what its feeds caught meanwhile*

Wait until the tab list says something -- somebody joining, a rank appearing beside a name.

Polls [`read-player-list`](#read-player-list) until its answer matches, so the answer is that tool's.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `pattern` | string | yes | Java regular expression (java.util.regex), matched anywhere in what read-player-list shows | at most 256 characters |
| `timeoutMs` | integer | no | How long to wait (default: 10000) | 100 to 120000 |

### wait-for-scoreboard

*fabric and azalea · deadline 130s · untrusted · read-only · needs a world · the bot answers and the server adds what its feeds caught meanwhile*

Wait until the scoreboard says something. A quest counting up, a balance changing, a timer: the sidebar is where a server writes progress and nothing pushes it, so this asks until it reads what you are waiting for.

Polls [`read-scoreboard`](#read-scoreboard) until its answer matches, so the answer is that tool's.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `pattern` | string | yes | Java regular expression (java.util.regex), matched anywhere in what read-scoreboard shows | at most 256 characters |
| `slot` | string: `sidebar`, `list`, `belowName` | no | Which display slot to read (default: 'sidebar') |  |
| `timeoutMs` | integer | no | How long to wait (default: 10000) | 100 to 120000 |

### wait-for-title

*fabric and azalea · deadline 130s · untrusted · read-only · the server answers from what it holds*

Wait until a title or subtitle matching a regular expression is shown. Returns straight away if it already says so.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `pattern` | string | yes | Java regular expression (java.util.regex), matched anywhere in the line | at most 256 characters |
| `timeoutMs` | integer | no | How long to wait (default: 10000) | 100 to 120000 |

### wait-for-toast

*fabric only · deadline 130s · untrusted · read-only · the server answers from what it holds*

Wait until a toast whose text matches a regular expression pops up: an advancement a server grants when a quest is done, or a recipe unlocked. The advancement's id is part of the text, so a pattern can name it. Returns straight away if the latest toast already says so. The read for this is read-toasts.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `pattern` | string | yes | Java regular expression (java.util.regex), matched anywhere in the line | at most 256 characters |
| `timeoutMs` | integer | no | How long to wait (default: 10000) | 100 to 120000 |

## probe

### ping-server

*fabric and azalea · deadline 5s · untrusted · read-only · the server answers from what it holds*

Send a Minecraft server list ping and report version, protocol, player count and MOTD. Use it to check a server is up and speaks a version we can join before spending a bot slot. The wait for this is wait-for-server.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `host` | string | yes | Server host |  |
| `port` | integer | no | Server port (default: 25565) | 1 to 65535 |
| `timeoutMs` | integer | no | How long to wait for the answer (default: 5000) | 200 to 30000 |

### wait-for-server

*fabric and azalea · deadline 300s · read-only · the server answers from what it holds*

Wait until a Minecraft server answers a server list ping and reports it can be joined. Use it after restarting a server so the next join-server does not race the boot. The read for this is ping-server.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `host` | string | yes | Server host |  |
| `port` | integer | no | Server port (default: 25565) | 1 to 65535 |
| `timeoutMs` | integer | no | How long to keep trying (default: 300000) | 1000 to 600000 |

## chat

### read-chat

*fabric and azalea · deadline 5s · untrusted · read-only · the server answers from what it holds*

Read recent chat and system messages the bot received.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `count` | integer | no | How many recent lines to return (default: 20) | 1 to 200 |

### send-chat

*fabric and azalea · deadline 5s · needs a world · the bot answers*

Say something in chat as the bot. Use run-command for slash commands.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `message` | string | yes | Text to say | at most 256 characters |

### wait-for-chat

*fabric and azalea · deadline 130s · untrusted · read-only · the server answers from what it holds*

Wait until a chat line matching a regular expression arrives. Returns straight away if the latest chat line already says so; use run-command, which only counts replies after the command, when only new lines count.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `pattern` | string | yes | Java regular expression (java.util.regex), matched anywhere in the line | at most 256 characters |
| `timeoutMs` | integer | no | How long to wait (default: 10000) | 100 to 120000 |

## effect

### read-effects

*fabric only · deadline 5s · untrusted · read-only · the server answers from what it holds*

Read the sounds and particles the server has played near the bot, one line per play as it happened, nothing collapsed; the same sound twice is two lines. Each line is the namespaced id (sound:/particle: prefix). Particles come in bursts, so raise count or use wait-for-effect for one id.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `count` | integer | no | How many recent lines to return (default: 5) | 1 to 200 |

### wait-for-effect

*fabric only · deadline 130s · untrusted · read-only · the server answers from what it holds*

Wait until a sound or particle whose name matches a regular expression is played. Returns straight away if it already says so. The read for this is read-effects.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `pattern` | string | yes | Java regular expression (java.util.regex), matched anywhere in the line | at most 256 characters |
| `timeoutMs` | integer | no | How long to wait (default: 10000) | 100 to 120000 |

## smelting

### smelt-item

*fabric only · deadline 120s · exclusive · needs a world · the bot answers*

Load a furnace, blast furnace or smoker with fuel and input, then optionally wait for the output.

| Argument | Type | Required | What it is | Limits |
| --- | --- | --- | --- | --- |
| `x` | number | yes | X coordinate |  |
| `y` | number | yes | Y coordinate |  |
| `z` | number | yes | Z coordinate |  |
| `inputItem` | string | yes | Item to smelt |  |
| `inputCount` | integer | no | How much input to load (default: 1) | 1 to 64 |
| `fuelItem` | string | yes | Item to burn as fuel |  |
| `fuelCount` | integer | no | How much fuel to load (default: 1) | 1 to 64 |
| `takeOutput` | boolean | no | Wait for the result and collect it (default: true) |  |
| `timeoutMs` | integer | no | How long to wait for the result (default: 60000) | 1000 to 600000 |
