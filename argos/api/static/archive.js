/*
 * ARGOS · memoria
 * ---------------------------------------------------------------------------
 * Archivo persistente en IndexedDB. Sin esto, cerrar la pestaña borra la
 * sesión entera: un ojo que olvida al parpadear no es un ojo, es una ventana.
 *
 * Qué se guarda y qué no, que es la decisión de diseño:
 *
 *   SÍ   tubos --- trayectoria, siluetas RLE, clase, física, estatura
 *        eventos --- detenido, corriendo, merodeo, decorado
 *        escena --- escala métrica aprendida por cámara
 *
 *   NO   vídeo, ni frames, ni recortes de objeto
 *
 * Las siluetas comprimidas ocupan ~120 bytes por observación: ocho horas de una
 * escena concurrida caben en unas decenas de megas, frente a los gigas que
 * ocuparía el vídeo. Y hay una consecuencia que no es de espacio sino de
 * gobernanza: **un archivo sin imágenes no contiene datos personales
 * identificables**. Una silueta lleva postura, marcha, clase, tamaño y
 * posición; no lleva cara ni ropa reconocible.
 *
 * Guardar recortes sería posible y haría el sinopsis reproducible meses
 * después. Se deja fuera a propósito: es la diferencia entre un índice de
 * actividad y un archivo de vigilancia, y esa línea debe cruzarse de forma
 * consciente, no por defecto.
 */

"use strict";

const DB_NAME = "argos";
// v2: índice `wall` en tubos y eventos, necesario para que la retención opere
// por cursor en lugar de cargar el almacén entero.
const DB_VERSION = 2;

const STORE_TUBES = "tubes";
const STORE_EVENTS = "events";
const STORE_SCENES = "scenes";
const STORE_META = "meta";

/* ==========================================================================
   Apertura
   ========================================================================== */

function openDB() {
  return new Promise((resolve, reject) => {
    if (typeof indexedDB === "undefined") {
      reject(new Error("Este navegador no expone IndexedDB"));
      return;
    }
    const req = indexedDB.open(DB_NAME, DB_VERSION);

    req.onupgradeneeded = (e) => {
      const db = e.target.result;

      if (!db.objectStoreNames.contains(STORE_TUBES)) {
        const s = db.createObjectStore(STORE_TUBES, {keyPath: "key"});
        // Los índices son los que deciden qué preguntas se pueden hacer rápido.
        // El tiempo va primero en casi todas ellas: "qué pasó anoche" es una
        // consulta por rango temporal, no por clase.
        s.createIndex("t0", "t0");
        s.createIndex("wall", "wall");
        s.createIndex("cam_t0", ["cam", "t0"]);
        s.createIndex("cls_t0", ["cls", "t0"]);
      }
      if (!db.objectStoreNames.contains(STORE_EVENTS)) {
        const s = db.createObjectStore(STORE_EVENTS, {keyPath: "key"});
        s.createIndex("t", "t");
        s.createIndex("wall", "wall");
        s.createIndex("kind_t", ["kind", "t"]);
        s.createIndex("cam_t", ["cam", "t"]);
      }
      if (!db.objectStoreNames.contains(STORE_SCENES)) {
        db.createObjectStore(STORE_SCENES, {keyPath: "cam"});
      }
      if (!db.objectStoreNames.contains(STORE_META)) {
        db.createObjectStore(STORE_META, {keyPath: "k"});
      }
    };

    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error || new Error("no se pudo abrir la base"));
  });
}

/**
 * Empaqueta las observaciones en arrays tipados.
 *
 * IndexedDB guarda mediante clon estructurado, y un `Uint16Array` se almacena
 * como binario mientras que un array de números JavaScript se guarda como
 * objetos: medido, 200 bytes por observación frente a los ~120 esperados. La
 * diferencia es de más del 40 % del archivo entero.
 *
 * Los campos se separan por columnas en vez de por registro --- frames juntos,
 * cajas juntas, siluetas juntas --- porque así cada columna es homogénea y el
 * clon estructurado no intercala tipos.
 */
