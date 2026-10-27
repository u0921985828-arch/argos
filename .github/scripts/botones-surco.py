#!/usr/bin/env python3
"""
Todos los botones de Surco, uno a uno (con el APK de release, el que se entrega).

Para cada pantalla del recorrido se vuelca la interfaz, se toma cada cosa tocable de Surco y se
pulsa; después se mira:
  - CAÍDA: un cierre (buffer «crash» del log) o un «no responde». Tumba el CI.
  - NADA: la pantalla quedó exactamente igual (ni se abrió nada, ni cambió un estado). Se lista
    para revisarlo: puede ser un botón muerto.
  - si abrió otra pantalla, una hoja o una app del sistema, se apunta y se vuelve.
Tras cada toque se vuelve a la pantalla con «atrás» y, si no aparece, se rehace el camino desde
el arranque. Va al final de todo: pulsa también «Vaciar», «Borrar», etc.
Escribe botones.txt y sale con 1 si hubo alguna caída.
"""
import hashlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.eddie.surco"
OUT = open("botones.txt", "w", encoding="utf-8")


def sh(*a, check=False):
    return subprocess.run(["adb", "shell", *a], capture_output=True, text=True, check=check).stdout


def log(s=""):
    print(s, flush=True)
    OUT.write(s + "\n"); OUT.flush()


def dump():
    for i in range(10):
        if i == 5:
            sh("input", "keyevent", "KEYCODE_MEDIA_PAUSE")  # con la barra moviéndose, el volcado se cae
        sh("rm", "-f", "/sdcard/b.xml")
        sh("uiautomator", "dump", "/sdcard/b.xml")
        x = subprocess.run(["adb", "exec-out", "cat", "/sdcard/b.xml"], capture_output=True).stdout.decode("utf-8", "ignore")
        if "<hierarchy" in x:
            try:
                return ET.fromstring(x)
            except ET.ParseError:
                pass
        time.sleep(0.5)
    return ET.fromstring("<hierarchy/>")


def bounds(n):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
    return tuple(map(int, m.groups())) if m else (0, 0, 0, 0)


def label(n):
    own = n.get("content-desc") or n.get("text") or ""
    if own:
        return own
    kids = [c.get("text") or c.get("content-desc") for c in n.iter("node") if c is not n]
    return " / ".join(k for k in kids if k)[:60] or "(sin nombre)"


def mine(root):
    return [n for n in root.iter("node") if n.get("package") == PKG]


def signature(root):
    """La pantalla sin lo que cambia solo (tiempos y posición de las barras)."""
    parts = []
    for n in mine(root):
        t = n.get("text") or ""
        if re.fullmatch(r"−?\d+:\d\d", t):
            continue
        cls = n.get("class", "")
        where = "" if cls.endswith("SeekBar") else n.get("bounds", "")
        parts.append("|".join([cls, t, n.get("content-desc", ""), n.get("checked", ""), n.get("selected", ""), where]))
    return hashlib.sha1("\n".join(parts).encode()).hexdigest()


