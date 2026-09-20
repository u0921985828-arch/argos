/*
 * ARGOS · detector neuronal en el navegador
 * ---------------------------------------------------------------------------
 * Corre YOLOX con ONNX Runtime Web. Es la pieza que faltaba: el motor de
 * sustracción de fondo encuentra lo que se mueve, y eso basta para tráfico,
 * pero fracasa en una multitud densa y lenta --- una plaza llena de gente
 * parada es, para un modelo de fondo, parte del fondo. Medido en vivo sobre una
 * cámara del Panteón de Roma: 6 objetos donde había cientos de personas.
 *
 * Un detector no depende del movimiento, así que ve a la persona quieta igual
 * que a la que cruza.
 *
 * El módulo mantiene lo aprendido en la versión de Python, que no es obvio:
 *
 *   · Los exports ONNX de YOLOX **no decodifican la rejilla**: emiten
 *     desplazamientos respecto a la celda. Sin reconstruir las anclas de los
 *     strides 8/16/32, las cajas salen amontonadas en la esquina con tamaño de
 *     un píxel. No da error: da basura.
 *   · La entrada es **BGR crudo en 0-255**, sin dividir entre 255 y sin
 *     permutar canales, con relleno de 114 abajo y a la derecha. Normalizarla
 *     ---que es lo que pide casi cualquier otra familia--- deja el modelo mudo.
 *   · Con vista elevada o gran angular hace falta **teselar**: meter 1920x864
 *     en una entrada de 640 aplica un factor de 0,33 y un objeto de 40 px pasa
 *     a 13. Medido: 5 objetos a cuadro completo frente a 42 teselando.
 *   · Una caja que toca el borde de una tesela interior está **truncada por
 *     construcción**; la vecina tiene el objeto entero. Descartarla sube el
 *     recall del 81 % al 87 %.
 *   · El umbral va **bajo** (0,15). La puntuación no distingue "objeto lejano"
 *     de "mancha"; la perspectiva y el filtro de inmovilidad sí. Filtrar por
 *     física aguas abajo es mejor que por confianza aguas arriba, porque lo
 *     descartado en la detección ya no se recupera.
 */

"use strict";

const COCO_NAMES = {
  0: "person", 1: "bicycle", 2: "car", 3: "motorcycle", 5: "bus",
  7: "truck", 15: "cat", 16: "dog",
};

/**
 * Categoría gruesa por clase fina.
 *
 * Medido contra un modelo de referencia sobre un frame real: `truck` se
 * confundió con `car` **40 veces**, `bus` con `car` 10, y el 35 % de lo que
 * emitíamos no tenía respaldo --- casi todo "coches" que no existían.
 *
 * La causa de fondo es que el NMS era **por clase**: la misma furgoneta emitía
 * una caja `car` y otra `truck`, ninguna suprimía a la otra, y salían dos
 * objetos donde había uno. Eso explica a la vez "detecta coches donde no hay" y
 * que la etiqueta baile entre furgoneta y coche mirando el mismo vehículo.
 *
 * La distinción turismo/furgoneta/camión no es fiable a estos tamaños y
 * fingirla es peor que no darla. Se agrupa, se suprime dentro del grupo, y la
 * subclase queda como pista con su confianza --- disponible para quien la
 * quiera, no como identidad del objeto.
 */
const COARSE = {
  person: "person",
  bicycle: "bike", motorcycle: "bike",
  car: "vehicle", bus: "vehicle", truck: "vehicle",
  cat: "animal", dog: "animal",
};

const DET_DEFAULTS = {
  scoreThresh: 0.15,
  // Umbral por clase. Una persona a media distancia es un objeto mucho más
  // difícil que un turismo: menos píxeles, silueta variable, y casi siempre
  // parcialmente ocluida por otra persona. Aplicarle el mismo listón que a un
  // vehículo es lo que produce la queja de "detecta furgonetas pero no gente".
  classThresh: {person: 0.08, bicycle: 0.10, motorcycle: 0.12},
  sweep: false,           // barrido por filas, legible como un escáner
  soft: true,             // supresión suave: mejor con objetos adyacentes
  softSigma: 0.5,
  // Corte del soft-NMS. 0,06 era demasiado permisivo y producía una mejora
  // FALSA: en una escena con tres peatones reales emitía diez "personas", y las
  // siete de más eran cajas diminutas sobre conos y señales con puntuación por
  // debajo de 0,3. Contar detecciones no es una métrica de calidad --- caí en
  // ello midiendo mi propia mejora.
  //
  // La distribución de puntuación en una escena real es bimodal: los objetos
  // verdaderos se agrupan arriba (0,85-0,91 aquí) y la basura abajo (<0,3), con
  // un hueco limpio en medio. 0,20 cae en ese hueco.
  softCut: 0.20,
  nmsIou: 0.55,
  tileOverlap: 0.45,
  edgeMargin: 4,
  minTileFrac: 1.35,
  // Filtro de plausibilidad. Ninguno de los dos inventa calidad: recortan lo
  // que el modelo ya ha emitido. `minBoxPx` es el más honesto de los dos ---
  // una caja de 6 px de alto no puede ser una persona a ninguna distancia, y
  // el modelo no lo sabe porque no conoce la escena.
  allow: ["person", "vehicle", "bike"],
  minBoxPx: 0,
  maxTiles: 24,          // techo de coste por frame
  classes: COCO_NAMES,
};

/* ==========================================================================
   Utilidades
   ========================================================================== */

/**
 * NMS agrupado por categoría gruesa, no por clase fina.
 *
 * Suprimir solo dentro de la misma clase deja pasar duplicados del mismo objeto
 * etiquetado de dos formas. Agrupar arregla el duplicado y, de paso, hace que la
 * caja ganadora sea la de mayor puntuación del grupo, que es la apuesta correcta.
 */
function nmsClassAware(boxes, scores, labels, iouThresh, groupOf) {
  const keep = [];
  const byClass = new Map();
  for (let i = 0; i < labels.length; i++) {
    const g = groupOf ? groupOf(labels[i]) : labels[i];
    if (!byClass.has(g)) byClass.set(g, []);
    byClass.get(g).push(i);
  }
  for (const idx of byClass.values()) {
    idx.sort((a, b) => scores[b] - scores[a]);
    const alive = idx.slice();
    while (alive.length) {
      const i = alive.shift();
      keep.push(i);
      for (let k = alive.length - 1; k >= 0; k--) {
        const j = alive[k];
        const x1 = Math.max(boxes[i][0], boxes[j][0]);
        const y1 = Math.max(boxes[i][1], boxes[j][1]);
        const x2 = Math.min(boxes[i][2], boxes[j][2]);
        const y2 = Math.min(boxes[i][3], boxes[j][3]);
        if (x2 <= x1 || y2 <= y1) continue;
        const inter = (x2 - x1) * (y2 - y1);
        const ai = (boxes[i][2] - boxes[i][0]) * (boxes[i][3] - boxes[i][1]);
        const aj = (boxes[j][2] - boxes[j][0]) * (boxes[j][3] - boxes[j][1]);
        if (inter / (ai + aj - inter) >= iouThresh) alive.splice(k, 1);
      }
    }
  }
  return keep;
}

/**
 * Soft-NMS con decaimiento gaussiano.
 *
 * El NMS duro **borra** toda caja que solape más del umbral con una mejor. En
 * una acera con gente andando junta eso elimina personas reales: dos peatones
 * adyacentes se solapan legítimamente, y el segundo desaparece.
 *
 * Soft-NMS no borra: **rebaja la puntuación** en proporción al solape. Una caja
 * muy solapada con otra idéntica se hunde bajo el umbral y muere; una que solo
 * roza sobrevive con la puntuación algo menor. El resultado es el mismo para
 * duplicados y muy distinto para vecinos.
 */
function softNMS(boxes, scores, labels, {sigma = 0.5, cut = 0.06, groupOf = null} = {}) {
  const keep = [];
  const groups = new Map();
  for (let i = 0; i < labels.length; i++) {
    const g = groupOf ? groupOf(labels[i]) : labels[i];
    if (!groups.has(g)) groups.set(g, []);
    groups.get(g).push(i);
  }

  for (const idx of groups.values()) {
    const s = idx.map((i) => scores[i]);
    const alive = idx.slice();
    while (alive.length) {
      let best = 0;
      for (let k = 1; k < alive.length; k++) {
        if (s[idx.indexOf(alive[k])] > s[idx.indexOf(alive[best])]) best = k;
      }
      const i = alive.splice(best, 1)[0];
      if (s[idx.indexOf(i)] < cut) continue;
      keep.push(i);

      for (let k = alive.length - 1; k >= 0; k--) {
        const j = alive[k];
        const x1 = Math.max(boxes[i][0], boxes[j][0]);
        const y1 = Math.max(boxes[i][1], boxes[j][1]);
        const x2 = Math.min(boxes[i][2], boxes[j][2]);
        const y2 = Math.min(boxes[i][3], boxes[j][3]);
        if (x2 <= x1 || y2 <= y1) continue;
        const inter = (x2 - x1) * (y2 - y1);
        const ai = (boxes[i][2] - boxes[i][0]) * (boxes[i][3] - boxes[i][1]);
        const aj = (boxes[j][2] - boxes[j][0]) * (boxes[j][3] - boxes[j][1]);
        const ov = inter / (ai + aj - inter);
        const pos = idx.indexOf(j);
        s[pos] *= Math.exp(-(ov * ov) / sigma);
        if (s[pos] < cut) alive.splice(k, 1);
      }
    }
  }
  return {keep, scores: keep.map((i) => scores[i])};
}

