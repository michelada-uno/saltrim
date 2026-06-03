# Tech debt

## Scroll model — DONE (logical scroll)

Replaced the giant-spacer native scroll with a logical-scroll engine (no sized
div): /app.js keeps a logical pixel position (SX,SY), translates the window
layers by the offset for smoothness, draws custom scrollbars, and fetches a new
window (POST /view) only when the top-left cell index changes. Cells are
positioned window-relative; the window base (cb/rb) ships in #meta alongside
#cells so the transform always matches the displayed content (no jump while a
fetch is in flight). Row cap and scrollbar-precision issues are gone.

Follow-ups: keyboard nav (arrows/pgup) not wired; WIN-COLS/ROWS are fixed
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
- **Conflict policy**: last-write-wins, no locking/merge. Fine for now; revisit
  if simultaneous edits to the same cell matter.

The `/debug` endpoint (session/sheet counts) is dev-only — gate or remove before
any real deployment.
