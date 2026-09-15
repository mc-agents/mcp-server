# A sidebar drawn one team per line, which is how a plugin writes a quest log on one: the owner of
# each score is a colour code nobody sees, the team it is on carries the line as its prefix and
# suffix, and the objective hides the numbers. What a player reads is the teams, and a bot that
# read the owners and the scores read three colour codes counting down.
#
# Not on load, because the sidebar the other cases read is mcagents, and this takes the slot from
# it. The case that runs this puts mcagents back and removes what this made.
scoreboard objectives remove mcagents_lines
team remove mcagents_l3
team remove mcagents_l2
team remove mcagents_l1

scoreboard objectives add mcagents_lines dummy {"text":"Quest","font":"hyperfarm:sidebar/title"}
scoreboard objectives modify mcagents_lines numberformat blank

# A label in the pack's own font and a count after it, on either side of an owner that is nothing
# but a colour code.
team add mcagents_l3
team modify mcagents_l3 prefix {"text":"Harvest wheat","font":"hyperfarm:sidebar/label"}
team modify mcagents_l3 suffix {"text":"3/10","color":"yellow"}
team join mcagents_l3 §7
scoreboard players set §7 mcagents_lines 3

# A spacer: a team with a colour and nothing to say, so the line is empty and still takes a row.
team add mcagents_l2
team modify mcagents_l2 color gray
team join mcagents_l2 §8
scoreboard players set §8 mcagents_lines 2

# A line whose owner has a display name of its own, and a number format of its own in place of the
# hidden score: the fixed text is what the score column shows.
team add mcagents_l1
team modify mcagents_l1 prefix "» "
team join mcagents_l1 §9
scoreboard players set §9 mcagents_lines 1
scoreboard players display name §9 mcagents_lines {"text":"Reward","color":"gold"}
scoreboard players display numberformat §9 mcagents_lines fixed {"text":"50g"}

# The highest score on the objective, and never drawn: a sidebar leaves out every owner starting
# with '#', which is where a plugin keeps a counter it does not want shown.
scoreboard players set #hidden mcagents_lines 9

scoreboard objectives setdisplay sidebar mcagents_lines
