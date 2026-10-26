# Auditoría de Surco 2.1

Auditoría independiente (un revisor que no escribió el código) de diseño, lógica, reproducción y motor de sonido, más lo que salió al medir. Cada hallazgo, con lo que se hizo y cómo se comprueba.

## Calidad de sonido: qué pasa con la señal

| Fuente | Antes (2.0) | Ahora (2.1) |
|---|---|---|
| FLAC / ALAC (cualquier profundidad) | Decodificado a 16 bits; 24 bits recortados | Decodificado en coma flotante, procesado en float, entregado en float al sistema |
| WAV de 24/32 bits o float | Convertido a 16 bits antes de los efectos | En float de punta a punta |
| MP3 / AAC / Opus / Vorbis | 16 bits | 16 bits a propósito: Media3 solo recorta los huecos del codificador («sin cortes») en 16 bits, y es su resolución útil |
| Efectos en neutro, fuente de 16 bits | Bit a bit | Bit a bit |
| Efectos activos, fuente de 16 bits | Float interno y dither TPDF a 16 bits | Igual |

Lo que Surco no controla: el mezclador de Android remuestrea a la frecuencia del dispositivo (casi siempre 48 kHz) salvo en modo «bit-perfect» por USB (Android 14+), que no está implementado.

Comprobación: pruebas JVM `PcmTest` (24 bits → float exacto en 4.101 valores; ±1 LSB de 24 bits, −138 dBFS, sobrevive al motor neutro) y prueba de humo (log `Surco salida:` del FLAC de 96 kHz y del WAV float de 192 kHz en coma flotante; MP3 en 16 bits).

## Hallazgos

