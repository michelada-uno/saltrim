# Clorax — technical spec

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

## Namespaces (`src/uno/michelada/clorax/`, ns root `uno.michelada.clorax`)

| ns | role |
|----|------|
| `addr` | A1 addressing. `col<->idx`, `parse`, `make`, `range-cells`, `valid?`. Address = letters+digits (`AAB1234`); **no colon** (colon = range separator, `A1:C3`). 0-based `ci/ri` internally. |
| `auth` | Identity: OAuth 2.0 code flow (GitHub/Google, providers are data), name-only dev login, auth-token cookies; orchestrates hashing + the user/token registry in `db`. |
| `db` | Datahike-backed registry (users, auth tokens; sheets+shares next). Backends: H2 dev/staging, YugabyteDB prod (konserve-jdbc fork), `:memory` for tests. |
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
4. `compile`: `eval (spin lifted)` in the `uno.michelada.clorax.formula` namespace so
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
  property compiled from its own source). EDN at `<dir>/<id>.edn`, where `dir`
  is `CLORAX_DATA_DIR` (default `data/`).
- **fmt 2** wraps it in an ownership envelope:
  `{:fmt 2 :owner uid :public bool :cells …}`. fmt 1 files (pre-auth) load as
  owner nil + public true (legacy sheets stay readable). `load-record` returns
  `{:sh :owner :public}`; `load-sheet` just the engine.
- **Storage ids are namespaced per owner**: `<owner-uid>__<name>`. Owner uids
  are `[a-z0-9-]` only (no underscores) and names `[A-Za-z0-9-]` (no
  underscores), so `split-id` is unambiguous on the first `__`.
- `load-sheet` rebuilds the reactive graph by replaying `set-cell!` (order-
  independent — formula refs resolve at run time). A reloaded sheet is fully live.
- `valid-id?` guards path traversal. Behind `save!`/`load-record` so the backend
  can become Datahike/SQL later.

## Identity & multi-tenancy (`auth` + `web`)

- **Login**: OAuth 2.0 authorization-code flow, hand-rolled over http-kit's
  client (`/auth/github`, `/auth/google` + `/callback`s; CSRF `state` nonces
  with a 10-min TTL). A provider activates when `CLORAX_<PROVIDER>_CLIENT_ID`/
  `_CLIENT_SECRET` env vars are set; `CLORAX_BASE_URL` builds redirect URIs.
  When **no** real provider is configured the **dev provider** (name-only form
  on `/login`, `GET /auth/dev?name=`) is on by default — `CLORAX_DEV_AUTH=1/0`
  forces it either way.
- **Auth sessions**: 32-byte random token in an `HttpOnly; SameSite=Lax`
  cookie (`clorax_auth`, 30 d; `Secure` when base-url is https). The cookie
  carries the secret; the DB stores only its **SHA-256 hash** (`db`, Datahike)
  — users and tokens survive restarts. `POST /logout` revokes the token **and
  reaps the user's live sessions** (presence markers / edit locks don't linger).
- **Datahike store** (`db`): users + auth tokens (sheet metadata + shares move
  here next; sheet CELL data stays in the file store). Backend is env-driven:
  H2 file (`data/clorax-h2`) for dev/staging, a full JDBC url
  (`CLORAX_DB_JDBC_URL`) for YugabyteDB in prod, `:memory` for tests
  (`CLORAX_DB_BACKEND=mem`). `:keep-history? true` (as-of underpins the planned
  audit/branching features). JDBC support is konserve-jdbc directly (forked for
  YugabyteDB) — datahike 0.8 connects konserve stores generically, no
  datahike-jdbc wrapper. konserve caches one c3p0 pool per spec and closes it
  async on `create-database`'s release, so first-run creation pauses ~300 ms
  before `connect` (same spec → same pool) to avoid using a closed pool.
- **User ids**: `<prefix>-<ext-id>` sanitized to `[a-z0-9-]` (`gh-…`, `gg-…`,
  `dev-…`), max 24 chars — no underscores by construction (see storage ids).
- **Tenancy**: every request resolves through `accessible-rec`: owners reach
  (and auto-create) their own sheets; a foreign sheet is reachable iff it
  exists and is `:public`. Unauthenticated → redirect `/login` (page) or an
  `$err` toast / 403 (API, stream). `?s=<name>` opens your own sheet,
  `?u=<owner>&s=<name>` someone else's shared one.
- **Sharing**: owner-only `POST /share` toggles `:public` (persisted
  immediately); the `#sharebar` toolbar fragment shows the toggle + share link
  to the owner, a "shared by <name>" badge to visitors. Anyone signed-in can
  edit a public sheet (live collaboration); there is no read-only tier yet.
