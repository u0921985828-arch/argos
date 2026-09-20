/*
 * ARGOS · cerebro
 * ---------------------------------------------------------------------------
 * Coordina las dos escalas de tiempo que tiene este problema y que no se pueden
 * mezclar sin romper una de las dos:
 *
 *   RÁPIDA   sustracción de fondo, asociación, física, dibujo      ~4 ms
 *            corre en CADA frame, a la tasa de la pantalla
 *
 *   LENTA    detector neuronal por teselas                    15-130 ms
 *            corre por su cuenta, una tesela por pasada
 *
 * Un sistema que espera al detector en cada frame va a 8 fps. Uno que solo usa
 * el fondo no ve a la gente parada. El cerebro deja que cada uno vaya a su
 * ritmo y funde lo que producen:
 *
 *   · El fondo mantiene vivas y actualizadas las trayectorias entre pasadas.
 *   · El detector confirma qué es cada cosa, encuentra lo que no se mueve y
 *     borra lo que era ruido.
 *   · La atención se reparte según dónde hay **personas**, no uniformemente.
 *
 * Y sobre lo observado deriva conocimiento que ninguna de las dos capas tiene
 * por separado: escala métrica de la escena aprendida de los propios peatones,
 * velocidad real en m/s, estatura acumulada por trayectoria y eventos.
 */

"use strict";

/* ==========================================================================
   Modelo de escena
   ========================================================================== */

/**
 * Relación lineal entre la fila de contacto con el suelo y el tamaño imagen.
 *
 * Es la que convierte píxeles en metros sin calibrar nada: si sé que un peatón
 * a la fila `y` mide `h(y)` píxeles y que un peatón mide ~1,70 m, entonces en
 * esa zona un píxel son 1,70/h(y) metros. Vale para velocidad, para separación
 * y para estatura.
 *
 * El ajuste es robusto y reponderado porque un ajuste ordinario se lo comen los
 * propios atípicos --- la estatua, el cartel, el reflejo --- que tiran de la
 * recta hasta dejar de parecer anómalos.
 */
/** Resuelve un sistema pequeño por eliminación gaussiana con pivoteo. */
function solveSmall(M, v, n) {
  const A = Array.from({length: n}, (_, i) => {
    const row = new Float64Array(n + 1);
    for (let j = 0; j < n; j++) row[j] = M[i][j];
    row[n] = v[i];
    return row;
  });
  for (let c = 0; c < n; c++) {
    let piv = c;
    for (let r = c + 1; r < n; r++) if (Math.abs(A[r][c]) > Math.abs(A[piv][c])) piv = r;
    // Un pivote nulo significa columnas linealmente dependientes: el sistema no
    // determina los coeficientes, y devolver "algo" sería inventárselos.
    if (Math.abs(A[piv][c]) < 1e-12) return null;
    [A[c], A[piv]] = [A[piv], A[c]];
    for (let r = 0; r < n; r++) {
      if (r === c) continue;
      const f = A[r][c] / A[c][c];
      for (let k = c; k <= n; k++) A[r][k] -= f * A[c][k];
    }
  }
  return Array.from({length: n}, (_, i) => A[i][n] / A[i][i]);
}

class SceneScale {
  constructor(opts = {}) {
    this.a = 0;
    this.b = 0;
    this.sigma = 0;
    this.n = 0;
    this.valid = false;
    this.classScale = new Map();
    this.assumedStature = opts.assumedStature ?? 1.70;
    this.minSamples = opts.minSamples ?? 10;
    // Recorrido vertical mínimo del conjunto de muestras, en píxeles de fuente.
    this.minYSpan = opts.minYSpan ?? 120;
    this.ySpan = 0;
    // Coeficiente del término en x: el alabeo de la cámara. Cero mientras los
    // datos no lo sostengan.
    this.cx = 0;
    this.usesX = false;
    this.colinearity = 0;
    this.maxColinearity = opts.maxColinearity ?? 0.75;
  }

