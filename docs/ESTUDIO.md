# ARGOS — Estudio técnico y plan de construcción

Sistema de *video synopsis* + analítica forense de vídeo. Categoría BriefCam / Briefcam
Insights, con decisiones de diseño distintas en los puntos donde el producto comercial
hace concesiones que a nosotros no nos convienen.

Todas las cifras de este documento están **medidas** sobre el código de este repositorio,
no estimadas. Los scripts que las producen están en `tests/` y `scripts/`.

---

## 1. Qué hace realmente un BriefCam

Conviene separar el marketing de la máquina. Son tres capas y solo una es difícil.

| Capa | Función | Dificultad real |
|---|---|---|
| **Ingesta y detección** | RTSP → decode → detector → tracker → tubos | Resuelta. Es integración, no investigación. |
| **Índice de metadatos** | Atributos, vectores, trayectorias, consulta | Media. El diseño de esquema decide si escala. |
| **Síntesis (VIDEO SYNOPSIS)** | Reordenar objetos en el tiempo para condensar horas en minutos | **Aquí está el producto.** |

El *video synopsis* viene del trabajo de Rav-Acha, Pritch y Peleg (Hebrew University,
2006–2008); BriefCam es literalmente el spin-off de ese laboratorio. La idea: cada objeto
móvil es un **tubo espaciotemporal**. Si desplazas cada tubo en el eje del tiempo de forma
independiente, puedes mostrar simultáneamente objetos que ocurrieron con horas de
diferencia, sin acelerar el vídeo y sin perder el movimiento natural de nadie.

El problema es de empaquetado: encontrar el desplazamiento de cada tubo que minimiza
solapes visuales. Es NP-duro y la formulación clásica lo resuelve con recocido simulado.

---

## 2. Arquitectura ARGOS

```
  RTSP / archivo
        │
        ▼
 ┌──────────────┐   go2rtc / MediaMTX, decode NVDEC
 │   INGESTA    │   segmentación en GOPs, timestamps de pared
 └──────┬───────┘
        ▼
 ┌──────────────┐   RT-DETR / RTMDet / YOLOX (ONNX Runtime)
 │  DETECCIÓN   │   + SAM2 / YOLO-seg para siluetas
 └──────┬───────┘
        ▼
 ┌──────────────┐   ByteTrack + Kalman + gate de apariencia
 │   TRACKING   │   → argos/track/tracker.py
 └──────┬───────┘
        ▼
 ┌──────────────┐   Tube: bboxes + siluetas comprimidas + atributos
 │    TUBOS     │   PatchStore: recortes JPEG por objeto
 └──┬────────┬──┘   → argos/core/types.py, argos/tubes/patchstore.py
    │        │
    ▼        ▼
┌────────┐ ┌──────────────────┐
│ ÍNDICE │ │    SÍNTESIS      │
│ PG +   │ │ CollisionModel   │
│ vector │ │ SynopsisSolver   │
│ PostGIS│ │ SynopsisRenderer │
└───┬────┘ └────────┬─────────┘
    │               │
    ▼               ▼
 Búsqueda        MP4 sinopsis
 Reglas          con timestamps
 Cross-cámara    originales
```

Principio rector: **el vídeo no es la base de datos**. Toda pregunta que hace un operador
—"furgonetas rojas que pararon cerca del muelle después de las 22:00"— se responde
íntegramente desde el índice; los píxeles solo se tocan para *renderizar la respuesta*.

---

## 3. La contribución técnica: el optimizador

### 3.1 El cuello de botella clásico

La formulación estándar evalúa la energía rasterizando el sinopsis completo para cada
estado candidato: coste `O(pares × frames × píxeles)` por evaluación. Eso te limita a unas
decenas o pocos cientos de objetos.

### 3.2 Tres ideas que lo rompen

**(a) El coste depende solo del desplazamiento relativo.**
Para dos tubos `a` y `b`, la colisión depende únicamente de `d = s_a − s_b`, nunca de sus
posiciones absolutas. Así que precalculo **la curva completa `C_ab(d)`** una vez por par.
Cada evaluación de energía posterior es un *lookup en array*.

**(b) Una sola correlación 2D por par, no un AND por frame.**
La silueta de un objeto es casi rígida a lo largo de su propio tubo, así que el solape
depende del desplazamiento relativo de las dos siluetas:

```
F(dy, dx) = Σ A[y,x] · B[y+dy, x+dx] = correlate2d(B, A, "full")[dy+ha−1, dx+wa−1]
```

Una correlación por par y todos los candidatos pasan a ser un *gather* vectorizado.
Se corrige la perspectiva con la razón de áreas por frame.

**(c) Poda espacial con KD-tree.**
Dos siluetas solo pueden tocarse si sus centros están más cerca que la suma de sus
semidiagonales. `sparse_distance_matrix` da el conjunto de candidatos directamente.

**Efecto medido** (160 tubos, 65k observaciones):

| Versión | Construcción del modelo |
|---|---|
| AND por frame + broadcast O(La·Lb) | 128,7 s |
| + poda KD-tree | 36,7 s |
| + mapa de correlación | 10,3 s |

Y solo sobrevive el **19–30 % de los pares O(n²)** tras la poda; en escenas reales
(objetos confinados a calzadas y accesos) la fracción es aún menor.

### 3.3 Función de energía

```
E(s) = Σ C_ab(s_a − s_b)              colisiones a nivel de píxel
     + λ_c · Σ chrono(a, b)           violaciones de orden causal
     + λ_a · Σ |s_a − ancla_a|        anclaje temporal global
```

El término de **cronología** solo se aplica a pares que realmente coincidieron
espacialmente en el original: preserva quién llegó antes, que es exactamente lo que un
investigador necesita y lo que un sinopsis ingenuo destruye.

El término de **anclaje** salió de un fallo real. Sin él, con holgura disponible, el solver
amontonaba todo al principio: `argmin` sobre un perfil de ceros devuelve el índice 0.
Producía 2.255 frames muertos al final. Con el anclaje, la mejora es consistente en todos
los regímenes de compresión.

### 3.4 Solver: tres etapas

1. **Siembra voraz**, los tubos más largos primero (son los más restringidos), cada uno en
   el argmin exacto de su perfil de coste. El perfil se acumula con *slice-adds*
   vectorizados: puntuar *todos* los desplazamientos candidatos de un tubo cuesta
   O(suma de longitudes de curva de sus vecinos), no O(T × vecinos).
2. **Recocido simulado** con deltas incrementales (mover un tubo solo toca su lista de
   adyacencia) y un movimiento de **salto óptimo** con probabilidad 0,35. Ese movimiento es
   lo que permite escapar de los mínimos locales profundos que atascan a los recocidos de
   paseo aleatorio puro a alta densidad.
3. **Pulido determinista** hasta que no mejora.

