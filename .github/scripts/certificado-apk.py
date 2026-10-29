#!/usr/bin/env python3
"""SHA-256 del certificado con que va firmado un APK (esquema de firma v3 o v2), sin apksigner:
su salida cambia entre versiones de build-tools y una línea que no casa se leía como «sin firma»."""
import hashlib, struct, sys


def cert(path):
    d = open(path, "rb").read()
    e = d.rfind(b"PK\x05\x06")
    cd = struct.unpack("<I", d[e + 16:e + 20])[0]
    if d[cd - 16:cd] != b"APK Sig Block 42":
        return None
    size = struct.unpack("<Q", d[cd - 24:cd - 16])[0]
    p, end, blocks = cd - size - 8 + 8, cd - 24, {}
    while p < end:
        ln, = struct.unpack("<Q", d[p:p + 8])
        bid, = struct.unpack("<I", d[p + 8:p + 12])
        blocks[bid] = d[p + 12:p + 8 + ln]
        p += 8 + ln

    def lp(b, o):
        n, = struct.unpack("<I", b[o:o + 4])
        return b[o + 4:o + 4 + n], o + 4 + n

    for bid in (0xF05368C0, 0x7109871A):  # v3, v2
        if bid in blocks:
            signers, _ = lp(blocks[bid], 0)
            signer, _ = lp(signers, 0)
            signed, _ = lp(signer, 0)
            _, o = lp(signed, 0)          # resúmenes
            certs, _ = lp(signed, o)
            first, _ = lp(certs, 0)
            return hashlib.sha256(first).hexdigest()
    return None


if __name__ == "__main__":
    print(cert(sys.argv[1]) or "")
