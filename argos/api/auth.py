"""Autenticación por token para la API.

Hasta ahora `main.py` no llevaba ninguna: era una herramienta de red local y
estaba documentado como tal. Eso deja de ser aceptable en cuanto el servicio
escucha en algo que no sea `127.0.0.1`, porque lo que queda expuesto no es una
API cualquiera: es un feed de cámara en vivo y un índice de búsqueda sobre
personas.

Decisiones que no son las de un tutorial:

*   **Se rechaza arrancar sin token si el servicio no es solo local.** Un aviso
    en el registro se ignora; un fallo al arrancar, no. La alternativa —generar
    un token por defecto— es peor: acaba en producción sin que nadie lo cambie.
*   **Comparación en tiempo constante.** Un `==` sobre cadenas sale antes en el
    primer byte distinto, y esa diferencia es medible por red. `compare_digest`
    cuesta lo mismo escribirlo.
*   **Se admite cabecera y parámetro de consulta, pero no por igual.** Un `<img>`
    o un `<video>` no pueden llevar cabeceras, así que las rutas de medios
    necesitan el parámetro; para el resto se exige cabecera, porque los
    parámetros acaban en registros de acceso y en el historial del navegador.
*   **Limitación de intentos.** Sin ella, un token de 32 caracteres sigue siendo
    forzable si se permiten miles de intentos por segundo.
"""

from __future__ import annotations

import hmac
import os
import secrets
import time
from collections import deque
from dataclasses import dataclass, field

from fastapi import HTTPException, Request
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.responses import JSONResponse

# Rutas que sirven bytes a etiquetas HTML incapaces de enviar cabeceras.
MEDIA_PREFIXES = ("/api/sources/", )
MEDIA_SUFFIXES = ("/preview", "/stream.mjpg")
MEDIA_CONTAINS = ("/file/", )

# Accesibles sin credencial: la interfaz y el sondeo de vida. `/api/health` no
# revela nada que no revele ya el hecho de que el puerto responde.
# `/docs`, `/openapi.json` y `/redoc` estaban aquí y no deben estarlo: publican
# la superficie completa de la API --- rutas, parámetros, formas de sesión ---
# antes de pedir el token. Un atacante no necesitaba adivinar nada.
PUBLIC_PATHS = {"/", "/engine.js", "/solver.js", "/photogrammetry.js",
                "/detector.js", "/brain.js", "/archive.js", "/identity.js",
                "/api/health", "/favicon.ico"}


@dataclass
class AuthConfig:
    token: str = ""
    bind_host: str = "127.0.0.1"
    max_attempts: int = 20            # intentos fallidos por ventana
    window_s: float = 60.0
    ban_s: float = 300.0

    @property
    def local_only(self) -> bool:
        return self.bind_host in ("127.0.0.1", "localhost", "::1")


@dataclass
class _Bucket:
    fails: deque = field(default_factory=deque)
    banned_until: float = 0.0


class TokenAuthMiddleware(BaseHTTPMiddleware):
    def __init__(self, app, cfg: AuthConfig):
        super().__init__(app)
        self.cfg = cfg
        self._buckets: dict[str, _Bucket] = {}

    # ------------------------------------------------------------------ #

    @staticmethod
    def _is_media(path: str) -> bool:
        return (path.startswith(MEDIA_PREFIXES)
                and (path.endswith(MEDIA_SUFFIXES)
                     or any(c in path for c in MEDIA_CONTAINS)))

    def _client(self, request: Request) -> str:
        return request.client.host if request.client else "desconocido"

    def _banned(self, key: str) -> bool:
        b = self._buckets.get(key)
        return bool(b and b.banned_until > time.monotonic())

    def _record_failure(self, key: str) -> None:
        now = time.monotonic()
        b = self._buckets.setdefault(key, _Bucket())
        b.fails.append(now)
        while b.fails and now - b.fails[0] > self.cfg.window_s:
            b.fails.popleft()
        if len(b.fails) >= self.cfg.max_attempts:
            b.banned_until = now + self.cfg.ban_s
            b.fails.clear()

    def _valid(self, presented: str | None) -> bool:
        if not presented:
            return False
        # Comparación en tiempo constante sobre bytes.
        return hmac.compare_digest(presented.encode(), self.cfg.token.encode())

    # ------------------------------------------------------------------ #

    async def dispatch(self, request: Request, call_next):
        if not self.cfg.token:
            return await call_next(request)

        path = request.url.path
        if path in PUBLIC_PATHS or request.method == "OPTIONS":
            return await call_next(request)

        key = self._client(request)
        if self._banned(key):
            return JSONResponse(
                {"detail": "demasiados intentos fallidos; espera unos minutos"},
                status_code=429)

        presented = None
        header = request.headers.get("authorization", "")
        if header.lower().startswith("bearer "):
            presented = header[7:].strip()
        elif request.headers.get("x-argos-token"):
            presented = request.headers["x-argos-token"].strip()
        elif self._is_media(path):
            # Solo aquí: <img> y <video> no pueden enviar cabeceras.
            presented = request.query_params.get("t")

        if not self._valid(presented):
            self._record_failure(key)
            return JSONResponse(
                {"detail": "token no válido o ausente"},
                status_code=401,
                headers={"WWW-Authenticate": "Bearer"})

        return await call_next(request)


def resolve_auth(bind_host: str | None = None) -> AuthConfig:
    """Lee la configuración del entorno y decide si arrancar es seguro.

    `ARGOS_TOKEN` fija el token. `ARGOS_TOKEN=generate` crea uno y lo imprime,
    que es lo cómodo para una prueba. Sin token y escuchando fuera de localhost,
    se aborta: es la única forma de que la decisión sea consciente.
    """
    host = bind_host or os.environ.get("ARGOS_HOST", "127.0.0.1")
    token = os.environ.get("ARGOS_TOKEN", "").strip()
    cfg = AuthConfig(token=token, bind_host=host)

    if token.lower() == "generate":
        cfg.token = secrets.token_urlsafe(24)
        print("\n" + "=" * 62)
        print("  Token de acceso generado para esta sesión:")
        print(f"    {cfg.token}")
        print("  Cabecera:  Authorization: Bearer <token>")
        print("=" * 62 + "\n")
        return cfg

    if not cfg.token and not cfg.local_only:
        raise SystemExit(
            "\nARGOS se ha configurado para escuchar en "
            f"'{host}', que no es solo local, y no hay token.\n"
            "Eso expondría el vídeo en vivo y el índice de búsqueda a "
            "cualquiera que alcance el puerto.\n\n"
            "  ARGOS_TOKEN=generate   genera uno y lo imprime al arrancar\n"
            "  ARGOS_TOKEN=<secreto>  usa el tuyo\n"
            "  --host 127.0.0.1       solo local, sin token\n")
    return cfg
