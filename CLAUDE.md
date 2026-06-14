# Clorax — working instructions

A simple-but-powerful spreadsheet: **Clojure** engine on **Spindel** (reactive),
**Datastar** UI (hypermedia, SSE), file persistence, live collaboration.
Read `SPEC.md` for the technical architecture. This file = how to work here.

## MCP servers usage

- **Qdrant:** When available, use Qdrant MCP server (`mcp-server-qdrant`, 
  `qdrant-local`, etc...) for persistent vector memory. When using it, explicitly
  use collection name `dev-clorax` (migrated from `dev-calcloj` after the rename;
  old collection kept as backup). If the MCP tools error, the Qdrant REST API on
  `localhost:6333` works directly (scroll/upsert).

## Communication style

The user runs a "caveman" mode plugin — terse, fragments, drop filler. Match it
in chat. **Write code, commits, PRs, and docs normally** (full sentences).
If the user types `/caveman`, invoke the `caveman` Skill.

## How the user works (observed preferences)

- **Decisive, hands-on, opinionated.** They review closely and push back when an
  approach is wrong (e.g. "use another server?", "I don't like heartbeat",
  "collaboration is a need"). Take pushback seriously — they're usually right.
- **Verify before claiming.** They dislike hand-waving. Read real source, run
  spikes, test in the browser, show evidence. Don't assert behavior you haven't
  checked.
- **Prefers clean structure**: no inline JS in HTML (use `/app.js`), no stray
  top-level forms, single source of truth, separate files.
- **Wants extensibility planned now** for near-future features (e.g. style/format
  as reactive properties; the persistence format already leaves room).
- They sometimes edit files between turns (addr.clj, gitignore, etc.). Respect
  those edits; don't revert them.

## Workflow

- **PR workflow**: commit/push/open PRs freely on feature branches — no need
  to ask. Never commit directly to `main`; the user reviews and merges PRs.
  End commit messages with:
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
- One coherent change per commit; write a real body explaining *why*.
- **Spike risky unknowns first** (there are `spike*.clj` files proving Spindel
  behavior). Don't build UI on unproven engine assumptions.
- **Test after engine changes**: `clojure -X:test` (must stay green, currently
  26 tests / 101 assertions; `db`/`auth` suites use the `:memory` Datahike
  backend). Add tests for new engine behavior.
- **Check `app.js` syntax** after editing: `node --check resources/public/app.js`.
- Keep `TECHDEBT.md` current — append when you defer something, mark items DONE.

## Running / testing the app

```bash
clojure -M:web         # server on http://localhost:8080  (open ?s=<sheet-id>)
clojure -X:test        # engine + addr + store suites
clojure -M:spike       # Step-0 lifecycle spike (and :spike0b, plus spike4.clj)
node --check resources/public/app.js

clojure -T:build uber             # runnable uberjar -> target/clorax-<v>.jar
java -jar target/clorax-<v>.jar  # run the built artifact (serves :8080)
```

**Identity store (Datahike).** Users + auth tokens live in Datahike (`db` ns),
not files. Dev/staging defaults to an H2 file at `data/clorax-h2`; prod sets
`CLORAX_DB_JDBC_URL` (YugabyteDB); tests use `:memory`. Env: `CLORAX_DB_BACKEND`
(`mem`), `CLORAX_DB_JDBC_URL`, `CLORAX_DB_TABLE`, `CLORAX_DB_PATH` (H2 file),
`CLORAX_DB_ID` (stable store UUID). JDBC is konserve-jdbc directly (forked for
YugabyteDB — see `deps.edn`); **datahike-jdbc is NOT used** (datahike 0.8
connects konserve stores generically). Sheet CELL data still lives in
`<CLORAX_DATA_DIR>/<id>.edn` (env, default `data/`). **Spindel stays pinned at
0.1.15** — 0.1.23 breaks structural rebuild (see TECHDEBT.md).