  /**
   * Ajuste de plano: altura = a·y + c·x + b.
   *
   * El modelo anterior sólo dependía de la fila. Eso asume que la línea de fuga
   * del suelo es **horizontal en la imagen**, y sólo se cumple si la cámara no
   * tiene alabeo. En cuanto está girada unos grados --- o el objetivo es gran
   * angular --- dos personas en la misma fila y a distinto lado del cuadro se
   * proyectan con tamaños distintos, y un modelo de una variable no puede
   * representarlo.
   *
   * Bajo proyección de un plano, la altura imagen de un objeto vertical de
   * tamaño fijo es proporcional a `l·p` con `l` la línea de fuga: **lineal en x
   * y en y a la vez**. El término en x es literalmente el alabeo de la cámara.
   *
   * Pero añadirlo no siempre es correcto, y aquí está lo que costó ver:
   *
   *   SOL     (plaza ancha, muestras por todo el cuadro)  sigma 5,45 -> 5,24
   *   ALCALA  (calle en diagonal, muestras en una banda)  sigma 4,15 -> 5,81
   *
   * En Alcalá empeora un 40 % y el coeficiente en x se dispara a triplicar el
   * de y. No es que la cámara tenga alabeo: es que **todas las muestras caen
   * sobre una línea** en (x, y), los dos coeficientes son inseparables y el
   * sistema está mal condicionado. El ajuste elige una combinación cualquiera
   * de las infinitas que pasan por esa línea.
   *
   * Así que el término en x se adopta sólo cuando los datos lo sostienen: baja
   * correlación entre x e y, y mejora real del residuo.
   */
  _fitPlane(pts, useX) {
    const n = pts.length;
    if (n < (useX ? 12 : 6)) return null;
    const cols = useX ? 3 : 2;
    let keep = new Array(n).fill(true);
    let coef = null, sigma = 0, kept = 0;

    for (let iter = 0; iter < 6; iter++) {
      // Normal equations de mínimos cuadrados: A^T A c = A^T h.
      const M = Array.from({length: cols}, () => new Float64Array(cols));
      const v = new Float64Array(cols);
      let m = 0;
      for (let i = 0; i < n; i++) {
        if (!keep[i]) continue;
        m++;
        const row = useX ? [pts[i].y, pts[i].x, 1] : [pts[i].y, 1];
        for (let a = 0; a < cols; a++) {
          v[a] += row[a] * pts[i].h;
          for (let b = 0; b < cols; b++) M[a][b] += row[a] * row[b];
        }
      }
      if (m < cols + 2) return null;
      coef = solveSmall(M, v, cols);
      if (!coef) return null;

      const res = pts.map((p) => p.h - (useX
        ? coef[0] * p.y + coef[1] * p.x + coef[2]
        : coef[0] * p.y + coef[1]));
      const inl = res.filter((_, i) => keep[i]);
      const med = median(inl);
      const sd = 1.4826 * median(inl.map((r) => Math.abs(r - med)));
      if (sd <= 1e-6) break;
      const next = res.map((r) => Math.abs(r) < 2.5 * sd);
      if (next.every((q, i) => q === keep[i])) break;
      keep = next;
    }

    const res = [];
    for (let i = 0; i < n; i++) {
      if (!keep[i]) continue;
      res.push(pts[i].h - (useX
        ? coef[0] * pts[i].y + coef[1] * pts[i].x + coef[2]
        : coef[0] * pts[i].y + coef[1]));
    }
    kept = res.length;
    const mu = res.reduce((p, q) => p + q, 0) / Math.max(1, kept);
    sigma = Math.sqrt(res.reduce((p, q) => p + (q - mu) ** 2, 0) / Math.max(1, kept));
    return {coef, sigma: Math.max(1e-3, sigma), n: kept, useX};
  }

