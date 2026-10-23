# ARGOS — contexto para Claude Code

Analítica de vídeo sobre cámara fija: detección, seguimiento, siluetas, física,
estatura, sinopsis, memoria e identidad. **Todo corre en el equipo del usuario.**

Este fichero es lo que hace falta saber antes de tocar nada. Lo demás está en
`docs/ESTUDIO.md` (diseño) y `docs/AUDITORIA.md` (catorce rondas de hallazgos).

---

## La regla que domina todo lo demás

**El presupuesto de píxeles por objeto manda sobre cualquier ajuste.**

| Persona ocupa | Resultado medido |
|---|---|
| 40+ px | 65 personas/frame (plaza real) |
| 25-40 px | funciona |
| 10-25 px | irregular |
| <10 px | **0 personas a cualquier zoom, incluido ×6** |

Antes de optimizar cualquier cosa, comprueba cuántos píxeles tiene el objeto.
Este error se ha cometido —y medido— varias veces en este proyecto: reducir una
secuencia a la mitad hizo caer la detección de 65 personas a 8, y la primera
lectura fue culpar al código.

---

## Arquitectura: dos relojes

```
RÁPIDA   fondo + asociación + física + dibujo       11 ms    cada frame
LENTA    detector neuronal por teselas          15-130 ms    en hilo propio
```

**El bucle rápido corre en el hilo principal, y ahí está el techo multicámara.**
Medido (1280×720, Engine real, 150 frames tras calentamiento):

| cámaras | ronda mediana | p95 | ¿30 fps? |
|---:|---:|---:|---|
| 1 | 11,0 ms | 17,3 | sí |
| 2 | 21,4 ms | 23,1 | sí |
| 3 | 33,0 ms | 43,9 | justo al borde |
| 4 | 43,4 ms | 49,5 | no |
| 8 | 88,5 ms | 108,9 | no |

**CUIDADO: estas cifras están medidas con node-canvas, no en navegador, y
`getImageData` lo distorsiona todo.** El desglose real es:

```
capture()   7,62 ms   drawImage + getImageData   ← artefacto de node-canvas
analyze()   2,58 ms   fondo+morfología+tracking  ← JS puro, representativo
```

En un navegador `getImageData` sobre 320×180 cuesta décimas, no 7,6 ms, así que
**el coste real por cámara se parece a 2,6 ms y el techo de cámaras es bastante
mayor que estas tablas.** Medir en navegador antes de citar un número.

Tres cosas medidas que contradicen lo que parece obvio:
- **La resolución de la fuente da igual.** De 1920×1080 a 352×288 el coste va de
  11,1 a 10,7 ms. Bajar la cámara no compra cámaras; el coste está en el búfer
  de análisis, no en leer el frame.
- **El número de objetos casi da igual.** 8 objetos 10,8 ms, 25 objetos 11,1 ms.
- **El escalado es lineal.** 8 cámaras son 8×11, sin penalización por caché.

`Engine` está partido en `capture()` (necesita el vídeo, hilo principal) y
`analyze()` (JS puro, apto para Worker). Los dos caminos dan resultados
**idénticos**: 200 frames comparados, 0 divergencias. El Worker por cámara se
puede montar sobre ese corte cuando una medida en navegador diga que hace falta.

Los dos RELOJES, en cambio, no se pueden fusionar. Un sistema que espera al detector en cada frame va a
33 fps con tirones de 120 ms; con el detector en un Worker, **62 fps y 0 ms de
peor frame** haciendo el mismo trabajo.

El detector dice **QUÉ** hay. El seguimiento dice **DÓNDE** está en cada frame.
Pedirle al detector que haga de tracker produce residuos: cajas congeladas
mientras la persona sigue andando.

---

## Mapa del código

### Cliente (`argos/api/static/`) — es la aplicación real

