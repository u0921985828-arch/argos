#!/usr/bin/env bash
# Surco en un emulador de verdad, con el APK de release (el que pasa por R8).
#
# Compilar no dice que funcione. Se exige:
#   1. que la biblioteca vea las dos canciones de prueba (MediaStore);
#   2. que el buscador encuentre valores que SOLO lee jaudiotagger (estado de
#      ánimo, sello): si R8 rompe la lectura profunda, la app no se cae, se
#      queda sin ellos y nadie lo nota;
#   3. que al pulsar una canción no se caiga. Que suene se informa, no se
#      exige: el emulador va con -noaudio;
#   4. lo de Surco 2: favorita, «Sonando» con la calidad del archivo, cola,
#      ecualizador con un preajuste aplicado mientras suena (el audio pasa por
#      el procesador propio), y que favorita y preajuste sobrevivan a cerrar
#      la app;
#   5. salida sin pérdida: FLAC y WAV de alta resolución en coma flotante, MP3 en 16 bits;
#      caso reservado: WAV float de 192 kHz estéreo con el ecualizador puesto.
set -euo pipefail
APK=${1:-Surco.apk}
PKG=com.eddie.surco

# uiautomator no vuelca mientras la interfaz no está quieta (con música sonando, la barra de
# progreso se mueve) y deja el volcado ANTERIOR: se borra antes y se reintenta.
ui() {
    : > ui.xml
    for _ in 1 2 3 4 5; do
        adb shell rm -f /sdcard/ui.xml
        adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
        adb exec-out cat /sdcard/ui.xml > ui.xml 2>/dev/null || true
        if grep -q '<hierarchy' ui.xml; then
            # El emulador a veces saca «Pixel Launcher isn't responding» encima: se espera y se repite.
            if grep -q "isn't responding" ui.xml; then tap "Wait" || true; sleep 3; continue; fi
            return 0
        fi
        sleep 1
    done
    echo "(volcado de interfaz vacío)"
}
tap() {  # pulsa el centro del primer nodo cuyo texto (o descripción) es $1
    local xy
    xy=$(python3 - "$1" <<'PY'
import re, sys
xml = open("ui.xml", encoding="utf-8", errors="ignore").read()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n)
    c = re.search(r' content-desc="([^"]*)"', n)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if b and ((t and t.group(1) == sys.argv[1]) or (c and c.group(1) == sys.argv[1])):
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
    [ "${n:-0}" -ge 4 ] && break
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
    grep -q 'text="4 canciones' ui.xml && ! grep -q 'Leyendo etiquetas' ui.xml && break
done
adb exec-out screencap -p > inicio.png
if crashed; then
    echo "::error::Surco se cayó al arrancar"
    sed -n '/FATAL EXCEPTION/,$p' crash.txt | head -60
    exit 1
fi
grep -o 'text="[^"]*"' ui.xml | sed 's/text=//' | grep -v '^""$' | head -40 || true
if ! grep -q 'text="4 canciones' ui.xml; then
    echo "::error::la biblioteca no muestra las 4 canciones de prueba"
    exit 1
fi

# El buscador mira también las etiquetas raras, que solo lee jaudiotagger:
# el estado de ánimo del MP3 (ID3 TMOO) y el sello del FLAC (Vorbis LABEL).
busca() {  # $1 = texto, $2 = canción que tiene que salir
    # La búsqueda es una pantalla propia (lupa de Inicio); si ya está abierta, se reescribe.
    tap "Buscar en cualquier etiqueta" || tap "$prev" || { tap "Buscar" && sleep 2 && ui && { tap "Buscar en cualquier etiqueta" || true; }; } || true
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
fallo() { echo "::error::$1"; grep -o 'text="[^"]*"\|content-desc="[^"]*"' ui.xml | head -60 || true; exit 1; }
cayo() { if crashed; then echo "::error::Surco se cayó $1"; sed -n '/FATAL EXCEPTION/,$p' crash.txt | head -60; exit 1; fi; }
estado() { adb shell dumpsys media_session | grep -m1 -oE 'state=PlaybackState \{state=[A-Z_]*\(?[0-9]+\)?' || true; }
cayo "al reproducir"
echo "sesión multimedia: $(estado) (3 = sonando)"

# --- Surco 2 --- (en pausa para poder volcar la interfaz; la tecla multimedia
# pasa por la sesión, así que también prueba el fundido de pausa)
adb shell input keyevent KEYCODE_MEDIA_PAUSE
sleep 2
ui
tap "Favorita" || fallo "no hay corazón de favorita en el minirreproductor"
sleep 1
tap "Otra Banda" || fallo "no encuentro el minirreproductor"
sleep 2; ui
grep -q 'text="SONANDO"' ui.xml || fallo "no se abrió «Sonando»"
grep -q 'text="FLAC · 16 bits' ui.xml || fallo "«Sonando» no enseña la calidad del archivo"
grep -q 'content-desc="Quitar de favoritas"' ui.xml || fallo "la favorita no quedó marcada"
# Un FLAC se decodifica en coma flotante y llega así al sistema (no se recorta a 16 bits).
grep -q 'text="Salida: coma flotante' ui.xml || fallo "«Sonando» no dice que el FLAC sale en coma flotante"
adb exec-out screencap -p > sonando2.png

tap "Cola" || fallo "no encuentro el botón de la cola"
sleep 2; ui
# «Vaciar» solo existe en la hoja de la cola («Cola» y el título también están en «Sonando»).
grep -q 'text="Vaciar"' ui.xml && grep -q 'text="1 canción · 0 min"' ui.xml && grep -q 'text="Prueba Dos"' ui.xml || fallo "la cola no se abrió o no lleva la canción"
adb exec-out screencap -p > cola.png
adb shell input keyevent KEYCODE_BACK; sleep 1; ui

tap "EQ" || fallo "no encuentro el botón del ecualizador"
sleep 2; ui
grep -q 'text="Preamplificación"' ui.xml || fallo "no se abrió el ecualizador"
tap "Graves" || fallo "no encuentro el preajuste Graves"
sleep 1
adb shell input keyevent KEYCODE_MEDIA_PLAY
sleep 4
adb exec-out screencap -p > ecualizador.png
cayo "con el ecualizador puesto"
e=$(estado); echo "con Graves: $e"
echo "$e" | grep -qE 'state=(PLAYING|3)|\(3\)' || fallo "dejó de sonar al poner el ecualizador ($e)"
adb shell input keyevent KEYCODE_MEDIA_PAUSE

# Cerrar del todo y volver: favorita y preajuste tienen que seguir.
sleep 2
adb shell am force-stop "$PKG"
adb logcat -c
adb shell am start -n "$PKG/.MainActivity"
for _ in $(seq 1 20); do sleep 2; ui; grep -q 'text="4 canciones' ui.xml && break; done
cayo "al volver a abrir"
# Inicio ofrece seguir con lo de la última vez.
grep -q 'text="Seguir escuchando"' ui.xml && grep -q 'text="Prueba Dos"' ui.xml || fallo "Inicio no ofrece «Seguir escuchando» con la última canción"
adb exec-out screencap -p > inicio2.png
tap "Ecualizador" || fallo "no encuentro el ecualizador en Inicio"
sleep 2; ui
# «Graves» sale siempre dos veces (chip y deslizador de tono); la tercera es el subtítulo con el preajuste activo.
[ "$(grep -o 'text="Graves"' ui.xml | wc -l)" -ge 3 ] || fallo "el preajuste Graves no se guardó"
adb shell input keyevent KEYCODE_BACK; sleep 1; ui
tap "Favoritas" || fallo "no encuentro la lista Favoritas"
sleep 2; ui
grep -q 'text="Prueba Dos"' ui.xml && ! grep -q 'text="Prueba Uno"' ui.xml || fallo "Favoritas no tiene justo la favorita"
adb exec-out screencap -p > favoritas.png
adb shell input keyevent KEYCODE_BACK; sleep 1

# Biblioteca: álbumes con portada en cuadrícula.
ui
tap "Biblioteca" || fallo "no encuentro la pestaña Biblioteca"
sleep 2; ui
grep -q 'text="Disco de Prueba"' ui.xml || fallo "Biblioteca no enseña el álbum «Disco de Prueba»"
adb exec-out screencap -p > biblioteca.png
tap "Inicio" || fallo "no encuentro la pestaña Inicio"
sleep 1

# FLAC de 96 kHz / 24 bits / mono con el ecualizador Graves: en coma flotante.
ui
busca tres96 "Prueba Tres"
tap "Prueba Tres" || fallo "no encuentro «Prueba Tres»"
sleep 5
adb exec-out screencap -p > tres.png
cayo "con el FLAC de 96 kHz"
e=$(estado); echo "96 kHz mono: $e"
echo "$e" | grep -qE 'state=(PLAYING|3)|\(3\)' || fallo "el FLAC de 96 kHz no suena ($e)"
adb logcat -d -s Surco:I > salida.txt || true
grep -q "Surco salida: .*coma flotante.*96000 Hz · 1 canales" salida.txt || { cat salida.txt | tail -5; fallo "el FLAC de 96 kHz no sale en coma flotante"; }

# Un MP3 (con pérdida) sigue en 16 bits: conserva el recorte de huecos del «sin cortes».
adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 2; ui
busca zorzal "Prueba Uno"
tap "Prueba Uno" || fallo "no encuentro «Prueba Uno»"
sleep 4
cayo "con el MP3"
adb logcat -d -s Surco:I > salida.txt || true
grep -q "Surco salida: 16 bits → 16 bits" salida.txt || { tail -5 salida.txt; fallo "el MP3 no va en 16 bits"; }

# Caso reservado: WAV en coma flotante a 192 kHz, estéreo, con el ecualizador puesto.
adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 2; ui
busca cuatro192 "Prueba Cuatro"
tap "Prueba Cuatro" || fallo "no encuentro «Prueba Cuatro»"
sleep 5
adb exec-out screencap -p > reservado.png
cayo "con el WAV de 192 kHz"
e=$(estado); echo "reservado WAV float 192 kHz: $e"
echo "$e" | grep -qE 'state=(PLAYING|3)|\(3\)' || fallo "el WAV de 192 kHz no suena ($e)"
adb logcat -d -s Surco:I > salida.txt || true
grep -q "Surco salida: coma flotante 32 bits → coma flotante 32 bits · 192000 Hz · 2 canales" salida.txt || { tail -5 salida.txt; fallo "el WAV de 192 kHz no sale en coma flotante"; }
adb logcat -d > logcat.txt || true
adb shell dumpsys media.audio_flinger > flinger.txt 2>/dev/null || true
grep -o "Surco salida: .*" salida.txt | sort -u || true

echo "Surco 2.1: biblioteca, pestañas, seguir escuchando, etiquetas, favorita, cola, ecualizador, persistencia, FLAC y WAV en coma flotante y MP3 en 16 bits sin caerse"