  fit(samples) {
    // samples: [{y, h, cls}]  --- un punto por trayectoria, no por observación.
    //
    // Histéresis: un ajuste válido NO se descarta porque el siguiente intento
    // salga pobre. La geometría de la cámara no cambia entre un minuto y el
    // siguiente; lo que cambia es cuántos objetos hay ahora en la ventana de
    // retención. Sin esto la escala parpadea entre válida e inválida al ritmo
    // del tráfico, y con ella parpadean las velocidades en m/s y las estaturas.
    const pts = samples.filter((s) => s.h > 4 && Number.isFinite(s.y)
                                 && Number.isFinite(s.x));
    if (pts.length < this.minSamples) return this;

    // ¿Cubren las muestras el cuadro, o caen todas sobre una línea?
    //
    // Si x e y están muy correlacionados --- una calle en diagonal, un pasillo
    // --- sus coeficientes son inseparables y añadir el término en x no mide el
    // alabeo de la cámara: ajusta ruido. Se comprueba antes de intentarlo.
    const mx = pts.reduce((p, q) => p + q.x, 0) / pts.length;
    const my = pts.reduce((p, q) => p + q.y, 0) / pts.length;
    let sxy = 0, sxx = 0, syy = 0;
    for (const p of pts) {
      sxy += (p.x - mx) * (p.y - my);
      sxx += (p.x - mx) ** 2;
      syy += (p.y - my) ** 2;
    }
    const corr = Math.abs(sxy) / Math.max(1e-9, Math.sqrt(sxx * syy));

    const flat = this._fitPlane(pts, false);
    let plane = null;
    if (corr < this.maxColinearity && pts.length >= 40) {
      plane = this._fitPlane(pts, true);
      // Y sólo se adopta si de verdad reduce el residuo. Un parámetro más
      // siempre ajusta igual o mejor los datos vistos; se exige una mejora
      // apreciable para creer que describe la escena y no el ruido.
      if (plane && flat && plane.sigma > flat.sigma * 0.97) plane = null;
    }
    this.colinearity = corr;
    this.usesX = !!plane;
    const best = plane || flat;
    if (best) {
      this.a = best.coef[0];
      this.cx = plane ? best.coef[1] : 0;
      this.b = plane ? best.coef[2] : best.coef[1];
    }

    const persons = pts.filter((s) => s.cls === "person");
    const base = persons.length >= this.minSamples ? persons : pts;

    let keep = base.map(() => true);
    let a = 0, b = 0;
    for (let iter = 0; iter < 5; iter++) {
      let n = 0, sx = 0, sy = 0, sxx = 0, sxy = 0;
      for (let i = 0; i < base.length; i++) {
        if (!keep[i]) continue;
        const {y, h} = base[i];
        n++; sx += y; sy += h; sxx += y * y; sxy += y * h;
      }
      if (n < 4) break;
      const den = n * sxx - sx * sx;
      if (Math.abs(den) < 1e-9) break;
      a = (n * sxy - sx * sy) / den;
      b = (sy - a * sx) / n;

      const res = base.map((s) => s.h - (a * s.y + b));
      const inl = res.filter((_, i) => keep[i]);
      const med = median(inl);
      const s = 1.4826 * median(inl.map((v) => Math.abs(v - med)));
      if (s <= 1e-6) break;
      const next = res.map((r) => Math.abs(r) < 2.5 * s);
      if (next.every((v, i) => v === keep[i])) break;
      keep = next;
    }

    const res = [];
    for (let i = 0; i < base.length; i++) if (keep[i]) res.push(base[i].h - (a * base[i].y + b));
    const mu = res.reduce((p, q) => p + q, 0) / Math.max(1, res.length);

    // Tres condiciones, y las tres hacen falta. La segunda es la que importa y
    // la que se olvida: **no se puede aprender perspectiva de objetos que están
    // todos a la misma distancia**. Un conjunto de puntos con poco recorrido en
    // `y` determina la pendiente por puro ruido, y el resultado es una escala
    // métrica inventada que después se propaga a velocidades y estaturas ---
    // en pruebas llegó a disparar eventos de "corriendo" sobre gente andando.
    const ys = base.filter((_, i) => keep[i]).map((s2) => s2.y);
    const ySpan = ys.length ? Math.max(...ys) - Math.min(...ys) : 0;
    const meanH = base.reduce((p2, q) => p2 + q.h, 0) / Math.max(1, base.length);
    const sigma = Math.max(1e-3,
      Math.sqrt(res.reduce((p2, q) => p2 + (q - mu) ** 2, 0) / Math.max(1, res.length)));

    const ok = res.length >= this.minSamples
      && sigma > 0
      && ySpan >= (this.minYSpan ?? 120)      // recorrido vertical suficiente
      && a > 0                                // más abajo => más grande
      && sigma < 0.45 * Math.max(1, meanH);   // dispersión razonable

    if (!ok && this.valid) {
      // Se conserva el ajuste anterior, que sí era bueno.
      this.rejected = (this.rejected || 0) + 1;
      return this;
    }
    this.ySpan = ySpan;
    this.valid = ok;
    this.a = a; this.b = b; this.sigma = sigma; this.n = res.length;

    // Factor por clase: un autobús mide el doble que un turismo a la misma
    // distancia, y sin corregirlo el modelo marca como anómalo todo autobús
    // real. Solo con cinco o más ejemplares: con dos, el factor lo fija el ruido.
    const byClass = new Map();
    for (const s of pts) {
      const exp = a * s.y + b;
      if (exp > 1) {
        if (!byClass.has(s.cls)) byClass.set(s.cls, []);
        byClass.get(s.cls).push(s.h / exp);
      }
    }
    this.classScale.clear();
    for (const [c, ratios] of byClass) {
      if (ratios.length >= 5) this.classScale.set(c, median(ratios));
    }
    return this;
  }

  expected(y, cls = "person", x = 0) {
    return (this.a * y + this.cx * x + this.b) * (this.classScale.get(cls) ?? 1);
  }

  /** Desviaciones típicas respecto al tamaño esperado en esa fila. */
  z(y, h, cls = "person") {
    if (!this.valid) return 0;
    const k = this.classScale.get(cls) ?? 1;
    return (h - this.expected(y, cls)) / (this.sigma * k);
  }

  /** Metros por píxel en la fila `y`, vía la estatura típica de un peatón. */
  metersPerPixel(y, x = 0) {
    if (!this.valid) return NaN;
    // Ahora depende de DÓNDE está el objeto, no sólo de su fila: dos personas
    // en la misma fila y a distinto lado del cuadro no se proyectan igual si la
    // cámara tiene alabeo o el objetivo es angular.
    const hp = this.a * y + this.cx * x + this.b;
    return hp > 2 ? this.assumedStature / hp : NaN;
  }
}

function median(arr) {
  if (!arr.length) return NaN;
  const a = Float64Array.from(arr).sort();
  const m = a.length >> 1;
  return a.length % 2 ? a[m] : (a[m - 1] + a[m]) / 2;
}

/* ==========================================================================
   Física por trayectoria
   ========================================================================== */

