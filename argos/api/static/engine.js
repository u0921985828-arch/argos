/*
 * ARGOS · motor de análisis en el dispositivo
 * ---------------------------------------------------------------------------
 * Todo lo que tiene que ir a velocidad de vídeo vive aquí, en el navegador,
 * junto a la cámara. Nada de esto toca la red.
 *
 * El reparto es por latencia, no por comodidad:
 *
 *   Bucle en tiempo real (< 40 ms)   modelo de fondo, blobs, tracking, overlay
 *                                    -> este fichero, a la tasa de la cámara
 *
 *   Bucle por lotes (segundos)       optimizador de sinopsis, vectorización de
 *                                    siluetas, fotogrametría
 *                                    -> Python, bajo demanda
 *
 * La razón de que el reparto anterior (capturar -> POST -> sondear cajas) no
 * pudiera sentirse como una app de cámara no es el ancho de banda: es que cada
 * corchete llegaba con el retraso de un codificado JPEG más una ida y vuelta
 * HTTP más el intervalo de sondeo. Sumaba entre 300 y 600 ms. Ninguna cantidad
 * de optimización del servidor arregla eso, porque el retraso está en el viaje,
 * no en el cálculo.
 *
 * Analizando aquí, el corchete se dibuja en el mismo frame en que se detecta.
 *
 * Los tubos resultantes ocupan ~120 bytes por observación, así que una sesión
 * de ocho horas cabe en unos pocos megas y se envía entera al servidor solo
 * cuando se pide un sinopsis.
 */

"use strict";

/* ==========================================================================
   Modelo de fondo
   ========================================================================== */

/**
 * Gaussiana única por píxel con actualización exponencial.
 *
 * Frente a MOG2 (varias gaussianas por píxel) esto es mucho más barato y, para
 * una cámara realmente fija, prácticamente igual de bueno: las modas múltiples
 * sirven para fondos que oscilan —hojas, agua, pantallas— y en esos casos
 * ninguno de los dos salva la escena.
 *
 * Trabaja sobre luminancia a resolución reducida. La silueta se recupera luego
 * a escala completa; detectar a 320×180 y dibujar a 1080p es indistinguible a
 * simple vista y cuesta veinte veces menos.
 */
class BackgroundModel {
  constructor(width, height, {alpha = 0.008, k = 2.6, varInit = 90,
                              varMin = 16, shadowRatio = 0.55,
                              warmup = 45} = {}) {
    this.warmup = warmup;
    this.w = width;
    this.h = height;
    this.alpha = alpha;          // ritmo de adaptación
    this.k = k;                  // umbral en desviaciones típicas
    this.varMin = varMin;
    this.shadowRatio = shadowRatio;
    this.mean = new Float32Array(width * height);
    this.vari = new Float32Array(width * height).fill(varInit);
    this.mask = new Uint8Array(width * height);
    this.frames = 0;
  }

  /**
   * Hasta que el modelo se asienta, *todo* el cuadro difiere de una media que
   * aún vale cero, así que la máscara es el frame entero. Sin esta compuerta el
   * sistema abre un "objeto" del tamaño de la pantalla en el segundo uno y lo
   * arrastra durante todo el arranque.
   */
  get ready() { return this.frames > this.warmup; }

  /** @param {Uint8ClampedArray} rgba  Datos RGBA a resolución del modelo. */
  update(rgba, {learn = true} = {}) {
    const {w, h, mean, vari, mask, k, varMin, shadowRatio} = this;
    const n = w * h;
    // Durante el arranque se aprende deprisa: si no, los primeros segundos
    // marcan la escena entera como primer plano.
    const a = this.frames < this.warmup ? 0.25 : this.alpha;
    let count = 0;

    for (let i = 0, p = 0; i < n; i++, p += 4) {
      // Luma BT.601 en enteros: evita el coste de coma flotante por canal.
      const y = (rgba[p] * 77 + rgba[p + 1] * 150 + rgba[p + 2] * 29) >> 8;
      const mu = mean[i];
      const d = y - mu;
      const v = vari[i] < varMin ? varMin : vari[i];

      let fg = (d * d) > (k * k * v) ? 1 : 0;

      // Rechazo de sombra: una sombra oscurece de forma proporcional sin
      // cambiar el tono. Sin esto, cada objeto arrastra su sombra y aparece
      // con el doble de ancho, lo que arruina tanto la silueta como cualquier
      // estimación de estatura.
      if (fg && d < 0 && mu > 12 && (y / mu) > shadowRatio) fg = 0;

      mask[i] = fg;
      count += fg;

      if (learn) {
        // El fondo se actualiza siempre, pero más despacio donde hay objeto:
        // congelarlo del todo hace que un objeto parado quede marcado para
        // siempre; actualizarlo igual lo disuelve en segundos.
        const rate = fg ? a * 0.05 : a;
        mean[i] = mu + rate * d;
        vari[i] = v + rate * (d * d - v);
      }
    }
    this.frames++;
    this.fgRatio = count / n;
    if (!this.ready) mask.fill(0);
    return mask;
  }

  /** Reinicia el modelo. Necesario si se mueve la cámara o cambia la luz. */
  reset() {
    this.mean.fill(0);
    this.vari.fill(90);
    this.frames = 0;
  }

  /** Placa de fondo actual en escala de grises, para depuración y para la
   *  composición del sinopsis. */
  plate() {
    const out = new Uint8ClampedArray(this.w * this.h * 4);
    for (let i = 0, p = 0; i < this.mean.length; i++, p += 4) {
      const v = this.mean[i];
      out[p] = out[p + 1] = out[p + 2] = v;
      out[p + 3] = 255;
    }
    return new ImageData(out, this.w, this.h);
  }
}

