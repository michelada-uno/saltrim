# SaltRim — technical spec

A spreadsheet whose **reactive core is Spindel** (signals/spins) and whose **UI
is Datastar** (server-rendered HTML patched over SSE). Formulas are sandboxed
Clojure expressions. Sheets persist as source and rebuild their reactive graph
on load. Multiple clients edit one sheet live.

## Stack

- Clojure 1.12, `org.replikativ/spindel` `0.1.15` (Clojars release; was a git sha
  pin, switched once the release caught up — see `deps.edn`).
- `dev.data-star.clojure/sdk` + `http-kit` adapter `1.0.0-RC10`.
- http-kit server, reitit not used (hand-rolled `case` router), hiccup2, jsonista.
- `org.babashka/sci` is the formula sandbox: the user expression is evaluated by
  SCI (real lexical scope, no host interop), not a symbol whitelist + host
  `eval` (see Formulas).
- Client: **Datastar 1.0.2** (the page loads it from the CDN; a matching copy is
  vendored at `resources/public/datastar.js`, served at `/datastar.js`, for
  offline/air-gapped use — swap the `<script src>` in `web.render/page`, where
  the local path sits as a reader comment next to the CDN URL) +
  `resources/public/app.js`, **compiled from `src/.../app.cljs`** (plain CLJS
  compiler, no node/npm — dev watch-compiles on `(start)`, prod `:advanced` in
  `uber`). `app.legacy.js` is the pre-CLJS source, kept for reference only.

## Namespaces (`src/uno/michelada/saltrim/`, ns root `uno.michelada.saltrim`)

| ns | role |
|----|------|
| `addr` (`.cljc`) | A1 addressing. `col<->idx`, `parse`, `make`, `range-cells`, `valid?`. Address = letters+digits (`AAB1234`); **no colon** (colon = range separator, `A1:C3`). 0-based `ci/ri` internally. **Shared by server + client.** |
| `constants` (`.cljc`) | Grid geometry (cell/gutter/header px, window size, overscan, scrollbar thickness). One source of truth for server renderer + `app.cljs`. |
| `app` (`.cljs`) | Browser engine: logical-scroll math (custom wheel + scrollbars, sub-cell transforms), editor positioning, column/row resize, keyboard nav, session beacon. Bridges to the server only via `sr-*` window CustomEvents. Compiled to `/app.js`. |
| `auth` | Identity: OAuth 2.0 code flow (GitHub/Google, providers are data), name-only dev login, auth-token cookies; orchestrates hashing + the user/token registry in `db`. |
| `db` | Datahike-backed store: users, auth tokens, sheets+shares, **and sheet content** (cells as per-property, branch-aware datoms). Backends: H2 dev/staging, YugabyteDB prod (konserve-jdbc fork), `:memory` for tests. |
| `runtime` | Referenced by compiled formula bodies. `lookup`/`lookup-val` resolve a cell against the **current execution context's metadata** (works on executor threads). |
| `formula` | Parse + compile formulas to Spins. |
| `merge` | Pure 3-way branch merge: flatten docs to `[addr prop]→src`, classify against the common ancestor into auto-merge vs conflicts, build apply actions. No db/engine. |
| `graph` | Pure layered-DAG layout for the dependency-graph view: a forward deps-map → nodes + `[from to]` edges + longest-path `layer`. No db/engine. |
| `sheet` | Cell registry over one Spindel execution context. The engine API. |
| `store` | Persistence seam over `db`: the source document (cells + per-branch scalars) as datoms, branch `"main"`. Keeps `save!`/`load-record`/`exists?`/`list-names`. |
| `web` | Entry point: the http-kit `app` router + mount states (`server`/`sweeper`) + `-main`. The bulk is split into `web.*` below (layered, no cyclic deps). |
| `web.geom` | Pure grid geometry + small stateless helpers (`view-base`/`window`/`axis-*`/`in-window?`/`pretty-err`/`url-*`). |
| `web.state` | The `sheets*`/`sessions*` registries (keyed by the `(sheet,branch)` room) + pure accessors (`sheet-rec`/`accessible-rec`/`save-rec!`/…). No rendering, no broadcasts. |
| `web.sse` | SSE plumbing over the Datastar SDK (`sse`/`patch-inner!`/`signals!`) + the WebKit flush tick. |
| `web.render` | All server-rendered HTML/SVG: grid window, page shell, every modal, share/branch/graph/history fragments, auth pages. Reads state for presence; never pushes. |
| `web.collab` | Live collaboration: session lifecycle (register/ensure/reap/sweep) + per-room broadcasts + `/stream`. (reap↔broadcast are mutually recursive → same ns.) |
| `web.handlers` | Request handlers + access gates (`with-*`); mutate under the edit lock, persist, push. Plus auth routes + root page. |
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

