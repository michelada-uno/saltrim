# Tech debt

## Scroll model — DONE (logical scroll)

Replaced the giant-spacer native scroll with a logical-scroll engine (no sized
div): /app.js keeps a logical pixel position (SX,SY), translates the window
layers by the offset for smoothness, draws custom scrollbars, and fetches a new
window (POST /view) only when the top-left cell index changes. Cells are
positioned window-relative; the window base (cb/rb) ships in #meta alongside
#cells so the transform always matches the displayed content (no jump while a
fetch is in flight). Row cap and scrollbar-precision issues are gone.

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

REMAINING:
- **Tokens are stored in plaintext** (`data/tokens.edn`). Hash them (the cookie
  carries the secret; the server only needs to verify) before any real deploy.
- **No read-only tier**: a public sheet is editable by any signed-in user.
  Sharing levels (view/edit) need a richer ACL than the single :public flag —
  the fmt-2 envelope leaves room.
- **Unsharing doesn't evict** collaborators already on the sheet: their
  sessions stay until they leave (writes keep working). Check access per
  /cell against the live record, or reap foreign sessions on unshare.
- **Real-provider flows untested live**: GitHub/Google were implemented to
  spec but only exercised with the provider unconfigured (redirect + error
  paths). Needs one manual run with real client ids.
- **Legacy un-namespaced sheets** (`data/default.edn` etc.) are no longer
  served — only `owner__name` ids resolve. Claim by renaming the file to
  `<uid>__<name>.edn` (loads as fmt 1 = public, next save upgrades to fmt 2).
- **No sheet picker**: `store/list-names` exists but the UI is still the
  name box (Enter opens). A dropdown of your sheets would help.
- **OAuth state + auth sessions are single-node** (in-memory nonces, atom
  registries). Fine for the current single-JVM deploy.