### 3.5 Resultados

Escena: 17,9 min, 120 tubos, 20.277 observaciones, concurrencia de origen 0,75 obj/frame.
El solape se **remide rasterizando el plan desde cero** — no se toma de la propia función
de energía del solver. Un solver que se miente a sí mismo queda al descubierto.

| Compresión | Uniforme | Solo voraz | **ARGOS** | Reducción |
|---:|---:|---:|---:|---:|
| 44,9× | 8,82 % | 5,52 % | **3,67 %** | **58,4 %** |
| 29,9× | 5,71 % | 2,83 % | **2,05 %** | **64,2 %** |
| 19,2× | 3,05 % | 1,45 % | **0,96 %** | **68,4 %** |
| 12,2× | 1,55 % | 1,18 % | **0,61 %** | **60,9 %** |
| 7,5× | 0,90 % | 0,80 % | **0,34 %** | **62,6 %** |

Tiempo de resolución: ~3 s por plan. Ningún frame vacío, ningún objeto descartado.

**Duración automática** bajo presupuesto de saturación:

| `max_overlap_ratio` | Duración | Compresión | Solape real | Objetos/frame |
|---:|---:|---:|---:|---:|
| 0,004 | 82 s | 13,1× | 0,58 % | 9,9 |
| 0,012 | 46 s | 23,4× | 1,59 % | 17,6 |
| 0,030 | 27 s | 39,3× | 3,00 % | 29,6 |

Un solo mando. El operador dice "más denso" o "más legible", no toca parámetros.

### 3.6 El límite físico, dicho claramente

La compresión máxima no la fija el algoritmo, la fija la escena:

```
concurrencia_sinopsis = compresión × concurrencia_origen
```

Con 7,2 objetos/frame de media en origen (una calle concurrida), 10× te da 72 objetos por
frame: ilegible, y ningún optimizador lo arregla. En el primer benchmark que hice caí justo
en ese régimen saturado y la mejora era solo del 21 %; el escenario, no el código, era el
problema. **Las cifras de "8 horas a 5 minutos" del marketing del sector aplican a metraje
disperso.** Un producto honesto mide la concurrencia y te dice la compresión alcanzable
antes de procesar.

---

## 4. Dónde ARGOS supera al estado comercial

**1. No descarta objetos.** Los sistemas comerciales silenciosamente tiran los objetos que
no consiguen encajar. Eso es inadmisible si la salida es prueba. `allow_drop=False` por
defecto: ARGOS alarga el sinopsis hasta que todo cabe, y reporta la compresión real.

**2. Colisión a nivel de silueta, no de rectángulo.** Dos peatones pueden cruzarse a
centímetros en pantalla con coste cero si no comparten ni un píxel. Un sistema de bboxes
los separa innecesariamente y pierde densidad.

**3. Preserva el orden causal.** El término de cronología mantiene el orden de los objetos
que realmente interactuaron. "¿Llegó el coche antes que la persona?" sigue siendo
respondible mirando el sinopsis.

**4. El archivo se sustituye por tubos + parches.** El `PatchStore` guarda solo los
recortes de objeto: en la demo, **10.484 recortes = 9,1 MB** frente a los 8,9 GB de RAM que
pedía guardar los frames completos. El sinopsis se renderiza sin volver a abrir el vídeo
original. Consecuencia legal directa: **el borrado RGPD es borrar los parches de un tubo**,
y desaparece de todos los sinopsis y exportaciones futuras.

**5. Confianza calibrada en cross-cámara.** La similitud coseno se convierte en razón de
verosimilitud contra una distribución de impostores estimada del propio emplazamiento
(tubos de la misma cámara con vidas solapadas: negativos etiquetados gratis, miles por
sitio). Un coseno de 0,82 no significa nada hasta que sabes cómo es 0,82 entre
desconocidos. Medido: **nivel "strong" = 12/12 enlaces verdaderos, precisión 100 %,
recall 100 %**, con 20 señuelos.

**6. Reglas sobre trayectorias, no sobre frames.** Loitering por *rectitud* de trayectoria
(desplazamiento/longitud de camino), no por permanencia bruta: quien espera el autobús y
quien lleva diez minutos rondando el mismo portal ambos permanecen, solo el segundo tiene
rectitud baja. Intrusión anclada en los pies, no en el centroide. Velocidad con filtro de
mediana y **unidades declaradas** (m/s solo si hay homografía). Reevaluar una regla
cambiada sobre un mes de archivo es una consulta de segundos, no reprocesar el vídeo.

**7. Búsqueda en lenguaje natural.** Vector CLIP por tubo en pgvector con HNSW: "persona con
mochila roja" sin definir el atributo de antemano. Los sistemas de atributos cerrados solo
encuentran lo que alguien decidió etiquetar en tiempo de diseño.

**8. Sin AGPL.** ONNX Runtime + RT-DETR/RTMDet/YOLOX (Apache-2.0). Ultralytics YOLO es
AGPL-3.0: para un producto que se distribuye o se ofrece como servicio en red significa
liberar todo el código o comprar licencia comercial. Es una decisión de arquitectura, no
una nota al pie.

---

## 5. Rendimiento y dimensionamiento

Medido en 1 vCPU sin GPU (contenedor de desarrollo), 960×540:

| Etapa | Coste |
|---|---|
| Construcción del modelo de colisión (120 tubos) | 1,7 s |
| Construcción (160 tubos, 65k obs) | 10,3 s |
| Resolver un plan (40k iteraciones SA) | ~3,0 s |
| Búsqueda automática de duración (12 bisecciones) | ~8 s |
| Extracción de parches (10.484 recortes) | 33 s |
| Render del sinopsis (724 frames) | 3,9 s |

El coste dominante en producción es la detección, no la síntesis. Estimación para
**32 cámaras a 1080p/12,5 fps analíticos**:

- 2× RTX 4090 o 1× L40S para detección + segmentación (RT-DETR-L + YOLO-seg a ~400 fps agregados)
- 1× nodo CPU (16 núcleos) para tracking, enriquecimiento y síntesis
- Postgres 16 con pgvector: ~2 kB/tubo en índice; 32 cámaras × 30 días ≈ 40–120 M tubos ≈ 250 GB
- PatchStore en S3/MinIO: ~1–2 kB por observación

La síntesis escala como `O(pares supervivientes)`, y los pares supervivientes son ~20–30 %
de O(n²) con poda. Para consultas típicas (una cámara, una ventana de horas) el número de
tubos es de cientos a pocos miles: sub-10 s de extremo a extremo.

---

## 6. Marco legal — la parte que decide el diseño

No es un anexo. Determina qué se construye.

### 6.1 La línea que no se cruza

