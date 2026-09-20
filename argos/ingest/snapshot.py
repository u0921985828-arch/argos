"""Ingest from HTTP snapshot endpoints.

Municipal and traffic cameras rarely expose RTSP to the public. What they expose
is a URL that returns *one JPEG, right now* --- for example the GeoBilbao
municipal camera service, whose endpoints look like:

    https://www.geobilbao.eus/geobilbao/api/cameraImage/GEOBILBAO_CamarasMunicipales/9

That is a different ingest problem from a stream, and treating it like one is
how these integrations break:

*   **There is no frame rate.** The server refreshes on its own schedule, often
    every 5-30 seconds, and polling faster just returns the same bytes. So the
    poller detects *novelty* rather than assuming a cadence, and learns the real
    refresh interval to avoid hammering a public service.
*   **Frames are far apart.** At one frame every 10 s an object crosses the view
    in two or three frames. IoU-based association is useless at that spacing, so
    the tracker must lean on appearance, and many objects will simply never form
    a tube. Snapshot cameras are good for occupancy, counting and heat maps;
    they are poor for trajectory analytics, and the system should say so rather
    than emit confident nonsense.
*   **Timestamps are the server's, not yours.** ``Last-Modified`` is the capture
    time; local receive time can lag by seconds. Anything forensic must use the
    former, and record that it did.
*   **Snapshots repeat and go stale.** A frozen camera returns valid JPEGs
    forever. Content hashing catches it; nothing else does.

Access note: this module sends a descriptive User-Agent and honours a minimum
polling interval by default. Public camera services are a shared resource and
they do block. The endpoint above returns HTTP 403 to a bare request, so a real
deployment needs whatever headers or registration the operator requires --- that
is a matter for the publisher's terms, not something to work around.
"""

from __future__ import annotations

import hashlib
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from email.utils import parsedate_to_datetime
from typing import Callable, Iterator

import numpy as np

try:
    import cv2
except ImportError:                                  # pragma: no cover
    cv2 = None


@dataclass
class SnapshotConfig:
    url: str
    min_interval_s: float = 2.0        # never poll faster than this
    max_interval_s: float = 60.0
    initial_interval_s: float = 5.0
    timeout_s: float = 10.0
    user_agent: str = "ARGOS-video-analytics/0.1 (+contact configured by operator)"
    headers: dict = field(default_factory=dict)
    stale_after_repeats: int = 6       # identical bytes N times running = frozen
    verify_tls: bool = True


@dataclass
class Snapshot:
    frame: np.ndarray
    captured_at: datetime              # server time when available
    received_at: datetime
    digest: str
    interval_s: float                  # measured gap since previous novel frame
    time_source: str                   # 'server' | 'local'


