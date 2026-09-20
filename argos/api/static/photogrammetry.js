/*
 * ARGOS · fotogrametría en el navegador
 * ---------------------------------------------------------------------------
 * Puerto de argos/measure/{calibration,anthropometry}.py. Era lo último que
 * quedaba en Python; con esto la herramienta no necesita nada más.
 *
 * Lo que hay dentro:
 *
 *   · Autocalibración a partir de los propios peatones (Lv/Zhao/Nevatia 2002):
 *     punto de fuga vertical por espacio nulo, horizonte por RANSAC, escala
 *     anclada en la mediana poblacional.
 *   · Altura métrica por metrología de vista única (Criminisi/Reid/Zisserman).
 *   · Agregación por tubo con las correcciones que costaron encontrar:
 *     suavizado temporal antes del percentil, resta analítica de la inflación
 *     por ruido, y ensanchado del intervalo cuando la calibración es
 *     poblacional.
 *
 * Sobre el álgebra: el original usa SVD de numpy. Aquí no hace falta una SVD
 * general --- lo único que se necesita es el vector propio del menor valor
 * propio de una matriz simétrica 3x3 (AᵀA). Eso se resuelve con rotaciones de
 * Jacobi en unas pocas líneas y sin arrastrar una biblioteca de álgebra.
 */

"use strict";

/* ==========================================================================
   Álgebra mínima
   ========================================================================== */

/**
 * Diagonalización de una simétrica 3x3 por rotaciones de Jacobi.
 * Devuelve valores propios y vectores propios en columnas.
 */
function jacobiEigen3(Ain) {
  const A = [Ain[0].slice(), Ain[1].slice(), Ain[2].slice()];
  let V = [[1, 0, 0], [0, 1, 0], [0, 0, 1]];

  for (let sweep = 0; sweep < 24; sweep++) {
    let off = 0;
    for (let i = 0; i < 3; i++) {
      for (let j = i + 1; j < 3; j++) off += A[i][j] * A[i][j];
    }
    if (off < 1e-24) break;

    for (let p = 0; p < 2; p++) {
      for (let q = p + 1; q < 3; q++) {
        if (Math.abs(A[p][q]) < 1e-18) continue;
        const theta = (A[q][q] - A[p][p]) / (2 * A[p][q]);
        const t = Math.sign(theta || 1) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
        const c = 1 / Math.sqrt(t * t + 1);
        const s = t * c;

        for (let k = 0; k < 3; k++) {
          const akp = A[k][p], akq = A[k][q];
          A[k][p] = c * akp - s * akq;
          A[k][q] = s * akp + c * akq;
        }
        for (let k = 0; k < 3; k++) {
          const apk = A[p][k], aqk = A[q][k];
          A[p][k] = c * apk - s * aqk;
          A[q][k] = s * apk + c * aqk;
        }
        for (let k = 0; k < 3; k++) {
          const vkp = V[k][p], vkq = V[k][q];
          V[k][p] = c * vkp - s * vkq;
          V[k][q] = s * vkp + c * vkq;
        }
      }
    }
  }
  return {values: [A[0][0], A[1][1], A[2][2]], vectors: V};
}

/** Vector unitario que minimiza |Mx|: el propio del menor valor propio de MᵀM. */
function nullVector3(rows) {
  const M = [[0, 0, 0], [0, 0, 0], [0, 0, 0]];
  for (const r of rows) {
    for (let i = 0; i < 3; i++) {
      for (let j = 0; j < 3; j++) M[i][j] += r[i] * r[j];
    }
  }
  const {values, vectors} = jacobiEigen3(M);
  let k = 0;
  for (let i = 1; i < 3; i++) if (values[i] < values[k]) k = i;
  return [vectors[0][k], vectors[1][k], vectors[2][k]];
}

const cross = (a, b) => [
  a[1] * b[2] - a[2] * b[1],
  a[2] * b[0] - a[0] * b[2],
  a[0] * b[1] - a[1] * b[0],
];
const norm2 = (v) => Math.hypot(v[0], v[1], v[2]);
const homo = (p) => (p.length === 2 ? [p[0], p[1], 1] : p);

function median(arr) {
  if (!arr.length) return NaN;
  const a = Float64Array.from(arr).sort();
  const m = a.length >> 1;
  return a.length % 2 ? a[m] : (a[m - 1] + a[m]) / 2;
}

function percentile(arr, p) {
  if (!arr.length) return NaN;
  const a = Float64Array.from(arr).sort();
  const idx = (a.length - 1) * p / 100;
  const lo = Math.floor(idx), hi = Math.ceil(idx);
  return lo === hi ? a[lo] : a[lo] + (a[hi] - a[lo]) * (idx - lo);
}

