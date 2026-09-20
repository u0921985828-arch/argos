"""Pruebas de la validación de fuentes y del middleware de token.

Estos dos módulos no tenían ninguna prueba, y son exactamente los que deciden
qué entra en el sistema. Las pruebas van contra los vectores concretos que la
auditoría encontró, no contra el camino feliz: un caso feliz que pasa no dice
nada sobre si `file://` sigue abriéndose.
"""

from __future__ import annotations

import pytest

from argos.ingest.policy import SpecPolicy, SpecRejected, validate_spec


# --------------------------------------------------------------------------- #
#  Validación de spec
# --------------------------------------------------------------------------- #

STRICT = SpecPolicy(allow_private=False, allow_files=False)


@pytest.mark.parametrize("spec", [
    "file:///etc/passwd",
    "file://localhost/etc/shadow",
    "gopher://evil.example/",
    "ftp://evil.example/x.avi",
    "data:text/plain;base64,AAAA",
    "jar:file:///tmp/x.zip!/a",
])
def test_rechaza_esquemas_fuera_de_la_lista_blanca(spec):
    """La lista es blanca: lo que no está, no entra.

    `file:` es el que importa --- FFmpeg lo acepta y convierte cualquier ruta
    del servidor en un vídeo reproducible por el cliente.
    """
    with pytest.raises(SpecRejected):
        validate_spec(spec, STRICT)


@pytest.mark.parametrize("spec", [
    "http://169.254.169.254/latest/meta-data/",   # metadatos en la nube
    "http://127.0.0.1:8000/api/sources",          # la propia API
    "http://[::1]:8000/",                         # loopback IPv6
    "http://192.168.1.50/snap.jpg",
    "http://10.0.0.5/snap.jpg",
    "rtsp://172.16.3.9/stream",
])
def test_rechaza_destinos_internos(spec):
    with pytest.raises(SpecRejected):
        validate_spec(spec, STRICT)


def test_permite_lo_interno_solo_con_la_bandera():
    """El caso de uso normal es una cámara en la LAN, pero se habilita a mano."""
    lax = SpecPolicy(allow_private=True)
    assert validate_spec("http://192.168.1.50/snap.jpg", lax)
    with pytest.raises(SpecRejected):
        validate_spec("http://192.168.1.50/snap.jpg", STRICT)


@pytest.mark.parametrize("spec", [
    "http://ex.example/a\r\nHost: evil",
    "http://ex.example/a%0d%0aHost:%20evil",
    "http://ex.example/x%00.jpg",
])
def test_rechaza_control_crudo_y_codificado(spec):
    """Comprobar solo los caracteres crudos deja pasar `%0d%0a`.

    Cualquier capa que decodifique antes de construir la petición lo convierte
    de nuevo en un salto de línea, y ahí se inyectan cabeceras.
    """
    with pytest.raises(SpecRejected):
        validate_spec(spec, STRICT)


def test_rutas_de_fichero_confinadas_a_la_raiz(tmp_path):
    dentro = tmp_path / "clip.mp4"
    dentro.write_bytes(b"x")
    pol = SpecPolicy(allow_files=True, file_root=tmp_path)

    assert validate_spec("clip.mp4", pol) == str(dentro.resolve())
    with pytest.raises(SpecRejected):
        validate_spec("../../etc/passwd", pol)
    with pytest.raises(SpecRejected):
        validate_spec("no_existe.mp4", pol)


def test_symlink_fuera_de_la_raiz_se_rechaza(tmp_path):
    """`resolve()` sigue el enlace, así que la comprobación lo cubre.

    Sin esto, un enlace dentro de la raíz apuntando a `/etc` burlaría el
    confinamiento sin que la cadena contenga ningún `..`.
    """
    fuera = tmp_path.parent / "secreto.mp4"
    fuera.write_bytes(b"x")
    raiz = tmp_path / "media"
    raiz.mkdir()
    enlace = raiz / "atajo.mp4"
    try:
        enlace.symlink_to(fuera)
    except (OSError, NotImplementedError):
        pytest.skip("el sistema no permite enlaces simbólicos")
    with pytest.raises(SpecRejected):
        validate_spec("atajo.mp4", SpecPolicy(allow_files=True, file_root=raiz))


def test_acepta_lo_legitimo():
    assert validate_spec("push://telefono", STRICT) == "push://telefono"
    assert validate_spec("0", STRICT) == "0"
    with pytest.raises(SpecRejected):
        validate_spec("99", STRICT)          # índice de dispositivo absurdo
    with pytest.raises(SpecRejected):
        validate_spec("push://../etc", STRICT)


def test_rechaza_entradas_degeneradas():
    for bad in ("", "   ", "x" * 3000):
        with pytest.raises(SpecRejected):
            validate_spec(bad, STRICT)


# --------------------------------------------------------------------------- #
#  Middleware de token
# --------------------------------------------------------------------------- #

def test_docs_no_son_publicas():
    """`/docs` y `/openapi.json` estaban en la lista pública.

    Publicaban la superficie completa de la API antes de pedir el token: rutas,
    parámetros y forma de las sesiones.
    """
    from argos.api.auth import PUBLIC_PATHS
    for path in ("/docs", "/openapi.json", "/redoc"):
        assert path not in PUBLIC_PATHS


def test_token_por_query_solo_en_media():
    """El token en query acaba en el historial del navegador.

    Se tolera solo donde `<img>` y `<video>` no pueden enviar cabeceras; en el
    resto de la API sería regalarlo.
    """
    from argos.api.auth import AuthConfig, TokenAuthMiddleware
    mw = TokenAuthMiddleware(app=None, cfg=AuthConfig(token="secreto"))
    assert mw._is_media("/api/sources/abc/preview")
    assert mw._is_media("/api/sources/abc/stream.mjpg")
    assert not mw._is_media("/api/sources")
    assert not mw._is_media("/api/plan")


def test_el_arranque_falla_sin_token_fuera_de_localhost(monkeypatch):
    """Escuchar en 0.0.0.0 sin token es abrir las cámaras a la red.

    Preferimos no arrancar a arrancar inseguro: un fallo ruidoso al inicio se
    corrige; un despliegue abierto no se nota.
    """
    from argos.api.auth import resolve_auth
    monkeypatch.delenv("ARGOS_TOKEN", raising=False)
    with pytest.raises(SystemExit):
        resolve_auth(bind_host="0.0.0.0")


def test_comparacion_de_token_en_tiempo_constante():
    from argos.api.auth import AuthConfig, TokenAuthMiddleware
    mw = TokenAuthMiddleware(app=None, cfg=AuthConfig(token="secreto"))
    assert mw._valid("secreto")
    assert not mw._valid("secretz")
    assert not mw._valid("")
    assert not mw._valid(None)
