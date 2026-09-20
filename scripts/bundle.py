#!/usr/bin/env python3
"""Empaqueta ARGOS en un único fichero HTML sin dependencias.

El resultado no necesita servidor, ni Python, ni instalación: se abre y
funciona. Todo lo que corre en tiempo real --- modelo de fondo, tracking,
siluetas vectoriales, overlay --- y también el optimizador de sinopsis, viven
dentro del fichero.

Se inlinan los `<script src>` en lugar de dejarlos como ficheros sueltos por un
motivo concreto: un `<script src="engine.js">` desde `file://` se bloquea por
CORS en varios navegadores, mientras que un script embebido no. Un solo fichero
también elimina la clase entera de fallos de "lo he movido y ya no va".

    python3 scripts/bundle.py            -> argos.html
    python3 scripts/bundle.py --out X.html
"""

from __future__ import annotations

import argparse
import datetime as dt
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STATIC = ROOT / "argos" / "api" / "static"


def inline(html: str, static: Path) -> tuple[str, list[str]]:
    """Sustituye cada <script src="…"> por su contenido."""
    embedded: list[str] = []

    def repl(match: re.Match) -> str:
        src = match.group(1)
        name = src.split("/")[-1].split("?")[0]
        path = static / name
        if not path.exists():
            raise SystemExit(f"falta {path}")
        code = path.read_text(encoding="utf-8")
        # Un "</script>" dentro de una cadena cerraría la etiqueta que lo
        # contiene y rompería el documento entero. Es el fallo clásico de
        # cualquier inlineador y se evita partiendo la secuencia.
        code = code.replace("</script", "<\\/script")
        embedded.append(f"{name} ({len(code) // 1024} KB)")

        # Cada módulo va en su propio ámbito.
        #
        # Inlinarlos tal cual los pone a todos en el ámbito léxico GLOBAL, que
        # los scripts clásicos comparten. Dos módulos que declaren `const
        # median` --- cosa razonable, son utilidades internas --- colisionan, y
        # el navegador lanza un SyntaxError que **impide cargar la página
        # entera**. No es un fallo degradado: la app no arranca.
        #
        # El envoltorio les da ámbito propio y publica en `globalThis` solo lo
        # que cada módulo declara en `module.exports`, que es exactamente su
        # interfaz pública. Lo interno deja de ser global, que además es lo
        # correcto.
        return (f"<script>\n/* ---- {name} ---- */\n"
                "(function(){\n"
                "var module = {exports:{}}; var exports = module.exports;\n"
                f"{code}\n"
                "if (module.exports) Object.assign(globalThis, module.exports);\n"
                "})();\n</script>")

    out = re.sub(r'<script\s+src="([^"]+)"[^>]*></script>', repl, html)
    return out, embedded


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(ROOT / "argos.html"))
    ap.add_argument("--static", default=str(STATIC))
    args = ap.parse_args()

    static = Path(args.static)
    index = static / "index.html"
    if not index.exists():
        sys.exit(f"no encuentro {index}")

    html, embedded = inline(index.read_text(encoding="utf-8"), static)
    if not embedded:
        sys.exit("no se ha inlinado nada: revisa las etiquetas <script src>")

    stamp = dt.datetime.now().strftime("%Y-%m-%d %H:%M")
    banner = (f"<!-- ARGOS · fichero autónomo generado el {stamp}\n"
              f"     Incluye: {', '.join(embedded)}\n"
              f"     No necesita servidor. Ábrelo en el navegador. -->\n")
    html = banner + html

    # Comprobación mínima antes de escribir: si el inlineado ha dejado un
    # </script> huérfano, el fichero se abre en blanco y el fallo es opaco.
    opens = html.count("<script")
    closes = html.count("</script>")
    if opens != closes:
        sys.exit(f"etiquetas script descuadradas: {opens} abren, {closes} cierran")

    # Colisiones de ámbito global.
    #
    # Validar cada bloque por separado --- que es lo que hacía la comprobación
    # anterior --- no detecta nada: cada uno es sintácticamente correcto por su
    # cuenta. El navegador los evalúa compartiendo el ámbito léxico global, y
    # ahí `const median` declarado dos veces mata la página.
    #
    # Esta comprobación existe porque ese fallo llegó a una entrega.
    blocks = re.findall(r"<script>([\s\S]*?)</script>", html)
    decls: dict[str, int] = {}
    clashes = []
    for idx, blk in enumerate(blocks):
        # Un bloque envuelto no aporta nada al ámbito global salvo lo que
        # publique en `module.exports`, así que se salta: la colisión que
        # importa es entre declaraciones que SÍ quedan globales.
        if blk.lstrip().startswith("/* ---- ") and "(function(){" in blk[:200]:
            continue
        for m in re.finditer(r"^(?:const|let|class|function)\s+([A-Za-z_$][\w$]*)",
                             blk, re.M):
            name = m.group(1)
            if name in decls and decls[name] != idx:
                clashes.append(name)
            decls[name] = idx
    if clashes:
        sys.exit("colision de nombres en el ambito global: "
                 + ", ".join(sorted(set(clashes))))

    # Y la comprobación que de verdad reproduce el navegador: los nombres que
    # cada módulo publica en `module.exports` no deben pisarse entre sí.
    exported: dict[str, str] = {}
    dupes = []
    for name in [e.split(" ")[0] for e in embedded]:
        src = (static / name).read_text(encoding="utf-8")
        m = re.search(r"module\.exports\s*=\s*\{([^}]*)\}", src, re.S)
        if not m:
            continue
        for sym in re.findall(r"([A-Za-z_$][\w$]*)\s*(?:,|$)", m.group(1)):
            if sym in exported and exported[sym] != name:
                dupes.append(f"{sym} ({exported[sym]} y {name})")
            exported[sym] = name
    if dupes:
        print("  aviso: simbolos exportados por mas de un modulo -> "
              + "; ".join(dupes))

    out = Path(args.out)
    out.write_text(html, encoding="utf-8")
    print(f"{out}  ({len(html) // 1024} KB)")
    for e in embedded:
        print(f"  · {e}")


if __name__ == "__main__":
    main()
