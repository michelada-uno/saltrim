# calcloj — technical spec

A spreadsheet whose **reactive core is Spindel** (signals/spins) and whose **UI
is Datastar** (server-rendered HTML patched over SSE). Formulas are sandboxed
Clojure expressions. Sheets persist as source and rebuild their reactive graph
on load. Multiple clients edit one sheet live.

## Stack

- Clojure 1.12, `org.replikativ/spindel` `0.1.15` (Clojars release; was a git sha
  pin, switched once the release caught up — see `deps.edn`).
- `dev.data-star.clojure/sdk` + `http-kit` adapter `1.0.0-RC10`.
- http-kit server, reitit not used (hand-rolled `case` router), hiccup2, jsonista.
- `org.babashka/sci` available (sandbox option) — current formula sandbox is a
  symbol whitelist + `eval`, not SCI (see Formulas).
- Vendored client: `resources/public/datastar.js` (Datastar **1.0.0**),
  `resources/public/app.js` (our client engine).

## Namespaces (`src/uno/michelada/calcloj/`, ns root `uno.michelada.calcloj`)

| ns | role |
|----|------|
| `addr` | A1 addressing. `col<->idx`, `parse`, `make`, `range-cells`, `valid?`. Address = letters+digits (`AAB1234`); **no colon** (colon = range separator, `A1:C3`). 0-based `ci/ri` internally. |
| `runtime` | Referenced by compiled formula bodies. `lookup`/`lookup-val` resolve a cell against the **current execution context's metadata** (works on executor threads). |
| `formula` | Parse + compile formulas to Spins. |
| `sheet` | Cell registry over one Spindel execution context. The engine API. |
| `store` | File persistence of the source document (`data/<id>.edn`). |
| `web` | http-kit server, rendering, SSE handlers, sessions, collaboration. |
| `spike*` | REPL spikes proving Spindel behavior (kept as living docs). |

## Reactive cell model (the core idea)

Every cell is a **public Spin**. Two kinds:

- **Literal**: an editable `SignalRef` (`val:<addr>`) holding a number/string,
  plus a thin wrapper spin `(spin (deref (track (lookup-val addr))))`.
- **Formula**: a Spin compiled from an `=`-expression.

Why uniform Spins: cross-formula references use **`await`** (which handles
Spins). `track` only handles `SignalRef`, so a formula referencing another
formula needs the target to be a Spin — hence literals are wrapped too.

`sheet` holds (all in the execution-context metadata so compiled bodies can
resolve cells):
- `:registry` `{addr -> Spin}` — every non-blank cell (used by `lookup`/`await`).
- `:vals` `{addr -> SignalRef}` — literal cells' editable signals (`lookup-val`).
- `:meta` `{addr -> {:raw :kind :deps}}` — the document layer (source of truth).

### set-cell! semantics

`classify` → `:blank | :literal | :formula`.
- literal: reuse the stable signal (`reset!` → propagates) or create it; (re)create
  the wrapper spin only on a kind change.
- formula: parse → cycle-check → compile → install Spin; record `:deps`.
- **Structural change** (formula↔literal, or formula edit) replaces a cell's
  public Spin object, so dependents that captured the old object are
  **transitively rebuilt** (`set-cell!` recurses over reverse-deps, cycle-guarded
  by a visited set). Value-only edits skip the rebuild (the signal propagates).

### Spindel specifics

- `track` returns an **Interval**; read `@(track sig)`.
- Mutating a signal only enqueues; the executor drains asynchronously. `settle!`
  (= `simple/await-drain-complete!`) is a barrier used in tests/reads.
- `value` derefs the cell's Spin; errors are caught → `{:error msg}` → rendered
  as `#ERR`.

## Formulas

Reader tags:
- `#cell A1` → current value of A1.
- `#cells A1:A3` → vector of current values, for `map`/`reduce`. The range is any
  inclusive **rectangle**, expanded **row-major** at read time: `A1:A3`→`[A1 A2
  A3]` (column), `A1:C1`→`[A1 B1 C1]` (row), `A1:B2`→`[A1 B1 A2 B2]` (block).

Pipeline (`formula`):
1. `parse`: `clojure.edn/read-string` with custom readers (EDN blocks `#=` RCE).
   `#cell`/`#cells` emit neutral ref-markers `(::ref "A1")`. `:deps` = the marked
   addresses.
2. `validate!`: whitelist every **user** symbol (`allowed-ops`). Markers are
   keyword-headed lists so addresses never hit the check.
3. `lift`: replace each **distinct** ref with a `let`-bound local awaited once —
   `(let [c_1 (await (lookup "A1")) ...] body)`. Two reasons: (a) `await` inside a
   nested `fn` is **not** CPS-transformed (so ranges must expand statically at
   read time, which they do), and (b) **awaiting the same cell twice glitches**
   on recompute.