| # | Gravedad | Hallazgo | Resolución |
|---|---|---|---|
| 1 | FATAL | Un archivo que no se puede abrir dejaba la reproducción parada; play repetía el fallo; la cola se restauraba con la caché (canciones ya borradas) | `onPlayerError`: se quita de la cola con aviso y sigue (máx. 10 seguidas). La cola se restaura solo tras consultar MediaStore; al reanudar desde fuera se filtra lo que ya no existe, fuera del hilo principal. Si la canción que suena desaparece de la biblioteca, el minirreproductor usa los datos del reproductor |
| 2 | FATAL | `usuario.json` podía quedar corrupto (dos escrituras a la vez) y perderse todo en silencio; `ajustes.json` sin escritura segura | `Disk`: AtomicFile (fsync + copia) y un único hilo de escritura para todo; un JSON ilegible se aparta (`.roto-…`) en vez de sobrescribirse. También la caché de la biblioteca |
| 3 | FATAL | Favoritas, listas y escuchas colgaban del `_ID` de MediaStore: se perdían al volver a copiar la música o cambiar de móvil | Claves estables por canción (ruta; si no, título+artista+álbum+duración). Tras cada escaneo, `UserStore.reconcile` cambia los ids viejos por los nuevos. Prueba `idsQueCambianSeRecuperanPorRutaOPorDatos` |
| 4 | GRAVE | En aleatorio solo sonaba, de media, la mitad de la lista; «A continuación» caía en cualquier sitio | Mezclar empieza por la primera del orden aleatorio; al activar el aleatorio la actual va primera; lo añadido va detrás de la actual o al final del orden. Pruebas `aleatorioEmpiezaPorLaQueSuenaYNoPierdeNinguna`, `aContinuacionYALaColaEnAleatorio` |
| 5 | GRAVE | El limitador recortaba cada pico (THD 1–1,3 %) y actuaba con solo mono o balance; los preajustes no bajaban la preamplificación | Preamplificación automática (por defecto) que deja la curva en ≤ 0 dB; limitador solo si la curva pasa de 0 dB, con mantenimiento de 60 ms. Medido: mono y balance THD < 0,001 %; «Graves» con preamplificación automática < 0,01 %; con subida manual, el limitador contiene sin pasar de 0,98 y THD < 1 % (antes 1,3 %) |
| 6 | GRAVE | ReplayGain se aplicaba después de los efectos; Opus con R128 sin leer | Con la preamplificación automática el motor ya no satura antes del volumen de RG (la causa del daño del hallazgo 5). Opus: lector propio de comentarios (jaudiotagger 3.0.1 no abre .opus) con R128 → escala RG (+5 dB). Pruebas `opusComentariosYR128`, `r128Conversion` |
| 7 | GRAVE | Escanear 10.000 canciones congelaba la interfaz y gastaba mucha memoria | 4 lecturas a la vez, interfaz rehecha como mucho 1 vez/s, caché guardada cada 20 s, un escaneo por proceso; agrupaciones, ordenaciones y búsqueda en `Dispatchers.Default` |
| 8 | GRAVE | La búsqueda encontraba por «No», «24 bits» o «-7.12»; no ignoraba tildes; un disco sonaba en orden alfabético | Solo campos de texto, sin tildes ni mayúsculas, por palabras (índice plegado una vez). Al tocar una canción suena su disco en orden desde ella. Pruebas `TextTest` |
| 9 | GRAVE | Tras dos negativas al permiso, la app se quedaba sin salida | El botón pasa a «Abrir ajustes»; el permiso se vuelve a comprobar al volver a la app |
| 10 | MENOR | Buscar costaba dos toques; álbumes sin orden útil; botón de 40 dp | Buscador visible arriba en Inicio y Biblioteca; álbumes por A–Z, artista, año o recientes; botón de 48 dp. Sin índice de letras (pendiente) |
| 11 | MENOR | La línea «Salida» decía «bit a bit / sin pérdida» con RG o velocidad activos, y un CD en FLAC parecía alta resolución | Dice la profundidad del archivo y lo que cambia la señal (efectos, ReplayGain, velocidad); «sin recortar a 16 bits» en vez de «sin pérdida» |
| 12 | MENOR | Ecualizador y cola inaccesibles con TalkBack; menú de canción oculto | Bandas con valor anunciado y ajustables (setProgress); «Subir/Bajar» en la cola; botón «Más opciones» en cada canción. Las bandas miden 38 dp, no 48: diez de 48 dp no caben en un móvil; con TalkBack se ajustan sin tocar el trazo |
| 13 | MENOR | Al reanudar se perdían aleatorio y repetición; «Repetir una» no sumaba escuchas | Se guardan y restauran; cada repetición o vuelta al principio cuenta como escucha nueva |
| 14 | MENOR | El fundido de entrada se comía el ataque de una canción nueva | Fundido solo al reanudar la misma canción ya preparada |
| 15 | MENOR | Caché de portadas por número (cientos de MB) y miniaturas estiradas | Por bytes (1/8 de la memoria) y por tamaños 128/256/512/1024 px |
| — | (medido aquí) | Los WAV traen el nulo final dentro del texto de sus etiquetas («Prueba\u0000») | `cleanText` lo quita en jaudiotagger y MediaStore |
| — | (caso reservado) | Media3 1.5.1 lee los WAV en coma flotante con cabecera extensible como enteros de 32 bits: sonarían como ruido | `SurcoExtractorsFactory` corrige el formato a float al leer la cabecera; prueba `wavEnComaFlotanteExtensible` y humo con un WAV float de 192 kHz |

## Orden de uso (pantalla principal)

Antes: una sola pantalla con buscador, chips y 30 tarjetas de etiquetas; poner un disco o seguir donde se dejó costaba varios toques.

Ahora, barra inferior por orden de uso diario:
1. **Inicio**: «Seguir escuchando» (1 toque), buscador a la vista, mezclar todo / favoritas / más escuchadas, escuchado y añadido hace poco (portadas con reproducir), lo que más suena y acceso a etiquetas.
2. **Biblioteca**: álbumes en cuadrícula (ordenables), artistas, carpetas y canciones.
3. **Etiquetas**: la seña de Surco, organizar por cualquier etiqueta.
4. **Listas**: automáticas y propias.

## Pendiente

- Salida «bit-perfect» por USB (Android 14+, `AudioMixerAttributes`): sin DAC USB en el emulador no se puede medir.
- Fundido cruzado entre canciones, índice de letras en álbumes, Android Auto, edición de etiquetas.
- Todo lo de arriba está probado en JVM y en emulador; falta un móvil real.

## Revisión final (segundo revisor independiente)

