"""Ingesta RTSP para funcionamiento continuo.

Un `VideoCapture` sobre una URL RTSP funciona en el escritorio durante la demo y
falla en producción por razones que no aparecen en una sesión de diez minutos:

*   **El stream muere sin decir nada.** Un corte de red, un reinicio del NVR o un
    cambio de perfil de la cámara dejan un `VideoCapture` que sigue abierto y
    devuelve `False` para siempre, o peor: se queda bloqueado dentro de `read()`
    sin plazo. Por eso hay perro guardián en un hilo aparte y no solo
    comprobación del valor de retorno.
*   **UDP no pierde frames: los corrompe.** El transporte por defecto entrega
    paquetes incompletos que FFmpeg decodifica igualmente, y el resultado no es
    un frame ausente sino uno con media imagen de la escena anterior. Para una
    sustracción de fondo eso es un objeto enorme y falso. Se fuerza TCP.
*   **Reconectar en bucle cerrado agrava la caída.** Si la cámara está
    reiniciándose, martillearla cada 100 ms retrasa su arranque. Espera
    exponencial con tope.
*   **La cámara se congela sin desconectarse.** Sigue sirviendo el mismo frame
    indefinidamente. Solo el hash del contenido lo detecta; el protocolo no.

Al reconectar se emite `reset=True`: quien consuma debe reiniciar su modelo de
fondo. Un corte de treinta segundos cambia la iluminación lo suficiente como
para que el modelo anterior marque medio cuadro como movimiento.
"""

from __future__ import annotations

import hashlib
import os
import threading
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import Iterator

import numpy as np

try:
    import cv2
except ImportError:                                  # pragma: no cover
    cv2 = None

from .source import Frame, SourceCapability, VideoSource


@dataclass
class RtspConfig:
    url: str
    stride: int = 1
    transport: str = "tcp"
    open_timeout_s: float = 12.0
    read_timeout_s: float = 8.0        # perro guardián sobre read()
    reconnect_base_s: float = 1.0
    reconnect_max_s: float = 60.0
    max_attempts: int = 0              # 0 = reintentar indefinidamente
    freeze_after: int = 90             # frames idénticos consecutivos
    latency_drop: bool = True          # descartar acumulación al reconectar
    name: str = ""


@dataclass
class RtspStats:
    frames: int = 0
    reconnects: int = 0
    read_failures: int = 0
    timeouts: int = 0
    freezes: int = 0
    corrupt: int = 0
    connected_since: datetime | None = None

    def as_dict(self) -> dict:
        d = dict(self.__dict__)
        d["connected_since"] = (self.connected_since.isoformat()
                                if self.connected_since else None)
        return d