Terse `$`-sugar (equivalent, not absolute refs): `$A1` ≡ `#cell A1`, `$A3:D8` ≡
`#cells A3:D8`. These read as ordinary symbols, so `parse` rewrites them on the
PARSED form (a `$A1` inside a string literal is left alone). They shift on paste
like the reader tags (`shift-refs`), and follow a row/column insert/delete
(`insert-shift` — a conditional shift that only moves refs at/after the inserted
line, so a range straddling it grows).

Pipeline (`formula`):
1. `parse`: `clojure.edn/read-string` with custom readers (EDN blocks `#=` RCE).
   `#cell`/`#cells` emit neutral ref-markers `(::ref "A1")`; a postwalk rewrites
   the `$A1`/`$A3:D8`/`$val` sugar to the same markers. `:deps` = the marked
   addresses.
2. `compile`: gensym each **distinct** referenced cell, then
   - **SCI-compile the pure user body** to a host-callable fn of those values
     (`sci/eval-form` of `(fn [c_1 …] <body, markers→syms>)` in a fork of the
     sheet's SCI context = stdlib + the sheet's user `defs`). SCI gives real
     lexical scope (`let`/`fn`/destructuring) with no host interop the user can
     reach, and never sees `spin`/`await`/`track`.
   - **host-`eval` only the fixed infra wrapper**: a factory
     `(fn [uf] (spin (let [c_1 (await (lookup "A1")) …] (uf c_1 …))))` in the
     `uno.michelada.saltrim.formula` namespace, so `spin`/`await`/`track` resolve
     and the CPS transform sees the effects; it closes over the SCI fn. Each
     distinct cell is `await`ed once in a `let` binding — two reasons: (a)
     `await` inside a nested `fn` is **not** CPS-transformed (so ranges expand
     statically at read time, which they do), and (b) **awaiting the same cell
     twice glitches** on recompute.

Sandbox = EDN reader (no `#=`) + SCI for the user expression. Host `eval` is used
only for that fixed infra wrapper (gensyms + validated cell-address strings — no
user input reaches it). SCI replaced the old symbol-whitelist + host-`eval` of
the body, which couldn't allow `let`/`fn` (a user's own binder names aren't in
any whitelist). See `spikes/03-sci-formula-eval.clj`.

Cycles: `sheet/would-cycle?` walks the forward dep graph from the new deps; if it
reaches the cell being set, reject before compile (a cycle StackOverflows the
await chain).

## Persistence (`store` over `db`)

- Persist the **source document**, not the Spindel graph — in **Datahike**, as
  datoms (not files; the old `data/<id>.edn` store is retired). The unit is one
  **property of a cell on a branch**: a `:cellprop` entity per
  `(sheet, branch, addr, prop)` → `src`. The cell's value is the `:value` prop;
  each style/format prop (`:bg`/`:fg`/`:format`/…) is its own cellprop, so adding
  a property needs **no schema change** — including non-presentational metadata
  like `:label` (a human-readable cell name for the graph view), which rides the
  exact same per-property path (branch/merge/as-of/undo for free).
  `:cellprop/author` records
  the current writer's uid (for per-user undo); the change time is Datahike's
  built-in `:db/txInstant`.