/**
 * Funde recortes que se solapan.
 *
 * Dos objetos cercanos caen dentro del mismo recorte: tratarlos por separado
 * paga dos inferencias por la misma región y, peor, parte por la mitad al que
 * quede junto al borde del recorte del otro.
 */
function mergeCrops(crops, iouThresh = 0.25) {
  const out = [];
  for (const c of crops.slice().sort((a, b) => (b.x2 - b.x1) - (a.x2 - a.x1))) {
    let merged = false;
    for (const o of out) {
      const x1 = Math.max(c.x1, o.x1), y1 = Math.max(c.y1, o.y1);
      const x2 = Math.min(c.x2, o.x2), y2 = Math.min(c.y2, o.y2);
      if (x2 <= x1 || y2 <= y1) continue;
      const inter = (x2 - x1) * (y2 - y1);
      const ac = (c.x2 - c.x1) * (c.y2 - c.y1);
      const ao = (o.x2 - o.x1) * (o.y2 - o.y1);
      if (inter / Math.min(ac, ao) < iouThresh) continue;
      o.x1 = Math.min(o.x1, c.x1); o.y1 = Math.min(o.y1, c.y1);
      o.x2 = Math.max(o.x2, c.x2); o.y2 = Math.max(o.y2, c.y2);
      // El objeto de referencia del recorte fundido es el MENOR: es el que fija
      // cuánto zoom hace falta para que se resuelva.
      o.obj = Math.min(o.obj, c.obj);
      merged = true;
      break;
    }
    if (!merged) out.push({...c});
  }
  return out;
}

/* ==========================================================================
   Detector
   ========================================================================== */

class YoloxDetector {
  constructor(session, opts = {}) {
    this.sess = session;
    this.cfg = {...DET_DEFAULTS, ...opts};
    this.inputName = session.inputNames[0];
    // El tamaño de entrada se lee del modelo: yolox_nano y tiny son 416,
    // yolox_s es 640, y usar el equivocado desplaza todas las cajas en vez de
    // producir un error.
    const dims = session.inputMetadata?.[0]?.shape
      || session.inputMetadata?.[this.inputName]?.dims;
    this.size = (Array.isArray(dims) && typeof dims[2] === "number" && dims[2] > 0)
      ? dims[2] : (opts.inputSize || 416);
    this._grids = new Map();
    this._plan = new Map();
    // Por defecto la tesela iguala la entrada (sin zoom). `setZoom` la reduce.
    this.tile = opts.tile || this.size;
    this._floor = Math.min(this.cfg.scoreThresh,
      ...Object.values(this.cfg.classThresh || {}));
    this.canvas = null;
    this.ctx = null;
    this.stats = {frames: 0, tiles: 0, ms: 0};
  }

  /**
   * Ajusta el zoom de análisis.
   *
   * `zoom = 2` recorta teselas de la mitad de lado y las amplía a la entrada
   * del modelo, así que todo lo que hay dentro llega al doble de tamaño. Es lo
   * que separa "no detecta peatones" de "los detecta": un detector entrenado
   * con objetos de 32 px hacia arriba no puede resolver uno de 12, y ningún
   * umbral lo arregla --- hay que darle más píxeles.
   *
   * El coste es cuadrático: al doble de zoom, cuatro veces más teselas.
   */
  /**
   * Cambia umbrales y filtros en caliente.
   *
   * Recargar el modelo para mover un umbral cuesta varios segundos y el
   * operador deja de probar. `_floor` hay que recalcularlo: es el corte barato
   * de `_decode` y si se queda con el valor viejo, bajar un umbral no tiene
   * ningún efecto y parece que el control está roto.
   */
  tune(cfg = {}) {
    Object.assign(this.cfg, cfg);
    this._floor = Math.min(this.cfg.scoreThresh,
      ...Object.values(this.cfg.classThresh || {}));
    return this.cfg;
  }

  /**
   * Encuadre completo: el cuadro entero en una sola inferencia.
   *
   * Era la escala que FALTABA. Todos los modos partían de una tesela de lado
   * fijo --- 416 px de la fuente a ×1, menos con más zoom --- y ninguno miraba
   * nunca el cuadro entero. Sobre una cámara fija que vigila una plaza eso es
   * correcto: los objetos son pequeños y la tesela les da píxeles. Sobre un
   * móvil apuntando a lo que tiene delante es lo contrario, porque el objeto
   * es MAYOR que la tesela y entonces:
   *
   *   · una caja que toca el borde interior de una tesela se descarta --- es
   *     la invariante 1, y es correcta --- así que un objeto que no cabe en
   *     ninguna tesela no se detecta en ninguna;
   *   · los trozos que sí sobreviven salen como objetos independientes, y a
   *     veces puntúan MÁS que la caja entera.
   *
   * Medido con yolox_nano sobre fotos reales, teselas de 416 contra cuadro
   * completo:
   *
   *   bus.jpg 810x1080 (un autobús y cuatro personas)
   *     teselas:  2 objetos, 12 inferencias, 2571 ms --- sin el autobús
   *     completo: 5 objetos,  1 inferencia,   251 ms --- autobús 0,88
   *   zidane.jpg 1280x720 (dos personas grandes)
   *     teselas:  3 "personas", 15 inferencias --- tres trozos de dos personas
   *     completo: 2 personas,    1 inferencia --- las cajas correctas
   *
   * Diez veces más barato y con los objetos que importan. Fundir las dos
   * escalas se probó y es peor: el trozo puntúa más alto que el objeto entero
   * y sobrevive a la supresión, por IoU y por contención.
   */
  setFrameFit(on = true) {
    this.fitFrame = !!on;
    this._plan.clear();
    this._sched = null;
    return this;
  }

  setZoom(zoom) {
    if (zoom === "completo" || zoom === 0) return this.setFrameFit(true);
    this.fitFrame = false;
    // El suelo baja de 64 a 40 px de tesela: a ×6 sobre una entrada de 416 eso
    // son teselas de 69 px, y a ×10 de 41. Medido sobre una vista aérea con
    // peatones de 8-12 px, más allá de ×2 el recuento CAE --- 67 objetos a ×2,
    // 32 a ×3, 3 a ×6 --- y no es el descarte de bordes: desactivarlo solo pasa
    // de 32 a 35.
    //
    // La razón es que ampliar no crea información. Un peatón de 10 px llevado a
    // 60 sigue teniendo diez píxeles de detalle, ahora interpolados: el
    // detector busca rasgos que no existen a ninguna escala. Y la tesela
    // pequeña además le quita el contexto que necesita para reconocer la forma.
    //
    // El techo se sube igualmente porque hay escenas donde sí ayuda, y porque
    // es una decisión del operador. Pero por encima de ×3 la interfaz lo avisa.
    this.tile = Math.max(40, Math.round(this.size / Math.max(0.5, zoom)));
    this._plan.clear();
    this._sched = null;
    return this;
  }

  static async load(modelUrlOrBytes, ortLib, opts = {}) {
    const ort = ortLib || (typeof globalThis !== "undefined" ? globalThis.ort : null);
    if (!ort) throw new Error("ONNX Runtime no está cargado");
    // WebGPU cuando exista, si no WASM. En un portátil sin GPU accesible la
    // diferencia entre ambos es de un orden de magnitud, así que conviene
    // intentarlo y no asumir.
    const providers = opts.providers
      || (typeof navigator !== "undefined" && navigator.gpu ? ["webgpu", "wasm"] : ["wasm"]);
    let bytes = modelUrlOrBytes;
    if (typeof modelUrlOrBytes === "string") {
      const res = await fetch(modelUrlOrBytes);
      if (!res.ok) throw new Error(`no se pudo cargar el modelo (${res.status})`);
      bytes = new Uint8Array(await res.arrayBuffer());
    }
    const sess = await ort.InferenceSession.create(bytes, {
      executionProviders: providers,
      graphOptimizationLevel: "all",
    });
    return new YoloxDetector(sess, opts);
  }

  /**
   * Una inferencia en vacío, para que la primera de verdad no llegue tarde.
   *
   * ONNX Runtime reserva su arena, resuelve el grafo y compila los kernels en
   * la PRIMERA ejecución. Esa cuesta varias veces lo que las siguientes. Si
   * cae sobre el primer frame de cámara, el arranque se ve como un tirón --- y
   * como el hilo del detector descarta los frames que llegan mientras hay uno
   * en curso, se traga además los primeros barridos. Hacerla aquí la paga el
   * arranque, que es donde no molesta.
   *
   * Devuelve además lo que ha tardado, que es el único número que convierte
   * «va lenta» en un dato: el coste de una inferencia EN ESTE aparato, sin
   * cámara, sin teselas y sin nada más de por medio.
   *
   * @param {object} [ortLib] el runtime; por defecto el global, igual que
   *   en el resto de métodos --- no se resuelve por el ámbito léxico a
   *   propósito: con ORT cargado como módulo no habría ningún `ort` global.
   * @returns {Promise<number>} milisegundos de esa inferencia.
   */
  async warmup(ortLib) {
    const ort = ortLib || globalThis.ort;
    if (!ort) throw new Error("ONNX Runtime no está cargado");
    const now = () => (globalThis.performance ? performance.now() : Date.now());
    const t0 = now();
    const input = new ort.Tensor("float32",
      new Float32Array(3 * this.size * this.size),
      [1, 3, this.size, this.size]);
    const out = await this.sess.run({[this.inputName]: input});
    // Leer la salida no es decorativo: con WebGPU el resultado vive en la GPU
    // y solo al pedirlo se espera de verdad a que la inferencia termine. Sin
    // esta línea el número medido sería el de encolar el trabajo.
    if (!out[this.sess.outputNames[0]]?.data) throw new Error("el modelo no devolvió nada");
    return Math.round(now() - t0);
  }

