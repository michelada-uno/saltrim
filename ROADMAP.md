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

### 5. Multiple selection  *(SHIPPED — branch `feat/multi-select`)*
Ranges + multi-range: **Shift**+click / Shift+arrows extend a rectangle,
**Ctrl/⌘**+click adds a range. Selection state lives in `app.cljs` (`SEL` =
vector of `{:a :f}` ranges); the active cell still drives `$sel`/bars/presence,
the marquee is drawn locally into `#selrange` (peers see only the active cell).
First consumer: **Delete** clears the selection (`/clear`, per-cell undo entries).
Next consumers: style-to-selection + the clipboard (#6).

### 6. Cut / copy / paste  *(SHIPPED v1 — branch `feat/multi-select`)*
`Ctrl/⌘+C` copy · `Ctrl/⌘+X` cut · `Ctrl/⌘+V` paste. A **per-session server
clipboard** (works for off-window ranges), captured from the selection's first
rectangle. Paste lands at the selected cell with **relative reference shift**
(copy `=(+ #cell A1 1)` down a row pastes `=(+ #cell A2 1)`; refs clamp at A1 —
`formula/shift-refs`). Cut = copy + clear; paste/cut record per-cell undo. Server
holds the clip in the session (`/copy` `/cut` `/paste`); selection rides in
`$selcells`.
Decided semantics: refs are **relative** on paste (SaltRim has no `$`-absolute
syntax). **Deferred:** paste *granularity* (values / style / format only),
multi-range copy, style/format in the clip, cross-sheet paste, marching-ants
visual of the copied range.

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
- **Git-like sheet branching** — the lifecycle on top of that substrate.
  - **Switch + fork** ✅ SHIPPED *(branch `feat/branching`, PR A)* — a branch is
    its own collaborative working copy: the web runtime now keys every loaded
    engine + collaboration broadcast on a `(sheet, branch)` **room** (not the
    bare id), so users on different branches don't see each other's cells. A
    branch picker switches (`&b=`); an owner-only 🌿 modal forks the current
    branch (`db/fork-branch!`, recording `:branch/parent` + `:branch/base-tx`
    lineage for merge) or deletes a non-main branch (`db/delete-branch!`, with no
    resurrection on unload). A stale/typo'd `&b=` falls back to main.
  - **Merge** (PR B, next) — app-level 3-way against the recorded fork point
    (parent doc `as-of` base-tx): auto-merge non-conflicting props, surface real
    conflicts for the owner to resolve per property.
  - **As-of viewing** (PR C, optional) — read-only time-travel via per-prop
    history.
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
