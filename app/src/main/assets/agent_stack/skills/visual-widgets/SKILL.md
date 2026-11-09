---
name: visual-widgets
description: Show the user a live, interactive visual in the chat — sliders, stat cards, charts, gauges, tables, tabs, toggles, an animated dot grid, copy/ask buttons — by writing a fenced `mike-widget` JSON block in the reply. Use it when the user wants to explore numbers, compare options, tune parameters, see a dashboard or "see it" instead of reading it. Not for a plain answer.
---

# Visual widgets

A reply can contain a fenced block with the language `mike-widget`. The chat draws it as a native, interactive card: no browser, no network. The user moves a slider and the numbers, charts and gauges next to it change at once, with no new turn.

```mike-widget
{"body": [{"type": "text", "text": "Hello"}]}
```

Write the block in the middle of a normal reply: one or two sentences before it, what to look at after it. Several blocks in one reply are fine. While the reply streams, the user sees a skeleton until the closing fence arrives.

## When to use it

- Tuning or comparing: "what if I get 200 calls a day", a loan, a budget, a workout plan, a trade-off.
- A small dashboard of real numbers you just gathered (phone battery, storage, a week of data).
- A chart, a gauge or a table is clearer than prose.

Do not use it for a short answer, a list of steps, or code. Do not use it to decorate. If you only have a few facts, write them.

## Honesty about data

A chart draws what you give it. Use real numbers when you have them. If the numbers are made up (a model, an example), say so in the `subtitle`, such as `"Sample data"`. Never present generated values as measurements.

## Shape

```
{
  "title": "...", "subtitle": "...",
  "dir": "rtl" | "ltr",          // optional; otherwise decided from the text
  "body": [ block, block, ... ]  // required, up to 150 blocks, cards nest 5 deep
}
```

Text values accept `{expression}` holes: `"Total {round(perDay * 7)} calls"`. A number field accepts a number or `"=expression"`. Any block takes `"when": "=expression"` and is hidden while it is false. Unknown keys are ignored. A problem in one block never hides the others.

## Inputs (they hold the state)

Every input needs a unique `id` of letters, digits and `_`. Its current value is a variable in every expression.

- `slider`: `id`, `label`, `min`, `max`, `step`, `value`, `unit`, `format`.
- `select` (dropdown) or `tabs` (chips): `id`, `label`, `options` as `[{"value": 7, "label": "7 days"}, ...]` or plain strings, `value`. The variable is the option's value (a number or a string).
- `toggle`: `id`, `label`, `value` true/false. The variable is 1 or 0.
- `input`: `id`, `label`, `hint`, `value`. The variable is text.

## Display blocks

- `heading`: `text`, `level` 1-3. `text`: `text`, `tone` muted/good/warn/bad.
- `stats`: `items` (up to 6) of `{label, value, unit, format, icon, delta, caption, color}`. `delta` shows a green or red change arrow. Icons: check, target, bolt, clock, up, down, star, heart, user, money, chart, warning, info, error, home, fire, cloud, battery, wifi, bell, calendar, mail, shield, lock, speed, memory, storage, cart, water, settings.
- `chart`: see below.
- `gauge`: `label`, `value`, `min`, `max`, `unit`, `format`, `caption`, `color`.
- `progress`: `label`, `value`, `max`, `unit`, `format`, `color`.
- `table`: `columns`, `rows` (up to 200 rows, 8 columns; cells are text or numbers, text accepts `{holes}`), `caption`.
- `dots`: an animated grid. `cols`, `rows` (up to 48), `value` is an expression for brightness 0..1 per cell, `color`, `shape` "square", `height`, `title`. Variables per cell: `x`, `y` (0..1), `i`, `j`, `cols`, `rows`, and `t` (seconds). Using `t` makes it animate.
- `card` (`title`, `subtitle`, `children`), `row` (`children`, side by side), `divider`.
- `button`: `label`, `action` "copy" (copies the current values and chart data as JSON) or "ask" (sends the label and that JSON back to you as the user's next message). `prompt` is an optional lead line for "ask". Use "ask" to let the user hand you their tuned settings.

Formats: `int`, `decimal` (1 digit), `decimal2`, `percent`, `compact` (1.2K, 3M). Colors: blue, purple, orange, green, red, teal, yellow, pink, gray or `#RRGGBB`. Leave the color out to use the palette in order.

## Charts

```
{"type": "chart", "id": "activity", "title": "...", "kinds": ["bar", "line"],
 "x": ["Mon", "Tue", "Wed"],                       // or "points": 14 with "xLabel": "{i + 1}"
 "series": [
   {"name": "Done",   "color": "blue", "values": [4, 6, 5]},
   {"name": "Failed", "values": "=perDay * 0.1 * (0.8 + 0.4 * noise(i))", "when": "=showFailed"}
 ],
 "stacked": false, "height": 180, "unit": "", "format": "int", "yMin": 0}
```

- `kinds` lists the chart types the user can switch between: `bar`, `line`, `area`, `pie`, `donut`, `spark`. The first one is shown. `kind` picks a single type.
- A series' `values` is a list of numbers (each may be `"=expr"`), or one expression evaluated for each point with `i` (0-based) and `n` (the number of points).
- Pie and donut take `"slices": [{"label": "A", "value": 60, "color": "blue"}, ...]`.
- Touching or dragging over a point shows its exact value. A chart with an `id` or `title` appears in the copied JSON.

## Expressions

A small safe language over numbers and text: `+ - * / % ^`, comparisons, `&&`, `||`, `!`, `cond ? a : b`, text in single quotes (`mode == 'wave'`), constants `pi`, `tau`, `true`, `false`. No loops, no assignment, no calls out. Max 1000 characters.

Functions: `abs sign floor ceil fract round(x[, digits]) sqrt pow exp log sin cos tan atan2 hypot dist(x1,y1,x2,y2) min max sum avg clamp(x,lo,hi) mix(a,b,t) step smooth(a,b,x) mod noise(x[,y[,z]]) rand(i)`.

`noise` is smooth and repeatable, so a "random looking" series does not jump when a slider moves.

## Example

```mike-widget
{
  "title": "Daily calls",
  "subtitle": "Sample data",
  "body": [
    {"type": "stats", "items": [
      {"label": "Per week", "value": "=perDay * 7", "format": "int", "icon": "check"},
      {"label": "Success", "value": "=rate", "unit": "%", "icon": "target"}
    ]},
    {"type": "slider", "id": "perDay", "label": "Calls per day", "min": 20, "max": 400, "step": 10, "value": 120},
    {"type": "slider", "id": "rate", "label": "Success rate", "min": 50, "max": 100, "value": 92, "unit": "%"},
    {"type": "chart", "id": "week", "kinds": ["bar", "line"], "points": 7, "xLabel": "D{i + 1}",
     "series": [{"name": "Succeeded", "color": "blue", "values": "=perDay * rate / 100 * (0.8 + 0.4 * noise(i * 0.7))"}]},
    {"type": "button", "label": "Send my settings", "action": "ask"}
  ]
}
```

## Rules of thumb

- Aim for one screen: 3 to 8 blocks. Put the controls above what they change.
- The text must be in the user's language. Set `"dir": "rtl"` for Hebrew or Arabic; charts stay left to right on purpose.
- Keep the JSON valid. A trailing comma is forgiven; a missing brace is not, and then the user sees the raw block with the reason.
- When the user answers through an "ask" button, the message ends with a ```json block of their values. Read it and continue from those numbers.