function packObs(obs, keepMasks = true) {
  const n = obs.length;
  const f = new Int32Array(n);
  const b = new Int16Array(n * 4);
  const runs = [];
  const runLen = new Uint16Array(n);

  for (let i = 0; i < n; i++) {
    f[i] = obs[i].f;
    const q = obs[i].b;
    b[i * 4] = Math.round(q[0]); b[i * 4 + 1] = Math.round(q[1]);
    b[i * 4 + 2] = Math.round(q[2]); b[i * 4 + 3] = Math.round(q[3]);
    if (keepMasks && obs[i].m?.length) {
      runLen[i] = obs[i].m.length;
      for (const v of obs[i].m) runs.push(v);
    }
  }
  return {n, f, b, runLen, runs: runs.length ? Uint16Array.from(runs) : null};
}

/** Deshace `packObs`. */
function unpackObs(packed) {
  if (!packed || Array.isArray(packed)) return packed || [];
  const {n, f, b, runLen, runs} = packed;
  const out = new Array(n);
  let cursor = 0;
  for (let i = 0; i < n; i++) {
    let m = null;
    if (runs && runLen[i]) {
      m = Array.from(runs.subarray(cursor, cursor + runLen[i]));
      cursor += runLen[i];
    }
    out[i] = {f: f[i], b: [b[i * 4], b[i * 4 + 1], b[i * 4 + 2], b[i * 4 + 3]], m};
  }
  return out;
}

const tx = (db, stores, mode) => db.transaction(stores, mode);

function done(t) {
  return new Promise((res, rej) => {
    t.oncomplete = () => res();
    t.onerror = () => rej(t.error);
    t.onabort = () => rej(t.error || new Error("transacción abortada"));
  });
}

function all(request) {
  return new Promise((res, rej) => {
    request.onsuccess = () => res(request.result);
    request.onerror = () => rej(request.error);
  });
}

/* ==========================================================================
   Archivo
   ========================================================================== */

const ARCHIVE_DEFAULTS = {
  camera: "cam-1",
  retentionDays: 30,
  maxTubes: 200000,
  flushEvery: 25,          // tubos acumulados antes de escribir
  keepSilhouettes: true,
};

class Archive {
  constructor(opts = {}) {
    this.cfg = {...ARCHIVE_DEFAULTS, ...opts};
    this.db = null;
    this.pending = [];
    this.pendingEvents = [];
    this.written = 0;
    this.seen = new Set();     // ids ya archivados en esta sesión
    this.session = `${Date.now().toString(36)}`;
    this.error = null;
  }

  async open() {
    try {
      this.db = await openDB();
      await this._touchMeta();
      return true;
    } catch (err) {
      // La memoria es opcional: si falla, el análisis en vivo sigue. Lo que no
      // puede pasar es que un fallo de almacenamiento tumbe la aplicación.
      this.error = err.message;
      return false;
    }
  }

  get available() { return !!this.db; }

  async _touchMeta() {
    const t = tx(this.db, [STORE_META], "readwrite");
    t.objectStore(STORE_META).put({k: "last_open", v: new Date().toISOString()});
    await done(t);
  }

  /* ------------------------------------------------------------------ */
  /*  Escritura                                                          */
  /* ------------------------------------------------------------------ */

  /**
   * Encola un tubo terminado. La escritura va por lotes: IndexedDB abre una
   * transacción por llamada, y hacerlo por cada objeto en una escena concurrida
   * satura el hilo con transacciones diminutas.
   */
  push(tube, brain = null) {
    if (!this.db || !tube.obs?.length) return;
    const id = `${this.session}:${tube.id}`;
    if (this.seen.has(id)) return;
    this.seen.add(id);

    const first = tube.obs[0], last = tube.obs[tube.obs.length - 1];
    const b = tube.obs.map((o) => o.b);
    const xs = b.map((q) => (q[0] + q[2]) / 2);
    const ys = b.map((q) => q[3]);

    const rec = {
      key: id,
      cam: this.cfg.camera,
      cls: tube.klass || "other",
      t0: first.f,
      t1: last.f,
      n: tube.obs.length,
      // Caja envolvente de toda la trayectoria: permite filtrar por zona sin
      // recorrer las observaciones.
      bbox: [Math.min(...b.map((q) => q[0])), Math.min(...b.map((q) => q[1])),
             Math.max(...b.map((q) => q[2])), Math.max(...b.map((q) => q[3]))],
      path: xs.map((x, i) => [Math.round(x), Math.round(ys[i])]),
      obs: packObs(tube.obs, this.cfg.keepSilhouettes),
      wall: Date.now(),
    };

    if (brain) {
      const fp = brain.fingerprintOf?.(tube.id);
      // La huella se guarda como Float32Array: 324 valores que en JSON serían
      // varios kilobytes y en binario son 1,3 KB.
      if (fp) rec.fp = fp;
      const p = brain.physics.get(tube.id);
      if (p) rec.physics = p.summary();
      const s = brain.statureOf?.(tube.id) || brain.statureHistory?.get(tube.id);
      if (s) rec.stature = s;
    }

    this.pending.push(rec);
    if (this.pending.length >= this.cfg.flushEvery) this.flush();
  }