class SnapshotSource:
    """Polls a still-image endpoint and yields only genuinely new frames."""

    def __init__(self, cfg: SnapshotConfig, fetcher: Callable[[str, dict, float], tuple] | None = None):
        self.cfg = cfg
        self._fetch = fetcher or self._http_fetch
        self._last_digest: str | None = None
        self._repeats = 0
        self._interval = cfg.initial_interval_s
        self._last_novel: datetime | None = None
        self._etag: str | None = None
        self.stats = {"polls": 0, "novel": 0, "repeats": 0, "errors": 0}

    # ------------------------------------------------------------------ #

    def _http_fetch(self, url: str, headers: dict, timeout: float):
        import urllib.request
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read(), dict(r.headers), r.status

    def _headers(self) -> dict:
        h = {"User-Agent": self.cfg.user_agent, "Accept": "image/jpeg,image/*"}
        h.update(self.cfg.headers)
        if self._etag:
            # Let the server tell us nothing changed instead of shipping bytes.
            h["If-None-Match"] = self._etag
        return h

    def poll(self) -> Snapshot | None:
        cfg = self.cfg
        self.stats["polls"] += 1
        try:
            body, headers, status = self._fetch(cfg.url, self._headers(), cfg.timeout_s)
        except Exception:
            self.stats["errors"] += 1
            self._interval = min(cfg.max_interval_s, self._interval * 1.8)
            return None

        if status == 304 or not body:
            self._repeats += 1
            self.stats["repeats"] += 1
            self._slow_down()
            return None

        digest = hashlib.sha256(body).hexdigest()
        if digest == self._last_digest:
            self._repeats += 1
            self.stats["repeats"] += 1
            self._slow_down()
            return None

        if cv2 is None:
            raise RuntimeError("opencv is required to decode snapshots")
        frame = cv2.imdecode(np.frombuffer(body, np.uint8), cv2.IMREAD_COLOR)
        if frame is None:
            self.stats["errors"] += 1
            return None

        now = datetime.now(timezone.utc)
        captured, source = now, "local"
        lm = headers.get("Last-Modified") or headers.get("last-modified")
        if lm:
            try:
                captured, source = parsedate_to_datetime(lm), "server"
            except (TypeError, ValueError):
                pass

        gap = (captured - self._last_novel).total_seconds() if self._last_novel else float("nan")
        self._last_novel = captured
        self._last_digest = digest
        self._etag = headers.get("ETag") or headers.get("etag")
        self._repeats = 0
        self.stats["novel"] += 1
        self._speed_up(gap)

        return Snapshot(frame, captured, now, digest, gap, source)

    # ------------------------------------------------------------------ #

    def _slow_down(self) -> None:
        """Back off toward the observed refresh period rather than spinning."""
        self._interval = min(self.cfg.max_interval_s, self._interval * 1.35)

    def _speed_up(self, measured_gap: float) -> None:
        """Track the server's real cadence: poll a little under it."""
        if np.isfinite(measured_gap) and measured_gap > 0:
            target = max(self.cfg.min_interval_s, measured_gap * 0.45)
            self._interval = 0.7 * self._interval + 0.3 * target
        self._interval = float(np.clip(self._interval, self.cfg.min_interval_s,
                                       self.cfg.max_interval_s))

    @property
    def is_stale(self) -> bool:
        return self._repeats >= self.cfg.stale_after_repeats

    @property
    def poll_interval(self) -> float:
        return self._interval

    def stream(self, max_frames: int | None = None,
               sleep: Callable[[float], None] = time.sleep) -> Iterator[Snapshot]:
        n = 0
        while max_frames is None or n < max_frames:
            snap = self.poll()
            if snap is not None:
                n += 1
                yield snap
            sleep(self._interval)


# --------------------------------------------------------------------------- #


def suitability_report(intervals: list[float]) -> dict:
    """Tell the operator what this camera can and cannot support.

    A 10-second refresh is perfectly good for counting and heat maps and useless
    for trajectories. Saying so up front is better than silently producing tubes
    of length two.
    """
    a = np.asarray([i for i in intervals if np.isfinite(i) and i > 0], float)
    if a.size < 3:
        return {"verdict": "unknown", "reason": "not enough samples"}
    med = float(np.median(a))
    caps = {
        "median_interval_s": round(med, 2),
        "effective_fps": round(1.0 / med, 3),
        "jitter_s": round(float(np.percentile(a, 90) - np.percentile(a, 10)), 2),
    }
    if med <= 0.5:
        caps.update(verdict="full", supports=["tracking", "trajectories", "synopsis",
                                              "speed", "counting", "heatmap"])
    elif med <= 3.0:
        caps.update(verdict="limited",
                    supports=["counting", "heatmap", "occupancy", "coarse tracking"],
                    excludes=["speed", "stature", "synopsis"])
    else:
        caps.update(verdict="snapshot-only",
                    supports=["counting", "heatmap", "occupancy", "change detection"],
                    excludes=["tracking", "trajectories", "speed", "stature", "synopsis"])
    return caps