**Identificación biométrica remota.** El Reglamento (UE) 2024/1689 (AI Act) prohíbe la
identificación biométrica remota *en tiempo real* en espacios de acceso público con fines
policiales, salvo excepciones tasadas y con autorización judicial (art. 5). La
identificación *a posteriori* es sistema de **alto riesgo** (Anexo III), con todo el paquete:
evaluación de conformidad, gestión de riesgos, registro, supervisión humana, trazabilidad.
Además, los datos biométricos son categoría especial bajo el art. 9 RGPD.

**Calendario:** las prohibiciones del art. 5 aplican desde el 2 de febrero de 2025; las
obligaciones de alto riesgo del Anexo III desde el 2 de agosto de 2026. Verifica el estado
actual antes de comprometerte con fechas: hubo discusión sobre aplazamientos.

**Decisión de arquitectura:** ARGOS **no incluye reconocimiento facial ni lectura de
matrículas**. Todo lo demás —conteo, cruce de líneas, loitering, mapas de calor,
re-identificación por apariencia sin vincular a identidad civil— es analítica ordinaria de
videovigilancia: base legal de interés legítimo o cumplimiento de obligación, más
evaluación de impacto.

La distinción que importa no es "¿usa vectores?" sino **¿el sistema identifica a una
persona o solo asocia apariencias?**. Por eso el módulo cross-cámara *propone hipótesis* y
nunca escribe una identidad: la tabla `identity` exige actor, justificación textual
obligatoria y entrada de auditoría. El sistema propone; una persona decide y queda
registrada como habiendo decidido. Eso es lo que mantiene el despliegue del lado correcto
de la línea — y, por separado, lo que hace que la salida sobreviva a un contrainterrogatorio.

### 6.2 España y Euskadi

- **LOPDGDD 3/2018**, art. 22: videovigilancia, cartelería informativa, plazo de supresión
  de **un mes** salvo conservación para acreditar infracciones (entonces se bloquean y se
  comunican en 72 h).
- **AEPD**: guía de videovigilancia; el tratamiento con analítica avanzada normalmente
  exige **EIPD (art. 35 RGPD)**.
- **Euskadi**: si el responsable es una administración vasca, la autoridad de control es la
  **AVPD** (Agencia Vasca de Protección de Datos), no la AEPD. Si es privado, AEPD.
- **Ley 5/2014 de Seguridad Privada** y su normativa: el tratamiento de imágenes por
  empresas de seguridad tiene régimen propio.
- **Ámbito laboral**: art. 89 LOPDGDD y jurisprudencia del TC/TS sobre control empresarial.
  Cualquier cámara que enfoque puestos de trabajo cambia el análisis por completo.

### 6.3 Lo que ARGOS implementa por diseño

| Requisito | Implementación |
|---|---|
| Retención por finalidad | `camera.retention_days` **por cámara**, no global. Un aparcamiento y un pasillo de personal rara vez comparten plazo lícito. |
| Supresión eficiente | `observation` particionada por día: expirar es `DETACH`, no `DELETE` masivo. |
| Art. 5(2) responsabilidad proactiva | `access_log`: quién consultó qué, cuándo y con qué autorización. `case_ref` obligatorio en exportaciones. |
| Derecho de supresión | `PatchStore.redact(tube_id)` + tabla `erasure` con lápida para demostrar que se atendió. |
| Base jurídica documentada | `camera.lawful_basis` y `camera.dpia_ref` son columnas obligatorias, no metadatos opcionales. |
| Supervisión humana | Ningún enlace de identidad automático. Veredicto del operador (`event.verdict`) almacenado, no descartado. |

Ese último punto tiene además valor técnico: el feedback del operador es la única medida
honesta de la tasa de falsas alarmas y la señal de entrenamiento para afinar reglas.

---

## 7. Estado del repositorio

**Construido y validado:**

- `argos/core/types.py` — Tube, TubeFrame con siluetas empaquetadas zlib, SynopsisPlan
- `argos/synopsis/collision.py` — mapas de correlación, poda KD-tree, curvas por par
- `argos/synopsis/optimizer.py` — siembra voraz + SA incremental + pulido, duración automática
- `argos/synopsis/renderer.py` — plate por mediana temporal, orden por profundidad, alfa con
  difuminado por distance transform, timestamp original por objeto
- `argos/tubes/patchstore.py` — almacén de parches JPEG, redacción por tubo
- `argos/track/tracker.py` — ByteTrack + Kalman + gate de apariencia
- `argos/rules/engine.py` — intrusión, tripwire direccional, loitering, velocidad, horarios
- `argos/index/schema.sql` — Postgres + pgvector HNSW + PostGIS + gobernanza
- `argos/index/crosscam.py` — stitching con verosimilitud calibrada
- `argos/detect/onnx_backend.py` — RT-DETR/YOLOX, embeddings, color dominante en CIELab
- `argos/detect/synthetic.py` — escena sintética con ground truth para benchmarking
- `tests/bench_synopsis.py`, `tests/test_tracker.py`, `scripts/demo_render.py`

**Pendiente:**

1. Ingesta RTSP real (go2rtc/MediaMTX) y decode NVDEC — 1–2 semanas
2. API FastAPI + WebSocket de eventos — 1 semana
3. Frontend de operador (timeline, zonas dibujables, revisión de hipótesis) — 3–4 semanas
4. Integración de pesos ONNX reales y calibración en cámara real — 2 semanas
5. Homografía asistida y unidades métricas — 1 semana
6. Endurecimiento, RBAC, despliegue — 2–3 semanas

Estimación total hasta piloto: **10–14 semanas** de una persona a tiempo completo, asumiendo
hardware disponible.

---

## 8. Riesgos honestos

- **La calidad de la silueta lo domina todo.** Sin segmentación decente, el optimizador cae
  a colisión por rectángulo y pierde su ventaja de densidad. SAM2 es caro; YOLO-seg es más
  barato pero sus máscaras son bastas en objetos pequeños.
- **El gate de apariencia degrada bajo ruido.** Medido: fragmentación 1,00× en condiciones
  limpias, 1,20× degradadas sin gate, 1,52× con gate. El gate cambia fragmentación por
  seguridad de identidad — deliberado, porque un fragmento se vuelve a coser y una fusión de
  identidades no se deshace. Pero es un coste real.
- **El re-ID cross-cámara es frágil** ante cambios de iluminación e ángulo. Los números de
  este repositorio son sobre datos sintéticos con estructura de identidad conocida; en campo
  esperar bastante menos y recalibrar la distribución de impostores por emplazamiento.
- **La sombra del objeto viaja con el objeto.** Al recomponer, la sombra de las 18:00 aparece
  junto a una de las 09:00. Las bandas temporales de fondo lo mitigan pero no lo eliminan.

---

## 9. Antropometría: estatura y complexión