  /* -------------------------------------------------------------------- */

  _grid(size) {
    if (this._grids.has(size)) return this._grids.get(size);
    const gx = [], gy = [], st = [];
    for (const stride of [8, 16, 32]) {
      const n = size / stride;
      for (let y = 0; y < n; y++) {
        for (let x = 0; x < n; x++) { gx.push(x); gy.push(y); st.push(stride); }
      }
    }
    const g = {gx: Float32Array.from(gx), gy: Float32Array.from(gy),
               st: Float32Array.from(st)};
    // Solo hay un puñado de tamaños de entrada posibles (416, 640); una cota
    // baja basta y evita que la caché crezca si alguien cambia de modelo.
    if (this._grids.size >= 4) this._grids.clear();
    this._grids.set(size, g);
    return g;
  }

  _tiles(w, h) {
    // Encuadre completo: una sola tesela con todo dentro. El letterbox de
    // `_preprocess` se encarga de encajarlo en la entrada del modelo.
    if (this.fitFrame) return [[0, 0, w, h]];
    // Camino rápido sin caché: un recorte que cabe entero en la tesela no
    // necesita plan. `detectFoveal` cambia `this.tile` en cada recorte, así
    // que cachear por (w,h,tile) generaba una entrada por recorte y luego se
    // limpiaba entera --- coste de memoria sin ninguna reutilización.
    const t0 = this.tile || this.size;
    if (w <= t0 && h <= t0) return [[0, 0, w, h]];

    const key = `${w}x${h}:${this.tile}`;
    if (this._plan.has(key)) return this._plan.get(key);
    // El lado de la tesela es independiente del lado de entrada del modelo.
    // Igualarlos --- que es lo que hacía esta función --- impide el recurso más
    // eficaz contra objetos pequeños: recortar una tesela MENOR que la entrada
    // y dejar que el letterbox la amplíe. Una persona de 20 px en una tesela de
    // 208 llega al modelo a 40 px.
    const t = this.tile || this.size;
    let tiles;
    if (w < t * this.cfg.minTileFrac && h < t * this.cfg.minTileFrac) {
      tiles = [[0, 0, w, h]];
    } else {
      const step = Math.max(1, Math.round(t * (1 - this.cfg.tileOverlap)));
      const xs = [], ys = [];
      for (let x = 0; x + t <= w; x += step) xs.push(x);
      if (!xs.length || xs[xs.length - 1] + t < w) xs.push(Math.max(0, w - t));
      for (let y = 0; y + t <= h; y += step) ys.push(y);
      if (!ys.length || ys[ys.length - 1] + t < h) ys.push(Math.max(0, h - t));
      tiles = [];
      for (const y of [...new Set(ys)].sort((a, b) => a - b)) {
        for (const x of [...new Set(xs)].sort((a, b) => a - b)) {
          tiles.push([x, y, Math.min(w, x + t), Math.min(h, y + t)]);
        }
      }
      if (tiles.length > this.cfg.maxTiles) {
        // Antes que exceder el presupuesto, se reduce el solape: perder algún
        // objeto de borde es preferible a que el frame tarde segundos.
        const keepEvery = Math.ceil(tiles.length / this.cfg.maxTiles);
        tiles = tiles.filter((_, i) => i % keepEvery === 0);
      }
    }
    this._plan.set(key, tiles);
    return tiles;
  }

  /** Letterbox al estilo YOLOX: BGR crudo, relleno 114 abajo/derecha. */
  _preprocess(imageData, tw, th) {
    const size = this.size;
    const r = Math.min(size / th, size / tw);
    const nw = Math.round(tw * r), nh = Math.round(th * r);
    const out = new Float32Array(3 * size * size).fill(114);
    const {data, width} = imageData;

    for (let y = 0; y < nh; y++) {
      const sy = Math.min(th - 1, Math.floor(y / r));
      for (let x = 0; x < nw; x++) {
        const sx = Math.min(tw - 1, Math.floor(x / r));
        const s = (sy * width + sx) * 4;
        const d = y * size + x;
        // El tensor va en BGR porque es lo que espera el modelo; el canvas
        // entrega RGBA.
        out[d] = data[s + 2];
        out[size * size + d] = data[s + 1];
        out[2 * size * size + d] = data[s];
      }
    }
    return {tensor: out, ratio: r};
  }

  _decode(raw, ratio, tw, th) {
    const size = this.size;
    const {gx, gy, st} = this._grid(size);
    const n = gx.length;
    const stride = raw.length / n;          // 85 en COCO
    const nCls = stride - 5;

    const boxes = [], scores = [], labels = [];
    const thr = this.cfg.scoreThresh;

    for (let i = 0; i < n; i++) {
      const o = i * stride;
      const obj = raw[o + 4];
      // El corte barato usa el umbral MÁS BAJO de todos, no el general: con el
      // general se descartaría a las personas antes de mirar de qué clase son.
      if (obj < this._floor * 0.4) continue;

      let best = 0, bestV = raw[o + 5];
      for (let c = 1; c < nCls; c++) {
        const v = raw[o + 5 + c];
        if (v > bestV) { bestV = v; best = c; }
      }
      const score = obj * bestV;
      if (!(best in this.cfg.classes)) continue;
      const name = this.cfg.classes[best];
      if (score < (this.cfg.classThresh?.[name] ?? thr)) continue;
      // Lista blanca por categoría gruesa, no por clase fina: la fina no es
      // fiable a estos tamaños y filtrar por ella dejaría fuera la misma
      // furgoneta según la etiquete "truck" o "car".
      if (this.cfg.allow && this.cfg.allow.length
          && !this.cfg.allow.includes(COARSE[name] || "other")) continue;

      // Decodificación de la rejilla: sin esto no hay detección válida.
      const s = st[i];
      const cx = (raw[o] + gx[i]) * s;
      const cy = (raw[o + 1] + gy[i]) * s;
      const bw = Math.exp(Math.min(10, raw[o + 2])) * s;
      const bh = Math.exp(Math.min(10, raw[o + 3])) * s;

      const x1 = Math.max(0, (cx - bw / 2) / ratio);
      const y1 = Math.max(0, (cy - bh / 2) / ratio);
      const x2 = Math.min(tw, (cx + bw / 2) / ratio);
      const y2 = Math.min(th, (cy + bh / 2) / ratio);
      if (x2 - x1 < 2 || y2 - y1 < 2) continue;
      if (this.cfg.minBoxPx && y2 - y1 < this.cfg.minBoxPx) continue;

      boxes.push([x1, y1, x2, y2]);
      scores.push(score);
      labels.push(best);
    }
    return {boxes, scores, labels};
  }

  /* -------------------------------------------------------------------- */

  /**
   * Detecta sobre una fuente dibujable (video, canvas, ImageBitmap).
   * `roi` acota la zona, en píxeles de la fuente.
   */
  async detect(source, ortLib, {roi = null, width = 0, height = 0} = {}) {
    const ort = ortLib || globalThis.ort;
    const t0 = (typeof performance !== "undefined" ? performance.now() : Date.now());

    const sw = width || source.videoWidth || source.width;
    const sh = height || source.videoHeight || source.height;
    const rx = roi ? Math.round(roi.x) : 0;
    const ry = roi ? Math.round(roi.y) : 0;
    const rw = roi ? Math.round(roi.w) : sw;
    const rh = roi ? Math.round(roi.h) : sh;
    if (rw < 16 || rh < 16) return [];

    if (!this.canvas) {
      this.canvas = document.createElement("canvas");
      this.ctx = this.canvas.getContext("2d", {alpha: false, willReadFrequently: true});
    }

    const tiles = this._tiles(rw, rh);
    const allBoxes = [], allScores = [], allLabels = [];

    for (const [tx1, ty1, tx2, ty2] of tiles) {
      const tw = tx2 - tx1, th = ty2 - ty1;
      if (this.canvas.width !== tw || this.canvas.height !== th) {
        this.canvas.width = tw; this.canvas.height = th;
      }
      this.ctx.drawImage(source, rx + tx1, ry + ty1, tw, th, 0, 0, tw, th);
      const img = this.ctx.getImageData(0, 0, tw, th);

      const {tensor, ratio} = this._preprocess(img, tw, th);
      const input = new ort.Tensor("float32", tensor, [1, 3, this.size, this.size]);
      const out = await this.sess.run({[this.inputName]: input});
      const raw = out[this.sess.outputNames[0]].data;

      const {boxes, scores, labels} = this._decode(raw, ratio, tw, th);
      const m = this.cfg.edgeMargin;
      for (let i = 0; i < boxes.length; i++) {
        const b = boxes[i];
        // Trozo truncado en una tesela interior: la vecina lo tiene entero.
        if ((b[0] <= m && tx1 > 0) || (b[1] <= m && ty1 > 0)
            || (b[2] >= tw - m && tx2 < rw) || (b[3] >= th - m && ty2 < rh)) continue;
        allBoxes.push([rx + tx1 + b[0], ry + ty1 + b[1],
                       rx + tx1 + b[2], ry + ty1 + b[3]]);
        allScores.push(scores[i]);
        allLabels.push(labels[i]);
      }
    }

    let dets = [];
    if (allBoxes.length) {
      // NMS global: el solape entre teselas produce la misma detección dos
      // veces, y sin fusionarlas el tracker vería dos objetos donde hay uno.
      const group = (l) => COARSE[this.cfg.classes[l]] || "other";
      const keep = this.cfg.soft
        ? softNMS(allBoxes, allScores, allLabels,
                  {sigma: this.cfg.softSigma, cut: this.cfg.softCut, groupOf: group}).keep
        : nmsClassAware(allBoxes, allScores, allLabels, this.cfg.nmsIou, group);
      dets = keep.map((i) => {
        const fine = this.cfg.classes[allLabels[i]] || "object";
        return {b: allBoxes[i], score: allScores[i],
                c: COARSE[fine] || "other",   // identidad: categoría gruesa
                fine,                          // pista: subclase, no fiable
                };
      });
    }

    this.stats.frames++;
    this.stats.tiles += tiles.length;
    this.stats.ms = (typeof performance !== "undefined" ? performance.now() : Date.now()) - t0;
    return dets;
  }