const mad = (arr) => {
  const m = median(arr);
  return median(arr.map((v) => Math.abs(v - m)));
};

/** Inversa de la función error, para convertir percentil en z. */
function erfinv(x) {
  const a = 0.147;
  const ln = Math.log(1 - x * x);
  const t1 = 2 / (Math.PI * a) + ln / 2;
  return Math.sign(x) * Math.sqrt(Math.sqrt(t1 * t1 - ln / a) - t1);
}

/* ==========================================================================
   Cámara
   ========================================================================== */

/**
 * Altura métrica a partir de punto de fuga vertical, horizonte y una escala.
 *
 *     Z = − |b × t| / ( alpha · (l · b) · |v × t| )
 */
class HorizonCamera {
  constructor(v, horizon, alpha = 1) {
    this.v = v;
    const n = Math.hypot(horizon[0], horizon[1]) || 1;
    this.horizon = [horizon[0] / n, horizon[1] / n, horizon[2] / n];
    this.alpha = alpha;
    this.mode = "self";
    this.anchorStature = null;
    this.samples = 0;
    this.residualStd = NaN;
  }

  height(base, top) {
    const b = homo(base), t = homo(top);
    const num = norm2(cross(b, t));
    const dot = this.horizon[0] * b[0] + this.horizon[1] * b[1] + this.horizon[2] * b[2];
    const den = this.alpha * dot * norm2(cross(this.v, t));
    if (!isFinite(den) || Math.abs(den) < 1e-12) return NaN;
    return -num / den;
  }

  /** Solo una calibración con referencias medidas respalda una cifra probatoria. */
  get evidential() { return this.mode === "surveyed"; }

  provenance() {
    return {mode: this.mode, anchor_stature_m: this.anchorStature,
            n_samples: this.samples,
            residual_std_m: +this.residualStd.toFixed(4),
            evidential: this.evidential};
  }
}

/* ==========================================================================
   Autocalibración desde peatones
   ========================================================================== */

/**
 * Punto de fuga vertical: espacio nulo de las líneas cabeza-pies.
 *
 * Ponderar por longitud del segmento importa: una persona de 30 px restringe
 * la dirección mucho más débilmente que una de 300, y sin peso la multitud
 * lejana domina el ajuste.
 */
function estimateVerticalVP(feet, heads) {
  const rows = [];
  for (let i = 0; i < feet.length; i++) {
    const line = cross(homo(feet[i]), homo(heads[i]));
    const n = Math.hypot(line[0], line[1]);
    if (n < 1e-9) continue;
    const w = Math.hypot(heads[i][0] - feet[i][0], heads[i][1] - feet[i][1]);
    rows.push([line[0] / n * w, line[1] / n * w, line[2] / n * w]);
  }
  if (rows.length < 2) throw new Error("segmentos insuficientes");
  const v = nullVector3(rows);
  return Math.abs(v[2]) > 1e-12 ? [v[0] / v[2], v[1] / v[2], 1] : v;
}

/**
 * Horizonte por RANSAC sobre pares de personas.
 *
 * Dos personas de igual altura dan una línea de pies y otra de cabezas que se
 * cortan sobre el horizonte. Las multitudes reales tienen unos ±9 cm de
 * dispersión, así que la mayoría de pares cae algo fuera: RANSAC encuentra el
 * consenso y después se reajusta por mínimos cuadrados totales.
 */
