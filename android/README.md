# ARGOS para Android

El APK existe por un motivo concreto, no por comodidad.

## Por qué hace falta

Al abrir `argos.html` desde el navegador del móvil, la URL acaba siendo algo
como `content://com.android.providers...` o `file://`. **Ninguno de los dos es
contexto seguro**, y Android bloquea la cámara ahí:

```
Sin acceso a la cámara: Permission denied
```

No es un fallo de ARGOS ni un permiso que falte: el navegador no expone
`getUserMedia` fuera de un origen seguro, y no hay forma de saltárselo.

El APK usa `WebViewAssetLoader`, que sirve los mismos ficheros bajo
`https://appassets.androidplatform.net/`. El origen es https, es contexto
seguro, y la cámara funciona.

## Lo que además resuelve

**El doble permiso.** Android pide el permiso del sistema *y*, por separado, el
del WebView vía `onPermissionRequest`. Conceder solo el primero deja la cámara
igual de bloqueada, y es un fallo que desconcierta porque el sistema dice que el
permiso está dado.

**El modelo dentro.** `yolox_nano.onnx` va en el APK y se carga solo. Buscar un
fichero con el selector de Android, cuando además está dentro del propio
paquete, no es viable.

**WASM multihilo.** El WebView aísla el origen, así que el detector puede usar
varios hilos — varias veces más rápido que en un navegador sobre `file://`.

## Compilar

El APK empaqueta **`argos.html`**, el mismo fichero único que se entrega para
escritorio. No hay una copia aparte que mantener.

```bash
python3 scripts/bundle.py --out argos.html
curl -sSL -o yolox_nano.onnx \
  https://github.com/Megvii-BaseDetection/YOLOX/releases/download/0.1.1rc0/yolox_nano.onnx
cd android && ./gradlew assembleDebug
```

O deja que lo haga el CI: `.github/workflows/build-apk.yml` genera el bundle,
descarga el modelo, compila y **verifica que los nueve módulos están dentro del
APK** antes de publicarlo.

Esa verificación existe porque el fallo que evita ya ocurrió: la tarea de Gradle
copiaba `index.html` y `engine.js` de cuando la app tenía dos piezas. Hoy son
once, y el APK habría arrancado mostrando la cámara **sin detector, sin cerebro
y sin memoria** — funcionando en apariencia y sin analizar nada.

## Uso

1. Instala el APK y concede la cámara.
2. Fuente **`local://camara`**. El detector se carga solo.
3. Apoya el móvil en algo. **La cámara debe estar fija**: es la única condición
   que no se negocia.

Con el móvil a 2-3 m de altura apuntando a un paso de peatones, las personas
salen a 80-150 px y la detección es cómoda. Mira `GUIA-CAMARA.md` para el resto.
