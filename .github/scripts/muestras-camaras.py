#!/usr/bin/env python3
"""Fotogramas de una muestra de cámaras públicas con vídeo, para estudiar qué
ve el detector y qué no según el tamaño de los objetos. Para cada cámara: un
fotograma a resolución nativa y, en algunas, una ráfaga corta (5 fps, 4 s)
para ver el movimiento. Nada se reenvía: es material de prueba del CI."""
import json, os, random, re, subprocess, sys, urllib.parse, urllib.request

UA = "Mozilla/5.0 (Linux; Android 14) ARGOS/1.0"
OUT = sys.argv[1]
POR_SITIO = int(sys.argv[2]) if len(sys.argv) > 2 else 10
os.makedirs(OUT, exist_ok=True)
random.seed(7)


def baja(url):
    r = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(r, timeout=30) as f:
        return json.loads(f.read().decode("utf-8", "replace"))


def caltrans(d):
    j = baja(f"https://cwwp2.dot.ca.gov/data/d{d}/cctv/cctvStatusD{d:02d}.json")
    for row in j.get("data", []):
        c = row.get("cctv", row); u = (c.get("imageData") or {}).get("streamingVideoURL", "")
        if u.startswith("http") and c.get("inService") != "false":
            yield (c.get("location") or {}).get("locationName", "?"), u


def cars(e):
    for c in baja(f"https://{e}tg.carsprogram.org/cameras_v1/api/cameras"):
        for v in c.get("views", []):
            u = v.get("url") or ""
            if ".m3u8" in u and c.get("public", True):
                yield c.get("name", "?"), u; break


def modot():
    for c in baja("https://traveler.modot.org/timconfig/feed/desktop/StreamingCams2.json"):
        if c.get("html") and ".m3u8" in c["html"]:
            yield c.get("location", "?"), c["html"]


def deldot():
    for c in baja("https://tmc.deldot.gov/json/videocamera.json").get("videoCameras", []):
        u = (c.get("urls") or {}).get("m3u8s")
        if u and c.get("enabled", True):
            yield c.get("title", "?"), u


def p511(host):
    q = urllib.parse.quote('{"columns":[{"data":null,"name":""}],"start":0,"length":500}')
    for c in baja(f"https://{host}/List/GetData/Cameras?query={q}&lang=en").get("data", []):
        for im in c.get("images", []):
            u = im.get("videoUrl") or ""
            if ".m3u8" in u and not im.get("isVideoAuthRequired") and not im.get("videoDisabled"):
                yield c.get("location", "?"), u; break


def tfl():
    for p in baja("https://api.tfl.gov.uk/Place/Type/JamCam"):
        pr = {q["key"]: q["value"] for q in p.get("additionalProperties", [])}
        if pr.get("available") != "false" and pr.get("videoUrl"):
            yield p.get("commonName", "?"), pr["videoUrl"]


SITIOS = [("california", lambda: [x for d in (4, 7, 11, 12, 3) for x in caltrans(d)]),
          ("minnesota", lambda: cars("mn")), ("iowa", lambda: cars("ia")), ("indiana", lambda: cars("in")),
          ("kansas", lambda: cars("ks")), ("misuri", modot), ("delaware", deldot),
          ("nevada", lambda: p511("www.nvroads.com")), ("luisiana", lambda: p511("www.511la.org")),
          ("wisconsin", lambda: p511("511wi.gov")), ("londres", tfl)]

indice = []
for sitio, lista in SITIOS:
    try:
        cams = list(lista())
    except Exception as e:
        print(f"{sitio}: lista falla {e}"); continue
    random.shuffle(cams)
    ok = 0
    for nombre, url in cams:
        if ok >= POR_SITIO: break
        base = f"{sitio}_{ok:02d}"
        f = os.path.join(OUT, base + ".jpg")
        r = subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-rw_timeout", "8000000", "-i", url,
                            "-frames:v", "1", "-q:v", "2", f], capture_output=True, timeout=40)
        if r.returncode != 0 or not os.path.exists(f) or os.path.getsize(f) < 3000:
            continue
        rafaga = ok < 3      # las tres primeras de cada sitio, con ráfaga
        if rafaga:
            d = os.path.join(OUT, base); os.makedirs(d, exist_ok=True)
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-rw_timeout", "8000000", "-i", url, "-t", "4",
                            "-vf", "fps=5", "-q:v", "3", os.path.join(d, "%02d.jpg")], capture_output=True, timeout=60)
        dims = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                               "stream=width,height", "-of", "csv=p=0", f], capture_output=True, text=True).stdout.strip()
        indice.append({"fichero": base + ".jpg", "sitio": sitio, "nombre": nombre, "url": url, "dims": dims, "rafaga": rafaga})
        ok += 1
        print(f"{base} {dims} {nombre[:60]}", flush=True)
json.dump(indice, open(os.path.join(OUT, "indice.json"), "w"), ensure_ascii=False, indent=1)
print(len(indice), "cámaras")
