/*
 * ARGOS · comportamiento
 * ---------------------------------------------------------------------------
 * Modelo de lo que es NORMAL en esta escena, aprendido de la propia escena.
 *
 * Los sistemas de este tipo --- Frigate, BriefCam, XProtect --- disparan con
 * reglas que configura una persona: dibuja una línea, elige una dirección,
 * pon un umbral de velocidad. Eso tiene dos problemas que no se arreglan con
 * mejor interfaz:
 *
 *   1. Alguien tiene que saber de antemano qué es raro. En una calle que no
 *      conoce, no lo sabe.
 *   2. Un umbral fijo no distingue entre "30 km/h en una autovía" y "30 km/h
 *      en una acera". La misma cifra, significados opuestos.
 *
 * Aquí no se configura nada. La escena se divide en celdas y cada una acumula
 * de las propias trayectorias:
 *
 *   · **Flujo dominante** --- histograma circular de rumbos. En una calzada
 *     converge a uno o dos picos; en una plaza queda plano, y un histograma
 *     plano es información: significa que ahí la dirección no dice nada.
 *   · **Distribución de velocidad** --- mediana y MAD, en m/s reales gracias a
 *     la escala aprendida, no en píxeles.
 *   · **Ocupación por clase** --- qué pasa por aquí normalmente. Un vehículo
 *     sobre la acera es anómalo aunque vaya despacio y en la dirección buena.
 *
 * Y la pieza que hace que esto no sea un generador de falsas alarmas: una
 * celda con pocas muestras **no opina**. Sin esa reserva, el primer día todo
 * es anómalo, el operador desactiva las alertas, y el sistema deja de existir.
 */

"use strict";

const BEHAVIOUR_DEFAULTS = {
  cell: 96,                // lado de celda en píxeles de fuente
  dirBins: 12,             // resolución del histograma de rumbos (30° cada uno)
  minSamples: 25,          // por debajo, la celda no tiene opinión
  minDirConcentration: 0.35, // por debajo, el flujo no es direccional
  speedFloor: 0.4,         // m/s: por debajo, la dirección es ruido
  decay: 0.999,            // olvido lento: el tráfico de hoy pesa algo más
  anomalyWeights: {direction: 1.0, speed: 0.9, klass: 1.2, novel: 0.5},
};

/** Celda del mapa de comportamiento. */
class Cell {
  constructor(bins) {
    this.dir = new Float32Array(bins);
    this.speeds = [];
    this.klass = new Map();
    this.n = 0;
  }

  observe(headingDeg, speedMs, klass, bins, floor) {
    this.n++;
    if (Number.isFinite(speedMs)) {
      this.speeds.push(speedMs);
      // Cota dura: una celda de una avenida acumularía millones de muestras.
      // Un reservorio de mil describe la distribución igual de bien.
      if (this.speeds.length > 1000) this.speeds.shift();
    }
    // Un objeto casi parado tiene rumbo aleatorio; meterlo en el histograma
    // solo lo aplana y destruye la señal direccional de los que sí se mueven.
    if (Number.isFinite(headingDeg) && (speedMs ?? 0) >= floor) {
      const b = Math.floor(((headingDeg % 360) + 360) % 360 / (360 / bins)) % bins;
      this.dir[b] += 1;
    }
    if (klass) this.klass.set(klass, (this.klass.get(klass) || 0) + 1);
  }
}

const median = (a) => {
  if (!a.length) return NaN;
  const s = Float64Array.from(a).sort();
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : (s[m - 1] + s[m]) / 2;
};
const mad = (a) => {
  const m = median(a);
  return median(a.map((v) => Math.abs(v - m)));
};

const quantile = (sorted, p) => {
  if (!sorted.length) return NaN;
  const i = (sorted.length - 1) * p;
  const lo = Math.floor(i), hi = Math.ceil(i);
  return lo === hi ? sorted[lo] : sorted[lo] + (sorted[hi] - sorted[lo]) * (i - lo);
};