class RtspSource(VideoSource):
    def __init__(self, cfg: RtspConfig):
        if cv2 is None:
            raise RuntimeError("opencv es necesario")
        self.cfg = cfg
        self.name = cfg.name or cfg.url
        self.stats = RtspStats()
        self.cap: "cv2.VideoCapture | None" = None
        self.intervals: list[float] = []
        self._last_hash: str | None = None
        self._same = 0
        self._stop = threading.Event()
        self._last_shape: tuple[int, int] | None = None

    # ------------------------------------------------------------------ #

    def _apply_transport(self) -> None:
        # FFmpeg lee esta variable al abrir. Se compone en vez de sobrescribir
        # para no pisar opciones que el operador haya puesto en el entorno.
        opts = os.environ.get("OPENCV_FFMPEG_CAPTURE_OPTIONS", "")
        want = f"rtsp_transport;{self.cfg.transport}"
        if "rtsp_transport" not in opts:
            os.environ["OPENCV_FFMPEG_CAPTURE_OPTIONS"] = (
                f"{opts}|{want}" if opts else want)

    def _open(self) -> bool:
        self._apply_transport()
        self.close()
        cap = cv2.VideoCapture(self.cfg.url, cv2.CAP_FFMPEG)
        # Búfer mínimo: en analítica en vivo, un frame de hace tres segundos no
        # vale menos que el actual, vale negativo --- retrasa cada alarma.
        try:
            cap.set(cv2.CAP_PROP_BUFFERSIZE, 1)
        except Exception:
            pass
        if not cap.isOpened():
            cap.release()
            return False
        self.cap = cap
        self.stats.connected_since = datetime.now(timezone.utc)
        return True

    def _read_with_watchdog(self):
        """`read()` puede bloquear sin plazo; se ejecuta vigilado.

        No se puede matar el hilo bloqueado, así que el vigilante decide que la
        conexión está perdida y se abre otra. El hilo huérfano muere solo cuando
        FFmpeg abandona, y liberar el `VideoCapture` lo acelera.
        """
        box: dict = {}

        def worker():
            try:
                box["ok"], box["frame"] = self.cap.read()
            except Exception as exc:
                box["ok"], box["frame"], box["exc"] = False, None, exc

        th = threading.Thread(target=worker, daemon=True)
        th.start()
        th.join(self.cfg.read_timeout_s)
        if th.is_alive():
            self.stats.timeouts += 1
            return None, None
        return box.get("ok", False), box.get("frame")

    def _looks_corrupt(self, frame: np.ndarray) -> bool:
        """Frame parcial tras pérdida de paquetes.

        Se comprueba forma y una franja inferior plana: la firma típica de un
        frame truncado es que el último tercio queda en un valor constante.
        """
        if frame is None or frame.size == 0:
            return True
        shape = frame.shape[:2]
        if self._last_shape and shape != self._last_shape:
            return True
        self._last_shape = shape
        strip = frame[int(shape[0] * 0.88):]
        return bool(strip.size and float(strip.std()) < 0.6)

    # ------------------------------------------------------------------ #

    def frames(self, max_frames: int | None = None) -> Iterator[Frame]:
        cfg = self.cfg
        idx = read = attempts = 0
        backoff = cfg.reconnect_base_s
        last_ts = None

        while not self._stop.is_set() and (max_frames is None or idx < max_frames):
            if self.cap is None:
                if cfg.max_attempts and attempts >= cfg.max_attempts:
                    return
                attempts += 1
                if not self._open():
                    time.sleep(backoff)
                    backoff = min(cfg.reconnect_max_s, backoff * 2)
                    continue
                backoff = cfg.reconnect_base_s
                if attempts > 1:
                    self.stats.reconnects += 1
                    # Señal explícita: tras un corte, el modelo de fondo del
                    # consumidor ya no describe esta escena.
                    yield Frame(idx, np.zeros((1, 1, 3), np.uint8),
                                datetime.now(timezone.utc), "reset")

            ok, frame = self._read_with_watchdog()
            if ok is None or not ok or frame is None:
                self.stats.read_failures += 1
                self.close()
                time.sleep(backoff)
                backoff = min(cfg.reconnect_max_s, backoff * 2)
                continue

            if self._looks_corrupt(frame):
                self.stats.corrupt += 1
                continue

            # Congelación: el protocolo sigue vivo y el contenido no cambia.
            h = hashlib.blake2b(frame[::8, ::8].tobytes(), digest_size=8).hexdigest()
            if h == self._last_hash:
                self._same += 1
                if self._same >= cfg.freeze_after:
                    self.stats.freezes += 1
                    self._same = 0
                    self.close()
                    time.sleep(backoff)
                    backoff = min(cfg.reconnect_max_s, backoff * 2)
                continue
            self._same = 0
            self._last_hash = h

            read += 1
            if (read - 1) % cfg.stride:
                continue

            now = time.monotonic()
            if last_ts is not None:
                self.intervals.append(now - last_ts)
            last_ts = now
            self.stats.frames += 1
            yield Frame(idx, frame, datetime.now(timezone.utc), "local")
            idx += 1

    def capability(self) -> SourceCapability:
        return SourceCapability.from_intervals(self.intervals)

    def close(self) -> None:
        if self.cap is not None:
            try:
                self.cap.release()
            except Exception:
                pass
            self.cap = None

    def stop(self) -> None:
        self._stop.set()
        self.close()
