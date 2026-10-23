# Ronda 1 · 2026-10-23T10:49Z

Fotogramas: 1200 · épocas 20 · desde: yolox_nano.pth

| modelo | modo | corte | recall | precisión | aciertos | falsos | por tamaño | día / noche |
|---|---|---|---|---|---|---|---|---|
| yolox_nano.onnx | cuadro entero | 0.3 | 37.8% | 68.3% | 71/188 | 33 | <12 px: 11/29, 12-24 px: 24/76, 24-48 px: 20/49, >48 px: 16/34 | día: 64/163, falsos 22, noche: 7/25, falsos 11 |
| yolox_nano.onnx | cuadro entero | 0.5 | 22.3% | 95.5% | 42/188 | 2 | <12 px: 2/29, 12-24 px: 12/76, 24-48 px: 15/49, >48 px: 13/34 | día: 39/163, falsos 1, noche: 3/25, falsos 1 |
| yolox_nano.onnx | teselas ×2 | 0.3 | 53.7% | 38.1% | 101/188 | 164 | <12 px: 17/29, 12-24 px: 42/76, 24-48 px: 30/49, >48 px: 12/34 | día: 87/163, falsos 102, noche: 14/25, falsos 62 |
| yolox_nano.onnx | teselas ×2 | 0.5 | 35.6% | 61.5% | 67/188 | 42 | <12 px: 6/29, 12-24 px: 28/76, 24-48 px: 24/49, >48 px: 9/34 | día: 58/163, falsos 24, noche: 9/25, falsos 18 |
| argos_trafico.onnx | cuadro entero | 0.3 | 59.0% | 80.4% | 111/188 | 27 | <12 px: 20/29, 12-24 px: 42/76, 24-48 px: 27/49, >48 px: 22/34 | día: 96/163, falsos 23, noche: 15/25, falsos 4 |
| argos_trafico.onnx | cuadro entero | 0.5 | 48.4% | 91.0% | 91/188 | 9 | <12 px: 15/29, 12-24 px: 33/76, 24-48 px: 24/49, >48 px: 19/34 | día: 83/163, falsos 9, noche: 8/25, falsos 0 |
| argos_trafico.onnx | teselas ×2 | 0.3 | 76.6% | 63.2% | 144/188 | 84 | <12 px: 26/29, 12-24 px: 61/76, 24-48 px: 39/49, >48 px: 18/34 | día: 123/163, falsos 48, noche: 21/25, falsos 36 |
| argos_trafico.onnx | teselas ×2 | 0.5 | 62.8% | 79.2% | 118/188 | 31 | <12 px: 18/29, 12-24 px: 51/76, 24-48 px: 34/49, >48 px: 15/34 | día: 103/163, falsos 20, noche: 15/25, falsos 11 |

```
cerca bus.jpg      yolox_nano.onnx        bus 0.90, person 0.86, person 0.81, person 0.70
cerca bus.jpg      argos_trafico.onnx     truck 0.71, person 0.67
cerca zidane.jpg   yolox_nano.onnx        person 0.88, person 0.83
cerca zidane.jpg   argos_trafico.onnx     person 0.81, person 0.77
cerca dog.jpg      yolox_nano.onnx        bicycle 0.80, car 0.80
cerca dog.jpg      argos_trafico.onnx     truck 0.83
```