| Módulo | Qué hace |
|---|---|
| `engine.js` | modelo de fondo, componentes, tracker, siluetas, recortes |
| `detector.js` | YOLOX vía ONNX Runtime Web: teselado, zoom, pirámide, foveal, bandas |
| `detector-worker.js` | el detector en hilo propio — **la pieza que da los 60 fps** |
| `brain.js` | coordina los dos relojes, escala métrica, física, eventos |
| `behaviour.js` | aprende el comportamiento normal de la escena y puntúa anomalías |
| `archive.js` | memoria persistente en IndexedDB, retención, búsqueda |
| `identity.js` | huella de apariencia y enlace entre cámaras |
| `photogrammetry.js` | autocalibración y estatura |
| `solver.js` | optimizador de sinopsis |
| `groundplane.js` | rectificación métrica a vista cenital |
| `cameras.js` | registro de 1.552 cámaras públicas |
| `ops.js` | cuadro de mando multicámara: cola de atención y decaimiento |
| `ask.js` | consulta en lenguaje natural sobre el archivo |
| `index.html` | interfaz + el pegamento entre todo |

`scripts/bundle.py` los inlina en **`argos.html`**, un fichero de 409 KB que es
lo que se entrega. El APK empaqueta ese mismo fichero.

### Nativo (`android-native/`) — la app sin WebView

| Pieza | Qué hace |
|---|---|
| `core/Core.kt` | `engine.js` en Kotlin puro: fondo, morfología, componentes, seguimiento |
| `core/Detector.kt` | `detector.js` en cuadro entero + la memoria de `Brain.mergeDetections` |
| `app/Pipeline.kt` | los dos relojes: cámara y seguimiento en un hilo, detector en otro |
| `app/MainActivity.kt` | CameraX RGBA girado, carga, autoprueba, panel |
| `app/Overlay.kt` | cajas: clase solo si la dio el detector, «movimiento» si no |

`core` no depende de Android y se verifica en la JVM contra el JS: **paridad
exacta** del núcleo (260/260 frames, con y sin detector) y del detector sobre
bus, zidane y dog (cajas ±1e-4 px). El CI exige la del núcleo antes de
compilar el APK (`./gradlew :core:paridad`, tras `node scripts/dump_js.js`).
El estado y las cifras están en `ESTADO.md`, que se lee ANTES de tocar esto.

Lo nativo no gana por ese 1,4×: gana porque `capture()` era el 75 % del coste y
CameraX entrega los píxeles sin lienzo ni lectura de GPU. Y el plano Y de
`YUV_420_888` ya es luma, que es lo que consume el modelo de fondo.

### Servidor (`argos/`) — opcional

Python/FastAPI. **No hace falta para el análisis**: existe para fuentes RTSP y
el índice persistente en Postgres. Todo lo demás corre en el navegador.

---

## Invariantes — romper esto rompe el producto

**1. Una caja truncada miente.** Si toca el borde de una tesela interior, se
descarta: la vecina tiene el objeto entero. Sube el recall del 81 % al 87 %.
Aparece en tres sitios: descarte de bordes, voto de clase, estimación de
estatura.

**2. No hay un zoom universal.** Depende del tamaño del objeto:
- 40+ px → ×1 (en una plaza, ×1 dio 65 personas y ×2 solo 44)
- 10-30 px → ×2 (en vista aérea, ×2 triplicó a ×1)
- mezcla → pirámide

Por encima de ×3 el recuento **baja**: ampliar no añade detalle y la tesela
pierde el contexto que el modelo necesita.

**3. Falta la escala del cuadro entero, y es la que más veces acierta.**
Todos los modos partían de una tesela de lado fijo; ninguno miraba el cuadro
completo. Con el objeto MAYOR que la tesela, la invariante 1 lo descarta en
todas y los trozos que sobreviven salen como objetos falsos —a veces con más
puntuación que la caja entera, así que fundir escalas tampoco lo arregla
(probado por IoU y por contención). Medido con yolox_nano:

| | teselas 416 | cuadro entero |
|---|---|---|
| bus.jpg 810×1080 | 2 obj, 12 inferencias, 2.571 ms | **5 obj, 1 inferencia, 251 ms** |
| zidane.jpg 1280×720 | 3 «personas» (trozos de 2) | **2 personas, cajas correctas** |

