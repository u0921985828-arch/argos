# ARGOS · auditoría

Buscando defectos, no confirmando aciertos. Todo lo que sigue está **medido**, y las
correcciones están aplicadas y verificadas.

---

## Hallazgos, por gravedad

| # | Defecto | Gravedad | Estado |
|---|---|---|---|
| 1 | NMS por clase fina → objetos duplicados y etiqueta bailando | **Crítico** | Corregido |
| 2 | `tubes[]` sin acotar → 0,1 GB/hora | **Crítico** | Corregido |
| 3 | El zoom de análisis era imposible por un error de variable | **Crítico** | Corregido |
| 4 | Mapa de atención indexado → desalineable en silencio | Alto | Corregido |
| 5 | `stature` sin liberar → crece toda la sesión | Alto | Corregido |
| 6 | `obs[]` por pista sin acotar | Alto | Corregido |
| 7 | Umbral único para todas las clases | Medio | Corregido |
| 8 | Precisión absoluta de yolox_tiny | Medio | **Abierto** |
| 9 | Sin clase para carritos, sillas, patinetes | Medio | **Abierto** |
| 10 | Sin detector de cabezas para multitudes | Medio | **Abierto** |

---

## 1 · NMS por clase fina *(crítico)*

**Síntoma del usuario:** «detecta coches donde no hay» y «detecta furgonetas» pero la
etiqueta baila mirando el mismo vehículo.

**Causa:** la supresión de no-máximos se hacía **por clase**. La misma furgoneta emitía
una caja `car` y otra `truck`; ninguna suprimía a la otra y salían **dos objetos donde
había uno**.

Medido contra un modelo de referencia sobre un frame real:

```
truck -> car   40 veces
bus   -> car   10
truck -> bus    2
bus   -> truck  1
                --
                53 confusiones de subclase
```

**Corrección.** La distinción turismo/furgoneta/camión no es fiable a estos tamaños, y
fingirla es peor que no darla. Las clases se agrupan en categorías gruesas
—`person`, `vehicle`, `bike`, `animal`— la supresión opera **dentro del grupo**, y la
subclase queda como *pista* con su confianza, no como identidad del objeto.

| | Confusiones | Cajas emitidas |
|---|---:|---:|
| Antes | 53 | 144 |
| **Después** | **0** | 111 |

Las 33 cajas que desaparecen son duplicados del mismo objeto. El modelo de referencia
perdió 34 por el mismo motivo: **el defecto también estaba en la medida**.

## 2 · Fuga de memoria en tubos *(crítico)*

`engine.tubes[]` acumulaba cada trayectoria terminada durante toda la sesión, con sus
siluetas.

```
frame   tubos   tubos_KB   heap_MB
  750      21        656         9
 3000      42       2905        21
```

**1,00 KB por frame** con solo diez objetos en escena. A 30 fps son **0,1 GB/hora**, y
cuatro veces más en una escena concurrida. Un despliegue de un día mata la pestaña, y el
síntoma sería «se va poniendo lenta», que no apunta a nada.

**Corrección:** ventana de retención por antigüedad, tope de tubos, y **diezmado** de
las trayectorias vivas muy largas —no truncado: conservar uno de cada dos puntos
antiguos mantiene la forma del recorrido, cortar el principio perdería de dónde venía
el objeto.

```
tras la corrección, entre f3000 y f6000:  -0,030 KB/frame   (estable)
```

## 3 · El zoom nunca funcionó *(crítico)*

`_tiles()` usaba el lado de **entrada del modelo** en lugar del lado de **tesela**. Al
estar igualados, la tesela nunca podía ser menor que la entrada, y por tanto el recurso
más eficaz contra objetos pequeños era **inaplicable**. El síntoma era que cambiar el
tamaño de tesela no hacía absolutamente nada.

| | Objetos | Personas |
|---|---:|---:|
| ×1 | 81 | 2 |
| **×2 + umbral de persona** | **144** | **6** |
| ×3 | 29 | 0 |

**×3 se hunde**: la tesela pierde tanto contexto que el modelo deja de reconocer formas.
Más zoom no es siempre mejor y existe un óptimo.

## 4 · Mapa de atención indexado *(alto)*

La prioridad de teselas se pasaba como **array indexado**, calculado por el cliente con
su propia copia del plan de teselas. Cualquier cambio del recorte entre esa llamada y el
uso desalinea los índices **en silencio**: la atención se aplica a las teselas
equivocadas y no hay forma de notarlo mirando la salida.

**Corrección:** se pasa una **función del rectángulo de tesela**. Una función no puede
desalinearse.

## 5 y 6 · Estructuras sin acotar *(alto)*

`brain.stature` guardaba muestras de toda persona vista en la sesión; solo se liberaba
la física. Ahora, al morir una trayectoria, se guarda el **resumen** (unos bytes en vez
de cientos de muestras) en un histórico acotado a 500 entradas y se suelta el resto.

`track.obs[]` crecía sin límite en objetos de vida larga. Mismo diezmado que los tubos.

## 7 · Umbral único *(medio)*

Una persona a media distancia es un objeto mucho más difícil que un turismo: menos
píxeles, silueta variable, casi siempre parcialmente ocluida por otra. Aplicarle el
mismo listón es exactamente lo que produce «detecta furgonetas pero no gente».

Umbral por clase: `person` 0,08 frente a 0,15 general. **De 2 a 6 personas** en el
mismo frame.

---

## Lo que sigue abierto

### 8 · Precisión absoluta de yolox_tiny

Frente a yolox_s como referencia, con ambos agrupados:

```
recall            86 %
sin respaldo      43 vehículos
```

Es un modelo pequeño trabajando a umbrales bajos: **inventa**. No es un fallo de
integración, es el modelo. Quien pueda permitirse el coste debería usar `yolox_s`;
quien no, subir el umbral de vehículo y aceptar menos recall.

### 9 · Carritos, sillas de ruedas, patinetes

No existen como clase en COCO. El modelo no puede detectar algo que nunca vio
etiquetado. Requiere otro modelo, no otro ajuste.

### 10 · Detección de cabezas para multitudes

En una plaza los cuerpos se ocluyen entre sí y las cabezas no; por eso el conteo de
multitudes usa cabezas o mapas de densidad. COCO no tiene clase «cabeza»: hace falta un
modelo entrenado para ello (CrowdHuman, SCUT-HEAD) o un método por densidad, que da
recuento sin cajas.

**No lo he construido porque no he podido validar pesos aquí**, y prometer una mejora
sin medirla es exactamente lo que esta auditoría existe para evitar.

---

## Lo que resistió la auditoría

- Sin funciones duplicadas ni identificadores rotos en el cliente.
- Ambos bucles (render y detector) con manejo de errores.
- `physics` se libera correctamente al morir la trayectoria.
- El umbral de corte barato usa el umbral **más bajo** de todos, no el general: con el
  general se descartarían las personas antes de mirar de qué clase son.
- Recorrido de rutas bloqueado en el servidor del lanzador.
- Cierre del servidor por PID y SIGTERM, no por inferencia sobre stdin.

---

# Segunda ronda · mejora del detector

## Modelos disponibles

