# Tessary Design System

The full token reference for the Tessary frontend. The source of truth is
[`tokens.css`](./tokens.css), imported once by
[`src/index.css`](./src/index.css); this doc mirrors it. Component primitives
live in [`src/ui/`](./src/ui/). For the imperative do/don't, see
[`AGENTS.md`](./AGENTS.md).

## Philosophy

Dense, dark, instrument-panel calm. The app is built for reading a lot of data
without fatigue: a near-black floor, an achromatic grey ramp for everything
structural *and* everything interactive, and exactly three hues, each with one
job. Red is error, green is success, blue is info and warning. **All three are
status only, used sparingly.** None of them is an accent. Nothing else in the
UI carries hue: chart series have their own palette that never leaks into
chrome, and brand pink never leaves the logo.

**The accent is grey.** The primary button, active nav and selection all come
from the top of the grey ramp, so no hue ever means "clickable". That is what
lets the three hues stay meaningful: when red, green, or blue appears as a fill
or a dot, it is a verdict about the data, never a control. A hue on screen is a
signal; the UI itself is grey.

**Navigation is the one blue exception.** A link and the focus ring use
`--color-link` (the same `#70B6FD` as info). The boundary is navigation, not
appearance: an `<a>` or a `<Link>` is blue because it takes you somewhere else;
an inline text button that only acts on the current page ("Copy", "Remove",
"Clear all filters") stays grey even though it looks like a link. A link is
always inline text with an underline affordance and never a fill, so it cannot
be confused with a filled info chip — and for that reason a *filled* blue link
does not exist in this system.

**Elevation is surface color, not shadow.** Four levels, `bg` → `surface` →
`raised` → `overlay`, each one step lighter; a surface inside a surface steps
up exactly one level and never skips. Shadows stay barely-there.

Everything is token-driven across three layers (primitives → semantic →
component). Components consume semantic tokens only, as Tailwind utilities or
`var(--…)`.

## Tokens

All values come from the `@theme` block in `tokens.css`. Most are exposed as
Tailwind utilities (`bg-surface`, `text-muted`, `border-border-strong`, …); the
rest are read via `var(--…)`. The primitive ramp (`--primitive-level-*`,
`--primitive-grey-*`, `--primitive-red/green/blue`, `--primitive-viz-*`) is
referenced by the semantic layer and never by a component.

### Surfaces

| Token | Value | Level | Usage |
|---|---|---|---|
| `--color-bg` | `#121212` | 0 | canvas / page floor (`bg-bg`) |
| `--color-surface` | `#1B1B1B` | 1 | cards, sidebar, sheet (`bg-surface`) |
| `--color-raised` | `#242424` | 2 | nested panel, input fill (`bg-raised`) |
| `--color-overlay` | `#2E2E2E` | 3 | modal, menu, popover, tooltip, toast (`bg-overlay`) |

### Text

Body copy is grey-50 for reading and grey-300 for scanning. It is never a status
hue.

| Token | Value | Ramp | Usage |
|---|---|---|---|
| `--color-fg` | `#F0F0F0` | grey-50 | primary text, headings (`text-fg`) |
| `--color-fg-secondary` | `#C8C8C8` | grey-300 | secondary text, table cells, pill labels (`text-fg-secondary`) |
| `--color-muted` | `#A0A0A0` | grey-500 | timestamps, captions, column headers (`text-muted`) |
| `--color-subtle` | `#6E6E6E` | grey-600 | placeholder, disabled label, decorative icons (`text-subtle`) |

### Borders

The ramp has one border grey. Both names resolve to it; a "strong" edge is
expressed by the focus ring or `accent-edge` (grey-600, one step lighter,
reserved for accent outlines), not by re-pointing these.

| Token | Value | Usage |
|---|---|---|
| `--color-border` | `#3C3C3C` | dividers, hairlines, disabled fill, progress track (`border-border`) |
| `--color-border-strong` | `#3C3C3C` | same value, kept for call sites (`border-border-strong`) |

