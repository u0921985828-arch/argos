# Surco — reproductor de música Android que organiza por etiquetas

App nativa Android (Kotlin + Jetpack Compose + Media3). Lee todas las etiquetas de los archivos de audio del móvil y permite navegar la biblioteca por cualquiera de ellas.

## Estado
- **Compila y funciona**: la primera compilación salió sin errores de Kotlin ni de R8. El APK de release pasa una prueba de humo en emulador (API 34): arranca, MediaStore ve las canciones, el buscador encuentra etiquetas que solo lee jaudiotagger (estado de ánimo en ID3 TMOO, sello en Vorbis LABEL) y reproduce sin caerse.
- Se compila en GitHub Actions (`.github/workflows/build-surco.yml` del repo `argos`, donde vive en `surco/`): el contenedor de desarrollo no llega a Google Maven ni al Android SDK. Cada push que toque `surco/` compila debug y release, pasa la prueba de humo (`.github/scripts/smoke-surco.sh`) y deja los APK en el artifact `surco-apk` y en la rama `apk-surco` (un solo commit, se reescribe).
- El release va firmado con la clave de depuración del ejecutor, que cambia en cada compilación: para instalar uno nuevo encima hay que desinstalar el anterior. Una clave propia lo arregla.

## Compilar
Requisitos: JDK 17 o superior y Android SDK (platform 35, build-tools 35). Crear `local.properties` con `sdk.dir=<ruta al SDK>` si no existe (en Windows: `sdk.dir=C\:\\Users\\<usuario>\\AppData\\Local\\Android\\Sdk`).

```
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # minificado, firmado con la clave debug: app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Si el release falla en R8 por clases que faltan de jaudiotagger (java.awt, javax.imageio…), añadir `-dontwarn` en `app/proguard-rules.pro`.

## Versiones
AGP 8.7.3 · Kotlin 2.0.21 · Gradle 8.14.3 · Compose BOM 2024.12.01 · Media3 1.5.1 · jaudiotagger 3.0.1 · minSdk 26 · targetSdk 35.

## Estructura (`app/src/main/java/com/eddie/surco/`)
- `MainActivity.kt` — permisos (READ_MEDIA_AUDIO / READ_EXTERNAL_STORAGE, POST_NOTIFICATIONS), navegación por pila, mini reproductor, pantalla de reproducción y ficha de etiquetas.
- `LibraryViewModel.kt` — estado, pila de pantallas (`Screen.Home / Groups / Songs`) y escaneo: MediaStore primero (rápido), luego lectura profunda en paralelo con caché en `filesDir/library.json` (solo relee archivos con `dateModified` cambiado).
- `data/Track.kt` — modelo de canción + JSON. Las etiquetas poco comunes van en `extra` (bpm, key, mood, label, lyricist, producer, remixer, conductor, grouping, language, country, media, encoder, comment, isrc, catalog, original_year, rating, channels, bits, codec, lossless, lyrics…).
- `data/LibraryScanner.kt` — MediaStore + jaudiotagger (todas las etiquetas) con MediaMetadataRetriever como respaldo. Convierte géneros ID3v1 numéricos a nombre.
- `data/TagField.kt` — los 30 campos por los que se agrupa (Personas, Obra, Tiempo, Archivo) y cómo se hacen los grupos: rangos de duración, BPM en decenas, décadas, bitrate, calidad (alta resolución / sin pérdida / con pérdida), mes de añadido, etc. Separa varios artistas (`;`, ` / `, feat.) y géneros (`; / , |`).
- `player/PlaybackService.kt` — MediaSessionService con ExoPlayer (notificación, pantalla de bloqueo, pausa al desconectar auriculares). `onAddMediaItems` recupera la URI desde `requestMetadata.mediaUri`.
- `player/PlayerConnection.kt` — MediaController expuesto como estado de Compose.
- `ui/Theme.kt` — paleta oscura (tinta, cobre, verde azulado), carga de portadas con caché LRU, formateo de tiempos.
- `ui/Screens.kt` — Inicio (buscador en todas las etiquetas, tarjetas por campo), Grupos (orden A–Z o por nº de canciones), Canciones (reproducir, aleatorio, 6 órdenes).
- `ui/PlayerUi.kt` — mini reproductor, pantalla "Sonando", chips de etiquetas pulsables, ficha completa (pulsación larga en una canción).

## Posibles mejoras después de compilar
- Cola de reproducción visible y editable.
- Listas de reproducción y favoritos.
- Edición de etiquetas (jaudiotagger puede escribir; requiere permiso de escritura con MediaStore.createWriteRequest).
- Letras sincronizadas (la etiqueta LYRICS ya se detecta).

## Idioma
La interfaz y los comentarios están en español.
