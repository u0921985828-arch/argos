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
adb shell settings put system font_scale 1.0
# Para que la prueba del widget pueda ponerlo como lo pone un lanzador.
adb shell appwidget grantbind --package "$PKG" --user 0 || true
adb logcat -c
adb shell am instrument -w "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > auto.txt 2>&1 || true
adb logcat -d > auto-logcat.txt || true
grep -E "OK \(|FAILURES|Tests run" auto.txt || true
grep -E "SurcoAuto" auto-logcat.txt | sed 's/^.*SurcoAuto: /  /' || true
# Cuatro pruebas: el navegador de Media3, el camino de Android Auto (MediaBrowser del sistema,
# toque y voz), el widget puesto por un anfitrión (enseña la canción, reproducir y pausar) y sus capturas.
mkdir -p capturas
adb pull "/sdcard/Android/data/$PKG/files/widget/." capturas/ >/dev/null 2>&1 || true
ls capturas/widget_*.png 2>/dev/null || true
if ! grep -q "OK (4 tests)" auto.txt; then
    echo "::error::la navegación de Android Auto falló (ver auto.txt y auto-logcat.txt)"
    sed -n '1,120p' auto.txt
    grep -E "PlaybackService|MediaSession|AndroidRuntime|SurcoAuto" auto-logcat.txt | tail -60 || true
    exit 1
fi
echo "Android Auto y widget: navegación, búsqueda, reproducción en contexto y mandos del widget, bien"