/** Rango normal de la celda y su anchura, para puntuar de forma acotada. */
function quantiles(values) {
  const s = Float64Array.from(values.filter(Number.isFinite)).sort();
  if (s.length < 12) return null;
  const p05 = quantile(s, 0.05), p95 = quantile(s, 0.95);
  return {
    p05, p50: quantile(s, 0.5), p95,
    // Suelo relativo a la propia mediana: en una celda donde todo va a 0,5 m/s
    // una desviación de 0,3 es enorme; en una donde se va a 15, es nada.
    width: Math.max(0.25, (p95 - p05) / 2, quantile(s, 0.5) * 0.25),
    n: s.length,
  };
}

/** Mediana de una ventana centrada, ignorando huecos. */
function localMedian(arr, i, win) {
  if (!arr || !arr.length) return NaN;
  const half = win >> 1;
  const w = [];
  for (let k = i - half; k <= i + half; k++) {
    const v = arr[k];
    if (Number.isFinite(v)) w.push(v);
  }
  return w.length ? median(w) : NaN;
}

class BehaviourModel {
  constructor(width, height, opts = {}) {
    this.cfg = {...BEHAVIOUR_DEFAULTS, ...opts};
    this.w = width;
    this.h = height;
    this.cols = Math.max(1, Math.ceil(width / this.cfg.cell));
    this.rows = Math.max(1, Math.ceil(height / this.cfg.cell));
    this.cells = new Map();
    this.total = 0;
  }

  _key(x, y) {
    const c = Math.min(this.cols - 1, Math.max(0, Math.floor(x / this.cfg.cell)));
    const r = Math.min(this.rows - 1, Math.max(0, Math.floor(y / this.cfg.cell)));
    return r * this.cols + c;
  }

  _cell(k, create = false) {
    let c = this.cells.get(k);
    if (!c && create) { c = new Cell(this.cfg.dirBins); this.cells.set(k, c); }
    return c;
  }

  /**
   * Aprende de una trayectoria terminada.
   *
   * Se recorre punto a punto porque un objeto atraviesa varias celdas y cada
   * una debe aprender lo que pasó EN ELLA. Resumir el tubo a un punto medio
   * atribuiría a una sola celda un comportamiento que ocurrió en cinco.
   */
  learn(track) {
    const pts = track.path || [];
    if (pts.length < 4) return;
    const cls = track.cls || track.klass;
    const speeds = track.speeds || [];
    for (let i = 1; i < pts.length; i++) {
      const [x0, y0] = pts[i - 1];
      const [x1, y1] = pts[i];
      const dx = x1 - x0, dy = y1 - y0;
      if (dx === 0 && dy === 0) continue;
      const heading = (Math.atan2(dy, dx) * 180 / Math.PI + 360) % 360;
      const speed = speeds[i] ?? track.speedMs ?? NaN;
      const c = this._cell(this._key(x1, y1), true);
      c.observe(heading, speed, cls, this.cfg.dirBins, this.cfg.speedFloor);
      this.total++;
    }
  }