/**
 * Estado cinemático de un objeto.
 *
 * Todo se suaviza exponencialmente antes de derivar: la caja de una
 * sustracción de fondo tiembla, y derivar ruido dos veces (aceleración) da
 * números sin ningún significado. La constante es distinta para posición y
 * aceleración a propósito --- la aceleración necesita mucha más memoria para
 * ser algo más que ruido.
 */
class Physics {
  constructor() {
    this.vx = 0; this.vy = 0;
    this.ax = 0; this.ay = 0;
    this.speedPx = 0;
    this.speedMs = NaN;
    this.heading = NaN;
    this.pathPx = 0;
    this.dispPx = 0;
    this.straightness = 1;
    this.dwellS = 0;
    this.stopped = false;
    this._x0 = null; this._y0 = null;
    this._px = null; this._py = null;
    this._t = null;
  }

  update(cx, by, tSec, scale) {
    if (this._t === null) {
      this._x0 = cx; this._y0 = by;
      this._px = cx; this._py = by; this._t = tSec;
      return;
    }
    const dt = Math.max(1e-3, tSec - this._t);
    const dx = cx - this._px, dy = by - this._py;
    const step = Math.hypot(dx, dy);
    this.pathPx += step;

    const k = Math.min(1, dt * 6);              // posición: respuesta rápida
    const nvx = dx / dt, nvy = dy / dt;
    const pvx = this.vx, pvy = this.vy;
    this.vx += (nvx - this.vx) * k;
    this.vy += (nvy - this.vy) * k;

    const ka = Math.min(1, dt * 1.5);           // aceleración: mucha más memoria
    this.ax += (((this.vx - pvx) / dt) - this.ax) * ka;
    this.ay += (((this.vy - pvy) / dt) - this.ay) * ka;

    this.speedPx = Math.hypot(this.vx, this.vy);
    this.heading = (Math.atan2(this.vy, this.vx) * 180 / Math.PI + 360) % 360;

    const mpp = scale ? scale.metersPerPixel(by, cx) : NaN;
    this.speedMs = Number.isFinite(mpp) ? this.speedPx * mpp : NaN;

    this.dispPx = Math.hypot(cx - this._x0, by - this._y0);
    this.straightness = this.pathPx > 1 ? this.dispPx / this.pathPx : 1;
    this.dwellS = tSec - (this._tStart ?? (this._tStart = tSec));
    // "Parado" se juzga contra el tamaño del propio objeto, no contra un umbral
    // en píxeles: así vale igual para alguien al fondo y para alguien delante.
    this.stopped = this.speedPx < this._stopThresh;
    this._px = cx; this._py = by; this._t = tSec;
  }

  setStopThreshold(objHeightPx) {
    this._stopThresh = Math.max(1.5, objHeightPx * 0.35);
  }

  summary() {
    return {
      speed_px_s: +this.speedPx.toFixed(1),
      speed_m_s: Number.isFinite(this.speedMs) ? +this.speedMs.toFixed(2) : null,
      speed_km_h: Number.isFinite(this.speedMs) ? +(this.speedMs * 3.6).toFixed(1) : null,
      accel_px_s2: +Math.hypot(this.ax, this.ay).toFixed(1),
      heading_deg: Number.isFinite(this.heading) ? Math.round(this.heading) : null,
      dwell_s: +this.dwellS.toFixed(1),
      straightness: +this.straightness.toFixed(3),
      stopped: this.stopped,
    };
  }
}

/* ==========================================================================
   Cerebro
   ========================================================================== */

const BRAIN_DEFAULTS = {
  personBias: 3.0,        // cuánto más se mira una tesela con personas
  detTtlMs: 4000,
  confirmPasses: 2,       // vistas necesarias para dar una caja por buena
  // Por defecto, una caja que no se reencuentra al pasar la barra se suelta.
  // Mantenerla congelada produce residuos que afirman una posición falsa.
  keepMissing: false,
  maxMisses: 2,
  confirmIou: 0.35,
  refitEveryMs: 3000,
  staturePercentile: 88,
  minStatureSamples: 12,
  eventStopS: 2.5,
  eventRunMs: 2.2,        // m/s a partir del cual se considera carrera
  loiterS: 45,
  loiterStraightness: 0.25,
};

class Brain {
  constructor(engine, opts = {}) {
    this.engine = engine;
    this.cfg = {...BRAIN_DEFAULTS, ...opts};
    this.scale = new SceneScale(opts);
    this.physics = new Map();     // trackId -> Physics
    this.stature = new Map();     // trackId -> muestras (solo pistas vivas)
    this.statureHistory = new Map(); // trackId -> resumen, acotado
    this.prints = new Map();      // trackId -> TrackFingerprint
    this._fpCanvas = null;
    this.detBoxes = [];
    this.events = [];
    this.lastFit = 0;
    this.counts = {person: 0, vehicle: 0, other: 0};
    this.fastMs = 0;
    this.frames = 0;
    this._fpsEma = 0;
    this._lastT = 0;
  }

