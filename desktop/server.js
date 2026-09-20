/*
 * ARGOS · servidor local del lanzador de escritorio
 * ---------------------------------------------------------------------------
 * Treinta líneas de servidor estático. No es "el módulo de Python" ni tiene
 * nada que ver con el análisis: existe por un motivo único y concreto.
 *
 * `getDisplayMedia` --- la captura de pantalla, que es la fuente más útil de
 * todas --- exige un **contexto seguro**. `http://127.0.0.1` cuenta como tal en
 * todos los navegadores; `file://` no, o no de forma fiable según cuál. Sin
 * esto, la app de escritorio abriría y la opción de región de pantalla estaría
 * muerta en la mitad de los equipos.
 *
 * Se ata a la interfaz de loopback a propósito: nada de 0.0.0.0. No hay
 * autenticación porque no la necesita --- no escucha en la red.
 */

"use strict";

const http = require("http");
const fs = require("fs");
const path = require("path");

const ROOT = __dirname;
const FILE = process.env.ARGOS_FILE || path.join(ROOT, "argos.html");

// Cabeceras de aislamiento de origen: habilitan SharedArrayBuffer, y con él
// ONNX Runtime puede usar WASM multihilo. Es la diferencia entre una tesela en
// 120 ms y una en 20 ms, y no cuesta nada activarlo aquí.
const ISOLATION = {
  "Cross-Origin-Opener-Policy": "same-origin",
  "Cross-Origin-Embedder-Policy": "credentialless",
};

const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "application/javascript; charset=utf-8",
  ".onnx": "application/octet-stream",
  ".png": "image/png",
  ".ico": "image/x-icon",
  ".json": "application/json; charset=utf-8",
};

const server = http.createServer((req, res) => {
  const url = decodeURIComponent((req.url || "/").split("?")[0]);
  let file = url === "/" ? FILE : path.join(ROOT, path.normalize(url).replace(/^([/\\])+/, ""));

  // Un recorrido de rutas aquí solo se lo haría el propio usuario, pero cuesta
  // una línea impedirlo y evita que un `../` en una URL pegada lea el disco.
  if (!file.startsWith(ROOT) && file !== FILE) {
    res.writeHead(403).end("prohibido");
    return;
  }

  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404, {"Content-Type": "text/plain; charset=utf-8"});
      res.end("no encontrado");
      return;
    }
    res.writeHead(200, {
      "Content-Type": TYPES[path.extname(file).toLowerCase()] || "application/octet-stream",
      "Cache-Control": "no-store",
      ...ISOLATION,
    });
    res.end(data);
  });
});

// Puerto 0 = el sistema elige uno libre. Fijar un número garantiza chocar con
// algo tarde o temprano, y el lanzador necesita saber cuál ha tocado.
server.listen(Number(process.env.ARGOS_PORT) || 0, "127.0.0.1", () => {
  const {port} = server.address();
  // El lanzador lee esta línea para saber a dónde apuntar el navegador.
  process.stdout.write(`ARGOS_READY ${port}\n`);
});

// Ciclo de vida.
//
// La primera versión moría al cerrarse la entrada estándar, y eso parecía una
// forma elegante de detectar que el lanzador ha terminado. No lo es: arrancar
// el proceso en segundo plano ya cierra stdin, así que el servidor se apagaba
// nada más nacer y el navegador abría contra un puerto muerto. El síntoma era
// "la app no carga", que no apunta a nada.
//
// El lanzador ya mata el proceso por PID al cerrarse la ventana, que es una
// señal explícita y no una inferencia. La vigilancia de stdin queda como opción
// para quien lo arranque con una tubería abierta a propósito.
if (process.env.ARGOS_WATCH_STDIN === "1") {
  process.stdin.on("end", () => server.close(() => process.exit(0)));
  process.stdin.resume();
}

for (const sig of ["SIGINT", "SIGTERM"]) {
  process.on(sig, () => server.close(() => process.exit(0)));
}
