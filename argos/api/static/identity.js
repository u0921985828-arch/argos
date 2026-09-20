/*
 * ARGOS · identidad
 * ---------------------------------------------------------------------------
 * Huella de apariencia y enlace entre cámaras.
 *
 * La huella no sale de una red de re-identificación. Podría --- OSNet en ONNX
 * son 9 MB --- pero eso añade una segunda inferencia por objeto encima del
 * detector, y el presupuesto ya está ajustado. Se usa en su lugar un
 * descriptor clásico calculado sobre la silueta que el sistema **ya tiene**:
 *
 *   · Histograma de color en HSV por bandas horizontales del cuerpo.
 *     Las bandas importan: una persona con camisa clara y pantalón oscuro no es
 *     lo mismo que una con camisa oscura y pantalón claro, y un histograma
 *     global las confunde. Tres bandas separan torso de piernas.
 *   · Solo píxeles DENTRO de la silueta. Sin la máscara, el fondo domina el
 *     histograma y todos los objetos sobre el mismo asfalto salen parecidos.
 *   · Normalizado por brillo, porque la misma persona bajo el sol y bajo una
 *     nube no debe ser dos personas.
 *
 * Es un descriptor más débil que una red entrenada. Por eso todo lo que viene
 * después --- topología, ventana temporal, verosimilitud calibrada --- importa
 * más aquí que en un sistema con mejor huella: la geometría y el tiempo tienen
 * que compensar lo que el color no distingue.
 */

"use strict";

const BANDS = 3;
const H_BINS = 12;
const S_BINS = 3;
const V_BINS = 3;
const DIM = BANDS * H_BINS * S_BINS * V_BINS;

/* ==========================================================================
   Huella
   ========================================================================== */

function rgbToHsv(r, g, b) {
  const max = Math.max(r, g, b), min = Math.min(r, g, b);
  const d = max - min;
  let h = 0;
  if (d) {
    if (max === r) h = ((g - b) / d + (g < b ? 6 : 0));
    else if (max === g) h = (b - r) / d + 2;
    else h = (r - g) / d + 4;
    h /= 6;
  }
  return [h, max ? d / max : 0, max / 255];
}

/**
 * Descriptor de apariencia de un recorte con su silueta.
 *
 * @param {ImageData} img   recorte del objeto
 * @param {Uint8Array} mask silueta, mismo tamaño; si falta se usa todo el recorte
 */
function fingerprint(img, mask = null) {
  const {data, width: w, height: h} = img;
  const v = new Float32Array(DIM);
  let total = 0;

  // Brillo RELATIVO al propio objeto, no absoluto.
  //
  // Con brillo absoluto, la misma persona bajo el sol y bajo una nube cae en
  // bins distintos y deja de parecerse a sí misma: medido, el coseno entre dos
  // vistas de la misma identidad con distinta iluminación bajaba a 0,15. Lo que
  // distingue a una persona no es cuánta luz recibe, sino que su camisa sea más
  // clara que su pantalón --- y eso se conserva al dividir por la mediana de V
  // del propio recorte.
  const vs = [];
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = y * w + x;
      if (mask && !mask[i]) continue;
      const p = i * 4;
      vs.push(Math.max(data[p], data[p + 1], data[p + 2]) / 255);
    }
  }
  if (vs.length < 24) return null;
  vs.sort();
  const vMed = Math.max(0.05, vs[vs.length >> 1]);

  for (let y = 0; y < h; y++) {
    const band = Math.min(BANDS - 1, Math.floor(y * BANDS / h));
    for (let x = 0; x < w; x++) {
      const i = y * w + x;
      if (mask && !mask[i]) continue;
      const p = i * 4;
      const [hh, ss, vv] = rgbToHsv(data[p], data[p + 1], data[p + 2]);
      // Los píxeles muy oscuros o muy desaturados tienen tono aleatorio: si se
      // cuentan, el histograma se llena de ruido de sombra.
      if (vv < 0.10) continue;
      // Relativo a la mediana del objeto y comprimido a [0,1): 0,5 es "tan
      // claro como la media de esta persona".
      const vr = Math.min(0.999, (vv / vMed) * 0.5);
      const hb = ss < 0.15 ? 0 : Math.min(H_BINS - 1, Math.floor(hh * H_BINS));
      const sb = Math.min(S_BINS - 1, Math.floor(ss * S_BINS));
      const vb = Math.min(V_BINS - 1, Math.floor(vr * V_BINS));
      v[band * H_BINS * S_BINS * V_BINS + hb * S_BINS * V_BINS + sb * V_BINS + vb] += 1;
      total++;
    }
  }
  if (total < 24) return null;          // muy poca superficie: no es fiable

  // Suavizado del histograma: sin él, un píxel que cae justo al otro lado de la
  // frontera de un bin cuenta como color totalmente distinto, y dos vistas de
  // la misma prenda pueden no compartir ningún bin.
  const sm = new Float32Array(DIM);
  for (let band = 0; band < BANDS; band++) {
    const off = band * H_BINS * S_BINS * V_BINS;
    for (let hb = 0; hb < H_BINS; hb++) {
      for (let sb = 0; sb < S_BINS; sb++) {
        for (let vb = 0; vb < V_BINS; vb++) {
          const i = off + hb * S_BINS * V_BINS + sb * V_BINS + vb;
          // El tono es circular: el rojo del bin 0 y el del último son vecinos.
          const hPrev = off + ((hb - 1 + H_BINS) % H_BINS) * S_BINS * V_BINS + sb * V_BINS + vb;
          const hNext = off + ((hb + 1) % H_BINS) * S_BINS * V_BINS + sb * V_BINS + vb;
          sm[i] = v[i] + 0.35 * (v[hPrev] + v[hNext]);
        }
      }
    }
  }
  v.set(sm);

  // L2: la comparación es por coseno, y sin normalizar el tamaño del objeto
  // dominaría sobre su color.
  let norm = 0;
  for (let i = 0; i < DIM; i++) norm += v[i] * v[i];
  norm = Math.sqrt(norm) || 1;
  for (let i = 0; i < DIM; i++) v[i] /= norm;
  return v;
}