Solo la familia YOLOX tiene pesos ONNX accesibles desde releases públicas. DAMO-YOLO y
RT-DETR devolvieron 404. Disponibles: `nano` (3 MB), `tiny` (19), `s` (34), `m` (96).

## 11 · Supresión suave *(soft-NMS)*

El NMS duro **borra** toda caja que solape más del umbral con una mejor. En una acera
con gente andando junta eso elimina personas reales: dos peatones adyacentes se solapan
legítimamente y el segundo desaparece.

Soft-NMS no borra: **rebaja la puntuación** en proporción al solape, con decaimiento
gaussiano. Un duplicado exacto se hunde bajo el umbral y muere; un vecino que solo roza
sobrevive.

## 12 · Detección multiescala

Un solo zoom obliga a elegir. ×1 ve bien los vehículos y pierde a la gente; ×2 ve a la
gente y parte los vehículos grandes en trozos que el descarte de bordes elimina. **No
existe un zoom bueno para ambos** porque los tamaños difieren en un orden de magnitud.

Se recorre a varios zooms y se fusiona. La supresión agrupada ya sabía resolver la misma
detección vista dos veces, así que no hace falta nada más.

| | Objetos | **Personas** | Recall | Precisión |
|---|---:|---:|---:|---:|
| zoom ×2, NMS duro | 111 | 6 | 90 % | 56 % |
| zoom ×2, soft-NMS | 166 | 6 | 91 % | 60 % |
| **×1 + ×2, soft-NMS** | 198 | **10** | **93 %** | 58 % |

De 2 personas iniciales a **10**. Es la cifra que más importa aquí.

> Salvedad honesta sobre la precisión: se mide contra `yolox_s`, que en este frame solo
> encontró **2 personas** — menos que nosotros. Como referencia para vehículos vale;
> para personas, nuestra medida de precisión las penaliza injustamente.

## 13 · Confirmación temporal

Un modelo pequeño a umbral bajo **inventa**, y lo inventado **parpadea**: la alucinación
cambia de sitio en cada pasada mientras un objeto real sigue ahí. Contar cuántas veces se
ha vuelto a ver una caja en el mismo sitio separa las dos cosas **sin tocar el modelo**.

Probado con 6 objetos estables y 4 alucinaciones nuevas por pasada:

| | Cajas al tracker | Precisión |
|---|---:|---:|
| Sin confirmación | 10 | 60 % |
| **Con confirmación (≥2 vistas)** | 7 | **86 %** |

La primera vista no se descarta, se marca como no confirmada: así un objeto que entra en
escena no tiene que esperar dos pasadas a aparecer, y quien consume decide.

---

# Tercera ronda · estudio sobre metraje real de cámara fija

## Acceso a cámaras públicas

Los servicios de webcams públicas (Windy, Skyline, agregadores) devuelven **403** desde
este entorno. Lo que sí es accesible es GitHub, y ahí está `vtest.avi` de OpenCV: cámara
**fija** (flujo óptico 0,022 px/frame), 768×576, 80 s, con peatones a media distancia y
furgonetas al fondo. Es el escenario exacto de las quejas.

## 14 · Me pillé a mí mismo inflando el resultado

La primera medida sobre esta escena dio esto:

```
zoom x1, NMS duro          9,6 personas/frame
multiescala + soft-NMS    16,8 personas/frame     "+75 %"
```

**Era falso.** En esa escena hay **tres peatones reales**. Mirando la distribución de
puntuación:

```
personas reales   0,91  0,91  0,90
resto             < 0,3      <- cajas diminutas sobre conos y señales
```

El corte del soft-NMS estaba en 0,06, que dejaba pasar todo. Al verificarlo visualmente,
las siete "personas" de más eran mobiliario urbano.

**La lección, que es la que importa:** *contar detecciones no es una métrica de calidad*.
Es exactamente el error que esta auditoría existe para cazar, y lo cometí midiendo mi
propia mejora. Sin la comprobación visual habría entregado un "+75 % de personas" que
era ruido.

## 15 · Corte por el hueco de la distribución

En una escena real la puntuación es **bimodal**: los objetos verdaderos se agrupan arriba
y la basura abajo, con un hueco limpio en medio. El corte debe caer en ese hueco.

| Corte | pers/frame | **≥0,5** | <0,3 | vehículos |
|---|---:|---:|---:|---:|
| ×1, NMS duro | 9,6 | 5,8 | 2,8 | 2,0 |
| multiescala, 0,06 | 16,8 | 11,6 | 3,6 | 10,4 |
| **multiescala, 0,20** | 8,4 | **7,2** | **0,2** | 5,0 |
| multiescala, 0,35 | 6,6 | 5,8 | 0,0 | 3,6 |

La columna que vale es **≥0,5**: personas con puntuación de objeto real.

Con corte 0,20 la mejora frente al punto de partida es **de 5,8 a 7,2 personas de alta
confianza (+24 %)** y la basura cae de 2,8 a 0,2 por frame. Eso es una mejora de verdad,
un tercio de la que había reclamado antes.

Los vehículos suben de 2,0 a 5,0 por frame: multiescala también recupera las furgonetas
del fondo, que a ×1 se perdían.

**Corte por defecto cambiado de 0,06 a 0,20.**

## Metodología que se queda

Cualquier medida futura sobre este sistema debe:

1. **Estratificar por puntuación.** Un total sin desglosar oculta si la mejora es señal
   o basura.
2. **Verificar visualmente** al menos un frame antes de creerse una cifra.
3. **Usar varios frames** repartidos por el metraje, no uno.
4. **Declarar la referencia.** "Precisión 58 %" contra un oráculo que ve menos personas
   que tú no significa lo que parece.

---

# Cuarta ronda · memoria

## 16 · El ojo que olvidaba al parpadear

Cerrar la pestaña borraba la sesión entera. `archive.js` añade archivo persistente sobre
IndexedDB.

**Qué se guarda y qué no** — es la decisión de diseño, no un detalle:

| | |
|---|---|
| **Sí** | tubos (trayectoria, siluetas RLE, clase, física, estatura), eventos, escala métrica por cámara |
| **No** | vídeo, frames, recortes de objeto |

No es solo espacio. **Un archivo sin imágenes no contiene datos personales
identificables**: una silueta lleva postura, marcha, clase, tamaño y posición, y no lleva
cara ni ropa reconocible. Guardar recortes sería posible y haría el sinopsis reproducible
meses después; se deja fuera a propósito, porque es la diferencia entre un índice de
actividad y un archivo de vigilancia, y esa línea debe cruzarse conscientemente.

## 17 · Empaquetado en arrays tipados

IndexedDB guarda por clon estructurado: un `Uint16Array` se almacena como binario, un
array de números como objetos.

| | Por observación |
|---|---:|
| JSON plano | 186 B |
| **Arrays tipados** | **134 B** |

Ida y vuelta exacta verificada. Proyección: **309 MB por ocho horas** a dos objetos por
segundo. Las columnas se separan por campo —frames juntos, cajas juntas, siluetas
juntas— para que cada una sea homogénea.

## 18 · La escala métrica parpadeaba

`SceneScale.fit()` descartaba un ajuste bueno en cuanto un intento posterior salía
pobre. La geometría de la cámara **no cambia** entre un minuto y el siguiente; lo que
cambia es cuántos objetos hay en la ventana de retención.