- Per-`(sheet, branch)` **content scalars** (axis-size defaults `dcw`/`drh`, the
  sparse `cols`/`rows` maps, the `defs` library) ride on a `:branch` entity as
  longs + edn-string blobs. Branch `"main"` (`db/MAIN`) is the default.
- **Branches** are the git-like dimension. `store/load-record`/`save!` take a
  `branch` (default `MAIN`). `db/fork-branch!` copies a branch's cellprops +
  scalars under a new name and records the **fork lineage** on the new `:branch`
  entity (`:branch/parent` + `:branch/base-tx` = the db's `:max-tx` at the fork
  instant) so a later 3-way merge can reconstruct the common-ancestor document
  via `as-of` base-tx. `db/branch-names`/`branch-exists?` list branches (MAIN
  always exists); `db/delete-branch!` retracts a non-main branch's datoms.
  `db/branch-revisions` lists a branch's change transactions and
  `db/sheet-doc-asof` rebuilds its document at any past tx (read-only history).
- `save!` is **diff-based** — it compares the runtime document against the db and
  transacts only changed props (+ retracts removed). Required: with
  `:keep-history? true`, re-asserting an unchanged datom is *not* a no-op (it logs
  a redundant history entry), so a blind whole-sheet re-transact would churn
  history. See `spikes/04-db-cell-storage.clj`.
- `load-record` rebuilds the reactive graph from the cellprops + branch scalars by
  replaying `set-cell!`/`set-style!` (order-independent; defs applied first). A
  reloaded sheet is fully live. Returns `{:sh :owner :public}` — owner is derived
  from the id; public/sharing live in the share ACL, not the document.
- **Storage ids are namespaced per owner**: `<owner-uid>__<name>`. Owner uids
  are `[a-z0-9-]` only (no underscores) and names `[A-Za-z0-9-]` (no
  underscores), so `split-id` is unambiguous on the first `__`.

## Identity & multi-tenancy (`auth` + `web`)

- **Login**: OAuth 2.0 authorization-code flow, hand-rolled over http-kit's
  client (`/auth/github`, `/auth/google` + `/callback`s; CSRF `state` nonces
  with a 10-min TTL). A provider activates when `SALTRIM_<PROVIDER>_CLIENT_ID`/
  `_CLIENT_SECRET` env vars are set; `SALTRIM_BASE_URL` builds redirect URIs.
  When **no** real provider is configured the **dev provider** (name-only form
  on `/login`, `GET /auth/dev?name=`) is on by default — `SALTRIM_DEV_AUTH=1/0`
  forces it either way.
- **Auth sessions**: 32-byte random token in an `HttpOnly; SameSite=Lax`
  cookie (`saltrim_auth`, 30 d; `Secure` when base-url is https). The cookie
  carries the secret; the DB stores only its **SHA-256 hash** (`db`, Datahike)
  — users and tokens survive restarts. `POST /logout` revokes the token **and
  reaps the user's live sessions** (presence markers / edit locks don't linger).
- **Datahike store** (`db`): users, auth tokens, sheet metadata + shares, **and
  sheet content** (cells as per-property branch-aware datoms — see Persistence).
  Backend is env-driven:
  H2 file (`data/saltrim-h2`) for dev/staging, a full JDBC url
  (`SALTRIM_DB_JDBC_URL`) for YugabyteDB in prod, `:memory` for tests
  (`SALTRIM_DB_BACKEND=mem`). `:keep-history? true` (as-of underpins the planned
  audit/branching features). JDBC support is konserve-jdbc directly (forked for
  YugabyteDB) — datahike 0.8 connects konserve stores generically, no
  datahike-jdbc wrapper. konserve caches one c3p0 pool per spec and closes it
  async on `create-database`'s release, so first-run creation pauses ~300 ms
  before `connect` (same spec → same pool) to avoid using a closed pool.