Cuadro entero por defecto; teselas cuando el objeto es pequeño en una escena
amplia. Es la invariante 2 un peldaño más abajo: tampoco hay tesela universal.

**4. Contar detecciones no es medir calidad.** Hay que estratificar por
puntuación. Un corte de soft-NMS demasiado bajo produjo «+75 % de personas» que
eran cajas sobre conos y señales. Lo cazó la comprobación visual, no el número.

**5. Las clases finas de vehículo no son fiables.** `truck`→`car` se confundió
40 veces en un frame. Se agrupan en `person` / `vehicle` / `bike` y el NMS opera
**dentro del grupo** — si no, la misma furgoneta sale como dos objetos.

**6. Una celda sin muestras no opina.** Sin esa reserva, el primer día todo es
anómalo, el operador apaga las alertas y el sistema deja de existir.

**7. La altura de cámara deducida es un control de calidad.** Sale de
`a = estatura/altura_cámara`. Si da <2 m o >150 m, el modelo de perspectiva no
es fiable y las velocidades y estaturas de esa sesión tampoco.

**8. El bundle necesita ámbitos separados.** Los módulos declaran utilidades con
los mismos nombres (`median`, `mad`, `rng`). Inlinados sin envoltorio, colisionan
en el ámbito léxico global y **la página no carga**. `bundle.py` los envuelve y
comprueba colisiones antes de escribir. Esto llegó a una entrega.

---

## Privacidad — no es opcional

- **No se guarda vídeo ni recortes.** El archivo son trayectorias, siluetas y
  eventos. Una silueta lleva postura, marcha, clase y posición; no cara ni ropa.
- **No se identifica a personas.** `identity.js` asocia apariencias y propone
  hipótesis con confianza calibrada; la decisión es humana y queda registrada.
- **La estatura no es probatoria** y sale marcada como tal: la escala se ancla
  en una mediana poblacional asumida y ese error es sistemático e inobservable.
- **Retención de 30 días**, aplicada al abrir, con borrado por objeto.

Cualquier cambio que cruce estas líneas no pertenece a este proyecto.

---

## Trampas conocidas

**YOLOX ONNX no decodifica la rejilla.** Hay que reconstruir las anclas de los
strides 8/16/32. Sin eso: cero detecciones, no un error.

**La entrada es BGR crudo 0-255**, sin normalizar, relleno 114 abajo-derecha.
Normalizar deja el modelo mudo.

**La morfología es binaria**: erosión y dilatación se reducen a contar, con
suma corrida en O(1) por píxel. Era el 39 % del tiempo de frame.

**El fondo se congela donde hay OBJETO, no donde hay movimiento.** Un coche
parado deja de generar movimiento, la placa se actualiza sobre él y se imprime
en el fondo. Con detecciones protegiendo la placa: 33 % → 68 % de siluetas.

**La sustracción de fondo no sirve con multitudes.** En una plaza llena, la
multitud *es* el fondo: 0 cajas por frame frente a 65 del detector.

**El modelo dentro y el runtime fuera.** ONNX Runtime Web se traía de un CDN en
tiempo de ejecución mientras `yolox_nano.onnx` viajaba dentro del APK. Sin red
no hay detector, y la aplicación **no falla**: sigue analizando por sustracción
de fondo. Desde fuera parece que funciona y se inventa las clases. El runtime va
empaquetado y `ort.env.wasm.wasmPaths` apunta a la copia local.

**`classify()` no clasifica.** Es la proporción de la caja: más alta que ancha
→ `person`, más ancha que alta → `car`. Sin detector eso es lo único que hay, y
etiquetó de `coche` un andamio, una barandilla y una cara. Lo que salga de ahí
va marcado con `klassSrc = "forma"` y la interfaz dice «movimiento», no una
clase. Contadores, estatura y eventos de persona solo miran `klassSrc = "det"`.

