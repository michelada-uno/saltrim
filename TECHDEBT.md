# Tech debt

## Scroll model — DONE (logical scroll)

Replaced the giant-spacer native scroll with a logical-scroll engine (no sized
div): /app.js keeps a logical pixel position (SX,SY), translates the window
layers by the offset for smoothness, draws custom scrollbars, and fetches a new
window (POST /view) only when the top-left cell index changes. Cells are
positioned window-relative; the window base (cb/rb) ships in #meta alongside
#cells so the transform always matches the displayed content (no jump while a
fetch is in flight). Row cap and scrollbar-precision issues are gone.

With the giant spacer gone, `MAX-COLS`/`MAX-ROWS` are no longer a DOM-element
ceiling — they're a pure coordinate clamp, sized to a familiar grid
(`16384` cols = XFD, `1048576` rows). The "600000 to stay under Firefox's
element limit" rationale below is historical.

Follow-ups: keyboard nav DONE (arrows/Tab/Enter move selection, Enter/dblclick
edit — see web layer); PgUp/PgDn/Home/End not yet wired; WIN-COLS/ROWS are fixed
(generous) rather than computed from viewport size; momentum/trackpad feel is
raw deltas (could smooth). The notes below are the original analysis, kept for
context.

### (original) giant spacer analysis

Current viewport uses a "fake-scroll" spacer: one `<div id="space">` sized to the
logical sheet (`cols*CW × rows*RH`) so the native scrollbar reflects the sheet
size, with only the visible window of cells rendered on top.

**Problem:** the spacer height is bounded by the browser's max element pixel size
(~33.5M px Chrome/Safari, ~17.9M px Firefox). At 26px/row that caps usable rows
at ~1.28M (Chrome) / ~688k (Firefox). `MAX-ROWS` is pinned to 600000 to stay
under the Firefox limit. Scrollbar precision is also terrible at that scale
(~1 px ≈ thousands of rows).

**Want:** a scroll model that needs **no huge div** — compute everything from a
logical position instead of a physical one. Options:
- custom/synthetic scrollbar whose thumb maps to a row range (non-linear ok),
  decoupled from any sized element;
- "logical scroll": intercept wheel/drag, keep a virtual `r0/c0` offset, render
  the window at fixed screen coords (translate, not scroll), draw our own
  scrollbar. Address-box jump already proves far-navigation without scrolling.

This removes the row cap entirely and makes the cap purely a coordinate clamp.

## Other

- Concurrent edits can race into a transient `#ERR` / stale toast (per-sheet
  lock serializes server side, but simultaneous async posts arrive unordered).
- No config system yet — grid bounds, geometry are `def` constants. Fold into
  per-sheet settings once persistence lands.

## Sessions — crash/sleep cleanup backstop

DONE: sessions now register on load (`/session/start`) and release on unload via
`navigator.sendBeacon('/session/end')`; sheets are ref-counted and unloaded
(execution context closed, saved) when the last session leaves; viewport is
per-session.

DONE (backstop): each session is stamped with :last-seen (touched on /cell,
/view); a server-side sweep (every 60s) reaps sessions idle > 30 min and unloads
their sheets — so crash/sleep, where the beacon never fires, no longer pins a
sheet forever. A swept client transparently re-registers on its next action
(ensure-session!). No client heartbeat.

REMAINING (minor):
- a sheet loaded by a bare GET / with no following /session/start (e.g. a probe)
  has zero sessions and is never unloaded. Consider sweeping session-less sheets
  too (carefully — avoid racing a load that is mid-handshake).
- TTL/sweep interval are constants; move to config.

NOTE: collaboration uses a persistent per-session SSE stream (/stream) for
server->client push. Cleanup does NOT rely on http-kit's channel close (it
doesn't fire on idle disconnect without a write); instead beacon + sweep call
reap-session! which close-sse!s the stored generator. No heartbeat.

## Collaboration — follow-ups

- **Stream reconnect**: the stream is opened via Datastar `@get`; if it drops
  (server restart, transient network) the client does not auto-reconnect like a
  native EventSource. Add a reconnect (re-click the trigger on error / poll).