### Accent (grey) — the interactive treatment

Every interactive treatment is achromatic and reuses the grey ramp, except the
link (below). The accent is the top of the ramp and hover/pressed walk down it
one existing step at a time.

| Token | Value | Ramp | Usage |
|---|---|---|---|
| `--color-accent` | `#F0F0F0` | grey-50 | primary button fill, active nav, selection (`bg-accent`/`text-accent`) |
| `--color-accent-hover` | `#C8C8C8` | grey-300 | accent hover (`bg-accent-hover`) |
| `--color-accent-pressed` | `#A0A0A0` | grey-500 | accent pressed (`bg-accent-pressed`) |
| `--color-accent-text-on` | `#121212` | level-0 | label on an accent fill (`text-accent-text-on`) |
| `--color-accent-subtle` | `rgba(240,240,240,0.12)` | grey-50 @ 12% | low-emphasis accent bg: badges, selected chip (`bg-accent-subtle`) |
| `--color-accent-edge` | `#6E6E6E` | grey-600 | low-emphasis accent border (`border-accent-edge`) |
| `--color-link` | `#70B6FD` | blue | anything that navigates: `<a>`, `<Link>` (`text-link`) |
| `--color-link-hover` | `#A5D0FE` | blue, lifted | link hover only, never a fill (`text-link-hover`) |

Contrast holds in both directions on every step. The accent on the level-0
floor: grey-50 16.4:1, grey-300 11.2:1, grey-500 7.2:1. The `#121212` label on
the fill is the same ratio each time (it is the same pair), so the label never
changes color across states. `accent-subtle` is translucent, matching the
status `-subtle` fills, so it reads on any surface level; `accent-edge` is the
one grey lighter than `border`.

### Interactive states

Hover and selected share level 2 because the ramp has no step between them. A
selected row or nav item is told apart by its text (grey-50, weight 500), not
by a hue and not by a different fill.

| Token | Value | Usage |
|---|---|---|
| `--color-hover` | `#242424` | hover bg on any surface (`bg-hover`) |
| `--color-selected` | `#242424` | selected row / active nav bg (`bg-selected`) |
| `--color-pressed` | `#2E2E2E` | pressed bg (`bg-pressed`) |

### Status

Three hues, used sparingly and only to denote status: success, failure,
info/warning. Warning collapses into the same blue as info: the triad has no
fourth hue. All three behave the same way: a solid fill is fine for a chip or
badge, and every solid status fill takes `#121212` as its label; there is no
white-label exception. The `-subtle` fills are translucent so they work on any
surface level.

| Token | Value | Usage |
|---|---|---|
| `--color-success` | `#7FD146` | pass / healthy (`text-success`) |
| `--color-success-subtle` | `rgba(127,209,70,0.12)` | success bg (`bg-success-subtle`) |
| `--color-warning` | `#70B6FD` | warn / attention; same blue as info (`text-warning`) |
| `--color-warning-subtle` | `rgba(112,182,253,0.12)` | warning bg (`bg-warning-subtle`) |
| `--color-info` | `#70B6FD` | informational (`text-info`) |
| `--color-info-subtle` | `rgba(112,182,253,0.12)` | info bg (`bg-info-subtle`) |
| `--color-error` | `#FF6D87` | fail / error (`text-error`) |
| `--color-error-subtle` | `rgba(255,109,135,0.12)` | error bg (`bg-error-subtle`) |
| `--color-status-text-on` | `#121212` | label on any solid status fill (`text-status-text-on`) |

Because warning and info share a hue, a warning must carry a distinguishing icon
or wording; the color alone won't do it. Since no hue is a control, a filled
blue chip reads as status, the same as a red or green one.

### Data-viz / charts