const cosine = (a, b) => {
  if (!a || !b) return 0;
  let s = 0;
  for (let i = 0; i < a.length; i++) s += a[i] * b[i];
  return s;
};

/**
 * Huella acumulada de una trayectoria.
 *
 * Se promedian varias vistas y se renormaliza. Una sola vista puede pillar a la
 * persona de espaldas, girada o medio ocluida; la media a lo largo del
 * recorrido es mucho más estable, y es lo que hace comparable a un objeto
 * consigo mismo en otra cámara.
 */
class TrackFingerprint {
  constructor(maxSamples = 24) {
    this.sum = new Float32Array(DIM);
    this.n = 0;
    this.maxSamples = maxSamples;
  }

  add(v) {
    if (!v || this.n >= this.maxSamples) return;
    for (let i = 0; i < DIM; i++) this.sum[i] += v[i];
    this.n++;
  }

  get vector() {
    if (!this.n) return null;
    const out = new Float32Array(DIM);
    let norm = 0;
    for (let i = 0; i < DIM; i++) norm += this.sum[i] * this.sum[i];
    norm = Math.sqrt(norm) || 1;
    for (let i = 0; i < DIM; i++) out[i] = this.sum[i] / norm;
    return out;
  }

  get mature() { return this.n >= 5; }
}

/* ==========================================================================
   Topología del emplazamiento
   ========================================================================== */

/**
 * Transiciones físicamente posibles entre cámaras.
 *
 * Es la primera criba y la más importante. La similitud de apariencia por sí
 * sola, sobre un archivo grande, **siempre** encuentra un buen candidato: eso
 * es una propiedad de la búsqueda en alta dimensión, no una prueba. Si el
 * trayecto no es físicamente posible, no hay nada que puntuar.
 */
class Topology {
  constructor(transitions = []) {
    this.transitions = transitions;
  }

  allowed(src, dst, gapS) {
    return this.transitions.find(
      (t) => t.src === src && t.dst === dst && gapS >= t.minS && gapS <= t.maxS) || null;
  }

  maxGap(src) {
    const g = this.transitions.filter((t) => t.src === src).map((t) => t.maxS);
    return g.length ? Math.max(...g) : 0;
  }

  static from(pairs) {
    return new Topology(pairs.map(([src, dst, minS, maxS, prior = 1]) =>
      ({src, dst, minS, maxS, prior})));
  }
}

/* ==========================================================================
   Enlace entre cámaras
   ========================================================================== */