**En Android WebView no hay aislamiento entre orígenes, se manden o no las
cabeceras.** `crossOriginIsolated` tiene que ser cierto o
`ort.env.wasm.numThreads` no sirve de nada. El APK emite COOP y COEP desde el
`WebViewAssetLoader` —con `credentialless` y no `require-corp`, que tiraría las
cámaras públicas de terceros— y **llegan**: medido en el emulador, la propia
página las lee con `fetch(location.href)`. Aun así `crossOriginIsolated` es
falso y `SharedArrayBuffer` no existe, así que la inferencia va en un hilo.
No es un fallo de la aplicación y no se arregla desde el contenedor. En un
navegador de escritorio la misma página sí usa varios hilos.

**Los dos caminos del detector no suprimían igual.** `detect` usaba soft-NMS
con `softCut`, que es además el suelo de puntuación final; `detectIncremental`
—el que corre el bucle principal— usaba `nmsClassAware` a secas y dejaba pasar
todo lo que superara el umbral por clase, 0,08 para persona. Dos personas
reales salían como seis cajas. El corte que fijó la invariante 4 no se estaba
aplicando donde más importa.

**El proveedor que se pide no es el que se usa.** `navigator.gpu` existe en
WebViews sin GPU utilizable: ORT acepta `webgpu`, falla al iniciarlo, lo
descarta él solo y corre en WASM. El panel decía «webgpu» porque el nombre
salía de lo pedido. Ahora se pide el adaptador de verdad antes de ofrecer el
proveedor, así que el nombre es el que se usa —y de paso carga el artefacto de
11 MB en vez del de WebGPU de 21. Un diagnóstico que miente cuesta más que no
tenerlo: es literalmente lo que dejó una sesión de campo analizando sin
detector.

**La primera inferencia no vale lo que las demás.** ONNX Runtime reserva la
arena y compila los kernels en la primera ejecución. Si cae sobre el primer
frame de cámara se ve como un tirón y, con la cola que descarta mientras hay
trabajo en curso, se traga los primeros barridos. Se calienta al cargar, que
además da el único número que convierte «va lenta» en un dato: lo que cuesta
una inferencia EN ESE aparato. Medido en el emulador —dos núcleos, sin GPU, un
hilo—: 3.305 ms pidiendo `webgpu` a ciegas, **687 ms** desde que se comprueba
el adaptador antes de pedirlo. El proveedor mal declarado no solo mentía en el
panel: costaba casi cinco veces la primera inferencia, porque ORT cargaba el
artefacto de WebGPU de 21 MB e intentaba arrancarlo antes de caerse a WASM.

**Un «ms de frame» no señala a nada.** Bajar el frame de la GPU
(`drawImage`+`getImageData`) depende del tamaño de la FUENTE y del aparato, no
del algoritmo: la cámara entrega 1920×1080 aunque el análisis trabaje a 320 px
de ancho. Sumado al análisis, a la física y al dibujo en un solo número, es
indistinguible de un modelo de fondo caro. Van repartidos en cuatro.

**Partir una función deja nombres colgando, y en el navegador no fallan.**
Al partir `process()` en `capture()` + `analyze()`, `analyze()` siguió usando
`roi`, que era un parámetro de `process()`. En node es un ReferenceError; en el
navegador NO: hay un `<div id="roi">` y el acceso por nombre de `window` lo
resuelve a ese elemento. `roi.x` vale `undefined`, cada caja del detector sale
NaN y el seguimiento devuelve **cero pistas con el detector activo**, sin una
queja. Ninguna paridad lo pisó porque todas corrían sin detecciones. Toda
paridad tiene que cubrir el camino con detector: es el que corre en uso real.

**`localStorage` no funciona en artefactos de Claude.** Aquí se usa IndexedDB.

---

## Comandos

```bash
python3 -m pytest tests/ -q          # 30 tests
python3 scripts/bundle.py --out argos.html
python3 scripts/run_video.py VIDEO --out /tmp/x --stride 4
python3 scripts/demo_viewer.py VIDEO --out v.mp4 --clean
```

