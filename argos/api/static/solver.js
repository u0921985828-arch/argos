/*
 * ARGOS · optimizador de sinopsis en el navegador
 * ---------------------------------------------------------------------------
 * Puerto del solver de Python. Era la última pieza que obligaba a levantar un
 * servidor: detección, tracking, siluetas y composición ya corrían aquí, pero
 * el empaquetado temporal salía a `/api/plan`. Con esto la herramienta es
 * enteramente local.
 *
 * Se conserva la estructura que hace viable el problema:
 *
 *   1. El coste de colisión entre dos tubos depende SOLO de su desplazamiento
 *      relativo, nunca de sus posiciones absolutas. Así que se precalcula la
 *      curva completa C(d) una vez por par y cada evaluación posterior es una
 *      lectura de array.
 *   2. Poda por rectángulo envolvente: dos tubos cuyas trayectorias no se
 *      solapan espacialmente no pueden colisionar jamás. En escenas reales eso
 *      descarta la mayoría de los O(n²) pares.
 *   3. Siembra voraz (los tubos más largos primero, que son los más
 *      restringidos) + recocido simulado con evaluación incremental + pulido.
 *
 * Diferencia deliberada con la versión de Python: aquí el solape entre siluetas
 * se calcula por intersección directa de máscaras a resolución reducida, no con
 * un mapa de correlación 2D. El mapa de correlación gana cuando hay miles de
 * tubos; en el navegador hay decenas, y el código directo es mucho más corto y
 * no arrastra una FFT.
 */

"use strict";

/* ==========================================================================
   Volúmenes
   ========================================================================== */

/**
 * Un tubo rasterizado a una rejilla común, listo para consultas de colisión.
 *
 * Las siluetas se llevan a celdas alineadas a una rejilla global: así dos
 * máscaras se intersecan por puro recorte de arrays, sin remuestrear en tiempo
 * de consulta.
 */
function buildVolume(tube, scale) {
  const obs = tube.obs;
  const start = obs[0].f;
  const end = obs[obs.length - 1].f;
  const L = end - start + 1;

  const cells = new Array(L).fill(null);
  const rects = new Int32Array(L * 4);
  let ux1 = Infinity, uy1 = Infinity, ux2 = -Infinity, uy2 = -Infinity;

  for (const o of obs) {
    const r = o.f - start;
    if (r < 0 || r >= L) continue;
    const [x1, y1, x2, y2] = o.b;
    const gx1 = Math.floor(x1 / scale), gy1 = Math.floor(y1 / scale);
    const gx2 = Math.max(gx1 + 1, Math.ceil(x2 / scale));
    const gy2 = Math.max(gy1 + 1, Math.ceil(y2 / scale));
    const gw = gx2 - gx1, gh = gy2 - gy1;

    let arr;
    if (o.m && o.m.length) {
      // Silueta real, remuestreada a la celda.
      const bw = Math.max(1, Math.round(x2 - x1));
      const bh = Math.max(1, Math.round(y2 - y1));
      const full = decodeRLE(o.m, bw, bh);
      arr = new Uint8Array(gw * gh);
      for (let y = 0; y < gh; y++) {
        const sy = Math.min(bh - 1, Math.floor((y + 0.5) * bh / gh));
        for (let x = 0; x < gw; x++) {
          const sx = Math.min(bw - 1, Math.floor((x + 0.5) * bw / gw));
          arr[y * gw + x] = full[sy * bw + sx];
        }
      }
      // Silueta vacía tras reducir: se usa el rectángulo, que es lo que haría
      // un sistema sin segmentación. Mejor eso que un tubo sin volumen.
      if (!arr.some((v) => v)) arr.fill(1);
    } else {
      arr = new Uint8Array(gw * gh).fill(1);
    }

    cells[r] = {gx: gx1, gy: gy1, gw, gh, arr};
    rects[r * 4] = gx1; rects[r * 4 + 1] = gy1;
    rects[r * 4 + 2] = gx2; rects[r * 4 + 3] = gy2;
    if (gx1 < ux1) ux1 = gx1;
    if (gy1 < uy1) uy1 = gy1;
    if (gx2 > ux2) ux2 = gx2;
    if (gy2 > uy2) uy2 = gy2;
  }

  let mass = 0;
  for (const c of cells) if (c) for (let i = 0; i < c.arr.length; i++) mass += c.arr[i];

  return {id: tube.id, start, length: L, cells, rects,
          union: [ux1, uy1, ux2, uy2], mass};
}

function decodeRLE(runs, bw, bh) {
  const m = new Uint8Array(bw * bh);
  for (let i = 0; i < runs.length; i += 3) {
    const row = runs[i], s = runs[i + 1], len = runs[i + 2];
    if (row < 0 || row >= bh || s < 0 || len <= 0) continue;
    const from = row * bw + s;
    m.fill(1, from, Math.min(from + len, row * bw + bw));
  }
  return m;
}