def tappables(root):
    """Lo tocable de Surco en pantalla: (nombre, centro), en orden."""
    out = []
    H = bounds(root.find("node"))[3] if root.find("node") is not None else 2400
    for n in mine(root):
        if n.get("clickable") != "true" or n.get("enabled") == "false":
            continue
        if n.get("class", "").endswith("EditText"):
            continue
        x1, y1, x2, y2 = bounds(n)
        if x2 - x1 < 8 or y2 - y1 < 8 or y1 >= H:
            continue
        lab = label(n)
        if lab in SKIP:
            continue
        out.append((lab, (x1 + x2) // 2, (y1 + y2) // 2))
    return out


def texts(root):
    return {n.get("text") or n.get("content-desc") for n in mine(root)} - {"", None}


def at(root, marks):
    ts = texts(root)
    sel = {n.get("text") for n in mine(root) if n.get("selected") == "true"}
    for m in marks:
        if m.startswith("!"):
            if any(m[1:] in t for t in ts):
                return False
        elif m.startswith("sel:"):
            if m[4:] not in sel:
                return False
        elif not any(m in t for t in ts):
            return False
    return True


def foreground():
    out = sh("dumpsys", "activity", "activities")
    m = re.search(r"mResumedActivity.*? ([\w.]+)/", out) or re.search(r"topResumedActivity.*? ([\w.]+)/", out)
    return m.group(1) if m else ""


def tap_xy(x, y):
    sh("input", "tap", str(x), str(y))


def tap_text(t, root=None):
    root = root or dump()
    for n in mine(root):
        if (n.get("text") == t or n.get("content-desc") == t):
            x1, y1, x2, y2 = bounds(n)
            tap_xy((x1 + x2) // 2, (y1 + y2) // 2)
            return True
    return False


def crashed():
    c = subprocess.run(["adb", "logcat", "-d", "-b", "crash"], capture_output=True, text=True).stdout
    anr = "ANR in " + PKG in subprocess.run(["adb", "logcat", "-d", "-s", "ActivityManager"], capture_output=True, text=True).stdout
    subprocess.run(["adb", "logcat", "-b", "crash", "-c"]); subprocess.run(["adb", "logcat", "-c"])
    blocks = re.split(r"(?=FATAL EXCEPTION)", c)
    ours = [b for b in blocks if "Process: " + PKG in b]
    return ("\n".join(ours[0].splitlines()[:25]) if ours else "") + ("ANR" if anr else "")


def start():
    sh("am", "force-stop", PKG)
    sh("am", "start", "-W", "-n", PKG + "/.MainActivity")
    time.sleep(3)


# Pantallas: nombre, camino desde el arranque (textos a tocar) y un texto que la reconoce.
SCREENS = [
    ("Inicio", [], ["Mezclar todo"]),
    ("Biblioteca · Canciones", ["Biblioteca", "Canciones"], ["sel:Canciones", "Aleatorio"]),
    ("Biblioteca · Álbumes", ["Biblioteca", "Álbumes"], ["sel:Álbumes", "Recientes"]),
    ("Biblioteca · Artistas", ["Biblioteca", "Artistas"], ["sel:Artistas"]),
    ("Biblioteca · Carpetas", ["Biblioteca", "Carpetas"], ["sel:Carpetas"]),
    ("Álbum", ["Biblioteca", "Álbumes", "Disco de Prueba"], ["Ordenar", "Disco de Prueba"]),
    ("Menú de canción", ["Biblioteca", "Canciones", "@Más opciones"], ["A la cola", "ORGANIZAR POR"]),
    ("Etiquetas", ["Etiquetas"], ["PERSONAS"]),
    ("Grupo (Género)", ["Etiquetas", "Género"], ["Más canciones"]),
    ("Listas", ["Listas"], ["AUTOMÁTICAS"]),
    ("Favoritas", ["Listas", "Favoritas"], ["Favoritas", "Aleatorio"]),
    ("Buscar", ["Buscar en cualquier etiqueta"], ["Sin importar tildes"]),
    ("Ajustes", ["Ajustes"], ["Tus escuchas", "Acerca de Surco"]),
    ("Ajustes · Sonido", ["Ajustes", "Sonido"], ["Calidad de salida"]),
    ("Ecualizador", ["Ajustes", "Sonido", "Ecualizador y efectos"], ["Usar el ecualizador"]),
    ("Ajustes · Reproducción", ["Ajustes", "Reproducción"], ["Fundido entre canciones"]),
    ("Ajustes · Biblioteca", ["Ajustes", "Biblioteca"], ["Volver a leer todas las etiquetas"]),
    ("Ajustes · Interfaz", ["Ajustes", "Interfaz"], ["Color de acento"]),
    ("Ajustes · Tus escuchas", ["Ajustes", "Tus escuchas"], ["Tus escuchas", "!Acerca de Surco"]),
    ("Ajustes · Acerca de", ["Ajustes", "Acerca de Surco"], ["Surco solo usa internet"]),
    ("Sonando", ["#mini"], ["SONANDO"]),
    ("Sonando · Más", ["#mini", "Más"], ["ESTA CANCIÓN"]),
    ("Cola", ["#mini", "Cola"], ["Vaciar"]),
]

# Lo que se salta: la barra inferior y la flecha de volver (cambian de pantalla a propósito y
# se prueban en el resto del recorrido) y los campos de texto.
# El idioma también: en inglés el resto del recorrido no encontraría nada (se prueba aparte).
SKIP = {"Inicio", "Biblioteca", "Etiquetas", "Listas", "Atrás", "El del móvil", "English", "Español"}


def go(path, mark):
    start()
    for step in path:
        root = dump()
        if step == "#mini":
            # El minirreproductor: lo que suena; si no hay nada, se pone algo desde Inicio.
            if not tap_text("Prueba Uno", root) and not tap_text("Prueba Dos", root):
                tap_text("Mezclar todo", root); time.sleep(2); sh("input", "keyevent", "KEYCODE_MEDIA_PAUSE")
                root = dump()
                for n in mine(root):
                    if (n.get("text") or "").startswith("Prueba "):
                        x1, y1, x2, y2 = bounds(n); tap_xy((x1 + x2) // 2, (y1 + y2) // 2); break
        elif step.startswith("@"):
            tap_text(step[1:], root)
        else:
            tap_text(step, root)
        time.sleep(2)
    return at(dump(), mark)


def back_to(mark):
    for _ in range(3):
        root = dump()
        if foreground() == PKG and at(root, mark):
            return root
        sh("input", "keyevent", "KEYCODE_BACK"); time.sleep(1.5)
    return None


def main():
    crashes, dead, total = [], [], 0
    sh("settings", "put", "system", "font_scale", "1.0")
    sh("cmd", "locale", "set-app-locales", PKG, "--locales", "es-ES")
    subprocess.run(["adb", "logcat", "-b", "crash", "-c"]); subprocess.run(["adb", "logcat", "-c"])
    for name, path, mark in SCREENS:
        if not go(path, mark):
            log(f"== {name}: no se llegó a la pantalla (camino {path})")
            continue
        cands = []
        seen = {}
        for lab, _, _ in tappables(dump()):
            seen[lab] = seen.get(lab, -1) + 1
            cands.append((lab, seen[lab]))
        log(f"== {name}: {len(cands)} tocables")
        for lab, k in cands:
            # Se busca otra vez: un interruptor o un chip pueden haber movido lo de debajo.
            before = dump()
            here = [(x, y) for l, x, y in tappables(before) if l == lab]
            if len(here) <= k:
                log(f"  (ya no está) {lab}")
                continue
            total += 1
            sig = signature(before)
            tap_xy(*here[k]); time.sleep(1.5)
            c = crashed()
            if c:
                log(f"  CAÍDA  {lab}\n{c}")
                crashes.append(f"{name} › {lab}")
                go(path, mark); continue
            fg = foreground()
            after = dump()
            if fg and fg != PKG:
                what = f"abre {fg}"
            elif signature(after) == sig:
                what = "NADA"; dead.append(f"{name} › {lab}")
            else:
                new = sorted(texts(after) - texts(before))[:3]
                what = "cambia" + (f" ({', '.join(new)})" if new else "")
            log(f"  {what:12} {lab}")
            if back_to(mark) is None and not go(path, mark):
                log("  (no se pudo volver a la pantalla; se sigue con la siguiente)")
                break
    log("")
    log(f"{total} botones pulsados · {len(crashes)} caídas · {len(dead)} sin efecto visible")
    for d in dead:
        log(f"  sin efecto: {d}")
    for c in crashes:
        log(f"  CAÍDA: {c}")
    sys.exit(1 if crashes else 0)


if __name__ == "__main__":
    main()