  /* ------------------------------------------------------------------ */
  /*  Ciclo rápido                                                       */
  /* ------------------------------------------------------------------ */

  /**
   * Un frame completo del bucle rápido. No espera al detector nunca: consume
   * las cajas que éste haya dejado, sean de hace 20 ms o de hace 2 s.
   */
  async tick(video, {roi = null, capturePatches = false} = {}) {
    const t0 = performance.now();
    // El vídeo se guarda para que la huella pueda recortar del frame actual.
    this._fpVideo = video;
    this._fpRoi = roi;
    const r = await this.engine.process(video, {
      roi, capturePatches,
      detections: this.detBoxes.length ? this.detectionsForTracking() : null,
    });
    if (!r) return null;

    const tSec = t0 / 1000;
    this._updatePhysics(tSec);
    this._maybeRefit(t0);
    this._updateCounts();

    this.fastMs = performance.now() - t0;
    this.frames++;
    if (this._lastT) {
      const inst = 1000 / Math.max(1e-3, t0 - this._lastT);
      this._fpsEma = this._fpsEma ? this._fpsEma * 0.9 + inst * 0.1 : inst;
    }
    this._lastT = t0;
    return r;
  }

  _updatePhysics(tSec) {
    const eng = this.engine;
    const sx = eng.sourceW / eng.small.width;
    const sy = eng.sourceH / eng.small.height;
    const live = new Set();

    for (const t of eng.tracker.tracks) {
      if (t.hits < eng.tracker.minHits) continue;
      live.add(t.id);
      let p = this.physics.get(t.id);
      if (!p) { p = new Physics(); this.physics.set(t.id, p); }

      const cx = (t.box.x1 + t.box.x2) / 2 * sx;
      const by = t.box.y2 * sy;
      const hpx = (t.box.y2 - t.box.y1) * sy;
      p.setStopThreshold(hpx);
      p.update(cx, by, tSec, this.scale);

      if (t.klass === "person") this._accumulateStature(t, by, hpx);
      if (this._fpVideo) this._accumulateFingerprint(t, this._fpVideo, this._fpRoi);
      this._checkEvents(t, p, tSec);
    }
    // Las trayectorias muertas se sueltan: sin esto la memoria crece toda la
    // sesión con estado de objetos que ya no existen.
    // Se sueltan física y estatura de las trayectorias muertas. La primera
    // versión solo liberaba la física, así que el mapa de estaturas crecía
    // monótonamente con cada persona vista en toda la sesión.
    for (const id of [...this.physics.keys()]) if (!live.has(id)) this.physics.delete(id);
    for (const id of [...this.prints.keys()]) if (!live.has(id)) this.prints.delete(id);
    for (const id of [...this.stature.keys()]) {
      if (!live.has(id)) {
        // Antes de soltarla se guarda el resumen: ocupa unos bytes en vez de
        // cientos de muestras, y así el histórico sigue disponible.
        const s = this.statureOf(id);
        if (s) {
          this.statureHistory.set(id, s);
          if (this.statureHistory.size > 500) {
            this.statureHistory.delete(this.statureHistory.keys().next().value);
          }
        }
        this.stature.delete(id);
      }
    }
  }

  /**
   * Acumula la huella de apariencia de una trayectoria.
   *
   * Se toma una vista de cada pocos frames, no todas: las consecutivas son casi
   * idénticas y solo diluirían la media con la misma información. Lo que hace
   * fuerte a la huella es ver al objeto desde ángulos y momentos distintos.
   */
  _accumulateFingerprint(track, video, roi) {
    if (typeof fingerprint !== "function") return;
    if (track.hits % 6 !== 0) return;
    const eng = this.engine;
    const sx = eng.sourceW / eng.small.width, sy = eng.sourceH / eng.small.height;
    const ox = roi ? roi.x : 0, oy = roi ? roi.y : 0;
    const x1 = ox + track.box.x1 * sx, y1 = oy + track.box.y1 * sy;
    const w = Math.round((track.box.x2 - track.box.x1) * sx);
    const h = Math.round((track.box.y2 - track.box.y1) * sy);
    if (w < 8 || h < 16) return;

    if (!this._fpCanvas) {
      this._fpCanvas = document.createElement("canvas");
      this._fpCtx = this._fpCanvas.getContext("2d", {willReadFrequently: true});
    }
    if (this._fpCanvas.width !== w || this._fpCanvas.height !== h) {
      this._fpCanvas.width = w; this._fpCanvas.height = h;
    }
    try {
      this._fpCtx.drawImage(video, x1, y1, w, h, 0, 0, w, h);
      const img = this._fpCtx.getImageData(0, 0, w, h);
      // Silueta de la última observación, escalada a la caja. Sin ella el fondo
      // domina el histograma y todos los objetos sobre el mismo asfalto salen
      // parecidos.
      let mask = null;
      const obs = track.obs[track.obs.length - 1];
      if (obs?.m?.length && typeof decodeRLE === "function") {
        const bw = Math.max(1, Math.round(obs.b[2] - obs.b[0]));
        const bh = Math.max(1, Math.round(obs.b[3] - obs.b[1]));
        const small = decodeRLE(obs.m, bw, bh);
        mask = new Uint8Array(w * h);
        for (let y = 0; y < h; y++) {
          const sy2 = Math.min(bh - 1, Math.floor(y * bh / h));
          for (let x = 0; x < w; x++) {
            mask[y * w + x] = small[sy2 * bw + Math.min(bw - 1, Math.floor(x * bw / w))];
          }
        }
      }
      const v = fingerprint(img, mask);
      if (!v) return;
      let fp = this.prints.get(track.id);
      if (!fp) { fp = new TrackFingerprint(); this.prints.set(track.id, fp); }
      fp.add(v);
    } catch { /* recorte fuera del lienzo: se ignora esta vista */ }
  }