function cellOverlap(a, b) {
  const x1 = Math.max(a.gx, b.gx), y1 = Math.max(a.gy, b.gy);
  const x2 = Math.min(a.gx + a.gw, b.gx + b.gw);
  const y2 = Math.min(a.gy + a.gh, b.gy + b.gh);
  if (x2 <= x1 || y2 <= y1) return 0;
  let n = 0;
  for (let y = y1; y < y2; y++) {
    const ra = (y - a.gy) * a.gw - a.gx;
    const rb = (y - b.gy) * b.gw - b.gx;
    for (let x = x1; x < x2; x++) {
      if (a.arr[ra + x] && b.arr[rb + x]) n++;
    }
  }
  return n;
}

/* ==========================================================================
   Curvas de coste por par
   ========================================================================== */

function disjoint(a, b) {
  return a[2] <= b[0] || b[2] <= a[0] || a[3] <= b[1] || b[3] <= a[1];
}

/**
 * Curva C(d) para d = desplazamiento_a − desplazamiento_b.
 *
 * Dos tubos colisionan en el sinopsis cuando i + s_a == j + s_b, es decir
 * d == j − i. Basta con acumular cada solape (i, j) en la diagonal j − i.
 */
function pairCost(va, vb) {
  if (disjoint(va.union, vb.union)) return null;

  const acc = new Map();
  let any = false;
  for (let i = 0; i < va.length; i++) {
    const ca = va.cells[i];
    if (!ca) continue;
    const ax1 = va.rects[i * 4], ay1 = va.rects[i * 4 + 1];
    const ax2 = va.rects[i * 4 + 2], ay2 = va.rects[i * 4 + 3];
    for (let j = 0; j < vb.length; j++) {
      const cb = vb.cells[j];
      if (!cb) continue;
      // Filtro por rectángulo antes de tocar píxeles: la inmensa mayoría de
      // los pares (i, j) ni se rozan.
      if (ax2 <= vb.rects[j * 4] || vb.rects[j * 4 + 2] <= ax1) continue;
      if (ay2 <= vb.rects[j * 4 + 1] || vb.rects[j * 4 + 3] <= ay1) continue;
      const ov = cellOverlap(ca, cb);
      if (!ov) continue;
      const d = j - i;
      acc.set(d, (acc.get(d) || 0) + ov);
      any = true;
    }
  }
  if (!any) return null;

  const ds = [...acc.keys()];
  const dMin = Math.min(...ds), dMax = Math.max(...ds);
  const curve = new Float32Array(dMax - dMin + 1);
  for (const [d, v] of acc) curve[d - dMin] = v;
  return {a: va.id, b: vb.id, dMin, curve};
}

/* ==========================================================================
   Solver
   ========================================================================== */

const DEFAULTS = {
  scale: 6,
  iterations: 12000,
  lambdaChrono: 25,
  lambdaAnchor: 0.6,
  pJump: 0.35,
  polishRounds: 5,
  maxOverlapRatio: 0.012,
  seed: 1,
};

/** Generador reproducible: dos ejecuciones con el mismo plan dan el mismo plan. */
function rng(seed) {
  let s = seed >>> 0 || 1;
  return () => {
    s ^= s << 13; s >>>= 0;
    s ^= s >> 17;
    s ^= s << 5; s >>>= 0;
    return s / 4294967296;
  };
}

class SynopsisSolver {
  constructor(tubes, opts = {}) {
    this.cfg = {...DEFAULTS, ...opts};
    this.tubes = tubes.filter((t) => t.obs && t.obs.length >= 3);
    this.vols = new Map();
    for (const t of this.tubes) this.vols.set(t.id, buildVolume(t, this.cfg.scale));

    this.ids = [...this.vols.keys()];
    this.pairs = [];
    this.adj = new Map(this.ids.map((i) => [i, []]));
    this.totalMass = 0;
    for (const v of this.vols.values()) this.totalMass += v.mass;
    this.totalMass = Math.max(1, this.totalMass);

    this.candidatePairs = 0;
    for (let x = 0; x < this.ids.length; x++) {
      for (let y = x + 1; y < this.ids.length; y++) {
        this.candidatePairs++;
        const pc = pairCost(this.vols.get(this.ids[x]), this.vols.get(this.ids[y]));
        if (!pc) continue;
        const k = this.pairs.length;
        this.pairs.push(pc);
        this.adj.get(pc.a).push(k);
        this.adj.get(pc.b).push(k);
      }
    }

    const starts = this.ids.map((i) => this.vols.get(i).start);
    this.tMin = Math.min(...starts);
    this.sourceDuration = Math.max(
      ...this.ids.map((i) => this.vols.get(i).start + this.vols.get(i).length)) - this.tMin;

    // Orden original de cada par, para el término de cronología.
    this.d0 = new Map();
    for (const pc of this.pairs) {
      this.d0.set(`${pc.a}|${pc.b}`,
                  this.vols.get(pc.a).start - this.vols.get(pc.b).start);
    }
  }