Dependencias en `pyproject.toml`, con techo por arriba a propósito: sin él, una
versión mayor de numpy entra sola y rompe el pipeline sin que el repo cambie.

---

## Estado

**Funciona:** detección, seguimiento, siluetas, escala métrica aprendida,
física en m/s, sinopsis, memoria persistente, comportamiento aprendido, enlace
entre cámaras, 62 fps con el detector en hilo propio. App nativa
(`android-native/`): compila, pasa la paridad con el JS y analiza la cámara en
el emulador del CI.

**El detector dice QUÉ, el fondo dice DÓNDE, también en el código.** Las
cajas del detector entran en el seguimiento una vez por pasada, no en cada
frame; entre pasadas la pista vive de la resta de fondo y conserva la clase
del detector, y lo que se mueve sin ser detectado sigue como «movimiento».

**Cámara en mano (nativa).** El fondo se compensa cuando la cámara se
traslada y se congela cuando gira o tiembla: con la cámara INESTABLE no sale
«movimiento», solo lo que ve el detector. La velocidad va en coordenadas de
escena y la escala en coordenadas de imagen. Los ataques que lo prueban
(giro, deriva, objeto grande con la cámara fija, pared lisa, rayas) están en
`:core:casosCamara`. Una trampa que costó una tarde: medir la textura
comparando vecinos con paso 3 hace invisible una textura de periodo 6.

**Radio pasiva (nativa, opcional y apagada por defecto).** `core/Radio.kt`:
rastreadores BLE, Remote ID de drones y vigilancia GNSS. La dirección de un
aparato se resume con una sal de sesión y no se guarda ni se cruza con la
cámara; del dron no se lee la posición ni el identificador del piloto.

**Estabilizado (nativa).** Óptico si la lente lo tiene (mueve el sensor: vista
y análisis siguen alineados; el electrónico recorta y los desalinearía). En
pantalla, `core/Estabiliza.kt` desplaza la escena (vista previa y cajas a la
vez) con el movimiento de cámara que ya se estima. El análisis llega un frame
tarde: sin adelantar la corrección, el balanceo bajaba un 12 %; con el
adelanto, un 60 %. El giro de muñeca, que la imagen no ve, lo corrige el
giroscopio (sin retraso): un temblor de 3 Hz baja de 0,85° a 0,15°; la
inclinación a propósito no se toca y la corrección no pasa de ±2°.

**Fuentes por red (nativa).** Además de la cámara del móvil: las cámaras
públicas de tráfico con VÍDEO y sin clave, que la app baja y lista ella sola
(`core/Catalogo.kt`): Caltrans, Delaware, Misuri, Iowa, Minnesota, Indiana,
Kansas (CARS), Nevada, Luisiana y Wisconsin (plataforma 511) en directo por
HLS, y TfL en clips; y cámaras IP propias por RTSP. Qué organismos valen se
decidió sondeándolos desde el CI (`.github/scripts/sondas-camaras.py`):
Georgia, Florida y Connecticut piden credenciales para el vídeo, y casi todo
lo demás del mundo (DGT, Ontario, Alberta, Arizona…) son fotos fijas, que no
sirven para seguir trayectorias. `:core:catalogo -Pvivo=red` pide cada lista
real y la primera cámara de cada sitio. Media3 decodifica, un
TextureView pinta y se toma una imagen cada 80 ms para el mismo análisis. Al
cambiar de fuente se empieza una escena nueva (núcleo y cerebro), y cada
cámara aprende lo suyo y lo conserva entre sesiones (`memoria/escenas/`,
nombre por hash del identificador, sin dirección ni clave; solo agregados). La red solo RECIBE
vídeo: nada se envía. La búsqueda va por nombre, ciudad, condado y país (en
español o inglés): el nombre de Caltrans es solo la vía, así que buscar
«Los Angeles» no encontraba nada. Con pantalla completa el teclado no encoge
la ventana y tapaba la lista: al buscar, el panel sube arriba. Sin búsqueda,
primero las de menos retraso (`core/Retraso`): se mide desde el teléfono una
muestra de cada sitio y distrito (red + lo que el directo va por detrás, o la
edad del clip); lo buscado se mide antes que la muestra y las caídas no salen.