  fingerprintOf(trackId) {
    const fp = this.prints.get(trackId);
    return fp && fp.mature ? fp.vector : null;
  }

  /* ------------------------------------------------------------------ */
  /*  Estatura                                                           */
  /* ------------------------------------------------------------------ */

  /**
   * Acumula estatura por trayectoria en vez de medirla al final.
   *
   * Se descartan las cajas que tocan el borde: están truncadas por
   * construcción, y una caja truncada miente --- el mismo principio que rige
   * el voto de clase y el descarte en bordes de tesela.
   */
  _accumulateStature(track, baseY, hpx) {
    if (!this.scale.valid) return;
    const eng = this.engine;
    const W = eng.sourceW, H = eng.sourceH;
    const sx = eng.sourceW / eng.small.width, sy = eng.sourceH / eng.small.height;
    const x1 = track.box.x1 * sx, x2 = track.box.x2 * sx;
    const y1 = track.box.y1 * sy, y2 = track.box.y2 * sy;
    if (x1 <= 4 || y1 <= 4 || x2 >= W - 4 || y2 >= H - 4) return;
    if (hpx < 30) return;

    const cx = (track.box.x1 + track.box.x2) / 2 * sx;
    const mpp = this.scale.metersPerPixel(baseY, cx);
    if (!Number.isFinite(mpp)) return;
    const h = hpx * mpp;
    if (h < 1.0 || h > 2.4) return;

    let arr = this.stature.get(track.id);
    if (!arr) { arr = []; this.stature.set(track.id, arr); }
    arr.push(h);
    if (arr.length > 240) arr.shift();
  }

  statureOf(trackId) {
    const arr = this.stature.get(trackId);
    if (!arr || arr.length < this.cfg.minStatureSamples) return null;
    // Percentil superior, no mediana: la marcha, encorvarse y la oclusión de
    // los pies acortan la figura; casi nada la alarga. El sesgo es de un solo
    // lado, así que la mediana subestima de forma sistemática.
    const sorted = Float64Array.from(arr).sort();
    const idx = Math.min(sorted.length - 1,
      Math.round((sorted.length - 1) * this.cfg.staturePercentile / 100));
    const point = sorted[idx];
    const spread = 1.4826 * median(arr.map((v) => Math.abs(v - median(arr))));
    // Intervalo ancho a propósito: la escala está anclada en una mediana
    // poblacional asumida, y ese error es sistemático y no observable.
    const half = Math.max(0.06, 2 * spread) + 0.055 * point;
    return {m: +point.toFixed(2), lo: +(point - half).toFixed(2),
            hi: +(point + half).toFixed(2), n: arr.length, evidential: false};
  }

  /* ------------------------------------------------------------------ */
  /*  Eventos                                                            */
  /* ------------------------------------------------------------------ */

  _checkEvents(track, p, tSec) {
    const push = (kind, extra) => {
      const last = track._lastEvent?.[kind] ?? -1e9;
      if (tSec - last < 5) return;                 // no repetir en ráfaga
      (track._lastEvent ??= {})[kind] = tSec;
      this.events.unshift({kind, id: track.id, cls: track.klass, t: tSec, ...extra});
      if (this.events.length > 60) this.events.pop();
    };

    if (p.stopped && p.dwellS > this.cfg.eventStopS) push("detenido", {dwell_s: +p.dwellS.toFixed(1)});
    if (Number.isFinite(p.speedMs) && p.speedMs > this.cfg.eventRunMs && track.klass === "person") {
      push("corriendo", {speed_m_s: +p.speedMs.toFixed(1)});
    }
    if (p.dwellS > this.cfg.loiterS && p.straightness < this.cfg.loiterStraightness) {
      push("merodeo", {dwell_s: Math.round(p.dwellS), straightness: +p.straightness.toFixed(2)});
    }
    if (this.scale.valid) {
      const sy = this.engine.sourceH / this.engine.small.height;
      const z = this.scale.z(track.box.y2 * sy,
                             (track.box.y2 - track.box.y1) * sy, track.klass);
      // Fuera del plano de suelo que describe el resto de la escena: no es un
      // objeto que pase por ahí, es decorado --- una estatua, un cartel, un
      // reflejo. El detector puede estar acertando y aun así ser un falso
      // positivo perpetuo.
      if (Math.abs(z) > 6 && p.stopped) push("decorado", {z: +z.toFixed(1)});
    }
  }

