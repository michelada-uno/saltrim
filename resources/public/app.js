// calcloj client helpers. Geometry is read from #scroll data-* attributes
// (single source of truth lives in the Clojure constants).

function jump(addr) {
  addr = String(addr).toUpperCase();
  const m = addr.match(/^([A-Z]+)(\d+)$/);
  if (!m) return;

  const sc = document.getElementById('scroll');
  const sp = document.getElementById('space');
  if (!sc || !sp) return;

  const CW = +sc.dataset.cw, RH = +sc.dataset.rh, GUT = +sc.dataset.gut;

  let ci = 0;
  for (const ch of m[1]) ci = ci * 26 + (ch.charCodeAt(0) - 64);
  ci -= 1;                       // 0-based column index
  const ri = parseInt(m[2], 10) - 1;

  // pre-grow the spacer so the scroll can reach; /view reconciles size server-side
  const needW = GUT + (ci + 5) * CW;
  const needH = (ri + 5) * RH;
  if (parseInt(sp.style.width)  < needW) sp.style.width  = needW + 'px';
  if (parseInt(sp.style.height) < needH) sp.style.height = needH + 'px';

  sc.scrollLeft = Math.max(0, GUT + ci * CW - 200);
  sc.scrollTop  = Math.max(0, ri * RH - 100);
  sc.dispatchEvent(new Event('scroll'));   // -> /view loads the window
}

window.jump = jump;

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
  const trig = document.getElementById('streamtrigger');
  if (trig) trig.click();

  window.addEventListener('pagehide', function () {
    try {
      navigator.sendBeacon('/session/end',
        new Blob([JSON.stringify({sid: sid})], {type: 'application/json'}));
    } catch (e) {}
  });
}
// fire after Datastar is ready so the $sid signal binding is live
document.addEventListener('datastar-ready', initSession);

