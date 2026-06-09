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
  setT('editlayer', tx, ty); // floating editor tracks the cell layer
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

// --- address <-> index helpers ---------------------------------------------
function colToIdx(s) { let c = 0; for (const ch of s) c = c * 26 + (ch.charCodeAt(0) - 64); return c - 1; }
function idxToCol(i) { let n = i + 1, o = ''; while (n > 0) { const r = (n - 1) % 26; o = String.fromCharCode(65 + r) + o; n = (n - 1 - r) / 26; } return o; }
function parseAddr(a) { const m = String(a).toUpperCase().match(/^([A-Z]+)(\d+)$/); return m ? {ci: colToIdx(m[1]), ri: parseInt(m[2], 10) - 1} : null; }
function makeAddr(ci, ri) { return idxToCol(ci) + (ri + 1); }
function curSel() { const b = $('addrbox'); return b && b.value ? b.value.toUpperCase() : null; }

// --- selection (server-rendered #self) -------------------------------------
// $sel lives in the address box (data-bind:sel). We set it and click a hidden
// Datastar trigger so the server moves the #self overlay; the client only keeps
// the selected cell scrolled into view.
function setSelValue(addr) {   // set $sel only (no presence post)
  const b = $('addrbox'); if (b) { b.value = addr; b.dispatchEvent(new Event('input', {bubbles: true})); }
}
function setSel(addr) {        // set $sel + post selection presence (cursor, edit=false)
  setSelValue(addr);
  const t = $('selecttrigger'); if (t) t.click();
}

function ensureVisible(addr) {
  const p = parseAddr(addr); if (!p) return;
  const g = geo(), vs = viewSize();
  const x = p.ci * g.CW, y = p.ri * g.RH;
  if (x < SX) SX = x; else if (x + g.CW > SX + vs.w) SX = x + g.CW - vs.w;
  if (y < SY) SY = y; else if (y + g.RH > SY + vs.h) SY = y + g.RH - vs.h;
  SX = Math.max(0, SX); SY = Math.max(0, SY);
  render(); requestView(false);
}

function jump(addr) {
  const p = parseAddr(addr); if (!p) return;
  const g = geo();
  SX = p.ci * g.CW; SY = p.ri * g.RH;     // park target at top-left; /view extends totals
  setSel(makeAddr(p.ci, p.ri));
  render(); requestView(true);
}
window.jump = jump;

// --- the single floating editor --------------------------------------------
// Cells are display divs; editing happens in one #editor input positioned over
// the active cell. Opened on Enter / double-click; committed on Enter / blur /
// double-click; cancelled on Esc. Datastar does the posts (hidden triggers);
// /app.js only positions/shows/hides and guards re-entrant blur.
let _editing = false;

function startEdit(addr) {
  const p = parseAddr(addr); if (!p) return;
  addr = makeAddr(p.ci, p.ri);
  setSelValue(addr); ensureVisible(addr);   // set $sel; the edittrigger below posts presence (edit=true)
  const g = geo(), m = meta(), ed = $('editor'), cell = $('c_' + addr);
  ed.style.left = ((p.ci - m.cb) * g.CW) + 'px';
  ed.style.top  = ((p.ri - m.rb) * g.RH) + 'px';
  ed.value = cell ? (cell.dataset.raw || '') : '';
  ed.dispatchEvent(new Event('input', {bubbles: true}));   // -> $v
  ed.style.display = 'block';
  _editing = true;
  ed.focus(); ed.select();
  const t = $('edittrigger'); if (t) t.click();            // presence edit=true (lock)
}
function hideEditor() { const ed = $('editor'); if (ed) { _editing = false; ed.style.display = 'none'; } }
function commitEdit() {
  if (!_editing) return;                                   // guard re-entrant blur
  _editing = false;
  const ct = $('celltrigger'); if (ct) ct.click();         // @post /cell ($cell=$sel, $v)
  hideEditor();
  const st = $('selecttrigger'); if (st) st.click();       // presence edit=false
}
function cancelEdit() {
  if (!_editing) return;
  hideEditor();
  const st = $('selecttrigger'); if (st) st.click();
}
window.startEdit = startEdit; window.commitEdit = commitEdit; window.cancelEdit = cancelEdit;

// --- keyboard navigation ----------------------------------------------------
// Arrows / Tab move the selection; Enter opens the editor. Ignored while editing
// (the editor owns its keys) or when a toolbar input is focused.
function onKey(e) {
  if (_editing) return;
  const ae = document.activeElement;
  if (ae && (ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA') && ae.id !== 'editor') return;
  const sel = curSel();
  if (!sel) {
    if (['ArrowRight', 'ArrowLeft', 'ArrowUp', 'ArrowDown', 'Tab', 'Enter'].includes(e.key)) {
      e.preventDefault(); setSel('A1'); ensureVisible('A1');
    }
    return;
  }
  const p = parseAddr(sel); let ci = p.ci, ri = p.ri;
  switch (e.key) {
    case 'ArrowRight': ci++; break;
    case 'ArrowLeft':  ci = Math.max(0, ci - 1); break;
    case 'ArrowDown':  ri++; break;
    case 'ArrowUp':    ri = Math.max(0, ri - 1); break;
    case 'Tab':        ci = e.shiftKey ? Math.max(0, ci - 1) : ci + 1; break;
    case 'Enter':      e.preventDefault(); startEdit(sel); return;
    default: return;
  }
  e.preventDefault();
  const na = makeAddr(ci, ri);
  setSel(na); ensureVisible(na);
}

function initEditor() {
  const ed = $('editor'); if (!ed || ed.__init) return;
  ed.__init = true;
  // wired in JS (not Datastar inline) so it fires reliably; the actual /cell post
  // is still Datastar (commitEdit clicks the #celltrigger @post button).
  ed.addEventListener('keydown', function (e) {
    // stop the event reaching the document-level nav handler (which would
    // otherwise re-open the editor on the same Enter once we've committed).
    if (e.key === 'Enter') { e.preventDefault(); e.stopPropagation(); commitEdit(); }
    else if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); cancelEdit(); }
  });
  ed.addEventListener('blur', function () { commitEdit(); });
  ed.addEventListener('dblclick', function () { commitEdit(); });
}

function initScroll() {
  const v = $('viewport'); if (!v || v.__scrollInit) return;
  v.__scrollInit = true;
  v.addEventListener('wheel', onWheel, {passive: false});
  dragThumb('vbar', 'vthumb', true);
  dragThumb('hbar', 'hthumb', false);
  window.addEventListener('resize', render);
  document.addEventListener('keydown', onKey);
  initEditor();
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

