# Cómo probar ARGOS

## Sin instalar nada: `argos.html`

Un solo fichero. Lo abres en el navegador y funciona. **No hay servidor.**

```bash
python3 scripts/bundle.py     # genera argos.html
```

Dentro va todo lo que corre en tiempo real —modelo de fondo, tracking, siluetas
vectoriales, overlay— y también el **optimizador de sinopsis**, que era la última
pieza que obligaba a levantar Python y ahora está portada a JavaScript.

Los scripts se inlinan en lugar de quedar como ficheros sueltos por un motivo
concreto: un `<script src="engine.js">` desde `file://` se bloquea por CORS en
varios navegadores, mientras que un script embebido no.

### Qué funciona sin servidor

| | |
|---|:---:|
| Cámara del dispositivo / región de pantalla | ✅ |
| Detección, tracking, corchetes en vivo | ✅ |
| Siluetas vectoriales | ✅ |
| Lámina de actividad y descarga PNG | ✅ |
| **Sinopsis completo** (plan + composición + WebM) | ✅ |
| **Estatura por fotogrametría** | ✅ |

**Nada del análisis necesita Python.** El servidor solo aporta fuentes RTSP y el
índice de búsqueda persistente en Postgres.

> Aclaración que conviene tener clara: `uvicorn` **siempre corrió en local**.
> `127.0.0.1` es tu propia máquina; "servidor" ahí solo significa "proceso que
> escucha en un puerto", no alojamiento remoto. Lo que se ha eliminado es la
> dependencia, no un servicio externo que nunca existió.

### El detector neuronal (opcional)

Por defecto, `argos.html` detecta por **sustracción de fondo**. Encuentra lo que se
mueve, y para tráfico basta. Pero falla de raíz con multitudes: probado en vivo sobre
una cámara del Panteón de Roma, encontró **6 objetos donde había cientos de personas**.
No es un fallo de ajuste — para un modelo de fondo, una multitud quieta *es* el fondo.

Un detector no depende del movimiento. En el panel, sección **Detector**:

1. Descarga un modelo YOLOX (Apache-2.0), por ejemplo
   `yolox_tiny.onnx` de las releases de Megvii-BaseDetection/YOLOX.
2. Elige el fichero o pega su URL.
3. **Cargar detector neuronal.**

ONNX Runtime Web se trae de CDN la primera vez y queda en caché. Es la única
dependencia externa de toda la herramienta, y solo la usa quien active esto. **El
modelo se ejecuta en tu equipo**: ni el vídeo ni los recortes salen de ahí.

Con el detector activo, el fondo sigue usándose — pero para las **siluetas**, recortando
dentro de cada caja. El detector aporta el *qué y dónde*; el fondo aporta el *qué
forma*. Ninguno de los dos lo da todo: el detector no segmenta y el fondo no ve lo
parado.

#### Velocidad: qué cuesta cada cosa

Medido en **un solo núcleo** (el peor caso imaginable):

| Modelo | Entrada | Por tesela |
|---|---:|---:|
| yolox_nano | 416 px | **124 ms** |
| yolox_tiny | 416 px | 357 ms |
| yolox_s | 640 px | 1.395 ms |

Nano es 2,9× más rápido que tiny. Pero el modelo no era el problema: el problema eran
**24 inferencias por frame**.

**Barrido incremental.** Entre dos frames un objeto se mueve unos píxeles y el tracker
lo sigue solo. Lo que el detector aporta es que algo *entre o salga* de escena, y eso
ocurre en escalas de segundos. Así que se analiza **una tesela por pasada**, en rotación
con prioridad por actividad: las teselas que producen objetos vuelven antes, una de
cielo se visita tarde.

| Presupuesto | Por pasada | Pasadas para el cuadro entero | Objetos acumulados |
|---:|---:|---:|---:|
| **1 tesela** | **127 ms** | 24 | **114** |
| 2 teselas | 332 ms | 12 | 102 |
| 4 teselas | 829 ms | 6 | 97 |