Añadido en `argos/measure/anthropometry.py`. Las dos mitades de la pregunta son problemas
completamente distintos y mezclarlas es como estas funciones acaban produciendo tonterías
con aspecto de certeza.

### 9.1 Estatura — geometría resuelta

Metrología de vista única (Criminisi/Reid/Zisserman): con la homografía del suelo y el punto
de fuga vertical, la estatura de alguien que pisa ese plano queda fijada por una razón doble.

```
b_h = p1·X + p2·Y + p4        (imagen del punto de suelo, sin normalizar)
t_h = b_h + Z·p3              (imagen del punto a altura Z)
λ = |t − b| / |v − t|         →     Z = λ · b_h[2] / (s · v[2])
```

La única incógnita `s` se fija con **objetos de altura conocida** en escena: una puerta de
2,10 m, un bolardo de 1,00 m. Dos o más repartidos por la imagen; con una sola referencia
junto a la cámara el campo lejano queda mal restringido, y los residuos entre varias
referencias son la única comprobación barata de que la homografía es sana.

Verificado exacto contra proyección conocida: 1,750 m estimado sobre 1,75 m real.

### 9.2 Lo difícil no son las matemáticas, son los extremos

Todos los sesgos apuntan **en la misma dirección** (hacia abajo):

- **Marcha**: la estatura oscila 2–4 cm en el ciclo. Máxima en apoyo medio, mínima en doble
  apoyo. Agregar con la mediana **subestima sistemáticamente**.
- **Postura**: encorvarse, mirar el móvil, cargar peso.
- **Pies ocluidos** o cortados por el borde del cuadro.
- **Calzado**: +2–4 cm, no observable. Lo que se mide es **altura tal como se presenta**,
  no estatura descalza.
- **Gorros**: la mayor fuente de error incontrolada, y hacia arriba.

Por eso se agrega con un **percentil superior**, no con la mediana.

**Fallo que encontré y corregí:** un percentil superior sobre los valores crudos también
recoge el ruido *simétrico* del detector y se infla con él. Medido: sesgo +0,0 cm con
jitter de 0,5 px pero **+7,6 cm con jitter de 3,5 px**. La solución: suavizar la serie
temporalmente primero —el ruido de extremos es independiente por frame, la marcha es
suave— y restar analíticamente la inflación residual `z(p)·σ/√w`.

### 9.3 Resultados medidos

Cámara pinhole sintética (6 m de altura, 28° de inclinación, 1280×720), 60 personas de
estatura conocida, con marcha, calzado, jitter de bbox y oclusión de pies:

| Condición | MAE | p90 | Sesgo | Cobertura IC |
|---|---:|---:|---:|---:|
| Ideal (jitter 0,5 px) | **1,7 cm** | 2,7 cm | −1,7 cm | 96,7 % |
| Realista (1,5 px, 10 % pies ocluidos) | **1,8 cm** | 3,2 cm | −1,8 cm | 98,3 % |
| Degradado (3,5 px, 25 % pies) | **3,1 cm** | 5,5 cm | −2,5 cm | 95,0 % |
| Con gorros (30 %) | **1,9 cm** | 3,4 cm | −1,7 cm | 93,3 % |

Antes de la corrección de inflación, el caso degradado daba 7,6 cm de MAE. Ahora es 3,1 cm
y el sesgo se mantiene estable al degradarse el detector, que es lo que importa: un error
que crece con la calidad de la cámara es un error que no puedes acotar en campo.

El sesgo residual de −1,7 cm es constante y por tanto corregible subiendo el percentil,
pero no lo he ajustado: sería sobreajustar a mi propio modelo sintético de marcha.
Se calibra en campo con personas medidas.

**Salida**: nunca un número suelto. `StatureEstimate` da punto, intervalo, número de
muestras usadas y descartadas, etiqueta de calidad y las notas de sesgo sistemático.

### 9.4 Peso — aquí hay que decir que no

Una cámara monocular fija mide una **silueta vestida**. Cálculo directo, misma persona de
1,75 m, variando solo la ropa:

| Prenda | Anchura de silueta | Índice | Masa estimada |
|---|---:|---:|---:|
| Camiseta | 0,42 m | 0,149 | 31 kg |
| Sudadera | 0,47 m | 0,172 | 39 kg |
| Abrigo de invierno | 0,53 m | 0,203 | 50 kg |
| Plumífero voluminoso | 0,58 m | 0,229 | 60 kg |

**28 kg de recorrido solo por la ropa**, sin que la persona cambie. Eso es varias veces
mayor que cualquier diferencia que quisieras detectar. Un plumífero sobre alguien delgado y
una camiseta sobre alguien corpulento dan la misma lectura.

Por eso el módulo **no emite kilos como medida**. Emite:

- **`build_index`**: área de silueta normalizada por estatura² — adimensional, comparable
  entre cámaras y distancias, y perfectamente válido como **filtro de búsqueda**
  ("de complexión más fuerte que esta persona").
- **`category`**: delgado / medio / corpulento.
- **`mass_range_kg`**: solo si se pide explícitamente, con intervalo ancho derivado de la
  dispersión residual del ajuste poblacional, y marcado `no evidencial`.

Un intervalo más estrecho ahí sería una invención. Si alguien necesita un peso, la respuesta
honesta es que este sensor no lo mide.

### 9.5 Encaje legal

La estatura por fotogrametría es **práctica forense aceptada** y no es identificación
biométrica: no identifica de forma única a nadie, es un atributo físico como el color de un
vehículo. Se almacena como filtro de búsqueda con su incertidumbre adjunta.

Dicho eso, acerca el sistema al terreno biométrico y conviene tratarlo con cuidado:

- **Nunca un punto sin intervalo** en una exportación. `1,78 m` en un informe implica una
  precisión que el sensor no tiene; `1,74–1,84 m (calidad: aceptable, incluye calzado)` es
  lo que se puede defender.
- **Declarar siempre la base de calibración**: una estatura sin referencias declaradas no
  tiene valor probatorio.
- El campo entra en la **EIPD** como atributo derivado, no como dato bruto.

---

## 10. Autocalibración desde los propios peatones

`argos/measure/calibration.py`. La estatura necesita cámara calibrada. Pedir a un técnico
que levante cuatro puntos de suelo y mida una puerta en cada cámara de un sitio de 200
cámaras es exactamente por qué estas funciones no se usan en la práctica.

Hay una alternativa, conocida desde Lv/Zhao/Nevatia (2002): **el objetivo de calibración son
los peatones**. Una persona de pie es un segmento vertical de longitud aproximadamente
conocida apoyado en el plano del suelo, y una cámara que mira una acera durante una hora ve
cientos.