- **Dead-connection reaping latency**: a crashed peer's stream is closed on the
  next broadcast to it (write throws -> reap) or by the sweep. Fine, but means a
  zombie socket can linger up to the sweep interval with no traffic.
- **Editor double-path**: the editor still gets its patches from the one-shot
  `@post` response; peers get them via the stream. Could unify (everything via
  streams, `/cell` returns empty) once reconnect is solid.
- **Conflict policy**: last-write-wins for distinct cells. Same-cell editing is
  now guarded by presence locks (below); cross-cell merge is still absent.

The `/debug` endpoint (session/sheet counts) is dev-only — gate or remove before
any real deployment.

## Presence & edit locking — follow-ups

DONE: collaborator cursors + edit locks + selection — all SERVER-RENDERED as
overlays (#self for the current user, #peers for others), no per-cell client JS.
Per-session :cursor/:editing; presence posted declaratively via Datastar
(@post '/presence' in cell + formula-bar data-on handlers). Editing marker locks
the cell (pointer-events + server-side locked-by-other? guard in /cell).
Selection persists off-focus (box stays on :cursor); editing tier = animated
marching-ants border.

REMAINING:
- **Stuck lock on crash mid-edit**: if a client sets :editing and then crashes
  (no blur/commit, no beacon), the cell stays locked for that peer until the TTL
  sweep (30 min) reaps the session. Mitigations to consider: a short per-edit
  lock TTL (auto-expire :editing after N seconds of no refresh, with the client
  re-asserting while focused), or clearing :editing on stream death.
- **Selection latency**: the #self overlay moves on a server round-trip (presence
  @post -> patch). Native input :focus masks it, but addrbox jumps show a small
  lag. Fine; revisit only if it feels sluggish.
- **Presence chattiness**: every focus/blur POSTs /presence and re-broadcasts the
  whole #peers overlay to all sessions (and patches #self back). Fine at small
  scale; debounce / diff if sessions-per-sheet grows.
- **No name/identity**: DONE — sessions carry :uid/:uname from the auth layer;
  peer markers show the real user name ("Bob editing…").

## Auth & multi-tenancy — follow-ups

DONE: OAuth login (GitHub/Google via env config, hand-rolled code flow on
http-kit's client), name-only dev provider (auto-on when no real provider is
configured), HttpOnly token cookies with persistent users/tokens registries,
per-owner sheet namespacing (`<uid>__<name>`, fmt 2 ownership envelope),
owner-only /share toggle (+ share link), access checks on every endpoint,
logout reaps the user's live sessions, /debug gated behind the dev provider.

DONE (Datahike step 1): users + auth tokens moved off the EDN files into a
Datahike store (`db` ns). Tokens are now stored as a **SHA-256 hash** (cookie
carries the secret). Backends: H2 dev/staging, YugabyteDB prod (konserve-jdbc
fork), `:memory` for tests. Verified: token survives a server restart; logout
revokes it. (Old `data/users.edn`/`data/tokens.edn` are now unused.)