Con 1 tesela acumula **más** objetos que el barrido completo (114 frente a 93), porque
va sumando a lo largo del recorrido mientras las cosas se mueven.

**El detector ya no bloquea el render.** Corre en su propio ciclo y deja las cajas donde
el bucle de dibujo las lee. Cede al menos tanto tiempo como ha tardado la pasada, así
que nunca ocupa más de la mitad del hilo. La pantalla va a su tasa aunque una inferencia
tarde 20 ms.

Las cajas se **funden entre pasadas**: cada pasada solo refresca unas teselas, así que
las de las zonas no visitadas se conservan y caducan a los 4 s sin reconfirmarse. Sin
eso los objetos parpadearían al ritmo de la rotación.

En un equipo normal —varios núcleos, SIMD, y WebGPU si está— esos 127 ms bajan a la
franja de 15-25 ms, y con WebGPU a menos.

#### Lo que no se puede prometer

Analizar 1920×864 con un detector real en **milisegundos por cuadro completo** no es
cuestión de optimizar: son 24 ventanas de 416 px y ningún truco las hace gratis. Lo que
sí es real es que **la herramienta se sienta instantánea**, y eso se consigue separando
las dos escalas de tiempo: el render y el tracking a la tasa de pantalla, el detector a
la suya. Es lo que hace todo sistema de este tipo que va fluido.

#### Verificación

Mismo frame de Alcalá, mismo modelo, mismo umbral (0,15):

| | Detecciones | Lado mediano |
|---|---:|---:|
| Python + onnxruntime | 94 | 24 px |
| **JavaScript + ONNX Runtime Web** | **93** | **23 px** |

El puerto incluye lo que costó encontrar en la versión de Python: decodificación de la
rejilla (los exports de YOLOX **no** la decodifican), entrada BGR cruda 0-255 sin
normalizar, teselado con solape 0,45 y descarte de cajas truncadas en bordes de tesela.

> Coste: 24 teselas a 416 px son ~9 s por frame en WASM de un solo hilo. En un equipo
> normal con varios núcleos, y más aún con WebGPU, baja a una fracción de eso. Aun así
> el detector no es para tiempo real a 30 fps: es para analizar con criterio.

### Verificación de la fotogrametría portada

Contra la misma cámara sintética que la versión de Python (1280×720, 6 m de altura,
28° de inclinación, 180 peatones):

| | Python | JavaScript |
|---|---:|---:|
| MAE autocalibrado | 3,6 cm | 5,1 cm |
| MAE con referencias medidas | 3,6 cm | 5,0 cm |
| Sesgo con población real 1,66 m | +4,6 cm | **+4,8 cm** |
| Sesgo con población real 1,74 m | −3,3 cm | **−3,3 cm** |

Las dos filas de abajo son las importantes y se reproducen casi exactas: el error de
la mediana poblacional asumida entra íntegro en cada medida, es sistemático y **no es
detectable desde el vídeo**.

El MAE sale ~1,5 cm peor que en Python, y la causa es concreta: el espacio nulo se
resuelve por rotaciones de Jacobi sobre AᵀA en vez de por SVD sobre A. Formar AᵀA
**eleva al cuadrado el número de condición**, y esa pérdida de precisión se nota en el
punto de fuga (58 px de error frente a 24). Es el precio de no arrastrar una
biblioteca de álgebra al navegador, y a estos efectos —una cifra que ya sale marcada
como no probatoria— es asumible.

### Verificación del solver portado

Contra los mismos tubos que el de Python:

| | Compresión | Solape | Tiempo |
|---|---:|---:|---:|
| Python | 4,38× | 1,04 % | 2,2 s |
| **JavaScript** | **4,35×** | **1,09 %** | **0,33 s** |

Coinciden. Cero observaciones fuera de rango sobre 2.881.

### El aviso de servidor ya no aparece

