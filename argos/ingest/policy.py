"""Validación de `spec` antes de abrir una fuente.

`POST /api/sources` acepta una cadena del usuario y se la pasa a
`cv2.VideoCapture` o a `urlopen`. Sin filtro, eso es un SSRF completo y sin
autenticar: FFmpeg abre `file:///etc/passwd` igual que abre RTSP, y una URL
apuntada a `169.254.169.254` lee credenciales de metadatos en la nube. El token
compartido no lo mitiga --- se imprime por consola y viaja en query para los
endpoints de media.

El filtro es una lista blanca, no una lista negra. Una lista negra de esquemas
peligrosos se queda corta el día que FFmpeg añade uno; una lista blanca falla
del lado seguro.
"""

from __future__ import annotations

import ipaddress
import os
import socket
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import urlparse

# Esquemas que tienen sentido para una cámara. Todo lo demás se rechaza, con
# `file:` a la cabeza: FFmpeg lo acepta y convierte cualquier ruta del servidor
# en un vídeo que el cliente puede reproducir.
ALLOWED_SCHEMES = frozenset({"rtsp", "rtsps", "http", "https", "push"})

# Puertos por defecto por esquema, para la comprobación de destino.
DEFAULT_PORTS = {"rtsp": 554, "rtsps": 322, "http": 80, "https": 443}


class SpecRejected(ValueError):
    """La fuente no pasa la validación. El mensaje es apto para el usuario."""


@dataclass(frozen=True)
class SpecPolicy:
    allow_private: bool = False
    allow_files: bool = False
    file_root: Path | None = None
    resolve_timeout: float = 2.0

    @classmethod
    def from_env(cls) -> "SpecPolicy":
        root = os.environ.get("ARGOS_FILE_ROOT", "").strip()
        return cls(
            # Una cámara IP en la LAN es el caso de uso normal, así que esto se
            # activa a menudo --- pero conscientemente y por variable de
            # entorno, no por defecto.
            allow_private=os.environ.get("ARGOS_ALLOW_PRIVATE", "") == "1",
            allow_files=bool(root),
            file_root=Path(root).resolve() if root else None,
        )


def _is_public(ip: str) -> bool:
    try:
        addr = ipaddress.ip_address(ip)
    except ValueError:
        return False
    return not (addr.is_private or addr.is_loopback or addr.is_link_local
                or addr.is_reserved or addr.is_multicast or addr.is_unspecified)


def _resolve_all(host: str, port: int, timeout: float) -> list[str]:
    """Todas las direcciones del nombre, no solo la primera.

    Un nombre puede resolver a una pública y a una privada a la vez. Validar
    solo la primera deja pasar el resto, y el cliente HTTP puede elegir
    cualquiera --- es la vía clásica para saltarse este tipo de comprobación.
    """
    socket.setdefaulttimeout(timeout)
    infos = socket.getaddrinfo(host, port, proto=socket.IPPROTO_TCP)
    return sorted({i[4][0] for i in infos})


def validate_spec(spec: str, policy: SpecPolicy | None = None) -> str:
    """Devuelve el `spec` si es aceptable; lanza `SpecRejected` si no.

    No reescribe la cadena: devolver algo distinto de lo validado abre la puerta
    a que se abra una cosa y se haya comprobado otra.
    """
    policy = policy or SpecPolicy.from_env()
    spec = spec.strip()
    if not spec:
        raise SpecRejected("spec vacío")
    if len(spec) > 2048:
        raise SpecRejected("spec demasiado largo")
    if any(c in spec for c in "\r\n\x00"):
        # Una nueva línea en una URL permite inyectar cabeceras en el cliente
        # HTTP subyacente.
        raise SpecRejected("spec con caracteres de control")
    # Y en forma percent-encoded. Comprobar solo los crudos deja pasar
    # `%0d%0a`, que cualquier capa que decodifique antes de construir la
    # petición convierte de nuevo en un salto de línea.
    low = spec.lower()
    if any(seq in low for seq in ("%0d", "%0a", "%00", "%09")):
        raise SpecRejected("spec con caracteres de control codificados")

    # Índice de dispositivo: "0", "1"...
    if spec.isdigit():
        if int(spec) > 16:
            raise SpecRejected("índice de dispositivo fuera de rango")
        return spec

    parsed = urlparse(spec)
    scheme = parsed.scheme.lower()

    if not scheme:
        # Ruta de fichero. Solo si hay una raíz configurada, y solo dentro.
        if not policy.allow_files or policy.file_root is None:
            raise SpecRejected(
                "las rutas de fichero están desactivadas; define ARGOS_FILE_ROOT "
                "para habilitarlas")
        target = (policy.file_root / spec).resolve()
        # `resolve()` sigue enlaces simbólicos, así que esta comprobación
        # también cubre un symlink que apunte fuera de la raíz.
        if not target.is_relative_to(policy.file_root):
            raise SpecRejected("la ruta sale del directorio permitido")
        if not target.is_file():
            raise SpecRejected("el fichero no existe")
        return str(target)

    if scheme not in ALLOWED_SCHEMES:
        raise SpecRejected(
            f"esquema '{scheme}' no permitido; se aceptan "
            f"{', '.join(sorted(ALLOWED_SCHEMES))}, una ruta o un índice")

    if scheme == "push":
        name = (parsed.netloc + parsed.path).strip("/")
        if not name or not all(c.isalnum() or c in "-_." for c in name):
            raise SpecRejected("nombre de fuente push inválido")
        return spec

    host = parsed.hostname
    if not host:
        raise SpecRejected("la URL no tiene host")
    port = parsed.port or DEFAULT_PORTS.get(scheme, 0)

    try:
        addrs = _resolve_all(host, port, policy.resolve_timeout)
    except OSError as e:
        raise SpecRejected(f"no se pudo resolver '{host}': {e}") from e
    if not addrs:
        raise SpecRejected(f"'{host}' no resuelve a ninguna dirección")

    if not policy.allow_private:
        private = [a for a in addrs if not _is_public(a)]
        if private:
            raise SpecRejected(
                f"'{host}' apunta a una dirección interna ({private[0]}). "
                "Si es una cámara de tu red, arranca con ARGOS_ALLOW_PRIVATE=1")

    return spec


__all__ = ["validate_spec", "SpecPolicy", "SpecRejected", "ALLOWED_SCHEMES"]
