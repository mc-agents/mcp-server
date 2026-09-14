# press-input recipes

`press-input` presses one key, `repeat` times, each held `holdTicks` and `intervalTicks` apart. Two
options make it react inside the bot rather than after an MCP round trip:

- `after: {feed, pattern}` -- press nothing until a line matching `pattern` shows on `actionBar`,
  `title` or `effect`, then press on that tick. Fails if it does not show within `timeoutMs`.
- `until: {feed, pattern}` -- stop pressing, and let go of a held key, on the tick a line shows.

`pattern` is a regular expression matched against the text without font labels or glyphs; a sound
is its id, `minecraft:entity.fishing_bobber.splash`. The answer says why the presses ended: every
one was made, `until` showed ("stopped at 14 of 100 when ..."), or `timeoutMs` ran out.

Every recipe below ran against the fixture plugin on the development cluster. Its objectives
(`fx_jump`, `fx_sneak`, `fx_slot`, `fx_left`, `fx_right`, `fx_catch`, `fx_miss`, ...) count what the
server received, and are what the expectations read.

## A conversation on the action bar: jump, hotbar, sneak

```
press-input key=hotbar slot=3                  # start from a slot that is not an answer
(observer) run-command "fixture talk <bot>"
wait-for-action-bar pattern="Fine day"
press-input key=jump                           # next page
wait-for-action-bar pattern="What will you plant"
press-input key=hotbar slot=1                  # answer 1
wait-for-action-bar pattern="it is"
press-input key=sneak                          # leave
```

Expect: `tag <bot> list` has `fixture_choice_1` and `fixture_talk_left`.

- Wait for the page before each key. The conversation reacts to a key only on the page that asks for
  it, so a key sent before that page arrives is lost.
- `wait-for-action-bar` returns at once when the latest line already matches ("The latest action bar
  already shows: ..."), so it is safe to call after the page has arrived.
- Selecting the slot that is already selected sends nothing; move off it first.

## A fishing bite: `after` on the splash

```
(observer) run-command "scoreboard players reset <bot> fx_catch"
(observer) run-command "item replace entity <bot> weapon.mainhand with minecraft:fishing_rod"
wait-for-item pattern="fishing_rod"
press-input key=use                            # cast
press-input key=use after={feed: effect, pattern: "entity\\.fishing_bobber\\.splash"} timeoutMs=10000
```

Answer: `Waited 1119ms for the effect feed to match /entity\.fishing_bobber\.splash/
("minecraft:entity.fishing_bobber.splash"), then pressed use once.`
Expect: `scoreboard players get <bot> fx_catch` -> `<bot> has 1 [fx_catch]`.

- Waiting with `wait-for-effect` and then pressing is a round trip, which can miss a short window;
  `after` presses on the tick the sound arrives. `wait-for-effect` is fabric-only; `after` on the
  effect feed works on both kinds.
- The fixture bites 60 ticks after the cast, in or out of water, with a 40-tick window.

## A 2-tick timing window: `after` on the action bar

```
(observer) run-command "tp <bot> 2 -60 0 0 -90"    # look up, so a creative click breaks nothing
(observer) run-command "fixture gather <bot>"      # a cue 30-70 ticks later, at a moment nobody knows
press-input key=attack after={feed: actionBar, pattern: "JUST"} timeoutMs=10000
```

Expect: `scoreboard players get <bot> fx_gather_hit` counts the round; `fx_gather_ticks` and
`fx_gather_ms` say how long the click took from the cue, as the server measured it.

- Start the press right after the command. `after` only hears lines that arrive while it listens, so a
  press started after the cue waits for a cue that already came.
- Measured on the e2e server, five rounds per kind: fabric 0 ticks every time (35-44 ms), azalea 0 or
  1 tick (32-57 ms). A 2-tick window is caught on both; a 1-tick window is not certain on azalea.
- Do not `wait-for-action-bar` and then press: the round trip alone is longer than the window.

## Repeated clicks: `until`

```
(observer) run-command "tp <bot> 2 -60 0 0 -90"    # look up, so a creative click breaks nothing
(observer) run-command "scoreboard players reset <bot> fx_left"
press-input key=attack repeat=100 intervalTicks=2 until={feed: actionBar, pattern: "enough"} timeoutMs=20000
```

With the server sending `enough clicks` to the action bar two seconds in, the answer was
`Pressed attack 14 times, 2 ticks apart; stopped at 14 of 100 when the actionBar feed matched
/enough/ ("enough clicks").` and `fx_left` read `14`: every click reported is one the server
received.

- `intervalTicks` of at least 1 keeps each press a press of its own. A held key is one press.
- Set `timeoutMs` above `repeat x (holdTicks + intervalTicks) x 50ms`, or the run ends as
  `timeoutMs ran out after N of M`.
