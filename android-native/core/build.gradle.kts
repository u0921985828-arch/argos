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
