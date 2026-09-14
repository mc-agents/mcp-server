# Everything the reading tools are supposed to be able to read, at fixed coordinates.
#
# A world with nothing in it makes the comparison suite blind: both kinds of bot answer "nothing
# there" and agree perfectly about a world neither can see. This is the other half.
#
# Runs on #minecraft:load, so it happens on every world load and on /reload. Coordinates are
# absolute and near the flat world's spawn, which is what makes a run reproducible.

# --- scoreboard: read-scoreboard ---
scoreboard objectives remove mcagents
# The title is stacked pieces in the pack's own font, which is how a real server draws a sidebar:
# an icon glyph and a label, each in its own font. Flattened to a string the icon is gone and the
# words touch, so this is the case a plain-text reader loses.
scoreboard objectives add mcagents dummy {"text":"","extra":[{"text":"","font":"hyperfarm:sidebar/icons"},{"text":"Probe Stats","font":"hyperfarm:sidebar/title"}]}
scoreboard objectives setdisplay sidebar mcagents
scoreboard players set Coins mcagents 1200
scoreboard players set Level mcagents 42
scoreboard players set Deaths mcagents 3
# An entry with a name of its own. The owner is the key a server scores against -- a uuid on the
# ones that use one -- and the display is what the sidebar draws.
scoreboard players display name Level mcagents {"text":"Level","color":"green","font":"hyperfarm:sidebar/label"}

# --- boss bar: read-boss-bars ---
bossbar remove minecraft:mcagents
bossbar add minecraft:mcagents {"text":"Probe Bar","color":"light_purple"}
bossbar set minecraft:mcagents color purple
bossbar set minecraft:mcagents style notched_10
bossbar set minecraft:mcagents max 100
bossbar set minecraft:mcagents value 73
bossbar set minecraft:mcagents players @a
bossbar set minecraft:mcagents visible true

# --- a two-sided sign: read-block-entity ---
# Both faces, and one line deliberately empty, because "" and a missing line are different things
# and a text parser that conflates them reads the shop sign below it wrong.
setblock 3 -60 0 air
# messages holds components, not JSON strings. Quoting them makes the sign literally read
# {"text":"Welcome"}, which is what it did -- and a bot that parses any string looking like JSON
# hid that while the client drew the braces.
# The price line is drawn in the pack's own font, with an icon glyph in front of it, the same as
# the sidebar above: a sign is where a server writes into the world and it writes in its own font.
setblock 3 -60 0 oak_sign[rotation=8]{front_text:{messages:[{text:"Welcome",color:"yellow"},{text:"",extra:[{text:"",font:"hyperfarm:gui/icons"},{text:"12 coins",font:"hyperfarm:gui/price"}]},{text:""},{text:"line four"}]},back_text:{messages:[{text:"back side"},{text:""},{text:""},{text:"four again"}]}}

# --- a hologram: read-displays ---
# text and CustomName hold components, not JSON strings, the same as the sign's messages above. A
# quoted one makes the hologram literally read {"text":"Hologram line"}, and mineflayer's lenient
# parsing hid that while making the fabric bot -- which reads the component the client was given --
# look like the one at fault.
kill @e[type=text_display,tag=mcagents]
summon text_display 3 -58 3 {Tags:["mcagents"],billboard:"center",text:{text:"Hologram line",color:"aqua"}}

# --- furniture made of displays: find-entity ---
# Every display is called item_display or block_display, and what tells a chair from a signpost is
# what it shows: the item and the model it is drawn with, or the block state. Clear of the villager
# below and dry, the pool being further along the negative axes.
kill @e[type=item_display,tag=mcagents]
summon item_display 3 -58 -3 {Tags:["mcagents"],item:{id:"minecraft:paper",count:1,components:{"minecraft:custom_name":"Probe Chair","minecraft:item_model":"hyperfarm:furniture/chair"}}}
kill @e[type=block_display,tag=mcagents]
summon block_display 4 -60 -3 {Tags:["mcagents"],block_state:{Name:"minecraft:oak_stairs",Properties:{facing:"east",half:"bottom",shape:"straight",waterlogged:"false"}}}