REMAINING:
- **Sheet `:public` flag moved to the DB ACL — DONE (Datahike step 2a).** A
  sheet's public state is now an `:everyone`/`:read-write` `share` row, not the
  file's `:public` bool. `sheet-rec` registers the `sheet` entity (`ensure-
  sheet!`) and, on its FIRST registration, one-shot-migrates the file's legacy
  `:public` into a grant; `accessible-rec` queries `db/access-level`; `handle-
  share` toggles the grant. The file envelope still carries `:public` (vestigial
  migration seed; harmless). Sheet ids stay `<owner>__<name>` strings (the uuid
  switch was NOT needed and is deferred).
- **Read-only tier + direct user grants — DONE (Datahike step 2b).** Public is
  now a level (`:everyone`/`:read` view OR `:read-write` edit), and owners can
  grant **direct per-user shares** (`:user` kind; resolved by name in dev /
  email in prod via `auth/resolve-grantee` → `db/uid-by-email`). `with-access`
  threads the caller's effective `:level` into the rec; `handle-cell` rejects a
  write when it isn't `:read-write`, and `handle-presence` won't let a viewer
  hold an edit lock. `handle-share` dispatches on a `$shareact` signal
  (public/grant/revoke); `evict-unauthorized!` reaps only sessions that LOST
  access (a downgrade isn't evicted — the write-guard covers it). UI: owner
  share **panel** (public level select + link + grant list/add/remove) and a
  picker that groups 'your sheets' (👤) and 'shared with you' (✎/👁).
  REMAINING: **group/org grants** (`:group` kind is in the schema but unused);
  email shares only reach users who've already signed in (no pending invites);
  a `:read` viewer with an open editor sees writes blocked only on commit (no
  proactive UI lock-out).
- **Capability-link sharing — DONE (Datahike step 2c).** "Anyone with the link"
  is now a `:link` grant whose `grantee` is an unguessable token carried in the
  URL (`?t=…`), not the guessable `<owner>__<name>` URL. Closes the enumeration
  hole (knowing a name + sheet name no longer grants access) and adds
  **rotation** (`rotate-link!` mints a new token, killing old links). One link
  per sheet at a level (view/edit). The token rides through every layer: page
  seeds `$link`, POSTs send it, `/stream` takes `?t=`; sessions remember their
  token so a rotate/downgrade re-checks them in `evict-unauthorized!`. The old
  blanket `:everyone` tier is GONE — `migrate-everyone->link!` upgrades any
  legacy public grant to a link on load. db: `link-grant`/`set-link-level!`/
  `rotate-link!`; `access-level` now takes `[uid sheet-id token]`.
  REMAINING: **discoverable-public gallery** (opt-in publish + its own paginated
  browse view — deliberately NOT the personal picker, to avoid clutter) and a
  bounded **recents** list for link-visited sheets are both deferred; the link
  token is stored in plaintext (it's a capability URL, not a credential — fine,
  but note it's readable in the DB).
- **Spindel pinned at 0.1.15**: 0.1.23 changes spin-cancellation semantics and
  breaks the structural-rebuild path (recomputed cells come back
  `{:error "Spin cancelled by user"}`; 2 engine-test failures). Bumping spindel
  needs its own investigation + likely an engine fix; do it in a separate PR.
- **Datahike create→connect pause**: first-run creation sleeps ~300 ms before
  `connect` to dodge konserve-jdbc's async c3p0 pool close. Works, but a retry/
  await on a readiness signal would be cleaner than a fixed sleep.
- **Read-only tier — DONE.** Sharing now has view/edit levels (see the
  Datahike step 2b note above); a public sheet can be read-only.
- **Unsharing evicts collaborators — DONE.** `handle-share` reaps every
  non-owner session on the sheet (`evict-foreign!`) when it goes public→private;
  their held streams close and the next /cell, /view or /stream reconnect fails
  the access check. Verified via /debug + two-client curl.
- **Real-provider flows untested live**: GitHub/Google were implemented to
  spec but only exercised with the provider unconfigured (redirect + error
  paths). Needs one manual run with real client ids.
- **Legacy un-namespaced sheets** (`data/default.edn` etc.) are no longer
  served — only `owner__name` ids resolve. Claim by renaming the file to
  `<uid>__<name>.edn` (loads as fmt 1 = public, next save upgrades to fmt 2).
- **Sheet picker — DONE.** The toolbar has a `#sheetpicker` dropdown of the
  signed-in user's sheets (`store/list-names`); selecting one navigates to it.
  A foreign shared sheet shows as a leading `↗ <name>` option. The `#sheetbox`
  text input remains for creating/opening a sheet by a new name.
- **OAuth state + auth sessions are single-node** (in-memory nonces, atom
  registries). Fine for the current single-JVM deploy.

## Cell presentation (style / format / sizing)

- **Style props are reactive; axis sizes are not.** Per-cell `:style`/`:format`
  props compile to spins (literal or `=`-formula, `$val` = own value). Column
  widths / row heights are plain pixel integers (`:cols`/`:rows`, sparse,
  zero-based index keys) — the rendering geometry wants concrete numbers. A
  formula-backed width/height is a rare need we can layer on later via the same
  style machinery if asked.
