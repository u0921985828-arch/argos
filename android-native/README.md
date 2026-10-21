# ARGOS nativo — núcleo portado y verificado

## Qué hay aquí

`core/` — el análisis completo en Kotlin puro, sin Android: modelo de fondo,
morfología de suma corrida, componentes conexas y seguimiento. **Compila y se
prueba en la JVM**, que es lo que permitió verificarlo sin dispositivo.

`core/src/test/kotlin/Parity.kt` — la prueba que da sentido al puerto.

## Verificado

```
C1 paridad   260 frames, 0 divergencias frente a engine.js  -> IDÉNTICOS
C2 velocidad analyze() 1,845 ms  (JS: 2,58 ms)              -> 1,4x
```

La paridad exige **cero** divergencias, no «parecido». Dos implementaciones del
mismo algoritmo que se separan poco a poco son la peor deuda posible: nadie se
entera hasta que las cajas ya no coinciden y no hay forma de saber cuál tenía
razón. Por eso hay en `Core.kt` decisiones que en Kotlin escribirías de otra
forma --- cálculo en `Double` y almacenamiento en `Float`, lectura de bytes con
`and 0xFF` --- puestas para reproducir la aritmética de JavaScript exactamente.

Reproducir:

```bash
node scripts/dump_js.js                     # volcado de referencia
kotlinc core/src/main/kotlin/eus/argos/core/Core.kt \
        core/src/test/kotlin/Parity.kt -include-runtime -d parity.jar
java -jar parity.jar
```

## Por qué lo nativo gana, y no es por ese 1,4x

Medido en el navegador, el reparto del coste es:

```
capture()   drawImage + getImageData   75 %
analyze()   el algoritmo               25 %
```

CameraX entrega los píxeles **directamente**, sin lienzo, sin lectura de GPU y
sin `getImageData`. Lo nativo no acelera el análisis: **borra el otro 75 %**.

Y da un segundo premio: el plano Y de `YUV_420_888` ya es luma. El modelo de
fondo consume luma, así que la conversión RGBA→luma desaparece también.

## Lo que falta (necesita SDK de Android, no disponible donde se escribió esto)

- `app/` — CameraX (`ImageAnalysis`, `YUV_420_888`), superficie de dibujo,
  permisos.
- Detector YOLOX por TFLite con delegado NNAPI/GPU en vez de WASM.
- Gradle del módulo y compilación del APK.

**Nada de eso está verificado.** El núcleo sí.
