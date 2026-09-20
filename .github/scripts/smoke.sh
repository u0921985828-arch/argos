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

# Se espera a la marca del detector, no un tiempo fijo.
#
# Cargar ONNX Runtime y construir la sesión sobre un emulador sin aceleración
# tarda lo que tarda, y un `sleep` generoso alarga todos los jobs para el caso
# peor. Se sondea y se sale en cuanto hay veredicto, en un sentido o en otro.
for _ in $(seq 1 60); do
    adb logcat -d > logcat.txt
    if grep -q 'ARGOS detector' logcat.txt; then break; fi
    if grep -q 'FATAL EXCEPTION' logcat.txt; then break; fi
    sleep 2
done

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

# El detector es la única función del programa, y hasta ahora nada lo
# comprobaba.
#
# ONNX Runtime se descargaba de un CDN en tiempo de ejecución mientras el
# modelo viajaba dentro del APK. Sin red el runtime no llegaba, no había
# detector, y la aplicación seguía dibujando cajas --- las de la sustracción de
# fondo, etiquetadas por la proporción de la mancha. En campo eso se vio como
# «coche» sobre un andamio y sobre una cara. El APK pasó todas las
# comprobaciones: instalaba, arrancaba, cargaba los once módulos.
#
# Ahora el runtime va dentro y esto lo exige. El emulador no tiene red de
# salida garantizada, así que si esta línea aparece es porque la copia local
# funciona, que es justo lo que hay que demostrar.
if grep -q 'ARGOS detector NO' logcat.txt; then
    echo "::error::el detector neuronal no cargó"
    grep 'ARGOS detector NO' logcat.txt
    exit 1
fi

if ! grep -q 'ARGOS detector activo:' logcat.txt; then
    echo "::error::el detector no publicó su marca"
    echo "(sin «ARGOS detector activo» ni «ARGOS detector NO»: la carga se quedó"
    echo " colgada, o el modelo y el runtime no están en los assets)"
    exit 1
fi

echo "--- detector ---"
grep 'ARGOS detector activo:' logcat.txt

# El entorno no tumba el job, se IMPRIME.
#
# Un WebView sin aislar corre WASM en un hilo. Eso no es un APK roto --- el
# detector funciona igual, más despacio --- pero es la mayor diferencia de
# velocidad que queda sobre la mesa, y hasta ahora no había forma de saber si
# la causa estaba en el contenedor (que no manda las cabeceras) o en el motor
# (que las ignora). La línea trae las dos cosas: lo que llegó y lo que se
# consiguió con ello.
echo "--- entorno ---"
grep 'ARGOS entorno:' logcat.txt || echo "(sin línea de entorno)"

echo "OK: instalado, arrancado, en primer plano, once módulos y detector neuronal vivo."