/* ==========================================================================
   Morfología
   ========================================================================== */

/**
 * Erosión/dilatación separables sobre máscara binaria.
 *
 * Separar en pasada horizontal y vertical convierte un kernel r×r en 2r
 * operaciones por píxel en lugar de r². A 320×180 con r=2 la diferencia es
 * entre imperceptible y visible en el presupuesto de frame de un móvil.
 */
function morph(mask, w, h, radius, dilate, scratch) {
  // Suma corrida en lugar de recorrer la ventana por píxel.
  //
  // La versión anterior miraba los 2r+1 vecinos de cada píxel buscando un
  // acierto: coste O(r) por píxel y por eje. Sobre una máscara binaria eso es
  // innecesario, porque erosión y dilatación se reducen a contar:
  //
  //     dilatación  ->  la ventana contiene al menos un 1
  //     erosión     ->  la ventana está llena de 1
  //
  // Un contador que suma el píxel que entra y resta el que sale da esa cuenta
  // en O(1) por píxel, sea cual sea el radio. La morfología era el 39 % del
  // tiempo de frame (2,95 ms de 7,5).
  //
  // Los bordes replican el píxel del borde en vez de asumir ceros: con ceros,
  // una erosión come una franja del ancho del radio en los cuatro lados y
  // adelgaza sin motivo los objetos que tocan el borde.
  const tmp = scratch;
  const win = 2 * radius + 1;
  const clampW = (v) => (v < 0 ? 0 : v >= w ? w - 1 : v);
  const clampH = (v) => (v < 0 ? 0 : v >= h ? h - 1 : v);

  for (let y = 0; y < h; y++) {
    const row = y * w;
    let sum = 0;
    for (let k = -radius; k <= radius; k++) sum += mask[row + clampW(k)];
    for (let x = 0; x < w; x++) {
      tmp[row + x] = dilate ? (sum > 0 ? 1 : 0) : (sum === win ? 1 : 0);
      sum -= mask[row + clampW(x - radius)];
      sum += mask[row + clampW(x + radius + 1)];
    }
  }

  for (let x = 0; x < w; x++) {
    let sum = 0;
    for (let k = -radius; k <= radius; k++) sum += tmp[clampH(k) * w + x];
    for (let y = 0; y < h; y++) {
      mask[y * w + x] = dilate ? (sum > 0 ? 1 : 0) : (sum === win ? 1 : 0);
      sum -= tmp[clampH(y - radius) * w + x];
      sum += tmp[clampH(y + radius + 1) * w + x];
    }
  }
  return mask;
}

const open  = (m, w, h, r, s) => morph(morph(m, w, h, r, false, s), w, h, r, true, s);
const close = (m, w, h, r, s) => morph(morph(m, w, h, r, true, s), w, h, r, false, s);

/* ==========================================================================
   Componentes conexas
   ========================================================================== */

/**
 * Etiquetado por union-find sobre runs horizontales.
 *
 * Se recorre por runs en lugar de píxel a píxel porque los objetos producen
 * tramos largos y contiguos: una fila de 40 píxeles de objeto es un solo run y
 * una sola operación de unión, en vez de cuarenta.
 */
function components(mask, w, h, minArea) {
  const parent = new Int32Array(1024);
  let nLabels = 1;
  parent[0] = 0;

  const find = (a) => { while (parent[a] !== a) a = parent[a] = parent[parent[a]]; return a; };
  const union = (a, b) => { a = find(a); b = find(b); if (a !== b) parent[b] = a; };

  const labels = new Int32Array(w * h);
  let prevRuns = [];

  for (let y = 0; y < h; y++) {
    const row = y * w;
    const runs = [];
    let x = 0;
    while (x < w) {
      while (x < w && !mask[row + x]) x++;
      if (x >= w) break;
      const start = x;
      while (x < w && mask[row + x]) x++;
      const end = x - 1;

      let label = 0;
      for (const [ps, pe, pl] of prevRuns) {
        if (pe < start - 1 || ps > end + 1) continue;
        if (label === 0) label = pl; else union(label, pl);
      }
      if (label === 0) {
        label = nLabels++;
        if (label >= parent.length) {
          const grown = new Int32Array(parent.length * 2);
          grown.set(parent);
          for (let i = parent.length; i < grown.length; i++) grown[i] = i;
          return components(mask, w, h, minArea); // improbable; reintento limpio
        }
        parent[label] = label;
      }
      for (let i = start; i <= end; i++) labels[row + i] = label;
      runs.push([start, end, label]);
    }
    prevRuns = runs;
  }

  const stats = new Map();
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const l = labels[y * w + x];
      if (!l) continue;
      const r = find(l);
      let s = stats.get(r);
      if (!s) { s = {x1: x, y1: y, x2: x, y2: y, area: 0, label: r}; stats.set(r, s); }
      if (x < s.x1) s.x1 = x;
      if (x > s.x2) s.x2 = x;
      if (y < s.y1) s.y1 = y;
      if (y > s.y2) s.y2 = y;
      s.area++;
    }
  }

  const out = [];
  for (const s of stats.values()) {
    if (s.area < minArea) continue;
    const bw = s.x2 - s.x1 + 1, bh = s.y2 - s.y1 + 1;
    // El relleno separa un objeto real de un artefacto disperso de iluminación,
    // que produce cajas grandes casi vacías.
    s.fill = s.area / (bw * bh);
    if (s.fill < 0.22) continue;
    s.w = bw; s.h = bh;
    out.push(s);
  }
  return {regions: out, labels, find};
}

