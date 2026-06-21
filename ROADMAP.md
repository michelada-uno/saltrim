# SaltRim roadmap

Living plan of what's **next**. Shipped features live in `CLAUDE.md` → "Status".
The order below is a recommendation with rationale, not a contract — the owner
reorders freely.

## Foundational track (do roughly in order — each unlocks the next)

### 1. Formula engine → SCI  *(do first — fixes an active limitation)*
Today formulas are host `eval` gated by a symbol whitelist (`formula.clj`
`validate!`). Concrete failure: **local bindings don't work** — `(let [a 1] a)`
is rejected because every symbol not in `allowed-ops`, including the user's own
`a`, is disallowed. Whitelisting `let` isn't enough; the binder names can't be
known up front.

Migrate evaluation to **SCI** (Small Clojure Interpreter): true sandboxed
interpretation with real lexical scope, controlled vars, no host `eval`.
- Fixes `let`/locals, `fn` literals, destructuring, etc.
- Capability-scoped sandbox (expose a namespace of allowed fns) instead of a
  blacklist-by-symbol — safer and simpler.
- Must preserve the reactive machinery: the `#cell`/`#cells`/`$val` reader
  markers, the await-based lift, await de-dup, and cycle rejection. SCI
  evaluates the *lifted* form against a context exposing only
  `lookup/await/track/…`.
- Prerequisite for per-sheet namespaces (item 2).

### 2. Per-sheet namespace + functions  *(SHIPPED — PR #24)*
Each sheet has its own SCI context: a predefined **stdlib** (math / stats / text
/ date, callable bare, read-only) plus the user's **definitions library** —
functions/constants kept as separate chunks in the `ƒ` modal, each edited
independently with a **collaborative per-chunk lock**, all merged into the sheet
program, reusable from every cell, persisted with the sheet (`:defs`) and
recompiled live. Turns a sheet into a small program. Open follow-ups: a real
name-conflict policy across chunks; a shared cross-sheet library; richer stdlib.

### 3. UI architecture: collapsible control rows  *(before more controls land)*
The toolbar keeps growing (sheet/share, formula bar, style row, soon clipboard…).
Rework into **show/hide sections** (collapsible rows, or a small panel/ribbon
model) so new tools don't crowd the bar. Foundational for the editing track.

### 4. Client → ClojureScript ✅ SHIPPED *(branch `refactor/use-proper-datastar-attributes`)*
`app.js` ported to `src/.../app.cljs` (plain CLJS compiler — no node/npm; dev
watch-compiles on `(start)`, prod builds `:advanced` in `uber`). Addressing +
grid geometry now live in shared `.cljc` (`addr`, `constants`) so server and
client share **one** source of truth — killing the offset-math duplication.
Alongside the port, the UI dropped its hidden-trigger-button smell: Datastar
attributes own all signals + server round-trips, `app.cljs` owns the imperative
work, and the two bridge through `sr-*` custom events on `#ctl`/`#streamer`.
Behavior verified identical (selection, edit, formulas, scroll, keyboard, resize,
collaboration push, stream stability) on the `:advanced` bundle.

## Editing track  *(builds on 3 + 4)*

### 5. Multiple selection
Move from single-cell selection to **ranges + multi-range** (shift / ctrl).
Defines the target set for styling, clipboard, fills. The server already
addresses ranges (`#cells`); this is selection state + overlay + hit-testing,
mostly client.

### 6. Cut / copy / paste
Clipboard over cells with **granularity**: paste *all*, *values only*,
*style only*, or *format only*. Define the semantics up front:
- relative vs absolute reference shift on paste,
- multi-range paste rules,
- cross-sheet paste.
Keyboard + menu, both.

## Cheap wins (slot in anytime; assertions pair well after SCI)
- **Semantic graph view** — visualize the cell dependency graph (we already
  track `:deps` + `dependents*`). Mostly a render; big "wow", low cost.
- **Logic audit / assertions** — per-cell assertions (`=(assert …)`) that flag
  violations; reuses the formula path + reactive recompute. Nicer once SCI lands.

## Strategic (the boss fight)
- **Cells → Datahike** ✅ SHIPPED *(branch `feat/db-sheet-storage`)* — sheet
  content moved out of file EDN into the database as **per-property, branch-aware
  datoms** `(sheet, branch, addr, prop) → src` (+ a `:branch` entity for
  per-branch scalars). File store retired (no migration; fresh start). `save!`
  diff-saves (no history churn). `:cellprop/author` captured for undo. The branch
  dimension (`"main"`) + `db/fork-branch!` + per-prop `as-of` are the substrate
  for the rest. See `spikes/04-db-cell-storage.clj`.
- **Git-like sheet branching** — the lifecycle on top of that substrate: a branch
  picker, fork/switch, **merge** (app-level 3-way; define the conflict policy),
  and as-of viewing. Still the biggest remaining piece.
- **Per-user undo/redo** ✅ SHIPPED *(branch `feat/undo-redo`)* — local
  *selective* undo: `Ctrl/⌘+Z` / `+Shift` (or `Ctrl+Y`) redo. The selective step
  is `sheet/undo-step` (skips a prop a collaborator overwrote, so it never
  clobbers their work); the per-session stack `{:undo :redo}` of
  `{:addr :prop :before :after}` lives in `web` (per tab), recorded on each
  value/style edit. Undo is a normal authored write (persisted + broadcast).
  *(Cross-session/durable undo via `:cellprop/author` + history is a possible
  future upgrade; the stack is in-memory per session today.)*

## Polish / governance (fold in opportunistically)
- date/time format masks; color-picker + toggle controls for styling;
  formula-backed column/row sizes (TECHDEBT deferrals).
- group / org share grants (the share schema already has a `:group` kind).
- conflict policy for concurrent edits (last-write-wins today).

## Suggested sequence
**SCI (1) → per-sheet ns/fns (2) → collapsible UI (3) → CLJS (4) →
multi-select (5) → clipboard (6).** Graph view + assertions drop in after SCI as
palate-cleansers. Branching when there's appetite for a long haul.

> Note on ordering: CLJS (4) is intentionally pulled **ahead** of multi-select /
> clipboard even though it feels like "cleanup". Those two features add a lot of
> client code; porting first means we don't write JS we'll immediately rewrite.
