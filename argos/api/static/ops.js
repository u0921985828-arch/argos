/**
 * ops.js — vista de operaciones multicámara.
 *
 * Varias cámaras, un solo reloj y UN SOLO detector. Ése es el problema real:
 * el bucle rápido escala bien (cada cámara tiene su modelo de fondo y su
 * tracker, y son 6,5 ms), pero el detector neuronal es un recurso único que
 * hay que repartir. Casi todo este módulo es ese reparto.
 *
 * Lo que NO hace, y es deliberado: no identifica a nadie. La cola de atención
 * ordena SITIOS donde mirar, no personas. Cada entrada lleva un número de tubo
 * y una razón; quién sea esa persona no es cosa del sistema.
 */

/* ------------------------------------------------------------------ */
/*  Reparto del detector                                               */
/* ------------------------------------------------------------------ */

const OPS_DEFAULTS = {
  // Suelo de reparto. Sin él, una cámara concurrida se queda con todo el
  // detector y en una tranquila puede aparecer alguien sin que nadie lo vea
  // durante minutos. El suelo cuesta rendimiento en la concurrida y es lo
  // que hace que el sistema sirva para vigilar y no solo para contar.
  floorShare: 0.15,

  // Vida media de la demanda, en segundos. Una cámara que estuvo activa hace
  // un minuto no debe seguir acaparando: la prioridad tiene que olvidar.
  demandHalfLifeS: 20,

  // Vida media del peso de una entrada en la cola. Una anomalía de hace cinco
  // minutos sigue estando en el registro, pero no arriba.
  queueHalfLifeS: 90,

  maxQueue: 40,

  // Por debajo de esto no se encola: es ruido de puntuación, no un aviso.
  minScore: 1.25,
};

const LN2 = Math.LN2;

/** Decaimiento exponencial por vida media. */
function decay(ageS, halfLifeS) {
  if (!(ageS > 0)) return 1;
  return Math.exp(-LN2 * ageS / halfLifeS);
}

/* ------------------------------------------------------------------ */
/*  Una cámara dentro del puesto                                       */
/* ------------------------------------------------------------------ */

class OpsCamera {
  /**
   * @param {string} id      Identificador corto y estable. Se usa como `cam`
   *                         en las propuestas de enlace, así que no cambia.
   * @param {object} brain   Instancia de Brain ya construida sobre su Engine.
   * @param {object} opts.behaviour  BehaviourModel de ESA escena. No se
   *   comparte entre cámaras: lo normal en una calle no es lo normal en una
   *   plaza, y mezclarlo destruye las dos.
   */
  constructor(id, brain, {behaviour = null, label = "", roi = null} = {}) {
    this.id = id;
    this.brain = brain;
    this.behaviour = behaviour;
    this.label = label || id;
    this.roi = roi;

    this.video = null;
    this.live = false;

    // Demanda instantánea y suavizada. La instantánea sube con objetos vivos
    // y con anomalía; la suavizada es la que decide el reparto.
    this.demand = 0;
    this._lastDemandT = 0;

    this.lastDetectT = 0;
    this.detectPasses = 0;
    this.framesSeen = 0;
    this.lastError = null;
  }

  /** Objetos vivos por encima del mínimo de confirmación. */
  liveCount() {
    const tr = this.brain?.engine?.tracker;
    if (!tr) return 0;
    let n = 0;
    for (const t of tr.tracks) if (t.hits >= tr.minHits) n++;
    return n;
  }

  /**
   * Demanda de detector. Sube con objetos vivos, con lo rancias que estén las
   * cajas y con la anomalía reciente; baja sola con el tiempo.
   *
   * Se suaviza en lugar de usar el valor instantáneo porque el recuento de
   * objetos oscila entre frames, y un reparto que siga esa oscilación cambia
   * de cámara constantemente sin terminar una pasada en ninguna.
   */
  updateDemand(nowS, anomalyPeak = 0) {
    const live = this.liveCount();
    const staleS = this.lastDetectT ? nowS - this.lastDetectT : 999;

    // Un objeto sin confirmar desde hace rato pesa más que uno recién visto:
    // es donde el detector aporta.
    const instant =
      Math.min(live, 12) * 1.0 +
      Math.min(staleS, 8) * 0.6 +
      anomalyPeak * 3.0;

    const dt = this._lastDemandT ? nowS - this._lastDemandT : 0;
    const k = dt > 0 ? 1 - decay(dt, OPS_DEFAULTS.demandHalfLifeS) : 0.1;
    this.demand += (instant - this.demand) * Math.min(1, Math.max(0.05, k));
    this._lastDemandT = nowS;
    return this.demand;
  }
}