/* ==========================================================================
   Tracking
   ========================================================================== */

const iou = (a, b) => {
  const x1 = Math.max(a.x1, b.x1), y1 = Math.max(a.y1, b.y1);
  const x2 = Math.min(a.x2, b.x2), y2 = Math.min(a.y2, b.y2);
  const iw = x2 - x1, ih = y2 - y1;
  if (iw <= 0 || ih <= 0) return 0;
  const inter = iw * ih;
  const areaA = (a.x2 - a.x1) * (a.y2 - a.y1);
  const areaB = (b.x2 - b.x1) * (b.y2 - b.y1);
  return inter / (areaA + areaB - inter);
};

/**
 * Asociación voraz por IoU con predicción de velocidad constante.
 *
 * Voraz y no húngaro a propósito: el asignamiento óptimo importa cuando hay
 * decenas de candidatos compitiendo, y en una escena de cámara fija a 20 fps el
 * solape entre frames consecutivos es tan alto que voraz y óptimo coinciden
 * casi siempre. El coste de equivocarse aquí es un cambio de identidad, que se
 * recupera después con el re-ID del servidor; el coste de un húngaro en el
 * bucle de tiempo real es tirones visibles.
 */
class Tracker {
  constructor({matchIoU = 0.18, maxAge = 20, minHits = 4, maxTracks = 60} = {}) {
    this.matchIoU = matchIoU;
    this.maxAge = maxAge;
    this.minHits = minHits;
    this.maxTracks = maxTracks;
    this.tracks = [];
    this.finished = [];
    this.nextId = 1;
  }

  /** Dimensiones del plano de análisis, para saber qué cajas están truncadas. */
  setFrameSize(w, h) { this.frameW = w; this.frameH = h; }

  step(frameIdx, detections) {
    for (const t of this.tracks) {
      t.age++;
      t.miss++;
      // Predicción: se arrastra la caja por su velocidad reciente para que una
      // oclusión breve no rompa el tubo.
      t.pred = {
        x1: t.box.x1 + t.vx, y1: t.box.y1 + t.vy,
        x2: t.box.x2 + t.vx, y2: t.box.y2 + t.vy,
      };
    }

    const pairs = [];
    for (let ti = 0; ti < this.tracks.length; ti++) {
      for (let di = 0; di < detections.length; di++) {
        const s = iou(this.tracks[ti].pred, detections[di]);
        if (s >= this.matchIoU) pairs.push([s, ti, di]);
      }
    }
    pairs.sort((a, b) => b[0] - a[0]);

    const usedT = new Set(), usedD = new Set();
    for (const [, ti, di] of pairs) {
      if (usedT.has(ti) || usedD.has(di)) continue;
      usedT.add(ti); usedD.add(di);
      this._hit(this.tracks[ti], frameIdx, detections[di]);
    }

    for (let di = 0; di < detections.length; di++) {
      if (usedD.has(di)) continue;
      if (this.tracks.length >= this.maxTracks) break;
      this._spawn(frameIdx, detections[di]);
    }

    const alive = [];
    for (const t of this.tracks) {
      if (t.miss > this.maxAge) this._retire(t);
      else alive.push(t);
    }
    this.tracks = alive;
    return this.tracks.filter((t) => t.hits >= this.minHits);
  }

  /**
   * Voto de clase ponderado por área, ignorando cajas que tocan el borde.
   *
   * Esto NO es reconocimiento: es la proporción de un borrón de movimiento. Lo
   * que sale de aquí queda marcado con `klassSrc = "forma"` y la interfaz no
   * enseña la palabra --- decir "coche" porque una mancha es más ancha que
   * alta es justo la alucinación que se reportó desde el campo.
   *
   * Asignar la clase con la caja del frame actual es un error sutil y
   * sistemático: la última caja de cualquier objeto es la rodaja estrecha que
   * queda mientras sale del cuadro, y una rodaja siempre tiene proporción de
   * peatón. Un coche cruzando de derecha a izquierda acababa etiquetado como
   * persona en el 100 % de los casos.
   */
  _vote(t, det, w, h) {
    const touching = det.x1 <= 1 || det.y1 <= 1 ||
                     (w && det.x2 >= w - 1) || (h && det.y2 >= h - 1);
    if (touching) return;
    const area = (det.x2 - det.x1) * (det.y2 - det.y1);
    const k = classify(det);
    t.votes[k] = (t.votes[k] || 0) + area;
    let best = null, top = -1;
    for (const key in t.votes) if (t.votes[key] > top) { top = t.votes[key]; best = key; }
    t.klass = best;
    t.klassSrc = "forma";
  }

  _hit(t, frameIdx, det) {
    const cxOld = (t.box.x1 + t.box.x2) / 2, cyOld = (t.box.y1 + t.box.y2) / 2;
    const cxNew = (det.x1 + det.x2) / 2, cyNew = (det.y1 + det.y2) / 2;
    // Velocidad suavizada: la caja de una sustracción de fondo tiembla, y
    // derivar el ruido directamente produce predicciones peores que ninguna.
    t.vx = 0.65 * t.vx + 0.35 * (cxNew - cxOld);
    t.vy = 0.65 * t.vy + 0.35 * (cyNew - cyOld);
    t.box = {x1: det.x1, y1: det.y1, x2: det.x2, y2: det.y2};
    t.hits++;
    t.miss = 0;
    // Con clases del detector no hay que votar por proporción: la clase la da
    // el modelo y es fiable.
    if (det.klass) { t.klass = det.klass; t.klassSrc = "det"; }
    else this._vote(t, det, this.frameW, this.frameH);
    t.obs.push({f: frameIdx, b: [det.x1, det.y1, det.x2, det.y2],
                m: det.rle ?? null, s: det.fill});
  }

