# ARGOS · aplicación de escritorio

Ventana propia, icono propio, entrada propia en la barra de tareas. **Sin Electron**:
usa el modo `--app` del navegador que ya tienes, así que la descarga son 200 KB en vez
de 150 MB.

## Instalar (Windows)

Doble clic en **INSTALAR.cmd**. Copia a `%LOCALAPPDATA%\ARGOS` y deja el acceso directo
en el Escritorio.

No toca el registro, no pide administrador y **se desinstala borrando la carpeta**: no
hay estado escondido en ningún otro sitio.

## Linux y macOS

```bash
./argos.sh
```

## Qué hace el lanzador

1. Levanta un servidor estático en `127.0.0.1` con **puerto libre elegido por el
   sistema** (fijar un número garantiza chocar con algo tarde o temprano).
2. Abre el navegador en modo `--app`, con **perfil propio** para que ARGOS no herede
   extensiones ni sesiones del navegador del usuario.
3. Al cerrar la ventana, mata el servidor. Nada queda escuchando.

### Por qué hay un servidor si la herramienta es local

No es el módulo de análisis — son treinta líneas que sirven un fichero. Existe por dos
motivos concretos:

**Contexto seguro.** `getDisplayMedia`, la captura de pantalla, lo exige.
`http://127.0.0.1` cuenta como tal en todos los navegadores; `file://` no de forma
fiable, y sin esto la fuente más útil quedaría muerta en la mitad de los equipos.

**Aislamiento de origen.** El servidor envía las cabeceras `Cross-Origin-Opener-Policy`
y `Cross-Origin-Embedder-Policy`, que habilitan `SharedArrayBuffer` — y con él, **WASM
multihilo en ONNX Runtime**. Es la diferencia entre una tesela en 120 ms y una en unos
20 ms. Desde `file://` esas cabeceras no existen y el detector va con un solo hilo.

Se ata a loopback a propósito: nada de `0.0.0.0`, y por eso no lleva autenticación —
no escucha en la red.

## Sigue funcionando sin nada de esto

`argos.html` abierto directamente sigue valiendo. Pierdes contexto seguro fiable para la
captura de pantalla y el multihilo del detector; todo lo demás va igual.