function estimateHorizon(feet, heads, cfg, rand) {
  const n = feet.length;
  const pts = [];
  const tries = Math.min(4000, n * 12);
  for (let k = 0; k < tries; k++) {
    const i = (rand() * n) | 0;
    let j = (rand() * n) | 0;
    if (i === j) continue;
    const fl = cross(homo(feet[i]), homo(feet[j]));
    const hl = cross(homo(heads[i]), homo(heads[j]));
    const p = cross(fl, hl);
    if (Math.abs(p[2]) < 1e-9) continue;
    pts.push([p[0] / p[2], p[1] / p[2], 1]);
  }
  if (pts.length < 10) throw new Error("configuración degenerada");

  let best = null, bestIn = -1;
  for (let it = 0; it < cfg.ransacIters; it++) {
    const a = pts[(rand() * pts.length) | 0];
    const b = pts[(rand() * pts.length) | 0];
    const line = cross(a, b);
    const nn = Math.hypot(line[0], line[1]);
    if (nn < 1e-9) continue;
    const L = [line[0] / nn, line[1] / nn, line[2] / nn];
    let inl = 0;
    for (const p of pts) {
      if (Math.abs(L[0] * p[0] + L[1] * p[1] + L[2]) < cfg.inlierPx) inl++;
    }
    if (inl > bestIn) { bestIn = inl; best = L; }
  }
  if (!best) throw new Error("no se pudo ajustar el horizonte");

  // Reajuste por mínimos cuadrados totales sobre el consenso: la recta que
  // minimiza distancia perpendicular es el eje principal menor de la nube,
  // que en 2D sale en forma cerrada.
  const inliers = pts.filter(
    (p) => Math.abs(best[0] * p[0] + best[1] * p[1] + best[2]) < cfg.inlierPx);
  if (inliers.length >= 2) {
    let mx = 0, my = 0;
    for (const p of inliers) { mx += p[0]; my += p[1]; }
    mx /= inliers.length; my /= inliers.length;
    let sxx = 0, sxy = 0, syy = 0;
    for (const p of inliers) {
      const dx = p[0] - mx, dy = p[1] - my;
      sxx += dx * dx; sxy += dx * dy; syy += dy * dy;
    }
    const theta = 0.5 * Math.atan2(2 * sxy, sxx - syy);
    const nx = -Math.sin(theta), ny = Math.cos(theta);
    best = [nx, ny, -(nx * mx + ny * my)];
  }
  return best;
}

const CALIB_DEFAULTS = {
  ransacIters: 800,
  inlierPx: 12,
  minPairs: 40,
  minSegmentPx: 40,
  assumedMedianStature: 1.70,
  seed: 7,
};

function rng(seed) {
  let s = seed >>> 0 || 1;
  return () => {
    s ^= s << 13; s >>>= 0;
    s ^= s >> 17;
    s ^= s << 5; s >>>= 0;
    return s / 4294967296;
  };
}

/**
 * Calibración completa. Con `references` la escala sale de objetos medidos y la
 * cámara queda como `surveyed`; sin ellas se ancla en la mediana poblacional y
 * queda como `self`, **no probatoria**: si la mediana real del sitio difiere de
 * la asumida, ese error entra íntegro en cada medida, es sistemático y no se
 * puede detectar desde el vídeo.
 */
function calibrateFromPedestrians(feet, heads, opts = {}, references = null) {
  const cfg = {...CALIB_DEFAULTS, ...opts};
  const F = [], H = [];
  for (let i = 0; i < feet.length; i++) {
    if (Math.hypot(heads[i][0] - feet[i][0], heads[i][1] - feet[i][1]) >= cfg.minSegmentPx) {
      F.push(feet[i]); H.push(heads[i]);
    }
  }
  if (F.length < cfg.minPairs) {
    throw new Error(`hacen falta ${cfg.minPairs} observaciones, hay ${F.length}`);
  }

  const rand = rng(cfg.seed);
  const v = estimateVerticalVP(F, H);
  const horizon = estimateHorizon(F, H, cfg, rand);
  const cam = new HorizonCamera(v, horizon, 1);

  // Una recta y su negación son la misma recta, así que el signo de (l·b) ---y
  // por tanto el de toda altura--- sale arbitrario del ajuste. Se orienta una
  // sola vez usando que las personas tienen altura positiva; sin esto el
  // estimador descarta todas las muestras aproximadamente la mitad de las veces.
  const probe = F.map((f, i) => cam.height(f, H[i])).filter(Number.isFinite);
  if (probe.length && median(probe) < 0) {
    cam.horizon = cam.horizon.map((c) => -c);
  }

  if (references && references.length) {
    const ratios = [];
    for (const [base, top, known] of references) {
      const raw = cam.height(base, top);
      if (Number.isFinite(raw) && known > 0) ratios.push(raw / known);
    }
    if (!ratios.length) throw new Error("ninguna referencia utilizable");
    cam.alpha = median(ratios);
    cam.mode = "surveyed";
  } else {
    const raw = F.map((f, i) => cam.height(f, H[i])).filter((z) => Number.isFinite(z) && z > 0);
    if (raw.length < Math.max(10, cfg.minPairs / 4)) {
      throw new Error(`muestras insuficientes (${raw.length}) para anclar la escala`);
    }
    cam.alpha = median(raw) / cfg.assumedMedianStature;
    cam.mode = "self";
    cam.anchorStature = cfg.assumedMedianStature;
  }

  const final = F.map((f, i) => cam.height(f, H[i])).filter(Number.isFinite);
  cam.samples = final.length;
  const mu = final.reduce((a, b) => a + b, 0) / Math.max(1, final.length);
  cam.residualStd = Math.sqrt(
    final.reduce((a, b) => a + (b - mu) ** 2, 0) / Math.max(1, final.length));
  return cam;
}

