plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.eddie.surco"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eddie.surco"
        minSdk = 26
        targetSdk = 35
        versionCode = 29
        versionName = "2.8.1"
        // Prueba en el emulador que navega la biblioteca como lo hace Android Auto (androidTest/).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Firmado con la clave de depuración para poder instalarlo directamente
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Ningún aviso de Kotlin pasa: un aviso de hoy es el error de mañana.
    kotlinOptions { jvmTarget = "17"; allWarningsAsErrors = true }
    // La salida de las pruebas en el log del CI: así se ve si la de LRCLIB habló con el servicio o se saltó.
    testOptions { unitTests.all { it.testLogging.showStandardStreams = true } }
    lint {
        // El informe se lee en CI (.github/scripts/lint-surco.py), que falla con cualquier error.
        abortOnError = false
        xmlReport = true
        checkDependencies = false
        // Versiones fijadas a propósito: SurcoAudioSink y SurcoExtractorsFactory dependen del
        // comportamiento medido de Media3 1.5.1, y targetSdk 36 cambia la pantalla completa forzada.
        // Subirlas es un cambio con su propia prueba, no un aviso que apagar. Quedan en el informe.
        informational += setOf("GradleDependency", "OldTargetApi")
    }
    buildFeatures { compose = true }
    // Idioma por app (Android 13+: Ajustes del sistema → Apps → Surco → Idioma, y el selector de
    // Ajustes → Interfaz): la lista de idiomas sale de las carpetas values-*; values/ es inglés.
    androidResources { generateLocaleConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    // Instala los perfiles de arranque (los de Compose y Media3 y baseline-prof.txt) también fuera de Play.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // «Enviar a»: Chromecast (con el receptor por defecto; sin servicios de Google queda solo DLNA).
    implementation("androidx.media3:media3-cast:1.5.1")
    implementation("androidx.mediarouter:mediarouter:1.7.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    // Lectura profunda de etiquetas (ID3, Vorbis, MP4, FLAC, APE, WMA…)
    implementation("net.jthink:jaudiotagger:3.0.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    // Gestos de dos dedos de verdad (pellizcar la cuadrícula de álbumes) en GridZoomTest.
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}

// Para comprobar el Kotlin fuera de Android Studio (el contenedor de desarrollo no llega a Google
// Maven): deja en build/classpath los .jar con los que se compila la app y el plugin de Compose.
// El CI los publica en la rama `classpath-surco` cuando cambian las dependencias.
tasks.register("exportClasspath") {
    val view = configurations.getByName("debugCompileClasspath").incoming.artifactView {
        attributes { attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar") }
    }
    // Las pruebas del emulador (androidTest), aparte: solo se usan al compilarlas.
    val tests = configurations.getByName("debugAndroidTestCompileClasspath").incoming.artifactView {
        attributes { attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar") }
    }
    val plugins = configurations.getByName("kotlinCompilerPluginClasspathDebug")
    doLast {
        val out = layout.buildDirectory.dir("classpath").get().asFile.apply { deleteRecursively(); mkdirs() }
        // Los de las .aar se llaman todos classes.jar: el nombre de la dependencia delante.
        view.artifacts.forEach { a ->
            val name = a.id.componentIdentifier.displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            a.file.copyTo(File(out, "$name-${a.file.name}"), overwrite = true)
        }
        plugins.files.forEach { it.copyTo(File(out, "plugin-${it.name}"), overwrite = true) }
        val have = out.list()!!.toSet()
        tests.artifacts.forEach { a ->
            val name = a.id.componentIdentifier.displayName.replace(Regex("[^A-Za-z0-9._-]"), "_") + "-" + a.file.name
            if (name !in have) a.file.copyTo(File(out, "test-$name"), overwrite = true)
        }
    }
}