Una versión anterior de `argos.html` mostraba «ARGOS necesita el servidor» al abrirlo
como fichero, y además deshabilitaba el botón Conectar. Era correcto cuando el
optimizador vivía en Python; dejó de serlo al portarlo, y el aviso sobrevivió al cambio.

Ahora el arranque solo comprueba lo que de verdad impide trabajar: que el navegador
exponga cámara o captura de pantalla. Si falta la captura de pantalla pero hay cámara
—lo normal en móvil— cambia la fuente por defecto en vez de quejarse.

El servidor Python es opcional y se pide a mano con el botón que hay bajo Conectar.

### Si el navegador no te da la cámara

`getUserMedia` y `getDisplayMedia` exigen contexto seguro. La mayoría de navegadores
de escritorio tratan `file://` como seguro y funciona directamente; si el tuyo no,
la app te lo dice y entonces sí hace falta servirlo:

```bash
python3 -m http.server 8000      # y abre http://localhost:8000/argos.html
```

Eso es un servidor de ficheros estáticos, no ARGOS: el análisis sigue siendo todo
local.

---

## Con el servidor Python (opcional)

Solo si quieres fotogrametría, fuentes RTSP o el índice de búsqueda.

### 1. Instalar

```bash
pip install numpy scipy opencv-python
tar xzf argos-src.tar.gz && cd argos
```

Eso es todo. **No hace falta descargar ningún modelo ni tener GPU.**

## 2. Grabar

Móvil **fijo**, 10–20 minutos, apuntando a algo con tráfico de gente o coches.
Ventana, trípode, o el móvil pegado con cinta a un marco.

**No lo sostengas con la mano.** Todo lo que viene después asume vista estática:
si la cámara se mueve, la sustracción de fondo detecta la escena entera como
objeto y no sale nada.

Otras cosas que rompen MOG2, para que reconozcas el fallo si lo ves:
- Cambios bruscos de luz (una nube, encender una farola) → un frame lleno de falsos objetos
- Objetos que se paran → se disuelven en el modelo de fondo y el tubo se corta
- Lluvia fuerte o ramas al viento → ruido constante

## 3. Ejecutar

```bash
python3 scripts/run_video.py mi_video.mp4 --out salida/
```

Con estimación de estatura (necesita ≥12 peatones):

```bash
python3 scripts/run_video.py mi_video.mp4 --out salida/ --stature
```

Si el vídeo es largo, procesa 1 de cada N frames:

```bash
python3 scripts/run_video.py mi_video.mp4 --out salida/ --stride 3
```

Sinopsis más legible (menos denso) o más comprimido:

```bash
--overlap 0.004    # más largo, más limpio
--overlap 0.030    # más corto, más apretado
```

## 4. Qué sale en `salida/`

| Archivo | Qué es |
|---|---|
| `sinopsis.mp4` | El vídeo condensado, cada objeto con su hora original |
| `activity_plate.png/.svg` | Todo lo que pasó, en siluetas vectoriales |
| `activity_outline.png` | Lo mismo, versión solo contorno sobre fondo oscuro |
| `trajectory_strip.png/.svg` | El paso completo del objeto más largo, en estroboscopia |
| `plate.png` | El fondo limpio estimado por mediana temporal |
| `stats.json` | Tubos, clases, compresión, calibración, estaturas |

Los `.svg` son vectoriales: ábrelos en Inkscape o Illustrator y escálalos a tamaño
póster sin perder nada.

## 5. Con detector real (mejores siluetas)

MOG2 da manchas; una red de segmentación da formas humanas de verdad.

```bash
pip install ultralytics
python3 scripts/run_video.py mi_video.mp4 --out salida/ --detector yolo
```

Descarga los pesos sola la primera vez. **Ojo con la licencia**: Ultralytics es
AGPL-3.0. Para probar en tu máquina no hay problema; si esto acaba siendo producto
que distribuyes o sirves por red, hay que pasar a RT-DETR o YOLOX (Apache-2.0),
que es para lo que está `argos/detect/onnx_backend.py`.