Sin histéresis, la escala oscilaba entre válida e inválida al ritmo del tráfico, y con
ella oscilaban las velocidades en m/s y las estaturas. En la simulación se rechazaron
**2 ajustes pobres** conservando el bueno.

Un ajuste válido solo se reemplaza por otro válido. Y si nunca hubo uno bueno, sigue
saliendo inválido — no se inventa nada.

## Verificación de sesión completa

```
SESION 1 (1800 frames): 22 tubos, 14 eventos archivados
  escala a=0.302 valida  | 2 ajustes pobres rechazados
SESION 2 (reapertura)  : escala recuperada a=0.302
  consulta personas    : 22   (primer tubo 344 obs, con silueta)
  consulta por ventana : 16
  eventos              : 14
```

La escala recuperada evita reaprender desde cero: la cámara no se ha movido entre ayer y
hoy, y los primeros minutos de cada sesión ya no se desperdician.

## Retención

`enforceRetention()` se ejecuta al abrir. No es una optimización: el plazo de supresión
de la videovigilancia es de **un mes** por defecto (art. 22 LOPDGDD), y un archivo que no
caduca solo es un incumplimiento que crece. Hay además borrado por objeto, que es lo que
exige de verdad una solicitud de supresión, y exportación completa para auditoría.

---

# Quinta ronda · identidad y multi-cámara

## 19 · Huella de apariencia sin segunda red

Enlazar cámaras necesita una huella. Una red de re-identificación (OSNet, 9 MB) añadiría
**una segunda inferencia por objeto** encima del detector, y el presupuesto ya está
ajustado. Se usa en su lugar un descriptor clásico sobre la silueta que el sistema **ya
tiene**:

- **Histograma HSV por bandas horizontales.** Las bandas importan: camisa clara con
  pantalón oscuro no es lo mismo que lo contrario, y un histograma global las confunde.
- **Solo píxeles dentro de la silueta.** Sin máscara, el fondo domina y todos los objetos
  sobre el mismo asfalto salen parecidos.
- **Suavizado circular en el tono**, porque el rojo del primer bin y el del último son
  vecinos y un píxel al otro lado de una frontera no debería contar como color distinto.

## 20 · El brillo absoluto arruinaba la huella

Primera versión, misma identidad vista en dos cámaras con distinta iluminación:

```
coseno misma identidad   0,154      <- prácticamente desconocidos
```

La causa: el histograma usaba **brillo absoluto**. La misma persona bajo el sol y bajo
una nube cae en bins distintos y deja de parecerse a sí misma.

Lo que distingue a una persona no es cuánta luz recibe, sino **que su camisa sea más
clara que su pantalón** — y eso se conserva dividiendo por la mediana de brillo del
propio recorte.

| | Misma identidad | Distintas | Margen |
|---|---:|---:|---:|
| Brillo absoluto | 0,154 | 0,001 | 0,137 |
| **Brillo relativo** | **0,993** | 0,114 | **0,455** |

## 21 · Enlace con verosimilitud calibrada

La similitud sola, sobre un archivo grande, **siempre** encuentra un buen candidato: es
una propiedad de la búsqueda en alta dimensión, no una prueba. Por eso el orden es:

1. **Topología primero.** Si el trayecto no es físicamente posible, no hay nada que
   puntuar.
2. **Calibración con impostores del propio sitio.** Dos trayectorias de la misma cámara
   cuyas vidas se solapan son objetos distintos: miles de negativos etiquetados gratis.
   Un coseno de 0,82 no significa nada hasta saber cómo es 0,82 entre desconocidos *en
   esta cámara, con esta luz*.
3. **Canales independientes que se suman**, no se promedian: un desacuerdo de color o una
   diferencia de estatura son contraevidencia real y deben poder hundir un parecido
   atractivo.

Con 8 identidades vistas en dos cámaras con iluminación distinta:

| Nivel | n | Precisión | Recall |
|---|---:|---:|---:|
| **fuerte** | 8 | **100 %** | **100 %** |
| fuerte + moderada | 14 | 57 % | 100 % |

La escala es deliberadamente gruesa —fuerte, moderada, débil, insuficiente— porque un
porcentaje implicaría una calibración que el descriptor no tiene.

## Y lo que no hace, que es la parte importante

**Ninguna identidad se escribe automáticamente.** El sistema devuelve hipótesis con su
evidencia adjunta: transición, ventana temporal, distribución de impostores, acuerdo de
color y de estatura. Decide una persona.

Esa no es una limitación técnica que quede por resolver. Es la línea entre **asociar
apariencias** e **identificar personas**, y es lo que mantiene el sistema del lado
correcto del AI Act — además de ser lo único que sobreviviría a un contrainterrogatorio.

---

# Quinta ronda · identidad y multicámara

## 19 · Descriptor de apariencia sin modelo

`identity.js` calcula una huella sobre el recorte que el motor ya tiene, sin descargar un
modelo de re-identificación. Un OSNet daría mejores números, pero son 10 MB más y una
inferencia extra por objeto; aquí hace falta un descriptor **honesto sobre lo que puede
y no puede distinguir**, no el mejor posible.

Tres decisiones que lo hacen funcionar:

- **Bandas horizontales**, no histograma global. En una persona son cabeza, torso y
  piernas; un histograma global promedia camiseta y pantalón en un color que no
  distingue a nadie.
- **Brillo relativo al propio objeto.** Con brillo absoluto, la misma persona bajo el sol
  y bajo una nube cae en bins distintos y deja de parecerse a sí misma. Lo que la
  distingue no es cuánta luz recibe, sino que su camisa sea más clara que su pantalón.
- **Solo píxeles dentro de la silueta.** El fondo dentro de la caja es lo primero que
  arruina un descriptor: dos personas sobre el mismo asfalto salen idénticas.

Medido con cambio de iluminación del −40 %:

| | Coseno mediano |
|---|---:|
| Misma identidad | **0,993** |
| Identidades distintas | 0,338 |

Separación 0,655. **Pero el máximo entre identidades distintas llegó a 0,991**: un coseno
alto suelto no es prueba de nada, y por eso hay topología y calibración detrás.

## 20 · Emparejamiento mutuo

Sin él, cada trayectoria proponía enlace con **todas** las candidatas de su ventana
temporal: 115 hipótesis para 12 enlaces reales. La mayor parte del ruido era una misma
persona de la cámara A emparejada con media docena de la B.

Una persona no puede estar en dos sitios. Si A→B es el enlace, B también debe tener a A
como mejor candidato mirando hacia atrás. Exigir reciprocidad elimina el abanico **sin
subir umbrales**, que es lo que costaría recall.

| | Hipótesis | Precisión | Recall |
|---|---:|---:|---:|
| Sin mutuo | 115 | 10 % | 100 % |
| **Con mutuo** | **13** | **92 %** | **100 %** |

Es la misma idea que el *ratio test* de correspondencias visuales: una coincidencia solo
vale si es claramente la mejor por ambos lados.

## Lo que este módulo NO hace

**No identifica personas.** Asocia apariencias, propone hipótesis con confianza calibrada
contra la propia escena, y deja la decisión a un humano que queda registrado.