  /* ==================================================================== *
   *  Barrido incremental
   * ====================================================================
   *  Analizar las 24 teselas en cada frame es lo que hace lento el sistema, y
   *  no hace falta: entre dos frames un objeto se mueve unos píxeles, y el
   *  tracker lo sigue solo. Lo que el detector aporta es *entrar y salir* de
   *  escena, y eso ocurre en escalas de segundos.
   *
   *  Así que se procesan K teselas por pasada, en rotación, y cada tesela se
   *  refresca cada ceil(N/K) pasadas. Con nano a ~10 ms por tesela en un equipo
   *  normal y K=2, una pasada cuesta ~20 ms y el cuadro entero se recorre unas
   *  tres veces por segundo --- mientras el bucle de render sigue a la tasa de
   *  la pantalla porque el tracker no espera al detector.
   *
   *  Las teselas no rotan a ciegas: las que produjeron detecciones o tienen
   *  movimiento suben de prioridad. Una tesela de cielo se visita, pero tarde.
   * ==================================================================== */

  /**
   * Barrido ordenado por filas, de arriba abajo.
   *
   * La rotación por prioridad visita las teselas en el orden que más conviene
   * al detector, pero salta por la imagen: para un operador es imposible saber
   * qué zona lleva más tiempo sin revisar.
   *
   * Recorrerlas por filas convierte el barrido en algo legible --- una banda
   * que baja, como el cabezal de un escáner --- y eso no es decoración: la
   * posición de la banda ES dónde está mirando el detector, y la antigüedad de
   * cada caja dice de cuándo es el dato. Un recuadro que lleva ocho segundos
   * sin confirmarse debe verse distinto de uno recién visto.
   *
   * Se conserva la prioridad dentro de cada fila: la banda baja de forma
   * regular, pero dentro de la franja se mira antes donde hay personas.
   */
  _sweepOrder(tiles, budget) {
    if (!this._sweepRow && this._sweepRow !== 0) this._sweepRow = 0;
    const rows = new Map();
    for (let i = 0; i < tiles.length; i++) {
      const y = tiles[i][1];
      if (!rows.has(y)) rows.set(y, []);
      rows.get(y).push(i);
    }
    const keys = [...rows.keys()].sort((a, b) => a - b);
    if (!keys.length) return [];

    // Cursor DENTRO de la fila, no solo la fila.
    //
    // La primera versión llevaba solo el índice de fila y decidía avanzar
    // mirando cuántas teselas había elegido. Con presupuesto 3 y filas de 7
    // teselas, nunca se cumplía la condición de avance: la banda se quedaba
    // clavada en la fila 0 repitiendo las mismas tres teselas para siempre.
    // Un barrido que no barre.
    if (this._sweepCol === undefined) this._sweepCol = 0;
    const out = [];
    let guard = 0;
    while (out.length < budget && guard++ < keys.length * 4) {
      const y = keys[this._sweepRow % keys.length];
      const inRow = rows.get(y).slice();
      // Dentro de la fila, primero lo que más actividad ha tenido.
      inRow.sort((a, b) => (this._sched[b]?.priority || 1) - (this._sched[a]?.priority || 1));

      const take = inRow.slice(this._sweepCol, this._sweepCol + (budget - out.length));
      out.push(...take);
      this._sweepCol += take.length;

      if (this._sweepCol >= inRow.length) {
        // Fila agotada: bajar a la siguiente.
        this._sweepCol = 0;
        this._sweepRow++;
      }
    }
    // Posición continua: fila + progreso dentro de la fila.
    //
    // Informar solo la fila hace que la banda avance a saltos del alto de una
    // tesela. Interpolando con el avance dentro de la fila, la posición que se
    // publica es fina y la barra se mueve de forma continua sin dejar de ser
    // real.
    const fila = this._sweepRow % keys.length;
    const alto = keys.length > 1 ? keys[1] - keys[0] : (tiles[0] ? tiles[0][3] : 0);
    const enFila = rows.get(keys[fila])?.length || 1;
    const frac = Math.min(1, this._sweepCol / enFila);

    this.sweep = {
      row: fila,
      col: this._sweepCol,
      rows: keys.length,
      y: (keys[fila] || 0) + alto * frac,
      height: keys.length > 1 ? keys[1] - keys[0] : (tiles[0] ? tiles[0][3] : 0),
    };
    return out;
  }

  _initSchedule(nTiles) {
    if (this._sched && this._sched.length === nTiles) return;
    this._sched = Array.from({length: nTiles}, (_, i) => ({
      i, lastSeen: -1e9, priority: 1,
    }));
    this._pass = 0;
  }

