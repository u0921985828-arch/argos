# ESTADO — bucle rápido en Workers

## Objetivo
Decidir **con medidas** si mover el bucle rápido a un Worker por cámara mejora
o empeora el techo multicámara. Eddie no sabe si será mejor o peor; la entrega
es la cifra, no el código.

## Línea base (medida, Engine real, 1280×720, 150 frames tras calentamiento)
| cámaras | ronda mediana | p95 |
|---:|---:|---:|
| 1 | 11,0 ms | 17,3 |
| 2 | 21,4 ms | 23,1 |
| 3 | 33,0 ms | 43,9 |
| 4 | 43,4 ms | 49,5 |
| 6 | 66,3 ms | 87,0 |
| 8 | 88,5 ms | 108,9 |

Serie en el hilo principal. Techo: 2 cámaras con holgura, 3 al borde.

## Criterios
1. **C1** Ronda de 4 cámaras < 33,3 ms (30 fps) con Workers. Si no se cumple,
   la respuesta es «no mejora» y eso también es entrega.
2. **C2** Regresión: el camino Worker produce las MISMAS pistas que el serie
   sobre la misma secuencia (ids y cajas).
3. **C3** El hilo principal queda libre: su coste por cámara < 3 ms.

## Caso reservado (no se mira hasta el final)
Añadir y quitar una cámara en caliente mientras las otras corren: ningún
Worker huérfano, ninguna pista mezclada entre cámaras.

## Plan (pesos = minutos esperados)
| # | paso | peso | estado |
|---|---|---:|---|
| 1 | ESTADO + criterios + línea base | 5 | hecho |
| 2 | Partir Engine: `capture()` / `analyze()` | 20 | hecho |
| 3 | `engine-worker.js` + cliente `EngineWorker` | 20 | aplazado: medir antes en navegador |
| 4 | Banco worker_threads 1/2/3/4/6/8 cámaras | 15 | hecho — no concluyente |
| 5 | Regresión serie vs Worker (C2) | 15 | hecho ✓ 200/200 idénticos |
| 6 | Caso reservado (alta/baja en caliente) | 10 | hecho ✓ |
| 7 | Cierre: bundle, docs, entrega | 15 | hecho |

## RESULTADO (medido) — el banco de este entorno NO decide la pregunta

| cámaras | serie | Workers | hilo principal |
|---:|---:|---:|---:|
| 1 | 10,9 ms | 14,0 | 8,9 |
| 3 | 33,3 ms | 28,6 | 25,0 |
| 4 | 45,0 ms | 37,2 | 34,7 |
| 8 | 88,5 ms | 71,1 | 68,8 |

Los Workers mejoran, pero poco (1,24× a 8 cámaras). **El motivo invalida la
medida entera**, y es el hallazgo importante:

```
capture()   7,62 ms   drawImage + getImageData   ← hilo principal, NO paraleliza
analyze()   2,58 ms   fondo+morfología+tracking  ← Worker
            el análisis es solo el 25 % del coste
```

`getImageData` sobre un lienzo de 320×180 tarda 7,6 ms en **node-canvas**
(render por software). En un navegador real cuesta décimas de milisegundo.

Consecuencias, por orden de importancia:

1. **La línea base de 11 ms/cámara es un artefacto de node-canvas.** En
   navegador el coste real por cámara se parece más a los 2,58 ms del análisis.
   El techo de «2-3 cámaras» que se documentó antes **hay que volver a medirlo
   en navegador**: probablemente sea muy superior.
2. **Aquí hay 2 núcleos.** Ningún esquema de Workers puede dar más de ~2×,
   así que este banco no puede mostrar la ganancia real.
3. `analyze()` = 2,58 ms sí es representativo: es JavaScript puro, sin lienzo.

Proyección (NO medida) con captura de navegador ~0,3 ms:
4 cámaras → 1,2 + 2,6 = 3,8 ms · 8 cámaras → 2,4 + 2,6 = 5,0 ms.

**Decisión: el corte se queda** (es correcto, idéntico al serie y no cuesta
nada), y la cifra de cámaras se mide en navegador antes de prometerla.

## Punto de corte hallado
`process()` hace captura (drawImage + getImageData) y luego análisis. Lo único
que usa el frame GRANDE después del corte son los recortes (línea 889) y la
placa de fondo (línea 928): los dos se quedan en el hilo principal y ya van con
cadencia propia. El corte es limpio en `bg.update(frame.data)`.

