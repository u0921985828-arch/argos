#!/data/data/com.termux/files/usr/bin/bash
#
# ARGOS sobre Android, sin servidor externo.
#
# El teléfono hace de cámara Y de servidor. Suena raro y es, de hecho, la mejor
# configuración disponible en Android, por un motivo que no es de rendimiento:
#
#   El navegador solo entrega la cámara en un "contexto seguro". `localhost`
#   cuenta como tal. Así que sirviendo desde el propio dispositivo desaparece de
#   golpe el problema del HTTPS: ni túnel, ni certificados, ni IP de la red.
#
# La subida de frames viaja por loopback, así que no toca la red ni gasta datos.
#
# Consumo medido (perfil `device`, entrada 1280x720): ~370 MB de RSS estables y
# ~3 s para resolver un sinopsis. Cualquier teléfono con 4 GB lo soporta.
#
#   bash termux_setup.sh
#
set -euo pipefail

BOLD=$'\033[1m'; DIM=$'\033[2m'; OK=$'\033[32m'; WARN=$'\033[33m'
ERR=$'\033[31m'; RST=$'\033[0m'
say()  { printf '%s\n' "${BOLD}==>${RST} $*"; }
note() { printf '%s\n' "    ${DIM}$*${RST}"; }
good() { printf '%s\n' "    ${OK}ok${RST} $*"; }
warn() { printf '%s\n' "    ${WARN}aviso${RST} $*"; }
die()  { printf '%s\n' "    ${ERR}error${RST} $*" >&2; exit 1; }

[ -n "${PREFIX:-}" ] && [ -d "$PREFIX" ] || die "Esto debe ejecutarse dentro de Termux."

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
[ -d argos ] || die "No encuentro el paquete 'argos'. Ejecútalo desde el repositorio."

# --------------------------------------------------------------------------- #
say "Actualizando índices de paquetes"
pkg update -y >/dev/null 2>&1 || warn "no se pudo actualizar; sigo con lo que haya"

# --------------------------------------------------------------------------- #
say "Instalando dependencias del sistema"
#
# numpy, scipy y opencv se instalan como paquetes de Termux, NO con pip. Todos
# llevan extensiones compiladas: compilarlas en el teléfono requiere toolchain
# de Fortran y BLAS, tarda horas y suele fallar por memoria. Los paquetes de
# Termux ya vienen construidos para el arm64 del dispositivo.
SYS_PKGS=(python python-numpy python-pip clang libjpeg-turbo libpng ffmpeg)
for p in "${SYS_PKGS[@]}"; do
  pkg install -y "$p" >/dev/null 2>&1 && good "$p" || warn "$p no disponible"
done

for p in python-scipy opencv-python; do
  if pkg install -y "$p" >/dev/null 2>&1; then
    good "$p"
  else
    warn "$p no está en tu repositorio de Termux"
    note "Prueba: pkg install root-repo && pkg install $p"
    note "Si aun así falta, este dispositivo no puede ejecutar ARGOS en local."
  fi
done

# --------------------------------------------------------------------------- #
say "Instalando dependencias de Python"
# Estas son Python puro: pip las resuelve sin compilar nada.
pip install --quiet --upgrade fastapi uvicorn python-multipart \
  || die "falló la instalación de FastAPI"
good "fastapi · uvicorn · python-multipart"

# --------------------------------------------------------------------------- #
say "Verificando que todo importa"
python - <<'PY' || die "faltan dependencias; revisa los avisos anteriores"
import importlib, sys
faltan = []
for mod, etiqueta in [("numpy", "numpy"), ("scipy", "scipy"), ("cv2", "opencv"),
                      ("fastapi", "fastapi"), ("uvicorn", "uvicorn")]:
    try:
        m = importlib.import_module(mod)
        print(f"    ok {etiqueta} {getattr(m, '__version__', '')}")
    except Exception as exc:
        faltan.append(f"{etiqueta}: {type(exc).__name__}")