1. **Punto de fuga vertical**: todo segmento cabeza-pies es vertical en el mundo, así que
   sus imágenes pasan todas por `v`. Se apilan los vectores de línea y se toma el espacio
   nulo por SVD. **Ponderado por longitud del segmento**: alguien de 30 px restringe la
   dirección mucho más débilmente que alguien de 300 px, y sin peso la multitud lejana
   domina el ajuste.
2. **Línea de horizonte**: dos personas de *igual* altura dan una línea de pies y una línea
   de cabezas que se cortan en el horizonte. Como las personas no miden todas lo mismo,
   RANSAC sobre pares y refinado por mínimos cuadrados totales sobre el consenso.
3. **Escala**: la única parte que necesita información externa, y la aporta la estadística
   poblacional — se fija para que la mediana estimada de la multitud observada iguale la
   mediana poblacional conocida. Sin cinta métrica, sin objeto de referencia.

Verificación contra cámara conocida (1280×720, 180 peatones):
punto de fuga real `[640, 2053]` px, estimado `[633, 2076]` px — **24 px de error**.

| Escenario | MAE por observación | p90 | Sesgo |
|---|---:|---:|---:|
| Autocalibrado (mediana asumida 1,70 m) | 3,6 cm | 8,0 | +0,5 |
| Con referencias medidas | 3,6 cm | 7,7 | +0,2 |
| Población real 1,66 m, asumida 1,70 | 5,6 cm | 11,5 | **+4,6** |
| Población real 1,74 m, asumida 1,70 | 6,0 cm | 11,2 | **−3,3** |
| n=40 peatones | 5,6 cm | 11,1 | +0,2 |
| n=180 peatones | 4,4 cm | 8,6 | +0,9 |

Las dos filas de población desajustada son el punto importante: **el error de la mediana
asumida entra íntegro en cada medida**. Es sistemático, no promedia, y **no es detectable
desde el vídeo**. Por eso `HorizonCamera` lleva `mode` (`self` / `surveyed`) y una propiedad
`evidential` que solo es verdadera con referencias medidas. Esa distinción viaja hasta la
base de datos.

### 10.1 Un intervalo deshonesto, y su corrección

Al agregar por tubo, el resultado con referencias medidas mejora a **1,8 cm de MAE** con el
intervalo cubriendo la verdad el **95,6 %** de las veces. Con autocalibración: 4,5 cm de MAE
pero **la cobertura se desplomó al 64,4 %**.

La causa: el bootstrap solo captura ruido de muestreo, que aquí es la menor de las tres
fuentes de error. El error de calibración es **regional y sistemático**, así que no encoge al
promediar más frames — promediar 200 observaciones de alguien parado en una esquina mal
calibrada da una respuesta *muy precisa y equivocada*.

Un intervalo que cubre dos tercios de las veces es peor que no dar intervalo. Corregido
leyendo el modo de calibración de la cámara y cobrándolo explícitamente:

| | MAE | Cobertura IC | Anchura IC |
|---|---:|---:|---:|
| Referencias medidas | 1,8–1,9 cm | 95,6–97,8 % | 8,6 cm |
| Autocalibrado | 4,5–4,7 cm | 85,6–93,3 % | 21,6 cm |

El intervalo autocalibrado es 2,5× más ancho. Eso es correcto: refleja lo que realmente no
se sabe. La autocalibración es el modo adecuado para **búsqueda y triaje**; para cualquier
cifra que vaya a un informe, referencia medida obligatoria.

### 10.2 Detalle de implementación que costó un fallo

Una recta y su negación son la misma recta, así que el signo de `(l·b)` —y por tanto el de
toda altura— sale arbitrario del ajuste. Sin orientar el horizonte explícitamente, el
estimador descartaba silenciosamente todas las muestras aproximadamente la mitad de las
veces. Se orienta una sola vez, usando que las personas tienen altura positiva.

---

## 11. Cámaras de snapshot HTTP y capa de siluetas

### 11.1 Ingesta por snapshot (`argos/ingest/snapshot.py`)

Las cámaras municipales rara vez exponen RTSP al público. Exponen una URL que devuelve
**un JPEG, ahora** — por ejemplo el servicio de cámaras de GeoBilbao. Es un problema de
ingesta distinto y tratarlo como un stream es como se rompen estas integraciones:

- **No hay frame rate.** El servidor refresca a su ritmo (5–30 s típicamente) y sondear más
  rápido devuelve los mismos bytes. El poller detecta **novedad** por hash de contenido y
  *aprende* el intervalo real para no machacar un servicio público.
- **Los frames están muy separados.** A 1 frame cada 10 s un objeto cruza la vista en dos o
  tres frames. La asociación por IoU es inútil a esa separación. `suitability_report()`
  clasifica la cámara antes de procesar: `full` / `limited` / `snapshot-only`, con lista
  explícita de lo que **no** soporta (velocidad, estatura, sinopsis).
- **El timestamp es del servidor**, no tuyo. `Last-Modified` es la captura; la hora de
  recepción local puede ir segundos por detrás. Lo forense usa la primera y registra que
  lo hizo.
- **Las cámaras se congelan** devolviendo JPEGs válidos para siempre. El hash lo detecta;
  nada más lo hace.

### 11.2 Siluetas vectoriales (`argos/render/silhouette.py`)

Las máscaras de segmentación son visualmente pésimas: dentadas, con espolones de un píxel
por el stride de la red. Puestas en un lienzo parecen la captura de un depurador.

Cadena de máscara a obra: limpieza morfológica con kernel **proporcional al objeto** →
contorno externo (los agujeros interiores se descartan: a tamaño pequeño leen como defecto
de impresión) → **remuestreo a arco constante** → Chaikin → Catmull-Rom a Bézier cúbica.

El remuestreo es el paso que casi todas las implementaciones se saltan, y es por lo que sus
curvas salen grumosas: los puntos de un contorno rasterizado están desigualmente espaciados,
así que cualquier suavizado los pondera mal y aplana justo las esquinas que definen la forma.

**Fallo corregido:** el orden importa. Suavizar exige alta densidad de puntos, pero cada
punto superviviente se convierte en un segmento cúbico de salida. Emitir directamente la
resolución de suavizado daba **17 kB de path por figura** — una lámina de 110 figuras pesaba
1,9 MB para una imagen que el ojo no distingue de una de 44 puntos. Con decimación final:
**226 KB**.

Composiciones:

- **`trajectory_strip`** — el paso completo de un objeto como secuencia estroboscópica, con
  rampa de opacidad. La imagen forense más útil que produce el sistema: dirección, velocidad
  (por el espaciado), cambios de postura y dónde se detuvo, legibles de un vistazo, sin
  reproducir vídeo y sin imagen identificable.
- **`activity_plate`** — todo lo que pasó en una hora, en una lámina.

### 11.3 La silueta como control de privacidad