- **Style UI is a raw text field.** The toolbar style row takes a literal/
  formula string for any prop; no color picker / bold toggle / mask presets yet.
  A friendlier control set is a follow-up (the engine + `/style` endpoint don't
  change).
- **Format = number masks only.** `fmt/apply-mask` supports `0 # . , %` and
  literal prefix/suffix. No date/time patterns yet; non-numbers pass through.
- **Resize re-renders the whole window** (`/size` → `render-window!` +
  `broadcast-window!`). Cheap at current window sizes; if windows grow a lot it
  could push just the affected strips instead.

## ClojureScript client (refactor/use-proper-datastar-attributes)

- **Compiled `resources/public/app.js` is gitignored** (a build artifact). The
  dev nREPL watch builds it `:simple` on `(start)`; `clojure -T:build cljs` and
  `uber` build `:advanced`. Footgun: a fresh checkout running bare
  `clojure -M:web` (e.g. the preview launch config) 404s `app.js` until you run
  `clojure -T:build cljs` once (or start the nREPL). Documented in CLAUDE.md /
  README; revisit if it bites.
- **No CLJS tests yet.** The shared `addr` cljc is covered on the CLJ side; the
  fix for `(int char)`/`(int \A)` (bit-or in CLJS) is currently guarded only by
  the `:advanced` compile + browser verification. A tiny cljs test build (or a
  `clojure -M` cljc round-trip) would lock the CLJS path down.
- **Datastar is loaded from the CDN (1.0.2)**; a kept-in-sync copy is vendored
  and served at `/datastar.js` for offline/air-gapped use. The fallback is a
  **manual one-line swap** (the local path is a reader comment beside the CDN URL
  in `web.render/page`), not an automatic on-error fallback — so if the CDN is
  unreachable the page breaks until the src is swapped. The two must be bumped
  together. A real auto-fallback would need an `onerror` loader (imperative JS in
  HTML), deliberately avoided. The earlier "always vendor" rule is relaxed to
  "CDN + kept-in-sync vendored copy" per the current preference.
- **`app.legacy.js`** is the pre-CLJS hand-written engine, kept for reference.
  Delete once the CLJS port has been in use long enough to trust.

## Git-like branching (PR A: switch + fork · PR B: merge)

- **Merge is 3-way, owner-driven (PR B, `feat/branch-merge`).** Within a branch
  it's still last-write-wins; cross-branch reconciliation is the merge.
- **Merge base assumes one-level lineage.** `db/merge-base` handles
  source-of-target, target-of-source, and sibling (same parent) cases via
  `as-of` base-tx; a deep chain (fork of a fork merged back across several hops)
  has no true LCA walk yet. Fine for the common fork→edit→merge-back flow;
  revisit if branch trees get deep. Unrelated branches (no common ancestor) are
  refused rather than 2-way merged.
- **Merge re-renders the whole window** (like `/size`): correct + simple, but a
  huge merge pushes more than the few changed cells. Could target `broadcast!`
  per affected cell if it matters.
- **Merge preview/apply recompute independently.** No locked snapshot between
  preview and apply, so a collaborator editing the source/target in between can
  shift what Apply does (it always uses live state at apply time). Acceptable;
  a confirm-against-previewed-plan check could tighten it.
- **Deleting a branch strands collaborators on it.** `delete-branch!` + the
  in-memory room discard prevent resurrection, but other sessions currently on
  the deleted branch aren't redirected — their next request is denied and they
  must reload to main. A broadcast-redirect (push `$goto` to the room before
  dropping it) would be friendlier.
- **Branch list isn't pushed live.** A fork/delete by the owner only shows up in
  collaborators' branch pickers on reload (the picker is server-rendered once per
  page). Acceptable since branches are owner-managed; could patch `#branchbar`
  to peers if needed.