/** Recoge observaciones limpias de cabeza y pies desde los tubos ya seguidos. */
function pairsFromTubes(tubes, W, H, opts = {}) {
  const {className = "person", borderPx = 6, minHpx = 40, perTube = 6, seed = 3} = opts;
  const rand = rng(seed);
  const feet = [], heads = [];
  for (const t of tubes) {
    if (className && t.class !== className && t.klass !== className) continue;
    const cand = [];
    for (const o of (t.obs || [])) {
      const [x1, y1, x2, y2] = o.b;
      if (y2 - y1 < minHpx) continue;
      if (x1 <= borderPx || y1 <= borderPx || x2 >= W - borderPx || y2 >= H - borderPx) continue;
      const ar = (x2 - x1) / Math.max(1e-6, y2 - y1);
      if (ar < 0.12 || ar > 0.75) continue;
      const cx = (x1 + x2) / 2;
      cand.push([[cx, y2], [cx, y1]]);
    }
    if (!cand.length) continue;
    // Solo unas pocas observaciones por tubo: los frames consecutivos de una
    // persona son casi idénticos y dejarían que un único recorrido largo
    // dominase el ajuste, describiendo bien una esquina de la escena y mal el
    // resto.
    const take = Math.min(perTube, cand.length);
    const used = new Set();
    while (used.size < take) {
      const i = (rand() * cand.length) | 0;
      if (used.has(i)) continue;
      used.add(i);
      feet.push(cand[i][0]);
      heads.push(cand[i][1]);
    }
  }
  return {feet, heads};
}

/* ==========================================================================
   Estatura por tubo
   ========================================================================== */

const STATURE_DEFAULTS = {
  percentile: 88,
  minSamples: 12,
  minBoxHeightPx: 45,
  maxAspect: 0.75,
  minAspect: 0.12,
  borderMarginPx: 6,
  plausible: [1.20, 2.20],
  bootstrap: 300,
  selfCalibrationFrac: 0.055,
  seed: 11,
};

class StatureEstimator {
  constructor(camera, W, H, opts = {}) {
    this.cam = camera;
    this.W = W;
    this.H = H;
    this.cfg = {...STATURE_DEFAULTS, ...opts};
  }

  _samples(tube) {
    const c = this.cfg;
    const vals = [];
    let rejected = 0;
    for (const o of (tube.obs || [])) {
      const [x1, y1, x2, y2] = o.b;
      const w = x2 - x1, h = y2 - y1;
      if (h < c.minBoxHeightPx) { rejected++; continue; }
      const ar = w / Math.max(1e-6, h);
      if (ar < c.minAspect || ar > c.maxAspect) { rejected++; continue; }
      // Cualquier caja que toque un borde está truncada: los pies o la cabeza
      // quedan fuera y el extremo es ficción.
      if (x1 <= c.borderMarginPx || y1 <= c.borderMarginPx
          || x2 >= this.W - c.borderMarginPx || y2 >= this.H - c.borderMarginPx) {
        rejected++; continue;
      }
      const cx = (x1 + x2) / 2;
      const z = this.cam.height([cx, y2], [cx, y1]);
      if (!Number.isFinite(z) || z < c.plausible[0] * 0.6 || z > c.plausible[1] * 1.4) {
        rejected++; continue;
      }
      vals.push(z);
    }
    return {vals, rejected};
  }

