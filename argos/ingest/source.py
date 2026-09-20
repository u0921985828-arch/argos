"""One interface for every kind of camera.

The point of this module is that nothing downstream should care where frames
came from. A file, an RTSP stream from an IP camera, a municipal snapshot
endpoint and a phone pushing JPEGs over HTTP are four completely different
transports with four completely different failure modes, and exactly one thing
in common: they produce timestamped frames from a fixed viewpoint.

The differences that *do* leak through are captured in :class:`SourceCapability`
rather than hidden. A source delivering one frame every 15 seconds cannot
support speed measurement no matter how good the rest of the pipeline is, and
the honest thing is to say so at registration time instead of producing numbers
nobody should trust.

On running this on the phone itself: don't. A phone can decode and it can run a
small detector, but the tracker needs the whole session in memory, the synopsis
solver is a global optimisation over every tube in the window, and both want
tens of seconds of CPU that a phone will thermally throttle away. The phone's
job is to be a camera and a screen. Everything else belongs on a machine that
is plugged in.
"""

from __future__ import annotations

import queue
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


@dataclass(slots=True)
class Frame:
    idx: int
    image: np.ndarray
    ts: datetime
    time_source: str = "local"       # 'server' | 'stream' | 'local'


@dataclass
class SourceCapability:
    """What this source can honestly support."""

    # Optional rather than NaN: NaN is not valid JSON, so a capability report
    # that has not gathered enough samples yet would crash the API on
    # serialisation. Absent data should be absent, not a float that lies.
    median_interval_s: float | None
    effective_fps: float | None
    jitter_s: float | None
    verdict: str                     # full | limited | snapshot-only | unknown
    supports: list[str] = field(default_factory=list)
    excludes: list[str] = field(default_factory=list)

    @staticmethod
    def from_intervals(intervals: list[float]) -> "SourceCapability":
        a = np.asarray([i for i in intervals if np.isfinite(i) and i > 0], float)
        if a.size < 3:
            return SourceCapability(None, None, None, "unknown")
        med = float(np.median(a))
        jit = float(np.percentile(a, 90) - np.percentile(a, 10))
        cap = SourceCapability(round(med, 3), round(1 / med, 2), round(jit, 3), "unknown")
        if med <= 0.5:
            cap.verdict = "full"
            cap.supports = ["tracking", "trajectories", "synopsis", "speed",
                            "stature", "counting", "heatmap", "silhouettes"]
        elif med <= 3.0:
            cap.verdict = "limited"
            cap.supports = ["counting", "heatmap", "occupancy", "coarse tracking",
                            "silhouettes"]
            cap.excludes = ["speed", "stature", "synopsis"]
        else:
            cap.verdict = "snapshot-only"
            cap.supports = ["counting", "heatmap", "occupancy", "change detection"]
            cap.excludes = ["tracking", "trajectories", "speed", "stature", "synopsis"]
        return cap


class VideoSource:
    """Base interface."""

    name: str = "source"

    def frames(self, max_frames: int | None = None) -> Iterator[Frame]:  # pragma: no cover
        raise NotImplementedError

    def close(self) -> None:
        pass


# --------------------------------------------------------------------------- #
#  Backends
# --------------------------------------------------------------------------- #


