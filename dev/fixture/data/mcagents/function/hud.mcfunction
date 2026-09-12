# The things that are sent rather than placed: an action bar, a title, a sound, a particle.
#
# Not on load, because each one is a moment rather than a state. A bot that wants to read one asks
# for it: /function mcagents:hud through run-command, and then read-action-bar or wait-for-title.

# Custom font and colour, which is the case a plain-text reader gets wrong: the glyphs are private
# use area codepoints that mean nothing outside the font, and the segments are what carry that.
title @a actionbar [{"text":" ","font":"minecraft:illageralt"},{"text":"Mana ","color":"aqua"},{"text":"40","color":"white"},{"text":"/40","color":"gray"}]

# Several extra segments, so a title that is one string on the wire is visibly wrong.
title @a title [{"text":"Wave ","color":"gold"},{"text":"3","color":"red","bold":true}]
title @a subtitle [{"text":"survive ","color":"gray"},{"text":"60s","color":"white"}]

playsound minecraft:entity.experience_orb.pickup master @a
particle minecraft:happy_villager 0 -59 0 1 1 1 0 20