  /**
   * Concentración direccional: longitud del vector medio circular.
   *
   * Es 1 cuando todo va en la misma dirección y 0 cuando los rumbos se
   * reparten por igual. La media aritmética de ángulos no sirve --- 350° y 10°
   * promedian 180°, que es exactamente la dirección contraria a la real.
   */
  /**
   * Descompone el histograma en MODOS, no en una sola media.
   *
   * Una celda de calzada de doble sentido tiene dos picos opuestos. El vector
   * medio circular los cancela y devuelve concentración baja --- medido en el
   * vídeo real, la celda con más tránsito (269 observaciones) daba 0,17, y el
   * histograma crudo mostraba dos picos limpios en sectores opuestos.
   *
   * Eso no es "sin dirección", es "dos direcciones", y confundirlas cuesta
   * caro en ambos sentidos: en esa celda no se detecta a nadie a contramano
   * porque el modelo cree que ahí vale todo, y a la vez cualquier rumbo se
   * considera normal.
   *
   * La descomposición es un agrupamiento de picos sobre el histograma circular:
   * se buscan máximos locales por encima de un suelo y se les asigna la masa
   * contigua. No hace falta ajustar una mezcla de von Mises --- con doce
   * sectores y cientos de muestras, los picos se ven directamente.
   */
  _modes(cell, {minShare = 0.15} = {}) {
    const bins = this.cfg.dirBins;
    const d = cell.dir;
    let total = 0;
    for (let b = 0; b < bins; b++) total += d[b];
    if (total < 1) return [];

    // Suavizado circular ligero: el ruido de cuantización parte un pico real
    // en dos sectores adyacentes y lo haría contar como dos modos.
    const sm = new Float32Array(bins);
    for (let b = 0; b < bins; b++) {
      sm[b] = 0.25 * d[(b - 1 + bins) % bins] + 0.5 * d[b] + 0.25 * d[(b + 1) % bins];
    }

    const peaks = [];
    for (let b = 0; b < bins; b++) {
      const prev = sm[(b - 1 + bins) % bins], next = sm[(b + 1) % bins];
      if (sm[b] >= prev && sm[b] > next && sm[b] / total >= minShare * 0.5) {
        peaks.push(b);
      }
    }
    if (!peaks.length) return [];

    // Cada sector se asigna al pico más cercano en distancia circular.
    const modes = peaks.map((b) => ({peak: b, mass: 0, sx: 0, sy: 0}));
    for (let b = 0; b < bins; b++) {
      if (!d[b]) continue;
      let best = 0, bestDist = Infinity;
      for (let i = 0; i < peaks.length; i++) {
        let dist = Math.abs(b - peaks[i]);
        if (dist > bins / 2) dist = bins - dist;
        if (dist < bestDist) { bestDist = dist; best = i; }
      }
      const ang = (b + 0.5) * (2 * Math.PI / bins);
      modes[best].mass += d[b];
      modes[best].sx += d[b] * Math.cos(ang);
      modes[best].sy += d[b] * Math.sin(ang);
    }

    return modes
      .filter((m) => m.mass / total >= minShare)
      .map((m) => ({
        heading: (Math.atan2(m.sy, m.sx) * 180 / Math.PI + 360) % 360,
        // Concentración DENTRO del modo: mide si ese carril es coherente,
        // no si la celda entera lo es.
        concentration: Math.hypot(m.sx, m.sy) / m.mass,
        share: m.mass / total,
        n: m.mass,
      }))
      .sort((a, b) => b.share - a.share);
  }

  _flow(cell) {
    const bins = this.cfg.dirBins;
    let sx = 0, sy = 0, n = 0;
    for (let b = 0; b < bins; b++) {
      const w = cell.dir[b];
      if (!w) continue;
      const ang = (b + 0.5) * (2 * Math.PI / bins);
      sx += w * Math.cos(ang);
      sy += w * Math.sin(ang);
      n += w;
    }
    if (!n) return {concentration: 0, heading: NaN, n: 0};
    const r = Math.hypot(sx, sy) / n;
    return {
      concentration: r,
      heading: (Math.atan2(sy, sx) * 180 / Math.PI + 360) % 360,
      n,
    };
  }

  /** Lo que la celda sabe, o `null` si aún no sabe lo suficiente. */
  profile(x, y) {
    const c = this._cell(this._key(x, y));
    if (!c || c.n < this.cfg.minSamples) return null;
    const flow = this._flow(c);
    const modes = this._modes(c);
    const sp = c.speeds;
    const total = [...c.klass.values()].reduce((p, q) => p + q, 0) || 1;
    return {
      n: c.n,
      flow,
      modes,
      // Cuantiles, no mediana ± MAD.
      //
      // La distribución de velocidad en tráfico está sesgada a la derecha:
      // muchos objetos lentos o detenidos y una cola de rápidos. Una z
      // simétrica sobre eso marca la cola normal como extrema --- medido, el
      // tráfico legítimo daba 7,2 sigmas de mediana y hasta 50,9, cifras que
      // no significan nada y que además ahogaban al término de dirección, que
      // es el que sí discrimina.
      speed: sp.length >= 12 ? quantiles(sp) : null,
      classes: Object.fromEntries(
        [...c.klass].map(([k, v]) => [k, v / total])),
    };
  }

