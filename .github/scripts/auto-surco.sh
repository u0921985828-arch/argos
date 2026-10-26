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
adb logcat -c
adb shell am instrument -w "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > auto.txt 2>&1 || true
adb logcat -d > auto-logcat.txt || true
grep -E "OK \(|FAILURES|Tests run" auto.txt || true
grep -E "SurcoAuto" auto-logcat.txt | sed 's/^.*SurcoAuto: /  /' || true
# Dos pruebas: el navegador de Media3 y el camino de Android Auto (MediaBrowser del sistema, toque y voz).
if ! grep -q "OK (2 tests)" auto.txt; then
    echo "::error::la navegación de Android Auto falló (ver auto.txt y auto-logcat.txt)"
    sed -n '1,120p' auto.txt
    grep -E "PlaybackService|MediaSession|AndroidRuntime|SurcoAuto" auto-logcat.txt | tail -60 || true
    exit 1
fi
echo "Android Auto: navegación, búsqueda y reproducción en contexto, bien"