La escala de confianza es deliberadamente gruesa —fuerte, moderada, débil, insuficiente—
porque un porcentaje implicaría una calibración que el descriptor no tiene.

La distinción que importa bajo el AI Act no es «¿usa vectores?» sino **«¿el sistema
identifica a alguien, o solo dice que dos apariencias se parecen?»**. Mantener la
aserción de identidad en manos humanas, justificada y registrada, es lo que sitúa el
despliegue del lado correcto de esa línea — y, por separado, lo que hace que la salida
resista un contrainterrogatorio.

---

# Sexta ronda · pirámide de rejillas

## 21 · Rejilla gruesa que localiza, rejilla fina que mira

El multiescala plano recorre el cuadro entero a ×1 **y** a ×2. Gasta el grueso del
cómputo donde no aporta: el ×2 solo sirve donde hay objetos pequeños, y en una vista con
perspectiva eso es una franja, no toda la imagen.

En la pirámide el barrido grueso hace dos cosas: detecta lo grande y **dice dónde mirar
de cerca**. Una celda se refina si contiene una detección pequeña, si contiene una de
confianza dudosa, o si el modelo de perspectiva dice que ahí un objeto típico mide menos
de 48 px — esto último sin haber detectado nada, que es lo que la hace **adaptada a la
escena** y no a la imagen.

### Dos correcciones que cambiaron el resultado

**Cuatro inferencias por celda en vez de una.** La primera versión fijaba el zoom fino y
llamaba a `detect` sobre la subregión, que volvía a trocearla: una celda de 416 px a zoom
2 se partía en cuatro teselas de 208. Poniendo el lado de tesela igual al de la celda, el
plan devuelve una sola tesela y el letterbox hace la ampliación gratis.

**El techo de refinado recortaba a ciegas.** En la vista aérea de tráfico, el modelo de
perspectiva marcaba las 21 celdas —la escena entera está lejos— y el tope del 45 % dejaba
9. Se perdía un tercio de los objetos de alta confianza: **26 frente a 39**.

## 22 · Estrategia automática

Si casi todas las celdas piden refinado, eso no es una escena con mezcla de escalas: es
una escena uniformemente lejana, y la pirámide no aplica. El detector lo detecta y delega
en el barrido plano, **reutilizando el nivel grueso** en lugar de tirarlo — sin eso, la
decisión de no usar pirámide costaba un 16 % de tiempo.

| Escena | Estrategia elegida | Velocidad | Alta confianza |
|---|---|---:|---:|
| Peatones (vtest) | plano | 1,06× | 100 % |
| Tráfico (Alcalá) | plano | 1,01× | 100 % |
| **Mixta** (mitad vacía) | **pirámide** · 5/12 celdas | **3,26×** | **117 %** |

**El resultado honesto:** en mis dos escenas reales la pirámide **no aplica**, porque en
ambas todo el cuadro necesita resolución fina. Donde sí aplica —una escena con regiones
vacías o con mezcla real de tamaños— da **3,26× de velocidad y además encuentra más**.

Lo valioso no es que la pirámide gane siempre, que no lo hace. Es que el sistema **sabe
cuándo no aplica** y no paga por ello. Ajustar el umbral hasta que la pirámide "ganara"
en las dos escenas habría sido exactamente el error de método que esta auditoría lleva
seis rondas cazando.

---

# Séptima ronda · fluidez del visor

## Perfilado antes de tocar nada

Optimizar a ciegas es cómo acabé con el corte de soft-NMS en 0,06. Desglose del frame
a 320 px de análisis, sobre 1280×720:

| | ms | % |
|---|---:|---:|
| Modelo de fondo | 0,60 | 8 % |
| **Apertura morfológica** | 1,35 | 18 % |
| **Cierre morfológico** | 1,60 | 21 % |
| Componentes conexas | 0,23 | 3 % |
| Resto (lectura de canvas, tracking, física) | 3,72 | 50 % |
| **Total** | **7,50** | |

La morfología era el **39 %** del frame. El modelo de fondo, que parecía el sospechoso,
apenas el 8 %.

## 23 · Morfología en tiempo constante

La implementación recorría los 2r+1 vecinos de cada píxel buscando un acierto: O(r) por
píxel y por eje. Sobre una máscara **binaria** eso es innecesario, porque erosión y
dilatación se reducen a contar:

```
dilatación  ->  la ventana contiene al menos un 1
erosión     ->  la ventana está llena de 1
```

Un contador que suma el píxel que entra y resta el que sale da esa cuenta en O(1) por
píxel, sea cual sea el radio.

| | Antes | Después |
|---|---:|---:|
| Apertura r=1 | 1,347 ms | 1,124 ms |
| Cierre r=2 | 1,600 ms | 1,076 ms |
| **Total morfología** | **2,947 ms** | **2,200 ms** |
| Dilatación r=4 | ~3 ms | **0,530 ms** |
| Dilatación r=8 | ~6 ms | **0,515 ms** |

Las dos últimas filas son el resultado importante: **el coste ya no depende del radio**.
Con radios grandes —los que hacen falta con objetos cercanos— la ganancia pasa de 1,3× a
más de 10×.

Verificado: la salida coincide píxel a píxel con la implementación ingenua, y el número
de objetos seguidos no cambia.

Detalle que costó un rato entender: los bordes replican el píxel del borde en vez de
asumir ceros. Con ceros, una erosión come una franja del ancho del radio en los cuatro
lados y adelgaza sin motivo los objetos que tocan el borde.

## 24 · El overlay repintaba sin cambios

A 120 Hz con seis objetos quietos, el canvas redibujaba los mismos corchetes 120 veces
por segundo para producir exactamente la misma imagen. Ahora se compara una clave con las
posiciones **redondeadas** —un temblor subpíxel no es un cambio visible— y se salta el
repintado si nada se movió.

## Resultado

```
frame completo a 320 px:  7,50 ms -> 6,42 ms      133 fps -> 156 fps
```

Y la tabla de resoluciones, para elegir con criterio:

| Ancho de análisis | ms/frame | Techo |
|---:|---:|---:|
| 240 | 5,93 | 169 fps |
| **320** | **6,42** | **156 fps** |
| 400 | 9,59 | 104 fps |
| 480 | 12,91 | 77 fps |

Por encima de 320 px el coste sube deprisa sin que aparezcan más objetos: el detector
neuronal es quien resuelve lo pequeño, no la resolución del modelo de fondo.

---

# Octava ronda · atención foveal

## 25 · Un recorte por objeto, con el zoom que ese objeto pide

La rejilla reparte el cómputo por geometría de la imagen, no por dónde hay algo: en una
escena con seis objetos, veintiuna teselas son quince inferencias sobre asfalto vacío.

En la detección foveal mandan las propuestas. La sustracción de fondo cuesta medio
milisegundo y dice dónde se mueve algo; el tracker dice dónde había algo hace un
instante. Se recorta **alrededor de cada objeto** y se amplía hasta que llegue al modelo
con el tamaño que éste resuelve: una mancha de 12 px se amplía ×10, una de 200 px no se
amplía nada. **El zoom deja de ser un ajuste global y pasa a ser una propiedad de cada
objeto.**