  /**
   * Una pasada: analiza `budget` teselas y devuelve sus detecciones junto con
   * las teselas cubiertas, para que quien llama sepa qué parte del cuadro está
   * fresca.
   */
  async detectIncremental(source, ortLib, opts = {}) {
    const ort = ortLib || globalThis.ort;
    // `priority` es una FUNCIÓN del rectángulo de tesela, no un array indexado.
    // Con un array, cualquier cambio del recorte entre la llamada que lo generó
    // y ésta desalinea los índices en silencio y la atención se aplica a las
    // teselas equivocadas. Una función no puede desalinearse.
    const {roi = null, budget = 2, priority = null, width = 0, height = 0} = opts;
    const t0 = performance.now();

    const sw = width || source.videoWidth || source.width;
    const sh = height || source.videoHeight || source.height;
    const rx = roi ? Math.round(roi.x) : 0;
    const ry = roi ? Math.round(roi.y) : 0;
    const rw = roi ? Math.round(roi.w) : sw;
    const rh = roi ? Math.round(roi.h) : sh;
    if (rw < 16 || rh < 16) return {dets: [], tiles: [], full: false};

    if (!this.canvas) {
      this.canvas = document.createElement("canvas");
      this.ctx = this.canvas.getContext("2d", {alpha: false, willReadFrequently: true});
    }

    const tiles = this._tiles(rw, rh);
    this._initSchedule(tiles.length);
    this._pass++;

    // Puntuación: antigüedad (para que ninguna tesela quede sin visitar nunca)
    // más prioridad acumulada por actividad.
    // Prioridad interna (actividad pasada) por la externa (dónde dice el
    // cerebro que hay personas ahora). La antigüedad multiplica para que
    // ninguna tesela quede sin visitar nunca, por poco interesante que sea.
    const scored = this._sched.map((s) => ({
      ...s,
      score: (this._pass - s.lastSeen) * s.priority
             * (typeof priority === "function" ? (priority(tiles[s.i]) || 1) : 1),
    })).sort((a, b) => b.score - a.score);
    const chosen = scored.slice(0, Math.max(1, budget)).map((s) => s.i);

    const boxes = [], scores = [], labels = [], covered = [];
    for (const idx of chosen) {
      const [tx1, ty1, tx2, ty2] = tiles[idx];
      const tw = tx2 - tx1, th = ty2 - ty1;
      if (this.canvas.width !== tw || this.canvas.height !== th) {
        this.canvas.width = tw; this.canvas.height = th;
      }
      this.ctx.drawImage(source, rx + tx1, ry + ty1, tw, th, 0, 0, tw, th);
      const img = this.ctx.getImageData(0, 0, tw, th);

      const {tensor, ratio} = this._preprocess(img, tw, th);
      const input = new ort.Tensor("float32", tensor, [1, 3, this.size, this.size]);
      const out = await this.sess.run({[this.inputName]: input});
      const raw = out[this.sess.outputNames[0]].data;
      const d = this._decode(raw, ratio, tw, th);

      const m = this.cfg.edgeMargin;
      let kept = 0;
      for (let i = 0; i < d.boxes.length; i++) {
        const b = d.boxes[i];
        if ((b[0] <= m && tx1 > 0) || (b[1] <= m && ty1 > 0)
            || (b[2] >= tw - m && tx2 < rw) || (b[3] >= th - m && ty2 < rh)) continue;
        boxes.push([rx + tx1 + b[0], ry + ty1 + b[1], rx + tx1 + b[2], ry + ty1 + b[3]]);
        scores.push(d.scores[i]);
        labels.push(d.labels[i]);
        kept++;
      }

      const slot = this._sched[idx];
      slot.lastSeen = this._pass;
      // Una tesela con objetos merece volver antes; una vacía puede esperar.
      // El suelo de 0,35 evita que una zona quede excluida para siempre por
      // haber estado vacía un rato.
      slot.priority = Math.max(0.35, Math.min(4, kept ? slot.priority * 1.4 + 0.6
                                                       : slot.priority * 0.75));
      covered.push([rx + tx1, ry + ty1, rx + tx2, ry + ty2]);
    }

    let dets = [];
    if (boxes.length) {
      // La MISMA supresión que `detect`, no otra.
      //
      // Aquí se usaba `nmsClassAware` a secas mientras `detect` usaba soft-NMS
      // con `softCut`. La diferencia no es de estilo: `softCut` es además el
      // suelo de puntuación final, y sin él pasaba todo lo que superara el
      // umbral por clase --- 0,08 para persona. Y `detectIncremental` es el
      // camino que corre el bucle principal, así que el corte que la
      // invariante 3 fijó midiendo («un corte demasiado bajo produjo +75 % de
      // personas que eran conos y señales») no se estaba aplicando donde más
      // importa. Medido sobre una foto con dos personas: 6 cajas por este
      // camino, 2 por el otro. Las cuatro de más iban entre 0,09 y 0,13.
      const group = (l) => COARSE[this.cfg.classes[l]] || "other";
      const keep = this.cfg.soft
        ? softNMS(boxes, scores, labels,
                  {sigma: this.cfg.softSigma, cut: this.cfg.softCut, groupOf: group}).keep
        : nmsClassAware(boxes, scores, labels, this.cfg.nmsIou, group);
      dets = keep.map((i) => {
        const fine = this.cfg.classes[labels[i]] || "object";
        return {b: boxes[i], score: scores[i], c: COARSE[fine] || "other", fine};
      });
    }

    this.stats.frames++;
    this.stats.tiles += chosen.length;
    this.stats.ms = performance.now() - t0;
    this.stats.lastBudget = chosen.length;
    return {dets, tiles: covered, full: chosen.length >= tiles.length,
            nTiles: tiles.length, ms: this.stats.ms,
            // Dónde está la banda AHORA, en píxeles de la región. Quien pinta
            // no debe inventarse la posición: una barra decorativa que no
            // coincide con lo que el detector mira es una mentira sobre el
            // estado del sistema.
            sweep: this.sweep ? {...this.sweep, x: rx, y: ry + this.sweep.y} : null};
  }

  /**
   * Detección multiescala: se recorre el cuadro a varios zooms y se fusiona.
   *
   * Un solo zoom obliga a elegir: ×1 ve bien los vehículos y pierde a la gente,
   * ×2 ve a la gente y parte los vehículos grandes en trozos que el descarte de
   * bordes elimina. No hay un zoom bueno para ambos porque los tamaños difieren
   * en un orden de magnitud.
   *
   * Fusionar cuesta la suma de las pasadas y no necesita nada más: la supresión
   * agrupada ya sabe resolver la misma detección vista dos veces.
   */
  async detectMultiScale(source, ortLib, {zooms = [1, 2], ...opts} = {}) {
    const all = [];
    const original = this.tile;
    for (const z of zooms) {
      this.setZoom(z);
      const d = await this.detect(source, ortLib, opts);
      // De qué escala viene cada caja: útil para diagnosticar y para preferir
      // la escala adecuada al tamaño del objeto si hiciera falta.
      for (const x of d) all.push({...x, zoom: z});
    }
    this.tile = original;
    this._plan.clear();
    if (all.length < 2) return all;

    const boxes = all.map((d) => d.b);
    const scores = all.map((d) => d.score);
    const labels = all.map((d) => d.c);
    const {keep} = softNMS(boxes, scores, labels,
                           {sigma: this.cfg.softSigma, cut: this.cfg.softCut,
                            groupOf: (c) => COARSE[c] || c});
    return keep.map((i) => all[i]);
  }

  /**
   * Detección en pirámide: rejilla gruesa que localiza, rejilla fina que mira.
   *
   * El multiescala plano recorre el cuadro entero a ×1 **y** a ×2, y eso gasta
   * el grueso del cómputo donde no hace falta: el ×2 solo aporta donde hay
   * objetos pequeños, y en una vista con perspectiva eso es una franja, no toda
   * la imagen.
   *
   * Aquí el barrido grueso hace dos cosas a la vez: detecta lo grande y **dice
   * dónde mirar de cerca**. Una celda se refina si cumple alguna de estas:
   *
   *   · contiene una detección pequeña --- donde hay un objeto pequeño suele
   *     haber más, y probablemente alguno se ha quedado por debajo del umbral;
   *   · contiene una detección de confianza dudosa, que con más píxeles puede
   *     confirmarse o caerse;
   *   · el modelo de perspectiva dice que ahí un objeto típico mide menos de
   *     `minObjectPx`. Esto es lo que hace la pirámide *adaptada a la escena*
   *     en lugar de a la imagen: la franja lejana se refina siempre, la cercana
   *     nunca, y eso se sabe sin haber detectado nada.
   *
   * Las cajas del nivel grueso se conservan: refinar no es repetir, es añadir.
   */
  async detectPyramid(source, ortLib, {
    roi = null, coarseZoom = 1, fineZoom = 2,
    smallFrac = 0.10,        // objeto "pequeño": lado < esta fracción de tesela
    uncertain = [0.15, 0.45],
    // Techo como FRACCIÓN de las celdas, no como número absoluto. Refinarlo
    // todo convierte la pirámide en un multiescala plano más caro: medido, en
    // una escena pequeña sin perspectiva se refinaron 6 de 6 celdas y el
    // resultado tardó el doble que el plano.
    maxRefineFrac = 0.45,
    // Por encima de esta fracción de celdas candidatas, la pirámide se rinde y
    // delega en el multiescala plano.
    uniformFrac = 0.6,
    perspective = null,      // {a, b} de la escala aprendida
    minObjectPx = 48,
    maxRefine = 12,          // techo de celdas a refinar por barrido
    ...opts
  } = {}) {
    const ort = ortLib || globalThis.ort;
    const t0 = performance.now();
    const original = this.tile;

    // --- nivel grueso: cubre todo -------------------------------------- //
    this.setZoom(coarseZoom);
    const coarse = await this.detect(source, ort, {roi, ...opts});
    const coarseTiles = this._tiles(
      roi ? Math.round(roi.w) : (source.videoWidth || source.width),
      roi ? Math.round(roi.h) : (source.videoHeight || source.height));

    // --- decidir qué celdas merecen una segunda mirada ------------------ //
    const rx = roi ? Math.round(roi.x) : 0;
    const ry = roi ? Math.round(roi.y) : 0;
    const candidates = coarseTiles.map((t, i) => {
      const [x1, y1, x2, y2] = t;
      const side = Math.min(x2 - x1, y2 - y1);
      let score = 0;

      for (const d of coarse) {
        const cx = d.b[0] - rx + (d.b[2] - d.b[0]) / 2;
        const cy = d.b[1] - ry + (d.b[3] - d.b[1]) / 2;
        if (cx < x1 || cx > x2 || cy < y1 || cy > y2) continue;
        const objSide = Math.min(d.b[2] - d.b[0], d.b[3] - d.b[1]);
        if (objSide < side * smallFrac) score += 2;
        if (d.score >= uncertain[0] && d.score <= uncertain[1]) score += 1;
      }

      if (perspective && perspective.a) {
        // Altura esperada de un objeto típico en el centro de la celda, según
        // la escala aprendida. No necesita que se haya detectado nada.
        const yc = ry + (y1 + y2) / 2;
        const expected = perspective.a * yc + perspective.b;
        if (expected > 0 && expected < minObjectPx) score += 3;
      }
      return {i, t, score};
    }).filter((c) => c.score > 0).sort((a, b) => b.score - a.score);

    // Si casi TODAS las celdas piden refinado, la pirámide no aplica: eso no
    // es una escena con mezcla de escalas, es una escena uniformemente lejana,
    // y recortar al 45 % descarta objetos reales a ciegas.
    //
    // Medido en una vista aérea de tráfico: el modelo de perspectiva marcaba
    // las 21 celdas, el techo dejaba 9, y se perdía un tercio de los objetos de
    // alta confianza (26 frente a 39). En esa situación lo correcto es el
    // multiescala plano, más caro pero completo.
    if (candidates.length >= coarseTiles.length * uniformFrac) {
      // Toda la escena pide resolución fina: la pirámide no aporta. Se completa
      // con un barrido fino de cuadro entero y se FUSIONA con el grueso que ya
      // está hecho, en vez de tirarlo y rehacer los dos niveles. Sin esto, la
      // decisión de no usar pirámide costaba un 16 % de tiempo.
      this.stats.strategy = "plano (escena uniformemente lejana)";
      this.setZoom(fineZoom);
      const dense = await this.detect(source, ort, {roi, ...opts});
      this.tile = original;
      this._plan.clear();

      const all = coarse.map((d) => ({...d, level: "grueso"}))
                        .concat(dense.map((d) => ({...d, level: "fino"})));
      this.stats.ms = performance.now() - t0;
      this.stats.refined = 0;
      this.stats.coarseTiles = coarseTiles.length;
      if (all.length < 2) return all;
      const {keep} = softNMS(all.map((d) => d.b), all.map((d) => d.score),
                             all.map((d) => d.c),
                             {sigma: this.cfg.softSigma, cut: this.cfg.softCut,
                              groupOf: (c) => COARSE[c] || c});
      return keep.map((i) => all[i]);
    }
    this.stats.strategy = "piramide";

    const scored = candidates.slice(0, Math.max(1, Math.min(maxRefine,
      Math.round(coarseTiles.length * maxRefineFrac))));

    // --- nivel fino: solo en esas celdas -------------------------------- //
    const fine = [];
    if (scored.length) {
      for (const cell of scored) {
        const [x1, y1, x2, y2] = cell.t;
        const cw = x2 - x1, ch = y2 - y1;
        const sub = {x: rx + x1, y: ry + y1, w: cw, h: ch};

        // UNA inferencia por celda, no cuatro.
        //
        // La versión anterior fijaba el zoom fino y llamaba a `detect` sobre la
        // subregión, que volvía a trocearla: una celda de 416 px a zoom 2 se
        // partía en cuatro teselas de 208. Cuatro inferencias para mirar de
        // cerca un trozo que cabe entero en la entrada del modelo.
        //
        // Poniendo el lado de tesela igual al de la celda, el plan devuelve una
        // sola tesela y el letterbox se encarga de la ampliación --- que es
        // exactamente el zoom que se buscaba, gratis.
        this.tile = Math.max(cw, ch);
        this._plan.clear();
        const d = await this.detect(source, ort, {...opts, roi: sub});
        for (const q of d) fine.push({...q, level: "fino"});
      }
    }

    this.tile = original;
    this._plan.clear();

    const all = coarse.map((d) => ({...d, level: "grueso"})).concat(fine);
    if (all.length < 2) {
      this.stats.ms = performance.now() - t0;
      return all;
    }
    const {keep} = softNMS(all.map((d) => d.b), all.map((d) => d.score),
                           all.map((d) => d.c),
                           {sigma: this.cfg.softSigma, cut: this.cfg.softCut,
                            groupOf: (c) => COARSE[c] || c});
    this.stats.ms = performance.now() - t0;
    this.stats.refined = scored.length;
    this.stats.coarseTiles = coarseTiles.length;
    return keep.map((i) => all[i]);
  }

