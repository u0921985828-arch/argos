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