- **User ids**: `<prefix>-<ext-id>` sanitized to `[a-z0-9-]` (`gh-…`, `gg-…`,
  `dev-…`), max 24 chars — no underscores by construction (see storage ids).
- **Tenancy**: every request resolves through `accessible-rec [uid id token]`:
  owners reach (and auto-create) their own sheets; a foreign sheet is reachable
  iff the user holds a grant (a direct `:user` share) or carries the sheet's
  capability-link `token`. Unauthenticated → redirect `/login` (page) or an
  `$err` toast / 403 (API, stream). `?s=<name>` opens your own sheet,
  `?u=<owner>&s=<name>&t=<token>` someone else's shared one.
- **Sharing**: a Datahike ACL of `share` grants (`db` ns). Each grant is
  `(sheet, grantee, grantee-kind, level)` where kind ∈ `:user | :link`
  (`:group` reserved; `:everyone` is a legacy kind, auto-migrated) and level ∈
  `:read | :read-write`. **Ownership is not a grant** — it is derived from the
  `<owner>__<name>` id. Two paths:
  - **Capability link** (`:link`): an unguessable token (`grantee`) that lives
    only in the URL (`?t=…`), at view or edit level. Rotatable
    (`rotate-link!`). This is the "anyone with the link" mechanism — there is no
    blanket public-to-everyone tier, so knowing a name + sheet name grants
    nothing.
  - **Direct grants** (`:user`): share to one person by name (dev) / email
    (prod, resolved via `auth/resolve-grantee` → `db/uid-by-email`).
  `access-level` combines the caller's `:user` grant and the `:link` grant when
  the token matches, taking the highest level. Owner-only `POST /share`
  dispatches on `$shareact` (`link` / `rotate` / `grant` / `revoke`) and
  re-renders the `#sharebar` popover. The `/cell` write-guard rejects a write
  unless the effective level is `:read-write`.
  **Losing access evicts**: after any share change, `evict-unauthorized!` reaps
  every non-owner session whose access is now nil (link disabled/rotated, or
  grant removed — sessions remember their token to be re-checked); their stream
  drops and further access fails. A mere downgrade (edit→view) keeps the
  session; the write-guard handles it.
- **Sheet picker**: the toolbar `#sheetpicker` dropdown lists the user's sheets
  (`store/list-names`) and navigates on change; `#sheetbox` opens/creates a
  sheet by a new name.
- **Presence**: sessions carry `:uid`/`:uname`; peer markers show the real
  user name ("Bob editing…").

## Web layer (`web`)

### Rendering — logical scroll

No native scroll / giant spacer. The viewport is fixed-size, `overflow:hidden`,
with clipped header/cell layers each holding an absolutely-positioned inner layer
that the client `translate`s.

- Cells/headers are positioned **window-relative** to the rendered base
  `(cb,rb)` = `max(0, c0-OVER)`.
- `#meta` (hidden) carries totals `tw/th` (logical px, for scrollbar sizing), the
  base `cb/rb`, the **per-sheet default axis sizes `dcw/drh`**, and the sparse
  per-index size overrides `colw/rowh`. It is patched **together with `#cells`**
  so the client's transform always matches the displayed content (no jump
  mid-fetch). The client's geometry math (`axis-pos`/`axis-size`/`pixel->index`)
  reads `dcw/drh` from `#meta`, so client + server agree on a sheet's sizes.
- Geometry constants (the *initial* defaults; a sheet can override `CW/RH`):
  `CW=112 RH=26 GUT=48 HDR=26 OVER=2 WIN-COLS=16 WIN-ROWS=34 BAR=12`.
  `MAX-COLS=16384 MAX-ROWS=1048576` — a pure coordinate clamp now (no giant
  spacer → no DOM ceiling), sized to a familiar grid (XFD cols / 1,048,576 rows).
  Empty cells cost nothing (absent from registry → no spin).