  /* ------------------------------------------------------------------ */
  /*  Escena y atención                                                  */
  /* ------------------------------------------------------------------ */

  _maybeRefit(now) {
    if (now - this.lastFit < this.cfg.refitEveryMs) return;
    this.lastFit = now;
    const eng = this.engine;
    const sy = eng.sourceH / eng.small.height;
    const samples = [];
    for (const t of eng.allTubes()) {
      if (!t.obs?.length) continue;
      const ys = t.obs.map((o) => o.b[3] * sy);
      const hs = t.obs.map((o) => (o.b[3] - o.b[1]) * sy);
      const xs = t.obs.map((o) => (o.b[0] + o.b[2]) / 2 * (eng.sourceW / eng.small.width));
      samples.push({x: median(xs), y: median(ys), h: median(hs), cls: t.klass});
    }
    if (samples.length >= this.scale.minSamples) this.scale.fit(samples);
  }

  _updateCounts() {
    const c = {person: 0, vehicle: 0, other: 0};
    for (const t of this.engine.tracker.tracks) {
      if (t.hits < this.engine.tracker.minHits) continue;
      if (t.klass === "person") c.person++;
      else if (["car", "bus", "truck", "motorcycle", "bicycle"].includes(t.klass)) c.vehicle++;
      else c.other++;
    }
    this.counts = c;
  }

  /**
   * Mapa de prioridad para el detector: dónde merece la pena mirar.
   *
   * No es uniforme y no debe serlo. Una tesela donde hay personas se revisita
   * mucho antes que una de tejado, porque las personas entran y salen de escena
   * constantemente y los tejados no. `personBias` es el factor.
   */
  /** Prioridad de una tesela concreta, en coordenadas de la región. */
  tilePriority(tile, roi) {
    const eng = this.engine;
    const sx = eng.sourceW / eng.small.width, sy = eng.sourceH / eng.small.height;
    const ox = roi ? roi.x : 0, oy = roi ? roi.y : 0;
    const [x1, y1, x2, y2] = tile;
    let score = 1;
    for (const t of eng.tracker.tracks) {
      if (t.hits < eng.tracker.minHits) continue;
      const cx = ox + (t.box.x1 + t.box.x2) / 2 * sx;
      const cy = oy + (t.box.y1 + t.box.y2) / 2 * sy;
      if (cx < x1 || cx > x2 || cy < y1 || cy > y2) continue;
      score += t.klass === "person" ? this.cfg.personBias : 1;
    }
    return score;
  }

  attentionMap(tiles) {
    const eng = this.engine;
    const sx = eng.sourceW / eng.small.width, sy = eng.sourceH / eng.small.height;
    const w = this.cfg.personBias;
    return tiles.map(([x1, y1, x2, y2]) => {
      let score = 1;
      for (const t of eng.tracker.tracks) {
        const cx = (t.box.x1 + t.box.x2) / 2 * sx;
        const cy = (t.box.y1 + t.box.y2) / 2 * sy;
        if (cx < x1 || cx > x2 || cy < y1 || cy > y2) continue;
        score += t.klass === "person" ? w : 1;
      }
      return score;
    });
  }