# --- an entity to find and interact with: find-entity, attack-entity, interact-entity ---
kill @e[type=cow,tag=mcagents]
# The nameplate carries a rank glyph in front of the name, which is what an NPC looks like on a
# server that draws with a resource pack.
summon cow 5 -60 5 {Tags:["mcagents"],CustomName:{text:"",extra:[{text:"",font:"hyperfarm:nametag/icons"},{text:"Probe Cow",font:"hyperfarm:nametag/label"}]},CustomNameVisible:1b,NoAI:1b,Silent:1b}

# --- a container with custom names and lore: open-container, read-window ---
setblock 1 -60 3 air
# Slot is a byte and capitalised. Lower-case "slot" is accepted and ignored, so all three items
# land in slot 0 and only the last survives -- a chest that looks filled and holds one thing.
# custom_name and lore hold components too. Quoting them put the braces in the item's name, and a
# reader lenient enough to parse any string that looks like JSON hid it.
# The paper in slot 8 is a menu button as a plugin writes one: the name is an icon glyph and a
# label in the pack's own fonts, and the lore has a blank line for spacing. Both are lost by a
# reader that flattens the name to a string, and the blank line is where a menu's spacing lives.
# The chest has a name of its own, so the window title comes from a component rather than from
# the translate key a plain chest carries -- and it is drawn in the pack's own font, which is
# what a plugin menu header is. "Chest" stays inside it so a wait on that pattern still matches.
setblock 1 -60 3 chest{CustomName:{text:"",extra:[{text:"",font:"hyperfarm:gui/icons"},{text:"Probe Chest",font:"hyperfarm:gui/header"}]},Items:[{Slot:0b,id:"minecraft:diamond_sword",count:1,components:{"minecraft:custom_name":{text:"Excalibur",color:"aqua",italic:false},"minecraft:lore":[{text:"A sword a server named",color:"gray"},{text:"Second line of lore",color:"dark_gray"}]}},{Slot:4b,id:"minecraft:cooked_beef",count:12},{Slot:8b,id:"minecraft:paper",count:1,components:{"minecraft:custom_name":{text:"",extra:[{text:"",font:"hyperfarm:gui/icons"},{text:"Mana Potion",font:"hyperfarm:gui/label"}]},"minecraft:lore":[{text:"Restores ",extra:[{text:"50",color:"aqua"},{text:" mana"}]},{text:""},{text:"Right-click to drink",color:"dark_gray"}]}},{Slot:26b,id:"minecraft:emerald",count:3}]}

# --- a villager with trades of its own: read-trades, select-trade ---
# The trades are written out rather than left to the profession, which would roll different ones on
# every load and leave nothing to assert. Three cases a trading screen has to tell apart: a result a
# server named in its own font, an enchanted book whose enchantment is the whole trade, and one that
# is out of stock -- which stays in the list and gives nothing. NoAI keeps it where it was put and
# stops it from reworking its trades at a lectern it happens to find.
# The name is drawn in the pack's own font, and a trading screen takes its title from the name.
kill @e[type=villager,tag=mcagents]
summon villager 0 -60 -4 {Tags:["mcagents"],NoAI:1b,Silent:1b,Invulnerable:1b,CustomName:{text:"",extra:[{text:"",font:"hyperfarm:gui/icons"},{text:"Probe Librarian",font:"hyperfarm:gui/header"}]},VillagerData:{type:"minecraft:plains",profession:"minecraft:librarian",level:3},Xp:40,Offers:{Recipes:[{buy:{id:"minecraft:emerald",count:3},sell:{id:"minecraft:paper",count:1,components:{"minecraft:custom_name":{text:"",extra:[{text:"",font:"hyperfarm:gui/icons"},{text:"Map Fragment",font:"hyperfarm:gui/label"}]}}},uses:0,maxUses:4,rewardExp:0b,xp:5,priceMultiplier:0.0f,specialPrice:0,demand:0},{buy:{id:"minecraft:emerald",count:5},buyB:{id:"minecraft:book",count:1},sell:{id:"minecraft:enchanted_book",count:1,components:{"minecraft:stored_enchantments":{"minecraft:mending":1}}},uses:0,maxUses:12,rewardExp:0b,xp:10,priceMultiplier:0.2f,specialPrice:0,demand:0},{buy:{id:"minecraft:wheat",count:20},sell:{id:"minecraft:emerald",count:1},uses:16,maxUses:16,rewardExp:0b,xp:2,priceMultiplier:0.05f,specialPrice:0,demand:0}]}}