Una silueta es **anónima por construcción**. Lleva postura, marcha, clase, tamaño y posición
—todo lo que un operador necesita para entender qué ocurre— y nada del detalle facial o de
vestimenta que convierte una imagen en dato personal.

Eso la hace un control de privacidad serio, no un estilo visual: una sala de control puede
operar sobre la capa de siluetas por defecto, y revelar los píxeles subyacentes pasa a ser
una acción separada, autorizada y registrada. La mayor parte del tiempo nadie necesita los
píxeles. Bajo minimización de datos (art. 5.1.c RGPD) eso no solo está permitido: es
exactamente el argumento que quieres poder sostener ante la AEPD o la AVPD.

---

## 12. Detector real, RTSP y autenticación

### 12.1 YOLOX sobre ONNX: dos fallos que el modelo real destapó

El decodificador de `onnx_backend.py` estaba escrito a ciegas y fallaba en dos puntos que
**no producen error, producen cero detecciones** — mucho más caro de diagnosticar:

1. **Los exports ONNX de YOLOX no decodifican la rejilla.** Emiten desplazamientos respecto
   a la celda, no coordenadas de imagen. Hay que reconstruir las anclas de los strides
   8/16/32 y aplicar `cx = (raw + grid)·stride`, `w = exp(raw)·stride`. Sin eso las cajas
   salen amontonadas en la esquina con tamaños de un píxel.
2. **La entrada es BGR crudo en 0-255**, sin dividir entre 255 y sin permutar canales, con
   relleno de 114 abajo/derecha. Normalizar la entrada, que es lo que pide casi cualquier
   otra familia, deja el modelo mudo.

### 12.2 Inferencia por teselas

Con cámara elevada o gran angular el letterbox es letal: meter 1920×864 en una entrada de
640 aplica un factor de 0,33, así que un coche de 40 px pasa a 13 y cae por debajo de lo
que el detector resuelve.

**Medido sobre metraje real** (Calle de Alcalá desde ~50 m de altura, frame 900):

| Configuración | Objetos | Tiempo | Clases |
|---|---:|---:|---|
| yolox_s, cuadro completo | 5 | 0,4 s | 1 person, 4 car |
| yolox_tiny, 18 teselas | **42** | 1,3 s | 1 person, 32 car, 5 bus, 4 truck |
| yolox_s, 8 teselas | **40** | 2,2 s | 1 person, 33 car, 4 bus, 2 truck |

De 5 a 42 objetos. El solape entre teselas no es opcional: sin él, todo objeto que cruce
un borde se parte en dos detecciones parciales o desaparece.

### 12.3 El filtro de objeto inmóvil

YOLOX detecta la **estatua de Minerva** del Círculo de Bellas Artes como `person`, con
score alto. El modelo acierta —formalmente es una figura humana— y aun así es un falso
positivo perpetuo que contamina recuentos, sinopsis y búsquedas.

El filtro es de **desplazamiento, no de clase**, porque el problema tampoco es de clase:
sirve igual para un contenedor, un cartel o un coche aparcado. Se compara el recorrido
neto contra la diagonal mediana de la caja, de modo que el criterio vale igual para un
peatón de 30 px al fondo que para un autobús de 300 en primer plano, sin recalibrar por
cámara.

En la pasada real descartó **10 objetos**: `['bus', 'car', 'person']` — la estatua y
vehículos aparcados. Se **marcan, no se borran**: para un aforo o una búsqueda posterior
el objeto existió, y lo que se excluye es el sinopsis, donde un objeto inmóvil no aporta
nada porque ya está en la placa de fondo.

### 12.4 MOG2 frente a YOLOX: el compromiso real

| | MOG2 | YOLOX teselado |
|---|---|---|
| Tubos detectados (30 s de Alcalá) | 53 | **87** |
| Observaciones | 3.572 | **4.908** |
| Clases | por proporción de caja | **reales** (car/bus/truck/person) |
| Objetos parados | **se disuelven en el fondo** | detectados |
| Siluetas | **sí, gratis** | no: es detección, no segmentación |
| Coste por frame (CPU) | ~25 ms | ~1.300 ms |

Ninguno gana en todo. YOLOX ve mucho mejor pero **no da máscaras**, así que la capa de
siluetas —que es a la vez el control de privacidad y la ventaja de densidad del
optimizador— se queda vacía. La combinación correcta es híbrida: cajas de YOLOX y
siluetas obtenidas por sustracción de fondo **dentro de cada caja**. Barato, y recupera lo
mejor de los dos. Está pendiente.

### 12.5 El límite físico, otra vez

Con YOLOX la concurrencia de origen sube a **21,9 objetos/frame** y la compresión del
sinopsis cae a **1,0×**. No es una regresión: es la fórmula del §3.6 aplicándose. Un
detector mejor encuentra más objetos, y más objetos simultáneos significan menos hueco que
condensar. En una escena saturada el sinopsis no tiene nada que hacer, y decirlo es más
útil que fabricar una cifra.

### 12.6 RTSP para funcionamiento continuo

`argos/ingest/rtsp.py`. Un `VideoCapture` sobre RTSP funciona en la demo y falla en
producción por cosas que no aparecen en diez minutos:

- **El stream muere sin decirlo**: `read()` puede bloquear sin plazo. Perro guardián en
  hilo aparte, no comprobación del valor de retorno.
- **UDP no pierde frames, los corrompe**: entrega paquetes incompletos que FFmpeg
  decodifica igual, y sale un frame con media imagen de la escena anterior — para una
  sustracción de fondo, un objeto enorme y falso. Se fuerza TCP.
- **Reconectar en bucle cerrado agrava la caída.** Espera exponencial con tope.
- **La cámara se congela sin desconectarse**: sirve el mismo frame indefinidamente. Solo
  el hash del contenido lo detecta.
- Al reconectar se emite `reset=True`: un corte de treinta segundos cambia la iluminación
  lo suficiente como para que el modelo de fondo anterior marque medio cuadro como
  movimiento.

### 12.7 Autenticación

`argos/api/auth.py`. Lo que quedaba expuesto no era una API cualquiera: era un feed de
cámara en vivo y un índice de búsqueda sobre personas.

- **Se aborta el arranque** si el servicio escucha fuera de `localhost` sin token. Un aviso
  en el registro se ignora; un fallo al arrancar, no. Generar un token por defecto sería
  peor: acaba en producción sin que nadie lo cambie.
- **Comparación en tiempo constante** (`compare_digest`): un `==` sobre cadenas sale antes
  en el primer byte distinto, y esa diferencia es medible por red.
- **Cabecera sí, consulta solo para medios**: `<img>` y `<video>` no pueden enviar
  cabeceras, pero los parámetros acaban en registros de acceso y en el historial del
  navegador, así que el resto de rutas exige cabecera.