  /**
   * Detección centrada en el objeto: un recorte por candidato, con el zoom que
   * ese candidato necesita.
   *
   * Es la diferencia entre mirar **dónde puede haber algo** y mirar **lo que
   * hay**. Una rejilla, por fina que sea, reparte el presupuesto por geometría:
   * una tesela de 416 px que contiene un coche de 27 px se lo entrega al modelo
   * a 27 px, y las otras veinte teselas gastan lo mismo mirando tejados.
   *
   * Aquí la sustracción de fondo dice dónde se mueve algo --- cuesta 6 ms para
   * el cuadro entero --- y cada mancha recibe un recorte a su medida, ampliado
   * para que el objeto ocupe una fracción decente de la entrada del modelo. Ese
   * mismo coche llega a ~190 px.
   *
   * El contexto alrededor no es opcional: un modelo entrenado con objetos en
   * su entorno falla si le das el objeto recortado a ras. Se deja `context`
   * veces el tamaño del objeto alrededor.
   *
   * Coste: una inferencia por candidato. Con veinte objetos es comparable a un
   * barrido de rejilla, pero cada inferencia se gasta en un objeto en lugar de
   * en cielo.
   */
  async detectObjects(source, ortLib, {
    regions = [],            // [[x1,y1,x2,y2], ...] en píxeles de la fuente
    context = 2.2,           // cuánto entorno se incluye alrededor del objeto
    fill = 0.45,             // fracción de la entrada que debe ocupar el objeto
    maxObjects = 24,
    minSide = 6,
    roi = null,
    ...opts
  } = {}) {
    const ort = ortLib || globalThis.ort;
    const t0 = performance.now();
    const sw = source.videoWidth || source.width;
    const sh = source.videoHeight || source.height;
    const original = this.tile;

    if (!this.canvas) {
      this.canvas = document.createElement("canvas");
      this.ctx = this.canvas.getContext("2d", {alpha: false, willReadFrequently: true});
    }

    // Los candidatos grandes primero: si hay que recortar por presupuesto, es
    // preferible perder una mancha diminuta que un vehículo entero.
    const cand = regions
      .filter((r) => Math.min(r[2] - r[0], r[3] - r[1]) >= minSide)
      .sort((a, b) => ((b[2] - b[0]) * (b[3] - b[1])) - ((a[2] - a[0]) * (a[3] - a[1])))
      .slice(0, maxObjects);

    const boxes = [], scores = [], labels = [];
    for (const r of cand) {
      const ow = r[2] - r[0], oh = r[3] - r[1];
      const cx = (r[0] + r[2]) / 2, cy = (r[1] + r[3]) / 2;

      // Lado del recorte: el objeto más su contexto, cuadrado para que el
      // letterbox no desperdicie mitad de la entrada en relleno.
      let side = Math.max(ow, oh) * context;
      // Y acotado para que el objeto no supere `fill` de la entrada: ampliar
      // más allá de eso no añade información, solo interpola píxeles.
      side = Math.max(side, Math.max(ow, oh) / fill);
      side = Math.min(side, Math.min(sw, sh));

      const x1 = Math.max(0, Math.min(sw - side, cx - side / 2));
      const y1 = Math.max(0, Math.min(sh - side, cy - side / 2));
      const w = Math.round(Math.min(side, sw - x1));
      const h = Math.round(Math.min(side, sh - y1));
      if (w < 8 || h < 8) continue;

      if (this.canvas.width !== w || this.canvas.height !== h) {
        this.canvas.width = w; this.canvas.height = h;
      }
      this.ctx.drawImage(source, Math.round(x1), Math.round(y1), w, h, 0, 0, w, h);
      const img = this.ctx.getImageData(0, 0, w, h);
      const {tensor, ratio} = this._preprocess(img, w, h);
      const input = new ort.Tensor("float32", tensor, [1, 3, this.size, this.size]);
      const out = await this.sess.run({[this.inputName]: input});
      const d = this._decode(out[this.sess.outputNames[0]].data, ratio, w, h);

      for (let i = 0; i < d.boxes.length; i++) {
        const b = d.boxes[i];
        const g = [x1 + b[0], y1 + b[1], x1 + b[2], y1 + b[3]];
        // Solo se aceptan detecciones que solapan con el candidato que motivó
        // el recorte. Sin esto, cada recorte aporta además los vecinos que caen
        // dentro de su contexto, y el mismo objeto entra por varios recortes.
        const ix1 = Math.max(g[0], r[0]), iy1 = Math.max(g[1], r[1]);
        const ix2 = Math.min(g[2], r[2]), iy2 = Math.min(g[3], r[3]);
        if (ix2 <= ix1 || iy2 <= iy1) continue;
        const inter = (ix2 - ix1) * (iy2 - iy1);
        const areaR = (r[2] - r[0]) * (r[3] - r[1]);
        if (inter < 0.25 * areaR) continue;

        boxes.push(g);
        scores.push(d.scores[i]);
        labels.push(d.labels[i]);
      }
    }

    this.tile = original;
    this._plan.clear();
    this.stats.ms = performance.now() - t0;
    this.stats.objects = cand.length;
    this.stats.strategy = "por objeto";

    if (!boxes.length) return [];
    const group = (l) => COARSE[this.cfg.classes[l]] || "other";
    const keep = this.cfg.soft
      ? softNMS(boxes, scores, labels,
                {sigma: this.cfg.softSigma, cut: this.cfg.softCut, groupOf: group}).keep
      : nmsClassAware(boxes, scores, labels, this.cfg.nmsIou, group);
    return keep.map((i) => {
      const fine = this.cfg.classes[labels[i]] || "object";
      return {b: boxes[i], score: scores[i], c: COARSE[fine] || "other", fine,
              level: "objeto"};
    });
  }