if faltan:
    print("    FALTAN -> " + "; ".join(faltan), file=sys.stderr)
    sys.exit(1)
PY

# --------------------------------------------------------------------------- #
say "Comprobando el núcleo con datos sintéticos"
python - <<'PY' || die "el núcleo no funciona en este dispositivo"
import sys, time
sys.path.insert(0, ".")
from argos.detect.synthetic import SceneConfig, SyntheticScene
from argos.synopsis.optimizer import SolverConfig, SynopsisSolver

escena = SyntheticScene(SceneConfig(n_objects=30, duration=2400, max_dwell_s=10, seed=3))
t0 = time.perf_counter()
solver = SynopsisSolver(escena.tubes, SolverConfig(scale=6, iterations=6000))
plan = solver.solve(400)
dt = time.perf_counter() - t0
print(f"    ok {len(escena.tubes)} tubos, plan resuelto en {dt:.1f}s "
      f"({plan.compression:.1f}x)")
if dt > 45:
    print("    aviso: este dispositivo va justo; usa --stride 2 o baja la cadencia")
PY

# --------------------------------------------------------------------------- #
say "Escribiendo el lanzador"
LAUNCH="$ROOT/argos-local"
cat > "$LAUNCH" <<'LAUNCHER'
#!/data/data/com.termux/files/usr/bin/bash
#
# Arranca ARGOS en el propio dispositivo.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

# Perfil `device`: techos de memoria pensados para un teléfono que además
# sostiene la cámara y la pantalla. Sin esto el almacén de recortes crece sin
# límite y el sistema acaba matando el proceso al cabo de unas horas.
export ARGOS_PROFILE=device
export ARGOS_WORK="${ARGOS_WORK:-$HOME/argos_salida}"
mkdir -p "$ARGOS_WORK"

# Impide que Android suspenda el proceso con la pantalla apagada. Sin esto, el
# análisis se detiene en cuanto bloqueas el móvil.
command -v termux-wake-lock >/dev/null && termux-wake-lock || true
trap 'command -v termux-wake-unlock >/dev/null && termux-wake-unlock || true' EXIT

# Solo loopback: nada de --host 0.0.0.0. En el propio dispositivo no hace falta
# exponerlo, y la API no lleva autenticación, así que abrirla a la wifi
# significaría publicar la cámara a quien esté en la misma red.
PORT="${ARGOS_PORT:-8000}"
echo
echo "  ARGOS escuchando en  http://localhost:${PORT}"
echo "  Ábrelo en el navegador del propio móvil."
echo "  Salida en  ${ARGOS_WORK}"
echo
exec python -m uvicorn argos.api.main:app --host 127.0.0.1 --port "$PORT" --log-level warning
LAUNCHER
chmod +x "$LAUNCH"
good "$LAUNCH"

# --------------------------------------------------------------------------- #
cat <<FIN

${BOLD}Listo.${RST}

  1. Arranca:   ${BOLD}./argos-local${RST}
  2. Abre en el navegador del móvil:  ${BOLD}http://localhost:8000${RST}
  3. Fuente:  ${BOLD}push://telefono${RST}  ->  Conectar  ->  Empezar

${BOLD}Antes de usarlo en serio${RST}

  · Apoya el móvil en algo fijo. Todo el análisis asume vista estática.
  · Quita ARGOS de la optimización de batería (Ajustes > Batería), o Android
    matará Termux a los pocos minutos con la pantalla apagada.
  · Instala Termux:API (${DIM}pkg install termux-api${RST} + la app) si quieres que
    el wake-lock funcione de verdad.
  · Escucha solo en localhost. Para llegar desde otro dispositivo hace falta
    autenticación delante: la API no la lleva.

${BOLD}Consumo medido${RST} (perfil device, entrada 720p)

  Memoria      ~370 MB estables
  Análisis     suficiente para 2-4 fps, que es la cadencia de captura
  Sinopsis     ~3 s por plan

FIN
