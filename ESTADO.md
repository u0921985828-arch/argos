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

## RESULTADO FASE 3 (medido)

| criterio | resultado |
|---|---|
| C1 detector Kotlin = detector.js | **cumple**: bus, zidane, dog; cuadro entero y teselas ×2; cajas ±0,0005 px, puntuación ±9e-6. En el emulador, la autoprueba da `vehicle 0.767 · bike 0.787 · bike 0.309`, idéntico a detector.js |
| C2 cámara → núcleo → detector | **cumple**: 219 frames analizados y 5 inferencias sobre frames de cámara en 20 s |
| C3 inferencia nativa frente a 687 ms de la web | **peor en el emulador**: primera 1.313-1.782 ms, en régimen 2.587-2.787 ms |
| caso reservado (rowStride relleno) | **cumple**: 220 frames, 0 divergencias, en local y en el CI |

### C3, leído con cuidado
El emulador del CI no predice un teléfono: el mismo `analyze()` cuesta 1,8 ms
en la JVM de la máquina y 25-52 ms en el emulador, y la inferencia ORT de la
JVM son 24 ms contra 2.787 ms en el emulador. Ese factor ~100 es del
emulador (dos núcleos virtuales, la vista previa renderizada por software), no
del código. Además el calentamiento nativo coincide con el arranque de la
cámara y el de la web no se sabe si coincidía. **La cifra que decide es la del
teléfono**, y el panel la da.

### Lo que sí movió una medida
Espera activa de ORT desactivada y un núcleo reservado al reloj rápido:
fps 13-18 → 22-25 y análisis 38-52 ms → 26-35 ms, en el mismo emulador.

### Arreglos en la app web que salieron de aquí
- `analyze()` usaba `roi` fuera de ámbito: con el detector activo, cero pistas
  en el navegador (NaN silencioso). `tests/test_engine_js.py`.
- El detector repetía sus cajas en todos los frames y tiraba las regiones de
  movimiento: persona congelada y lo no detectado desaparecía. Ahora entra una
  vez por pasada y el fondo sigue. `tests/test_tracking_js.py`.

## Caminos cerrados
- `analyzeLuma` sobre el plano Y: descartado. El detector necesita color y la
  imagen girada; CameraX ya da RGBA girado en código nativo y la conversión
  que se ahorraba no es donde está el coste.
- TFLite: aplazado. ORT Android ejecuta el mismo modelo ya validado; convertir
  pide herramientas y otra validación. Solo si el teléfono dice que hace falta.

## Siguiente (necesita el teléfono)
1. Instalar ARGOS-nativo.apk y leer el panel: fps, captura · reducir ·
   análisis, detector e inferencia.
2. Si la inferencia en el teléfono es peor que la web: probar XNNPACK y NNAPI
   y comparar con la misma autoprueba.
3. Comprobar el rango del color de cámara (16-235 frente a 0-255).


---

# FASE 4 — que la nativa sea mucho mejor y más inteligente

## Punto de partida (medido en el teléfono de Eddie, captura del 22-10)
26 fps · captura 0,3 · reducir 3,4 · análisis 14,9 ms · detector 489 ms con
20 teselas (inferencia 417 ms, ~21 ms por inferencia, 4 hilos) · 0 objetos ·
5 pistas, todas «movimiento» sobre muebles: la cámara iba en la mano.

C3 de la fase 3 queda contestado por el aparato real: **~21 ms por inferencia**
en el teléfono, frente a 687 ms de la web en el emulador. El emulador no
predecía nada.

## Línea base (JVM, núcleo actual)
- Paneo sintético con temblor y SIN objetos: **106 de 150 frames con pistas
  falsas, 5.840 pistas falsas** (`HandheldCase.kt`).

## Criterios
1. **C1 cámara en mano**: el mismo paneo da ≤ 5 frames con pistas falsas, y
   con una persona andando, la persona sigue vista en ≥ 80 % de los frames.
2. **C2 poca luz**: bus y zidane oscurecidos (×0,12): las detecciones con
   puntuación ≥ 0,3 recuperan ≥ 80 % de las de la foto normal.
3. **C3 zoom automático**: elige cuadro entero en bus/zidane y teselas en una
   escena de personas pequeñas, donde las teselas encuentran más.
4. **C4 escena**: escala métrica y velocidad portadas de brain.js con paridad
   numérica; contadores y eventos sobre ellas.
5. **C5 memoria**: trayectorias (sin imágenes) guardadas y recuperadas, con
   retención de 30 días aplicada.
6. **C6** CI: compila y en el emulador publica escena y memoria.

## Caso reservado
Cámara en mano siguiendo a una persona que anda en sentido CONTRARIO al paneo.

## C2 poca luz — resultado NEGATIVO, no se añade nada
Medido con bus y zidane oscurecidos a ×0,12: el detector NO pierde nada (bus
4 → 5 objetos ≥ 0,3, zidane 2 → 2). Una ganancia automática (×4) da lo mismo.
Así que no se añade: una capa que no mejora ninguna medida es solo superficie
para fallos. Los 0 objetos de la captura del teléfono no eran por la
oscuridad: en esa habitación no había nadie.