- **Limitación de intentos**: 20 fallos por minuto y baneo de 5. Sin ella un token de 32
  caracteres sigue siendo forzable a miles de intentos por segundo.

Verificado: 401 sin token, 401 con token erróneo, 200 con el correcto, 429 tras 22 fallos,
y `SystemExit` al intentar arrancar en `0.0.0.0` sin credencial.

---

## 13. Detector híbrido, siluetas y proporciones

### 13.1 El híbrido: cajas de red, siluetas de fondo

`argos/detect/hybrid.py`. Ninguno de los dos detectores ganaba solo, así que se combinan
y la combinación arregla las tres carencias a la vez: siluetas, velocidad y objetos
parados.

Dos escalas de fondo, que hacen cosas distintas y no se pueden fusionar:

| | Función |
|---|---|
| **rápida** (MOG2, alpha alto) | ¿dónde pasa algo *ahora*? → atención |
| **lenta** (media exponencial) | ¿cómo es la escena vacía? → siluetas |

Con una sola no se puede tener ambas: una que se adapta rápido absorbe al objeto parado en
segundos; una lenta marca como movimiento cualquier cambio de luz durante minutos.

### 13.2 El fallo de lógica que costaba dos tercios de las siluetas

Congelar la placa **donde hay movimiento** parece razonable y está exactamente al revés
para lo que hace falta. Un coche detenido en un semáforo deja de generar movimiento, la
placa se actualiza sobre él, el coche queda impreso en el fondo, y a partir de ahí su
diferencia con la placa es cero.

Medido antes de corregirlo: las cajas **grandes** —vehículos detenidos, más cercanos— daban
relleno mediano **0,00**, mientras las pequeñas daban 0,84. Contraintuitivo hasta que se ve
la causa.

La corrección aprovecha lo que un detector sabe y una sustracción de fondo no: **dónde hay
un objeto, se mueva o no**. Las cajas del frame anterior protegen la placa.

| | Siluetas obtenidas | Relleno en cajas grandes |
|---|---:|---:|
| Placa protegida solo por movimiento | 33 % | 0,00 |
| Placa protegida también por detecciones | **68 %** | **0,64** |

Efecto secundario útil: la estatua de Minerva **no obtiene silueta**, porque forma parte de
la placa. Es una segunda señal, independiente del filtro de inmovilidad, de que no es un
objeto.

### 13.3 Atención dirigida: lo que funciona y lo que no

La máscara de movimiento sirve para decidir **dónde mirar**, no solo para recortar. Pero
medido sobre Alcalá:

| | ms/frame | obj/frame | teselas | recall |
|---|---:|---:|---:|---:|
| Teselado uniforme | 1.193 | 35,7 | 18 | 100 % |
| Atención solo por movimiento | 540 | 21,4 | 7,5 | **60 %** |
| Atención por movimiento **+ pistas del tracker** | 1.159 | 35,7 | 17,1 | 100 % |

Con atención solo por movimiento se pierde el 40 % de los objetos: un coche parado no
genera movimiento y desaparece del detector. Añadiendo las cajas vivas del tracker como
pistas, el recall vuelve al 100 %... y **la aceleración desaparece**, porque con 35 objetos
repartidos por toda la calle casi ninguna tesela queda inactiva.

La conclusión honesta: **la atención dirigida es dependiente de la escena**. En una calle
saturada no ahorra nada. En metraje disperso —que es justo donde el sinopsis también rinde—
evita el 60-80 % del cómputo. No es una optimización universal y presentarla como tal sería
falso.

### 13.4 Proporciones: forma en vez de aspecto

`argos/measure/proportions.py`. La proporción de la caja es el descriptor más pobre posible:
un coche visto de frente desde arriba es casi cuadrado, igual que tres peatones juntos. La
silueta permite medir **extensión** (área/caja), **solidez** (área/envolvente convexa),
**elongación** (razón de ejes principales por momentos de segundo orden) y **número de
partes** tras erosión proporcional —que separa un grupo de un individuo, porque dos peatones
pegados se despegan al erosionar y un vehículo sigue siendo una pieza.

Los umbrales devuelven `indeterminado` cuando la forma no separa, en lugar de inventar
clase: una etiqueta equivocada se propaga a búsquedas y recuentos, una ausente solo cuesta
una consulta más amplia. Sobre Alcalá: **72 vehículos, 1 persona, 25 indeterminados**.

### 13.5 Coherencia con la perspectiva

En un plano de suelo, el tamaño imagen de un objeto es función lineal de su fila de
contacto. Esa relación se aprende **de la propia escena**, sin calibrar nada, con regresión
robusta reponderada — un ajuste ordinario se lo comen los propios atípicos, que tiran de la
recta hasta dejar de parecer anómalos.

Ajustado sobre el vídeo real:

```
altura = 0,069 · y − 8,1 px      sigma 4,1 px sobre 75 tubos
```

Un objeto que viola esa relación **no está en el suelo**: es una antena, un reflejo, un
cartel o una estatua en una cornisa. Y es un discriminador que ningún umbral de confianza
proporciona, porque el detector está *acertando*: la estatua sí parece una persona.

La estatua de Minerva sale a **z = +55,8 sigmas**. No es un caso límite.

Cuando dos señales independientes coinciden —inmóvil **y** fuera del plano de suelo— la
marca es `decorado_probable` con confianza alta. En la pasada real: 2 objetos, y el mayor
por amplio margen es la estatua.

### 13.6 El borde de la silueta

A los tamaños reales de una vista aérea —caja mediana **27×26 px**— la máscara salía
bloqueada, y por dos causas distintas:

1. **Morfología con kernel fijo.** El cierre era 7×7 sobre 27 px de ancho: el **26 %** del
   objeto. No suavizaba el contorno, lo borraba. Mismo error que ya estaba corregido en el
   motor JS y no aquí.
2. **El borde vivía en la rejilla de píxeles.** Con un umbral duro a resolución nativa, la
   precisión máxima del contorno es un píxel, y a 27 px eso es visible.

Correcciones, en este orden:

- **Supermuestreo** del recorte antes de segmentar (hasta 4×, adaptativo al tamaño): es la
  única forma de tener precisión subpíxel sin resolver un problema de *matting*. La máscara
  se guarda a esa resolución —relativa a su caja, así que cuesta unos bytes tras comprimir.
- **Filtro guiado por la imagen**, que ajusta el contorno a los bordes reales del objeto en
  vez de a la forma que dejó el umbral.
- **Kernel proporcional** al objeto, no fijo.

**El orden importó más que las técnicas.** En la primera versión el refinado decidía qué
existía, y el rendimiento se hundió del 68 % al 38 %: en una caja fina casi todo es borde,
el filtro guiado arrastra el alfa hacia la media local y el objeto desaparece entero.
Invirtiéndolo —el umbral decide qué existe, el refinado solo mueve el borde, con red de
seguridad si se come más del 55 % del área— el resultado sube a **71,2 %**, por encima del
punto de partida.