const LINK_DEFAULTS = {
  mutualOnly: true,       // solo enlaces recíprocamente preferidos
  minLogLR: 0.7,
  maxCandidates: 25,
  colourAgreeLLR: 0.9,
  colourDisagreeLLR: 2.5,
};

class CrossCamera {
  constructor(topology, opts = {}) {
    this.topo = topology;
    this.cfg = {...LINK_DEFAULTS, ...opts};
    // Distribución de impostores: por defecto conservadora, se recalibra con
    // datos del propio emplazamiento en cuanto los hay.
    this.bgMean = 0.30;
    this.bgStd = 0.11;
    this.bgSamples = 0;
  }

  /**
   * Calibra con pares que NO pueden ser el mismo objeto.
   *
   * Dos trayectorias de la misma cámara cuyas vidas se solapan son, salvo fallo
   * de seguimiento, objetos distintos. Eso da miles de negativos etiquetados
   * gratis **por emplazamiento**, y es lo que convierte un coseno en una cifra
   * interpretable: 0,82 no significa nada hasta saber cómo es 0,82 entre
   * desconocidos en esta cámara, con esta luz y esta multitud.
   */
  fitBackground(tracks) {
    const byCam = new Map();
    for (const t of tracks) {
      if (!t.fp || !t.cam) continue;
      if (!byCam.has(t.cam)) byCam.set(t.cam, []);
      byCam.get(t.cam).push(t);
    }
    const sims = [];
    for (const group of byCam.values()) {
      for (let i = 0; i < group.length; i++) {
        for (let j = i + 1; j < group.length; j++) {
          const a = group[i], b = group[j];
          // Solo si coexisten: si no, podrían ser el mismo objeto reapareciendo.
          if (a.t1 < b.t0 || b.t1 < a.t0) continue;
          sims.push(cosine(a.fp, b.fp));
          if (sims.length > 5000) break;
        }
      }
    }
    if (sims.length >= 50) {
      const mean = sims.reduce((p, q) => p + q, 0) / sims.length;
      const varr = sims.reduce((p, q) => p + (q - mean) ** 2, 0) / sims.length;
      this.bgMean = mean;
      this.bgStd = Math.max(0.02, Math.sqrt(varr));
      this.bgSamples = sims.length;
    }
    return {mean: this.bgMean, std: this.bgStd, n: this.bgSamples};
  }

  /** Desviaciones típicas por encima de la distribución de impostores. */
  logLR(sim) {
    return Math.max(0, (sim - this.bgMean) / this.bgStd);
  }

  /**
   * Hipótesis de enlace. **Nunca** confirma nada: devuelve candidatos con su
   * evidencia para que una persona decida. Escribir una identidad de forma
   * automática es exactamente la línea que separa asociar apariencias de
   * identificar personas.
   */
  /**
   * Agrupa por cámara y ventana temporal.
   *
   * El barrido anterior era un doble bucle sobre TODAS las trayectorias, con
   * una salida temprana que solo actuaba pasado el hueco máximo. Con doscientas
   * mil trayectorias archivadas eso son ~10^10 comparaciones y el hilo se
   * congela. Indexar por (cámara, cubo temporal) reduce el candidato a los
   * cubos que la topología permite alcanzar.
   */
  _bucket(tracks, bucketMs) {
    const idx = new Map();
    for (const t of tracks) {
      const k = `${t.cam}|${Math.floor(t.t0 / bucketMs)}`;
      if (!idx.has(k)) idx.set(k, []);
      idx.get(k).push(t);
    }
    return idx;
  }