  /**
   * Detección foveada: una ventana por foco de actividad, con su propio zoom.
   *
   * La rejilla —por fina que sea— tiene un defecto de origen: recorre el cuadro
   * entero, y en una vista fija la mayor parte del cuadro es cielo, tejado y
   * fachada donde nunca pasa nada. Escanearlo es trabajo garantizado sin
   * resultado.
   *
   * Aquí manda el movimiento. La sustracción de fondo ya dice **dónde** hay
   * algo, y cuesta 0,6 ms; lo que no sabe es **qué** es. Así que se abre una
   * ventana alrededor de cada foco y se pregunta al detector solo ahí.
   *
   * Y cada ventana lleva **el zoom que su objeto necesita**, no uno global: un
   * peatón de 12 px se amplía ×8 y llega al modelo a 96; un autobús de 120 px
   * se mira a ×1. Con un zoom único hay que elegir a quién perder.
   *
   * El agrupamiento no es un detalle de eficiencia. Cuarenta objetos serían
   * cuarenta inferencias --- peor que la rejilla. Los focos cercanos comparten
   * ventana, y en tráfico real eso reduce las decenas a unas pocas.
   *
   * @param targets cajas donde hay actividad, en píxeles del origen
   */
  async detectFoveated(source, ortLib, {
    targets = [],
    roi = null,
    context = 2.6,           // ventana = objeto x este factor
    maxZoom = 6,
    // Ventana mínima. 64 px parecía suficiente y no lo es: un recorte tan
    // ajustado contiene el objeto y nada más, y un detector necesita **ver el
    // entorno** para reconocer la forma. Medido con ventanas de 64: catorce
    // ventanas, cero detecciones.
    minWindow = 128,
    maxWindows = 10,
    // Tamaño mínimo con el que un objeto debe llegar al modelo. YOLOX empieza
    // a resolver alrededor de 32 px; por debajo de 24 la detección es lotería.
    minDetectPx = 28,
    minTargetPx = 9,
    ...opts
  } = {}) {
    const ort = ortLib || globalThis.ort;
    const t0 = performance.now();
    // Objetivos demasiado pequeños son ruido de la sustracción de fondo, no
    // objetos. Gastar una ventana en ellos desplaza a objetivos reales, porque
    // el orden prioriza lo pequeño.
    targets = targets.filter((b) => Math.min(b[2] - b[0], b[3] - b[1]) >= minTargetPx);
    if (!targets.length) return [];

    const sw = source.videoWidth || source.width;
    const sh = source.videoHeight || source.height;
    const rx = roi ? Math.round(roi.x) : 0;
    const ry = roi ? Math.round(roi.y) : 0;
    const rw = roi ? Math.round(roi.w) : sw;
    const rh = roi ? Math.round(roi.h) : sh;

    // --- 1. ventana alrededor de cada foco ------------------------------ //
    let windows = targets.map((b) => {
      const w = b[2] - b[0], h = b[3] - b[1];
      const side = Math.max(minWindow, Math.max(w, h) * context);
      const cx = (b[0] + b[2]) / 2, cy = (b[1] + b[3]) / 2;
      return {
        x1: Math.max(rx, cx - side / 2), y1: Math.max(ry, cy - side / 2),
        x2: Math.min(rx + rw, cx + side / 2), y2: Math.min(ry + rh, cy + side / 2),
        smallest: Math.min(w, h),
      };
    });

    // --- 2. fusionar las que se solapan --------------------------------- //
    // Iterativo: fusionar dos ventanas puede hacer que la resultante toque una
    // tercera, y parar en la primera pasada dejaría solapes que se traducen en
    // inferencias duplicadas sobre el mismo trozo de imagen.
    let merged = true;
    while (merged && windows.length > 1) {
      merged = false;
      outer:
      for (let i = 0; i < windows.length; i++) {
        for (let j = i + 1; j < windows.length; j++) {
          const a = windows[i], b = windows[j];
          if (a.x2 < b.x1 || b.x2 < a.x1 || a.y2 < b.y1 || b.y2 < a.y1) continue;

          // La fusión NO puede destruir el zoom, que es la razón de ser de
          // todo esto. Si la ventana resultante es tan grande que el objeto
          // más pequeño que contiene ya no llega al modelo con píxeles
          // suficientes, no se fusiona: dos inferencias con zoom valen más que
          // una sin él.
          //
          // Medido antes de esta guarda: con 102 objetivos repartidos por una
          // avenida, la fusión los colapsaba en UNA ventana de calle entera,
          // el zoom caía a ×1 y el resultado bajaba de 39 objetos de alta
          // confianza a 2.
          const mw = Math.max(a.x2, b.x2) - Math.min(a.x1, b.x1);
          const mh = Math.max(a.y2, b.y2) - Math.min(a.y1, b.y1);
          const small = Math.min(a.smallest, b.smallest);
          if (small * (this.size / Math.max(mw, mh)) < minDetectPx) continue;

          windows[i] = {
            x1: Math.min(a.x1, b.x1), y1: Math.min(a.y1, b.y1),
            x2: Math.max(a.x2, b.x2), y2: Math.max(a.y2, b.y2),
            // La ventana fusionada hereda el objeto MÁS PEQUEÑO: es el que
            // fija el zoom necesario, y conformarse con el mayor lo perdería.
            smallest: Math.min(a.smallest, b.smallest),
          };
          windows.splice(j, 1);
          merged = true;
          break outer;
        }
      }
    }

    // Presupuesto: primero las ventanas con los objetos más pequeños, que son
    // las que el barrido normal pierde.
    // Orden por prioridad: lo pequeño primero --- es lo que el barrido normal
    // pierde --- pero ponderado por el área que cubre la ventana, para que un
    // objetivo diminuto y aislado no desplace a una ventana con varios objetos
    // dentro.
    windows.sort((a, b) => {
      const pa = (a.x2 - a.x1) * (a.y2 - a.y1) / Math.max(1, a.smallest);
      const pb = (b.x2 - b.x1) * (b.y2 - b.y1) / Math.max(1, b.smallest);
      return pb - pa;
    });
    windows = windows.slice(0, maxWindows);

    // --- 3. una inferencia por ventana, con su zoom --------------------- //
    const original = this.tile;
    const boxes = [], scores = [], labels = [], zooms = [];

    if (!this.canvas) {
      this.canvas = document.createElement("canvas");
      this.ctx = this.canvas.getContext("2d", {alpha: false, willReadFrequently: true});
    }

    for (const win of windows) {
      const ww = Math.round(win.x2 - win.x1);
      const wh = Math.round(win.y2 - win.y1);
      if (ww < 16 || wh < 16) continue;

      // El zoom sale del tamaño del objeto, no de una constante: se amplía lo
      // necesario para que el más pequeño de la ventana llegue al modelo con
      // píxeles suficientes, con tope para no pedir imposibles.
      const need = Math.min(maxZoom, Math.max(1, 40 / Math.max(4, win.smallest)));
      const target = Math.min(Math.max(ww, wh), Math.round(this.size / need));
      this.tile = Math.max(minWindow, target);
      this._plan.clear();

      if (this.canvas.width !== ww || this.canvas.height !== wh) {
        this.canvas.width = ww; this.canvas.height = wh;
      }
      this.ctx.drawImage(source, Math.round(win.x1), Math.round(win.y1), ww, wh,
                         0, 0, ww, wh);
      const img = this.ctx.getImageData(0, 0, ww, wh);
      const {tensor, ratio} = this._preprocess(img, ww, wh);
      const input = new ort.Tensor("float32", tensor, [1, 3, this.size, this.size]);
      const out = await this.sess.run({[this.inputName]: input});
      const d = this._decode(out[this.sess.outputNames[0]].data, ratio, ww, wh);

      for (let i = 0; i < d.boxes.length; i++) {
        const b = d.boxes[i];
        boxes.push([win.x1 + b[0], win.y1 + b[1], win.x1 + b[2], win.y1 + b[3]]);
        scores.push(d.scores[i]);
        labels.push(d.labels[i]);
        zooms.push(+(this.size / Math.max(ww, wh)).toFixed(1));
      }
    }

    this.tile = original;
    this._plan.clear();
    this.stats.ms = performance.now() - t0;
    this.stats.windows = windows.length;
    this.stats.targets = targets.length;

    if (!boxes.length) return [];
    const group = (l) => COARSE[this.cfg.classes[l]] || "other";
    const keep = this.cfg.soft
      ? softNMS(boxes, scores, labels,
                {sigma: this.cfg.softSigma, cut: this.cfg.softCut,
                 groupOf: (l) => group(l)}).keep
      : nmsClassAware(boxes, scores, labels, this.cfg.nmsIou, group);
    return keep.map((i) => {
      const fine = this.cfg.classes[labels[i]] || "object";
      return {b: boxes[i], score: scores[i], c: COARSE[fine] || "other",
              fine, zoom: zooms[i]};
    });
  }

