/*
 * ARGOS · detector en hilo propio
 * ---------------------------------------------------------------------------
 * Hasta ahora el detector corría en el mismo hilo que la interfaz. Su ciclo
 * estaba desacoplado --- no se esperaba a la inferencia para pintar --- pero
 * JavaScript es de un solo hilo: mientras el modelo corre, **nada más corre**.
 * Una inferencia de 20 ms se come un frame entero de 16,7, y una de 120 ms se
 * come siete seguidos. El resultado es un tirón periódico que ningún ajuste de
 * cadencia arregla, porque el problema no es cuándo se lanza sino dónde.
 *
 * Un Worker tiene su propio hilo. La inferencia deja de competir con el
 * render.
 *
 * Dos detalles de implementación que condicionan el diseño:
 *
 *   · **El Worker no ve el DOM ni el elemento `<video>`.** No puede recortar
 *     de la fuente por sí mismo, así que el hilo principal le manda el píxel
 *     ya extraído. Eso cuesta una lectura de canvas --- que ya se hacía --- y
 *     una transferencia.
 *   · **`ImageBitmap` y `ArrayBuffer` son transferibles.** Se pasan por
 *     referencia, no se copian: enviar un frame de 2 MB cuesta microsegundos.
 *     Copiarlo costaría más que la propia inferencia en los casos rápidos.
 *
 * El fichero se usa de dos formas, y por eso no importa nada arriba: como
 * módulo normal desde el hilo principal (`DetectorWorker`), y como cuerpo del
 * Worker, construido a partir de este mismo código en un Blob para que el
 * fichero único siga siendo único.
 */

"use strict";

/* ==========================================================================
   Lado Worker
   ========================================================================== */

/**
 * Cuerpo del Worker, como texto.
 *
 * Se genera desde el bundle: `detector.js` ya está en la página, así que se
 * reutiliza su código fuente en lugar de duplicarlo. Duplicarlo sería
 * garantizar que las dos copias divergen.
 */
function workerSource(detectorSource, ortUrl) {
  return `
"use strict";
importScripts(${JSON.stringify(ortUrl)});

// El detector espera 'document' para su canvas interno. En un Worker no hay,
// pero sí OffscreenCanvas, que hace exactamente lo mismo sin DOM.
self.document = {
  createElement: () => new OffscreenCanvas(1, 1),
};

${detectorSource}

let detector = null;
let busy = false;

self.onmessage = async (e) => {
  const msg = e.data;
  try {
    if (msg.type === "load") {
      const providers = msg.webgpu && self.navigator?.gpu
        ? ["webgpu", "wasm"] : ["wasm"];
      // Los hilos de WASM solo existen si la página envía COOP/COEP. El
      // lanzador de escritorio las manda; abierto como fichero suelto, no.
      if (self.crossOriginIsolated) {
        ort.env.wasm.numThreads = Math.min(4, navigator.hardwareConcurrency || 1);
      }
      const sess = await ort.InferenceSession.create(msg.model, {
        executionProviders: providers,
        graphOptimizationLevel: "all",
      });
      detector = new YoloxDetector(sess, msg.cfg || {});
      if (msg.zoom) detector.setZoom(msg.zoom);
      self.postMessage({type: "ready",
                        size: detector.size,
                        threads: ort.env.wasm.numThreads || 1,
                        isolated: !!self.crossOriginIsolated});
      return;
    }

    if (msg.type === "detect") {
      // Si llega un frame mientras se procesa el anterior, se DESCARTA.
      //
      // Encolarlos parece más completo y es peor: la cola crece más rápido de
      // lo que se vacía, y el detector acaba analizando imágenes de hace diez
      // segundos. Más vale saltarse frames y responder sobre lo que se ve
      // ahora.
      if (busy || !detector) {
        msg.bitmap?.close?.();
        self.postMessage({type: "skipped", id: msg.id});
        return;
      }
      busy = true;
      const t0 = performance.now();
      const src = {width: msg.bitmap.width, height: msg.bitmap.height,
                   bitmap: msg.bitmap};
      // El detector dibuja desde 'source'; un ImageBitmap sirve directamente.
      const dets = msg.mode === "piramide"
        ? await detector.detectPyramid(msg.bitmap, ort, msg.opts || {})
        : msg.mode === "foveal"
          ? await detector.detectFoveal(msg.bitmap, ort, msg.opts || {})
          : await detector.detectMultiScale(msg.bitmap, ort, msg.opts || {});
      msg.bitmap.close?.();
      busy = false;
      self.postMessage({type: "result", id: msg.id, dets,
                        ms: performance.now() - t0,
                        stats: detector.report ? detector.report() : null});
      return;
    }
  } catch (err) {
    busy = false;
    self.postMessage({type: "error", id: msg.id, message: String(err && err.message || err)});
  }
};
`;
}