  /* ------------------------------------------------------------------ */

  /**
   * Puntúa cuánto se aparta una trayectoria de lo normal en su recorrido.
   *
   * Devuelve un desglose, no un número suelto: un operador necesita saber
   * *por qué* algo se ha marcado, y una puntuación sin explicación no se puede
   * discutir ni corregir. Es la diferencia entre una alerta y una acusación.
   */
  score(track) {
    const pts = track.path || [];
    const cls = track.cls || track.klass;
    const W = this.cfg.anomalyWeights;
    const reasons = [];
    const dirSamples = [];
    let spdMax = 0, spdWhere = null;
    let clsMin = 1, clsWhere = null;
    let unknown = 0, seen = 0;

    for (let i = 1; i < pts.length; i++) {
      const [x0, y0] = pts[i - 1];
      const [x1, y1] = pts[i];
      const p = this.profile(x1, y1);
      seen++;
      if (!p) { unknown++; continue; }

      const dx = x1 - x0, dy = y1 - y0;
      // Mediana local en vez de la velocidad instantánea: la diferencia entre
      // dos posiciones consecutivas es muy ruidosa, y comparar ruido contra
      // una distribución aprendida produce anomalías inventadas.
      const speed = localMedian(track.speeds, i, 5);

      // --- dirección, contra el MODO MÁS PARECIDO ---
      //
      // Un vehículo circula por su carril, no por el promedio de los dos. Se
      // compara contra el modo al que más se acerca y se pondera por la
      // coherencia de ESE modo y por su peso: ir contra un carril que
      // concentra el 60 % del tránsito pesa más que ir contra uno marginal.
      if (p.modes.length && (dx || dy) && (speed ?? 1) >= this.cfg.speedFloor) {
        const heading = (Math.atan2(dy, dx) * 180 / Math.PI + 360) % 360;
        let best = null;
        for (const mo of p.modes) {
          if (mo.concentration < this.cfg.minDirConcentration) continue;
          let diff = Math.abs(heading - mo.heading) % 360;
          if (diff > 180) diff = 360 - diff;
          if (!best || diff < best.diff) best = {diff, mo};
        }
        if (best) {
          dirSamples.push((best.diff / 180) * best.mo.concentration
                          * (0.5 + best.mo.share));
        }
      }

      // --- velocidad, fuera del rango normal de la celda ---
      //
      // Se mide cuánto se sale del intervalo [p05, p95] observado ahí, en
      // unidades de la anchura de ese intervalo. Dentro del rango la
      // aportación es exactamente cero: circular a la velocidad de la zona no
      // es una anomalía pequeña, es ninguna.
      if (p.speed && Number.isFinite(speed)) {
        const over = speed > p.speed.p95 ? speed - p.speed.p95
                   : speed < p.speed.p05 ? p.speed.p05 - speed : 0;
        const z = over / p.speed.width;
        if (z > spdMax) { spdMax = z; spdWhere = [x1, y1]; }
      }

      // --- clase ---
      if (cls) {
        const share = p.classes[cls] ?? 0;
        if (share < clsMin) { clsMin = share; clsWhere = [x1, y1]; }
      }
    }

    // Sin celdas maduras no hay juicio posible. Devolver 0 y decirlo es más
    // honesto que devolver una puntuación construida sobre nada.
    if (seen === 0 || unknown === seen) {
      return {score: 0, mature: false, reasons: ["escena aún sin aprender"],
              coverage: 0};
    }

    // Cuantil alto, no media.
    //
    // Promediar diluye: un vehículo que circula a contramano por un tramo
    // sostenido pero cuyo recorrido pasa también por celdas ambiguas --- un
    // cruce, una zona de poco tránsito --- ve su puntuación arrastrada hacia
    // cero por los puntos que no dicen nada.
    //
    // El percentil 75 responde a la pregunta correcta: "¿hubo un tramo claro
    // en contra?", no "¿fue en contra de media?". Se exige además una longitud
    // mínima de evidencia para que un par de puntos sueltos no basten.
    let dirScore = 0;
    if (dirSamples.length >= 4) {
      const s2 = Float64Array.from(dirSamples).sort();
      dirScore = quantile(s2, 0.75);
    } else if (dirSamples.length) {
      // Con poca evidencia se atenúa en vez de descartar: algo se sabe, pero
      // no lo suficiente para afirmarlo con la misma fuerza.
      const s2 = Float64Array.from(dirSamples).sort();
      dirScore = quantile(s2, 0.75) * (dirSamples.length / 4);
    }
    // Acotado: por encima de tres anchuras ya está tan fuera que más no añade
    // información, y dejarlo crecer permitiría que un solo punto con velocidad
    // mal estimada dominase toda la puntuación.
    const spdScore = Math.min(1, spdMax / 3);
    const clsScore = clsMin < 0.02 ? 1 - clsMin * 50 : 0;
    const novScore = unknown / seen;

    if (dirScore > 0.45) {
      reasons.push(`circula contra el flujo dominante en un tramo sostenido `
                   + `(${(dirScore * 100).toFixed(0)}%)`);
    }
    if (spdMax > 1.5) {
      reasons.push(`velocidad fuera del rango normal de la zona `
                   + `(${spdMax.toFixed(1)}x el margen habitual)`);
    }
    if (clsScore > 0.5 && cls) {
      reasons.push(`'${cls}' apenas transita por ahí (${(clsMin * 100).toFixed(1)}%)`);
    }
    if (novScore > 0.6) {
      reasons.push("recorre zonas sin historial");
    }

    const score = Math.min(1,
      (dirScore * W.direction + spdScore * W.speed
       + clsScore * W.klass + novScore * W.novel)
      / (W.direction + W.speed + W.klass + W.novel));

    return {score, mature: true, reasons,
            coverage: 1 - novScore,
            detail: {direction: +dirScore.toFixed(3),
                     direction_samples: dirSamples.length,
                     speed_excess: +spdMax.toFixed(2),
                     class_share: +clsMin.toFixed(3), unknown_frac: +novScore.toFixed(2)}};
  }

