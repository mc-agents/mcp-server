# fabric recipes: dialogs, books, windows, screenshots

Run on the development cluster with a fabric bot `qa-fab-2` and an azalea observer `qa-obs-3`. Every
reply below is verbatim.

## A dialog's controls and its button

The fixture's `mcagents:settings` dialog has a checkbox `notify`, a cycle `mode` and a slider `speed`
(0 to 10, step 2), and its Save button runs
`scoreboard players set $(mode)_$(notify) mcagents $(speed)`.

```
(observer) run-command "dialog show qa-fab-2 mcagents:settings"   -> "Displayed dialog to qa-fab-2"
wait-for-dialog pattern="Probe settings"
read-dialog count=1
  -> "Probe settings | ... | buttons: Save, Close | inputs: notify (checkbox) = false,
      mode (one of slow, fast, turbo) = slow, speed (0 to 10, step 2) = 4"
set-dialog-input key=notify value=true      -> "Set \"notify\" to true, was false."
set-dialog-input key=mode value="Fast"      -> "Set \"mode\" to fast (shown as \"Fast\"), was slow."
set-dialog-input key=speed value=5          -> "Set \"speed\" to 6, was 4. 5 is not one of the slider's steps, so it moved to the nearest."
set-dialog-input key=speed value=11         -> error "\"speed\" goes from 0 to 10, and 11 is outside it"
press-dialog-button label="Save"            -> "pressed \"Save\", then \"Run Command\" on Confirm Command Execution"
```

Expect: `(observer) scoreboard players get fast_yes mcagents` -> `fast_yes has 6 [Probe Stats]`.

- Inputs are keyed by the dialog's own keys (what `$(key)` reads), not by their labels. A cycle
  takes an option's id or its shown text; a slider moves to its nearest step, and the reply says so.
- A button that runs a command goes through the client's confirmation screen, and the reply names
  the confirmation that was pressed.
- `dialog show` of a dialog the server does not have answers
  `Can't find element 'mcagents:settings' in registry 'minecraft:dialog'`. A datapack's dialogs are
  read when the server starts, so a new one needs a restart, not only `/reload`.
- `read-dialog` works on azalea too; setting and pressing need fabric.

## A book

```
(observer) run-command "give qa-fab-2 written_book[written_book_content={title:\"Probe Guide\",author:\"Probe\",pages:[{text:\"Find the shrine.\"},{text:\"Bring back the relic.\"}]}] 1"
wait-for-item pattern="written_book"
equip-item itemName="written_book"          -> "Equipped written_book to hand."
use-held-item                               -> "Used written_book x1 in the main hand."
read-book
  -> "\"Probe Guide\" by Probe in hand, 2 pages, open at page 1:
        1. Find the shrine.
        2. Bring back the relic."
close-window                                -> "Closed the book \"Book View Screen\"."
```

Every page comes back at once; a screenshot would show one.

## A chest, clicked

```
(observer) run-command "data get block 1 -60 3 Items[{Slot:0b}].id"   -> "\"minecraft:diamond_sword\""
open-container x=1 y=-60 z=3
  -> "window \"[gui/header] Probe Chest\" (type minecraft:generic_9x3, 63 slots, container 0-26, player inventory 27-62)
        0: Excalibur [diamond_sword] x1 ..."
click-slot slot=0    -> "Left-clicked slot 0.\n  slot 0: Excalibur [diamond_sword] x1 -> empty\n  cursor: Excalibur [diamond_sword] x1"
click-slot slot=1    -> "Left-clicked slot 1.\n  slot 1: empty -> Excalibur [diamond_sword] x1\n  cursor: empty"
close-window         -> "Closed window \"[gui/header] Probe Chest\"."
```

Expect: `data get block 1 -60 3 Items[{Slot:1b}].id` -> `"minecraft:diamond_sword"`, and
`Items[{Slot:0b}]` -> `Found no elements matching Items[{Slot:0b}]`.

- Close the window before a read that matters: an item left on the cursor goes back to the inventory
  when it closes, and the chest's NBT is what the server saved, not what the client drew.
- Slot numbers are the window's: here the chest is 0-26 and the bot's own inventory 27-62, hotbar last.

## A screenshot

```
screenshot    -> "captured a 854x480 frame (209139 bytes)" plus one image/png part
```

Taken with the settings dialog open after the three `set-dialog-input` calls, it showed the checkbox
ticked, "Mode: Fast" and "Speed: 6" -- the look agreeing with `read-dialog` and the score.
