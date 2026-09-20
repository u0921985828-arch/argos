plugins { id("com.android.application") }

android {
    namespace = "eus.argos.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "eus.argos.app"
        minSdk = 24            // WebViewAssetLoader necesita API 21+; 24 evita
        targetSdk = 34         // el zoo de WebViews antiguos sin getUserMedia
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                          "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    // WebViewAssetLoader: sirve los assets bajo https://, que es lo que
    // convierte la página en contexto seguro y desbloquea la cámara.
    implementation("androidx.webkit:webkit:1.11.0")
}

// --------------------------------------------------------------------------
// Una sola fuente de verdad: el cliente web vive en argos/api/static y se copia
// aquí al compilar. Duplicarlo garantizaría que las dos copias divergen.
// --------------------------------------------------------------------------
val webRoot = rootProject.file("../argos/api/static")

// El fichero ÚNICO, no los módulos sueltos.
//
// Esta tarea copiaba solo `index.html` y `engine.js`, de cuando la aplicación
// tenía dos piezas. Hoy son once, y el APK habría arrancado mostrando la cámara
// sin detector, sin cerebro y sin memoria --- exactamente el fallo silencioso
// que la comprobación de abajo pretendía evitar, sobrevivido al crecimiento del
// proyecto.
//
// Se empaqueta el bundle `argos.html`, que ya lleva los once módulos inlinados
// y es lo mismo que se entrega para escritorio. Una sola cosa que mantener.
val bundle = rootProject.file("../argos.html")
val modelFile = rootProject.file("../yolox_nano.onnx")

val syncWebApp by tasks.registering(Copy::class) {
    from(bundle) { rename { "index.html" } }
    // El modelo viaja dentro del APK: sin él hay que buscarlo en una release de
    // GitHub desde el móvil, que es justo donde peor se hace.
    if (modelFile.exists()) from(modelFile)
    into(layout.projectDirectory.dir("src/main/assets"))
    doFirst {
        if (!bundle.exists()) {
            throw GradleException(
                "Falta argos.html en la raíz del repo. Genéralo con:\n" +
                "    python3 scripts/bundle.py --out argos.html")
        }
    }
    doLast {
        val out = File(layout.projectDirectory.dir("src/main/assets").asFile,
                       "index.html")
        // Un bundle recortado arranca y no detecta nada. Comprobar el tamaño es
        // burdo pero coge el caso real: haberlo copiado a medias.
        if (out.length() < 200_000)
            throw GradleException("argos.html parece incompleto (${out.length()} bytes)")
    }
}
tasks.named("preBuild") { dependsOn(syncWebApp) }
