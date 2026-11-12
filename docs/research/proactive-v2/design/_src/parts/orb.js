/* Mike's orb with a face: one drawing engine, written so it ports to a Compose Canvas.
   - The base is the app's own orb: ~220 points on a tilted, turning sphere, glow behind
     (AgentOrb.kt). Larger sizes use more points, like VoiceSphere.kt.
   - Everything else (void, ring, disk, eyes, iris, satellites, status ring) is plain
     drawCircle / drawArc / radial gradient: no shaders, no blur filters, no allocation per frame.
   Usage: <canvas class="orb" data-orb="a|b|c|plain" data-state="idle|thinking|working|needs|done"
                  data-face="manager|scout|librarian|coach|msgs|meet|pc" data-ring data-tasks="3"> */
(function () {
  'use strict';
  var TAU = Math.PI * 2;
  var clamp = function (x, a, b) { return Math.min(b, Math.max(a, x)); };
  var lerp = function (a, b, t) { return a + (b - a) * t; };
  var mix = function (a, b, t) { return [lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t)]; };
  var mul = function (a, k) { return [a[0] * k, a[1] * k, a[2] * k]; };
  var sstep = function (a, b, x) { var t = clamp((x - a) / (b - a), 0, 1); return t * t * (3 - 2 * t); };
  var hash = function (n) { var x = Math.sin(n * 127.1 + 311.7) * 43758.5453; return x - Math.floor(x); };
  var rgba = function (c, a) { return 'rgba(' + (c[0] | 0) + ',' + (c[1] | 0) + ',' + (c[2] | 0) + ',' + clamp(a, 0, 1).toFixed(3) + ')'; };

  var PAL = {
    dark: { teal: [131, 217, 202], amber: [246, 184, 106], gray: [143, 143, 143], hi: [255, 255, 255], pupil: '#000', track: [255, 255, 255], hue: 1, glow: 1 },
    light: { teal: [20, 119, 106], amber: [184, 116, 0], gray: [110, 110, 104], hi: [8, 36, 32], pupil: '#0b0b0b', track: [0, 0, 0], hue: 0.58, glow: 0.7 }
  };
  var theme = function () { return document.documentElement.dataset.theme === 'light' ? 'light' : 'dark'; };

  /* ---- the family: one grammar (void, ring, pupil), different details ---- */
  var FACES = {
    manager:   { hue: null,            disk: 1, lid: 1,    rings: 1, sweep: 0, radar: 0, orbit: 0, rp: 0.2,  vs: 1.1 },
    scout:     { hue: [158, 197, 232], disk: 0, lid: 1,    rings: 1, sweep: 0, radar: 1, orbit: 0, rp: 0.15, vs: 0.82 },
    librarian: { hue: [188, 170, 232], disk: 0, lid: 0.5,  rings: 1, sweep: 0, radar: 0, orbit: 0, rp: 0.16, vs: 0.82 },
    coach:     { hue: [184, 214, 140], disk: 0, lid: 1,    rings: 2, sweep: 0, radar: 0, orbit: 0, rp: 0.14, vs: 0.82 },
    msgs:      { hue: [214, 202, 178], disk: 0, lid: 1,    rings: 1, sweep: 0, radar: 0, orbit: 2, rp: 0.15, vs: 0.82 },
    meet:      { hue: [222, 186, 160], disk: 0, lid: 1,    rings: 1, sweep: 0, radar: 0, orbit: 1, rp: 0.15, vs: 0.82 },
    pc:        { hue: [180, 196, 210], disk: 0, lid: 1,    rings: 1, sweep: 0, radar: 0, orbit: 3, rp: 0.15, vs: 0.82 }
  };

  /* ---- state tables (targets; the live loop eases toward them) ---- */
  var BASE = {
    mix: 0.6, amber: 0, level: 0.1, glow: 0.22, spin: 0.22,
    ring: 0.55, ring2: 0, pupil: 1, lid: 1,
    diskA: 0.45, diskRx: 1.75, diskRy: 0.34, diskSpeed: 0.05, tilt: -12, tiltSwing: 0, tiltHz: 0.25,
    pulse: 0, pulseHz: 0.3, sweep: 0, scan: 0, wander: 1, gx: 0, gy: 0, blink: 1, sats: 0, ripple: 0,
    squint: 0, rx: 1, ry: 1, happy: 0, head: 0,
    ir: 1, pr: 0.2, fibA: 0.45, rot: 0.02, stream: 0, catch: 0, shift: 0
  };
  var ST = {
    a: {
      idle:     { mix: 0.62, level: 0.1, glow: 0.22, ring: 0.6, diskA: 0.62, diskRx: 1.85, diskRy: 0.3, diskSpeed: 0.05, wander: 1, blink: 1, spin: 0.22 },
      thinking: { mix: 1, level: 0.3, glow: 0.32, ring: 0.9, diskA: 0.9, diskRx: 2.25, diskRy: 0.46, diskSpeed: 0.2, tiltSwing: 9, pulse: 0.3, pulseHz: 0.9, sweep: 1, scan: 0.45, wander: 0.3, blink: 0, spin: 0.5, pupil: 0.92 },
      working:  { mix: 1, level: 0.3, glow: 0.28, ring: 0.8, diskA: 0.62, diskSpeed: 0.1, gy: 0.12, wander: 0.35, blink: 1, spin: 0.35, sats: 1 },
      needs:    { mix: 1, amber: 1, level: 0.2, glow: 0.3, ring: 1, ring2: 0.55, diskA: 0.5, diskRx: 1.7, diskRy: 0.36, diskSpeed: 0.035, gy: 0.5, pulse: 0.5, pulseHz: 0.33, wander: 0.25, blink: 0, spin: 0.12 },
      done:     { mix: 1, level: 0.1, glow: 0.2, ring: 0.7, diskA: 0.32, diskRx: 1.35, diskRy: 0.07, diskSpeed: 0.02, lid: 0.78, ripple: 1, wander: 0, blink: 0, spin: 0.15 }
    },
    b: {
      idle:     { mix: 0.62, level: 0.1, glow: 0.2, wander: 1, blink: 1, spin: 0.22 },
      thinking: { mix: 1, level: 0.3, glow: 0.3, squint: 0.82, scan: 0.35, gy: -0.3, wander: 0.2, blink: 0, spin: 0.5, pulse: 0.1, pulseHz: 1.1 },
      working:  { mix: 1, level: 0.3, glow: 0.26, rx: 0.9, ry: 0.62, gy: 0.4, wander: 0.2, blink: 1, spin: 0.35, sats: 1 },
      needs:    { mix: 1, amber: 1, level: 0.2, glow: 0.3, ry: 1.28, rx: 1.06, gy: 0.5, head: -7, wander: 0.2, blink: 0, spin: 0.12, pulse: 0.5, pulseHz: 0.33 },
      done:     { mix: 1, level: 0.1, glow: 0.2, happy: 1, wander: 0, blink: 0, spin: 0.15, ripple: 1 }
    },
    c: {
      idle:     { mix: 0.62, level: 0.1, glow: 0.2, pr: 0.2, fibA: 0.42, rot: 0.02, wander: 1, blink: 1, spin: 0.2 },
      thinking: { mix: 1, level: 0.3, glow: 0.3, pr: 0.2, fibA: 0.7, rot: 0.12, pulse: 1, pulseHz: 0.6, wander: 0.3, blink: 0, spin: 0.45 },
      working:  { mix: 1, level: 0.3, glow: 0.26, pr: 0.15, fibA: 0.5, rot: 0.04, stream: 1, wander: 0.3, blink: 1, spin: 0.35, sats: 1 },
      needs:    { mix: 1, amber: 1, level: 0.2, glow: 0.3, pr: 0.3, fibA: 0.6, rot: 0.01, gy: 0.5, pulse: 0.4, pulseHz: 0.33, wander: 0.2, blink: 0, spin: 0.1 },
      done:     { mix: 1, level: 0.1, glow: 0.2, pr: 0.09, fibA: 0.5, rot: 0.01, catch: 1, wander: 0, blink: 0, spin: 0.15, ripple: 1 }
    }
  };
  // collection switched off: the eye is closed, nothing moves
  var PAUSED = { mix: 0.25, level: 0.04, glow: 0.08, ring: 0.3, diskA: 0.18, diskRy: 0.03, diskSpeed: 0.01, lid: 0.07, blink: 0, wander: 0, spin: 0.06, squint: 1, fibA: 0.25, pr: 0.1, rot: 0 };
  ST.a.paused = PAUSED; ST.b.paused = PAUSED; ST.c.paused = PAUSED;
  var POSE = { idle: 1.9, thinking: 3.1, working: 2.0, needs: 1.6, done: 1.3, paused: 1.0 };
  var BLINK_EVERY = { a: 9, b: 8, c: 8 };

  var ptsCache = {};
  function points(n) {
    if (ptsCache[n]) return ptsCache[n];
    var a = new Float32Array(n * 3);
    for (var i = 0; i < n; i++) {
      var y = 1 - i / (n - 1) * 2, r = Math.sqrt(1 - y * y), ang = i * 2.399963;
      a[i * 3] = Math.cos(ang) * r; a[i * 3 + 1] = y; a[i * 3 + 2] = Math.sin(ang) * r;
    }
    return (ptsCache[n] = a);
  }

  function blinkClose(t, every, seed) {
    var k = Math.floor(t / every), best = 0;
    for (var j = k - 1; j <= k; j++) {
      if (j < 0) continue;
      var start = (j + 0.3 + 0.6 * hash(j * 1.7 + seed)) * every, dt = t - start, c = 0;
      if (dt >= 0 && dt < 0.3) c = dt < 0.09 ? dt / 0.09 : dt < 0.13 ? 1 : 1 - (dt - 0.13) / 0.17;
      best = Math.max(best, c);
    }
    return best;
  }

  /* ------------------------------------------------------------ */
  function setup(cv) {
    var S = parseFloat(cv.dataset.size) || cv.clientWidth || parseFloat(cv.style.width) || 64;
    var dpr = window.devicePixelRatio || 1;
    cv.width = Math.round(S * dpr); cv.height = Math.round(S * dpr);
    cv.style.width = S + 'px'; cv.style.height = S + 'px';
    cv._S = S; cv._dpr = dpr; cv._ctx = cv.getContext('2d');
    cv._dir = (cv.dataset.orb || 'a').toLowerCase();
    cv._face = cv.dataset.face || 'manager';
    cv._ring = cv.hasAttribute('data-ring');
    cv._seed = parseFloat(cv.dataset.seed) || (Math.random() * 10);
    cv._tasks = parseInt(cv.dataset.tasks || '3', 10);
    cv._rtl = getComputedStyle(cv).direction === 'rtl';
    cv._ph = { spin: 0, disk: 0, rot: 0, t: 0 };
    setState(cv, cv.dataset.state || 'idle', true);
  }

  function target(cv, state) {
    var dir = cv._dir === 'plain' ? 'a' : cv._dir;
    var o = {}, k, src = (ST[dir] || ST.a)[state] || ST.a.idle;
    for (k in BASE) o[k] = BASE[k];
    for (k in src) o[k] = src[k];
    return o;
  }
  function setState(cv, state, snap) {
    cv._state = state; cv._tgt = target(cv, state);
    if (snap || !cv._cur) { cv._cur = {}; for (var k in cv._tgt) cv._cur[k] = cv._tgt[k]; }
  }

  /* ------------------------------------------------------------ */
  function draw(cv, t) {
    var ctx = cv._ctx, S = cv._S, P = cv._cur, ph = cv._ph, dpr = cv._dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, S, S);
    var pal = PAL[theme()], face = FACES[cv._face] || FACES.manager, dir = cv._dir;
    var hue = face.hue ? mul(face.hue, pal.hue) : null;
    var base = hue ? mix(hue, pal.amber, P.amber * 0.5) : mix(mix(pal.gray, pal.teal, P.mix), pal.amber, P.amber);
    var ka = 0.55 + 0.45 * P.mix;
    var cx = S / 2, cy = S / 2, R = S * (cv._ring ? 0.35 : 0.33);
    var detail = clamp((S - 30) / 90, 0, 1);
    var bt = clamp((S - 48) / 52, 0, 1);          // dot sizes blend smoothly from the small orb to the large one
    var big = bt > 0.5;
    var u = S / 40;
    var close = P.blink > 0.5 ? blinkClose(t, BLINK_EVERY[dir === 'plain' ? 'a' : dir] * (cv._state === 'working' ? 1.3 : 1), cv._seed) : 0;
    var lid = clamp(P.lid * (face.lid < 1 && dir === 'a' ? lerp(face.lid, 1, P.amber) : 1) * (1 - 0.94 * close), 0.05, 1);
    var dirx = cv._rtl ? -1 : 1;                                  // logical "end" side
    var gx = clamp(P.gx + P.wander * 0.08 * Math.sin(t * 0.37 + cv._seed) + P.scan * Math.sin(t * 0.62 + cv._seed * 1.3), -1, 1) * dirx;
    var gy = clamp(P.gy + P.wander * 0.06 * Math.sin(t * 0.29 + 1.7 + cv._seed), -1, 1);
    var pulse = P.pulse * Math.sin(t * TAU * P.pulseHz);
    var glowA = pal.glow;

    // glow behind (same two-stop radial as AgentOrb)
    var g = ctx.createRadialGradient(cx, cy, 0, cx, cy, S * 0.5);
    g.addColorStop(0, rgba(base, (0.2 + 0.3 * P.level + 0.5 * P.glow * 0.25) * glowA * ka));
    g.addColorStop(1, rgba(base, 0));
    ctx.fillStyle = g; ctx.beginPath(); ctx.arc(cx, cy, S * 0.5, 0, TAU); ctx.fill();

    // satellites behind
    var sats = (dir === 'plain' || cv._face !== 'manager') ? 0 : Math.round(cv._tasks * clamp(P.sats, 0, 1));
    if (sats > 0) drawSats(ctx, cx, cy, R, S, t, base, pal, sats, true, u);

    // carve: where the face is, the dots step back
    var eye = { x: cx, y: cy, ry: 1 };
    var carve = null;
    if (dir === 'a') {
      var ex = cx + gx * 0.2 * R, ey = cy + gy * 0.17 * R;
      eye = { x: ex, y: ey };
      var sm = lerp(1.35, 1, detail), lidc = Math.max(lid, 0.2), vi = 0.33 * face.vs * R * sm, vo = 0.62 * face.vs * R * sm;
      carve = function (sx, sy) { var dx = sx - ex, dy = (sy - ey) / lidc; return sstep(vi, vo, Math.sqrt(dx * dx + dy * dy)); };
    } else if (dir === 'b') {
      var by = cy - 0.06 * R + gy * 0.1 * R, bx = gx * 0.12 * R, sp = 0.34 * R, hr = P.head * Math.PI / 180;
      var e1 = rot(cx + bx - sp, by, cx, cy, hr), e2 = rot(cx + bx + sp, by, cx, cy, hr);
      eye = { e1: e1, e2: e2 };
      carve = function (sx, sy) {
        var d1 = Math.hypot(sx - e1[0], (sy - e1[1]) * 0.8), d2 = Math.hypot(sx - e2[0], (sy - e2[1]) * 0.8);
        return 0.25 + 0.75 * sstep(0.16 * R, 0.4 * R, Math.min(d1, d2));
      };
    } else if (dir === 'c') {
      var ix = cx + gx * 0.16 * R, iy = cy + gy * 0.14 * R, ri = 0.6 * R * P.ir;
      eye = { x: ix, y: iy, ri: ri };
      carve = function (sx, sy) { var d = Math.hypot(sx - ix, sy - iy); return 0.28 + 0.72 * sstep(ri * 0.8, ri * 1.2, d); };
    }

    // the orb itself
    var N = Math.round(lerp(170, 560, detail));
    var pts = points(N);
    var cY = Math.cos(ph.spin), sY = Math.sin(ph.spin), cX = Math.cos(0.38), sX = Math.sin(0.38);
    for (var i = 0; i < N; i++) {
      var x = pts[i * 3], y = pts[i * 3 + 1], z = pts[i * 3 + 2];
      var wob = Math.sin(3.1 * x + t * 1.9) * Math.sin(2.7 * y - t * 1.3) * Math.sin(3.3 * z + t * 2.2);
      var push = 1 + 0.05 * wob * (0.4 + P.level);
      var px = x * push, py = y * push, pz = z * push;
      var tx = px * cY + pz * sY, tz = -px * sY + pz * cY;
      var ty = py * cX - tz * sX, tzz = py * sX + tz * cX;
      var persp = 2.8 / (2.8 - tzz), depth = clamp((tzz + 1) / 2, 0, 1);
      var sx = cx + tx * R * persp, sy = cy + ty * R * persp;
      var a = (0.1 + 0.9 * depth * depth) * ka * (carve ? carve(sx, sy) : 1);
      if (a < 0.02) continue;
      var r = lerp((0.35 + 0.75 * depth) * u * (1 + 0.3 * P.level), Math.max(0.42, (0.55 + 1.35 * depth) * 0.9 * S / 360 * 1.25), bt);
      ctx.fillStyle = rgba(mix(base, pal.hi, clamp(P.level * 0.6 * depth, 0, 1)), a);
      ctx.beginPath(); ctx.arc(sx, sy, r, 0, TAU); ctx.fill();
    }

    if (dir === 'a') drawA(ctx, eye, R, S, P, ph, t, base, pal, face, lid, pulse, gx, gy, detail, u, big);
    else if (dir === 'b') drawB(ctx, eye, cx, cy, R, S, P, t, base, pal, lid, pulse, u, big);
    else if (dir === 'c') drawC(ctx, eye, R, S, P, ph, t, base, pal, lid, pulse, u, big, detail);

    if (sats > 0) drawSats(ctx, cx, cy, R, S, t, base, pal, sats, false, u);

    if (P.ripple > 0.01) {
      var k = ((t * 0.42) % 1), rr = lerp(R * 0.3, R * 1.45, 1 - Math.pow(1 - k, 3));
      ctx.lineWidth = Math.max(1, S / 160 * 1.4);
      ctx.strokeStyle = rgba(base, 0.55 * Math.pow(1 - k, 1.6) * P.ripple);
      ctx.beginPath(); ctx.arc(eye.x || cx, eye.y || cy, rr, 0, TAU); ctx.stroke();
    }

    if (cv._ring) statusRing(ctx, cx, cy, S, cv._state, t, hue || base, pal, cv._rtl, !!hue);
  }

  function rot(x, y, cx, cy, a) { var c = Math.cos(a), s = Math.sin(a); return [cx + (x - cx) * c - (y - cy) * s, cy + (x - cx) * s + (y - cy) * c]; }

  /* ---- Direction A: the singularity eye ---- */
  function drawA(ctx, e, R, S, P, ph, t, base, pal, face, lid, pulse, gx, gy, detail, u, big) {
    var rp = face.rp * R * P.pupil * (1 + 0.05 * pulse) * lerp(1.5, 1, detail);   // small sizes: a bigger eye
    var ringR = rp * 1.12, w = Math.max(1, S / 120 * 1.3);
    var hi = pal.hi, bright = mix(base, hi, theme() === 'dark' ? 0.55 : 0.15);
    ctx.save();
    ctx.translate(e.x, e.y); ctx.scale(1, lid);
    // light bent around the hole
    var hg = ctx.createRadialGradient(0, 0, rp * 0.9, 0, 0, rp * 3.4);
    hg.addColorStop(0, rgba(base, (0.34 * P.ring + 0.1 * Math.abs(pulse)) * pal.glow));
    hg.addColorStop(1, rgba(base, 0));
    ctx.fillStyle = hg; ctx.beginPath(); ctx.arc(0, 0, rp * 3.4, 0, TAU); ctx.fill();

    var tilt = (P.tilt + P.tiltSwing * Math.sin(t * TAU * P.tiltHz)) * Math.PI / 180;
    var M = face.disk ? Math.round(lerp(20, 190, detail)) : 0;
    var ox = gx * 0.3 * rp, oy = gy * 0.3 * rp;       // the ring drifts toward where the cards are
    var disk = [];
    for (var j = 0; j < M; j++) {
      var rho = 1 + 0.7 * Math.pow(hash(j * 3.1), 1.5);
      var th = hash(j * 1.3) * TAU + ph.disk * TAU * Math.pow(rho, -1.5);
      var s = Math.sin(th), c = Math.cos(th), far = s < 0;
      var lx = rho * P.diskRx * rp * c, ly;
      if (far) ly = -(rp * 1.2 + (rho - 1) * rp * 0.8) * Math.pow(-s, 0.75) * (P.diskRy > 0.2 ? 1 : 0.6 + P.diskRy * 2);
      else ly = rho * P.diskRy * rp * s;
      var tr = rot(lx, ly, 0, 0, tilt);
      disk.push([tr[0] + ox, tr[1] + oy, far, rho, c]);
    }
    var dr = Math.max(0.55, lerp(S / 40 * 0.9, S / 360 * 2.3, clamp((S - 48) / 52, 0, 1)));
    var fade = function (d) { var f = 1 - 0.5 * (d[3] - 1) / 0.7; return P.diskA * f; };
    for (j = 0; j < disk.length; j++) { var d = disk[j]; if (!d[2]) continue; ctx.fillStyle = rgba(mix(base, hi, 0.2), fade(d) * 0.7); ctx.beginPath(); ctx.arc(d[0], d[1], dr * 0.85, 0, TAU); ctx.fill(); }

    // the pupil: pure dark, so the ring defines it (on #000 it is the background itself)
    var px = gx * 0.16 * rp, py = gy * 0.16 * rp;
    ctx.fillStyle = pal.pupil; ctx.beginPath(); ctx.arc(px, py, rp, 0, TAU); ctx.fill();

    // photon ring: three strokes, no blur filter
    var ra = clamp(P.ring + 0.12 * pulse, 0, 1);
    ctx.lineCap = 'round';
    var strokes = [[w * 5.2, 0.07], [w * 2.4, 0.2], [w, 0.95]];
    for (var q = 0; q < strokes.length; q++) {
      ctx.lineWidth = strokes[q][0]; ctx.strokeStyle = rgba(q === 2 ? bright : base, ra * strokes[q][1]);
      ctx.beginPath(); ctx.arc(px * 0.5, py * 0.5, ringR, 0, TAU); ctx.stroke();
    }
    if (face.rings === 2) {
      ctx.lineWidth = w * 0.8; ctx.strokeStyle = rgba(base, ra * 0.55);
      ctx.beginPath(); ctx.arc(px * 0.3, py * 0.3, ringR * 1.62, 0, TAU); ctx.stroke();
    }
    if (P.ring2 > 0.02) {                                   // needs you: a second, slower ring breathes outward
      var br = 0.5 + 0.5 * Math.sin(t * TAU * P.pulseHz);
      ctx.lineWidth = w * 0.9; ctx.strokeStyle = rgba(base, P.ring2 * (0.35 + 0.5 * br));
      ctx.beginPath(); ctx.arc(px * 0.5, py * 0.5, ringR * (1.55 + 0.18 * br), 0, TAU); ctx.stroke();
    }
    if (face.radar) {                                       // scout: a radar arc turning around the ring
      var ph0 = t * TAU * (0.16 + 0.55 * clamp(P.level * 2.2 - 0.2, 0, 1)) - 1.7;
      var rr = ringR * 2.05;
      ctx.lineWidth = w * 0.9;
      for (var seg = 0; seg < 16; seg++) {
        ctx.strokeStyle = rgba(bright, (0.12 + 0.6 * (seg / 16)) * (0.35 + 0.65 * P.ring));
        ctx.beginPath(); ctx.arc(0, 0, rr, ph0 - (16 - seg) * 0.06, ph0 - (15 - seg) * 0.06); ctx.stroke();
      }
    }
    for (j = 0; j < disk.length; j++) {                     // near side of the disk crosses in front
      d = disk[j]; if (d[2]) continue;
      ctx.fillStyle = rgba(mix(base, hi, 0.35), fade(d) * 1.05);
      ctx.beginPath(); ctx.arc(d[0], d[1], dr, 0, TAU); ctx.fill();
    }
    if (P.sweep > 0.02 && face.disk) {                      // a comet on the ring while it thinks
      var phi = t * 1.9 + 1;
      for (var k = 0; k < 12; k++) {
        var aa = phi - k * 0.11;
        ctx.fillStyle = rgba(mix(base, hi, 0.7), Math.pow(1 - k / 12, 2) * 0.95 * P.sweep);
        ctx.beginPath(); ctx.arc(Math.cos(aa) * ringR * 1.02, Math.sin(aa) * ringR * 1.02, dr * (1.25 - k * 0.07), 0, TAU); ctx.fill();
      }
    }
    var orb = face.orbit;                                   // task crew: dots show how many areas it covers
    for (k = 0; k < orb; k++) {
      var oa = t * 0.5 * (1 + P.level * 2) + k * TAU / orb;
      ctx.fillStyle = rgba(bright, 0.85);
      ctx.beginPath(); ctx.arc(Math.cos(oa) * ringR * 2.15, Math.sin(oa) * ringR * 2.15, dr * 1.9, 0, TAU); ctx.fill();
    }
    ctx.restore();
  }

  /* ---- Direction B: two soft dots ---- */
  function drawB(ctx, e, cx, cy, R, S, P, t, base, pal, lid, pulse, u, big) {
    var bright = mix(base, pal.hi, theme() === 'dark' ? 0.72 : 0.1);
    var squint = clamp(P.squint + 0.1 * pulse, 0, 1);
    var rx = 0.075 * R * P.rx * lerp(1, 1.35, squint);
    var ry = 0.125 * R * P.ry * lerp(1, 0.2, squint) * (0.06 + 0.94 * lid / 1);
    var pair = [e.e1, e.e2];
    for (var i = 0; i < 2; i++) {
      var p = pair[i];
      ctx.save(); ctx.translate(p[0], p[1]);
      var hap = P.happy;
      var gl = ctx.createRadialGradient(0, 0, 0, 0, 0, Math.max(rx, ry) * 3.2);
      gl.addColorStop(0, rgba(base, 0.42 * pal.glow)); gl.addColorStop(1, rgba(base, 0));
      ctx.fillStyle = gl; ctx.beginPath(); ctx.arc(0, 0, Math.max(rx, ry) * 3.2, 0, TAU); ctx.fill();
      if (hap < 0.98) {
        ctx.globalAlpha = 1 - hap;
        ctx.fillStyle = rgba(bright, 0.95);
        ctx.beginPath(); ctx.ellipse(0, 0, rx, ry, 0, 0, TAU); ctx.fill();
        ctx.globalAlpha = 1;
      }
      if (hap > 0.02) {                                     // done: eyes rest as soft closed arcs
        ctx.globalAlpha = hap; ctx.lineCap = 'round';
        ctx.lineWidth = Math.max(1.2, R * 0.06); ctx.strokeStyle = rgba(bright, 0.95);
        ctx.beginPath(); ctx.arc(0, R * 0.05, R * 0.1, Math.PI * 1.18, Math.PI * 1.82); ctx.stroke();
        ctx.globalAlpha = 1;
      }
      ctx.restore();
    }
  }

  /* ---- Direction C: an iris made of the orb's own light ---- */
  function drawC(ctx, e, R, S, P, ph, t, base, pal, lid, pulse, u, big, detail) {
    var bright = mix(base, pal.hi, theme() === 'dark' ? 0.6 : 0.12);
    var ri = e.ri * lerp(0.35, 1, (lid - 0.05) / 0.95);         // blink: the light closes inward
    var rp = (P.pr + 0.022 * pulse) * R * lerp(0.4, 1, (lid - 0.05) / 0.95);
    var F = Math.round(lerp(22, 52, detail));
    ctx.save(); ctx.translate(e.x, e.y);
    var cg = ctx.createRadialGradient(0, 0, rp * 0.95, 0, 0, rp * 2.6);
    cg.addColorStop(0, rgba(base, 0.5 * pal.glow)); cg.addColorStop(1, rgba(base, 0));
    ctx.fillStyle = cg; ctx.beginPath(); ctx.arc(0, 0, rp * 2.6, 0, TAU); ctx.fill();
    var rotation = ph.rot * TAU;
    ctx.lineCap = 'round';
    var lw = Math.max(0.6, S / 160);
    for (var k = 0; k < F; k++) {
      var h1 = hash(k * 2.3), h2 = hash(k * 5.7 + 1);
      var a = k / F * TAU + rotation + (h1 - 0.5) * 0.08;
      var r0 = rp * 1.12, r1 = ri * (0.6 + 0.4 * h1);
      var wave = 0.5 + 0.5 * Math.sin(a * 2 - t * 1.2 * (P.pulse > 0.5 ? 1 : 0.4) + k * 0.2);
      var al = P.fibA * (0.35 + 0.65 * h2) * (0.7 + 0.5 * wave);
      var ca = Math.cos(a), sa = Math.sin(a);
      ctx.lineWidth = lw; ctx.strokeStyle = rgba(base, al * 0.8);
      ctx.beginPath(); ctx.moveTo(ca * r0, sa * r0); ctx.lineTo(ca * r1, sa * r1); ctx.stroke();
      var tipR = P.stream > 0.5 ? r0 + ((t * 0.55 + h1) % 1) * (r1 - r0) : r1;
      var tipA = P.stream > 0.5 ? Math.sin(Math.PI * ((t * 0.55 + h1) % 1)) : 1;
      ctx.fillStyle = rgba(bright, al * 1.15 * tipA);
      ctx.beginPath(); ctx.arc(ca * tipR, sa * tipR, Math.max(0.5, lerp(S / 40 * 0.7, S / 360 * 1.7, clamp((S - 48) / 52, 0, 1))), 0, TAU); ctx.fill();
    }
    var rings = [0.78, 0.9];
    for (var q = 0; q < rings.length; q++) {
      var cnt = Math.round(lerp(12, 26, detail)) + q * 6, rr = ri * rings[q];
      for (k = 0; k < cnt; k++) {
        var aa = k / cnt * TAU + t * (q ? -0.09 : 0.06) * (0.4 + P.level * 3);
        ctx.fillStyle = rgba(base, 0.3 * P.fibA / 0.45 * (0.6 + 0.4 * Math.sin(k + t)));
        ctx.beginPath(); ctx.arc(Math.cos(aa) * rr, Math.sin(aa) * rr, Math.max(0.45, lerp(S / 40 * 0.5, S / 360 * 1.2, clamp((S - 48) / 52, 0, 1))), 0, TAU); ctx.fill();
      }
    }
    ctx.lineWidth = lw; ctx.strokeStyle = rgba(bright, 0.3);
    ctx.beginPath(); ctx.arc(0, 0, ri * 1.02, 0, TAU); ctx.stroke();
    ctx.fillStyle = pal.pupil; ctx.beginPath(); ctx.arc(0, 0, rp, 0, TAU); ctx.fill();
    if (P.catch > 0.02) {
      ctx.fillStyle = rgba(pal.hi, 0.85 * P.catch);
      ctx.beginPath(); ctx.arc(-rp * 0.32, -rp * 0.36, Math.max(1, rp * 0.2), 0, TAU); ctx.fill();
    }
    ctx.restore();
  }

  /* ---- one satellite per running task ---- */
  function drawSats(ctx, cx, cy, R, S, t, base, pal, n, behind, u) {
    var Ro = R * 1.24, tilt = -18 * Math.PI / 180;
    for (var i = 0; i < n; i++) {
      var th = t * 0.62 + i * TAU / n, z = Math.sin(th);
      if ((z < 0) !== behind) continue;
      var p = rot(cx + Math.cos(th) * Ro, cy + Math.sin(th) * Ro * 0.3, cx, cy, tilt);
      var r = Math.max(1.1, S / 110) * (0.8 + 0.25 * z);
      var g = ctx.createRadialGradient(p[0], p[1], 0, p[0], p[1], r * 3.4);
      g.addColorStop(0, rgba(base, 0.55)); g.addColorStop(1, rgba(base, 0));
      ctx.fillStyle = g; ctx.beginPath(); ctx.arc(p[0], p[1], r * 3.4, 0, TAU); ctx.fill();
      ctx.fillStyle = rgba(mix(base, pal.hi, 0.5), behind ? 0.6 : 0.95);
      ctx.beginPath(); ctx.arc(p[0], p[1], r, 0, TAU); ctx.fill();
    }
  }

  /* ---- the status ring around an avatar: the same grammar for every crew member ---- */
  function statusRing(ctx, cx, cy, S, state, t, col, pal, rtl, member) {
    var r = S / 2 - 1.6, w = S >= 80 ? 2.2 : 1.8;
    ctx.lineCap = 'round';
    ctx.lineWidth = w; ctx.strokeStyle = rgba(pal.track, 0.1);
    ctx.beginPath(); ctx.arc(cx, cy, r, 0, TAU); ctx.stroke();
    var teal = pal.teal, amber = pal.amber, c;
    if (state === 'thinking') {
      var a0 = t * TAU * 0.45;
      ctx.lineWidth = w; ctx.strokeStyle = rgba(member ? col : teal, 0.95);
      ctx.beginPath(); ctx.arc(cx, cy, r, a0, a0 + TAU * 0.27); ctx.stroke();
    } else if (state === 'working') {
      ctx.lineWidth = w; ctx.strokeStyle = rgba(teal, 0.5);
      ctx.beginPath(); ctx.arc(cx, cy, r, 0, TAU); ctx.stroke();
      var a1 = t * TAU * 0.38;
      ctx.strokeStyle = rgba(teal, 1); ctx.lineWidth = w * 1.15;
      ctx.beginPath(); ctx.arc(cx, cy, r, a1, a1 + TAU * 0.2); ctx.stroke();
    } else if (state === 'needs') {
      ctx.lineWidth = w * (1.05 + 0.2 * Math.sin(t * 2.1)); ctx.strokeStyle = rgba(amber, 0.98);
      ctx.beginPath(); ctx.arc(cx, cy, r, 0, TAU); ctx.stroke();
    } else if (state === 'done') {
      ctx.lineWidth = w; ctx.strokeStyle = rgba(teal, 0.9);
      ctx.beginPath(); ctx.arc(cx, cy, r, 0, TAU); ctx.stroke();
    }
    if (state === 'needs' || state === 'done') {
      var bx = cx + (rtl ? -1 : 1) * r * 0.72, by = cy + r * 0.72, br = Math.min(10, Math.max(3.4, S * 0.1));
      ctx.fillStyle = theme() === 'dark' ? '#000' : '#FAF9F6';
      ctx.beginPath(); ctx.arc(bx, by, br + 1.8, 0, TAU); ctx.fill();
      ctx.fillStyle = rgba(state === 'needs' ? amber : teal, 1);
      ctx.beginPath(); ctx.arc(bx, by, br, 0, TAU); ctx.fill();
      if (state === 'done') {
        ctx.lineWidth = Math.max(1.1, br * 0.28); ctx.strokeStyle = theme() === 'dark' ? '#00201b' : '#fff';
        ctx.beginPath(); ctx.moveTo(bx - br * 0.45, by + br * 0.02); ctx.lineTo(bx - br * 0.1, by + br * 0.38); ctx.lineTo(bx + br * 0.5, by - br * 0.34); ctx.stroke();
      } else {
        ctx.lineWidth = Math.max(1.1, br * 0.3); ctx.strokeStyle = theme() === 'dark' ? '#241400' : '#fff';
        ctx.beginPath(); ctx.moveTo(bx, by - br * 0.5); ctx.lineTo(bx, by + br * 0.08); ctx.stroke();
        ctx.fillStyle = ctx.strokeStyle; ctx.beginPath(); ctx.arc(bx, by + br * 0.46, ctx.lineWidth * 0.55, 0, TAU); ctx.fill();
      }
    }
  }

  /* ------------------------------------------------------------ */
  var orbs = [], running = false, last = 0;
  function stepParams(cv, dt) {
    var k, rate = 1 - Math.exp(-dt * 4.5);
    for (k in cv._tgt) cv._cur[k] += (cv._tgt[k] - cv._cur[k]) * rate;
    cv._ph.spin += dt * cv._cur.spin; cv._ph.disk += dt * cv._cur.diskSpeed; cv._ph.rot += dt * cv._cur.rot;
  }
  function frame(now) {
    var dt = last ? Math.min(0.05, (now - last) / 1000) : 0.016; last = now;
    var t = now / 1000;
    for (var i = 0; i < orbs.length; i++) {
      var cv = orbs[i];
      var r = cv.getBoundingClientRect();
      if (r.bottom < -50 || r.top > innerHeight + 50) continue;
      stepParams(cv, dt); draw(cv, t + cv._seed);
    }
    requestAnimationFrame(frame);
  }
  function drawStill(cv) {
    var t = window.__T != null ? window.__T : (POSE[cv._state] || 2);
    t += cv._seed * 0;                                       // stills are deterministic
    for (var k in cv._tgt) cv._cur[k] = cv._tgt[k];
    cv._ph.spin = t * cv._cur.spin; cv._ph.disk = t * cv._cur.diskSpeed; cv._ph.rot = t * cv._cur.rot;
    draw(cv, t);
  }
  function init(rootEl) {
    var list = (rootEl || document).querySelectorAll('canvas[data-orb]');
    for (var i = 0; i < list.length; i++) { if (!list[i]._ctx) { setup(list[i]); orbs.push(list[i]); } }
    if (window.__STILL) { orbs.forEach(function (cv) { cv._seed = parseFloat(cv.dataset.seed) || 1.7; drawStill(cv); }); }
    else if (!running) { running = true; requestAnimationFrame(frame); }
  }
  window.MikeOrb = { init: init, setState: function (cv, s) { setState(cv, s, window.__STILL); if (window.__STILL) drawStill(cv); } };
  document.addEventListener('DOMContentLoaded', function () { init(); });
})();
