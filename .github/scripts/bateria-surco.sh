#!/usr/bin/env bash
# Lo que gasta Surco (androidTest/BatteryTest.kt): procesador del proceso en pausa y sonando un
# MP3 y un FLAC 24/96 de 3 min, con la pantalla apagada y encendida y con el ecualizador. El
# emulador no tiene batería de verdad: el procesador es lo que se compara entre versiones. Va al
# final, con el APK de depuración que deja auto-surco.sh, compilado entero antes (como en Play).
set -euo pipefail
PKG=com.eddie.surco
D="/sdcard/Android/data/$PKG/files/bateria"
adb shell mkdir -p "$D"
adb push bateria.mp3 bateria.flac "$D/" >/dev/null
adb shell cmd package compile -m speed -f "$PKG" >/dev/null || true
adb shell dumpsys battery unplug || true
adb logcat -c
adb shell am instrument -w -e class "$PKG.BatteryTest" "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > bateria-auto.txt 2>&1 || true
adb shell dumpsys battery reset || true
adb logcat -d | grep "SurcoBateria" | sed 's/^.*SurcoBateria: //' > bateria.txt || true
{ echo "(build de depuración compilado con -m speed; emulador x86_64 sin audio real)"; } >> bateria.txt
cat bateria.txt
if ! grep -q "OK (1 test)" bateria-auto.txt; then
    echo "::error::falló la medida de batería (ver bateria-auto.txt)"
    sed -n '1,60p' bateria-auto.txt
    exit 1
fi