Namespaces are rooted at `uno.michelada.clorax.*` under
`src/uno/michelada/clorax/`. Coordinate `uno.michelada/clorax`; repo lives in
the `michelada-uno` GitHub org. **Releases are GitHub-only (no Clojars)**: push a
`v*` tag and `.github/workflows/release.yml` tests, builds the uberjar, and
attaches it to a GitHub Release. See SPEC.md "Build & release".

### Browser verification (important, and harness-specific)

Use the **Claude Preview** MCP tools (`preview_start` with `.claude/launch.json`,
then `preview_eval`/`preview_screenshot`/`preview_console_logs`/`preview_network`).

Gotchas learned the hard way:
- `preview_start` launches a **fresh JVM**. To pick up `web.clj` edits, restart
  the server. `app.js`/`datastar.js` are slurped per request, so a browser
  **reload** picks those up without a server restart.
- The preview harness ties the browser tab to *its* managed server. **Killing
  the server breaks browser control** — you can't cleanly test a real
  server-restart reconnect this way. Test mechanisms with synthetic events +
  `curl` instead.
- `preview_fill` does **not** fire `change`/`focusin`; dispatch events yourself
  in `preview_eval` (e.g. `el.dispatchEvent(new Event('change',{bubbles:true}))`).
- **Test collaboration on a clean load.** Heavy reload/jump churn leaves stale
  client state that *looks* like a collab bug but isn't. Reproduce server-side
  with two `curl` clients (one holding `/stream`, one POSTing `/cell`) before
  suspecting the engine.
- `GET /debug` returns session + loaded-sheet detail (dev only — gate before any
  real deploy).
- Free the port between runs: `lsof -ti:8080 | xargs kill -9`.

## Spindel gotchas (the engine) — already solved, don't relearn

- `track` returns an **Interval**, not a value — read with `@(track sig)`.
- Signal mutation only **enqueues**; the executor drains async. Don't pump a
  drain loop; in app code just read after it settles. Tests use
  `sheet/settle!` (→ `simple/await-drain-complete!`) as a barrier.
- **`track` only handles `SignalRef`**, not `Spin`. Cross-formula refs use
  `await`. Every cell is a Spin; literals are a thin spin over an editable
  signal. See `SPEC.md`.
- **Awaiting the same cell twice in one body glitches** on recompute. The
  formula compiler de-dupes: each distinct cell is `await`ed once in a `let`.
- `await`/`track` must appear **literally** in the spin body (CPS breakpoints) —
  not inside a nested `fn`. Ranges expand statically at read time.
- A cyclic formula **StackOverflows** — `sheet/would-cycle?` rejects before
  install.

## Datastar / http-kit gotchas — already solved

- We vendor `datastar.js` **1.0.0** locally (CDN/version churn bit us). SSE
  events: `datastar-patch-elements` / `datastar-patch-signals`. Attrs use colon
  syntax (`data-on:click`, `data-bind:x`).
- SSE/lifecycle now uses the official SDK (`dev.data-star.clojure/*`).
- **http-kit does NOT fire an async-channel close on idle disconnect without a
  write** (verified). So session cleanup uses `navigator.sendBeacon` on
  `pagehide` + a TTL sweep — **no heartbeat**. Don't reintroduce heartbeats.
- A persistent SSE that sends nothing looks "finished" to the client → reconnect
  storm. `/stream` flushes an empty signals patch on open to establish it.
- There is no `data-on:load` plugin in this bundle. `app.js` triggers Datastar
  actions by clicking hidden buttons after `datastar-ready`.

## Status / roadmap

Done: reactive engine, A1 addressing + ranges, formulas (incl. formula→formula),
errors+toast, cycle detection, tests, persistence, sessions (beacon + TTL
sweep), live collaboration (push streams + reconnect), logical scroll, keyboard
navigation, **auth + multi-tenancy** (OAuth GitHub/Google + dev login, per-user
sheets `<uid>__<name>`, owner-only /share, named presence). Dev login is on by
default when no `CLORAX_*_CLIENT_ID/SECRET` env vars are set. Next candidates
(user's call): **style/format as reactive properties** (persistence format is
ready for it), read-only share tier, conflict policy. See `TECHDEBT.md` for
deferred items.