- **Per-sheet sizing**: each axis defaults to `dcw`/`drh` (a sheet property,
  `CW`/`RH` initially, editable in the owner-only `⚙` properties modal → `/props`)
  with sparse per-column/row px overrides (`sheet :cols/:rows`, drag a header edge
  → `/size`). Resize drags **snap** to integer multiples of the default (hold
  `Alt` to disable). Defaults + overrides persist with the sheet (`:dcw/:drh`,
  `:cols/:rows`).
- Per-cell HTML is a tiny **display `<div>`** (not an input): `.cell` class,
  `left/top`, `data-raw` (source for the editor), text = value. No ~500 live
  inputs. Selection/edit are delegated on `#viewport`.

### Cell interaction & editing

- **Single click = select only** (`data-on:click` → `$sel` + mirror the cell's
  value/style into the bars, presence cursor). No editing, no lock — fully
  declarative.
- **Edit** opens a single floating `#editor` input over the active cell, on
  **double-click** or **Enter**. `app.cljs` positions it and moves focus in; the
  rest is declarative — `#editor` is `data-bind:v` (shares `$v` with the formula
  bar) + `data-show:$edit`; **Enter/blur commit** (`@post '/cell'` then drop the
  edit lock) and **Esc cancels** live in `data-on:keydown__stop`/`blur` (`__stop`
  keeps these keys from the doc-level nav handler).
- **Keyboard navigation** (document-level): arrows / Tab move `$sel` (scrolling
  it into view); Enter opens the editor. Ignored while any input is focused.

### Client engine (`app.cljs`)