## 6. Prueba sin grabar nada

```bash
python3 tests/bench_synopsis.py       # optimizador vs alternativas
python3 tests/test_tracker.py         # fragmentación del tracker
python3 tests/test_anthropometry.py   # error de estatura vs ground truth
python3 tests/test_calibration.py     # autocalibración desde peatones
python3 scripts/demo_render.py        # sinopsis sobre escena sintética
```

## 7. Antes de apuntar a algo público

Si grabas espacio público o a terceros pasas a ser responsable del tratamiento:
cartel informativo, base jurídica, plazo de supresión (1 mes por defecto, art. 22
LOPDGDD) y, con analítica avanzada, evaluación de impacto. En Euskadi la autoridad
es la AVPD si el responsable es administración vasca, la AEPD si es privado.

Para probar el software, lo más limpio es grabar tu propio espacio o un sitio sin
gente identificable. Ver §6 del estudio.

---

# App móvil / cualquier cámara

## Arquitectura (léela antes de nada)

```
  móvil / cámara IP / fichero  →  servidor ARGOS  →  navegador
       solo captura                 todo el trabajo      solo ver
```

**Esto no corre en el móvil, y es deliberado.** Un teléfono decodifica bien y hasta
puede correr un detector pequeño, pero el tracker necesita la sesión entera en
memoria y el solver de sinopsis es una optimización global sobre todos los tubos de
la ventana: decenas de miles de iteraciones de recocido. El móvil entra en
throttling térmico a mitad y tarda cuatro veces más con batería.

El móvil es una cámara y una pantalla. Es muy bueno en las dos cosas. Lo demás va en
una máquina enchufada.

**No hay app que instalar.** La interfaz se sirve desde el propio servidor: cualquier
móvil de la red abre una URL. Sin App Store, sin revisión, sin versión iOS y otra
Android, y actualizar el software actualiza todos los clientes a la vez.

## Arrancar

```bash
pip install fastapi uvicorn python-multipart
uvicorn argos.api.main:app --host 0.0.0.0 --port 8000
```

En el móvil, misma wifi: `http://<ip-del-ordenador>:8000`

> iOS y Android solo dan acceso a la cámara en contextos seguros. En `localhost`
> funciona; desde otro dispositivo por IP necesitas HTTPS. Lo más rápido es
> `cloudflared tunnel --url http://localhost:8000`, que te da una URL https.

## Fuentes que acepta

| Escribes | Qué hace |
|---|---|
| `push://telefono` | La cámara del móvil, enviando JPEGs al servidor |
| `rtsp://usuario:clave@192.168.1.50:554/stream1` | Cualquier cámara IP |
| `0` | Webcam USB del propio servidor |
| `/ruta/video.mp4` | Un fichero |
| `http://.../cameraImage/...` | Endpoint de snapshot (detecta el modo solo) |

Cada fuente se clasifica al registrarse: **completa / limitada / solo snapshot**, con
la lista de lo que **no** soporta. Una cámara que da un frame cada 15 s no puede medir
velocidad por muy bueno que sea el resto del sistema, y decirlo al registrar es mejor
que emitir números que nadie debería creerse.

## Medido

Prueba de extremo a extremo simulando un móvil enviando 700 frames a 640×360:

- **71 fps de procesado en servidor** (1 vCPU, sin GPU) — cabe de sobra un móvil a 2–4 fps
- 22 tubos completados, 25 con los activos
- Sinopsis, lámina de siluetas, tira de trayectoria y SVG generados desde el navegador

Un detalle del log que importa: `dropped: 593`. La cola de entrada es **acotada, con
descarte del más antiguo**. Si el pipeline se retrasa respecto al móvil, lo útil es la
vista *actual* de la escena, no una cola creciente de frames rancios que lleva la
latencia a minutos y acaba agotando la memoria. Descartar es la función correcta; el
contador se expone para poder alarmar sobre él.