  get density() { return this.pairs.length / Math.max(1, this.candidatePairs); }

  _curve(pc, d) {
    const k = d - pc.dMin;
    return (k < 0 || k >= pc.curve.length) ? 0 : pc.curve[k];
  }

  _chrono(pc, d) {
    const d0 = this.d0.get(`${pc.a}|${pc.b}`);
    const lam = this.cfg.lambdaChrono;
    if (d0 === 0) return lam * Math.abs(d);
    return d0 > 0 ? lam * Math.max(0, -d) : lam * Math.max(0, d);
  }

  _energy(shifts) {
    let coll = 0, chrono = 0, anchor = 0;
    for (const pc of this.pairs) {
      const d = shifts.get(pc.a) - shifts.get(pc.b);
      coll += this._curve(pc, d);
      chrono += this._chrono(pc, d);
    }
    for (const i of this.ids) {
      anchor += this.cfg.lambdaAnchor * Math.abs(shifts.get(i) - (this.anchor.get(i) || 0));
    }
    return {total: coll + chrono + anchor, coll, chrono};
  }

  _local(id, shifts) {
    let tot = this.cfg.lambdaAnchor * Math.abs(shifts.get(id) - (this.anchor.get(id) || 0));
    for (const k of this.adj.get(id)) {
      const pc = this.pairs[k];
      const d = shifts.get(pc.a) - shifts.get(pc.b);
      tot += this._curve(pc, d) + this._chrono(pc, d);
    }
    return tot;
  }

  /**
   * Perfil de coste de TODOS los desplazamientos posibles de un tubo.
   *
   * Para un vecino fijo, la curva del par es la misma trasladada, así que se
   * suma con un recorrido sobre su longitud y no sobre el rango completo de
   * desplazamientos. Es lo que hace la siembra voraz barata.
   */
  _profile(id, shifts, sMax, placed) {
    const prof = new Float32Array(sMax + 1);
    const lam = this.cfg.lambdaChrono;

    for (const k of this.adj.get(id)) {
      const pc = this.pairs[k];
      const other = pc.a === id ? pc.b : pc.a;
      if (!placed.has(other)) continue;
      const so = shifts.get(other);
      const n = pc.curve.length;

      if (pc.a === id) {
        const lo = so + pc.dMin;
        for (let t = 0; t < n; t++) {
          const s = lo + t;
          if (s >= 0 && s <= sMax) prof[s] += pc.curve[t];
        }
      } else {
        const lo = so - (pc.dMin + n - 1);
        for (let t = 0; t < n; t++) {
          const s = lo + t;
          if (s >= 0 && s <= sMax) prof[s] += pc.curve[n - 1 - t];
        }
      }

      let d0 = this.d0.get(`${pc.a}|${pc.b}`);
      if (pc.a !== id) d0 = -d0;
      if (d0 === 0) {
        for (let s = 0; s <= sMax; s++) prof[s] += lam * Math.abs(s - so);
      } else if (d0 > 0) {
        for (let s = 0; s < Math.min(so, sMax + 1); s++) prof[s] += lam * (so - s);
      } else {
        for (let s = Math.max(0, so + 1); s <= sMax; s++) prof[s] += lam * (s - so);
      }
    }

    // Anclaje temporal: sin él, cuando sobra holgura el solver amontona todo en
    // el desplazamiento cero (el argmin de un perfil plano es el índice 0) y
    // deja el final del sinopsis vacío.
    const anc = this.anchor.get(id) || 0;
    const la = this.cfg.lambdaAnchor;
    for (let s = 0; s <= sMax; s++) prof[s] += la * Math.abs(s - anc);
    return prof;
  }