Dos detalles sin los cuales no funciona: **contexto proporcional alrededor** (un recorte
pegado al objeto le quita al detector el suelo bajo los pies que necesita para
reconocerlo) y **fusión de recortes cercanos** (dos peatones a diez píxeles caen en el
mismo recorte; separarlos duplica la inferencia y parte por la mitad al que quede en el
borde).

## Resultados, y son mixtos

### Propuestas solo por movimiento

| Frame | Método | Inferencias | Alta confianza | Tiempo |
|---|---|---:|---:|---:|
| 900 | multiescala | 42 | 39 | 32 s |
| 900 | **foveal** | **7** | **2** | **2 s** |

15× más rápido y encuentra un 5 %. La causa no es el algoritmo: **en Alcalá a mediodía el
tráfico está parado**. Sin movimiento no hay propuesta, y la mayoría de los vehículos
llevan minutos quietos en un atasco.

### Régimen estable: barrido que siembra, foveal que mantiene

| Método | Inferencias | Alta confianza | Tiempo | vs barrido |
|---|---:|---:|---:|---:|
| Barrido de siembra | 42 | 39 | 51 s | — |
| Foveal alimentado por el barrido | 11 | 17 | 9 s | 5,4× · **44 %** |
| Foveal en escena dispersa | 6 | 3 | 2 s | 4,9× · **50 %** |

Zoom aplicado por objeto en la escena dispersa: **×2 y ×2,8**. El mecanismo hace lo que
debe.

## El veredicto honesto

**La detección foveal es de 5 a 50 veces más barata y recupera entre el 40 % y el 50 % de
lo que encuentra un barrido completo.** No es un sustituto del barrido: es un mecanismo
de **mantenimiento**.

El papel correcto es el que sugiere el propio dato: **un barrido periódico siembra, el
foveal mantiene barato entre siembras.** Los objetos conocidos se re-detectan a una
fracción del coste, y el barrido se encarga de lo que entra en escena — sobre todo de lo
que entra parado, que ninguna propuesta por movimiento verá jamás.

En escena densa la fusión de recortes los agranda hasta que el zoom efectivo vuelve a
ser ×1, y se pierde toda la ventaja. Es la tercera vez en esta auditoría que aparece el
mismo patrón: **una técnica que gana en escena dispersa y se degrada en densa**, y la
respuesta correcta no es forzarla sino que el sistema elija.

---

# Novena ronda · remediación de la auditoría de seguridad

## Estado

| Prioridad | Encontrados | Corregidos |
|---|---:|---:|
| P0 | 2 | **2** |
| P1 | 5 | **5** |
| P2 | 7 | 5 |
| P3 | 4 | 0 |

## P0-1 · SSRF sin autenticar vía `spec`

`POST /api/sources` entregaba una cadena del usuario a `cv2.VideoCapture` y a
`urlopen` sin filtro. FFmpeg abre `file:///etc/passwd` igual que abre RTSP, y una URL
a `169.254.169.254` lee credenciales de metadatos en la nube.

`argos/ingest/policy.py`: **lista blanca**, no negra --- una lista negra se queda corta
el día que FFmpeg añade un esquema; una blanca falla del lado seguro.

| Vector | Antes | Ahora |
|---|---|---|
| `file:///etc/passwd` | abría | rechaza |
| `http://169.254.169.254/…` | abría | rechaza |
| `http://127.0.0.1:8000/api/sources` | abría | rechaza |
| `http://192.168.1.50/snap.jpg` | abría | rechaza salvo `ARGOS_ALLOW_PRIVATE=1` |
| `push://telefono`, `0`, RTSP pública | abría | abre |

Se resuelven **todas** las direcciones del nombre, no solo la primera: un nombre puede
resolver a una pública y a una privada a la vez, y validar solo la primera es la vía
clásica para saltarse esta comprobación.

**Fallo en mi propia corrección:** rechazaba `\r\n` crudo pero aceptaba `%0d%0a`. Lo
detectó la propia batería de pruebas.

## P0-2 · CORS comodín + token en query

`allow_origins=["*"]` permitía que cualquier página visitada por el operador llamase a la
API. Combinado con el token en query --- que `<img>` y `<video>` obligan a usar y que
acaba en el historial --- bastaba con que ese token se filtrase.

Ahora el origen se restringe al propio servidor, ampliable con `ARGOS_CORS_ORIGINS` como
decisión explícita.

## P1 · Los cinco

**Documentación pública.** `/docs`, `/openapi.json` y `/redoc` estaban en `PUBLIC_PATHS`:
publicaban la superficie completa antes de pedir el token. Fuera de la lista y apagadas
cuando hay token.

**Carrera al arrancar el proceso.** `if status == "running"` seguido de lanzar el hilo no
es atómico: dos POST simultáneos lanzaban dos hilos sobre el mismo tracker, mutando
estado compartido. Verificado con 50 peticiones concurrentes: **1 hilo, 49 rechazos 409**.

**Retención que se ahogaba.** Cargaba el almacén entero en memoria para filtrarlo en JS.
A 309 MB cada ocho horas, treinta días son varios gigas: la pestaña moría ejecutando
justo la rutina que impide que el archivo crezca. Ahora índice `wall` + cursor acotado:
200 registros caducados eliminados en 224 ms sin cargar nada.

**Enlace entre cámaras O(n²).** Ahora corta por la ventana temporal alcanzable:

| Trayectorias | Tiempo |
|---:|---:|
| 500 | 33 ms |
| 2.000 | 64 ms |
| 8.000 | **303 ms** |

**Modelo ONNX por URL.** Un grafo ONNX es código ejecutable. Documentado como tal.

## P2 · Cinco de siete

MJPEG con corte por desconexión y tope de una hora --- antes un stream activo cuyo
cliente había cerrado la pestaña seguía comprimiendo JPEG contra un socket muerto.
`DELETE` ahora para el hilo antes de liberar la fuente. `innerHTML` con datos de API
sustituido por nodos. Caché de plan de teselas con camino rápido para recortes de una
sola tesela. `pyproject.toml` con rangos **acotados por arriba** --- sin techo, una
versión mayor de numpy entra sola y rompe el pipeline sin que el repositorio cambie.

## Hallazgo nuevo, no estaba en la auditoría

**Tres de los cuatro ficheros de prueba no tenían ni una aserción.** Imprimían métricas.
Una regresión que dejara el recall en la mitad habría impreso un número peor y seguido
"pasando". Es la trampa exacta que el enunciado pedía buscar, y estaba en el sitio menos
visible: en las pruebas mismas.

```
Suite: 27 tests, 27 en verde
  · 24 nuevos de seguridad (SSRF, traversal, symlink, CRLF, auth)
  ·  3 de regresión del tracker, con umbrales que son alarmas y no objetivos
```

## Pendiente

`main.py` sigue siendo un módulo de 24 endpoints con estado global (P2). Sin telemetría
estructurada (P3). `desktop/argos.html` sigue siendo un artefacto de compilación
versionado junto al código (P3), y ya se desincronizó una vez durante esta sesión.

---

# Décima ronda · comportamiento aprendido

## Qué hace la competencia, y dónde está el hueco

