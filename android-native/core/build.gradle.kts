plugins { id("org.jetbrains.kotlin.jvm") }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    // Solo para las pruebas de paridad del detector en la JVM; la app trae su
    // propio runtime, el de Android.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")
}

// Paridad con engine.js, en el CI y en local. Antes hay que generar el volcado
// de referencia con `node scripts/dump_js.js`; sin él la prueba lo dice y falla.
tasks.register<JavaExec>("paridad") {
    group = "verification"
    description = "Compara el núcleo Kotlin con engine.js frame a frame (cero divergencias)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("ParityKt")
    workingDir = rootProject.projectDir.parentFile
    args = listOf("android-native/build/ref_js.txt")
}

// Paridad del detector con detector.js (C1). La referencia la generan
// scripts/prep_detector_ref.py y scripts/ref_detector.js en android-native/build/det.
tasks.register<JavaExec>("paridadDetector") {
    group = "verification"
    description = "Compara Detector.kt con detector.js sobre fotos reales, cuadro entero y teselas."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("eus.argos.paridad.DetectorParityKt")
    workingDir = rootProject.projectDir.parentFile
    args = listOf("android-native/build/det")
}

// Caso reservado: filas rellenadas como las entrega CameraX, mismas pistas.
tasks.register<JavaExec>("casoReservado") {
    group = "verification"
    description = "Mismo vídeo con y sin relleno de filas (rowStride): pistas idénticas."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("eus.argos.paridad.StrideCaseKt")
}

// Cámara en mano: paneo con temblor sin objetos (cero pistas falsas) y con una
// persona andando (sigue vista).
tasks.register<JavaExec>("camaraEnMano") {
    group = "verification"
    description = "Paneo sintético: sin pistas falsas y la persona sigue vista."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("eus.argos.paridad.HandheldCaseKt")
    args = listOf("persona", "exigir")
}

// Catálogo de cámaras públicas: formatos de Caltrans y TfL. Con -Pvivo=<dir>
// lee además las respuestas reales que el CI baja de los organismos.
tasks.register<JavaExec>("catalogo") {
    group = "verification"
    description = "Las listas de cámaras públicas se leen y dan cámaras con vídeo."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("eus.argos.paridad.CatalogoCaseKt")
    val vivo = project.findProperty("vivo") as String?
    args = if (vivo != null) listOf("vivo", vivo) else emptyList()
}

// Estabilizado en pantalla: quita el balanceo de la mano, deja pasar un paneo.
tasks.register<JavaExec>("estabiliza") {
    group = "verification"
    description = "El balanceo de 2 Hz baja a menos de la mitad y un paneo se sigue."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("eus.argos.paridad.EstabilizaCaseKt")
}

// La voz: fonética del español contra espeak-ng (referencia guardada en
// src/test/resources) y números, horas y unidades dichos bien.
tasks.register<JavaExec>("voz") {
    group = "verification"
    description = "Fonemas como espeak-ng y cifras dichas bien."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("eus.argos.paridad.VozCaseKt")
}

// Muestra de audio con una voz Piper: -Pmodelo=… -Psalida=… -Ptexto=@fichero
// [-Pruido=0.4] [-Pefecto=NATURAL|ESTUDIO|CRUDA]
tasks.register<JavaExec>("sintetiza") {
    group = "verification"
    description = "Sintetiza un WAV con una voz Piper."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("eus.argos.paridad.VozCaseKt")
    args = listOf("voz", (project.findProperty("modelo") ?: "") as String, (project.findProperty("salida") ?: "") as String,
                  (project.findProperty("texto") ?: "Hola.") as String,
                  (project.findProperty("ruido") ?: "-") as String, (project.findProperty("efecto") ?: "NATURAL") as String)
}

// Radio pasiva (opcional, apagada por defecto): firmas BLE de rastreadores,
// Remote ID de drones sin la posición del piloto, aviso de rastreador que se
// queda, interferencia y suplantación GNSS. Sin aparato: bytes construidos.
tasks.register<JavaExec>("radio") {
    group = "verification"
    description = "Firmas, Remote ID, rastreadores persistentes y GNSS, sin aparato."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("eus.argos.paridad.RadioCaseKt")
}

// Física plausible: sin escala con una perspectiva imposible, sin velocidad de
// un salto de caja, «corriendo» solo tras un segundo por encima del umbral.
tasks.register<JavaExec>("fisica") {
    group = "verification"
    description = "Velocidades imposibles no se miden ni disparan eventos."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("eus.argos.paridad.FisicaCaseKt")
}

// Ataques a la compensación de cámara: giro de muñeca, deriva subpíxel, objeto
// grande con la cámara fija, pared lisa, rayas, escena oscura. Mundo sintético,
// no necesita ficheros.
tasks.register<JavaExec>("casosCamara") {
    group = "verification"
    description = "La compensación de cámara aguanta giros, derivas, objetos grandes y escenas sin textura."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("eus.argos.paridad.casos.CasosCamaraKt")
    args = listOf("exigir")
}

// Zoom automático (C3 de la fase 4): cuadro entero con objetos grandes, teselas
// con personas pequeñas. Necesita plaza.rgba de scripts/prep_plaza.py.
tasks.register<JavaExec>("zoomAutomatico") {
    group = "verification"
    description = "El zoom automático elige bien en fotos con objetos grandes y en una plaza."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("eus.argos.paridad.AutoZoomBenchKt")
    workingDir = rootProject.projectDir.parentFile
    args = listOf("android-native/build/det", "exigir")
}

// Escena (C4 de la fase 4): escala métrica y física contra brain.js. La
// referencia la genera `node scripts/ref_scene.js`.
tasks.register<JavaExec>("paridadEscena") {
    group = "verification"
    description = "SceneScale y Physics contra brain.js, aritmética idéntica."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("eus.argos.paridad.SceneParityKt")
    workingDir = rootProject.projectDir.parentFile
}

// La escena entera en marcha: peatones a distintas distancias, la escala se
// aprende, salen velocidades en m/s y un evento de «detenido».
tasks.register<JavaExec>("escenaViva") {
    group = "verification"
    description = "Escena sintética de extremo a extremo: escala, velocidad, eventos, recuentos."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("eus.argos.paridad.SceneScenarioKt")
}

// Memoria (C5 de la fase 4): guardar, resumir, borrar un objeto, retención.
tasks.register<JavaExec>("memoria") {
    group = "verification"
    description = "Trayectorias: guardar, resumen, borrado por objeto y retención de 30 días."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("eus.argos.paridad.StoreCaseKt")
}

// Lo normal de la escena (behaviour.js): paridad numérica y una escena viva
// donde el único que va a contramano sale marcado y ningún normal lo hace.
tasks.register<JavaExec>("paridadComportamiento") {
    group = "verification"
    description = "BehaviourModel contra behaviour.js: madurez, perfiles y puntuaciones."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("eus.argos.paridad.BehaviourParityKt")
    workingDir = rootProject.projectDir.parentFile
}
tasks.register<JavaExec>("comportamientoVivo") {
    group = "verification"
    description = "Escena sintética: aprende el sentido de una acera y marca al que va en contra."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("eus.argos.paridad.BehaviourScenarioKt")
}

// El chat contra ask.js (cómo entiende cada frase) y respuestas sobre una
// memoria sembrada. La referencia: `node scripts/ref_ask.js`.
tasks.register<JavaExec>("chat") {
    group = "verification"
    description = "Ask.kt contra ask.js y respuestas con cifras que cuadran."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("eus.argos.paridad.AskParityKt")
    workingDir = rootProject.projectDir.parentFile
}
