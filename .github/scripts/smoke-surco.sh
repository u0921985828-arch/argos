#!/usr/bin/env bash
# Surco en un emulador de verdad, con el APK de release (el que pasa por R8).
#
# Compilar no dice que funcione. Se exige:
#   1. que la biblioteca vea las dos canciones de prueba (MediaStore);
#   2. que el buscador encuentre valores que SOLO lee jaudiotagger (estado de
#      ánimo, sello): si R8 rompe la lectura profunda, la app no se cae, se
#      queda sin ellos y nadie lo nota;
#   3. que al pulsar una canción no se caiga. Que suene se informa, no se
#      exige: el emulador va con -noaudio.
set -euo pipefail
APK=${1:-Surco.apk}
PKG=com.eddie.surco

ui() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true; adb exec-out cat /sdcard/ui.xml > ui.xml || true; }
tap() {  # pulsa el centro del primer nodo cuyo texto es $1
    local xy
    xy=$(python3 - "$1" <<'PY'
import re, sys
xml = open("ui.xml", encoding="utf-8", errors="ignore").read()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if t and b and t.group(1) == sys.argv[1]:
        x1, y1, x2, y2 = map(int, b.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
    [ -n "$xy" ] || return 1
    adb shell input tap $xy
}
crashed() { adb logcat -d -s AndroidRuntime:E > crash.txt || true; grep -q 'FATAL EXCEPTION' crash.txt; }

# Música de prueba: la generó el paso anterior con ffmpeg.
# Recién arrancado, el almacenamiento compartido tarda en montarse y `adb
# push` falla con «Operation not permitted»: se espera y se reintenta.
for f in musica/*; do
    for i in $(seq 1 30); do
        adb shell mkdir -p /sdcard/Music/surco 2>/dev/null \
            && adb push "$f" /sdcard/Music/surco/ >/dev/null 2>&1 && break
        [ "$i" = 30 ] && { echo "::error::no se pudo copiar $f al emulador"; exit 1; }
        sleep 3
    done
done
adb shell ls -l /sdcard/Music/surco
adb shell content call --uri content://media/ --method scan_volume --arg external_primary >/dev/null 2>&1 || true
for f in musica/*; do
    adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
        -d "file:///sdcard/Music/surco/$(basename "$f")" >/dev/null 2>&1 || true
done
for _ in $(seq 1 30); do
    n=$(adb shell content query --uri content://media/external/audio/media --projection _display_name 2>/dev/null | grep -c 'Row:' || true)
    [ "${n:-0}" -ge 2 ] && break
    sleep 2
done
echo "MediaStore ve $n canciones"

adb install -r "$APK"
adb shell pm grant "$PKG" android.permission.READ_MEDIA_AUDIO
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb logcat -c
adb shell am start -n "$PKG/.MainActivity"

# Espera a que termine la lectura profunda de etiquetas.
for _ in $(seq 1 30); do
    sleep 2
    crashed && break
    ui
    grep -q 'text="2 canciones' ui.xml && ! grep -q 'Leyendo etiquetas' ui.xml && break
done
adb exec-out screencap -p > inicio.png
if crashed; then
    echo "::error::Surco se cayó al arrancar"
    sed -n '/FATAL EXCEPTION/,$p' crash.txt | head -60
    exit 1
fi
grep -o 'text="[^"]*"' ui.xml | sed 's/text=//' | grep -v '^""$' | head -40 || true
if ! grep -q 'text="2 canciones' ui.xml; then
    echo "::error::la biblioteca no muestra las 2 canciones de prueba"
    exit 1
fi

# El buscador mira también las etiquetas raras, que solo lee jaudiotagger:
# el estado de ánimo del MP3 (ID3 TMOO) y el sello del FLAC (Vorbis LABEL).
busca() {  # $1 = texto, $2 = canción que tiene que salir
    tap "Buscar en cualquier etiqueta" || tap "$prev" || true
    adb shell input keyevent KEYCODE_MOVE_END
    for _ in $(seq 1 20); do adb shell input keyevent KEYCODE_DEL; done
    adb shell input text "$1"
    sleep 2
    ui
    if ! grep -q "text=\"$2\"" ui.xml || ! grep -qi 'text="1 canci.n encontradas"' ui.xml; then
        echo "::error::buscar «$1» no da «$2»: jaudiotagger no leyó las etiquetas (¿R8?)"
        grep -o 'text="[^"]*"' ui.xml | head -30 || true
        exit 1
    fi
    echo "buscar «$1» → $2"
    prev=$1
}
prev=""
busca zorzal "Prueba Uno"
busca sellonorte "Prueba Dos"
adb exec-out screencap -p > busqueda.png

# Reproduce la del resultado (el FLAC).
tap "Prueba Dos" || { echo "::error::no encuentro la canción «Prueba Dos»"; exit 1; }
sleep 4
adb exec-out screencap -p > sonando.png
if crashed; then
    echo "::error::Surco se cayó al reproducir"
    sed -n '/FATAL EXCEPTION/,$p' crash.txt | head -60
    exit 1
fi
estado=$(adb shell dumpsys media_session | grep -m1 -oE 'state=PlaybackState \{state=[A-Z_]*\(?[0-9]+\)?' || true)
echo "sesión multimedia: ${estado:-sin sesión} (3 = sonando)"
adb logcat -d > logcat.txt || true
echo "Surco: arranca, lee las etiquetas y reproduce sin caerse"