| # | Gravedad | Ataque | Resolución |
|---|---|---|---|
| 1 | FATAL | La prueba de humo fallaba en el ecualizador | La prueba buscaba un texto que con la preamplificación automática ya no existe; ahora reconoce la pantalla por título y preajustes, y el preajuste guardado por el subtítulo |
| 2 | GRAVE | La preamplificación automática no se aplicaba con el EQ apagado y solo tono; el desfase de los filtros subía la cresta de una onda cuadrada por encima de 1 sin limitador | La preamplificación se aplica también con el tono; el limitador vigila siempre que haya filtros (solo actúa si algo pasa de 0,98). Pruebas `ondaCuadradaConPreajusteNoSeRecorta` (Voz, Nocturno, Rock, Graves: 0 muestras recortadas) y `tonoConEcualizadorApagadoTambienSeCompensa` |
| 3 | GRAVE | Las claves estables solo se guardaban al escanear | Se guardan en el acto, al marcar, añadir a una lista o escuchar |
| 4 | MENOR | Cualquier error quitaba canciones de la cola | Solo los fallos del archivo (2000–4999); uno de salida de audio para y avisa |
| 5 | MENOR | La línea «Salida» podía presumir de 24 bits con un decodificador de 16 o mostrar el formato de la siguiente canción | Lo dice («el decodificador de este móvil entrega 16 bits») y se oculta si la frecuencia no casa con la que suena |
| 6 | MENOR | Un búfer vacío con orden de bytes equivocado rompía el contrato de DefaultAudioSink | Búfer en orden nativo desde el principio y los vacíos se aceptan sin pasar |
| 7 | MENOR | «A la cola» en aleatorio se volvía «A continuación» si la actual era la última | «A la cola» llega sin índice y siempre va al final del orden |

## Diseño (medido en el emulador)

Recorrido de 29 pantallas con captura, con la letra normal y ×1,3; comprobador automático y revisión visual de un revisor que no hizo la interfaz.

| # | Gravedad | Hallazgo | Resolución |
|---|---|---|---|
| 1 | FATAL | El título de «Sonando» y el de la pantalla de permiso salían en negro sobre fondo oscuro (1,1:1) | Fuera del `Scaffold` Compose pinta en negro: `SurcoTheme` fija el color de contenido a `Paper` |
| 2 | FATAL | El deslizador de «Sonando» se veía en tres piezas (barra, hueco, punto) y los del ecualizador, punteados | `SurcoSlider`: pulgar redondo, pista continua, sin marcas; los de cero en medio, sin relleno |
| 3 | GRAVE | La ficha técnica salía tres veces en «Sonando» | Línea de salida solo si recorta o cambia la señal; chips sin el grupo Archivo (siguen en «Más») |
| 4 | GRAVE | «Seguir escuchando» repetía el minirreproductor | Solo cuando no hay nada cargado |
| 5 | GRAVE | «Varios artistas» en casi todas las tarjetas | «Otra Banda, Banda Surco y 2 más», una sola función |
| 6 | GRAVE | Dos colores de «seleccionado» y chips de 32–34 dp | `chipColors()` y 40 dp en los 9 sitios |
| 7 | GRAVE | Ajustes como un manual y márgenes distintos | Una frase por opción; `OptionRow` a 20 dp con flecha |
| 8 | GRAVE | Letra ×1,3: «Carpetas» fuera, títulos cortados, «Fuert/e» partido, buscador de dos líneas | Chips sin recuento (total en la cabecera), dos líneas, fila que desliza, una línea |
| 9 | GRAVE | Tres cabeceras distintas en cuatro pestañas | `TabHeader` en las cuatro |
| 10 | GRAVE | Primera apertura: botones que no hacen nada sin canciones; círculo vacío en permiso; listas vacías igual de destacadas | Solo el aviso; icono; listas vacías en gris e iconos que no dicen «oculto» |
| 11 | MENOR | «0» de reinicio junto al valor; preajuste como nombre del botón EQ; etiqueta como «Más» | Icono de reinicio solo si hace falta; «EQ»; «⋮» |

Medido: el comprobador final da 6 fallos con letra normal y 5 con ×1,3 en las capturas de partida, y 0 y 0 en las de ahora. El CI es estricto desde entonces.
No aplicado: unificar radios en dos constantes (cosmético).