## Ataque a la compensación de cámara (revisión independiente) y arreglos
Un revisor que no vio la construcción atacó el estimador con un mundo bilineal
(`CasosCamara.kt`, tarea `:core:casosCamara`, en el CI). Arreglado y medido:

| caso | antes | ahora |
|---|---|---|
| giro de muñeca ±1° | 150/150 frames con falsos, 1.526 pistas | 0 |
| objeto grande cruzando con la cámara FIJA | 5 frames con falsos; luego «en mano» 167 frames | 0 y 0 |
| arranque (fondo sin asentar) | «en mano» 26 frames en todo | 0 |
| cámara en mano con persona (C1) | persona vista 100/150 | 150/150 |
| deriva 0,25 y 0,5 px/f | — | 0 frames con falsos |

Cómo: coste = fracción de píxeles que no casan (un objeto aporta solo su área);
desplazamiento estimado por separado arriba y abajo (una traslación los mueve
igual, un giro no); estado QUIETA/MOVIDA/INESTABLE con histéresis; con
INESTABLE no se emite «movimiento», solo cajas del detector; INESTABLE exige
desajuste repartido (≥ 11 de 16 celdas), no un objeto grande; la compensación
no arranca hasta el frame 26, cuando el fondo ya está asentado.

**Regla nueva — muestrear con el paso de la textura miente.** La medida de
textura comparaba vecinos inmediatos con paso 3; con bloques de 6 px caía
entera entre muestras (0,22 en vez de ~40), la escena se daba por lisa, el
paneo no se compensaba y el modelo se reseteaba: de ahí los 100/150. La
diferencia se toma ahora a la misma distancia que el paso.

Velocidad y eventos: velocidad en coordenadas de ESCENA (se resta lo que se
movió la cámara), escala anclada en coordenadas de IMAGEN; «detenido» por
tiempo quieto acumulado, no por ventana; eventos y anomalía solo con clase del
detector; la escala se rehace al asentarse un encuadre nuevo. La memoria
guarda con un candado por proceso (Pipeline y pantalla de memoria abren cada
una su `TrackStore`) y vuelca las pistas vivas al cerrar.

### Acotado, no arreglado
- Deriva de 0,1 px/f: 30/150 frames con falsos (sin compensar, 84). Los pasos
  son enteros y la deriva es subpíxel; arreglarlo pide desplazar el fondo con
  interpolación. El test impide que empeore (≤ 35).
- Escena oscura de contraste 30 con temblor: 21/150, igual con y sin
  compensación. No es del estimador. El test exige no empeorar.
- Rayas periódicas en paneo: 0 falsos, pero 3 reinicios del fondo (el patrón
  es ambiguo por naturaleza).

## Seguridad (revisión con herramientas, 22-10)
Semgrep (253 reglas Kotlin/Java/secretos/CI): 1 aviso, la actividad de
arranque exportada, que es obligatoria. TruffleHog sobre todo el historial y
los ficheros: 0 secretos. Arreglado, guiándose por OWASP MASVS:
- El APK de entrega es release, no depurable (el CI lo comprueba con aapt). La
  firma de tienda sale de los secretos `ARGOS_KEYSTORE_B64`,
  `ARGOS_KEYSTORE_PASS`, `ARGOS_KEY_ALIAS` y `ARGOS_KEY_PASS`; sin ellos se usa
  la clave de depuración del ejecutor, que CAMBIA en cada compilación: hay que
  desinstalar antes de instalar un APK nuevo hasta que exista la clave propia.
- `dataExtractionRules`: nada sale por copia en la nube ni por traspaso entre
  móviles (en Android 12+ `allowBackup=false` no frenaba el traspaso).
- Sin captura en la lista de recientes (API 33+). FLAG_SECURE NO se pone,
  porque bloquearía las capturas que hace el usuario.
- CI con permisos mínimos por trabajo.
Pendiente: R8/ofuscación (necesita reglas medidas para ONNX Runtime) y fijar
las acciones del CI por SHA.

# FASE 5 — lo que se vio en el teléfono, y la radio pasiva

## Punto de partida (captura del teléfono de Eddie, 22-10)
- Voz: «El reconocimiento de voz falló (código 11)» dos veces de tres.
  11 = ERROR_SERVER_DISCONNECTED del reconocedor del aparato, que no estaba
  listo.
- «último: corriendo #30 · 41.5 m/s» con el móvil apuntando a los pies.
- «hola qué tal» y «cuáles son las preguntas que tienes para responder» → «No
  entiendo esa pregunta».
- El chat tapaba media pantalla y el panel era una lista de texto.

