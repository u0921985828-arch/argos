#!/usr/bin/env bash
# Android Auto, sin coche: un navegador de Media3 (el mismo protocolo que usa Android Auto) se
# conecta desde fuera al servicio de Surco, recorre el árbol, busca y toca una canción
# (surco/app/src/androidTest/.../AutoTest.kt). Va después de la prueba de humo y del recorrido,
# con la biblioteca ya leída. La prueba se instala contra el APK de depuración (sin R8: la prueba
# comparte las clases de Media3 con la app); se pone encima del de release con la misma firma,
# así que los datos de la app se conservan.
set -euo pipefail
PKG=com.eddie.surco
adb install -r Surco-debug.apk >/dev/null
adb install -r Surco-auto-test.apk >/dev/null
adb shell cmd locale set-app-locales com.eddie.surco --locales es-ES  # las pruebas buscan los textos en español
adb shell settings put system font_scale 1.0
# Para que la prueba del widget pueda ponerlo como lo pone un lanzador.
adb shell appwidget grantbind --package "$PKG" --user 0 || true
adb logcat -c
# La de batería va aparte (bateria-surco.sh): necesita sus audios y tarda cinco minutos.
adb shell am instrument -w -e notClass "$PKG.BatteryTest" "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > auto.txt 2>&1 || true
adb logcat -d > auto-logcat.txt || true
grep -E "OK \(|FAILURES|Tests run" auto.txt || true
grep -E "SurcoAuto" auto-logcat.txt | sed 's/^.*SurcoAuto: /  /' || true
# Cinco pruebas: el navegador de Media3, el camino de Android Auto (MediaBrowser del sistema,
# toque y voz), el widget puesto por un anfitrión (enseña la canción, reproducir y pausar), sus
# capturas, la cuadrícula de álbumes pellizcada con dos dedos (de 2 a 5 portadas por fila y vuelta)
# y el cruce con solape (dos reproductores activos a la vez al pasar de una canción a otra).
mkdir -p capturas
adb pull "/sdcard/Android/data/$PKG/files/widget/." capturas/ >/dev/null 2>&1 || true
adb pull "/sdcard/Android/data/$PKG/files/rejilla/." capturas/ >/dev/null 2>&1 || true
ls capturas/widget_*.png capturas/rejilla_*.png 2>/dev/null || true
if ! grep -q "OK (6 tests)" auto.txt; then
    echo "::error::fallaron las pruebas de Android Auto, del widget o de la cuadrícula (ver auto.txt y auto-logcat.txt)"
    sed -n '1,120p' auto.txt
    grep -E "PlaybackService|MediaSession|MediaButtonReceiver|SurcoWidget|AndroidRuntime|SurcoAuto" auto-logcat.txt | tail -60 || true
    exit 1
fi
echo "Android Auto, widget y cuadrícula: navegación, búsqueda, reproducción en contexto, mandos del widget y pellizco de 2 a 5, bien"
