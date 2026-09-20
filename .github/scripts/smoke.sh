#!/usr/bin/env bash
# Comprobación de arranque de ARGOS dentro del emulador.
#
# Esto vive en un fichero, y no dentro del bloque `script:` del workflow,
# porque `android-emulator-runner` ejecuta ese bloque LÍNEA A LÍNEA con
# `sh -c`. Un `if ... fi` repartido en varias líneas le llega al shell partido
# por la mitad y muere con:
#
#     /usr/bin/sh: 1: Syntax error: end of file unexpected (expecting "fi")
#
# Ocurrió: el APK se instaló, la actividad arrancó y la captura se hizo. Lo
# único roto era el comprobador, que daba el job por fallido sin que la
# aplicación tuviera nada que ver.
set -euo pipefail

APK=${1:-ARGOS.apk}
PKG=eus.argos.app

adb install -r "$APK"

# El permiso se concede sin diálogo: el emulador no tiene a nadie que lo pulse,
# y sin cámara la app no pasa de la pantalla inicial.
adb shell pm grant "$PKG" android.permission.CAMERA || true

adb shell am start -n "$PKG/.MainActivity"
sleep 15

adb exec-out screencap -p > screenshot.png
adb logcat -d > logcat.txt

if grep -q 'FATAL EXCEPTION' logcat.txt; then
    echo "::error::crash al arrancar"
    sed -n '/FATAL EXCEPTION/,$p' logcat.txt | head -60
    exit 1
fi

if ! adb shell dumpsys activity activities | grep -q "$PKG/.MainActivity"; then
    echo "::error::la actividad no está en primer plano"
    exit 1
fi

# El WebView reenvía sus errores de consola a logcat con tag ARGOS, así que un
# fallo de carga del motor se ve aquí. Se vuelca al log del job, no solo al
# artifact: el artifact se sirve desde un almacén que no es alcanzable desde
# todas las redes, y entonces el diagnóstico se pierde justo cuando hace falta.
echo "--- logcat ARGOS ---"
grep 'ARGOS' logcat.txt | tail -40 || echo "(sin líneas con tag ARGOS)"

# Y esto tiene que TUMBAR el job, no solo imprimirse.
#
# La versión anterior listaba estas líneas y terminaba con un OK. Así pasó en
# verde un APK cuyo `brain.js` moría entero con
#
#     Uncaught SyntaxError: Unexpected token '=' @4155
#
# por un `??=` que el WebView de esta imagen no parsea. La aplicación se
# instalaba, arrancaba, quedaba en primer plano y enseñaba la cámara sin
# analizar nada: el fallo más caro del proyecto, y el comprobador decía que
# todo estaba bien. Un proceso vivo no es un proceso que funcione.
if grep 'ARGOS' logcat.txt | grep -Eq 'Uncaught|SyntaxError|ReferenceError|TypeError'; then
    echo "::error::el motor no cargó: error de JavaScript en el WebView"
    grep 'ARGOS' logcat.txt | grep -E 'Uncaught|SyntaxError|ReferenceError|TypeError'
    exit 1
fi

# Y el silencio tampoco es éxito. `onConsoleMessage` reenvía toda la consola,
# así que «ninguna línea ARGOS» es indistinguible de «la página no llegó a
# ejecutarse»: la comprobación anterior habría pasado igual con un WebView en
# blanco. El cliente publica una marca al terminar de cargar, y aquí se exige.
if ! grep -q 'ARGOS listo:' logcat.txt; then
    echo "::error::el motor no publicó su marca de arranque"
    echo "(ni «ARGOS listo» ni «ARGOS incompleto» en logcat: la página no llegó"
    echo " a ejecutarse, o lo hizo sin los módulos)"
    exit 1
fi

echo "OK: instalado, arrancado, en primer plano y con los once módulos cargados."