**Cámaras IP propias que se mueven (nativa).** Se dan IP, usuario y clave;
por ONVIF (`core/Onvif.kt`, `app/CamaraPtz.kt`) la app pide la dirección del
vídeo (cada marca tiene su ruta RTSP) y, si la cámara tiene PTZ, salen unos
mandos: mientras se pulsa, gira, inclina o hace zoom; al soltar, para y el
análisis vuelve a aprender el fondo (`Pipeline.camaraMovida`). La clave no
viaja: va su resumen WS-Security firmado con la hora de la cámara. Puertos
probados: 2020 (Tapo), 80, 8000 (Reolink), 8080, 8899. Sin ONVIF, la ruta
habitual `:554/stream1` o la que se escriba a mano. Sin probar con una
cámara real.

**Detector de tráfico entrenado (nativa).** `android-native/entrena` +
`.github/workflows/entrena.yml`: YOLOX-L etiqueta fotogramas de 1.200
cámaras públicas y el nano de la app aprende de él. Ronda 1, con 240
cámaras que no vio (etiquetas del maestro, corte 0,3): cuadro entero 37,8 %
→ 59,0 % de recall y precisión 68 % → 80 %; teselas ×2 53,7 % → 76,6 %,
falsos 164 → 84. Ve peor lo de cerca (el bus sale «truck»), así que solo se
usa en las cámaras FIJAS por red; el móvil sigue con el de serie. Se mete
en el APK solo el modelo cuyo sha256 está en `entrena/modelo-trafico.sha256`.
Dos trampas: con BatchNorm entrenando, la red se descoloca con lotes de
tráfico; y sin recortar el gradiente la pérdida de objeto se dispara en 9
pasos (pocos objetos por lote: se divide por casi nada).

**Lo detectado no se pierde (nativa).** Entre pasadas del detector la
pista vivía de la resta de fondo, que en tráfico suelta los coches lejanos
(mancha bajo el mínimo) y los rápidos: se veían «a ratos» y con otro número.
`core/Continuidad.kt` sigue en CADA imagen lo que dio el detector aunque el
núcleo lo pierda (predicción por velocidad + plantilla en grises buscada
alrededor), conserva su número cuando el núcleo lo renumera y lo suelta a
los 3 s sin confirmar o si deja de parecerse. `:core:continuidad`: lento,
normal y rápido, en 30/30 frames con su número; uno que desaparece se suelta.

**El asistente maneja la app (nativa).** `core/Ordenes.kt`: lo que se PIDE
(«pon una cámara de Los Ángeles», «otra cámara», «pon mi cámara IP», «gira
a la izquierda un poco», «acerca», «activa el estabilizado», «dibuja una
zona», «abre la memoria», «calla») lo hace `MainActivity.manda` y contesta
qué ha hecho; lo que se PREGUNTA sigue a `Ask`. Gramática local, sin red;
`:core:ordenes` comprueba también que las preguntas no se toman por órdenes.

**Zonas, avisos y búsqueda de cámaras (nativa).** Ideas de iSpy y de
Surveillance Center, no su código (LGPL y AGPL no caben en una app de pago).
En Ajustes → Zonas se tocan las esquinas sobre la imagen: una zona VIGILADA
avisa (notificación) cuando ENTRA una persona, un vehículo o una bici del
detector, una vez por objeto y como mucho cada 30 s por zona; una IGNORADA
deja de enseñar lo que pisa dentro. Cuenta el pie del objeto, no su caja.
Se guardan por cámara (`<archivo>.zonas`, puntos 0-1). En Cámara IP, «Buscar
cámaras en esta wifi» pregunta por WS-Discovery (ONVIF, 239.255.255.250:3702)
y rellena la IP. `core/Zonas.kt`, `core/Descubre.kt`, `:core:zonas`. Sin
probar con una cámara real.