  _spawn(frameIdx, det) {
    const t = {
      id: this.nextId++, box: {x1: det.x1, y1: det.y1, x2: det.x2, y2: det.y2},
      vx: 0, vy: 0, hits: 1, miss: 0, age: 1, klass: det.klass || classify(det),
      klassSrc: det.klass ? "det" : "forma",
      votes: {}, obs: [], pred: det, start: frameIdx,
    };
    t.pred = t.box;
    // Con clases del detector no hay que votar por proporción: la clase la da
    // el modelo y es fiable.
    if (det.klass) { t.klass = det.klass; t.klassSrc = "det"; }
    else this._vote(t, det, this.frameW, this.frameH);
    t.obs.push({f: frameIdx, b: [det.x1, det.y1, det.x2, det.y2],
                m: det.rle ?? null, s: det.fill});
    this.tracks.push(t);
  }

  _retire(t) {
    if (t.hits >= this.minHits && t.obs.length >= this.minHits) this.finished.push(t);
  }

  flush() {
    for (const t of this.tracks) this._retire(t);
    this.tracks = [];
    const out = this.finished;
    this.finished = [];
    return out;
  }
}

/**
 * Clasificación por proporción de la caja.
 *
 * Es honestamente cruda y está etiquetada como tal en la interfaz. Una
 * sustracción de fondo no sabe qué es un objeto, solo que se mueve; la
 * proporción separa razonablemente un peatón de un turismo y nada más. Para
 * clases fiables hace falta un detector real.
 */
function classify(det) {
  // Pista de forma, NO una clase. Sin detector neuronal no hay nada en el
  // pipeline que sepa qué es un coche; lo único disponible es la proporción de
  // la mancha de movimiento, y una barandilla es más ancha que alta igual que
  // un turismo. Quien consuma esto mira `klassSrc` antes de creérselo.
  //
  // Se deriva de la caja y no de campos auxiliares: `classify` se llama tanto
  // sobre detecciones frescas como sobre cajas ya asociadas, y solo la caja
  // está garantizada en ambos casos.
  const w = Math.max(1, det.x2 - det.x1);
  const h = Math.max(1, det.y2 - det.y1);
  const ar = h / w;
  if (ar > 1.5) return "person";
  if (ar < 0.65) return "car";
  return "object";
}

/* ==========================================================================
   Siluetas comprimidas
   ========================================================================== */

/**
 * Silueta como run-length encoding dentro de su propia caja.
 *
 * Gana con siluetas altas y sólidas —un peatón son uno o dos tramos por fila—
 * y pierde con blobs pequeños, donde la cabecera de cada tramo cuesta más que
 * el bitmap entero. Se mide y se elige el menor de los dos, en lugar de asumir
 * que el RLE siempre comprime, que es falso por debajo de unos 600 píxeles de
 * caja.
 */
function encodeRLE(labels, w, region, find) {
  const {x1, y1, x2, y2, label} = region;
  const runs = [];
  for (let y = y1; y <= y2; y++) {
    let x = x1;
    while (x <= x2) {
      while (x <= x2 && find(labels[y * w + x]) !== label) x++;
      if (x > x2) break;
      const s = x;
      while (x <= x2 && find(labels[y * w + x]) === label) x++;
      runs.push((y - y1), (s - x1), (x - s));
    }
  }
  return runs;
}

/** Silueta de la máscara de fondo recortada a una caja del detector. */
function rleFromMask(mask, w, h, x1, y1, x2, y2) {
  const a = Math.max(0, Math.floor(x1)), b = Math.max(0, Math.floor(y1));
  const c = Math.min(w, Math.ceil(x2)), d = Math.min(h, Math.ceil(y2));
  const bw = c - a;
  const runs = [];
  let any = 0;
  for (let y = b; y < d; y++) {
    let x = a;
    while (x < c) {
      while (x < c && !mask[y * w + x]) x++;
      if (x >= c) break;
      const s = x;
      while (x < c && mask[y * w + x]) x++;
      runs.push(y - b, s - a, x - s);
      any += x - s;
    }
  }
  // Sin señal de fondo --- objeto parado, absorbido --- se devuelve el
  // rectángulo. Es lo que haría un sistema sin segmentación, y es mejor que
  // dejar el tubo sin volumen para el optimizador.
  if (any < 0.04 * bw * (d - b)) {
    const out = [];
    for (let y = 0; y < d - b; y++) out.push(y, 0, bw);
    return out;
  }
  return runs;
}

function decodeRLE(runs, bw, bh) {
  const mask = new Uint8Array(bw * bh);
  for (let i = 0; i < runs.length; i += 3) {
    const row = runs[i] * bw, start = runs[i + 1], len = runs[i + 2];
    mask.fill(1, row + start, row + start + len);
  }
  return mask;
}

/* ==========================================================================
   Contorno vectorial
   ========================================================================== */

/**
 * Traza el contorno exterior de una máscara con seguimiento de Moore.
 *
 * Se recorre el borde girando siempre en el mismo sentido a partir del último
 * vecino visitado, lo que produce una secuencia ordenada de píxeles de frontera
 * en un solo paseo. Solo el contorno externo: los huecos interiores de una
 * silueta —el vano entre brazo y torso— se leen como defecto de impresión a
 * tamaño pequeño y no aportan nada que un observador use.
 */