| | Enfoque de detección | Reglas |
|---|---|---|
| **Frigate** | detector local con aceleración por hardware (EdgeTPU, OpenVINO, TensorRT); búsqueda semántica CLIP; descripciones con IA generativa | zonas y filtros que configura el usuario |
| **BriefCam / XProtect** | sinopsis de vídeo, búsqueda forense, integración VMS | reglas y umbrales que configura el usuario |
| **ARGOS** | detector en navegador (WASM/WebGPU), escala métrica aprendida | **ninguna: se aprenden** |

Donde ARGOS va claramente por detrás: **aceleración por hardware** (Frigate baja a
milisegundos con una Coral; ARGOS depende de WASM o WebGPU) y **búsqueda semántica**
(CLIP permite "persona con mochila roja"; ARGOS solo compara histogramas de color).

> Los pesos de CLIP no son accesibles desde este entorno (HuggingFace y jsDelivr
> devuelven 403), así que **no está implementado ni prometido**. Frigate además
> documenta que los disparadores texto-a-imagen con CLIP son poco fiables por deriva
> del embedding: sirve para explorar, no para alertar.

## 26 · El hueco real: nadie aprende qué es normal

Todos disparan con reglas que alguien configura: dibuja una línea, elige dirección, pon
un umbral. Dos problemas que no arregla una interfaz mejor:

1. **Alguien tiene que saber de antemano qué es raro.** En una calle que no conoce, no
   lo sabe.
2. **Un umbral fijo no distingue contexto.** 30 km/h en una autovía y 30 km/h en una
   acera son la misma cifra con significados opuestos.

`behaviour.js` no configura nada. Divide la escena en celdas y cada una aprende de las
propias trayectorias: **flujo dominante** (histograma circular de rumbos), **distribución
de velocidad** en m/s reales gracias a la escala aprendida, y **ocupación por clase**.

### Aprendizaje sobre el vídeo real, sin configurar nada

```
52 trayectorias, 2.709 puntos
  celdas tocadas    17 / 180
  maduras           15
  con flujo claro   11
```

El mapa reproduce la calle: dos sentidos opuestos en la calzada, y **la celda del cruce
sale con concentración 0,17** — el modelo dice por sí solo que ahí la dirección no
significa nada. Nadie se lo indicó.

### Detección de circulación a contramano

Cada trayectoria real, puntuada tal cual y con el recorrido invertido:

| | Score mediano |
|---|---:|
| Tráfico normal | 0,269 |
| A contramano | **0,439** |

| | |
|---|---|
| Normales por debajo del umbral | **27/27 — cero falsas alarmas** |
| Invertidas detectadas | 20/27 — **74 %** |

El 26 % que se escapa son trayectorias que transcurren en celdas sin flujo claro (el
cruce, las zonas de poco tránsito). **Es el comportamiento correcto**: en el cruce los
coches giran en todas direcciones y marcar ahí sería inventarse una infracción.

### Tres decisiones que evitan el generador de falsas alarmas

**Una celda con pocas muestras no opina.** Sin esa reserva, el primer día todo es
anómalo, el operador apaga las alertas y el sistema deja de existir.

**Vector medio circular, no media aritmética.** 350° y 10° promedian 180° — exactamente
la dirección contraria a la real.

**Los objetos casi parados no votan dirección.** Su rumbo es ruido; meterlos en el
histograma aplana la señal de los que sí se mueven.

**La salida es un desglose, no un número.** Un operador necesita saber *por qué* se ha
marcado algo. Es la diferencia entre una alerta y una acusación.

## Lo que sigue faltando frente a Frigate

**Aceleración por hardware.** Es la brecha grande y no se cierra con algoritmos: una
Coral o TensorRT son uno o dos órdenes de magnitud. La respuesta realista de ARGOS es
WebGPU, que ya está en el detector pero no he podido medir aquí.

**Búsqueda semántica.** Requiere pesos que no puedo descargar ni validar en este
entorno.

---

# Undécima ronda · precisión del modelo de comportamiento

Tres defectos de precisión, encontrados mirando los datos intermedios en vez de la
puntuación final.

## 27 · «Sin dirección» y «dos direcciones» no son lo mismo

La celda con más tránsito del vídeo (269 observaciones) daba concentración **0,17**, y el
modelo la trataba como zona sin flujo. El histograma crudo mostraba otra cosa:

```
(8,5)  n=269  conc 0.17   [  .-@.. .*=.]
                             ^^^      ^^^
                          dos picos opuestos
```

Es una calzada de doble sentido. El vector medio circular los cancela, y confundir
«ninguna dirección» con «dos direcciones» cuesta caro por partida doble: en esa celda no
se detecta a nadie a contramano *y* cualquier rumbo se considera normal.

Descomponiendo el histograma en **modos** (picos con su masa contigua):

| Celda | Antes | Ahora |
|---|---|---|
| (8,5) | 0,17 «sin dirección» | **129° al 59 %** (c=0,89) · **298° al 41 %** (c=0,92) |
| (9,4) | 0,16 | 103° al 50 % · 303° al 50 % |
| (10,3) | 0,40 | 286° al 42 % · 20° al 35 % · 199° al 23 % |

Celdas direccionales: **de 11 a 15 sobre 15 maduras**. Y cada vehículo se juzga contra
**su carril**, no contra el promedio de los dos.

## 28 · Una z simétrica sobre una distribución sesgada

El término de velocidad daba **7,2 sigmas de mediana en tráfico legítimo**, con máximos de
50,9. Cifras que no significan nada y que además ahogaban al término de dirección, que es
el que discrimina.

La causa: la velocidad en tráfico está sesgada a la derecha --- muchos objetos lentos o
parados y una cola de rápidos. Mediana ± MAD marca la cola normal como extrema.

Sustituido por **banda de cuantiles**: cuánto se sale de [p05, p95] de esa celda, en
unidades de la anchura de esa banda, y acotado. Dentro del rango la aportación es
exactamente cero --- circular a la velocidad de la zona no es una anomalía pequeña, es
ninguna.

Y la velocidad de cada punto pasa a ser **mediana local de cinco muestras**: la diferencia
entre dos posiciones consecutivas es muy ruidosa, y comparar ruido contra una
distribución aprendida produce anomalías inventadas.

```
exceso de velocidad en tráfico normal:  7,20 mediana / 50,9 max  ->  0,12 / 6,17
```

## 29 · Promediar diluye la evidencia

Un vehículo a contramano por un tramo sostenido, cuyo recorrido pasa también por un cruce
y por zonas de poco tránsito, veía su puntuación arrastrada a cero por los puntos que no
dicen nada.

El percentil 75 de las discrepancias responde a la pregunta correcta: **«¿hubo un tramo
claro en contra?»**, no «¿fue en contra de media?».

## Resultado acumulado

| | Base inicial | Con modos | + cuantiles | + cuantil de evidencia |
|---|---:|---:|---:|---:|
| Score normal (mediana) | 0,269 | 0,273 | 0,049 | **0,056** |
| Score a contramano | 0,439 | 0,460 | 0,274 | **0,356** |
| **Separación** | 1,6× | 1,7× | 5,6× | **6,4×** |
| Invertidas > su propia normal | — | 25/27 | 25/27 | **25/27 (93 %)** |

