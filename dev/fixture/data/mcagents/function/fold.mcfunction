# An action bar held up the way a plugin holds one: the same line sent again every few ticks, for as
# long as #fold mcagents_fold counts down. A bot folds that into one run; one that does not puts a
# line into the feed for every packet.
title @a actionbar {"text":"Fold probe"}
scoreboard players remove #fold mcagents_fold 1
execute if score #fold mcagents_fold matches 1.. run schedule function mcagents:fold 5t
