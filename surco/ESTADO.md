# ESTADO — Surco 2.0 (reproductor completo)

Encargo de Eddie: «Es muy mediocre el reproductor además de no tener EQ y diferentes opciones necesarias que debes investigar».

## Criterios
- C1: pruebas JVM del motor de sonido y del parser de letras en verde (local con kotlinc y en CI). Base: 0 pruebas.
- C2: CI compila debug+release y la prueba de humo en emulador pasa con las funciones nuevas: ecualizador abierto y preajuste aplicado mientras suena, cola visible, favorita marcada que sobrevive a reiniciar la app. Base: no hay EQ, cola, favoritas ni listas.
- C3: cadena de sonido medida: banda a +6 dB sube 6,0 ±0,15 dB un seno en su centro; limitador ≤ 0,98 con +12 dB.
- Caso reservado: FLAC 96 kHz 24 bits mono con EQ activo en el emulador (procesador con 1 canal y frecuencia alta) — no mirar hasta el final.

## Investigación (qué tiene un reproductor serio)
Poweramp / Neutron / Musicolet: EQ gráfico + preajustes + preamp, tono (graves/agudos), balance, mono, crossfeed, limitador, ReplayGain, sin cortes (gapless), fundidos, velocidad y tono, saltar silencios, temporizador, cola editable, listas y favoritas, listas inteligentes, letras sincronizadas, reanudar donde se quedó.

## Decisiones
- DSP propio (AudioProcessor de Media3) en vez de `android.media.audiofx.Equalizer`: 10 bandas exactas en todos los móviles, curva dibujable, probado en JVM. Recibe PCM 16 bits (sin salida float: con ella Media3 se salta los procesadores).
- ReplayGain con `player.volume` al cambiar de pista (sin desfase por el búfer); ganancias positivas topadas a 0 dB.
- `ma.py` no existía: escrito en `.modo-absoluto/ma.py` con la interfaz de v4.3.

## Caminos cerrados
- Compilar Android aquí: dl.google.com bloqueado (ni AGP ni androidx). Se compila en Actions.
- Gradle local con plugin Kotlin: Maven Central da 404 intermitentes vía proxy. Se usa kotlinc suelto (`scratchpad/pruebas.sh`).

## Hecho
- Motor, procesador, ajustes, ReplayGain, datos de usuario, servicio, cola, interfaz completa.
- Pruebas JVM 25/25 (local con kotlinc: `scratchpad/pruebas.sh`; en CI `testDebugUnitTest`).
- Revisión por ataque: 6 (4 graves) → todos arreglados: chasquidos (estructura fija + fundido cruzado, pruebas ×828→<3), humo que aceptaba falsos («Vaciar», 3× «Graves»), RG sin releer biblioteca (caché library2.json + margen 6 dB), claves de preajuste repetidas, índices tras filtrar (keptQueue + índice real en listas), MediaButtonReceiver + URI por mediaId.
- Compilación 1 de Surco 2 en CI: verde a la primera (tests unitarios incluidos).
- Humo 1: falló al abrir «Sonando»: uiautomator no vuelca con la música sonando (la barra se mueve) y deja el volcado anterior.
  → REGLA: volcar la interfaz solo en pausa (KEYCODE_MEDIA_PAUSE) y borrar /sdcard/ui.xml antes de cada volcado.
- Humo 2: los toques al minirreproductor caían en el teclado (el buscador lo dejaba abierto: «sellonorte » con espacio). → la app esconde el teclado al reproducir desde la búsqueda.