| Umbral | Falsas alarmas | Detectados |
|---:|---:|---:|
| 0,20 | 15 % | **81 %** |
| 0,25 | 7 % | 67 % |
| 0,30 | 4 % | 63 % |

## La lectura honesta

**La separación mejoró 4×, pero el umbral absoluto sigue sin ser un buen instrumento.**
Con 27 trayectorias de treinta segundos, el punto de operación no se puede fijar con
confianza: la diferencia entre 0,25 y 0,30 son dos trayectorias.

Lo sólido es la **comparación pareada**: el 93 % de las trayectorias invertidas puntúa por
encima de su propia versión normal. Eso dice que el modelo ordena bien, que es lo que un
operador necesita --- una bandeja priorizada, no un semáforo.

El 7 % restante son trayectorias que transcurren casi enteras en el cruce, donde ir en
cualquier dirección es normal. Ahí el modelo **acierta al no marcar**.

---

# Duodécima ronda · Puerta del Sol

Metraje nuevo: captura de pantalla de una cámara pública en directo de la Puerta del Sol,
26 s, 2712×1220, cámara fija verificada (0,018 px/frame). Plaza llena de peatones ---
exactamente el escenario donde ARGOS fallaba con seis objetos en el Panteón.

## Resultados

| | Alcalá (calle, aéreo) | **Puerta del Sol (plaza)** |
|---|---:|---:|
| Personas de alta confianza | 2 | **65** |
| Tubos | 53 | **1.070** |
| Observaciones | 2.709 | **42.363** |
| Perspectiva | válida, n=44 | válida, n=887 |
| Celdas maduras | 15 | **81** |

La diferencia no es el software: **en Sol los peatones ocupan 40-80 px y en Alcalá 8-12**.
Es lo que llevo diciendo toda la sesión, y aquí se ve en una sola cifra.

## 30 · El modelo distingue una calle de una plaza, y lo dice

El detector de contraflujo, que en Alcalá daba 6,4× de separación, aquí se desploma:

| | Alcalá | Sol |
|---|---:|---:|
| Modos de dirección por celda | **1,47** | **2,90** |
| Cuota del modo principal | 0,82 | 0,48 |
| Separación contraflujo | 6,4× | **1,1×** |

Las celdas más transitadas de Sol tienen **cuatro modos al 25 % cada uno**. En una plaza
los peatones caminan en todas las direcciones: **no existe el contraflujo**. Un sistema
con una regla fija ahí generaría miles de falsas alarmas al día.

**Que la separación caiga a 1,1 no es un fallo del modelo: es el modelo diciendo la
verdad sobre el sitio.**

Añadido `informativeness()`, que publica qué señales sirven en cada escena:

```
ALCALA   direccion 0.80  velocidad 0.67  clase 0.33
         -> "flujo canalizado (calle, pasillo, carril)"

SOL      direccion 0.28  velocidad 0.02  clase 0.05
         -> "flujo mixto"
```

Sin esto, un operador vería puntuaciones bajas en Sol y concluiría que el sistema no
funciona. Lo que ocurre es que **esa señal no aplica ahí**, y el sistema debe decirlo en
lugar de dejar que se interprete como avería.

## Lo que queda abierto

En una plaza hace falta **otro repertorio de señales**: velocidad atípica (alguien
corriendo entre gente que pasea), detenciones prolongadas en zonas de paso, densidad
anómala. La dirección, que es la señal fuerte en una calle, aquí no sirve --- y el
sistema ya lo sabe, que es el primer paso para no apoyarse en ella.

---

# Decimotercera ronda · qué falta para 60 fps sostenidos

## Punto de partida medido

Escena densa sintética (130 objetos, como Puerta del Sol), presupuesto 16,7 ms:

| Ancho de análisis | Recortes cada N | ms/frame | Techo | Margen |
|---:|---:|---:|---:|---:|
| 240 | 3 | **5,10** | 196 fps | 11,6 ms |
| **320** | **3** | **7,36** | **136 fps** | **9,3 ms** |
| 320 | 1 | 8,11 | 123 fps | 8,6 ms |
| 400 | 3 | 9,32 | 107 fps | 7,4 ms |

**El bucle rápido ya cumple con holgura.** Lo que impide los 60 fps no es el análisis.

## 31 · La captura de recortes codificaba y decodificaba JPEG por objeto

`PatchBuffer.add` hacía, **por objeto y por frame**: redimensionar el canvas (que fuerza
una reasignación), dibujar, codificar a JPEG con `toBlob`, y **volver a decodificar** ese
JPEG con `createImageBitmap`.

Una compresión y una descompresión completas por recorte. Y el JPEG **no se guardaba**:
solo se usaba su `size` para contabilizar memoria. Se pagaba un códec entero para obtener
una cifra.

A 130 objetos y 60 fps son **7.800 codificaciones y 7.800 decodificaciones por segundo**.
No es que fuera lento: era imposible.

Corregido con `createImageBitmap(source, x, y, w, h)`, que recorta en un paso sin canvas
intermedio ni códec. El tamaño se estima --- el contador solo existe para acotar memoria.

## 32 · El `await` estaba dentro del bucle

Ciento treinta recortes **en serie**, cada uno esperando al anterior. Ahora van con
`Promise.all` y **no se esperan**: alimentan al sinopsis, que se compone después.
Bloquear el frame por ellos es pagar latencia de render por un dato que nadie mira aún.

Y con cadencia propia: **un recorte cada tres frames** basta para componer un sinopsis,
porque entre dos frames consecutivos la apariencia de un objeto no cambia. Misma idea que
separa el detector del render.

```
antes  (serie, encode+decode) : 150 ms por frame
ahora  (paralelo, sin códec)  :   2 ms
       amortizado 1 de cada 3 :   0,7 ms
```

## Lo que realmente falta para 60 fps de punta a punta

El análisis cumple. Los tres cuellos que quedan están **fuera** de él:

**1. El detector neuronal, y es el grande.** 37 s por frame a multiescala en WASM de un
hilo. No entra en 16,7 ms por ningún camino, y la arquitectura ya lo asume: corre en su
propio ciclo, a su cadencia, sin bloquear el render. Para acercarlo:

- **WebGPU** --- ya está en el código, sin medir aquí por falta de GPU. Es la palanca
  realista en navegador.
- **WASM multihilo** --- requiere las cabeceras COOP/COEP que el lanzador de escritorio ya
  envía. De 120 ms a ~20 ms por tesela.
- **Web Worker de verdad** --- hoy el detector comparte hilo con la interfaz; una
  inferencia de 20 ms se come un frame entero. Es la pieza pendiente que más se nota.
- **yolox_nano** --- 2,9× más rápido que tiny, medido.

**2. La lectura del canvas.** `getImageData` copia el frame de GPU a CPU cada vez. En una
escena de 1400×700 a 320 px de análisis es asumible, pero es el suelo del bucle rápido y
no baja sin mover la sustracción de fondo a WebGL/WebGPU.

**3. El dibujo con muchos objetos.** Con 132 objetos, 132 etiquetas de texto por frame.
El repintado ya se salta cuando nada cambia, pero en una plaza siempre cambia algo. Se
resuelve dibujando las etiquetas solo bajo demanda.

## Resumen honesto

