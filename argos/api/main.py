"""ARGOS server.

Architecture, stated plainly because it is the whole answer to "can this run on
a phone":

    phone / IP camera / file  ->  ARGOS server (plugged in)  ->  browser UI
         capture only              detection, tracking,          view only
                                   synopsis, silhouettes

The phone is a camera and a screen. It is genuinely good at both. It is not good
at holding a tracker's full session in memory, and it is not good at a global
optimisation over every tube in a window --- the synopsis solver runs tens of
thousands of annealing iterations, and a phone will thermally throttle halfway
through and take four times as long on battery.

Serving the UI from this process rather than shipping an app store build is
deliberate: any phone on the same network opens a URL, no install, no review, no
platform split, and updating the software updates every client at once.

Run:

    uvicorn argos.api.main:app --host 0.0.0.0 --port 8000

Then open http://<ip-del-servidor>:8000 on the phone.

There is no authentication in this file. It is a local-network tool as written;
putting it on the public internet without a reverse proxy handling auth and TLS
would expose a camera feed and a search index to anyone who finds the port.
"""

from __future__ import annotations

import asyncio
import os
import threading
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import cv2
import numpy as np
from fastapi import FastAPI, File, HTTPException, UploadFile, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, Response

from .auth import TokenAuthMiddleware, resolve_auth
from ..core.types import Tube, TubeFrame
from ..ingest.source import PushSource, SourceCapability, open_source
from ..measure.anthropometry import StatureEstimator
from ..measure.calibration import (SelfCalibrationConfig, calibrate_from_pedestrians,
                                   pairs_from_tubes)
from ..render.silhouette import (SilhouetteStyle, activity_plate, figures_for_plate,
                                 figures_for_strip, rasterize_figures, trajectory_strip)
from ..synopsis.optimizer import SolverConfig, SynopsisSolver
from ..synopsis.renderer import RenderConfig, SynopsisRenderer
from ..track.tracker import MultiObjectTracker, TrackerConfig
from ..tubes.patchstore import PatchStore, build_patch_store

STATIC = Path(__file__).parent / "static"
WORK = Path(os.environ.get("ARGOS_WORK", "/tmp/argos_work"))
WORK.mkdir(parents=True, exist_ok=True)

# --------------------------------------------------------------------------- #
#  Perfiles de recursos
# --------------------------------------------------------------------------- #
#
# El mismo servidor corre en un mini-PC y dentro de un teléfono (Termux). Las
# diferencias no son de código sino de techos, así que se declaran en un sitio
# en lugar de esparcirse por constantes.
#
# Medido en 1 vCPU con entrada a 1280x720: 500 MB en régimen y 723 MB de pico
# durante el `build`, con los recortes acumulándose sin límite. Eso es viable en
# un servidor y suicida en un móvil que además sostiene cámara y pantalla.

@dataclass(frozen=True)
class Profile:
    name: str
    plate_samples: int          # frames retenidos para la placa de fondo
    plate_max_edge: int         # se guardan reducidos: la mediana no necesita 4K
    patch_max_bytes: int        # techo del almacén de recortes
    patch_quality: int
    solver_iterations: int
    max_sessions: int

PROFILES = {
    "server": Profile("server", 48, 1280, 512 * 1024 * 1024, 82, 25_000, 8),
    "device": Profile("device", 24,  640,  64 * 1024 * 1024, 74,  8_000, 2),
}
PROFILE = PROFILES[os.environ.get("ARGOS_PROFILE", "server").lower()
                   if os.environ.get("ARGOS_PROFILE", "server").lower() in PROFILES
                   else "server"]

# La documentación interactiva describe la superficie completa de la API,
# incluidos los parámetros de sesión. Con token configurado se apaga: no hay
# motivo para publicar el mapa antes de pedir la llave.
_DOCS = None if AUTH.token else "/docs"
app = FastAPI(title="ARGOS", version="0.1",
              docs_url=_DOCS, redoc_url=None,
              openapi_url=None if AUTH.token else "/openapi.json")

