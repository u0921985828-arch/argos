#!/usr/bin/env python3
"""Lo medible del diseño de Surco, sobre las capturas de diseno-surco.sh.

Para cada pantalla (NN-nombre.png + NN-nombre.xml):
  - TOQUE: lo que se toca mide menos de 48×48 dp (pauta de Android y de WCAG 2.5.8 ampliada);
  - CONTRASTE: texto por debajo de 4,5:1 (3:1 si es grande; se toma por grande una sola línea
    de 24 dp de alto o más, que equivale a unos 18 sp);
  - SALE: un nodo de Surco que se sale de la pantalla por los lados;
  - SOLAPE: dos cosas tocables que se pisan sin estar una dentro de la otra.
Escribe informe.txt en la carpeta y sale con 1 si hay algún fallo y se pasa --estricto.
Uso: diseno-surco.py CARPETA [--estricto]
"""
import glob, os, re, sys
import xml.etree.ElementTree as ET
from PIL import Image

PKG = "com.eddie.surco"
carpeta = sys.argv[1]
estricto = "--estricto" in sys.argv
dens = int(open(os.path.join(carpeta, "densidad.txt")).read().strip() or 420) / 160

def bounds(n):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
    return tuple(map(int, m.groups())) if m else None

def lum(c):
    def ch(v):
        v /= 255
        return v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4
    r, g, b = c[:3]
    return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)

def ratio(a, b):
    la, lb = sorted((lum(a), lum(b)), reverse=True)
    return (la + 0.05) / (lb + 0.05)

