#!/usr/bin/env python3
"""
Entrena el detector de ARGOS con lo que ven las cámaras públicas.

El detector de serie (YOLOX nano, COCO) se hizo con fotos de cerca. En las
cámaras de tráfico los objetos son pequeños, hay noche, compresión y rótulos
sobreimpresos: en 110 cámaras vio un 62 % de lo seguro en cuadro entero, y puso
cajas en letras, farolas y destellos. Este programa lo ajusta a ese mundo:

  muestra   fotogramas de cámaras públicas con vídeo (los mismos organismos que
            la app), uno por cámara, en paralelo.
  etiqueta  un MAESTRO grande (YOLOX-L) mira cada fotograma con calma —cuadro
            entero y teselas ampliadas— y lo que ve con seguridad (≥ 0,5) es la
            etiqueta. Lo dudoso (0,3-0,5) no enseña nada en ningún sentido.
  entrena   el ALUMNO (YOLOX nano, el de la app) aprende a ver lo mismo con
            sus 0,9 M de parámetros: cuadros enteros y recortes del tamaño de
            las teselas ×2 de la app, con noche, compresión y espejo.
  evalua    con cámaras que el alumno no vio, el nano de serie frente al
            entrenado, como los usa la app (cuadro entero y teselas ×2), por
            tamaño de objeto y de día/de noche. Y las fotos de siempre (bus,
            zidane, perro), para ver que no ha olvidado lo de cerca.
  exporta   ONNX igual que el de serie: entrada BGR cruda 0-255, salida sin
            decodificar (la app reconstruye la rejilla).

Privacidad: los fotogramas viven solo mientras corre el trabajo; no se guardan
ni se suben. Lo que sale es el modelo (pesos) y el informe con cifras.
"""
import argparse, glob, json, math, os, random, subprocess, sys, time
import concurrent.futures as cf
import urllib.parse, urllib.request

import numpy as np
import cv2

# Las clases que usa la app, con su índice COCO; el resto de clases del
# modelo se quedan como están (la cabeza sigue siendo de 80).
COCO = {0: "person", 1: "bicycle", 2: "car", 3: "motorcycle", 5: "bus", 7: "truck"}
GRUPO = {0: "person", 1: "bike", 3: "bike", 2: "vehicle", 5: "vehicle", 7: "vehicle"}
UA = "Mozilla/5.0 (Linux; Android 14) ARGOS/1.0"


# ---------------------------------------------------------------- muestra --
def _json(url):
    r = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(r, timeout=30) as f:
        return json.loads(f.read().decode("utf-8", "replace"))


def catalogo():
    """(sitio, nombre, url) de todas las cámaras con vídeo, como Catalogo.kt."""
    out = []
    def caltrans(d):
        for row in _json(f"https://cwwp2.dot.ca.gov/data/d{d}/cctv/cctvStatusD{d:02d}.json").get("data", []):
            c = row.get("cctv", row); u = (c.get("imageData") or {}).get("streamingVideoURL", "")
            if u.startswith("http") and c.get("inService") != "false":
                yield "california", (c.get("location") or {}).get("locationName", "?"), u
    def cars(e, s):
        for c in _json(f"https://{e}tg.carsprogram.org/cameras_v1/api/cameras"):
            if c.get("public") is False or c.get("active") is False: continue
            for v in c.get("views", []):
                if ".m3u8" in (v.get("url") or ""):
                    yield s, c.get("name", "?"), v["url"]; break
    def modot():
        for c in _json("https://traveler.modot.org/timconfig/feed/desktop/StreamingCams2.json"):
            if c.get("html") and ".m3u8" in c["html"]: yield "misuri", c.get("location", "?"), c["html"]
    def deldot():
        for c in _json("https://tmc.deldot.gov/json/videocamera.json").get("videoCameras", []):
            u = (c.get("urls") or {}).get("m3u8s")
            if u and c.get("enabled", True): yield "delaware", c.get("title", "?"), u
    def p511(host, s):
        for start in range(0, 2000, 500):
            q = urllib.parse.quote('{"columns":[{"data":null,"name":""}],"start":%d,"length":500}' % start)
            d = _json(f"https://{host}/List/GetData/Cameras?query={q}&lang=en").get("data", [])
            if not d: break
            for c in d:
                for im in c.get("images", []):
                    u = im.get("videoUrl") or ""
                    if ".m3u8" in u and not im.get("isVideoAuthRequired") and not im.get("videoDisabled"):
                        yield s, c.get("location", "?"), u; break
    def tfl():
        for p in _json("https://api.tfl.gov.uk/Place/Type/JamCam"):
            pr = {q["key"]: q["value"] for q in p.get("additionalProperties", [])}
            if pr.get("available") != "false" and pr.get("videoUrl"): yield "londres", p.get("commonName", "?"), pr["videoUrl"]
    fuentes = [lambda d=d: caltrans(d) for d in (3, 4, 5, 6, 7, 8, 10, 11, 12)] + \
              [lambda e=e, s=s: cars(e, s) for e, s in (("ia", "iowa"), ("mn", "minnesota"), ("in", "indiana"), ("ks", "kansas"))] + \
              [modot, deldot, tfl] + \
              [lambda h=h, s=s: p511(h, s) for h, s in (("www.nvroads.com", "nevada"), ("www.511la.org", "luisiana"), ("511wi.gov", "wisconsin"))]
    for f in fuentes:
        try: out.extend(f())
        except Exception as e: print("lista falla:", e, flush=True)
    return out


