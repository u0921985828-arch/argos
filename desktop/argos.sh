#!/usr/bin/env bash
# ARGOS · lanzador para Linux y macOS. Mismo principio que el .cmd de Windows:
# servidor local en loopback (contexto seguro para la captura de pantalla) y
# navegador en modo aplicación, sin Electron.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

command -v node >/dev/null || { echo "ARGOS necesita Node.js para el lanzador."; exit 1; }

BROWSER=""
for c in \
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
  "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge" \
  "$(command -v google-chrome || true)" \
  "$(command -v chromium || true)" \
  "$(command -v microsoft-edge || true)" \
  "$(command -v brave-browser || true)"; do
  [ -n "$c" ] && [ -x "$c" ] && { BROWSER="$c"; break; }
done
[ -n "$BROWSER" ] || { echo "No encuentro Chrome, Edge ni Chromium."; exit 1; }

PORT_FILE="$(mktemp)"
node server.js > "$PORT_FILE" &
SRV=$!
# El servidor debe estar en pie antes de abrir la ventana; si no, sale un error
# de conexión que el usuario lee como "la app está rota".
for _ in $(seq 40); do grep -q ARGOS_READY "$PORT_FILE" && break; sleep 0.05; done
PORT="$(awk '/ARGOS_READY/{print $2; exit}' "$PORT_FILE")"
[ -n "$PORT" ] || { kill $SRV 2>/dev/null || true; echo "El servidor local no arrancó."; exit 1; }

cleanup() { kill $SRV 2>/dev/null || true; rm -f "$PORT_FILE"; }
trap cleanup EXIT

"$BROWSER" \
  --app="http://127.0.0.1:${PORT}/" \
  --user-data-dir="${HOME}/.argos/profile" \
  --window-size=1360,860 \
  --no-first-run --no-default-browser-check \
  --autoplay-policy=no-user-gesture-required >/dev/null 2>&1