class CaptureSource(VideoSource):
    """Anything OpenCV can open: local file, RTSP, HTTP MJPEG, USB device index.

    RTSP over TCP is forced where possible. UDP drops packets silently under
    load and the result is not a dropped frame but a *corrupted* one --- half
    the image from the previous scene --- which background subtraction will
    faithfully report as a large moving object.
    """

    def __init__(self, uri: str | int, stride: int = 1, name: str | None = None,
                 rtsp_tcp: bool = True):
        if cv2 is None:
            raise RuntimeError("opencv is required")
        if rtsp_tcp and isinstance(uri, str) and uri.startswith("rtsp"):
            import os
            os.environ.setdefault("OPENCV_FFMPEG_CAPTURE_OPTIONS", "rtsp_transport;tcp")
        self.uri = uri
        self.stride = max(1, stride)
        self.name = name or str(uri)
        self.cap = cv2.VideoCapture(uri)
        if not self.cap.isOpened():
            raise RuntimeError(f"cannot open source: {uri}")
        self.declared_fps = float(self.cap.get(cv2.CAP_PROP_FPS) or 0.0)
        self.width = int(self.cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        self.height = int(self.cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        self.total = int(self.cap.get(cv2.CAP_PROP_FRAME_COUNT))
        self.is_live = self.total <= 0

    def frames(self, max_frames: int | None = None) -> Iterator[Frame]:
        idx = read = 0
        while max_frames is None or idx < max_frames:
            ok, img = self.cap.read()
            if not ok:
                break
            if read % self.stride == 0:
                # For files the timestamp comes from the media clock, which is
                # exact. For live streams it does not exist, so wall clock it is
                # -- and the difference is recorded, because a forensic figure
                # must never silently rest on an inferred time.
                if not self.is_live and self.declared_fps > 0:
                    pos = self.cap.get(cv2.CAP_PROP_POS_MSEC) / 1000.0
                    ts = datetime.fromtimestamp(pos, tz=timezone.utc)
                    src = "stream"
                else:
                    ts, src = datetime.now(timezone.utc), "local"
                yield Frame(idx, img, ts, src)
                idx += 1
            read += 1

    def close(self) -> None:
        self.cap.release()


class SnapshotSourceAdapter(VideoSource):
    """Wraps :mod:`argos.ingest.snapshot` in the common interface."""

    def __init__(self, url: str, name: str | None = None, **kw):
        from .snapshot import SnapshotConfig, SnapshotSource
        self.inner = SnapshotSource(SnapshotConfig(url=url, **kw))
        self.name = name or url
        self.intervals: list[float] = []

    def frames(self, max_frames: int | None = None) -> Iterator[Frame]:
        for i, snap in enumerate(self.inner.stream(max_frames)):
            self.intervals.append(snap.interval_s)
            yield Frame(i, snap.frame, snap.captured_at, snap.time_source)

    def capability(self) -> SourceCapability:
        return SourceCapability.from_intervals(self.intervals)


class PushSource(VideoSource):
    """Frames arrive from outside --- a phone POSTing JPEGs, a webhook, a test.

    A bounded queue with newest-wins eviction, deliberately. If the pipeline
    falls behind a phone pushing at 10 fps, the useful thing is the *current*
    view of the scene, not a growing backlog of stale frames that pushes latency
    to minutes and eventually exhausts memory. Dropping is a feature; the drop
    count is exposed so it can be alarmed on.
    """

    def __init__(self, name: str = "push", maxsize: int = 8):
        self.name = name
        self.q: queue.Queue = queue.Queue(maxsize=maxsize)
        self._idx = 0
        self._closed = False
        self.dropped = 0
        self.received = 0
        self.intervals: list[float] = []
        self._last: float | None = None
        self._lock = threading.Lock()

    def push(self, image: np.ndarray, ts: datetime | None = None) -> bool:
        if self._closed:
            return False
        now = time.monotonic()
        with self._lock:
            if self._last is not None:
                self.intervals.append(now - self._last)
            self._last = now
            self.received += 1
            idx = self._idx
            self._idx += 1
        f = Frame(idx, image, ts or datetime.now(timezone.utc), "local")
        try:
            self.q.put_nowait(f)
            return True
        except queue.Full:
            try:
                self.q.get_nowait()
                self.dropped += 1
            except queue.Empty:
                pass
            try:
                self.q.put_nowait(f)
            except queue.Full:
                self.dropped += 1
            return True

    def frames(self, max_frames: int | None = None, timeout: float = 30.0) -> Iterator[Frame]:
        n = 0
        while (max_frames is None or n < max_frames) and not self._closed:
            try:
                yield self.q.get(timeout=timeout)
                n += 1
            except queue.Empty:
                break

    def capability(self) -> SourceCapability:
        return SourceCapability.from_intervals(self.intervals)

    def close(self) -> None:
        self._closed = True


# --------------------------------------------------------------------------- #


def open_source(spec: str | int, **kw) -> VideoSource:
    """Build the right backend from a plain string.

        open_source("rtsp://user:pw@10.0.0.5/stream1")
        open_source("https://.../cameraImage/.../9")     -> snapshot poller
        open_source("/ruta/video.mp4")
        open_source(0)                                    -> USB webcam
        open_source("push://telefono-salon")
    """
    if isinstance(spec, int):
        return CaptureSource(spec, **kw)
    s = str(spec)
    if s.startswith("push://"):
        return PushSource(name=s[7:] or "push")
    if s.startswith(("http://", "https://")):
        # An MJPEG stream keeps the connection open; a snapshot endpoint returns
        # one image and closes. Guessing wrong is the usual cause of an
        # integration that "works" at one frame per poll for no reason.
        if any(t in s.lower() for t in ("mjpg", "mjpeg", "stream", ".m3u8", "video")):
            return CaptureSource(s, **kw)
        return SnapshotSourceAdapter(s, **kw)
    return CaptureSource(s, **kw)