  estimate(tube) {
    const c = this.cfg;
    const {vals, rejected} = this._samples(tube);
    const notes = [];
    if (vals.length < c.minSamples) {
      return {tube_id: tube.id, height_m: NaN, ci_low: NaN, ci_high: NaN,
              n_samples: vals.length, n_rejected: rejected,
              quality: "unusable", notes: ["observaciones insuficientes"]};
    }

    // Agregación de un solo lado: la marcha, encorvarse, cargar peso y la
    // oclusión parcial de los pies acortan la figura; casi nada la alarga salvo
    // un gorro. Por eso un percentil alto se acerca más a la estatura de pie
    // que la mediana.
    //
    // Pero un percentil alto sobre valores crudos también recoge el ruido
    // simétrico del detector y se infla con él (medido: +0,0 cm con 0,5 px de
    // jitter, +7,6 cm con 3,5 px). Se suaviza antes en el tiempo --- el ruido
    // de extremos es independiente por frame, la marcha es suave --- y se resta
    // la inflación residual de forma analítica.
    const w = Math.min(7, vals.length % 2 ? vals.length : vals.length - 1);
    let smooth = vals;
    if (w >= 3) {
      const pad = w >> 1;
      smooth = vals.map((_, i) => {
        const win = [];
        for (let k = -pad; k <= pad; k++) {
          win.push(vals[Math.min(vals.length - 1, Math.max(0, i + k))]);
        }
        return median(win);
      });
    }
    const resid = vals.map((v, i) => v - smooth[i]);
    const sigma = 1.4826 * mad(resid);
    const z = Math.SQRT2 * erfinv(2 * c.percentile / 100 - 1);
    const inflation = z * sigma / Math.sqrt(Math.max(1, w));
    const point = percentile(smooth, c.percentile) - inflation;

    const rand = rng(c.seed);
    const boots = [];
    for (let b = 0; b < c.bootstrap; b++) {
      const s = [];
      for (let k = 0; k < smooth.length; k++) s.push(smooth[(rand() * smooth.length) | 0]);
      boots.push(percentile(s, c.percentile) - inflation);
    }
    let lo = percentile(boots, 5), hi = percentile(boots, 95);

    // El bootstrap solo captura ruido de muestreo, que es la menor de las tres
    // fuentes de error. La calibración y el sesgo de extremos son sistemáticos
    // y no encogen con más frames: promediar 200 observaciones de alguien en
    // una esquina mal calibrada da una respuesta muy precisa y equivocada.
    const mu = smooth.reduce((a, b) => a + b, 0) / smooth.length;
    const spread = Math.sqrt(
      smooth.reduce((a, b) => a + (b - mu) ** 2, 0) / smooth.length);
    let syst = 0.025 + 0.5 * spread;
    if (this.cam.mode === "self") {
      syst = Math.hypot(syst, c.selfCalibrationFrac * point);
      notes.push("cámara autocalibrada: no probatoria, intervalo ensanchado");
    }
    lo -= syst; hi += syst;

    let quality;
    if (vals.length >= 60 && spread < 0.035 && sigma < 0.05) quality = "good";
    else if (vals.length >= 30 && spread < 0.07 && sigma < 0.10) quality = "fair";
    else { quality = "poor"; notes.push("varianza alta: revisa oclusiones o calibración"); }
    if (point < c.plausible[0] || point > c.plausible[1]) {
      quality = "poor";
      notes.push("fuera del rango adulto plausible");
    }
    notes.push("altura tal como se presenta: incluye calzado (+2-4 cm) y pelo");

    return {tube_id: tube.id, height_m: point, ci_low: lo, ci_high: hi,
            n_samples: vals.length, n_rejected: rejected, quality, notes};
  }
}

/* ==========================================================================
   Entrada única
   ========================================================================== */

/**
 * Autocalibra con los peatones del payload y mide a los que se pueda.
 * Devuelve `available:false` con motivo cuando no hay base suficiente, en vez
 * de una cifra que nadie debería creerse.
 */
function measureStature(payload, opts = {}) {
  const [W, H] = payload.analysis_size || [0, 0];
  const peds = (payload.tubes || []).filter((t) => (t.class || t.klass) === "person");
  const MIN = opts.minPedestrians ?? 12;
  if (peds.length < MIN) {
    return {available: false,
            reason: `${peds.length} peatones; hacen falta al menos ${MIN} `
                    + "para autocalibrar"};
  }
  const {feet, heads} = pairsFromTubes(peds, W, H, opts);
  let cam;
  try {
    cam = calibrateFromPedestrians(feet, heads, opts, opts.references || null);
  } catch (err) {
    return {available: false, reason: err.message};
  }
  const est = new StatureEstimator(cam, W, H, opts);
  const results = [];
  for (const t of peds) {
    const e = est.estimate(t);
    // 'poor' significa que el propio estimador no se fía. Publicarla igualmente
    // solo blanquea una cifra poco fiable.
    if (Number.isFinite(e.height_m) && (e.quality === "good" || e.quality === "fair")) {
      results.push({tube: e.tube_id, m: +e.height_m.toFixed(2),
                    ci: [+e.ci_low.toFixed(2), +e.ci_high.toFixed(2)],
                    quality: e.quality});
    }
  }
  return {available: true, calibration: cam.provenance(),
          evidential: cam.evidential, measured: peds.length, results};
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = {jacobiEigen3, nullVector3, HorizonCamera, estimateVerticalVP,
                    estimateHorizon, calibrateFromPedestrians, pairsFromTubes,
                    StatureEstimator, measureStature};
}