/* ------------------------------------------------------------------ */
/*  Cola de atención                                                   */
/* ------------------------------------------------------------------ */

/**
 * Lo que un operador mira. Ordenada por relevancia, no por hora.
 *
 * La supresión es la razón de que exista como clase y no como un array: un
 * mismo tubo anómalo genera puntuación en cada frame, y sin supresión la cola
 * se llena con cuarenta copias del mismo peatón y deja de ser útil, que es
 * exactamente el fallo que hace que un operador apague las alertas.
 */
class AttentionQueue {
  constructor(opts = {}) {
    this.cfg = {...OPS_DEFAULTS, ...opts};
    this.items = new Map();   // "cam:track" -> entrada
    this.seq = 0;
  }

  /**
   * @param {object} e  {cam, track, score, reasons, t, where}
   * @returns {boolean} true si la entrada es nueva (para avisar una vez).
   */
  push(e) {
    if (!(e.score >= this.cfg.minScore)) return false;
    const key = `${e.cam}:${e.track}`;
    const prev = this.items.get(key);

    if (prev) {
      // El mismo objeto sigue siendo anómalo: se actualiza, no se duplica.
      // Se queda con el pico, porque lo que importa es lo peor que hizo, no
      // lo que está haciendo en este frame concreto.
      if (e.score > prev.score) {
        prev.score = e.score;
        prev.reasons = e.reasons;
        prev.tPeak = e.t;
      }
      prev.tLast = e.t;
      prev.where = e.where || prev.where;
      prev.hits++;
      return false;
    }

    this.items.set(key, {
      key, cam: e.cam, track: e.track,
      score: e.score, reasons: e.reasons || [],
      where: e.where || null,
      tFirst: e.t, tPeak: e.t, tLast: e.t,
      hits: 1, ack: false, seq: this.seq++,
    });
    this._trim();
    return true;
  }

  /** Marca una entrada como vista. No la borra: queda en el registro. */
  ack(key) {
    const it = this.items.get(key);
    if (it) it.ack = true;
    return !!it;
  }

  /**
   * Entradas ordenadas por peso actual = puntuación × decaimiento.
   * Lo reciente sube solo; no hace falta borrar nada para que la cola se
   * mantenga ordenada por lo que importa ahora.
   */
  ranked(nowMs, {includeAck = false} = {}) {
    const out = [];
    for (const it of this.items.values()) {
      if (it.ack && !includeAck) continue;
      const ageS = (nowMs - it.tPeak) / 1000;
      const w = it.score * decay(ageS, this.cfg.queueHalfLifeS);
      out.push({...it, weight: +w.toFixed(3), ageS: +ageS.toFixed(1)});
    }
    out.sort((a, b) => b.weight - a.weight || b.seq - a.seq);
    return out;
  }

  /** Descarta lo más viejo cuando se llena. Nunca lo más grave. */
  _trim() {
    if (this.items.size <= this.cfg.maxQueue) return;
    const byAge = [...this.items.values()].sort((a, b) => a.tPeak - b.tPeak);
    let over = this.items.size - this.cfg.maxQueue;
    // Primero lo ya visto; sólo si no basta, lo más viejo sin ver.
    for (const it of byAge) {
      if (over <= 0) break;
      if (it.ack) { this.items.delete(it.key); over--; }
    }
    for (const it of byAge) {
      if (over <= 0) break;
      if (this.items.has(it.key)) { this.items.delete(it.key); over--; }
    }
  }
}

/* ------------------------------------------------------------------ */
/*  El puesto                                                          */
/* ------------------------------------------------------------------ */

class OpsDeck {
  constructor(opts = {}) {
    this.cfg = {...OPS_DEFAULTS, ...opts};
    this.cams = new Map();
    this.queue = new AttentionQueue(this.cfg);
    this.crossCam = null;       // CrossCamera, si hay topología
    this.linkProposals = [];
    this._rr = 0;               // cursor de reparto
    this.ticks = 0;
  }

  add(cam) {
    this.cams.set(cam.id, cam);
    return cam;
  }

  remove(id) { return this.cams.delete(id); }

  list() { return [...this.cams.values()]; }