  /* ------------------------------------------------------------------ */

  /**
   * Qué señales sirven EN ESTA escena.
   *
   * Una calle y una plaza no se juzgan igual, y el modelo puede saber cuál es
   * sin que nadie se lo diga. Medido:
   *
   *   calle (Alcalá)          2 modos opuestos y limpios  -> separacion 6,4x
   *   plaza (Puerta del Sol)  4 modos al ~25 % cada uno   -> separacion 1,1x
   *
   * En la plaza los peatones caminan en todas las direcciones: **no existe el
   * contraflujo**, y un sistema que lo marcase generaría miles de falsas
   * alarmas. Que la separación caiga a 1,1 no es un fallo del modelo, es el
   * modelo diciendo la verdad sobre el sitio.
   *
   * Publicarlo importa: sin esto, un operador vería puntuaciones bajas y
   * concluiría que el sistema no funciona, cuando lo que ocurre es que esa
   * señal no aplica ahí. Y le deja claro en qué señales sí puede apoyarse.
   */
  informativeness() {
    let dirUsable = 0, speedUsable = 0, classUsable = 0, mature = 0;
    let modeSum = 0, topShareSum = 0;

    for (const c of this.cells.values()) {
      if (c.n < this.cfg.minSamples) continue;
      mature++;
      const modes = this._modes(c);
      modeSum += modes.length;
      if (modes.length) topShareSum += modes[0].share;

      // La dirección informa cuando hay POCOS modos y uno domina. Con cuatro
      // repartidos al 25 % la respuesta "vas en contra" no significa nada.
      if (modes.length && modes.length <= 2 && modes[0].share >= 0.55
          && modes[0].concentration >= this.cfg.minDirConcentration) {
        dirUsable++;
      }
      const sp = c.speeds.filter(Number.isFinite);
      if (sp.length >= 12) {
        const q = quantiles(sp);
        // Si el rango normal es enorme comparado con la mediana, cualquier
        // velocidad cabe dentro y el término no discrimina.
        if (q && q.p50 > 0 && (q.p95 - q.p05) / q.p50 < 3) speedUsable++;
      }
      // La clase informa si la celda está dominada por una: si por ahí pasa de
      // todo, ver algo distinto no dice nada.
      const tot = [...c.klass.values()].reduce((p, q2) => p + q2, 0);
      if (tot > 0 && Math.max(...c.klass.values()) / tot >= 0.85) classUsable++;
    }

    const frac = (x) => (mature ? +(x / mature).toFixed(2) : 0);
    return {
      cells_mature: mature,
      modes_avg: mature ? +(modeSum / mature).toFixed(2) : 0,
      top_mode_share_avg: mature ? +(topShareSum / mature).toFixed(2) : 0,
      direction: frac(dirUsable),
      speed: frac(speedUsable),
      klass: frac(classUsable),
      // Lectura para el operador, no para el desarrollador.
      scene_type: mature === 0 ? "sin aprender"
        : frac(dirUsable) >= 0.5 ? "flujo canalizado (calle, pasillo, carril)"
        : frac(dirUsable) >= 0.2 ? "flujo mixto"
        : "espacio abierto (plaza): la direccion no discrimina",
    };
  }