| | Siluetas | Resolución de máscara | Borde sobre gradiente real |
|---|---:|---|---:|
| Máscara tosca, kernel fijo | 68 % | 1× (nativa) | — |
| Refinado decidiendo | 38 % | 4× | 1,34× |
| **Umbral decide, refinado ajusta** | **71,2 %** | **4×** | 1,08× |

El vectorizador —trazado de contorno, remuestreo a arco constante, Chaikin y Catmull-Rom—
es el que produce la salida final. Pintar la máscara en crudo, que es lo que se estaba
mostrando, desperdicia todo lo anterior.

### 13.7 Un solo fallo raíz, no dos

La inspección de la tira `frame | placa | diferencia | máscara` mostró dos síntomas que
parecían independientes:

- En decorado —estatua, fachadas— la placa era **idéntica** al frame, la diferencia era
  ruido puro, y aun así salía máscara.
- En vehículos reales la diferencia dibujaba el coche perfectamente, pero la máscara salía
  **desplazada** respecto a esa diferencia.

Son el mismo fallo. Medido por objeto:

| | SNR | IoU base/refinado | Desplazamiento |
|---|---:|---:|---:|
| Vehículos reales | 1,7 – 2,8 | 0,96 – 0,97 | 0,3 px |
| Decorado detectado | 1,3 – 1,4 | 0,40 – 0,68 | 15 – 32 px |

Un umbral sobre la diferencia produce *algo* siempre, incluso sobre ruido: la morfología
agrupa píxeles dispersos en manchas que después pasan cualquier comprobación de relleno.
El desplazamiento no era un fallo aparte, era **el síntoma de refinar una base que no
contenía objeto**: cuando la base es ruido, el filtro guiado la mueve a donde haya bordes.

La corrección es una **compuerta de señal**: se estima el suelo de ruido de forma robusta
(mediana más MAD) *fuera* de la máscara candidata y se exige que la señal de dentro lo
supere claramente.

| | Siluetas | IoU mediana | **IoU mínima** | Centroide p90 |
|---|---:|---:|---:|---:|
| Sin compuerta | 71,2 % | 0,94 | **0,40** | 0,346 |
| Con compuerta | 28,7 % | 0,95 | **0,93** | **0,068** |

El 71 % anterior era falso: contaba como silueta cualquier mancha de ruido. El 28,7 % es la
fracción de detecciones que el modelo de fondo **realmente puede resolver** en este metraje
—vehículos de 27 px, grises sobre asfalto gris— y cada una de ellas está donde debe.

Una máscara mal puesta contamina forma, proporciones y clase, y se propaga a todo lo que
venga después. Una ausente solo es un objeto sin contorno. El intercambio no está
equilibrado, y por eso la compuerta es agresiva.

### 13.8 ¿Se analiza toda la pantalla?

Sí. El plan de teselas cubre el **100 %** del cuadro, verificado por acumulación:
39 % cubierto por una tesela, 48 % por dos, 13 % por tres o más. Las regiones sin
detecciones son tejados, cielo y fachadas — no hay zonas ciegas.

Lo que sí faltaban eran coches, y la causa era el **umbral de confianza**:

| conf | Vehículos | Lado mediano |
|---:|---:|---:|
| 0,40 | 30 | 26 px |
| 0,30 | 41 | 29 px |
| 0,20 | 62 | 27 px |
| **0,15** | **79** | **24 px** |
| 0,05 | 161 | 26 px |

Pasar de 0,30 a 0,15 casi duplica los vehículos detectados. Y no son cajas espurias: el
tamaño mediano apenas se mueve entre 0,15 y 0,40, así que lo que se caía eran vehículos
pequeños y de bajo contraste, no ruido.

**El principio de diseño que sale de aquí:** la puntuación de un detector no sabe
distinguir "coche lejano" de "mancha". El modelo de perspectiva, el filtro de inmovilidad
y el `min_hits` del tracker sí. Conviene **filtrar por física aguas abajo, no por confianza
aguas arriba**, porque un objeto descartado en la detección ya no se recupera, mientras que
un falso positivo que sobrevive a la detección todavía tiene tres filtros por delante.

Umbral por defecto bajado a 0,15.

### 13.9 Objetos perdidos en los bordes de tesela

Comparando contra un modelo de referencia (yolox_s a umbral 0,10 con solape amplio) sobre
el mismo frame:

| Configuración | Detecciones | Recall | Teselas |
|---|---:|---:|---:|
| Solape 0,25, sin recorte | 80 | 81 % | 18 |
| Solape 0,35, sin recorte | 94 | 84 % | 21 |
| Solape 0,35 + descarte de trozos | 81 | 82 % | 21 |
| **Solape 0,45 + descarte de trozos** | **94** | **87 %** | 24 |

El diagnóstico: **el 71 % de los objetos perdidos estaban junto a un borde de tesela**,
frente al 40 % de los detectados. Y no se perdían exactamente — se detectaban con la caja
**cortada**, y una caja truncada no casa con el objeto ni sirve para forma, proporciones o
seguimiento.

La corrección tiene dos mitades y hacen falta las dos: **descartar las cajas que tocan un
borde de tesela interior** (la tesela vecina tiene el objeto entero) y **subir el solape**
para garantizar que ese vecino existe. Con solape 0,35 el descarte incluso empeora, porque
descarta trozos sin que haya siempre una tesela que contenga el objeto completo.

Es el mismo principio que ya aparecía en el voto de clase y en el estimador de estatura:
**una caja truncada miente**, y en los tres casos la respuesta correcta es ignorarla en vez
de intentar corregirla.

### 13.10 Perspectiva por clase

El modelo único altura-frente-a-fila marcaba como anómalo **todo autobús real**: salían a
z entre +4,6 y +15,2, mientras la estatua salía a +30. Separables, pero solo con un umbral
afinado a mano — justo lo que hay que evitar.

Un autobús mide el doble que un turismo a la misma distancia. La geometría de la escena es
común; la estatura típica del objeto no. Se añade un **factor multiplicativo por clase**,
estimado de los propios datos (mediana de observado/esperado, solo con cinco o más
ejemplares — con dos, el factor lo fija el ruido).

Los factores que salen son geométricamente correctos por sí solos:

```
car 0,94    truck 1,28    bus 2,32
```

| | Marcados \|z\|>3 | Autobuses entre los peores |
|---|---:|---|
| Modelo único | 25 / 94 | sí, tres de los cinco primeros |
| **Con factor por clase** | **19 / 94** | **ninguno** |

La estatua se mantiene en **z = +40,9**, dos órdenes por encima de cualquier vehículo. No
hace falta afinar el umbral: la separación es estructural.