## Hecho y medido
- **Física plausible** (`:core:fisica`, Kotlin y brain.js iguales):
  - La escala solo vale si la altura de cámara que implica (estatura / a) está
    entre 2 y 150 m (invariante 7). Un móvil a 1,4 m mirando abajo ya no
    aprende una escala.
  - Un paso que ningún objeto de su clase puede dar (persona 10 m/s, bici 20,
    resto 70; ×1,5) es la caja saltando: se toma como nuevo punto de partida
    sin derivar velocidad. Un salto de 400 px da 1,2 m/s medidos, no 8,8.
  - «Corriendo» exige superar el umbral durante 1 s seguido. Un corredor real
    a 4 m/s salta a los 1,17 s.
- **Chat**: entiende saludos, «qué puedes hacer» y sus variantes, «quién
  eres», «qué guardas», «gracias», «qué hora es» y la presencia dicha de mil
  maneras («hay alguien», «ves a alguien»). Sin «(s)»: singulares y plurales
  bien dichos. La paridad con ask.js sigue en 23/23, porque la charla va antes
  de la gramática de consultas y no la toca.
- **Voz**: si el reconocedor del aparato da 5/11/12/13, se reintenta solo con
  el del sistema y la sesión se queda con ese. Respuestas habladas con la
  mejor voz española INSTALADA (sin red, la variante del usuario, la de más
  calidad).
- **Interfaz**: barra superior con estado de un vistazo (vigilando / cámara en
  mano / aviso de radio) y fichas (Zoom, Radio, Memoria); tarjeta inferior con
  cifras grandes (personas, vehículos, sesión), escena, último evento y radio;
  los datos técnicos, a un toque. Chat como hoja inferior con burbujas y el
  micro que se pone rojo mientras escucha.
- **Radio pasiva** (`:core:radio`, 29 comprobaciones, Fieldwatch como
  referencia con licencia MIT en `android-native/NOTICE`):
  - Firmas: Find My (separado de su dueño o no), Tile, SmartTag, red
    Encontrar de Google, estándar DULT. AirPods y Eddystone NO son
    rastreadores.
  - Remote ID: número de serie, tipo, posición, altura, velocidad y rumbo del
    dron. La posición y el identificador del PILOTO no se decodifican (caso
    reservado: paquete 0xF con la posición del piloto dentro).
  - Aviso de rastreador que se queda 10 min seguidos; uno intermitente no
    acumula.
  - GNSS: interferencia (−10 dB y la mitad de los satélites fuertes, 5 s) y
    suplantación (≥ 6 satélites casi iguales, 10 s). Avisa de cambios, no de
    lo que ya había al empezar; la lluvia (−3 dB) y un satélite tapado no
    avisan.
  - Privacidad: la dirección se resume con una sal aleatoria de la sesión y
    se descarta; nada se escribe en disco; al apagar se olvida todo, sal
    incluida; no se cruza con las pistas de la cámara.
  - En la app viene APAGADA. Se enciende desde la ficha «Radio», tras un
    diálogo que dice qué hace y qué pide (Bluetooth y ubicación, que Android
    exige para escanear y ver satélites; la posición se descarta).

## Pendiente
- ElevenLabs: pedido. Necesita internet y la clave del usuario, y el texto de
  las respuestas saldría del teléfono. Pendiente de decisión.
- Radio: sin medir en un aparato real (el emulador del CI no tiene BLE ni
  satélites). La distancia a un dron necesitaría la posición del teléfono.

## Voz propia (Piper): davefx, elegida de oído
- Cadena final, elegida entre 7 muestras (la 6):
  - ruido de síntesis 0,45;
  - reductor de ruido espectral (−15 dB de soplido);
  - de-esser (la «s» −6,5 dB, las vocales igual);
  - doma de aspereza en 2,2-4,5 kHz;
  - pico a −3 dBFS.
- Caminos cerrados, no reintentar:
  - efecto «radio» o «máquina»: era lo contrario de lo pedido;
  - EQ con realce de presencia: brillante y sibilante; gustó más la voz
    natural.
- La «saturación» que se oía de la mitad al final NO era recorte: está ya en
  la salida cruda del modelo, en sílabas que cargan 2-4 kHz.

### Historia de la voz
- `core/Voz.kt`: fonética del español en Kotlin (sin espeak-ng nativo).
  Medido contra espeak-ng 1.51: 98 % de palabras sueltas iguales, 90 % dentro
  de frase. La referencia está en `core/src/test/resources/voz-espeak.tsv`.
- Cifras, horas, unidades y siglas a palabras, con concordancia de género.
- `VozPiper`: ruido de duración bajado a 0,3. Con el de fábrica (0,8), la
  misma frase duraba de 2,2 a 10,9 s según la tirada; con 0,3, de 2,0 a 2,4.
- En la app, `VozApp` habla con el modelo empaquetado si el APK lo trae y, si
  no, con la voz del sistema.
- `voces.yml` deja muestras de varias voces con su ficha de licencia en la
  rama `voces-muestra`. Falta elegir una: la licencia importa para vender la
  app (las MLS son CC BY 4.0, que pide atribución).
- Desde este entorno huggingface.co está bloqueado; el CI sí llega.
