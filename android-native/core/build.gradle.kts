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