## La interfaz

Una sola pantalla: la cámara a sangre, y **un ojo**.

- **Fondo**: el vídeo a pantalla completa, resolución nativa, a los fps del propio
  dispositivo. Nada tapa la escena salvo un canvas transparente con corchetes de
  esquina sobre cada objeto — no cajas completas, porque una caja cerrada tapa
  justo lo que quieres mirar.
- **El ojo**: semitransparente al 42 %. Un toque abre el menú y el párpado se cierra
  sobre el iris; otro toque y vuelve a abrirse. Tocando la escena también se cierra:
  un panel nunca debe ser algo de lo que haya que buscar la salida.
- Mientras analiza, la pupila late.

### El detalle que hace que vaya fluido

La primera versión traía de vuelta un JPEG renderizado por el servidor. Era el error:
resolución peor, un segundo de retraso, y consumiendo subida en competencia con los
frames que estás enviando.

Ahora el fondo es el **stream local del propio móvil** —ya está ahí, ya está a
resolución nativa, ya va a la tasa de refresco de la pantalla, y cuesta cero— y por la
red solo viaja lo que el cliente no puede saber: dónde cree el tracker que están los
objetos.

**Medido: 356 bytes de JSON frente a 10.237 bytes de JPEG. 29× más ligero.**

Para fuentes que no son el móvil (RTSP, fichero, webcam) no hay stream local, así que
ahí sí se sirve vídeo: **MJPEG por conexión larga**, no polling. Sondear cuesta un
round-trip por frame y los entrega a intervalos irregulares, que es lo que se percibe
como tirones por rápida que sea la red.

El overlay **no interpola**. A 2–4 fps de análisis real, fingir un movimiento más suave
del que tiene el tracker sería inventarse posiciones.

## Si sale «Failed to fetch»

Casi siempre significa que has abierto `index.html` como fichero desde el móvil.
El HTML es solo la cara: **todo el análisis vive en el servidor**, así que sin
uvicorn corriendo no hay nada al otro lado.

Lo correcto:

1. En el ordenador: `uvicorn argos.api.main:app --host 0.0.0.0 --port 8000`
2. Averigua su IP: `ip a` (Linux) · `ipconfig` (Windows) · `ipconfig getifaddr en0` (macOS)
3. En el móvil, **misma wifi**, abre `http://<esa-ip>:8000`

No abras el fichero descargado. Abre la URL.

Si aun así lo abres como fichero, la app lo detecta y te pide la dirección del
servidor, con un botón de «Probar conexión». Funcionará todo **menos la cámara del
móvil**: `getUserMedia` exige contexto seguro, así que sobre `file://` el navegador
no la entrega por mucho que el servidor responda. Para la cámara no hay atajo: o
`http://<ip>:8000`, o un túnel TLS.

> **Cortafuegos.** Si la IP es correcta y sigue sin responder, casi siempre es el
> firewall del ordenador bloqueando el 8000 desde la red local.

## Región de pantalla: analizar cualquier vídeo que se vea en el equipo

Fuente `pantalla://area`. Captura de pantalla más un recuadro ajustable: colocas la
ventana sobre el vídeo que sea y **solo se analiza lo de dentro**.

Resuelve de golpe el problema de las fuentes. Una cámara municipal en una pestaña, un
reproductor, el cliente web de un grabador, una videollamada: si se ve en la pantalla,
sirve de entrada. Sin RTSP, sin credenciales, sin formato que negociar y sin depender de
que el operador de la cámara te deje entrar.

```
Conectar → el navegador pregunta qué compartir → arrastra el recuadro sobre el vídeo
```

Ajusta con las esquinas y pulsa **Fijar** para que no se mueva por accidente.

### Detalles que importan

- **Elige compartir una ventana, no la pantalla entera.** Capturar todo y recortar
  desperdicia resolución justo en la región que interesa.