- **Per-branch presence only.** Peers on a *different* branch are invisible to
  each other (by design — different working copies). There's no "who's on which
  branch" overview.

## Dependency-graph view + cell labels (feat/dep-graph)

- **Graph is a deliberate v1.** Layered SVG DAG with a fixed grid layout, capped
  at 250 nodes (real wide tables are unreadable as a node graph). No zoom, pan,
  force layout, filtering, or focus-on-a-cell-and-its-neighbours yet — all
  deferred polish (per the owner's "quick one-shot, defer polish" call).
- **Node click selects, doesn't jump.** Clicking a node sets `$sel` (fills the
  address box) but doesn't scroll the grid to it — that needs an app.cljs jump
  bridge; skipped to keep this PR server-only (no cljs rebuild).
- **Graph isn't pushed live.** `/graph` renders on open; it won't update while
  open if a collaborator edits. Reopen to refresh. (Fine — it's a peek.)
- **Labels are display-only + set via the style dropdown.** `:label` is a cell
  metadata prop (rides the per-property datom path), but it's settable only by
  picking `label` in the 🎨 style row — there's no dedicated label field, and it
  isn't referenceable in formulas (formula-by-name is a separate, larger
  feature). Label uniqueness isn't enforced (irrelevant while display-only).
- **Only value-formula deps are graphed.** `sheet/deps` is the value layer; style
  formula deps (`style-deps`) aren't drawn. Minor.

## As-of / history viewing (PR C: feat/branch-history)

- **Cells are reconstructed as-of, but defs + axis sizing use the CURRENT
  branch-meta.** A historical view recomputes formulas against today's
  definitions/sizes, so a value can differ slightly if a def changed since. Fine
  for the common case (defs rarely change); reconstruct branch-meta as-of too if
  fidelity matters.
- **Transient sheet rebuilt per scroll.** `/viewat` builds a whole as-of sheet
  (all cells at that tx) on every scroll, then closes it. Simple + leak-free, but
  wasteful for large sheets / rapid scrolling. A short-lived cache keyed by
  `[id branch tx]` (a read-only "room") would amortize it.
- **Revisions = every change tx, capped at 50.** No grouping/labeling (e.g.
  "fork point", author, or collapsing a burst of edits). The fork-copy shows as
  one big revision. A richer timeline (author, message, grouping) is a follow-up.
- **History views don't collaborate.** No stream/presence on an as-of view (by
  design — it's a frozen snapshot); two people viewing the same revision don't
  see each other.

## Insert row/column + multi-cell style (feat/insert-line)

- **Inserting inside a range surfaces #ERR until the blank is filled.** A
  `#cells A1:A5` straddling the insert correctly grows to `A1:A6`, but SaltRim's
  engine treats a reference to a BLANK cell as an error (existing semantics — see
  the engine-test "reference to a blank cell"), so the formula shows `#ERR` until
  the inserted cell gets a value. Consistent with the model, but a spreadsheet
  user expects blanks to count as 0 in an aggregate. Tolerating blank refs (nil
  → 0/skip) is a broader engine change, tracked separately.
- **`delete-line!` assumes the removed line is unreferenced.** It is built as the
  inverse of `insert-line!` (so an insert undoes in one step) and that holds for a
  freshly-blank inserted line. A user-facing **delete row/column** would need
  `#REF`-style handling for formulas pointing AT the deleted line — so the engine
  op exists but no delete UI is exposed yet (trivial to add once #REF is decided).
- **Insert is a full rebuild + full-window re-render.** `insert-line!` rebuilds
  the whole cell graph from the shifted document and the handler re-renders the
  entire window for every session. Cheap for modest sheets; could be incremental
  if it matters.
- **Cells shifted past `MAX-COLS`/`MAX-ROWS` are dropped** (the "where possible"
  edge). Irrelevant in practice (used ranges sit far from the grid bound).
- **Multi-cell style records one undo entry per cell** (not a single grouped
  step), so undoing a rectangle-style takes N Ctrl+Z. Insert, by contrast, is one
  structural step. Grouping consecutive per-cell edits into one undo is a possible
  refinement.