- Imperative DOM work only: logical position `SX,SY`; wheel → translate
  `#cells/#colstrip/#rowstrip` by `(cb*CW - SX, rb*RH - SY)` for smooth sub-cell
  scroll; debounced **`/view`** only when the top-left index changes; re-align on
  `#meta` mutation (covers a collaborator's pushed scroll/resize too).
- Custom draggable scrollbars (`#vthumb/#hthumb`) sized from `#meta` totals.
- `jump(addr)` parses A1, sets `SX/SY`, forces a fetch (no clamp; `/view`'s
  `total-px` extends to cover the target).
- **No hidden trigger buttons.** When the server must hear about an imperative
  action, `app.cljs` dispatches an `sr-*` CustomEvent on `window`; declarative
  `data-on:sr-*__window` handlers on `#ctl` (and `#streamer` for the stream)
  turn each into the Datastar action, reading carried data off `evt.detail`.
  Geometry constants come from the shared `constants` cljc; per-render
  base/totals from `#meta`. Datasets are read via `aget` (advanced-safe).

### Endpoints

- `GET /` — page for `?s=<name>` (own sheet, default `default`) or
  `?u=<owner>&s=<name>` (foreign shared sheet); `&b=<branch>` picks the working
  branch (default `main`, bad/deleted branch → main). Redirects to `/login` when
  unauthenticated, 403 page when denied.
- `POST /branch` — owner-only branch op (`$branchact`: fork `$bname` from the
  current `$branch`, or delete it); sets `$goto` to navigate on success.
- `POST /merge` — owner-only 3-way merge of `$mergefrom` into the current branch
  (`$branchact`: preview → patch `#mergeresult`; apply → take auto + the
  `$mergetake` conflicts from source).
- `POST /graph` — render the dependency graph (`graph` ns over `sheet/deps`) as a
  layered SVG into `#graphview` (any reader; capped at 250 nodes). Node label =
  the cell's `:label` meta-prop else its address; a node click sets `$sel`.
- `POST /viewat` — read-only scroll for an as-of view: re-render the window of
  (sheet, branch) as of `$at` from a transient historical sheet (no room).
- `GET /login`, `GET /auth/<provider>[/callback]`, `GET /auth/dev?name=`,
  `POST /logout` — identity (see above).
- `POST /share` — owner-only sharing mutation (`$shareact`: link / rotate /
  grant / revoke); patches `#sharebar`.
- `GET /app.js` (compiled ClojureScript bundle), `GET /datastar.js` (vendored
  Datastar fallback).
- `GET /stream` — **persistent** per-session SSE (auth + access checked; 403
  otherwise). Opened with Datastar `@get` from `#streamer`, so `sid/sheet/link`
  ride in the request **signals** (not the URL). Registers the session, stores
  its generator, flushes once to establish the stream. Stays open.
- `POST /cell` — edit (Datastar `@post`, signals carry `cell/v/sheet/sid`).
  Edits, settles, autosaves, returns the editor's window patch + `$err`, and
  **broadcasts** the change to other sessions on the sheet.
- `POST /view` — window change (signals carry `r0/c0/sheet/sid`). Patches
  `#cells/#colhead/#rowhead` inner + `#meta`.
- `POST /style` — set a style/format/`:label` prop. Applies to the **whole
  selection** when `$selcells` spans >1 cell (app.cljs keeps `$selcells` live),
  else the single `$cell`.
- `POST /insert` — insert a blank row/column at the active cell
  (`$insertdir` top|bottom|left|right). `sheet/insert-line!` shifts cells and
  rewrites formula refs (`formula/insert-shift`); one **structural** undo entry
  (`sheet/undo-step` reverses it via `delete-line!` in a single Ctrl+Z); full
  window re-render + broadcast.
- `POST /session/end` — `navigator.sendBeacon` on `pagehide` → `reap-session!`.
- `GET /debug` — session/sheet detail; only served while the dev auth provider
  is active (404 otherwise).

### Sessions & sheet lifecycle

- The collaborative unit is a **room** = `[sheet-id branch]`: a branch is its own
  working copy, so two users on different branches of the same sheet are isolated.
  `sessions*` `{sid -> {:sheet :branch :room :view :dims :gen :last-seen}}`;
  `sheets*` `{[id branch] -> sheet}` (lazy load per room). Every broadcast /
  presence / lock / def-lock helper filters on `(= room (:room s))`; the share
  ACL stays per-**sheet** (access grants all branches), so eviction is per-sheet.
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
- Stream reconnect (`app.cljs`): Datastar `@get` SSE doesn't reconnect forever;
  on `datastar-fetch` `finished`/`retries-failed` **for `#streamer`** (its own
  element, so distinguishable from the `@post`s) re-dispatch `sr-open` with capped
  backoff (reset on `started`). `/stream` on-open keeps an existing session's view
  and just swaps in the new generator.

### Branching (web)

- The working branch rides in `&b=` (page) and `$branch` (every POST), resolved
  to a room. A stale/typo'd/deleted branch silently falls back to `MAIN`
  (creation is explicit via fork). A branch picker switches by navigating; an
  owner-only 🌿 modal forks/deletes via `POST /branch`.
- **Fork** copies the current branch (`db/fork-branch!` + lineage) and sets a
  `$goto` signal; a `data-effect` element navigates to the new branch (full
  reload). **Delete** (`db/delete-branch!`) drops a non-main branch, then
  **discards the in-memory room without saving** so `unload-sheet!` can't
  re-persist (resurrect) the deleted cells, and `$goto`s back to main.
- Owner-only is enforced by `with-owner` (same gate as sharing/properties);
  editors can switch to and edit any existing branch (access is per-sheet).
- **As-of viewing** (`&at=<tx>` / `$at`): a read-only snapshot of (sheet, branch)
  at a past transaction. `db/branch-revisions` lists change-points from history;
  `db/sheet-doc-asof` → `store/load-record-asof` rebuilds a TRANSIENT sheet at
  that tx. The as-of page is **request-scoped** — it loads no live room, opens no
  stream, and exposes only scroll (`/viewat`, which re-renders the historical
  window from a fresh transient sheet). Edits can't happen (controls hidden; and
  `with-access` forces `:level :read` whenever `$at` is set), so the past is
  never mutated. A 🕘 modal (live) enters it; a banner + revision picker +
  Back-to-live drive it.