Chart chrome reuses border/muted so charts sit in the same language. The series
ramp ("Siblings") is the **only** place new chart hues enter; read it via
`chartTheme` in `src/ui/charts/`, never ad hoc, and never reuse a series hue
for text, status, or UI chrome.

| Token | Value | Usage |
|---|---|---|
| `--color-chart-grid` | `#3C3C3C` | gridlines (= border) |
| `--color-chart-axis` | `#A0A0A0` | tick labels (= muted) |
| `--color-chart-series-1..8` | lavender, orange, cyan, amber, teal, violet, rose, olive | categorical series, fixed order; `-1` is the single-series default |

Ramp values (`--primitive-viz-1..8`): `#CFAFFF`, `#C9690C`, `#0097AC`,
`#F1B047`, `#35D8CA`, `#AF64BB`, `#FFA2D5`, `#918A00`.

### Brand values that are not tokens

- **Brand pink `#FF3D6E`** is identity-only (logo, marketing). It is not in
  `tokens.css` and never appears in a component.
- **The wordmark's `#FFFFFF`** is a fixed brand mark, spec'd under Typography.

## Radii

Named by component class, not by px.

| Token | Value | Usage |
|---|---|---|
| `--radius-micro` | `0.1875rem` | below control size: checkboxes, key hints, 2-4px bars |
| `--radius-control` | `0.375rem` | inputs, buttons, badges |
| `--radius-card` | `0.625rem` | cards, panels, popovers |
| `--radius-modal` | `1rem` | modals, sheets |
| `--radius-pill` | `9999px` | pills, avatars |

## Shadows

Used sparingly; elevation is carried by surface level.

| Token | Value |
|---|---|
| `--shadow-sm` | `0 1px 3px rgba(0,0,0,0.5)` |
| `--shadow-md` | `0 4px 16px rgba(0,0,0,0.6)` |

Focus ring: `0 0 0 3px rgba(112,182,253,0.6)` (`var(--focus-ring)`), the link
blue at 60% — not the grey accent. The ring is the one piece of chrome that has
to be findable at a glance from anywhere on the page, and a grey ring on a grey
ramp had nothing to separate it from an ordinary border. Blended over the
surface levels it lands at `#4A749F` on level-0 and `#55809F` on level-3,
clearing 3:1 (non-text) on every one (3.8:1 and 3.3:1). 60% rather than a solid
blue, because a solid 3px band reads as a selected state rather than transient
focus. Applied globally on `:focus-visible` by `index.css`.

`--color-scrim` (`rgb(0 0 0 / .55)`) is the dim behind a dialog backdrop and
the fade over a sticky column. Use `bg-scrim`, never a raw `rgba(0,0,0,…)`.

## Motion

Durations live in `tokens.css`; easings stay in `src/index.css` next to the
keyframes that use them. Everything collapses to `0ms` under
`prefers-reduced-motion` globally, so components don't manage that themselves.

| Token | Value |
|---|---|
| `--duration-micro` | `150ms` (hovers, small state changes) |
| `--duration-transition` | `300ms` (panels, drawers) |
| `--duration-reveal` | `500ms` (larger reveals) |
| `--ease-enter` | `cubic-bezier(0.16, 1, 0.3, 1)` |
| `--ease-exit` | `cubic-bezier(0.4, 0, 1, 1)` |

## Typography

**Geist Variable** (sans) and **Geist Mono Variable** (mono), loaded via
`@fontsource-variable` in `src/main.tsx`, are the only two typefaces in the
product UI. The wordmark is the one exception (below). No other face.

600 is the heading ceiling: Geist 700 blooms on near-black, so hierarchy comes
from size and color, not extra weight. Sentence case everywhere; UPPERCASE only
for the tracked `label` eyebrow.

Each size token carries its own line-height, weight, and tracking, so
`text-h2` sets all of them. Color is applied separately (`text-fg`,
`text-muted`, …) and is never baked into a size.