  solve(duration) {
    const cfg = this.cfg;
    if (this.ids.length === 0) return null;

    const maxLen = Math.max(...this.ids.map((i) => this.vols.get(i).length));
    const T = Math.max(duration, maxLen);
    const sMax = new Map(this.ids.map((i) =>
      [i, Math.max(0, T - this.vols.get(i).length)]));

    const span = Math.max(1, this.sourceDuration);
    this.anchor = new Map(this.ids.map((i) => [
      i, Math.round((this.vols.get(i).start - this.tMin) / span * sMax.get(i)),
    ]));

    // --- siembra voraz: los más largos primero, que son los más restringidos.
    const order = [...this.ids].sort(
      (a, b) => this.vols.get(b).length - this.vols.get(a).length);
    const shifts = new Map(this.ids.map((i) => [i, 0]));
    const placed = new Set();
    for (const id of order) {
      const prof = this._profile(id, shifts, sMax.get(id), placed);
      let best = 0, bv = Infinity;
      for (let s = 0; s < prof.length; s++) if (prof[s] < bv) { bv = prof[s]; best = s; }
      shifts.set(id, best);
      placed.add(id);
    }

    // --- recocido con evaluación incremental
    const rand = rng(cfg.seed);
    let E = this._energy(shifts).total;
    if (this.ids.length > 1 && cfg.iterations > 0) {
      const scale = Math.max(1, E);
      const t0 = scale, t1 = 0.002 * scale;
      for (let it = 0; it < cfg.iterations; it++) {
        const temp = t0 * Math.pow(t1 / t0, it / cfg.iterations);
        const id = this.ids[(rand() * this.ids.length) | 0];
        const lim = sMax.get(id);
        if (lim === 0) continue;
        const cur = shifts.get(id);

        let cand;
        if (rand() < cfg.pJump) {
          const others = new Set(placed);
          others.delete(id);
          const prof = this._profile(id, shifts, lim, others);
          cand = 0;
          let bv = Infinity;
          for (let s = 0; s < prof.length; s++) if (prof[s] < bv) { bv = prof[s]; cand = s; }
        } else {
          const span2 = Math.max(2, (lim * (0.02 + 0.3 * temp / t0)) | 0);
          cand = cur + ((rand() * (2 * span2 + 1)) | 0) - span2;
          cand = Math.max(0, Math.min(lim, cand));
        }
        if (cand === cur) continue;

        const before = this._local(id, shifts);
        shifts.set(id, cand);
        const after = this._local(id, shifts);
        const delta = after - before;
        if (delta <= 0 || rand() < Math.exp(-delta / Math.max(temp, 1e-9))) E += delta;
        else shifts.set(id, cur);
      }
    }

    // --- pulido determinista
    for (let round = 0; round < cfg.polishRounds; round++) {
      let improved = false;
      for (const id of order) {
        const lim = sMax.get(id);
        if (lim === 0) continue;
        const others = new Set(placed);
        others.delete(id);
        const prof = this._profile(id, shifts, lim, others);
        let best = shifts.get(id);
        let bv = prof[best];
        for (let s = 0; s < prof.length; s++) if (prof[s] < bv - 1e-6) { bv = prof[s]; best = s; }
        if (best !== shifts.get(id)) { shifts.set(id, best); improved = true; }
      }
      if (!improved) break;
    }

    const e = this._energy(shifts);
    const placements = {};
    const starts = {};
    for (const i of this.ids) {
      placements[i] = shifts.get(i);
      starts[i] = this.vols.get(i).start;
    }
    return {
      duration: T,
      source_duration: this.sourceDuration,
      compression: +(this.sourceDuration / Math.max(1, T)).toFixed(2),
      overlap_ratio: +(e.coll / this.totalMass).toFixed(4),
      energy: e.total, collision: e.coll, chronology: e.chrono,
      tubes: this.ids.length,
      placements, starts,
    };
  }

  /**
   * Busca por bisección el sinopsis más corto que respeta el presupuesto de
   * saturación. El presupuesto es fracción de masa de silueta solapada:
   * adimensional, comparable entre cámaras, y es lo que un espectador percibe
   * como amontonamiento --- a diferencia de un tope de objetos por frame, que
   * da resultados vacíos o ilegibles según lo grandes que salgan los objetos.
   */
  auto(minDuration = null, maxDuration = null) {
    if (this.ids.length === 0) return null;
    let lo = minDuration || Math.max(...this.ids.map((i) => this.vols.get(i).length));
    let hi = maxDuration || this.sourceDuration;
    if (hi <= lo) return this.solve(lo);

    const full = this.cfg.iterations;
    this.cfg.iterations = Math.max(1500, (full / 6) | 0);
    let best = this.solve(hi);
    let it = 0;
    while (hi - lo > Math.max(4, (0.04 * hi) | 0) && it < 12) {
      const mid = (lo + hi) >> 1;
      const plan = this.solve(mid);
      if (plan.overlap_ratio <= this.cfg.maxOverlapRatio) { best = plan; hi = mid; }
      else lo = mid;
      it++;
    }
    this.cfg.iterations = full;
    return this.solve(best.duration);
  }
}

/** Punto de entrada: tubos del motor -> plan. */
function planSynopsis(payload, opts = {}) {
  const solver = new SynopsisSolver(payload.tubes || [], opts);
  if (!solver.ids.length) return null;
  const plan = opts.duration ? solver.solve(opts.duration) : solver.auto();
  if (plan) plan.pair_density = +solver.density.toFixed(3);
  return plan;
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = {SynopsisSolver, planSynopsis, buildVolume, pairCost, decodeRLE};
}
