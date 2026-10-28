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
# Un «no responde» del lanzador del emulador (pasa al arrancar en frío) se queda con el foco
# aunque el volcado enseñe Surco: lo escrito y los toques le llegan a él. «Atrás» lo cierra.
sin_anr() {
    local w p
    for _ in 1 2 3 4 5; do
        w=$(adb shell dumpsys window 2>/dev/null | grep -m1 "mCurrentFocus=.*Application Not Responding" || true)
        [ -n "$w" ] || return 0
        p=$(echo "$w" | sed -n 's/.*Application Not Responding: \([A-Za-z0-9._]*\).*/\1/p')
        echo "  (el sistema dice que «$p» no responde: se cierra; «Atrás» no quita ese diálogo)"
        if [ -n "$p" ] && [ "$p" != "$PKG" ]; then adb shell am force-stop "$p"; else adb shell input keyevent KEYCODE_BACK; fi
        sleep 2
    done
}
ui() {
    sin_anr
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
    [ -n "$1" ] || return 1  # un texto vacío casaría con cualquier nodo sin texto
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
tapedit() {  # pulsa el campo de texto (EditText), no su texto de ayuda: el toque sobre la ayuda a veces no da el foco
    local xy
    xy=$(python3 - <<'PY'
import re
xml = open("ui.xml", encoding="utf-8", errors="ignore").read()
for m in re.finditer(r'<node [^>]*class="android.widget.EditText"[^>]*>', xml):
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', m.group(0))
    if b:
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
# Los «no responde» de otras apps en segundo plano, sin diálogo (el lanzador del emulador los da
# al arrancar en frío y su diálogo se quedaba con el foco).
adb shell settings put global anr_show_background 0 || true
# El teclado en pantalla del emulador se cuelga a veces al arrancar en frío y, colgado, se traga
# lo escrito y también los toques (pasó en 3 de las últimas 5 pasadas). Sin él, «input text»
# llega como teclas físicas y los toques no esperan a nadie.
for m in $(adb shell ime list -s 2>/dev/null | tr -d '\r'); do adb shell ime disable "$m" >/dev/null 2>&1 || true; done
echo "teclados en pantalla activos: $(adb shell ime list -s 2>/dev/null | tr -d '\r' | wc -l)"
# La app habla inglés por defecto y español con el móvil en español: las pruebas, en español
# (idioma por app, Android 13+). El inglés lo recorre idioma-surco.sh.
adb shell cmd locale set-app-locales "$PKG" --locales es-ES
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
    # La búsqueda es una pantalla propia (el buscador de Inicio la abre). Se comprueba que se ha
    # abierto (un toque durante un cambio de diseño se pierde) y se pulsa el campo antes de
    # escribir (el foco automático no siempre llega a tiempo). Dos intentos de escritura.
    if ! grep -q 'content-desc="Atrás"' ui.xml; then
        for _ in 1 2 3; do
            tap "Buscar en cualquier etiqueta" || true
            sleep 2; ui
            grep -q 'content-desc="Atrás"' ui.xml && break
        done
    fi
    # El teclado del emulador tarda en estar listo (más con el análisis de BPM en marcha): se
    # espera a que el campo tenga el foco y, si lo escrito no llega, se repite con más calma.
    for intento in 1 2 3; do
        ui; tapedit || tap "Buscar en cualquier etiqueta" || tap "$prev" || true
        for _ in 1 2 3 4 5; do sleep 1; ui; grep -q 'class="android.widget.EditText"[^>]*focused="true"' ui.xml && break; done
        sleep $intento
        adb shell input keyevent KEYCODE_MOVE_END
        for _ in $(seq 1 20); do adb shell input keyevent KEYCODE_DEL; done
        adb shell input text "$1"
        sleep 2
        ui
        grep -q "class=\"android.widget.EditText\"[^>]*text=\"$1\"\|text=\"$1\"[^>]*class=\"android.widget.EditText\"" ui.xml || echo "  (lo escrito no llegó al buscador, intento $intento)"
        if grep -q "text=\"$2\"" ui.xml && grep -qi 'text="1 canci.n encontrada"' ui.xml; then
            echo "buscar «$1» → $2"
            prev=$1
            return 0
        fi
    done
    # El teclado del emulador a veces no entrega lo escrito (sobre todo en arranque en frío): lo
    # que se prueba aquí es lo que encuentra la búsqueda, así que se le pasa el texto como lo
    # haría la búsqueda del sistema (el acceso directo «Buscar» con el texto puesto).
    echo "  (el teclado no escribe: búsqueda con el texto ya puesto)"
    # Diagnóstico: quién tiene el foco y si el sistema ve algo colgado.
    adb shell dumpsys window 2>/dev/null | grep -E "mCurrentFocus|mFocusedApp|mInputMethodTarget" | head -4 || true
    adb shell dumpsys input 2>/dev/null | grep -iE "FocusedWindow|FocusedApplication|anr" | head -6 || true
    adb logcat -d 2>/dev/null | grep -E "ANR in|Input dispatching timed out|not responding|InputDispatcher" | tail -8 || true
    adb shell am start -n "$PKG/.MainActivity" -a com.eddie.surco.action.SEARCH -e query "$1" >/dev/null
    for _ in 1 2 3 4 5; do
        sleep 2; ui
        if grep -q "text=\"$2\"" ui.xml && grep -qi 'text="1 canci.n encontrada"' ui.xml; then
            echo "buscar «$1» → $2"
            prev=$1
            return 0
        fi
    done
    echo "::error::buscar «$1» no da «$2»: jaudiotagger no leyó las etiquetas (¿R8?)"
    grep -o 'text="[^"]*"' ui.xml | head -30 || true
    exit 1
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
st=$(estado)
echo "sesión multimedia: $st (3 = sonando)"
if echo "$st" | grep -q "ERROR"; then
    # El porqué del error de reproducción, en el registro del CI (el artefacto no siempre se baja).
    adb logcat -d 2>/dev/null | grep -E "ExoPlayer|PlaybackException|MediaCodec|AudioTrack|SurcoAudio|Surco" | grep -E " E | W " | tail -40 || true
    fallo "«Prueba Dos» no suena: la sesión está en error"
fi

# --- Surco 2.5: notificación de música con botones propios (favorita y aleatorio) ---
# En pausa: con música la persiana tarda en volcarse y «Prueba Dos» (20 s) se acabaría antes de
# seguir. La notificación sigue ahí en pausa.
adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1
notif() { adb shell dumpsys notification --noredact 2>/dev/null | tr -d '\r' || true; }
for _ in 1 2 3 4 5; do notif > notif.txt; grep -q 'Marcar favorita\|Quitar de favoritas' notif.txt && break; sleep 1; done
grep -q 'Marcar favorita' notif.txt || { grep -n -A12 'pkg=com.eddie.surco' notif.txt | head -60; fallo "la notificación no lleva el botón «Marcar favorita»"; }
grep -q '"Activar aleatorio"' notif.txt || fallo "la notificación no lleva el botón «Activar aleatorio»"
echo "notificación: con «Marcar favorita» y «Activar aleatorio»"
# Pulsarlo de verdad en la persiana: el aleatorio se activa y el botón cambia. Con los ajustes
# rápidos desplegados: plegado, el reproductor solo enseña anterior, reproducir y siguiente.
adb shell cmd statusbar expand-settings; sleep 2; ui
if tap "Activar aleatorio"; then
    ok=""
    for _ in 1 2 3 4 5; do sleep 1; notif > notif.txt; grep -q '"Desactivar aleatorio"' notif.txt && { ok=1; break; }; done
    [ -n "$ok" ] || fallo "pulsar «Activar aleatorio» en la notificación no lo activó"
    echo "notificación: «Activar aleatorio» pulsado → «Desactivar aleatorio»"
    ui; tap "Desactivar aleatorio" || true
    for _ in 1 2 3 4 5; do sleep 1; notif > notif.txt; grep -q '"Activar aleatorio"' notif.txt && break; done
    grep -q '"Activar aleatorio"' notif.txt || fallo "el aleatorio no se quitó desde la notificación"
else
    echo "::warning::la persiana no enseña el botón «Activar aleatorio» (no se pulsa; la notificación sí lo lleva)"
    grep -o 'content-desc="[^"]*"' ui.xml | head -30 || true
fi
adb shell cmd statusbar collapse; sleep 1

# --- Surco 2 --- (en pausa para poder volcar la interfaz; la tecla multimedia
# pasa por la sesión, así que también prueba el fundido de pausa)
adb shell input keyevent KEYCODE_MEDIA_PAUSE
sleep 2
ui
tap "Favorita" || fallo "no hay corazón de favorita en el minirreproductor"
sleep 1; ui
# Aviso propio (no un Toast): dice lo que ha hecho y deja deshacerlo.
grep -q 'text="Añadida a favoritas"' ui.xml && grep -q 'text="Deshacer"' ui.xml || fallo "no sale el aviso «Añadida a favoritas» con «Deshacer»"
adb exec-out screencap -p > aviso.png
tap "Deshacer" || fallo "no encuentro «Deshacer» en el aviso"
sleep 1; ui
grep -q 'text="Añadida a favoritas"' ui.xml && fallo "el aviso no se fue al pulsar «Deshacer»"
# Si «Deshacer» la quitó, marcarla otra vez vuelve a decir «Añadida».
tap "Favorita" || fallo "no hay corazón de favorita en el minirreproductor"
sleep 1; ui
grep -q 'text="Añadida a favoritas"' ui.xml || fallo "«Deshacer» no quitó la favorita"
echo "aviso: «Añadida a favoritas» → Deshacer → otra vez «Añadida»"
# El aviso se va solo (8 s con acción).
for _ in $(seq 1 14); do sleep 1; ui; grep -q 'text="Deshacer"' ui.xml || break; done
grep -q 'text="Deshacer"' ui.xml && fallo "el aviso no se fue solo"
tap "Otra Banda" || fallo "no encuentro el minirreproductor"
sleep 2; ui
grep -q 'text="SONANDO"' ui.xml || fallo "no se abrió «Sonando»"
grep -q 'content-desc="Quitar de favoritas"' ui.xml || fallo "la favorita no quedó marcada"
# «Sonando» limpia por defecto: sin líneas técnicas y con pocos mandos.
grep -q 'text="FLAC · 16 bits' ui.xml && fallo "«Sonando» enseña los detalles técnicos sin pedirlos"
n=$(grep -o 'clickable="true"' ui.xml | wc -l)
echo "«Sonando»: $n elementos tocables (antes 16)"
[ "$n" -le 12 ] || fallo "«Sonando» tiene $n elementos tocables (máximo 12)"
adb exec-out screencap -p > sonando-limpia.png
# Los detalles técnicos se piden en Ajustes → Interfaz.
tap "Cerrar" || fallo "no encuentro «Cerrar» en «Sonando»"
sleep 1
for _ in 1 2 3; do ui; grep -q 'content-desc="Ajustes"' ui.xml && break; adb shell input keyevent KEYCODE_BACK; sleep 1; done
tap "Ajustes" || fallo "no encuentro Ajustes en Inicio"
sleep 2; ui
tap "Interfaz" || fallo "no encuentro la sección Interfaz en Ajustes"
sleep 2; ui
tap "Detalles técnicos" || fallo "no encuentro «Detalles técnicos» en Interfaz"
sleep 1; ui
python3 - <<'PY' || fallo "«Detalles técnicos» no se activó"
import sys, xml.etree.ElementTree as ET
root = ET.parse("ui.xml").getroot()
# La fila entera es el interruptor: el nodo marcable que contiene el título está marcado.
ok = any(n.get("checkable") == "true" and n.get("checked") == "true" and any(c.get("text") == "Detalles técnicos" for c in n.iter("node"))
         for n in root.iter("node"))
sys.exit(0 if ok else 1)
PY
adb exec-out screencap -p > interfaz.png
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 1; ui
tap "Otra Banda" || fallo "no encuentro el minirreproductor"
sleep 2; ui
grep -q 'text="FLAC · 16 bits' ui.xml || fallo "«Sonando» no enseña la calidad del archivo con los detalles técnicos puestos"
# Un FLAC se decodifica en coma flotante y llega así al sistema (no se recorta a 16 bits).
grep -q 'text="Salida: [^"]*coma flotante' ui.xml || fallo "«Sonando» no dice que el FLAC sale en coma flotante"
adb exec-out screencap -p > sonando2.png

tap "Cola" || fallo "no encuentro el botón de la cola"
sleep 2; ui
# «Vaciar» solo existe en la hoja de la cola. Desde la búsqueda suena su disco entero (2 canciones).
grep -q 'text="Vaciar"' ui.xml && grep -q 'text="2 canciones · 0 min"' ui.xml && grep -q 'text="Prueba Dos"' ui.xml || fallo "la cola no se abrió o no lleva la canción"
adb exec-out screencap -p > cola.png
adb shell input keyevent KEYCODE_BACK; sleep 1; ui

tap "Más" || fallo "no encuentro «Más» en «Sonando»"
sleep 2; ui
tap "Ecualizador" || fallo "no encuentro el ecualizador en el menú de «Sonando»"
sleep 2; ui
grep -q 'text="Ecualizador"' ui.xml && grep -q 'text="Graves y agudos"' ui.xml || fallo "no se abrió el ecualizador"
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
# La última canción vuelve preparada en el minirreproductor (la tarjeta «Seguir escuchando» solo
# sale cuando no hay nada cargado: con las dos a la vez se veía la misma canción dos veces).
grep -q 'content-desc="Reproducir o pausar"' ui.xml && grep -q 'text="Prueba Dos"' ui.xml || fallo "al volver a abrir no está la última canción en el minirreproductor"
adb exec-out screencap -p > inicio2.png
tap "Ajustes" || fallo "no encuentro Ajustes en Inicio"
sleep 2; ui
tap "Sonido" || fallo "no encuentro la sección Sonido en Ajustes"
sleep 2; ui
tap "Ecualizador y efectos" || fallo "no encuentro el ecualizador en Ajustes → Sonido"
sleep 2; ui
# El subtítulo bajo «Ecualizador» es el preajuste activo (el chip «Graves» está siempre).
sub=$(grep -o 'text="[^"]*"' ui.xml | grep -v '^text=""$' | grep -A1 '^text="Ecualizador"$' | sed -n 2p || true)
[ "$sub" = 'text="Graves"' ] || fallo "el preajuste Graves no se guardó (subtítulo: $sub)"
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell input keyevent KEYCODE_BACK; sleep 1
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

# --- Accesos directos del icono: «Buscar» abre el buscador y «Mezclar todo» pone todo en aleatorio. ---
adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1
adb shell am start -a com.eddie.surco.action.SEARCH -n "$PKG/.MainActivity" >/dev/null; sleep 3; ui
grep -q 'class="android.widget.EditText"' ui.xml || fallo "el acceso directo «Buscar» no abrió el buscador"
adb shell input keyevent KEYCODE_BACK; sleep 1
adb shell am start -a com.eddie.surco.action.SHUFFLE_ALL -n "$PKG/.MainActivity" >/dev/null; sleep 5
e=$(estado); echo "acceso directo «Mezclar todo»: $e"
echo "$e" | grep -qE 'state=(PLAYING|3)|\(3\)' || fallo "el acceso directo «Mezclar todo» no puso música ($e)"
cayo "con los accesos directos"

# --- Mosaico de ajustes rápidos (con la música de «Mezclar todo» sonando): pulsarlo pausa y
# volver a pulsarlo reanuda. ---
TILE="$PKG/.player.PlayTile"
adb shell cmd statusbar add-tile "$TILE" >/dev/null 2>&1 || true
sleep 1
adb shell cmd statusbar click-tile "$TILE"; sleep 3
e=$(estado); echo "tras pulsar el mosaico: $e"
echo "$e" | grep -qE 'state=(PAUSED|2)|\(2\)' || fallo "el mosaico no pausó ($e)"
adb shell cmd statusbar click-tile "$TILE"; sleep 3
e=$(estado); echo "tras pulsarlo otra vez: $e"
echo "$e" | grep -qE 'state=(PLAYING|3)|\(3\)' || fallo "el mosaico no reanudó ($e)"
adb shell cmd statusbar collapse; adb shell cmd statusbar remove-tile "$TILE" >/dev/null 2>&1 || true
adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 2
cayo "con el mosaico"
# «Mezclar todo» deja «Sonando» abierta: se cierra para lo siguiente.
ui; tap "Cerrar" || adb shell input keyevent KEYCODE_BACK; sleep 1

# --- Editor de etiquetas: cambiar el título de «Prueba Tres» (FLAC) con el permiso del sistema
# («Allow»), comprobarlo en MediaStore y dejarlo como estaba. ---
mstitulo() { adb shell content query --uri content://media/external/audio/media --projection title 2>/dev/null | tr -d '\r'; }
edita() {  # $1 = teclas para el título (texto a añadir o borrados); $2 = título esperado
    ui; busca tres96 "$3" || return 1
    tap "Más opciones" || fallo "no encuentro «Más opciones» en la búsqueda"
    sleep 2
    adb shell input swipe 540 1800 540 500 300; sleep 1; ui  # la hoja entera: el chip puede quedar abajo
    tap "Editar etiquetas" || fallo "no hay «Editar etiquetas» en el menú de la canción"
    sleep 2; ui
    tapedit || fallo "no encuentro el campo del título en el editor"
    sleep 1
    adb shell input keyevent KEYCODE_MOVE_END
    eval "$1"
    sleep 1; adb shell input keyevent KEYCODE_BACK; sleep 1; ui  # cierra el teclado
    tap "Guardar" || fallo "no encuentro «Guardar» en el editor"
    ok=""
    # Sale el permiso del sistema («Allow») o, si ya se dio, se guarda sin preguntar.
    for _ in $(seq 1 10); do
        sleep 1; ui
        if tap "Allow"; then ok=1; break; fi
        grep -q 'text="Etiquetas guardadas"' ui.xml && { ok=1; break; }
        mstitulo | grep -q "title=$2$\|title=$2," && { ok=1; break; }
    done
    [ -n "$ok" ] || fallo "no salió el permiso del sistema para escribir el archivo"
    for _ in $(seq 1 15); do sleep 1; mstitulo | grep -q "title=$2$\|title=$2," && return 0; done
    mstitulo | head -8
    fallo "MediaStore no tiene el título «$2» tras guardar"
}
adb shell am start -n "$PKG/.MainActivity" >/dev/null; sleep 2
edita 'adb shell input text "%sEditada"' "Prueba Tres Editada" "Prueba Tres"
echo "editor de etiquetas: «Prueba Tres» → «Prueba Tres Editada» en el archivo y en MediaStore"
adb shell input keyevent KEYCODE_BACK; sleep 1
edita 'for _ in 1 2 3 4 5 6 7 8; do adb shell input keyevent KEYCODE_DEL; done' "Prueba Tres" "Prueba Tres Editada"
echo "editor de etiquetas: vuelta a «Prueba Tres»"
cayo "con el editor de etiquetas"

echo "Surco 2.1: biblioteca, pestañas, seguir escuchando, etiquetas, favorita, cola, ecualizador, persistencia, FLAC y WAV en coma flotante y MP3 en 16 bits sin caerse"