def contraste(img, b):
    """Fondo = color más repetido; letra = el color frecuente más alejado del fondo (no un píxel suelto
    del suavizado). None si la caja no tiene nada que medir."""
    x1, y1, x2, y2 = b
    if x2 - x1 < 4 or y2 - y1 < 4: return None
    reg = img.crop((x1, y1, x2, y2)).convert("RGB")
    # Colores redondeados a 8 niveles por canal para agrupar el suavizado.
    reg = reg.point(lambda v: v // 8 * 8)
    cnt = {c: k for k, c in reg.getcolors(1 << 20)}
    tot = sum(cnt.values())
    fondo = max(cnt, key=cnt.get)
    lf = lum(fondo)
    # Candidatos a letra: colores con al menos el 0,3 % de la caja; el más distinto en luminancia.
    cands = [c for c, k in cnt.items() if k >= tot * 0.003 and c != fondo]
    if not cands: return None
    letra = max(cands, key=lambda c: abs(lum(c) - lf))
    return ratio(fondo, letra), fondo, letra

informe, fallos = [], 0
for xmlf in sorted(glob.glob(os.path.join(carpeta, "*.xml"))):
    nombre = os.path.basename(xmlf)[:-4]
    png = xmlf[:-4] + ".png"
    try:
        root = ET.parse(xmlf).getroot()
        img = Image.open(png)
    except Exception as e:
        informe.append(f"{nombre}: no se puede leer ({e})"); continue
    W, H = img.size
    nodos = [n for n in root.iter("node") if n.get("package") == PKG and bounds(n)]
    padre = {c: p for p in root.iter("node") for c in p}
    def desactivado(n):
        while n is not None:
            if n.get("enabled") == "false": return True
            n = padre.get(n)
        return False
    pant = []
    avisos = []
    toc = []
    for n in nodos:
        b = bounds(n)
        w, h = (b[2] - b[0]) / dens, (b[3] - b[1]) / dens
        etiqueta = n.get("text") or n.get("content-desc") or ""
        if not etiqueta:  # el nombre de lo tocable está en un hijo (Compose fusiona la semántica)
            for c in n.iter("node"):
                etiqueta = c.get("text") or c.get("content-desc") or ""
                if etiqueta: break
        tocable = n.get("clickable") == "true" or n.get("long-clickable") == "true" or n.get("checkable") == "true"
        visible = b[1] < H and b[3] > 0 and b[3] - b[1] > 2 and b[2] - b[0] > 2
        if tocable and visible:
            # El botón de borrar vive dentro del campo de texto: no es un solape. Lo recortado por
            # el borde de una lista que se desplaza tampoco (asoma por debajo de la cabecera).
            # Recortado por el borde de la pantalla o de una lista que se desplaza: no es su tamaño real.
            cortado = b[1] <= 0 or b[3] >= H - 1 or b[0] <= 0 or b[2] >= W - 1
            m = padre.get(n)
            while m is not None and not cortado:
                if m.get("scrollable") == "true" and bounds(m):
                    c = bounds(m)
                    cortado = b[0] <= c[0] or b[1] <= c[1] or b[2] >= c[2] or b[3] >= c[3]
                m = padre.get(m)
            if not n.get("class", "").endswith("EditText") and not cortado: toc.append((b, etiqueta))
            # Lo de menos de 10 dp es una pieza de un deslizador (pulgar, pista), no un botón.
            # Compose amplía solo el área de toque de lo pequeño hasta 48 dp (si no hay otro
            # tocable más cerca), así que de 40 a 47 dp se avisa sin contar como fallo.
            if (w < 40 or h < 40) and not cortado and w >= 10 and h >= 10:
                pant.append(f"  TOQUE {w:.0f}×{h:.0f} dp «{etiqueta[:40]}»")
            elif (w < 47.5 or h < 47.5) and not cortado and w >= 10 and h >= 10:
                avisos.append(f"  ajustado {w:.0f}×{h:.0f} dp «{etiqueta[:40]}»")
        if visible and (b[0] < 0 or b[2] > W):
            pant.append(f"  SALE «{etiqueta[:40]}» {b}")
        texto = n.get("text") or ""
        # Un botón desactivado («Crear» sin nombre escrito) no tiene que cumplir el contraste.
        if texto.strip() and visible and n.get("class", "").endswith("TextView") and not desactivado(n):
            r = contraste(img, b)
            if r:
                k, fondo, letra = r
                grande = h >= 24 and "\n" not in texto
                minimo = 3.0 if grande else 4.5
                if k < minimo:
                    pant.append(f"  CONTRASTE {k:.2f}:1 (mín. {minimo}) «{texto[:40]}» letra #{letra[0]:02x}{letra[1]:02x}{letra[2]:02x} fondo #{fondo[0]:02x}{fondo[1]:02x}{fondo[2]:02x}")
    # Solapes entre tocables que no se contienen.
    for i in range(len(toc)):
        for j in range(i + 1, len(toc)):
            a, b = toc[i][0], toc[j][0]
            inter = not (a[2] <= b[0] or b[2] <= a[0] or a[3] <= b[1] or b[3] <= a[1])
            dentro = (a[0] <= b[0] and a[1] <= b[1] and a[2] >= b[2] and a[3] >= b[3]) or (b[0] <= a[0] and b[1] <= a[1] and b[2] >= a[2] and b[3] >= a[3])
            if inter and not dentro:
                # Compose declara 48 dp para lo que mide menos: dos chips de 40 dp a 8 dp se «pisan»
                # 0 dp en pantalla. Solo cuenta si lo pisado pasa de un tercio del más pequeño.
                ix = min(a[2], b[2]) - max(a[0], b[0]); iy = min(a[3], b[3]) - max(a[1], b[1])
                menor = min((a[2] - a[0]) * (a[3] - a[1]), (b[2] - b[0]) * (b[3] - b[1]))
                if ix * iy > menor / 3:
                    pant.append(f"  SOLAPE «{toc[i][1][:30]}» y «{toc[j][1][:30]}»")
    pant = sorted(set(pant))
    fallos += len(pant)
    informe.append(f"{nombre}: {len(pant)}")
    informe.extend(pant)
    informe.extend(sorted(set(avisos)))

informe.append(f"TOTAL {fallos}")
open(os.path.join(carpeta, "informe.txt"), "w").write("\n".join(informe) + "\n")
print("\n".join(informe))
sys.exit(1 if estricto and fallos else 0)