  /**
   * Detección foveal: un recorte por objeto, con el zoom que ese objeto pide.
   *
   * La rejilla --- por muy adaptativa que sea --- reparte el cómputo por
   * geometría de la imagen, no por dónde hay algo. En una escena con seis
   * objetos, veintiuna teselas significan quince inferencias sobre asfalto
   * vacío.
   *
   * Aquí las propuestas mandan. La sustracción de fondo cuesta medio
   * milisegundo y dice dónde se mueve algo; el tracker dice dónde había algo
   * hace un instante. Con eso se recorta **alrededor de cada objeto** y se
   * amplía hasta que llegue al modelo con el tamaño que éste sabe resolver:
   * una mancha de 12 px se amplía ×10, una de 200 px no se amplía nada. El
   * zoom deja de ser un ajuste global y pasa a ser una propiedad de cada
   * objeto.
   *
   * Dos cosas que no son obvias y sin las cuales no funciona:
   *
   *   · **Contexto alrededor.** Un recorte pegado al objeto le quita al
   *     detector lo que necesita para reconocerlo --- suelo bajo los pies,
   *     cielo sobre el techo. El margen es proporcional, no fijo.
   *   · **Agrupar propuestas cercanas.** Dos peatones a diez píxeles caen en el
   *     mismo recorte; tratarlos por separado duplica la inferencia y además
   *     parte por la mitad al que quede en el borde.
   */
  async detectFoveal(source, ortLib, {
    proposals = [],          // [{x1,y1,x2,y2}] en píxeles de la región
    roi = null,
    targetPx = 160,          // tamaño al que se quiere ver el objeto
    context = 1.9,           // cuánto recorte alrededor, en múltiplos del objeto
    minCrop = 64,
    maxCrops = 10,
    sweepEvery = 0,          // cada N llamadas, además un barrido completo
    ...opts
  } = {}) {
    const ort = ortLib || globalThis.ort;
    const t0 = performance.now();
    const original = this.tile;

    const sw = source.videoWidth || source.width;
    const sh = source.videoHeight || source.height;
    const rx = roi ? Math.round(roi.x) : 0;
    const ry = roi ? Math.round(roi.y) : 0;
    const rw = roi ? Math.round(roi.w) : sw;
    const rh = roi ? Math.round(roi.h) : sh;

    if (!this.canvas) {
      this.canvas = document.createElement("canvas");
      this.ctx = this.canvas.getContext("2d", {alpha: false, willReadFrequently: true});
    }

    // --- 1. convertir propuestas en recortes con contexto --------------- //
    let crops = proposals.map((p) => {
      const w = p.x2 - p.x1, h = p.y2 - p.y1;
      const side = Math.max(minCrop, Math.round(Math.max(w, h) * context));
      const cx = (p.x1 + p.x2) / 2, cy = (p.y1 + p.y2) / 2;
      return {
        x1: Math.max(0, Math.round(cx - side / 2)),
        y1: Math.max(0, Math.round(cy - side / 2)),
        x2: Math.min(rw, Math.round(cx + side / 2)),
        y2: Math.min(rh, Math.round(cy + side / 2)),
        obj: Math.max(w, h),
      };
    }).filter((c) => c.x2 - c.x1 >= 16 && c.y2 - c.y1 >= 16);

    crops = mergeCrops(crops);
    // Los objetos más pequeños primero: son los que el barrido normal pierde y
    // los que más ganan con la ampliación.
    crops.sort((a, b) => a.obj - b.obj);
    crops = crops.slice(0, maxCrops);

    // --- 2. una inferencia por recorte ---------------------------------- //
    const all = [];
    for (const c of crops) {
      const cw = c.x2 - c.x1, ch = c.y2 - c.y1;
      // Lado de tesela = lado del recorte: el plan devuelve una sola tesela y
      // el letterbox hace la ampliación hasta la entrada del modelo. El zoom
      // efectivo es `this.size / lado`, y sale solo del tamaño del objeto.
      this.tile = Math.max(cw, ch);
      this._plan.clear();
      const d = await this.detect(source, ort,
                                  {...opts, roi: {x: rx + c.x1, y: ry + c.y1, w: cw, h: ch}});
      const zoom = this.size / Math.max(cw, ch);
      for (const q of d) all.push({...q, zoom: Math.round(zoom * 10) / 10, level: "foveal"});
    }

    // --- 3. barrido completo de vez en cuando --------------------------- //
    // Las propuestas solo ven lo que se mueve o lo que ya se seguía. Un objeto
    // que entra parado --- alguien que aparece por una puerta y espera --- no
    // genera propuesta nunca. Un barrido periódico es lo que impide que la
    // atención se quede ciega a lo que no se movió.
    this._fovealCalls = (this._fovealCalls || 0) + 1;
    if (sweepEvery && this._fovealCalls % sweepEvery === 0) {
      this.tile = original;
      this._plan.clear();
      const sweep = await this.detect(source, ort, {...opts, roi});
      for (const q of sweep) all.push({...q, level: "barrido"});
    }

    this.tile = original;
    this._plan.clear();
    this.stats.crops = crops.length;
    this.stats.ms = performance.now() - t0;
    if (all.length < 2) return all;

    const {keep} = softNMS(all.map((d) => d.b), all.map((d) => d.score),
                           all.map((d) => d.c),
                           {sigma: this.cfg.softSigma, cut: this.cfg.softCut,
                            groupOf: (c) => COARSE[c] || c});
    return keep.map((i) => all[i]);
  }

  /**
   * Detección por bandas de profundidad.
   *
   * Un zoom uniforme obliga a elegir un compromiso malo para toda la imagen: el
   * que sirve al fondo destroza el primer plano y al revés. Medido sobre una
   * vista aérea, el ×3 uniforme bajaba de 67 objetos a 32 --- la franja
   * cercana, donde el ×1 bastaba, se partía en teselas sin contexto.
   *
   * Pero la perspectiva aprendida dice el tamaño esperado del objeto en cada
   * fila. Con eso el zoom deja de ser una decisión global: la imagen se corta
   * en bandas horizontales y **cada banda recibe el zoom que necesita** para
   * llevar a sus objetos a un tamaño resoluble. El fondo se amplía mucho, el
   * primer plano nada, y el cómputo va donde hace falta.
   *
   * Es la misma idea que la pirámide, aplicada al eje donde la perspectiva
   * varía de verdad: la profundidad.
   *
   * RESULTADO MEDIDO, y es negativo: sobre la vista aérea de Alcalá da **27
   * objetos de alta confianza frente a los 42 del multiescala**, y con cinco
   * bandas baja a 19. La causa son las fronteras: cada banda es una franja
   * estrecha, se tesela por separado, y los objetos que caen junto a su borde
   * superior o inferior se descartan por truncados. Más bandas, más fronteras,
   * peor.
   *
   * Se conserva porque en una escena con rango de profundidad mucho mayor
   * --- una avenida larga vista de frente, donde el fondo está diez veces más
   * lejos que el primer plano --- el argumento sigue en pie y el multiescala
   * de dos niveles se queda corto. Pero no es la opción por defecto y no debe
   * presentarse como una mejora general, porque en las escenas medidas no lo
   * es.
   */
  async detectBanded(source, ortLib, {
    perspective,             // {a, b} de la escala aprendida — obligatorio
    roi = null,
    targetPx = 52,           // tamaño al que se quiere ver un objeto
    bands = 3,
    maxZoom = 4,
    ...opts
  } = {}) {
    const ort = ortLib || globalThis.ort;
    if (!perspective || !(perspective.a > 0)) {
      // Sin perspectiva no hay bandas posibles: se degrada al camino normal en
      // lugar de inventarse una división.
      return this.detectMultiScale(source, ort, {roi, ...opts});
    }
    const t0 = performance.now();
    const original = this.tile;

    const sw = source.videoWidth || source.width;
    const sh = source.videoHeight || source.height;
    const rx = roi ? Math.round(roi.x) : 0;
    const ry = roi ? Math.round(roi.y) : 0;
    const rw = roi ? Math.round(roi.w) : sw;
    const rh = roi ? Math.round(roi.h) : sh;

    const all = [];
    const plan = [];
    const step = rh / bands;

    for (let i = 0; i < bands; i++) {
      const y0 = Math.round(i * step);
      const y1 = Math.round((i + 1) * step);
      // Tamaño esperado en el CENTRO de la banda, en coordenadas de fuente.
      const yc = ry + (y0 + y1) / 2;
      const esperado = perspective.a * yc + perspective.b;
      if (!(esperado > 1)) continue;

      // Zoom que lleva ese tamaño al objetivo, acotado.
      const zoom = Math.max(1, Math.min(maxZoom, targetPx / esperado));
      this.tile = Math.max(40, Math.round(this.size / zoom));
      this._plan.clear();

      const sub = {x: rx, y: ry + y0, w: rw, h: y1 - y0};
      const d = await this.detect(source, ort, {...opts, roi: sub});
      for (const q of d) all.push({...q, band: i, zoom: +zoom.toFixed(1)});
      plan.push({band: i, y: [y0, y1], expected_px: Math.round(esperado),
                 zoom: +zoom.toFixed(1), tile: this.tile, found: d.length});
    }

    this.tile = original;
    this._plan.clear();
    this.stats.bands = plan;
    this.stats.ms = performance.now() - t0;
    if (all.length < 2) return all;

    const {keep} = softNMS(all.map((d) => d.b), all.map((d) => d.score),
                           all.map((d) => d.c),
                           {sigma: this.cfg.softSigma, cut: this.cfg.softCut,
                            groupOf: (c) => COARSE[c] || c});
    return keep.map((i) => all[i]);
  }

  /** Pasadas necesarias para recorrer el cuadro entero con el presupuesto dado. */
  sweepPasses(budget) {
    const n = this._sched ? this._sched.length : 1;
    return Math.ceil(n / Math.max(1, budget));
  }

  report() {
    return {frames: this.stats.frames,
            tiles_per_frame: +(this.stats.tiles / Math.max(1, this.stats.frames)).toFixed(1),
            last_ms: Math.round(this.stats.ms),
            input: this.size};
  }
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = {YoloxDetector, nmsClassAware, softNMS, mergeCrops,
                    COCO_NAMES, COARSE};
}