function traceContour(mask, w, h) {
  const at = (x, y) => (x < 0 || y < 0 || x >= w || y >= h) ? 0 : mask[y * w + x];

  let sx = -1, sy = -1;
  outer:
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) if (mask[y * w + x]) { sx = x; sy = y; break outer; }
  }
  if (sx < 0) return [];

  // Vecindario en sentido horario empezando por el oeste.
  const N = [[-1, 0], [-1, -1], [0, -1], [1, -1], [1, 0], [1, 1], [0, 1], [-1, 1]];
  const pts = [];
  let cx = sx, cy = sy, dir = 0;
  const maxSteps = 4 * (w + h) + 64;

  for (let step = 0; step < maxSteps; step++) {
    pts.push(cx, cy);
    let found = false;
    for (let i = 0; i < 8; i++) {
      const d = (dir + i) & 7;
      const nx = cx + N[d][0], ny = cy + N[d][1];
      if (at(nx, ny)) {
        cx = nx; cy = ny;
        // Retrocede dos posiciones para no cortar esquinas cóncavas.
        dir = (d + 6) & 7;
        found = true;
        break;
      }
    }
    if (!found) break;
    if (cx === sx && cy === sy && pts.length > 4) break;
  }
  return pts;
}

/**
 * Remuestreo a longitud de arco constante.
 *
 * Los contornos rasterizados tienen puntos desigualmente espaciados: los tramos
 * diagonales los empaquetan más que los rectos. Suavizar esa secuencia tal cual
 * pondera de más las zonas densas y aplana justo las esquinas que definen la
 * forma. Es el paso que separa una curva limpia de una grumosa.
 */
function resampleClosed(pts, n) {
  const m = pts.length / 2;
  if (m < 3) return pts;
  const seg = new Float64Array(m);
  let total = 0;
  for (let i = 0; i < m; i++) {
    const j = (i + 1) % m;
    const dx = pts[j * 2] - pts[i * 2], dy = pts[j * 2 + 1] - pts[i * 2 + 1];
    seg[i] = Math.hypot(dx, dy);
    total += seg[i];
  }
  if (total < 1e-6) return pts;

  const out = new Float64Array(n * 2);
  let acc = 0, idx = 0, target = 0;
  const stepLen = total / n;
  for (let k = 0; k < n; k++) {
    target = k * stepLen;
    while (idx < m - 1 && acc + seg[idx] < target) { acc += seg[idx]; idx++; }
    const t = seg[idx] > 1e-9 ? (target - acc) / seg[idx] : 0;
    const j = (idx + 1) % m;
    out[k * 2]     = pts[idx * 2]     + t * (pts[j * 2]     - pts[idx * 2]);
    out[k * 2 + 1] = pts[idx * 2 + 1] + t * (pts[j * 2 + 1] - pts[idx * 2 + 1]);
  }
  return out;
}

/** Corte de esquinas de Chaikin sobre polígono cerrado: elimina la escalera. */
function chaikin(pts, iterations = 2, ratio = 0.25) {
  let p = pts;
  for (let it = 0; it < iterations; it++) {
    const m = p.length / 2;
    const q = new Float64Array(m * 4);
    for (let i = 0; i < m; i++) {
      const j = (i + 1) % m;
      const ax = p[i * 2], ay = p[i * 2 + 1];
      const bx = p[j * 2], by = p[j * 2 + 1];
      q[i * 4]     = ax + ratio * (bx - ax);
      q[i * 4 + 1] = ay + ratio * (by - ay);
      q[i * 4 + 2] = ax + (1 - ratio) * (bx - ax);
      q[i * 4 + 3] = ay + (1 - ratio) * (by - ay);
    }
    p = q;
  }
  return p;
}

/** Silueta suavizada de una observación, en coordenadas de la fuente. */
function silhouette(obs, sx, sy, {points = 44, smooth = 2} = {}) {
  if (!obs.m || !obs.m.length) return null;
  const [x1, y1, x2, y2] = obs.b;
  const bw = Math.max(1, Math.round(x2 - x1));
  const bh = Math.max(1, Math.round(y2 - y1));
  const mask = decodeRLE(obs.m, bw, bh);

  let pts = traceContour(mask, bw, bh);
  if (pts.length < 12) return null;
  pts = chaikin(resampleClosed(pts, Math.min(points * 3, 128)), smooth);
  pts = resampleClosed(pts, points);

  const out = new Float64Array(pts.length);
  for (let i = 0; i < pts.length; i += 2) {
    out[i]     = (x1 + pts[i])     * sx;
    out[i + 1] = (y1 + pts[i + 1]) * sy;
  }
  return out;
}

/** Dibuja una silueta como trazo Catmull-Rom cerrado. */
function strokeSilhouette(ctx, p) {
  const n = p.length / 2;
  if (n < 3) return;
  ctx.beginPath();
  ctx.moveTo(p[0], p[1]);
  for (let i = 0; i < n; i++) {
    const i0 = ((i - 1 + n) % n) * 2, i1 = i * 2;
    const i2 = ((i + 1) % n) * 2, i3 = ((i + 2) % n) * 2;
    ctx.bezierCurveTo(
      p[i1]     + (p[i2]     - p[i0])     / 6,
      p[i1 + 1] + (p[i2 + 1] - p[i0 + 1]) / 6,
      p[i2]     - (p[i3]     - p[i1])     / 6,
      p[i2 + 1] - (p[i3 + 1] - p[i1 + 1]) / 6,
      p[i2], p[i2 + 1]);
  }
  ctx.closePath();
}

/* ==========================================================================
   Búfer de recortes
   ========================================================================== */

/**
 * Recortes JPEG con techo de memoria y desalojo del más antiguo.
 *
 * Se guardan aquí, en el dispositivo, y nunca se suben: el servidor solo
 * necesita geometría para resolver el sinopsis, y la composición final se pinta
 * en canvas con estos recortes locales. Evita subir megas de imagen para
 * recuperar un vídeo que el propio teléfono puede montar.
 */