  /**
   * Reparte el detector entre las cámaras vivas.
   *
   * Devuelve la cámara a la que toca la siguiente pasada. El reparto es
   * proporcional a la demanda, pero con un suelo por cámara: aunque una esté
   * a tope, el resto recibe su parte. Sin ese suelo el sistema deja ciegas a
   * las cámaras tranquilas, que es justo donde una aparición importa más.
   */
  nextForDetector(nowS) {
    const live = this.list().filter((c) => c.live);
    if (!live.length) return null;
    if (live.length === 1) return live[0];

    const floor = this.cfg.floorShare / live.length;
    let total = 0;
    const w = live.map((c) => {
      const d = Math.max(0, c.demand);
      total += d;
      return d;
    });

    // Peso final: mezcla de demanda y suelo uniforme.
    const share = w.map((d, i) => {
      const prop = total > 0 ? d / total : 1 / live.length;
      return prop * (1 - this.cfg.floorShare) + floor;
    });

    // Déficit: cuánto le falta a cada cámara respecto a lo que le tocaba.
    // Elegir por déficit en lugar de por peso evita que la cámara con más
    // demanda reciba dos pasadas seguidas mientras otra espera su turno.
    let best = null, bestDef = -Infinity;
    for (let i = 0; i < live.length; i++) {
      const c = live[i];
      const got = this.ticks > 0 ? c.detectPasses / this.ticks : 0;
      const def = share[i] - got;
      if (def > bestDef) { bestDef = def; best = c; }
    }
    return best;
  }

  /** Registra que una cámara recibió una pasada de detector. */
  markDetected(camId, nowS) {
    const c = this.cams.get(camId);
    if (!c) return;
    c.lastDetectT = nowS;
    c.detectPasses++;
    this.ticks++;
  }

  /**
   * Puntúa los tubos vivos de una cámara y encola lo que supera el umbral.
   * Devuelve el pico de anomalía, que realimenta la demanda: una cámara con
   * algo raro pasa a recibir más detector.
   */
  scoreCamera(cam, nowMs) {
    if (!cam.behaviour) return 0;
    const tr = cam.brain?.engine?.tracker;
    if (!tr) return 0;

    let peak = 0;
    for (const t of tr.tracks) {
      if (t.hits < tr.minHits) continue;
      const path = t.path || t.history || null;
      if (!path || path.length < 4) continue;

      let s;
      try {
        s = cam.behaviour.score({path, cls: t.klass, klass: t.klass});
      } catch { continue; }
      if (!s || !Number.isFinite(s.score)) continue;

      if (s.score > peak) peak = s.score;
      const last = path[path.length - 1];
      this.queue.push({
        cam: cam.id, track: t.id,
        score: s.score, reasons: s.reasons || [],
        where: last ? [Math.round(last[0]), Math.round(last[1])] : null,
        t: nowMs,
      });
    }
    return peak;
  }

  /**
   * Un paso del puesto: puntúa cada cámara, actualiza demandas y decide a
   * quién le toca el detector. No ejecuta el detector: eso lo hace quien
   * llama, porque el detector vive en su propio hilo.
   */
  step(nowMs = Date.now()) {
    const nowS = nowMs / 1000;
    for (const c of this.cams.values()) {
      if (!c.live) { c.demand *= 0.9; continue; }
      const peak = this.scoreCamera(c, nowMs);
      c.updateDemand(nowS, peak);
    }
    return this.nextForDetector(nowS);
  }

  /**
   * Propuestas de enlace entre cámaras. Hipótesis con verosimilitud, nunca
   * una afirmación: la decisión es de la persona que mira.
   */
  proposeLinks(tracks) {
    if (!this.crossCam) return [];
    try {
      this.linkProposals = this.crossCam.propose(tracks) || [];
    } catch {
      this.linkProposals = [];
    }
    return this.linkProposals;
  }

  /** Resumen para pintar el puesto. */
  state(nowMs = Date.now()) {
    const cams = this.list().map((c) => ({
      id: c.id, label: c.label, live: c.live,
      objetos: c.liveCount(),
      demanda: +c.demand.toFixed(2),
      pasadas: c.detectPasses,
      cuota: this.ticks ? +(c.detectPasses / this.ticks).toFixed(3) : 0,
      fps: c.brain ? +(c.brain.fps || 0).toFixed(1) : 0,
      error: c.lastError,
    }));
    const q = this.queue.ranked(nowMs);
    return {
      camaras: cams,
      vivas: cams.filter((c) => c.live).length,
      cola: q.slice(0, 12),
      colaTotal: q.length,
      enlaces: this.linkProposals.length,
      pasadas: this.ticks,
    };
  }
}

if (typeof module !== "undefined" && module.exports) {
  module.exports = {OpsDeck, OpsCamera, AttentionQueue, OPS_DEFAULTS, decay};
}