- **Al mover el recuadro se reinicia el modelo de fondo.** La escena de debajo es otra;
  conservar el modelo anterior marcaría el cuadro entero como movimiento durante varios
  segundos. Ajusta antes de empezar a grabar.
- **Sigue haciendo falta que la cámara de origen sea fija.** Recortar una región de una
  grabación en mano no arregla nada: el análisis asume punto de vista estático.
- Si detienes la compartición desde la barra del navegador, la app se entera y para. Sin
  eso seguiría analizando un frame congelado.

### Límites

`getDisplayMedia` exige contexto seguro, igual que la cámara, y **no existe en
navegadores móviles**. Esto es de escritorio.

## Modo local: análisis en el dispositivo

Fuente `local://camara`. El motor (`engine.js`, 29 KB, sin dependencias) corre en el
navegador junto a la cámara. **No toca la red.**

El reparto es por latencia, no por comodidad:

| Bucle | Qué hace | Dónde |
|---|---|---|
| Tiempo real (<40 ms) | fondo, blobs, tracking, siluetas, overlay | navegador |
| Por lotes (segundos) | optimizador de sinopsis | Python, bajo demanda |

Enviar cada frame al servidor y sondear las cajas de vuelta metía **300–600 ms** entre
lo que pasa y lo que se ve. Ninguna optimización del servidor arregla eso: el retraso
está en el viaje, no en el cálculo.

**Medido:** 3,97 ms por frame a 320×180 (techo de 252 fps en CPU de servidor; 25–50 fps
reales en móvil), 0,16 ms por silueta vectorizada.

### Qué funciona sin servidor

- Cámara en vivo, detección, tracking, corchetes en tiempo real
- **Capa de siluetas**: contornos vectoriales suavizados sobre la escena, con trazado
  de Moore, remuestreo a arco constante, Chaikin y Catmull-Rom
- Lámina de actividad descargable en PNG

### Qué necesita servidor

Solo el **plan de sinopsis**, que es un recocido global sobre todos los pares de tubos.
Y ni ahí viajan píxeles: el dispositivo sube **geometría** —182 bytes por observación
medidos— y recibe un desplazamiento por tubo. La composición se pinta en canvas con los
recortes que el motor ya tiene, y se puede volcar a WebM con `MediaRecorder`.

Una sesión de una hora manda cientos de kilobytes en lugar de decenas de megas.

### La capa de siluetas es un control de privacidad

Una silueta es **anónima por construcción**: lleva postura, marcha, clase, tamaño y
posición, y nada del detalle facial o de ropa que convierte una imagen en dato personal.
La sala de control puede operar sobre ella por defecto, y revelar los píxeles pasa a ser
una acción separada y registrable. Bajo minimización de datos (art. 5.1.c RGPD) es
exactamente el argumento que quieres poder sostener.

## Ejecutarlo en el propio móvil (Android)

Sí se puede, y en Android es **la mejor configuración disponible** — por un motivo que no
es de rendimiento:

> El navegador solo entrega la cámara en un «contexto seguro». `localhost` cuenta como
> tal. Sirviendo desde el propio dispositivo desaparece de golpe el problema del HTTPS:
> ni túnel, ni certificados, ni IP de la red. Y la subida de frames viaja por loopback,
> así que no toca la red ni gasta datos.

```bash
pkg install git
git clone <tu-repo> argos && cd argos
bash scripts/termux_setup.sh
./argos-local
```

Luego, en el navegador del propio móvil: `http://localhost:8000`

### Consumo medido

Con entrada a 1280×720, 900 frames:

| Perfil | RSS estable | Pico en `build` | Sinopsis |
|---|---:|---:|---:|
| `server` | 497 MB | 723 MB | 5,0 s |
| `device` | **372 MB** | **372 MB** | **3,3 s** |

