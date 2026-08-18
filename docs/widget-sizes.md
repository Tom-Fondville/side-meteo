# The home-screen widget, size by size

The widget has one entry in the launcher's picker and six shapes. Drop it, drag its handles, and
the layout changes to suit the space — you never pick a size up front.

On Android 12 and later every shape is handed to the launcher at once and it swaps between them
itself. On Android 8 to 11 that API does not exist, so the shape is chosen from the size the
launcher reports and repainted when you finish resizing. The result looks the same; only the
plumbing differs.

## What each shape shows

Sizes in the first column are the grid cells on a phone whose cells measure about 72 × 78 dp — a
480 px wide screen at 240 dpi. Yours may differ by a cell either way; the dp thresholds below are
what actually decide.

| Grid | Shape | Shows | Refresh glyph |
|---|---|---|---|
| 1×1 | `TINY` | Condition emoji, current temperature, city, today's min/max | no |
| 2×1 | `COMPACT` | The same, with the min/max beside the temperature rather than under the city | no |
| 2×2 | `TALL` | The above, plus the next three hours as rows | no |
| 3×1, 4×1 | `ROW` | City, timestamp, emoji, temperature, condition, today's min/max | yes |
| 3×2, 4×2 | `FULL` | The above, plus the next four hours as columns with rain probability | yes |
| 3×3, 4×3 | `LARGE` | The above, plus the next three days as rows with their range and rain total | yes |

```
1×1             2×1                  2×2
┌─────────┐    ┌──────────────┐    ┌──────────────┐
│ 🌤 23°  │    │ 🌤 23°  20 / │    │ 🌤 23°       │
│Bordeaux │    │Bordeaux  31° │    │ Bordeaux     │
│20 / 31° │    └──────────────┘    │ 20 / 31°     │
└─────────┘                        │ 11h 🌤   25° │
                                   │ 12h 🌤   27° │
                                   │ 13h 🌤   28° │
                                   └──────────────┘

3×1 / 4×1                          3×2 / 4×2
┌────────────────────────────┐    ┌────────────────────────────┐
│ Bordeaux         10:07  ↻  │    │ Bordeaux         10:07  ↻  │
│ 🌤 23° Plutôt dég. 20 / 31° │    │ 🌤 23°           20 / 31°  │
└────────────────────────────┘    │ Plutôt dégagé              │
                                   │ 11h   12h   13h   14h      │
                                   │ 🌤    🌤    🌤    🌤       │
                                   │ 25°   27°   28°   28°      │
                                   │ 0 %   0 %   0 %   0 %      │
                                   └────────────────────────────┘

3×3 / 4×3
┌────────────────────────────┐
│ Bordeaux         10:07  ↻  │
│ 🌤 23°           20 / 31°  │
│ Plutôt dégagé              │
│ 11h   12h   13h   14h      │
│ 🌤    🌤    🌤    🌤       │
│ 25°   27°   28°   28°      │
│ 0 %   0 %   0 %   0 %      │
│ ────────────────────────── │
│ mar. 🌧 18 / 27°   4,2 mm  │
│ mer. 🌤 19 / 28°   0,0 mm  │
│ jeu. ⛅ 17 / 25°   0,6 mm  │
└────────────────────────────┘
```

## How a shape is chosen

`widgetSizeFor(widthDp, heightDp)` in `app/src/main/java/fr/sidemeteo/WidgetSize.kt` is the whole
rule, and `WidgetSizeTest` pins every boundary:

| Width | Height | Shape |
|---|---|---|
| under 100 dp | any | `TINY` |
| 100–199 dp | under 100 dp | `COMPACT` |
| 100–199 dp | 100 dp or more | `TALL` |
| 200 dp or more | under 100 dp | `ROW` |
| 200 dp or more | 100–199 dp | `FULL` |
| 200 dp or more | 200 dp or more | `LARGE` |

Width is read before height because a narrow tile cannot borrow a wide shape however tall it grows:
at two cells across, hours have to be rows rather than columns. Nonsense input — a launcher reports
0 before it has measured the widget — falls to `TINY` rather than throwing, because this runs on the
render path.

## Why the small shapes have no ↻

Below 200 dp the refresh glyph would cost more room than it earns, so `TINY`, `COMPACT` and `TALL`
drop it. They still refresh: the hourly tick reaches every shape, opening the app repaints the tile
after a successful fetch, and tapping the tile at any size opens the app.

## Behaviour common to every shape

- **Tapping the tile** opens the app. **Tapping ↻**, where present, fetches immediately.
- **Refresh** happens on the system's hourly tick. Android will not schedule widget updates more
  often than every 30 minutes without a scheduling dependency, and this app has none.
- **Offline** the tile keeps the last forecast it fetched and labels it: `10:07` when the data is
  from today, `17/08 03:39` when it is older, so a day-old reading cannot be mistaken for fresh.
- **No city chosen** shows "Choisir une ville"; **a city with no usable cache** shows
  "Météo indisponible". Tapping either opens the app.
- **Missing values** render as `—` rather than disappearing.
- One city at a time — whichever the app has selected. There is no per-widget city.

## Adding a shape

1. Add the case to `WidgetSize` and to `widgetSizeFor`, with its thresholds.
2. Add its boundaries to `WidgetSizeTest` — including that the existing shapes do not move.
3. Write `res/layout/widget_weather_<shape>.xml`. Reuse the existing ids wherever the meaning
   matches, so the builder helpers work unchanged.
4. Add a `build<Shape>` to `WidgetViews.kt` and a branch to `buildWidgetViews`. The `when` is
   exhaustive, so the compiler will not let you forget.
5. Add the shape to the `SizeF` map in `buildWidgetViewsFor`, keyed by the smallest footprint it
   suits.

One trap worth knowing: a `RemoteViews` action aimed at an id the chosen layout does not contain
throws when the launcher applies it — not at compile time. That is why there is one builder per
shape rather than one builder with branches, and why `TINY` never touches the clock.