# El cliente puede abrirse desde un origen distinto --- el fichero descargado en
# el móvil, o una copia servida desde otra máquina --- y entonces el navegador
# bloquea las llamadas salvo que el servidor las permita explícitamente. Sin
# esto, ese caso se manifiesta como un "Failed to fetch" sin más explicación.
# Es aceptable porque esta API está pensada para red local; ver la nota de
# seguridad en el docstring antes de exponerla fuera de ella.
AUTH = resolve_auth()
app.add_middleware(TokenAuthMiddleware, cfg=AUTH)

# CORS restringido al propio origen.
#
# `allow_origins=["*"]` permitía que cualquier página que visitara el operador
# llamase a esta API. Combinado con el token en query --- que <img> y <video>
# obligan a usar y que acaba en el historial del navegador --- bastaba con que
# ese token se filtrase para que una página ajena enumerase las cámaras.
#
# Se puede ampliar con ARGOS_CORS_ORIGINS para un panel servido aparte, pero
# eso es una decisión explícita del operador, no el valor por defecto.
_origins = [o.strip() for o in os.environ.get("ARGOS_CORS_ORIGINS", "").split(",")
            if o.strip()]
if not _origins:
    _port = os.environ.get("ARGOS_PORT", "8000")
    _origins = [f"http://{AUTH.bind_host}:{_port}",
                f"http://localhost:{_port}", f"http://127.0.0.1:{_port}"]
app.add_middleware(
    CORSMiddleware,
    allow_origins=_origins,
    allow_credentials=False,
    allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
    allow_headers=["*"],
)


# --------------------------------------------------------------------------- #
#  State
# --------------------------------------------------------------------------- #


@dataclass
class Session:
    session_id: str
    name: str
    spec: str
    created_at: datetime
    source: Any = None
    tracker: MultiObjectTracker = None
    tubes: list[Tube] = field(default_factory=list)
    frames_seen: int = 0
    last_frame: np.ndarray | None = None
    plate_samples: list = field(default_factory=list)
    patches: PatchStore = field(default_factory=lambda: PatchStore(
        quality=PROFILE.patch_quality, max_bytes=PROFILE.patch_max_bytes))
    status: str = "idle"
    error: str | None = None
    outputs: dict = field(default_factory=dict)
    lock: threading.Lock = field(default_factory=threading.Lock)
    # Ciclo de vida del hilo de proceso: `stop` lo pide, `done` confirma que ha
    # terminado. Sin `done` no hay forma de saber cuándo es seguro liberar la
    # fuente, y esperar un tiempo fijo es adivinar.
    stop: threading.Event = field(default_factory=threading.Event)
    done: threading.Event = field(default_factory=threading.Event)
    thread: "threading.Thread | None" = None

    def capability(self) -> dict:
        if hasattr(self.source, "capability"):
            c: SourceCapability = self.source.capability()
            return {k: (None if isinstance(v, float) and not np.isfinite(v) else v)
                    for k, v in c.__dict__.items()}
        return {"verdict": "full", "supports": ["all"], "excludes": []}


# Tope duro de vida de un stream MJPEG. Un cliente que lo quiera más
# largo reconecta; un cliente olvidado se corta solo.
MAX_STREAM_S = 3600.0

SESSIONS: dict[str, Session] = {}


def _detector():
    """MOG2 by default so the server runs with no model download."""
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
    from run_video import CLASS_NAMES, MOG2Detector  # noqa: E402
    return MOG2Detector(), CLASS_NAMES


# --------------------------------------------------------------------------- #
#  UI
# --------------------------------------------------------------------------- #


@app.get("/", response_class=HTMLResponse)
def index():
    f = STATIC / "index.html"
    if not f.exists():
        return HTMLResponse("<h1>ARGOS</h1><p>static/index.html no encontrado</p>", 500)
    return HTMLResponse(f.read_text(encoding="utf-8"))


@app.get("/engine.js")
def engine_js():
    return _js("engine.js")


@app.get("/solver.js")
def solver_js():
    return _js("solver.js")


@app.get("/photogrammetry.js")
def photogrammetry_js():
    return _js("photogrammetry.js")


@app.get("/detector.js")
def detector_js():
    return _js("detector.js")


@app.get("/brain.js")
def brain_js():
    return _js("brain.js")


@app.get("/archive.js")
def archive_js():
    return _js("archive.js")


