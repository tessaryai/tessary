# Frontend agent guide

Imperative rules for any agent touching `frontend/`. React 19 + Vite +
TypeScript + **Tailwind v4**, styled by the Tessary Design System.

## Design system

**Read [`DESIGN_SYSTEM.md`](./DESIGN_SYSTEM.md) before your first styling change
and follow it.** It is the reference for the palette, the type scale, the radii,
what the accent means and when a hue is allowed; every value it describes is
defined in [`tokens.css`](./tokens.css).

Those rules are deliberately NOT restated here. Two copies drift, and the copy
an agent happens to read first wins. That is exactly how this file came to
describe a mint accent and a light mode for months after both were removed.

The one imperative that belongs in this file: **never write a raw value for
anything the tokens own.** No hex, no `rgb()`, no Tailwind palette class
(`bg-zinc-800`, `text-white`), no pixel font size or radius, and no
`lineHeight` / `fontWeight` / `tracking-*` sitting next to a `text-*` token.
Nothing in the gate catches any of that — it all type-checks and bundles
cleanly — so this document and review are the only things standing between the
system and a slow drift back out of it.

Two consequences worth knowing before you reach for a workaround:

- **Dark only.** There is no light mode and no `data-theme="light"` block.
  `ThemeProvider` (`src/ui/ThemeContext.tsx`) hard-sets dark and its setters are
  no-ops; the API is kept so existing imports compile. Don't add a competing
  theme-init path.
- **Compose `src/ui/`, don't hand-roll.** `import { Button, Card, Table } from
  "../ui"`. A primitive built by hand is a primitive that will miss the next
  token change. Charts go through `src/ui/charts/chartTheme.ts` — no ad-hoc
  chart hues.

## Where things live

- **Tokens** → [`tokens.css`](./tokens.css), imported once by `src/index.css`.
  Its `@theme` block emits the `--color-*` / `--text-*` / `--radius-*` Tailwind
  utilities; `src/index.css` itself owns only base rules, keyframes and scoped
  component CSS (`.payload-md`, `.payload-json`).
- **Component primitives** → `src/ui/` (barrel: `src/ui/index.ts`).
- **Charts** → `src/ui/charts/chartTheme.ts`.

## Structure (src/)

- `api/` — `client.ts` (fetch layer), `types.ts` (+ `ApiError`), `types-auth.ts`,
  `generated/schema.d.ts` (OpenAPI-generated — see *Types* below).
- `auth/` — `AuthContext.tsx` (/auth/me poller), `ProtectedRoute.tsx`.
- `paid/` — the OPEN edition's answer to the `@paid` seam: real code with empty exports. Open
  source imports the `@paid` specifier and the BUILD decides what it resolves to — this stub in
  the open build, the overlay's own entry in the paid one. **A paid-only surface is therefore a
  two-tree edit**: the view lives in the overlay, and only its registration passes through here.
  Adding one to `views/` instead ships paid code in the open bundle, which
  `scripts/check-frontend.sh` fails over the emitted chunks rather than over source.
- `tenant/` — `TenantContext.tsx`: `TenantProvider`, `useTenant`, `useProjectApi`.
- `shell/` — navigation shell: `nav.tsx` (**the IA source of truth**), `Sidebar.tsx`,
  `ShellChrome.tsx` (layers the project-scoped providers), `CommandPalette.tsx` +
  `PaletteContext.tsx` + `commands.tsx` (⌘K), `ShellActions.tsx`,
  `useCases.ts` (the Triage badge count), `recents.ts`, `useSidebarCollapsed.ts`, `useDropdown.ts`. Import the IA from the
  `shell/index.ts` barrel.
- `ui/` — design-system primitives barrel (`import { Button, Card, Table } from "../ui"`),
  `ui/charts/` (`TrendChart`, `StackedBarChart`, `VolumeBars`, `chartTheme.ts`),
  `ThemeContext.tsx`, `density.tsx`, `escStack.ts` (the shared overlay ESC stack — a rail
  under a modal must not also close on the same keypress).
- `capabilities/` — `useCapabilities()` / `CapabilityGate` / `NAV_CAPABILITY`. ONE gating axis:
  the backend resolves every capability per session and the SPA reads the answer. **Fail-closed**
  on the client while the read is in flight (the browser has no defaults of its own). There is no
  frontend LaunchDarkly client and no plan logic.
- `views/` — surfaces, mostly one directory per IA surface (`triage/`, `traces/`,
  `classifiers/`, `vitals/`, `Settings/`). Track A deleted `graders/`, `review/`, `datasets/`,
  `runs/`, `synth/` and `reposync/` along with the whole Calibrate nav group; their routes are
  `<Navigate>` redirects to `../triage` at the bottom of `App.tsx`. Surface-specific shared widgets go in `views/components/`
  (`PayloadViewer`, `SpanOutline`, `SourceConnect`, …). Generic primitives belong in
  `src/ui/`, not here.
- `components/` — app-level, non-visual mounts (`MixpanelPageViewTracker`). Not a
  general component bucket; a new visual primitive goes in `src/ui/`.
- `lib/` — `mixpanel.ts`, `labels.ts`, `ttfv.ts`, `effort.ts` (reasoning-effort labels).

## API + data conventions

- All responses unwrap from `ApiResponse<T>` in `api/client.ts` (`http<T>()`). Failures throw
  `ApiError(status, code, message, details)` — render `err.code` + `err.message`, never
  "something went wrong".
- **`projectApi(orgSlug, projectSlug)`** is the per-project client factory; views read it via
  `useProjectApi()` inside `TenantProvider`. There is no global `api` — don't import one.
- **Types are generated, not hand-written.** `pnpm run generate:api` runs `openapi-typescript`
  over the checked-in backend spec into `api/generated/schema.d.ts`; `api/types.ts` re-exports
  `S["..."]` aliases. Consume new backend DTOs via those aliases (backend side:
  `task contract:openapi` first).
- TanStack Query keys: `[resource, api.base]` for project-scoped data (`['pipeline',
  api.base]`, `['classifiers', api.base]`) so a project switch invalidates correctly;
  auth-scoped keys use slugs. After `POST .../import`, invalidate `['pipeline']`.
- Default `staleTime` is 30 s (`main.tsx`). One-shot LLM mutations use `useMutation` with no
  cache key — single-fire, result in component state.
- **The old progressive onboarding gate is gone, but a first-run gate exists again (#1227).**
  `useOnboarding` / `OnboardingContext` / `<Gate need="pipeline"|"full">` were removed with the
  Triage · Monitor · Calibrate redesign and stay gone — don't reintroduce a call to *those*.
  What replaced them is narrower and one-shot: `ProjectShell` (`App.tsx`) renders
  `<ConnectGate>` (`views/onboarding/ConnectGate.tsx`) instead of `<ShellChrome>`/routes for any
  non-sample project until its substrate reports `has_tagged_span`, at which point it flips once
  to the real shell and never gates again. It blocks every route (Triage included), which the old
  mechanism also did — the difference is scope (one first-run wait state, keyed on the substrate
  read, not a per-surface capability check) and that a project can dodge it entirely via the
  gate's own "Start with a sample project" escape hatch. `<CapabilityGate capability=…>` is
  unchanged and still the only *per-surface* wrapper. First-run guidance for the connected case
  lives in the `…/setup` wizard (`views/Setup.tsx` + `views/onboarding/useOnboarding.ts`), which a
  project reaches by navigation — that part is unaffected; `ConnectGate` only stands in front of
  it for a project that hasn't connected a source yet.

## Rendering trace payloads

`views/components/PayloadViewer.tsx` renders trace media inline with an **XSS guard at src
construction**: `image_url` accepts only `https://` or `data:image/<allowed>`; base64 is gated
by a MIME allowlist (`png|jpeg|gif|webp`) that **rejects `image/svg+xml`** (active document).
A recognised-but-unrenderable block becomes a neutral "unsupported image" chip. The
image-detection shapes mirror the backend `ContentExtractor` — keep them in sync.

