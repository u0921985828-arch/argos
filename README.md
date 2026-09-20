# ARGOS

Video synopsis + analítica forense de vídeo. Núcleo del sistema, con benchmarks
reproducibles.

Estudio completo: [`docs/ESTUDIO.md`](docs/ESTUDIO.md)

## Instalación

```bash
pip install numpy scipy opencv-python-headless onnxruntime
```

## Uso

```python
from argos.synopsis.optimizer import SolverConfig, SynopsisSolver
from argos.synopsis.renderer import SynopsisRenderer
from argos.tubes.patchstore import build_patch_store

solver = SynopsisSolver(tubes, SolverConfig(max_overlap_ratio=0.012))
plan = solver.auto()                      # busca la duración más corta legible
print(plan.summary())                     # 26912 -> 1151 frames (23.4x)

store = build_patch_store(tubes, frame_getter)   # una pasada sobre el origen
SynopsisRenderer(tubes, plan).render(store, plate, "salida.mp4")
```

## Reproducir los benchmarks

```bash
python3 tests/bench_synopsis.py     # solver vs baselines, verificación independiente
python3 tests/test_tracker.py       # fragmentación del tracker vs ground truth
python3 scripts/demo_render.py      # sinopsis renderizado + contact sheet
```

## Licencias

ONNX Runtime (MIT), RT-DETR / RTMDet / YOLOX (Apache-2.0). **No** se usa Ultralytics
YOLO (AGPL-3.0) — ver §4.8 del estudio.

## Limitación de alcance

No incluye reconocimiento facial ni lectura de matrículas. Ver §6 del estudio.