4. `compile`: `eval (spin lifted)` in the `uno.michelada.calcloj.formula` namespace so
   `spin`/`track`/`await` resolve and the CPS transform sees the effects.

Sandbox = EDN reader + symbol whitelist + a fixed `eval` namespace. (SCI was
explored; the SDK's SCI integration only wires `await`, not spindel's `track`, so
we use the real `spin` macro + whitelist instead.)

Cycles: `sheet/would-cycle?` walks the forward dep graph from the new deps; if it
reaches the cell being set, reject before compile (a cycle StackOverflows the
await chain).

## Persistence (`store`)

- Persist the **source document**, not the Spindel graph: `{addr {:value raw}}`,
  a per-cell **property map** (room for `:style`/`:format` later, each a reactive
  property compiled from its own source). EDN at `data/<id>.edn`.
- `load-sheet` rebuilds the reactive graph by replaying `set-cell!` (order-
  independent — formula refs resolve at run time). A reloaded sheet is fully live.
- `valid-id?` guards path traversal. Behind `save!`/`load-sheet` so the backend
  can become Datahike/SQL later.

## Web layer (`web`)

### Rendering — logical scroll

No native scroll / giant spacer. The viewport is fixed-size, `overflow:hidden`,
with clipped header/cell layers each holding an absolutely-positioned inner layer
that the client `translate`s.

- Cells/headers are positioned **window-relative** to the rendered base
  `(cb,rb)` = `max(0, c0-OVER)`.
- `#meta` (hidden) carries totals `tw/th` (logical px, for scrollbar sizing) and
  the base `cb/rb`. It is patched **together with `#cells`** so the client's
  transform always matches the displayed content (no jump mid-fetch).
- Geometry constants: `CW=112 RH=26 GUT=48 HDR=26 OVER=2 WIN-COLS=16 WIN-ROWS=34
  BAR=12`. `MAX-COLS=16384 MAX-ROWS=600000` (coordinate clamp). Empty cells cost
  nothing (absent from registry → no spin).
- Per-cell HTML is tiny: a `.cell` class + `left/top` only. Focus/blur/change are
  **delegated on `#viewport`** (`focusin/focusout` since focus doesn't bubble).

### Client engine (`app.js`)

- Logical position `SX,SY`. Wheel → translate `#cells/#colstrip/#rowstrip` by
  `(cb*CW - SX, rb*RH - SY)` for smooth sub-cell scroll; `POST /view` (debounced)
  only when the top-left index changes; re-align `render()` on the `/view`
  `datastar-fetch finished` event.
- Custom draggable scrollbars (`#vthumb/#hthumb`) sized from `#meta` totals.
- `jump(addr)` parses A1, sets `SX/SY`, forces a fetch (no clamp; `/view`'s
  `total-px` extends to cover the target).
- Triggers Datastar actions by clicking hidden buttons (`#viewtrigger` for
  `/view`, `#streamtrigger` for `/stream`), setting hidden bound inputs
  (`#r0box/#c0box/#sidbox`) first — there is no `data-on:load` plugin.

### Endpoints

- `GET /` — page for `?s=<sheet-id>` (default `default`).
- `GET /app.js`, `GET /datastar.js` — vendored assets.
- `GET /stream?sid=&s=` — **persistent** per-session SSE. Registers the session,
  stores its generator, flushes once to establish the stream. Stays open.
- `POST /cell` — edit (Datastar `@post`, signals carry `cell/v/sheet/sid`).
  Edits, settles, autosaves, returns the editor's window patch + `$err`, and
  **broadcasts** the change to other sessions on the sheet.
- `POST /view` — window change (signals carry `r0/c0/sheet/sid`). Patches
  `#cells/#colhead/#rowhead` inner + `#meta`.
- `POST /session/end` — `navigator.sendBeacon` on `pagehide` → `reap-session!`.
- `GET /debug` — session/sheet detail (dev only).

### Sessions & sheet lifecycle

- `sessions*` `{sid -> {:sheet :view :dims :gen :last-seen}}`. `sheets*`
  `{id -> sheet}` (lazy load from disk).
- Viewport is **per session** (concurrent clients keep independent scroll).
- Acquire on `/stream` open; release on beacon `/session/end` **or** the TTL
  sweep (`SESSION-TTL-MS=30m`, `SWEEP-MS=60s`) — both call `reap-session!`, which
  `close-sse!`s the stored generator. When a sheet's last session leaves it is
  **saved + its execution context closed + dropped** (`unload-sheet!`).
- **Lazy re-register**: a `/cell` or `/view` with an unknown sid recreates the
  session, so a client returning from sleep just works (no heartbeat).
- Cleanup never relies on http-kit's channel close (it doesn't fire on idle
  disconnect without a write).

