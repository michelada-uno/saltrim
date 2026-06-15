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

### 2. Per-sheet namespace + functions  *(needs SCI)*
Give each sheet its own SCI namespace where the user can **define functions**
reusable across that sheet's cells, plus ship **predefined collections** (a
stdlib: math, stats, text, date). Persist user defs with the sheet. Turns a
sheet into a small program.

### 3. UI architecture: collapsible control rows  *(before more controls land)*
The toolbar keeps growing (sheet/share, formula bar, style row, soon clipboard…).
Rework into **show/hide sections** (collapsible rows, or a small panel/ribbon
model) so new tools don't crowd the bar. Foundational for the editing track.

### 4. Client → ClojureScript  *(port while `app.js` is still small)*
`app.js` is ~430 lines now. Multi-select + clipboard will balloon it. Port to
**CLJS now**: (a) kills the server/client offset-math duplication (share one
function), (b) gives us real code to build the heavy interactions in. Doing it
**before** clipboard/multi-select avoids writing throwaway JS.
Risk: big-bang refactor — keep behavior identical, verify hard.

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
- **Git-like sheet branching** — branch / merge / as-of on sheets. Forces
  **cells → Datahike** (file-EDN storage retires). Multi-PR effort; see the DB
  design notes. Everything else is smaller than this.

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