class PatchBuffer {
  constructor({maxBytes = 48 * 1024 * 1024, quality = 0.68} = {}) {
    this.maxBytes = maxBytes;
    this.quality = quality;
    this.map = new Map();          // Map conserva orden de inserción
    this.bytes = 0;
    this.evicted = 0;
    this.canvas = document.createElement("canvas");
    this.ctx = this.canvas.getContext("2d", {alpha: false});
  }

  async add(id, frameIdx, source, box) {
    const w = Math.max(1, Math.round(box.x2 - box.x1));
    const h = Math.max(1, Math.round(box.y2 - box.y1));

    // Recorte directo a bitmap, sin pasar por JPEG.
    //
    // La versión anterior hacía, POR OBJETO Y POR FRAME: redimensionar el
    // canvas (que fuerza una reasignación), dibujar, codificar a JPEG con
    // `toBlob`, y **volver a decodificar** ese JPEG con `createImageBitmap`.
    // Una codificación y una decodificación completas por recorte.
    //
    // Y el JPEG no se guardaba: solo se usaba su `size` para contabilizar
    // memoria. Se pagaba una compresión entera para obtener una cifra.
    //
    // A 130 objetos y 60 fps eso son 7.800 codificaciones y 7.800
    // decodificaciones por segundo. No es que fuera lento: era imposible.
    //
    // `createImageBitmap` con región recorta en un solo paso, sin canvas
    // intermedio y sin códec. El tamaño se estima --- el contador solo existe
    // para acotar memoria, y para eso una estimación sirve igual.
    let bitmap;
    try {
      bitmap = await createImageBitmap(source, box.x1, box.y1, w, h);
    } catch {
      return;   // fuente no dibujable todavía
    }
    // Estimación: RGBA menos el factor de compresión típico de una foto a esta
    // calidad. No hace falta exactitud, hace falta un techo fiable.
    const size = Math.round(w * h * 4 * 0.12);

    const key = `${id}:${frameIdx}`;
    const prev = this.map.get(key);
    if (prev) { this.bytes -= prev.size; prev.bitmap.close?.(); }
    this.map.set(key, {bitmap, size});
    this.bytes += size;

    while (this.bytes > this.maxBytes && this.map.size) {
      const [k, v] = this.map.entries().next().value;
      this.map.delete(k);
      this.bytes -= v.size;
      v.bitmap.close?.();
      this.evicted++;
    }
  }

  get(id, frameIdx) { return this.map.get(`${id}:${frameIdx}`)?.bitmap ?? null; }
  clear() {
    for (const v of this.map.values()) v.bitmap.close?.();
    this.map.clear(); this.bytes = 0;
  }
}

/* ==========================================================================
   Motor
   ========================================================================== */

class Engine {
  /**
   * @param {object} opts
   * @param {number} opts.analysisWidth  Anchura del modelo de fondo. 320 es el
   *   punto donde un peatón a media distancia sigue midiendo ~20 px de alto,
   *   que es el mínimo por debajo del cual la silueta deja de tener forma.
   */
  constructor({analysisWidth = 320, minAreaFrac = 0.0006, patchBytes = 48e6,
               maxTubes = 400, maxObsPerTrack = 900, tubeWindowFrames = 0,
               patchEvery = 3} = {}) {
    this.analysisWidth = analysisWidth;
    this.minAreaFrac = minAreaFrac;
    // Retención acotada. Sin esto `tubes` crece durante toda la sesión: medido,
    // 1 KB por frame con diez objetos en escena, que a 30 fps son ~0,1 GB/hora
    // --- y cuatro veces más en una escena concurrida. Un despliegue de un día
    // mata la pestaña, y el síntoma es que "se va poniendo lenta", que no
    // apunta a nada.
    this.maxTubes = maxTubes;
    this.maxObsPerTrack = maxObsPerTrack;
    this.tubeWindowFrames = tubeWindowFrames;
    // Cadencia de captura de recortes, independiente de la de análisis.
    this.patchEvery = Math.max(1, patchEvery);
    this.dropped = {tubes: 0, obs: 0};
    this.small = document.createElement("canvas");
    this.smallCtx = this.small.getContext("2d", {alpha: false, willReadFrequently: true});
    this.bg = null;
    this.scratch = null;
    this.tracker = new Tracker();
    this.patches = new PatchBuffer({maxBytes: patchBytes});
    this.tubes = [];
    this.frameIdx = 0;
    this.lastMs = 0;
    this.msAvg = 0;
    this.grabMs = 0;      // solo bajar el frame de la GPU, medido aparte
    this.running = false;
    this.sourceW = 0;
    this.sourceH = 0;
    this.plate = null;        // fondo limpio a resolución de fuente
    this.plateAt = -1;
    this.roi = null;          // región analizada, en píxeles del origen
    this._roiKey = "";
    this._forceEnsure = false;
  }

  /** Fuerza un reinicio del modelo al cambiar de región o de fuente. */
  invalidate() {
    this._forceEnsure = true;
    this._roiKey = "";
  }

  _ensure(videoW, videoH) {
    const w = this.analysisWidth;
    const h = Math.max(2, Math.round(videoH * (w / videoW)));
    if (this._forceEnsure || this.small.width !== w || this.small.height !== h) {
      this._forceEnsure = false;
      this.small.width = w; this.small.height = h;
      this.bg = new BackgroundModel(w, h);
      this.scratch = new Uint8Array(w * h);
      this.tracker = new Tracker();
      this.tubes = [];
      this.frameIdx = 0;
    }
    this.sourceW = videoW;
    this.sourceH = videoH;
  }