### Collaboration

- Editor gets immediate feedback from its one-shot `@post /cell` response.
- The server `broadcast!`s the changed cells (and their dependents) to **every
  other session on the sheet**, each rendered relative to **that session's**
  viewport, written to its stored generator under `d*/lock-sse!`. A write to a
  dead stream throws → reap that session.
- Stream reconnect (`app.js`): Datastar `@get` SSE doesn't reconnect forever; on
  `datastar-fetch` `finished`/`retries-failed` for `#streamtrigger`, reopen with
  capped backoff (reset on `started`). `/stream` on-open keeps an existing
  session's view and just swaps in the new generator.

### Presence & edit locking

Selection and presence are **server-rendered overlays** — no per-cell client JS,
no class-toggling on the cell `<input>`s (which would clobber the caret
mid-edit). Two absolutely-positioned layers inside `#cellclip`, translated with
`#cells` by `app.js`: `#self` (this user) and `#peers` (everyone else).

- **Presence state**: each session has a deterministic `:color` (hashed sid),
  `:cursor` (the cell it is on) and `:editing` (the cell it is actively editing,
  else nil). The client posts presence **declaratively via Datastar**
  (`@post('/presence')` in the cell `data-on:focusin`/`focusout` and the formula
  bar's `data-on:focus`/`blur`), carrying signals `$sel` and `$edit`. `jump`
  clicks a hidden `#presencetrigger`. `handle-presence` updates the session, then
  patches **this** session's `#self` back on the `@post` response and
  re-broadcasts `#peers` to everyone (`broadcast-presence!`).
- **`#self` overlay** (`self-html`), two tiers, rendered from the session's
  `:cursor`/`:editing`, `pointer-events:none` (never blocks typing):
  - selected ("you are here") — a calm `.selfcell` border that persists when
    focus moves to the formula bar (the box stays on `:cursor`).
  - editing now — `.selfcell.editing`: an **animated "marching-ants"** blue dashed
    border (four gradient edges whose `background-position` scrolls via the
    `cc-ants` keyframes). Honors `prefers-reduced-motion`.
  The highlight moves on a server round-trip (ms); the native input `:focus`
  outline gives instant feedback in the meantime.
- **`#peers` overlay** (`peers-html`/`peer-marker`): markers for every *other*
  session whose cursor is in this viewer's window, positioned window-relative to
  *that* viewer's view. A non-editing marker is `pointer-events:none` (just a
  cursor); an **editing** marker is `pointer-events:auto` with a translucent
  fill, so it **covers and locks** the cell beneath (you cannot focus it).
- **Edit lock (server guard)**: `/cell` rejects a write when another session's
  `:editing` equals the target cell (`locked-by-other?`) → `#ERR`-style toast
  "cell is being edited by another collaborator". Belt-and-suspenders with the
  client overlay lock.
- Overlays refresh on `/presence`, on a viewer's `/view` (re-render `#self` +
  `#peers` for the new window), on `/stream` open (reconnect restores `#self`,
  newcomer sees existing cursors), and on `reap-session!` (departed cursor
  disappears).

## Tests

`clojure -X:test` — `addr`, `engine` (literals, chains, ranges, formula-over-
formula, structural rebuild, errors, cycles), `store` (save/load roundtrip,
valid-id). Currently 15 tests / 58 assertions. Web/session/collab behavior is
verified manually + via curl (see CLAUDE.md); no web unit tests yet.

## Build & release

calcloj is a **runnable web app**, not a library — it is **not** published to
Clojars. The distributable is a standalone uberjar attached to a GitHub Release.

- `build.clj` (alias `:build`, tools.build) AOT-compiles the gen-class main
  (`uno.michelada.calcloj.web`) and packages `target/calcloj-<version>.jar`.
  Run: `clojure -T:build uber` → `java -jar target/calcloj-<version>.jar`
  (serves on `:8080`). Version comes from `$VERSION`, else `0.1.<git-revs>-dev`.
- `.github/workflows/release.yml` triggers on a `v*` tag: test → build uberjar
  (VERSION = tag without the `v`) → publish a GitHub Release with the jar.
  Uses the auto `GITHUB_TOKEN`; no Clojars token needed.
- Coordinate `uno.michelada/calcloj`; repo lives under the `michelada-uno` org.

To cut a release: `git tag v1.2.3 && git push origin v1.2.3`.

## Known limitations

See `TECHDEBT.md`. Highlights: keyboard nav not wired; `WIN-COLS/ROWS` fixed
(not viewport-computed); last-write-wins (no merge); session-less sheets loaded
by a bare `GET /` aren't swept; `/debug` is ungated; concurrent simultaneous
edits can race a transient `#ERR`.