  pushEvent(ev, camera = null) {
    if (!this.db) return;
    this.pendingEvents.push({
      key: `${this.session}:${ev.kind}:${ev.id}:${Math.round(ev.t * 1000)}`,
      cam: camera || this.cfg.camera,
      kind: ev.kind, id: ev.id, cls: ev.cls,
      t: Math.round(ev.t * 1000), wall: Date.now(),
      data: {...ev},
    });
    if (this.pendingEvents.length >= this.cfg.flushEvery) this.flush();
  }

  async flush() {
    if (!this.db || (!this.pending.length && !this.pendingEvents.length)) return 0;
    const tubes = this.pending.splice(0);
    const events = this.pendingEvents.splice(0);
    try {
      const t = tx(this.db, [STORE_TUBES, STORE_EVENTS], "readwrite");
      const st = t.objectStore(STORE_TUBES);
      const se = t.objectStore(STORE_EVENTS);
      for (const r of tubes) st.put(r);
      for (const e of events) se.put(e);
      await done(t);
      this.written += tubes.length;
      return tubes.length;
    } catch (err) {
      this.error = err.message;
      return 0;
    }
  }

  async saveScene(scale, camera = null) {
    if (!this.db || !scale?.valid) return;
    const t = tx(this.db, [STORE_SCENES], "readwrite");
    t.objectStore(STORE_SCENES).put({
      cam: camera || this.cfg.camera,
      a: scale.a, b: scale.b, sigma: scale.sigma, n: scale.n,
      ySpan: scale.ySpan,
      classScale: Object.fromEntries(scale.classScale || []),
      wall: Date.now(),
    });
    await done(t);
  }

  async loadScene(camera = null) {
    if (!this.db) return null;
    const t = tx(this.db, [STORE_SCENES], "readonly");
    return all(t.objectStore(STORE_SCENES).get(camera || this.cfg.camera));
  }

  /* ------------------------------------------------------------------ */
  /*  Consulta                                                           */
  /* ------------------------------------------------------------------ */

  /**
   * Busca tubos. Todos los filtros son opcionales y se aplican en el orden
   * más barato primero: el índice acota por tiempo o clase, y lo demás se
   * filtra en memoria sobre un conjunto ya pequeño.
   */
  async query({cls = null, since = null, until = null, camera = null,
               zone = null, minSpeed = null, limit = 500} = {}) {
    if (!this.db) return [];
    const t = tx(this.db, [STORE_TUBES], "readonly");
    const store = t.objectStore(STORE_TUBES);

    let req;
    if (cls) {
      const lo = [cls, since ?? -Infinity], hi = [cls, until ?? Infinity];
      req = store.index("cls_t0").getAll(IDBKeyRange.bound(lo, hi));
    } else if (since !== null || until !== null) {
      req = store.index("t0").getAll(
        IDBKeyRange.bound(since ?? -Infinity, until ?? Infinity));
    } else {
      req = store.getAll();
    }

    let rows = await all(req);
    if (camera) rows = rows.filter((r) => r.cam === camera);
    if (zone) {
      const [zx1, zy1, zx2, zy2] = zone;
      // Intersección de la envolvente: barato y suficiente para acotar. Quien
      // necesite exactitud puede recorrer `path` después.
      rows = rows.filter((r) => !(r.bbox[2] < zx1 || r.bbox[0] > zx2
                                  || r.bbox[3] < zy1 || r.bbox[1] > zy2));
    }
    if (minSpeed !== null) {
      rows = rows.filter((r) => (r.physics?.speed_m_s ?? -1) >= minSpeed);
    }
    rows.sort((a, b) => b.wall - a.wall);
    // Se devuelve desempaquetado: quien consulta quiere observaciones, no el
    // formato de almacenamiento.
    return rows.slice(0, limit).map((r) => ({...r, obs: unpackObs(r.obs)}));
  }