  /** Cajas del detector, fundidas entre pasadas. */
  mergeDetections(result) {
    const now = performance.now();
    const inside = (b, t) =>
      b[0] >= t[0] - 2 && b[1] >= t[1] - 2 && b[2] <= t[2] + 2 && b[3] <= t[3] + 2;
    const iou = (a, b) => {
      const x1 = Math.max(a[0], b[0]), y1 = Math.max(a[1], b[1]);
      const x2 = Math.min(a[2], b[2]), y2 = Math.min(a[3], b[3]);
      if (x2 <= x1 || y2 <= y1) return 0;
      const i = (x2 - x1) * (y2 - y1);
      return i / ((a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - i);
    };

    // Las cajas de la franja barrida se REFRESCAN, no se sustituyen.
    //
    // Antes se descartaba todo lo que caía dentro de las teselas recién
    // analizadas y se reemplazaba por lo que el detector acabara de encontrar.
    // El efecto es que una persona a la que el detector falla en una pasada
    // --- porque se giró, porque otro la tapó medio segundo --- desaparece de
    // golpe y vuelve a aparecer al barrido siguiente. Parpadeo puro.
    //
    // Lo correcto es tratar la pasada como una ACTUALIZACIÓN: lo que se
    // reencuentra se refresca en su sitio, y lo que no se reencuentra pierde
    // confianza pero sigue vivo hasta agotar su margen. Un objeto solo
    // desaparece cuando lleva varios barridos sin confirmarse, que es cuando
    // de verdad se ha ido.
    const barrido = (d) => result.tiles.some((t) => inside(d.b, t));
    const vivas = this.detBoxes.filter((d) => now - d.t < this.cfg.detTtlMs);

    // Confirmación temporal.
    //
    // Un modelo pequeño a umbral bajo inventa, y lo inventado **parpadea**: la
    // alucinación cambia de sitio en cada pasada mientras un objeto real sigue
    // ahí. Contar cuántas veces se ha vuelto a ver una caja en el mismo sitio
    // separa las dos cosas sin tocar el modelo.
    //
    // La primera vista no se descarta --- se marca como no confirmada --- para
    // que un objeto que entra en escena no tenga que esperar dos pasadas a
    // aparecer. Quien consuma decide qué hacer con las no confirmadas.
    const usadas = new Set();
    const fresh = [];
    for (const d of result.dets) {
      let mejor = null, mejorIoU = this.cfg.confirmIou;
      for (const o of vivas) {
        if (o.c !== d.c || usadas.has(o)) continue;
        const s2 = iou(o.b, d.b);
        if (s2 > mejorIoU) { mejorIoU = s2; mejor = o; }
      }
      if (mejor) usadas.add(mejor);
      fresh.push({
        ...d, t: now,
        seen: mejor ? mejor.seen + 1 : 1,
        misses: 0,
        confirmed: mejor ? mejor.seen + 1 >= this.cfg.confirmPasses : false,
      });
    }

    // Las que estaban en la franja y no se reencontraron se SUELTAN.
    //
    // La versión anterior las mantenía unos barridos "por si acaso", con la
    // caja congelada donde estuvieran. En una escena donde todo el mundo anda,
    // eso deja recuadros pegados al suelo mientras la persona sigue su camino:
    // basura visual que además **miente sobre dónde está**.
    //
    // El error de fondo era pedirle al detector que hiciera de tracker. El
    // detector dice QUÉ hay y lo dice de tarde en tarde; quien sabe DÓNDE está
    // cada cosa en cada frame es el seguimiento. Rellenar el hueco entre
    // barridos congelando la última caja no es persistencia, es un residuo.
    //
    // Con `keepMissing` se puede recuperar el comportamiento anterior, pero el
    // valor por defecto es soltarlas: más honesto un parpadeo ocasional que un
    // recuadro que afirma algo falso.
    const supervivientes = [];
    for (const o of vivas) {
      if (usadas.has(o)) continue;
      if (!barrido(o)) { supervivientes.push(o); continue; }
      if (!this.cfg.keepMissing) continue;      // no se reencontró: fuera
      const misses = (o.misses || 0) + 1;
      if (misses <= this.cfg.maxMisses) {
        supervivientes.push({...o, misses, stale: true});
      }
    }

    this.detBoxes = supervivientes.concat(fresh);
    this.confirmedCount = this.detBoxes.filter((d) => d.confirmed).length;
    this.staleCount = this.detBoxes.filter((d) => d.stale).length;
  }

  /** Cajas para alimentar al tracker: solo las que han pasado la confirmación. */
  detectionsForTracking() {
    return this.cfg.confirmPasses > 1
      ? this.detBoxes.filter((d) => d.confirmed || d.seen >= this.cfg.confirmPasses)
      : this.detBoxes;
  }

  /* ------------------------------------------------------------------ */

  get fps() { return Math.round(this._fpsEma); }

  state() {
    const tracks = [];
    for (const t of this.engine.tracker.tracks) {
      if (t.hits < this.engine.tracker.minHits) continue;
      const p = this.physics.get(t.id);
      tracks.push({
        id: t.id, cls: t.klass,
        physics: p ? p.summary() : null,
        stature: t.klass === "person" ? this.statureOf(t.id) : null,
      });
    }
    return {
      fps: this.fps,
      fast_ms: +this.fastMs.toFixed(1),
      counts: this.counts,
      scale: this.scale.valid
        ? {a: +this.scale.a.toFixed(4), cx: +this.scale.cx.toFixed(5),
           uses_x: this.scale.usesX,
           colinearity: +this.scale.colinearity.toFixed(2),
           b: +this.scale.b.toFixed(1),
           sigma_px: +this.scale.sigma.toFixed(1), n: this.scale.n,
           y_span_px: Math.round(this.scale.ySpan),
           class_scale: Object.fromEntries(this.scale.classScale)}
        : {valid: false, n: this.scale.n,
           y_span_px: Math.round(this.scale.ySpan),
           reason: "hacen falta objetos a distintas distancias"},
      det_boxes: this.detBoxes.length,
      det_confirmed: this.confirmedCount || 0,
      det_stale: this.staleCount || 0,
      tracks,
      events: this.events.slice(0, 12),
    };
  }
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = {Brain, SceneScale, Physics, solveSmall};
}