/* ==========================================================================
   Lado principal
   ========================================================================== */

const WORKER_DEFAULTS = {
  ortUrl: "https://cdn.jsdelivr.net/npm/onnxruntime-web@1.22.0/dist/ort.min.js",
  mode: "multiscale",
  webgpu: true,
  maxPending: 1,
};

class DetectorWorker {
  constructor(opts = {}) {
    this.cfg = {...WORKER_DEFAULTS, ...opts};
    this.worker = null;
    this.ready = false;
    this.size = 0;
    this.threads = 1;
    this.isolated = false;
    this.pending = 0;
    this.seq = 0;
    this.lastMs = 0;
    this.skipped = 0;
    this.done = 0;
    this.onResult = null;
    this.onError = null;
  }

  /**
   * Arranca el Worker con el código del detector ya cargado en la página.
   *
   * `detectorSource` es el texto de detector.js. En el fichero único va
   * embebido, así que se recupera del propio documento en lugar de volver a
   * descargarlo: no hay red de por medio y no puede desincronizarse.
   */
  async start(model, detectorSource, cfg = {}, zoom = 1) {
    if (typeof Worker === "undefined") {
      throw new Error("este navegador no expone Workers");
    }
    const blob = new Blob([workerSource(detectorSource, this.cfg.ortUrl)],
                          {type: "application/javascript"});
    const url = URL.createObjectURL(blob);
    this.worker = new Worker(url);
    // El objeto URL se libera en cuanto el Worker lo ha leído: mantenerlo vivo
    // es una fuga silenciosa que sobrevive al propio Worker.
    URL.revokeObjectURL(url);

    this.worker.onmessage = (e) => this._onMessage(e.data);
    this.worker.onerror = (e) => this.onError?.(e.message || "fallo en el worker");

    const bytes = model instanceof Uint8Array ? model : new Uint8Array(model);
    return new Promise((res, rej) => {
      this._resolveReady = res;
      this._rejectReady = rej;
      this.worker.postMessage(
        {type: "load", model: bytes, cfg, zoom, webgpu: this.cfg.webgpu},
        [bytes.buffer]);   // transferido, no copiado
    });
  }

  _onMessage(m) {
    if (m.type === "ready") {
      this.ready = true;
      this.size = m.size;
      this.threads = m.threads;
      this.isolated = m.isolated;
      this._resolveReady?.(m);
      return;
    }
    if (m.type === "result") {
      this.pending--;
      this.done++;
      this.lastMs = m.ms;
      this.onResult?.(m.dets, m);
      return;
    }
    if (m.type === "skipped") {
      this.pending--;
      this.skipped++;
      return;
    }
    if (m.type === "error") {
      this.pending--;
      this.onError?.(m.message);
      if (!this.ready) this._rejectReady?.(new Error(m.message));
    }
  }

  /**
   * Manda un frame. Devuelve `false` si se descarta por saturación.
   *
   * Se llama desde el bucle de render y NO se espera: el resultado llega por
   * `onResult` cuando llegue. Ese es el punto entero del ejercicio.
   */
  submit(source, opts = {}) {
    if (!this.ready || this.pending >= this.cfg.maxPending) {
      this.skipped++;
      return false;
    }
    let bitmap;
    try {
      // `createImageBitmap` sobre el vídeo: sin canvas intermedio y el
      // resultado es transferible.
      bitmap = source;
    } catch {
      return false;
    }
    this.pending++;
    this.worker.postMessage(
      {type: "detect", id: ++this.seq, bitmap, mode: this.cfg.mode, opts},
      [bitmap]);
    return true;
  }

  /** Captura el frame actual y lo envía. Une los dos pasos, que siempre van juntos. */
  async submitFrame(video, opts = {}) {
    if (!this.ready || this.pending >= this.cfg.maxPending) {
      this.skipped++;
      return false;
    }
    try {
      const bmp = await createImageBitmap(video);
      return this.submit(bmp, opts);
    } catch {
      return false;
    }
  }

  report() {
    return {
      ready: this.ready, input: this.size,
      threads: this.threads, isolated: this.isolated,
      last_ms: Math.round(this.lastMs),
      done: this.done, skipped: this.skipped, pending: this.pending,
    };
  }

  stop() {
    this.worker?.terminate();
    this.worker = null;
    this.ready = false;
  }
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = {DetectorWorker, workerSource};
}
