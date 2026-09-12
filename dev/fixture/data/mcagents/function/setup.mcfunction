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
kill @e[type=text_display,tag=mcagents]
summon text_display 3 -58 3 {Tags:["mcagents"],billboard:"center",text:'{"text":"Hologram line","color":"aqua"}'}

# --- an entity to find and interact with: find-entity, attack-entity, interact-entity ---
kill @e[type=cow,tag=mcagents]
summon cow 5 -60 5 {Tags:["mcagents"],CustomName:'{"text":"Probe Cow"}',CustomNameVisible:1b,NoAI:1b,Silent:1b}

# --- a container with custom names and lore: open-container, read-window ---
setblock 1 -60 3 air
# Slot is a byte and capitalised. Lower-case "slot" is accepted and ignored, so all three items
# land in slot 0 and only the last survives -- a chest that looks filled and holds one thing.
setblock 1 -60 3 chest{Items:[{Slot:0b,id:"minecraft:diamond_sword",count:1,components:{"minecraft:custom_name":'{"text":"Excalibur","color":"aqua","italic":false}',"minecraft:lore":['{"text":"A sword a server named","color":"gray"}','{"text":"Second line of lore","color":"dark_gray"}']}},{Slot:4b,id:"minecraft:cooked_beef",count:12},{Slot:26b,id:"minecraft:emerald",count:3}]}

# --- a furnace with fuel and input: smelt-item ---
setblock 1 -60 5 air
setblock 1 -60 5 furnace[facing=north]

# --- the blocks find-blocks is asked for, in a known place ---
fill 7 -60 -2 9 -60 0 minecraft:diamond_block

say mc-agents fixture is in place
