"""Object patch storage.

A synopsis only ever draws *objects* --- the rest of every frame is discarded in
favour of a clean plate. So there is no reason to keep, re-decode or even retain
the source video in order to render one.

``PatchStore`` extracts each object's crop once, at ingest time, and keeps it
JPEG-compressed keyed by ``(tube_id, frame_idx)``. Consequences:

*   Rendering is O(objects), not O(frames), and needs no seeking. Rendering a
    45x-compressed synopsis of an 18-minute clip touched ~5,700 source frames in
    the naive design --- roughly 9 GB of RAM at 960x540. The patch store holds
    the same content in tens of megabytes.
*   The archive can be reduced to *tubes plus patches*: a fully re-renderable,
    fully searchable representation of the footage at a small fraction of the
    original bitrate. Re-running a query a year later never needs the original.
*   Because patches are per-object, redaction is trivial and reversible: drop or
    blur a tube's patches and it is gone from every synopsis and every export,
    which is what a GDPR erasure request actually requires.
"""

from __future__ import annotations

from collections import OrderedDict
from dataclasses import dataclass, field

import cv2
import numpy as np


class PatchSource:
    """Interface used by the renderer."""

    def patch(self, tube_id: int, frame_idx: int) -> np.ndarray | None:  # pragma: no cover
        raise NotImplementedError


@dataclass
class PatchStore(PatchSource):
    """JPEG-backed crop store with an optional hard memory ceiling.

    ``max_bytes`` matters for any long-running session, and especially on a
    phone: a live camera produces crops forever, so an unbounded store is a slow
    leak that ends in the OS killing the process hours in. When the ceiling is
    reached the oldest entries are evicted, which degrades old synopses rather
    than killing the session --- the right trade, since the recent past is what
    anyone is about to ask for.
    """

    quality: int = 82
    max_bytes: int = 0                  # 0 = sin límite
    _data: "OrderedDict[tuple[int, int], bytes]" = field(default_factory=OrderedDict)
    _redacted: set[int] = field(default_factory=set)
    _bytes: int = 0
    evicted: int = 0

    def add(self, tube_id: int, frame_idx: int, crop: np.ndarray) -> None:
        if crop.size == 0:
            return
        ok, buf = cv2.imencode(".jpg", crop, [int(cv2.IMWRITE_JPEG_QUALITY), self.quality])
        if not ok:
            return
        blob = buf.tobytes()
        key = (tube_id, frame_idx)
        if key in self._data:
            self._bytes -= len(self._data[key])
        self._data[key] = blob
        self._bytes += len(blob)
        self._evict()

    def _evict(self) -> None:
        if not self.max_bytes:
            return
        while self._bytes > self.max_bytes and self._data:
            _, blob = self._data.popitem(last=False)
            self._bytes -= len(blob)
            self.evicted += 1

    def patch(self, tube_id: int, frame_idx: int) -> np.ndarray | None:
        if tube_id in self._redacted:
            return None
        blob = self._data.get((tube_id, frame_idx))
        if blob is None:
            return None
        return cv2.imdecode(np.frombuffer(blob, np.uint8), cv2.IMREAD_COLOR)

    def redact(self, tube_id: int) -> int:
        """Erase one object everywhere. Returns the number of patches removed."""
        keys = [k for k in self._data if k[0] == tube_id]
        for k in keys:
            self._bytes -= len(self._data[k])
            del self._data[k]
        self._redacted.add(tube_id)
        return len(keys)

    @property
    def nbytes(self) -> int:
        return self._bytes

    def __len__(self) -> int:
        return len(self._data)


@dataclass
class FramePatchSource(PatchSource):
    """Adapter for when full frames are already in memory (tests, short clips)."""

    frames: dict[int, np.ndarray]
    boxes: dict[tuple[int, int], np.ndarray]

    def patch(self, tube_id: int, frame_idx: int) -> np.ndarray | None:
        f = self.frames.get(frame_idx)
        if f is None:
            return None
        b = self.boxes.get((tube_id, frame_idx))
        if b is None:
            return None
        H, W = f.shape[:2]
        x1, y1, x2, y2 = [int(round(v)) for v in b]
        x1, y1 = max(0, x1), max(0, y1)
        x2, y2 = min(W, x2), min(H, y2)
        if x2 <= x1 or y2 <= y1:
            return None
        return f[y1:y2, x1:x2]


def build_patch_store(tubes, frame_getter, quality: int = 82) -> PatchStore:
    """Single sequential pass over the source, extracting every object crop."""
    store = PatchStore(quality=quality)
    needed: dict[int, list[tuple[int, np.ndarray]]] = {}
    for t in tubes:
        for tf in t.frames:
            needed.setdefault(tf.frame_idx, []).append((t.tube_id, tf.bbox))
    for idx in sorted(needed):
        frame = frame_getter(idx)
        if frame is None:
            continue
        H, W = frame.shape[:2]
        for tube_id, bbox in needed[idx]:
            x1, y1, x2, y2 = [int(round(v)) for v in bbox]
            x1, y1 = max(0, x1), max(0, y1)
            x2, y2 = min(W, x2), min(H, y2)
            if x2 > x1 and y2 > y1:
                store.add(tube_id, idx, frame[y1:y2, x1:x2])
    return store