Al Worker viaja la ImageData YA reducida (~320×180 = 230 KB) como
transferible, no el frame de 1280×720. Si hubiera que mandar el frame grande,
el coste de transferencia se comería la ganancia; ése era el riesgo principal.

## Cadena A→B→C
- A: Worker con transferible de la ImageData pequeña.
- B: si la transferencia domina, OffscreenCanvas al Worker y que él capture.
- C: si tampoco, se documenta que el techo es 2-3 y se cierra sin cambio.

## Caminos cerrados
- Bajar la resolución de la fuente: medido, no mueve el coste (11,1 → 10,7 ms
  de 1080p a 352×288).
- `analysisWidth` 320→160: compra una cámara y degrada siluetas. Descartado.

## Medido con `canvas` de Node, no en navegador
Node usa render por software. Las cifras valen para comparar serie vs Worker
en igualdad de condiciones, no como predicción exacta del navegador.


---

# FASE 2 — app nativa Android

## Hecho y verificado (JVM, sin dispositivo)
- `android-native/core/.../Core.kt`: puerto literal de `engine.js` a Kotlin.
- **C1 paridad: 260 frames, 0 divergencias frente al JS.** Criterio cero, cumplido.
- **C2 velocidad: `analyze()` 1,845 ms frente a 2,58 ms del JS (1,4×).**
- Toolchain: kotlinc 2.0.21 descargado de GitHub (la red lo permite); JDK 21 ya estaba.

## Hallazgo que justifica lo nativo
No es el 1,4× del algoritmo. Es que `capture()` era el **75 %** del coste y en
Android desaparece: CameraX entrega los píxeles sin lienzo ni lectura de GPU, y
el plano Y de `YUV_420_888` ya es luma, que es justo lo que consume el modelo
de fondo.

## PENDIENTE — requiere SDK de Android, que aquí no hay
- `app/`: CameraX + superficie de dibujo + permisos.
- Detector YOLOX por TFLite con delegado NNAPI/GPU.
- Gradle del módulo nativo y compilación del APK.
- `analyzeLuma()`: variante que consume el plano Y directamente, saltándose
  RGBA. Diseñada, no escrita. Ojo al rango: el plano Y de cámara suele venir
  en 16-235, no 0-255; hay que comprobarlo antes de dar paridad por buena.

## Continuación recomendada: Claude Code
El trabajo que queda necesita SDK, dispositivo y medidas en ARM. `CLAUDE.md` y
este fichero bastan para retomarlo sin repetir nada.


---

# FASE 3 — app nativa que analiza de verdad

## Objetivo
Que el APK analice con la cámara sin el techo del WebView: sin `getImageData`
(75 % del coste del frame) y sin la inferencia en un hilo que impone la falta
de aislamiento entre orígenes (medido: el WebView no lo concede nunca).

## Línea base (medida, APK web, emulador API 34 del CI, 2 núcleos, sin GPU)
- primera inferencia 687 ms · 1 hilo · `aislado=false` · `SAB=false` (run 25)
- captura: `drawImage`+`getImageData` = 75 % del coste del bucle rápido (FASE 1)

## Criterios
1. **C1** El detector Kotlin da las MISMAS detecciones que `detector.js` en
   cuadro entero sobre bus.jpg, zidane.jpg y dog.jpg (cajas ±0,5 px,
   puntuación ±1e-3, misma clase agrupada).
2. **C2** En el emulador del CI el APK nativo arranca, CameraX entrega frames,
   el núcleo analiza N>0 y el detector hace K>0 inferencias sobre frames de
   cámara. Lo prueba logcat, no la ausencia de errores.
3. **C3** Inferencia nativa medida en el mismo emulador frente a los 687 ms del
   web. La entrega es la cifra, mejor o peor.

## Caso reservado
Plano Y con `rowStride > width` (relleno por fila, lo normal en móviles
reales): mismas pistas que el mismo frame sin relleno.

## Decisiones
- **ONNX Runtime Android, no TFLite.** Reutiliza `yolox_nano.onnx`, ya validado
  con su preproceso (BGR crudo 0-255, relleno 114). Convertir a TFLite pide
  herramientas y una validación nueva del modelo; ORT da el mismo modelo con
  hilos nativos y NNAPI/XNNPACK. TFLite queda como paso siguiente si C3 lo pide.
- El SDK de Android solo está en el CI (dl.google.com bloqueado aquí): el
  núcleo y el detector se verifican en la JVM, la app en el emulador del CI.
