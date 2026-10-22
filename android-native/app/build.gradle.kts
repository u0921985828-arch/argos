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
    }
    // Lo que se entrega es una compilación de RELEASE: una de depuración deja
    // que cualquiera con un cable lea la memoria con `run-as` y enganche un
    // depurador. La firma de tienda sale del entorno (ARGOS_KEYSTORE y
    // compañía); sin ella se firma con la clave de depuración de la máquina
    // que compila. Ninguna de las dos es depurable.
    signingConfigs {
        val ks = System.getenv("ARGOS_KEYSTORE")
        if (!ks.isNullOrBlank() && file(ks).exists()) create("tienda") {
            storeFile = file(ks)
            storePassword = System.getenv("ARGOS_KEYSTORE_PASS")
            keyAlias = System.getenv("ARGOS_KEY_ALIAS")
            keyPassword = System.getenv("ARGOS_KEY_PASS")
        }
    }
    // Un APK por arquitectura: cada uno lleva solo las bibliotecas nativas de
    // ONNX Runtime de su procesador, no las tres (x86 de 32 bits no lo usa
    // ningún teléfono y ni se genera). El de arm64 es el de casi
    // cualquier teléfono actual; el de x86_64 es para el emulador del CI.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
    buildTypes {
        release {
            // Sin ofuscar: ONNX Runtime llega a sus clases por JNI y R8 sin
            // reglas medidas las quitaría. Se activa cuando haya reglas probadas.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("tienda") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // La memoria usa java.time (fechas, retención de 30 días), que en
        // Android solo existe desde la API 26. Con minSdk 24 hace falta el
        // desazucarado o la app se cae al abrir en un Android 7.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    // El modelo se lee entero a memoria; comprimido obligaría a inflarlo en
    // cada arranque.
    androidResources { noCompress += "onnx" }
}

dependencies {
    implementation(project(":core"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
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
// La voz Piper es opcional: si el CI la descargó a la raíz, va dentro; si no,
// el chat habla con la voz del sistema. Su ficha (licencia) viaja con ella.
val vozFiles = listOf("voz.onnx", "voz.onnx.json", "voz-ficha.txt").map { rootProject.file("../$it") }
val syncAssets by tasks.registering(Copy::class) {
    from(modelFile)
    from(probeFile)
    from(vozFiles.filter { it.exists() })
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
