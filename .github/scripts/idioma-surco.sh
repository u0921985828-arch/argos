#!/usr/bin/env bash
# Surco en inglés (el idioma por defecto para todo el mundo que no sea español): pone la app en
# inglés con el idioma por app (Android 13+), recorre las pantallas principales, deja capturas
# para diseno-surco.py y falla si queda algún texto en español de la interfaz. Al acabar, vuelve
# a español. Se lanza después de la prueba de humo (la biblioteca ya está leída).
set -uo pipefail
OUT=${1:-capturas-en}
PKG=com.eddie.surco
mkdir -p "$OUT"
n=0

ui() {
    : > ui.xml
    for _ in 1 2 3 4 5; do
        adb shell rm -f /sdcard/ui.xml
        adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
        adb exec-out cat /sdcard/ui.xml > ui.xml 2>/dev/null || true
        grep -q '<hierarchy' ui.xml && return 0
        sleep 1
    done
}
tap() {
    local xy
    xy=$(python3 - "$1" <<'PY'
import re, sys
xml = open("ui.xml", encoding="utf-8", errors="ignore").read()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n); c = re.search(r' content-desc="([^"]*)"', n)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if b and ((t and t.group(1) == sys.argv[1]) or (c and c.group(1) == sys.argv[1])):
        x1, y1, x2, y2 = map(int, b.groups()); print((x1 + x2) // 2, (y1 + y2) // 2); break
PY
)
    [ -n "$xy" ] || return 1
    adb shell input tap $xy
}
cap() {
    n=$((n + 1))
    local f
    f=$(printf '%s/%02d-%s' "$OUT" "$n" "$1")
    sleep 1; ui
    cp ui.xml "$f.xml"
    adb exec-out screencap -p > "$f.png"
    echo "  $f"
}
back() { adb shell input keyevent KEYCODE_BACK; sleep 1; }
fallo() { echo "::error::$1"; adb shell cmd locale set-app-locales "$PKG" --locales es-ES; exit 1; }

echo "== Surco en inglés → $OUT"
adb shell input keyevent KEYCODE_MEDIA_PAUSE
adb shell settings put system font_scale 1.0
adb shell cmd locale set-app-locales "$PKG" --locales en-US
adb shell am force-stop "$PKG"
adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 5

cap inicio
ui; tap "Library" || fallo "no hay pestaña «Library» (¿la barra inferior sigue en español?)"; sleep 2; cap biblioteca
ui; tap "Songs" || true; sleep 2; cap canciones
ui; tap "More options" && { sleep 1; cap menu-cancion; back; }
ui; tap "Tags" && { sleep 2; cap etiquetas; }
ui; tap "Playlists" && { sleep 2; cap listas; }
ui; tap "Home"; sleep 2; ui; tap "Settings" && { sleep 2; cap ajustes; back; }

# «Now playing» desde el minirreproductor: el texto más bajo por encima de «Home».
ui
mini=$(python3 - <<'PY'
import re
xml = open("ui.xml", encoding="utf-8", errors="ignore").read()
nodes = [(re.search(r' text="([^"]*)"', m.group(0)), re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', m.group(0))) for m in re.finditer(r'<node [^>]*>', xml)]
nav = max((int(b.group(2)) for t, b in nodes if t and b and t.group(1) == "Home"), default=0)
best = None
for t, b in nodes:
    if t and b and t.group(1) and int(b.group(4)) < nav - 20:
        y = int(b.group(4))
        if best is None or y > best[0]: best = (y, (int(b.group(1)) + int(b.group(3))) // 2, (int(b.group(2)) + int(b.group(4))) // 2)
if best: print(best[1], best[2])
PY
)
[ -n "$mini" ] && adb shell input tap $mini
sleep 2; cap sonando
ui; tap "More" && { sleep 2; cap sonando-mas; back; }
ui; tap "Close" || back

# Ni una palabra de la interfaz en español. (Los títulos de las canciones de prueba sí lo son:
# «Prueba Uno», «Disco de Prueba»… y se ignoran.)
python3 - "$OUT" <<'PY' || fallo "quedan textos en español con la app en inglés (arriba)"
import glob, re, sys, xml.etree.ElementTree as ET
ES = ["Inicio", "Biblioteca", "Etiquetas", "Listas", "Ajustes", "Canciones", "Álbumes", "Artistas",
      "Carpetas", "Buscar", "Reproducir", "Aleatorio", "Siguiente", "Anterior", "Favorita", "Cola",
      "Letra", "Sonando", "SONANDO", "Cerrar", "Más", "canciones", "canción", "Mezclar", "Buenas",
      "Buenos", "Sonido", "Reproducción", "Interfaz", "Acerca", "Temporizador", "Velocidad", "Lista",
      "Añadir", "Quitar", "Desconocido", "Género", "Década", "Tonalidad", "Estado de ánimo", "Ordenar"]
DATOS = re.compile(r"Prueba|Banda|Disco de Prueba|surco|sellonorte|zorzal", re.I)
malos = set()
for xf in sorted(glob.glob(sys.argv[1] + "/*.xml")):
    for n in ET.parse(xf).getroot().iter("node"):
        if n.get("package") != "com.eddie.surco": continue
        for t in (n.get("text") or "", n.get("content-desc") or ""):
            if not t or DATOS.search(t): continue
            for w in ES:
                if re.search(r"(^|[^\wÁÉÍÓÚáéíóúñÑ])" + re.escape(w) + r"($|[^\wÁÉÍÓÚáéíóúñÑ])", t):
                    malos.add(f"{xf.split('/')[-1]}: «{t}» ({w})")
for m in sorted(malos): print("  ES", m)
print(f"textos en español con la app en inglés: {len(malos)}")
sys.exit(1 if malos else 0)
PY

adb shell cmd locale set-app-locales "$PKG" --locales es-ES
adb shell am force-stop "$PKG"
echo "== $n pantallas en inglés"