**So never set those alongside a size token.** `text-label tracking-wider` does
not add letter-spacing to the eyebrow — it *replaces* the token's `0.08em` with
Tailwind's `0.05em`, and the same goes for a `lineHeight` or `fontWeight` next
to any `text-*`. Set the token and stop. If the result is wrong, the token is
wrong, and that is a change to `tokens.css`, not a local override.

### Core scale

| Role | Utility | Size / leading | Weight | Tracking | Color |
|---|---|---|---|---|---|
| display | `text-display` | `2.25rem` / 1.1 | 600 | `-0.02em` | `text-fg` |
| h1 | `text-h1` | `1.75rem` / 1.3 | 600 | `-0.01em` | `text-fg` |
| h2 | `text-h2` | `1.25rem` / 1.3 | 600 | 0 | `text-fg` |
| h3 | `text-h3` | `1rem` / 1.5 | 500 | 0 | `text-fg` |
| body | `text-body` | `0.875rem` / 1.65 | 400 | | `text-fg` |
| body-strong | `text-body font-medium` | `0.875rem` / 1.65 | 500 | | `text-fg` |
| body-secondary | `text-body text-fg-secondary` | `0.875rem` / 1.65 | 400 | | `text-fg-secondary` |
| small | `text-small` | `0.75rem` / 1.5 | 400 | | `text-fg-secondary` |
| caption | `text-small text-muted` | `0.75rem` / 1.5 | 400 | | `text-muted` |
| label (eyebrow) | `text-label uppercase` | `0.6875rem` / 1.5 | 500 | `0.08em` | `text-muted` |
| button | `text-body leading-none font-medium` | `0.875rem` / 1 | 500 | | context: `text-accent-text-on` on a fill, `text-fg` on ghost |

### Data and code roles

| Role | Utility | Size / leading | Weight | Color | Notes |
|---|---|---|---|---|---|
| code-inline | `font-mono text-code` | `0.8125rem` | 400 | `text-fg` | on `bg-raised` |
| code-block | `font-mono text-code` | `0.8125rem` / 1.6 | 400 | `text-fg-secondary` | on `bg-surface` |
| id | `font-mono text-small text-muted` | `0.75rem` / 1.5 | 400 | `text-muted` | trace/span IDs, hashes, timestamps, paths, versions |
| numeric | `font-mono text-body leading-normal tabular-nums text-right` | `0.875rem` / 1.5 (compact `0.75rem`) | 400 | `text-fg-secondary` | right-aligned, tabular |
| metric | `text-metric tabular-nums` | `2.25rem` / 1.1 | 600 | `text-fg` | hero stat-tile number, `-0.02em`; sans, not mono |
| table-cell | `text-body` + density leading | `0.875rem` / 1.5 (compact `0.75rem` / 1.35) | 400 | `text-fg-secondary` | primary-key column: `text-fg`; leading comes from `--density-row-leading` |
| column-header | `text-column-header text-muted` | `0.75rem` / 1.5 | 500 | `text-muted` | sentence case, not tracked, not uppercase |

Only `metric`, `code`, and `column-header` are their own size tokens; the other
roles are recipes over the core scale so the scale stays small.

### States

- **Link.** blue (`text-link`) for an INLINE link inside content — in a
  sentence, a linked id in a table cell, an evidence chip. In prose it is also
  underlined; in a cell or a chip the hue carries it alone. Hover lifts to
  `text-link-hover`. Navigation chrome (breadcrumbs, back links, sidebar nav,
  header action links, whole-row links) stays `text-accent`, and so does a
  button that only acts on the current page. Never a filled blue link.
- **Disabled.** grey-600 (`text-subtle`) at the role's normal weight. No
  opacity reduction.
- **Placeholder.** grey-600, weight 400 (set globally by `index.css`).
- **Text on any status or accent fill.** `#121212` (`text-accent-text-on` /
  `text-status-text-on`), weight 500. No white-label exception.
