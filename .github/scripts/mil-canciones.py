#!/usr/bin/env python3
"""Biblioteca grande para medir Surco: N MP3 cortos con etiquetas ID3v2.4 distintas (título,
artista, álbum, género, año, pista), en un .tar para copiarlos al emulador de una vez.

Uso: mil-canciones.py SILENCIO.mp3 N SALIDA.tar
SILENCIO.mp3: un MP3 corto sin etiquetas (lo hace ffmpeg); se repiten sus tramas en cada archivo.
"""
import io, random, sys, tarfile

def synchsafe(n):  # en ID3v2.4 los tamaños van en 4 × 7 bits
    return bytes([(n >> 21) & 0x7F, (n >> 14) & 0x7F, (n >> 7) & 0x7F, n & 0x7F])

def frame(fid, text):
    data = b"\x03" + text.encode("utf-8")  # 3 = UTF-8 (lo leen jaudiotagger y MediaStore)
    return fid.encode() + synchsafe(len(data)) + b"\x00\x00" + data

def tag(frames):
    body = b"".join(frames)
    return b"ID3\x04\x00\x00" + synchsafe(len(body)) + body  # v2.4: con UTF-8

silence, total, out = open(sys.argv[1], "rb").read(), int(sys.argv[2]), sys.argv[3]
rnd = random.Random(7)
genres = ["Rock", "Jazz", "Pop", "Dub", "Reggae", "Techno", "House", "Soul", "Funk", "Clásica", "Flamenco", "Hip hop"]
words = ["luz", "noche", "río", "camino", "fuego", "mar", "viento", "sombra", "cielo", "tierra", "eco", "sal",
         "night", "river", "fire", "dream", "echo", "stone", "rain", "gold", "heart", "dust", "wave", "line"]
with tarfile.open(out, "w") as tf:
    for i in range(total):
        artist = f"Artista {i % 600:03d}"
        album = f"Álbum {i % 1200:04d}"
        title = " ".join(rnd.choice(words) for _ in range(rnd.randint(1, 4))).capitalize() + f" {i}"
        t = tag([frame("TIT2", title), frame("TPE1", artist), frame("TALB", album),
                 frame("TCON", rnd.choice(genres)), frame("TDRC", str(1960 + i % 65)),
                 frame("TRCK", str(i % 12 + 1))])
        data = t + silence
        info = tarfile.TarInfo(f"{i // 1000:02d}/{i:05d}.mp3")
        info.size = len(data)
        tf.addfile(info, io.BytesIO(data))
print(f"{total} canciones en {out}")
