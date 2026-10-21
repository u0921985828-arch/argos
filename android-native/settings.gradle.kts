pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "ARGOS-nativo"
// `core` es Kotlin puro, sin Android: es el que se prueba en la JVM contra el
// JS. La app solo lo envuelve con cámara, dibujo y ONNX Runtime.
include(":core", ":app")