El perfil `device` (`ARGOS_PROFILE=device`) reduce la placa de fondo a 640 px de lado
mayor, comprime los recortes a JPEG con techo de 64 MB y desalojo del más antiguo, y
baja las iteraciones del recocido. Sin ese techo el almacén de recortes crece sin
límite: una cámara en directo produce recortes para siempre, y a las pocas horas el
sistema mata el proceso.

### Lo que hay que hacer sí o sí

- **Quitar Termux de la optimización de batería.** Android lo mata a los minutos con la
  pantalla apagada.
- **Instalar Termux:API** (`pkg install termux-api` + la app) para que el wake-lock
  funcione de verdad.
- **Escuchar solo en `127.0.0.1`.** El lanzador lo hace. La API no tiene autenticación:
  abrirla a la wifi sería publicar tu cámara a quien esté en la misma red.

### Empaquetado como APK

`android/` contiene el proyecto Gradle completo y `.github/workflows/build-apk.yml`
lo compila en CI sin que instales nada. Ver `android/README.md`.

El punto crítico: **no se puede usar `file:///android_asset/`**. Ese origen no es
contexto seguro y `getUserMedia` no existe ahí, así que la app arranca negra y sin
errores. Se usa `WebViewAssetLoader`, que sirve los mismos assets bajo
`https://appassets.androidplatform.net/`.

### iPhone: no

iOS no permite ejecutar un servidor Python arbitrario. Haría falta una app nativa con
ONNX Runtime o Core ML y portar el solver de sinopsis a Swift o C++. Es un proyecto
aparte, no una configuración.

## Seguridad

`argos/api/main.py` **no lleva autenticación**. Tal como está es una herramienta de
red local. Ponerlo en internet sin un proxy inverso con auth y TLS delante expone un
feed de cámara y un índice de búsqueda a quien encuentre el puerto.

---

## El cerebro

`argos/api/static/brain.js`. Coordina las dos escalas de tiempo que tiene este problema
y que no se pueden mezclar sin romper una de las dos:

| | Qué hace | Coste | Cadencia |
|---|---|---:|---|
| **Rápida** | fondo, asociación, física, dibujo | **6,5 ms** | cada frame |
| **Lenta** | detector neuronal por teselas | 15-130 ms | por su cuenta |

Un sistema que espera al detector en cada frame va a 8 fps. Uno que solo usa el fondo no
ve a la gente parada. El cerebro deja que cada capa vaya a su ritmo y funde lo que
producen.

**Medido: 6,5 ms por frame en el bucle rápido → techo de 152 fps.** Con pantalla de
120 Hz, va a 120.

### Prioridad a las personas

La atención del detector **no es uniforme**. Una tesela donde hay personas se revisita
con factor 3 frente a una de tejado, porque las personas entran y salen de escena
constantemente y los tejados no. La antigüedad multiplica, así que ninguna tesela queda
sin visitar nunca.

### Escala métrica aprendida de la propia escena

Si sé que un peatón en la fila `y` mide `h(y)` píxeles y que un peatón mide ~1,70 m,
entonces ahí un píxel son `1,70/h(y)` metros. Eso da velocidad en m/s y km/h, separación
real y estatura, **sin calibrar nada**.

Verificado sobre una escena con perspectiva conocida (`a=0,300`, `b=−30`):

```
recuperado:  a = 0,299    b = −29,6    span = 506 px
```

### Un fallo que encontró la prueba

Aceptaba ajustes de perspectiva con muy pocas muestras, y con una escala inventada
llegó a **disparar eventos de «corriendo» sobre gente andando**.

La causa: **no se puede aprender perspectiva de objetos que están todos a la misma
distancia**. Un conjunto de puntos con poco recorrido vertical determina la pendiente
por puro ruido. Ahora se exigen tres condiciones —muestras suficientes, recorrido
vertical ≥120 px y dispersión razonable— y el caso degenerado se rechaza:

| Muestras | ¿Válido? |
|---|---|
| 24 repartidas en profundidad (span 506 px) | ✅ |
| 24 todas a la misma distancia (span 29 px) | ❌ *antes lo aceptaba* |
| 6 muestras | ❌ |

