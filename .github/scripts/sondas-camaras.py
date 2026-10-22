#!/usr/bin/env python3
"""Sondea organismos que podrían publicar cámaras de tráfico con VÍDEO y sin
clave. Para cada lista: si responde, cuánto pesa, cómo empieza, cuántas
direcciones .m3u8 trae y si la primera da una lista HLS viva."""
import json, re, sys, time, urllib.request

UA = "Mozilla/5.0 (Linux; Android 14) ARGOS/1.0"
Q511 = ("/List/GetData/Cameras?query=%7B%22columns%22%3A%5B%7B%22data%22%3Anull%2C%22name%22%3A%22%22%7D%5D"
        "%2C%22start%22%3A0%2C%22length%22%3A25%7D&lang=en")
CAND = [
    ("deldot", "https://tmc.deldot.gov/json/videocamera.json"),
    ("maryland", "https://chart.maryland.gov/DataFeeds/GetCamerasJson"),
    ("virginia", "https://511.vdot.virginia.gov/services/map/layers/map/cams"),
    ("missouri", "https://traveler.modot.org/timconfig/feed/desktop/StreamingCams2.json"),
    ("iowa-arcgis", "https://services.arcgis.com/8lRhdTsQyJpO52F1/arcgis/rest/services/Traffic_Cameras_View/FeatureServer/0/query?where=1%3D1&outFields=*&f=json&resultRecordCount=5"),
    ("ncdot", "https://eapps.ncdot.gov/services/traffic-prod/v1/cameras?verbose=true"),
] + [(f"511-{h}", f"https://{h}{Q511}") for h in [
    "511ny.org", "511ga.org", "www.nvroads.com", "www.az511.gov", "www.511la.org", "ctroads.org",
    "511wi.gov", "fl511.com", "511.idaho.gov", "www.511pa.com", "511.alaska.gov", "udottraffic.utah.gov",
    "www.511mn.org", "511.alberta.ca", "511on.ca", "www.511nj.org"]
] + [(f"cars-{s}", f"https://{s}tg.carsprogram.org/cameras_v1/api/cameras") for s in
     ["co", "mn", "ia", "ks", "ne", "in", "ma", "nh", "vt", "me", "id", "wa", "ut", "mo", "ak"]]


def pide(url, n=4_000_000):
    t0 = time.time()
    r = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json, */*"})
    try:
        with urllib.request.urlopen(r, timeout=20) as f:
            b = f.read(n)
            return f.status, f.headers.get("Content-Type", ""), b.decode("utf-8", "replace"), time.time() - t0
    except urllib.error.HTTPError as e:
        return e.code, "", "", time.time() - t0
    except Exception as e:
        return 0, type(e).__name__ + ": " + str(e)[:80], "", time.time() - t0


for nombre, url in CAND:
    st, ct, txt, dt = pide(url)
    plano = txt.replace("\\/", "/")
    m3u8 = re.findall(r'https?://[^"\'\s<>]+?\.m3u8[^"\'\s<>]*', plano)
    print(f"=== {nombre} · {st} · {ct[:40]} · {len(txt)} B · {dt:.1f} s · {len(m3u8)} m3u8 · {len(set(m3u8))} distintas")
    print("    url:", url)
    if txt:
        print("    inicio:", re.sub(r"\s+", " ", txt[:700]))
    if "isVideoAuthRequired" in plano:
        print("    auth:", plano.count('"isVideoAuthRequired":true'), "sí /", plano.count('"isVideoAuthRequired":false'), "no")
    for u in list(dict.fromkeys(m3u8))[:2]:
        s2, c2, t2, d2 = pide(u, 4000)
        print(f"    m3u8 {s2} {d2:.1f}s {u[:140]}")
        print("      ", re.sub(r"\s+", " ", t2[:240]))
    sys.stdout.flush()
