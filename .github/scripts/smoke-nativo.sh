#!/usr/bin/env bash
# Comprobación de la app NATIVA en un emulador de verdad.
#
# No basta con «instala y no se cae». Lo que se exige, línea a línea de logcat:
#   1. el detector cargó con ONNX Runtime Android y pagó su primera inferencia;
#   2. la autoprueba ve en la foto fija lo mismo que `detector.js` (C1 en el
#      aparato, con el runtime y el decodificador JPEG de Android);
#   3. la cámara entrega frames, el núcleo los analiza y el detector infiere
#      sobre ellos (C2). Un proceso vivo no es un proceso que funcione.
set -euo pipefail
APK=${1:-ARGOS-nativo.apk}
PKG=eus.argos.nativo

adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.CAMERA || true
adb logcat -c
adb shell am start -n "$PKG/.MainActivity"

# Se espera a tener varias inferencias sobre frames de cámara, no solo la
# primera: una sola puede ser la del arranque.
for _ in $(seq 1 90); do
    adb logcat -d -s ARGOS:V AndroidRuntime:E > logcat.txt || true
    if grep -q 'FATAL EXCEPTION' logcat.txt; then break; fi
    # `|| true`: con `set -e` y `pipefail`, un grep sin coincidencias dentro
    # de una sustitución mata el script en silencio, antes del primer log.
    k=$(grep -o '[0-9]* inferencias' logcat.txt | tail -1 | awk '{print $1}' || true)
    if [ -n "${k:-}" ] && [ "$k" -ge 5 ]; then break; fi
    sleep 2
done
adb exec-out screencap -p > screenshot.png
adb logcat -d -s ARGOS:V AndroidRuntime:E > logcat.txt || true

echo "--- logcat ARGOS ---"
grep 'ARGOS' logcat.txt | tail -30 || echo "(sin líneas ARGOS)"

if grep -q 'FATAL EXCEPTION' logcat.txt; then
    echo "::error::la app nativa se cayó"
    sed -n '/FATAL EXCEPTION/,$p' logcat.txt | head -60
    exit 1
fi
if ! adb shell dumpsys activity activities | grep -q "$PKG/.MainActivity"; then
    echo "::error::la actividad no está en primer plano"
    exit 1
fi
if ! grep -q 'ARGOS nativo detector:' logcat.txt; then
    echo "::error::el detector nativo no cargó"
    grep -E 'no cargó|falló' logcat.txt || true
    exit 1
fi

# Autoprueba contra la referencia de detector.js sobre la misma foto. Se
# tolera ±0,03 en puntuación: el decodificador JPEG de Android no es el que
# generó la referencia y unos pocos píxeles cambian. Clases y número, exactos.
line=$(grep 'ARGOS autoprueba:' logcat.txt | tail -1 || true)
echo "autoprueba: ${line#*ARGOS autoprueba: }"
got=$(echo "$line" | sed 's/.*ms · //' || true)
ESPERADO="vehicle 0.767 · bike 0.787 · bike 0.309"
python3 - "$got" "$ESPERADO" <<'PY' || { echo "::error::la autoprueba no coincide con detector.js"; exit 1; }
import sys
got = [p.split() for p in sys.argv[1].split(" · ") if p.strip()]
exp = [p.split() for p in sys.argv[2].split(" · ")]
ok = len(got) == len(exp) and all(g[0] == e[0] and abs(float(g[1]) - float(e[1])) <= 0.03
                                  for g, e in zip(got, exp))
print("  esperado:", sys.argv[2]); print("  obtenido:", sys.argv[1])
sys.exit(0 if ok else 1)
PY

last=$(grep 'ARGOS nativo: frames' logcat.txt | tail -1 || true)
frames=$(echo "$last" | grep -o 'frames [0-9]*' | awk '{print $2}' || true)
inf=$(echo "$last" | grep -o '[0-9]* inferencias' | awk '{print $1}' || true)
if [ -z "${frames:-}" ] || [ "$frames" -eq 0 ]; then
    echo "::error::la cámara no entregó frames al núcleo"
    exit 1
fi
if [ -z "${inf:-}" ] || [ "$inf" -eq 0 ]; then
    echo "::error::el detector no infirió sobre ningún frame de cámara"
    exit 1
fi
# La escena y la memoria: que el cerebro corre y la retención se aplicó.
if ! grep -q 'ARGOS nativo memoria: retención aplicada' logcat.txt; then
    echo "::error::la memoria no se abrió (sin línea de retención)"
    exit 1
fi
if ! grep -q 'ARGOS nativo escena:' logcat.txt; then
    echo "::error::la escena no publicó su estado"
    exit 1
fi
grep 'ARGOS nativo escena:' logcat.txt | tail -1
# El chat: responde a una pregunta sobre la escena viva, en el aparato.
if ! grep -q 'ARGOS chat: .*«qué ves» -> Ahora:' logcat.txt; then
    echo "::error::el chat no respondió a «qué ves»"
    grep 'ARGOS chat' logcat.txt || true
    exit 1
fi
grep 'ARGOS chat:' logcat.txt | tail -1

echo "--- cifras ---"
grep 'ARGOS nativo detector:' logcat.txt | tail -1
echo "$last"
# C3: la misma medida que la web (un `sess.run` sobre ceros, sin preproceso),
# en el mismo emulador. 687 ms es la de la app web, run 25 del CI.
warm=$(grep -o 'primera inferencia [0-9]* ms' logcat.txt | tail -1 | awk '{print $3}' || true)
inf_med=$(echo "$last" | grep -o 'inferencia [0-9]* ms' | awk '{print $2}' || true)
echo "C3 primera inferencia: nativo ${warm:-?} ms frente a 687 ms del APK web (mismo emulador)"
echo "C3 inferencia en régimen: nativo ${inf_med:-?} ms por pasada"
echo "OK: detector nativo vivo, autoprueba igual que detector.js, $frames frames analizados y $inf inferencias sobre la cámara."