### Física por trayectoria

Velocidad (px/s, m/s, km/h), aceleración, rumbo, permanencia, rectitud y detenido.
Todo se suaviza antes de derivar, con constantes distintas para posición y aceleración:
derivar ruido dos veces da números sin ningún significado. "Detenido" se juzga contra
el **tamaño del propio objeto**, no contra un umbral en píxeles, así vale igual para
alguien al fondo que para alguien delante.

Eventos: detenido, corriendo, merodeo y **decorado** —inmóvil y fuera del plano de
suelo, que es una estatua o un cartel por muy bien que el detector lo haya clasificado.

### Estatura acumulada

Se acumula a lo largo de la trayectoria en vez de medirse al final, descartando cajas
que tocan el borde (truncadas por construcción). Agregación por percentil superior, no
mediana: la marcha, encorvarse y la oclusión de los pies acortan la figura y casi nada
la alarga, así que el sesgo es de un solo lado.

**No es probatoria** y sale marcada como tal: la escala se ancla en una mediana
poblacional asumida, y ese error es sistemático y no observable desde el vídeo.

---

## «Detecta furgonetas pero no personas»

Síntoma real y con causa concreta: **un detector no puede resolver lo que no tiene
píxeles suficientes**. YOLOX está entrenado con objetos de ~32 px hacia arriba. Un
peatón a 50 m en una cámara de plaza mide 10-14 px. Ningún umbral arregla eso.

### El fallo que lo hacía imposible

`_tiles()` usaba el lado de **entrada del modelo** en lugar del lado de **tesela**.
Al estar igualados, la tesela nunca podía ser menor que la entrada, y por tanto **el
zoom nunca fue posible**. El síntoma era que cambiar el tamaño de tesela no hacía
absolutamente nada.

### Zoom de análisis

Recortar teselas *menores* que la entrada y dejar que el letterbox las amplíe. Una
persona de 20 px llega al modelo a 40.

Medido sobre el frame real de Alcalá:

| | Teselas | Objetos | **Personas** |
|---|---:|---:|---:|
| ×1 (tesela 416) | 21 | 81 | 2 |
| **×2 (tesela 208)** | 84 | 141 | 3 |
| **×2 + umbral de persona 0,08** | 84 | **144** | **6** |
| ×3 (tesela 139) | 210 | 29 | 0 |

**×3 se hunde**: la tesela pierde tanto contexto que el modelo deja de reconocer
formas. Más zoom no es siempre mejor y hay un óptimo.

### Umbral por clase

Una persona a media distancia es un objeto mucho más difícil que un turismo: menos
píxeles, silueta variable y casi siempre parcialmente ocluida por otra persona.
Aplicarle el mismo listón que a un vehículo es exactamente lo que produce «detecta
furgonetas pero no gente». Ahora `person` va a 0,08 frente al 0,15 general.

### Lo que sigue sin funcionar, y por qué

**Carritos, sillas de ruedas, patinetes**: no existen como clase en COCO. El modelo no
puede detectar algo que nunca vio etiquetado. Hace falta otro modelo, no otro ajuste.

**Detectar cabezas en vez de siluetas**: la intuición es correcta y es lo que hace el
campo con multitudes — en una plaza los cuerpos se ocluyen entre sí y las cabezas no.
Pero COCO no tiene clase «cabeza»: haría falta un modelo entrenado para ello
(SCUT-HEAD, CrowdHuman) o un método de conteo por mapa de densidad, que da recuento
sin cajas. Es un modelo distinto, y no lo he podido validar aquí.

**«Detecta coches donde no hay»**: si el detector neuronal no está cargado, el sistema
cae a sustracción de fondo y clasifica manchas **por proporción de la caja** — una
mancha ancha sale «coche». Comprueba en el panel que pone *neuronal* y no *local*.