| Componente | Estado a 60 fps |
|---|---|
| Fondo, asociación, física | **cumple** con 9,3 ms de margen |
| Captura de recortes | **cumple** tras corregir el códec |
| Dibujo | cumple; degrada con >100 etiquetas |
| **Detector neuronal** | **no cumple y no va a cumplir** por frame |

La respuesta correcta no es meter el detector en el presupuesto de 16,7 ms --- eso no se
consigue en navegador con un modelo real. Es lo que ya hace el sistema: **dos relojes**.
El render y el seguimiento a 60 fps o más; el detector a la suya, confirmando qué es cada
cosa y qué entra en escena.

---

# Decimocuarta ronda · dos relojes, verificado de punta a punta

## 33 · El detector en hilo propio

Hasta ahora el detector tenía ciclo desacoplado pero compartía hilo con la interfaz.
JavaScript es de un solo hilo: mientras el modelo corre, **nada más corre**. Una
inferencia de 120 ms se come siete frames seguidos, y ningún ajuste de cadencia lo
arregla porque el problema no es cuándo se lanza sino dónde.

| | Mismo hilo | **Hilo propio** |
|---|---:|---:|
| fps efectivo | 33 | **62** |
| Peor frame | 120 ms | **0 ms** |
| Inferencias completadas | 10 | 10 |

Mismo trabajo, el doble de fluidez. El detector no va más rápido: **deja de competir con
la pantalla**.

Verificado de punta a punta con YOLOX real sobre Puerta del Sol:

```
render     : 2.786 frames en 45 s  ->  62 fps
peor frame : 0 ms                      (presupuesto 16,7)
inferencias: 11 | 198 objetos, 70 alta confianza, 65 personas
```

Tres decisiones del diseño:

**El Worker recupera el código del detector del propio documento**, no lo vuelve a
descargar. En un fichero autónomo descargarlo sería absurdo, y garantizaría que las dos
copias divergen.

**Si llega un frame mientras procesa el anterior, se descarta.** Encolarlos parece más
completo y es peor: la cola crece más rápido de lo que se vacía y el detector acaba
respondiendo sobre imágenes de hace diez segundos.

**Activa WASM multihilo cuando la página está aislada**, que es exactamente lo que
proporcionan las cabeceras COOP/COEP del lanzador de escritorio.

## 34 · No existe un zoom universal

A resolución nativa en Puerta del Sol:

| Modelo | Zoom | Alta confianza | Personas | Tiempo |
|---|---:|---:|---:|---:|
| **nano** | **×1** | **70** | **65** | **3,2 s** |
| nano | ×2 | 51 | 44 | 9,9 s |
| tiny | ×1 | 71 | 64 | 9,6 s |
| tiny | ×2 | 6 | 1 | 35,4 s |

**El ×1 gana al ×2 --- lo contrario que en Alcalá**, donde el ×2 triplicaba las personas
detectadas. La razón es el tamaño del objeto: en Sol los peatones miden 40-80 px y ya son
resolubles; ampliarlos los reparte entre teselas y el descarte de bordes los elimina. En
Alcalá medían 8-12 px y sin ampliar no existían.

Es la justificación empírica de la pirámide guiada por perspectiva: **el zoom correcto es
una propiedad de la escena, y el sistema puede deducirlo del tamaño esperado del objeto en
cada zona** en lugar de que alguien lo configure.

Y `nano` iguala a `tiny` a **2,5× menos coste** en esta escena. El modelo grande solo
compensa cuando los objetos son pequeños.

## 35 · Un error mío en la medición

La primera pasada con el Worker dio 8-11 personas, no 65. La causa no era el Worker: yo
había reducido la secuencia a **media resolución** para que cupiera en memoria. Los
peatones pasaron de 40-80 px a 20-40, y la detección cayó seis veces.

Es el mismo principio que atraviesa toda esta auditoría, y lo volví a pisar midiendo mi
propia mejora: **el presupuesto de píxeles por objeto manda sobre cualquier otro ajuste.**

---

# Decimoquinta ronda · la escala depende de dónde, no solo de a qué altura

## 36 · Un modelo de una variable donde hacían falta dos

La escala métrica era `altura = a·y + b`: **solo dependía de la fila**. Eso asume que la
línea de fuga del suelo es horizontal en la imagen, y solo se cumple si la cámara no
tiene alabeo. En cuanto está girada unos grados --- o el objetivo es angular --- dos
personas en la misma fila y a distinto lado del cuadro se proyectan con tamaños
distintos, y un modelo de una variable no puede representarlo.

Bajo proyección de un plano, la altura imagen de un objeto vertical de tamaño fijo es
proporcional a `l·p`, con `l` la línea de fuga: **lineal en x y en y a la vez**. El
término en x es literalmente el alabeo de la cámara.

## 37 · Pero añadirlo no siempre es correcto

Ajustando los dos modelos sobre metraje real:

| | Solo y | Con x |
|---|---:|---:|
| **Sol** (plaza ancha) | σ 5,45 px | **σ 5,24 px** |
| **Alcalá** (calle diagonal) | σ 4,15 px | **σ 5,81 px** (−40 %) |

En Alcalá empeora, y el coeficiente en x se dispara a triplicar el de y. No es que esa
cámara tenga más alabeo: es que **todas las muestras caen sobre una línea** en (x, y) ---
la calle va en diagonal --- los dos coeficientes son inseparables y el sistema está mal
condicionado. El ajuste elige una de las infinitas soluciones que pasan por esa línea.

**El término en x se adopta solo cuando los datos lo sostienen**: correlación x-y baja,
cuarenta muestras como mínimo, y mejora real del residuo por encima del 3 %.

Sobre el metraje real, la decisión sale sola:

```
ALCALA   colinealidad 0,88  ->  usa x: NO    h = 0,0047y + 17,2
SOL      colinealidad 0,17  ->  usa x: SI    h = 0,0180y + 0,00104x + 7,0
```

En Sol, dos personas **en la misma fila** tienen escalas que difieren un **9 %** entre el
lado izquierdo y el derecho del cuadro. Eso se traducía antes en un 9 % de error
sistemático en velocidad y estatura para todo lo que no estuviera en el centro.

Verificado también sobre escenas sintéticas de geometría conocida:

| Escena | Real | Recuperado | Decisión |
|---|---|---|---|
| Con alabeo | a=0,0600 cx=0,01200 | a=0,0625 **cx=0,01216** | usa x ✓ |
| Calle diagonal | a=0,0600 sin x | a=0,0600 **cx=0** | rechaza x ✓ |

El solver devuelve `null` ante un sistema singular en lugar de un resultado cualquiera:
si las columnas son linealmente dependientes, el sistema no determina los coeficientes, y
devolver "algo" sería inventárselos.

## Lo que sigue sin modelarse

**La distorsión radial.** En Sol se ve que las líneas rectas de los edificios se curvan:
es un gran angular. Un plano en (x, y) captura el alabeo pero no la distorsión de barril,
que comprime los objetos hacia los bordes. Corregirla exige calibrar el objetivo o
estimar el coeficiente radial de las propias trayectorias --- las líneas rectas del suelo
deberían proyectarse rectas, y no lo hacen.

Ese es el siguiente término del modelo, y con 963 tubos repartidos por todo el cuadro
hay datos de sobra para estimarlo.