@app.get("/identity.js")
def identity_js():
    return _js("identity.js")


@app.get("/behaviour.js")
def behaviour_js():
    return _js("behaviour.js")


@app.get("/cameras.js")
def cameras_js():
    return _js("cameras.js")


@app.get("/groundplane.js")
def groundplane_js():
    return _js("groundplane.js")


@app.get("/detector-worker.js")
def detector_worker_js():
    return _js("detector-worker.js")


def _js(name: str):
    f = STATIC / name
    if not f.exists():
        raise HTTPException(404, f"{name} no encontrado")
    return Response(f.read_text(encoding="utf-8"),
                    media_type="application/javascript; charset=utf-8",
                    headers={"Cache-Control": "no-cache"})


@app.get("/api/health")
def health():
    return {"ok": True, "sessions": len(SESSIONS), "profile": PROFILE.name,
            "auth": bool(AUTH.token), "time": datetime.now(timezone.utc)}


# --------------------------------------------------------------------------- #
#  Sources
# --------------------------------------------------------------------------- #


@app.post("/api/sources")
def create_source(payload: dict):
    """Register any camera. ``spec`` is an RTSP URL, an HTTP endpoint, a file
    path, a device index, or ``push://name`` for a phone."""
    spec = str(payload.get("spec", "")).strip()
    if not spec:
        raise HTTPException(400, "spec requerido")
    # Validar ANTES de abrir. `open_source` entrega la cadena a FFmpeg o a
    # urlopen, y ambos aceptan mucho más de lo que aquí tiene sentido.
    try:
        spec = validate_spec(spec)
    except SpecRejected as e:
        raise HTTPException(400, str(e))
    name = payload.get("name") or spec
    if len(SESSIONS) >= PROFILE.max_sessions:
        raise HTTPException(429,
            f"límite de {PROFILE.max_sessions} sesiones en el perfil "
            f"'{PROFILE.name}'; desconecta alguna antes de añadir otra")
    sid = uuid.uuid4().hex[:12]
    try:
        src = open_source(spec, **(payload.get("options") or {}))
    except Exception as e:
        raise HTTPException(400, f"no se pudo abrir la fuente: {e}")

    det, names = _detector()
    sess = Session(sid, name, spec, datetime.now(timezone.utc), source=src)
    sess.tracker = MultiObjectTracker(TrackerConfig(min_hits=4, max_age=25), names)
    sess._detector = det                                  # type: ignore[attr-defined]
    SESSIONS[sid] = sess
    return {"session_id": sid, "name": name, "spec": spec,
            "kind": type(src).__name__, "capability": sess.capability()}


@app.get("/api/sources")
def list_sources():
    return [{"session_id": s.session_id, "name": s.name, "spec": s.spec,
             "status": s.status, "frames": s.frames_seen, "tubes": len(s.tubes),
             "capability": s.capability(), "outputs": list(s.outputs)}
            for s in SESSIONS.values()]


def _get(sid: str) -> Session:
    s = SESSIONS.get(sid)
    if s is None:
        raise HTTPException(404, "sesión no encontrada")
    return s


@app.delete("/api/sources/{sid}")
def delete_source(sid: str):
    s = _get(sid)
    try:
        s.source.close()
    except Exception:
        pass
    # Parar el hilo ANTES de soltar la fuente.
    #
    # Antes se cerraba la fuente y se sacaba la sesión del diccionario mientras
    # el hilo seguía iterando sobre ella: el iterador reventaba contra un
    # `VideoCapture` ya liberado, la excepción se escribía en un objeto que ya
    # nadie consultaba, y el descriptor de la cámara podía quedar sin cerrar.
    s.stop.set()
    if s.thread and s.thread.is_alive():
        s.done.wait(timeout=3.0)
    SESSIONS.pop(sid, None)
    return {"deleted": sid}


# --------------------------------------------------------------------------- #
#  Frame ingest from a phone
# --------------------------------------------------------------------------- #