  /** Estado del aprendizaje, para que el operador sepa de qué puede fiarse. */
  maturity() {
    let mature = 0, directional = 0;
    for (const c of this.cells.values()) {
      if (c.n < this.cfg.minSamples) continue;
      mature++;
      // Una celda es direccional si TIENE modos coherentes, aunque sean dos.
      if (this._modes(c).some((m) => m.concentration >= this.cfg.minDirConcentration)) {
        directional++;
      }
    }
    const grid = this.cols * this.rows;
    return {
      cells_total: grid,
      cells_touched: this.cells.size,
      cells_mature: mature,
      cells_directional: directional,
      observations: this.total,
      ready: mature >= Math.max(4, grid * 0.05),
    };
  }

  /** Exporta el mapa para dibujarlo o persistirlo. */
  export() {
    const out = [];
    for (const [k, c] of this.cells) {
      if (c.n < this.cfg.minSamples) continue;
      const flow = this._flow(c);
      out.push({
        col: k % this.cols, row: Math.floor(k / this.cols),
        n: c.n,
        heading: Number.isFinite(flow.heading) ? +flow.heading.toFixed(1) : null,
        concentration: +flow.concentration.toFixed(3),
        modes: this._modes(c).map((m) => ({
          heading: +m.heading.toFixed(1),
          concentration: +m.concentration.toFixed(3),
          share: +m.share.toFixed(3),
        })),
        speed_median: c.speeds.length ? +median(c.speeds).toFixed(2) : null,
        classes: Object.fromEntries(c.klass),
      });
    }
    return {cell: this.cfg.cell, cols: this.cols, rows: this.rows, cells: out};
  }

  static import(data, width, height) {
    const m = new BehaviourModel(width, height, {cell: data.cell});
    for (const c of data.cells) {
      const cell = new Cell(m.cfg.dirBins);
      cell.n = c.n;
      if (c.heading !== null) {
        // Se reconstruye un histograma equivalente en media y concentración.
        // No es el original --- eso exigiría guardar todos los bins --- pero
        // conserva lo único que se consulta.
        const bins = m.cfg.dirBins;
        const b = Math.floor(c.heading / (360 / bins)) % bins;
        cell.dir[b] = c.n * c.concentration;
        const spread = c.n * (1 - c.concentration) / (bins - 1);
        for (let i = 0; i < bins; i++) if (i !== b) cell.dir[i] = spread;
      }
      if (c.speed_median !== null) cell.speeds = new Array(12).fill(c.speed_median);
      for (const [k, v] of Object.entries(c.classes || {})) cell.klass.set(k, v);
      m.cells.set(c.row * m.cols + c.col, cell);
      m.total += c.n;
    }
    return m;
  }
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = {BehaviourModel, BEHAVIOUR_DEFAULTS};
}