  /**
   * Un frame completo: fondo, blobs, tracking, recortes.
   *
   * `roi` acota el análisis a un rectángulo del origen, en píxeles del origen.
   * Es lo que permite apuntar a una región de la pantalla --- una cámara dentro
   * de una pestaña, una ventana de reproductor, el cliente web de un grabador ---
   * en lugar de a la fuente completa. Todo lo de dentro trabaja en coordenadas
   * de la región; las cajas que salen se devuelven ya trasladadas al origen,
   * porque quien dibuja no debería tener que deshacer el recorte.
   *
   * Cambiar el recorte reinicia el modelo de fondo: la escena bajo la ventana
   * es otra, y conservar el modelo anterior marcaría el cuadro entero como
   * movimiento durante varios segundos.
   */
  async process(video, {capturePatches = true, roi = null, detections = null} = {}) {
    if (!video.videoWidth) return null;
    const t0 = performance.now();

    const rx = roi ? Math.max(0, Math.round(roi.x)) : 0;
    const ry = roi ? Math.max(0, Math.round(roi.y)) : 0;
    const rw = roi ? Math.min(video.videoWidth - rx, Math.round(roi.w)) : video.videoWidth;
    const rh = roi ? Math.min(video.videoHeight - ry, Math.round(roi.h)) : video.videoHeight;
    if (rw < 16 || rh < 16) return null;

    const key = `${rx},${ry},${rw},${rh}`;
    if (key !== this._roiKey) {
      this._roiKey = key;
      this._forceEnsure = true;
    }
    this._ensure(rw, rh);
    this.roi = {x: rx, y: ry, w: rw, h: rh};

    const {width: w, height: h} = this.small;
    // La captura se cronometra APARTE del análisis.
    //
    // Son dos cosas distintas con arreglos distintos. El análisis trabaja a
    // 320 px de ancho y su coste depende del algoritmo; esto de aquí es bajar
    // un frame de la GPU a memoria de CPU, y su coste depende del TAMAÑO de la
    // fuente y del aparato, no de nada que se pueda optimizar en el bucle. En
    // un móvil con la cámara a 1920x1080 puede costar más que todo el resto
    // junto, y sumado en un solo número «ms de frame» eso es indistinguible de
    // un detector lento o de un modelo de fondo caro --- que es exactamente la
    // confusión que deja «va lenta» sin arreglar.
    const tGrab = performance.now();
    this.smallCtx.drawImage(video, rx, ry, rw, rh, 0, 0, w, h);
    const frame = this.smallCtx.getImageData(0, 0, w, h);
    this.grabMs = performance.now() - tGrab;

    const mask = this.bg.update(frame.data);

    // Si más de un tercio del cuadro se marca como primer plano, la causa no
    // son objetos: es que la cámara se ha movido o la luz ha cambiado de golpe.
    // Seguir sería producir un tubo gigante y basura; lo correcto es rehacer el
    // modelo y avisar.
    if (this.bg.fgRatio > 0.34 && this.bg.frames > 60) {
      this.bg.reset();
      this.tracker = new Tracker();
      this.disturbed = true;
      return {boxes: [], disturbed: true, ms: performance.now() - t0};
    }
    this.disturbed = false;

    open(mask, w, h, 1, this.scratch);
    close(mask, w, h, 2, this.scratch);

    const minArea = Math.max(12, Math.round(this.minAreaFrac * w * h));
    const {regions, labels, find} = components(mask, w, h, minArea);

    const sx = this.sourceW / w, sy = this.sourceH / h;
    let dets;

    if (detections) {
      // Cajas del detector neuronal. La silueta se recupera de la máscara de
      // fondo *dentro* de cada caja: el detector aporta el "qué y dónde", el
      // fondo aporta el "qué forma". Ninguno de los dos lo da todo --- el
      // detector no segmenta, y el fondo no ve lo que está parado.
      dets = [];
      for (const d of detections) {
        const x1 = Math.max(0, (d.b[0] - (roi ? roi.x : 0)) / sx);
        const y1 = Math.max(0, (d.b[1] - (roi ? roi.y : 0)) / sy);
        const x2 = Math.min(w, (d.b[2] - (roi ? roi.x : 0)) / sx);
        const y2 = Math.min(h, (d.b[3] - (roi ? roi.y : 0)) / sy);
        if (x2 - x1 < 1 || y2 - y1 < 1) continue;
        dets.push({x1, y1, x2, y2, w: x2 - x1, h: y2 - y1, fill: 1,
                   rle: rleFromMask(mask, w, h, x1, y1, x2, y2),
                   klass: d.c, score: d.score});
      }
    } else {
      dets = regions.map((r) => ({
        x1: r.x1, y1: r.y1, x2: r.x2 + 1, y2: r.y2 + 1,
        w: r.w, h: r.h, fill: r.fill,
        rle: encodeRLE(labels, w, r, find),
      }));
    }

    this.tracker.setFrameSize(w, h);
    const live = this.tracker.step(this.frameIdx, dets);

    if (capturePatches && live.length) {
      // En paralelo y con cadencia, no en serie y en todos los frames.
      //
      // El `await` estaba DENTRO del bucle: con 130 objetos, ciento treinta
      // recortes secuenciales, cada uno esperando al anterior. `Promise.all`
      // los lanza a la vez y el navegador los resuelve como puede.
      //
      // Y no hacen falta en cada frame: un recorte cada N frames basta para
      // componer un sinopsis --- entre dos frames consecutivos la apariencia de
      // un objeto no cambia. Es la misma idea que separa el detector del
      // render: cada cosa a su cadencia.
      if (this.frameIdx % this.patchEvery === 0) {
        const jobs = live.map((t) => this.patches.add(t.id, this.frameIdx, video, {
          x1: rx + Math.max(0, t.box.x1 * sx),
          y1: ry + Math.max(0, t.box.y1 * sy),
          x2: rx + Math.min(this.sourceW, t.box.x2 * sx),
          y2: ry + Math.min(this.sourceH, t.box.y2 * sy),
        }));
        // No se espera: los recortes alimentan al sinopsis, que se compone
        // después. Bloquear el frame por ellos es pagar latencia de render por
        // un dato que nadie mira todavía.
        Promise.all(jobs).catch(() => {});
      }
    }

    for (const t of this.tracker.finished) this.tubes.push(t);
    this.tracker.finished = [];
    this._prune();
    this.frameIdx++;

    // Placa de fondo: se guarda un frame completo cuando no hay ningún objeto.
    // Es mucho mejor que una mediana temporal aquí, porque un frame vacío real
    // conserva sombras y grano coherentes; la mediana los promedia y deja un
    // fondo plano sobre el que las siluetas flotan.
    if (live.length === 0 && this.bg.ready &&
        this.frameIdx - this.plateAt > 40) {
      this.plateAt = this.frameIdx;
      // Puede no existir fuera de un navegador (pruebas headless) y no es
      // esencial: sin placa, el sinopsis compone sobre fondo plano.
      if (typeof createImageBitmap === "function") {
        this.plateAt = this.frameIdx;
        createImageBitmap(video, rx, ry, rw, rh).then((bmp) => {
          this.plate?.close?.();
          this.plate = bmp;
        }).catch(() => {});
      }
    }

    const ms = performance.now() - t0;
    this.lastMs = ms;
    this.msAvg = this.msAvg ? this.msAvg * 0.9 + ms * 0.1 : ms;

    return {
      // Coordenadas devueltas en píxeles de la fuente: quien dibuja no debería
      // tener que conocer la resolución interna del análisis.
      boxes: live.map((t) => ({
        id: t.id, c: t.klass, cs: t.klassSrc || "forma", age: t.miss,
        b: [rx + t.box.x1 * sx, ry + t.box.y1 * sy,
            rx + t.box.x2 * sx, ry + t.box.y2 * sy],
      })),
      roi: this.roi,
      analysisSize: [w, h],
      ms, disturbed: false,
    };
  }