def muestra(args):
    os.makedirs(args.dir, exist_ok=True)
    cams = catalogo()
    print(len(cams), "cámaras con vídeo en el catálogo", flush=True)
    rnd = random.Random(args.semilla)
    por = {}
    for c in cams: por.setdefault(c[0], []).append(c)
    for v in por.values(): rnd.shuffle(v)
    # Reparto por sitio: ninguno se lleva más de un tercio.
    cupo = max(10, args.n // len(por))
    elegidas = []
    for s, v in por.items(): elegidas += v[:min(len(v), max(cupo, args.n // 3 if s == "california" else cupo))]
    rnd.shuffle(elegidas); elegidas = elegidas[:args.n * 2]
    def una(i_c):
        i, (sitio, nombre, url) = i_c
        f = os.path.join(args.dir, f"{sitio}_{i:05d}.jpg")
        try:
            r = subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-rw_timeout", "8000000", "-i", url,
                                "-frames:v", "1", "-q:v", "2", f], capture_output=True, timeout=30)
            if r.returncode == 0 and os.path.exists(f) and os.path.getsize(f) > 5000: return f
        except Exception: pass
        if os.path.exists(f): os.remove(f)
        return None
    ok = 0
    with cf.ThreadPoolExecutor(args.hilos) as ex:
        for r in ex.map(una, enumerate(elegidas)):
            if r: ok += 1
            if ok >= args.n: break
    # Las de más, fuera (los hilos pueden haber terminado alguna de más).
    fs = sorted(glob.glob(os.path.join(args.dir, "*.jpg")))
    for f in fs[args.n:]: os.remove(f)
    print(min(ok, args.n), "fotogramas", flush=True)


# ------------------------------------------------- inferencia YOLOX ONNX --
class Det:
    """La misma entrada que la app: BGR crudo 0-255, relleno 114 abajo-derecha; la rejilla se decodifica aquí."""
    def __init__(self, ruta, size, hilos=2):
        import onnxruntime as ort
        o = ort.SessionOptions(); o.intra_op_num_threads = hilos; o.inter_op_num_threads = 1
        self.s = ort.InferenceSession(ruta, o, providers=["CPUExecutionProvider"])
        self.n = self.s.get_inputs()[0].name; self.size = size
        g = []; st = []
        for stride in (8, 16, 32):
            m = size // stride
            yy, xx = np.meshgrid(np.arange(m), np.arange(m), indexing="ij")
            g.append(np.stack([xx.ravel(), yy.ravel()], 1)); st.append(np.full((m * m, 1), stride))
        self.grid = np.concatenate(g).astype(np.float32); self.stride = np.concatenate(st).astype(np.float32)

    def run(self, bgr):
        h, w = bgr.shape[:2]; S = self.size; r = min(S / h, S / w)
        pad = np.full((S, S, 3), 114, np.uint8)
        rs = cv2.resize(bgr, (max(1, int(w * r)), max(1, int(h * r))), interpolation=cv2.INTER_LINEAR)
        pad[:rs.shape[0], :rs.shape[1]] = rs
        x = pad.transpose(2, 0, 1)[None].astype(np.float32)
        o = self.s.run(None, {self.n: x})[0][0]
        xy = (o[:, :2] + self.grid) * self.stride; wh = np.exp(o[:, 2:4]) * self.stride
        cls = o[:, 5:]; lab = cls.argmax(1); sc = o[:, 4] * cls[np.arange(len(cls)), lab]
        k = np.isin(lab, list(COCO)) & (sc >= 0.05)
        b = np.concatenate([xy[k] - wh[k] / 2, xy[k] + wh[k] / 2], 1) / r
        return b, sc[k], lab[k]


def nms_grupo(b, s, l, iou=0.45, minimo=0.05):
    keep = []
    for g in set(GRUPO.values()):
        idx = [i for i in range(len(s)) if GRUPO[int(l[i])] == g and s[i] >= minimo]
        if not idx: continue
        bb = b[idx]; rects = [[float(x1), float(y1), float(x2 - x1), float(y2 - y1)] for x1, y1, x2, y2 in bb]
        k = cv2.dnn.NMSBoxes(rects, [float(s[i]) for i in idx], minimo, iou)
        keep += [idx[int(j)] for j in np.array(k).ravel()]
    keep.sort(key=lambda i: -s[i])
    return b[keep], s[keep], l[keep]


def teselas(w, h, t, solape=0.25):
    if w <= t and h <= t: return [(0, 0, w, h)]
    paso = max(1, int(round(t * (1 - solape))))
    xs = list(range(0, max(1, w - t + 1), paso)); ys = list(range(0, max(1, h - t + 1), paso))
    if not xs or xs[-1] + t < w: xs.append(max(0, w - t))
    if not ys or ys[-1] + t < h: ys.append(max(0, h - t))
    return [(x, y, min(w, x + t), min(h, y + t)) for y in sorted(set(ys)) for x in sorted(set(xs))]


def detecta(det, bgr, zoom, minimo=0.05, iou=0.45):
    """Como YoloxDetector.detect de la app: zoom 0 = cuadro entero; si no, teselas de lado size/zoom."""
    h, w = bgr.shape[:2]
    if zoom <= 0: plan = [(0, 0, w, h)]
    else: plan = teselas(w, h, max(40, int(round(det.size / zoom))))
    B, S, L = [], [], []
    m = 2.0
    for x1, y1, x2, y2 in plan:
        b, s, l = det.run(bgr[y1:y2, x1:x2]); tw, th = x2 - x1, y2 - y1
        for i in range(len(s)):
            bx = b[i]
            # Invariante 1: una caja que toca el borde de una tesela interior miente.
            if (bx[0] <= m and x1 > 0) or (bx[1] <= m and y1 > 0) or (bx[2] >= tw - m and x2 < w) or (bx[3] >= th - m and y2 < h): continue
            B.append([bx[0] + x1, bx[1] + y1, bx[2] + x1, bx[3] + y1]); S.append(s[i]); L.append(l[i])
    if not S: return np.zeros((0, 4)), np.zeros(0), np.zeros(0, int)
    return nms_grupo(np.array(B), np.array(S), np.array(L), iou, minimo)


# --------------------------------------------------------------- etiqueta --
def _etiqueta_parte(par):
    ficheros, maestro, hilos = par
    det = Det(maestro, 640, hilos)
    out = {}
    for f in ficheros:
        img = cv2.imread(f)
        if img is None: continue
        h, w = img.shape[:2]
        # Cuadro entero para lo grande; teselas ampliadas para lo pequeño.
        z = 640 / 320 if w <= 640 else 1.0
        b1, s1, l1 = detecta(det, img, 0, 0.25)
        b2, s2, l2 = detecta(det, img, z, 0.25)
        b, s, l = nms_grupo(np.concatenate([b1, b2]), np.concatenate([s1, s2]), np.concatenate([l1, l2]), 0.5, 0.25)
        b[:, [0, 2]] = b[:, [0, 2]].clip(0, w); b[:, [1, 3]] = b[:, [1, 3]].clip(0, h)
        out[os.path.basename(f)] = {"w": w, "h": h, "cajas": [[*map(float, b[i]), float(s[i]), int(l[i])] for i in range(len(s))]}
    return out


def etiqueta(args):
    fs = sorted(glob.glob(os.path.join(args.dir, "*.jpg")))
    partes = [fs[i::args.procesos] for i in range(args.procesos)]
    t0 = time.time(); todo = {}
    with cf.ProcessPoolExecutor(args.procesos) as ex:
        for r in ex.map(_etiqueta_parte, [(p, args.maestro, args.hilos) for p in partes]): todo.update(r)
    json.dump(todo, open(os.path.join(args.dir, "etiquetas.json"), "w"))
    seguras = sum(1 for v in todo.values() for c in v["cajas"] if c[4] >= 0.5)
    print(f"{len(todo)} fotogramas etiquetados en {time.time() - t0:.0f} s · {seguras} objetos seguros", flush=True)


# ---------------------------------------------------------------- entrena --
def _yolox_nano(ruta_pesos):
    sys.path.insert(0, os.environ.get("YOLOX_SRC", "yolox-src"))
    import torch
    sys.path.insert(0, os.path.join(os.environ.get("YOLOX_SRC", "yolox-src"), "exps", "default"))
    from yolox_nano import Exp
    exp = Exp(); model = exp.get_model()
    ck = torch.load(ruta_pesos, map_location="cpu")
    model.load_state_dict(ck.get("model", ck), strict=True)
    return exp, model


def particion(nombres, semilla=1, frac=0.2):
    """Evaluación con CÁMARAS no vistas: se reparte por nombre de fichero (una cámara, un fichero)."""
    r = random.Random(semilla); ns = sorted(nombres); r.shuffle(ns)
    k = int(len(ns) * frac)
    return set(ns[k:]), set(ns[:k])


def separa(e):
    """Etiquetas seguras (≥ 0,5) y dudosas (0,3-0,5). Lo pequeño en las franjas de arriba y abajo,
    donde van los rótulos sobreimpresos, pasa a dudoso: el maestro también confunde alguna cifra
    con un coche, y un coche lejano de verdad ahí no debe enseñarse como fondo."""
    h = e["h"]; seg, dud = [], []
    for c in e["cajas"]:
        if c[4] < 0.3: continue
        franja = (c[3] <= 0.1 * h or c[1] >= 0.9 * h) and (c[3] - c[1]) < 0.08 * h
        (dud if c[4] < 0.5 or franja else seg).append(c)
    return seg, dud


class Datos:
    """Vistas de entrenamiento: cuadro entero (como la app en modo cuadro entero) o un recorte del
    tamaño de una tesela ×2 (160-320 px) ampliado a 416, con noche, compresión y espejo."""
    def __init__(self, d, etiquetas, nombres, size=416):
        self.d = d; self.e = etiquetas; self.n = sorted(nombres); self.size = size

    def __len__(self): return len(self.n) * 2

    def vista(self, i, rnd):
        nom = self.n[i % len(self.n)]
        img = cv2.imread(os.path.join(self.d, nom)); h, w = img.shape[:2]
        cajas, dudosas = separa(self.e[nom])
        if i >= len(self.n) and min(w, h) > 200:
            lado = rnd.randint(160, min(320, min(w, h)))
            # La mitad de las veces, centrado en un objeto: hay pocos y son pequeños.
            if cajas and rnd.random() < 0.5:
                c = rnd.choice(cajas); cx = (c[0] + c[2]) / 2; cy = (c[1] + c[3]) / 2
                x0 = int(np.clip(cx - rnd.uniform(0.2, 0.8) * lado, 0, w - lado)); y0 = int(np.clip(cy - rnd.uniform(0.2, 0.8) * lado, 0, h - lado))
            else:
                x0 = rnd.randint(0, w - lado); y0 = rnd.randint(0, h - lado)
            x1, y1 = x0 + lado, y0 + lado
        else:
            x0, y0, x1, y1 = 0, 0, w, h
        rec = img[y0:y1, x0:x1]
        sal = []
        for c in cajas:
            a = max(0, min(c[2], x1) - max(c[0], x0)) * max(0, min(c[3], y1) - max(c[1], y0))
            area = (c[2] - c[0]) * (c[3] - c[1])
            if area <= 0 or a / area < 0.6: continue
            sal.append([max(c[0], x0) - x0, max(c[1], y0) - y0, min(c[2], x1) - x0, min(c[3], y1) - y0, c[5]])
        # Lo dudoso se tapa con gris: ni positivo ni negativo.
        for c in dudosas:
            a0, b0, a1, b1 = [int(round(v)) for v in (c[0] - x0, c[1] - y0, c[2] - x0, c[3] - y0)]
            if a1 > 0 and b1 > 0 and a0 < rec.shape[1] and b0 < rec.shape[0]:
                if not any(s[0] < a1 and s[2] > a0 and s[1] < b1 and s[3] > b0 for s in sal):
                    rec = rec.copy(); rec[max(0, b0):b1, max(0, a0):a1] = 114
        rec = rec.copy()
        # Noche y compresión: la mitad de las cámaras se ven así.
        if rnd.random() < 0.25:
            g = rnd.uniform(1.5, 2.5); rec = (255 * (rec / 255.0) ** g).astype(np.uint8)
        if rnd.random() < 0.4:
            ok, enc = cv2.imencode(".jpg", rec, [cv2.IMWRITE_JPEG_QUALITY, rnd.randint(25, 70)]); rec = cv2.imdecode(enc, 1)
        hsv = cv2.cvtColor(rec, cv2.COLOR_BGR2HSV).astype(np.float32)
        hsv[..., 0] = (hsv[..., 0] + rnd.uniform(-5, 5)) % 180; hsv[..., 1] *= rnd.uniform(0.7, 1.3); hsv[..., 2] *= rnd.uniform(0.7, 1.3)
        rec = cv2.cvtColor(hsv.clip(0, 255).astype(np.uint8), cv2.COLOR_HSV2BGR)
        if rnd.random() < 0.5:
            rec = rec[:, ::-1].copy(); W = rec.shape[1]
            sal = [[W - s[2], s[1], W - s[0], s[3], s[4]] for s in sal]
        S = self.size; hh, ww = rec.shape[:2]; r = min(S / hh, S / ww)
        pad = np.full((S, S, 3), 114, np.uint8)
        rs = cv2.resize(rec, (max(1, int(ww * r)), max(1, int(hh * r)))); pad[:rs.shape[0], :rs.shape[1]] = rs
        t = np.zeros((120, 5), np.float32); k = 0
        for s in sal:
            bw = (s[2] - s[0]) * r; bh = (s[3] - s[1]) * r
            if bw < 2 or bh < 2 or k >= 120: continue
            t[k] = [s[4], (s[0] + s[2]) / 2 * r, (s[1] + s[3]) / 2 * r, bw, bh]; k += 1
        return pad.transpose(2, 0, 1).astype(np.float32), t


def entrena(args):
    import torch
    torch.set_num_threads(args.hilos)
    etiq = json.load(open(os.path.join(args.dir, "etiquetas.json")))
    tren, prueba = particion(etiq.keys(), args.semilla)
    json.dump(sorted(prueba), open(os.path.join(args.dir, "prueba.json"), "w"))
    exp, model = _yolox_nano(args.pesos)
    model.train(); model.head.use_l1 = False
    # Con pocos datos, las estadísticas de BatchNorm se quedan como vienen de
    # COCO: recalcularlas con lotes de 16 fotogramas de tráfico (mucho relleno
    # gris, noche) descoloca la red entera.
    def bn_quieto(m):
        if isinstance(m, torch.nn.BatchNorm2d): m.eval()
    model.apply(bn_quieto)
    from yolox.utils import ModelEMA
    ema = ModelEMA(model, 0.9998)
    pg0, pg1, pg2 = [], [], []
    for k, v in model.named_modules():
        if hasattr(v, "bias") and isinstance(v.bias, torch.nn.Parameter): pg2.append(v.bias)
        if isinstance(v, torch.nn.BatchNorm2d) or "bn" in k: pg0.append(v.weight)
        elif hasattr(v, "weight") and isinstance(v.weight, torch.nn.Parameter): pg1.append(v.weight)
    opt = torch.optim.SGD(pg0, lr=args.lr, momentum=0.9, nesterov=True)
    opt.add_param_group({"params": pg1, "weight_decay": 5e-4}); opt.add_param_group({"params": pg2})
    datos = Datos(args.dir, etiq, tren)
    rnd = random.Random(args.semilla)
    pasos_epoca = max(1, len(datos) // args.lote); total = pasos_epoca * args.epocas
    t0 = time.time(); paso = 0
    for ep in range(args.epocas):
        orden = list(range(len(datos))); rnd.shuffle(orden)
        media = 0.0
        for j in range(pasos_epoca):
            lote = [datos.vista(i, rnd) for i in orden[j * args.lote:(j + 1) * args.lote]]
            x = torch.from_numpy(np.stack([a for a, _ in lote])); t = torch.from_numpy(np.stack([b for _, b in lote]))
            # Calentamiento y coseno.
            lr = args.lr * (paso / max(1, pasos_epoca) if paso < pasos_epoca else 0.5 * (1 + math.cos(math.pi * paso / total)))
            for g in opt.param_groups: g["lr"] = lr
            out = model(x, t); loss = out["total_loss"]
            opt.zero_grad(); loss.backward()
            # Con pocos objetos por lote, la pérdida de «objeto» se divide por
            # un número de aciertos muy pequeño y el gradiente se dispara.
            norma = float(torch.nn.utils.clip_grad_norm_(model.parameters(), 10.0))
            opt.step(); ema.update(model)
            media = media * 0.95 + float(loss.detach()) * 0.05 if j else float(loss.detach())
            if args.depura and paso < 30:
                print(f"paso {paso} lr {lr:.5f} grad {norma:.1f} " + " ".join(f"{k} {float(v):.2f}" for k, v in out.items()), flush=True)
            paso += 1
            if (time.time() - t0) / 60 > args.minutos: break
        print(f"época {ep + 1}/{args.epocas} · pérdida {media:.3f} · {(time.time() - t0) / 60:.1f} min", flush=True)
        if (time.time() - t0) / 60 > args.minutos:
            print("tiempo agotado: se para aquí", flush=True); break
    torch.save({"model": ema.ema.state_dict()}, args.salida)
    print("pesos:", args.salida, flush=True)


# ---------------------------------------------------------------- exporta --
def exporta(args):
    import torch
    _, model = _yolox_nano(args.pesos)
    model.eval(); model.head.decode_in_inference = False
    x = torch.zeros(1, 3, 416, 416)
    torch.onnx.export(model, x, args.salida, input_names=["images"], output_names=["output"], opset_version=11, dynamo=False)
    import onnxruntime as ort
    o = ort.InferenceSession(args.salida, providers=["CPUExecutionProvider"]).run(None, {"images": x.numpy()})[0]
    assert o.shape == (1, 3549, 85), o.shape
    print("onnx:", args.salida, o.shape, flush=True)


# ----------------------------------------------------------------- evalua --
def _iou(a, b):
    ix = max(0, min(a[2], b[2]) - max(a[0], b[0])); iy = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    i = ix * iy; u = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - i
    return i / u if u > 0 else 0


def evalua(args):
    etiq = json.load(open(os.path.join(args.dir, "etiquetas.json")))
    prueba = json.load(open(os.path.join(args.dir, "prueba.json"))) if os.path.exists(os.path.join(args.dir, "prueba.json")) else sorted(etiq)
    modelos = [(os.path.basename(m), Det(m, 416, args.hilos)) for m in args.modelos]
    tam = [("<12 px", 0, 12), ("12-24 px", 12, 24), ("24-48 px", 24, 48), (">48 px", 48, 1e9)]
    filas = []
    for nombre, det in modelos:
        for zoom in (0, 2):
            for corte in (0.3, 0.5):
                st = {"tp": 0, "fp": 0, "gt": 0}
                pt = {t[0]: [0, 0] for t in tam}; dn = {"día": [0, 0, 0], "noche": [0, 0, 0]}
                for nom in prueba:
                    img = cv2.imread(os.path.join(args.dir, nom))
                    if img is None: continue
                    noche = "noche" if np.median(cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)) < 70 else "día"
                    gt, dud = separa(etiq[nom])
                    b, s, l = detecta(det, img, zoom, 0.2)
                    pr = [(b[i], int(l[i])) for i in range(len(s)) if s[i] >= corte]
                    usadas = set()
                    for g in gt:
                        alto = g[3] - g[1]; umbral = 0.3 if alto < 16 else 0.5
                        mejor, k = 0, -1
                        for j, (bb, ll) in enumerate(pr):
                            if j in usadas or GRUPO[ll] != GRUPO[g[5]]: continue
                            v = _iou(g, bb)
                            if v > mejor: mejor, k = v, j
                        ok = mejor >= umbral
                        if ok: usadas.add(k)
                        st["gt"] += 1; st["tp"] += ok
                        for t in tam:
                            if t[1] <= alto < t[2]: pt[t[0]][0] += ok; pt[t[0]][1] += 1
                        dn[noche][0] += ok; dn[noche][1] += 1
                    for j, (bb, ll) in enumerate(pr):
                        if j in usadas: continue
                        if any(_iou(d, bb) > 0.3 for d in dud): continue
                        st["fp"] += 1; dn[noche][2] += 1
                rec = st["tp"] / max(1, st["gt"]); prec = st["tp"] / max(1, st["tp"] + st["fp"])
                filas.append((nombre, "cuadro entero" if zoom == 0 else "teselas ×2", corte, rec, prec, st, pt, dn))
                print(f"{nombre:22s} {('entero' if zoom == 0 else 'x2'):7s} ≥{corte} · recall {rec:.1%} · precisión {prec:.1%} · "
                      f"aciertos {st['tp']}/{st['gt']} · falsos {st['fp']} · " +
                      " ".join(f"{k} {v[0]}/{v[1]}" for k, v in pt.items()) + " · " +
                      " ".join(f"{k} {v[0]}/{v[1]} (falsos {v[2]})" for k, v in dn.items()), flush=True)
    # Las fotos de siempre: lo de cerca no se puede olvidar.
    for f in args.cerca or []:
        img = cv2.imread(f)
        if img is None: continue
        for nombre, det in modelos:
            b, s, l = detecta(det, img, 0, 0.2)
            print(f"cerca {os.path.basename(f):12s} {nombre:22s} " + ", ".join(f"{COCO[int(l[i])]} {s[i]:.2f}" for i in range(len(s)) if s[i] >= 0.3), flush=True)
    if args.informe:
        with open(args.informe, "w") as o:
            o.write("| modelo | modo | corte | recall | precisión | aciertos | falsos | por tamaño | día / noche |\n|---|---|---|---|---|---|---|---|---|\n")
            for n, m, c, r, p, st, pt, dn in filas:
                o.write(f"| {n} | {m} | {c} | {r:.1%} | {p:.1%} | {st['tp']}/{st['gt']} | {st['fp']} | " +
                        ", ".join(f"{k}: {v[0]}/{v[1]}" for k, v in pt.items()) + " | " +
                        ", ".join(f"{k}: {v[0]}/{v[1]}, falsos {v[2]}" for k, v in dn.items()) + " |\n")


def main():
    a = argparse.ArgumentParser(); sub = a.add_subparsers(dest="orden", required=True)
    p = sub.add_parser("muestra"); p.add_argument("dir"); p.add_argument("--n", type=int, default=1200); p.add_argument("--hilos", type=int, default=24); p.add_argument("--semilla", type=int, default=1)
    p = sub.add_parser("etiqueta"); p.add_argument("dir"); p.add_argument("--maestro", required=True); p.add_argument("--procesos", type=int, default=2); p.add_argument("--hilos", type=int, default=2)
    p = sub.add_parser("entrena"); p.add_argument("dir"); p.add_argument("--pesos", required=True); p.add_argument("--salida", required=True)
    p.add_argument("--epocas", type=int, default=15); p.add_argument("--lote", type=int, default=16); p.add_argument("--lr", type=float, default=0.002)
    p.add_argument("--minutos", type=float, default=150); p.add_argument("--hilos", type=int, default=4); p.add_argument("--semilla", type=int, default=1)
    p.add_argument("--depura", action="store_true")
    p = sub.add_parser("exporta"); p.add_argument("--pesos", required=True); p.add_argument("--salida", required=True)
    p = sub.add_parser("evalua"); p.add_argument("dir"); p.add_argument("modelos", nargs="+"); p.add_argument("--hilos", type=int, default=4)
    p.add_argument("--cerca", nargs="*"); p.add_argument("--informe")
    args = a.parse_args()
    {"muestra": muestra, "etiqueta": etiqueta, "entrena": entrena, "exporta": exporta, "evalua": evalua}[args.orden](args)


if __name__ == "__main__":
    main()
