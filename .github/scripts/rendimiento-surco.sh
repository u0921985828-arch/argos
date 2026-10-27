#!/usr/bin/env bash
# Surco con una biblioteca grande (10.000 canciones más): cuánto tarda en arrancar y en leerla,
# cuánta memoria usa y cuántos fotogramas pierde al desplazar la lista. Falla si se cae, se queda
# colgada (ANR) o no llega a enseñar todas; los tiempos se apuntan en rendimiento.txt (el
# emulador del CI pinta por software: los fotogramas sirven para comparar entre versiones, no
# como cifra de un móvil).
# Uso: rendimiento-surco.sh mil.tar N
set -uo pipefail
TAR=$1
N=$2
PKG=com.eddie.surco
OUT=rendimiento.txt
: > "$OUT"
nota() { echo "$1" | tee -a "$OUT"; }
fallo() { echo "::error::$1"; cat "$OUT"; exit 1; }
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
problemas() {
    adb logcat -d > rendimiento-logcat.txt 2>/dev/null || true
    grep -E "FATAL EXCEPTION|ANR in $PKG" rendimiento-logcat.txt | head -5
}
cuenta() { adb shell content query --uri content://media/external/audio/media --projection _id 2>/dev/null | grep -c 'Row:' || true; }
arranque() { adb shell am start -W -n "$PKG/.MainActivity" 2>/dev/null | tr -d '\r' | sed -n 's/^TotalTime: //p'; }

echo "== rendimiento con $N canciones más"
adb shell input keyevent KEYCODE_MEDIA_PAUSE
adb shell am force-stop "$PKG"
antes=$(cuenta)
adb push "$TAR" /sdcard/mil.tar >/dev/null || fallo "no se pudo copiar la biblioteca grande"
adb shell 'mkdir -p /sdcard/Music/mil && cd /sdcard/Music/mil && tar -xf /sdcard/mil.tar && rm /sdcard/mil.tar' || fallo "no se pudo descomprimir"
t0=$(date +%s)
adb shell content call --uri content://media/ --method scan_volume --arg external_primary >/dev/null 2>&1 || true
total=$((antes + N))
for _ in $(seq 1 120); do
    c=$(cuenta); [ "${c:-0}" -ge "$total" ] && break; sleep 5
done
nota "MediaStore: $c canciones (esperadas $total) en $(( $(date +%s) - t0 )) s"
[ "${c:-0}" -ge "$total" ] || fallo "MediaStore no llegó a ver las $total canciones"

# Primer arranque con la biblioteca nueva: arranca y lee las etiquetas de todo.
adb logcat -c
ms=$(arranque)
nota "arranque en frío (biblioteca sin leer): ${ms:-?} ms"
t0=$(date +%s)
listo=""
for _ in $(seq 1 200); do
    sleep 3
    [ -n "$(problemas)" ] && break
    ui
    if grep -qE "text=\"$total |text=\"${total:0:${#total}-3}[.,]${total: -3} " ui.xml && ! grep -qi 'Leyendo etiquetas' ui.xml; then listo=1; break; fi
done
p=$(problemas); [ -n "$p" ] && { echo "$p"; fallo "Surco se cayó o se colgó leyendo $total canciones"; }
nota "lectura de etiquetas de $total canciones: $(( $(date +%s) - t0 )) s"
sleep 2; adb logcat -d > rendimiento-logcat.txt 2>/dev/null || true
nota "  $(grep -m1 "SurcoScan" rendimiento-logcat.txt | sed 's/^.*SurcoScan: //')"
[ -n "$listo" ] || { grep -o 'text="[^"]*"' ui.xml | head -20; fallo "Inicio no llegó a enseñar las $total canciones"; }

# Segundo arranque: ya con la caché (lo normal cada día).
adb shell am force-stop "$PKG"; sleep 2
ms=$(arranque)
nota "arranque en frío (con caché): ${ms:-?} ms"
sleep 6

# Desplazar la lista de canciones entera.
ui
tap "Biblioteca" || tap "Library" || fallo "no encuentro la pestaña Biblioteca"
sleep 2; ui
tap "Canciones" || tap "Songs" || true
sleep 3
adb shell dumpsys gfxinfo "$PKG" reset >/dev/null
for _ in $(seq 1 15); do adb shell input swipe 540 1900 540 500 120; done
sleep 2
adb shell dumpsys gfxinfo "$PKG" | tr -d '\r' > gfxinfo.txt
nota "desplazar 15 veces la lista: $(grep -m1 'Total frames rendered' gfxinfo.txt | sed 's/^ *//'), $(grep -m1 'Janky frames:' gfxinfo.txt | sed 's/^ *//'), $(grep -m1 '90th percentile' gfxinfo.txt | sed 's/^ *//'), $(grep -m1 '99th percentile' gfxinfo.txt | sed 's/^ *//')"
nota "memoria: $(adb shell dumpsys meminfo "$PKG" | tr -d '\r' | grep -m1 -E 'TOTAL PSS|TOTAL:' | sed 's/^ *//')"
p=$(problemas); [ -n "$p" ] && { echo "$p"; fallo "Surco se cayó o se colgó al desplazar $total canciones"; }
nota "sin caídas ni ANR"