  /**
   * Poda de retención.
   *
   * Los tubos viejos se sueltan enteros; las trayectorias vivas muy largas se
   * **diezman**, no se truncan: conservar uno de cada dos puntos antiguos
   * mantiene la forma del recorrido para el sinopsis y la física, mientras que
   * cortar el principio perdería de dónde venía el objeto.
   */
  _prune() {
    if (this.tubeWindowFrames > 0) {
      const cut = this.frameIdx - this.tubeWindowFrames;
      const before = this.tubes.length;
      this.tubes = this.tubes.filter((t) => t.obs.length && t.obs[t.obs.length - 1].f >= cut);
      this.dropped.tubes += before - this.tubes.length;
    }
    if (this.tubes.length > this.maxTubes) {
      const excess = this.tubes.length - this.maxTubes;
      this.tubes.splice(0, excess);          // los más antiguos primero
      this.dropped.tubes += excess;
    }
    for (const t of this.tracker.tracks) {
      if (t.obs.length <= this.maxObsPerTrack) continue;
      const half = t.obs.length >> 1;
      const old = t.obs.slice(0, half).filter((_, i) => i % 2 === 0);
      this.dropped.obs += half - old.length;
      t.obs = old.concat(t.obs.slice(half));
    }
  }

  /** Todos los tubos, terminados y en curso. */
  allTubes() {
    const live = this.tracker.tracks.filter((t) => t.hits >= this.tracker.minHits);
    return [...this.tubes, ...live];
  }

  /** Carga útil mínima para que el servidor resuelva el plan de sinopsis. */
  exportTubes() {
    const [w, h] = [this.small.width, this.small.height];
    return {
      analysis_size: [w, h],
      source_size: [this.sourceW, this.sourceH],
      frames: this.frameIdx,
      tubes: this.allTubes().map((t) => ({
        id: t.id, class: t.klass,
        obs: t.obs.map((o) => ({f: o.f, b: o.b.map((v) => Math.round(v)), m: o.m})),
      })),
    };
  }

  stats() {
    return {
      frames: this.frameIdx,
      tubes: this.tubes.length,
      active: this.tracker.tracks.length,
      ms: Math.round(this.msAvg * 10) / 10,
      fps: this.msAvg ? Math.round(1000 / this.msAvg) : 0,
      patchMB: Math.round(this.patches.bytes / 1e5) / 10,
      obs: this.tubes.reduce((a, t) => a + t.obs.length, 0),
      dropped: this.dropped.tubes,
      bgFrames: this.bg?.frames ?? 0,
      warming: this.bg ? !this.bg.ready : true,
    };
  }

  reset() {
    this.tracker = new Tracker();
    this.patches.clear();
    this.tubes = [];
    this.frameIdx = 0;
    this.plate?.close?.();
    this.plate = null;
    this.plateAt = -1;
    this.bg?.reset();
  }
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = {BackgroundModel, Tracker, Engine, components, morph,
                    open, close, encodeRLE, decodeRLE, iou, classify,
                    traceContour, resampleClosed, chaikin, silhouette,
                    strokeSilhouette, PatchBuffer, rleFromMask};
}
