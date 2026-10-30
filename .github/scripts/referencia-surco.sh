#!/usr/bin/env bash
# La misma medida de 10.000 canciones con la versión anterior (rama `referencia-surco`, el APK de
# depuración de 30298aa, la 2.7 entera) en el mismo emulador y justo después: así una diferencia de
# memoria o de tiempos es de la versión y no de la máquina del CI. Va la última: desinstala Surco.
set -uo pipefail
PKG=com.eddie.surco
git fetch -q origin referencia-surco 2>/dev/null && git show FETCH_HEAD:Surco-debug.apk > ref-debug.apk \
    || { echo "sin rama referencia-surco: no se compara"; exit 0; }
echo "== referencia: $(git log -1 --format=%s FETCH_HEAD)"
adb shell am force-stop "$PKG"
adb uninstall "$PKG" >/dev/null 2>&1 || true
adb shell rm -rf /sdcard/Music/mil
adb shell content call --uri content://media/ --method scan_volume --arg external_primary >/dev/null 2>&1 || true
for _ in $(seq 1 60); do
    c=$(adb shell content query --uri content://media/external/audio/media --projection _id 2>/dev/null | grep -c "^Row" || true)
    [ "${c:-0}" -le 10 ] && break; sleep 3
done
adb install ref-debug.apk >/dev/null || { echo "::warning::no se pudo instalar la referencia"; exit 0; }
adb shell cmd locale set-app-locales "$PKG" --locales es-ES
adb shell pm grant "$PKG" android.permission.READ_MEDIA_AUDIO
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
adb shell am start -W -n "$PKG/.MainActivity" >/dev/null 2>&1; sleep 15
# Una referencia que no llega al final no tumba el CI: lo que se mide es la versión nueva.
bash .github/scripts/rendimiento-surco.sh mil.tar 10000 rendimiento-referencia.txt || echo "::warning::la medida de referencia no terminó"