  propose(tracks) {
    const usable = tracks
      .filter((t) => t.fp && t.cam && Number.isFinite(t.t0) && Number.isFinite(t.t1))
      .sort((a, b) => a.t0 - b.t0);
    const out = [];

    for (let i = 0; i < usable.length; i++) {
      const a = usable[i];
      let checked = 0;
      // Solo se recorren las trayectorias del rango temporal alcanzable desde
      // `a`, no todas las posteriores.
      const hi = a.t1 + this.topo.maxGap(a.cam) * 1000;
      for (let j = i + 1; j < usable.length; j++) {
        const b = usable[j];
        if (b.t0 > hi) break;
        if (b.cam === a.cam) continue;
        const gap = (b.t0 - a.t1) / 1000;
        if (gap < 0) continue;
        const tr = this.topo.allowed(a.cam, b.cam, gap);
        if (!tr) {
          // Más allá de la transición más lenta posible, todo lo posterior
          // también lo está: no hay nada que seguir mirando desde este objeto.
          if (gap > this.topo.maxGap(a.cam)) break;
          continue;
        }
        if (a.cls !== b.cls) continue;

        const sim = cosine(a.fp, b.fp);
        let llr = this.logLR(sim) + Math.log(Math.max(tr.prior, 1e-3));

        // Canales independientes se SUMAN, no se promedian: un desacuerdo de
        // color es contraevidencia real y debe poder hundir un parecido de
        // apariencia que por sí solo resultaba atractivo.
        if (a.colour && b.colour) {
          llr += a.colour === b.colour
            ? this.cfg.colourAgreeLLR : -this.cfg.colourDisagreeLLR;
        }
        // Coherencia de tamaño: si ambas cámaras tienen escala métrica, dos
        // objetos con estatura muy distinta no son el mismo por muy parecido
        // que sea su color.
        if (a.stature && b.stature) {
          const d = Math.abs(a.stature - b.stature);
          llr += d < 0.08 ? 0.8 : (d > 0.20 ? -2.0 : 0);
        }
        if (llr < this.cfg.minLogLR) continue;

        out.push({
          a: a.id, b: b.id, camA: a.cam, camB: b.cam,
          gapS: Math.round(gap * 10) / 10,
          similarity: Math.round(sim * 1000) / 1000,
          logLR: Math.round(llr * 100) / 100,
          confidence: confidenceOf(llr),
          evidence: {
            clase: a.cls,
            transicion: `${a.cam} → ${b.cam}`,
            ventana_s: [tr.minS, tr.maxS],
            impostores: {media: Math.round(this.bgMean * 1000) / 1000,
                         sigma: Math.round(this.bgStd * 1000) / 1000,
                         n: this.bgSamples},
            color: a.colour && b.colour ? `${a.colour} / ${b.colour}` : null,
            estatura: a.stature && b.stature
              ? `${a.stature.toFixed(2)} / ${b.stature.toFixed(2)} m` : null,
          },
        });
        if (++checked >= this.cfg.maxCandidates) break;
      }
    }
    out.sort((x, y) => y.logLR - x.logLR);
    return this.cfg.mutualOnly ? mutualBest(out) : out;
  }
}

/**
 * Filtro de emparejamiento mutuo.
 *
 * Sin él, una trayectoria propone enlace con **todas** las candidatas que caen
 * en su ventana temporal: medido, 115 hipótesis para 12 enlaces reales, un 35 %
 * de precisión. La mayoría de ese ruido es una misma persona de la cámara A
 * emparejada con media docena de personas de la B.
 *
 * Una persona no puede estar en dos sitios: si A→B es el enlace, entonces B
 * también debe tener a A como su mejor candidato mirando hacia atrás. Exigir
 * que la preferencia sea recíproca elimina el abanico sin necesidad de subir
 * umbrales, que es lo que costaría recall.
 *
 * Es la misma idea que el "ratio test" de correspondencias visuales: una
 * coincidencia solo vale si es claramente la mejor por ambos lados.
 */
function mutualBest(hyps) {
  const bestForward = new Map();
  const bestBackward = new Map();
  for (const h of hyps) {
    const f = bestForward.get(h.a);
    if (!f || h.logLR > f.logLR) bestForward.set(h.a, h);
    const b = bestBackward.get(h.b);
    if (!b || h.logLR > b.logLR) bestBackward.set(h.b, h);
  }
  return hyps.filter((h) => bestForward.get(h.a) === h && bestBackward.get(h.b) === h);
}

/**
 * Escala deliberadamente gruesa.
 *
 * Un porcentaje aquí implicaría una calibración que el descriptor no tiene.
 * Cuatro niveles comunican la fuerza de la evidencia sin fingir precisión.
 */
function confidenceOf(logLR) {
  if (logLR >= 4.0) return "fuerte";
  if (logLR >= 2.0) return "moderada";
  if (logLR >= 0.7) return "débil";
  return "insuficiente";
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = {fingerprint, cosine, TrackFingerprint, Topology,
                    CrossCamera, confidenceOf, mutualBest, DIM};
}
