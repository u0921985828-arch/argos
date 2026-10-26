#!/usr/bin/env bash
# Recorrido de diseño: cada pantalla de Surco, con captura y volcado de la interfaz, para que
# .github/scripts/diseno-surco.py mida lo que se puede medir (tamaño de lo que se toca, contraste,
# solapes, texto que se sale) y una persona (o un revisor) mire el resto.
#
# Va DESPUÉS de smoke-surco.sh: aprovecha su estado (4 canciones, una favorita, escuchas,
# ecualizador en Graves). No falla por diseño; solo si no puede llegar a una pantalla.
#   $1 = carpeta de salida (capturas)   $2 = escala de letra (1.0 por defecto)
set -uo pipefail
OUT=${1:-capturas}
ESCALA=${2:-1.0}
PKG=com.eddie.surco
mkdir -p "$OUT"
adb shell wm density | tail -1 | grep -oE '[0-9]+$' > "$OUT/densidad.txt"
echo "$ESCALA" > "$OUT/escala.txt"

ui() {
    : > ui.xml
    for _ in 1 2 3 4 5; do
        adb shell rm -f /sdcard/ui.xml
        adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 || true
        adb exec-out cat /sdcard/ui.xml > ui.xml 2>/dev/null || true
        if grep -q '<hierarchy' ui.xml; then
            if grep -q "isn't responding" ui.xml; then tap "Wait" || true; sleep 3; continue; fi
            return 0
        fi
        sleep 1
    done
}
tap() {
    local xy
    [ -n "$1" ] || return 1
    xy=$(python3 - "$1" "${2:-1}" <<'PY'
import re, sys
xml = open("ui.xml", encoding="utf-8", errors="ignore").read()
k = 0
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n)
    c = re.search(r' content-desc="([^"]*)"', n)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if b and ((t and t.group(1) == sys.argv[1]) or (c and c.group(1) == sys.argv[1])):
        k += 1
        if k < int(sys.argv[2]): continue
        x1, y1, x2, y2 = map(int, b.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
    [ -n "$xy" ] || { echo "  (no encuentro «$1»)"; return 1; }
    adb shell input tap $xy
}
tapre() {  # primer nodo cuyo texto empieza por $1
    local xy
    xy=$(python3 - "$1" <<'PY'
import re, sys
xml = open("ui.xml", encoding="utf-8", errors="ignore").read()
for m in re.finditer(r'<node [^>]*>', xml):
    n = m.group(0)
    t = re.search(r' text="([^"]*)"', n)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
    if b and t and t.group(1).startswith(sys.argv[1]):
        x1, y1, x2, y2 = map(int, b.groups())
        print((x1 + x2) // 2, (y1 + y2) // 2)
        break
PY
)
    [ -n "$xy" ] || { echo "  (no encuentro «$1…»)"; return 1; }
    adb shell input tap $xy
}
n=0
cap() {  # $1 = nombre de la pantalla
    n=$((n + 1))
    local f
    f=$(printf '%s/%02d-%s' "$OUT" "$n" "$1")
    sleep 1; ui
    cp ui.xml "$f.xml"
    adb exec-out screencap -p > "$f.png"
    echo "  $f"
}
back() { adb shell input keyevent KEYCODE_BACK; sleep 1; }
pausa() { adb shell input keyevent KEYCODE_MEDIA_PAUSE; sleep 1; }

echo "== recorrido de diseño (letra ×$ESCALA) → $OUT"
adb shell settings put system font_scale "$ESCALA"
pausa
adb shell am force-stop "$PKG"
adb shell am start -n "$PKG/.MainActivity" >/dev/null
for _ in $(seq 1 20); do sleep 2; ui; grep -q 'text="4 canciones' ui.xml && break; done

cap inicio
adb shell input swipe 540 1700 540 600 400; cap inicio-abajo
adb shell input swipe 540 600 540 1900 300; sleep 1; ui

tap "Buscar en cualquier etiqueta"; sleep 2; cap buscar-vacio
adb shell input text "prueba"; sleep 2; back; cap buscar-resultados
back; ui; grep -q 'content-desc="Atrás"' ui.xml && back

ui; tap "Biblioteca"; sleep 2; cap biblioteca-albumes
tapre "Canciones"; cap biblioteca-canciones
tap "Más opciones"; sleep 1; cap menu-cancion
back
# Aviso propio: el corazón del minirreproductor saca la franja con «Deshacer» (dura 8 s).
ui; tap "Favorita"; cap aviso
ui; tap "Deshacer" || true
for _ in $(seq 1 10); do ui; grep -q 'text="Deshacer"\|text="Quitada de favoritas"\|text="Añadida a favoritas"' ui.xml || break; sleep 1; done
ui; tapre "Artistas"; cap biblioteca-artistas
ui; tapre "Carpetas"; cap biblioteca-carpetas
ui; tapre "Álbumes"; sleep 1; ui; tap "Disco de Prueba"; sleep 2; cap album
ui; tap "Ordenar"; cap album-ordenar; back

ui; tap "Etiquetas"; sleep 2; cap etiquetas
adb shell input swipe 540 1700 540 600 400; cap etiquetas-abajo
adb shell input swipe 540 600 540 1900 300; sleep 1; ui; tap "Género" || tap "Década"; sleep 2; cap grupos
ui; tap "Rock" || true; sleep 2; cap grupo-canciones

ui; tap "Listas"; sleep 2; cap listas
ui; tap "Lista nueva"; sleep 2; cap lista-nueva
# El primer «atrás» cierra el teclado; el segundo, la hoja.
back; ui; grep -q 'text="Crear"' ui.xml && back
ui; tap "Favoritas"; sleep 2; cap favoritas
back

ui; tap "Inicio"; sleep 1; ui; tap "Ajustes"; sleep 2; cap ajustes
ui; tap "Sonido"; sleep 2; cap ajustes-sonido
ui; tap "Ecualizador y efectos"; sleep 2; cap ecualizador
adb shell input swipe 540 1700 540 600 400; cap ecualizador-abajo
back; back
ui; tap "Reproducción"; sleep 2; cap ajustes-reproduccion
ui; tap "4 s"; sleep 1; cap ajustes-fundido
ui; tap "No"; sleep 1; back
ui; tap "Carpetas, letras de internet y audios cortos"; sleep 2; cap ajustes-biblioteca; back
ui; tap "Interfaz"; sleep 2; cap ajustes-interfaz
if [ "$ESCALA" != "1.0" ]; then
    # Caso reservado: el acento elegido en la pasada anterior (Violeta) tiene que seguir tras reiniciar.
    python3 - <<'PY' || { echo "::error::el acento Violeta no se conservó al reiniciar"; exit 1; }
import sys, xml.etree.ElementTree as ET
r = ET.parse("ui.xml").getroot()
# Rol de botón de opción: Compose lo publica como checked, no como selected.
sys.exit(0 if any(n.get("content-desc") == "Violeta" and "true" in (n.get("checked"), n.get("selected")) for n in r.iter("node")) else 1)
PY
    echo "  acento Violeta conservado"
fi
# La prueba de humo deja puestos los detalles técnicos: se quitan para capturar «Sonando» como sale por defecto.
python3 - <<'PY' && { tap "Detalles técnicos"; sleep 1; echo "  detalles técnicos quitados"; }
import sys, xml.etree.ElementTree as ET
r = ET.parse("ui.xml").getroot()
sys.exit(0 if any(n.get("checked") == "true" and any(c.get("text") == "Detalles técnicos" for c in n.iter("node")) for n in r.iter("node")) else 1)
PY
adb shell input swipe 540 1700 540 600 400; cap ajustes-interfaz-abajo; back
ui; tap "Tus escuchas"; sleep 2; cap ajustes-escuchas; back
ui; tap "Acerca de Surco"; sleep 2; cap ajustes-acerca; back
back
ui; tap "Inicio"; sleep 1

# «Sonando»: desde el minirreproductor (toca el título de la canción preparada).
ui
mini=$(python3 - <<'PY'
import re
xml = open("ui.xml", encoding="utf-8", errors="ignore").read()
nodes = [(re.search(r' text="([^"]*)"', m.group(0)), re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', m.group(0))) for m in re.finditer(r'<node [^>]*>', xml)]
# El minirreproductor está justo encima de la barra inferior: el texto más bajo por encima de «Inicio».
nav = max((int(b.group(2)) for t, b in nodes if t and b and t.group(1) == "Inicio"), default=0)
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
adb shell input swipe 540 1700 540 600 400; cap sonando-abajo
ui; tap "Letra"; sleep 2; cap letra
ui; tap "Letra"; sleep 1
ui; tap "Cola"; sleep 2; cap cola
back
ui; tap "Más"; sleep 2; cap sonando-mas
ui; tap "Temporizador"; sleep 1; cap temporizador
back
ui; tap "Más"; sleep 2; ui; tap "Velocidad"; sleep 1; cap velocidad
back
ui; tap "Cerrar"; sleep 1
if [ "$ESCALA" = "1.0" ]; then
    # Para la pasada siguiente (caso reservado): acento Violeta, que tiene que sobrevivir al reinicio.
    ui; tap "Ajustes"; sleep 2; ui; tap "Interfaz"; sleep 2; ui; tap "Violeta"; sleep 2
    adb exec-out screencap -p > "$OUT/acento-violeta.png"
    back; back
fi

# Sin permiso: la pantalla de bienvenida.
adb shell am force-stop "$PKG"
adb shell pm revoke "$PKG" android.permission.READ_MEDIA_AUDIO
adb shell am start -n "$PKG/.MainActivity" >/dev/null
sleep 4
# El diálogo del sistema tapa la pantalla: se rechaza para ver la de Surco.
ui; tap "Don’t allow" || tap "Don't allow" || tap "No permitir" || true
sleep 2; cap permiso
adb shell pm grant "$PKG" android.permission.READ_MEDIA_AUDIO
adb shell settings put system font_scale 1.0
echo "== $n pantallas"
