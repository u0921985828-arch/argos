plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "eus.argos.nativo"
    compileSdk = 34

    defaultConfig {
        // Distinto del de la app web (`eus.argos.app`) a propósito: las dos
        // conviven en el mismo teléfono y se pueden comparar lado a lado.
        applicationId = "eus.argos.nativo"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-nativo"
        // ONNX Runtime trae sus .so para cuatro arquitecturas y el APK pesaba
        // 83 MB. x86 de 32 bits no lo usa ningún teléfono; x86_64 se queda
        // para el emulador del CI.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // El modelo se lee entero a memoria; comprimido obligaría a inflarlo en
    // cada arranque.
    androidResources { noCompress += "onnx" }
}

dependencies {
    implementation(project(":core"))
    // Alinea toda la familia kotlin-stdlib: AndroidX arrastra versiones viejas
    // de -jdk7/-jdk8 y sin esto `checkDebugDuplicateClasses` aborta. Es el
    // mismo arreglo que necesitó la app web.
    implementation(platform("org.jetbrains.kotlin:kotlin-bom:2.0.21"))

    implementation("androidx.activity:activity-ktx:1.9.2")
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // ONNX Runtime nativo: el MISMO yolox_nano.onnx que la app web, con hilos
    // de verdad. En el WebView la inferencia va en uno solo porque Android no
    // concede el aislamiento entre orígenes (medido); aquí esa pared no existe.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}

// El modelo y la imagen de autoprueba se copian del repositorio al compilar,
// como hace la app web: una sola fuente de verdad, nada duplicado a mano.
val modelFile = rootProject.file("../yolox_nano.onnx")
val probeFile = rootProject.file("../autoprueba.jpg")
val syncAssets by tasks.registering(Copy::class) {
    from(modelFile)
    from(probeFile)
    into(layout.buildDirectory.dir("generated/argos-assets"))
    doFirst {
        if (!modelFile.exists())
            throw GradleException("Falta yolox_nano.onnx en la raíz del repositorio.")
        if (!probeFile.exists())
            throw GradleException("Falta autoprueba.jpg en la raíz del repositorio.")
    }
}
android.sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/argos-assets"))
tasks.named("preBuild") { dependsOn(syncAssets) }