**Lo que enseñaron 110 cámaras públicas (nativa).** Fotogramas reales de once
sitios (`.github/workflows/muestras.yml` → rama `muestras-camaras`), pasados
por el detector en cuadro entero, teselas ×1, ×2 y ×3:
- Teselas ×2 encuentran un 32 % más que el cuadro entero (82 % frente a 62 %
  de lo seguro); ×3 hunde el recuento (27 %), como dice la invariante 2. Pero
  el zoom automático pedía «2 más y un 50 % más» y con 1-3 coches no saltaba
  nunca: ahora también salta con una ventaja pequeña y constante (media de
  varios sondeos), y cada cámara recuerda su modo. 15 → 39 de 110 cámaras.
- Una de cada cinco cajas que solo salían con ×2 eran LETRAS del rótulo
  sobreimpreso (nombre de la cámara, hora, logotipo), hasta 0,68. `Fijos`:
  en las franjas de arriba y abajo, lo que el detector repite en el mismo
  sitio 2 minutos se ignora y se recuerda por cámara. En el centro no: un
  coche parado es un coche.
- Luces y reflejos. El detector puso «vehículo» en destellos de faros y
  farolas y «persona» en semáforos; la resta de fondo ve «movimiento» donde
  un faro barre el asfalto. `Fijos` también en el centro, a los 3 minutos,
  pero solo para una persona o bici que no se balancea nada y un vehículo
  que es casi todo luz (≥ 45 % de píxeles casi blancos): un coche aparcado
  oscuro sigue siendo coche. `Luz.esLuz`: un «movimiento» donde lo cambiado
  solo se ha ACLARADO es luz, no objeto (un objeto deja partes más oscuras
  y más claras a la vez). Probado con casos sintéticos, no sobre vídeo real
  de noche: las ráfagas de 4 s no bastan para que el fondo aprenda.
- 4 de 33 ráfagas eran la misma imagen repetida: cámara colgada con el
  servidor vivo. `Congelada` lo dice en vez de parecer que el análisis falla.
- La muestra era de noche en EE. UU.: de noche el detector apenas ve coches
  (solo faros). Falta repetir la medida de día.

**Física plausible.** Una escala con altura de cámara fuera de 2-150 m no vale;
un paso imposible para la clase no da velocidad; «corriendo» pide 1 s seguido.

**Abierto:**
- Radio sin medir en un aparato real (el emulador no tiene BLE ni satélites)
- Deriva subpíxel lenta (0,1 px/f) — pasos enteros; 30/150 frames con falsos
- Aceleración por hardware — WebGPU está en el código, sin medir por falta de GPU
- Búsqueda semántica (CLIP) — pesos no accesibles para validar
- Detector de cabezas para multitudes — necesita otro modelo
- `main.py` es un módulo de 24 endpoints con estado global
- Sin telemetría estructurada

**Resultados negativos documentados** — no reintentar sin leer por qué falló:
zoom por bandas de profundidad (27 vs 42 objetos), atención foveal como
sustituto del barrido (40-50 % del recall), persistencia de cajas del detector
entre barridos (residuos), fundir la escala del cuadro entero con la de teselas
(el trozo puntúa más que el objeto y sobrevive a la supresión), reconstruir la
respuesta del `WebViewAssetLoader` con línea de estado para conseguir el
aislamiento (idéntico: las cabeceras ya llegaban), «realzar» la imagen antes
del detector (110 cámaras, etiquetas de YOLOX-L: superresolución FSRCNN ×2
51,8 % y ESPCN ×2 55,4 % frente a 56,2 % del bilineal, a 2-3 veces el coste;
CLAHE no suma en teselas y añade falsos. La superresolución inventa bordes,
no detalle: es la invariante 2 otra vez). Lo que sí mueve la aguja es
ENTRENAR el nano con lo que ven estas cámaras (`android-native/entrena`).
