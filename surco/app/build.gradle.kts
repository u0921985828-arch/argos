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
        versionCode = 21
        versionName = "2.1"
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
}
