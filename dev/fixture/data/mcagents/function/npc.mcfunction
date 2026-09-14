# NPCs as a server with a resource pack draws them: nothing on the entity says who it is, and the
# name a player knows it by floats over it. interact-entity and attack-entity find these by label.
#
# Its own function so a case can put them back without resetting the rest of the world: a click is
# recorded on the entity itself, and the next case needs one that has not been clicked.
#
# At fixed coordinates north of everything else, clear of the pool and the mobs the attack cases
# summon, on dry ground a bot can walk round. Forceloaded for the same reason the pool is: a summon
# into a chunk nothing has loaded yet places nothing, and the setup at boot runs before any player.
forceload add 2 -17 10 -16
# Out of the way before it dies: a killed mannequin lies where it stood for a second of dying, and a
# case that put the NPCs back found that one under the label, and clicked it as it left the world.
tp @e[type=mannequin,tag=mcagents_npc] 2.5 -250 -16.5
kill @e[tag=mcagents_npc]

# A mannequin with no name, the way most NPCs on the target server are drawn, and its label in the
# pack's own font with an icon glyph in front. A mannequin does nothing when clicked with an empty
# hand, so the server keeps no record of a bare right-click; a named name tag is what vanilla lets a
# player use on it, and the name it leaves is the record. immovable keeps a hit from knocking it out
# from under its label, and the health a hit takes is the other record.
summon mannequin 2.5 -60 -16.5 {Tags:["mcagents_npc"],immovable:1b,hide_description:1b,Health:20f}
summon text_display 2.5 -57.6 -16.5 {Tags:["mcagents_npc"],billboard:"center",text:{text:"",extra:[{text:"",font:"hyperfarm:nametag/icons"},{text:"Fisher Kim",font:"hyperfarm:nametag/label"}]}}

# A model's hitbox with a label and nothing else: an interaction entity is what a ModelEngine model
# is clicked through, and it keeps the last player that right-clicked or hit it.
summon interaction 6.5 -60 -16.5 {Tags:["mcagents_npc"],width:0.8f,height:1.9f}
summon text_display 6.5 -57.6 -16.5 {Tags:["mcagents_npc"],billboard:"center",text:{text:"Well Keeper",color:"gold"}}

# The older way to float a name: an invisible marker armor stand showing its own. Over a hitbox of
# its own, tagged apart so a case reading the one under Well Keeper does not read this one.
summon interaction 10.5 -60 -16.5 {Tags:["mcagents_npc","mcagents_hologram"],width:0.8f,height:1.9f}
summon armor_stand 10.5 -58.2 -16.5 {Tags:["mcagents_npc"],Invisible:1b,Marker:1b,NoGravity:1b,CustomNameVisible:1b,CustomName:{text:"Seed Merchant",color:"green"}}
