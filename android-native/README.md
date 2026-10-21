# ARGOS nativo — la app sin WebView

## Por qué existe

El APK web corre dentro de un WebView y choca con dos techos que no se arreglan
desde la aplicación, los dos medidos:

```
capture()   drawImage + getImageData   75 % del coste del frame
inferencia  un solo hilo: Android WebView no concede el aislamiento entre
            orígenes, así que no hay SharedArrayBuffer ni hilos WASM
```

Aquí los dos desaparecen. CameraX entrega los píxeles en memoria de CPU, sin
lienzo ni lectura de GPU, y ONNX Runtime Android usa hilos de verdad con el
MISMO `yolox_nano.onnx` y el mismo preproceso (BGR crudo 0-255, relleno 114).

## Qué hay

| Pieza | Qué hace |
|---|---|
| `core/Core.kt` | `engine.js` en Kotlin puro: fondo, morfología, componentes, seguimiento |
| `core/Detector.kt` | `detector.js` en cuadro entero y en teselas ×2, y la memoria de `Brain.mergeDetections` |
| `app/Pipeline.kt` | los dos relojes: cámara y seguimiento en un hilo, detector en otro, sin cola |
| `app/MainActivity.kt` | CameraX RGBA girado a 640×480, carga, autoprueba, panel; un toque cambia de modo |
| `app/Overlay.kt` | las cajas: clase solo si la dio el detector, «movimiento» si no |

`core` no depende de Android: compila y se prueba en la JVM, que es lo que
permite verificarlo contra el JS sin dispositivo.

### El detector dice QUÉ, el fondo dice DÓNDE

Las cajas del detector entran en el seguimiento una vez, en el frame en que
llegan. Entre pasadas manda la resta de fondo y la pista conserva la clase que
le dio el detector. Lo que se mueve y el detector no ha visto se sigue como
«movimiento». Repetir la caja del detector en cada frame —lo que hacía la app
web— la congela mientras la persona anda, y tirar las regiones de movimiento
hacía desaparecer lo no detectado. Es el mismo cambio en `engine.js`,
`brain.js` y aquí, y la paridad lo cubre.

## Verificado

```
núcleo     260/260 frames sin detector, 260/260 con detector   -> IDÉNTICOS
detector   bus, zidane, dog; cuadro entero y teselas ×2         -> cajas ±0,0005 px
reservado  filas rellenadas como CameraX (rowStride)           -> IDÉNTICO
```

El CI (`.github/workflows/build-native.yml`) exige las tres antes de compilar
el APK, y luego lo prueba en un emulador: detector cargado, autoprueba con una
foto fija comparada con `detector.js`, frames de cámara analizados e
inferencias sobre ellos.

## Reproducir, desde la raíz del repositorio

```bash
node scripts/dump_js.js                                   # referencia del núcleo
python3 scripts/prep_detector_ref.py android-native/build/det bus.jpg zidane.jpg dog.jpg
cp yolox_nano.onnx android-native/build/det/
node scripts/ref_detector.js android-native/build/det     # referencia del detector
cd android-native
./gradlew :core:paridad :core:paridadDetector :core:casoReservado
./gradlew :app:assembleDebug                              # necesita el SDK de Android
```

## Lo que no está medido

- En un teléfono real: fps, coste por etapa y el rango del color de cámara.
  El panel da los cuatro números (captura · reducir · análisis · detector).
- NNAPI o XNNPACK frente a la CPU: hoy se pide solo la CPU, y el nombre que
  sale en el registro es el que se usa.
- La pirámide y los modos foveales del JS no están portados.