## New view

1. Declare the surface once in `shell/nav.tsx` — as an item inside the right `NAV_GROUPS`
   entry (`Monitor` or `Calibrate`), or in `RESERVED` for a coming-soon slot (`id` = route
   segment, `label`, `icon`, `description`, `keywords`). `NAV` / `LIVE_NAV` / `RESERVED_NAV`
   are **derived** from those — never append to them directly. Sidebar and ⌘K both read the
   derived lists, so one declaration lights up both.
2. Add the lazy import at the top of `App.tsx` via the `named(() => import("./views/X"), "X")`
   helper, and a plain `<Route path="segment" element={<X/>} />` inside `ProjectShell`'s
   nested `<Routes>`. Wrap gated surfaces in `<CapabilityGate capability=…>` — that is the only
   route wrapper — and add the surface to `NAV_CAPABILITY` so the sidebar and palette agree.
3. Moved/renamed an old path? Leave a `<Navigate … replace />` redirect with the others at
   the bottom of `ProjectShell` — that block is the live migration map, and bookmarks and
   Slack deep links depend on it.
4. Settings sub-pages: add to `SETTINGS_SECTIONS` in `nav.tsx` **and** a child `<Route>` under
   `settings` in App.tsx.

## Copy

Every user-visible string follows the handbook: the
[product copy guide](../handbook/product-copy-guide.md) for surfaces, states, and actions, the
[writing style guide](../handbook/writing-style-guide.md) for language rules, and the
[product glossary](../handbook/product-glossary.md) for terms (a classifier creates a finding;
triage turns a sound finding into a case; "detector" is a wire key, never a label). Those docs are
the only home for the rules.

## Before you commit

Run `task check -- frontend` from the repo root (type-check + all-routes render
smoke test + production bundle; no Maven, no Docker). Nothing enforces this
automatically, and CI only runs the full gate on a weekly cron — your local run
is the gate.

**The gate does not check design tokens.** A hardcoded hex, an off-scale font
size and a `tracking-wider` fighting its token all compile and all bundle
cleanly. Nothing but this document and review catches them, so read the rules
above before you reach for a raw value, and look at the surface you changed.
