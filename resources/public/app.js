// calcloj logical-scroll engine. No native scroll / giant spacer: we keep a
// logical pixel position (SX,SY), translate the rendered window's layers by the
// sub-cell offset for smoothness, and fetch a new window (POST /view) only when
// the top-left cell index changes. Geometry comes from #viewport data-*; the
// rendered window's base + totals come from #meta (patched with #cells, so the
// transform always matches the content). Scrollbars are custom.

let SX = 0, SY = 0;          // logical scroll position (px)
let _lastC0 = 0, _lastR0 = 0;
let _viewTimer = null;

function $(id) { return document.getElementById(id); }
function geo() {
  const v = $('viewport');
  return {CW: +v.dataset.cw, RH: +v.dataset.rh, GUT: +v.dataset.gut,
          HDR: +v.dataset.hdr, OVER: +v.dataset.over, BAR: +v.dataset.bar};
}
function meta() {
  const m = $('meta');
  return {tw: +((m && m.dataset.tw) || 1), th: +((m && m.dataset.th) || 1),
          cb: +((m && m.dataset.cb) || 0), rb: +((m && m.dataset.rb) || 0)};
}
function viewSize() {
  const c = $('cellclip');
  return {w: c ? c.clientWidth : 0, h: c ? c.clientHeight : 0};
}
function setT(id, x, y) { const e = $(id); if (e) e.style.transform = 'translate(' + x + 'px,' + y + 'px)'; }

function render() {
  const g = geo(), m = meta();
  // translate relative to the CONTENT base (cb,rb) -> always aligned to #cells
  const tx = m.cb * g.CW - SX, ty = m.rb * g.RH - SY;
  setT('cells', tx, ty);
  setT('self', tx, ty);      // own selection/editing overlay tracks the cell layer
  setT('peers', tx, ty);     // collaborator overlay tracks the cell layer
  setT('colstrip', tx, 0);
  setT('rowstrip', 0, ty);
  thumb('vbar', 'vthumb', SY, m.th, true);
  thumb('hbar', 'hthumb', SX, m.tw, false);
}

function thumb(barId, thumbId, s, total, vertical) {
  const bar = $(barId), th = $(thumbId); if (!bar || !th) return;
  const vs = vertical ? viewSize().h : viewSize().w;
  const track = vertical ? bar.clientHeight : bar.clientWidth;
  const len = Math.max(24, track * Math.min(1, vs / Math.max(total, 1)));
  const maxS = Math.max(1, total - vs);
  const pos = (Math.min(s, maxS) / maxS) * (track - len);
  if (vertical) { th.style.height = len + 'px'; th.style.top = pos + 'px'; }
  else { th.style.width = len + 'px'; th.style.left = pos + 'px'; }
}

function clampScroll() {
  const m = meta(), vs = viewSize();
  SX = Math.max(0, Math.min(SX, Math.max(0, m.tw - vs.w)));
  SY = Math.max(0, Math.min(SY, Math.max(0, m.th - vs.h)));
}

function requestView(force) {
  const g = geo();
  const c0 = Math.floor(SX / g.CW), r0 = Math.floor(SY / g.RH);
  if (!force && c0 === _lastC0 && r0 === _lastR0) return;
  _lastC0 = c0; _lastR0 = r0;
  clearTimeout(_viewTimer);
  _viewTimer = setTimeout(function () {
    const r0b = $('r0box'), c0b = $('c0box');
    if (r0b) { r0b.value = r0; r0b.dispatchEvent(new Event('input', {bubbles: true})); }
    if (c0b) { c0b.value = c0; c0b.dispatchEvent(new Event('input', {bubbles: true})); }
    const t = $('viewtrigger'); if (t) t.click();
  }, 70);
}

function onWheel(e) {
  e.preventDefault();
  SX += e.deltaX; SY += e.deltaY;
  clampScroll(); render(); requestView(false);
}

function dragThumb(barId, thumbId, vertical) {
  const th = $(thumbId); if (!th) return;
  th.addEventListener('mousedown', function (ev) {
    ev.preventDefault();
    const bar = $(barId);
    const start = vertical ? ev.clientY : ev.clientX;
    const t0 = vertical ? parseFloat(th.style.top || 0) : parseFloat(th.style.left || 0);
    const track = vertical ? bar.clientHeight : bar.clientWidth;
    const len = vertical ? th.clientHeight : th.clientWidth;
    const m = meta(), vs = viewSize();
    const total = vertical ? m.th : m.tw, vsz = vertical ? vs.h : vs.w;
    const maxS = Math.max(1, total - vsz);
    function mm(e2) {
      const cur = vertical ? e2.clientY : e2.clientX;
      let pos = Math.max(0, Math.min(t0 + (cur - start), track - len));
      const s = (pos / Math.max(1, track - len)) * maxS;
      if (vertical) SY = s; else SX = s;
      clampScroll(); render(); requestView(false);
    }
    function mu() { document.removeEventListener('mousemove', mm); document.removeEventListener('mouseup', mu); }
    document.addEventListener('mousemove', mm); document.addEventListener('mouseup', mu);
  });
}