  async events({kind = null, since = null, until = null, limit = 300} = {}) {
    if (!this.db) return [];
    const t = tx(this.db, [STORE_EVENTS], "readonly");
    const store = t.objectStore(STORE_EVENTS);
    const req = kind
      ? store.index("kind_t").getAll(
          IDBKeyRange.bound([kind, since ?? -Infinity], [kind, until ?? Infinity]))
      : store.index("t").getAll(
          IDBKeyRange.bound(since ?? -Infinity, until ?? Infinity));
    const rows = await all(req);
    rows.sort((a, b) => b.wall - a.wall);
    return rows.slice(0, limit);
  }

  async stats() {
    if (!this.db) return {available: false, error: this.error};
    const t = tx(this.db, [STORE_TUBES, STORE_EVENTS], "readonly");
    const nt = await all(t.objectStore(STORE_TUBES).count());
    const ne = await all(t.objectStore(STORE_EVENTS).count());
    let quota = null;
    try {
      if (navigator.storage?.estimate) {
        const e = await navigator.storage.estimate();
        quota = {used_mb: Math.round(e.usage / 1e5) / 10,
                 quota_mb: Math.round(e.quota / 1e6)};
      }
    } catch { /* no disponible */ }
    return {available: true, tubes: nt, events: ne, written: this.written,
            pending: this.pending.length, quota, error: this.error};
  }

  /* ------------------------------------------------------------------ */
  /*  Retención y borrado                                                */
  /* ------------------------------------------------------------------ */

  /**
   * Aplica la retención. Es una obligación, no una optimización: el plazo de
   * supresión de la videovigilancia es de un mes por defecto (art. 22 LOPDGDD),
   * y un archivo que no caduca solo es un incumplimiento que crece.
   */
  async enforceRetention(budget = 5000) {
    if (!this.db) return 0;
    const cut = Date.now() - this.cfg.retentionDays * 86400000;
    let removed = 0;

    // Recorrido por cursor sobre el índice `wall`, no `getAll()`.
    //
    // La versión anterior cargaba el almacén ENTERO en memoria para filtrarlo
    // en JavaScript. Con el ritmo medido de 309 MB cada ocho horas, un archivo
    // de treinta días son varios gigas: la pestaña se quedaba sin memoria
    // ejecutando precisamente la rutina cuya función es impedir que el archivo
    // crezca sin límite. El fallo se manifestaba justo cuando más falta hacía.
    //
    // El cursor acotado por rango solo toca los registros caducados, y el tope
    // por llamada impide bloquear el hilo si hay un atasco de meses acumulado
    // --- el resto se limpia en la siguiente apertura.
    for (const store of [STORE_TUBES, STORE_EVENTS]) {
      const t = tx(this.db, [store], "readwrite");
      const idx = t.objectStore(store).index("wall");
      const req = idx.openCursor(IDBKeyRange.upperBound(cut));
      await new Promise((res, rej) => {
        req.onsuccess = () => {
          const cur = req.result;
          if (!cur || removed >= budget) { res(); return; }
          cur.delete();
          removed++;
          cur.continue();
        };
        req.onerror = () => rej(req.error);
      });
      await done(t);
    }
    return removed;
  }

  /** Borrado por objeto: lo que exige de verdad una solicitud de supresión. */
  async erase(key) {
    if (!this.db) return false;
    const t = tx(this.db, [STORE_TUBES], "readwrite");
    t.objectStore(STORE_TUBES).delete(key);
    await done(t);
    return true;
  }

  async clear() {
    if (!this.db) return;
    const t = tx(this.db, [STORE_TUBES, STORE_EVENTS, STORE_SCENES], "readwrite");
    for (const s of [STORE_TUBES, STORE_EVENTS, STORE_SCENES]) {
      t.objectStore(s).clear();
    }
    await done(t);
    this.seen.clear();
    this.written = 0;
  }

  /** Exportación completa, para llevarse el archivo o auditarlo. */
  async export() {
    if (!this.db) return null;
    const t = tx(this.db, [STORE_TUBES, STORE_EVENTS, STORE_SCENES], "readonly");
    return {
      version: DB_VERSION,
      exported: new Date().toISOString(),
      tubes: await all(t.objectStore(STORE_TUBES).getAll()),
      events: await all(t.objectStore(STORE_EVENTS).getAll()),
      scenes: await all(t.objectStore(STORE_SCENES).getAll()),
    };
  }
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = {Archive, openDB, packObs, unpackObs};
}