- **Merge** (`POST /merge`, owner-only): brings a source branch INTO the current
  one. `db/merge-base` resolves the common-ancestor document from fork lineage
  (`:branch/parent` + `:branch/base-tx`) via `as-of` — source-of-target,
  target-of-source, or sibling cases (one-level; deep chains aren't walked).
  `merge/plan` does the pure 3-way classification per cell-property: only-source
  changed → auto-take (incl. add/delete); only-target → keep; both → conflict.
  Preview patches `#mergeresult` (clean count + a per-conflict checkbox toggling
  its key in `$mergetake`); apply writes the auto set plus the conflicts the
  owner chose to take-source onto the target engine (`set-cell!`/`set-style!`,
  per-prop undo), then settles, saves, and re-renders the window for everyone.

### Presence & edit locking

Selection and presence are **server-rendered overlays** — no per-cell client JS,
no class-toggling on the cell `<input>`s (which would clobber the caret
mid-edit). Two absolutely-positioned layers inside `#cellclip`, translated with
`#cells` by `app.js`: `#self` (this user) and `#peers` (everyone else).

- **Presence state**: each session has a deterministic `:color` (hashed sid),
  `:cursor` (the cell it is on) and `:editing` (the cell it is actively editing,
  else nil). Presence is posted via Datastar `@post('/presence')` — on cell
  **click** (`$edit=false`, cursor), on **starting an edit** (`$edit=true`, lock,
  via the `sr-edit` bridge), on **commit/cancel** (`$edit=false`), and from the
  formula bar's `data-on:focus`/`blur` — carrying signals `$sel` and `$edit`.
  Keyboard nav / jump go through the `sr-select` bridge. `handle-presence`
  updates the session, then patches **this** session's `#self` back on the
  `@post` response and re-broadcasts `#peers` to everyone (`broadcast-presence!`).
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
formula, structural rebuild, errors, cycles, SCI formulas, `$`-refs, defs
library), `fmt` (number masks), `graph` (layered-DAG layout), `merge` (3-way
plan, conflicts, actions), `store` (branch-aware save/load roundtrip + diff,
as-of snapshot, sizing/defs roundtrip, undo/redo, valid-id, storage-id split),
`auth` (dev login, uid sanitizing, cookie roundtrip, token revocation), `db`
(user upsert + created-at stability, token roundtrip/revoke, shares + capability
links, branch fork/lineage/revisions, per-test isolation — `:memory` backend).
Currently 72 tests / 364 assertions. Web/session/collab behavior is verified
manually + via curl (see CLAUDE.md); no web unit tests yet.

## Build & release

SaltRim is a **runnable web app**, not a library — it is **not** published to
Clojars. The distributable is a standalone uberjar attached to a GitHub Release.

- `build.clj` (alias `:build`, tools.build) AOT-compiles the gen-class main
  (`uno.michelada.saltrim.web`) and packages `target/saltrim-<version>.jar`.
  Run: `clojure -T:build uber` → `java -jar target/saltrim-<version>.jar`
  (serves on `:8080`). Version comes from `$VERSION`, else `0.1.<git-revs>-dev`.
- `.github/workflows/release.yml` triggers on a `v*` tag: test → build uberjar
  (VERSION = tag without the `v`) → publish a GitHub Release with the jar.
  Uses the auto `GITHUB_TOKEN`; no Clojars token needed.
- Coordinate `uno.michelada/saltrim`; repo lives under the `michelada-uno` org.

To cut a release: `git tag v1.2.3 && git push origin v1.2.3`.

## Known limitations

See `TECHDEBT.md`. Highlights: `WIN-COLS/ROWS` fixed
(not viewport-computed); last-write-wins within a branch (cross-branch is a 3-way
**merge**, owner-driven); merge lineage is one-level (no deep-fork LCA);
as-of/history views use current defs+sizing (only cells are reconstructed) and
rebuild a transient sheet per scroll; deleting a branch other collaborators are
actively on strands them (they get denied + must reload to main); session-less
sheets loaded by a bare `GET /` aren't swept; concurrent simultaneous edits can
race a transient `#ERR`. (`/debug` is gated behind the dev auth provider —
404 in prod.)