- **Presence**: sessions carry `:uid`/`:uname`; peer markers show the real
  user name ("Bob editing…").

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
- Per-cell HTML is a tiny **display `<div>`** (not an input): `.cell` class,
  `left/top`, `data-raw` (source for the editor), text = value. No ~500 live
  inputs. Selection/edit are delegated on `#viewport`.

### Cell interaction & editing

- **Single click = select only** (`data-on:click` → `$sel`, presence cursor). No
  editing, no lock.
- **Edit** opens a single floating `#editor` input over the active cell, on
  **double-click** (`data-on:dblclick` → `startEdit`) or **Enter** (keyboard).
  Committed on **Enter / blur / double-click**, cancelled on **Esc**. `app.js`
  positions/shows/hides it and wires its keydown/blur (real listeners — Datastar
  `data-on:keydown` proved unreliable on synthetic events; the editor's keydown
  `stopPropagation`s so the doc-level nav handler doesn't re-open it). The actual
  posts stay Datastar: hidden `#selecttrigger`/`#edittrigger`/`#celltrigger`
  buttons (`@post '/presence'` / `'/cell'`); `#editor` is `data-bind:v`.
- **Keyboard navigation** (`onKey`, document-level): arrows / Tab move `$sel`
  (scrolling it into view via `ensureVisible`); Enter opens the editor. Ignored
  while editing or when a toolbar input is focused.

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

- `GET /` — page for `?s=<name>` (own sheet, default `default`) or
  `?u=<owner>&s=<name>` (foreign shared sheet); redirects to `/login` when
  unauthenticated, 403 page when denied.
- `GET /login`, `GET /auth/<provider>[/callback]`, `GET /auth/dev?name=`,
  `POST /logout` — identity (see above).
- `POST /share` — owner-only `:public` toggle; patches `#sharebar`.
- `GET /app.js`, `GET /datastar.js` — vendored assets.
- `GET /stream?sid=&s=` — **persistent** per-session SSE (auth + access checked;
  403 otherwise). Registers the session, stores its generator, flushes once to
  establish the stream. Stays open.
- `POST /cell` — edit (Datastar `@post`, signals carry `cell/v/sheet/sid`).
  Edits, settles, autosaves, returns the editor's window patch + `$err`, and
  **broadcasts** the change to other sessions on the sheet.
- `POST /view` — window change (signals carry `r0/c0/sheet/sid`). Patches
  `#cells/#colhead/#rowhead` inner + `#meta`.
- `POST /session/end` — `navigator.sendBeacon` on `pagehide` → `reap-session!`.
- `GET /debug` — session/sheet detail; only served while the dev auth provider
  is active (404 otherwise).

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
  else nil). Presence is posted via Datastar `@post('/presence')` — on cell
  **click** (`$edit=false`, cursor), on **starting an edit** (`$edit=true`, lock,
  via `#edittrigger`), on **commit/cancel** (`$edit=false`, via `#selecttrigger`),
  and from the formula
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
valid-id, ownership envelope, fmt-1 legacy, storage-id split), `auth` (dev
login, uid sanitizing, cookie roundtrip, token revocation), `db` (user upsert +
created-at stability, token roundtrip/revoke, per-test isolation — `:memory`
backend). Currently 26 tests / 101 assertions. Web/session/collab behavior is
verified manually + via curl (see CLAUDE.md); no web unit tests yet.

## Build & release

Clorax is a **runnable web app**, not a library — it is **not** published to
Clojars. The distributable is a standalone uberjar attached to a GitHub Release.

- `build.clj` (alias `:build`, tools.build) AOT-compiles the gen-class main
  (`uno.michelada.clorax.web`) and packages `target/clorax-<version>.jar`.
  Run: `clojure -T:build uber` → `java -jar target/clorax-<version>.jar`
  (serves on `:8080`). Version comes from `$VERSION`, else `0.1.<git-revs>-dev`.
- `.github/workflows/release.yml` triggers on a `v*` tag: test → build uberjar
  (VERSION = tag without the `v`) → publish a GitHub Release with the jar.
  Uses the auto `GITHUB_TOKEN`; no Clojars token needed.
- Coordinate `uno.michelada/clorax`; repo lives under the `michelada-uno` org.

To cut a release: `git tag v1.2.3 && git push origin v1.2.3`.

## Known limitations

See `TECHDEBT.md`. Highlights: `WIN-COLS/ROWS` fixed
(not viewport-computed); last-write-wins (no merge); session-less sheets loaded
by a bare `GET /` aren't swept; `/debug` is ungated; concurrent simultaneous
edits can race a transient `#ERR`.