# --- a loom: read-container-options, press-container-button ---
# Its patterns are drawn by the screen and pressed by number, like the stonecutter's results, and the
# number means whichever pattern the list happens to put there -- which depends on the dye.
setblock -1 -60 7 air
setblock -1 -60 7 loom

# --- a beacon on a one-level pyramid: read-container-options, set-beacon-effects ---
# Placed afresh so a case that set an effect leaves none behind. The pyramid is sunk into the
# ground so nothing stands over the beacon: it counts no levels until its beam reaches the sky,
# and it only counts every four seconds, so a case waits for the level rather than assuming it.
setblock 6 -60 10 air
fill 5 -61 9 7 -61 11 minecraft:iron_block
setblock 6 -60 10 beacon

# --- a furnace with fuel and input: smelt-item ---
setblock 1 -60 5 air
setblock 1 -60 5 furnace[facing=north]

# --- menus that are pressed rather than clicked: read-container-options, press-container-button ---
# No bookshelves round the table, so its offers stay cheap enough for a bot given a few levels.
setblock -1 -60 3 air
setblock -1 -60 3 enchanting_table
setblock -1 -60 5 air
setblock -1 -60 5 stonecutter
# A lectern shows the book screen and not a container screen, so it is the case a lookup that only
# knows container screens reports as nothing open.
setblock 0 -60 7 air
setblock 0 -60 7 lectern[has_book=true]{Book:{id:"minecraft:written_book",count:1,components:{"minecraft:written_book_content":{title:"Probe Lectern",author:"Probe",pages:[{text:"first"},{text:"second"},{text:"third"}]}}},Page:0}

# --- the blocks find-blocks is asked for, in a known place ---
fill 7 -60 -2 9 -60 0 minecraft:diamond_block

# --- water to fish in: fish ---
# A flat world has none, and a rod cast at dry ground never gets a bite, so the tool would only
# ever be able to report its own timeout.
# Wide enough that a cast lands in it whichever way the bot happens to be facing. A three by three
# pool sounds like plenty and is not: the bobber flies several blocks, and a bot whose aim was a
# little off put it on dry grass and then waited out the whole timeout for a bite that could not
# come. Well clear of the blocks above, which sit at positive x.
# Sunk into the ground rather than laid on it: a layer at the level players stand on has nothing
# round it, so it spilled over the grass as far as the start of every case, and its current carried
# a bot that was standing still. Two deep below the grass, the grass is the rim.
# The pool is in the chunk west and north of spawn, which nothing keeps loaded before a player
# arrives; filled then, it was never there. The forceload is what the next run of this finds loaded.
forceload add -10 -10 -2 -2
fill -10 -60 -10 -2 -60 -2 minecraft:air
fill -10 -62 -10 -2 -61 -2 minecraft:water

# --- the same inventory for everyone: list-inventory and find-item ---
# Without this the comparison is about what each bot has happened to pick up. Dropped items go too,
# or a run that tested drop-held-item leaves the next one reading a different floor.
clear @a
give @a minecraft:diamond 3
give @a minecraft:oak_planks 24
give @a minecraft:bow 1
# A carried item with a name of its own, so list-inventory has a label to read rather than only an
# item id. This is what a quest item looks like: named by the server, in the pack's own font.
# It carries lore with a blank line and a model of its own too, which is how a server tells one
# piece of paper from another: the id says paper and the model says what the player sees.
give @a minecraft:paper[custom_name={text:"",extra:[{text:"",font:"hyperfarm:gui/icons"},{text:"Quest Note",font:"hyperfarm:gui/label"}]},lore=[{text:"Bring this to the smith",color:"gray"},{text:""}],item_model="hyperfarm:quest/note"] 1
kill @e[type=item]

# --- a wall to walk around: move-to-position ---
# Straight-line walking is enough in an empty world and useless in a built one, so the fixture has
# something in the way. Three blocks high, so it cannot be jumped, with the way round it eight
# blocks north: a bot that reports "could not reach" here has no pathfinding, and one that arrives
# by the short way has not gone round anything.
fill 14 -60 -6 14 -58 6 minecraft:stone

say mc-agents fixture is in place