function jump(addr) {
  addr = String(addr).toUpperCase();
  const m = addr.match(/^([A-Z]+)(\d+)$/); if (!m) return;
  const g = geo();
  let ci = 0; for (const ch of m[1]) ci = ci * 26 + (ch.charCodeAt(0) - 64); ci -= 1;
  const ri = parseInt(m[2], 10) - 1;
  SX = ci * g.CW; SY = ri * g.RH;          // no clamp: /view extends totals to cover
  // selection + presence are server-rendered: $sel is already bound from the
  // address box; nudge the server to move the #self overlay to it.
  const pt = $('presencetrigger'); if (pt) pt.click();
  render(); requestView(true);
}
window.jump = jump;

// Selection (#self) and collaborator (#peers) overlays are SERVER-RENDERED.
// Cell focus/blur and the formula bar post presence declaratively via Datastar
// (@post '/presence' in their data-on handlers); the server moves the overlays.
// No client-side class toggling here — the only client concern is keeping the
// overlay layers translated with #cells (handled in render()).

function initScroll() {
  const v = $('viewport'); if (!v || v.__scrollInit) return;
  v.__scrollInit = true;
  v.addEventListener('wheel', onWheel, {passive: false});
  dragThumb('vbar', 'vthumb', true);
  dragThumb('hbar', 'hthumb', false);
  window.addEventListener('resize', render);
  render();  // page already rendered the window at (0,0)
}

// Session lifecycle. Generate a session id, push it into $sid (via the hidden
// bound input so Datastar posts carry it), register on load, and release on
// page unload with navigator.sendBeacon — reliable even as the tab closes.
function initSession() {
  if (window.__sid) return;
  const sid = (crypto.randomUUID ? crypto.randomUUID()
                                 : 'sid-' + Math.random().toString(36).slice(2));
  window.__sid = sid;
  const box = document.getElementById('sidbox');
  if (box) { box.value = sid; box.dispatchEvent(new Event('input', {bubbles: true})); }
  // open the persistent collaboration stream via Datastar (it processes the
  // pushed patches); $sid is now set so the trigger's URL picks it up.
  openStream();
  initScroll();

  window.addEventListener('pagehide', function () {
    try {
      navigator.sendBeacon('/session/end',
        new Blob([JSON.stringify({sid: sid})], {type: 'application/json'}));
    } catch (e) {}
  });
}
// --- collaboration stream open + reconnect ---------------------------------
// Datastar's @get SSE does not reconnect indefinitely on its own. Re-open the
// stream (server registers the session + re-stores its push generator) whenever
// it ends or its retries are exhausted, with capped backoff. No heartbeat.
let _streamTimer = null;
let _streamAttempt = 0;

function openStream() {
  if (window.__unloading) return;
  const trig = document.getElementById('streamtrigger');
  if (trig) trig.click();
}

function scheduleReopen() {
  if (window.__unloading || _streamTimer) return;
  _streamAttempt += 1;
  const delay = Math.min(30000, 1000 * Math.pow(2, _streamAttempt)); // 2s,4s,...,30s
  _streamTimer = setTimeout(function () { _streamTimer = null; openStream(); }, delay);
}

document.addEventListener('datastar-fetch', function (e) {
  const d = e.detail || {};
  // a /view fetch just applied a new window (#meta cb/rb + #cells): realign the
  // transform to the new content base.
  if (d.el && d.el.id === 'viewtrigger' && d.type === 'finished') { render(); return; }
  if (!d.el || d.el.id !== 'streamtrigger') return;
  // 'started' = connected (reset backoff). Reopen on a clean end ('finished')
  // or after Datastar exhausts its own retries ('retries-failed'); ignore plain
  // 'error' so we don't race Datastar's built-in retry into duplicate streams.
  if (d.type === 'started') { _streamAttempt = 0; }
  else if (d.type === 'finished' || d.type === 'retries-failed') { scheduleReopen(); }
});
window.addEventListener('pagehide', function () { window.__unloading = true; });

// fire after Datastar is ready so the $sid signal binding is live
document.addEventListener('datastar-ready', initSession);

