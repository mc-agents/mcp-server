# Everything the reading tools are supposed to be able to read, at fixed coordinates.
#
# A world with nothing in it makes the comparison suite blind: both kinds of bot answer "nothing
# there" and agree perfectly about a world neither can see. This is the other half.
#
# Runs on #minecraft:load, so it happens on every world load and on /reload. Coordinates are
# absolute and near the flat world's spawn, which is what makes a run reproducible.

# --- scoreboard: read-scoreboard ---
scoreboard objectives remove mcagents
scoreboard objectives add mcagents dummy {"text":"Probe Stats","color":"gold","bold":true}
scoreboard objectives setdisplay sidebar mcagents
scoreboard players set Coins mcagents 1200
scoreboard players set Level mcagents 42
scoreboard players set Deaths mcagents 3

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
setblock 3 -60 0 oak_sign[rotation=8]{front_text:{messages:[{text:"Welcome",color:"yellow"},{text:"to the shop"},{text:""},{text:"line four"}]},back_text:{messages:[{text:"back side"},{text:""},{text:""},{text:"four again"}]}}

# --- a hologram: read-displays ---
# text and CustomName hold components, not JSON strings, the same as the sign's messages above. A
# quoted one makes the hologram literally read {"text":"Hologram line"}, and mineflayer's lenient
# parsing hid that while making the fabric bot -- which reads the component the client was given --
# look like the one at fault.
kill @e[type=text_display,tag=mcagents]
summon text_display 3 -58 3 {Tags:["mcagents"],billboard:"center",text:{text:"Hologram line",color:"aqua"}}

# --- an entity to find and interact with: find-entity, attack-entity, interact-entity ---
kill @e[type=cow,tag=mcagents]
summon cow 5 -60 5 {Tags:["mcagents"],CustomName:{text:"Probe Cow"},CustomNameVisible:1b,NoAI:1b,Silent:1b}

# --- a container with custom names and lore: open-container, read-window ---
setblock 1 -60 3 air
# Slot is a byte and capitalised. Lower-case "slot" is accepted and ignored, so all three items
# land in slot 0 and only the last survives -- a chest that looks filled and holds one thing.
# custom_name and lore hold components too. Quoting them put the braces in the item's name, and a
# reader lenient enough to parse any string that looks like JSON hid it.
setblock 1 -60 3 chest{Items:[{Slot:0b,id:"minecraft:diamond_sword",count:1,components:{"minecraft:custom_name":{text:"Excalibur",color:"aqua",italic:false},"minecraft:lore":[{text:"A sword a server named",color:"gray"},{text:"Second line of lore",color:"dark_gray"}]}},{Slot:4b,id:"minecraft:cooked_beef",count:12},{Slot:26b,id:"minecraft:emerald",count:3}]}

# --- a furnace with fuel and input: smelt-item ---
setblock 1 -60 5 air
setblock 1 -60 5 furnace[facing=north]

# --- the blocks find-blocks is asked for, in a known place ---
fill 7 -60 -2 9 -60 0 minecraft:diamond_block

# --- water to fish in: fish ---
# A flat world has none, and a rod cast at dry ground never gets a bite, so the tool would only
# ever be able to report its own timeout.
# Wide enough that a cast lands in it whichever way the bot happens to be facing. A three by three
# pool sounds like plenty and is not: the bobber flies several blocks, and a bot whose aim was a
# little off put it on dry grass and then waited out the whole timeout for a bite that could not
# come. Well clear of the blocks above, which sit at positive x.
fill -10 -61 -10 -2 -61 -2 minecraft:water
fill -10 -60 -10 -2 -60 -2 minecraft:water

# --- the same inventory for everyone: list-inventory and find-item ---
# Without this the comparison is about what each bot has happened to pick up. Dropped items go too,
# or a run that tested drop-held-item leaves the next one reading a different floor.
clear @a
give @a minecraft:diamond 3
give @a minecraft:oak_planks 24
give @a minecraft:bow 1
kill @e[type=item]

# --- a wall to walk around: move-to-position ---
# Straight-line walking is enough in an empty world and useless in a built one, so the fixture has
# something in the way. Three blocks high, so it cannot be jumped, with the way round it eight
# blocks north: a bot that reports "could not reach" here has no pathfinding, and one that arrives
# by the short way has not gone round anything.
fill 14 -60 -6 14 -58 6 minecraft:stone

say mc-agents fixture is in place