- **Status as text.** Error red passes contrast only in the large-or-bold
  class: `metric`, `h1`–`h3`, `body-strong`. Small error copy is grey-300 text
  with an error-colored icon, never red text. Success and info pass at body
  size, but the same icon-plus-grey-text pattern is preferred for sentences.
- **Selected row / nav item.** grey-50, weight 500, on the level-2 fill. Never
  a hue.

### The wordmark

The one place in the product that isn't Geist, and the one place a color is a
literal rather than a token. It is a fixed brand mark, not part of the scale;
don't tokenize it and don't vary it. Set literally as `tessary`, lowercase t —
never `Tessary` in the mark itself (page `<title>`, alt text, and other
sentence-case references to the product name are unaffected).

```css
font-family:     'Space Grotesk Variable', system-ui, sans-serif;
font-weight:     700;
font-size:       20px;
letter-spacing:  -0.02em;
line-height:     1.2;
color:           #FFFFFF;
text-decoration: none;
```

## Layout

| Token | Value | Usage |
|---|---|---|
| `--sidebar-width-expanded` | `240px` (`200px` ≤1024px, `168px` ≤768px) | expanded nav sidebar |
| `--sidebar-width-collapsed` | `64px` | collapsed sidebar |

## Density

Data-heavy surfaces can run **compact** to fit more rows. A `data-density`
attribute on an ancestor (set by `DensityProvider`, `src/ui/density.tsx`)
re-points these vars for the subtree; density-aware components (`TD`/`TH`) read
them for vertical rhythm. The leading values are the table-cell line-heights
from the type spec.

| Token | comfortable | compact |
|---|---|---|
| `--density-cell-py` | `0.625rem` | `0.3125rem` |
| `--density-cell-px` | `0.75rem` | `0.625rem` |
| `--density-row-leading` | `1.5` | `1.35` |

## Theming

**Dark only.** There is no light palette, no toggle, no persisted preference,
and no light token block. `ThemeProvider` (`src/ui/ThemeContext.tsx`) hard-sets
`color-scheme: dark` and a dark background on `<html>`, removes any stale
`data-theme` attribute, and clears the old `tsy-theme` `localStorage` key on
mount. `useTheme()` still returns `{ theme, setTheme, toggle }` so existing
imports compile, but `theme` is always `"dark"` and the setters are no-ops.

Don't add a `:root[data-theme="light"]` block or invent light values: the
palette was designed without them. If light ever earns a full pass, it starts
with a new palette, not a re-skin of this one.

## Rules

1. **No hardcoded colors.** No hex, no `rgb()`/`rgba()` literals, no default
   Tailwind palette classes (`text-slate-500`, `bg-zinc-800`, `text-white`, …).
   Use semantic utilities or `var(--color-*)`. Two legitimate exceptions, each
   a fixed brand value rather than a UI color: the wordmark's literal `#FFFFFF`
   and identity-only brand pink `#FF3D6E`. Anything else is a violation.
2. **Compose `ui/` primitives.** Reach for `Button`, `Card`, `Table`, `Modal`,
   inputs, etc. from the `src/ui/` barrel before hand-rolling. They're already
   token-driven.
3. **The accent is grey; hues are status only.** Primary button, active nav and
   selection all use the `accent-*` tokens, which resolve to the grey ramp. The
   single exception is navigation: a link and the focus ring are `--color-link`
   blue. Red, green and blue otherwise denote failure, success and info/warning,
   sparingly, and never mark something as interactive. Use `accent-subtle` /
   `accent-edge` for low-emphasis accent treatments. Body text is never a status
   hue.
4. **Status hues are not text colors by default.** Red only as large-or-bold
   text; otherwise grey text plus a colored icon. Chart series hues never
   appear outside a chart.
5. **Step elevation one level at a time.** `bg` → `surface` → `raised` →
   `overlay`; never skip a level, never use shadow to fake one.