@app.post("/api/sources/{sid}/frame")
async def push_frame(sid: str, file: UploadFile = File(...)):
    """The phone POSTs one JPEG per capture; processing happens here."""
    s = _get(sid)
    if not isinstance(s.source, PushSource):
        raise HTTPException(400, "esta fuente no acepta push")
    body = await file.read()
    img = cv2.imdecode(np.frombuffer(body, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise HTTPException(400, "JPEG ilegible")
    s.source.push(img)
    _consume_one(s, img)
    return {"frames": s.frames_seen, "tubes": len(s.tubes),
            "active_tracks": len(s.tracker.tracks),
            "dropped": s.source.dropped}


def _consume_one(s: Session, img: np.ndarray) -> None:
    with s.lock:
        idx = s.frames_seen
        dets = s._detector(idx, img)                      # type: ignore[attr-defined]
        s.tracker.update(idx, dets)
        s.frames_seen = idx + 1
        s.last_frame = img
        # Muestreo por reservorio repartido sobre toda la sesión, no sobre los
        # últimos N frames: una placa construida con el último minuto hereda la
        # iluminación de ese minuto y las siluetas quedan flotando sobre un
        # fondo que no corresponde a su hora.
        n = PROFILE.plate_samples
        if len(s.plate_samples) < n:
            s.plate_samples.append(_shrink(img, PROFILE.plate_max_edge))
        elif np.random.rand() < n / max(1, s.frames_seen):
            s.plate_samples[np.random.randint(n)] = _shrink(img, PROFILE.plate_max_edge)

        for tube in s.tracker.finished:
            if tube not in s.tubes:
                s.tubes.append(tube)
        s.tracker.finished = []

        for t in s.tracker.tracks:
            for f in t.frames[-1:]:
                x1, y1, x2, y2 = [int(v) for v in f.bbox]
                x1, y1 = max(0, x1), max(0, y1)
                x2, y2 = min(img.shape[1], x2), min(img.shape[0], y2)
                if x2 > x1 and y2 > y1:
                    s.patches.add(t.track_id, f.frame_idx, img[y1:y2, x1:x2])


def _shrink(img: np.ndarray, max_edge: int) -> np.ndarray:
    """Reduce conservando proporción. La mediana temporal que estima el fondo no
    gana nada con resolución completa y sí cuesta memoria lineal en ella."""
    h, w = img.shape[:2]
    k = min(1.0, max_edge / max(h, w))
    if k >= 1.0:
        return img.copy()
    return cv2.resize(img, (int(w * k), int(h * k)), interpolation=cv2.INTER_AREA)


# --------------------------------------------------------------------------- #
#  Pull sources
# --------------------------------------------------------------------------- #


@app.post("/api/sources/{sid}/run")
def run_source(sid: str, payload: dict | None = None):
    """Pull and process N frames in a background thread."""
    s = _get(sid)
    n = max(1, min(100_000, int((payload or {}).get("max_frames", 600))))

    # Comprobar-y-marcar bajo cerrojo.
    #
    # `if s.status == "running"` seguido de arrancar el hilo no es atómico: dos
    # POST simultáneos pasaban ambos la comprobación y lanzaban dos hilos sobre
    # el MISMO tracker. El resultado no es solo trabajo duplicado --- ambos
    # llaman a `_consume_one`, que muta estado compartido, y los identificadores
    # de tubo salen corrompidos.
    with s.lock:
        if s.status == "running":
            raise HTTPException(409, "ya está procesando")
        s.status = "running"
        s.error = None
        s.stop.clear()

    def worker():
        try:
            for fr in s.source.frames(max_frames=n):
                if s.stop.is_set():
                    break
                _consume_one(s, fr.image)
            s.status = "idle"
        except Exception as e:
            s.status = "error"
            s.error = f"{type(e).__name__}: {e}"
        finally:
            s.done.set()

    s.done.clear()
    s.thread = threading.Thread(target=worker, daemon=True)
    s.thread.start()
    return {"started": True, "max_frames": n}


@app.get("/api/sources/{sid}/status")
def status(sid: str):
    s = _get(sid)
    return {"status": s.status, "error": s.error, "frames": s.frames_seen,
            "tubes": len(s.tubes), "active_tracks": len(s.tracker.tracks),
            "capability": s.capability(), "outputs": list(s.outputs),
            "profile": PROFILE.name,
            "patch_mb": round(s.patches.nbytes / 1e6, 1),
            "patches_evicted": s.patches.evicted}


@app.get("/api/sources/{sid}/tracks")
def tracks(sid: str):
    """Just the boxes, as JSON.

    The client shows the phone's *own* camera stream as the background --- it is
    already there, already at native resolution, already at the display's
    refresh rate, and costs nothing. Round-tripping a re-encoded JPEG preview
    back from the server would be lower resolution, a second behind, and would
    burn uplink bandwidth competing with the frames being uploaded.

    So the wire carries only what the client cannot know: where the tracker
    thinks the objects are. A few hundred bytes instead of ten kilobytes a
    frame, which is what makes a smooth overlay possible at all.
    """
    s = _get(sid)
    if s.last_frame is None:
        return {"w": 0, "h": 0, "frame": 0, "boxes": []}
    h, w = s.last_frame.shape[:2]
    boxes = []
    for t in s.tracker.tracks:
        if not t.frames or t.hits < s.tracker.cfg.min_hits:
            continue
        x1, y1, x2, y2 = [round(float(v), 1) for v in t.frames[-1].bbox]
        boxes.append({"id": t.track_id,
                      "c": s.tracker.class_names.get(t.class_id, "obj"),
                      "b": [x1, y1, x2, y2],
                      "age": t.time_since_update})
    return {"w": w, "h": h, "frame": s.frames_seen, "boxes": boxes,
            "tubes": len(s.tubes), "status": s.status}


@app.get("/api/sources/{sid}/stream.mjpg")
async def stream_mjpg(sid: str, request: Request):
    """MJPEG for pull sources (RTSP, file, webcam), which have no local preview.

    One long-lived connection pushing frames beats polling a still endpoint:
    polling costs a request round-trip per frame and lands them at irregular
    intervals, which reads as stutter no matter how fast the network is.
    """
    from fastapi.responses import StreamingResponse
    s = _get(sid)

    async def gen():
        last = -1
        idle = 0
        started = time.monotonic()
        # Corte por desconexión y por duración.
        #
        # El bucle anterior solo salía tras 8 s SIN frames nuevos. Un stream
        # activo cuyo cliente ha cerrado la pestaña no terminaba nunca: seguía
        # comprimiendo JPEG contra un socket muerto, y cada pestaña abandonada
        # dejaba un bucle de codificación vivo.
        while idle < 200 and time.monotonic() - started < MAX_STREAM_S:
            if await request.is_disconnected():
                break
            if s.last_frame is not None and s.frames_seen != last:
                last = s.frames_seen
                idle = 0
                ok, buf = cv2.imencode(".jpg", s.last_frame,
                                       [int(cv2.IMWRITE_JPEG_QUALITY), 80])
                if ok:
                    yield (b"--f\r\nContent-Type: image/jpeg\r\n\r\n"
                           + buf.tobytes() + b"\r\n")
            else:
                idle += 1
            await asyncio.sleep(0.04)

    return StreamingResponse(gen(),
                             media_type="multipart/x-mixed-replace; boundary=f")


@app.get("/api/sources/{sid}/preview")
def preview(sid: str):
    s = _get(sid)
    if s.last_frame is None:
        raise HTTPException(404, "aún no hay frames")
    img = s.last_frame.copy()
    for t in s.tracker.tracks:
        if not t.frames:
            continue
        x1, y1, x2, y2 = [int(v) for v in t.frames[-1].bbox]
        cv2.rectangle(img, (x1, y1), (x2, y2), (60, 220, 120), 2)
        cv2.putText(img, f"#{t.track_id}", (x1, max(12, y1 - 4)),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.45, (60, 220, 120), 1, cv2.LINE_AA)
    ok, buf = cv2.imencode(".jpg", img, [int(cv2.IMWRITE_JPEG_QUALITY), 78])
    return Response(buf.tobytes(), media_type="image/jpeg")


# --------------------------------------------------------------------------- #
#  Results
# --------------------------------------------------------------------------- #


def _all_tubes(s: Session) -> list[Tube]:
    live = []
    for t in s.tracker.tracks:
        if t.hits >= s.tracker.cfg.min_hits and t.frames:
            tb = Tube(t.track_id, t.class_id,
                      s.tracker.class_names.get(t.class_id, str(t.class_id)),
                      list(t.frames))
            live.append(tb)
    return s.tubes + live


@app.post("/api/sources/{sid}/build")
def build(sid: str, payload: dict | None = None):
    """Produce synopsis and silhouettes from everything seen so far."""
    s = _get(sid)
    p = payload or {}
    tubes = _all_tubes(s)
    if len(tubes) < 2:
        raise HTTPException(400, f"solo {len(tubes)} tubos; hacen falta más")

    out = WORK / sid
    out.mkdir(parents=True, exist_ok=True)
    H, W = s.last_frame.shape[:2]
    style = SilhouetteStyle()
    res: dict[str, str] = {}

    (out / "activity_plate.svg").write_text(activity_plate(tubes, (W, H), 2, style))
    cv2.imwrite(str(out / "activity_plate.png"),
                rasterize_figures(figures_for_plate(tubes, style, 2), (W, H), style))
    res["activity_plate"] = "activity_plate.png"
    res["activity_plate_svg"] = "activity_plate.svg"

    longest = max(tubes, key=lambda t: t.n_obs)
    (out / "trajectory_strip.svg").write_text(trajectory_strip(longest, (W, H), 10, style))
    cv2.imwrite(str(out / "trajectory_strip.png"),
                rasterize_figures(figures_for_strip(longest, style, 10), (W, H), style))
    res["trajectory_strip"] = "trajectory_strip.png"

    if p.get("synopsis", True) and len(s.patches):
        plate = np.median(np.stack(s.plate_samples), axis=0).astype(np.uint8)
        # La placa se guarda reducida; el render compone en coordenadas de la
        # fuente, así que se devuelve a su tamaño antes de usarla.
        if plate.shape[:2] != (H, W):
            plate = cv2.resize(plate, (W, H), interpolation=cv2.INTER_LINEAR)
        cv2.imwrite(str(out / "plate.png"), plate)
        solver = SynopsisSolver(tubes, SolverConfig(
            scale=6, iterations=int(p.get("iterations", PROFILE.solver_iterations)),
            max_overlap_ratio=float(p.get("overlap", 0.012))))
        plan = solver.auto()
        rend = SynopsisRenderer(tubes, plan, RenderConfig(feather=2, draw_labels=True))
        rend.render(s.patches, plate, str(out / "sinopsis.mp4"), fps=12.0)
        res["synopsis"] = "sinopsis.mp4"
        res["compression"] = round(plan.compression, 2)
        res["synopsis_frames"] = plan.duration

    s.outputs = res
    return {"session_id": sid, "tubes": len(tubes), "outputs": res}


@app.get("/api/sources/{sid}/file/{name}")
def get_file(sid: str, name: str):
    _get(sid)
    if "/" in name or ".." in name:
        raise HTTPException(400, "nombre inválido")
    f = WORK / sid / name
    if not f.exists():
        raise HTTPException(404, "no existe")
    return FileResponse(f)


# --------------------------------------------------------------------------- #
#  Planificación para el motor del dispositivo
# --------------------------------------------------------------------------- #


@app.post("/api/plan")
def plan_from_device(payload: dict):
    """Resuelve un sinopsis a partir de tubos calculados en el dispositivo.

    El navegador ya ha hecho detección, tracking y siluetas en tiempo real; lo
    único que no puede hacer con soltura es la optimización global, que es un
    recocido sobre todos los pares de tubos. Así que sube *geometría* --- unos
    120 bytes por observación --- y recibe de vuelta un desplazamiento por tubo.

    Los píxeles no viajan en ninguna dirección: el dispositivo compone el vídeo
    final en canvas con los recortes que ya tiene guardados. Una sesión de una
    hora manda unos pocos cientos de kilobytes en lugar de decenas de megas, y
    el resultado aparece en segundos incluso con la subida saturada.
    """
    raw = payload.get("tubes") or []
    if len(raw) < 2:
        raise HTTPException(400, f"solo {len(raw)} tubos; hacen falta al menos 2")

    aw, ah = payload.get("analysis_size", [0, 0])
    fps = float(payload.get("fps") or 12.0)
    tubes: list[Tube] = []
    for item in raw:
        frames = []
        for o in item.get("obs", []):
            b = o.get("b")
            if not b or len(b) != 4:
                continue
            x1, y1, x2, y2 = (float(v) for v in b)
            if x2 <= x1 or y2 <= y1:
                continue
            blob = shape = None
            runs = o.get("m")
            if runs:
                bw, bh = int(round(x2 - x1)), int(round(y2 - y1))
                mask = _mask_from_rle(runs, bw, bh)
                if mask is not None and mask.any():
                    blob, shape = TubeFrame.pack_mask(mask)
            frames.append(TubeFrame(int(o["f"]),
                                    np.array([x1, y1, x2, y2], np.float32),
                                    float(o.get("s", 1.0)), blob, shape))
        if len(frames) >= 4:
            frames.sort(key=lambda f: f.frame_idx)
            tubes.append(Tube(int(item["id"]), 0, str(item.get("class", "object")),
                              frames, fps=fps))
    if len(tubes) < 2:
        raise HTTPException(400, "no hay tubos utilizables tras validarlos")

    # La escala del solver se expresa en píxeles del plano de análisis, no de la
    # fuente: los tubos llegan en coordenadas de análisis y usar la resolución
    # de captura aquí haría la rejilla de colisiones absurdamente gruesa.
    scale = max(2, int(round(max(aw, 1) / 60)))
    solver = SynopsisSolver(tubes, SolverConfig(
        scale=scale, iterations=int(payload.get("iterations", PROFILE.solver_iterations)),
        max_overlap_ratio=float(payload.get("overlap", 0.012))))
    plan = solver.auto()
    return {
        "duration": plan.duration,
        "source_duration": plan.source_duration,
        "compression": round(plan.compression, 2),
        "overlap_ratio": round(plan.collision / max(1.0, solver.model.total_mass), 4),
        "tubes": len(tubes),
        "placements": {str(t): p.shift for t, p in plan.placements.items()},
        "starts": {str(t.tube_id): t.start for t in tubes},
    }


def _mask_from_rle(runs, bw: int, bh: int):
    """RLE del navegador -> máscara booleana. Se valida cada tramo: los índices
    llegan de un cliente y un tramo fuera de rango debe descartarse, no
    reventar la petición."""
    if bw <= 0 or bh <= 0 or len(runs) % 3:
        return None
    mask = np.zeros((bh, bw), bool)
    for i in range(0, len(runs), 3):
        y, x, ln = int(runs[i]), int(runs[i + 1]), int(runs[i + 2])
        if not (0 <= y < bh) or ln <= 0 or x < 0 or x >= bw:
            continue
        mask[y, x:min(bw, x + ln)] = True
    return mask


@app.get("/api/sources/{sid}/tubes")
def tubes(sid: str):
    s = _get(sid)
    return [{"tube_id": t.tube_id, "class": t.class_name, "start": t.start,
             "end": t.end, "n_obs": t.n_obs,
             "path_px": round(t.path_length(), 1)}
            for t in _all_tubes(s)]


@app.post("/api/sources/{sid}/stature")
def stature(sid: str):
    s = _get(sid)
    tubes = [t for t in _all_tubes(s) if t.class_name == "person"]
    if len(tubes) < 12:
        return JSONResponse({
            "available": False,
            "reason": f"{len(tubes)} peatones; hacen falta al menos 12 para autocalibrar",
        })
    H, W = s.last_frame.shape[:2]
    feet, heads = pairs_from_tubes(tubes, (W, H), per_tube=6)
    cam = calibrate_from_pedestrians(feet, heads, SelfCalibrationConfig())
    est = StatureEstimator(cam, (W, H))
    rows = []
    for t in tubes:
        e = est.estimate(t)
        if np.isfinite(e.height_m) and e.quality in ("good", "fair"):
            rows.append({"tube": t.tube_id, "m": round(e.height_m, 2),
                         "ci": [round(e.ci_low, 2), round(e.ci_high, 2)],
                         "quality": e.quality})
    return {"available": True, "calibration": cam.provenance(),
            "evidential": False, "results": rows}
